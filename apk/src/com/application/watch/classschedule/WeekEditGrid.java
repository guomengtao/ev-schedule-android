package com.application.watch.classschedule;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 编辑态周网格（单 View 自绘，方案 v2 第 9 章）：
 *   - 7 列，每天的课程按开始时间从上往下堆叠；行数 = 全周最多一天的课程数（至少 3 行）
 *   - 课程色块：Ui.courseColor 同色，课名 + 教室两行；右上角 × 删除角标
 *   - 空格子：淡色圆角 + 淡淡的 +，点击即"在该位置添加"
 *   - 手势：点色块编辑 / 点 × 删除 / 点空格添加 / 长按色块拖到任意格（换天 + 落格） / 长按表头清空一天
 *
 * 拖动语义：松手落到某个格 cell(day,row) → 换天并落格；落格行会映射为对应节次时间
 * （见 CourseEditUtil.timeForRow），支持同一天内任意行重排。
 */
public class WeekEditGrid extends View {

    /** 交互回调（宿主 = CourseEditActivity） */
    public interface Callback {
        void onCourseTap(CourseCache.Course c);

        void onCourseDelete(CourseCache.Course c);

        void onEmptyTap(int day, int row);

        void onCourseLongPress(CourseCache.Course c);

        void onCourseMoved(CourseCache.Course c, int newDay, int newRow);

        void onDayHeaderLongPress(int day);
    }

    private static final String[] DAYS = {"一", "二", "三", "四", "五", "六", "日"};

    private final int cellH;        // 色块高度
    private final int gap;          // 行/列间距
    private final int pad;          // 网格内边距
    private final int headH;        // 表头高度
    private final float sp;         // sp → px

    private final Paint pBlock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBadge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    private List<CourseCache.Course> courses = new ArrayList<>();
    /** 每天按开始时间排好序的课程（渲染与命中都用它） */
    private final List<List<CourseCache.Course>> byDay = new ArrayList<>();
    private int rows = 3;
    private boolean shortNames = false;

    private Callback cb;

    // 手势状态
    private CourseCache.Course pressed;
    private boolean dragMode = false;
    private float downX, downY;
    private float dragX, dragY;
    private int dragSourceDay = -1;
    private int headerDay = -1;
    private static final int LONG_PRESS_MS = 350;
    private final Runnable longPressFire = new Runnable() {
        @Override public void run() {
            if (pressed != null && cb != null) {
                dragMode = true;
                dragSourceDay = pressed.day;
                invalidate();
                cb.onCourseLongPress(pressed);
            }
        }
    };
    private final Runnable headerLongFire = new Runnable() {
        @Override public void run() {
            if (headerDay >= 0 && cb != null) {
                cb.onDayHeaderLongPress(headerDay);
                headerDay = -1;
            }
        }
    };

    public WeekEditGrid(Context c) {
        super(c);
        cellH = Ui.dp(c, 46);
        gap = Ui.dp(c, 3);
        pad = Ui.dp(c, 2);
        headH = Ui.dp(c, 24);
        sp = getResources().getDisplayMetrics().scaledDensity;
        for (int i = 0; i < 7; i++) {
            byDay.add(new ArrayList<CourseCache.Course>());
        }
    }

    public void setCallback(Callback l) {
        cb = l;
    }

    public void setData(List<CourseCache.Course> data, boolean shortNameMode) {
        courses = data == null ? new ArrayList<CourseCache.Course>() : data;
        shortNames = shortNameMode;
        rebuild();
        requestLayout();
        invalidate();
    }

    /** 重新按天分组 + 排序 + 计算行数 */
    public void rebuild() {
        for (int d = 0; d < 7; d++) {
            byDay.get(d).clear();
        }
        int max = 0;
        for (CourseCache.Course c : courses) {
            if (c == null || c.day < 0 || c.day > 6) {
                continue;
            }
            byDay.get(c.day).add(c);
        }
        for (int d = 0; d < 7; d++) {
            List<CourseCache.Course> col = byDay.get(d);
            Collections.sort(col, new Comparator<CourseCache.Course>() {
                @Override public int compare(CourseCache.Course a, CourseCache.Course b) {
                    int[] ma = CourseCache.minutes(a.time);
                    int[] mb = CourseCache.minutes(b.time);
                    int sa = ma == null ? Integer.MAX_VALUE : ma[0];
                    int sb = mb == null ? Integer.MAX_VALUE : mb[0];
                    return sa - sb;
                }
            });
            if (col.size() > max) {
                max = col.size();
            }
        }
        rows = Math.max(3, max);
    }

    public int gridHeightPx() {
        return pad * 2 + headH + rows * cellH + Math.max(0, rows - 1) * gap;
    }

