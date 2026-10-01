package com.application.watch.classschedule;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Toolbox broadcasts: find-phone stop button + countdown fire/dismiss.
 * Registered in Manifest so alarms fire even when process is dead.
 */
public class ToolboxReceiver extends BroadcastReceiver {

    public static final String ACTION_FIND_STOP = "ev.toolbox.FIND_STOP";
    public static final String ACTION_COUNTDOWN_FIRE = "ev.toolbox.COUNTDOWN_FIRE";
    public static final String ACTION_COUNTDOWN_DISMISS = "ev.toolbox.COUNTDOWN_DISMISS";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_FIND_STOP.equals(a)) {
            CommandRouter.stopFindPhone(context);
        } else if (ACTION_COUNTDOWN_FIRE.equals(a)) {
            CommandRouter.onCountdownFire(context);
        } else if (ACTION_COUNTDOWN_DISMISS.equals(a)) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(9102);
            CommandRouter.dismissCountdownFired(context);
        }
    }
}