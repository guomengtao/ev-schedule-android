package com.application.watch.classschedule;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 4×2「今日课程」桌面插件。
 *
 * 数据：只读 CourseCache（手机本地课表缓存），不连手环也能显示上次的数据。
 * 规则：最多显示 4 门；正在上的课名用课程区分色高亮，已结束的整行灰显。
 * 刷新：系统每 30 分钟（updatePeriodMillis）+ App 打开/课表更新时主动 refreshAll。
 */
public class TodayWidgetProvider extends AppWidgetProvider {

    private static final int[] ROW = {R.id.row1, R.id.row2, R.id.row3, R.id.row4};
    private static final int[] BAR = {R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4};
    private static final int[] TIME = {R.id.time1, R.id.time2, R.id.time3, R.id.time4};
    private static final int[] NAME = {R.id.name1, R.id.name2, R.id.name3, R.id.name4};
    private static final int[] ROOM = {R.id.room1, R.id.room2, R.id.room3, R.id.room4};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, rv);
        }
    }

    /** App 更新了本地课表后调用：刷新桌面上所有本类插件 */
    static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TodayWidgetProvider.class));
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
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_today);
        rv.setInt(R.id.widget_root, "setBackgroundResource",
                Ui.isDark() ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
        rv.setTextColor(R.id.widget_title, Ui.TEXT);
        rv.setTextColor(R.id.widget_date, Ui.MUTED);
        rv.setTextColor(R.id.widget_foot, Ui.MUTED);

        int today = CourseCache.todayIndex();
        // 假期 / 调休（移植自 EV）：假期 → 显示假期卡片；调休 → 按目标周几的课表
        int override = Holiday.resolveToday(ctx);
        boolean holiday = (override == Holiday.HOLIDAY);
        List<CourseCache.Course> day = CourseCache.coursesOfDay(CourseCache.load(ctx),
                override >= 0 ? override : today);
        int now = CourseCache.nowMinutes();

        SimpleDateFormat df = new SimpleDateFormat("M月d日", Locale.CHINA);
        if (holiday) {
            String name = Holiday.holidayName(ctx, java.util.Calendar.getInstance());
            rv.setTextViewText(R.id.widget_title, "假期中");
            rv.setTextViewText(R.id.widget_date,
                    df.format(new Date()) + " " + (name.length() > 0 ? name : "假期") + " · 今天没课");
        } else {
            rv.setTextViewText(R.id.widget_date, df.format(new Date()) + " "
                    + CourseCache.WEEK[override >= 0 ? override : today]
                    + (override >= 0 ? " (调休)" : ""));
        }

        int shown = holiday ? 0 : Math.min(ROW.length, day.size());
        for (int i = 0; i < ROW.length; i++) {
            if (i >= shown) {
                rv.setViewVisibility(ROW[i], View.GONE);
                continue;
            }
            CourseCache.Course c = day.get(i);
            int[] m = CourseCache.minutes(c.time);
            boolean past = m != null && m[1] <= now;
            boolean live = m != null && now >= m[0] && now < m[1];
            int color = Ui.courseColor(c.name);

            rv.setViewVisibility(ROW[i], View.VISIBLE);
            rv.setInt(BAR[i], "setBackgroundColor", color);
            rv.setTextViewText(TIME[i], CourseCache.shortTime(c.time));
            rv.setTextViewText(NAME[i], c.name);
            rv.setTextViewText(ROOM[i], c.location);
            // 已结束灰显；正在上的课用课程色高亮课名（高亮度过强的色在浅底压一档）
            rv.setTextColor(NAME[i], past ? Ui.MUTED
                    : (live ? Ui.readableAccent(color, Ui.isDark()) : Ui.TEXT));
            rv.setTextColor(TIME[i], Ui.MUTED);
            rv.setTextColor(ROOM[i], past ? Ui.MUTED : Ui.MUTED);
        }

        long at = CourseCache.savedAt(ctx);
        rv.setTextViewText(R.id.widget_foot, at > 0
                ? "更新于 " + CourseCache.ago(at)
                : "连接手环后自动更新");

        rv.setOnClickPendingIntent(R.id.widget_root, openApp(ctx));
        return rv;
    }

    /** 点插件打开 App 首页（复用已存在的任务栈，不新开实例） */
    static PendingIntent openApp(Context ctx) {
        Intent it = new Intent(ctx, HomeActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 0, it, flags);
    }
}
