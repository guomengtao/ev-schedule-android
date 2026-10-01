package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 连接手环日志：记录每次连接尝试（型号 / 成功 / 失败 / 原因）。
 * 头部支持筛选——按“成功/失败”结果 + 按“手环型号”分别筛选。
 * 数据来自 {@link ConnLog}，纯本地展示，不参与埋点。
 */
public class ConnLogActivity extends Activity {

    private final SimpleDateFormat TSF =
            new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    private List<ConnLog.Entry> all;
    private LinearLayout resultRow;
    private LinearLayout modelBox;
    private LinearLayout listBox;
    private TextView empty;

    private int resultFilter;    // 0 全部 / 1 成功 / 2 失败
    private String modelFilter = ""; // 空 = 全部型号

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        all = ConnLog.list(this);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "连接日志"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "记录每次手环连接尝试（型号 / 成功 / 失败 / 原因）", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 8));

        // 顶部操作：刷新 / 清空
        root.addView(Ui.grid(this,
                Ui.button(this, "刷新", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        all = ConnLog.list(ConnLogActivity.this);
                        render();
                    }
                }),
                Ui.button(this, "清空记录", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ConnLog.clear(ConnLogActivity.this);
                        all = ConnLog.list(ConnLogActivity.this);
                        render();
                    }
                })));

        root.addView(Ui.space(this, 8));
        // 筛选栏：按结果
        root.addView(Ui.text(this, "按结果：", 12f, Ui.MUTED, false));
        resultRow = new LinearLayout(this);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(resultRow);

        root.addView(Ui.space(this, 6));
        // 筛选栏：按型号（型号可能较多，横向可滚）
        root.addView(Ui.text(this, "按型号：", 12f, Ui.MUTED, false));
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        modelBox = new LinearLayout(this);
        modelBox.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(modelBox, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(hsv);

        root.addView(Ui.space(this, 8));
        // 记录列表（唯一可伸缩区，weight=1）
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = Ui.text(this, "暂无记录", 12f, Ui.MUTED, false);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(empty);

        setContentView(root);
        render();
    }

    // ======================= 渲染 =======================

    private void render() {
        buildChips();
        listBox.removeAllViews();
        empty.setVisibility(View.GONE);
        int shown = 0;
        for (ConnLog.Entry e : all) {
            if (!match(e)) {
                continue;
            }
            listBox.addView(item(e));
            shown++;
        }
        if (shown == 0) {
            empty.setText(all.isEmpty()
                    ? "暂无记录。到调试页执行一次连接后，这里会记录。"
                    : "没有匹配的记录，换个筛选试试。");
            empty.setVisibility(View.VISIBLE);
        }
    }

    private boolean match(ConnLog.Entry e) {
        if (resultFilter == 1 && !e.ok) {
            return false;
        }
        if (resultFilter == 2 && e.ok) {
            return false;
        }
        return modelFilter.isEmpty() || modelFilter.equals(e.model);
    }

    private void buildChips() {
        resultRow.removeAllViews();
        resultRow.addView(chip("全部", resultFilter == 0, new Runnable() {
            @Override public void run() { resultFilter = 0; render(); }
        }));
        resultRow.addView(chip("成功", resultFilter == 1, new Runnable() {
            @Override public void run() { resultFilter = 1; render(); }
        }));
        resultRow.addView(chip("失败", resultFilter == 2, new Runnable() {
            @Override public void run() { resultFilter = 2; render(); }
        }));

        modelBox.removeAllViews();
        modelBox.addView(chip("全部型号", modelFilter.isEmpty(), new Runnable() {
            @Override public void run() { modelFilter = ""; render(); }
        }));
        for (String m : ConnLog.models(this)) {
            final String model = m;
            modelBox.addView(chip(m, m.equals(modelFilter), new Runnable() {
                @Override public void run() { modelFilter = model; render(); }
            }));
        }
    }

    private Button chip(String label, boolean sel, final Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setTextColor(sel ? 0xFFFFFFFF : Ui.MUTED);
        b.setBackground(sel
                ? Ui.round(Ui.ACCENT, 14, 0, this)
                : Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onClick != null) {
                    onClick.run();
                }
            }
        });
        return b;
    }

    private View item(ConnLog.Entry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.setBackground(Ui.round(Ui.CARD2, 12, 0, this));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(this, 8));
        card.setLayoutParams(lp);

        int okColor = e.ok ? Ui.OK : Ui.ERR;
        String mark = e.ok ? "✓ " : "✕ ";
        String model = e.model != null && e.model.length() > 0 ? e.model : "未知设备";
        card.addView(Ui.text(this, mark + model, 13f, okColor, true));

        String meta = TSF.format(new Date(e.ts));
        if (e.ok && e.versionName != null && e.versionName.length() > 0) {
            meta += " · EV " + e.versionName;
        }
        card.addView(Ui.text(this, meta, 11.5f, Ui.MUTED, false));
        if (e.ok) {
            card.addView(Ui.text(this, "连接成功", 11.5f, Ui.OK, false));
        } else {
            String reason = e.reason != null ? e.reason : "";
            String detail = reason.length() > 0 ? reason : "未知原因";
            if (e.step > 0) {
                detail = "第 " + e.step + " 步失败：" + detail;
            }
            card.addView(Ui.text(this, detail, 11.5f, okColor, false));
        }
        return card;
    }
}