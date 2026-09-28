package com.application.watch.classschedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 工具箱广播：找手机「停止」按钮 + 倒计时到点（Manifest 注册，进程死也能收到闹钟） */
public class ToolboxReceiver extends BroadcastReceiver {

    public static final String ACTION_FIND_STOP = "ev.toolbox.FIND_STOP";
    public static final String ACTION_COUNTDOWN_FIRE = "ev.toolbox.COUNTDOWN_FIRE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_FIND_STOP.equals(a)) {
            CommandRouter.stopFindPhone(context);
        } else if (ACTION_COUNTDOWN_FIRE.equals(a)) {
            CommandRouter.onCountdownFire(context);
        }
    }
}
