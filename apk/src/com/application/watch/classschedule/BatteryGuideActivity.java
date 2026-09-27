package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 省电白名单引导页。
 *
 * 背景：后台常驻提醒（SyncService）能不能活下来，取决于 ROM 的省电策略。
 * 前台服务不受 Doze 挂起影响，但国内 ROM 仍会主动清理后台 App。
 * 本页把"三件事"引导做完：
 *   1. 允许「忽略电池优化」（系统级）
 *   2. 打开本应用详情页（顺手把「省电策略/后台限制」设为无限制）
 *   3. 打开「自启动管理」（多数国产 ROM 独有此开关）
 */
public class BatteryGuideActivity extends Activity {

    private TextView statusView, resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "省电白名单"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "让 App 在后台活着，才收得到手环留言", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        LinearLayout statusCard = Ui.card(this);
        statusView = Ui.text(this, "检测中…", 13f, Ui.TEXT, true);
        statusCard.addView(statusView);
        statusCard.addView(Ui.space(this, 6));
        statusCard.addView(Ui.text(this, deviceHint(), 11.5f, Ui.MUTED, false));
        root.addView(statusCard);
        root.addView(Ui.space(this, 10));

        LinearLayout step1 = Ui.card(this);
        step1.addView(Ui.text(this, "① 忽略电池优化（推荐）", 12.5f, Ui.TEXT, true));
        step1.addView(Ui.space(this, 6));
        step1.addView(Ui.text(this,
                "系统会在省电模式/息屏后限制后台应用。加白名单后，服务可以持续运行。",
                11.5f, Ui.MUTED, false));
        step1.addView(Ui.space(this, 10));
        step1.addView(Ui.button(this, "去申请忽略电池优化", true, new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        }));
        root.addView(step1);
        root.addView(Ui.space(this, 10));

        LinearLayout step2 = Ui.card(this);
        step2.addView(Ui.text(this, "② 应用详情里设为「无限制」", 12.5f, Ui.TEXT, true));
        step2.addView(Ui.space(this, 6));
        step2.addView(Ui.text(this,
                "部分 ROM 把「省电策略 / 后台运行」单独放在应用详情页，需要手动改成无限制。",
                11.5f, Ui.MUTED, false));
        step2.addView(Ui.space(this, 10));
        step2.addView(Ui.grid(this,
                Ui.button(this, "打开应用详情", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { openAppDetails(); }
                }),
                Ui.button(this, "省电优化列表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { openBatteryList(); }
                })));
        root.addView(step2);
        root.addView(Ui.space(this, 10));

        LinearLayout step3 = Ui.card(this);
        step3.addView(Ui.text(this, "③ 允许自启动（国产 ROM 必需）", 12.5f, Ui.TEXT, true));
        step3.addView(Ui.space(this, 6));
        step3.addView(Ui.text(this,
                "小米 / 华为 / OPPO / vivo 等需要在「自启动管理」里放行，否则重启后不会自动恢复。",
                11.5f, Ui.MUTED, false));
        step3.addView(Ui.space(this, 10));
        step3.addView(Ui.button(this, "打开自启动管理", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openAutoStart(); }
        }));
        root.addView(step3);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.button(this, "重新检测状态", false, new View.OnClickListener() {
            @Override public void onClick(View v) { refreshStatus(true); }
        }));
        root.addView(Ui.space(this, 6));
        root.addView(Ui.mono(this,
                "说明：即使全部设置好，被系统强杀时仍收不到留言 —— 这是没有推送通道的固有限制。"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        refreshStatus(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页返回后自动复检
        refreshStatus(false);
    }

    // ======================= 状态 =======================

    private boolean isIgnoring() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshStatus(boolean fromButton) {
        boolean ok = isIgnoring();
        statusView.setText(ok ? "状态：已加入电池优化白名单 ✓" : "状态：未加入白名单（后台可能被清理）");
        statusView.setTextColor(ok ? Ui.OK : Ui.WARN);
        boolean serviceOn = SyncService.enabled(this);
        if (!serviceOn) {
            resultView.setText("提示：「后台常驻提醒」当前是关闭的，去设置页打开才有意义");
            resultView.setTextColor(Ui.WARN);
        } else if (fromButton) {
            resultView.setText(ok ? "已确认：服务可以持续运行" : "仍未加入，请在上面的第 ① 步里允许");
            resultView.setTextColor(ok ? Ui.OK : Ui.MUTED);
        }
    }

    private String deviceHint() {
        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        return "当前机型：" + brand + " " + Build.MODEL;
    }

    // ======================= 跳转动作 =======================

    /** 直接请求「忽略电池优化」（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限） */
    private void requestIgnoreBattery() {
        if (isIgnoring()) {
            resultView.setText("已经是白名单状态，无需重复申请");
            resultView.setTextColor(Ui.OK);
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            resultView.setText("已打开系统授权页，请选择「允许」");
            resultView.setTextColor(Ui.MUTED);
            return;
        } catch (Throwable ignored) {
        }
        // 兜底：打开系统的电池优化列表，让用户手动把本应用设为「不优化」
        openBatteryList();
    }

    private void openBatteryList() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            resultView.setText("请在列表里找到「EV 同步器」，设为「不优化」");
            resultView.setTextColor(Ui.MUTED);
        } catch (Throwable t) {
            resultView.setText("无法打开电池优化设置：" + t);
            resultView.setTextColor(Ui.ERR);
        }
    }

    private void openAppDetails() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            resultView.setText("在「省电策略 / 后台运行」里选择「无限制」");
            resultView.setTextColor(Ui.MUTED);
        } catch (Throwable t) {
            resultView.setText("无法打开应用详情：" + t);
            resultView.setTextColor(Ui.ERR);
        }
    }

    /** 依次尝试各主流 ROM 的「自启动管理」入口，全失败则退回应用详情页 */
    private void openAutoStart() {
        String[][] list = {
                {"小米", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"小米(旧)", "com.miui.securitycenter", "com.miui.powercenter.PowerSettings"},
                {"华为", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"华为(旧)", "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"OPPO", "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"OPPO(旧)", "com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"vivo", "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"vivo(旧)", "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"魅族", "com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                {"三星", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] rom : list) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(rom[1], rom[2]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                resultView.setText("已打开「" + rom[0] + "」的自启动管理，请允许本应用");
                resultView.setTextColor(Ui.MUTED);
                return;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        resultView.setText("没有找到本机的自启动管理入口，已改为打开应用详情页");
        resultView.setTextColor(Ui.WARN);
        openAppDetails();
    }
}
