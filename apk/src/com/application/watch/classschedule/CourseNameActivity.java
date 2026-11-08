package com.application.watch.classschedule;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 课程名管理：课名库的增删改查页（与手环端 course-manager 对齐）。
 *
 * <p>和编辑弹层里的 chips 是同一份数据（{@link CourseNameStore}），在这里改完，编辑课程时立刻生效。
 * 删除课名<b>不会删掉已排进课表的课程</b>，只是从候选清单里拿掉；改名则会把课表里的同名课程一起改掉。
 */
public class CourseNameActivity extends Activity {

    private LinearLayout listBox;
    private TextView countView;
    private TextView emptyView;
    private ScrollView scroll;
    /** 已点过一次「删」的课名（二次确认；4 秒未再点自动复位，避免误删） */
    private String armedName = null;
    private final Handler handler = new Handler();

    private final Runnable disarm = new Runnable() {
        @Override public void run() {
            if (armedName != null) {
                armedName = null;
                render();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        CourseNameStore.ensureSeeded(this);
        buildUi();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑页回来后可能多了新用过的课名
        CourseNameStore.mergeFromSchedules(this);
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

        head.addView(Ui.text(this, "课程名管理", 16.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        TextView sub = Ui.text(this,
                "编辑课程时可直接点选。改名会同步改掉课表里的同名课程；删除只从清单移除，不动已排好的课。",
                11.5f, Ui.MUTED, false);
        sub.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, Ui.dp(this, 8));
        sub.setLineSpacing(Ui.dp(this, 2), 1f);
        root.addView(sub);

        countView = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        countView.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 6));
        root.addView(countView);

        // ── 列表（可滚动，课名多了不至于把按钮顶出屏幕）──
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyView = Ui.text(this, "还没有课程名，点下方「添加」开始", 12f, Ui.MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, Ui.dp(this, 28), 0, 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        // ── 底部：添加 / 清空 ──
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));

        Button add = Ui.button(this, "+ 添加课程名", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showNameDialog(null); }
        });
        foot.addView(add, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));

        Button clear = Ui.button(this, "清空", false, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClear(); }
        });
        clear.setBackground(Ui.round(0x00000000, 12, Ui.LINE, this));
        clear.setTextColor(Ui.TEXT);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44));
        clp.leftMargin = Ui.dp(this, 10);
        foot.addView(clear, clp);
        root.addView(foot);

        setContentView(root);
    }

    // ======================= 渲染 =======================

    private void render() {
        listBox.removeAllViews();
        List<CourseNameStore.Entry> list = CourseNameStore.list(this);
        countView.setText(list.isEmpty() ? "" : "共 " + list.size() + " 个课程名");
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);

        for (CourseNameStore.Entry e : list) {
            listBox.addView(row(e));
            listBox.addView(Ui.space(this, 6));
        }
    }

    private View row(final CourseNameStore.Entry e) {
        final int used = CourseNameStore.usedInSchedules(this, e.name);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        int pad = Ui.dp(this, Ui.GAP_MD);
        card.setPadding(pad, Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

        // 课名 + 副行（用了几门课 / 未使用）
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, e.name, 14.5f, Ui.TEXT, true));
        String sub = used > 0 ? ("课表里已用 " + used + " 门课") : "暂未使用";
        TextView sv = Ui.text(this, sub, 11f, Ui.MUTED, false);
        sv.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(sv);
        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 改名（点整行进去，比再塞一个图标省地方）
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNameDialog(e.name); }
        });

        // 删除：第一次点变红「删？」，4 秒内再点才真删（和手环端同一套二次确认）
        final boolean armed = e.name.equals(armedName);
        TextView del = Ui.text(this, armed ? "删？" : "删", 12f,
                armed ? 0xFFFFFFFF : Ui.ERR, armed);
        del.setGravity(Gravity.CENTER);
        del.setBackground(armed
                ? Ui.round(Ui.ERR, 10, 0, this)
                : Ui.round(Ui.CARD2, 10, Ui.LINE, this));
        del.setContentDescription("删除课程名 " + e.name);
        del.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (e.name.equals(armedName)) {
                    armedName = null;
                    handler.removeCallbacks(disarm);
                    // 用户在课表里还排着这个课名 → 提醒但不阻止（清单和课表是两件事）
                    CourseNameStore.remove(CourseNameActivity.this, e.name);
                    toast(used > 0
                            ? "已从清单移除（课表里 " + used + " 门课保留）"
                            : "已删除「" + e.name + "」");
                    render();
                } else {
                    armedName = e.name;
                    handler.removeCallbacks(disarm);
                    handler.postDelayed(disarm, 4000);
                    render();
                }
            }
        });
        card.addView(del, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34)));

        return card;
    }

    // ======================= 添加 / 改名 =======================

    /**
     * 添加或改名共用的输入框。
     *
     * @param origin 非 null = 改名
     */
    private void showNameDialog(final String origin) {
        final EditText input = Ui.editText(this);
        input.setText(origin == null ? "" : origin);
        input.setHint("例如：高等数学");
        input.setTextSize(14f);
        input.setTextColor(Ui.TEXT);
        input.setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, this));
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        input.setSingleLine(true);

        final int used = origin == null ? 0 : CourseNameStore.usedInSchedules(this, origin);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 8));
        box.setBackground(Ui.round(Ui.CARD, 22, 0, this));
        box.addView(Ui.text(this, origin == null ? "添加课程名" : "修改课程名", 15.5f, Ui.TEXT, true));
        box.addView(Ui.space(this, 4));
        box.addView(Ui.text(this,
                origin == null
                        ? "加进来以后，编辑课程时点一下就能填"
                        : (used > 0 ? "课表里有 " + used + " 门课叫这个名字，会一起改掉" : "只改清单里的名字"),
                11.5f, Ui.MUTED, false));
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
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });

        Button ok = Ui.button(this, "确定", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = input.getText().toString().trim();
                if (name.length() == 0) {
                    toast("课程名不能为空");
                    return;
                }
                if (origin == null) {
                    if (!CourseNameStore.add(CourseNameActivity.this, name)) {
                        toast("「" + name + "」已经在清单里了");
                        return;
                    }
                } else if (!name.equals(origin)) {
                    int n = CourseNameStore.rename(CourseNameActivity.this, origin, name);
                    toast(n > 0 ? "已改名，同步更新 " + n + " 门课" : "已改名");
                }
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                render();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
        blp.leftMargin = Ui.dp(this, 10);
        btns.addView(ok, blp);
        box.addView(btns);

        Dialog dlg = new Dialog(this);
        holder[0] = dlg;
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(this, 310), WindowManager.LayoutParams.WRAP_CONTENT);
        dlg.show();
    }

    // ======================= 清空 =======================

    private void confirmClear() {
        if (CourseNameStore.names(this).isEmpty()) {
            toast("清单已经是空的");
            return;
        }
        Dialogs.confirm(this, 0, 0, "清空课程名清单？",
                "只清空候选清单，已经排进课表的课程不受影响。",
                null, "清空", true, new Dialogs.Action() {
                    @Override public void run() {
                        CourseNameStore.clear(CourseNameActivity.this);
                        armedName = null;
                        render();
                        toast("已清空课程名清单");
                    }
                });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(disarm);
        super.onDestroy();
    }
}
