package com.application.watch.classschedule;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

/**
 * 手环遥控指令路由（工具箱）。
 *
 * 手环 → {"action":"cmd","type":"find_phone|phone_status|mute|countdown","minutes":N}
 *      → 本类白名单分发执行 → {"action":"cmd_result","type":...,"ok":...,"text":...} 回传手环
 * 手机端「工具箱」页（ToolboxActivity）直接调执行器，同一套逻辑两处复用。
 *
 * 安全：只认白名单 type，未知类型静默忽略；无对外发送/涉账号操作。
 */
public final class CommandRouter {

    private static final String TAG = "EVToolbox";
    private static final int FIND_NOTIFY_ID = 9101;

    /**
     * 「找手机」单次响铃 / 震动的**硬上限**（毫秒）。
     * 到点一律强制停止 —— 由「Handler 快路径 + AlarmManager 硬兜底 + 震动的有限波形」三重保证，
     * 绝不允许一直响下去（见 findPhone 注释）。
     */
    private static final long FIND_MAX_MS = 30_000L;
    private static final int FIND_STOP_ALARM_CODE = 7005;   // AlarmManager 兜底停止
    private static final int FIND_OPEN_CODE = 7010;         // 点通知/全屏意图打开关闭页

    private static MediaPlayer player;          // 找手机响铃
    private static PowerManager.WakeLock wakeLock;
    private static Runnable autoStop;
    /** 当前是否正在「找手机」响铃（FindPhoneActivity 据此自动关闭） */
    private static volatile boolean findingActive = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CommandRouter() {
    }

    // ======================= 手环消息入口（返回 true = 已消费） =======================

    public static boolean handle(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"cmd".equals(o.optString("action"))) {
                return false;
            }
            String type = o.optString("type");
            String text;
            if ("find_phone".equals(type)) {
                findPhone(c);
                text = "手机正在响铃";
            } else if ("phone_status".equals(type)) {
                text = statusText(c);
            } else if ("mute".equals(type)) {
                text = toggleMute(c);
            } else if ("countdown".equals(type)) {
                countdown(c, Math.max(1, o.optInt("minutes", 5)));
                text = "倒计时 " + Math.max(1, o.optInt("minutes", 5)) + " 分钟已设置";
            } else {
                return true; // cmd 动作但未知类型：消费掉，不进留言
            }
            android.util.Log.i("EVToolbox", "cmd: " + type);
            // 回执手环（尽力而为）
            try {
                JSONObject r = new JSONObject();
                r.put("action", "cmd_result");
                r.put("type", type);
                r.put("ok", true);
                r.put("text", text);
                SyncEngine.get(c).send(r.toString(), null);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ======================= 找手机 =======================

    /**
     * 找手机：响铃（ALARM 音轨，穿透静音/勿扰）+ 震动 + 亮屏。
     *
     * 停止保障（三重，杜绝「一直响」）：
     *   ① Handler 快路径：FIND_MAX_MS 后停（进程活着时最快生效）；
     *   ② AlarmManager 硬兜底：即使 Doze 让 Handler 延后，闹钟也会到点强制停；
     *   ③ 震动用**有限时长波形**：即便进程被杀，系统的震动器也会自己播完就停
     *      （这是最容易被忽略的一条 —— 无限循环的震动会脱离 App 进程继续抖）。
     *
     * 可关闭：响铃同时弹出一个**全屏提示页**（FindPhoneActivity），点「停止响铃」立即关闭；
     * 通知也可划掉 / 点「停止响铃」立即停。
     */
    public static synchronized void findPhone(Context c) {
        final Context app = c.getApplicationContext();
        try {
            stopFindPhone(app);
        } catch (Throwable ignored) {
        }

        boolean started = false;

        // ---- ① 响铃：ALARM 音轨 + 循环 ----
        try {
            MediaPlayer p = new MediaPlayer();
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            p.setDataSource(app, uri);
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build()); // 勿扰/静音下也能响
            p.setLooping(true);
            p.prepare();
            p.start();
            player = p;
            started = true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "findPhone: ring fail", t);
        }

