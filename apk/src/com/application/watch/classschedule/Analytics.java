package com.application.watch.classschedule;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

/**
 * 轻量埋点：把页面访问 + 设备/应用/手环上下文上报到网站后台（复用已有的 visitor-track 接口）。
 *
 * 复用点：POST /api/activate?section=visitor-track
 *   body {
 *     path, query, nickname, deviceId,
 *     device: { model, brand, manufacturer, os, sdk, os_brand },   // 手机
 *     app:    { version, code, variant, first_install, last_update }, // 本 APK
 *     watch:  { connected, model, ev_version, ev_code, node_id }    // 手环 / EV 快应用
 *   }
 *   - IP 由服务端从请求头取，客户端不传
 *   - 服务端落 visitor_logs（含 device jsonb）+ tracking_events(kind=visit) + PV/UV，
 *     并把 device_model / os_version / os_brand 透进 page_visit 通知（Mac 播报「安卓<型号>用户…」）
 *   - 完整字段清单与运维口径见仓库文档 docs/apk-tracking-telemetry-spec.md
 *
 * 约束：
 *   - 异步执行（Net 内部起线程），失败静默，绝不能影响连接/导入等主流程
 *   - 只传「非敏感的设备与运行状态」：不传 IMEI/手机号/通讯录/定位，不传手环课程内容
 *   - 用 connected=0/1 区分「未连上手环」与「已连上手环」两档
 */
public final class Analytics {

    private static boolean enabled = true;

    private Analytics() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 页面访问上报；connected 由当前是否已取得手环 nodeId 自动判定 */
    public static void pageView(Context ctx, String path) {
        if (!enabled || ctx == null) {
            return;
        }
        // 兜底初始化（正常路径已由 EvApp.onCreate 调过；这里保证埋点一定拿到统计值）
        Stats.onProcessStart(ctx);
        boolean connected = false;
        String nickname = "";
        String nodeId = "";
        String watchName = "";
        String evVersion = "";
        int evCode = 0;
        try {
            SyncEngine e = SyncEngine.get(ctx);
            connected = e.hasNode();
            nickname = e.nickname;
            nodeId = e.getNodeId() != null ? e.getNodeId() : "";
            watchName = e.deviceName;
            evVersion = e.versionName;
            evCode = e.versionCode;
        } catch (Throwable ignored) {
        }
        JSONObject body = new JSONObject();
        try {
            body.put("path", path);
            body.put("query", "from=apk&v=" + version(ctx) + "&connected=" + (connected ? 1 : 0)
                    + "&variant=" + Variant.name(ctx));
            body.put("nickname", nickname);
            body.put("deviceId", nodeId);
            body.put("device", deviceInfo());
            body.put("app", appInfo(ctx));
            body.put("watch", watchInfo(ctx, connected, watchName, evVersion, evCode, nodeId));
        } catch (Throwable ignored) {
        }
        Net.postJson(Net.BASE + "/api/activate?section=visitor-track", body.toString(), null);
    }

    // ---------------- 手机 ----------------

    /** 手机厂商/型号/系统。后台「设备」列、Mac 播报、以及「华为鸿蒙连不上手环」的排查都靠它 */
    private static JSONObject deviceInfo() {
        JSONObject d = new JSONObject();
        try {
            d.put("model", safe(Build.MODEL));
            d.put("brand", safe(Build.BRAND));
            d.put("manufacturer", safe(Build.MANUFACTURER));
            d.put("os", safe(Build.VERSION.RELEASE));
            d.put("sdk", Build.VERSION.SDK_INT);
            d.put("os_brand", osBrand());
        } catch (Throwable ignored) {
        }
        return d;
    }

    /**
     * 系统品牌判定：harmony（鸿蒙，含卓易通沙箱）/ emui（华为荣耀的 Android）/ android。
     *
     * ⚠️ 只用于「提示文案 + 运维排查」，**绝不能当功能开关**：判定失败会误伤能正常连手环的机器。
     * 三条线索按可靠性排序：华为 BuildEx.getOsBrand() → os.harmony.version 属性 → Build.DISPLAY。
     */
    private static String osBrand() {
        try {
            Class<?> cls = Class.forName("com.huawei.system.BuildEx");
            Object brand = cls.getMethod("getOsBrand").invoke(null);
            if (brand != null) {
                String s = String.valueOf(brand).toLowerCase();
                if (s.contains("harmony")) {
                    return "harmony";
                }
                if (s.length() > 0) {
                    return s; // emui / magic …
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String prop = System.getProperty("os.harmony.version");
            if (prop != null && prop.length() > 0) {
                return "harmony";
            }
        } catch (Throwable ignored) {
        }
        try {
            String display = safe(Build.DISPLAY).toLowerCase();
            if (display.contains("harmony")) {
                return "harmony";
            }
        } catch (Throwable ignored) {
        }
        String mf = safe(Build.MANUFACTURER).toLowerCase();
        if (mf.contains("huawei") || mf.contains("honor")) {
            return "emui";
        }
        return "android";
    }

    // ---------------- 本 APK ----------------

    private static String version(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static JSONObject appInfo(Context ctx) {
        JSONObject a = new JSONObject();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            a.put("version", safe(pi.versionName));
            a.put("code", pi.versionCode);
            a.put("variant", Variant.name(ctx));
            // first_install / last_update 让服务端能算出「装机时长」和「是否升级过」
            a.put("first_install", pi.firstInstallTime / 1000);
            a.put("last_update", pi.lastUpdateTime / 1000);
            // 本机累计：升级次数 / 打开次数 / 前台时长（见 Stats）
            Stats.fillApp(ctx, a);
        } catch (Throwable ignored) {
        }
        return a;
    }

    // ---------------- 手环 / EV 快应用 ----------------

    private static JSONObject watchInfo(Context ctx, boolean connected, String model, String evVersion, int evCode, String nodeId) {
        JSONObject w = new JSONObject();
        try {
            w.put("connected", connected);
            w.put("model", safe(model));
            w.put("ev_version", safe(evVersion));
            w.put("ev_code", evCode);
            w.put("node_id", safe(nodeId));
            // 连接次数 / 成功率 / 失败步与原因（见 Stats + SyncEngine）
            Stats.fillWatch(ctx, w);
        } catch (Throwable ignored) {
        }
        return w;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
