package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程可视化编辑的公共工具：
 *   1. 常用节次时间模板 + 时间解析/归一化
 *   2. 课名候选（自动补全候选集 + 最近使用记录）
 *   3. 课表 ↔ 编辑器 JSON 换算（day 1-7 人类友好格式，与 JsonEditorActivity 互通）
 *
 * JSON 换算从 ScheduleListActivity 抽过来集中维护，避免两处 day 换算逻辑漂移。
 */
public final class CourseEditUtil {

    private static final String PREF = "ev_course_edit";
    private static final String KEY_RECENT = "recent_names";

    /** 星期短标签（"周一"…"周日"）。CourseCache.WEEK 是全称"星期一"，这里编辑场景用短式 */
    public static final String[] DAY_SHORT = {"一", "二", "三", "四", "五", "六", "日"};

    private CourseEditUtil() {
    }

    // ======================= 时间模板 =======================

    /** 常用节次模板：{展示文案, 生成的时间字符串}。仅是输入辅助，存进去的还是普通字符串 */
    public static final String[][] TEMPLATES = {
            {"早自习", "07:00 - 08:00"},
            {"第1节", "08:00 - 08:40"},
            {"第2节", "08:55 - 09:35"},
            {"第3节", "10:15 - 10:55"},
            {"第4节", "11:10 - 11:50"},
            {"第5节", "13:10 - 13:50"},
            {"第6节", "14:05 - 14:55"},
            {"第7节", "15:20 - 16:00"},
            {"第8节", "16:15 - 16:55"},
            {"第9节", "17:10 - 18:00"}
    };

    /** 时间字符串匹配到第几个模板；不匹配任何模板（如手环同步来的 19:30）返回 -1 */
    public static int matchTemplate(String time) {
        return matchTemplate(null, time);
    }

