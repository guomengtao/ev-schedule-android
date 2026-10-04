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
    /** 同步徽标：原为页顶全宽胶囊，现降级进「当前课表卡」内（D3）。卡片未渲染时为空。 */
    private TextView syncBadgeView;
    /** 设备条右侧「N 套」计数徽标（仅已连接且已知真实套数时显示） */
    private TextView deviceCountView;
    /** 设备条刷新图标：未连接时转灰（与设备名同步降级） */
    private ImageView deviceRefreshBtn;
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
    /** 已折叠的分组 key（deviceId / "legacy" / "local"）。
     *  ⚠️ 语义与旧版相反：默认空集合 = **全部展开**（D2 —— 管理页应先把课表清单摊开，
     *  而不是让人每次进页面都要先点一下才看得到课表名）。 */
    private final Set<String> collapsedGroups = new HashSet<String>();

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
        // 顶栏「课表库」+ 右侧 ⊕：创建入口唯一化（新建 / 导入 / 导出 全收进菜单，D6）
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        root.addView(Ui.space(this, Ui.GAP_SM));

        // 设备条（D1）：原 ConnectionBar「已连接 · 设备 · v1.7.x」与本条合并为唯一一条。
        // 两栏原本说的是同一件事（设备 + 连接状态），竖直叠放只会互相打架、稀释注意力。
        // 原「本机保存的全部课表：可切换、编辑、同步到手环」说明行已删（自解释，白占一行）。
        deviceBar = new LinearLayout(this);
        deviceBar.setOrientation(LinearLayout.HORIZONTAL);
        deviceBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        deviceBar.setBackground(Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        deviceBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        deviceBar.setClickable(true);
        deviceBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 未连接：整条就是「去连接」入口；
                // 已连接：本条即当前设备分组的组头，点按展开 / 收起它（保留原有能力，不另起一行）。
                if (!SyncEngine.get(ScheduleListActivity.this).connected()) {
                    startActivity(new Intent(ScheduleListActivity.this, BandActivity.class));
                    return;
                }
                if (curDeviceGroupKey == null) {
                    return;
                }
                if (collapsedGroups.contains(curDeviceGroupKey)) {
                    collapsedGroups.remove(curDeviceGroupKey);
                } else {
                    collapsedGroups.add(curDeviceGroupKey);
                }
                render();
            }
        });
        deviceBarTitle = Ui.textMedium(this, "", 12.5f, Ui.TEXT);
        deviceBar.addView(deviceBarTitle, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // 右侧「N 套」计数徽标（设备条同时承担「当前手环分组头」的职责）
        deviceCountView = Ui.textMedium(this, "", 11f, Ui.ACCENT);
        deviceCountView.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        deviceCountView.setBackground(Ui.round(Ui.ACCENT_LIGHT, 14, 0, this));
        deviceCountView.setVisibility(View.GONE);
        deviceBar.addView(deviceCountView);
        // 手动刷新：强制向手环要一次清单并补齐（点击带旋转反馈；子控件 clickable 会消费点击，
        // 不会误触发整条的展开/收起）
        ImageView refreshBtn = new ImageView(this);
        deviceRefreshBtn = refreshBtn;
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

        // 瞬时状态行：原在列表下方，上移紧贴设备条（避免与列表尾部抢位，B3.4）
        statusView = Ui.text(this, "", 12f, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 6), 0, 0);
        // 初始无消息 → 直接收起（否则首帧会留一条空行，白占约 23dp）
        statusView.setVisibility(View.GONE);
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

        root.addView(Ui.space(this, Ui.GAP_SM));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        setContentView(Ui.wrapWithBottomBar(this, root, 1));
        render();
    }

    // ======================= 顶栏 + 创建入口菜单 =======================

    /** 顶栏：左「课表库」标题 + 右 ⊕（44dp 热区 / 34dp 视觉，与首页 ⊕ 位置一致）。 */
    private View buildTopBar() {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);

        TextView t = Ui.textMediumLh(this, "课表库", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
        bar.addView(t, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER_VERTICAL));

        // 44dp 热区（视觉 34dp）：小控件也要点得中
        android.widget.FrameLayout hit = new android.widget.FrameLayout(this);
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setColorFilter(Ui.ACCENT);
        plus.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        plus.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        hit.addView(plus, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34), android.view.Gravity.CENTER));
        hit.setClickable(true);
        hit.setContentDescription("新建或导入课表");
        hit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCreateMenu(v); }
        });
        bar.addView(hit, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END));
        return bar;
    }

    /** ⊕ 菜单（沿用首页 showPlusMenu 的既有样式：白底圆角单框 + 纯文字行 + 细分隔线，点外面收起）。
     *  把「新建本地课表 / 导入课程表 / 导出课程表」三类创建入口收拢到一处（D6）。 */
    private void showCreateMenu(View anchor) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        int pad = Ui.dp(this, Ui.GAP_SM);
        panel.setPadding(pad, pad, pad, pad);

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(panel,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        pw.setOutsideTouchable(true);

        addMenuItem(panel, pw, "新建本地课表", null);
        if (Variant.isEv(this)) {
            panel.addView(menuDivider());
            addMenuItem(panel, pw, "导入课程表", TransferActivity.MODE_IMPORT);
            panel.addView(menuDivider());
            addMenuItem(panel, pw, "导出课程表", TransferActivity.MODE_EXPORT);
        }

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        pw.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                loc[0] + anchor.getWidth() - panel.getMeasuredWidth(),
                loc[1] + anchor.getHeight() + Ui.dp(this, 6));
    }

    /** 菜单项之间的 1dp 细分隔线（左右留 12dp 不顶满）。 */
    private View menuDivider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        p.leftMargin = Ui.dp(this, Ui.GAP_MD);
        p.rightMargin = Ui.dp(this, Ui.GAP_MD);
        v.setLayoutParams(p);
        return v;
    }

    /** 菜单里的一行：只有文字，点击即执行。 */
    private void addMenuItem(LinearLayout panel, final android.widget.PopupWindow pw,
                             String label, final String transferMode) {
        TextView tx = Ui.textLh(this, label, Ui.SP_SUBTITLE, Ui.TEXT, false, Ui.LH_SUBTITLE);
        tx.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tx.setPadding(Ui.dp(this, Ui.GAP_LG), 0, Ui.dp(this, Ui.GAP_LG), 0);
        tx.setClickable(true);
        panel.addView(tx, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.TOUCH_MIN)));
        tx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pw.dismiss();
                if (transferMode != null) {
                    open(transferMode);
                } else {
                    createLocal();
                }
            }
        });
    }

    // ======================= 列表渲染 =======================

    private void render() {
        listBox.removeAllViews();
        syncBadgeView = null;      // 卡片重建 → 旧徽标引用失效，列表构建完成后统一回填
        curDeviceGroupKey = null;
        curGroupCount = -1;
        renderDeviceBar();
        final String activeId = ScheduleStore.activeId(this);
        List<ScheduleStore.Schedule> all = ScheduleStore.list(this);
        lastRenderedCount = all.size();
        if (all.isEmpty()) {
            listBox.addView(emptyState());
            refreshSyncView();
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
            listBox.addView(groupHeader(label, g.size(), key));
            addGroupBody(key, g, activeId);
        }
        // 本地课程分组（组头已是细行、自带呼吸感 → 不再需要分割线，B3.3）
        if (!local.isEmpty()) {
            listBox.addView(groupHeader("本地课程", local.size(), "local"));
            addGroupBody("local", local, activeId);
        }
        refreshSyncView();
    }

    /** 渲染某分组的课程卡片：默认渲染（collapsedGroups 为空 = 全部展开，D2），
     *  折叠态只留一条空隙（当前设备组与其余分组共用）。 */
    private void addGroupBody(String key, List<ScheduleStore.Schedule> g, String activeId) {
        if (collapsedGroups.contains(key)) {
            listBox.addView(Ui.space(this, 6));
            return;
        }
        for (ScheduleStore.Schedule s : g) {
            listBox.addView(scheduleCard(s, activeId));
            listBox.addView(Ui.space(this, 8));
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
        // 但 interconnect 其实已连上，不能误报「未连接手环」。
        if (!e.connected()) {
            deviceBarTitle.setText("未连接手环 · 点此连接");
            deviceBarTitle.setTextColor(Ui.MUTED);
            if (deviceCountView != null) {
                deviceCountView.setVisibility(View.GONE);
            }
            if (deviceRefreshBtn != null) {
                deviceRefreshBtn.setColorFilter(Ui.MUTED);
            }
            return;
        }
        String name = e.currentDeviceName().trim();   // 手环端回的名字带多余空格，展示前裁掉
        String tail = tail4(curDev);
        if (deviceRefreshBtn != null) {
            deviceRefreshBtn.setColorFilter(Ui.ACCENT);
        }
        String title = (name.isEmpty() ? "当前手环" : name)
                + (tail.isEmpty() ? "" : " ··" + tail)
                + (curDev.isEmpty() ? "（设备ID未取到）" : "");
        deviceBarTitle.setText(withGreenDot(title));
        deviceBarTitle.setTextColor(Ui.TEXT);
        if (deviceCountView != null) {
            if (curGroupCount >= 0) {
                deviceCountView.setText(curGroupCount + " 套");
                deviceCountView.setVisibility(View.VISIBLE);
            } else {
                deviceCountView.setVisibility(View.GONE);
            }
        }
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

    /** 分组标题（点击展开/收起）。D2：由「厚卡」降为「细行」—— 去掉卡片底色与描边、
     *  字号降到 SP_CAPTION 且用次要色，把它压到卡片视觉之下，
     *  让「课表名」成为页内唯一的视觉锚点。 */
    private View groupHeader(String title, int n, final String key) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(0, Ui.dp(this, Ui.GAP_LG), 0, Ui.dp(this, 7));
        head.setClickable(true);
        final boolean open = !collapsedGroups.contains(key);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (collapsedGroups.contains(key)) {
                    collapsedGroups.remove(key);
                } else {
                    collapsedGroups.add(key);
                }
                render();
            }
        });
        TextView t = Ui.textMediumLh(this, title, Ui.SP_CAPTION, Ui.MUTED, Ui.LH_CAPTION);
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = Ui.text(this, n + " 套", 11f, Ui.MUTED, false);
        cnt.setPadding(0, 0, Ui.dp(this, 6), 0);
        head.addView(cnt);
        // 箭头用 chevron_right 旋转：展开 90°（朝下）/ 折叠 0°（朝右）
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(Ui.MUTED);
        arrow.setRotation(open ? 90f : 0f);
        head.addView(arrow, new LinearLayout.LayoutParams(
                Ui.dp(this, 13), Ui.dp(this, 13)));
        return head;
    }

    /** 非当前设备的分组标题：手环展示名 + 设备 ID 后4位（取组内第一个带设备名的课表） */
    private String devLabel(String devId, List<ScheduleStore.Schedule> g) {
        String nm = devName(g).trim();
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

    // ======================= 课表卡片（D4 / D5 重排） =======================

    /** 课表卡片。当前课表 = 主卡（主色描边 + 4dp 主色左竖条 + 同步徽标 + 「使用中」）；
     *  其余 = 次卡（⋯ 溢出菜单 + 「设为当前」）。
     *  破坏性操作不再与「编辑」并排常驻（D5），互斥选择也不再借 CheckBox 表达（D4）。 */
    private View scheduleCard(final ScheduleStore.Schedule s, final String activeId) {
        final boolean active = s.id.equals(activeId);

        // 外层横向容器承载「卡片底色 + 描边」；当前卡描边走主色
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(active
                ? Ui.round(Ui.CARD, Ui.R_CARD, Ui.ACCENT, this)
                : Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));

        // 当前课表：左缘 4dp 主色竖条（只圆左侧两角，与卡片左缘严丝合缝）
        if (active) {
            View strip = new View(this);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(Ui.ACCENT);
            float r = Ui.dp(this, Ui.R_CARD);
            // 顺序：左上 右上 右下 左下
            g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            strip.setBackground(g);
            card.addView(strip, new LinearLayout.LayoutParams(
                    Ui.dp(this, 4), LinearLayout.LayoutParams.MATCH_PARENT));
        }

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        // 左内边距扣掉竖条宽度，保证两种卡的「标题左缘」严格对齐
        inner.setPadding(Ui.dp(this, active ? Ui.GAP_MD - 4 : Ui.GAP_MD), Ui.dp(this, 4),
                Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 8));

        // ── 行 1：课表名（SP_TITLE 17sp，做页内视觉锚点）+ ⋯ 溢出菜单
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView titleView = Ui.textMediumLh(this, s.name, Ui.SP_TITLE,
                active ? Ui.ACCENT : Ui.TEXT, Ui.LH_TITLE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(titleView,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(overflowBtn(s), new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN)));
        inner.addView(head);

        // ── 行 2：来源 · N 门课（相对时间另起一段，避免一行过长）
        TextView sub = Ui.textLh(this, subShort(s), Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
        inner.addView(sub);

        // ── 行 3：同步徽标（左）+ 「使用中」/「设为当前」（右）。整行固定 TOUCH_MIN 高，
        //         保证两种卡的卡片总高一致（否则列表会高高低低）。
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView badge = syncBadge();
        if (active) {
            // 同步是「当前课表」的属性 → 徽标点即同步（原页顶胶囊的交互原样保留，D3）
            syncBadgeView = badge;
            applySyncBadge(badge, s, true);
            row3.addView(badgeTouch(badge), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.TOUCH_MIN)));
            TextView time = Ui.text(this, syncAgo(s), Ui.SP_MICRO, Ui.MUTED, false);
            time.setPadding(Ui.dp(this, Ui.GAP_SM), 0, 0, 0);
            row3.addView(time, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row3.addView(usedBadge());
        } else {
            applySyncBadge(badge, s, false);
            row3.addView(badge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            View grow = new View(this);
            row3.addView(grow, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row3.addView(setCurrentBtn(s), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.TOUCH_MIN)));
        }
        inner.addView(row3, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.TOUCH_MIN)));

        card.addView(inner, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    /** 副信息：「来源 · N 门课」 */
    private String subShort(ScheduleStore.Schedule s) {
        return (s.isSync() ? "来自手环" : "仅本机") + " · " + s.courses.size() + " 门课";
    }

    /** 最近一次同步的相对时间（无值返回空串，不放占位符） */
    private String syncAgo(ScheduleStore.Schedule s) {
        if (!s.isSync() || s.syncedAt <= 0) {
            return "";
        }
        return CourseCache.ago(s.syncedAt);
    }

    /** ⋯ 溢出菜单按钮：44dp 热区 / 30dp 视觉。破坏性操作全收进这里（D5）。 */
    private View overflowBtn(final ScheduleStore.Schedule s) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_more_vertical);
        ic.setColorFilter(Ui.MUTED);
        ic.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        ic.setBackground(Ui.round(Ui.CARD2, 9, 0, this));
        box.addView(ic, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 30), Ui.dp(this, 30), android.view.Gravity.CENTER));
        box.setClickable(true);
        box.setContentDescription("更多操作");
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCardMenu(v, s); }
        });
        return box;
    }

    /** 卡片 ⋯ 菜单：编辑课表 / 同步到手环（仅本机卡）/ 从本机移除（红色警示）。 */
    private void showCardMenu(View anchor, final ScheduleStore.Schedule s) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        int pad = Ui.dp(this, Ui.GAP_SM);
        panel.setPadding(pad, pad, pad, pad);

        final android.widget.PopupWindow pw = new android.widget.PopupWindow(panel,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        pw.setOutsideTouchable(true);

        cardMenuItem(panel, pw, "编辑课表", Ui.TEXT, new Runnable() {
            @Override public void run() { editSchedule(s); }
        });
        if (!s.isSync()) {
            panel.addView(menuDivider());
            cardMenuItem(panel, pw, "同步到手环", Ui.TEXT, new Runnable() {
                @Override public void run() { syncToWatch(s); }
            });
        }
        panel.addView(menuDivider());
        cardMenuItem(panel, pw, "从本机移除", Ui.ERR, new Runnable() {
            @Override public void run() { deleteSchedule(s); }
        });

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        pw.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                loc[0] + anchor.getWidth() - panel.getMeasuredWidth(),
                loc[1] + anchor.getHeight() + Ui.dp(this, 4));
    }

    private void cardMenuItem(LinearLayout panel, final android.widget.PopupWindow pw,
                              String label, int color, final Runnable action) {
        TextView tx = Ui.textLh(this, label, Ui.SP_BODY, color, false, Ui.LH_BODY);
        tx.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tx.setPadding(Ui.dp(this, Ui.GAP_LG), 0, Ui.dp(this, Ui.GAP_LG), 0);
        tx.setClickable(true);
        panel.addView(tx, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.TOUCH_MIN)));
        tx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pw.dismiss();
                action.run();
            }
        });
    }

    /** 同步徽标胶囊（紧凑视觉）。 */
    private TextView syncBadge() {
        TextView v = Ui.textMedium(this, "", 11f, Ui.ACCENT);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
        return v;
    }

    /** 给徽标套一个 44dp 热区（视觉仍是紧凑胶囊）：视觉可小，热区不能缩。 */
    private android.widget.FrameLayout badgeTouch(final TextView pill) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.addView(pill, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL));
        box.setClickable(true);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (syncPendingConnect) {
                    syncPendingConnect = false;
                    startActivity(new Intent(ScheduleListActivity.this, BandActivity.class));
                } else {
                    syncActive();
                }
            }
        });
        return box;
    }

    /** 「使用中」徽标（当前课表，不可点 —— 当前课表不能取消自己）。 */
    private TextView usedBadge() {
        TextView v = Ui.textMedium(this, "使用中", 11f, Ui.OK);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
        v.setBackground(Ui.round(Ui.OK_LIGHT, 14, 0, this));
        return v;
    }

    /** 「设为当前」描边按钮（D4：互斥选择改用单选语义，替代原 CheckBox）；44dp 热区。 */
    private View setCurrentBtn(final ScheduleStore.Schedule s) {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        TextView pill = Ui.textMedium(this, "设为当前", Ui.SP_CAPTION, Ui.ACCENT);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setPadding(Ui.dp(this, 13), Ui.dp(this, 5), Ui.dp(this, 13), Ui.dp(this, 5));
        pill.setBackground(Ui.round(0x00000000, 14, Ui.ACCENT, this));
        box.addView(pill, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL));
        box.setClickable(true);
        box.setContentDescription("设为当前");
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!s.id.equals(ScheduleStore.activeId(ScheduleListActivity.this))) {
                    ScheduleStore.setActive(ScheduleListActivity.this, s.id);
                    status("已切换到「" + s.name + "」", Ui.OK);
                    render();
                }
            }
        });
        return box;
    }

    /** 同步徽标三态（D3）。优先级：待连接 > 未连接 > 有未同步 > 从未同步 > 已同步。 */
    private void applySyncBadge(TextView v, ScheduleStore.Schedule s, boolean live) {
        if (v == null) {
            return;
        }
        boolean connected = SyncEngine.get(this).connected();
        if (live && syncPendingConnect && !connected) {
            v.setText("手环未连接 · 点此去连接");
            v.setTextColor(Ui.WARN);
            v.setBackground(Ui.round(Ui.WARN_LIGHT, 14, 0, this));
            return;
        }
        int unsaved = SyncCoordinator.unsavedCount(s);
        if (!connected) {
            // 未连接 → 明确「无法同步」，别让用户误以为是自己忘了同步（语义澄清）
            v.setText("未连接 · 无法同步");
            v.setTextColor(Ui.WARN);
            v.setBackground(Ui.round(Ui.WARN_LIGHT, 14, 0, this));
        } else if (s.isSync() && unsaved == 0) {
            v.setText("✓ 已同步");
            v.setTextColor(Ui.OK);
            v.setBackground(Ui.round(Ui.OK_LIGHT, 14, 0, this));
        } else if (unsaved > 0) {
            v.setText(unsaved + " 门课未同步");
            v.setTextColor(Ui.ACCENT);
            v.setBackground(Ui.round(Ui.ACCENT_LIGHT, 14, 0, this));
        } else {
            v.setText("尚未同步到手环");
            v.setTextColor(Ui.WARN);
            v.setBackground(Ui.round(Ui.WARN_LIGHT, 14, 0, this));
        }
    }

    // ======================= 空态（D7） =======================

    /** 空态：线稿图 + 标题 + 双 CTA；主 CTA 按连接状态切换语境。 */
    private View emptyState() {
        final boolean connected = SyncEngine.get(this).connected();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(this, 44), 0, Ui.dp(this, 20));

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.ic_calendar);
        art.setColorFilter(Ui.LINE);
        box.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));

        TextView h = Ui.textMediumLh(this, "还没有课表", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
        h.setPadding(0, Ui.dp(this, Ui.GAP_LG), 0, 0);
        box.addView(h);

        TextView p = Ui.textLh(this,
                connected ? "手环上还没有课表，可以新建一套本地课表开始。"
                          : "连接手环会自动导入手环里的课表，也可以直接新建一套本地课表。",
                Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        p.setGravity(android.view.Gravity.CENTER);
        p.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(p);

        View.OnClickListener toConnect = new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(ScheduleListActivity.this, BandActivity.class));
            }
        };
        View.OnClickListener toCreate = new View.OnClickListener() {
            @Override public void onClick(View v) { createLocal(); }
        };

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, Ui.dp(this, 22), 0, 0);
        btns.addView(Ui.button(this, connected ? "＋ 新建本地课表" : "连接手环",
                true, connected ? toCreate : toConnect),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        btns.addView(Ui.space(this, Ui.GAP_SM));
        btns.addView(Ui.button(this, connected ? "连接手环" : "＋ 新建本地课表",
                false, connected ? toConnect : toCreate),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(btns);
        return box;
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

    /** 回填「当前课表卡」内的同步徽标（D3：原为页顶全宽胶囊，现贴着它所属的那套课表）。
     *  当前设备分组被折叠时没有卡片 → 徽标引用为空，此处自然空转（可接受）。 */
    private void refreshSyncView() {
        if (syncBadgeView == null) {
            return;
        }
        ScheduleStore.Schedule s = ScheduleStore.active(this);
        if (s == null) {
            return;
        }
        // ⚠️ 只在「已连上」时才清「待连接」标记：否则会把自己刚设上的二次确认提示抹掉
        //（点徽标 → 提示「点此去连接」→ 再点才跳转，这个两步确认不能被刷新冲掉）。
        if (SyncEngine.get(this).connected()) {
            syncPendingConnect = false;
        }
        applySyncBadge(syncBadgeView, s, true);
    }

    /** 同步当前课表：字段级三向合并，把本机改动写回手环。 */
    private void syncActive() {
        if (!SyncEngine.get(this).connected()) {
            syncPendingConnect = true;
            refreshSyncView();
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
        // 无消息时整行收起：空状态行也会占掉约 26dp 竖向空间，首屏因此少露半张卡片
        statusView.setVisibility(
                (msg == null || msg.length() == 0) ? View.GONE : View.VISIBLE);
    }

    private void open(String mode) {
        Intent i = new Intent(this, TransferActivity.class);
        i.putExtra(TransferActivity.EXTRA_MODE, mode);
        startActivity(i);
    }
}