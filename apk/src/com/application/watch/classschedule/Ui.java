package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一视觉 —— Token + Theme（2026-09-28 起）。
 *
 * ★ 设计令牌（Design Token）：所有颜色从这里的 token 取，**任何页面不许写死颜色**。
 *   两套主题：
 *     - 浅色「晴空蓝」（默认）：白天教室/户外高频扫一眼的课表场景，浅色可读性最好
 *     - 深色「夜幕蓝」（夜间）：跟随系统夜间模式自动切换
 *
 * ★ 兼容性：token 由 final 常量改为可变字段 + applyTheme() 刷新，
 *   全部 9 个 Activity 已经只走 Ui.text/card/button 等 API，**无需任何改动**。
 *
 * ★ 课程区分色：12 色调色板，按课程名 hash 稳定分配（同一门课永远同色）。
 */
public final class Ui {

    // ============ Token（由 applyTheme 按当前主题刷新；不要在别处写死颜色） ============
    public static int BG;
    public static int CARD;
    public static int CARD2;
    public static int LINE;
    public static int TEXT;
    public static int MUTED;
    public static int ACCENT;
    public static int OK;
    public static int WARN;
    public static int ERR;
    private static boolean dark = true;

    // ============ 浅色「晴空蓝」 ============
    private static final int[] L = {
            0xFFF4F7FB, // BG
            0xFFFFFFFF, // CARD
            0xFFEAF0F8, // CARD2（次级表面 / 非主按钮）
            0xFFE3EAF3, // LINE
            0xFF0F172A, // TEXT
            0xFF64748B, // MUTED
            0xFF2F6BFF, // ACCENT
            0xFF16A34A, // OK
            0xFFF59E0B, // WARN
            0xFFDC2626, // ERR
    };

    // ============ 深色「夜幕蓝」（ACCENT 提亮一档保证对比度） ============
    private static final int[] D = {
            0xFF0B1020, // BG
            0xFF151C30, // CARD
            0xFF1C2542, // CARD2
            0xFF27314C, // LINE
            0xFFE9EEF9, // TEXT
            0xFF8695B4, // MUTED
            0xFF5B9BFF, // ACCENT
            0xFF34C759, // OK
            0xFFFFB020, // WARN
            0xFFFF6B6B, // ERR
    };

    /** 12 色课程区分色（浅/深两套主题共用；同一门课永远同色） */
    public static final int[] COURSE_COLORS = {
            0xFF3B82F6, 0xFFEF4444, 0xFF10B981, 0xFFF59E0B,
            0xFF8B5CF6, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16,
            0xFFF97316, 0xFF6366F1, 0xFF14B8A6, 0xFFA855F7,
    };

    public static boolean isDark() {
        return dark;
    }

