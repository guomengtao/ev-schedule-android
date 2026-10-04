package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 本次会话已做过一次自动补齐（onResume/连上瞬间强制一次，其余走 30s 节流） */
    private boolean pulledThisSession = false;
    /** 连接状态边沿检测：断开→连上时强制补齐一次 */
    private boolean wasConnected = false;
    /** 上次 render() 时的课表总数：状态回调里据此决定全量重建还是只刷状态条（防闪烁） */
    private int lastRenderedCount = -1;
    /** 自动补齐失败后允许点状态条重试 */
    private boolean pullRetryArmed = false;
    /** 头部设备条：明确「当前某某手环的课表」+ 切换入口（P3 多设备分组） */
    private LinearLayout deviceBar;
    private TextView deviceBarTitle;
    /** 当前手环对应的分组 key（deviceBar 点击展开/收起「它的课程」用） */
    private String curDeviceGroupKey = null;
    /** 当前设备分组内的课表套数（-1 = 无当前分组）：设备条据此显示「· N 套」，与分组标题行合并为一行 */
    private int curGroupCount = -1;
    /** 已展开的分组 key（deviceId / "legacy" / "local"）；默认空 = 全部折叠收敛视图 */
    private final Set<String> expandedGroups = new HashSet<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScheduleStore.ensureInitialized(this);
        if (ScheduleStore.lastReadCorrupt) {
            ScheduleStore.lastReadCorrupt = false;
            Toast.makeText(this, "本地课表数据曾损坏，已保留损坏备份；连接手环后可重新拉取",
                    Toast.LENGTH_LONG).show();
        }
        buildUi();
        // 状态回调：手环真实套数读到后刷新分组标题；并把「手环上有、本机没有」的课表
        // 自动读取到本机（入库即标记 source=sync，仍属「手环课表」组，无需手动操作）
        SyncEngine.get(this).addStatusCallback(new Runnable() {
            @Override public void run() {
                SyncEngine e = SyncEngine.get(ScheduleListActivity.this);
                boolean nowConnected = e.connected();
                // 连接边沿：断开→连上 强制补齐一次（不计 30s 节流），否则刚在手环建的课表要等最多 30s
                boolean edge = nowConnected && !wasConnected;
                wasConnected = nowConnected;
                // 列表数据没变（课表总数不变）时只刷新状态条/设备条，不全量重建列表（防闪烁）
                int n = ScheduleStore.list(ScheduleListActivity.this).size();
                if (n != lastRenderedCount) {
                    render();
                } else {
                    refreshSyncView();
                    renderDeviceBar();
                }
                // 自动补齐节流：常规 30s 一次；首次进页/刚连上时立即触发
                long now = System.currentTimeMillis();
                if (e.hasNode() && (edge || !pulledThisSession || now - lastPullAt > 30000)) {
                    lastPullAt = now;
                    pulledThisSession = true;
                    startPull(e);
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
                // 点击展开/收起「当前手环」对应的课程分组，而不是跳转到连接页
                if (curDeviceGroupKey == null) {
                    return;
                }
                if (expandedGroups.contains(curDeviceGroupKey)) {
                    expandedGroups.remove(curDeviceGroupKey);
                } else {
                    expandedGroups.add(curDeviceGroupKey);
                }
                render();
            }
        });
        deviceBarTitle = Ui.text(this, "", 12.5f, Ui.TEXT, true);
        deviceBar.addView(deviceBarTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // 手动刷新：强制向手环要一次清单并补齐（点击带旋转反馈；子控件 clickable 会消费点击，
        // 不会误触发整条的展开/收起）
        ImageView refreshBtn = new ImageView(this);
        refreshBtn.setImageResource(R.drawable.ic_refresh_cw);
        refreshBtn.setColorFilter(Ui.ACCENT);
        refreshBtn.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
        refreshBtn.setContentDescription("刷新手环课表清单");
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.animate().rotation(v.getRotation() + 360f).setDuration(700).start();
                forcePull("正在刷新手环课表清单…");
            }
        });
        deviceBar.addView(refreshBtn);
        // 「切换 ›」按钮已移除：在线状态直接在标题前用绿色实心圆点标识，整条可点进连接页
        root.addView(deviceBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.space(this, 8));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        statusView = Ui.text(this, "", 12f, Ui.MUTED, false);
        // 自动补齐失败后：点状态条重试（平时无动作）
        statusView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!pullRetryArmed) {
                    return;
                }
                pullRetryArmed = false;
                forcePull(null);
            }
        });
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
        curDeviceGroupKey = null;
        curGroupCount = -1;
        refreshSyncView();
        renderDeviceBar();
        final String activeId = ScheduleStore.activeId(this);
        List<ScheduleStore.Schedule> all = ScheduleStore.list(this);
        lastRenderedCount = all.size();
        if (all.isEmpty()) {
            // 空态按语境给引导：连着手环 = 手环侧是空的；没连 = 引导连接后自动导入
            String hint = SyncEngine.get(this).connected()
                    ? "手环上还没有课表 · 可在下方新建本地课表"
                    : "还没有课表 · 连接手环后自动导入手环已有课表，或点下方按钮新建";
            listBox.addView(Ui.text(this, hint, 12.5f, Ui.MUTED, false));
            return;
        }
        SyncEngine e = SyncEngine.get(this);
        String curDev = e.currentDeviceId();
        String curName = e.currentDeviceName();

        // 全部按设备分组，默认折叠收敛：手环（各设备）/ 未识别 / 本地课程
        Map<String, List<ScheduleStore.Schedule>> byDev = new LinkedHashMap<String, List<ScheduleStore.Schedule>>();
        List<ScheduleStore.Schedule> local = new ArrayList<ScheduleStore.Schedule>();
        for (ScheduleStore.Schedule s : all) {
            if (!s.isSync()) {
                local.add(s);
                continue;
            }
            String key = (s.deviceId.isEmpty() || "legacy-unknown".equals(s.deviceId)) ? "legacy" : s.deviceId;
            List<ScheduleStore.Schedule> g = byDev.get(key);
            if (g == null) {
                g = new ArrayList<ScheduleStore.Schedule>();
                byDev.put(key, g);
            }
            g.add(s);
        }

        // 手环分组：当前设备组置顶，其余按出现顺序；每组默认折叠。
        // 当前设备组**不再单独渲染标题行**——顶部「在线 · 设备」条（deviceBar）就是它的组头，
        // 点击该条展开/收起，故其卡片紧随设备条渲染（这就是「在线条 + 当前手环行」两行合并为一行的落地）。
        final List<Map.Entry<String, List<ScheduleStore.Schedule>>> groups =
                new ArrayList<Map.Entry<String, List<ScheduleStore.Schedule>>>(byDev.entrySet());
        // 认当前设备组：deviceId 已知走精确匹配；未知（手环端未回 get_device_id）才用
        // 「设备展示名精确相等」兜底，绝不前缀猜测
        //（「小米手环」与「小米手环 9 Pro」前缀重叠，startsWith 会互相串组）。
        Map.Entry<String, List<ScheduleStore.Schedule>> curEntry = null;
        for (Map.Entry<String, List<ScheduleStore.Schedule>> en : groups) {
            String key = en.getKey();
            boolean isCur = e.connected() && (curDev.isEmpty() ? "legacy".equals(key) : key.equals(curDev));
            if (!isCur && e.connected() && curDev.isEmpty() && !curName.isEmpty()
                    && curName.equals(devName(en.getValue()))) {
                // 仅当手环端未回 get_device_id（curDev 为空）时，才按「设备展示名精确相等」兜底归组
                isCur = true;
            }
            if (isCur) {
                curEntry = en;
                break;
            }
        }
        if (curEntry != null) {
            // 设备条即该组组头：补上「· N 套」与展开箭头
            curDeviceGroupKey = curEntry.getKey();
            curGroupCount = curEntry.getValue().size();
            renderDeviceBar();
            addGroupBody(curEntry.getKey(), curEntry.getValue(), activeId);
        }
        // 其余手环分组（未识别 legacy / 其它设备）：照旧各自渲染标题行
        for (Map.Entry<String, List<ScheduleStore.Schedule>> en : groups) {
            if (en == curEntry) {
                continue;
            }
            final String key = en.getKey();
            List<ScheduleStore.Schedule> g = en.getValue();
            String label = "legacy".equals(key)
                    ? "未识别手环（旧数据，连接后归位）"
                    : devLabel(key, g);
            listBox.addView(groupHeader(label, g.size(), key, false));
            addGroupBody(key, g, activeId);
        }
        // 本地课程分组
        if (!local.isEmpty()) {
            listBox.addView(divider());
            listBox.addView(groupHeader("本地课程", local.size(), "local", false));
            addGroupBody("local", local, activeId);
        }
    }

    /** 渲染某分组的课程卡片：展开态才渲染卡片，折叠态只留一条空隙（当前设备组与其余分组共用） */
    private void addGroupBody(String key, List<ScheduleStore.Schedule> g, String activeId) {
        if (expandedGroups.contains(key)) {
            for (ScheduleStore.Schedule s : g) {
                listBox.addView(scheduleCard(s, activeId));
                listBox.addView(Ui.space(this, 8));
            }
        } else {
            listBox.addView(Ui.space(this, 6));
        }
    }

    /** 头部设备条文案：已连接 → 「● 在线 · 设备名 ··后4位 · N 套 ▸」；未连接 → 提示
     *  （该条同时充当「当前手环」分组头，与列表里的分组标题行合并为一行） */
    private void renderDeviceBar() {
        if (deviceBar == null) {
            return;
        }
        SyncEngine e = SyncEngine.get(this);
        String curDev = e.currentDeviceId();
        // 判据用「连接状态」而非 deviceId：手环端未实现 get_device_id 时 deviceId 恒空，
        // 但 interconnect 其实已连上（ConnectionBar 显示已连接），不能误报「未连接手环」。
        if (!e.connected()) {
            deviceBarTitle.setText("未连接手环 · 课表按设备分组显示");
            return;
        }
        String name = e.currentDeviceName();
        String tail = tail4(curDev);
        String count = curGroupCount >= 0 ? " · " + curGroupCount + " 套" : "";
        String arrow = curDeviceGroupKey != null
                ? (expandedGroups.contains(curDeviceGroupKey) ? "   ▾" : "   ▸")
                : "";
        String title = "在线 · " + (name.isEmpty() ? "当前手环" : name)
                + (tail.isEmpty() ? "" : " ··" + tail)
                + count + arrow
                + (curDev.isEmpty() ? "（设备ID未取到）" : "");
        deviceBarTitle.setText(withGreenDot(title));
    }

    /** 标题开头加一个绿色实心圆点（●），仅圆点着色，表示在线（颜色走主题 token，勿写死） */
    private CharSequence withGreenDot(String text) {
        SpannableString ss = new SpannableString("● " + text);
        ss.setSpan(new ForegroundColorSpan(Ui.OK), 0, 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 设备 ID 后 4 位（展示用，区分同名设备） */
    private String tail4(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        return id.length() <= 4 ? id : id.substring(id.length() - 4);
    }

    /** 分组标题（点击展开/收起）；默认折叠，▸/▾ 表示状态 */
    private View groupHeader(String title, int n, final String key, boolean isCur) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        head.setBackground(Ui.round(Ui.CARD2, 12, Ui.LINE, this));
        head.setClickable(true);
        final boolean open = expandedGroups.contains(key);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (expandedGroups.contains(key)) {
                    expandedGroups.remove(key);
                } else {
                    expandedGroups.add(key);
                }
                render();
            }
        });
        TextView t = Ui.text(this, (open ? "▾ " : "▸ ") + title + " · " + n + " 套",
                12.5f, isCur ? Ui.ACCENT : Ui.TEXT, true);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return head;
    }

    /** 非当前设备的分组标题：手环展示名 + 设备 ID 后4位（取组内第一个带设备名的课表） */
    private String devLabel(String devId, List<ScheduleStore.Schedule> g) {
        String nm = devName(g);
        String tail = tail4(devId);
        return (nm.isEmpty() ? "手环" : nm) + (tail.isEmpty() ? "" : " ··" + tail);
    }

    /** 组内第一个非空设备展示名（分组的「精确相等」兜底归组与展示共用） */
    private String devName(List<ScheduleStore.Schedule> g) {
        for (ScheduleStore.Schedule s : g) {
            if (!s.deviceName.isEmpty()) {
                return s.deviceName;
            }
        }
        return "";
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

    // ======================= 操作：从手环自动补齐 =======================

    /** 强制补齐一次（手动刷新 / 失败重试共用）：绕过 30s 节流与「本会话已拉过」标记 */
    private void forcePull(String busyHint) {
        pullRetryArmed = false;
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            status("手环未连接 · 连接后自动拉取课表清单", Ui.WARN);
            return;
        }
        lastPullAt = 0;
        pulledThisSession = false;
        if (busyHint != null) {
            status(busyHint, Ui.ACCENT);
        }
        // 先要一次清单（结果经状态回调触发补齐），避免清单未就绪时空跑
        e.refreshBandScheduleCount();
    }

    /** 自动补齐（带 UI 反馈）：加载中 → 成功 N 套 / 失败可点状态条重试 */
    private void startPull(final SyncEngine e) {
        if (e.isPullingMissing()) {
            return;
        }
        status("正在从手环读取课表清单…", Ui.ACCENT);
        e.pullMissingFromWatch(this, new SyncEngine.PullCallback() {
            @Override public void onStart() { }
            @Override public void onDone(int pulled, int failed) {
                if (pulled > 0) {
                    status("已从手环同步 " + pulled + " 套课表到本机 ✓", Ui.OK);
                    Toast.makeText(ScheduleListActivity.this,
                            "已从手环同步 " + pulled + " 套课表", Toast.LENGTH_SHORT).show();
                } else if (failed > 0) {
                    pullRetryArmed = true;
                    status("从手环读取课表失败（" + failed + " 项）· 点此重试", Ui.ERR);
                } else {
                    status("", Ui.MUTED);
                }
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