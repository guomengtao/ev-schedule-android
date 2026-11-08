package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 首页（方案 A）：首页 = 当前课表的周视图。
 *
 * 布局：
 *   标题栏（EV 课程表 + 手动同步按钮）
 *   当前课表名（点击 → 课程表管理）
 *   7 列周视图（主视觉，断网/未连接也能看：默认本地课表兜底）
 *   快捷操作 2×2（呼叫手环 / 上课了 / 留言 / 下课了）
 *   连接状态迷你条（成功/失败/连接中都在这一行内呈现；失败附重试）
 *
 * EvBox 变体没有 schedule 域 → 保留旧的连接工具型首页（buildLegacyUi）。
 */
public class HomeActivity extends Activity {

    private int lastThemeVersion = 0;

    private static final int PHASE_CONNECT = 1, PHASE_PROFILE = 2, PHASE_DONE = 3, PHASE_ERR = 4;
    private static final int TIME_COL_W = 36; // 周课表时间列宽（dp）
    /** 节次归一容差（分钟）：跨天同一节课开始时间差 ≤ 该值视为同一节，归到同一行。 */
    private static final int SLOT_TOLERANCE_MIN = 5;

    // ---- EV 新首页视图 ----
    private TextView scheduleNameView, miniStatusView;
    private LinearLayout weekBox, errorCard, quickBox;
    /** 今日课程列表容器（首页 = 当天视图；完整周课表在 EXTRA_VIEW=week 模式里） */
    private LinearLayout todayBox;
    /** 日期头：主行「11月8日 周日」/ 副行「今天 3 节课 · 第 10 周」 */
    private TextView dateTitleView, dateSubView;
    /** 首页日期偏移：0=今天，-1=昨天，+1=明天（左右箭头切换，最多 ±14 天） */
    private int dayOffset = 0;

    /**
     * 视图模式（2026-11-08 首页改版，evtask-D-ev-schedule--261108-e8q4tv）。
     *
     * <p>为什么复用同一个 Activity 而不是新建 WeekActivity：周课表渲染（renderWeek /
     * weekGridWithTime）与首页共享 20+ 个字段和 300+ 行逻辑，搬出去要么大量复制、
     * 要么做大重构；而两者共用同一份数据源与主题，只是「显示哪一块」不同。
     * 用启动参数切换渲染分支，零复制、零搬移，风险最小。
     */
    public static final String EXTRA_VIEW = "view";
    public static final String VIEW_WEEK = "week";
    /** true = 本次是「只看周课表」模式：不建首页块、不连蓝牙、不跑升级检查 */
    private boolean weekOnly;
    private TextView hintView;
    private int phase = PHASE_CONNECT;

    private TextView greetingView, pageTitleView, weekLabelView;
    /** 连接迷你条容器（与错误卡互斥显示，修"同屏两处说未连接"）；§4.2 起改为「瞬时反馈」：显示后自动淡出 */
    private LinearLayout miniBarView;
    /** §D5 顶栏连接状态胶囊：圆点（绿=已连接 / 蓝=连接中 / 灰=未连接 / 红=失败）+ 文字，点按进「手环」页 */
    private View statusDotView;
    private TextView statusPillText;
    private android.widget.FrameLayout statusPillBtn;
    /** §D2 「接下来」焦点卡容器（renderFocusCard 填充） */
    private LinearLayout focusCardView;
    private LinearLayout dateStripView, deviceCardView;
    private TextView deviceNameView, deviceStatusView, batteryView;
    /** P2：页面级 ScrollView（Ui.wrapWithBottomBar 内），用于日期表头滚动吸顶 */
    private android.widget.ScrollView pageScroller;
    /** 表头在内容坐标系里的 top；-1 表示尚未测量 */
    private int stickyHeaderTop = -1;
    /** 表头允许下移的最大值（不滑出网格卡片底部） */
    private int stickyHeaderMax = 0;
    /** 表头当前是否处于浮起（吸顶）状态，避免每次滚动都重设 elevation */
    private boolean stickyHeaderFloating = false;
    private int selectedDay = CourseCache.todayIndex();
    /** 周视图偏移：0=本周，-1 上周，+1 下周；点周标签回到本周 */
    private int weekOffset = 0;

