package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 工具箱（子页）：手环遥控指令的手机端执行面板。
 *
 * 四个能力与 CommandRouter 完全一致——手机端点按钮 = 手环发 {"action":"cmd",...}，
 * 用于：① 不等手环侧发版即可先验证执行器；② 手环不在身边时的直达入口。
 * 手环侧协议：{"action":"cmd","type":"find_phone|phone_status|mute|countdown","minutes":N}
 */
public class ToolboxActivity extends Activity {

    private int lastThemeVersion = 0;
    private TextView findStatusView, muteStatusView, cdStatusView, statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "工具箱"));
        root.addView(Ui.space(this, 12));

        // ===== 找手机 =====
        LinearLayout findCard = Ui.card(this);
        findCard.addView(Ui.text(this, "找手机", 13.5f, Ui.TEXT, true));
        findStatusView = Ui.text(this, "手机响铃 30 秒（静音下也会响）", 11.5f, Ui.MUTED, false);
        findStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        findCard.addView(findStatusView);
        findCard.addView(Ui.grid(this,
                Ui.button(this, "开始响铃", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        CommandRouter.findPhone(ToolboxActivity.this);
                        findStatusView.setText("响铃中…点「停止」或等 30 秒自动停");
                        findStatusView.setTextColor(Ui.OK);
                    }
                }),
                Ui.button(this, "停止", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        CommandRouter.stopFindPhone(ToolboxActivity.this);
                        findStatusView.setText("已停止");
                        findStatusView.setTextColor(Ui.MUTED);
                    }
                })));
        root.addView(findCard);
        root.addView(Ui.space(this, 10));

        // ===== 静音切换 =====
        LinearLayout muteCard = Ui.card(this);
        muteCard.addView(Ui.text(this, "静音切换", 13.5f, Ui.TEXT, true));
        muteStatusView = Ui.text(this, currentRinger(), 11.5f, Ui.MUTED, false);
        muteStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        muteCard.addView(muteStatusView);
        muteCard.addView(Ui.button(this, "响铃 / 振动 切换", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String r = CommandRouter.toggleMute(ToolboxActivity.this);
                muteStatusView.setText(r);
                muteStatusView.setTextColor(Ui.OK);
            }
        }));
        root.addView(muteCard);
        root.addView(Ui.space(this, 10));

        // ===== 倒计时 =====
        LinearLayout cdCard = Ui.card(this);
        cdCard.addView(Ui.text(this, "倒计时", 13.5f, Ui.TEXT, true));
        cdStatusView = Ui.text(this, cdText(), 11.5f, Ui.MUTED, false);
        cdStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        cdCard.addView(cdStatusView);
        cdCard.addView(Ui.grid(this,
                Ui.button(this, "1 分钟", false, clickCd(1)),
                Ui.button(this, "5 分钟", false, clickCd(5))));
        cdCard.addView(Ui.space(this, 2));
        cdCard.addView(Ui.grid(this,
                Ui.button(this, "10 分钟", false, clickCd(10)),
                Ui.button(this, "30 分钟", false, clickCd(30))));
        root.addView(cdCard);
        root.addView(Ui.space(this, 10));

        // ===== 手机状态 =====
        LinearLayout stCard = Ui.card(this);
        stCard.addView(Ui.text(this, "手机状态", 13.5f, Ui.TEXT, true));
        statusView = Ui.text(this, CommandRouter.statusText(this), 11.5f, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        stCard.addView(statusView);
        stCard.addView(Ui.button(this, "刷新", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                statusView.setText(CommandRouter.statusText(ToolboxActivity.this));
            }
        }));
        root.addView(stCard);

        root.addView(Ui.space(this, 10));
        root.addView(Ui.mono(this, "手环侧 EV 工具箱菜单可远程触发同样指令"
                + "（{\"action\":\"cmd\",...}，待手环端发版）"));

        setContentView(Ui.wrapWithBottomBar(this, root, -1));
        Analytics.pageView(this, "/apk/toolbox");
    }

    private View.OnClickListener clickCd(final int minutes) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                CommandRouter.countdown(ToolboxActivity.this, minutes);
                cdStatusView.setText("已设置 " + minutes + " 分钟倒计时，到点响铃提醒");
                cdStatusView.setTextColor(Ui.OK);
            }
        };
    }

    private String currentRinger() {
        return "当前：" + CommandRouter.statusText(this);
    }

    private String cdText() {
        long left = CommandRouter.countdownRemaining(this);
        return left > 0 ? "进行中：还剩 " + (left / 60) + " 分 " + (left % 60) + " 秒"
                : "选择时长开始倒计时";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        cdStatusView.setText(cdText());
        statusView.setText(CommandRouter.statusText(this));
    }
}
