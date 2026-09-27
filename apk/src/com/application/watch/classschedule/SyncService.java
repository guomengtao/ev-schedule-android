package com.application.watch.classschedule;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务：让进程在后台活着，从而在手环主动推送留言时能收到并提醒。
 *
 * 说明（诚实版）：
 *   - 它只保证"**进程活着**"，不做任何轮询；
 *   - 被系统/ROM 强杀后仍收不到（没有任何推送通道能唤醒 App）；
 *   - 代价：一条常驻通知 + 少量常驻内存/电量。
 */
public class SyncService extends Service {

    public static final String PREFS = "ev_settings";
    public static final String KEY_BG = "bg_service";

    // ======================= 开关 =======================

    public static boolean enabled(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BG, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BG, on).apply();
        } catch (Throwable ignored) {
        }
    }

    public static void startIfEnabled(Context c) {
        if (!enabled(c)) {
            return;
        }
        try {
            Intent i = new Intent(c, SyncService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, SyncService.class));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 页面退到后台时调用：把「手环主动消息」的观察者交给服务/应用上下文处理。
     * 这样即使页面不在前台，留言也会被发现并以**系统通知**的形式提醒（仍按 id 去重）。
     */
    public static void installObserverIfEnabled(Context c) {
        try {
            if (!enabled(c)) {
                SyncEngine.get(c).setObserver(null);
                return;
            }
            final Context app = c.getApplicationContext();
            SyncEngine.get(c).setObserver(new SyncEngine.Observer() {
                @Override public void onMessage(String json) {
                    MessageActivity.handleUnsolicited(app, json);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ======================= 生命周期 =======================

    @Override
    public void onCreate() {
        super.onCreate();
        Notifications.ensureChannels(this);
        goForeground("正在等待手环消息");
        // 与页面共用同一套「按 id 去重」逻辑（重复到达只提醒一次）
        SyncEngine.get(this).setObserver(new SyncEngine.Observer() {
            @Override public void onMessage(String json) {
                MessageActivity.handleUnsolicited(getApplicationContext(), json);
            }
        });
        tryConnectIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground("正在等待手环消息");
        tryConnectIfNeeded();
        return START_STICKY;
    }

    private void goForeground(String text) {
        try {
            Notification n = Notifications.service(this, text);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_SERVICE, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifications.ID_SERVICE, n);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 进程被系统拉起后 nodeId 会丢：这里静默重建一次连接（不轮询，只在启动时做一次） */
    private void tryConnectIfNeeded() {
        try {
            SyncEngine e = SyncEngine.get(this);
            if (!e.sdkReady() || e.hasNode()) {
                return;
            }
            e.connect(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        try {
            SyncEngine.get(this).setObserver(null);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
