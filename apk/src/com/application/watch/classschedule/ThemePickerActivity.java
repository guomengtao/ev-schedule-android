package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 主题外观页（独立页面，从设置进入）。
 *
 * ★ 10 套主题静态内置，**不连手环也能换**：点卡片 → 本机立即换装（recreate 全页重着色）。
 * ★ 卡片是迷你预览：用该主题的 bg/accent 实时画出来的，所见即所得。
 * ★ 「跟随手环」开关：开启后手机主题跟手环走（手环主题优先于本地选择）。
 * ★ 「同步到手环」：把当前主题写回手环（需已连接）。
 *
 * 主题源优先级：跟随手环（开启且已同步）> 本地选择 > 系统深浅色默认。
 */
public class ThemePickerActivity extends Activity {

    private int lastThemeVersion = 0;

    private TextView statusView, resultView;
    private Switch followSw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "主题外观"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "10 套主题 · 选择后整个 App 立即生效，无需手环",
                11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        // ======================= 当前状态 + 跟随开关 =======================
        LinearLayout top = Ui.card(this);
        statusView = Ui.text(this, "", 13f, Ui.TEXT, true);
        top.addView(statusView);
        top.addView(Ui.space(this, 8));
        followSw = new Switch(this);
        followSw.setText("跟随手环（手环换主题，手机自动跟）");
        followSw.setTextSize(12.5f);
        followSw.setTextColor(Ui.TEXT);
        followSw.setChecked(WatchAppearance.followEnabled(this));
        followSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                WatchAppearance.setFollow(ThemePickerActivity.this, isChecked);
                recreate(); // 整页按新模式立即重新着色
            }
        });
        top.addView(followSw);
        top.addView(Ui.space(this, 8));
        top.addView(Ui.button(this, "把当前主题同步到手环", false, new View.OnClickListener() {
            @Override public void onClick(View v) { syncToWatch(); }
        }));
        root.addView(top);
        root.addView(Ui.space(this, 10));

        // ======================= 主题卡片网格（2 列 × 5 行） =======================
        for (int i = 0; i < WatchAppearance.THEMES.length; i += 2) {
            View a = themeCard(WatchAppearance.THEMES[i]);
            View b = i + 1 < WatchAppearance.THEMES.length
                    ? themeCard(WatchAppearance.THEMES[i + 1]) : new View(this);
            root.addView(Ui.grid(this, a, b));
        }
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "选择主题 = 独立模式（自动关闭跟随手环，可再打开）；"
                + "「默认」= 跟随系统深浅色"));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 6));

        refreshStatus();
        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/theme");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        refreshStatus();
    }

    // ======================= 渲染 =======================

    /** 当前主题来源说明（状态卡） */
    private String sourceText() {
        if (WatchAppearance.followEnabled(this)
                && WatchAppearance.themeId(this).length() > 0) {
            return "跟随手环 · " + WatchAppearance.themeName(WatchAppearance.themeId(this));
        }
        String local = WatchAppearance.localTheme(this);
        if (local.length() > 0) {
            return "本地 · " + WatchAppearance.themeName(local);
        }
        return "默认（跟随系统深浅色）";
    }

    private void refreshStatus() {
        statusView.setText("当前：" + sourceText());
    }

    /**
     * 单张主题卡：迷你预览（主题 bg + 主色圆点 + 两条文字线）+ 名称 + 选中标记。
     * 选中态 = 主色描边 + 「✓ 使用中」。
     */
    private View themeCard(final String[] t) {
        final String id = t[0];
        final String name = t[1];
        int[] pal = WatchAppearance.palette(id); // {bg, card, card2, line, text, muted, accent}
        boolean selected = isActive(id);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8));
        card.setBackground(Ui.round(Ui.CARD, 12, selected ? Ui.ACCENT : Ui.LINE, this));
        card.setClickable(true);

        // —— 迷你预览（该主题的真实 bg/accent）——
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setBackground(Ui.round(pal[0], 10, 0, this));
        preview.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));

        // 主色圆点 + 一条“标题线”
        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        dot.setBackground(Ui.round(pal[6], 7, 0, this));
        line1.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
        TextView bar1 = new TextView(this);
        bar1.setBackground(Ui.round(WatchAppearance.mix(pal[0], pal[4], 0.55f), 3, 0, this));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 6));
        l1.setMargins(Ui.dp(this, 8), 0, 0, 0);
        line1.addView(bar1, l1);
        preview.addView(line1);

        // 两条“内容线”（粗细不一，模拟课表条目）
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                Ui.dp(this, 64), Ui.dp(this, 5));
        lp2.setMargins(0, Ui.dp(this, 7), 0, 0);
        TextView bar2 = new TextView(this);
        bar2.setBackground(Ui.round(WatchAppearance.mix(pal[0], pal[4], 0.30f), 3, 0, this));
        preview.addView(bar2, lp2);

        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 5));
        lp3.setMargins(0, Ui.dp(this, 5), 0, 0);
        TextView bar3 = new TextView(this);
        bar3.setBackground(Ui.round(WatchAppearance.mix(pal[0], pal[4], 0.30f), 3, 0, this));
        preview.addView(bar3, lp3);

        card.addView(preview);

        // —— 名称 + 选中标记 ——
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setPadding(0, Ui.dp(this, 7), 0, 0);
        TextView label = Ui.text(this, name, 12f, selected ? Ui.ACCENT : Ui.TEXT, selected);
        nameRow.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            TextView check = Ui.text(this, "✓ 使用中", 10f, Ui.ACCENT, true);
            nameRow.addView(check);
        }
        card.addView(nameRow);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(id, name); }
        });
        return card;
    }

    /** 该 id 是否为当前生效主题（含跟随手环命中的情况） */
    private boolean isActive(String id) {
        if (WatchAppearance.followEnabled(this)
                && WatchAppearance.themeId(this).length() > 0) {
            return WatchAppearance.themeId(this).equals(id);
        }
        String local = WatchAppearance.localTheme(this);
        return local.length() > 0 ? local.equals(id) : false;
    }

    // ======================= 交互 =======================

    /** 选择主题：本机立即生效；手动选择 = 独立模式（自动关闭跟随） */
    private void pick(String id, String name) {
        WatchAppearance.setLocalTheme(this, id);
        if (WatchAppearance.followEnabled(this)) {
            WatchAppearance.setFollow(this, false);
        }
        recreate(); // 整页立刻换装（所有页面下一次进入也会应用）
    }

    /** 把当前生效主题写回手环（需已连接） */
    private void syncToWatch() {
        String cur;
        if (WatchAppearance.followEnabled(this)
                && WatchAppearance.themeId(this).length() > 0) {
            cur = WatchAppearance.themeId(this);
        } else {
            cur = WatchAppearance.localTheme(this);
        }
        if (cur == null || cur.length() == 0) {
            resultView.setText("当前是默认主题，没有可写入手环的选项；请先选一套主题");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        if (!SyncEngine.get(this).hasNode()) {
            resultView.setText("未连接手环，请先回首页连接");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("appTheme", cur);
            JSONObject req = new JSONObject();
            req.put("action", "update_settings");
            req.put("payload", payload);
            resultView.setText("正在写入手环…");
            resultView.setTextColor(Ui.MUTED);
            SyncEngine.get(this).send(req.toString(), new SyncEngine.Reply() {
                @Override public void onReply(String json) {
                    try {
                        JSONObject o = new JSONObject(json);
                        if (o.optBoolean("ok", false)) {
                            resultView.setText("已同步到手环，主题立即生效");
                            resultView.setTextColor(Ui.OK);
                        } else {
                            resultView.setText("手环拒绝：" + o.optString("reason"));
                            resultView.setTextColor(Ui.ERR);
                        }
                    } catch (Throwable t) {
                        resultView.setText("回包无法解析");
                        resultView.setTextColor(Ui.ERR);
                    }
                }
                @Override public void onTimeout(String hint) {
                    resultView.setText(hint);
                    resultView.setTextColor(Ui.WARN);
                }
                @Override public void onError(String msg) {
                    resultView.setText("写入失败：" + msg);
                    resultView.setTextColor(Ui.ERR);
                }
            });
        } catch (Throwable t) {
            resultView.setText("构造报文失败");
            resultView.setTextColor(Ui.ERR);
        }
    }
}