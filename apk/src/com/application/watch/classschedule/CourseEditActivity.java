package com.application.watch.classschedule;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 课程可视化编辑页（方案 v2，docs/课程可视化编辑器方案分析.md）：
 *
 *   入口：首页周视图 ✏️ / 课程表管理页 ✏️ / 新建本地课表 / 首页课程详情 [编辑]
 *   模型：进入时深拷贝一份【草稿】，所有增删改拖都只改草稿并实时刷色块；
 *        点「✓ 完成」才经 ScheduleStore.updateCourses 落盘（active 课表自动联动插件/提醒）。
 *
 *   入参（Intent extras）：
 *     EXTRA_ID   — 要编辑的课表 id（与 EXTRA_NEW 二选一）
 *     EXTRA_NEW  — true = 新建本地课表（完成后 addLocal + 设激活）
 *     EXTRA_FOCUS_NAME / EXTRA_FOCUS_DAY / EXTRA_FOCUS_TIME
 *                — 从首页课程详情 [编辑] 跳来时，直接打开该课的编辑面板
 */
public class CourseEditActivity extends Activity implements WeekEditGrid.Callback {

    public static final String EXTRA_ID = "id";
    public static final String EXTRA_NEW = "new";
    public static final String EXTRA_FOCUS_NAME = "focusName";
    public static final String EXTRA_FOCUS_DAY = "focusDay";
    public static final String EXTRA_FOCUS_TIME = "focusTime";

    private static final int REQ_JSON = 11;

    private boolean newMode = false;
    private String scheduleId = "";
    private String draftName = "";
    /** 编辑前的课表名（判断是否改过名用） */
    private String originName = "";
    private boolean sourceSync = false;
    private final List<CourseCache.Course> draft = new ArrayList<>();
    private boolean dirty = false;
    /** 从首页课程详情跳来时要立刻打开的课（消费一次后清空） */
    private CourseCache.Course focusCourse;

    private WeekEditGrid grid;
    private TextView subView;
    private TextView titleView;
    private LinearLayout undoBar;
    private TextView undoText;
    private Button undoBtn;
    private Button discardBtn;
    private final Runnable undoHide = new Runnable() {
        @Override public void run() { undoBar.setVisibility(View.GONE); }
    };
    private int lastThemeVersion = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScheduleStore.ensureInitialized(this);

