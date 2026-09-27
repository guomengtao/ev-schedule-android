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
    public static final String KEY_AT = "saved_at";

    /** 手环主题色 id → {bg, accent}（与手环 store.js THEMES 同源） */
    private static final String[][] THEME_COLORS = {
            {"blue",   "#1a1a2e", "#7ec8e3"},
            {"green",  "#1a2e1a", "#7ec8a0"},
            {"red",    "#2e1a1a", "#e37e7e"},
            {"dark",   "#000000", "#666666"},
            {"gray",   "#1a1a1a", "#888899"},
            {"purple", "#1a0a2e", "#b07ec8"},
            {"light",  "#f0f0f0", "#4a90d9"},
            {"warm",   "#f5f0e8", "#c4a882"},
            {"forest", "#1a2a1a", "#6a9a6a"},
            {"amber",  "#2a1a0a", "#d4a060"},
    };

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
        for (String[] t : THEME_COLORS) {
            if (t[0].equals(themeId)) {
                try {
                    return new int[]{
                            (int) (Long.parseLong(t[1].substring(1), 16) | 0xFF000000L),
                            (int) (Long.parseLong(t[2].substring(1), 16) | 0xFF000000L)};
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
}
