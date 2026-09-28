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
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 4×2「本周课表」桌面插件：周一 ~ 周日每天一行摘要。
 *
 * 数据：只读 CourseCache（手机本地课表缓存），不连手环也能显示。
 * 假期 / 调休：当天是假期 → 摘要显示「假期名 · 休息」；是调休 → 显示「班」+ 按目标周几的课。
 * 高亮：今天那一行用主色加粗。
 * 刷新：系统每 30 分钟 + App 打开/课表更新时主动 refreshAll。
 */
public class WeekWidgetProvider extends AppWidgetProvider {

    private static final int[] ROW = {R.id.wrow1, R.id.wrow2, R.id.wrow3, R.id.wrow4,
            R.id.wrow5, R.id.wrow6, R.id.wrow7};
    private static final int[] BAR = {R.id.wbar1, R.id.wbar2, R.id.wbar3, R.id.wbar4,
            R.id.wbar5, R.id.wbar6, R.id.wbar7};
    private static final int[] DAY = {R.id.wday1, R.id.wday2, R.id.wday3, R.id.wday4,
            R.id.wday5, R.id.wday6, R.id.wday7};
    private static final int[] DATE = {R.id.wdate1, R.id.wdate2, R.id.wdate3, R.id.wdate4,
            R.id.wdate5, R.id.wdate6, R.id.wdate7};
    private static final int[] SUM = {R.id.wsum1, R.id.wsum2, R.id.wsum3, R.id.wsum4,
            R.id.wsum5, R.id.wsum6, R.id.wsum7};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews rv = build(ctx);
        for (int id : ids) {
            mgr.updateAppWidget(id, rv);
        }
    }

    /** App 更新了本地课表 / 假期开关变化后调用 */
    static void refreshAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WeekWidgetProvider.class));
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
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_week);
        rv.setInt(R.id.widget_root, "setBackgroundResource",
                Ui.isDark() ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
        rv.setTextColor(R.id.widget_title, Ui.TEXT);
        rv.setTextColor(R.id.widget_week_date, Ui.MUTED);
        rv.setTextColor(R.id.widget_week_foot, Ui.MUTED);

        List<CourseCache.Course> all = CourseCache.load(ctx);
        int today = CourseCache.todayIndex();
        SimpleDateFormat df = new SimpleDateFormat("M.d", Locale.CHINA);

        Calendar mon = Calendar.getInstance();
        mon.add(Calendar.DAY_OF_MONTH, -today);

        for (int d = 0; d < 7; d++) {
            Calendar day = (Calendar) mon.clone();
            day.add(Calendar.DAY_OF_MONTH, d);

            boolean isToday = (d == today);
            String badge = Holiday.badge(ctx, day);
            int override = Holiday.resolveDay(ctx, day);

            rv.setViewVisibility(ROW[d], View.VISIBLE);
            rv.setTextViewText(DAY[d], CourseCache.WEEK[d].substring(0, 1));
            rv.setTextViewText(DATE[d], df.format(day.getTime()) + (badge.length() > 0 ? " " + badge : ""));

            String sum;
            int sumColor = Ui.TEXT;
            if (override == Holiday.HOLIDAY) {
                String name = Holiday.holidayName(ctx, day);
                sum = (name.length() > 0 ? name : "假期") + " · 休息";
                sumColor = Ui.OK;
            } else {
                List<CourseCache.Course> list = CourseCache.coursesOfDay(all,
                        override >= 0 ? override : d);
                if (override >= 0) {
                    rv.setTextViewText(DATE[d], df.format(day.getTime()) + " 班");
                }
                if (list.isEmpty()) {
                    sum = "无课";
                    sumColor = Ui.MUTED;
                } else {
                    StringBuilder sb = new StringBuilder();
                    int shown = Math.min(3, list.size());
                    for (int i = 0; i < shown; i++) {
                        if (i > 0) {
                            sb.append(" · ");
                        }
                        sb.append(list.get(i).name);
                    }
                    if (list.size() > shown) {
                        sb.append(" 等").append(list.size()).append("门");
                    }
                    sum = sb.toString();
                }
            }

            rv.setTextViewText(SUM[d], sum);
            rv.setTextColor(SUM[d], isToday ? Ui.ACCENT : sumColor);
            rv.setInt(BAR[d], "setBackgroundColor", isToday ? Ui.ACCENT : Ui.LINE);
        }

        long at = CourseCache.savedAt(ctx);
        rv.setTextViewText(R.id.widget_week_foot, at > 0
                ? "更新于 " + CourseCache.ago(at)
                : "连接手环后自动更新");

        rv.setOnClickPendingIntent(R.id.widget_root, TodayWidgetProvider.openApp(ctx));
        return rv;
    }
}
