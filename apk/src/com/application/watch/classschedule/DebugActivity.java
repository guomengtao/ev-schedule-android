package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 多步骤调试页：每一步独立可点，逐步看连接状态。
 * 与首页的区别：这里不做自动化，纯粹是"哪一步不通"的定位工具。
 *
 * UI 结构（v1 重设计后）：
 *   状态总览条 → [链路自检] 4 张步骤卡（失败就地长建议条） → 全部执行 / 重置
 *   → [单项探测] 3 个探测按钮 → [报文 · 日志] JSON 输入 + 就地结果条 + 运行日志
 * 字号一律走 Ui.SP_* 档位，零裸写。
 */
public class DebugActivity extends Activity {

    private int lastThemeVersion = 0;

    private static final int STEPS = 4;
    /** 序号由步骤卡左侧圆点承担，标题不再带 "N " 前缀 */
    private static final String[] LABELS =
            {"初始化穿戴服务", "查找已连接设备", "申请设备权限", "连接 EV 课程表"};
    private static final String[] SUBS = {
            "检测小米穿戴服务是否可用",
            "拿到 nodeId（手环必须已连接）",
            "DEVICE_MANAGER + NOTIFY",
            "ping 手环上的 EV 课程表"
    };

    private final int[] states = new int[STEPS];
    private final String[] details = new String[STEPS];

    // 步骤卡各部件（供 setState 就地刷新，不再 removeAllViews 重建）
    private final LinearLayout[] rows = new LinearLayout[STEPS];
    private final View[] bars = new View[STEPS];
    private final TextView[] dots = new TextView[STEPS];
    private final TextView[] titles = new TextView[STEPS];
    private final TextView[] subs = new TextView[STEPS];
    /** 就地恢复建议条容器（缩进 47dp 对齐步骤卡文字左缘），仅失败时出现 */
    private final LinearLayout[] hintBox = new LinearLayout[STEPS];

