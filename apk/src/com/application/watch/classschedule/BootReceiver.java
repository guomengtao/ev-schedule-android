package com.application.watch.classschedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用被更新后重排提醒（闹钟不跨重启存活，必须补排）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? "" : intent.getAction();
        if (a != null && (a.equals(Intent.ACTION_BOOT_COMPLETED)
                || a.equals(Intent.ACTION_MY_PACKAGE_REPLACED))) {
            Reminders.reschedule(context);
        }
    }
}