    // ---- 旧版（EvBox）视图 ----
    private TextView welcomeView, statusView, estimateView;
    private LinearLayout stepsView, actionsView;
    private final TextView[] stepRows = new TextView[4];
    private final int[] states = new int[4];
    private boolean legacy;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean ticking = false;
    /** 新首页迷你条：当前连接步骤文案（tick 组合「步骤 + 预计剩余」时避免互相覆盖） */
    private String connectingLabel = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) {
                return;
            }
            int left = estimateSeconds();
            String s = left > 0 ? "预计还需 ~" + left + " 秒" : "马上就好…";
            if (legacy && estimateView != null) {
                estimateView.setText(s);
            } else if (miniStatusView != null) {
                miniStatus("● " + connectingLabel + " · " + s, Ui.ACCENT);
            }
            ui.postDelayed(this, 1000);
        }
    };

    private static final int REQ_NOTIF = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        legacy = !Variant.isEv(this);
        weekOnly = VIEW_WEEK.equals(getIntent().getStringExtra(EXTRA_VIEW));
        if (!legacy) {
            // 数据层兜底：旧缓存迁移 / 出厂默认课表（保证首页永远有课表可看）
            ScheduleStore.ensureInitialized(this);
        }
        buildUi();
        // 周课表模式是纯只读视图：不注册状态回调、不拉前台服务、不检查升级、不连蓝牙。
        // 这样从首页点进来不会触发一次连接（用户只是来看课表的）。
        if (weekOnly) {
            return;
        }
        // 状态回调（onCreate 注册一次；onResume 不重复注册，多监听列表会堆积）：
        // 真实套数读到后 / 连接状态变化时，迷你条自动刷新
        if (!legacy) {
            SyncEngine.get(this).addStatusCallback(new Runnable() {
                @Override public void run() {
                    refreshMiniFromEngine();
                    // 顺带刷新版本身份（5 分钟节流；手环 ≥1.7.96 才回 auth 域）
                    AuthState.maybeRefreshFromWatch(HomeActivity.this);
                }
            });
        }
        // 常驻前台服务：进程活着才能在后台收到手环推来的留言（可在设置页关闭）
        SyncService.startIfEnabled(this);
        requestNotifPermission();
        // 自动升级：静默检查（仅发现新版本才弹窗，失败不打扰；见 docs/自动升级实现方案.md）
        UpdateChecker.checkSilent(this);
        installNodeChooser();
        startConnect();
    }

    /** Android 13+ 需用户授权通知，前台服务的常驻通知才会显示 */
    private void requestNotifPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            }
        } catch (Throwable ignored) {
        }
    }

    // ======================= 多手环：让用户选连哪一台 =======================

    /** 发现多台已连接设备时弹选择框（只选一次，之后记住） */
    private void installNodeChooser() {
        SyncEngine.get(this).setNodeChooser(new SyncEngine.NodeChooser() {
            @Override public void onNeedChoose(java.util.List<SyncEngine.DeviceInfo> devices,
                                               String preferredId) {
                showDeviceChooser(devices, preferredId);
            }
        });
    }

    private void showDeviceChooser(final java.util.List<SyncEngine.DeviceInfo> devices,
                                   String preferredId) {
        if (isFinishing() || devices == null || devices.isEmpty()) {
            return;
        }
        final String[] names = new String[devices.size()];
        int checked = -1;
        for (int i = 0; i < devices.size(); i++) {
            String did = devices.get(i).id;
            names[i] = devices.get(i).name + "   · 编号 "
                    + (did.length() > 4 ? did.substring(did.length() - 4) : did);
            if (devices.get(i).id.equals(preferredId)) {
                checked = i;
            }
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("找到 " + devices.size() + " 个手环，请选择")
                    .setSingleChoiceItems(names, checked,
                            new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int which) {
                                    d.dismiss();
                                    SyncEngine.get(HomeActivity.this)
                                            .chooseNode(devices.get(which).id);
                                }
                            })
                    .setCancelable(false)
                    .show();
        } catch (Throwable ignored) {
            // 极端情况下弹不出来：退回默认（第一台），保证连接流程不卡死
            SyncEngine.get(this).chooseNode(devices.get(0).id);
        }
    }

    // ======================= UI：EV 新首页 =======================

    private void buildUi() {
        if (legacy) {
            buildLegacyUi();
            return;
        }
        if (weekOnly) {
            buildWeekOnlyUi();
            return;
        }
        LinearLayout root = Ui.screen(this);
        // 首页专属边距：左右 16 / 上 8（Ui.screen 的 20/12 偏松；不动全局方法，只在此覆盖）
        root.setPadding(Ui.dp(this, Ui.GAP_LG), Ui.dp(this, Ui.GAP_SM),
                Ui.dp(this, Ui.GAP_LG), Ui.dp(this, Ui.GAP_SM));

        // ---- 日期头：一眼回答「今天几号、星期几、第几周、几节课」----
        // 手环首页第一屏也是这三件事，同步器对齐它，用户不用在两台设备上换脑子。
        buildDateHead(root);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // ---- §D2 焦点卡「接下来」：把「我现在该干嘛」从"两次定位"变"一次阅读" ----
        focusCardView = new LinearLayout(this);
        focusCardView.setOrientation(LinearLayout.VERTICAL);
        root.addView(focusCardView);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // ---- 今日课程列表（首页只放当天；完整周课表走 EXTRA_VIEW=week 的独立页）----
        todayBox = new LinearLayout(this);
        todayBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(todayBox);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // ---- 手势：下拉回今天（日期头起手）/ 左滑进周课表 ----
        attachGestures(root);

        // ---- 周课表入口 ----
        root.addView(buildWeekEntry());
        root.addView(Ui.space(this, Ui.GAP_LG));

        // ---- 快捷操作 2×2（呼叫手环 / 上课了 / 留言 / 下课了）----
        // 类注释里承诺过这一块，但 quickBox 字段声明后 buildUi 从未渲染 → 首页根本点不到；此处补回。
        quickBox = buildQuickBox();
        root.addView(quickBox);
        root.addView(Ui.space(this, Ui.GAP_LG));

        // ---- 设备卡已移至「手环」页（连接管理集中在设备作用域页；首页只留迷你状态条） ----

        // ---- 连接状态条（§4.2 改为「瞬时反馈」：显示后 2.6s 自动收起，稳态只留顶栏状态圆点）----
        LinearLayout bar = Ui.card(this);
        bar.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        bar.setPadding(Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 10),
                Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 10));
        miniStatusView = Ui.textLh(this, "", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        bar.addView(miniStatusView);
        bar.setVisibility(View.GONE);   // 无常驻文案：由 miniStatus/flashStatus 按需显示
        miniBarView = bar;
        root.addView(bar);
        root.addView(Ui.space(this, Ui.GAP_MD));

        // ---- 错误卡（默认隐藏；附重试） ----
        errorCard = Ui.card(this);
        errorCard.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        errorCard.setVisibility(View.GONE);
        hintView = Ui.textLh(this, "", Ui.SP_BODY, Ui.WARN, false, Ui.LH_BODY);
        errorCard.addView(hintView);
        errorCard.addView(Ui.space(this, Ui.GAP_MD));
        errorCard.addView(Ui.grid(this,
                Ui.button(this, "再试一次", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { startConnect(); }
                }),
                Ui.button(this, "打开手环课表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                })));
        root.addView(errorCard);

        root.addView(Ui.space(this, Ui.GAP_SM));

        // 顶栏：§D1 删掉居中「Ev课程表」标题（App 名零信息量，且与「本周课表」构成双标题）；
        // 右上角 ⊕ 圆钮保留（微信式下拉菜单：导出课程/导入课程/呼叫手环）
        android.widget.FrameLayout header = new android.widget.FrameLayout(this);
        ImageView plusBtn = new ImageView(this);
        plusBtn.setImageResource(R.drawable.ic_plus);
        plusBtn.setColorFilter(Ui.ACCENT);
        plusBtn.setBackground(Ui.round(Ui.ACCENT_LIGHT, 22, 0, this));
        // 热区 44dp：视觉 24dp 图标 + 10dp padding（原 36dp 热区偏小）
        plusBtn.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        plusBtn.setClickable(true);
        plusBtn.setContentDescription("更多操作");
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPlusMenu(v);
            }
        });
        android.widget.FrameLayout.LayoutParams pbP = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        pbP.rightMargin = Ui.dp(this, Ui.GAP_SM);
        header.addView(plusBtn, pbP);

        // §D5 连接状态胶囊：圆点 + 文字（10dp 圆点几乎不可发现 → 加字），44dp 热区，点按进「手环」页
        statusPillBtn = new android.widget.FrameLayout(this);
        statusPillBtn.setClickable(true);
        statusPillBtn.setContentDescription("手环连接状态");
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 胶囊弱化成「圆点 + 弱字」：首页主角是今天的课，连接状态是背景信息，不该抢第一眼
        pill.setPadding(0, 0, 0, 0);
        statusDotView = new View(this);
        statusDotView.setBackground(Ui.round(Ui.MUTED, 4, 0, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(Ui.dp(this, 7), Ui.dp(this, 7));
        dotLp.rightMargin = Ui.dp(this, 4);
        pill.addView(statusDotView, dotLp);
        statusPillText = Ui.textMediumLh(this, "未连接", Ui.SP_MICRO, Ui.MUTED, Ui.LH_MICRO);
        pill.addView(statusPillText);
        statusPillBtn.addView(pill, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        statusPillBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, BandActivity.class));
            }
        });
        // 热区：高度撑到 44dp，但【宽度必须 WRAP_CONTENT】——否则「已连接」会被挤成两行
        statusPillBtn.setMinimumWidth(Ui.dp(this, Ui.TOUCH_MIN));
        android.widget.FrameLayout.LayoutParams sbP = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, Ui.TOUCH_MIN),
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        sbP.rightMargin = Ui.dp(this, Ui.GAP_SM + Ui.TOUCH_MIN);
        header.addView(statusPillBtn, sbP);

        // §4.1 问候上移至顶栏左侧：原本「Hi，同学」独占正文首行，而 360dp 屏下标题行可用宽仅 170dp、
        // 右侧周导航固定 157dp —— 问候无法与「本周课表」同行（实测仅剩 0.7dp 余量），且字号阶梯
        // 不允许把 22sp 标题再降档。顶栏左侧有 ~216dp 空余，问候移入后正文首行即页面标题，
        // 既消除「问候 + 标题」的重复，又省下一行高度。maxWidth + 省略号兜底，绝不挤压居中标题。
        greetingView = Ui.textLh(this, "Hi，同学", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        greetingView.setMaxWidth(Ui.dp(this, 110));
        greetingView.setSingleLine(true);
        greetingView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.FrameLayout.LayoutParams gp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        gp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        header.addView(greetingView, gp);
        // §D1 顶栏 52 → 44dp（删掉居中标题后无需容纳 17sp 标题行；固定高避免整页抖动）
        root.addView(header, 0, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        setContentView(Ui.wrapWithBottomBar(this, root, 0));
        bindStickyDateStrip(root);
        renderToday();
    }

    /**
     * 周课表独立页（EXTRA_VIEW=week）。
     *
     * <p>复用 buildHeader：那里建出 pageTitleView / weekLabelView / 周导航箭头，
     * 而 renderWeek() 会无条件改写 pageTitleView（没有 null 检查），所以这里必须先把它们建好。
     * 顶部额外加一行「‹ 首页」返回——它是页面级导航，和右侧周切换不是同一层。
     */
    private void buildWeekOnlyUi() {
        LinearLayout root = Ui.screen(this);
        root.setPadding(Ui.dp(this, Ui.GAP_LG), Ui.dp(this, Ui.GAP_SM),
                Ui.dp(this, Ui.GAP_LG), Ui.dp(this, Ui.GAP_SM));

        LinearLayout backRow = new LinearLayout(this);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView back = Ui.textMediumLh(this, "‹ 首页", Ui.SP_BODY, Ui.ACCENT, Ui.LH_BODY);
        back.setPadding(Ui.dp(this, 0), Ui.dp(this, Ui.GAP_SM),
                Ui.dp(this, Ui.GAP_LG), Ui.dp(this, Ui.GAP_SM));
        back.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        back.setClickable(true);
        back.setContentDescription("返回首页");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        backRow.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(backRow);

        buildHeader(root);

        weekBox = new LinearLayout(this);
        weekBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(weekBox);
        root.addView(Ui.space(this, Ui.GAP_LG));

        setContentView(Ui.wrapWithBottomBar(this, root, 0));
        bindStickyDateStrip(root);
        renderWeek();
    }

    /**
     * 快捷操作 2×2：呼叫手环 / 上课了 / 留言 / 下课了。
     *
     * 类注释里早就承诺过这一块，但 quickBox 字段声明后 buildUi 从未渲染 → 首页根本点不到。
     * 指令沿用 quickSend 已验证过的 action=call（手环侧收得到并弹通知），仅换文案。
     */
    // ================= 首页「今天」视图 =================
    // 为什么改（evtask-D-ev-schedule--261108-e8q4tv）：手环首页第一屏就是「今天几节课、
    // 现在哪节」，同步器却在首屏平铺整周网格 —— 同一件事在两台设备上是两种看法。
    // 改后首页=今天，完整周课表走 EXTRA_VIEW=week 的独立页，两边心智一致。

    /** 日期头：主行「11月8日 周日」，副行「今天 3 节课」。 */
    private void buildDateHead(LinearLayout root) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dateRow.addView(circleBtn("\u2039", "上一天", new View.OnClickListener() {
            @Override public void onClick(View v) { shiftDay(-1); }
        }));
        LinearLayout dateCol = new LinearLayout(this);
        dateCol.setOrientation(LinearLayout.VERTICAL);
        // 点日期 = 手环首页那颗「今」圆钮：不管翻到哪一天，一下就回今天
        dateCol.setPadding(Ui.dp(this, 11), 0, Ui.dp(this, 6), 0);
        dateCol.setClickable(true);
        dateCol.setContentDescription("回到今天");
        dateCol.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goToday(v); }
        });
        dateTitleView = Ui.textMediumLh(this, "", Ui.SP_DISPLAY, Ui.TEXT, Ui.LH_DISPLAY);
        dateCol.addView(dateTitleView);
        dateSubView = Ui.textLh(this, "", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        dateSubView.setSingleLine(true);
        dateSubView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dateSubView.setPadding(0, Ui.dp(this, 3), 0, 0);
        dateCol.addView(dateSubView);
        dateRow.addView(dateCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dateRow.addView(circleBtn("\u203a", "下一天", new View.OnClickListener() {
            @Override public void onClick(View v) { shiftDay(1); }
        }));

        // 总课表：40dp 圆钮 + lucide calendar，位置和职能都对齐手环首页那颗「总」
        android.widget.FrameLayout weekBtn = new android.widget.FrameLayout(this);
        weekBtn.setBackground(Ui.round(Ui.CARD, 999, Ui.LINE, this));
        weekBtn.setClickable(true);
        weekBtn.setContentDescription("查看完整周课表");
        ImageView calBtn = new ImageView(this);
        calBtn.setImageResource(R.drawable.ic_calendar);
        calBtn.setColorFilter(Ui.ACCENT);
        weekBtn.addView(calBtn, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 19), Ui.dp(this, 19), android.view.Gravity.CENTER));
        weekBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openWeek(v); }
        });
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40));
        wlp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        dateRow.addView(weekBtn, wlp);
        head.addView(dateRow);
        root.addView(head);
    }

    /** 圆形导航钮：40dp 白圆 + 主题色字形（手环首页同款），点击给一次轻震 */
    private View circleBtn(String glyph, String desc, final View.OnClickListener l) {
        TextView t = Ui.text(this, glyph, 20f, Ui.ACCENT, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackground(Ui.round(Ui.CARD, 999, Ui.LINE, this));
        t.setClickable(true);
        t.setContentDescription(desc);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                l.onClick(v);
            }
        });
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        return t;
    }

    /** 回到今天：点日期、下拉都走这里；已经是今天就不做事（避免无谓重绘） */
    private void goToday(View src) {
        if (src != null) {
            src.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (dayOffset == 0) {
            return;
        }
        dayOffset = 0;
        renderToday();
    }

    private void openWeek(View src) {
        if (src != null) {
            src.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }
        startActivity(new Intent(HomeActivity.this, HomeActivity.class)
                .putExtra(EXTRA_VIEW, VIEW_WEEK));
    }

    /**
     * 首页手势（对齐手环首页里能在手机上成立的方向）：
     * 下拉（起手必须在日期头区域）→ 回今天；左滑 → 周课表。
     *
     * <p>为什么限定起手区：列表要纵向滚动，全天候的下拉会把「往上翻找第一节课」
     * 判成「回今天」。手环端也踩过同一个坑，最后同样是顶栏区起手才算数。
     * 右滑留给系统返回、上滑留给滚动，不抢。
     */
    private void attachGestures(final View root) {
        // 必须挂在 ScrollView 上，不能挂内容根：根只拿到 ACTION_DOWN（返回 false 就失去触摸目标），
        // MOVE/UP 全被外层 ScrollView 接走 → 手势永远判不出来。
        root.post(new Runnable() {
            @Override public void run() {
                View target = root;
                android.view.ViewParent p = root.getParent();
                while (p != null) {
                    if (p instanceof android.widget.ScrollView) {
                        target = (View) p;
                        break;
                    }
                    p = (p instanceof View) ? ((View) p).getParent() : null;
                }
                installGestureListener(target);
            }
        });
    }

    private void installGestureListener(final View root) {
        final int minDx = Ui.dp(this, 64);
        final int minDy = Ui.dp(this, 56);
        final int headLimit = Ui.dp(this, 200);
        root.setOnTouchListener(new View.OnTouchListener() {
            private float x0, y0;
            private boolean fired;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        x0 = e.getX();
                        y0 = e.getY();
                        fired = false;
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (fired) {
                            break;
                        }
                        float dx = e.getX() - x0;
                        float dy = e.getY() - y0;
                        if (Math.abs(dy) > 2 * Math.abs(dx) && dy > minDy && y0 <= headLimit) {
                            fired = true;
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            goToday(null);
                        } else if (Math.abs(dx) > 2 * Math.abs(dy) && dx < -minDx) {
                            fired = true;
                            v.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            openWeek(null);
                        }
                        break;
                }
                return false;
            }
        });
    }

    /** 周课表入口：首页不放网格了，入口必须显眼，否则用户以为周课表被删了。 */
    private View buildWeekEntry() {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        btn.setPadding(Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 12),
                Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 12));
        btn.setClickable(true);
        btn.setContentDescription("查看完整周课表");
        btn.addView(Ui.textMediumLh(this, "查看完整周课表", Ui.SP_BODY, Ui.ACCENT, Ui.LH_BODY));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, HomeActivity.class)
                        .putExtra(EXTRA_VIEW, VIEW_WEEK));
            }
        });
        return btn;
    }

    /** 首页首次渲染 + 每次数据变化后的刷新入口（周模式仍走 renderWeek）。 */
    private void renderToday() {
        if (legacy) {
            return;
        }
        if (dayOffset == 0) {
            if (focusCardView != null) {
                focusCardView.setVisibility(View.VISIBLE);
            }
            renderFocusCard();
        } else if (focusCardView != null) {
            focusCardView.setVisibility(View.GONE);
        }
        renderTodayHead();
        renderTodayList();
        applyQuickBoxVisibility();
    }

    /**
     * 数据变化后的统一刷新入口。
     *
     * <p>不能直接把各处的 renderWeek() 全换成 renderToday()：周模式那些调用来自
     * 周导航箭头和短名开关，必须重绘网格；而导入课表 / onResume 回来这两种
     * 在两种模式下都要刷新，只是刷的东西不同。
     */
    private void renderContent() {
        if (weekOnly) {
            renderWeek();
        } else {
            renderToday();
        }
    }

    /** 左右切天：-1 上一天 / +1 下一天，越界夹到 ±14 天 */
    private void shiftDay(int delta) {
        int v = dayOffset + delta;
        if (v < -14) {
            v = -14;
        }
        if (v > 14) {
            v = 14;
        }
        if (v == dayOffset) {
            return;
        }
        dayOffset = v;
        renderToday();
    }

    private void renderTodayHead() {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_MONTH, dayOffset);
            String[] wk = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            // 分段式：周几做主字（大字），日期退到副行（小字）——一天里最常被问的是「周几」
            String main = wk[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1];
            String md = (cal.get(java.util.Calendar.MONTH) + 1) + "月"
                    + cal.get(java.util.Calendar.DAY_OF_MONTH) + "日";
            String sub = "";
            boolean hideSub = false;
            int n = todayCourses().size();
            if (dayOffset == 0) {
                if (Holiday.resolveToday(this) == Holiday.HOLIDAY) {
                    sub = "假期 · 今天没有课";
                } else {
                    sub = n == 0 ? "" : "今天 " + n + " 节课";
                    hideSub = n == 0;
                }
            } else {
                String tag;
                if (dayOffset == 1) {
                    tag = "明天";
                } else if (dayOffset == -1) {
                    tag = "昨天";
                } else if (dayOffset > 0) {
                    tag = dayOffset + " 天后";
                } else {
                    tag = (-dayOffset) + " 天前";
                }
                sub = n == 0 ? tag + " · 没有课" : tag + " · " + n + " 节课";
            }
            if (dateTitleView != null) {
                dateTitleView.setText(main);
            }
            // 副行 = 「日期 · 状态」；状态为空（无课）时只留日期，副行不消失
            if (sub.length() == 0) {
                sub = md;
                hideSub = false;
            } else {
                sub = md + " · " + sub;
            }
            if (dateSubView != null) {
                dateSubView.setText(sub);
                dateSubView.setVisibility(hideSub ? View.GONE : View.VISIBLE);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 当天课程（按开始时间升序）。
     *
     * <p>调休重定向必须与 renderFocusCard / 周网格取课口径一致：调休日（如周日补周三课）
     * 按「今天真实星期几」取会取到空，于是日期头说「今天没有课」而上方焦点卡说有课，
     * 同屏自相矛盾。resolveToday() 返回 0..6 时以它为准。
     */
    private java.util.List<CourseCache.Course> todayCourses() {
        java.util.List<CourseCache.Course> out = new java.util.ArrayList<CourseCache.Course>();
        try {
            ScheduleStore.Schedule s = ScheduleStore.active(this);
            if (s == null) {
                return out;
            }
            int dayIdx;
            java.util.Calendar dc = java.util.Calendar.getInstance();
            dc.add(java.util.Calendar.DAY_OF_MONTH, dayOffset);
            if (dayOffset == 0) {
                dayIdx = CourseCache.todayIndex();
                int overrideToday = Holiday.resolveToday(this);
                if (overrideToday >= 0) {
                    dayIdx = overrideToday;
                }
            } else {
                // Calendar 里周日=1、周一=2，而课表用 0=周一..6=周日，必须折算
                dayIdx = (dc.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
            }
            // 取课必须与周视图/桌面插件同口径（都是 CourseCache.load 的那两层折算），
            // 否则首页会漏掉换课和第 9 节晚课。区别只在「周次」：看今天按今天算，
            // 左右切天按目标那天算（切到下周就该贴下周的晚课）。
            java.util.List<CourseCache.Course> pool =
                    new java.util.ArrayList<CourseCache.Course>(s.courses);
            if (dayOffset == 0) {
                try {
                    pool = ShiftStore.apply(this, pool);
                } catch (Throwable ignored) {
                }
            }
            try {
                pool = RotationStore.inject(this, pool, dc);
            } catch (Throwable ignored) {
            }
            out = CourseCache.coursesOfDay(pool, dayIdx);
            java.util.Collections.sort(out, new java.util.Comparator<CourseCache.Course>() {
                @Override public int compare(CourseCache.Course a, CourseCache.Course b) {
                    int[] ma = CourseCache.minutes(a.time);
                    int[] mb = CourseCache.minutes(b.time);
                    int sa = ma == null ? Integer.MAX_VALUE : ma[0];
                    int sb = mb == null ? Integer.MAX_VALUE : mb[0];
                    return (sa < sb) ? -1 : (sa > sb ? 1 : 0);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static final int SEC_AM = 1, SEC_PM = 2, SEC_NIGHT = 3;

    /**
     * 今日课程：按「早自习 / 上午 / 下午 / 晚课」分四段，每段一张整卡。
     *
     * <p>原来是一节一张描边小卡，10 张卡互相打断，读起来像 10 条通知而不是一张时间表；
     * 现在段内只留 1px 细线，段间靠留白 + 小标签分段，扫视时先看到「上午 4 节」，再落到具体课。
     */
    private void renderTodayList() {
        if (todayBox == null) {
            return;
        }
        todayBox.removeAllViews();
        java.util.List<CourseCache.Course> list = todayCourses();
        if (list.isEmpty()) {
            // 空课日：焦点卡已经写明「今天没有课 / 好好休息一下」，这里不再叠一张同义卡
            return;
        }
        int now = (dayOffset == 0) ? CourseCache.nowMinutes() : -1;
        int i = 0;
        while (i < list.size()) {
            int sec = sectionOf(list.get(i).time);
            int j = i;
            while (j < list.size() && sectionOf(list.get(j).time) == sec) {
                j++;
            }
            if (i > 0) {
                todayBox.addView(Ui.space(this, 7));
            }
            todayBox.addView(sectionLabel(sec, j - i));
            boolean night = sec == SEC_NIGHT;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            // 晚课整段淡紫：轮转注入的科目名每周都换，跟课名上色会周周变色，认不出「晚上那节」
            card.setBackground(Ui.round(night ? NIGHT_BG : Ui.CARD, Ui.R_CARD,
                    night ? NIGHT_LINE : Ui.LINE, this));
            card.setPadding(Ui.dp(this, 4), Ui.dp(this, 1), Ui.dp(this, 4), Ui.dp(this, 1));
            for (int k = i; k < j; k++) {
                if (k > i) {
                    card.addView(hairline(night));
                }
                card.addView(todayRow(list.get(k), now));
            }
            todayBox.addView(card);
            i = j;
        }
    }

    /** 分段判定：17:00 后算晚课；早自习（档位 0）单独一段，其余按 12:00 分上午/下午 */
    private int sectionOf(String time) {
        int[] m = CourseCache.minutes(time);
        if (m == null) {
            return SEC_AM;
        }
        if (m[0] >= 17 * 60) {
            return SEC_NIGHT;
        }
        if (m[0] >= 12 * 60) {
            return SEC_PM;
        }
        return SEC_AM;
    }

    /** 段标题带节数：「上午 · 5 节」比只写「上午」多一次信息，且不占额外高度 */
    private View sectionLabel(int sec, int count) {
        String t = sec == SEC_AM ? "上午" : sec == SEC_PM ? "下午" : "晚课";
        TextView v = Ui.textMediumLh(this, t + " · " + count + " 节", Ui.SP_MICRO,
                sec == SEC_NIGHT ? NIGHT_FG : Ui.MUTED, Ui.LH_MICRO);
        v.setPadding(Ui.dp(this, 7), 0, 0, Ui.dp(this, 3));
        return v;
    }

    /** 段内行间细线：左右缩进，避免和卡片圆角打架 */
    private View hairline(boolean night) {
        View line = new View(this);
        line.setBackgroundColor(night ? NIGHT_LINE : Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        lp.leftMargin = Ui.dp(this, 7);
        lp.rightMargin = Ui.dp(this, 7);
        line.setLayoutParams(lp);
        return line;
    }

    /**
     * 单节课行：一行一条 —— 序号 | 开始时间 | 课名（同色小字追加教室/老师） | 状态徽标。
     * <p>行本身不再带描边（段卡负责边界），只在「进行中」时铺一层淡色圆角，
     * 这样一屏 10 节读下去是连续的时间表，而不是 10 个各自为政的方块。
     */
    private View todayRow(CourseCache.Course c, int now) {
        int[] m = CourseCache.minutes(c.time);
        boolean doing = m != null && now >= m[0] && now < m[1];
        boolean done = m != null && now >= m[1];
        boolean night = isNight(c.time);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (doing) {
            row.setBackground(Ui.round(night ? 0xFFE7E1FF : Ui.ACCENT_LIGHT, 10, 0, this));
        }
        row.setPadding(Ui.dp(this, 7), Ui.dp(this, 4), Ui.dp(this, 7), Ui.dp(this, 4));

        // 节次序号窄列：早自习「早」/ 第N节「N」/ 最后一节「晚」，匹配不上作息档就不占位
        String seq = periodSeq(c.time);
        if (seq.length() > 0) {
            TextView seqView = Ui.textLh(this, seq, Ui.SP_MICRO,
                    night ? NIGHT_FG : Ui.MUTED, !night && doing, Ui.LH_MICRO);
            seqView.setGravity(android.view.Gravity.CENTER);
            seqView.setSingleLine(true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 16), LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.rightMargin = Ui.dp(this, 5);
            row.addView(seqView, slp);
        }

        // 起止时间同一行：「08:00-08:40」。竖排两行要多占 18dp 行高，10 节就挤出首屏，
        // 所以压到 SP_MICRO + 70dp 定宽，横排一次读完。（解析不出区间就只显示开始时间）
        TextView timeView = Ui.textLh(this, timeRange(c.time), Ui.SP_MICRO,
                night ? NIGHT_FG : (doing ? Ui.ACCENT : Ui.MUTED), false, Ui.LH_MICRO);
        timeView.setSingleLine(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 70), LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = Ui.dp(this, Ui.GAP_SM);
        row.addView(timeView, tlp);

        TextView name = Ui.textMediumLh(this, "", Ui.SP_BODY,
                night ? NIGHT_FG : ((done && !doing) ? Ui.MUTED : Ui.TEXT), Ui.LH_BODY);
        name.setText(titleWithSub(c));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (doing || done) {
            TextView badge = Ui.textLh(this, doing ? "进行中" : "已上", Ui.SP_MICRO,
                    doing ? Ui.ACCENT : Ui.MUTED, false, Ui.LH_MICRO);
            badge.setBackground(Ui.round(doing ? Ui.CARD : Ui.CARD2, 6, 0, this));
            badge.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = Ui.dp(this, Ui.GAP_SM);
            row.addView(badge, blp);
        }
        return row;
    }

    /** "08:00-08:40"：结束时间直接读作息档，解析不出就退回开始时刻单项。 */
    private String timeRange(String t) {
        int[] m = CourseCache.minutes(t);
        String start = CourseCache.shortTime(t);
        if (m == null) {
            return start;
        }
        return start + "-" + hhmm(m[1]);
    }

    private String hhmm(int minutes) {
        int h = minutes / 60;
        int mi = minutes % 60;
        return (h < 10 ? "0" : "") + h + ":" + (mi < 10 ? "0" : "") + mi;
    }

    /** 晚自习底色 / 描边 / 字色：淡紫，和白天的中性卡片拉开但不抢眼 */
    private static final int NIGHT_BG = 0xFFF3F0FF;
    private static final int NIGHT_LINE = 0xFFDED6FE;
    private static final int NIGHT_FG = 0xFF6C5BD4;

    /** 晚辅/晚自习判定：17:00 之后开始的课 */
    private boolean isNight(String time) {
        int[] m = CourseCache.minutes(time);
        return m != null && m[0] >= 17 * 60;
    }

    /** 节次序号：早自习「早」、第 N 节「N」、最后一节（第9节）「晚」 */
    private String periodSeq(String time) {
        int idx = CourseEditUtil.matchTemplate(time);
        if (idx < 0) {
            return "";
        }
        if (idx == 0) {
            return "早";
        }
        if (idx >= CourseEditUtil.TEMPLATES.length - 1) {
            return "晚";
        }
        return String.valueOf(idx);
    }

    /**
     * 课名 + 小字教室/老师：拼进同一个 TextView，单行省略号收尾。
     * location / teacher 都空时 sub() 返回空串，这里原样只显示课名，不留分隔符。
     */
    private CharSequence titleWithSub(CourseCache.Course c) {
        String title = c.name == null ? "" : c.name;
        String sub = c.sub();
        if (sub.length() == 0) {
            return title;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title);
        int start = sb.length();
        sb.append("  ").append(sub);
        sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED),
                start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.82f),
                start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private LinearLayout buildQuickBox() {
        LinearLayout box = Ui.card(this);
        box.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        box.setPadding(Ui.dp(this, Ui.GAP_SM), Ui.dp(this, Ui.GAP_SM),
                Ui.dp(this, Ui.GAP_SM), Ui.dp(this, Ui.GAP_SM));

        // §D4 快捷 2×2 → 4 联横条（竖省 ≈70dp）；标签 4 字收 2 字（呼叫/上课/留言/下课）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(quickCell(R.drawable.ic_smartphone, "呼叫", new View.OnClickListener() {
            @Override public void onClick(View v) { callBand(); }
        }), cellLp());
        row.addView(quickCell(R.drawable.ic_bell_ring, "上课", new View.OnClickListener() {
            @Override public void onClick(View v) {
                classNotify("上课了", "class_start");
            }
        }), cellLp());
        row.addView(quickCell(R.drawable.ic_tab_message, "留言", new View.OnClickListener() {
            @Override public void onClick(View v) { quickMessage(); }
        }), cellLp());
        row.addView(quickCell(R.drawable.ic_bell, "下课", new View.OnClickListener() {
            @Override public void onClick(View v) {
                classNotify("下课了", "class_end");
            }
        }), cellLp());
        box.addView(row);

        return box;
    }

    /** 快捷操作单格：等宽 weight=1，左右各 4dp 形成 8dp 列间距 */
    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, Ui.GAP_XS), 0, Ui.dp(this, Ui.GAP_XS), 0);
        return lp;
    }

    /** 快捷操作单格内容：图标 24dp + 11.5sp 文字（整格背景即可点，热区远大于 44dp） */
    private View quickCell(int icon, String label, View.OnClickListener l) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setClickable(true);
        // §4.4 卡片底 CARD2 → CARD（白）：浅色下 CARD2(#EAF0F8) 与主卡 CARD(#FFF) 对比过弱，
        // 抬到白底才有「卡片」感，也才衬得出下面那块主色淡底图标。
        cell.setBackground(Ui.round(Ui.CARD, Ui.R_CTRL, Ui.LINE, this));
        int pad = Ui.dp(this, Ui.GAP_SM);
        cell.setPadding(pad, pad, pad, pad);

        // §4.4 图标底色改用主色淡底块（ACCENT_LIGHT）→ 四个快捷入口有「可点」暗示；
        // 24dp 图标 + 8dp 内距 = 40dp 淡底方块。
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setColorFilter(Ui.ACCENT);
        ic.setBackground(Ui.round(Ui.ACCENT_LIGHT, Ui.R_CTRL, 0, this));
        ic.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        cell.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        TextView t = Ui.textLh(this, label, Ui.SP_CAPTION, Ui.TEXT, false, Ui.LH_CAPTION);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, Ui.GAP_XS), 0, 0);
        cell.addView(t);

        cell.setOnClickListener(l);
        return cell;
    }

    private void buildHeader(LinearLayout root) {
        // 旧顶行（字母 E 头像 + 右上角闹钟入口）已按需求整行删除；
        // 右上角功能入口由顶栏的 ⊕ 圆钮承担（onCreate 里，微信式下拉菜单）
        // §4.1 问候已上移顶栏左侧（见 buildUi 的顶栏段），本行首行即页面标题
        root.addView(Ui.space(this, Ui.GAP_XS));

        // titleRow: page title + week switcher
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout greetingBlock = new LinearLayout(this);
        greetingBlock.setOrientation(LinearLayout.HORIZONTAL);
        greetingBlock.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 26sp → 22sp：26 与下方 64dp 日期条同屏时严重抢焦点，降档后主视觉回归课表网格
        pageTitleView = Ui.textMediumLh(this, "本周课表", Ui.SP_DISPLAY, Ui.TEXT, Ui.LH_DISPLAY);
        // 标题位复用为「状态/操作」双态切换（显隐在 renderWeek 里控制）：
        // 当前周 = 「📅 本周课表」普通标题（图标仅装饰、中性色、不可点）；
        // 非当前周 = 「回到本周 ↩」可点操作（主题色 + 按压水波纹）。
        // 两种状态都是「图标 + 4 字」，宽度稳定，右侧周导航不会左右晃。
        pageTitleView.setCompoundDrawablePadding(Ui.dp(this, 8));
        pageTitleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (weekOffset != 0) {
                    weekOffset = 0;
                    renderWeek();
                } else {
                    // 当前周：点标题（📅+课表名）去课表管理页
                    startActivity(new Intent(HomeActivity.this, ScheduleListActivity.class));
                }
            }
        });
        // ⚠️ 必须 wrap_content：LinearLayout 纵向默认把子 View 拉成满宽，
        // 「回到本周」末尾的 ↩ compound drawable 会被推到整行最右端（贴着 ‹ 按钮），
        // 看起来离文字隔了一个字。wrap 后图标才紧贴文字。
        greetingBlock.addView(pageTitleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        titleRow.addView(greetingBlock,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // week switcher
        LinearLayout weekNav = new LinearLayout(this);
        weekNav.setOrientation(LinearLayout.HORIZONTAL);
        weekNav.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 周导航：矢量箭头（字符 ‹ 字形小且基线不齐，18dp 图标在 32dp 圆钮里视觉饱满）
        ImageView prevBtn = new ImageView(this);
        prevBtn.setImageResource(R.drawable.ic_chevron_left);
        prevBtn.setColorFilter(Ui.MUTED);
        // 热区 32 → 44dp：视觉图标 20dp + padding 12；圆角 15 → 12（圆角三档化）
        prevBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        prevBtn.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        prevBtn.setContentDescription("上一周");
        int btnSize = Ui.dp(this, Ui.TOUCH_MIN);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset--; renderWeek(); }
        });
        weekNav.addView(prevBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        weekLabelView = Ui.textLh(this, "9.28-10.04", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        weekLabelView.setPadding(Ui.dp(this, Ui.GAP_SM), Ui.dp(this, Ui.GAP_SM),
                Ui.dp(this, Ui.GAP_SM), Ui.dp(this, Ui.GAP_SM));
        weekLabelView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset = 0; renderWeek(); }
        });
        weekNav.addView(weekLabelView);

        ImageView nextBtn = new ImageView(this);
        nextBtn.setImageResource(R.drawable.ic_chevron_right);
        nextBtn.setColorFilter(Ui.MUTED);
        nextBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        nextBtn.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        nextBtn.setContentDescription("下一周");
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset++; renderWeek(); }
        });
        weekNav.addView(nextBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        titleRow.addView(weekNav);
        root.addView(titleRow);
        root.addView(Ui.space(this, Ui.GAP_LG));
    }

    /** ⊕ 下拉菜单：白底圆角单框 + 纯文字行 + 细分隔线（简洁清晰，右缘对齐 ⊕，点外面收起）。 */
    private void showPlusMenu(View anchor) {
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

        addMenuItem(panel, pw, "导出课程", TransferActivity.MODE_EXPORT);
        panel.addView(menuDivider());
        addMenuItem(panel, pw, "导入课程", TransferActivity.MODE_IMPORT);
        panel.addView(menuDivider());
        addMenuItem(panel, pw, "呼叫手环", null);

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

    /** 下拉菜单里的一行：只有文字，点击即执行。 */
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
                // 标准版门禁：导入课程属于高级版功能（导出不限制）
                if (TransferActivity.MODE_IMPORT.equals(transferMode)
                        && AuthState.isStandardLocked(HomeActivity.this)) {
                    AuthState.showUpgradeDialog(HomeActivity.this);
                    return;
                }
                if (transferMode != null) {
                    Intent it = new Intent(HomeActivity.this, TransferActivity.class);
                    it.putExtra(TransferActivity.EXTRA_MODE, transferMode);
                    startActivity(it);
                } else {
                    callBand();
                }
            }
        });
    }

    /** 呼叫手环：双通道（① 系统通知卡 + ② EV {@code action=call}），一路断开另一路兜底——
     *  手环响铃、震动、亮屏并弹提示，结果反馈在首页 mini 状态条上。
     *  蓝牙未连接时两路都发不出去 → 弹窗明确提醒，并提供一键去连接。 */
    private void callBand() {
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            // 共用美化弹窗：图标章 + 提示 + 去连接
            Dialogs.confirm(this, R.drawable.ic_unlink, 0,
                    "手环未连接",
                    "手环还没连上，叫不醒它呀。\n先去「手环」页连一下吧？",
                    null, "去连接", false,
                    new Dialogs.Action() {
                        @Override public void run() {
                            startActivity(new Intent(HomeActivity.this, BandActivity.class));
                        }
                    });
            return;
        }
        miniStatus("正在呼叫手环…", Ui.ACCENT);
        e.ringBand(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String info) {
                if (ok) {
                    miniStatus("已呼叫手环 ✓（" + info + "）", Ui.OK);
                } else {
                    miniStatus("呼叫失败，再试一次吧：" + info, Ui.WARN);
                }
            }
        });
    }

    /** P2：绑定页面级 ScrollView，滚动时把日期表头钉在网格卡顶部。 */
    private void bindStickyDateStrip(LinearLayout contentRoot) {
        if (!(contentRoot.getParent() instanceof android.widget.ScrollView)) {
            return;
        }
        pageScroller = (android.widget.ScrollView) contentRoot.getParent();
        pageScroller.setOnScrollChangeListener(new android.view.View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                applyStickyHeader(sy);
            }
        });
    }

    /**
     * P2：表头位置在布局完成后测量一次。renderWeek() 会重建整张网格卡，
     * 因此每次新建 dateStripView 都要重新测量并套用当前滚动量。
     */
    private void prepareStickyHeader() {
        stickyHeaderTop = -1;
        stickyHeaderMax = 0;
        stickyHeaderFloating = false;
        if (dateStripView != null) {
            dateStripView.setElevation(0f);
        }
        if (pageScroller == null || dateStripView == null) {
            return;
        }
        final LinearLayout strip = dateStripView;
        strip.post(new Runnable() {
            @Override public void run() {
                if (dateStripView != strip || pageScroller == null) {
                    return;
                }
                // 累加 layout 坐标（不含 translationY，测量稳定）到 ScrollView 的内容根
                int top = 0;
                View v = strip;
                while (v.getParent() instanceof View && v.getParent() != pageScroller) {
                    top += v.getTop();
                    v = (View) v.getParent();
                }
                if (v.getParent() == pageScroller) {
                    top += v.getTop();
                }
                stickyHeaderTop = top;
                android.view.ViewGroup card = (android.view.ViewGroup) strip.getParent();
                stickyHeaderMax = Math.max(0, card.getHeight() - card.getPaddingBottom()
                        - strip.getTop() - strip.getHeight());
                applyStickyHeader(pageScroller.getScrollY());
            }
        });
    }

    /** P2：scrollY 超过表头位置后按滚动量下移表头，视觉上保持在滚动视口顶部。 */
    private void applyStickyHeader(int scrollY) {
        if (dateStripView == null || stickyHeaderTop < 0) {
            return;
        }
        int ty = scrollY - stickyHeaderTop;
        if (ty < 0) ty = 0;
        if (ty > stickyHeaderMax) ty = stickyHeaderMax;
        // 只有真正浮在课程行之上时才提升 Z 序；静态时 elevation=0，画面与修复前一致
        boolean floating = ty > 0;
        if (floating != stickyHeaderFloating) {
            dateStripView.setElevation(floating ? Ui.dp(this, 2) : 0f);
            stickyHeaderFloating = floating;
        }
        dateStripView.setTranslationY(ty);
    }

    /** P2：第几节按「开始时间早于本节的去重时间槽」计数，同槽多课只算一节。 */
    private int periodIndex(java.util.Set<Integer> slotStarts, int[] m) {
        if (m == null) return 1;
        int n = 0;
        for (int s : slotStarts) {
            if (s < m[0]) n++;
        }
        return n + 1;
    }

    private void renderDateStrip() {
        if (dateStripView == null) {
            return;
        }
        dateStripView.removeAllViews();
        // §D3 日期条下沉为网格卡表头：左侧留出与网格时间列同宽的空档（36dp），
        // 7 格与下方天列严格同列；同一张卡、同一内边距 → 对齐问题从根上消失。
        TextView sp = new TextView(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, TIME_COL_W),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        dateStripView.addView(sp);

        int today = CourseCache.todayIndex();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_MONTH, -today + weekOffset * 7);
        String[] weekLabels = {"一", "二", "三", "四", "五", "六", "日"};

        for (int d = 0; d < 7; d++) {
            boolean sel = (d == selectedDay);
            int cellH = Ui.dp(this, 54);

            // 假期 / 调休角标：「休」= 放假，「班」= 调休补课
            java.util.Calendar dayCal = (java.util.Calendar) cal.clone();
            String badge = Holiday.badge(this, dayCal);

            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.setClipChildren(false);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER);
            // §3.1 选中格改 ACCENT 实底矩形；非选中格透明（不铺白、不去阴影，直接透出卡底）
            if (sel) {
                card.setBackground(Ui.round(Ui.ACCENT, Ui.R_CTRL, 0, this));
            } else {
                card.setBackground(Ui.round(0x00000000, Ui.R_CTRL, 0, this));
            }
            android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
            clp.leftMargin = Ui.dp(this, 2);
            clp.rightMargin = Ui.dp(this, 2);
            card.setLayoutParams(clp);

            TextView wv = Ui.textMediumLh(this, weekLabels[d], Ui.SP_CAPTION,
                    sel ? Ui.ON_ACCENT : Ui.MUTED, Ui.LH_MICRO);
            wv.setGravity(android.view.Gravity.CENTER);
            TextView dd = Ui.textMediumLh(this, String.valueOf(cal.get(java.util.Calendar.DAY_OF_MONTH)),
                    Ui.SP_NUM, sel ? Ui.ON_ACCENT : Ui.TEXT, Ui.LH_SUBTITLE);
            dd.setGravity(android.view.Gravity.CENTER);
            dd.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(wv);
            card.addView(dd);
            cell.addView(card);

            // 角标：右上角小方块（休=绿 / 班=琥珀），靠「位置」与星期字分离，避免拼读成一个词
            if (badge.length() > 0) {
                boolean rest = "休".equals(badge);
                TextView bd = Ui.textMedium(this, badge, Ui.SP_MICRO, rest ? Ui.OK : Ui.WARN);
                bd.setGravity(android.view.Gravity.CENTER);
                bd.setIncludeFontPadding(false);
                bd.setBackground(Ui.round(rest ? Ui.OK_LIGHT : Ui.WARN_LIGHT, Ui.R_BLOCK, 0, this));
                android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                        Ui.dp(this, 14), Ui.dp(this, 14));
                blp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                blp.topMargin = Ui.dp(this, 3);
                blp.rightMargin = Ui.dp(this, 3);
                bd.setLayoutParams(blp);
                // ⚠️ 必须比选中实底（无 elevation）更高：同一 FrameLayout 里带 elevation 的子 View
                // 会盖在没有 elevation 的兄弟之上（Z 序优先于添加顺序），否则角标被压住看不见。
                bd.setElevation(Ui.dp(this, 3));
                cell.addView(bd);
            }

            final int dayIndex = d;
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedDay = dayIndex;
                    renderDateStrip();
                }
            });

            // 无 gap：每格按 1/7 均分，pitch 与网格天列严格一致（gap 会造成逐列累积漂移）
            dateStripView.addView(cell, new LinearLayout.LayoutParams(0, cellH, 1f));
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
    }

    /**
     * §D2 焦点卡「接下来」——本方案的核心新增。
     *
     * 首页只回答「我现在该干嘛」：把「下一节课是什么 / 还有多久 / 在哪」从网格里的
     * 「找到今天那一列、再从上往下扫」两次定位，压缩成一次阅读。
     * 三态：进行中 / 有下一节 / 今天没课（含放假，复用 {@link Holiday#greeting} 祝福语）。
     * 倒计时：进行中→「还剩 N 分钟」；≤60 分钟→「还有 N 分钟」；>60 分钟→「HH:MM 开始」。
     */
    private void renderFocusCard() {
        if (legacy || focusCardView == null) {
            return;
        }
        focusCardView.removeAllViews();

        java.util.Calendar cal = java.util.Calendar.getInstance();
        boolean holiday = Holiday.resolveToday(this) == Holiday.HOLIDAY;

        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        card.setElevation(Ui.dp(this, 4));
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14));

        // ---- 放假态：复用 Holiday 祝福语（与手环端 holiday-preset 同源）----
        if (holiday) {
            card.setBackground(Ui.round(Ui.OK_LIGHT, Ui.R_CARD, 0, this));
            card.addView(Ui.textLh(this, "今天 · 假期", Ui.SP_CAPTION, Ui.OK, false, Ui.LH_CAPTION));
            String greet = Holiday.greeting(this, cal);
            TextView main = Ui.textMediumLh(this, greet.length() > 0 ? greet : "今天休息",
                    Ui.SP_TITLE, Ui.OK, Ui.LH_TITLE);
            main.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(main);
            String name = Holiday.holidayName(this, cal);
            TextView sub = Ui.textLh(this, (name.length() > 0 ? name : "假期") + " · 今天没有课，好好休息",
                    Ui.SP_MICRO, Ui.MUTED, false, Ui.LH_CAPTION);
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(sub);
            focusCardView.addView(card);
            return;
        }

        ScheduleStore.Schedule s = ScheduleStore.active(this);

        // ⚠️ 调休重定向：必须与下方提示条（renderOverrideBar，resolveToday >= 0 判「今天调休，按X课表」）
        // 及课表网格取课口径一致。否则调休日（如周日补周三课）按「今天真实星期几」取课为空，
        // 焦点卡会误报「今天没有课」，与同屏「今天调休」提示自相矛盾。
        // resolveToday 返回 0..6 = 按该下标星期几的课表（0=周一）；NORMAL / HOLIDAY 为负值，走原逻辑。
        int dayIdx = CourseCache.todayIndex();
        int overrideToday = Holiday.resolveToday(this);
        if (overrideToday >= 0) {
            dayIdx = overrideToday;
        }
        java.util.List<CourseCache.Course> today = (s == null)
                ? new java.util.ArrayList<CourseCache.Course>()
                : CourseCache.coursesOfDay(s.courses, dayIdx);

        // 按开始时间排序（第 N 节 = 排序后序号）
        java.util.Collections.sort(today, new java.util.Comparator<CourseCache.Course>() {
            @Override public int compare(CourseCache.Course a, CourseCache.Course b) {
                int[] ma = CourseCache.minutes(a.time);
                int[] mb = CourseCache.minutes(b.time);
                int sa = ma == null ? Integer.MAX_VALUE : ma[0];
                int sb = mb == null ? Integer.MAX_VALUE : mb[0];
                return (sa < sb) ? -1 : (sa > sb ? 1 : 0);
            }
        });
        int total = today.size();
        int now = CourseCache.nowMinutes();

        // 2026-11-05 P2：节次按「去重后的开始时间」计数。同一节若有多门课
        // （合班 / 单双周 / 手环重复导入），原来的列表索引会把一节算成两节，
        // 使「第 N 节」与今日进度条同时偏大；时间定位本身仍按 nowMinutes()。
        java.util.Set<Integer> slotStarts = new java.util.HashSet<Integer>();
        java.util.Set<Integer> doneSlots = new java.util.HashSet<Integer>();
        CourseCache.Course current = null, next = null;
        for (int i = 0; i < total; i++) {
            int[] m = CourseCache.minutes(today.get(i).time);
            if (m == null) continue;
            slotStarts.add(m[0]);
            if (now >= m[1]) doneSlots.add(m[0]);
            if (current == null && now >= m[0] && now < m[1]) { current = today.get(i); }
        }
        if (current == null) {
            int best = Integer.MAX_VALUE;
            for (int i = 0; i < total; i++) {
                int[] m = CourseCache.minutes(today.get(i).time);
                if (m == null) continue;
                if (m[0] > now && m[0] < best) { best = m[0]; next = today.get(i); }
            }
        }
        final int totalSlots = Math.max(1, slotStarts.size());
        final int doneSlotCount = doneSlots.size();

        // ---- 今天一节都没有 ----
        if (total == 0) {
            card.addView(Ui.textLh(this, "今天", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION));
            TextView main = Ui.textMediumLh(this, "今天没有课", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
            main.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(main);
            TextView sub = Ui.textLh(this, "好好休息一下", Ui.SP_MICRO, Ui.MUTED, false, Ui.LH_CAPTION);
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(sub);
            focusCardView.addView(card);
            return;
        }

        CourseCache.Course show;
        boolean running;
        String kicker;
        if (current != null) {
            show = current; running = true;
            int[] cm = CourseCache.minutes(current.time);
            kicker = "进行中 · 第" + periodIndex(slotStarts, cm) + "节"
                    + (cm != null ? "　" + CourseCache.hm(cm[0]) + "–" + CourseCache.hm(cm[1]) : "");
        } else if (next != null) {
            show = next; running = false;
            int[] nm = CourseCache.minutes(next.time);
            kicker = "接下来 · 第" + periodIndex(slotStarts, nm) + "节"
                    + (nm != null ? "　" + CourseCache.hm(nm[0]) + "–" + CourseCache.hm(nm[1]) : "");
        } else {
            show = today.get(total - 1); running = false;
            kicker = "今天的课都上完了 🎉 · 最后一节";
        }
        final CourseCache.Course showFinal = show;
        int[] sm = CourseCache.minutes(show.time);
        int start = sm == null ? -1 : sm[0];
        int end = sm == null ? -1 : sm[1];

        // 进度原来是「进度条 + 一行今日 x/y 节」共两行，占掉 45dp —— 正好是首页能不能
        // 放下 10 节课的那点余量。改成并入 kicker 行，信息不丢，高度省一行。
        if (totalSlots > 1) {
            kicker = kicker + "　今日 " + doneSlotCount + "/" + totalSlots + " 节";
        }
        card.addView(Ui.textLh(this, kicker, Ui.SP_CAPTION,
                running ? Ui.ACCENT : Ui.MUTED, false, Ui.LH_CAPTION));

        // 主行：课名（SP_DISPLAY 22sp，全页唯一最大字，视觉锚点）+ 倒计时
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = Ui.textMediumLh(this, show.name, Ui.SP_DISPLAY, Ui.TEXT, Ui.LH_DISPLAY);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String cd = "";
        if (running && end >= 0) {
            cd = "还剩 " + Math.max(0, end - now) + " 分钟";
        } else if (!running && start >= 0) {
            int diff = start - now;
            if (diff > 0) {
                cd = (diff <= 60) ? ("还有 " + diff + " 分钟") : (CourseCache.hm(start) + " 开始");
            }
        }
        if (cd.length() > 0) {
            titleRow.addView(Ui.textMediumLh(this, cd, Ui.SP_BODY, Ui.ACCENT, Ui.LH_BODY));
        }
        titleRow.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(titleRow);

        // 次行：地点 · 教师
        StringBuilder meta = new StringBuilder();
        if (show.location != null && show.location.length() > 0) meta.append("📍 ").append(show.location);
        if (show.teacher != null && show.teacher.length() > 0) {
            if (meta.length() > 0) meta.append("　");
            meta.append("👤 ").append(show.teacher);
        }
        if (meta.length() > 0) {
            TextView mv = Ui.textLh(this, meta.toString(), Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
            mv.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(mv);
        }

        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCourseDetail(showFinal); }
        });
        focusCardView.addView(card);
    }

    /** 渲染当前激活课表的周视图（带时间轴） */
    private void renderWeek() {
        if (legacy || weekBox == null) {
            return;
        }
        applyQuickBoxVisibility();
        // 周标签：按 weekOffset 推算周一~周日（点标签回到本周）
        java.util.Calendar mon = java.util.Calendar.getInstance();
        mon.add(java.util.Calendar.DAY_OF_MONTH, -CourseCache.todayIndex() + weekOffset * 7);
        java.util.Calendar sun = (java.util.Calendar) mon.clone();
        sun.add(java.util.Calendar.DAY_OF_MONTH, 6);
        // 非本周整体降透明度：翻到别的周时视觉上明显不同于本周，避免误认（点击不受影响）
        if (weekBox != null) {
            weekBox.setAlpha(weekOffset == 0 ? 1f : 0.55f);
        }
        if (weekLabelView != null) {
            String label = (mon.get(java.util.Calendar.MONTH) + 1) + "."
                    + mon.get(java.util.Calendar.DAY_OF_MONTH) + "-"
                    + (sun.get(java.util.Calendar.MONTH) + 1) + "."
                    + sun.get(java.util.Calendar.DAY_OF_MONTH);
            // 周标签带假期痕迹：本屏 7 天里有假期打「休」、有调休打「班」，
            // 不用翻到具体格子就知道这周值不值得看（假期功能关闭时不加标记）
            if (Holiday.enabled(this)) {
                int hdCnt = 0;
                int wkCnt = 0;
                for (int i = 0; i < 7; i++) {
                    java.util.Calendar d = (java.util.Calendar) mon.clone();
                    d.add(java.util.Calendar.DAY_OF_MONTH, i);
                    int r = Holiday.resolveDay(this, d);
                    if (r == Holiday.HOLIDAY) {
                        hdCnt++;
                    } else if (r >= 0) {
                        wkCnt++;
                    }
                }
                if (hdCnt > 0) {
                    label += " · 休";
                }
                if (wkCnt > 0) {
                    label += " · 班";
                }
            }
            weekLabelView.setText(label);
        }
        renderFocusCard();

        // 标题位双态：当前周=普通标题（📅 中性色，不可点、无按压态）；非当前周=「回到本周 ↩」
        // （主题色 + 水波纹按压，整块可点，语义 contentDescription=回到今天所在周）。
        // 周范围标签（weekLabelView）始终显示，翻到别的周也能看到当前看的是哪一周。
        if (weekOffset == 0) {
            pageTitleView.setText("本周课表");
            pageTitleView.setTextColor(Ui.TEXT);
            // 📅 用 InsetDrawable 顶部垫 3dp：compound drawable 相对 26sp 大字号偏上，视觉居中
            android.graphics.drawable.Drawable cal = new android.graphics.drawable.InsetDrawable(
                    getDrawable(R.drawable.ic_calendar), 0, Ui.dp(this, 3), 0, 0);
            pageTitleView.setCompoundDrawablesWithIntrinsicBounds(cal, null, null, null);
            pageTitleView.setCompoundDrawablePadding(Ui.dp(this, 8));
            pageTitleView.getCompoundDrawables()[0].setColorFilter(Ui.MUTED, android.graphics.PorterDuff.Mode.SRC_IN);
            // 📅+标题 整体可点 → 课表管理页（带主色 12% 水波纹按压反馈）
            int ripple = (Ui.ACCENT & 0x00FFFFFF) | 0x1F000000;
            pageTitleView.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ripple),
                    Ui.round(0x00000000, 10, 0, this), null));
            pageTitleView.setClickable(true);
            pageTitleView.setContentDescription("去课表管理");
        } else {
            pageTitleView.setText("回到本周");
            pageTitleView.setTextColor(Ui.ACCENT);
            // ↩ 同样垫 3dp 保持视觉居中；与文字间距收到 2dp，紧贴「回到本周」（用户要求不要空格感）
            android.graphics.drawable.Drawable undo = new android.graphics.drawable.InsetDrawable(
                    getDrawable(R.drawable.ic_corner_up_left), 0, Ui.dp(this, 3), 0, 0);
            pageTitleView.setCompoundDrawablesWithIntrinsicBounds(null, null, undo, null);
            pageTitleView.setCompoundDrawablePadding(0); // 已是 wrap_content 且字形贴边，0 间距即紧挨
            pageTitleView.getCompoundDrawables()[2].setColorFilter(Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
            int rippleColor = (Ui.ACCENT & 0x00FFFFFF) | 0x33000000; // 主色 20% 透明按压反馈
            android.graphics.drawable.GradientDrawable bg = Ui.round(0x00000000, 10, 0, this);
            pageTitleView.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), bg, null));
            pageTitleView.setClickable(true);
            pageTitleView.setContentDescription("回到今天所在周");
        }

        weekBox.removeAllViews();

        // ── 调休提示（假期祝福卡已上移至焦点卡「接下来」的放假态，避免同屏两处说放假）──
        int overrideToday = Holiday.resolveToday(this);
        if (overrideToday >= 0) {
            TextView b = Ui.textLh(this, "今天调休，按" + CourseCache.WEEK[overrideToday] + "课表",
                    Ui.SP_CAPTION, Ui.WARN, false, Ui.LH_CAPTION);
            b.setPadding(Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_SM),
                    Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_SM));
            b.setBackground(Ui.round(Ui.WARN_LIGHT, Ui.GAP_SM, 0, this));
            weekBox.addView(b);
            weekBox.addView(Ui.space(this, Ui.GAP_SM));
        }

        ScheduleStore.Schedule s = ScheduleStore.active(this);
        if (s == null) {
            if (greetingView != null) {
                greetingView.setText("Hi，同学");
            }
            weekBox.addView(Ui.textLh(this, "连上手环，课表会自动飞进手机", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION));
            return;
        }
        // update greeting with nickname
        String nick = SyncEngine.get(this).nickname;
        String displayName = (nick != null && nick.length() > 0) ? nick : "同学";
        if (greetingView != null) {
            greetingView.setText("Hi，" + displayName);
        }
        // 课表名行：🔁切换图标 + 课表名（整行可点 → 课表管理页=切换课表）+ 右侧[单]/✏️控件组
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView nameLine = Ui.textMediumLh(this, s.name, Ui.SP_SUBTITLE, Ui.TEXT, Ui.LH_SUBTITLE);
        nameLine.setCompoundDrawablePadding(Ui.dp(this, Ui.GAP_SM));
        // 图标 17dp 本征尺寸（ic_repeat.xml width/height），与文字行高齐平
        android.graphics.drawable.Drawable listIc = new android.graphics.drawable.InsetDrawable(
                getDrawable(R.drawable.ic_repeat), 0, Ui.dp(this, 1), 0, 0);
        nameLine.setCompoundDrawablesWithIntrinsicBounds(listIc, null, null, null);
        nameLine.getCompoundDrawables()[0].setColorFilter(Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        nameLine.setClickable(true);
        nameLine.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((Ui.ACCENT & 0x00FFFFFF) | 0x1F000000),
                Ui.round(0x00000000, 8, 0, this), null));
        nameLine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, ScheduleListActivity.class));
            }
        });
        nameRow.addView(nameLine, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final boolean shortOn = CourseCache.shortNameMode(this);
        TextView singleBtn = Ui.textMediumLh(this, "单", Ui.SP_BODY,
                shortOn ? Ui.ON_ACCENT : Ui.MUTED, Ui.LH_BODY);
        singleBtn.setGravity(android.view.Gravity.CENTER);
        singleBtn.setBackground(shortOn
                ? Ui.round(Ui.ACCENT, Ui.R_CTRL, 0, this)
                : Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        singleBtn.setPadding(0, 0, 0, 0);
        singleBtn.setClickable(true);
        singleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CourseCache.setShortNameMode(HomeActivity.this, !shortOn);
                renderWeek();
            }
        });
        // 与 ✏️ 完全同框：30×30 → 44×44（原 30dp 低于 44dp 最小热区）
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN));
        slp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        nameRow.addView(singleBtn, slp);
        // ✏️ 编辑：进入可视化编辑态（点课表名 → 课程表管理仍是管理入口）；与[单]同高成组
        ImageView editBtn = new ImageView(this);
        editBtn.setImageResource(R.drawable.ic_pencil);
        editBtn.setColorFilter(Ui.ACCENT);
        editBtn.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        // 热区 44dp：视觉图标 22dp + padding 11（原 20dp 在网格旁偏小，铅笔看不清）
        editBtn.setPadding(Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11));
        editBtn.setClickable(true);
        editBtn.setContentDescription("编辑课表");
        editBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(HomeActivity.this, CourseEditActivity.class);
                i.putExtra(CourseEditActivity.EXTRA_ID, s.id);
                startActivity(i);
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN));
        elp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        nameRow.addView(editBtn, elp);
        weekBox.addView(nameRow);
        weekBox.addView(Ui.space(this, Ui.GAP_MD));
        weekBox.addView(weekGridWithTime(s.courses));
        // note 信息去重：sync 课表"最后同步"与"更新于"是同一件事，只说一遍
        // 2026-11-05 P2：门数口径写明「全周 N 节课」—— 这是整份课表（7 天）的课程条数，
        // 不是今天门数、也不是去重后的课名数，避免被误读成「网格少了一门课」。
        String note;
        if (s.isSync()) {
            note = "从手环来的 · 全周 " + s.courses.size() + " 节课 · 最后同步 "
                    + CourseCache.ago(s.syncedAt);
        } else {
            note = "只在这台手机 · 全周 " + s.courses.size() + " 节课 · 更新于 "
                    + CourseCache.ago(s.createdAt);
        }
        TextView n = Ui.textLh(this, note, Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        n.setPadding(0, Ui.dp(this, Ui.GAP_SM), 0, 0);
        weekBox.addView(n);

        // update device name from connection
        SyncEngine e = SyncEngine.get(this);
        if (e.deviceName != null && e.deviceName.length() > 0 && deviceNameView != null) {
            deviceNameView.setText(e.deviceName);
        }
        if (deviceStatusView != null) {
            boolean connected = e.connected();
            deviceStatusView.setText(connected ? " 已连接" : " 未连接");
            deviceStatusView.setTextColor(connected ? Ui.OK : Ui.ERR);
        }
    }

    /**
     * 周课表网格：左侧时间列 + 7 天列，行高对齐。
     * 今天列背景加淡色蒙层，课程块按颜色语义区分。
     */
    private View weekGridWithTime(List<CourseCache.Course> all) {
        // 周网格原来直接吃 ScheduleStore 的原始课表，绕过了折算层 → 换课和第9节晚课永远不出现。
        // 这里补上同口径的两层折算，并按「正在看的那一周」算轮次（翻周时晚课跟着变）。
        try {
            all = ShiftStore.apply(this, all);
        } catch (Throwable ignored) {
        }
        java.util.Calendar rotWhen = java.util.Calendar.getInstance();
        rotWhen.add(java.util.Calendar.DAY_OF_MONTH, -CourseCache.todayIndex() + weekOffset * 7);
        try {
            all = RotationStore.inject(this, all, rotWhen);
        } catch (Throwable ignored) {
        }
        int today = CourseCache.todayIndex();
        String[] heads = {"一", "二", "三", "四", "五", "六", "日"};

        // collect all unique time slots across all days
        java.util.Set<String> timeSet = new java.util.LinkedHashSet<>();
        for (CourseCache.Course c : all) {
            if (c.time != null && c.time.length() > 0) {
                timeSet.add(CourseCache.shortTime(c.time));
            }
        }
        java.util.List<String> timeSlots = new java.util.ArrayList<>(timeSet);
        // 2026-11-05 修复（evtask-D-evev-schedul-261105-jf47jt）：LinkedHashSet 保留的是
        // 课程在数据里的插入顺序（手环导出 JSON 的顺序），不是时间先后 —— 会出现 10:55 置顶、
        // 12:55 排在 11:20 前、18:50 掉到 19:40 后。统一按「开始时间的当日分钟数」升序；
        // 解析不出时间的槽位（如占位「全」）排最后，并保持其原有相对顺序（Collections.sort 稳定）。
        java.util.Collections.sort(timeSlots, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int[] ma = CourseCache.minutes(a);
                int[] mb = CourseCache.minutes(b);
                int va = ma == null ? Integer.MAX_VALUE : ma[0];
                int vb = mb == null ? Integer.MAX_VALUE : mb[0];
                return (va < vb) ? -1 : (va > vb ? 1 : 0);
            }
        });
        // 2026-11-05 修复（evtask-D-evev-schedul-261105-seba8h）：跨天同一节课的开始时间
        // 可能差几分钟（截图里左列 18:45 / 右列 18:50 都是「晚一」），按字符串精确去重会
        // 把一节课拆成两行、彼此留空格。按 ±SLOT_TOLERANCE_MIN 分钟把相邻槽位聚成一簇，
        // 簇内所有原始 key 映射到簇首；时间列显示簇首，课程块按映射归到同一行。
        // 以簇首为锚（不是链式相邻比较），避免 18:45 / 18:50 / 18:55 被无限串成一簇。
        java.util.Map<String, String> slotAlias = new java.util.HashMap<String, String>();
        java.util.List<String> mergedSlots = new java.util.ArrayList<String>();
        int clusterAnchor = -1;
        for (String raw : timeSlots) {
            int[] m = CourseCache.minutes(raw);
            int v = m == null ? Integer.MAX_VALUE : m[0];
            boolean newCluster = mergedSlots.isEmpty()
                    || clusterAnchor == Integer.MAX_VALUE
                    || v == Integer.MAX_VALUE
                    || v - clusterAnchor > SLOT_TOLERANCE_MIN;
            if (newCluster) {
                mergedSlots.add(raw);
                clusterAnchor = v;
            }
            slotAlias.put(raw, mergedSlots.get(mergedSlots.size() - 1));
        }
        timeSlots = mergedSlots;
        if (timeSlots.isEmpty()) {
            timeSlots.add("全");
        }

        // wrap in elevated card
        LinearLayout wrapper = Ui.cardElevated(this);
        wrapper.setPadding(Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_MD),
                Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_MD));
        // 阴影 6 → 4：与「日期格 2」形成层次，避免整页过重
        wrapper.setElevation(Ui.dp(this, 4));

        // §D3 表头 = 日期条（二合一）：日期格已含「星期字 + 日期数字 + 休/班角标」，
        // 不再另起一行「一二三四五六日」——从根上消除重复文本与横向对齐问题。
        dateStripView = new LinearLayout(this);
        dateStripView.setOrientation(LinearLayout.HORIZONTAL);
        // 2026-11-05 P2（evtask-D-evev-schedul-261105-8qd7kf）：滚动吸顶时表头浮在课程行之上，
        // 必须不透明，否则会透出下方色块；用 elevation 提升 Z 序而不是 bringToFront()
        // —— 后者会改变 LinearLayout 子 View 顺序，把表头挪到卡片底部。
        dateStripView.setBackgroundColor(Ui.CARD);
        // elevation 只在吸顶生效时开启（见 applyStickyHeader）：静态画面保持零视觉回归
        stickyHeaderFloating = false;
        wrapper.addView(dateStripView);
        renderDateStrip();
        prepareStickyHeader();
        wrapper.addView(Ui.space(this, Ui.GAP_XS));

        // 每列对应的日期（含 weekOffset），用于假期 / 调休判定
        java.util.Calendar gridMon = java.util.Calendar.getInstance();
        gridMon.add(java.util.Calendar.DAY_OF_MONTH, -CourseCache.todayIndex() + weekOffset * 7);

        boolean thisWeek = (weekOffset == 0);

        // body: time col + 7 day cols
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // time column（宽 34 / 行高 44 与课程块对齐 / 字号 10）
        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        for (String ts : timeSlots) {
            TextView tv = Ui.textLh(this, ts, Ui.SP_MICRO, Ui.MUTED, false, Ui.LH_MICRO);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, TIME_COL_W),
                    Ui.dp(this, Ui.ROW_PITCH)));
            timeCol.addView(tv);
        }
        body.addView(timeCol);

        // 7 day columns（假期列清空并在列内写假期名；调休列按目标星期几的课表显示）
        for (int d = 0; d < 7; d++) {
            LinearLayout dayCol = new LinearLayout(this);
            dayCol.setOrientation(LinearLayout.VERTICAL);
            java.util.Calendar day = (java.util.Calendar) gridMon.clone();
            day.add(java.util.Calendar.DAY_OF_MONTH, d);
            int override = Holiday.resolveDay(this, day);

            // 假期列（EV 首页语义：假期名写在放假的「那一天」）——整列淡绿底 + 列中央假期名
            if (override == Holiday.HOLIDAY) {
                dayCol.setBackground(Ui.round(Ui.OK_LIGHT, Ui.R_BLOCK, 0, this));
                dayCol.setGravity(android.view.Gravity.CENTER);
                dayCol.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
                TextView hn = Ui.textMedium(this, Holiday.holidayName(this, day),
                        Ui.SP_CAPTION, Ui.OK);
                hn.setGravity(android.view.Gravity.CENTER);
                int colH = Ui.dp(this, Ui.ROW_PITCH) * Math.max(1, timeSlots.size());
                hn.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, colH));
                dayCol.addView(hn);
                body.addView(dayCol, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                continue;
            }

            // §3.2 今天列加主色淡底（圆角 6）：一眼定位「今天在哪一列」
            if (thisWeek && d == today) {
                dayCol.setBackground(Ui.round(Ui.ACCENT_LIGHT, 6, 0, this));
            }
            dayCol.setPadding(Ui.dp(this, Ui.COL_PAD), Ui.dp(this, Ui.COL_PAD),
                    Ui.dp(this, Ui.COL_PAD), Ui.dp(this, Ui.COL_PAD));

            // 调休列按目标星期几取课
            java.util.List<CourseCache.Course> dayList = CourseCache.coursesOfDay(all, override >= 0 ? override : d);

            // 2026-11-05 修复：同一时间槽可能有多门课（合班 / 单双周 / 手环重复导入）。
            // 原来是 Map<String, Course>，map.put 会把靠前的那门静默覆盖掉；改成按槽位分桶，
            // 全部保留，多课时合成一个块并显式标注数量，点开可逐门查看。
            java.util.Map<String, java.util.List<CourseCache.Course>> map =
                    new java.util.LinkedHashMap<String, java.util.List<CourseCache.Course>>();
            for (CourseCache.Course c : dayList) {
                String raw = CourseCache.shortTime(c.time);
                // 2026-11-05：归一到所属节次簇的代表 key，18:50 的课与 18:45 的课同槽显示
                String key = slotAlias.containsKey(raw) ? slotAlias.get(raw) : raw;
                java.util.List<CourseCache.Course> bucket = map.get(key);
                if (bucket == null) {
                    bucket = new java.util.ArrayList<CourseCache.Course>();
                    map.put(key, bucket);
                }
                bucket.add(c);
            }

            for (String ts : timeSlots) {
                java.util.List<CourseCache.Course> cs = map.get(ts);
                if (cs != null && !cs.isEmpty()) {
                    // 当前节：本周 + 今天列 + 现在时刻落在该槽任一门课的时间区间内
                    boolean current = false;
                    if (thisWeek && d == today) {
                        int now = CourseCache.nowMinutes();
                        for (CourseCache.Course c : cs) {
                            int[] m = CourseCache.minutes(c.time);
                            if (m != null && now >= m[0] && now < m[1]) {
                                current = true;
                                break;
                            }
                        }
                    }
                    dayCol.addView(cs.size() == 1
                            ? courseBlockCompact(cs.get(0), current)
                            : courseBlockMulti(cs, current));
                } else {
                    // empty slot
                    View empty = new View(this);
                    empty.setBackground(Ui.round(0x00000000, Ui.R_BLOCK, 0, this));
                    empty.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.ROW_PITCH)));
                    dayCol.addView(empty);
                }
            }
            body.addView(dayCol, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        wrapper.addView(body);
        return wrapper;
    }

    /**
     * 课程小色块（周视图网格用）：单字模式近正方 40dp、双行 44dp；上下均分补足 ROW_PITCH，行距恒对齐。
     * ev-ui-android v1.1：浅底 + 4dp 身份色条，课名走 Ui.courseInk 深字；单字模式放大课名。
     * current=true（今天列里正在上的课）加 2dp ACCENT 描边。
     */
    private View courseBlockCompact(CourseCache.Course c, boolean current) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        // ev-ui-android v1.1 · P1-①：浅底 + 4dp 身份色条，课名深字；底色与字色全由 Ui.java token 提供
        // 晚自习（17:00 后）统一淡紫：轮转注入的科目名每周都不同，跟着课名上色会周周换色，
        // 反而认不出「这一行是晚上那节」。整行一个色，比逐课配色更能表达「晚课」。
        boolean night = isNight(c.time);
        b.setBackground(night ? Ui.round(NIGHT_BG, Ui.R_BLOCK, NIGHT_LINE, this)
                : Ui.courseBlockBg(this, c.name, current));
        // 2026-10-05 首页 UI 精细化：块高按模式收敛（单字近正方），上下均分补足 ROW_PITCH；
        // 左右外边距由 3 收口到 BLOCK_MARGIN → 相邻块横向缝 10→4dp、可用文字宽 +25%
        int blockH = shortModeHere() ? Ui.BLOCK_H_1CHAR : Ui.BLOCK_H_2LINE;
        int vMargin = (Ui.ROW_PITCH - blockH) / 2;   // 每行总高恒 = ROW_PITCH，时间轴不再越滚越歪
        b.setPadding(Ui.dp(this, 2), Ui.dp(this, Ui.GAP_XS),
                Ui.dp(this, 2), Ui.dp(this, Ui.GAP_XS));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, blockH));
        lp.setMargins(Ui.dp(this, Ui.BLOCK_MARGIN), Ui.dp(this, vMargin),
                Ui.dp(this, Ui.BLOCK_MARGIN), Ui.dp(this, vMargin));
        b.setLayoutParams(lp);
        int fg = night ? NIGHT_FG : Ui.courseInk(c.name);
        String disp = CourseCache.displayName(c.name, CourseCache.shortNameMode(this));
        // 10f → 11.5sp（原 10sp 偏小）；单字 16f → 15sp 与次标题档一致
        TextView tv = Ui.textMediumLh(this, disp,
                disp.length() <= 1 ? Ui.SP_SUBTITLE : Ui.SP_CAPTION, fg, Ui.LH_TIGHT);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setSingleLine(true);
        // 2026-11-05（evtask-D-evev-schedul-261105-seba8h）：列宽约屏宽 1/7，四字课名放不下；
        // 加尾省略号 + 无障碍全名，完整课名仍可点块进 showCourseDetail 查看（点击逻辑不变）。
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setContentDescription(disp);
        b.addView(tv);
        if (!shortModeHere() && c.location != null && c.location.length() > 0) {
            // 9f → 10.5sp：9sp 低于中文可读下限
            TextView loc = Ui.textLh(this, ellip6(c.location), Ui.SP_MICRO,
                    (fg & 0x00FFFFFF) | 0xC8000000, false, Ui.LH_TIGHT);
            loc.setGravity(android.view.Gravity.CENTER);
            loc.setSingleLine(true);
            loc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.addView(loc);
        }
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCourseDetail(c); }
        });
        return b;
    }

    /**
     * 同一时间槽有多门课时的合并块：行高仍 = ROW_PITCH（与单课块一致，时间轴不错位），
     * 首行显示第一门课名，次行显式标注「共 N 门 · 点开查看」，点开后可逐门进详情。
     */
    private View courseBlockMulti(final java.util.List<CourseCache.Course> cs, boolean current) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        b.setBackground(Ui.courseBlockBg(this, cs.get(0).name, current));
        int blockH = shortModeHere() ? Ui.BLOCK_H_1CHAR : Ui.BLOCK_H_2LINE;
        int vMargin = (Ui.ROW_PITCH - blockH) / 2;
        b.setPadding(Ui.dp(this, 2), Ui.dp(this, Ui.GAP_XS),
                Ui.dp(this, 2), Ui.dp(this, Ui.GAP_XS));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, blockH));
        lp.setMargins(Ui.dp(this, Ui.BLOCK_MARGIN), Ui.dp(this, vMargin),
                Ui.dp(this, Ui.BLOCK_MARGIN), Ui.dp(this, vMargin));
        b.setLayoutParams(lp);
        int fg = Ui.courseInk(cs.get(0).name);
        TextView tv = Ui.textMediumLh(this,
                CourseCache.displayName(cs.get(0).name, CourseCache.shortNameMode(this)),
                Ui.SP_CAPTION, fg, Ui.LH_TIGHT);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setContentDescription(tv.getText());
        b.addView(tv);
        TextView hint = Ui.textLh(this, "共 " + cs.size() + " 门 · 点开查看", Ui.SP_MICRO,
                (fg & 0x00FFFFFF) | 0xC8000000, false, Ui.LH_TIGHT);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.addView(hint);
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCoursePicker(cs); }
        });
        return b;
    }

    /** 多课选择：不静默丢课，让每门课都能进详情/编辑。 */
    private void showCoursePicker(final java.util.List<CourseCache.Course> cs) {
        String[] names = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) {
            names[i] = cs.get(i).name == null ? "(未命名)" : cs.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("这一节有 " + cs.size() + " 门课")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        showCourseDetail(cs.get(which));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private boolean shortModeHere() {
        return CourseCache.shortNameMode(this);
    }

    private static String ellip6(String s) {
        return s != null && s.length() > 7 ? s.substring(0, 6) + "…" : s;
    }

    /**
     * 课程只读详情（浏览态轻量路径，方案 v2 3.2）：看到课有问题 → [编辑] 直达对应课的编辑面板。
     * 这里只负责"看"，改课必须进编辑态页面，避免首页误触改数据。
     */
    private void showCourseDetail(final CourseCache.Course c) {
        Dialogs.confirm(this, 0, 0,
                c.name,
                "周" + CourseEditUtil.DAY_SHORT[Math.max(0, Math.min(6, c.day))] + "　" + (c.time == null ? "" : c.time)
                        + (c.location.length() > 0 ? "\n📍 " + c.location : "")
                        + (c.teacher.length() > 0 ? "　👤 " + c.teacher : ""),
                null, "编辑", false,
                new Dialogs.Action() {
                    @Override public void run() {
                        Intent i = new Intent(HomeActivity.this, CourseEditActivity.class);
                        i.putExtra(CourseEditActivity.EXTRA_ID, ScheduleStore.activeId(HomeActivity.this));
                        i.putExtra(CourseEditActivity.EXTRA_FOCUS_NAME, c.name);
                        i.putExtra(CourseEditActivity.EXTRA_FOCUS_DAY, c.day);
                        i.putExtra(CourseEditActivity.EXTRA_FOCUS_TIME, c.time);
                        startActivity(i);
                    }
                });
    }

    // ======================= 快捷操作 =======================

    /**
     * 发一条 EV 指令给手环。无回应 / 通道没建立时会自动「拉起手环 EV → 补发」，
     * 该逻辑已内建在 {@link SyncEngine#sendWake}（见其注释），此处不必重复。
     *
     * 现由「上课了/下课了」在旧版手环不认 notify 时回落调用。
     */
    private void quickSend(final String json, final String label) {
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            miniStatus("手环未连接，无法" + label, Ui.WARN);
            return;
        }
        miniStatus("正在发送「" + label + "」…", Ui.ACCENT);
        e.sendWake(json, new SyncEngine.Reply() {
            @Override public void onReply(String r) {
                // 检查回包 ok 字段：手环「执行成功」才算送达，避免把错误回包误判成成功
                boolean ok = true;
                String reason = "";
                try {
                    JSONObject o = new JSONObject(r);
                    ok = o.optBoolean("ok", true);   // 无 ok 字段按兼容成功
                    reason = o.optString("reason", "");
                } catch (Throwable ignored) {
                }
                if (ok) {
                    miniStatus("「" + label + "」已送达 ✓", Ui.OK);
                } else {
                    miniStatus("手环未响应「" + label + "」"
                            + (reason.length() > 0 ? "：" + reason : ""), Ui.WARN);
                }
            }
            @Override public void onTimeout(String hint) {
                miniStatus("手环没理我（" + label + "）", Ui.WARN);
            }
            @Override public void onError(String msg) {
                miniStatus(label + "发送失败：" + msg, Ui.ERR);
            }
            @Override public void onWaking() {
                miniStatus("手环没理我，正在叫醒它再发一次「" + label + "」…", Ui.ACCENT);
            }
        });
    }

    /**
     * 「上课了 / 下课了」：双通道提醒手环（2026-10-04 起，替代原先直接复用 action=call 的做法）。
     *
     * 背景：原先两个按钮都发 {@code {"action":"call"}}，而手环端 call 只做「一次长震动」、
     * 完全忽略 text —— 于是「上课了」和「呼叫手环」效果一模一样，手环上不会有任何「上课了」提示。
     *
     * 现在走两条互补通道：
     *  ① 系统通知（{@link SyncEngine#notifyWatch} → sendNotify）：手环弹一张通知卡，
     *     **不依赖手环是否装了 EV 课程表**，是留言页已在用的成熟通道。
     *  ② EV 协议（interconnect，{@code action=notify}）：新版手环弹提示条 + 上课/下课区分震动，
     *     并回 {@code {ok:true}}；手机端据回包判定端到端送达。
     *
     * 兼容：旧版手环不认 notify（会掉进 import 兜底回 "no courses"）→ 自动回落
     * {@code action=call}（长震动），保证老用户点了也有反馈、不空点。
     */
    private void classNotify(final String label, final String type) {
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            miniStatus("手环未连接，无法" + label, Ui.WARN);
            return;
        }
        miniStatus("正在提醒手环「" + label + "」…", Ui.ACCENT);

        // ① 系统通知卡（尽力而为：失败不影响 ② EV 通道，故不给 UI 噪音）
        final boolean isEnd = "class_end".equals(type);
        e.notifyWatch(label, isEnd ? "下课了，休息一下吧" : "上课了，看看接下来的课", new SyncEngine.Cb() {
            @Override public void on(boolean ok, String info) { /* best-effort */ }
        });

        // ② EV 协议：notify（新版手环弹提示条 + 专用震动）；失败回落 call（长震动）
        final String json = "{\"action\":\"notify\",\"type\":\"" + type + "\",\"text\":\"" + label + "！\"}";
        e.sendWake(json, new SyncEngine.Reply() {
            @Override public void onReply(String r) {
                boolean ok = true;
                try {
                    JSONObject o = new JSONObject(r);
                    ok = o.optBoolean("ok", true);   // 无 ok 字段按兼容成功
                } catch (Throwable ignored) {
                }
                if (ok) {
                    miniStatus("「" + label + "」已提醒手环 ✓", Ui.OK);
                } else {
                    // 旧版手环不认 notify → 回落长震动，保证有反馈
                    miniStatus("手环有点老，改成用震动提醒「" + label + "」…", Ui.MUTED);
                    quickSend("{\"action\":\"call\",\"text\":\"" + label + "\"}", label);
                }
            }
            @Override public void onTimeout(String hint) {
                miniStatus("手环没理我（" + label + "）", Ui.WARN);
            }
            @Override public void onError(String msg) {
                miniStatus(label + "提醒失败：" + msg, Ui.ERR);
            }
            @Override public void onWaking() {
                miniStatus("手环没理我，正在叫醒它再发一次「" + label + "」…", Ui.ACCENT);
            }
        });
    }

    /** 快速留言：自绘弹窗（与全站弹窗同视觉语言），写队列并尝试立即送达（与留言页同一份存储） */
    private void quickMessage() {
        final Dialog[] holder = new Dialog[1];

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        box.setBackground(Ui.round(Ui.CARD, 22, 0, this));
        box.setPadding(pad, pad, pad, pad);

        // 头部：图标章 + 标题 + 副标题
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_tab_message);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setBackground(Ui.round(Ui.ACCENT, 24, 0, this));
        iv.setPadding(Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11));
        head.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout.LayoutParams ivLp = (LinearLayout.LayoutParams) head.getChildAt(0).getLayoutParams();
        ivLp.rightMargin = Ui.dp(this, 12);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "给手环留言", 16.5f, Ui.TEXT, true));
        titles.addView(Ui.text(this, "会显示在手环首页；没连上就先存着，连上自动发", 11f, Ui.MUTED, false));
        head.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);
        box.addView(Ui.space(this, 14));

        // 输入框（圆角内嵌，多行）
        final EditText input = Ui.editText(this);
        input.setHint("写一条留言给手环…");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(13.5f);
        input.setBackground(Ui.round(Ui.CARD2, 12, Ui.LINE, this));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(60) });
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(Ui.space(this, 6));

        // 字数计数（右对齐）
        final TextView counter = Ui.text(this, "0 / 60 字", 11f, Ui.MUTED, false);
        counter.setGravity(Gravity.END);
        box.addView(counter, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                int n = s.length();
                counter.setText(n + " / 60 字");
                counter.setTextColor(n >= 50 ? Ui.WARN : Ui.MUTED);
            }

            @Override public void afterTextChanged(Editable s) {
            }
        });
        box.addView(Ui.space(this, 12));

        // 按钮：打开留言页（幽灵） + 发送（主色，空输入置灰）
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button page = Ui.button(this, "去留言页", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                startActivity(new Intent(HomeActivity.this, MessageActivity.class));
            }
        });
        page.setBackground(Ui.round(0x00000000, 12, Ui.LINE, this));
        page.setTextColor(Ui.TEXT);
        btns.addView(page, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
        LinearLayout.LayoutParams lp0 = (LinearLayout.LayoutParams) btns.getChildAt(0).getLayoutParams();
        lp0.rightMargin = Ui.dp(this, 10);
        final Button send = Ui.button(this, "发送", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() == 0) {
                    return;
                }
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                MessageActivity.enqueueOutgoing(HomeActivity.this, text);
                miniStatus("留言发出去啦（手环没连上也不要紧，连上会自动补发）", Ui.OK);
            }
        });
        send.setBackground(Ui.round(Ui.ACCENT, 12, 0, this));
        send.setTextColor(0xFFFFFFFF);
        btns.addView(send, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));
        box.addView(btns);

        // 空输入 → 发送置灰
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean ok = s.toString().trim().length() > 0;
                send.setEnabled(ok);
                send.setAlpha(ok ? 1f : 0.45f);
            }

            @Override public void afterTextChanged(Editable s) {
            }
        });
        send.setEnabled(false);
        send.setAlpha(0.45f);

        Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(this, 330), WindowManager.LayoutParams.WRAP_CONTENT);
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.setCancelable(true);
        holder[0] = dlg;
        dlg.show();
    }

    /** 手动同步：已连接 → 重新拉课表；未连接 → 走完整连接流程 */
    private void manualSync() {
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            miniStatus("正在把课表取过来…", Ui.ACCENT);
            pullSchedule();
        } else {
            startConnect();
        }
    }

    // ======================= UI：EvBox 旧首页 =======================

    private void buildLegacyUi() {
        LinearLayout root = Ui.screen(this);

        root.addView(Ui.title(this, "EV 课程表"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "安卓同步器 v" + version(), 12f, Ui.MUTED, false));
        root.addView(Ui.space(this, 14));

        LinearLayout welcome = Ui.card(this);
        welcomeView = Ui.text(this, "欢迎！", 22f, Ui.TEXT, true);
        welcome.addView(welcomeView);
        statusView = Ui.text(this, "正在连接手环…", 12.5f, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 4), 0, 0);
        welcome.addView(statusView);
        root.addView(welcome);
        root.addView(Ui.space(this, 10));

        LinearLayout progress = Ui.card(this);
        estimateView = Ui.text(this, "预计还需 ~4 秒", 12f, Ui.ACCENT, true);
        progress.addView(estimateView);
        progress.addView(Ui.space(this, 8));
        stepsView = new LinearLayout(this);
        stepsView.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 4; i++) {
            stepRows[i] = Ui.text(this, stepLine(i, SyncEngine.PENDING, ""),
                    12f, stepColor(SyncEngine.PENDING), false);
            stepRows[i].setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            stepsView.addView(stepRows[i]);
        }
        progress.addView(stepsView);
        root.addView(progress);
        root.addView(Ui.space(this, 10));

        errorCard = Ui.card(this);
        errorCard.setVisibility(View.GONE);
        hintView = Ui.text(this, "", 13f, Ui.WARN, false);
        errorCard.addView(hintView);
        errorCard.addView(Ui.space(this, 10));
        errorCard.addView(Ui.grid(this,
                Ui.button(this, "再试一次", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { startConnect(); }
                }),
                Ui.button(this, "打开手环课表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                })));
        root.addView(errorCard);
        root.addView(Ui.space(this, 10));

        actionsView = new LinearLayout(this);
        actionsView.setOrientation(LinearLayout.VERTICAL);
        actionsView.addView(Ui.grid(this,
                Ui.button(this, "设置", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
                    }
                }),
                Ui.button(this, "调试", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(HomeActivity.this, DebugActivity.class));
                    }
                })));
        actionsView.setVisibility(View.GONE);
        root.addView(actionsView);

        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "包名 " + getPackageName() + "  ·  v" + version()));

        setContentView(Ui.wrapWithBottomBar(this, root, 0));
    }

    private static final String[] STEP_LABELS =
            {"看看小米运动健康在不在", "找找你的手环", "请手环点个同意", "打开手环上的课程表"};

    private String version() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int stepColor(int state) {
        switch (state) {
            case SyncEngine.OK:      return Ui.OK;
            case SyncEngine.RUNNING: return Ui.ACCENT;
            case SyncEngine.FAIL:    return Ui.ERR;
            default:                 return Ui.MUTED;
        }
    }

    /**
     * 只负责拼文案，【不许】在这里碰 stepRows[i]。
     * 之前这里写了 stepRows[i].setTextColor(...)，而调用处是
     *     stepRows[i] = Ui.text(..., stepLine(...), ...)
     * —— 赋值发生在最后，stepLine 执行时 stepRows[i] 还是 null，直接 NPE 闪退。
     */
    private String stepLine(int i, int state, String detail) {
        String mark;
        switch (state) {
            case SyncEngine.OK:      mark = "✓"; break;
            case SyncEngine.RUNNING: mark = "◐"; break;
            case SyncEngine.FAIL:    mark = "✕"; break;
            default:                 mark = "○"; break;
        }
        String s = (i + 1) + ". " + mark + "  " + STEP_LABELS[i];
        if (detail != null && detail.length() > 0) {
            s += "   — " + detail;
        }
        return s;
    }

    private int estimateSeconds() {
        int left = 0;
        for (int i = 0; i < states.length; i++) {
            if (states[i] != SyncEngine.OK) {
                left++;
            }
        }
        if (phase == PHASE_PROFILE) {
            left = Math.max(left, 1) + 1;
        }
        if (phase == PHASE_DONE || phase == PHASE_ERR) {
            left = 0;
        }
        return left;
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        ui.post(tick);
    }

    private void stopTicking() {
        ticking = false;
        ui.removeCallbacks(tick);
    }

    // ======================= 连接流程 =======================

    private void startConnect() {
        phase = PHASE_CONNECT;
        for (int i = 0; i < states.length; i++) {
            states[i] = SyncEngine.PENDING;
        }
        errorCard.setVisibility(View.GONE);
        if (legacy) {
            actionsView.setVisibility(View.GONE);
            welcomeView.setText("欢迎！");
            statusView.setText("正在连接手环…");
            for (int i = 0; i < 4; i++) {
                stepRows[i].setText(stepLine(i, SyncEngine.PENDING, ""));
                stepRows[i].setTextColor(stepColor(SyncEngine.PENDING));
            }
        } else {
            connectingLabel = "正在连接手环…";
            miniStatus("● " + connectingLabel, Ui.MUTED);
            setStatusPill(Ui.ACCENT, "连接中", "正在连接手环");
        }
        startTicking();

        SyncEngine.get(this).connect(new SyncEngine.Steps() {
            @Override public void onUpdate(String[] labels, int[] st, String[] details) {
                for (int i = 0; i < 4; i++) {
                    states[i] = st[i];
                }
                if (legacy) {
                    for (int i = 0; i < 4; i++) {
                        stepRows[i].setText(stepLine(i, st[i], details[i]));
                        stepRows[i].setTextColor(stepColor(st[i]));
                    }
                } else {
                    // 新首页：迷你条只汇报当前进行到哪一步
                    for (int i = 0; i < 4; i++) {
                        if (st[i] == SyncEngine.RUNNING) {
                            connectingLabel = "连接中（" + (i + 1) + "/4）：" + STEP_LABELS[i] + "…";
                            miniStatus("● " + connectingLabel, Ui.ACCENT);
                            break;
                        }
                    }
                }
            }

            @Override public void onFinish(boolean ok, String hint) {
                if (!ok) {
                    fail(hint);
                } else {
                    loadProfile();
                }
            }
        });
    }

    /** 连上后再拉一次 export，用于取昵称 / 版本号 / 课表 */
    private void loadProfile() {
        phase = PHASE_PROFILE;
        if (legacy) {
            statusView.setText("已连接，正在读取资料…");
            estimateView.setText("预计还需 ~2 秒");
        } else {
            connectingLabel = "连上啦，正在把课表取过来…";
            miniStatus("● " + connectingLabel, Ui.ACCENT);
        }
        SyncEngine.get(this).export(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    SyncEngine e = SyncEngine.get(HomeActivity.this);
                    e.lastExportJson = json;
                    JSONObject d = o.optJSONObject("data");
                    if (d != null) {
                        e.nickname = d.optString("nickname");
                        JSONArray sch = d.optJSONArray("schedule");
                        int total = 0;
                        if (sch != null) {
                            for (int i = 0; i < sch.length(); i++) {
                                JSONObject day = sch.optJSONObject(i);
                                JSONArray cs = (day == null) ? null : day.optJSONArray("classes");
                                total += (cs == null) ? 0 : cs.length();
                            }
                        }
                        e.courseCount = total;
                        // 手环外观镜像（「跟随手环」主题模式的数据源）
                        WatchAppearance.save(HomeActivity.this,
                                d.has("appTheme") ? d.optString("appTheme") : null,
                                d.has("homepageTemplate") ? d.optString("homepageTemplate") : null,
                                d.has("weekviewTemplate") ? d.optString("weekviewTemplate") : null);
                        if (sch != null) {
                            if (legacy) {
                                CourseCache.save(HomeActivity.this, sch, "");
                            } else if (ScheduleStore.active(HomeActivity.this) != null
                                    && ScheduleStore.active(HomeActivity.this).dirty) {
                                // 有未同步本地改动：走三方合并补发，不整表覆盖（否则会丢本地编辑）
                                SyncCoordinator.syncNow(HomeActivity.this, null);
                            } else {
                                // 方案 A：写入多课表存储（拿到当前课表名后覆盖更新）
                                syncScheduleToStore(sch);
                            }
                        }
                    }
                    done();
                } catch (Throwable t) {
                    fail("没读懂手环的回复，再试一次吧");
                }
            }

            @Override public void onTimeout(String hint) { fail(hint); }
            @Override public void onError(String msg) { fail(msg); }
        });
    }

    /**
     * 把刚拉到的课表写入多课表存储：
     * 先问手环要课表清单（拿当前课表名），失败则用「手环课表」兜底名。
     */
    private void syncScheduleToStore(final JSONArray sch) {
        SyncEngine.get(this).listSchedules(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                String name = "";
                try {
                    JSONObject o = new JSONObject(json);
                    JSONArray names = o.optJSONArray("names");
                    int cur = o.optInt("current", 0);
                    if (names != null && cur >= 0 && cur < names.length()) {
                        name = names.optString(cur);
                    }
                } catch (Throwable ignored) {
                }
                ScheduleStore.upsertFromWatch(HomeActivity.this,
                        SyncEngine.get(HomeActivity.this).currentDeviceId(),
                        SyncEngine.get(HomeActivity.this).currentDeviceName(), name, sch);
                renderContent();
            }
            @Override public void onTimeout(String hint) {
                ScheduleStore.upsertFromWatch(HomeActivity.this,
                        SyncEngine.get(HomeActivity.this).currentDeviceId(),
                        SyncEngine.get(HomeActivity.this).currentDeviceName(), "", sch);
                renderContent();
            }
            @Override public void onError(String msg) {
                ScheduleStore.upsertFromWatch(HomeActivity.this,
                        SyncEngine.get(HomeActivity.this).currentDeviceId(),
                        SyncEngine.get(HomeActivity.this).currentDeviceName(), "", sch);
                renderContent();
            }
        });
    }

    /** 手动「🔄 同步」：拉当前课表刷新首页 */
    private void pullSchedule() {
        SyncEngine.get(this).export(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        miniStatus("手环里好像还没有课表", Ui.WARN);
                        return;
                    }
                    SyncEngine.get(HomeActivity.this).lastExportJson = json;
                    syncScheduleToStore(sch);
                    flashStatus("● 课表已更新 ✓", Ui.OK);
                } catch (Throwable t) {
                    miniStatus("更新失败：没读懂手环的回复", Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) { miniStatus(hint, Ui.WARN); }
            @Override public void onError(String msg) { miniStatus("同步失败：" + msg, Ui.ERR); }
        });
    }

    private void done() {
        phase = PHASE_DONE;
        stopTicking();
        SyncEngine e = SyncEngine.get(this);
        if (legacy) {
            welcomeView.setText("欢迎，" + (e.nickname.length() > 0 ? e.nickname : "同学") + "！");
            statusView.setText("已连接 " + e.deviceName + "  ·  " + e.versionName);
            statusView.setTextColor(Ui.OK);
            estimateView.setText("连接完成");
            estimateView.setTextColor(Ui.OK);
            actionsView.setVisibility(View.VISIBLE);
        } else {
            // 显示手环真实套数（list_schedules 权威值）；还没读到清单时先报本地镜像数并标注
            int n = e.bandScheduleCount;
            String sets;
            if (n >= 0) {
                sets = "手环课表 " + n + " 套";
            } else {
                int local = 0;
                for (ScheduleStore.Schedule ss : ScheduleStore.list(this)) {
                    if (ss.isSync()) {
                        local++;
                    }
                }
                sets = "本地已存 " + local + " 套（以手环为准）";
            }
            // §4.2 稳态只点绿状态点（连接详情在「手环」页），不再常驻一条「已连接 …」文案；
            // 套数/版本仍进无障碍描述，长按读屏或点圆点进「手环」页都能看到。
            setStatusPill(Ui.OK, "已连接", "已连接 " + e.deviceName + " · v" + e.versionName + " · " + sets);
            e.refreshBandScheduleCount(); // 清单一到，状态回调会把文案刷成真实套数
            // update device card
            if (deviceNameView != null && e.deviceName != null && e.deviceName.length() > 0) {
                deviceNameView.setText(e.deviceName);
            }
            if (deviceStatusView != null) {
                deviceStatusView.setText(" 已连接");
                deviceStatusView.setTextColor(Ui.OK);
            }
        }
        errorCard.setVisibility(View.GONE);
        applyQuickBoxVisibility();
    }

    private void fail(String hint) {
        phase = PHASE_ERR;
        stopTicking();
        if (legacy) {
            estimateView.setText("连接未完成");
            estimateView.setTextColor(Ui.ERR);
            statusView.setText("未连接");
            statusView.setTextColor(Ui.ERR);
            actionsView.setVisibility(View.VISIBLE);
        } else {
            miniStatus("● 未连接手环", Ui.ERR);
            // 红 ⟺ 连接失败（与 refreshMiniFromEngine 的「错误卡在屏」判定同义），描述同步为「连接失败」
            setStatusPill(Ui.ERR, "未连接", "连接失败");
        }
        hintView.setText(hint);
        errorCard.setVisibility(View.VISIBLE);
        // 状态只说一遍：错误卡（带重试按钮）在场时，隐藏迷你条
        if (miniBarView != null) {
            miniBarView.setVisibility(View.GONE);
        }
        // 连不上也保持课表在屏（默认本地课表兜底），只是迷你条提示未连接
        applyQuickBoxVisibility();
    }

    private void miniStatus(String s, int color) {
        if (miniStatusView == null) {
            return;
        }
        ui.removeCallbacks(miniFade);   // 新的即时反馈取消上一条的自动收起
        miniStatusView.setText(s);
        miniStatusView.setTextColor(color);
        if (miniBarView != null) {
            miniBarView.setVisibility(View.VISIBLE);
        }
    }

    /** §4.2 状态条的自动收起任务（瞬时反馈用） */
    private final Runnable miniFade = new Runnable() {
        @Override public void run() {
            if (miniBarView != null) {
                miniBarView.setVisibility(View.GONE);
            }
        }
    };

    /**
     * §4.2 瞬时反馈：显示状态条并在 2.6s 后自动收起。
     * 用于「已送达 ✓ / 已同步 ✓」这类一次性结果 —— 首页稳态不留常驻条（连接详情在「手环」页）。
     */
    private void flashStatus(String s, int color) {
        miniStatus(s, color);
        ui.postDelayed(miniFade, 2600);
    }

    /** §D5 顶栏连接状态胶囊：圆点颜色 + 胶囊文字（label）+ 无障碍描述（desc） */
    private void setStatusPill(int color, String label, String desc) {
        if (statusDotView != null) {
            statusDotView.setBackground(Ui.round(color, 4, 0, this));
        }
        if (statusPillText != null) {
            statusPillText.setText(label);
            statusPillText.setTextColor(color);
        }
        if (statusPillBtn != null) {
            statusPillBtn.setContentDescription("手环连接状态：" + desc);
        }
    }

    /** 迷你条按引擎状态刷新（状态回调驱动；与 ConnectionBar 同一数据源）。 */
    private void refreshMiniFromEngine() {
        applyQuickBoxVisibility();
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            // 稳态：只把顶栏状态点点绿，不再常驻一条「已连接 …」文案（连接详情在「手环」页）
            setStatusPill(Ui.OK, "已连接", "已连接" + (e.deviceName != null && e.deviceName.length() > 0 ? " " + e.deviceName : ""));
        } else if (e.autoRetryRunning()) {
            setStatusPill(Ui.ACCENT, "重连中", "重连中");
            String p = e.connectProgress();
            miniStatus("● " + (p.length() > 0 ? p : "重连中…"), Ui.ACCENT);
        } else {
            // 未连接：错误卡在屏（上次连接失败）→ 红；否则（从未连上/闲置）→ 灰。
            // 用错误卡的可见性而非调用顺序判定，避免「红 ↔ 灰」因谁最后执行而闪烁。
            boolean failed = (errorCard != null && errorCard.getVisibility() == View.VISIBLE);
            setStatusPill(failed ? Ui.ERR : Ui.MUTED, "未连接",
                    failed ? "连接失败" : "未连接手环");
        }
    }

    /**
     * 首页「呼叫手环」快捷区（呼叫手环 / 上课了 / 留言 / 下课了）全都依赖手环连接——
     * 未连接时整块隐藏，避免点了只弹「未连接」；连上后自动恢复。
     */
    private void applyQuickBoxVisibility() {
        if (legacy || quickBox == null) {
            return;
        }
        quickBox.setVisibility(SyncEngine.get(this).connected() ? View.VISIBLE : View.GONE);
    }

    private void launchEv() {
        SyncEngine.get(this).launchEv(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                String s = (ok ? "已请求拉起" : "拉起失败") + "：" + msg;
                if (legacy && statusView != null) {
                    statusView.setText(s);
                    statusView.setTextColor(ok ? Ui.OK : Ui.ERR);
                } else {
                    miniStatus(s, ok ? Ui.OK : Ui.ERR);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台：把手环消息交给服务/应用上下文接管 → 改用系统通知提醒
        SyncService.installObserverIfEnabled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.needRecreate(this, lastThemeVersion)) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, "/apk/home");
        // 首页也接管「手环主动消息」：只要 App 在前台，留言就能被提醒（按 id 去重）
        MessageActivity.installObserver(this);
        installNodeChooser();
        if (!legacy) {
            // 从课程表管理页切换回来 → 按当前模式刷新（首页刷今天，周页刷网格）
            renderContent();
            return;
        }
        SyncEngine e = SyncEngine.get(this);
        if (e.connected() && phase == PHASE_DONE && e.nickname.length() > 0
                && welcomeView != null) {
            welcomeView.setText("欢迎，" + e.nickname + "！");
        }
    }
}