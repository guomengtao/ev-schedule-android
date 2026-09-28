package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 手环页（底栏第 3 tab，设备作用域）：连接管理 + 昵称 + 首页设置 + 留言入口。
 *
 * 与「设置」页的分工：凡是「写回手环才生效 / 设备仅有的」配置都在这里；
 * App 自身行为（后台常驻、上课提醒、主题、高级版、打赏、更新）在「设置」页。
 * 多手环：连接与记忆逻辑在 SyncEngine（preferredNodeId），这里提供查看与重置。
 */
public class BandActivity extends Activity {

    private int lastThemeVersion = 0;

    private TextView currentView, resultView, devStatusView, heroNameView, heroInfoView;
    private EditText nickView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "穿戴设备"));
        root.addView(Ui.space(this, 12));

        // ===== 设备头部（参考小米运动健康样式）：左表盘视觉 + 右名称▼/状态/信息 + 同步胶囊 =====
        LinearLayout hero = Ui.card(this);
        hero.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        LinearLayout hRow = new LinearLayout(this);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        hRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 左：手环视觉（填充手表图标 + 浅色圆角底）
        LinearLayout vis = new LinearLayout(this);
        vis.setGravity(android.view.Gravity.CENTER);
        vis.setBackground(Ui.round(Ui.CARD2, 24, 0, this));
        ImageView watchIv = new ImageView(this);
        watchIv.setImageResource(R.drawable.ic_tab_watch_filled);
        watchIv.setColorFilter(Ui.ACCENT);
        vis.addView(watchIv, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        hRow.addView(vis, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 120)));

        // 右：名称 ▼ / 状态 / 信息
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 16), 0, 0, 0);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heroNameView = Ui.text(this, "未连接手环", 17f, Ui.TEXT, true);
        nameRow.addView(heroNameView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView caret = Ui.text(this, "▼", 11f, Ui.MUTED, false);
        caret.setPadding(Ui.dp(this, 6), 0, 0, 0);
        caret.setClickable(true);
        caret.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // ▼ = 清除设备记忆并重连（多手环时会重新询问选哪台）
                SyncEngine.get(BandActivity.this).setPreferredNodeId("");
                SyncEngine.get(BandActivity.this).autoReconnect();
                resultView.setText("已清除设备记忆，正在重新连接…");
                resultView.setTextColor(Ui.OK);
                updateHero();
            }
        });
        nameRow.addView(caret);
        info.addView(nameRow);

        devStatusView = Ui.text(this, "未连接", 13.5f, Ui.ERR, false);
        devStatusView.setPadding(0, Ui.dp(this, 4), 0, 0);
        info.addView(devStatusView);

        heroInfoView = Ui.text(this, "打开小米运动健康连接手环", 12f, Ui.MUTED, false);
        heroInfoView.setPadding(0, Ui.dp(this, 2), 0, 0);
        info.addView(heroInfoView);

        hRow.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(hRow);

        hero.addView(Ui.space(this, 14));
        // 同步胶囊（浅主题色底 + 主题色字），对齐右侧信息列
        android.widget.Button syncPill = Ui.button(this, "同步", false, new View.OnClickListener() {
            @Override public void onClick(View v) { manualSync(); }
        });
        syncPill.setTextSize(15f);
        syncPill.setTextColor(Ui.ACCENT);
        syncPill.setBackground(Ui.round((Ui.ACCENT & 0x00FFFFFF) | 0x2E000000, 22, 0, this));
        syncPill.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                Ui.dp(this, 104), Ui.dp(this, 44));
        sp.leftMargin = Ui.dp(this, 120);
        hero.addView(syncPill, sp);
        root.addView(hero);
        root.addView(Ui.space(this, 10));
        updateHero();

        // ===== 快捷操作（原首页设备卡 2×2 搬家至此：连接相关动作集中在设备页） =====
        LinearLayout quickCard = Ui.card(this);
        // 标题行带 ⌚ 图标块（自首页设备卡迁来的视觉元素，颜色随主题）
        LinearLayout quickHead = new LinearLayout(this);
        quickHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView qIcon = Ui.text(this, "⌚", 20f, Ui.ACCENT, false);
        qIcon.setGravity(android.view.Gravity.CENTER);
        qIcon.setBackground(Ui.round((Ui.ACCENT & 0x00FFFFFF) | 0x1E000000, 12, 0, this));
        quickHead.addView(qIcon, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        TextView qTitle = Ui.text(this, "快捷操作", 12.5f, Ui.TEXT, true);
        qTitle.setPadding(Ui.dp(this, 10), 0, 0, 0);
        quickHead.addView(qTitle);
        quickCard.addView(quickHead);
        quickCard.addView(Ui.space(this, 8));
        quickCard.addView(Ui.grid(this,
                Ui.button(this, "手环课程同步", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { manualSync(); }
                }),
                Ui.button(this, "呼叫手环", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { callBand(); }
                })));
        quickCard.addView(Ui.space(this, 8));
        quickCard.addView(Ui.grid(this,
                Ui.button(this, "发消息给手环", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { quickMessage(); }
                }),
                Ui.button(this, "连接手环", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { startConnect(); }
                })));
        root.addView(quickCard);
        root.addView(Ui.space(this, 10));

        // ===== 昵称（写回手环） =====
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "当前昵称", 12f, Ui.MUTED, false));
        currentView = Ui.text(this, "—", 18f, Ui.TEXT, true);
        currentView.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(currentView);
        root.addView(card);
        root.addView(Ui.space(this, 10));

        LinearLayout edit = Ui.card(this);
        edit.addView(Ui.text(this, "修改昵称（会写入手环）", 12.5f, Ui.TEXT, true));
        edit.addView(Ui.space(this, 8));
        nickView = new EditText(this);
        nickView.setTextSize(14f);
        nickView.setTextColor(Ui.TEXT);
        nickView.setHint("请输入昵称");
        edit.addView(nickView);
        edit.addView(Ui.space(this, 10));
        edit.addView(Ui.button(this, "保存到手环", true, new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        }));
        root.addView(edit);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        root.addView(resultView);

        // ===== 首页设置（模板/字号，写回手环） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "首页设置", "显示开关 / 模板 / 字号（写回手环生效）", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, HomepageSettingsActivity.class));
                    }
                }));

        // ===== 工具箱（找手机/状态/静音/倒计时） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "工具箱", "找手机 / 手机状态 / 静音 / 倒计时", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, ToolboxActivity.class));
                    }
                }));

        // ===== 留言（原底栏入口取消后的固定去处） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "留言", "给手环发消息 / 查看手环发来的留言", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, MessageActivity.class));
                    }
                }));

        // ===== 连接调试：连不上手环时在这里分步排查（主入口放本页，紧挨连接管理） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "连接调试", "四步逐步执行，看卡在哪一步", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, DebugActivity.class));
                    }
                }));

        refresh();
        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/band");
    }

    // ======================= 快捷操作（自首页设备卡迁入） =======================

    /** 从手环拉当前课表写入本地多课表存储（先问课表名，失败用兜底名）。 */
    private void manualSync() {
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            resultView.setText("手环未连接，先连接手环");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在从手环读取课表…");
        resultView.setTextColor(Ui.ACCENT);
        e.sendWake("{\"action\":\"export\"}", new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    final org.json.JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        resultView.setText("手环回包里没课表数据");
                        resultView.setTextColor(Ui.WARN);
                        return;
                    }
                    e.lastExportJson = json;
                    e.listSchedules(new SyncEngine.Reply() {
                        @Override public void onReply(String j2) {
                            String name = "";
                            try {
                                JSONObject o2 = new JSONObject(j2);
                                org.json.JSONArray names = o2.optJSONArray("names");
                                int cur = o2.optInt("current", 0);
                                if (names != null && cur >= 0 && cur < names.length()) {
                                    name = names.optString(cur);
                                }
                            } catch (Throwable ignored) {
                            }
                            ScheduleStore.upsertFromWatch(BandActivity.this, name, sch);
                            resultView.setText("课表已同步 ✓" + (name.length() > 0 ? "（" + name + "）" : ""));
                            resultView.setTextColor(Ui.OK);
                        }
                        @Override public void onTimeout(String h) { store(sch); }
                        @Override public void onError(String m) { store(sch); }
                    });
                } catch (Throwable t) {
                    resultView.setText("同步失败：回包无法解析");
                    resultView.setTextColor(Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                resultView.setText("读取超时：" + hint);
                resultView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                resultView.setText("同步失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }

    private void store(org.json.JSONArray sch) {
        ScheduleStore.upsertFromWatch(BandActivity.this, "", sch);
        resultView.setText("课表已同步 ✓（课表名未取到，用默认名）");
        resultView.setTextColor(Ui.OK);
    }

    /** 呼叫手环：action=call（响铃+震动+亮屏+通知）。 */
    private void callBand() {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            resultView.setText("手环未连接，无法呼叫");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在呼叫手环…");
        resultView.setTextColor(Ui.ACCENT);
        e.sendWake("{\"action\":\"call\",\"text\":\"请查看手机\"}", new SyncEngine.Reply() {
            @Override public void onReply(String r) {
                resultView.setText("呼叫已送达 ✓ 看看手腕吧");
                resultView.setTextColor(Ui.OK);
            }
            @Override public void onTimeout(String h) {
                resultView.setText("手环无回应。可到「连接调试」拉起 EV 后重试");
                resultView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                resultView.setText("发送失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }

    /** 快速留言：入队，连上后自动补发（与留言页同一份存储）。 */
    private void quickMessage() {
        final EditText input = new EditText(this);
        input.setHint("写一条留言给手环…");
        input.setTextSize(14f);
        new android.app.AlertDialog.Builder(this)
                .setTitle("发消息给手环")
                .setView(input)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String text = input.getText().toString().trim();
                        if (text.length() == 0) {
                            return;
                        }
                        MessageActivity.enqueueOutgoing(BandActivity.this, text);
                        resultView.setText("留言已入队（连上手环后自动补发）");
                        resultView.setTextColor(Ui.OK);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 四步连接，进度逐行写进结果区。 */
    private void startConnect() {
        final SyncEngine e = SyncEngine.get(this);
        resultView.setText("连接中…");
        resultView.setTextColor(Ui.ACCENT);
        e.connect(new SyncEngine.Steps() {
            @Override public void onUpdate(String[] labels, int[] states, String[] details) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < labels.length; i++) {
                    String mark = states[i] == SyncEngine.OK ? "✅"
                            : states[i] == SyncEngine.FAIL ? "❌"
                            : states[i] == SyncEngine.RUNNING ? "⏳" : "·";
                    sb.append(mark).append(' ').append(labels[i]);
                    if (details[i] != null && details[i].length() > 0) {
                        sb.append("：").append(details[i]);
                    }
                    sb.append('\n');
                }
                resultView.setText(sb.toString().trim());
                resultView.setTextColor(Ui.TEXT);
            }
            @Override public void onFinish(boolean ok, String hint) {
                if (ok) {
                    resultView.setText("已连接 " + e.deviceName + " · v" + e.versionName
                            + " · 课表 " + e.courseCount + " 节");
                    resultView.setTextColor(Ui.OK);
                    updateHero();
                    refresh();
                } else {
                    resultView.setText("连接未完成：" + hint + "\n可到「连接调试」分步排查");
                    resultView.setTextColor(Ui.ERR);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        refresh();
        updateHero();
    }

    /** 头部设备卡按连接状态刷新（名称/状态/信息三行）。 */
    private void updateHero() {
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            String name = (e.deviceName == null || e.deviceName.length() == 0)
                    ? "手环" : e.deviceName;
            heroNameView.setText(name);
            devStatusView.setText("已连接");
            devStatusView.setTextColor(Ui.OK);
            heroInfoView.setText("EV " + e.versionName + " · 课表 " + e.courseCount + " 节");
            heroInfoView.setTextColor(Ui.MUTED);
        } else {
            heroNameView.setText("未连接手环");
            devStatusView.setText("未连接");
            devStatusView.setTextColor(Ui.ERR);
            heroInfoView.setText("打开小米运动健康连接手环，或点下方「连接手环」");
            heroInfoView.setTextColor(Ui.MUTED);
        }
    }

    private void refresh() {
        SyncEngine e = SyncEngine.get(this);
        String nick = e.nickname;
        currentView.setText(nick == null || nick.length() == 0 ? "（未知，先回首页连接）" : nick);
        if (nick != null && nick.length() > 0 && nickView.getText().length() == 0) {
            nickView.setText(nick);
        }
    }

    private void save() {
        String nick = nickView.getText().toString().trim();
        if (TextUtils.isEmpty(nick)) {
            resultView.setText("昵称不能为空");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        if (!SyncEngine.get(this).connected()) {
            resultView.setText("手环未连接：请先回首页连接手环");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在写入手环…");
        resultView.setTextColor(Ui.MUTED);
        SyncEngine.get(this).setNickname(nick, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        SyncEngine.get(BandActivity.this).nickname =
                                nickView.getText().toString().trim();
                        resultView.setText("已保存，手环首页昵称已更新");
                        resultView.setTextColor(Ui.OK);
                        refresh();
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
                resultView.setTextColor(Ui.ERR);
            }

            @Override public void onError(String msg) {
                resultView.setText("写入失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }
}
