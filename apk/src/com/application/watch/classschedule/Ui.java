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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 统一视觉：深空蓝风格（对齐 EV 课程表默认主题） */
public final class Ui {

    public static final int BG     = 0xFF0B1020;
    public static final int CARD   = 0xFF151C30;
    public static final int CARD2  = 0xFF1C2542;
    public static final int LINE   = 0xFF27314C;
    public static final int TEXT   = 0xFFE9EEF9;
    public static final int MUTED  = 0xFF8695B4;
    public static final int ACCENT = 0xFF4C8DFF;
    public static final int OK     = 0xFF34C759;
    public static final int WARN   = 0xFFFFB020;
    public static final int ERR    = 0xFFFF6B6B;

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static LinearLayout screen(Activity a) {
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
     *  每个 tab = 图标 + 文字 的竖向布局（图标用 emoji，零资源依赖）。 */
    public static LinearLayout bottomBar(final Activity a, int current) {
        final String[] icons = {"🏠", "💬", "⚙"};   // 首页 / 留言 / 设置
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
            TextView ic = text(a, icons[i], 19f, color, false);
            ic.setGravity(Gravity.CENTER);
            TextView lb = text(a, labels[i], 10.5f, color, false);
            lb.setGravity(Gravity.CENTER);
            tab.addView(ic);
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

        root.addView(bottomBar(a, currentTab), barParams());
        return root;
    }

    /**
     * 页面内部已有自己的滚动区（如留言列表、调试日志）时用这个：
     * 不套外层 ScrollView（避免两层纵向滚动嵌套），只把底栏钉在底部 +
     * 给内容区预留底部留白，让内部 weight=1 的滚动区自然收在底栏之上。
     */
    public static ViewGroup fixedWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bottomBar(a, currentTab), barParams());
        return root;
    }

    private static void reserveBottomSpace(Activity a, LinearLayout contentRoot) {
        contentRoot.setPadding(contentRoot.getPaddingLeft(), contentRoot.getPaddingTop(),
                contentRoot.getPaddingRight(), dp(a, BAR_HEIGHT_DP));
    }

    private static FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }
}