    /**
     * 动态时段表：内置 5 档 + 本机课表里出现过的自定义时段（按出现频次取前 4 个）。
     *
     * <p>为什么不是纯常量表：用户自定义过一次 19:30-21:05，下次编辑就再也点不到了——
     * 只能再手打一遍。把用过的时段收进候选，等于模板随用户习惯长出来，代码里那 5 档只是起步。
     *
     * @param c 传 null 时只返回内置档（后台/无 Context 场景）
     * @return 每项为 {展示文案, 时间字符串}；自定义项的展示文案为空串（chip 只显示时间）
     */
    public static List<String[]> templates(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String[] t : TEMPLATES) {
            out.add(t);
        }
        if (c == null) {
            return out;
        }
        final Map<String, Integer> freq = new HashMap<>();
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(c)) {
                for (CourseCache.Course co : s.courses) {
                    String norm = canon(co == null ? null : co.time);
                    if (norm == null || isBuiltin(norm)) {
                        continue;
                    }
                    Integer f = freq.get(norm);
                    freq.put(norm, f == null ? 1 : f + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        if (freq.isEmpty()) {
            return out;
        }
        List<String> times = new ArrayList<>(freq.keySet());
        java.util.Collections.sort(times, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = freq.get(b) - freq.get(a);
                return d != 0 ? d : a.compareTo(b);
            }
        });
        for (int i = 0; i < times.size() && i < 4; i++) {
            out.add(new String[]{"", times.get(i)});
        }
        return out;
    }

    /** 在动态时段表里找下标；-1 = 需要自定义 */
    public static int matchTemplate(Context c, String time) {
        String norm = canon(time);
        if (norm == null) {
            return -1;
        }
        List<String[]> all = templates(c);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i)[1].equals(norm)) {
                return i;
            }
        }
        // 宽松一点：只比起止分钟（兼容 "08:00-09:35" 这类不完全规范写法）
        int[] m = CourseCache.minutes(time);
        if (m != null) {
            for (int i = 0; i < all.size(); i++) {
                int[] t = CourseCache.minutes(all.get(i)[1]);
                if (t != null && t[0] == m[0] && t[1] == m[1]) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 归一化为 "HH:mm - HH:mm"；无法解析返回 null */
    public static String canon(String time) {
        int[] m = CourseCache.minutes(time);
        if (m == null) {
            return null;
        }
        return CourseCache.hm(m[0]) + " - " + CourseCache.hm(m[1]);
    }

    /** 是否是内置那 5 档之一（内置的不重复进「常用」区） */
    private static boolean isBuiltin(String norm) {
        for (String[] t : TEMPLATES) {
            String n = canon(t[1]);
            if (n != null && n.equals(norm)) {
                return true;
            }
        }
        return false;
    }

    /** 两个 "HH:mm" → 归一化时间字符串 "08:00 - 09:35"；起止非法返回 null */
    public static String normalizeRange(String start, String end) {
        int s = CourseCache.parseHm(start);
        int e = CourseCache.parseHm(end);
        if (s < 0 || e < 0 || e <= s) {
            return null;
        }
        return CourseCache.hm(s) + " - " + CourseCache.hm(e);
    }

    /**
     * 编辑网格的某一行（从上往下 0 起）→ 对应节次时间段字符串：
     * 行号与常用节次模板一一对应（第 1 行=第1-2节、第 2 行=第3-4节……），
     * 用于"点在哪个格/拖到哪个格就填哪个时段"，减少用户再改。超出模板范围返回 null，由调用方兜底。
     */
    public static String timeForRow(int row) {
        if (row >= 0 && row < TEMPLATES.length) {
            return TEMPLATES[row][1];
        }
        return null;
    }

    /**
     * 某天空格子点添加时的预填时段（方案 3.3）：
     * 该列已有一节课 → 取它匹配模板的"下一节"；没课或匹配不到 → 第 1-2 节。
     */
    public static String nextSlotForDay(List<CourseCache.Course> all, int day) {
        int lastIdx = -1;
        int lastStart = -1;
        if (all != null) {
            for (CourseCache.Course c : all) {
                if (c == null || c.day != day || c.time == null || c.time.length() == 0) {
                    continue;
                }
                int[] m = CourseCache.minutes(c.time);
                if (m != null && m[0] > lastStart) {
                    lastStart = m[0];
                    lastIdx = matchTemplate(c.time);
                }
            }
        }
        int next = (lastIdx >= 0 && lastIdx < TEMPLATES.length - 1) ? lastIdx + 1 : 0;
        return TEMPLATES[next][1];
    }

    // ======================= 课名候选 =======================

    /**
     * 自动补全候选：本机全部课表出现过的课名，按使用频次降序。
     * 隐式字典，零维护（方案 5.1：不做独立课名管理页）。
     */
    public static List<String> allCourseNames(Context c) {
        final Map<String, Integer> freq = new HashMap<>();
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(c)) {
                for (CourseCache.Course co : s.courses) {
                    if (co != null && co.name != null && co.name.trim().length() > 0) {
                        String n = co.name.trim();
                        Integer f = freq.get(n);
                        freq.put(n, f == null ? 1 : f + 1);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<String> out = new ArrayList<>(freq.keySet());
        java.util.Collections.sort(out, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = freq.get(b) - freq.get(a);
                return d != 0 ? d : a.compareTo(b);
            }
        });
        return out;
    }

    /** 记录一次课名使用（确定/保存课程时调用），供「最近使用」chips */
    public static void recordNameUse(Context c, String name) {
        if (name == null) {
            return;
        }
        String n = name.trim();
        if (n.length() == 0) {
            return;
        }
        try {
            List<String> cur = new ArrayList<>();
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            try {
                JSONArray arr = new JSONArray(sp.getString(KEY_RECENT, "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i);
                    if (s.length() > 0 && !s.equals(n)) {
                        cur.add(s);
                    }
                }
            } catch (Throwable ignored) {
            }
            cur.add(0, n);
            while (cur.size() > 20) {
                cur.remove(cur.size() - 1);
            }
            JSONArray out = new JSONArray();
            for (String s : cur) {
                out.put(s);
            }
            sp.edit().putString(KEY_RECENT, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 最近使用的课名（最多 max 个，按最近优先） */
    public static List<String> recentNames(Context c, int max) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_RECENT, "[]"));
            for (int i = 0; i < arr.length() && out.size() < max; i++) {
                String s = arr.optString(i);
                if (s.length() > 0) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ======================= JSON 换算（day 1-7 人类友好格式） =======================

    /** 课表 → 编辑器 JSON（day 用 1-7） */
    public static String scheduleToEditorJson(String name, List<CourseCache.Course> courses) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name == null ? "" : name);
            JSONArray arr = new JSONArray();
            if (courses != null) {
                for (CourseCache.Course c : courses) {
                    JSONObject co = new JSONObject();
                    co.put("name", c.name);
                    co.put("day", c.day + 1);
                    co.put("time", c.time);
                    co.put("teacher", c.teacher);
                    co.put("location", c.location);
                    arr.put(co);
                }
            }
            o.put("courses", arr);
            return o.toString(2);
        } catch (Throwable t) {
            return "{\"name\":\"\",\"courses\":[]}";
        }
    }

    /** 课表对象便捷重载 */
    public static String scheduleToEditorJson(ScheduleStore.Schedule s) {
        return scheduleToEditorJson(s.name, s.courses);
    }

    /** 课程列表 → JSON 数组文本（草稿持久化用，day 保持 0-6 原值） */
    public static String coursesToJson(List<CourseCache.Course> courses) {
        JSONArray arr = new JSONArray();
        if (courses != null) {
            for (CourseCache.Course c : courses) {
                arr.put(CourseCache.toJson(c));
            }
        }
        return arr.toString();
    }

    /** JSON 数组文本 → 课程列表（草稿恢复用）；解析失败返回空列表 */
    public static List<CourseCache.Course> coursesFromJson(String json) {
        List<CourseCache.Course> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(CourseCache.fromJson(arr.optJSONObject(i)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static final class ParseResult {
        public String name;
        public List<CourseCache.Course> courses = new ArrayList<>();
    }

    /** 编辑器 JSON → {name, courses}（day 1-7 → 0-6）；结构非法抛异常 */
    public static ParseResult parseEditorJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        ParseResult r = new ParseResult();
        r.name = o.optString("name", "未命名课表");
        JSONArray arr = o.optJSONArray("courses");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject co = arr.optJSONObject(i);
                if (co == null) {
                    continue;
                }
                CourseCache.Course c = new CourseCache.Course();
                c.name = co.optString("name");
                c.time = co.optString("time");
                c.teacher = co.optString("teacher");
                c.location = co.optString("location");
                c.day = CourseCache.dayIndex(co.opt("day"));
                r.courses.add(c);
            }
        }
        return r;
    }

    /** 默认新建模板（JSON 高级模式新建用） */
    public static String defaultTemplate() {
        return "{\n"
                + "  \"name\": \"新课表\",\n"
                + "  \"courses\": [\n"
                + "    {\"name\":\"课程名\",\"day\":1,\"time\":\"08:00 - 09:35\",\"teacher\":\"\",\"location\":\"教学楼\"},\n"
                + "    {\"name\":\"课程名\",\"day\":2,\"time\":\"10:00 - 11:35\",\"teacher\":\"\",\"location\":\"教学楼\"}\n"
                + "  ]\n"
                + "}";
    }
}