    /** 当前行数（宿主判断某天还有没有空档用） */
    public int rowCount() {
        return rows;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = View.MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, gridHeightPx());
    }

    // ======================= 绘制 =======================

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int colW = (w - pad * 2 - gap * 6) / 7;

        // 表头
        pText.setTextAlign(Paint.Align.CENTER);
        for (int d = 0; d < 7; d++) {
            float cx = pad + colW / 2f + d * (colW + gap);
            pText.setColor(Ui.MUTED);
            pText.setTextSize(10.5f * sp);
            pText.setFakeBoldText(true);
            cv.drawText(DAYS[d], cx, pad + headH * 0.72f, pText);
        }

        // 单元格背景：空格淡底 + 淡 + 号
        pBlock.setStyle(Paint.Style.FILL);
        int emptyBg = (Ui.CARD & 0x00FFFFFF) | 0x0A000000;
        for (int d = 0; d < 7; d++) {
            for (int r = 0; r < rows; r++) {
                if (courseAt(d, r) != null) {
                    continue;
                }
                float left = pad + d * (colW + gap);
                float top = pad + headH + r * (cellH + gap);
                rf.set(left, top, left + colW, top + cellH);
                pBlock.setColor(emptyBg);
                cv.drawRoundRect(rf, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), pBlock);
                pText.setColor((Ui.MUTED & 0x00FFFFFF) | 0x66000000);
                pText.setTextSize(14f * sp);
                pText.setFakeBoldText(false);
                cv.drawText("+", left + colW / 2f, top + cellH / 2f + 5 * sp, pText);
            }
        }

        // 课程色块
        for (int d = 0; d < 7; d++) {
            List<CourseCache.Course> col = byDay.get(d);
            for (int r = 0; r < col.size(); r++) {
                CourseCache.Course c = col.get(r);
                float left = pad + d * (colW + gap);
                float top = pad + headH + r * (cellH + gap);
                boolean dragging = dragMode && pressed == c;
                drawBlock(cv, c, left, top, colW, cellH, dragging);
            }
        }

        // 拖动幽灵：跟随手指的半透明色块
        if (dragMode && pressed != null) {
            drawBlock(cv, pressed, dragX - colW / 2f, dragY - cellH / 2f, colW, cellH, false);
        }
    }

    private void drawBlock(Canvas cv, CourseCache.Course c, float left, float top,
                           float colW, float h, boolean ghost) {
        int color = Ui.courseColor(c.name);
        if (ghost) {
            color = (color & 0x00FFFFFF) | 0xB2000000;
        }
        pBlock.setStyle(Paint.Style.FILL);
        pBlock.setColor(color);
        rf.set(left, top, left + colW, top + h);
        cv.drawRoundRect(rf, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), pBlock);

        // 文案：单字模式只画一个大字；否则课名（最多 4 字/行）+ 教室
        pText.setTextAlign(Paint.Align.CENTER);
        String name = CourseCache.displayName(c.name, shortNames);
        float cx = left + colW / 2f;
        if (shortNames && name.length() > 0) {
            pText.setColor(0xFFFFFFFF);
            pText.setTextSize(15f * sp);
            pText.setFakeBoldText(true);
            cv.drawText(name.substring(0, 1), cx, top + h / 2f + 5.5f * sp, pText);
        } else {
            pText.setColor(0xFFFFFFFF);
            pText.setTextSize(10f * sp);
            pText.setFakeBoldText(true);
            float ty = top + (colW < Ui.dp(getContext(), 52) ? h * 0.42f : h * 0.38f);
            cv.drawText(ellipsize(name, 4), cx, ty, pText);
            if (c.location != null && c.location.length() > 0 && colW >= Ui.dp(getContext(), 46)) {
                pText.setTextSize(8.5f * sp);
                pText.setFakeBoldText(false);
                pText.setColor((0xFFFFFFFF & 0x00FFFFFF) | 0xCC000000);
                cv.drawText(ellipsize(c.location, 6), cx, ty + 11.5f * sp, pText);
            }
        }

        // × 删除角标（拖动中的幽灵不画）
        if (!ghost) {
            float bw = Ui.dp(getContext(), 15);
            float bx = left + colW - bw - Ui.dp(getContext(), 3);
            float by = top + Ui.dp(getContext(), 3);
            pBadge.setStyle(Paint.Style.FILL);
            pBadge.setColor((0x00000000 & 0x00FFFFFF) | 0x40000000);
            cv.drawCircle(bx + bw / 2f, by + bw / 2f, bw / 2f, pBadge);
            pBadge.setColor(0xFFFFFFFF);
            pBadge.setTextSize(9.5f * sp);
            pBadge.setFakeBoldText(true);
            pBadge.setTextAlign(Paint.Align.CENTER);
            cv.drawText("×", bx + bw / 2f, by + bw / 2f + 3.5f * sp, pBadge);
        }
    }

    private static String ellipsize(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ======================= 命中与手势 =======================

    private int colW() {
        return (getWidth() - pad * 2 - gap * 6) / 7;
    }

    /** day 列第 r 行的课程；越界/无课返回 null */
    private CourseCache.Course courseAt(int day, int row) {
        if (day < 0 || day > 6 || row < 0) {
            return null;
        }
        List<CourseCache.Course> col = byDay.get(day);
        return row < col.size() ? col.get(row) : null;
    }

    /** 点位 → {day, row}；不在网格区返回 null */
    private int[] hitCell(float x, float y) {
        int colW = colW();
        int top = pad + headH;
        if (x < pad || y < top) {
            return null;
        }
        int d = (int) ((x - pad) / (colW + gap));
        if (d < 0 || d > 6 || (x - pad) - d * (colW + gap) > colW) {
            return null;
        }
        int r = (int) ((y - top) / (cellH + gap));
        if (r < 0 || r >= rows) {
            return null;
        }
        return new int[]{d, r};
    }

    /** 点位是否落在某课程色块的 × 角标内 */
    private boolean hitDeleteBadge(float x, float y) {
        int[] cell = hitCell(x, y);
        if (cell == null) {
            return false;
        }
        CourseCache.Course c = courseAt(cell[0], cell[1]);
        if (c == null) {
            return false;
        }
        int colW = colW();
        float bw = Ui.dp(getContext(), 15);
        float left = pad + cell[0] * (colW + gap);
        float top = pad + headH + cell[1] * (cellH + gap);
        // 视觉 15dp，热区放大到 32dp（方案 11 章风险表）
        float cx = left + colW - bw / 2f - Ui.dp(getContext(), 3);
        float cy = top + bw / 2f + Ui.dp(getContext(), 3);
        float half = Ui.dp(getContext(), 16);
        return Math.abs(x - cx) <= half && Math.abs(y - cy) <= half;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                dragMode = false;
                dragX = x;
                dragY = y;
                int[] cell = hitCell(x, y);
                pressed = cell == null ? null : courseAt(cell[0], cell[1]);
                EvLog.i("grid DOWN x=" + (int) x + " y=" + (int) y
                        + " pressed=" + (pressed == null ? "null" : pressed.name)
                        + " headerDay=" + headerDayAt(x, y));
                if (pressed != null) {
                    postDelayed(longPressFire, LONG_PRESS_MS);
                } else {
                    headerDay = headerDayAt(x, y);
                    if (headerDay >= 0) {
                        postDelayed(headerLongFire, LONG_PRESS_MS);
                    }
                }
                return true;

            case android.view.MotionEvent.ACTION_MOVE:
                if (dragMode) {
                    dragX = x;
                    dragY = y;
                    invalidate();
                    return true;
                }
                // 长按等待期滑动超阈值 → 取消长按（普通滑动留给外层 ScrollView）
                if ((pressed != null || headerDay >= 0)
                        && (Math.abs(x - downX) > Ui.dp(getContext(), 12)
                            || Math.abs(y - downY) > Ui.dp(getContext(), 12))) {
                    removeCallbacks(longPressFire);
                    removeCallbacks(headerLongFire);
                    pressed = null;
                    headerDay = -1;
                }
                return pressed != null; // 按在色块上：吞掉滑动，避免把"拖动"漏成滚动

            case android.view.MotionEvent.ACTION_UP: {
                removeCallbacks(longPressFire);
                removeCallbacks(headerLongFire);
                headerDay = -1;
                CourseCache.Course c = pressed;
                pressed = null;
                if (dragMode) {
                    dragMode = false;
                    int[] target = hitCell(x, y);
                    if (c != null && cb != null && target != null) {
                        List<CourseCache.Course> src = byDay.get(dragSourceDay);
                        int srcRow = -1;
                        if (src != null) {
                            srcRow = src.indexOf(c);
                        }
                        // 目标格与来源格（天/行）不同才视为移动 → 支持落回任意格，含同一天内重排
                        if (target[1] != srcRow || target[0] != dragSourceDay) {
                            cb.onCourseMoved(c, target[0], target[1]);
                        }
                    }
                    invalidate();
                    return true;
                }
                if (cb == null) {
                    return true;
                }
                if (hitDeleteBadge(x, y) && c != null) {
                    cb.onCourseDelete(c);
                    return true;
                }
                int[] cur = hitCell(x, y);
                if (cur == null) {
                    return true;
                }
                CourseCache.Course tapped = courseAt(cur[0], cur[1]);
                if (tapped != null) {
                    cb.onCourseTap(tapped);
                } else {
                    cb.onEmptyTap(cur[0], cur[1]);
                }
                return true;
            }

            case android.view.MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressFire);
                removeCallbacks(headerLongFire);
                pressed = null;
                headerDay = -1;
                dragMode = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(ev);
    }

    /** 长按表头 → 清空一天：表头区域单独做命中（交给外层处理，这里只暴露判定） */
    public int headerDayAt(float x, float y) {
        if (y > pad + headH || x < pad) {
            return -1;
        }
        int colW = colW();
        int d = (int) ((x - pad) / (colW + gap));
        return (d >= 0 && d <= 6) ? d : -1;
    }
}