        // ---- ② 震动：有限时长波形（自带上限，与 FIND_MAX_MS 等长）----
        try {
            startBoundedVibration(app);
            started = true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "findPhone: vibrate fail", t);
        }

        // ---- ③ 亮屏（锁屏也能看到找手机页）----
        // ⚠️ 独立守卫：acquire 需要 android.permission.WAKE_LOCK。
        //    历史事故（2026-10-04 真机）：清单漏声明该权限 → acquire 抛 SecurityException →
        //    把整段 findPhone 打断在「响铃已开始、通知/弹窗/兜底停都还没做」的中间态 →
        //    手机一直响下去且无法从通知栏停止。故这里必须单独 try，绝不允许它拖垮后面的步骤。
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "ev:findphone");
                wakeLock.acquire(FIND_MAX_MS);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "findPhone: wakelock fail（检查 WAKE_LOCK 权限）: " + t);
            wakeLock = null;
        }

        findingActive = started;

        // ---- ④ 通知：高优先级 + 全屏意图 + 可划掉（划掉即停）----
        if (started) {
            try {
                nm(app).notify(FIND_NOTIFY_ID, buildFindNotification(app));
            } catch (Throwable t) {
                android.util.Log.w(TAG, "findPhone: notify fail: " + t);
            }
            // ---- ⑤ 立即弹出可一键关闭的提示页 ----
            try {
                showFindPopup(app);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "findPhone: popup fail: " + t);
            }
        }

        // ---- ⑥ 兜底停：Handler 快路径 + AlarmManager 硬兜底（必须执行）----
        try {
            autoStop = new Runnable() {
                @Override public void run() {
                    stopFindPhone(app);
                }
            };
            MAIN.postDelayed(autoStop, FIND_MAX_MS);
            scheduleStopAlarm(app);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "findPhone: schedule auto-stop fail", t);
        }
    }

    /** 当前是否正在「找手机」响铃 */
    public static boolean isFinding() {
        return findingActive;
    }

    /** 单次找手机响铃/震动的最大秒数（供 UI 展示） */
    public static long findMaxSeconds() {
        return FIND_MAX_MS / 1000L;
    }

    /** 有限时长震动波形：{600,400} 循环铺满 FIND_MAX_MS，repeat=-1 → 播完即止 */
    private static void startBoundedVibration(Context app) {
        Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) {
            return;
        }
        final long on = 600, off = 400;
        java.util.ArrayList<Long> pat = new java.util.ArrayList<Long>();
        long total = 0;
        while (total < FIND_MAX_MS) {
            long turnOn = Math.min(on, FIND_MAX_MS - total);
            if (turnOn <= 0) {
                break;
            }
            pat.add(turnOn);
            total += turnOn;
            if (total >= FIND_MAX_MS) {
                break;
            }
            long turnOff = Math.min(off, FIND_MAX_MS - total);
            if (turnOff <= 0) {
                break;
            }
            pat.add(turnOff);
            total += turnOff;
        }
        long[] arr = new long[pat.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = pat.get(i);
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(arr, -1));   // -1 = 不重复
            } else {
                v.vibrate(arr, -1);
            }
        } catch (Throwable t) {
            // 极端情况下退化为一次性短震动
            try { v.vibrate(600); } catch (Throwable ignored) {}
        }
    }

    private static Notification buildFindNotification(Context app) {
        Notification.Builder b = newBuilder(app, Notifications.CH_REMIND);
        b.setSmallIcon(R.drawable.ic_bell_ring)
                .setContentTitle("正在响铃找手机")
                .setContentText("点「停止响铃」立即关闭，最多 " + (FIND_MAX_MS / 1000) + " 秒自动停止")
                .setOngoing(false)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openFindPi(app))
                .setDeleteIntent(stopPi(app))                 // 划掉通知也停止
                .addAction(0, "停止响铃", stopPi(app));
        if (Build.VERSION.SDK_INT >= 21) {
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        try {
            b.setFullScreenIntent(openFindPi(app), true);      // 锁屏时全屏弹出（Android 14+ 可能降级为横幅）
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    /** 弹出可一键关闭的找手机提示页（尽力而为；被后台启动限制拦截时静默失败） */
    private static void showFindPopup(Context app) {
        try {
            Intent i = new Intent(app, FindPhoneActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            app.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** AlarmManager 硬兜底：到点强制 stopFindPhone（Doze 下也能触发） */
    private static void scheduleStopAlarm(Context app) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            long at = System.currentTimeMillis() + FIND_MAX_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, findStopAlarmPi(app));
                } catch (Throwable t) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, findStopAlarmPi(app));
                }
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, findStopAlarmPi(app));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cancelStopAlarm(Context app) {
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(findStopAlarmPi(app));
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void stopFindPhone(Context c) {
        findingActive = false;
        try {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.cancel();
            }
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
            nm(c).cancel(FIND_NOTIFY_ID);
            if (autoStop != null) {
                MAIN.removeCallbacks(autoStop);
                autoStop = null;
            }
            cancelStopAlarm(c);
        } catch (Throwable ignored) {
        }
    }

    /** 通知「停止响铃」动作 / 划掉通知 → 停止 */
    private static PendingIntent stopPi(Context c) {
        Intent it = new Intent(c, ToolboxReceiver.class).setAction(ToolboxReceiver.ACTION_FIND_STOP);
        return PendingIntent.getBroadcast(c, 7002, it, piFlags());
    }

    /** 点通知/全屏意图 → 打开找手机提示页 */
    private static PendingIntent openFindPi(Context c) {
        Intent it = new Intent(c, FindPhoneActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, FIND_OPEN_CODE, it, piFlags());
    }

    /** AlarmManager 兜底停止的 PendingIntent（与通知动作分开，便于单独 cancel） */
    private static PendingIntent findStopAlarmPi(Context c) {
        Intent it = new Intent(c, ToolboxReceiver.class).setAction(ToolboxReceiver.ACTION_FIND_STOP);
        return PendingIntent.getBroadcast(c, FIND_STOP_ALARM_CODE, it, piFlags());
    }

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    private static Notification.Builder newBuilder(Context c, String channel) {
        return (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(c, channel)
                : new Notification.Builder(c);
    }

    // ======================= 静音切换 =======================

    /** 响铃 ↔ 振动 切换；返回切换后的状态描述 */
    public static String toggleMute(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        boolean toVibrate = am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
        am.setRingerMode(toVibrate ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_NORMAL);
        return toVibrate ? "手机已设为振动" : "手机已恢复响铃";
    }

    // ======================= 手机状态 =======================

    public static String statusText(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            Intent bs = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int st = bs == null ? -1 : bs.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
            sb.append("电量 ").append(pct).append("%").append(charging ? "（充电中）" : "");
        } catch (Throwable ignored) {
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int m = am.getRingerMode();
            sb.append(" · ").append(m == AudioManager.RINGER_MODE_NORMAL ? "响铃"
                    : m == AudioManager.RINGER_MODE_VIBRATE ? "振动" : "静音");
        } catch (Throwable ignored) {
        }
        try {
            SyncEngine e = SyncEngine.get(c);
            sb.append(" · 手环").append(e.connected()
                    ? "已连接" + (e.deviceName.length() > 0 ? "（" + e.deviceName + "）" : "") : "未连接");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    // ======================= 倒计时 =======================

    private static final String PREFS = "toolbox";
    private static final String KEY_END = "countdown_end";
    private static final String KEY_FIRED = "countdown_fired";
    private static final int CD_REQUEST_CODE = 7003;
    private static final int CD_NOTIFY_ID = 9102;

    public static void countdown(Context c, int minutes) {
        final Context app = c.getApplicationContext();
        long at = System.currentTimeMillis() + minutes * 60_000L;
        PendingIntent pi = cdPendingIntent(app);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 21) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, pi), pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_END, at)
                .putBoolean(KEY_FIRED, false)
                .apply();
    }

    /** Cancel the active countdown alarm and clear stored state */
    public static void cancelCountdown(Context c) {
        final Context app = c.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(cdPendingIntent(app));
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_END)
                .remove(KEY_FIRED)
                .apply();
    }

    /** Check if a countdown has fired (user should see the alert) */
    public static boolean countdownFired(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FIRED, false);
    }

    /** Dismiss the fired state after user has acknowledged */
    public static void dismissCountdownFired(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FIRED, false)
                .remove(KEY_END)
                .apply();
    }

    /** Countdown fired: shows a notification with action to open toolbox */
    public static void onCountdownFire(Context c) {
        final Context app = c.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FIRED, true)
                .apply();

        Intent open = new Intent(app, ToolboxActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(app, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dismissI = new Intent(app, ToolboxReceiver.class)
                .setAction(ToolboxReceiver.ACTION_COUNTDOWN_DISMISS);
        PendingIntent dismissPi = PendingIntent.getBroadcast(app, 7004, dismissI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(app, Notifications.CH_REMIND)
                .setSmallIcon(R.drawable.ic_timer)
                .setContentTitle("倒计时结束")
                .setContentText("时间到，点按打开工具箱")
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .addAction(0, "知道了", dismissPi)
                .build();
        nm(app).notify(CD_NOTIFY_ID, n);

        Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(
                        new long[]{300, 150, 300, 150, 500}, -1));
            } else {
                v.vibrate(new long[]{300, 150, 300, 150, 500}, -1);
            }
        }
    }

    /** Remaining seconds (-1 = no active countdown) */
    public static long countdownRemaining(Context c) {
        long end = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_END, 0);
        long left = (end - System.currentTimeMillis()) / 1000;
        return left > 0 ? left : -1;
    }

    private static PendingIntent cdPendingIntent(Context app) {
        return PendingIntent.getBroadcast(app, CD_REQUEST_CODE,
                new Intent(app, ToolboxReceiver.class)
                        .setAction(ToolboxReceiver.ACTION_COUNTDOWN_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}