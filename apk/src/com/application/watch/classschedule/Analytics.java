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
 *     path, query, nickname, install_id, deviceId,               // deviceId = 手环**真实** deviceId
 *     device: { model, brand, manufacturer, os, sdk, os_brand },   // 手机
 *     app:    { version, code, variant, first_install, last_update,
 *               upgrade_count, open_count, foreground_ms, last_open_ms,
 *               install_id },                                       // 本 APK
 *     watch:  { connected, model, ev_version, ev_code, node_id,     // 手环 / EV 快应用
 *               device_id, connect_total, connect_ok, connect_fail,
 *               connect_last_ms, connect_last_fail_step, connect_last_fail_reason }
 *   }
 *   - IP 由服务端从请求头取，客户端不传
 *   - 服务端落 visitor_logs（含 device jsonb）+ tracking_events(kind=visit) + PV/UV，
 *     并把 device_model / os_version / os_brand 透进 page_visit 通知（Mac 播报「安卓<型号>用户…」）
 *   - 完整字段清单与运维口径见仓库文档 docs/apk-tracking-telemetry-spec.md
 *
 * 📱 三套编号各归其位（别混，混了画像就合并错人）：
 *   install_id  手机侧安装实例（`apk-xxxxxxxx`，随机生成，非硬件标识）
 *   deviceId    手环**真实** deviceId —— 与用户激活时提交的是同一个，是「手机↔手环」的合并钥匙
 *   node_id     手环 XMS 节点 ID（纯数字）—— 手机连接手环用的编号，**不是** deviceId
 *   ⚠️ 0.5.101 及其之前把 node_id 当 deviceId 发过（本次已修）。老版本无法强制升级，
 *      服务端需要**长期**识别并归一这种报文，见分析方案 §6.1。
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
            // query 串是老字段（后台按 from=apk 识别来源、按 connected 分档），**保持原样**
            body.put("query", "from=apk&v=" + version(ctx) + "&connected=" + (connected ? 1 : 0)
                    + "&variant=" + Variant.name(ctx));
            body.put("nickname", nickname);
            // 📱 A2：修掉「拿 nodeId 冒充 deviceId」——这是全链路里唯一会污染强锚点的地方。
            //   deviceId 只放手环**真实** deviceId（没取到过就是空串，服务端按缺省处理）；
            //   手环 XMS nodeId 走 watch.node_id；App 安装实例走 install_id。三套编号各归其位，
            //   服务端才能把「这台手机」和「这只手环」确定性地钉在一起（详见分析方案 §3.1）。
            // ⚠️ 字段永远存在（哪怕是空串），保持报文形状稳定 —— 老版本已发出去的报文形状不变。
            body.put("deviceId", Stats.watchDeviceId(ctx));
            String installId = Stats.installId(ctx);
            if (installId.length() > 0) {
                body.put("install_id", installId);
            }
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
            // 连接次数 / 成功率 / 失败步与原因（见 Stats + SyncEngine）；
            // 📱 A3 的手环真实 device_id 也在这里输出（Stats.fillWatch 统一出口，避免两处各写一遍）
            Stats.fillWatch(ctx, w);
        } catch (Throwable ignored) {
        }
        return w;
    }

    // ---------------- P3：客户端阶段事件（section=client-event） ----------------

    /**
     * 关键操作事件上报（连接结果 / 导入导出 / 激活成功 / 升级）。
     * 与 pageView 的区别：这是「发生了什么事」，pageView 是「打开了哪个页面」；
     * 服务端按 kind 白名单分级推送（成功实时有节流、失败整点合并，见分析方案 §8#4）。
     *
     * 约束同 pageView：异步、失败静默、绝不影响主流程；body 里 device/app/watch 上下文
     * 与 pageView 完全同构（服务端 sanitizeDevice 一把抓）。
     *
     * @param dedupeKey 可空。给了就由服务端 dedupe_key 唯一索引幂等（重报只记一次）；
     *                  不给则每次都落库（如 connect_fail：每次尝试都是数据，噪音由服务端合并桶吸收）
     */
    public static void event(Context ctx, String kind, JSONObject payload, String dedupeKey) {
        if (!enabled || ctx == null || kind == null || kind.length() == 0) {
            return;
        }
        boolean connected = false;
        String watchName = "";
        String evVersion = "";
        int evCode = 0;
        String nodeId = "";
        try {
            SyncEngine e = SyncEngine.get(ctx);
            connected = e.hasNode();
            nodeId = e.getNodeId() != null ? e.getNodeId() : "";
            watchName = e.deviceName;
            evVersion = e.versionName;
            evCode = e.versionCode;
        } catch (Throwable ignored) {
        }
        JSONObject body = new JSONObject();
        try {
            body.put("kind", kind);
            // 三套编号各归其位（同 pageView 的 A2 口径）
            body.put("deviceId", Stats.watchDeviceId(ctx));
            String installId = Stats.installId(ctx);
            if (installId.length() > 0) {
                body.put("install_id", installId);
            }
            body.put("channel", "apk");
            body.put("device", deviceInfo());
            body.put("app", appInfo(ctx));
            body.put("watch", watchInfo(ctx, connected, watchName, evVersion, evCode, nodeId));
            if (payload != null && payload.length() > 0) {
                body.put("payload", payload);
            }
            if (dedupeKey != null && dedupeKey.length() > 0) {
                body.put("dedupeKey", dedupeKey);
            }
        } catch (Throwable ignored) {
        }
        Net.postJson(Net.BASE + "/api/activate?section=client-event", body.toString(), null);
    }

    /** event(ctx, kind, payload) 的便捷重载：不幂等（每次都记） */
    public static void event(Context ctx, String kind, JSONObject payload) {
        event(ctx, kind, payload, null);
    }

    /** 便捷 payload 构造：交替 key/value，如 p("stage", 3, "reason", "timeout") */
    public static JSONObject p(Object... kv) {
        JSONObject o = new JSONObject();
        if (kv == null) {
            return o;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                Object v = kv[i + 1];
                if (v == null) {
                    o.put(String.valueOf(kv[i]), "");
                } else if (v instanceof Number) {
                    o.put(String.valueOf(kv[i]), ((Number) v).doubleValue());
                } else if (v instanceof Boolean) {
                    o.put(String.valueOf(kv[i]), ((Boolean) v).booleanValue());
                } else {
                    o.put(String.valueOf(kv[i]), String.valueOf(v));
                }
            } catch (Throwable ignored) {
            }
        }
        return o;
    }

    /**
     * 幂等键：apk:<installId>:<kind>:<extra>:<北京日期>。
     * 「按天去重」类事件用（发现更新/完成升级 —— 每天最多记一次，防止每次启动都重报）。
     */
    public static String dedupeKey(Context ctx, String kind, String extra) {
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        df.setTimeZone(java.util.TimeZone.getTimeZone("GMT+8"));
        return "apk:" + Stats.installId(ctx) + ":" + kind + ":" + (extra == null ? "" : extra)
                + ":" + df.format(new java.util.Date());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
