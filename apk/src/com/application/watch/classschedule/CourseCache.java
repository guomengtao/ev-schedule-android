package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 手机本地课表【只读缓存】（阶段 0）。
 *
 * ★ 定位：手环仍是唯一真源，这里只在本机存一份副本，用于
 *   ① 首屏展示（兑现「Ev课程表」这个名字）
 *   ② 后续桌面插件 / 上课提醒（提醒必须本地有数据才可靠）
 *   **手机上不可编辑** —— 因此现阶段不存在"双写"，也不需要任何冲突解决。
 *
 * ★ 存的是摊平后的课程列表（一门课一个对象），而不是手环原始的"按天分组"格式：
 *   展示、排序、将来做差异摘要都直接用它，不必每次再转一遍。
 *
 * ⚠️ 所有方法都必须吞掉异常：缓存是锦上添花，绝不能因为读缓存把 App 搞崩。
 */
public final class CourseCache {

    private static final String PREF = "ev_course_cache";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_AT = "saved_at";
    private static final String KEY_NAME = "schedule_name";

    public static final String[] WEEK = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final String WEEK_TAIL = "一二三四五六日";

    private CourseCache() {
    }

    public static final class Course {
        public String name = "";
        public String time = "";
        public String teacher = "";
        public String location = "";
        /** 0 = 周一 … 6 = 周日；-1 = 未知 */
        public int day = -1;
        // ===== 字段级三方合并的同步元数据 =====
        /** 全局唯一键（生成后终身不变），三方合并按它对齐 */
        public String id = "";
        /** 最后修改时间（本地时钟，仅展示/调试，不用于裁决冲突） */
        public long updatedAt = 0;
        /** 软删除标记：删课不物理删，打标后由合并逻辑处理 */
        public boolean deleted = false;

        /** 生成一个全局唯一课程 id */
        public static String newId() {
            return "c_" + System.currentTimeMillis() + "_"
                    + Integer.toHexString((int) (Math.random() * 0xFFFF));
        }

        /** 无 id 时补齐（读旧数据 / 新建课程时调用），幂等 */
        public void ensureId() {
            if (id == null || id.length() == 0) {
                id = newId();
            }
            if (updatedAt == 0) {
                updatedAt = System.currentTimeMillis();
            }
        }

        public String dayLabel() {
            return (day >= 0 && day < WEEK.length) ? WEEK[day] : "";
        }

        /** 副行：教室 / 老师，缺哪个就不显示哪个 */
        public String sub() {
            StringBuilder sb = new StringBuilder();
            if (location.length() > 0) {
                sb.append(location);
            }
            if (teacher.length() > 0) {
                if (sb.length() > 0) {
                    sb.append("　·　");
                }
                sb.append(teacher);
            }
            return sb.toString();
        }
    }

    // ======================= 写 =======================

    /**
     * 把手环 export 回来的 data.schedule（按天分组）转成本地扁平列表保存。
     *
     * @param schedule EV export 的 data.schedule，可能为空/null（容错：此时不覆盖已有缓存）
     * @return 实际存下的课程数
     */
    public static int save(Context c, JSONArray schedule, String scheduleName) {
        List<Course> flat = flatten(schedule);
        return saveFlat(c, flat, scheduleName);
    }

