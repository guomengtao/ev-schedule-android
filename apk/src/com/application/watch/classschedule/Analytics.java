package com.application.watch.classschedule;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

/**
 * 轻量埋点：把页面访问上报到网站后台（复用已有的 visitor-track 接口）。
 *
 * 复用点：POST /api/activate?section=visitor-track  body {path, query}
 *   - IP 由服务端从请求头取，客户端不传
 *   - 后台会写 visitor_logs + tracking_events(kind=visit) + PV/UV
 *
 * 约束：
 *   - 异步执行（Net 内部起线程），失败静默，绝不能影响连接/导入等主流程
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
        boolean connected = false;
        try {
            connected = SyncEngine.get(ctx).hasNode();
        } catch (Throwable ignored) {
        }
        JSONObject body = new JSONObject();
        try {
            body.put("path", path);
            body.put("query", "from=apk&v=" + version(ctx) + "&connected=" + (connected ? 1 : 0));
        } catch (Throwable ignored) {
        }
        Net.postJson(Net.BASE + "/api/activate?section=visitor-track", body.toString(), null);
    }

    private static String version(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
