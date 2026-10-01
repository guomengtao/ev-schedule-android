package com.application.watch.classschedule;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 单课编辑底部面板（方案 v2 4.3）：不跳页，半透明露出课表，改完立即看到色块变化。
 *
 * 字段：课程名（必填，空名红字拦截）+ 最近使用 chips + 星期 chip 单选
 *      + 时间模板 chips / 自定义起止 + 教室 / 老师
 * 操作：[复制到其他天]（仅编辑已有课时）[删除]（红色，仅编辑已有课时）[确定]
 *
 * 「确定」只回调宿主更新【草稿】，不落盘 —— 文案用「确定」避免用户误以为整张课表已保存。
 */
public class CourseSheetDialog {

    /** 面板结果回调 */
    public interface Listener {
        /** 确定保存（course 已构建好；original == null 表示新增） */
        void onSaved(CourseCache.Course course, CourseCache.Course original);

        /** 删除（仅编辑已有课时可能触发） */
        void onDeleted(CourseCache.Course original);

        /** 「复制到其他天」：picked[0..6] 为勾选的目标星期 */
        void onCopyCourse(CourseCache.Course source, boolean[] picked);
    }

    private final Context ctx;
    private final Listener listener;
    /** null = 新增课程；非 null = 编辑该课程 */
    private final CourseCache.Course original;

    private final List<CourseCache.Course> draftAll;   // 冲突检测 / 最近课名默认值用
    private final List<String> recentNames;

    private EditText nameInput;
    private EditText locInput;
    private EditText teacherInput;
    private EditText startInput;
    private EditText endInput;
    private TextView errorView;
    private LinearLayout customTimeRow;

    private int selDay = 0;
    /** 选中模板下标；-1 = 自定义 */
    private int selTemplate = 0;
    private final List<TextView> dayChips = new ArrayList<>();
    private final List<TextView> timeChips = new ArrayList<>();

    private Dialog dlg;
    private Dialog copyDlg;

    public CourseSheetDialog(Context ctx, CourseCache.Course editTarget,
                             List<CourseCache.Course> draftAll, Listener listener) {
        this(ctx, editTarget, null, draftAll, listener);
    }

    /**
     * @param editTarget 非 null = 编辑该课程；null = 新增
     * @param prefill    新增时的预填（星期/时间来自点中的空格位置，方案 3.3）；editTarget 非 null 时忽略
     */
    public CourseSheetDialog(Context ctx, CourseCache.Course editTarget,
                             CourseCache.Course prefill,
                             List<CourseCache.Course> draftAll, Listener listener) {
        this.ctx = ctx;
        this.original = editTarget;
        this.prefill = prefill;
        this.draftAll = draftAll;
        this.listener = listener;
        this.recentNames = CourseEditUtil.recentNames(ctx, 8);
        build();
    }

    private final CourseCache.Course prefill;

    public void show() {
        dlg.show();
        if (original == null) {
            nameInput.requestFocus();
        }
    }

    // ======================= 构建 =======================

