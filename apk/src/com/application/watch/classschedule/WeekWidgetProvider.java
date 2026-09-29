package com.application.watch.classschedule;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 4x2「本周课表」桌面插件：时间列 + 周一~周日 7 列的网格（与首页的周课表同构）。
 *
 * 数据：只读 CourseCache（手机本地课表缓存），不连手环也能显示。
 * 行 = 该周出现的时间段（去重排序，最多 5 行）；列 = 星期，格子按时间段对齐，底色 = 课程区分色。
 * 假期 / 调休：假期列清空（表头带「休」）；调休列显示目标星期几的课（表头带「班」）。
 * 刷新：系统每 30 分钟 + App 打开/课表更新时主动 refreshAll。
 */
public class WeekWidgetProvider extends AppWidgetProvider {

    private static final int GRID_ROWS = 5;
    private static final int[] WHDR = {R.id.whdr1, R.id.whdr2, R.id.whdr3, R.id.whdr4,
            R.id.whdr5, R.id.whdr6, R.id.whdr7};
    private static final int[] WTIME = {R.id.wtime1, R.id.wtime2, R.id.wtime3, R.id.wtime4,
            R.id.wtime5};

    /** wcellRxC 的 id（R = 行时间段，C = 列星期，1 = 周一） */
    private static int cellId(int row, int day) {
        try {
            return R.id.class.getField("wc" + row + "x" + day).getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

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
        boolean shortName = CourseCache.shortNameMode(ctx);

        // 本周 7 天的日期（含假期 / 调休覆盖）
        Calendar[] days = new Calendar[7];
        int[] override = new int[7];
        String[] hname = new String[7]; // 该列若是假期日 → 假期名（写入当天列）
        Calendar mon = Calendar.getInstance();
        mon.add(Calendar.DAY_OF_MONTH, -today);
        for (int d = 0; d < 7; d++) {
            days[d] = (Calendar) mon.clone();
            days[d].add(Calendar.DAY_OF_MONTH, d);
            override[d] = Holiday.resolveDay(ctx, days[d]);
            hname[d] = Holiday.holidayName(ctx, days[d]);
        }

        // 表头：假期列「休」（绿）、调休列「班」（琥珀）、今天主色
        String[] heads = {"一", "二", "三", "四", "五", "六", "日"};
        for (int d = 0; d < 7; d++) {
            String badge = Holiday.badge(ctx, days[d]);
            boolean isToday = (d == today);
            String label = heads[d] + badge;
            int color = isToday ? Ui.ACCENT
                    : ("休".equals(badge) ? 0xFF16A34A : ("班".equals(badge) ? 0xFFD97706 : Ui.MUTED));
            rv.setTextViewText(WHDR[d], label);
            rv.setTextColor(WHDR[d], color);
        }

        // 该周出现的时间段：去重 + 按开始时间排序，最多 GRID_ROWS 行
        Map<Integer, String> slotMap = new TreeMap<>();
        for (CourseCache.Course c : all) {
            if (c.time == null || c.time.length() == 0) {
                continue;
            }
            String s = CourseCache.shortTime(c.time);
            int[] m = CourseCache.minutes(c.time);
            slotMap.put(m != null ? m[0] : 0, s);
        }
        List<String> slots = new ArrayList<>(slotMap.values());
        while (slots.size() < GRID_ROWS) {
            slots.add("");
        }

        // 每列的课（假期列空，调休列用目标星期几的课）
        List<List<CourseCache.Course>> byDay = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            int idx = override[d] >= 0 ? override[d] : d;
            byDay.add(override[d] == Holiday.HOLIDAY ? new ArrayList<CourseCache.Course>()
                    : CourseCache.coursesOfDay(all, idx));
        }

        // 网格：行 = 时间段，列 = 星期；底色 = 课程区分色，假期列整列留空
        for (int r = 0; r < GRID_ROWS; r++) {
            String slot = r < slots.size() ? slots.get(r) : "";
            rv.setTextViewText(WTIME[r], slot);
            for (int d = 0; d < 7; d++) {
                int id = cellId(r + 1, d + 1);
                if (id == 0) {
                    continue;
                }
                CourseCache.Course hit = null;
                if (slot.length() > 0) {
                    for (CourseCache.Course c : byDay.get(d)) {
                        if (slot.equals(CourseCache.shortTime(c.time))) {
                            hit = c;
                            break;
                        }
                    }
                }
                if (hit == null && override[d] == Holiday.HOLIDAY && r == 1
                        && hname[d] != null && hname[d].length() > 0) {
                    // 假期列第一格：写假期名（那天放假就写那天）
                    rv.setTextViewText(id, hname[d]);
                    rv.setInt(id, "setBackgroundColor", 0x1422C55E);
                    rv.setTextColor(id, 0xFF166534);
                    rv.setInt(id, "setGravity", android.view.Gravity.CENTER);
                    rv.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
                } else if (hit == null) {
                    rv.setTextViewText(id, "");
                    rv.setInt(id, "setBackgroundColor", 0x00000000);
                } else {
                    String disp = CourseCache.displayName(hit.name, shortName);
                    rv.setTextViewText(id, disp);
                    rv.setInt(id, "setBackgroundColor", Ui.courseColor(hit.name));
                    rv.setTextColor(id, 0xFFFFFFFF);
                    // 单字放大居中；多字恢复常规字号（RemoteViews 同一布局复用，两档都要显式设）
                    rv.setInt(id, "setGravity", android.view.Gravity.CENTER);
                    rv.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP,
                            disp.length() <= 1 ? 13f : 9f);
                }
            }
        }

        long at = CourseCache.savedAt(ctx);
        String foot = at > 0 ? "更新于 " + CourseCache.ago(at) : "连接手环后自动更新";
        rv.setTextViewText(R.id.widget_week_foot, foot);
        Calendar sun = (Calendar) mon.clone();
        sun.add(Calendar.DAY_OF_MONTH, 6);
        String dateLabel = (mon.get(Calendar.MONTH) + 1) + "." + mon.get(Calendar.DAY_OF_MONTH) + "-"
                + (sun.get(Calendar.MONTH) + 1) + "." + sun.get(Calendar.DAY_OF_MONTH);
        // 本周有假期 → 日期旁并排显示假期名（如「10.1-10.7 · 国庆」）
        java.util.LinkedHashSet<String> hs = new java.util.LinkedHashSet<>();
        for (int d = 0; d < 7; d++) {
            String hn = Holiday.holidayName(ctx, days[d]);
            if (hn.length() > 0) {
                hs.add(hn);
            }
        }
        if (!hs.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String hn : hs) {
                if (sb.length() > 0) {
                    sb.append("·");
                }
                sb.append(hn);
            }
            dateLabel = dateLabel + " · " + sb;
        }
        rv.setTextViewText(R.id.widget_week_date, dateLabel);

        rv.setOnClickPendingIntent(R.id.widget_root, TodayWidgetProvider.openApp(ctx));
        return rv;
    }
}
