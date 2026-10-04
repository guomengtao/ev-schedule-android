package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Toolbox (sub-page): phone-side execution panel for watch remote commands.
 *
 * Four capabilities mirror CommandRouter exactly — tapping a button on phone
 * equals the watch sending {"action":"cmd",...}, used for:
 *   1. validating executors without waiting for watch-side release;
 *   2. direct access when the watch isn't nearby.
 *
 * Watch-side protocol: {"action":"cmd","type":"find_phone|phone_status|mute|countdown","minutes":N}
 */
public class ToolboxActivity extends Activity {

    private int lastThemeVersion = 0;
    private TextView findStatusView, muteStatusView, cdStatusView, cdTimerView, statusView;
    private Button cdCancelBtn;
    private LinearLayout cdCard, cdFiredCard;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable tickRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "工具箱"));
        root.addView(Ui.space(this, 12));

        // ===== Find Phone =====
        LinearLayout findCard = Ui.card(this);
        findCard.addView(Ui.text(this, "找手机", 13.5f, Ui.TEXT, true));
        findStatusView = Ui.text(this, "手机响铃 30 秒（静音/勿扰也能响）", 11.5f, Ui.MUTED, false);
        findStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        findCard.addView(findStatusView);
        findCard.addView(Ui.grid(this,
                Ui.button(this, "开始响铃", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        CommandRouter.findPhone(ToolboxActivity.this);
                        findStatusView.setText("响铃中… 全屏弹窗可一键停止");
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

        // ===== Mute Toggle =====
        LinearLayout muteCard = Ui.card(this);
        muteCard.addView(Ui.text(this, "静音切换", 13.5f, Ui.TEXT, true));
        muteStatusView = Ui.text(this, currentRinger(), 11.5f, Ui.MUTED, false);
        muteStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        muteCard.addView(muteStatusView);
        muteCard.addView(Ui.button(this, "切换响铃 / 振动", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String r = CommandRouter.toggleMute(ToolboxActivity.this);
                muteStatusView.setText(r);
                muteStatusView.setTextColor(Ui.OK);
            }
        }));
        root.addView(muteCard);
        root.addView(Ui.space(this, 10));

        // ===== Countdown =====
        cdCard = Ui.card(this);
        cdCard.addView(Ui.text(this, "倒计时", 13.5f, Ui.TEXT, true));

        cdTimerView = Ui.text(this, "", 24f, Ui.ACCENT, true);
        cdTimerView.setGravity(Gravity.CENTER);
        cdTimerView.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        cdCard.addView(cdTimerView);

        cdStatusView = Ui.text(this, cdStatusText(), 11.5f, Ui.MUTED, false);
        cdStatusView.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        cdCard.addView(cdStatusView);

        cdCard.addView(Ui.grid(this,
                Ui.button(this, "1 分钟", false, clickCd(1)),
                Ui.button(this, "5 分钟", false, clickCd(5))));
        cdCard.addView(Ui.space(this, 2));
        cdCard.addView(Ui.grid(this,
                Ui.button(this, "10 分钟", false, clickCd(10)),
                Ui.button(this, "30 分钟", false, clickCd(30))));

        cdCancelBtn = Ui.button(this, "取消", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                CommandRouter.cancelCountdown(ToolboxActivity.this);
                stopTick();
                cdTimerView.setText("");
                cdStatusView.setText("选择时长开始倒计时");
                cdStatusView.setTextColor(Ui.MUTED);
                cdCancelBtn.setVisibility(View.GONE);
                dismissFiredCard();
            }
        });
        cdCancelBtn.setTextColor(Ui.ERR);
        cdCancelBtn.setVisibility(View.GONE);
        cdCard.addView(Ui.space(this, 2));
        cdCard.addView(cdCancelBtn);

        root.addView(cdCard);

        // ===== Countdown completion card (shown when fired while app foreground) =====
        cdFiredCard = Ui.card(this);
        cdFiredCard.setVisibility(View.GONE);
        cdFiredCard.addView(Ui.text(this, "倒计时结束！", 15f, Ui.OK, true));
        TextView firedHint = Ui.text(this, "你设置的倒计时已到点。", 12f, Ui.TEXT, false);
        firedHint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        cdFiredCard.addView(firedHint);
        cdFiredCard.addView(Ui.button(this, "知道了", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissFiredCard();
            }
        }));
        root.addView(cdFiredCard);

        root.addView(Ui.space(this, 10));

        // ===== Phone Status =====
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
        root.addView(Ui.mono(this, "手环端 EV 工具箱的同名菜单会触发同样的指令"
                + "（{\"action\":\"cmd\",...}）"));

        setContentView(Ui.wrapWithBottomBar(this, root, -1));
        Analytics.pageView(this, "/apk/toolbox");
    }

    // ======================= Countdown =======================

    private View.OnClickListener clickCd(final int minutes) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                CommandRouter.countdown(ToolboxActivity.this, minutes);
                cdStatusView.setText("已设置 " + minutes + " 分钟倒计时，到点响铃");
                cdStatusView.setTextColor(Ui.OK);
                cdCancelBtn.setVisibility(View.VISIBLE);
                dismissFiredCard();
                startTick();
            }
        };
    }

    private String cdStatusText() {
        long left = CommandRouter.countdownRemaining(this);
        if (CommandRouter.countdownFired(this)) {
            return "倒计时结束！";
        }
        return left > 0 ? "剩余 " + (left / 60) + " 分 " + (left % 60) + " 秒"
                : "选择时长开始倒计时";
    }

    private void startTick() {
        stopTick();
        tickRunnable = new Runnable() {
            @Override public void run() {
                long left = CommandRouter.countdownRemaining(ToolboxActivity.this);
                if (CommandRouter.countdownFired(ToolboxActivity.this)) {
                    cdTimerView.setText("00:00");
                    cdStatusView.setText("倒计时结束！");
                    cdStatusView.setTextColor(Ui.OK);
                    cdCancelBtn.setVisibility(View.GONE);
                    showFiredCard();
                    return;
                }
                if (left <= 0) {
                    cdTimerView.setText("");
                    cdStatusView.setText("选择时长开始倒计时");
                    cdStatusView.setTextColor(Ui.MUTED);
                    cdCancelBtn.setVisibility(View.GONE);
                    return;
                }
                long min = left / 60;
                long sec = left % 60;
                cdTimerView.setText(String.format("%02d:%02d", min, sec));
                cdStatusView.setText("剩余 " + min + " 分 " + sec + " 秒");
                cdStatusView.setTextColor(Ui.ACCENT);
                cdCancelBtn.setVisibility(View.VISIBLE);
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.post(tickRunnable);
    }

    private void stopTick() {
        if (tickRunnable != null) {
            mainHandler.removeCallbacks(tickRunnable);
            tickRunnable = null;
        }
    }

    private void showFiredCard() {
        cdFiredCard.setVisibility(View.VISIBLE);
    }

    private void dismissFiredCard() {
        cdFiredCard.setVisibility(View.GONE);
        CommandRouter.dismissCountdownFired(this);
    }

    // ======================= Lifecycle =======================

    private String currentRinger() {
        return "当前：" + CommandRouter.statusText(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;

        long left = CommandRouter.countdownRemaining(this);
        boolean fired = CommandRouter.countdownFired(this);

        if (fired) {
            cdTimerView.setText("00:00");
            cdStatusView.setText("倒计时结束！");
            cdStatusView.setTextColor(Ui.OK);
            cdCancelBtn.setVisibility(View.GONE);
            showFiredCard();
            stopTick();
        } else if (left > 0) {
            cdCancelBtn.setVisibility(View.VISIBLE);
            startTick();
        } else {
            cdTimerView.setText("");
            cdStatusView.setText(cdStatusText());
            cdStatusView.setTextColor(Ui.MUTED);
            cdCancelBtn.setVisibility(View.GONE);
            stopTick();
        }

        statusView.setText(CommandRouter.statusText(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTick();
    }
}