    private void build() {
        Context c = ctx;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, 18), Ui.dp(c, 10), Ui.dp(c, 18), Ui.dp(c, 16));

        // 拖动条
        View grip = new View(c);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 4));
        grip.setBackground(Ui.round(Ui.LINE, 2, 0, c));
        LinearLayout gripRow = new LinearLayout(c);
        gripRow.setGravity(Gravity.CENTER_HORIZONTAL);
        gripRow.addView(grip, glp);
        box.addView(gripRow);
        box.addView(Ui.space(c, 12));

        ScrollView scroll = new ScrollView(c);
        scroll.addView(buildForm(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(scroll);

        // 底部操作行：[复制到其他天] [删除] [确定]
        box.addView(Ui.space(c, 10));
        LinearLayout ops = new LinearLayout(c);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.CENTER_VERTICAL);

        if (original != null) {
            TextView copyBtn = Ui.text(c, "复制到其他天", 11f, Ui.ACCENT, true);
            copyBtn.setGravity(Gravity.CENTER);
            copyBtn.setPadding(Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12));
            copyBtn.setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, c));
            copyBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showCopyToDays(); }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(c, 8);
            ops.addView(copyBtn, clp);

            TextView delBtn = Ui.text(c, "删除", 12.5f, Ui.ERR, true);
            delBtn.setGravity(Gravity.CENTER);
            delBtn.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
            delBtn.setBackground(Ui.round(Ui.CARD2, 10, 0, c));
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    if (listener != null) {
                        listener.onDeleted(original);
                    }
                }
            });
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.rightMargin = Ui.dp(c, 8);
            ops.addView(delBtn, dlp);
        }

        TextView okBtn = Ui.text(c, "确定", 13f, 0xFFFFFFFF, true);
        okBtn.setGravity(Gravity.CENTER);
        okBtn.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12));
        okBtn.setBackground(Ui.round(Ui.ACCENT, 10, 0, c));
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirm(); }
        });
        ops.addView(okBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(ops);

        // 面板壳：上圆角卡片、屏幕底部
        LinearLayout shell = new LinearLayout(c);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(topRounded());
        shell.addView(box);

        dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(shell);
        dlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.45f);
        WindowManager.LayoutParams lp = dlg.getWindow().getAttributes();
        lp.gravity = Gravity.BOTTOM;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        dlg.getWindow().setAttributes(lp);
        dlg.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        fill(original);
    }

    private View buildForm() {
        Context c = ctx;
        LinearLayout form = new LinearLayout(c);
        form.setOrientation(LinearLayout.VERTICAL);

        // ── 课程名 ──
        form.addView(fieldLabel("课程名 *"));
        nameInput = input(c, original == null ? "" : original.name);
        form.addView(nameInput);
        errorView = Ui.text(c, "", 11f, Ui.ERR, false);
        errorView.setPadding(Ui.dp(c, 2), Ui.dp(c, 3), 0, 0);
        form.addView(errorView);

        // ── 最近使用 chips ──
        if (!recentNames.isEmpty()) {
            form.addView(Ui.space(c, 2));
            TextView recLabel = Ui.text(c, "最近使用", 10.5f, Ui.MUTED, false);
            recLabel.setPadding(Ui.dp(c, 2), 0, 0, Ui.dp(c, 4));
            form.addView(recLabel);
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String n : recentNames) {
                row.addView(chip(c, n, new View.OnClickListener() {
                    @Override public void onClick(View v) { applyRecent(n); }
                }));
            }
            hs.addView(row);
            form.addView(hs, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        form.addView(Ui.space(c, 8));

        // ── 星期 ──
        form.addView(fieldLabel("星期"));
        LinearLayout dayRow1 = new LinearLayout(c);
        LinearLayout dayRow2 = new LinearLayout(c);
        for (int d = 0; d < 7; d++) {
            final int day = d;
            TextView ch = chip(c, "周" + CourseEditUtil.DAY_SHORT[d], new View.OnClickListener() {
                @Override public void onClick(View v) { selectDay(day); }
            });
            dayChips.add(ch);
            (d < 4 ? dayRow1 : dayRow2).addView(ch);
        }
        form.addView(dayRow1);
        form.addView(dayRow2);
        form.addView(Ui.space(c, 8));

        // ── 时间（横向单行滚动，chip 不换行挤压）──
        form.addView(fieldLabel("时间"));
        HorizontalScrollView tScroll = new HorizontalScrollView(c);
        tScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tRow = new LinearLayout(c);
        tRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < CourseEditUtil.TEMPLATES.length; i++) {
            final int idx = i;
            String label = CourseEditUtil.TEMPLATES[i][0] + " " + CourseEditUtil.TEMPLATES[i][1];
            TextView ch = chipNoWrap(c, label, new View.OnClickListener() {
                @Override public void onClick(View v) { selectTemplate(idx); }
            });
            timeChips.add(ch);
            tRow.addView(ch);
        }
        TextView customChip = chipNoWrap(c, "自定义", new View.OnClickListener() {
            @Override public void onClick(View v) { selectTemplate(-1); }
        });
        timeChips.add(customChip);
        tRow.addView(customChip);
        tScroll.addView(tRow);
        form.addView(tScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        customTimeRow = new LinearLayout(c);
        customTimeRow.setOrientation(LinearLayout.HORIZONTAL);
        customTimeRow.setGravity(Gravity.CENTER_VERTICAL);
        startInput = input(c, "");
        startInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        startInput.setHint("08:00");
        endInput = input(c, "");
        endInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        endInput.setHint("09:35");
        TextView dash = Ui.text(c, "—", 13f, Ui.MUTED, false);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ip.topMargin = Ui.dp(c, 6);
        ip.rightMargin = Ui.dp(c, 8);
        customTimeRow.addView(startInput, ip);
        customTimeRow.addView(dash);
        LinearLayout.LayoutParams ip2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ip2.topMargin = Ui.dp(c, 6);
        ip2.leftMargin = Ui.dp(c, 8);
        customTimeRow.addView(endInput, ip2);
        customTimeRow.setVisibility(View.GONE);
        form.addView(customTimeRow);
        form.addView(Ui.space(c, 8));

        // ── 教室 / 老师 ──
        form.addView(fieldLabel("教室 / 老师"));
        LinearLayout two = new LinearLayout(c);
        two.setOrientation(LinearLayout.HORIZONTAL);
        locInput = input(c, original == null ? "" : original.location);
        locInput.setHint("教室");
        teacherInput = input(c, original == null ? "" : original.teacher);
        teacherInput.setHint("老师");
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = Ui.dp(c, 8);
        two.addView(locInput, l1);
        two.addView(teacherInput, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        form.addView(two);

        return form;
    }

    private View fieldLabel(String s) {
        TextView t = Ui.text(ctx, s, 11.5f, Ui.MUTED, true);
        t.setPadding(Ui.dp(ctx, 2), 0, 0, Ui.dp(ctx, 5));
        return t;
    }

    private EditText input(Context c, String text) {
        EditText e = new EditText(c);
        e.setText(text);
        e.setTextSize(13.5f);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, c));
        e.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        return e;
    }

    /** 可点小 chip（默认描边态；选中态由 refreshChip 刷） */
    private TextView chip(Context c, String label, View.OnClickListener l) {
        TextView t = Ui.text(c, label, 11.5f, Ui.TEXT, false);
        t.setPadding(Ui.dp(c, 11), Ui.dp(c, 7), Ui.dp(c, 11), Ui.dp(c, 7));
        t.setBackground(Ui.round(Ui.CARD2, 16, Ui.LINE, c));
        if (l != null) {
            t.setOnClickListener(l);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(c, 6);
        lp.bottomMargin = Ui.dp(c, 4);
        t.setLayoutParams(lp);
        return t;
    }

    /** 单行不换行的 chip（时间模板行用，避免长文案被挤成竖排） */
    private TextView chipNoWrap(Context c, String label, View.OnClickListener l) {
        TextView t = chip(c, label, l);
        t.setSingleLine(true);
        return t;
    }

    private android.graphics.drawable.GradientDrawable topRounded() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.CARD);
        float r = Ui.dp(ctx, 22);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    // ======================= 状态填充与选择 =======================

    private void fill(CourseCache.Course c) {
        if (c == null) {
            if (prefill != null) {
                // 点空格添加：预填该列星期 + 该列下一常用时段（课程名留给用户/chips）
                selectDay(Math.max(0, Math.min(6, prefill.day)));
                int tpl = CourseEditUtil.matchTemplate(prefill.time);
                if (tpl >= 0) {
                    selectTemplate(tpl);
                } else {
                    selectTemplate(-1);
                    String t = prefill.time == null ? "" : prefill.time;
                    int i = t.indexOf('-');
                    startInput.setText(i > 0 ? t.substring(0, i).trim() : t);
                    endInput.setText(i > 0 ? t.substring(i + 1).trim() : "");
                }
            } else {
                selectDay(todayOrDefault());
                selectTemplate(0);
            }
            return;
        }
        nameInput.setText(c.name);
        locInput.setText(c.location);
        teacherInput.setText(c.teacher);
        selectDay(Math.max(0, Math.min(6, c.day)));
        int tpl = CourseEditUtil.matchTemplate(c.time);
        if (tpl >= 0) {
            selectTemplate(tpl);
        } else {
            selectTemplate(-1);
            // 非标时间拆进自定义输入框
            String t = c.time == null ? "" : c.time;
            int i = t.indexOf('-');
            startInput.setText(i > 0 ? t.substring(0, i).trim() : t);
            endInput.setText(i > 0 ? t.substring(i + 1).trim() : "");
        }
    }

    private int todayOrDefault() {
        int t = CourseCache.todayIndex();
        return (t >= 0 && t <= 6) ? t : 0;
    }

    private void selectDay(int day) {
        selDay = day;
        for (int d = 0; d < dayChips.size(); d++) {
            refreshChip(dayChips.get(d), d == day);
        }
    }

    private void selectTemplate(int idx) {
        selTemplate = idx;
        for (int i = 0; i < timeChips.size(); i++) {
            refreshChip(timeChips.get(i), i == idx);
        }
        customTimeRow.setVisibility(idx == -1 ? View.VISIBLE : View.GONE);
    }

    private void refreshChip(TextView ch, boolean selected) {
        ch.setTextColor(selected ? 0xFFFFFFFF : Ui.TEXT);
        ch.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        ch.setBackground(Ui.round(selected ? Ui.ACCENT : Ui.CARD2, 16,
                selected ? 0 : Ui.LINE, ctx));
    }

    /** 点最近使用课名 chip：填名字 + 带出该课名已填的教室/老师（方案 3.3） */
    private void applyRecent(String name) {
        nameInput.setText(name);
        errorView.setText("");
        CourseCache.Course last = findLatestByName(name);
        if (last != null) {
            if (last.location.length() > 0 && locInput.getText().toString().trim().length() == 0) {
                locInput.setText(last.location);
            }
            if (last.teacher.length() > 0 && teacherInput.getText().toString().trim().length() == 0) {
                teacherInput.setText(last.teacher);
            }
        }
    }

    /** 该课名最近（草稿里最先出现）的一节课，取它的教室/老师做默认 */
    private CourseCache.Course findLatestByName(String name) {
        if (draftAll != null) {
            for (CourseCache.Course c : draftAll) {
                if (c != null && name.equals(c.name)) {
                    return c;
                }
            }
        }
        return null;
    }

    // ======================= 提交 =======================

    private void confirm() {
        String name = nameInput.getText().toString().trim();
        if (name.length() == 0) {
            errorView.setText("请输入课程名");
            nameInput.requestFocus();
            return;
        }
        String time;
        if (selTemplate >= 0) {
            time = CourseEditUtil.TEMPLATES[selTemplate][1];
        } else {
            time = CourseEditUtil.normalizeRange(
                    startInput.getText().toString().trim(),
                    endInput.getText().toString().trim());
            if (time == null) {
                errorView.setText("时间格式应为 HH:mm，且结束晚于开始");
                return;
            }
        }
        final CourseCache.Course out = new CourseCache.Course();
        out.name = name;
        out.day = selDay;
        out.time = time;
        out.location = locInput.getText().toString().trim();
        out.teacher = teacherInput.getText().toString().trim();

        // 冲突检测：同一天 + 同一时间段 + 同名（排除自己）→ 弹确认不硬拦（方案 5.1）
        if (draftAll != null && isDuplicate(out)) {
            Dialogs.confirm((android.app.Activity) ctx, 0, 0,
                    "该时段已有一节课",
                    "周" + CourseEditUtil.DAY_SHORT[out.day] + " " + time + "\n「" + out.name + "」重复添加",
                    null, "仍然添加", false,
                    new Dialogs.Action() {
                        @Override public void run() { finishSave(out); }
                    });
            return;
        }
        finishSave(out);
    }

    private boolean isDuplicate(CourseCache.Course candidate) {
        for (CourseCache.Course c : draftAll) {
            if (c == null || c == original) {
                continue;
            }
            if (c.day == candidate.day && c.name.equals(candidate.name)
                    && c.time != null && c.time.equals(candidate.time)) {
                return true;
            }
        }
        return false;
    }

    private void finishSave(CourseCache.Course out) {
        CourseEditUtil.recordNameUse(ctx, out.name);
        dlg.dismiss();
        if (listener != null) {
            listener.onSaved(out, original);
        }
    }

    // ======================= 复制到其他天 =======================

    private void showCopyToDays() {
        if (original == null) {
            return;
        }
        final boolean[] picked = new boolean[7];
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(ctx, 22), Ui.dp(ctx, 20), Ui.dp(ctx, 22), Ui.dp(ctx, 8));
        box.setBackground(Ui.round(Ui.CARD, 22, 0, ctx));
        box.addView(Ui.text(ctx, "把这门课复制到哪几天？", 15.5f, Ui.TEXT, true));
        box.addView(Ui.space(ctx, 4));
        box.addView(Ui.text(ctx, "课名、时间、教室、老师一起复制，一天一格",
                12f, Ui.MUTED, false));
        box.addView(Ui.space(ctx, 12));
        LinearLayout grid1 = new LinearLayout(ctx);
        LinearLayout grid2 = new LinearLayout(ctx);
        for (int d = 0; d < 7; d++) {
            if (d == original.day) {
                continue; // 源列不选
            }
            final int day = d;
            TextView ch = chip(ctx, "周" + CourseEditUtil.DAY_SHORT[d], new View.OnClickListener() {
                @Override public void onClick(View v) {
                    picked[day] = !picked[day];
                    refreshChip((TextView) v, picked[day]);
                }
            });
            (grid1.getChildCount() < 4 ? grid1 : grid2).addView(ch);
        }
        box.addView(grid1);
        box.addView(grid2);
        box.addView(Ui.space(ctx, 14));
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = Ui.button(ctx, "取消", false, new View.OnClickListener() {
            @Override public void onClick(View v) { copyDlg.dismiss(); }
        });
        cancel.setBackground(Ui.round(0x00000000, 12, Ui.LINE, ctx));
        cancel.setTextColor(Ui.TEXT);
        btns.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(ctx, 42), 1f));
        Button ok = Ui.button(ctx, "复制", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyDlg.dismiss();
                if (listener != null) {
                    listener.onCopyCourse(original, picked);
                }
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(ctx, 42), 1f);
        blp.leftMargin = Ui.dp(ctx, 10);
        btns.addView(ok, blp);
        box.addView(btns);

        copyDlg = new Dialog(ctx);
        copyDlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        copyDlg.setContentView(box);
        copyDlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        copyDlg.getWindow().setDimAmount(0.55f);
        copyDlg.getWindow().setLayout(Ui.dp(ctx, 310), WindowManager.LayoutParams.WRAP_CONTENT);
        copyDlg.show();
    }
}
