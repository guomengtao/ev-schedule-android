package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
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

    private int lastThemeVersion = 0;

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

    // ---- 模板与主题（与手环 EV 同源；id 必须与手环侧一致） ----
    /** 首页模板（手环 index.ux HOMEPAGE_TEMPLATES） */
    private static final String[][] HOME_TPLS = {
            {"default", "默认标题"}, {"accent-title", "强调标题"}, {"soft-title", "柔和标题"}};
    /** 周视图模板（手环 template-picker.ux TEMPLATES） */
    private static final String[][] WEEK_TPLS = {
            {"minimal-char", "极简·单字"}, {"minimal-en", "极简·英文缩写"},
            {"standard-block", "标准块"}, {"compact-grid", "紧凑网格"},
            {"color-pastel", "柔和色彩"}};
    /** 当前值；null = 手环未上报（EV 版本较旧），此时不写该字段以免误改 */
    private String homeTpl, weekTpl, appTheme;
    private Button homeBtn, weekBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 未连接也先显示值；主题色优先级与 Ui.applyTheme 一致：跟随手环镜像 > 本地选择
        homeTpl = emptyToNull(WatchAppearance.homeTpl(this));
        weekTpl = emptyToNull(WatchAppearance.weekTpl(this));
        String mirrored = emptyToNull(WatchAppearance.themeId(this));
        String localTheme = emptyToNull(WatchAppearance.localTheme(this));
        appTheme = (WatchAppearance.followEnabled(this) && mirrored != null) ? mirrored
                : (localTheme != null ? localTheme : mirrored);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "首页设置"));
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

        // ======================= 模板（读手环回显，保存时写回） =======================
        // 主题色已独立为「主题外观」页（设置 → 主题外观）
        LinearLayout apCard = Ui.card(this);
        apCard.addView(Ui.text(this, "模板（保存时写回手环）", 12.5f, Ui.TEXT, true));
        apCard.addView(Ui.space(this, 8));
        homeBtn = Ui.button(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick("首页模板", HOME_TPLS, homeTpl, new Picker() {
                    @Override public void onPick(String id, String label) {
                        homeTpl = id;
                        updateAppearanceButtons();
                    }
                });
            }
        });
        apCard.addView(homeBtn);
        apCard.addView(Ui.space(this, 6));
        weekBtn = Ui.button(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick("周视图模板", WEEK_TPLS, weekTpl, new Picker() {
                    @Override public void onPick(String id, String label) {
                        weekTpl = id;
                        updateAppearanceButtons();
                    }
                });
            }
        });
        apCard.addView(weekBtn);
        apCard.addView(Ui.space(this, 6));
        homeBtn.setText("首页模板：（读取中）");
        weekBtn.setText("周视图模板：（读取中）");
        apCard.addView(Ui.space(this, 6));
        apCard.addView(Ui.button(this, "立即同步手环设置", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                resultView.setText("正在读取手环外观设置…");
                resultView.setTextColor(Ui.MUTED);
                load();
            }
        }));
        apCard.addView(Ui.mono(this,
                "模板写回手环后生效；手机端主题在「设置 → 主题外观」\n"
                        + "若显示「手环未上报」表示手环 EV 版本较旧，升级手环端后可用"));
        root.addView(apCard);
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
                        // 模板与主题回显（旧版手环不返回这些字段 → 置 null，保存时跳过）
                        homeTpl = d.has("homepageTemplate") ? d.optString("homepageTemplate") : null;
                        weekTpl = d.has("weekviewTemplate") ? d.optString("weekviewTemplate") : null;
                        appTheme = d.has("appTheme") ? d.optString("appTheme") : null;
                        updateAppearanceButtons();
                    }
                    if (baseFontSize < 20 || baseFontSize > 76) {
                        baseFontSize = 48;
                    }
                    fontView.setText(baseFontSize + "px");
                    loaded = true;
                    setEnabled(true);
                    resultView.setText("已读取当前设置");
                    resultView.setTextColor(Ui.OK);
                    // 更新手环外观镜像（跟随手环模式的数据源；主题页展示也用它）
                    WatchAppearance.save(HomepageSettingsActivity.this, appTheme, homeTpl, weekTpl);
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
            // 模板与主题（手环未上报的项不写，避免误改）
            if (homeTpl != null && homeTpl.length() > 0) {
                payload.put("homepageTemplate", homeTpl);
            }
            if (weekTpl != null && weekTpl.length() > 0) {
                payload.put("weekviewTemplate", weekTpl);
            }
            if (appTheme != null && appTheme.length() > 0) {
                payload.put("appTheme", appTheme);
            }
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

    // ======================= 模板与主题 =======================

    private interface Picker {
        void onPick(String id, String label);
    }

    /** 通用单选对话框（选项 id 与手环端同源） */
    private void pick(String title, final String[][] opts, String current, final Picker cb) {
        try {
            String[] names = new String[opts.length];
            int checked = -1;
            for (int i = 0; i < opts.length; i++) {
                names[i] = opts[i][1];
                if (opts[i][0].equals(current)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            cb.onPick(opts[which][0], opts[which][1]);
                            d.dismiss();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private static String labelOf(String[][] opts, String id) {
        if (id == null || id.length() == 0) {
            return "手环未上报";
        }
        for (String[] o : opts) {
            if (o[0].equals(id)) {
                return o[1];
            }
        }
        return id;
    }

    private void updateAppearanceButtons() {
        homeBtn.setText("首页模板：" + labelOf(HOME_TPLS, homeTpl));
        weekBtn.setText("周视图模板：" + labelOf(WEEK_TPLS, weekTpl));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.length() == 0) ? null : s;
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
}