        newMode = getIntent().getBooleanExtra(EXTRA_NEW, false);
        scheduleId = getIntent().getStringExtra(EXTRA_ID);
        if (savedInstanceState != null) {
            // 主题切换 recreate：恢复草稿
            newMode = savedInstanceState.getBoolean(EXTRA_NEW, newMode);
            scheduleId = savedInstanceState.getString(EXTRA_ID, scheduleId);
            draftName = savedInstanceState.getString("draftName", "");
            originName = savedInstanceState.getString("originName", "");
            dirty = savedInstanceState.getBoolean("dirty", false);
            draft.addAll(CourseEditUtil.coursesFromJson(savedInstanceState.getString("draftJson", "[]")));
        } else if (newMode) {
            draftName = "";
            originName = "";
            dirty = false;
        } else {
            ScheduleStore.Schedule s = ScheduleStore.find(this, scheduleId);
            if (s == null) {
                // id 失效（课表被删等）：退回首页
                Toast.makeText(this, "课表不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            draftName = s.name;
            originName = s.name;
            sourceSync = s.isSync();
            dirty = false;
            draft.addAll(s.courses);
        }

        // 首页课程详情 [编辑] 直达：定位要编辑的课
        String fn = getIntent().getStringExtra(EXTRA_FOCUS_NAME);
        if (fn != null && fn.length() > 0) {
            int fd = getIntent().getIntExtra(EXTRA_FOCUS_DAY, -1);
            String ft = getIntent().getStringExtra(EXTRA_FOCUS_TIME);
            for (CourseCache.Course c : draft) {
                if (fn.equals(c.name) && (fd < 0 || fd == c.day)
                        && (ft == null || ft.length() == 0 || ft.equals(c.time))) {
                    focusCourse = c;
                    break;
                }
            }
        }

        buildUi();
        if (focusCourse != null) {
            grid.post(new Runnable() {
                @Override public void run() { openSheet(focusCourse); focusCourse = null; }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, newMode ? "/apk/course-edit-new" : "/apk/course-edit");
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(EXTRA_NEW, newMode);
        out.putString(EXTRA_ID, scheduleId);
        out.putString("draftName", draftName);
        out.putString("originName", originName);
        out.putBoolean("dirty", dirty);
        out.putString("draftJson", CourseEditUtil.coursesToJson(draft));
    }

    // ======================= UI =======================

    private void buildUi() {
        Ui.applyTheme(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.CANVAS);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 8));

        // ── 顶栏：返回 | 课表名（可点改名） | [JSON] ──
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(Ui.TEXT);
        back.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBack(); }
        });
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        titleView = Ui.text(this, titleText(), 16.5f, Ui.TEXT, true);
        titleView.setSingleLine(true);
        titleView.setClickable(true);
        titleView.setContentDescription("点按修改课表名");
        titleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRename(); }
        });
        head.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView jsonBtn = Ui.text(this, "JSON", 12f, Ui.ACCENT, true);
        jsonBtn.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        jsonBtn.setBackground(Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        jsonBtn.setContentDescription("JSON 高级模式");
        jsonBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openJsonMode(); }
        });
        head.addView(jsonBtn);
        root.addView(head);

        // ── 副信息行 ──
        subView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        subView.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, 0);
        root.addView(subView);
        root.addView(Ui.space(this, 8));

        // ── 网格（可滚动）──
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        grid = new WeekEditGrid(this);
        grid.setCallback(this);
        body.addView(grid);
        body.addView(Ui.space(this, 10));
        TextView hint = Ui.text(this, "点色块编辑 · 点空格添加 · 长按拖动换天 · 长按表头清空当天",
                11f, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        body.addView(hint);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(shellWithBars(root));
        refresh();
    }

    private String titleText() {
        if (newMode) {
            return draftName.length() > 0 ? draftName : "新建本地课表";
        }
        return draftName.length() > 0 ? draftName : "未命名课表";
    }

    /**
     * 页面骨架：内容 + 底部固定操作栏 + 撤销横幅（悬浮在操作栏上方）。
     * 不走 Ui.wrapWithBottomBar（那是主导航底栏），这里钉自己的编辑操作栏。
     */
    private View shellWithBars(LinearLayout content) {
        FrameLayout shell = new FrameLayout(this);

        LinearLayout bottomStack = new LinearLayout(this);
        bottomStack.setOrientation(LinearLayout.VERTICAL);
        bottomStack.setGravity(Gravity.BOTTOM);

        // 撤销横幅（默认隐藏）
        undoBar = new LinearLayout(this);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        undoBar.setBackground(Ui.round(Ui.CARD, 12, Ui.LINE, this));
        undoBar.setElevation(Ui.dp(this, 6));
        LinearLayout.LayoutParams ubp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ubp.setMargins(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), 0);
        undoBar.setVisibility(View.GONE);
        undoText = Ui.text(this, "", 12f, Ui.TEXT, false);
        undoText.setPadding(0, 0, 0, 0);
        undoBar.addView(undoText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        undoBtn = new Button(this);
        undoBtn.setText("撤销");
        undoBtn.setAllCaps(false);
        undoBtn.setTextSize(12.5f);
        undoBtn.setTextColor(Ui.ACCENT);
        undoBtn.setBackground(Ui.round(0x00000000, 10, 0, this));
        undoBtn.setMinimumHeight(0);
        undoBtn.setMinimumWidth(0);
        undoBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doUndo(); }
        });
        undoBar.addView(undoBtn);

        // 操作栏：＋添加 | 放弃更改 | ✓ 完成
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.CARD);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        bar.setElevation(Ui.dp(this, 8));

        Button addBtn = Ui.button(this, "＋ 添加课程", false, new View.OnClickListener() {
            @Override public void onClick(View v) { addCourseAtFirstEmpty(); }
        });
        bar.addView(addBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1.2f));

        discardBtn = Ui.button(this, "放弃", false, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDiscard(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 0.8f);
        dlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(discardBtn, dlp);

        Button doneBtn = Ui.button(this, "✓ 完成", true, new View.OnClickListener() {
            @Override public void onClick(View v) { commit(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
        plp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(doneBtn, plp);

        bottomStack.addView(undoBar, ubp);
        bottomStack.addView(bar);
        FrameLayout.LayoutParams bsp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        shell.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        shell.addView(bottomStack, bsp);
        // 内容区底部留白，避免被操作栏盖住
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                content.getPaddingRight(), Ui.dp(this, 72));
        return shell;
    }

    // ======================= 刷新 =======================

    private void refresh() {
        grid.setData(draft, CourseCache.shortNameMode(this));
        titleView.setText(titleText());
        String sub = draft.size() + " 门课";
        if (newMode) {
            sub += " · 新建本地课表";
        } else if (sourceSync) {
            sub += " · 来自手环（完成后需同步才写回手环）";
        }
        if (dirty) {
            sub += " · 有未保存修改";
        }
        subView.setText(sub);
        discardBtn.setVisibility(dirty ? View.VISIBLE : View.INVISIBLE);
    }

    private void markDirty() {
        dirty = true;
        refresh();
    }

    // ======================= 网格回调（WeekEditGrid.Callback）=======================

    @Override public void onCourseTap(CourseCache.Course c) { openSheet(c); }

    @Override
    public void onCourseDelete(final CourseCache.Course c) {
        draft.remove(c);
        grid.rebuild();
        markDirty();
        showUndo("已删除「" + c.name + "」", new Runnable() {
            @Override public void run() {
                draft.add(c);
                grid.rebuild();
                refresh();
            }
        });
    }

    @Override
    public void onEmptyTap(int day, int row) {
        // 记住点的具体天 + 该位置对应的节次时段，避免用户再改
        CourseCache.Course prefill = new CourseCache.Course();
        prefill.day = day;
        String t = CourseEditUtil.timeForRow(row);
        prefill.time = t != null ? t : CourseEditUtil.nextSlotForDay(draft, day);
        openAddSheet(prefill);
    }

    @Override
    public void onCourseLongPress(CourseCache.Course c) {
        Toast.makeText(this, "拖到任意格可调整星期与节次", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCourseMoved(final CourseCache.Course c, int newDay, int newRow) {
        final int oldDay = c.day;
        final String oldTime = c.time;
        c.day = newDay;
        String t = CourseEditUtil.timeForRow(newRow);
        if (t != null) {
            c.time = t;
        }
        EvLog.i("moved " + c.name + " day " + oldDay + "->" + newDay
                + " time " + oldTime + "->" + c.time);
        grid.rebuild();
        markDirty();
        showUndo("已移到周" + CourseEditUtil.DAY_SHORT[newDay], new Runnable() {
            @Override public void run() {
                EvLog.i("undo moved " + c.name + " day " + newDay + " -> " + oldDay);
                c.day = oldDay;
                c.time = oldTime;
                grid.rebuild();
                refresh();
            }
        });
    }

    @Override
    public void onDayHeaderLongPress(final int day) {
        EvLog.i("dayHeaderLongPress day=" + day);
        // 方案 6.3：清空这一天
        int n = 0;
        for (CourseCache.Course c : draft) {
            if (c.day == day) {
                n++;
            }
        }
        if (n == 0) {
            Toast.makeText(this, "周" + CourseEditUtil.DAY_SHORT[day] + "没有课", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<CourseCache.Course> removed = new ArrayList<>();
        Dialogs.confirm(this, R.drawable.ic_trash_2, 0,
                "清空周" + CourseEditUtil.DAY_SHORT[day],
                "将删除这一天的 " + n + " 门课", null, "清空", true,
                new Dialogs.Action() {
                    @Override public void run() {
                        for (CourseCache.Course c : draft) {
                            if (c.day == day) {
                                removed.add(c);
                            }
                        }
                        draft.removeAll(removed);
                        grid.rebuild();
                        markDirty();
                        showUndo("已清空周" + CourseEditUtil.DAY_SHORT[day] + "（" + removed.size() + " 门课）",
                                new Runnable() {
                                    @Override public void run() {
                                        draft.addAll(removed);
                                        grid.rebuild();
                                        refresh();
                                    }
                                });
                    }
                });
    }

    // ======================= 单课面板 =======================

    private void openSheet(final CourseCache.Course target) {
        new CourseSheetDialog(this, target, draft, sheetListener()).show();
    }

    private void openAddSheet(CourseCache.Course prefill) {
        new CourseSheetDialog(this, null, prefill, draft, sheetListener()).show();
    }

    private CourseSheetDialog.Listener sheetListener() {
        return new CourseSheetDialog.Listener() {
            @Override public void onSaved(CourseCache.Course course, CourseCache.Course original) {
                if (original == null) {
                    draft.add(course);
                } else {
                    original.name = course.name;
                    original.day = course.day;
                    original.time = course.time;
                    original.location = course.location;
                    original.teacher = course.teacher;
                }
                grid.rebuild();
                markDirty();
            }

            @Override public void onDeleted(CourseCache.Course original) {
                onCourseDelete(original);
            }

            @Override public void onCopyCourse(CourseCache.Course source, boolean[] picked) {
                int n = 0;
                for (int d = 0; d < 7; d++) {
                    if (!picked[d]) {
                        continue;
                    }
                    CourseCache.Course clone = new CourseCache.Course();
                    clone.name = source.name;
                    clone.time = source.time;
                    clone.location = source.location;
                    clone.teacher = source.teacher;
                    clone.day = d;
                    draft.add(clone);
                    n++;
                }
                if (n > 0) {
                    grid.rebuild();
                    markDirty();
                    Toast.makeText(CourseEditActivity.this,
                            "已复制到 " + n + " 天", Toast.LENGTH_SHORT).show();
                }
            }
        };
    }

    /** 底部「＋ 添加课程」：找第一个有空档的天，预填第一个空格对应的时段 */
    private void addCourseAtFirstEmpty() {
        for (int d = 0; d < 7; d++) {
            int n = 0;
            for (CourseCache.Course c : draft) {
                if (c.day == d) {
                    n++;
                }
            }
            if (n < grid.rowCount()) {
                onEmptyTap(d, n);
                return;
            }
        }
        onEmptyTap(0, 0);
    }

    // ======================= 撤销横幅 =======================

    private Runnable pendingUndo = null;

    private void showUndo(String msg, Runnable undoAction) {
        undoText.setText(msg);
        pendingUndo = undoAction;
        undoBar.setVisibility(View.VISIBLE);
        undoBar.removeCallbacks(undoHide);
        undoBar.postDelayed(undoHide, 5000);
    }

    private void doUndo() {
        EvLog.i("doUndo " + (pendingUndo != null ? "has-pending" : "EMPTY"));
        undoBar.removeCallbacks(undoHide);
        undoBar.setVisibility(View.GONE);
        if (pendingUndo != null) {
            pendingUndo.run();
            pendingUndo = null;
        }
    }

    // ======================= 课表名改名 =======================

    private void showRename() {
        final EditText input = new EditText(this);
        input.setText(draftName);
        input.setTextSize(14f);
        input.setTextColor(Ui.TEXT);
        input.setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, this));
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 8));
        box.setBackground(Ui.round(Ui.CARD, 22, 0, this));
        box.addView(Ui.text(this, "课表名", 15.5f, Ui.TEXT, true));
        box.addView(Ui.space(this, 4));
        box.addView(Ui.text(this, "与手环上的课表同名会视为同一套", 11.5f, Ui.MUTED, false));
        box.addView(Ui.space(this, 12));
        box.addView(input);
        box.addView(Ui.space(this, 14));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = Ui.button(this, "取消", false, null);
        cancel.setBackground(Ui.round(0x00000000, 12, Ui.LINE, this));
        cancel.setTextColor(Ui.TEXT);
        btns.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
        final Dialog[] holder = new Dialog[1];
        Button ok = Ui.button(this, "确定", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                draftName = input.getText().toString().trim();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                dirty = dirty || !draftName.equals(originName);
                refresh();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
        blp.leftMargin = Ui.dp(this, 10);
        btns.addView(ok, blp);
        box.addView(btns);

        final Dialog dlg = new Dialog(this);
        holder[0] = dlg;
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(this, 310), WindowManager.LayoutParams.WRAP_CONTENT);
        dlg.show();
    }

    // ======================= JSON 模式互通 =======================

    private void openJsonMode() {
        Intent i = new Intent(this, JsonEditorActivity.class);
        i.putExtra(JsonEditorActivity.EXTRA_TITLE, newMode ? "新建本地课表（JSON）" : "编辑（JSON）");
        i.putExtra(JsonEditorActivity.EXTRA_JSON,
                CourseEditUtil.scheduleToEditorJson(draftName, draft));
        i.putExtra(JsonEditorActivity.EXTRA_SAVE_LABEL, "返回可视化编辑");
        startActivityForResult(i, REQ_JSON);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_JSON || resultCode != RESULT_OK || data == null) {
            return;
        }
        String json = data.getStringExtra(JsonEditorActivity.RESULT_JSON);
        if (json == null) {
            return;
        }
        try {
            CourseEditUtil.ParseResult r = CourseEditUtil.parseEditorJson(json);
            draft.clear();
            draft.addAll(r.courses);
            if (r.name != null && r.name.trim().length() > 0) {
                draftName = r.name.trim();
            }
            markDirty();
            Toast.makeText(this, "已应用 JSON 内容（" + draft.size() + " 门课）",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "JSON 结构错误：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ======================= 退出与提交 =======================

    private void confirmBack() {
        if (!dirty) {
            finish();
            return;
        }
        // 取消 = 继续编辑；主按钮 = 保存并退出；「放弃」走底部常驻按钮
        Dialogs.confirm(this, 0, 0, "有未保存的修改",
                "离开前保存草稿？", null, "保存并退出", false,
                new Dialogs.Action() {
                    @Override public void run() { commit(); }
                });
    }

    private void confirmDiscard() {
        Dialogs.confirm(this, 0, 0, "放弃本次修改",
                "草稿里的所有增删改都不会保存", null, "放弃", true,
                new Dialogs.Action() {
                    @Override public void run() {
                        dirty = false;
                        finish();
                    }
                });
    }

    /** ✓ 完成：草稿 → ScheduleStore（active 课表自动联动插件/提醒） */
    private void commit() {
        String name = draftName.trim().length() > 0 ? draftName.trim() : "未命名课表";
        if (newMode) {
            String id = ScheduleStore.addLocal(this, name, draft);
            ScheduleStore.setActive(this, id);
            Toast.makeText(this, "已创建并激活「" + name + "」", Toast.LENGTH_SHORT).show();
            Intent out = new Intent();
            out.putExtra("summary", "已创建并激活「" + name + "」（" + draft.size() + " 门课）");
            setResult(RESULT_OK, out);
            finish();
            return;
        }
        final String finalName = name;
        // 重名警告：与另一套课表同名（sync 语义下同名 = 手环侧同一套，方案 5.2）
        String dupId = null;
        for (ScheduleStore.Schedule s : ScheduleStore.list(this)) {
            if (!s.id.equals(scheduleId) && s.name.equals(finalName)) {
                dupId = s.id;
                break;
            }
        }
        if (dupId != null) {
            Dialogs.confirm(this, 0, 0, "已有同名课表",
                    "本机/手环上已有一套「" + finalName + "」。\n同步后两套会合并为同一套（同名即同一套）。",
                    null, "仍然保存", false,
                    new Dialogs.Action() {
                        @Override public void run() { doCommit(finalName); }
                    });
            return;
        }
        doCommit(finalName);
    }

    private void doCommit(String name) {
        ScheduleStore.updateCourses(this, scheduleId, draft);
        if (!name.equals(originName)) {
            ScheduleStore.rename(this, scheduleId, name);
        }
        String msg = sourceSync
                ? "已更新「" + name + "」，正在同步到手环…"
                : "已保存「" + name + "」（" + draft.size() + " 门课）";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        Intent out = new Intent();
        out.putExtra("summary", msg);
        setResult(RESULT_OK, out);
        finish();
        // ★ 编辑后立即同步（字段级三方合并；断连则暂存置 dirty，连上后补发）
        final android.content.Context app = getApplicationContext();
        SyncCoordinator.syncNow(app, new SyncCoordinator.Callback() {
            @Override public void onDone(boolean ok, String m) {
                Toast.makeText(app, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        confirmBack();
    }
}