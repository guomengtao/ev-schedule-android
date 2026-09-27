package com.application.watch.classschedule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 闹钟触发：发提醒 + 排下一个。
 * ⚠️ onReceive 里只做轻量工作；发通知与 reschedule 都是毫秒级，无需 goAsync。
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            CourseCache.Course co = Reminders.upcoming(context);
            if (co != null) {
                Reminders.fire(context, co);
            }
        } catch (Throwable ignored) {
        } finally {
            // 无论命中与否都排下一个（时钟偏移 / 课表变更时也不会断档）
            Reminders.reschedule(context);
        }
    }
}
