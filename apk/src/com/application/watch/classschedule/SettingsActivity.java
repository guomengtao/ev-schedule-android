package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页（App 级）：主题、高级版、打赏、更新、后台常驻、上课提醒。设备相关配置在「手环」页。 */
public class SettingsActivity extends Activity {

    private int lastThemeVersion = 0;

    private TextView resultView, bgStatusView, remindStatusView, holidayStatusView;
    private Button remindToggleBtn, remindLeadBtn, remindPushBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "设置"));
        root.addView(Ui.space(this, 12));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);

        root.addView(Ui.row(this, "主题外观", "10 套主题即点即换 · 也可跟随手环", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, ThemePickerActivity.class));
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

        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "检查更新", "手动检测新版本", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        UpdateChecker.checkManual(SettingsActivity.this);
                    }
                }));

        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "调试", "手环连接四步诊断（连不上时排查）", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DebugActivity.class));
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
                        Reminders.setPushWatch(SettingsActivity.this,
                                !Reminders.pushWatch(SettingsActivity.this));
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

        // ======================= 假期 / 调休（移植自 EV，默认开启） =======================
        root.addView(Ui.space(this, 10));
        LinearLayout holidayCard = Ui.card(this);
        holidayCard.addView(Ui.text(this, "假期 / 调休", 12.5f, Ui.TEXT, true));
        holidayStatusView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        holidayStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        holidayCard.addView(holidayStatusView);
        holidayCard.addView(Ui.button(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Holiday.setEnabled(SettingsActivity.this, !Holiday.enabled(SettingsActivity.this));
                refreshHoliday();
                // 插件上的假期/调休展示也要跟着变
                TodayWidgetProvider.refreshAll(SettingsActivity.this);
                NextWidgetProvider.refreshAll(SettingsActivity.this);
                WeekWidgetProvider.refreshAll(SettingsActivity.this);
            }
        }));
        holidayCard.addView(Ui.space(this, 6));
        holidayCard.addView(Ui.mono(this,
                "内置 2026 年国务院放假安排：假期当天不排课，调休日按对应星期几的课表显示"));
        root.addView(holidayCard);

        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "本机 v" + version()));

        root.addView(Ui.space(this, 6));
        refreshRemind();
        refreshHoliday();
        setContentView(Ui.wrapWithBottomBar(this, root, 3));
        Analytics.pageView(this, "/apk/settings");
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

    private void refreshHoliday() {
        boolean on = Holiday.enabled(this);
        String when = "";
        int ov = Holiday.resolveToday(this);
        if (ov == Holiday.HOLIDAY) {
            when = "今天放假，不排课";
        } else if (ov >= 0) {
            when = "今天调休，按" + CourseCache.WEEK[ov] + "课表";
        } else {
            when = "今天按正常星期课表";
        }
        holidayStatusView.setText((on ? "已开启" : "已关闭") + " · " + when);
        holidayStatusView.setTextColor(on ? Ui.OK : Ui.MUTED);
    }
}
