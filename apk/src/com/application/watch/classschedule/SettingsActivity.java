package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页：显示当前昵称，并允许写回手环 */
public class SettingsActivity extends Activity {

    private TextView currentView, resultView, bgStatusView, devStatusView, remindStatusView;
    private EditText nickView;
    private Button remindToggleBtn, remindLeadBtn, remindPushBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "设置"));
        root.addView(Ui.space(this, 12));

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

        root.addView(Ui.space(this, 12));
        root.addView(Ui.row(this, "主题外观", "10 套主题即点即换 · 也可跟随手环", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, ThemePickerActivity.class));
                    }
                }));
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "首页设置", "显示开关 / 模板 / 字号", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, HomepageSettingsActivity.class));
                    }
                }));
        // 「高级版一键激活」依赖 EV 的 activate 动作（EvBox 工具箱暂无此动作）
        if (Variant.isEv(this)) {
            root.addView(Ui.space(this, 6));
            root.addView(Ui.row(this, "高级版", "4 位兑换码一键激活", Ui.TEXT,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            startActivity(new Intent(SettingsActivity.this, FastActivateActivity.class));
                        }
                    }));
        }
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "打赏支持", "爱发电 / 微信 / 支付宝", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DonateActivity.class));
                    }
                }));

        root.addView(Ui.space(this, 12));
        LinearLayout bgCard = Ui.card(this);
        bgCard.addView(Ui.text(this, "后台常驻提醒", 12.5f, Ui.TEXT, true));
        bgStatusView = Ui.text(this, bgText(), 11.5f, Ui.MUTED, false);
        bgStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        bgCard.addView(bgStatusView);
        bgCard.addView(Ui.grid(this,
                Ui.button(this, "切换开关", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleBg(); }
                }),
                Ui.button(this, "省电白名单", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, BatteryGuideActivity.class));
                    }
                })));
        root.addView(bgCard);

        root.addView(Ui.space(this, 10));
        LinearLayout devCard = Ui.card(this);
        devCard.addView(Ui.text(this, "手环设备", 12.5f, Ui.TEXT, true));
        devStatusView = Ui.text(this, devText(), 11.5f, Ui.MUTED, false);
        devStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        devCard.addView(devStatusView);
        devCard.addView(Ui.button(this, "重新选择设备", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                SyncEngine.get(SettingsActivity.this).setPreferredNodeId("");
                devStatusView.setText(devText());
                resultView.setText("已清除记忆，下次连接会重新询问");
                resultView.setTextColor(Ui.OK);
            }
        }));
        root.addView(devCard);

        // ======================= 上课提醒（本地闹钟 + 可选推手环） =======================
        root.addView(Ui.space(this, 10));
        LinearLayout remindCard = Ui.card(this);
        remindCard.addView(Ui.text(this, "上课提醒", 12.5f, Ui.TEXT, true));
        remindStatusView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        remindStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        remindCard.addView(remindStatusView);
        remindToggleBtn = Ui.button(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !Reminders.enabled(SettingsActivity.this);
                Reminders.setEnabled(SettingsActivity.this, on);
                refreshRemind();
                resultView.setText(!on ? "已关闭上课提醒"
                        : (Reminders.exactAllowed(SettingsActivity.this)
                                ? "上课提醒已开启"
                                : "已开启。未授予「闹钟和提醒」权限，可能有 ±1 分钟误差"));
                resultView.setTextColor(!on ? Ui.MUTED : Ui.OK);
            }
        });
        remindCard.addView(remindToggleBtn);
        remindCard.addView(Ui.space(this, 6));
        remindCard.addView(Ui.grid(this,
                remindLeadBtn = Ui.button(this, "", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int cur = Reminders.leadMinutes(SettingsActivity.this);
                        int idx = 0;
                        for (int i = 0; i < Reminders.LEAD_STEPS.length; i++) {
                            if (Reminders.LEAD_STEPS[i] == cur) {
                                idx = i;
                                break;
                            }
                        }
                        Reminders.setLeadMinutes(SettingsActivity.this,
                                Reminders.LEAD_STEPS[(idx + 1) % Reminders.LEAD_STEPS.length]);
                        refreshRemind();
                    }
                }),
                remindPushBtn = Ui.button(this, "", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Reminders.setPushWatch(SettingsActivity.this, !Reminders.pushWatch(SettingsActivity.this));
                        refreshRemind();
                    }
                })));
        remindCard.addView(Ui.space(this, 6));
        remindCard.addView(Ui.grid(this,
                Ui.button(this, "测试提醒", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Reminders.test(SettingsActivity.this);
                        resultView.setText("已发测试提醒（手机通知"
                                + (Reminders.pushWatch(SettingsActivity.this) ? " + 手环通知，手环需已连接" : "") + "）");
                        resultView.setTextColor(Ui.OK);
                    }
                }),
                Ui.button(this, "重排提醒", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Reminders.reschedule(SettingsActivity.this);
                        resultView.setText("已按最新课表重排提醒");
                        resultView.setTextColor(Ui.OK);
                    }
                })));
        remindCard.addView(Ui.space(this, 6));
        remindCard.addView(Ui.mono(this,
                "基于本地课表缓存 + 系统闹钟，不依赖手环连接；课表更新/开机后自动重排"));
        root.addView(remindCard);

        root.addView(Ui.space(this, 8));
        SyncEngine e = SyncEngine.get(this);
        root.addView(Ui.mono(this, "手环 " + (e.connected()
                ? e.deviceName + " · EV " + e.versionName : "未连接")));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.mono(this, "本机 v" + version()));

        root.addView(Ui.space(this, 6));

        refresh();
        refreshRemind();
        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/settings");
    }

    private String version() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ======================= 后台常驻提醒 =======================

    /** 当前记住的手环设备（多设备时连接不再随机挑） */
    private String devText() {
        SyncEngine e = SyncEngine.get(this);
        String id = e.preferredNodeId();
        if (id == null || id.length() == 0) {
            return "未指定：多台手环时会在连接时询问选哪台";
        }
        String name = (e.deviceName == null) ? "" : e.deviceName;
        return "已记住：" + (name.length() > 0 ? name + "  " : "") + id;
    }

    private String bgText() {
        return SyncService.enabled(this)
                ? "已开启：App 退到后台也能提醒新留言（会有一条常驻通知）"
                : "已关闭：仅在 App 打开时提醒";
    }

    private void toggleBg() {
        boolean on = !SyncService.enabled(this);
        SyncService.setEnabled(this, on);
        if (on) {
            SyncService.startIfEnabled(this);
            // 打开后台提醒后，顺手引导加省电白名单（否则服务仍可能被 ROM 清理）
            if (!isIgnoringBattery(this)) {
                resultView.setText("已开启。建议加省电白名单，否则后台仍可能被清理");
                resultView.setTextColor(Ui.WARN);
                startActivity(new Intent(SettingsActivity.this, BatteryGuideActivity.class));
            } else {
                resultView.setText("已开启后台常驻提醒");
                resultView.setTextColor(Ui.OK);
            }
        } else {
            SyncService.stop(this);
            resultView.setText("已关闭后台常驻提醒（仅在 App 打开时提醒）");
            resultView.setTextColor(Ui.MUTED);
        }
        if (bgStatusView != null) {
            bgStatusView.setText(bgText());
        }
    }

    private static boolean isIgnoringBattery(android.content.Context c) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) c.getSystemService(android.content.Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ======================= 上课提醒 =======================

    private String remindText() {
        if (!Reminders.enabled(this)) {
            return "已关闭";
        }
        String s = "已开启 · 提前 " + Reminders.leadMinutes(this) + " 分钟"
                + (Reminders.pushWatch(this) ? " · 同时推送到手环" : " · 仅手机通知");
        if (!Reminders.exactAllowed(this)) {
            s += "\n未授予「闹钟和提醒」权限，可能有 ±1 分钟误差";
        }
        return s;
    }

    private void refreshRemind() {
        remindStatusView.setText(remindText());
        remindToggleBtn.setText(Reminders.enabled(this) ? "关闭提醒" : "开启提醒");
        remindLeadBtn.setText("提前 " + Reminders.leadMinutes(this) + " 分钟");
        remindPushBtn.setText("推手环：" + (Reminders.pushWatch(this) ? "开" : "关"));
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
        resultView.setText("正在写入手环…");
        resultView.setTextColor(Ui.MUTED);
        SyncEngine.get(this).setNickname(nick, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        SyncEngine.get(SettingsActivity.this).nickname = nickView.getText().toString().trim();
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