package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 手环外观设置的本地镜像（「跟随手环」模式的数据源）。
 *
 * 数据流：每次 export 读到手环的 appearance（appTheme / homepageTemplate / weekviewTemplate）
 * 就存一份到这里；Ui.applyTheme 在「跟随手环」开启时按手环主题色刷新手机端主色与明暗。
 *
 * ★ 明暗与主色跟手环走，中性色（背景/卡片/文字）仍用本端调好的色板 —— 保证可读性不失控。
 * ★ 主题 id 必须与手环 store.js THEMES 一致（10 套）。
 */
public final class WatchAppearance {

    private static final String PREF = "ev_watch_appearance";
    public static final String KEY_FOLLOW = "follow_watch";
    public static final String KEY_THEME = "app_theme";
    public static final String KEY_HOME_TPL = "home_tpl";
    public static final String KEY_WEEK_TPL = "week_tpl";
    public static final String KEY_LOCAL_THEME = "local_theme";
    public static final String KEY_AT = "saved_at";

    /**
     * 手环主题色清单（与手环 store.js THEMES 同源，10 套）。
     * 列：{id, 中文名, bg, accent} —— 全 App 主题相关功能的唯一数据源。
     */
    public static final String[][] THEMES = {
            {"blue",   "深空蓝",   "#1a1a2e", "#7ec8e3"},
            {"green",  "翡翠绿",   "#1a2e1a", "#7ec8a0"},
            {"red",    "珊瑚红",   "#2e1a1a", "#e37e7e"},
            {"dark",   "暗夜黑",   "#000000", "#666666"},
            {"gray",   "深空灰",   "#1a1a1a", "#888899"},
            {"purple", "暗紫魅影", "#1a0a2e", "#b07ec8"},
            {"light",  "晨光白",   "#f0f0f0", "#4a90d9"},
            {"warm",   "暖阳米",   "#f5f0e8", "#c4a882"},
            {"forest", "墨绿护眼", "#1a2a1a", "#6a9a6a"},
            {"amber",  "琥珀金",   "#2a1a0a", "#d4a060"},
    };

    /** 主题中文名；未知 id 原样返回 */
    public static String themeName(String themeId) {
        if (themeId == null) {
            return "";
        }
        for (String[] t : THEMES) {
            if (t[0].equals(themeId)) {
                return t[1];
            }
        }
        return themeId;
    }

    private WatchAppearance() {
    }

    // ======================= 跟随开关（默认开） =======================

    public static boolean followEnabled(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_FOLLOW, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setFollow(Context c, boolean v) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FOLLOW, v).apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 本地主题（静态内置，不连手环也能换） =======================

    /** 本地选择的主题 id；"" = 默认（跟随系统深浅色，用本端标准色板） */
    public static String localTheme(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LOCAL_THEME, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setLocalTheme(Context c, String id) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LOCAL_THEME, id == null ? "" : id).apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 镜像值 =======================

    public static String themeId(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_THEME, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static String homeTpl(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_HOME_TPL, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static String weekTpl(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_WEEK_TPL, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static long savedAt(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_AT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 存手环外观镜像；传 null 的字段保留旧值（兼容旧版手环不返回的情况） */
    public static void save(Context c, String theme, String homeTpl, String weekTpl) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            if (theme != null && theme.length() > 0) {
                e.putString(KEY_THEME, theme);
            }
            if (homeTpl != null && homeTpl.length() > 0) {
                e.putString(KEY_HOME_TPL, homeTpl);
            }
            if (weekTpl != null && weekTpl.length() > 0) {
                e.putString(KEY_WEEK_TPL, weekTpl);
            }
            e.putLong(KEY_AT, System.currentTimeMillis());
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 主题色映射（给 Ui 用） =======================

    /** 手环主题色 → {bgColor, accentColor}；未知 id 返回 null */
    public static int[] watchColors(String themeId) {
        if (themeId == null) {
            return null;
        }
        for (String[] t : THEMES) {
            if (t[0].equals(themeId)) {
                try {
                    return new int[]{
                            (int) (Long.parseLong(t[2].substring(1), 16) | 0xFF000000L),
                            (int) (Long.parseLong(t[3].substring(1), 16) | 0xFF000000L)};
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 背景亮度 → 手环这套主题是深色还是浅色 */
    public static boolean isDarkBg(int bgColor) {
        int r = (bgColor >> 16) & 0xFF;
        int g = (bgColor >> 8) & 0xFF;
        int b = bgColor & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128;
    }

    /**
     * 主题 id → 完整手机端色板 {BG, CARD, CARD2, LINE, TEXT, MUTED, ACCENT}。
     * 由手环同源的 (bg, accent) 推导：背景直接用主题 bg，卡片/边线/文字按深浅自适应派生，
     * 语义色（OK/WARN/ERR）由 Ui 用本端标准值补齐。未知 id 返回 null。
     */
    public static int[] palette(String themeId) {
        int[] wa = watchColors(themeId);
        if (wa == null) {
            return null;
        }
        int bg = wa[0];
        int accent = wa[1];
        int card, card2, line, text, muted;
        if (isDarkBg(bg)) {
            card = mix(bg, 0xFFFFFFFF, 0.06f);
            card2 = mix(bg, 0xFFFFFFFF, 0.12f);
            line = mix(bg, 0xFFFFFFFF, 0.20f);
            text = 0xFFE9EEF9;
            muted = mix(bg, 0xFFFFFFFF, 0.52f);
        } else {
            card = 0xFFFFFFFF;
            card2 = mix(bg, 0xFF000000, 0.04f);
            line = mix(bg, 0xFF000000, 0.10f);
            text = 0xFF0F172A;
            muted = mix(bg, 0xFF000000, 0.45f);
        }
        return new int[]{bg, card, card2, line, text, muted, accent};
    }

    /** 颜色线性插值：t=0 → base，t=1 → target */
    public static int mix(int base, int target, float t) {
        int r = (int) (((base >> 16) & 0xFF) + (((target >> 16) & 0xFF) - ((base >> 16) & 0xFF)) * t);
        int g = (int) (((base >> 8) & 0xFF) + (((target >> 8) & 0xFF) - ((base >> 8) & 0xFF)) * t);
        int b = (int) ((base & 0xFF) + ((target & 0xFF) - (base & 0xFF)) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
