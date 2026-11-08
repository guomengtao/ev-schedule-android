package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 第9节（晚辅 / 晚自习）的「六周轮转」模板。
 *
 * <p>为什么单独一层，而不是往课表里塞 30 条课：
 * 白天 8 节是固定的周模板（每周一样），第9节是「每天都有、内容随周次轮转」。
 * 若把 6 周 × 5 天摊成 30 条课程，用户打开课表管理会看到 30 条分不清周次的记录，
 * 改一格要翻 30 条。所以这里只存一张「表格」：周次 × 星期 → 科目名，共 30 格。
 *
 * <p>它不是第二套课表，是旁挂在课表旁边的模板。渲染时由 {@link #inject} 按
 * 「本周是第几周」查出今天那格，合成一条第9节课注入当天课程，与白天 8 节合成一周。
 * 用户不需要知道有两套东西，他只知道「今天第9节是地理」。
 *
 * <p>同步口径：注入只发生在 {@link CourseCache#load}，而推手环的课表走
 * {@link ScheduleStore} 的结构化存储，不经过 load —— 因此第9节天然只在手机端显示。
 * 这是 P0 的刻意约束：手环端要认这张表得先改同步协议与快应用，且老版本快应用不认。
 */
public final class RotationStore {

    private static final String PREF = "ev_rotation";
    private static final String KEY = "template";

    /** 默认时段：晚辅一节 45 分钟 */
    public static final String DEFAULT_TIME = "17:10 - 18:00";
    public static final int DEFAULT_CYCLE = 6;
    /** 星期下标上限：0=周一 … 6=周日 */
    private static final int DAYS = 7;
    private static final String DATE_FMT = "yyyy-MM-dd";

    private RotationStore() {
    }

    /** 轮转模板。cells 是「周次 × 星期」的二维表，下标 0 对应第 1 周 / 周一。 */
    public static final class Template {
        /** 关掉时 inject 原样返回，行为与没有这个功能完全一致 */
        public boolean enabled = false;
        /** 轮转周期：你这里是 6 周一轮 */
        public int cycle = DEFAULT_CYCLE;
        /** 第9节时段 */
        public String time = DEFAULT_TIME;
        /** 手动校正：calibDate 那一周算第 calibWeek 周，其余周按差值外推 */
        public String calibDate = "";
        public int calibWeek = 1;
        /** cells.get(week-1)[day]，day 0=周一 */
        public final List<String[]> cells = new ArrayList<>();

        public Template() {
            ensureCycle();
        }

        /** 按 cycle 补齐/裁剪行数，任何读写前都先调一次 */
        public void ensureCycle() {
            if (cycle < 1) {
                cycle = 1;
            }
            while (cells.size() < cycle) {
                cells.add(new String[DAYS]);
            }
            while (cells.size() > cycle) {
                cells.remove(cells.size() - 1);
            }
        }

        /** 取某周某天的科目名；越界返回空串 */
        public String cell(int week, int day) {
            if (day < 0 || day >= DAYS) {
                return "";
            }
            int w = week - 1;
            if (w < 0 || w >= cells.size()) {
                return "";
            }
            String[] row = cells.get(w);
            if (row == null || row.length <= day || row[day] == null) {
                return "";
            }
            return row[day];
        }

        /** 写某周某天的科目名；传空串即清除 */
        public void setCell(int week, int day, String name) {
            if (day < 0 || day >= DAYS) {
                return;
            }
            int w = week - 1;
            if (w < 0) {
                return;
            }
            ensureCycle();
            if (w >= cells.size()) {
                return;
            }
            String[] row = cells.get(w);
            if (row == null) {
                row = new String[DAYS];
                cells.set(w, row);
            }
            row[day] = name == null ? "" : name.trim();
        }

        /** 30 格全空 → 视为没有配置，inject 直接跳过 */
        public boolean isEmpty() {
            for (String[] row : cells) {
                if (row == null) {
                    continue;
                }
                for (String v : row) {
                    if (!TextUtils.isEmpty(v)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** 已填格数，用于设置页显示「已配置 24/30 格」 */
        public int filled() {
            int n = 0;
            for (String[] row : cells) {
                if (row == null) {
                    continue;
                }
                for (String v : row) {
                    if (!TextUtils.isEmpty(v)) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    // ======================= 存取 =======================

    /** 官方演示数据：八年级 8.20 班晚课六周轮转（第一周..第六周 x 周一..周五） */
    private static final String[][] DEMO_CELLS = {
        {"语", "物", "体", "生", "英"},
        {"语", "历", "数", "英", "道"},
        {"语", "生", "英", "数", "地"},
        {"语", "体", "物", "地", "英"},
        {"道", "历", "体", "语", "英"},
        {"生", "物", "英", "地", "数"},
    };
    /** 演示用校正基准：2026-11-09（周一）算第 3 周，之后自动外推 */
    public static final String DEMO_CALIB_DATE = "2026-11-09";
    public static final int DEMO_CALIB_WEEK = 3;

    /** 构造演示模板：6 周 x 5 天，第9节 17:10-18:00，本周按第 3 周 */
    private static Template demo() {
        Template t = new Template();
        t.enabled = true;
        t.time = DEFAULT_TIME;
        t.calibDate = DEMO_CALIB_DATE;
        t.calibWeek = DEMO_CALIB_WEEK;
        t.ensureCycle();
        for (int w = 1; w <= t.cycle && w <= DEMO_CELLS.length; w++) {
            String[] row = t.cells.get(w - 1);
            for (int d = 0; d < DAYS && d < row.length; d++) {
                row[d] = DEMO_CELLS[w - 1][d];
            }
        }
        return t;
    }

    public static Template load(Context c) {
        Template t = new Template();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String s = sp.getString(KEY, "");
            if (TextUtils.isEmpty(s)) {
                // 首次运行：写入官方演示数据（8.20 班六周轮转），用户之后改格即覆盖
                Template d = demo();
                save(c, d);
                return d;
            }
            JSONObject o = new JSONObject(s);
            t.enabled = o.optBoolean("enabled", false);
            t.cycle = Math.max(1, Math.min(12, o.optInt("cycle", DEFAULT_CYCLE)));
            String tm = o.optString("time", "");
            t.time = TextUtils.isEmpty(tm) ? DEFAULT_TIME : tm;
            t.calibDate = o.optString("calibDate", "");
            t.calibWeek = Math.max(1, o.optInt("calibWeek", 1));
            t.cells.clear();
            JSONObject cs = o.optJSONObject("cells");
            t.ensureCycle();
            if (cs != null) {
                for (int w = 1; w <= t.cycle; w++) {
                    JSONObject wo = cs.optJSONObject(String.valueOf(w));
                    String[] row = t.cells.get(w - 1);
                    if (wo == null || row == null) {
                        continue;
                    }
                    for (int d = 0; d < DAYS; d++) {
                        String v = wo.optString(String.valueOf(d), "");
                        row[d] = v == null ? "" : v.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 坏数据当成没配置：宁可少显示一节，也不能让首页崩
        }
        return t;
    }

    public static boolean save(Context c, Template t) {
        if (t == null) {
            return false;
        }
        try {
            t.ensureCycle();
            JSONObject cs = new JSONObject();
            for (int w = 1; w <= t.cycle; w++) {
                JSONObject wo = new JSONObject();
                String[] row = t.cells.get(w - 1);
                if (row != null) {
                    for (int d = 0; d < DAYS; d++) {
                        String v = row[d];
                        if (!TextUtils.isEmpty(v)) {
                            wo.put(String.valueOf(d), v);
                        }
                    }
                }
                cs.put(String.valueOf(w), wo);
            }
            JSONObject o = new JSONObject();
            o.put("enabled", t.enabled);
            o.put("cycle", t.cycle);
            o.put("time", t.time);
            o.put("calibDate", t.calibDate);
            o.put("calibWeek", t.calibWeek);
            o.put("cells", cs);
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return sp.edit().putString(KEY, o.toString()).commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ======================= 周次推算 =======================

    /**
     * 目标日期所在周是第几周（1..cycle）。
     *
     * <p>以 calibDate 那一周为第 calibWeek 周，其余周按「相差几周」外推到 1..cycle 内。
     * 不用「开学第一天」硬算的原因：考试周 / 运动会 / 临时放假都会把实际周次推偏，
     * 必须允许用户手动说一句「这周算第 3 周」，之后自动往后推。
     */
    public static int weekOf(Template t, Calendar cal) {
        if (t == null || cal == null) {
            return 1;
        }
        int cycle = t.cycle < 1 ? 1 : t.cycle;
        if (TextUtils.isEmpty(t.calibDate)) {
            return clampWeek(t.calibWeek, cycle);
        }
        Calendar base = parseDate(t.calibDate);
        if (base == null) {
            return clampWeek(t.calibWeek, cycle);
        }
        Calendar target = mondayOf(cal);
        long diffMs = target.getTimeInMillis() - base.getTimeInMillis();
        long weeks = Math.floorDiv(diffMs, 7L * 24L * 3600L * 1000L);
        long w = t.calibWeek + weeks;
        return (int) (mod(w - 1, cycle) + 1);
    }

    /** 今天所在周的周次 */
    public static int currentWeek(Context c) {
        try {
            return weekOf(load(c), Calendar.getInstance());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 把任意日期所在周的周一 00:00 作为校正基准（设置页选完「这周是第 N 周」时用） */
    public static String mondayDateString(Calendar cal) {
        Calendar m = mondayOf(cal == null ? Calendar.getInstance() : cal);
        return new SimpleDateFormat(DATE_FMT, Locale.US).format(m.getTime());
    }

    private static Calendar mondayOf(Calendar cal) {
        Calendar m = (Calendar) cal.clone();
        int dow = m.get(Calendar.DAY_OF_WEEK);      // SUNDAY=1 … SATURDAY=7
        int dayIdx = (dow + 5) % 7;                  // 周一=0 … 周日=6
        m.add(Calendar.DAY_OF_MONTH, -dayIdx);
        zeroTime(m);
        return m;
    }

    private static Calendar parseDate(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(DATE_FMT, Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(s);
            if (d == null) {
                return null;
            }
            Calendar cal = Calendar.getInstance();
            cal.setTime(d);
            zeroTime(cal);
            return cal;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static int clampWeek(int w, int cycle) {
        return (int) (mod(w - 1, cycle) + 1);
    }

    private static long mod(long a, long n) {
        long r = a % n;
        return r < 0 ? r + n : r;
    }

    // ======================= 渲染注入 =======================

    /**
     * 把「本周第9节」合成进课程列表。
     *
     * <p>按本周周次，把周一到周日每格有内容的都合成一条课，而不是只加今天那一条 ——
     * 因为周视图要看整周。周视图翻到别的周仍显示本周的第9节，这是已知限制：
     * 周网格本身不表达跨周变化，等周视图支持「按周次预览」再一起改。
     *
     * <p>若用户自己在同一时段排了课（比如第9节他手工建过），以他的为准，不注入。
     */
    public static List<CourseCache.Course> inject(Context c, List<CourseCache.Course> base) {
        Template t;
        try {
            t = load(c);
        } catch (Throwable e) {
            return base;
        }
        if (t == null || !t.enabled || t.isEmpty()) {
            return base;
        }
        int week;
        try {
            week = weekOf(t, Calendar.getInstance());
        } catch (Throwable e) {
            week = 1;
        }
        List<CourseCache.Course> out = (base == null)
                ? new ArrayList<CourseCache.Course>()
                : new ArrayList<CourseCache.Course>(base);
        try {
            for (int d = 0; d < DAYS; d++) {
                String name = t.cell(week, d);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                if (hasSlot(out, d, t.time)) {
                    continue;
                }
                CourseCache.Course co = new CourseCache.Course();
                co.name = name;
                co.time = t.time;
                co.teacher = "";
                co.location = "";
                co.day = d;
                // 槽位固定 id：同一时段同一星期，只换内容，避免 UI 把它当成新建课程
                co.id = "rot_d" + d;
                co.updatedAt = 0;
                out.add(co);
            }
        } catch (Throwable ignored) {
            return base;
        }
        return out;
    }

    /** 目标星期是否已经存在同一时段的课（用户自建优先） */
    private static boolean hasSlot(List<CourseCache.Course> list, int day, String time) {
        if (list == null || TextUtils.isEmpty(time)) {
            return false;
        }
        String want = canon(time);
        for (CourseCache.Course c : list) {
            if (c == null || c.day != day) {
                continue;
            }
            if (want.equals(canon(c.time))) {
                return true;
            }
        }
        return false;
    }

    private static String canon(String time) {
        if (time == null) {
            return "";
        }
        return time.replace("－", "-").replace("—", "-").replaceAll("\\s+", "").trim();
    }

    /** 首页提示条用：今天有第9节吗，是什么 */
    public static String todayName(Context c) {
        try {
            Template t = load(c);
            if (t == null || !t.enabled) {
                return "";
            }
            int day = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
            return t.cell(weekOf(t, Calendar.getInstance()), day);
        } catch (Throwable e) {
            return "";
        }
    }
}
