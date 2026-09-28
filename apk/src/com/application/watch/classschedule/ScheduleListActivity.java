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
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                listBox.addView(Ui.space(this, 8));
            }
            listBox.addView(scheduleCard(all.get(i), activeId));
        }
    }

    private View scheduleCard(final ScheduleStore.Schedule s, final String activeId) {
        final boolean active = s.id.equals(activeId);
        LinearLayout card = Ui.card(this);

        // 第一行：标题 + 右侧图标操作
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        StringBuilder title = new StringBuilder();
        title.append(s.name);
        if (active) {
            title.append("  ✓");
        }
        TextView titleView = Ui.text(this, title.toString(), 14.5f,
                active ? Ui.ACCENT : Ui.TEXT, true);
        head.addView(titleView,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧图标行
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        if (!s.isSync()) {
            icons.addView(iconBtn(R.drawable.ic_refresh_cw, new View.OnClickListener() {
                @Override public void onClick(View v) { syncToWatch(s); }
            }));
        }
        icons.addView(iconBtn(R.drawable.ic_pencil, new View.OnClickListener() {
            @Override public void onClick(View v) { editSchedule(s); }
        }));
        icons.addView(iconBtn(R.drawable.ic_trash_2, new View.OnClickListener() {
            @Override public void onClick(View v) { deleteSchedule(s); }
        }));
        head.addView(icons);
        card.addView(head);

        TextView sub = Ui.text(this, s.sub(), 11.5f, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(sub);

        // 非激活：右下角「切换为此」文字链
        if (!active) {
            TextView switchLink = Ui.text(this, "切换为此 →", 12f, Ui.ACCENT, true);
            switchLink.setPadding(0, Ui.dp(this, 6), 0, 0);
            switchLink.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ScheduleStore.setActive(ScheduleListActivity.this, s.id);
                    status("已切换到「" + s.name + "」", Ui.OK);
                    render();
                }
            });
            card.addView(switchLink);
        }
        return card;
    }

    /** 紧凑图标按钮（Lucide 矢量 + 小 padding + ACCENT 染色） */
    private View iconBtn(int iconRes, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(iconRes);
        v.setColorFilter(Ui.ACCENT);
        v.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
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
        if (s.isSync()) {
            // 已同步课表：仅删本地（手环删除本期不可用）
            String msg = "确定仅从本机删除「" + s.name + "」？\n\n"
                    + "手环上仍保留这套课表，下次连接手环同步时会重新拉取到本机。\n\n"
                    + "（彻底从手环删除需手环端支持，暂未开放）";
            new AlertDialog.Builder(this)
                    .setTitle("删除课表")
                    .setMessage(msg)
                    .setPositiveButton("仅删本地", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            ScheduleStore.remove(ScheduleListActivity.this, s.id);
                            status("已从本机删除「" + s.name + "」（手环仍保留）", Ui.OK);
                            render();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            // 本地课表：直接删
            new AlertDialog.Builder(this)
                    .setTitle("删除课表")
                    .setMessage("确定删除「" + s.name + "」？此操作不可撤销。")
                    .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            ScheduleStore.remove(ScheduleListActivity.this, s.id);
                            status("已删除「" + s.name + "」", Ui.OK);
                            render();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
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
