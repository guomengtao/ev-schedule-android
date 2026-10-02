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
 *   - 编辑 → 可视化编辑器（CourseEditActivity，JSON 编辑器降级为它内部的「JSON」按钮）
 *   - 删除 → local 直删；sync 仅删本地（附说明），「从手环删除」本期置灰
 *   - 新建 → 可视化编辑器（新建模式）
 */
public class ScheduleListActivity extends Activity {

    private static final int REQ_EDIT = 1;
    private static final int REQ_CREATE = 2;

    private int lastThemeVersion = 0;

    private LinearLayout listBox;
    private TextView statusView;
    private TextView syncView;
    private boolean syncPendingConnect = false;
    private String editingId;
    private long lastPullAt = 0;
    /** 头部设备条：明确「当前某某手环的课表」+ 切换入口（P3 多设备分组） */
    private LinearLayout deviceBar;
    private TextView deviceBarTitle;
    /** 「其他设备」折叠分组是否展开（默认收起，避免多设备课表淹没当前设备） */
    private boolean otherExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScheduleStore.ensureInitialized(this);
        buildUi();
        // 状态回调：手环真实套数读到后刷新分组标题；并把「手环上有、本机没有」的课表
        // 自动读取到本机（入库即标记 source=sync，仍属「手环课表」组，无需手动操作）
        SyncEngine.get(this).addStatusCallback(new Runnable() {
            @Override public void run() {
                render();
                // 自动补齐节流：30s 一次即可，避免频繁重建列表影响点击
                SyncEngine e = SyncEngine.get(ScheduleListActivity.this);
                long now = System.currentTimeMillis();
                if (e.hasNode() && now - lastPullAt > 30000) {
                    lastPullAt = now;
                    e.pullMissingFromWatch(ScheduleListActivity.this);
                }
            }
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

        // active schedule sync status: unsaved count, tap to force 3-way sync (moved here from home)
        syncView = Ui.textMedium(this, "", 12f, Ui.ACCENT);
        syncView.setGravity(android.view.Gravity.CENTER);
        syncView.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        syncView.setBackground(Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        syncView.setClickable(true);
        syncView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncPendingConnect) {
                    syncPendingConnect = false;
                    startActivity(new Intent(ScheduleListActivity.this, BandActivity.class));
                } else {
                    syncActive();
                }
            }
        });
        root.addView(syncView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.space(this, 8));

        // 头部设备条：明确「当前某某手环的课表」，点击切换设备（P3）
        deviceBar = new LinearLayout(this);
        deviceBar.setOrientation(LinearLayout.HORIZONTAL);
        deviceBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        deviceBar.setBackground(Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        deviceBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        deviceBar.setClickable(true);
        deviceBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ScheduleListActivity.this, BandActivity.class));
            }
        });
        deviceBarTitle = Ui.text(this, "", 12.5f, Ui.TEXT, true);
        deviceBar.addView(deviceBarTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        deviceBar.addView(Ui.text(this, "切换 ›", 12f, Ui.ACCENT, true));
        root.addView(deviceBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.space(this, 8));

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
        refreshSyncView();
        renderDeviceBar();
        final String activeId = ScheduleStore.activeId(this);
        List<ScheduleStore.Schedule> all = ScheduleStore.list(this);
        if (all.isEmpty()) {
            listBox.addView(Ui.text(this, "还没有课表，点下方按钮新建", 12.5f, Ui.MUTED, false));
            return;
        }
        SyncEngine e = SyncEngine.get(this);
        String curDev = e.currentDeviceId();
        String[] bandNames = e.bandScheduleNames;

        // 三分类：本地创建 / 当前设备 / 其他设备（含未识别 legacy）
        List<ScheduleStore.Schedule> local = new ArrayList<>();
        List<ScheduleStore.Schedule> syncCur = new ArrayList<>();
        List<ScheduleStore.Schedule> syncOther = new ArrayList<>();
        for (ScheduleStore.Schedule s : all) {
            if (!s.isSync()) {
                local.add(s);
            } else if (!curDev.isEmpty() && curDev.equals(s.deviceId)) {
                syncCur.add(s);
            } else {
                syncOther.add(s);
            }
        }

        if (!curDev.isEmpty()) {
            // 已连接手环：按设备分组 —— 当前设备展开，其他设备默认折叠（不隐藏，可展开查）
            if (bandNames != null && bandNames.length > 0) {
                int localMirror = 0;
                for (int i = 0; i < bandNames.length; i++) {
                    if (ScheduleStore.findByDeviceName(this, curDev, bandNames[i]) != null) {
                        localMirror++;
                    }
                }
                listBox.addView(sectionHead("当前手环课表 · 手环上共 " + bandNames.length
                        + " 套 · 本地已存 " + localMirror + " 套"));
                for (int i = 0; i < bandNames.length; i++) {
                    ScheduleStore.Schedule mirror = ScheduleStore.findByDeviceName(this, curDev, bandNames[i]);
                    listBox.addView(mirror != null ? scheduleCard(mirror, activeId) : bandOnlyCard(bandNames[i]));
                    listBox.addView(Ui.space(this, 8));
                }
                // 本地有、手环清单已没有的当前设备课表：仍展示，避免「切了设备就消失」
                for (ScheduleStore.Schedule s : syncCur) {
                    if (!inNames(bandNames, s.name)) {
                        listBox.addView(scheduleCard(s, activeId));
                        listBox.addView(Ui.space(this, 8));
                    }
                }
            } else if (!syncCur.isEmpty()) {
                listBox.addView(sectionHead("当前手环课表", syncCur.size()));
                for (ScheduleStore.Schedule s : syncCur) {
                    listBox.addView(scheduleCard(s, activeId));
                    listBox.addView(Ui.space(this, 8));
                }
            }
            // 其他设备：默认收起，点标题展开（每套副行仍带设备名，不与当前设备混淆）
            if (!syncOther.isEmpty()) {
                listBox.addView(divider());
                listBox.addView(otherGroupHead(syncOther.size()));
                if (otherExpanded) {
                    for (ScheduleStore.Schedule s : syncOther) {
                        listBox.addView(scheduleCard(s, activeId));
                        listBox.addView(Ui.space(this, 8));
                    }
                }
            }
        } else {
            // 未连接手环：无「当前设备」可言，全部手环课表平铺（副行已带设备名/未识别）
            List<ScheduleStore.Schedule> fromWatch = new ArrayList<>(syncCur);
            fromWatch.addAll(syncOther);
            if (!fromWatch.isEmpty()) {
                listBox.addView(sectionHead("手环课表（已自动同步保存到本地）", fromWatch.size()));
                for (ScheduleStore.Schedule s : fromWatch) {
                    listBox.addView(scheduleCard(s, activeId));
                    listBox.addView(Ui.space(this, 8));
                }
            }
        }
        if (!local.isEmpty()) {
            if (!syncCur.isEmpty() || !syncOther.isEmpty()) {
                listBox.addView(divider());
            }
            listBox.addView(sectionHead("本机课表", local.size()));
            for (ScheduleStore.Schedule s : local) {
                listBox.addView(scheduleCard(s, activeId));
                listBox.addView(Ui.space(this, 8));
            }
        }
    }

    /** 头部设备条文案：已连接 → 「当前手环课表：XX ··后4位」；未连接 → 提示 */
    private void renderDeviceBar() {
        if (deviceBar == null) {
            return;
        }
        String curDev = SyncEngine.get(this).currentDeviceId();
        if (curDev.isEmpty()) {
            deviceBarTitle.setText("未连接手环 · 课表按设备分组显示");
            return;
        }
        String name = SyncEngine.get(this).currentDeviceName();
        String tail = tail4(curDev);
        deviceBarTitle.setText("当前手环课表：" + (name.isEmpty() ? "当前手环" : name)
                + (tail.isEmpty() ? "" : " ··" + tail));
    }

    /** 设备 ID 后 4 位（展示用，区分同名设备） */
    private String tail4(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return id.length() <= 4 ? id : id.substring(id.length() - 4);
    }

    /** 「其他设备」折叠分组标题（点击展开/收起） */
    private View otherGroupHead(final int n) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 6));
        head.setClickable(true);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                otherExpanded = !otherExpanded;
                render();
            }
        });
        TextView t = Ui.text(this,
                (otherExpanded ? "▾" : "▸") + " 其他设备 · " + n + " 套（点击" + (otherExpanded ? "收起" : "展开") + "）",
                11.5f, Ui.ACCENT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return head;
    }

    private static boolean inNames(String[] names, String name) {
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 只存在于手环、本机还没有的课表：显示名字 + 提示（不需要手动点，进页面会自动读取） */
    private View bandOnlyCard(final String name) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, name, 14.5f, Ui.TEXT, true));
        TextView sub = Ui.text(this, "手环课表 · 正在自动读取到本机…", 11.5f, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(sub);
        return card;
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
        Intent i = new Intent(this, CourseEditActivity.class);
        i.putExtra(CourseEditActivity.EXTRA_NEW, true);
        startActivityForResult(i, REQ_CREATE);
    }

    // ======================= 操作：编辑 =======================

    private void editSchedule(ScheduleStore.Schedule s) {
        editingId = s.id;
        Intent i = new Intent(this, CourseEditActivity.class);
        i.putExtra(CourseEditActivity.EXTRA_ID, s.id);
        startActivityForResult(i, REQ_EDIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            return;
        }
        // 可视化编辑器自己负责落盘（updateCourses / addLocal），这里只刷列表 + 显示它带回的结果
        String summary = data == null ? null : data.getStringExtra("summary");
        if (requestCode == REQ_EDIT) {
            status(summary != null ? summary : "课表已更新", Ui.OK);
            editingId = null;
        } else if (requestCode == REQ_CREATE) {
            status(summary != null ? summary : "已创建新课表", Ui.OK);
        }
        render();
    }

    // ======================= 操作：删除 =======================

    private void deleteSchedule(final ScheduleStore.Schedule s) {
        final boolean syncOnly = s.isSync();
        // 共用美化弹窗（Dialogs）：圆角卡 + 图标章 + 红色警示 + 幽灵/红色按钮
        Dialogs.confirm(this, R.drawable.ic_trash_2, 0,
                syncOnly ? "从本机移除课表" : "删除课表",
                "「" + s.name + "」\n" + s.sub(),
                syncOnly
                        ? "仅从本机删除；手环上仍保留，连接同步后会重新拉回。\n彻底从手环删除需手环端支持，暂未开放"
                        : "此操作不可撤销，删除后无法恢复。",
                syncOnly ? "仅删本地" : "删除", true,
                new Dialogs.Action() {
                    @Override public void run() {
                        ScheduleStore.remove(ScheduleListActivity.this, s.id);
                        status(syncOnly ? "已从本机删除「" + s.name + "」（手环仍保留）"
                                : "已删除「" + s.name + "」", Ui.OK);
                        render();
                    }
                });
    }

    // ======================= 操作：同步到手环 =======================

    /** Refresh top sync status button (active schedule): unsaved count / synced / not synced. */
    private void refreshSyncView() {
        if (syncView == null) {
            return;
        }
        ScheduleStore.Schedule s = ScheduleStore.active(this);
        if (s == null) {
            syncView.setVisibility(View.GONE);
            syncView.setText("");
            return;
        }
        syncView.setVisibility(View.VISIBLE);
        syncPendingConnect = false;
        int unsaved = SyncCoordinator.unsavedCount(s);
        if (unsaved > 0) {
            syncView.setText(unsaved + " 门课未同步 · 点此同步");
            syncView.setTextColor(Ui.ACCENT);
        } else if (s.isSync()) {
            syncView.setText("已同步 ✓");
            syncView.setTextColor(Ui.OK);
        } else {
            syncView.setText("尚未同步到手环 · 点此同步");
            syncView.setTextColor(Ui.WARN);
        }
    }

    /** Force sync active schedule: field-level 3-way merge, write local changes back to watch. */
    private void syncActive() {
        if (!SyncEngine.get(this).connected()) {
            syncPendingConnect = true;
            syncView.setText("手环未连接 · 点此去连接");
            syncView.setTextColor(Ui.WARN);
            return;
        }
        syncPendingConnect = false;
        status("正在同步课表…", Ui.ACCENT);
        SyncCoordinator.syncNow(this, new SyncCoordinator.Callback() {
            @Override public void onDone(boolean ok, String msg) {
                status((ok ? "● " : "✕ ") + msg, ok ? Ui.OK : Ui.WARN);
                render();
            }
        });
    }

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
                            SyncEngine e2 = SyncEngine.get(ScheduleListActivity.this);
                            ScheduleStore.markSynced(ScheduleListActivity.this, s.id,
                                    e2.currentDeviceId(), e2.currentDeviceName());
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

    private void status(String msg, int color) {
        statusView.setText(msg);
        statusView.setTextColor(color);
    }

    private void open(String mode) {
        Intent i = new Intent(this, TransferActivity.class);
        i.putExtra(TransferActivity.EXTRA_MODE, mode);
        startActivity(i);
    }
}