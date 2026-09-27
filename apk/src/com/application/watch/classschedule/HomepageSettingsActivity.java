package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首页设置（与手环 EV 主项目「首页设置」同字段）。
 *
 * 读：{"action":"export"} → data.homepage（homepage_settings 对象）+ data.baseFontSize
 * 写：{"action":"update_settings","payload":{"homepage":{...},"baseFontSize":N}}
 */
public class HomepageSettingsActivity extends Activity {

    private static final int[] FONT_STEPS = {28, 36, 48, 62, 76};

    /** 字段 key → 中文标签（顺序即展示顺序） */
    private static final String[][] TOGGLES = {
            {"showQuickAdd", "快速添加"},
            {"showStatusBar", "课程提醒"},
            {"showDayNavZong", "总课程"},
            {"showDayNavJin", "今日"},
            {"showDayNavMing", "明日"},
            {"showCustomContent", "自定义"},
            {"showTime", "时钟"},
            {"showPinnedBar", "钉首页"},
    };

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private TextView fontView, resultView;
    private int baseFontSize = 48;
    private JSONObject homepage = new JSONObject();
    private boolean loaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "首页设置"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "与手环上的首页显示保持一致", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        LinearLayout toggleCard = Ui.card(this);
        for (String[] row : TOGGLES) {
            Switch sw = new Switch(this);
            sw.setText(row[1]);
            sw.setTextSize(13.5f);
            sw.setTextColor(Ui.TEXT);
            sw.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            switches.put(row[0], sw);
            toggleCard.addView(sw);
        }
        root.addView(toggleCard);
        root.addView(Ui.space(this, 10));

        LinearLayout fontCard = Ui.card(this);
        fontCard.addView(Ui.text(this, "课程字号（仅首页课程卡片）", 12.5f, Ui.TEXT, true));
        fontCard.addView(Ui.space(this, 6));
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.addView(Ui.button(this, "−", false, new View.OnClickListener() {
            @Override public void onClick(View v) { stepFont(-1); }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fontView = Ui.text(this, baseFontSize + "px", 15f, Ui.ACCENT, true);
        fontView.setGravity(android.view.Gravity.CENTER);
        fontRow.addView(fontView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fontRow.addView(Ui.button(this, "+", false, new View.OnClickListener() {
            @Override public void onClick(View v) { stepFont(1); }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        fontCard.addView(fontRow);
        root.addView(fontCard);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 10));

        root.addView(Ui.grid(this,
                Ui.button(this, "保存到手环", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { save(); }
                }),
                Ui.button(this, "重新读取", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { load(); }
                })));
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "保存会写入手环的首页设置，手环首页立即生效"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        setEnabled(false);
        load();
    }

    private void setEnabled(boolean on) {
        for (Switch s : switches.values()) {
            s.setEnabled(on);
        }
    }

    // ======================= 读 =======================

    private void load() {
        if (!SyncEngine.get(this).hasNode()) {
            resultView.setText("未连接手环，请先回首页连接");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在读取手环设置…");
        resultView.setTextColor(Ui.MUTED);
        setEnabled(false);
        SyncEngine.get(this).export(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    JSONObject h = (d == null) ? null : d.optJSONObject("homepage");
                    homepage = (h == null) ? new JSONObject() : h;
                    applyHomepage();
                    if (d != null) {
                        baseFontSize = d.optInt("baseFontSize", 48);
                    }
                    if (baseFontSize < 20 || baseFontSize > 76) {
                        baseFontSize = 48;
                    }
                    fontView.setText(baseFontSize + "px");
                    loaded = true;
                    setEnabled(true);
                    resultView.setText("已读取当前设置");
                    resultView.setTextColor(Ui.OK);
                } catch (Throwable t) {
                    resultView.setText("读取失败：回包无法解析");
                    resultView.setTextColor(Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                resultView.setText(hint);
                resultView.setTextColor(Ui.ERR);
            }
            @Override public void onError(String msg) {
                resultView.setText("读取失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }

    private void applyHomepage() {
        for (String[] row : TOGGLES) {
            Switch sw = switches.get(row[0]);
            if (sw == null) {
                continue;
            }
            // 手环没写过的字段默认 true（保持与主项目默认一致）
            sw.setChecked(homepage.optBoolean(row[0], true));
        }
    }

    private void stepFont(int dir) {
        int idx = 2;
        for (int i = 0; i < FONT_STEPS.length; i++) {
            if (FONT_STEPS[i] == baseFontSize) {
                idx = i;
                break;
            }
        }
        idx += dir;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= FONT_STEPS.length) {
            idx = FONT_STEPS.length - 1;
        }
        baseFontSize = FONT_STEPS[idx];
        fontView.setText(baseFontSize + "px");
    }

    // ======================= 写 =======================

    private void save() {
        if (!loaded) {
            resultView.setText("还没有读到设置，先点「重新读取」");
            return;
        }
        JSONObject home = new JSONObject();
        try {
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                home.put(e.getKey(), e.getValue().isChecked());
            }
            JSONObject payload = new JSONObject();
            payload.put("homepage", home);
            payload.put("baseFontSize", baseFontSize);
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
                            resultView.setText("已保存，手环首页设置已更新");
                            resultView.setTextColor(Ui.OK);
                        } else {
                            resultView.setText("手环拒绝：" + o.optString("reason"));
                            resultView.setTextColor(Ui.ERR);
                        }
                    } catch (Throwable t) {
                        resultView.setText("回包无法解析：" + json);
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
            resultView.setText("构造报文失败：" + t);
            resultView.setTextColor(Ui.ERR);
        }
    }
}
