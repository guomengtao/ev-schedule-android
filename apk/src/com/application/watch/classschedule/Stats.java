package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.util.UUID;

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
            // 📱 安装实例 ID（App 同步器维度的手机侧主键，见下方说明）
            String iid = installId(c);
            if (iid.length() > 0) {
                app.put("install_id", iid);
            }
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
            // 📱 手环**真实** deviceId（与激活用的同一把钥匙）；没取到过就不发这个字段，
            //    保持未激活设备的报文与老版本完全一致（服务端按缺省处理）。
            String did = watchDeviceId(c);
            if (did.length() > 0) {
                watch.put("device_id", did);
            }
            // 📱 A5（P4）：多手环历史清单。有记录才发（空数组省略，保持未激活/单手环
            //    用户的报文与老版本一致）；服务端 sanitizeWatchHistory 会逐项限长清洗。
            org.json.JSONArray h = watchHistory(c);
            if (h.length() > 0) {
                watch.put("history", h);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= App 同步器维度（分析方案 §3.3-A1 / §6.1） =================
    // 为什么需要这两个 ID：
    //   ① installId  —— 手机侧「这次安装」的稳定主键。**不是**硬件标识（不碰 IMEI / 序列号 / 手机号），
    //      重装或清数据会变，这是有意的隐私取舍；它让「同一台手机上的一系列行为」能归到一起。
    //   ② watchDeviceId —— 手环**真实** deviceId（向手环要 get_device_id 拿到的）。
    //      它是「这台手机 ↔ 这只手环」的唯一合并钥匙：与用户激活时提交的是**同一个** deviceId，
    //      所以拿到它就能确认「这个 App 用户」和「这只手环的激活用户」是同一个人。
    //
    // ⚠️ 两套编号绝不能混：手环 XMS nodeId（纯数字，如 2137618976）走 watch.node_id；
    //    真实 deviceId 走 watch.device_id / 顶层 deviceId。
    //    0.5.101 及其之前把 nodeId 当 deviceId 发过，服务端需要长期识别并归一（分析方案 §6.1）。

    private static String cachedInstallId;

    /** 安装实例 ID，形如 {@code apk-3f9c2a1b}。首次调用生成并持久化；异常时返回空串（服务端容忍缺省）。 */
    public static String installId(Context c) {
        if (cachedInstallId != null && cachedInstallId.length() > 0) {
            return cachedInstallId;
        }
        try {
            SharedPreferences p = sp(c);
            String v = p.getString("install_id", "");
            if (v == null || v.length() == 0) {
                // 取 UUID 前 8 位十六进制：够用（碰撞概率可忽略）且字段短；
                // 不用 Hardware ID，也不需要任何额外权限。
                v = "apk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                p.edit().putString("install_id", v).apply();
            }
            cachedInstallId = v;
            return v;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 缓存手环真实 deviceId（从 {@code get_device_id} 回包解析）。幂等、静默失败、不影响主流程。
     * 回包形如 {@code {ok,action:"get_device_id",deviceId,deviceId4,fallback}}。
     *
     * @param nodeId 本次取 deviceId 时连着的 nodeId（用于区分多手环，P4 的历史清单会用到）
     */
    public static void cacheWatchDeviceId(Context c, String replyJson, String nodeId) {
        try {
            if (replyJson == null || replyJson.length() == 0) {
                return;
            }
            JSONObject o = new JSONObject(replyJson);
            if (!o.optBoolean("ok", false)) {
                return;
            }
            String did = clip(o.optString("deviceId", ""), 128);
            if (did.length() == 0) {
                return;   // 空值 / 只有 fallback：不缓存，避免把临时 UUID 当成真实 deviceId
            }
            SharedPreferences.Editor e = sp(c).edit()
                    .putString("watch_device_id", did)
                    .putLong("watch_device_at", System.currentTimeMillis());
            String nid = clip(nodeId, 64);
            if (nid.length() > 0) {
                e.putString("watch_device_node", nid);
            }
            e.apply();
            // A5（P4）：历史清单里对应节点的 device_id 同步补上
            updateHistoryDeviceId(c, nid, did);
        } catch (Throwable ignored) {
        }
    }

    /** 已缓存的手环真实 deviceId；从未取到过则为空串 */
    public static String watchDeviceId(Context c) {
        try {
            return clip(sp(c).getString("watch_device_id", ""), 128);
        } catch (Throwable t) {
            return "";
        }
    }

    // ================= A5 多手环历史清单（P4） =================
    // 分析方案 §3.3-A5：多手环用户换表/连表时，老逻辑只覆盖单 nodeId，历史会永久丢。
    // 这里持久化一个「见过谁」的清单：连接结束（成功/失败都算见过）时 upsert，
    // 拿到真实 deviceId 时回填。上限 10 只，按 last_seen 淘汰最旧 —— 与服务端
    // sanitizeWatchHistory 的 MAX_WATCH_HISTORY 对齐，字段名也一一对应。

    private static final int WATCH_HISTORY_MAX = 10;

    /**
     * 是否该在空闲时刷新 deviceId（§7.1 遗留 #2 的「空闲时刷新」调度）：
     * ① 从未拿到过真实 deviceId；② 当前连的 nodeId 与缓存时不同（用户换了手环）。
     * 供 SyncEngine 保活心跳在「连接 OK + 无操作在途 + 30s 无收发」时调用，
     * 避免硬插 get_device_id 顶掉用户操作回包。
     */
    public static boolean needsDeviceIdRefresh(Context c, String nodeId) {
        try {
            if (watchDeviceId(c).length() == 0) {
                return true;
            }
            SharedPreferences p = sp(c);
            String cachedNode = p.getString("watch_device_node", "");
            return nodeId != null && nodeId.length() > 0 && !nodeId.equals(cachedNode);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 连接结束（成功或失败都算「见过这只手环」）时把节点 upsert 进历史清单。
     * model / ev_version 空值不覆盖（连接失败时版本号已被清空，保留上次的）。
     */
    public static void recordWatchSeen(Context c, String nodeId, String model, String evVersion, boolean ok) {
        try {
            String nid = clip(nodeId, 64);
            if (nid.length() == 0) {
                return; // 没落到任何节点，无从记录
            }
            org.json.JSONArray arr = watchHistory(c);
            long now = System.currentTimeMillis() / 1000;
            org.json.JSONObject hit = null;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject it = arr.optJSONObject(i);
                if (it != null && nid.equals(it.optString("node_id"))) {
                    hit = it;
                    break;
                }
            }
            if (hit == null) {
                hit = new org.json.JSONObject();
                try {
                    hit.put("node_id", nid);
                    hit.put("device_id", "");
                    hit.put("model", "");
                    hit.put("ev_version", "");
                    hit.put("first_seen", now);
                } catch (Throwable ignored) {
                }
                arr.put(hit);
                while (arr.length() > WATCH_HISTORY_MAX) {
                    int oldest = 0;
                    for (int i = 1; i < arr.length(); i++) {
                        if (arr.optJSONObject(i).optLong("last_seen") < arr.optJSONObject(oldest).optLong("last_seen")) {
                            oldest = i;
                        }
                    }
                    arr.remove(oldest);
                }
            }
            if (ok) {
                hit.put("ok", hit.optInt("ok") + 1);
            } else {
                hit.put("fail", hit.optInt("fail") + 1);
            }
            String m = clip(model, 64);
            if (m.length() > 0) {
                hit.put("model", m);
            }
            String v = clip(evVersion, 24);
            if (v.length() > 0) {
                hit.put("ev_version", v);
            }
            hit.put("last_seen", now);
            sp(c).edit().putString("watch_history", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 历史清单里对应节点的 device_id 回填（拿到真实 deviceId 时调用）；无清单则建一条 */
    private static void updateHistoryDeviceId(Context c, String nodeId, String deviceId) {
        try {
            String nid = clip(nodeId, 64);
            String did = clip(deviceId, 128);
            if (nid.length() == 0 || did.length() == 0) {
                return;
            }
            org.json.JSONArray arr = watchHistory(c);
            org.json.JSONObject hit = null;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject it = arr.optJSONObject(i);
                if (it != null && nid.equals(it.optString("node_id"))) {
                    hit = it;
                    break;
                }
            }
            if (hit == null) {
                hit = new org.json.JSONObject();
                try {
                    hit.put("node_id", nid);
                    hit.put("first_seen", System.currentTimeMillis() / 1000);
                } catch (Throwable ignored) {
                }
                arr.put(hit);
                while (arr.length() > WATCH_HISTORY_MAX) {
                    arr.remove(0); // 没有last_seen可比时按最旧位置淘汰，简单够用
                }
            }
            hit.put("device_id", did);
            hit.put("last_seen", System.currentTimeMillis() / 1000);
            sp(c).edit().putString("watch_history", arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static org.json.JSONArray watchHistory(Context c) {
        try {
            return new org.json.JSONArray(sp(c).getString("watch_history", "[]"));
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    private static String clip(String s, int n) {
        s = s == null ? "" : s.trim();
        return s.length() > n ? s.substring(0, n) : s;
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
