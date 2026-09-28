package com.application.watch.classschedule;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 4×1「下一节课」桌面插件 —— 最小占地，最容易长期留在桌面。
 *
 * 逻辑：
 *   正在上的课 → 标签「进行中」，右侧显示结束时间
 *   下一节课   → 标签「下一节」，右侧显示开始时间 +「还有 N 分钟」
 *   没课了     → 「今天没课」/「今天的课都结束了」
 */
public class NextWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, rv);
        }
    }

    static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NextWidgetProvider.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            RemoteViews rv = build(ctx);
            for (int id : ids) {
                mgr.updateAppWidget(id, rv);
            }
        } catch (Throwable ignored) {
        }
    }

    static RemoteViews build(Context ctx) {
        Ui.applyTheme(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_next);
        rv.setInt(R.id.next_root, "setBackgroundResource",
                Ui.isDark() ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
        rv.setTextColor(R.id.next_label, Ui.MUTED);
        rv.setTextColor(R.id.next_name, Ui.TEXT);
        rv.setTextColor(R.id.next_room, Ui.MUTED);
        rv.setTextColor(R.id.next_time, Ui.ACCENT);
        rv.setTextColor(R.id.next_sub, Ui.MUTED);

        int today = CourseCache.todayIndex();
        // 假期 / 调休（移植自 EV）：假期 → 显示「假期中」；调休 → 按目标周几的课表找下一节
        int override = Holiday.resolveToday(ctx);
        boolean holiday = (override == Holiday.HOLIDAY);
        List<CourseCache.Course> day = CourseCache.coursesOfDay(CourseCache.load(ctx),
                override >= 0 ? override : today);
        if (holiday) {
            day = new java.util.ArrayList<>();
        }
        int now = CourseCache.nowMinutes();

        CourseCache.Course hit = null;
        boolean live = false;
        int[] hm = null;
        for (CourseCache.Course c : day) {
            int[] m = CourseCache.minutes(c.time);
            if (m == null) {
                continue;
            }
            if (now < m[0]) {
                hit = c;
                hm = m;
                break;                      // 下一节
            }
            if (now < m[1]) {
                hit = c;
                hm = m;
                live = true;
                break;                      // 正在上
            }
        }

        if (hit == null) {
            String noClass = day.isEmpty()
                    ? (holiday ? (Holiday.holidayName(ctx, java.util.Calendar.getInstance()) + " · 今天没课")
                               : "今天没课")
                    : "今天的课都结束了";
            rv.setTextViewText(R.id.next_label, holiday ? "假期中" : "休息");
            rv.setTextViewText(R.id.next_name, noClass);
            rv.setTextViewText(R.id.next_room, "");
            rv.setTextViewText(R.id.next_time, "");
            rv.setTextViewText(R.id.next_sub, "");
            rv.setInt(R.id.next_bar, "setBackgroundColor", Ui.MUTED);
        } else {
            int color = Ui.courseColor(hit.name);
            rv.setTextViewText(R.id.next_name, hit.name);
            rv.setTextViewText(R.id.next_room, hit.location);
            rv.setInt(R.id.next_bar, "setBackgroundColor", color);
            if (live) {
                rv.setTextViewText(R.id.next_label, "进行中");
                rv.setTextViewText(R.id.next_time, CourseCache.hm(hm[1]));
                rv.setTextViewText(R.id.next_sub, "结束");
            } else {
                rv.setTextViewText(R.id.next_label, "下一节");
                rv.setTextViewText(R.id.next_time, CourseCache.hm(hm[0]));
                int left = hm[0] - now;
                rv.setTextViewText(R.id.next_sub, left >= 60
                        ? (left / 60) + " 小时 " + (left % 60) + " 分后"
                        : left + " 分钟后");
            }
        }

        rv.setOnClickPendingIntent(R.id.next_root, TodayWidgetProvider.openApp(ctx));
        return rv;
    }
}
