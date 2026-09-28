package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

/**
 * 本机运行统计（埋点用）：升级次数 / 打开次数 / 前台时长 / 手环连接统计。
 *
 * 设计约束：
 *   - 只累计**本机数字**，不采集任何内容（不碰课程、留言、账号、位置）；
 *   - 存储 SharedPreferences("ev_stats")，读取走内存映射，开销可忽略；
 *   - 所有方法**永不抛异常**：统计坏掉绝不能影响主流程；
 *   - 进程启动调一次 {@link #onProcessStart}（由 EvApp.onCreate 驱动），
 *     前台时长由 EvApp 的 Activity 生命周期回调驱动，连接统计由 SyncEngine 驱动。
 *
 * 字段口径与运维用法见仓库文档 docs/apk-tracking-telemetry-spec.md。
 */
public final class Stats {

    private static final String PREF = "ev_stats";
    private static final int REASON_MAX = 120;

    private static boolean started;

    private Stats() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /**
     * 进程启动调一次：首次安装只记起点，之后 versionCode 变大才算一次「升级」；
     * 同时累计一次「打开次数」（= 进程启动次数，即用户打开 App 的次数）。
     */
    public static void onProcessStart(Context c) {
        try {
            if (started) {
                return;
            }
            started = true;
            SharedPreferences p = sp(c);
            int code = versionCode(c);
            int seen = p.getInt("seen_code", 0);
            SharedPreferences.Editor e = p.edit();
            if (seen <= 0) {
                e.putInt("seen_code", code);            // 首次安装：只记起点，不算升级
            } else if (code > seen) {
                e.putInt("seen_code", code);
                e.putInt("upgrade_count", p.getInt("upgrade_count", 0) + 1);
            }
            e.putInt("open_count", p.getInt("open_count", 0) + 1);
            e.putLong("last_open_ms", System.currentTimeMillis());
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 一次前台会话结束（App 退到后台）时累加时长。进程被系统直接杀掉的尾巴会丢，可接受。 */
    public static void addForegroundMs(Context c, long ms) {
        if (ms <= 0 || ms > 24L * 3600 * 1000) {
            return; // 明显异常的时长不入账（比如系统时间被改过）
        }
        try {
            SharedPreferences p = sp(c);
            p.edit().putLong("foreground_ms", p.getLong("foreground_ms", 0) + ms).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 一次连接尝试开始计时（SyncEngine.connect 调用） */
    public static void connectStart(Context c) {
        try {
            SharedPreferences p = sp(c);
            p.edit()
                    .putInt("connect_total", p.getInt("connect_total", 0) + 1)
                    .putLong("connect_start_ms", System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 连接结束。ok=false 时记「失败步」（1 初始化服务 / 2 查找设备 / 3 申请权限 / 4 ping EV）
     * 与原因文案 —— 运维据此能直接回答"卡在第几步"。
     */
    public static void connectEnd(Context c, boolean ok, int failStep, String reason) {
        try {
            SharedPreferences p = sp(c);
            SharedPreferences.Editor e = p.edit();
            long start = p.getLong("connect_start_ms", 0);
            long cost = start > 0 ? System.currentTimeMillis() - start : 0;
            e.putLong("connect_start_ms", 0);
            if (ok) {
                e.putInt("connect_ok", p.getInt("connect_ok", 0) + 1);
                e.putLong("connect_last_ms", cost);
            } else {
                e.putInt("connect_fail", p.getInt("connect_fail", 0) + 1);
                e.putInt("connect_last_fail_step", failStep);
                e.putString("connect_last_fail_reason", safe(reason));
            }
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 把「本 APK 运行统计」填进埋点的 app 段 */
    public static void fillApp(Context c, JSONObject app) {
        try {
            SharedPreferences p = sp(c);
            app.put("upgrade_count", p.getInt("upgrade_count", 0));
            app.put("open_count", p.getInt("open_count", 0));
            app.put("foreground_ms", p.getLong("foreground_ms", 0));
            app.put("last_open_ms", p.getLong("last_open_ms", 0));
        } catch (Throwable ignored) {
        }
    }

    /** 把「手环连接统计」填进埋点的 watch 段 */
    public static void fillWatch(Context c, JSONObject watch) {
        try {
            SharedPreferences p = sp(c);
            watch.put("connect_total", p.getInt("connect_total", 0));
            watch.put("connect_ok", p.getInt("connect_ok", 0));
            watch.put("connect_fail", p.getInt("connect_fail", 0));
            watch.put("connect_last_ms", p.getLong("connect_last_ms", 0));
            watch.put("connect_last_fail_step", p.getInt("connect_last_fail_step", 0));
            watch.put("connect_last_fail_reason", safe(p.getString("connect_last_fail_reason", "")));
        } catch (Throwable ignored) {
        }
    }

    private static int versionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String safe(String s) {
        s = s == null ? "" : s.replace('\n', ' ').trim();
        return s.length() > REASON_MAX ? s.substring(0, REASON_MAX) : s;
    }
}
