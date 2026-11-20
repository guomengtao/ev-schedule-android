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

        CloudApi.publish(app, s.guid,
                s.deviceId == null ? "" : s.deviceId,
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