    /**
     * 套用主题。每个页面入口（screen / wrapWithBottomBar / fixedWithBottomBar）都会调它。
     *
     * 主题源优先级：
     *   1. 「跟随手环」开启且已同步到手环主题色 → 用手环主题的整套色板
     *   2. 本地选过主题（静态内置 10 套，**不连手环也能用**）→ 用本地主题色板
     *   3. 都没有 → 系统夜间模式（浅色「晴空蓝」默认 / 深色「夜幕蓝」）
     */
    public static void applyTheme(Context c) {
        boolean night;
        try {
            int mode = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            night = (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        } catch (Throwable t) {
            night = true;
        }
        int[] custom = null;
        try {
            String tid = null;
            if (WatchAppearance.followEnabled(c) && WatchAppearance.themeId(c).length() > 0) {
                tid = WatchAppearance.themeId(c);           // 跟随手环：手环主题优先
            }
            if (tid == null && WatchAppearance.localTheme(c).length() > 0) {
                tid = WatchAppearance.localTheme(c);        // 本地静态主题（离线可用）
            }
            if (tid != null) {
                custom = WatchAppearance.palette(tid);
                if (custom != null) {
                    night = WatchAppearance.isDarkBg(custom[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        dark = night;
        int[] p = dark ? D : L;
        BG = custom != null ? custom[0] : p[0];
        CARD = custom != null ? custom[1] : p[1];
        CARD2 = custom != null ? custom[2] : p[2];
        LINE = custom != null ? custom[3] : p[3];
        TEXT = custom != null ? custom[4] : p[4];
        MUTED = custom != null ? custom[5] : p[5];
        ACCENT = custom != null ? custom[6] : p[6];
        OK = p[7];
        WARN = p[8];
        ERR = p[9];
        // 系统控件（Switch / AlertDialog / EditText 光标等）也要跟着换：
        // 必须在 setContentView 之前 setTheme —— 每个页面都是先走 Ui.screen()，恰好满足
        if (c instanceof Activity) {
            ((Activity) c).setTheme(dark
                    ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
        }
    }

    /** 课程 → 区分色（稳定：同一门课在任何页面/任何手机上都是同一个颜色） */
    public static int courseColor(String courseName) {
        int h = (courseName == null) ? 0 : courseName.hashCode();
        return COURSE_COLORS[(h & 0x7fffffff) % COURSE_COLORS.length];
    }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static LinearLayout screen(Activity a) {
        applyTheme(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
        return root;
    }

    public static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(null, Typeface.BOLD);
        }
        return t;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        l.setBackground(round(CARD, 14, LINE, c));
        return l;
    }

    public static GradientDrawable round(int fill, int radiusDp, int stroke, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(c, 1)), stroke);
        }
        return g;
    }

    public static Button button(Context c, String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        b.setBackground(round(primary ? ACCENT : CARD2, 12, primary ? 0 : LINE, c));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    /** 列表式可点击行（用于调试步骤 / 设置项） */
    public static LinearLayout row(Context c, String title, String sub, int tint,
                                  final View.OnClickListener click) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        outer.setBackground(round(CARD2, 12, LINE, c));

        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);

        TextView t = text(c, title, 13.5f, tint, true);
        inner.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11.5f, MUTED, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            inner.addView(s);
        }
        outer.addView(inner);

        if (click != null) {
            outer.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { click.onClick(v); }
            });
        }
        return outer;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, h)));
        return v;
    }

    /** 两列按钮网格 */
    public static LinearLayout grid(Context c, View a, View b) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        r.addView(a, lp);
        r.addView(b, lp);
        return r;
    }

    public static TextView mono(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(MUTED);
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 19f, TEXT, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /**
     * 底栏高度（dp）。内容区必须预留这么多底部留白，
     * 否则最后一段内容会被悬浮在底部的导航栏盖住（"底部的东西看不到"）。
     */
    public static final int BAR_HEIGHT_DP = 62;

    /** 底部固定导航：首页 / 留言 / 设置（任何时候都常驻）。
     *  每个 tab = 图标 + 文字 的竖向布局。
     *  图标：Lucide 规范单色线性图标（res/drawable/ic_tab_*.xml，24×24 描边 2），
     *  运行时按选中态染色（选中 = 主色，未选中 = 次要色）。 */
    public static LinearLayout bottomBar(final Activity a, int current) {
        final int[] icons = {R.drawable.ic_tab_home, R.drawable.ic_tab_message,
                R.drawable.ic_tab_settings};
        final String[] labels = {"首页", "留言", "设置"};
        final Class[] targets = {HomeActivity.class, MessageActivity.class, SettingsActivity.class};

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final int color = (i == current) ? ACCENT : MUTED;
            LinearLayout tab = new LinearLayout(a);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setClickable(true);
            tab.setBackground(round(i == current ? CARD2 : 0x00000000, 12, 0, a));
            ImageView ic = new ImageView(a);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(color); // 单色染色：SRC_IN，透明底描边图标整体变色
            tab.addView(ic, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
            TextView lb = text(a, labels[i], 10.5f, color, false);
            lb.setGravity(Gravity.CENTER);
            tab.addView(lb);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == current) {
                        return;
                    }
                    Intent it = new Intent(a, targets[idx]);
                    it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    a.startActivity(it);
                }
            });
            bar.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    /**
     * 内容可滚动的页面：内容放进 ScrollView，底栏固定在屏幕底部。
     *
     * ⚠️ 两个关键点（都是之前"首页滚不动 / 底部看不到"的根因）：
     *   1) 给 ScrollView 的子 View 显式传 WRAP_CONTENT 高度。
     *      ScrollView 继承 FrameLayout，不传 LayoutParams 时默认是 MATCH_PARENT，
     *      子 View 会被强制成"一屏高"→ 超出部分被裁掉，滚动失效。
     *   2) 内容区预留 BAR_HEIGHT_DP 的底部留白，避免被悬浮底栏盖住。
     *
     * 底栏由本方法唯一创建；调用方【不要】再往 contentRoot 里加 bottomBar。
     */
    public static ViewGroup wrapWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        applyTheme(a);
        final LinearLayout bar = bottomBar(a, currentTab);
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(bar, barParams());
        syncPaddingToBar(contentRoot, bar);
        return root;
    }

    /**
     * 页面内部已有自己的滚动区（如留言列表、调试日志）时用这个：
     * 不套外层 ScrollView（避免两层纵向滚动嵌套），只把底栏钉在底部 +
     * 给内容区预留底部留白，让内部 weight=1 的滚动区自然收在底栏之上。
     */
    public static ViewGroup fixedWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        applyTheme(a);
        final LinearLayout bar = bottomBar(a, currentTab);
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bar, barParams());
        syncPaddingToBar(contentRoot, bar);
        return root;
    }

    private static void reserveBottomSpace(Activity a, LinearLayout contentRoot) {
        contentRoot.setPadding(contentRoot.getPaddingLeft(), contentRoot.getPaddingTop(),
                contentRoot.getPaddingRight(), dp(a, BAR_HEIGHT_DP));
    }

    /**
     * 用【实测】的底栏高度校准底部留白，而不是只信 BAR_HEIGHT_DP 常量。
     *
     * 底栏实际高度 = 图标(19sp emoji) + 文字 + 上下 padding，在某些机型/字体缩放下会超过
     * 常量的 62dp（实测 BLN-AL20 density=3 时是 86dp），于是「最后一行按钮被压掉半截」。
     * 这里在布局完成后按真实高度重设 paddingBottom，常量退化为"首次渲染前的估计值"。
     */
    private static void syncPaddingToBar(final LinearLayout contentRoot, final View bar) {
        bar.post(new Runnable() {
            @Override public void run() {
                int h = bar.getHeight();
                if (h <= 0) {
                    return;
                }
                contentRoot.setPadding(contentRoot.getPaddingLeft(), contentRoot.getPaddingTop(),
                        contentRoot.getPaddingRight(), h + dp(contentRoot.getContext(), 12));
            }
        });
    }

    private static FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }
}