    private TextView sumDot, sumTitle, sumSub, sumProgress;
    private TextView resultBar;
    private TextView logView;
    private EditText inputView;
    private final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "调试"));
        root.addView(Ui.space(this, 4));
        root.addView(buildSumBar());
        root.addView(Ui.space(this, 12));

        // ---------- 链路自检 ----------
        root.addView(grpTitle("链路自检"));
        for (int i = 0; i < STEPS; i++) {
            states[i] = SyncEngine.PENDING;
            details[i] = "待执行";
            rows[i] = stepCard(i);
            root.addView(rows[i]);

            hintBox[i] = new LinearLayout(this);
            hintBox[i].setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams hp = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.leftMargin = Ui.dp(this, 47);
            hp.topMargin = Ui.dp(this, 6);
            hintBox[i].setLayoutParams(hp);
            hintBox[i].setVisibility(View.GONE);
            root.addView(hintBox[i]);

            if (i < STEPS - 1) {
                root.addView(Ui.space(this, 6));
            }
        }
        root.addView(Ui.space(this, 10));
        root.addView(Ui.button(this, "全部执行", true, new View.OnClickListener() {
            @Override public void onClick(View v) { runAll(); }
        }));
        root.addView(ghost("重置状态", new View.OnClickListener() {
            @Override public void onClick(View v) { reset(); }
        }));

        // ---------- 单项探测 ----------
        root.addView(Ui.space(this, 8));
        root.addView(grpTitle("单项探测"));
        root.addView(Ui.grid(this,
                Ui.button(this, "EV 是否安装", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { evInstalled(); }
                }),
                Ui.button(this, "拉起手环 EV", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                })));
        root.addView(Ui.grid(this,
                Ui.button(this, "手表通知(验下行)", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { notifyTest(); }
                }),
                spacer()));

        // ---------- 报文 · 日志 ----------
        root.addView(Ui.space(this, 8));
        root.addView(grpTitle("报文 · 日志"));
        root.addView(microLabel("自定义 JSON"));

        inputView = new EditText(this);
        inputView.setText("{\"action\":\"ping\"}");
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
        inputView.setTypeface(Typeface.MONOSPACE);
        inputView.setTextColor(Ui.TEXT);
        inputView.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        inputView.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(inputView);

        root.addView(Ui.space(this, 8));
        root.addView(Ui.button(this, "发送自定义 JSON", true, new View.OnClickListener() {
            @Override public void onClick(View v) { sendRaw(); }
        }));
        root.addView(Ui.space(this, 8));
        resultBar = new TextView(this);
        resultBar.setVisibility(View.GONE);
        resultBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
        resultBar.setTypeface(Typeface.DEFAULT_BOLD);
        resultBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(resultBar);

        root.addView(Ui.space(this, 10));
        root.addView(buildLogHead());

        logView = Ui.mono(this, "");
        logView.setTypeface(Typeface.MONOSPACE);
        ScrollView logScroll = new ScrollView(this);
        logScroll.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 104)));
        logScroll.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        logScroll.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        logScroll.addView(logView);
        root.addView(logScroll);

        root.addView(Ui.space(this, 8));
        root.addView(Ui.button(this, "连接手环日志", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(DebugActivity.this, ConnLogActivity.class));
            }
        }));

        setContentView(Ui.wrapWithBottomBar(this, root, -1));

        // 进页面不自动弹出输入法（把焦点交给根布局，EditText 不抢焦点）
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    // ======================= 构件 =======================

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 分组标题：沿用设置页 .grp —— SP_CAPTION 11.5 600 MUTED */
    private TextView grpTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
        t.setTextColor(Ui.MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams p = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 7);
        t.setLayoutParams(p);
        return t;
    }

    /** 小标签（JSON / 日志表头）：SP_MICRO 10.5 600 MUTED */
    private TextView microLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_MICRO);
        t.setTextColor(Ui.MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams p = lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = Ui.dp(this, 5);
        t.setLayoutParams(p);
        return t;
    }

    /** ghost 按钮：透明底无描边、MUTED、400 字重，热区靠 padding 撑到 ≥44dp */
    private TextView ghost(String s, final View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_BODY);
        t.setTextColor(Ui.MUTED);
        t.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14));
        t.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        t.setOnClickListener(l);
        return t;
    }

    /** grid 第二列占位（保证左侧按钮仍是半宽，视觉对齐） */
    private View spacer() {
        View v = new View(this);
        v.setVisibility(View.INVISIBLE);
        return v;
    }

    /** 正圆背景 */
    private GradientDrawable circle(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dp(this, radiusDp));
        return g;
    }

    /** 只保留左侧两圆角的竖条背景（左上/左下） */
    private GradientDrawable leftBar(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = Ui.dp(this, Ui.R_CTRL);
        g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        return g;
    }

    // ---------- 状态总览条 ----------

    private LinearLayout buildSumBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 12),
                Ui.dp(this, 14), Ui.dp(this, 12));
        bar.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));

        sumDot = new TextView(this);
        sumDot.setLayoutParams(lp(Ui.dp(this, 8), Ui.dp(this, 8)));
        sumDot.setBackground(circle(Ui.MUTED, 4));
        bar.addView(sumDot);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 10);
        tp.rightMargin = Ui.dp(this, 8);
        texts.setLayoutParams(tp);
        sumTitle = Ui.text(this, "尚未执行", Ui.SP_BODY, Ui.TEXT, true);
        sumSub = Ui.text(this, "点任一步骤开始定位，或点「全部执行」",
                Ui.SP_MICRO, Ui.MUTED, false);
        texts.addView(sumTitle);
        texts.addView(sumSub);
        bar.addView(texts);

        sumProgress = Ui.text(this, "0 / " + STEPS, Ui.SP_CAPTION, Ui.MUTED, false);
        bar.addView(sumProgress);
        return bar;
    }

    // ---------- 步骤卡 ----------

    private LinearLayout stepCard(final int i) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        card.setMinimumHeight(Ui.dp(this, Ui.TOUCH_MIN));
        card.setLayoutParams(lp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        View bar = new View(this);
        bar.setLayoutParams(lp(Ui.dp(this, 3), LinearLayout.LayoutParams.MATCH_PARENT));
        bar.setBackground(leftBar(Ui.LINE));
        bars[i] = bar;
        card.addView(bar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setLayoutParams(lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        body.setPadding(Ui.dp(this, 11), Ui.dp(this, 11),
                Ui.dp(this, 11), Ui.dp(this, 11));

        TextView dot = new TextView(this);
        dot.setLayoutParams(lp(Ui.dp(this, 26), Ui.dp(this, 26)));
        dot.setGravity(Gravity.CENTER);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setText(String.valueOf(i + 1));
        dot.setTextColor(Ui.MUTED);
        dot.setBackground(circle(Ui.CARD2, 13));
        dots[i] = dot;
        body.addView(dot);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 10);
        texts.setLayoutParams(tp);

        TextView t = Ui.text(this, LABELS[i], Ui.SP_BODY, Ui.TEXT, true);
        titles[i] = t;
        texts.addView(t);

        TextView s = Ui.text(this, SUBS[i] + " · 待执行", Ui.SP_CAPTION, Ui.MUTED, false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        s.setMaxLines(2);
        s.setEllipsize(TextUtils.TruncateAt.END);
        subs[i] = s;
        texts.addView(s);
        body.addView(texts);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_BODY);
        chev.setTextColor(Ui.MUTED);
        chev.setGravity(Gravity.CENTER);
        chev.setLayoutParams(lp(Ui.dp(this, 34),
                LinearLayout.LayoutParams.MATCH_PARENT));
        body.addView(chev);

        card.addView(body);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runStep(i); }
        });
        return card;
    }

    // ---------- 就地恢复建议条 ----------

    private String hintText(int i) {
        switch (i) {
            case 0: return "小米穿戴服务不可用 —— 确认已安装并更新到新版，再回来重试";
            case 1: return "小米运动健康与手环的 BLE 连接久了会自己断开 —— 把它拉到前台即可重连";
            case 2: return "小米穿戴权限需在小米运动健康里重新授权 —— 打开它确认本 App 已被允许";
            default: return "手环上的 EV 课程表没响应 —— 先把它拉起来再重试";
        }
    }

    private String hintBtn(int i) {
        switch (i) {
            case 0: return "打开小米运动健康";
            case 1: return "去重连";
            case 2: return "去小米运动健康";
            default: return "拉起手环 EV";
        }
    }

    private View.OnClickListener hintAction(int i) {
        if (i == 3) {
            return new View.OnClickListener() {
                @Override public void onClick(View v) { launchEv(); }
            };
        }
        // 步骤 1/2/3 的恢复手段都是把小米运动健康拉到前台（openMiFitness 已实现，零风险）
        return new View.OnClickListener() {
            @Override public void onClick(View v) { openMiFitness(); }
        };
    }

    private void refreshHint(final int i) {
        if (states[i] != SyncEngine.FAIL) {
            hintBox[i].removeAllViews();
            hintBox[i].setVisibility(View.GONE);
            return;
        }
        hintBox[i].removeAllViews();

        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackground(Ui.round(Ui.WARN_LIGHT, Ui.R_CTRL, 0, this));
        h.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        TextView tv = Ui.text(this, hintText(i), Ui.SP_CAPTION, Ui.TEXT, false);
        h.addView(tv);

        TextView b = new TextView(this);
        b.setText(hintBtn(i));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFFFFFFFF);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(Ui.WARN, Ui.R_CTRL, 0, this));
        LinearLayout.LayoutParams bp = lp(LinearLayout.LayoutParams.WRAP_CONTENT,
                Ui.dp(this, 32));
        bp.topMargin = Ui.dp(this, 8);
        b.setLayoutParams(bp);
        // 视觉 32dp，上下各补 6dp padding → 实际热区 44dp
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 6),
                Ui.dp(this, 14), Ui.dp(this, 6));
        b.setOnClickListener(hintAction(i));
        h.addView(b);

        hintBox[i].addView(h);
        hintBox[i].setVisibility(View.VISIBLE);
    }

    // ---------- 日志表头 ----------

    private LinearLayout buildLogHead() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText("运行日志");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_MICRO);
        t.setTextColor(Ui.MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);

        TextView clear = new TextView(this);
        clear.setText("清屏");
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_MICRO);
        clear.setTextColor(Ui.MUTED);
        clear.setPadding(Ui.dp(this, 10), Ui.dp(this, 14),
                Ui.dp(this, 10), Ui.dp(this, 14));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logView.setText(""); }
        });
        head.addView(clear);
        return head;
    }

    // ======================= 状态刷新 =======================

    private void setState(int i, int state, String detail) {
        states[i] = state;
        details[i] = detail;

        int color = Ui.TEXT;
        int barColor = Ui.LINE;
        if (state == SyncEngine.OK) {
            color = Ui.OK;
            barColor = Ui.OK;
        } else if (state == SyncEngine.RUNNING) {
            color = Ui.ACCENT;
            barColor = Ui.ACCENT;
        } else if (state == SyncEngine.FAIL) {
            color = Ui.ERR;
            barColor = Ui.ERR;
        }

        bars[i].setBackground(leftBar(barColor));
        titles[i].setTextColor(color);

        if (state == SyncEngine.OK) {
            dots[i].setText("✓");
            dots[i].setTextColor(Ui.OK);
            dots[i].setBackground(circle(Ui.OK_LIGHT, 13));
        } else if (state == SyncEngine.FAIL) {
            dots[i].setText("✕");
            dots[i].setTextColor(0xFFFFFFFF);
            dots[i].setBackground(circle(Ui.ERR, 13));
        } else if (state == SyncEngine.RUNNING) {
            dots[i].setText(String.valueOf(i + 1));
            dots[i].setTextColor(Ui.ACCENT);
            dots[i].setBackground(circle(Ui.ACCENT_LIGHT, 13));
        } else {
            dots[i].setText(String.valueOf(i + 1));
            dots[i].setTextColor(Ui.MUTED);
            dots[i].setBackground(circle(Ui.CARD2, 13));
        }

        // 副文案：成功显示真实返回值，而不是沿用静态说明
        String sub;
        if (state == SyncEngine.PENDING) {
            sub = SUBS[i] + " · 待执行";
        } else if (state == SyncEngine.RUNNING) {
            sub = "执行中…";
        } else if (detail == null || detail.length() == 0) {
            sub = SUBS[i];
        } else {
            sub = detail;
        }
        subs[i].setText(sub);

        refreshHint(i);
        refreshSum();
    }

    private void refreshSum() {
        int okCount = 0, failIdx = -1, runIdx = -1;
        for (int i = 0; i < STEPS; i++) {
            if (states[i] == SyncEngine.OK) {
                okCount++;
            } else if (states[i] == SyncEngine.FAIL && failIdx < 0) {
                failIdx = i;
            } else if (states[i] == SyncEngine.RUNNING && runIdx < 0) {
                runIdx = i;
            }
        }

        int dotColor;
        String title, sub;
        if (runIdx >= 0) {
            dotColor = Ui.ACCENT;
            title = "正在自检…";
            sub = "依次执行 4 个步骤，请稍候";
        } else if (failIdx >= 0) {
            dotColor = Ui.ERR;
            title = "停在第 " + (failIdx + 1) + " 步";
            sub = LABELS[failIdx] + " 未通过，看该步下方的建议";
        } else if (okCount == STEPS) {
            dotColor = Ui.OK;
            title = "4 项全部通过";
            sub = "链路正常，手环可以收发消息";
        } else {
            dotColor = Ui.MUTED;
            title = "尚未执行";
            sub = "点任一步骤开始定位，或点「全部执行」";
        }
        sumDot.setBackground(circle(dotColor, 4));
        sumTitle.setText(title);
        sumTitle.setTextColor(dotColor == Ui.MUTED ? Ui.TEXT : dotColor);
        sumSub.setText(sub);
        sumProgress.setText(okCount + " / " + STEPS);
    }

    /** 报文区就地结果条：0=进行 1=成功 2=超时 3=错误 */
    private void showResult(int level, String text) {
        int bg, fg;
        if (level == 1) {
            bg = Ui.OK_LIGHT;
            fg = Ui.OK;
        } else if (level == 2) {
            bg = Ui.WARN_LIGHT;
            fg = Ui.WARN;
        } else if (level == 3) {
            bg = Ui.ERR;
            fg = 0xFFFFFFFF;
        } else {
            bg = Ui.ACCENT_LIGHT;
            fg = Ui.ACCENT;
        }
        resultBar.setText(text);
        resultBar.setTextColor(fg);
        resultBar.setBackground(Ui.round(bg, Ui.R_CTRL, 0, this));
        resultBar.setVisibility(View.VISIBLE);
    }

    // ======================= 步骤 =======================

    private void runStep(final int i) {
        setState(i, SyncEngine.RUNNING, "执行中…");
        final SyncEngine.Cb cb = new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                setState(i, ok ? SyncEngine.OK : SyncEngine.FAIL, msg);
                log((ok ? "[OK] " : "[FAIL] ") + LABELS[i] + " → " + msg);
            }
        };
        SyncEngine e = SyncEngine.get(this);
        switch (i) {
            case 0: e.step1Service(cb); break;
            case 1: e.step2Nodes(cb); break;
            case 2: e.step3Perm(cb); break;
            default: e.step4Ping(cb); break;
        }
    }

    private void runAll() {
        reset();
        log("—— 开始全部执行 ——");
        runStep(0);
        for (int i = 1; i < STEPS; i++) {
            final int idx = i;
            logView.postDelayed(new Runnable() {
                @Override public void run() {
                    if (states[idx - 1] == SyncEngine.OK) {
                        runStep(idx);
                    } else {
                        setState(idx, SyncEngine.PENDING, "前一步未完成，已跳过");
                    }
                }
            }, 1500L * i);
        }
    }

    private void reset() {
        for (int i = 0; i < STEPS; i++) {
            setState(i, SyncEngine.PENDING, "待执行");
        }
        resultBar.setVisibility(View.GONE);
    }

    private void evInstalled() {
        SyncEngine.get(this).evInstalled(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                log("EV 已安装? " + msg);
            }
        });
    }

    private void launchEv() {
        SyncEngine.get(this).launchEv(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) { log("拉起 EV → " + msg); }
        });
    }

    /** 打开小米运动健康：它到前台才会重新去连手环（步骤 2 超时的最常见恢复手段）。 */
    private void openMiFitness() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.xiaomi.wearable");
            if (i == null) {
                i = getPackageManager().getLaunchIntentForPackage("com.xiaomi.health");
            }
            if (i == null) {
                log("未找到小米运动健康，请手动打开");
                return;
            }
            startActivity(i);
            log("已打开小米运动健康——等它连上手环后，回来点「全部执行」");
        } catch (Throwable t) {
            log("打开失败：" + t);
        }
    }

    private void notifyTest() {
        SyncEngine.get(this).notifyTest(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                log("手表通知 → " + msg + (ok ? "（请查看手环是否弹出通知）" : ""));
            }
        });
    }

    private void sendRaw() {
        final String json = inputView.getText().toString();
        if (TextUtils.isEmpty(json)) {
            showResult(2, "报文为空");
            return;
        }
        log(">> TX  " + json);
        showResult(0, "已发送 · 等待回包…");
        SyncEngine.get(this).send(json, new SyncEngine.Reply() {
            @Override public void onReply(String text) {
                showResult(1, "<< RX  len=" + text.length());
                log("<< RX  len=" + text.length());
                log("   " + (text.length() > 500 ? text.substring(0, 500) + " …" : text));
            }
            @Override public void onTimeout(String hint) {
                showResult(2, hint);
                log("!! " + hint);
            }
            @Override public void onError(String msg) {
                showResult(3, msg);
                log("!! " + msg);
            }
        });
    }

    /** 时间戳走 ACCENT，[OK] 绿 / [FAIL]·!! 红，其余 MUTED */
    private void log(String s) {
        String ts = TS.format(new Date());
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(ts);
        sb.setSpan(new ForegroundColorSpan(Ui.ACCENT), 0, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");
        int p0 = sb.length();
        sb.append(s);
        int c = Ui.MUTED;
        if (s.startsWith("[OK]")) {
            c = Ui.OK;
        } else if (s.startsWith("[FAIL]") || s.startsWith("!!")) {
            c = Ui.ERR;
        }
        sb.setSpan(new ForegroundColorSpan(c), p0, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("\n");
        logView.append(sb);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
    }

    // ======================= Countdown Unit Tests =======================
    // These tests verify the countdown logic. They can be called via:
    //   adb shell am start -n com.application.watch.classschedule/.DebugActivity \
    //     --es test countdown_all

    /** Run all countdown tests: invoked via intent extra "test" = "countdown_all" */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String test = intent != null ? intent.getStringExtra("test") : null;
        if ("countdown_all".equals(test)) {
            log("=== Countdown Unit Tests ===");
            testCountdownSetAndRemaining();
            testCountdownCancel();
            testCountdownFired();
            testCountdownMinutesEdgeCases();
            log("=== All countdown tests finished ===");
        }
    }

    private void testCountdownSetAndRemaining() {
        CommandRouter.cancelCountdown(this);
        long before = System.currentTimeMillis();

        CommandRouter.countdown(this, 5);
        long remaining = CommandRouter.countdownRemaining(this);
        boolean ok = remaining > 0 && remaining <= 300;
        log("  testSetAndRemaining: " + (ok ? "PASS" : "FAIL")
                + " (remaining=" + remaining + "s, expected 0<r<=300)");

        // Clean up
        CommandRouter.cancelCountdown(this);
    }

    private void testCountdownCancel() {
        CommandRouter.cancelCountdown(this);
        CommandRouter.countdown(this, 10);
        CommandRouter.cancelCountdown(this);

        long remaining = CommandRouter.countdownRemaining(this);
        boolean ok = remaining == -1;
        log("  testCancel: " + (ok ? "PASS" : "FAIL")
                + " (remaining=" + remaining + ", expected -1)");
    }

    private void testCountdownFired() {
        CommandRouter.cancelCountdown(this);

        // Simulate a countdown that has already ended
        // store end time in the past
        getSharedPreferences("toolbox", MODE_PRIVATE).edit()
                .putLong("countdown_end", System.currentTimeMillis() - 60_000)
                .putBoolean("countdown_fired", true)
                .apply();

        boolean fired = CommandRouter.countdownFired(this);
        boolean ok = fired;
        log("  testFiredFlag: " + (ok ? "PASS" : "FAIL")
                + " (fired=" + fired + ", expected true)");

        // Dismiss and verify
        CommandRouter.dismissCountdownFired(this);
        boolean afterDismiss = CommandRouter.countdownFired(this);
        boolean dismissedOk = !afterDismiss;
        log("  testDismissFired: " + (dismissedOk ? "PASS" : "FAIL")
                + " (fired after dismiss=" + afterDismiss + ", expected false)");

        // Clean up
        CommandRouter.cancelCountdown(this);
    }

    private void testCountdownMinutesEdgeCases() {
        CommandRouter.cancelCountdown(this);

        // Test 1 minute
        CommandRouter.countdown(this, 1);
        long rem1 = CommandRouter.countdownRemaining(this);
        boolean ok1 = rem1 > 0 && rem1 <= 60;
        log("  test1min: " + (ok1 ? "PASS" : "FAIL") + " (remaining=" + rem1 + "s)");
        CommandRouter.cancelCountdown(this);

        // Test 30 minutes
        CommandRouter.countdown(this, 30);
        long rem30 = CommandRouter.countdownRemaining(this);
        boolean ok30 = rem30 > 0 && rem30 <= 1800;
        log("  test30min: " + (ok30 ? "PASS" : "FAIL") + " (remaining=" + rem30 + "s)");
        CommandRouter.cancelCountdown(this);
    }
}
