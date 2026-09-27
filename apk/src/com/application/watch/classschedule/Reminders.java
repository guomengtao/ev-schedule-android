package com.application.watch.classschedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/**
 * 上课提醒 —— 手机本地闹钟方案。
 *
 * ★ 为什么必须走本地闹钟，而不是"到点连手环拉一次"：
 *   前台服务会被 ROM 省电策略杀掉、被杀期间没有任何通道能唤醒 App、轮询最小 15 分钟且受 Doze 延迟
 *   （详见 docs/message-background-alert-review.md）。唯一可靠路径 =
 *   本地课表数据（CourseCache）+ 系统闹钟（AlarmManager），不依赖任何保活。
 *
 * ★ 模型：任意时刻只排「下一个事件」= 某门课的 (开始时间 - 提前量)。
 *   触发时：找出正处提前窗口内的课 → 系统通知 + （可选）notifyWatch 推手环 → 再排下一个。
 *   重新排定的时机：设置变更 / 课表缓存更新（CourseCache.save）/ 开机（BootReceiver）。
 *
 * ★ 精确性：Android 12+ 需要用户授予「闹钟和提醒」特殊权限才能精确定时；
 *   未授予时降级为 ±1 分钟的 setWindow —— 对上课提醒完全够用，不为此打断用户。
 */
public final class Reminders {

    private static final String PREF = "ev_remind";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LEAD = "lead_minutes";
    public static final String KEY_PUSH_WATCH = "push_watch";

    /** 提前量可选项（分钟） */
    public static final int[] LEAD_STEPS = {0, 5, 10, 15};
    private static final int RC_ALARM = 1001;

    private Reminders() {
    }

    // ======================= 设置存取 =======================

    public static boolean enabled(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(Context c, boolean v) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, v).apply();
        } catch (Throwable ignored) {
        }
        reschedule(c);
    }

    public static int leadMinutes(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_LEAD, 5);
        } catch (Throwable t) {
            return 5;
        }
    }

    public static void setLeadMinutes(Context c, int m) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_LEAD, m).apply();
        } catch (Throwable ignored) {
        }
        reschedule(c);
    }

    public static boolean pushWatch(Context c) {
        try {
            return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PUSH_WATCH, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setPushWatch(Context c, boolean v) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_PUSH_WATCH, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Android 12+ 是否已授予精确闹钟（决定提示文案；未授予也能用，只是 ±1 分钟） */
    public static boolean exactAllowed(Context c) {
        try {
            if (Build.VERSION.SDK_INT < 31) {
                return true;
            }
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    // ======================= 排程 =======================

    static PendingIntent operation(Context c) {
        Intent it = new Intent(c, ReminderReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, RC_ALARM, it, flags);
    }

    /**
     * 重算并排下一个提醒。重复调用安全（先 cancel 再排）。
     * 数据/设置变化、开机后都应调用。
     */
    public static void reschedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = operation(c);
            am.cancel(pi);
            if (!enabled(c)) {
                return;
            }
            long at = nextFireAt(c);
            if (at <= 0) {
                return;
            }
            if (exactAllowed(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 下一个该响的时刻（毫秒）；没有可提醒的返回 -1。扫未来 7 天。 */
    private static long nextFireAt(Context c) {
        List<CourseCache.Course> all = CourseCache.load(c);
        if (all.isEmpty()) {
            return -1;
        }
        int lead = leadMinutes(c);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        int today = CourseCache.todayIndex();
        long now = System.currentTimeMillis();
        long best = -1;
        for (int off = 0; off < 7 && best < 0; off++) {
            int d = (today + off) % 7;
            long dayStart = todayStart + off * 86_400_000L;
            for (CourseCache.Course co : all) {
                if (co == null || co.day != d) {
                    continue;
                }
                int[] m = CourseCache.minutes(co.time);
                if (m == null) {
                    continue;
                }
                long at = dayStart + (m[0] - lead) * 60_000L;
                if (at <= now) {
                    continue;
                }
                if (best < 0 || at < best) {
                    best = at;
                }
            }
        }
        return best;
    }

    // ======================= 触发（由 ReminderReceiver 调） =======================

    /** 找出正处提前窗口 [start-lead, start] 内的课（当天按时间升序，取最早命中的） */
    static CourseCache.Course upcoming(Context c) {
        int lead = leadMinutes(c);
        int nowMin = CourseCache.nowMinutes();
        for (CourseCache.Course co : CourseCache.coursesOfDay(
                CourseCache.load(c), CourseCache.todayIndex())) {
            int[] m = CourseCache.minutes(co.time);
            if (m == null) {
                continue;
            }
            if (nowMin >= m[0] - lead && nowMin <= m[0]) {
                return co;
            }
        }
        return null;
    }

    /** 通知文案 */
    static String text(CourseCache.Course co, Context c) {
        int left = CourseCache.parseHm(co.time) - CourseCache.nowMinutes();
        StringBuilder sb = new StringBuilder();
        sb.append(CourseCache.shortTime(co.time)).append(" 上课 · ").append(co.name);
        if (co.location.length() > 0) {
            sb.append(" · ").append(co.location);
        }
        if (left > 0) {
            sb.append("（还有 ").append(left).append(" 分钟）");
        }
        return sb.toString();
    }

    /** 真正发提醒：手机通知 + （可选）notifyWatch 推手环 */
    static void fire(Context c, CourseCache.Course co) {
        String text = text(co, c);
        Notifications.remind(c, "快上课了", text);
        if (pushWatch(c)) {
            // 尽力推送：手环未连接 / notify 权限未授予时静默失败，不影响手机通知
            SyncEngine.get(c).notifyWatch("上课提醒", text, new SyncEngine.Cb() {
                @Override public void on(boolean ok, String msg) {
                }
            });
        }
    }

    /** 设置页「测试提醒」：立刻发一条，不走闹钟 */
    public static void test(Context c) {
        CourseCache.Course co = upcoming(c);
        if (co == null) {
            // 没有临近的课就用今天第一节做演示
            List<CourseCache.Course> day = CourseCache.coursesOfDay(
                    CourseCache.load(c), CourseCache.todayIndex());
            if (day.isEmpty()) {
                Notifications.remind(c, "上课提醒", "还没有课表数据，先连接手环同步一次");
                return;
            }
            co = day.get(0);
        }
        fire(c, co);
    }
}
