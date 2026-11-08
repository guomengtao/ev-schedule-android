package com.application.watch.classschedule;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/**
 * 第9节晚课（晚辅 / 晚自习）设置页：一张 6 周 × 5 天的格子表。
 *
 * <p>交互核心是「点格子填科目」，不是 30 次新建课程 —— 用户对着学校发的那张表一格一格点，
 * 一屏填完。填完立即保存并生效（首页、周视图、桌面插件、上课提醒都走同一个注入点）。
 *
 * <p>默认只做周一到周五（你给的表就是 5 列）。「社团」这类非学科内容按普通文字存，
 * 不进科目候选库，也不参与任何特殊逻辑。
 */
public class RotationActivity extends Activity {

    /** 只做工作日：周一到周五 */
    private static final String[] DAY_LABEL = {"一", "二", "三", "四", "五"};

    private RotationStore.Template tpl;
    private LinearLayout gridBox;
    private TextView weekView;
    private TextView countView;
    private TextView timeView;
    private TextView enableView;
    private TextView cycleView;

    /** 这套课表的 id：晚课按课表分开存，不再全局一份 */
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    private String scheduleId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        // 候选科目来自课名库：先进已有课表里的课名，库空且无历史时才用内置 12 门种子
        try {
            CourseNameStore.ensureSeeded(this);
            CourseNameStore.mergeFromSchedules(this);
        } catch (Throwable ignored) {
        }
        scheduleId = getIntent().getStringExtra(EXTRA_SCHEDULE_ID);
        if (scheduleId == null) {
            scheduleId = "";
        }
        tpl = RotationStore.load(this, scheduleId);
        buildUi();
        render();
    }

    // ======================= 骨架 =======================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.CANVAS);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));

        // ── 顶栏：返回 | 标题 ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(Ui.TEXT);
        back.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        head.addView(Ui.text(this, "晚课设置", 16.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        TextView sub = Ui.text(this,
                "给这套课表补一节「每天都有、内容按周期轮换」的课，默认排在白天的课之后。只在本机显示，不同步手环。",
                11.5f, Ui.MUTED, false);
        sub.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, Ui.dp(this, 8));
        sub.setLineSpacing(Ui.dp(this, 2), 1f);
        root.addView(sub);

        // ── 状态卡：本周第几周 + 时段 + 开关 ──
        LinearLayout status = Ui.card(this);
        status.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        status.addView(row("本周", "", new View.OnClickListener() {
            @Override public void onClick(View v) { calibrate(); }
        }, new RowHolder() {
            @Override void bind(TextView value) { weekView = value; }
        }));

        status.addView(divider());

        status.addView(row("上课时段", "", new View.OnClickListener() {
            @Override public void onClick(View v) { pickTime(); }
        }, new RowHolder() {
            @Override void bind(TextView value) { timeView = value; }
        }));

        status.addView(divider());

        status.addView(row("轮换周期", "", new View.OnClickListener() {
            @Override public void onClick(View v) { pickCycle(); }
        }, new RowHolder() {
            @Override void bind(TextView value) { cycleView = value; }
        }));

        status.addView(divider());

        status.addView(row("启用", "", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleEnabled(); }
        }, new RowHolder() {
            @Override void bind(TextView value) { enableView = value; }
        }));

        root.addView(status);
        root.addView(Ui.space(this, Ui.GAP_MD));

        countView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        countView.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        root.addView(countView);

        // ── 格子表（按屏宽均分，不横滑：横滑会让人看不到右边两列）──
        ScrollView vScroll = new ScrollView(this);
        vScroll.setVerticalScrollBarEnabled(false);

        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        vScroll.addView(gridBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(vScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tip = Ui.text(this,
                "填完自动保存。每套课表各有一份晚课，互不影响。",
                11f, Ui.MUTED, false);
        tip.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        tip.setSingleLine(true);
        root.addView(tip);

        setContentView(root);
    }

    /** 一行「标签 + 值 + 点击」的简易抽象，避免三段重复代码 */
    private abstract static class RowHolder {
        abstract void bind(TextView value);
    }

    private View row(String label, String value, View.OnClickListener l, RowHolder holder) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        r.setMinimumHeight(Ui.dp(this, 44));
        r.setOnClickListener(l);

        r.addView(Ui.text(this, label, 13f, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = Ui.text(this, value, 13f, Ui.ACCENT, false);
        tv.setGravity(Gravity.END);
        r.addView(tv);
        holder.bind(tv);
        return r;
    }

    /** 周期 1~6 周：不是每所学校都六周一轮，一周/三周同样常见 */
    private void pickCycle() {
        final int[] opts = {1, 2, 3, 4, 5, 6};
        String[] labels = new String[opts.length];
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " 周一轮";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("轮换周期")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        tpl.cycle = opts[which];
                        tpl.ensureCycle();
                        RotationStore.save(RotationActivity.this, scheduleId, tpl);
                        render();
                    }
                })
                .show();
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        return v;
    }

    // ======================= 渲染 =======================

    private void render() {
        int week = RotationStore.weekOf(tpl, Calendar.getInstance());
        if (weekView != null) {
            weekView.setText("第 " + week + " 周（" + tpl.cycle + " 周一轮）›");
        }
        if (cycleView != null) {
            cycleView.setText(tpl.cycle + " 周一轮 ›");
        }
        if (timeView != null) {
            timeView.setText(tpl.time + " ›");
        }
        if (enableView != null) {
            enableView.setText(tpl.enabled ? "已开启" : "已关闭");
            enableView.setTextColor(tpl.enabled ? Ui.OK : Ui.MUTED);
        }
        if (countView != null) {
            countView.setText("已填 " + tpl.filled() + " / " + (tpl.cycle * DAY_LABEL.length) + " 格");
        }
        renderGrid(week);
    }

    private void renderGrid(int currentWeek) {
        if (gridBox == null) {
            return;
        }
        gridBox.removeAllViews();

        int labelW = Ui.dp(this, 42);
        int cellH = Ui.dp(this, 44);
        int gap = Ui.dp(this, 4);

        // 表头：空一格 + 周一到周五（各占 1 份宽，屏幕多宽就多宽）
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.addView(labelCell(""), new LinearLayout.LayoutParams(labelW, Ui.dp(this, 26)));
        for (String d : DAY_LABEL) {
            TextView t = labelCell(d);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 26), 1f);
            lp.rightMargin = gap;
            headRow.addView(t, lp);
        }
        gridBox.addView(headRow);

        for (int w = 1; w <= tpl.cycle; w++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);

            TextView wl = labelCell("第" + w + "周");
            if (w == currentWeek) {
                wl.setTextColor(Ui.ACCENT);
            }
            line.addView(wl, new LinearLayout.LayoutParams(labelW, cellH));

            for (int d = 0; d < DAY_LABEL.length; d++) {
                final int fw = w;
                final int fd = d;
                String name = tpl.cell(w, d);
                boolean empty = (name == null || name.isEmpty());
                TextView cell = new TextView(this);
                cell.setText(empty ? "·" : name);
                cell.setTextSize(empty ? 12f : 12.5f);
                cell.setGravity(Gravity.CENTER);
                cell.setSingleLine(true);
                if (empty) {
                    cell.setTextColor(Ui.MUTED);
                    cell.setBackground(Ui.round(0x00000000, 10, Ui.LINE, this));
                } else {
                    cell.setTextColor(Ui.TEXT);
                    cell.setBackground(Ui.round(Ui.CARD, 10, Ui.LINE, this));
                }
                cell.setContentDescription("第" + w + "周 周" + DAY_LABEL[d] + " " + (empty ? "空" : name));
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { editCell(fw, fd); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellH - gap, 1f);
                lp.rightMargin = gap;
                lp.bottomMargin = gap;
                line.addView(cell, lp);
            }
            gridBox.addView(line);
        }
    }

    private TextView labelCell(String s) {
        TextView t = Ui.text(this, s, 11f, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    // ======================= 操作 =======================

    private void toggleEnabled() {
        tpl.enabled = !tpl.enabled;
        RotationStore.save(this, scheduleId, tpl);
        render();
    }

    /** 点格子：弹科目编辑（候选 + 手打 + 清除） */
    private void editCell(final int week, final int day) {
        final Dialog dlg = new Dialog(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 12));

        box.addView(Ui.text(this, "第 " + week + " 周 · 周" + DAY_LABEL[day], 15f, Ui.TEXT, true));

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(15f);
        input.setHint("科目名，如 地理");
        input.setText(tpl.cell(week, day));
        input.setBackground(Ui.round(Ui.CANVAS, 10, Ui.LINE, this));
        input.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(this, 10);
        box.addView(input, ip);

        // 候选科目：取课名库（读不到就退化为空，不阻塞手打）
        List<String> names = null;
        try {
            names = CourseNameStore.names(this);
        } catch (Throwable ignored) {
        }
        if (names != null && !names.isEmpty()) {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int shown = 0;
            for (final String n : names) {
                if (shown++ >= 14) {
                    break;
                }
                TextView chip = Ui.text(this, n, 12f, Ui.TEXT, false);
                chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
                chip.setBackground(Ui.round(Ui.CANVAS, 10, Ui.LINE, this));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { input.setText(n); input.setSelection(n.length()); }
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                cp.rightMargin = Ui.dp(this, 6);
                row.addView(chip, cp);
            }
            hs.addView(row);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.topMargin = Ui.dp(this, 8);
            box.addView(hs, hp);
        }

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Ui.dp(this, 12);

        Button clear = Ui.button(this, "清除", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                tpl.setCell(week, day, "");
                RotationStore.save(RotationActivity.this, scheduleId, tpl);
                dlg.dismiss();
                render();
            }
        });
        clear.setBackground(Ui.round(0x00000000, 12, Ui.LINE, this));
        clear.setTextColor(Ui.TEXT);
        foot.addView(clear, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)));

        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        okp.leftMargin = Ui.dp(this, 10);
        Button ok = Ui.button(this, "确定", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String v2 = input.getText().toString().trim();
                tpl.setCell(week, day, v2);
                if (!v2.isEmpty()) {
                    tpl.enabled = true;   // 填了格子就默认开启，避免「设了没生效」
                }
                RotationStore.save(RotationActivity.this, scheduleId, tpl);
                dlg.dismiss();
                render();
            }
        });
        foot.addView(ok, okp);
        box.addView(foot, fp);

        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            w.setAttributes(attrs);
        }
        dlg.show();
    }

    /** 手动校正「本周算第几周」：考试周 / 运动会让实际周次推偏，不能只靠日期算 */
    private void calibrate() {
        final Dialog dlg = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 12));

        box.addView(Ui.text(this, "本周算第几周", 15f, Ui.TEXT, true));
        TextView hint = Ui.text(this,
                "填本周实际是第几周，之后自动往后推。考试周、运动会放假错过几天时用这个校正。",
                11.5f, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(this, 2), 1f);
        box.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(RotationStore.weekOf(tpl, Calendar.getInstance())));
        input.setBackground(Ui.round(Ui.CANVAS, 10, Ui.LINE, this));
        input.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Ui.dp(this, 10);
        box.addView(input, ip);

        Button ok = Ui.button(this, "确定", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int w;
                try {
                    w = Integer.parseInt(input.getText().toString().trim());
                } catch (Throwable e) {
                    Toast.makeText(RotationActivity.this, "请输入 1-" + tpl.cycle + " 的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (w < 1 || w > tpl.cycle) {
                    Toast.makeText(RotationActivity.this, "请输入 1-" + tpl.cycle + " 的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
                tpl.calibWeek = w;
                tpl.calibDate = RotationStore.mondayDateString(Calendar.getInstance());
                RotationStore.save(RotationActivity.this, scheduleId, tpl);
                dlg.dismiss();
                render();
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        op.topMargin = Ui.dp(this, 12);
        box.addView(ok, op);

        dlg.setContentView(box);
        Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = win.getAttributes();
            attrs.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            win.setAttributes(attrs);
        }
        dlg.show();
    }

    /** 第9节时段：先选开始，再选结束（默认 +45 分钟） */
    private void pickTime() {
        int[] m = null;
        try {
            m = CourseCache.minutes(tpl.time);
        } catch (Throwable ignored) {
        }
        int sh = (m == null) ? 19 : m[0] / 60;
        int sm = (m == null) ? 0 : m[0] % 60;

        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, final int startH, final int startM) {
                int defEnd = startH * 60 + startM + 45;
                new TimePickerDialog(RotationActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker v2, int endH, int endM) {
                        int s = startH * 60 + startM;
                        int e = endH * 60 + endM;
                        if (e <= s) {
                            Toast.makeText(RotationActivity.this, "结束要晚于开始，已按 45 分钟设", Toast.LENGTH_SHORT).show();
                            e = s + 45;
                        }
                        tpl.time = hm(s) + " - " + hm(Math.min(e, 24 * 60 - 1));
                        RotationStore.save(RotationActivity.this, scheduleId, tpl);
                        render();
                    }
                }, (defEnd / 60) % 24, defEnd % 60, true).show();
            }
        }, sh, sm, true).show();
    }

    private static String hm(int minutes) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }
}
