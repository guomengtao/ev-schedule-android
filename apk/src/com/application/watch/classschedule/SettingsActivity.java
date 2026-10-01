package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页（App 级）：主题、高级版、打赏、更新、后台常驻、上课提醒。设备相关配置在「手环」页。 */
public class SettingsActivity extends Activity {

    private int lastThemeVersion = 0;

    private TextView resultView, bgStatusView, remindStatusView, holidayStatusView;
    private Button remindLeadBtn;
    private android.widget.Switch remindSwitch, pushSwitch, bgSwitch;
    /** 程序化 setChecked 时置 true，避免触发 onCheckedChanged 造成循环 */
    private boolean switching;

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
        root.addView(Ui.row(this, "帮助与反馈", "常见问题 · QQ 群 · 提交截图反馈", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openFeedbackPage(); }
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
        // 标题行：标题 + Switch（与假期开关同款）
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bgRow.addView(Ui.text(this, "后台常驻提醒", 12.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bgSwitch = new android.widget.Switch(this);
        bgSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        applyBg(on);
                    }
                });
        bgRow.addView(bgSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bgCard.addView(bgRow);
        bgStatusView = Ui.text(this, bgText(), 11.5f, Ui.MUTED, false);
        bgStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        bgCard.addView(bgStatusView);
        bgCard.addView(Ui.button(this, "省电白名单引导", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, BatteryGuideActivity.class));
            }
        }));
        root.addView(bgCard);

        // ======================= 上课提醒（本地闹钟 + 可选推手环） =======================
        root.addView(Ui.space(this, 10));
        LinearLayout remindCard = Ui.card(this);
        // 标题行：标题 + Switch
        LinearLayout rRow = new LinearLayout(this);
        rRow.setOrientation(LinearLayout.HORIZONTAL);
        rRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        rRow.addView(Ui.text(this, "上课提醒", 12.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        remindSwitch = new android.widget.Switch(this);
        remindSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        Reminders.setEnabled(SettingsActivity.this, on);
                        refreshRemind();
                        resultView.setText(!on ? "已关闭上课提醒"
                                : (Reminders.exactAllowed(SettingsActivity.this)
                                        ? "上课提醒已开启"
                                        : "已开启。未授予「闹钟和提醒」权限，可能有 ±1 分钟误差"));
                        resultView.setTextColor(!on ? Ui.MUTED : Ui.OK);
                    }
                });
        rRow.addView(remindSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        remindCard.addView(rRow);
        remindStatusView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        remindStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        remindCard.addView(remindStatusView);
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
                Ui.button(this, "测试提醒", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Reminders.test(SettingsActivity.this);
                        resultView.setText("已发测试提醒（手机通知"
                                + (Reminders.pushWatch(SettingsActivity.this) ? " + 手环通知，手环需已连接" : "") + "）");
                        resultView.setTextColor(Ui.OK);
                    }
                })));
        remindCard.addView(Ui.space(this, 6));
        // 推送到手环：独立一行 Switch
        LinearLayout pushRow = new LinearLayout(this);
        pushRow.setOrientation(LinearLayout.HORIZONTAL);
        pushRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pushRow.addView(Ui.text(this, "推送到手环", 12.5f, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pushSwitch = new android.widget.Switch(this);
        pushSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        Reminders.setPushWatch(SettingsActivity.this, on);
                        refreshRemind();
                    }
                });
        pushRow.addView(pushSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        remindCard.addView(pushRow);
        remindCard.addView(Ui.space(this, 6));
        remindCard.addView(Ui.button(this, "重排提醒", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.reschedule(SettingsActivity.this);
                resultView.setText("已按最新课表重排提醒");
                resultView.setTextColor(Ui.OK);
            }
        }));
        remindCard.addView(Ui.space(this, 6));
        remindCard.addView(Ui.mono(this,
                "基于本地课表缓存 + 系统闹钟，不依赖手环连接；课表更新/开机后自动重排"));
        root.addView(remindCard);

        // ======================= 假期 / 调休（移植自 EV，默认开启） =======================
        root.addView(Ui.space(this, 10));
        LinearLayout holidayCard = Ui.card(this);
        // 标题行：标题 + Switch 开关（跟随主题的 Material 样式）
        LinearLayout hRow = new LinearLayout(this);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        hRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        hRow.addView(Ui.text(this, "假期 / 调休", 12.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch holidaySwitch = new android.widget.Switch(this);
        holidaySwitch.setChecked(Holiday.enabled(this));
        holidaySwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        Holiday.setEnabled(SettingsActivity.this, on);
                        refreshHoliday();
                        // 插件上的假期/调休展示也要跟着变
                        TodayWidgetProvider.refreshAll(SettingsActivity.this);
                        NextWidgetProvider.refreshAll(SettingsActivity.this);
                        WeekWidgetProvider.refreshAll(SettingsActivity.this);
                    }
                });
        hRow.addView(holidaySwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        holidayCard.addView(hRow);
        holidayStatusView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        holidayStatusView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        holidayCard.addView(holidayStatusView);
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

    /** 帮助与反馈：浏览器打开带参反馈页（src/版本/机型/渠道，服务端据此定位问题） */
    private void openFeedbackPage() {
        try {
            String ch = Variant.isEv(this) ? "ev-apk" : "evbox-apk";
            String url = "https://app-auth.gudq.com/feedback.html"
                    + "?src=app"
                    + "&v=" + java.net.URLEncoder.encode(version(), "UTF-8")
                    + "&m=" + java.net.URLEncoder.encode(android.os.Build.MODEL, "UTF-8")
                    + "&ch=" + ch;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            // 无浏览器等极端情况：退化为打开站点首页
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://app-auth.gudq.com/")));
            } catch (Throwable ignored) {
            }
        }
    }

    // ======================= 后台常驻提醒 =======================

    private String bgText() {
        return SyncService.enabled(this)
                ? "已开启：App 退到后台也能提醒新留言（会有一条常驻通知）"
                : "已关闭：仅在 App 打开时提醒";
    }

    private void toggleBg() {
        applyBg(!SyncService.enabled(this));
    }

    /** Switch 回调入口：按目标状态应用（含省电白名单引导） */
    private void applyBg(boolean on) {
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
        remindLeadBtn.setText("提前 " + Reminders.leadMinutes(this) + " 分钟");
        switching = true; // 程序化同步 Switch 状态，不触发监听
        remindSwitch.setChecked(Reminders.enabled(this));
        pushSwitch.setChecked(Reminders.pushWatch(this));
        bgSwitch.setChecked(SyncService.enabled(this));
        switching = false;
        bgStatusView.setText(bgText());
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
