package com.application.watch.classschedule;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 通知统一出口。
 *
 * 两个渠道：
 *   ev_service —— 前台服务的常驻通知（低优先级、无声音、不可划掉）
 *   ev_message —— 新留言提醒（高优先级、带声音/震动；App 不在前台时用它替代弹窗）
 */
public final class Notifications {

    public static final String CH_SERVICE = "ev_service";
    public static final String CH_MESSAGE = "ev_message";
    public static final int ID_SERVICE = 1001;
    public static final int ID_MESSAGE = 1002;

    private Notifications() {
    }

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (nm.getNotificationChannel(CH_SERVICE) == null) {
                NotificationChannel svc = new NotificationChannel(CH_SERVICE, "后台运行",
                        NotificationManager.IMPORTANCE_LOW);
                svc.setDescription("保持与手环的连接，以便接收留言提醒");
                svc.setShowBadge(false);
                nm.createNotificationChannel(svc);
            }
            if (nm.getNotificationChannel(CH_MESSAGE) == null) {
                NotificationChannel msg = new NotificationChannel(CH_MESSAGE, "留言提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                msg.setDescription("收到新留言时提醒");
                msg.enableVibration(true);
                nm.createNotificationChannel(msg);
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent activity(Context c, Class<?> cls, int req) {
        Intent i = new Intent(c, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(c, req, i, flags);
    }

    /** 前台服务的常驻通知 */
    public static Notification service(Context c, String text) {
        ensureChannels(c);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(c, CH_SERVICE)
                : new Notification.Builder(c);
        b.setContentTitle("EV 同步器 · 后台运行中")
                .setContentText(text == null ? "正在等待手环消息" : text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(activity(c, HomeActivity.class, 0));
        if (Build.VERSION.SDK_INT >= 21) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }

    /** 新留言提醒（App 不在前台时用） */
    public static void showMessage(Context c, String title, String text) {
        ensureChannels(c);
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(c, CH_MESSAGE)
                    : new Notification.Builder(c);
            b.setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setAutoCancel(true)
                    .setContentIntent(activity(c, MessageActivity.class, 1));
            if (Build.VERSION.SDK_INT >= 21) {
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            nm.notify(ID_MESSAGE, b.build());
        } catch (Throwable ignored) {
        }
    }
}
