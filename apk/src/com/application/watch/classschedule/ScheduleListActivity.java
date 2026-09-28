package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 课程表管理（底部导航「课程表」Tab）。
 *
 * 重做后卡片样式：图标 + 课表名 + 副信息 + 右侧紧凑图标行（编辑/删除/同步）。
 *   - 编辑 → 通用 JSON 编辑器（JsonEditorActivity）
 *   - 删除 → local 直删；sync 仅删本地（附说明），「从手环删除」本期置灰
 *   - 新建 → 通用 JSON 编辑器（默认模板）
 */
public class ScheduleListActivity extends Activity {

    private static final int REQ_EDIT = 1;
    private static final int REQ_CREATE = 2;

    private int lastThemeVersion = 0;

    private LinearLayout listBox;
    private TextView statusView;
    private String editingId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScheduleStore.ensureInitialized(this);
        buildUi();
        // 状态回调：手环真实套数读到后自动刷新分组标题
        SyncEngine.get(this).addStatusCallback(new Runnable() {
            @Override public void run() { render(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, "/apk/schedules");
        render();
        // 在线时向手环要一次清单，拿到真实套数（离线跳过）
        if (SyncEngine.get(this).hasNode()) {
            SyncEngine.get(this).refreshBandScheduleCount();
        }
    }

    private void buildUi() {
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "课程表管理"));
        root.addView(Ui.space(this, 8));
        ConnectionBar.attach(this, root);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "本机保存的全部课表：可切换、编辑、同步到手环",
                11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        statusView = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(statusView);
        root.addView(Ui.space(this, 10));

        root.addView(Ui.button(this, "＋ 新建本地课表", true, new View.OnClickListener() {
            @Override public void onClick(View v) { createLocal(); }
        }));

        if (Variant.isEv(this)) {
            root.addView(Ui.space(this, 14));
            LinearLayout io = Ui.card(this);
            io.addView(Ui.text(this, "导入 / 导出", 12.5f, Ui.TEXT, true));
            io.addView(Ui.space(this, 6));
            io.addView(Ui.grid(this,
                    Ui.button(this, "导入课程表", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(TransferActivity.MODE_IMPORT); }
                    }),
                    Ui.button(this, "导出课程表", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(TransferActivity.MODE_EXPORT); }
                    })));
            root.addView(io);
        }

        setContentView(Ui.wrapWithBottomBar(this, root, 1));
        render();
    }

    // ======================= 列表渲染 =======================

    private void render() {
        listBox.removeAllViews();
        final String activeId = ScheduleStore.activeId(this);
        List<ScheduleStore.Schedule> all = ScheduleStore.list(this);
        if (all.isEmpty()) {
            listBox.addView(Ui.text(this, "还没有课表，点下方按钮新建", 12.5f, Ui.MUTED, false));
            return;
        }
        // 分组：来自手环（连接时已自动同步保存到本地）在上，本机课表在下，中间分割线
        List<ScheduleStore.Schedule> fromWatch = new ArrayList<>();
        List<ScheduleStore.Schedule> local = new ArrayList<>();
        for (ScheduleStore.Schedule s : all) {
            (s.isSync() ? fromWatch : local).add(s);
        }
        // 以手环真实清单为准渲染（本地只有拉取过的，是手环的子集）：
        // 有本地镜像 → 完整卡片；只存在于手环 → 占位卡，可一键「读取到本机」
        String[] bandNames = SyncEngine.get(this).bandScheduleNames;
        if (bandNames != null && bandNames.length > 0) {
            int localMirror = 0;
            for (int i = 0; i < bandNames.length; i++) {
                if (ScheduleStore.find(this, "ev_watch_" + bandNames[i]) != null) {
                    localMirror++;
                }
            }
            listBox.addView(sectionHead("手环课表 · 手环上共 " + bandNames.length
                    + " 套 · 本地已存 " + localMirror + " 套"));
            for (int i = 0; i < bandNames.length; i++) {
                ScheduleStore.Schedule mirror = ScheduleStore.find(this, "ev_watch_" + bandNames[i]);
                if (mirror != null) {
                    listBox.addView(scheduleCard(mirror, activeId));
                } else {
                    listBox.addView(bandOnlyCard(bandNames[i], i));
                }
                listBox.addView(Ui.space(this, 8));
            }
        } else if (!fromWatch.isEmpty()) {
            listBox.addView(sectionHead("手环课表（已自动同步保存到本地）", fromWatch.size()));
            for (ScheduleStore.Schedule s : fromWatch) {
                listBox.addView(scheduleCard(s, activeId));
                listBox.addView(Ui.space(this, 8));
            }
        }
        if (!local.isEmpty()) {
            if (!fromWatch.isEmpty()) {
                listBox.addView(divider());
            }
            listBox.addView(sectionHead("本机课表", local.size()));
            for (ScheduleStore.Schedule s : local) {
                listBox.addView(scheduleCard(s, activeId));
                listBox.addView(Ui.space(this, 8));
            }
        }
    }

    /** 只存在于手环、本机还没有的课表：显示名字 + 「读取到本机」 */
    private View bandOnlyCard(final String name, final int index) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, name, 14.5f, Ui.TEXT, true));
        TextView sub = Ui.text(this, "仅存在于手环 · 本机还没有保存", 11.5f, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(sub);
        card.addView(Ui.space(this, 8));
        card.addView(Ui.button(this, "读取到本机", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pullFromWatch(name, index); }
        }));
        return card;
    }

    /** 按手环清单下标拉取该套课表到本地（upsert 进多课表存储）。 */
    private void pullFromWatch(final String name, final int index) {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            status("手环未连接，无法读取", Ui.WARN);
            return;
        }
        status("正在从手环读取「" + name + "」…", Ui.ACCENT);
        e.exportSchedule(index, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        status("手环回包里没课表数据", Ui.WARN);
                        return;
                    }
                    ScheduleStore.upsertFromWatch(ScheduleListActivity.this, name, sch);
                    status("已保存到本机「" + name + "」", Ui.OK);
                    render();
                } catch (Throwable t) {
                    status("回包无法解析", Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) { status(hint, Ui.WARN); }
            @Override public void onError(String msg) { status("读取失败：" + msg, Ui.ERR); }
        });
    }

    /** 分组小标题：名称 + 数量 */
    private View sectionHead(String label, int n) {
        TextView t = Ui.text(this, label + " · " + n + " 套", 11.5f, Ui.MUTED, true);
        t.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 6));
        return t;
    }

    /** 分组小标题（数量已含在 label 里，不重复追加） */
    private View sectionHead(String label) {
        TextView t = Ui.text(this, label, 11.5f, Ui.MUTED, true);
        t.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 6));
        return t;
    }

    /** 组间分割线 */
    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        p.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        v.setLayoutParams(p);
        return v;
    }

    private View scheduleCard(final ScheduleStore.Schedule s, final String activeId) {
        final boolean active = s.id.equals(activeId);
        LinearLayout card = Ui.card(this);

        // 第一行：标题 + 右侧图标操作（同步/编辑/删除）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView titleView = Ui.text(this, s.name, 14.5f,
                active ? Ui.ACCENT : Ui.TEXT, true);
        head.addView(titleView,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        if (!s.isSync()) {
            icons.addView(iconBtn(R.drawable.ic_refresh_cw, "同步到手环", new View.OnClickListener() {
                @Override public void onClick(View v) { syncToWatch(s); }
            }));
        }
        icons.addView(iconBtn(R.drawable.ic_pencil, "编辑", new View.OnClickListener() {
            @Override public void onClick(View v) { editSchedule(s); }
        }));
        icons.addView(iconBtn(R.drawable.ic_trash_2, "删除", new View.OnClickListener() {
            @Override public void onClick(View v) { deleteSchedule(s); }
        }));
        head.addView(icons);
        card.addView(head);

        // 第二行：副信息 + 右侧「当前」勾选框（替代原第三行「切换为此 →」）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView sub = Ui.text(this, s.sub(), 11.5f, Ui.MUTED, false);
        row2.addView(sub, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final android.widget.CheckBox cur = new android.widget.CheckBox(this);
        cur.setText("当前");
        cur.setTextSize(12f);
        cur.setTextColor(active ? Ui.ACCENT : Ui.MUTED);
        cur.setChecked(active);
        cur.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                if (isChecked && !s.id.equals(ScheduleStore.activeId(ScheduleListActivity.this))) {
                    ScheduleStore.setActive(ScheduleListActivity.this, s.id);
                    status("已切换到「" + s.name + "」", Ui.OK);
                    render();
                } else if (!isChecked && s.id.equals(ScheduleStore.activeId(ScheduleListActivity.this))) {
                    // 当前课表不能取消勾选（要先勾选别的课表）
                    b.setChecked(true);
                }
            }
        });
        row2.addView(cur);
        card.addView(row2);
        return card;
    }

    /** 紧凑图标按钮（Lucide 矢量 + 小 padding + ACCENT 染色 + 无障碍描述） */
    private View iconBtn(int iconRes, String desc, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(iconRes);
        v.setColorFilter(Ui.ACCENT);
        v.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        v.setContentDescription(desc);
        v.setOnClickListener(l);
        return v;
    }

    // ======================= 操作：新建 =======================

    private void createLocal() {
        Intent i = new Intent(this, JsonEditorActivity.class);
        i.putExtra(JsonEditorActivity.EXTRA_TITLE, "新建本地课表");
        i.putExtra(JsonEditorActivity.EXTRA_JSON, defaultTemplate());
        i.putExtra(JsonEditorActivity.EXTRA_SAVE_LABEL, "创建课表");
        startActivityForResult(i, REQ_CREATE);
    }

    // ======================= 操作：编辑 =======================

    private void editSchedule(ScheduleStore.Schedule s) {
        editingId = s.id;
        Intent i = new Intent(this, JsonEditorActivity.class);
        i.putExtra(JsonEditorActivity.EXTRA_TITLE, "编辑：" + s.name);
        i.putExtra(JsonEditorActivity.EXTRA_JSON, scheduleToEditorJson(s));
        i.putExtra(JsonEditorActivity.EXTRA_SAVE_LABEL, "更新课表");
        startActivityForResult(i, REQ_EDIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        String json = data.getStringExtra(JsonEditorActivity.RESULT_JSON);
        if (json == null) {
            return;
        }
        EditorResult r;
        try {
            r = parseEditorJson(json);
        } catch (Throwable t) {
            status("JSON 结构错误：" + t.getMessage(), Ui.ERR);
            return;
        }
        if (requestCode == REQ_EDIT && editingId != null) {
            ScheduleStore.updateCourses(this, editingId, r.courses);
            // 同时更新课表名（编辑可能改了 name）
            ScheduleStore.Schedule s = ScheduleStore.find(this, editingId);
            if (s != null && !s.name.equals(r.name)) {
                rename(editingId, r.name);
            }
            status("已更新「" + r.name + "」（" + r.courses.size() + " 门课）", Ui.OK);
            editingId = null;
        } else if (requestCode == REQ_CREATE) {
            String id = ScheduleStore.addLocal(this, r.name, r.courses);
            ScheduleStore.setActive(this, id);
            status("已创建并激活「" + r.name + "」", Ui.OK);
        }
        render();
    }

    // ======================= 操作：删除 =======================

    private void deleteSchedule(final ScheduleStore.Schedule s) {
        final boolean syncOnly = s.isSync();
        final int RED = 0xFFE5484D;
        final android.app.Dialog[] holder = new android.app.Dialog[1];

        // 自绘弹窗：圆角卡 + 图标章 + 居中文案 + 幽灵/红色按钮（脱离系统 AlertDialog 默认样式）
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 22);
        box.setBackground(Ui.round(Ui.CARD, 22, 0, this));
        box.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_trash_2);
        iv.setColorFilter(RED);
        iv.setBackground(Ui.round(0x2EE5484D, 26, 0, this));
        iv.setPadding(Ui.dp(this, 13), Ui.dp(this, 13), Ui.dp(this, 13), Ui.dp(this, 13));
        box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        box.addView(Ui.space(this, 12));

        box.addView(Ui.text(this, syncOnly ? "从本机移除课表" : "删除课表", 16.5f, Ui.TEXT, true));
        box.addView(Ui.space(this, 6));
        box.addView(Ui.text(this, "「" + s.name + "」", 14f, Ui.TEXT, false));
        TextView meta = Ui.text(this, s.sub(), 11.5f, Ui.MUTED, false);
        meta.setPadding(0, Ui.dp(this, 2), 0, 0);
        box.addView(meta);
        TextView warn = Ui.text(this, syncOnly
                        ? "仅从本机删除；手环上仍保留，连接同步后会重新拉回。"
                        : "此操作不可撤销，删除后无法恢复。",
                12f, RED, false);
        warn.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(warn);
        if (syncOnly) {
            TextView extra = Ui.text(this, "彻底从手环删除需手环端支持，暂未开放", 10.5f, Ui.MUTED, false);
            extra.setPadding(0, Ui.dp(this, 4), 0, 0);
            box.addView(extra);
        }
        box.addView(Ui.space(this, 18));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button cancel = Ui.button(this, "取消", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        cancel.setBackground(Ui.round(0x00000000, 12, Ui.LINE, this));
        cancel.setTextColor(Ui.TEXT);
        btns.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
        // ⚠️ 这里不能用 Ui.space：它是 MATCH_PARENT 宽，在横向布局里会把「删除」挤出对话框
        android.widget.Button del = Ui.button(this, syncOnly ? "仅删本地" : "删除", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                ScheduleStore.remove(ScheduleListActivity.this, s.id);
                status(syncOnly ? "已从本机删除「" + s.name + "」（手环仍保留）"
                        : "已删除「" + s.name + "」", Ui.OK);
                render();
            }
        });
        del.setBackground(Ui.round(RED, 12, 0, this));
        del.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
        dlp.leftMargin = Ui.dp(this, 10);
        btns.addView(del, dlp);
        box.addView(btns);

        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(this, 310),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        holder[0] = dlg;
        dlg.show();
    }

    // ======================= 操作：同步到手环 =======================

    private void syncToWatch(final ScheduleStore.Schedule s) {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            status("手环未连接，请先在首页完成连接", Ui.WARN);
            return;
        }
        try {
            JSONArray courses = new JSONArray();
            for (CourseCache.Course c : s.courses) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("day", c.day + 1);
                o.put("time", c.time);
                if (c.teacher.length() > 0) {
                    o.put("teacher", c.teacher);
                }
                if (c.location.length() > 0) {
                    o.put("location", c.location);
                }
                courses.put(o);
            }
            JSONObject body = new JSONObject();
            body.put("courses", courses);
            body.put("scheduleName", s.name);
            JSONObject msg = new JSONObject();
            msg.put("action", "import");
            msg.put("payload", body);

            status("正在同步「" + s.name + "」到手环…", Ui.ACCENT);
            e.send(msg.toString(), new SyncEngine.Reply() {
                @Override public void onReply(String json) {
                    try {
                        JSONObject o = new JSONObject(json);
                        if (o.optBoolean("ok", false)) {
                            ScheduleStore.markSynced(ScheduleListActivity.this, s.id);
                            status("已同步到手环 ✓（覆盖手环当前课表）", Ui.OK);
                            render();
                        } else {
                            status("手环拒绝了导入：" + o.optString("reason", "未知原因"), Ui.WARN);
                        }
                    } catch (Throwable t) {
                        status("回包无法解析", Ui.ERR);
                    }
                }
                @Override public void onTimeout(String hint) { status(hint, Ui.WARN); }
                @Override public void onError(String msg) { status("同步失败：" + msg, Ui.ERR); }
            });
        } catch (Throwable t) {
            status("构造同步报文失败", Ui.ERR);
        }
    }

    // ======================= JSON 工具 =======================

    /** 课表 → 编辑器 JSON（day 用 1-7，人类友好） */
    static String scheduleToEditorJson(ScheduleStore.Schedule s) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", s.name);
            JSONArray arr = new JSONArray();
            for (CourseCache.Course c : s.courses) {
                JSONObject co = new JSONObject();
                co.put("name", c.name);
                co.put("day", c.day + 1);
                co.put("time", c.time);
                co.put("teacher", c.teacher);
                co.put("location", c.location);
                arr.put(co);
            }
            o.put("courses", arr);
            return o.toString(2);
        } catch (Throwable t) {
            return "{\"name\":\"\",\"courses\":[]}";
        }
    }

    /** 默认新建模板 */
    static String defaultTemplate() {
        return "{\n"
                + "  \"name\": \"新课表\",\n"
                + "  \"courses\": [\n"
                + "    {\"name\":\"课程名\",\"day\":1,\"time\":\"08:00 - 09:35\",\"teacher\":\"\",\"location\":\"教学楼\"},\n"
                + "    {\"name\":\"课程名\",\"day\":2,\"time\":\"10:00 - 11:35\",\"teacher\":\"\",\"location\":\"教学楼\"}\n"
                + "  ]\n"
                + "}";
    }

    static final class EditorResult {
        String name;
        List<CourseCache.Course> courses = new ArrayList<>();
    }

    /** 编辑器 JSON → {name, courses}（day 1-7 → 0-6） */
    static EditorResult parseEditorJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        EditorResult r = new EditorResult();
        r.name = o.optString("name", "未命名课表");
        JSONArray arr = o.optJSONArray("courses");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject co = arr.optJSONObject(i);
                if (co == null) {
                    continue;
                }
                CourseCache.Course c = new CourseCache.Course();
                c.name = co.optString("name");
                c.time = co.optString("time");
                c.teacher = co.optString("teacher");
                c.location = co.optString("location");
                c.day = CourseCache.dayIndex(co.opt("day"));
                r.courses.add(c);
            }
        }
        return r;
    }

    private void status(String msg, int color) {
        statusView.setText(msg);
        statusView.setTextColor(color);
    }

    private void open(String mode) {
        Intent i = new Intent(this, TransferActivity.class);
        i.putExtra(TransferActivity.EXTRA_MODE, mode);
        startActivity(i);
    }

    /** 重命名课表（编辑时改了 name 字段用） */
    private void rename(String id, String name) {
        try {
            // 直接操作存储：更新该 id 的 name 字段
            android.content.SharedPreferences sp =
                    getSharedPreferences("ev_schedules", MODE_PRIVATE);
            String raw = sp.getString("data", "");
            JSONObject root = new JSONObject(raw);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("name", name);
                    break;
                }
            }
            sp.edit().putString("data", root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }
}
