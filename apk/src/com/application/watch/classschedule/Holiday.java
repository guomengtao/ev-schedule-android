package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * 假期 / 调休（移植自 EV 课程表的 holiday_data + holidays-2026，逻辑等价、字段收窄）。
 *
 * 数据模型：日期(YYYY-MM-DD) → 条目，一个日期只可能是其中一种：
 *   holiday：放假 —— 当天不排课，显示假期名
 *   workday：调休补课 —— 当天按 targetWeekDay 对应星期几的课表显示
 *
 * {@link #resolveDay} 返回值（与 EV holiday.js 的约定一致）：
 *   {@link #HOLIDAY}(-2) = 假期日（当天课程清空，显示假期卡片）
 *   0..6                 = 调休日，按该下标的星期几课表显示（0=周一 … 6=周日，与 CourseCache 一致）
 *   {@link #NORMAL}(-1)  = 普通日（按真实星期）
 *
 * 开关：`holiday_enabled`，**默认开启**（对应 EV「每张课表默认开启假期提醒」）。
 * ⚠️ 与 EV 语义不同的一点：EV 的开关只控制「要不要显示提示」，关了提醒课照样被清空/换课，
 *    这里改成开关即总开关（关掉 = 完全按普通日历走），更符合直觉。
 *
 * 内置数据：国务院办公厅《关于2026年部分节假日安排的通知》（国办发明电〔2025〕7号）。
 * 种子里的 weekDay 沿用 EV 的口径（0=周日 … 6=周六），读取时换算成 CourseCache 的 0=周一 … 6=周日。
 */
public final class Holiday {

    /** 普通日：按真实星期 */
    public static final int NORMAL = -1;
    /** 假期日：当天无课 */
    public static final int HOLIDAY = -2;

    private static final String PREF = "holiday_store";
    private static final String KEY_ENABLED = "holiday_enabled";
    private static final String KEY_OVERRIDES = "holiday_overrides"; // 预留：用户自定义覆盖（json）

    /**
     * 内置 2026 放假/调休种子。格式：`日期|h|假期名`（放假）/ `日期|w|目标周几`（调休）。
     * 调休的周几沿用 EV 口径：0=周日 1=周一 … 6=周六。
     */
    private static final String[] SEED = {
            "2026-01-01|h|元旦", "2026-01-02|h|元旦", "2026-01-03|h|元旦",
            "2026-01-04|w|1",

            "2026-02-15|h|春节", "2026-02-16|h|春节", "2026-02-17|h|春节", "2026-02-18|h|春节",
            "2026-02-19|h|春节", "2026-02-20|h|春节", "2026-02-21|h|春节", "2026-02-22|h|春节",
            "2026-02-23|h|春节",
            "2026-02-14|w|1",
            "2026-02-28|w|5",

            "2026-04-04|h|清明", "2026-04-05|h|清明", "2026-04-06|h|清明",

            "2026-05-01|h|劳动节", "2026-05-02|h|劳动节", "2026-05-03|h|劳动节",
            "2026-05-04|h|劳动节", "2026-05-05|h|劳动节",
            "2026-05-09|w|2",

            "2026-06-19|h|端午", "2026-06-20|h|端午", "2026-06-21|h|端午",

            "2026-09-20|w|2",
            "2026-09-25|h|中秋", "2026-09-26|h|中秋", "2026-09-27|h|中秋",

            "2026-10-01|h|国庆", "2026-10-02|h|国庆", "2026-10-03|h|国庆", "2026-10-04|h|国庆",
            "2026-10-05|h|国庆", "2026-10-06|h|国庆", "2026-10-07|h|国庆",
            "2026-10-10|w|3",
    };

    private static final class Entry {
        final boolean holiday;
        final String name;
        /** EV 口径：0=周日 … 6=周六 */
        final int weekDay;

        Entry(boolean holiday, String name, int weekDay) {
            this.holiday = holiday;
            this.name = name;
            this.weekDay = weekDay;
        }
    }

    private static Map<String, Entry> table;

    private static synchronized Map<String, Entry> table() {
        if (table == null) {
            Map<String, Entry> m = new HashMap<>();
            for (String row : SEED) {
                try {
                    String[] p = row.split("\\|");
                    if (p.length < 3) {
                        continue;
                    }
                    if ("h".equals(p[1])) {
                        m.put(p[0], new Entry(true, p[2], -1));
                    } else if ("w".equals(p[1])) {
                        m.put(p[0], new Entry(false, "", Integer.parseInt(p[2])));
                    }
                } catch (Throwable ignored) {
                }
            }
            table = m;
        }
        return table;
    }

    private static String ymd(Calendar cal) {
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int d = cal.get(Calendar.DAY_OF_MONTH);
        return String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", y, m, d);
    }

    // ---------------- 开关 ----------------

    /**
     * 假期模式是否开启。**真源在课程表 JSON 的 holiday 字段**（每张课表各自一个，默认 true），
     * 没有激活课表时也按开启处理。
     */
    public static boolean enabled(Context c) {
        try {
            return ScheduleStore.holidayEnabled(c);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 开/关当前激活课表的假期模式（设置页的开关） */
    public static void setEnabled(Context c, boolean on) {
        try {
            ScheduleStore.setHolidayEnabled(c, on);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 解析 ----------------

    /**
     * 给定日期 → 当天该按哪套课表显示。
     *
     * @return {@link #HOLIDAY}（假期） / 0..6（调休，按该下标星期几的课表，0=周一） / {@link #NORMAL}
     */
    public static int resolveDay(Context c, Calendar cal) {
        if (cal == null) {
            return NORMAL;
        }
        if (!enabled(c)) {
            return NORMAL;
        }
        Entry e = table().get(ymd(cal));
        // 用户自定义覆盖（暂无 UI，但优先级最高，方便手工救急）
        if (e == null) {
            e = override(c, ymd(cal));
        }
        if (e == null) {
            return NORMAL;
        }
        if (e.holiday) {
            return HOLIDAY;
        }
        // EV 口径(0=周日) → CourseCache 口径(0=周一)
        return (e.weekDay + 6) % 7;
    }

    /** 今天该按哪套课表显示（快捷方法） */
    public static int resolveToday(Context c) {
        return resolveDay(c, Calendar.getInstance());
    }

    /** 假期名（仅当当天是假期才有值） */
    public static String holidayName(Context c, Calendar cal) {
        if (!enabled(c) || cal == null) {
            return "";
        }
        Entry e = table().get(ymd(cal));
        return (e != null && e.holiday) ? e.name : "";
    }

    /** 日期条上的角标：放假 = 「休」，调休 = 「班」，普通日 = 空串 */
    public static String badge(Context c, Calendar cal) {
        if (!enabled(c) || cal == null) {
            return "";
        }
        Entry e = table().get(ymd(cal));
        if (e == null) {
            return "";
        }
        return e.holiday ? "休" : "班";
    }

    /** 用户手工覆盖：写入 prefs（优先级高于内置种子），key = "日期|h|名" / "日期|w|周几" */
    public static void putOverride(Context c, String ymdStr, boolean holiday, String nameOrWeekDay) {
        try {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = p.getString(KEY_OVERRIDES, "{}");
            String row = ymdStr + "|" + (holiday ? "h|" + safe(nameOrWeekDay) : "w|" + safe(nameOrWeekDay));
            // 极简存储：一行一条，按日期去重
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\n")) {
                if (line.trim().length() == 0 || line.startsWith(ymdStr + "|")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            sb.append(row);
            p.edit().putString(KEY_OVERRIDES, sb.toString()).apply();
            table = null; // 让缓存失效，下次读取重建
        } catch (Throwable ignored) {
        }
    }

    private static Entry override(Context c, String day) {
        try {
            String raw = c.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString(KEY_OVERRIDES, "");
            if (raw == null || raw.length() == 0) {
                return null;
            }
            for (String line : raw.split("\n")) {
                String[] p = line.split("\\|");
                if (p.length >= 3 && p[0].equals(day)) {
                    if ("h".equals(p[1])) {
                        return new Entry(true, p[2], -1);
                    }
                    if ("w".equals(p[1])) {
                        return new Entry(false, "", Integer.parseInt(p[2]));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String safe(String s) {
        s = s == null ? "" : s.replace('\n', ' ').trim();
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
