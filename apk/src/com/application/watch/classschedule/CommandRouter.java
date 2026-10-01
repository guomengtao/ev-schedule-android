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

    private static MediaPlayer player;          // 找手机响铃
    private static PowerManager.WakeLock wakeLock;
    private static Runnable autoStop;
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

    /** 响铃（ALARM 音轨穿透静音）+ 震动 + 亮屏，30 秒后自动停止，通知栏可手动停 */
    public static synchronized void findPhone(Context c) {
        final Context app = c.getApplicationContext();
        stopFindPhone(app);
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

            Vibrator v = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{600, 400}, 0));
            } else if (v != null) {
                v.vibrate(new long[]{600, 400}, 0);
            }

            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "ev:findphone");
                wakeLock.acquire(30_000L);
            }

            Notification n = new Notification.Builder(app, Notifications.CH_REMIND)
                    .setSmallIcon(R.drawable.ic_bell_ring)
                    .setContentTitle("正在响铃找手机")
                    .setContentText("找到后点此停止")
                    .setOngoing(true)
                    .addAction(0, "停止", stopPi(app))
                    .build();
            nm(app).notify(FIND_NOTIFY_ID, n);

            autoStop = new Runnable() {
                @Override public void run() {
                    stopFindPhone(app);
                }
            };
            MAIN.postDelayed(autoStop, 30_000L);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "findPhone fail", t);
        }
    }

    public static synchronized void stopFindPhone(Context c) {
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
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent stopPi(Context c) {
        Intent it = new Intent(c, ToolboxReceiver.class).setAction(ToolboxReceiver.ACTION_FIND_STOP);
        return PendingIntent.getBroadcast(c, 7002, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
                .setContentTitle("Countdown Finished")
                .setContentText("Time's up — tap to open toolbox")
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .addAction(0, "Dismiss", dismissPi)
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