    /**
     * 把扁平课程列表写入缓存（多课表切换 active 时的直写通道）。
     * 空数据不覆盖旧缓存：宁可显示上次的数据，也不要把课表清成空白。
     *
     * @return 实际存下的课程数
     */
    public static int saveFlat(Context c, List<Course> flat, String scheduleName) {
        if (c == null || flat == null || flat.isEmpty()) {
            return 0;
        }
        try {
            JSONArray arr = new JSONArray();
            for (Course co : flat) {
                if (co != null) {
                    arr.put(toJson(co));
                }
            }
            if (arr.length() == 0) {
                return 0;
            }
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_ITEMS, arr.toString())
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .putString(KEY_NAME, scheduleName == null ? "" : scheduleName)
                    .apply();
            // 桌面上的插件跟着刷新（未添加插件时这两句是 no-op）
            TodayWidgetProvider.refreshAll(c);
            NextWidgetProvider.refreshAll(c);
            WeekWidgetProvider.refreshAll(c);
            // 课表变了，上课提醒也要重排
            Reminders.reschedule(c);
            return arr.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 手环 export 的 data.schedule（按天分组）→ 扁平课程列表。
     * 容错：null / 空 / 格式异常都返回空列表，不抛异常。
     */
    public static List<Course> flatten(JSONArray schedule) {
        List<Course> out = new ArrayList<>();
        if (schedule == null) {
            return out;
        }
        try {
            for (int i = 0; i < schedule.length(); i++) {
                JSONObject day = schedule.optJSONObject(i);
                if (day == null) {
                    continue;
                }
                JSONArray classes = day.optJSONArray("classes");
                if (classes == null) {
                    continue;
                }
                for (int k = 0; k < classes.length(); k++) {
                    JSONObject src = classes.optJSONObject(k);
                    if (src == null) {
                        continue;
                    }
                    Course co = new Course();
                    co.name = src.optString("name");
                    co.time = src.optString("time");
                    co.teacher = src.optString("teacher");
                    co.location = src.optString("location");
                    co.day = dayIndex(day.opt("day"));
                    co.id = src.optString("id");
                    co.ensureId();
                    out.add(co);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void clear(Context c) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 读 =======================

    /** 本地缓存的全部课程（无缓存返回空列表，永不返回 null） */
    public static List<Course> load(Context c) {
        List<Course> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_ITEMS, "");
            if (raw.length() == 0) {
                return out;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                out.add(fromJson(o));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 缓存写入时间；0 = 没有缓存 */
    public static long savedAt(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_AT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String scheduleName(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_NAME, "");
        } catch (Throwable t) {
            return "";
        }
    }

    // ======================= 查询辅助 =======================

    /** 今天是周几 → 0=周一 … 6=周日 */
    /** 单字模式：课表/小插件里课程名只显示第一个字（语/数/英）。用户打开后记住。 */
    public static boolean shortNameMode(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean("short_name_mode", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setShortNameMode(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean("short_name_mode", on).apply();
        } catch (Throwable ignored) {
        }
        // 同步刷新两个桌面插件（未添加时 no-op）
        WeekWidgetProvider.refreshAll(c);
        TodayWidgetProvider.refreshAll(c);
    }

    /** 按单字模式取显示名（关 = 原名；开 = 第一个字） */
    public static String displayName(String name, boolean shortMode) {
        if (shortMode && name != null && name.length() > 1) {
            return name.substring(0, 1);
        }
        return name == null ? "" : name;
    }

    public static int todayIndex() {
        int cal = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); // Calendar.MONDAY=2 … SUNDAY=1
        return (cal - Calendar.MONDAY + 7) % 7;
    }

    /** 某天的课程（按上课时间升序） */
    public static List<Course> coursesOfDay(List<Course> all, int day) {
        List<Course> out = new ArrayList<>();
        if (all == null) {
            return out;
        }
        for (Course c : all) {
            if (c != null && c.day == day) {
                out.add(c);
            }
        }
        sortByTime(out);
        return out;
    }

    /** 整周视图：先按天、天内按时间升序 */
    public static void sortForWeek(List<Course> list) {
        try {
            Collections.sort(list, new Comparator<Course>() {
                @Override public int compare(Course a, Course b) {
                    int d = (a.day < 0 ? 99 : a.day) - (b.day < 0 ? 99 : b.day);
                    if (d != 0) {
                        return d;
                    }
                    return timeKey(a.time).compareTo(timeKey(b.time));
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void sortByTime(List<Course> list) {
        try {
            Collections.sort(list, new Comparator<Course>() {
                @Override public int compare(Course a, Course b) {
                    return timeKey(a.time).compareTo(timeKey(b.time));
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 "08:00 - 08:45" 这类字符串里取出起始 "08:00" 作为排序键。
     * 取不出来就原样返回（退化为字符串排序，不会崩）。
     */
    private static String timeKey(String t) {
        if (t == null || t.length() < 4) {
            return t == null ? "" : t;
        }
        int i = t.indexOf(':');
        if (i < 1) {
            return t;
        }
        int start = Math.max(0, i - 2);
        int end = Math.min(t.length(), i + 3);
        return t.substring(start, end);
    }

    /**
     * day 兼容：数字 1-7、字符 "1"、"星期一"/"周一"、"一" → 0-6；认不出来返回 -1。
     * 与 TransferActivity.dayLabel 保持同一套假设（1 = 星期一）。
     */
    static int dayIndex(Object v) {
        if (v == null) {
            return -1;
        }
        if (v instanceof Number) {
            int d = ((Number) v).intValue();
            return (d >= 1 && d <= 7) ? d - 1 : -1;
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) {
            return -1;
        }
        if (s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '7') {
            return s.charAt(0) - '1';
        }
        for (int i = 0; i < WEEK.length; i++) {
            if (WEEK[i].equals(s)) {
                return i;
            }
        }
        if (s.equals("星期天") || s.equals("周天") || s.equals("天")) {
            return 6;
        }
        for (int i = 0; i < WEEK_TAIL.length(); i++) {
            char ch = WEEK_TAIL.charAt(i);
            if (s.equals(String.valueOf(ch)) || s.equals("星期" + ch) || s.equals("周" + ch)) {
                return i;
            }
        }
        return -1;
    }

    /** "刚刚 / N 分钟前 / N 小时前 / N 天前 / M月D日" */
    public static String ago(long at) {
        if (at <= 0) {
            return "未知时间";
        }
        long diff = System.currentTimeMillis() - at;
        if (diff < 0) {
            diff = 0;
        }
        long min = diff / 60000L;
        if (min < 1) {
            return "刚刚";
        }
        if (min < 60) {
            return min + " 分钟前";
        }
        long hour = min / 60L;
        if (hour < 24) {
            return hour + " 小时前";
        }
        long day = hour / 24L;
        if (day < 30) {
            return day + " 天前";
        }
        try {
            return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(at));
        } catch (Throwable t) {
            return "很久以前";
        }
    }

    // ======================= 时间工具（供插件 / 提醒用） =======================

    /** "08:00 - 08:45" → {480, 525}（当日分钟数）；解析不出返回 null */
    public static int[] minutes(String t) {
        if (t == null || t.length() < 4) {
            return null;
        }
        try {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(t);
            int[] out = new int[]{-1, -1};
            while (m.find()) {
                int v = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                if (out[0] < 0) {
                    out[0] = v;
                } else {
                    out[1] = v;
                    break;
                }
            }
            if (out[0] < 0) {
                return null;
            }
            if (out[1] <= out[0]) {
                out[1] = out[0] + 45; // 只有一个时间点（或区间非法）时按 45 分钟一节课算
            }
            return out;
        } catch (Throwable t2) {
            return null;
        }
    }

    /** 当前时刻的"当日分钟数" */
    public static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** "08:00" 风格的时分文本 → 当日分钟数；解析不出返回 -1 */
    public static int parseHm(String s) {
        int[] m = minutes(s);
        return m == null ? -1 : m[0];
    }

    /** 当日分钟数 → "08:45" */
    public static String hm(int minutes) {
        if (minutes < 0) {
            return "";
        }
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** "08:00 - 08:45" → "08:00"（窄列 / 插件里放不下完整区间） */
    public static String shortTime(String t) {
        if (t == null) {
            return "";
        }
        int i = t.indexOf('-');
        String s = (i > 0 ? t.substring(0, i) : t).trim();
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    // ======================= JSON 转换 =======================

    public static JSONObject toJson(Course c) {
        JSONObject o = new JSONObject();
        try {
            c.ensureId();
            o.put("id", c.id);
            o.put("name", c.name);
            o.put("time", c.time);
            o.put("teacher", c.teacher);
            o.put("location", c.location);
            o.put("day", c.day);
            o.put("updatedAt", c.updatedAt);
            o.put("deleted", c.deleted);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static Course fromJson(JSONObject o) {
        Course co = new Course();
        if (o == null) {
            return co;
        }
        try {
            co.id = o.optString("id");
            co.name = o.optString("name");
            co.time = o.optString("time");
            co.teacher = o.optString("teacher");
            co.location = o.optString("location");
            co.day = o.optInt("day", -1);
            co.updatedAt = o.optLong("updatedAt", 0);
            co.deleted = o.optBoolean("deleted", false);
            co.ensureId();
        } catch (Throwable ignored) {
        }
        return co;
    }
}
