package com.application.watch.classschedule;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 云端待推送队列的消费循环（P1）。
 *
 * 形态是「串行泵」而不是线程池：Net 本身已经是异步的，队列又必须保序
 * （同一张表的改动要按入队顺序落库），所以这里只保证「一次只有一个 inflight」，
 * 上一个回调回来才取下一个 —— 并发只会带来 rev 冲突，不会更快。
 *
 * 三条硬约束（与 CloudSyncStore 分工）：
 * 1. 队列负责持久化与退避时间，这里**只挑 due 的项**，不自己算退避；
 * 2. 成功/失败一律回写 CloudSyncStore，失败**绝对不弹提示**；
 * 3. 任何时候调用 {@link #kick(Context)} 都安全（重入被 running 挡住）。
 *
 * 触发时机（P1 先接「App 回前台」，WorkManager 定时等真机验证后再加）。
 */
public final class CloudSyncWorker {

    private static volatile boolean running = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CloudSyncWorker() {
    }

    /** 首次开通云同步的结果回调（主线程） */
    public interface SyncCb {
        void on(boolean ok, String msg, String url, int rev);
    }

    /** 拉取云端更新的结果回调（主线程）。n = 本次真正拉到并应用的课表数，0 表示没变化。 */
    public interface PullCb {
        void on(int n);
    }

    /**
     * 云端有新版本 → 拉下来覆盖本地（ack_rev 回路）。
     *
     * <p>只查「已开启云同步」的表（cloudEnabled && cloudRev>0），逐张 state 对比 rev：
     * 云端 rev 比本地基线大，才 pull 全量并写入。写入走
     * {@link ScheduleStore#applyCloudCourses}，**不入待推队列**，否则拉完立刻回推、rev 自增抖动。
     *
     * <p>拉到之后立刻 ack(rev)，让服务端知道手机已收到这个版本。
     * 手环上的落地仍走原有同步链路（推手环），这里只负责把「云端真源」搬回手机。
     */
    public static void pullAll(final Context c, final PullCb cb) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        final java.util.List<ScheduleStore.Schedule> targets = new java.util.ArrayList<ScheduleStore.Schedule>();
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(app)) {
                if (s.cloudEnabled && s.cloudRev > 0
                        && s.guid != null && s.guid.length() > 0) {
                    targets.add(s);
                }
            }
        } catch (Throwable t) {
            if (cb != null) cb.on(0);
            return;
        }
        checkNext(app, targets, 0, 0, cb);
    }

    private static void checkNext(final Context app, final java.util.List<ScheduleStore.Schedule> t,
                                  final int i, final int applied, final PullCb cb) {
        if (i >= t.size()) {
            if (cb != null) {
                final int n = applied;
                MAIN.post(new Runnable() {
                    @Override public void run() { cb.on(n); }
                });
            }
            return;
        }
        final ScheduleStore.Schedule s = t.get(i);
        CloudApi.state(app, s.guid, new CloudApi.Cb() {
            @Override public void on(int code, String body) {
                int rev = 0;
                try { rev = new org.json.JSONObject(body == null ? "{}" : body).optInt("rev", 0); }
                catch (Throwable ignored) { }
                if (code != 200 || rev <= s.cloudRev) {
                    checkNext(app, t, i + 1, applied, cb);
                    return;
                }
                CloudApi.pull(app, s.guid, new CloudApi.Cb() {
                    @Override public void on(int c2, String b2) {
                        boolean ok = applyPayload(app, s.guid, b2);
                        checkNext(app, t, i + 1, applied + (ok ? 1 : 0), cb);
                    }
                });
            }
        });
    }

    /** 把 pull 回来的 payload 写进本地；成功则 ack 该 rev。 */
    private static boolean applyPayload(Context app, String guid, String body) {
        try {
            org.json.JSONObject r = new org.json.JSONObject(body == null ? "{}" : body);
            if (!r.optBoolean("exists", true)) return false;
            if (r.optBoolean("deleted", false)) return false;   // 墓碑语义交给上层，不在这里删
            int rev = r.optInt("rev", 0);
            org.json.JSONObject p = r.optJSONObject("payload");
            org.json.JSONArray cs = p == null ? null : p.optJSONArray("courses");
            if (cs == null) return false;
            java.util.List<CourseCache.Course> list = new java.util.ArrayList<CourseCache.Course>();
            for (int i = 0; i < cs.length(); i++) {
                org.json.JSONObject o = cs.optJSONObject(i);
                if (o != null) list.add(CourseCache.fromJson(o));
            }
            boolean ok = ScheduleStore.applyCloudCourses(app, guid, list, rev);
            if (ok && rev > 0) CloudApi.ack(app, guid, rev, null);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 有活就开泵；正在跑就什么都不做（不排队、不重复推）。 */
    public static void kick(final Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        if (running) return;
        try {
            if (CloudSyncStore.pendingCount(app) <= 0) return;
        } catch (Throwable t) {
            return;
        }
        running = true;
        pump(app);
    }

    private static void pump(final Context app) {
        final CloudSyncStore.Item item;
        try {
            item = CloudSyncStore.nextDue(app);
        } catch (Throwable t) {
            running = false;
            return;
        }
        if (item == null) {
            running = false;
            return;
        }

        final String guid = item.guid;
        final int baseRev = item.baseRev < 0 ? 0 : item.baseRev;

        try {
            CloudApi.push(app, guid, item.op, baseRev, item.payload, new CloudApi.Cb() {
                @Override public void on(int code, String body) {
                    boolean ok;
                    try {
                        ok = CloudApi.applyPushResult(app, guid, code, body);
                    } catch (Throwable t) {
                        ok = false;
                    }
                    if (!ok) {
                        // 失败：队列已写入退避，收手等下一次 kick，不弹任何提示
                        running = false;
                        return;
                    }
                    // 成功：把云端 rev 追平到本地，下一次改动的 baseRev 才对得上
                    try {
                        int rev = 0;
                        try {
                            rev = new org.json.JSONObject(body == null ? "{}" : body).optInt("rev", 0);
                        } catch (Throwable ignored) {
                        }
                        if (rev > 0) ScheduleStore.setCloudState(app, guid, rev, true);
                    } catch (Throwable ignored) {
                    }
                    pump(app);   // 继续下一个
                }
            });
        } catch (Throwable t) {
            running = false;
        }
    }

    /**
     * 首次开通：把某张表整份推上云（publish），成功后回写 token 与 cloudRev，
     * 并把 cloudEnabled 置真 —— 之后所有本地改动才会自动入队。
     */
    /**
     * 云端 watch_key = 这只手环的稳定锚点。
     * 优先 "mac:XX:XX:..."（Stats 缓存，零探测），退化 "uuid:<deviceId>"。
     * 两者都带前缀，服务端和人都能一眼看出这条数据挂在哪类锚上。
     */
    static String anchorOf(android.content.Context c, ScheduleStore.Schedule s) {
        String mac = "";
        try { mac = Stats.watchMac(c); } catch (Throwable ignored) { }
        if (mac != null && mac.length() > 0) {
            return "mac:" + mac.toUpperCase(java.util.Locale.US);
        }
        String dev = s.deviceId == null ? "" : s.deviceId.trim();
        return dev.length() == 0 ? "" : "uuid:" + dev;
    }

    public static void enableAndPublish(final Context c, final ScheduleStore.Schedule s,
                                        final SyncCb cb) {
        if (c == null || s == null) {
            if (cb != null) cb.on(false, "no_schedule", "", 0);
            return;
        }
        final Context app = c.getApplicationContext();
        final String payload;
        try {
            org.json.JSONArray cs = new org.json.JSONArray();
            for (CourseCache.Course co : s.courses) {
                cs.put(CourseCache.toJson(co));
            }
            org.json.JSONObject p = new org.json.JSONObject();
            p.put("courses", cs);
            payload = p.toString();
        } catch (Throwable t) {
            if (cb != null) cb.on(false, "payload_failed", "", 0);
            return;
        }

        // 2026-11-20 用户裁定：锚点优先用蓝牙 MAC（跨重装/重置/换机不变），
        // 只有拿不到 MAC 的 mac_random 机型才退化到 deviceId。
        // 退化值必须带前缀区分，否则「重装后 UUID 变了」会变成一座新孤岛，无法与 MAC 对齐。
        CloudApi.publish(app, s.guid,
                anchorOf(app, s),
                s.deviceName == null ? "" : s.deviceName,
                s.name, payload,
                new CloudApi.PubCb() {
                    @Override public void on(int code, final int rev, final String token,
                                              final String url, String body) {
                        final boolean ok = code == 200 && rev > 0;
                        if (ok) {
                            try {
                                CloudApi.setToken(app, s.guid, token);
                                ScheduleStore.setCloudState(app, s.guid, rev, true);
                            } catch (Throwable ignored) {
                            }
                        }
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (cb != null) cb.on(ok, ok ? "" : ("http " + code), url, rev);
                            }
                        });
                    }
                });
    }
}
