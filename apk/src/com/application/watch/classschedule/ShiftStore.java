package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 调课单：临时换课 / 跨天挪课的本地账本。
 *
 * <p>核心口径（这一条决定整个设计）：<b>周模板永远不动，只改「今天实际看到的课」</b>。
 * 用户在周三把下午的课换到上午，周四打开看到的还是原模板——因为那是「临时」。
 * 要永久改，就走课表编辑（改周模板本体），不经过这里。
 *
 * <p>一条调课单只描述<b>一门课的单向移动</b>：
 * <pre>
 *   courseId  从 fromDate 的 fromTime   →   toDate 的 toTime
 * </pre>
 * 于是三种场景都退化成同一条规则的组合：
 * <ul>
 *   <li><b>同天挪到空档</b>：fromDate = toDate = 今天，一条</li>
 *   <li><b>同天互换</b>：生成两条（A→B、B→A），共用一个 groupId，撤销时按 group 一起撤</li>
 *   <li><b>跨天调课</b>：fromDate = 今天，toDate = 明天，一条。今天少一节、明天多一节，各自按自己的日期判断</li>
 * </ul>
 *
 * <p>折算入口是 {@link #apply}，被 {@link CourseCache#load} 末尾调用一次，
 * 所以首页、周视图、两个桌面插件、上课提醒全部自动跟着变——不需要各处改。
 *
 * <p>已知边界（第一版有意不处理）：调休日与调课叠加时，调课按调休重定向后的星期判断，
 * 假期日（不排课）直接不生效；跨天挪到「已经有课」的位置不在这里拦，由换课弹层负责提示。
 */
public final class ShiftStore {

    private static final String PREF = "ev_shifts";
    private static final String KEY_ITEMS = "items";

    /** 过期保留天数：过去 7 天之外的调课单自动清掉，避免无限累积 */
    private static final int KEEP_DAYS = 7;

    private ShiftStore() {
    }

    /** 一条单向调课 */
    public static final class Shift {
        /** 本机唯一 id */
        public String id = "";
        /** 同一次操作生成的多条（互换）共用；撤销按它整组撤 */
        public String groupId = "";
        /** 被移动的课程（周模板里的 c_xxx） */
        public String courseId = "";
        /** 原来在哪天（yyyy-MM-dd）与哪个时段 */
        public String fromDate = "";
        public String fromTime = "";
        /** 挪到哪天（yyyy-MM-dd）与哪个时段；跨天时与 fromDate 不同 */
        public String toDate = "";
        public String toTime = "";
        /** 备注：「与王老师换课」「教研」 */
        public String note = "";
        public long createdAt = 0;

        public boolean isCrossDay() {
            return fromDate != null && toDate != null && !fromDate.equals(toDate);
        }
    }

    // ======================= 读 =======================

    /** 全部调课单（已清理过期项），按创建时间倒序 */
    public static synchronized List<Shift> list(Context c) {
        List<Shift> out = read(c);
        // 过期清理：fromDate 与 toDate 都早于 KEEP_DAYS 天前的丢掉
        String floor = ymd(offsetDays(-KEEP_DAYS));
        List<Shift> keep = new ArrayList<>();
        boolean changed = false;
        for (Shift s : out) {
            String latest = (s.toDate != null && s.toDate.compareTo(s.fromDate) > 0) ? s.toDate : s.fromDate;
            if (latest != null && latest.compareTo(floor) < 0) {
                changed = true;
                continue;
            }
            keep.add(s);
        }
        if (changed) {
            write(c, keep);
        }
        java.util.Collections.sort(keep, new java.util.Comparator<Shift>() {
            @Override public int compare(Shift a, Shift b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return keep;
    }

    /** 今天（含今天作为起点或终点）的调课单——首页提示条用 */
    public static List<Shift> todayShifts(Context c) {
        String today = ymdNow();
        List<Shift> out = new ArrayList<>();
        for (Shift s : list(c)) {
            if (today.equals(s.fromDate) || today.equals(s.toDate)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 今天有没有调过课 */
    public static boolean hasToday(Context c) {
        return !todayShifts(c).isEmpty();
    }

    // ======================= 写 =======================

    /**
     * 挪课：把一门课从它的原时段挪到指定日期 / 时段。
     *
     * @param toDate 目标日期 yyyy-MM-dd（同一天传今天即可；跨天传明天）
     * @param toTime 目标时段，空则沿用原时段
     * @return 生成的调课单 id
     */
    public static synchronized String moveTo(Context c, CourseCache.Course src,
                                             String toDate, String toTime, String note) {
        if (src == null || src.id == null || src.id.length() == 0) {
            return "";
        }
        String gid = "g_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        Shift s = new Shift();
        s.id = gid + "_1";
        s.groupId = gid;
        s.courseId = src.id;
        s.fromDate = ymdNow();
        s.fromTime = src.time == null ? "" : src.time;
        s.toDate = (toDate == null || toDate.length() == 0) ? ymdNow() : toDate;
        s.toTime = (toTime == null || toTime.length() == 0) ? s.fromTime : toTime;
        s.note = note == null ? "" : note;
        s.createdAt = System.currentTimeMillis();
        List<Shift> all = read(c);
        all.add(s);
        write(c, all);
        return s.id;
    }

    /**
     * 互换：两门课对调时段（同一天内）。
     * 生成两条互为反向的调课单，共用一个 groupId。
     *
     * @return groupId（撤销用）；失败返回空串
     */
    public static synchronized String swapToday(Context c, CourseCache.Course a,
                                                CourseCache.Course b, String note) {
        if (a == null || b == null
                || a.id == null || a.id.length() == 0
                || b.id == null || b.id.length() == 0
                || a.id.equals(b.id)) {
            return "";
        }
        String gid = "g_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        String today = ymdNow();
        long now = System.currentTimeMillis();
        String n = note == null ? "" : note;

        Shift s1 = new Shift();
        s1.id = gid + "_1";
        s1.groupId = gid;
        s1.courseId = a.id;
        s1.fromDate = today;
        s1.fromTime = a.time == null ? "" : a.time;
        s1.toDate = today;
        s1.toTime = b.time == null ? "" : b.time;
        s1.note = n;
        s1.createdAt = now;

        Shift s2 = new Shift();
        s2.id = gid + "_2";
        s2.groupId = gid;
        s2.courseId = b.id;
        s2.fromDate = today;
        s2.fromTime = b.time == null ? "" : b.time;
        s2.toDate = today;
        s2.toTime = a.time == null ? "" : a.time;
        s2.note = n;
        s2.createdAt = now;

        List<Shift> all = read(c);
        all.add(s1);
        all.add(s2);
        write(c, all);
        return gid;
    }

    /** 撤销一条调课 */
    public static synchronized void remove(Context c, String id) {
        if (id == null || id.length() == 0) {
            return;
        }
        List<Shift> all = read(c);
        List<Shift> keep = new ArrayList<>();
        for (Shift s : all) {
            if (!id.equals(s.id)) {
                keep.add(s);
            }
        }
        write(c, keep);
    }

    /** 撤销一整组（互换的两条一起撤） */
    public static synchronized void undoGroup(Context c, String groupId) {
        if (groupId == null || groupId.length() == 0) {
            return;
        }
        List<Shift> all = read(c);
        List<Shift> keep = new ArrayList<>();
        for (Shift s : all) {
            if (!groupId.equals(s.groupId)) {
                keep.add(s);
            }
        }
        write(c, keep);
    }

    /** 清空全部调课单（设置页救援用） */
    public static synchronized void clear(Context c) {
        write(c, new ArrayList<Shift>());
    }

    // ======================= 折算（接入渲染的唯一入口） =======================

    /**
     * 把调课单折算进课程列表：返回「今天实际该上的课」。
     *
     * <p>两步，顺序不能反：<b>先移除再加入</b>。同日挪课时 fromDate 与 toDate 都等于今天，
     * 若先加后删，刚加进去的副本会被自己的移除规则删掉。
     *
     * @param base 周模板全集（{@link CourseCache#load} 的原始结果）
     * @return 折算后的新列表；无调课或异常时原样返回 base
     */
    public static List<CourseCache.Course> apply(Context c, List<CourseCache.Course> base) {
        if (base == null || base.isEmpty()) {
            return base;
        }
        List<Shift> all;
        try {
            all = list(c);
        } catch (Throwable t) {
            return base;
        }
        if (all.isEmpty()) {
            return base;
        }
        int today;
        try {
            // 与渲染口径保持一致：调休日按重定向后的星期判断；假期日不排课，调课不生效
            today = Holiday.resolveDay(c, Calendar.getInstance());
            if (today == Holiday.HOLIDAY) {
                return base;
            }
            if (today < 0 || today > 6) {
                today = CourseCache.todayIndex();
            }
        } catch (Throwable t) {
            today = CourseCache.todayIndex();
        }
        if (today < 0 || today > 6) {
            return base;
        }
        String ymd = ymdNow();

        List<CourseCache.Course> out = new ArrayList<>();
        // ① 先剔除「今天被挪走」的课
        for (CourseCache.Course co : base) {
            if (co == null) {
                continue;
            }
            boolean moved = false;
            for (Shift s : all) {
                if (ymd.equals(s.fromDate) && co.id != null && co.id.equals(s.courseId)) {
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                out.add(co);
            }
        }
        // ② 再加入「挪到今天」的课
        for (Shift s : all) {
            if (!ymd.equals(s.toDate)) {
                continue;
            }
            CourseCache.Course src = findById(base, s.courseId);
            if (src == null) {
                continue;   // 原课已被删除 → 调课单自然失效
            }
            CourseCache.Course copy = copyOf(src);
            copy.day = today;
            if (s.toTime != null && s.toTime.length() > 0) {
                copy.time = s.toTime;
            }
            out.add(copy);
        }
        return out;
    }

    /** 今天有没有课被挪走（用于「今日课表已临时调整」标记） */
    public static boolean adjustedToday(Context c) {
        return hasToday(c);
    }

    // ======================= 内部 =======================

    private static CourseCache.Course findById(List<CourseCache.Course> list, String id) {
        if (list == null || id == null) {
            return null;
        }
        for (CourseCache.Course co : list) {
            if (co != null && id.equals(co.id)) {
                return co;
            }
        }
        return null;
    }

    /** 浅拷贝（Course 字段都是值类型 + String，逐字段抄一遍即可） */
    private static CourseCache.Course copyOf(CourseCache.Course s) {
        CourseCache.Course co = new CourseCache.Course();
        co.id = s.id;
        co.name = s.name;
        co.time = s.time;
        co.teacher = s.teacher;
        co.location = s.location;
        co.day = s.day;
        co.updatedAt = s.updatedAt;
        co.deleted = s.deleted;
        return co;
    }

    private static List<Shift> read(Context c) {
        List<Shift> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Shift s = new Shift();
                s.id = o.optString("id", "");
                s.groupId = o.optString("groupId", "");
                s.courseId = o.optString("courseId", "");
                s.fromDate = o.optString("fromDate", "");
                s.fromTime = o.optString("fromTime", "");
                s.toDate = o.optString("toDate", "");
                s.toTime = o.optString("toTime", "");
                s.note = o.optString("note", "");
                s.createdAt = o.optLong("createdAt", 0);
                if (s.courseId.length() > 0) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Shift> items) {
        try {
            JSONArray arr = new JSONArray();
            for (Shift s : items) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("groupId", s.groupId);
                o.put("courseId", s.courseId);
                o.put("fromDate", s.fromDate);
                o.put("fromTime", s.fromTime);
                o.put("toDate", s.toDate);
                o.put("toTime", s.toTime);
                o.put("note", s.note);
                o.put("createdAt", s.createdAt);
                arr.put(o);
            }
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 日期工具 =======================

    /** 今天（yyyy-MM-dd） */
    public static String ymdNow() {
        return ymd(Calendar.getInstance());
    }

    /** 相对今天偏移 n 天后的日期（yyyy-MM-dd）；负数为过去 */
    public static String ymdOffset(int days) {
        return ymd(offsetDays(days));
    }

    private static String ymd(Calendar cal) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        } catch (Throwable t) {
            return "";
        }
    }

    private static Calendar offsetDays(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal;
    }
}
