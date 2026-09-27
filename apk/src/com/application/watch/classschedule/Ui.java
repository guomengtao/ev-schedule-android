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

    /** 底部固定导航：首页 / 聊天 / 设置（对标官方 demo 的底部栏） */
    public static LinearLayout bottomBar(final Activity a, int current) {
        final String[] names = {"首页", "聊天", "设置"};
        final Class[] targets = {HomeActivity.class, ChatActivity.class, SettingsActivity.class};

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = new Button(a);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            b.setTextColor(i == current ? ACCENT : MUTED);
            b.setBackground(round(i == current ? CARD2 : 0x00000000, 12, 0, a));
            b.setMinimumHeight(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(a, 6), dp(a, 8), dp(a, 6), dp(a, 8));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == current) {
                        return;
                    }
                    Intent it = new Intent(a, targets[idx]);
                    it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    a.startActivity(it);
                }
            });
            bar.addView(b, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    /**
     * Wrap content + bottom bar into a FrameLayout.
     * Content sits in a ScrollView (fills screen), bottom bar pinned at bottom.
     * Caller must have added Ui.bottomBar() as the LAST child of contentRoot.
     */
    public static ViewGroup wrapWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        int count = contentRoot.getChildCount();
        if (count > 0) {
            contentRoot.removeViewAt(count - 1);
        }

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(contentRoot);

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = bottomBar(a, currentTab);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bar, barLp);

        return root;
    }
}