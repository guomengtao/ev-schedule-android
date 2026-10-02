package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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

    // ---- EV 新首页视图 ----
    private TextView scheduleNameView, miniStatusView;
    private LinearLayout weekBox, errorCard, quickBox;
    private TextView hintView;
    private int phase = PHASE_CONNECT;

    private TextView greetingView, pageTitleView, weekLabelView;
    /** 连接迷你条容器（与错误卡互斥显示，修"同屏两处说未连接"） */
    private LinearLayout miniBarView;
    private LinearLayout dateStripView, deviceCardView;
    private TextView deviceNameView, deviceStatusView, batteryView;
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
        if (!legacy) {
            // 数据层兜底：旧缓存迁移 / 出厂默认课表（保证首页永远有课表可看）
            ScheduleStore.ensureInitialized(this);
        }
        buildUi();
        // 状态回调（onCreate 注册一次；onResume 不重复注册，多监听列表会堆积）：
        // 真实套数读到后 / 连接状态变化时，迷你条自动刷新
        if (!legacy) {
            SyncEngine.get(this).addStatusCallback(new Runnable() {
                @Override public void run() { refreshMiniFromEngine(); }
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
            names[i] = devices.get(i).name + "   (" + devices.get(i).id + ")";
            if (devices.get(i).id.equals(preferredId)) {
                checked = i;
            }
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("发现 " + devices.size() + " 台已连接设备，请选择")
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
        LinearLayout root = Ui.screen(this);

        // ---- 头部：头像 + 问候 + 标题 + 周切换 ----
        buildHeader(root);

        // ---- 周日期条 ----
        // 格子用 weight 均分宽度（原 43dp 固定宽 × 7 + 间隔会超出 360dp 屏宽，最后一格被裁掉）
        dateStripView = new LinearLayout(this);
        dateStripView.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(dateStripView);
        root.addView(Ui.space(this, 12));

        // ---- 周课表网格（带时间轴） ----
        weekBox = new LinearLayout(this);
        weekBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(weekBox);
        root.addView(Ui.space(this, 12));

        // ---- 设备卡已移至「手环」页（连接管理集中在设备作用域页；首页只留迷你状态条） ----

        // ---- 连接状态迷你条 ----
        LinearLayout bar = Ui.card(this);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        miniStatusView = Ui.text(this, "● 正在连接手环…", 12f, Ui.MUTED, false);
        bar.addView(miniStatusView);
        miniBarView = bar;
        root.addView(bar);
        root.addView(Ui.space(this, 8));

        // ---- 错误卡（默认隐藏；附重试） ----
        errorCard = Ui.card(this);
        errorCard.setVisibility(View.GONE);
        hintView = Ui.text(this, "", 13f, Ui.WARN, false);
        hintView.setLineSpacing(Ui.dp(this, 2), 1f);
        errorCard.addView(hintView);
        errorCard.addView(Ui.space(this, 10));
        errorCard.addView(Ui.grid(this,
                Ui.button(this, "重试连接", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { startConnect(); }
                }),
                Ui.button(this, "打开手环 EV", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                })));
        root.addView(errorCard);

        root.addView(Ui.space(this, 6));

        // 顶栏：居中标题 + 右上角 ⊕ 圆钮（微信式下拉菜单：导出课程/导入课程/呼叫手环）
        android.widget.FrameLayout header = new android.widget.FrameLayout(this);
        TextView hTitle = Ui.textMedium(this, "Ev课程表", 16f, Ui.TEXT);
        hTitle.setGravity(android.view.Gravity.CENTER);
        header.addView(hTitle, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView plusBtn = new ImageView(this);
        plusBtn.setImageResource(R.drawable.ic_plus);
        plusBtn.setColorFilter(Ui.ACCENT);
        plusBtn.setBackground(Ui.round(Ui.ACCENT_LIGHT, 20, 0, this));
        plusBtn.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        plusBtn.setClickable(true);
        plusBtn.setContentDescription("更多操作");
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPlusMenu(v);
            }
        });
        android.widget.FrameLayout.LayoutParams pbP = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 36), Ui.dp(this, 36),
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        pbP.rightMargin = Ui.dp(this, 12);
        header.addView(plusBtn, pbP);
        root.addView(header, 0);
        setContentView(Ui.wrapWithBottomBar(this, root, 0));
        renderWeek();
    }

    private void buildHeader(LinearLayout root) {
        // 旧顶行（字母 E 头像 + 右上角闹钟入口）已按需求整行删除；
        // 右上角功能入口由顶栏的 ⊕ 圆钮承担（onCreate 里，微信式下拉菜单）
        root.addView(Ui.space(this, 10));

        // titleRow: greeting + page title + week switcher
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout greetingBlock = new LinearLayout(this);
        greetingBlock.setOrientation(LinearLayout.VERTICAL);
        greetingView = Ui.text(this, "Hi，同学", 12.5f, Ui.MUTED, false);
        greetingBlock.addView(greetingView);
        pageTitleView = Ui.textMedium(this, "本周课表", 26f, Ui.TEXT);
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
        prevBtn.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        prevBtn.setBackground(Ui.round(Ui.CARD2, 15, 0, this));
        prevBtn.setContentDescription("上一周");
        int btnSize = Ui.dp(this, 32);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset--; renderWeek(); }
        });
        weekNav.addView(prevBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        weekLabelView = Ui.text(this, "9.28-10.04", 12f, Ui.MUTED, false);
        weekLabelView.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        weekLabelView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset = 0; renderWeek(); }
        });
        weekNav.addView(weekLabelView);

        ImageView nextBtn = new ImageView(this);
        nextBtn.setImageResource(R.drawable.ic_chevron_right);
        nextBtn.setColorFilter(Ui.MUTED);
        nextBtn.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        nextBtn.setBackground(Ui.round(Ui.CARD2, 15, 0, this));
        nextBtn.setContentDescription("下一周");
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { weekOffset++; renderWeek(); }
        });
        weekNav.addView(nextBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        titleRow.addView(weekNav);
        root.addView(titleRow);
        root.addView(Ui.space(this, 12));
    }

    /** ⊕ 下拉菜单：白底圆角单框 + 纯文字行 + 细分隔线（简洁清晰，右缘对齐 ⊕，点外面收起）。 */
    private void showPlusMenu(View anchor) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.CARD, 12, Ui.LINE, this));
        int pad = Ui.dp(this, 6);
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
        p.leftMargin = Ui.dp(this, 12);
        p.rightMargin = Ui.dp(this, 12);
        v.setLayoutParams(p);
        return v;
    }

    /** 下拉菜单里的一行：只有文字，点击即执行。 */
    private void addMenuItem(LinearLayout panel, final android.widget.PopupWindow pw,
                             String label, final String transferMode) {
        TextView tx = Ui.text(this, label, 15f, Ui.TEXT, false);
        tx.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tx.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 28), 0);
        tx.setClickable(true);
        panel.addView(tx, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        tx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pw.dismiss();
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

    /** 呼叫手环：与设备卡「呼叫手环」按钮同一条 EV 指令（action=call）——
     *  手环响铃、震动、亮屏并弹通知提示，结果反馈在首页 mini 状态条上。
     *  蓝牙未连接时 call 发不出去 → 弹窗明确提醒，并提供一键去连接。 */
    private void callBand() {
        if (!SyncEngine.get(this).hasNode()) {
            // 共用美化弹窗：图标章 + 提示 + 去连接
            Dialogs.confirm(this, R.drawable.ic_unlink, 0,
                    "手环未连接",
                    "手环蓝牙还没有连接，无法呼叫。\n先到「手环」页连接手环？",
                    null, "去连接", false,
                    new Dialogs.Action() {
                        @Override public void run() {
                            startActivity(new Intent(HomeActivity.this, BandActivity.class));
                        }
                    });
            return;
        }
        quickSend("{\"action\":\"call\",\"text\":\"请查看手机\"}", "呼叫手环");
    }

    private void renderDateStrip() {
        if (dateStripView == null) {
            return;
        }
        dateStripView.removeAllViews();
        int today = CourseCache.todayIndex();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_MONTH, -today + weekOffset * 7);
        String[] weekLabels = {"一", "二", "三", "四", "五", "六", "日"};

        for (int d = 0; d < 7; d++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER);
            int cellH = Ui.dp(this, 62);
            boolean sel = (d == selectedDay);

            // 假期 / 调休角标：「休」= 放假，「班」= 调休补课
            java.util.Calendar dayCal = (java.util.Calendar) cal.clone();
            String badge = Holiday.badge(this, dayCal);
            String dayLabel = weekLabels[d] + (badge.length() > 0 ? " " + badge : "");

            // 文字水平居中（之前 Ui.text 默认靠左，视觉上歪）
            // badge（休/班）单独上主题色变体（暗底亮色/浅底深色），选中格保持纯白
            TextView wv = Ui.textMedium(this, dayLabel, 11f, sel ? 0xFFFFFFFF : Ui.MUTED);
            if (!sel && badge.length() > 0) {
                boolean darkNow = Ui.isDark();
                int badgeColor = "休".equals(badge)
                        ? (darkNow ? 0xFF7BD88F : 0xFF16A34A)
                        : (darkNow ? 0xFFFFC53D : 0xFFD97706);
                android.text.SpannableString sp = new android.text.SpannableString(dayLabel);
                sp.setSpan(new android.text.style.ForegroundColorSpan(badgeColor),
                        dayLabel.length() - badge.length(), dayLabel.length(),
                        android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                wv.setText(sp);
            }
            wv.setGravity(android.view.Gravity.CENTER);
            TextView dd = Ui.text(this, String.valueOf(cal.get(java.util.Calendar.DAY_OF_MONTH)),
                    15f, sel ? 0xFFFFFFFF : Ui.TEXT, true);
            dd.setGravity(android.view.Gravity.CENTER);
            dd.setPadding(0, Ui.dp(this, 1), 0, 0);

            if (sel) {
                cell.setBackground(Ui.round(Ui.ACCENT, 14, 0, this));
            } else {
                cell.setBackground(Ui.round(Ui.CARD, 14, 0, this));
                cell.setElevation(Ui.dp(this, 4));
            }
            cell.addView(wv);
            cell.addView(dd);

            final int dayIndex = d;
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedDay = dayIndex;
                    renderDateStrip();
                }
            });

            dateStripView.addView(cell, new LinearLayout.LayoutParams(0, cellH, 1f));
            if (d < 6) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 4),
                        LinearLayout.LayoutParams.MATCH_PARENT));
                dateStripView.addView(gap);
            }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
        }
    }

    /** 渲染当前激活课表的周视图（带时间轴） */
    private void renderWeek() {
        if (legacy || weekBox == null) {
            return;
        }
        // 周标签：按 weekOffset 推算周一~周日（点标签回到本周）
        java.util.Calendar mon = java.util.Calendar.getInstance();
        mon.add(java.util.Calendar.DAY_OF_MONTH, -CourseCache.todayIndex() + weekOffset * 7);
        java.util.Calendar sun = (java.util.Calendar) mon.clone();
        sun.add(java.util.Calendar.DAY_OF_MONTH, 6);
        if (weekLabelView != null) {
            weekLabelView.setText((mon.get(java.util.Calendar.MONTH) + 1) + "."
                    + mon.get(java.util.Calendar.DAY_OF_MONTH) + "-"
                    + (sun.get(java.util.Calendar.MONTH) + 1) + "."
                    + sun.get(java.util.Calendar.DAY_OF_MONTH));
        }
        renderDateStrip();

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

        // ── 假期 / 调休提示（移植自 EV 首页的祝福卡 + 调休提示条）──
        int overrideToday = Holiday.resolveToday(this);
        if (overrideToday == Holiday.HOLIDAY) {
            String name = Holiday.holidayName(this, java.util.Calendar.getInstance());
            TextView b = Ui.text(this, (name.length() > 0 ? name : "假期") + " · 今天休息，没有课",
                    12.5f, 0xFF166534, false);
            b.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            b.setBackground(Ui.round(0x2E22C55E, 10, 0, this));
            weekBox.addView(b);
            weekBox.addView(Ui.space(this, 8));
        } else if (overrideToday >= 0) {
            TextView b = Ui.text(this, "今天调休，按" + CourseCache.WEEK[overrideToday] + "课表",
                    12.5f, 0xFF92400E, false);
            b.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            b.setBackground(Ui.round(0x40FDE68A, 10, 0, this));
            weekBox.addView(b);
            weekBox.addView(Ui.space(this, 8));
        }

        ScheduleStore.Schedule s = ScheduleStore.active(this);
        if (s == null) {
            if (greetingView != null) {
                greetingView.setText("Hi，同学");
            }
            weekBox.addView(Ui.text(this, "连接手环后会自动同步课表", 12.5f, Ui.MUTED, false));
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
        TextView nameLine = Ui.textMedium(this, s.name, 13f, Ui.TEXT);
        nameLine.setCompoundDrawablePadding(Ui.dp(this, 6));
        // 图标 17dp 本征尺寸（ic_repeat.xml width/height），与 13sp 文字行高齐平
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
        TextView singleBtn = Ui.textMedium(this, "单", 12f, shortOn ? 0xFFFFFFFF : Ui.MUTED);
        singleBtn.setGravity(android.view.Gravity.CENTER);
        singleBtn.setBackground(shortOn
                ? Ui.round(Ui.ACCENT, 14, 0, this)
                : Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        singleBtn.setPadding(0, 0, 0, 0);
        singleBtn.setClickable(true);
        singleBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CourseCache.setShortNameMode(HomeActivity.this, !shortOn);
                renderWeek();
            }
        });
        // 与 ✏️ 完全同框：30×30 正方
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 30), Ui.dp(this, 30));
        slp.leftMargin = Ui.dp(this, 8);
        nameRow.addView(singleBtn, slp);
        // ✏️ 编辑：进入可视化编辑态（点课表名 → 课程表管理仍是管理入口）；与[单]同高成组
        ImageView editBtn = new ImageView(this);
        editBtn.setImageResource(R.drawable.ic_pencil);
        editBtn.setColorFilter(Ui.ACCENT);
        editBtn.setBackground(Ui.round(Ui.CARD2, 14, Ui.LINE, this));
        editBtn.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
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
                Ui.dp(this, 30), Ui.dp(this, 30));
        elp.leftMargin = Ui.dp(this, 6);
        nameRow.addView(editBtn, elp);
        weekBox.addView(nameRow);
        weekBox.addView(Ui.space(this, 8));
        weekBox.addView(weekGridWithTime(s.courses));
        // note 信息去重：sync 课表"最后同步"与"更新于"是同一件事，只说一遍
        String note;
        if (s.isSync()) {
            note = "来自手环 · " + s.courses.size() + " 门课 · 最后同步 "
                    + CourseCache.ago(s.syncedAt);
        } else {
            note = "仅本机 · " + s.courses.size() + " 门课 · 更新于 "
                    + CourseCache.ago(s.createdAt);
        }
        TextView n = Ui.text(this, note, 11.5f, Ui.MUTED, false);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
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
        if (timeSlots.isEmpty()) {
            timeSlots.add("全");
        }

        // wrap in elevated card
        LinearLayout wrapper = Ui.cardElevated(this);
        wrapper.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));

        // header row: empty + day labels
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        // time column spacer in header
        TextView timeSpacer = new TextView(this);
        timeSpacer.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 33),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        headerRow.addView(timeSpacer);

        // 每列对应的日期（含 weekOffset），用于假期 / 调休判定
        java.util.Calendar gridMon = java.util.Calendar.getInstance();
        gridMon.add(java.util.Calendar.DAY_OF_MONTH, -CourseCache.todayIndex() + weekOffset * 7);

        boolean thisWeek = (weekOffset == 0);
        for (int d = 0; d < 7; d++) {
            boolean isToday = thisWeek && d == today;
            java.util.Calendar day = (java.util.Calendar) gridMon.clone();
            day.add(java.util.Calendar.DAY_OF_MONTH, d);
            String badge = Holiday.badge(this, day);
            String head = heads[d] + badge;
            // 休/班 badge：深浅主题各给可读变体（暗底暗字看不清 → 暗底用亮色）
            boolean darkNow = Ui.isDark();
            int holidayColor = darkNow ? 0xFF7BD88F : 0xFF16A34A;
            int workdayColor = darkNow ? 0xFFFFC53D : 0xFFD97706;
            int headColor = isToday ? 0xFFFFFFFF
                    : ("休".equals(badge) ? holidayColor
                        : ("班".equals(badge) ? workdayColor : Ui.MUTED));
            TextView hl = Ui.textMedium(this, head, 10.5f, headColor);
            hl.setGravity(android.view.Gravity.CENTER);
            hl.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            if (isToday) {
                // 今天列头：主色药丸（「今天」唯一主表达；列蒙层已移除避免重复强调）
                hl.setBackground(Ui.round(Ui.ACCENT, 12, 0, this));
            }
            headerRow.addView(hl, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        wrapper.addView(headerRow);
        wrapper.addView(Ui.space(this, 4));

        // body: time col + 7 day cols
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // time column（宽 34 / 行高 44 与课程块对齐 / 字号 10）
        LinearLayout timeCol = new LinearLayout(this);
        timeCol.setOrientation(LinearLayout.VERTICAL);
        for (String ts : timeSlots) {
            TextView tv = Ui.text(this, ts, 10f, Ui.MUTED, false);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 34),
                    Ui.dp(this, 44)));
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
                dayCol.setBackground(Ui.round(Ui.isDark() ? 0x142FBF6A : 0x1422C55E, 6, 0, this));
                dayCol.setGravity(android.view.Gravity.CENTER);
                dayCol.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
                TextView hn = Ui.textMedium(this, Holiday.holidayName(this, day),
                        11.5f, Ui.isDark() ? 0xFF7BD88F : 0xFF166534);
                hn.setGravity(android.view.Gravity.CENTER);
                int colH = Ui.dp(this, 44) * Math.max(1, timeSlots.size());
                hn.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, colH));
                dayCol.addView(hn);
                body.addView(dayCol, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                continue;
            }

            dayCol.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));

            // 调休列按目标星期几取课
            java.util.List<CourseCache.Course> dayList = CourseCache.coursesOfDay(all, override >= 0 ? override : d);

            java.util.Map<String, CourseCache.Course> map = new java.util.LinkedHashMap<>();
            for (CourseCache.Course c : dayList) {
                String key = CourseCache.shortTime(c.time);
                map.put(key, c);
            }

            for (String ts : timeSlots) {
                CourseCache.Course c = map.get(ts);
                if (c != null) {
                    // 当前节：本周 + 今天列 + 现在时刻落在该课时间区间内
                    boolean current = false;
                    if (thisWeek && d == today) {
                        int[] m = CourseCache.minutes(c.time);
                        if (m != null) {
                            int now = CourseCache.nowMinutes();
                            current = now >= m[0] && now < m[1];
                        }
                    }
                    dayCol.addView(courseBlockCompact(c, current));
                } else {
                    // empty slot
                    View empty = new View(this);
                    empty.setBackground(Ui.round(0x00000000, 5, 0, this));
                    empty.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
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
     * 课程小色块（周视图网格用，固定高度 44dp 双行：课名 + 教室）。
     * 字色按底色亮度自动选深/白（Ui.onCourseColor），单字模式放大课名。
     * current=true（今天列里正在上的课）加 2dp ACCENT 描边。
     */
    private View courseBlockCompact(CourseCache.Course c, boolean current) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        int bg = Ui.courseColor(c.name);
        b.setBackground(current
                ? Ui.round(bg, 10, Ui.ACCENT, 2, this)
                : Ui.round(bg, 10, 0, this));
        b.setPadding(Ui.dp(this, 1), Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2)); // 四向留缝
        b.setLayoutParams(lp);
        int fg = Ui.onCourseColor(bg);
        String disp = CourseCache.displayName(c.name, CourseCache.shortNameMode(this));
        TextView tv = Ui.textMedium(this, disp, disp.length() <= 1 ? 16f : 10f, fg);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setLineSpacing(Ui.dp(this, 1), 1f);
        b.addView(tv);
        if (!shortModeHere() && c.location != null && c.location.length() > 0) {
            TextView loc = Ui.text(this, ellip6(c.location), 9f,
                    (fg & 0x00FFFFFF) | 0xC8000000, false);
            loc.setGravity(android.view.Gravity.CENTER);
            loc.setSingleLine(true);
            b.addView(loc);
        }
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCourseDetail(c); }
        });
        return b;
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

    /** 呼叫手环 / 上课了 / 下课了：发 EV 消息 + 尽力推一条手表通知 */
    private void quickSend(final String json, final String label) {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            miniStatus("手环未连接，无法" + label, Ui.WARN);
            return;
        }
        miniStatus("正在发送「" + label + "」…", Ui.ACCENT);
        e.send(json, new SyncEngine.Reply() {
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
                miniStatus("手环无回应（" + label + "）", Ui.WARN);
            }
            @Override public void onError(String msg) {
                miniStatus(label + "发送失败：" + msg, Ui.ERR);
            }
        });
    }

    /** 快速留言：弹输入框，写队列并尝试立即送达（与留言页同一份存储） */
    private void quickMessage() {
        final EditText input = new EditText(this);
        input.setHint("写一条留言给手环…");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        new AlertDialog.Builder(this)
                .setTitle("快速留言")
                .setView(input)
                .setPositiveButton("发送", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String text = input.getText().toString().trim();
                        if (text.length() == 0) {
                            return;
                        }
                        MessageActivity.enqueueOutgoing(HomeActivity.this, text);
                        miniStatus("留言已发送（未连接时会在连上后自动补发）", Ui.OK);
                    }
                })
                .setNeutralButton("打开留言页", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(HomeActivity.this, MessageActivity.class));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 手动同步：已连接 → 重新拉课表；未连接 → 走完整连接流程 */
    private void manualSync() {
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            miniStatus("正在同步课表…", Ui.ACCENT);
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
                Ui.button(this, "重试连接", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { startConnect(); }
                }),
                Ui.button(this, "打开手环 EV", false, new View.OnClickListener() {
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
            {"初始化穿戴服务", "查找已连接设备", "申请设备权限", "连接 EV 课程表"};

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
            connectingLabel = "已连接，正在同步课表…";
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
                    fail("读取资料失败：回包无法解析");
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
                renderWeek();
            }
            @Override public void onTimeout(String hint) {
                ScheduleStore.upsertFromWatch(HomeActivity.this,
                        SyncEngine.get(HomeActivity.this).currentDeviceId(),
                        SyncEngine.get(HomeActivity.this).currentDeviceName(), "", sch);
                renderWeek();
            }
            @Override public void onError(String msg) {
                ScheduleStore.upsertFromWatch(HomeActivity.this,
                        SyncEngine.get(HomeActivity.this).currentDeviceId(),
                        SyncEngine.get(HomeActivity.this).currentDeviceName(), "", sch);
                renderWeek();
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
                        miniStatus("手环回包里没课表数据", Ui.WARN);
                        return;
                    }
                    SyncEngine.get(HomeActivity.this).lastExportJson = json;
                    syncScheduleToStore(sch);
                    miniStatus("● 课表已同步 ✓", Ui.OK);
                } catch (Throwable t) {
                    miniStatus("同步失败：回包无法解析", Ui.ERR);
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
            statusView.setText("已连接 " + e.deviceName + "  ·  " + e.versionName
                    + " (code " + e.versionCode + ")");
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
            miniStatus("● 已连接 " + e.deviceName + "  ·  v" + e.versionName
                    + "  ·  " + sets, Ui.OK);
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
        }
        hintView.setText(hint);
        errorCard.setVisibility(View.VISIBLE);
        // 状态只说一遍：错误卡（带重试按钮）在场时，隐藏迷你条
        if (miniBarView != null) {
            miniBarView.setVisibility(View.GONE);
        }
        // 连不上也保持课表在屏（默认本地课表兜底），只是迷你条提示未连接
    }

    private void miniStatus(String s, int color) {
        if (miniStatusView == null) {
            return;
        }
        miniStatusView.setText(s);
        miniStatusView.setTextColor(color);
        if (miniBarView != null) {
            miniBarView.setVisibility(View.VISIBLE);
        }
    }

    /** 迷你条按引擎状态刷新（状态回调驱动；与 ConnectionBar 同一数据源）。 */
    private void refreshMiniFromEngine() {
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            int n = e.bandScheduleCount;
            String sets = (n >= 0) ? ("手环课表 " + n + " 套") : ("课表 " + e.courseCount + " 节");
            if (miniStatusView != null && miniStatusView.getText().toString().contains("已连接")) {
                miniStatus("● 已连接 " + e.deviceName + "  ·  v" + e.versionName + "  ·  " + sets, Ui.OK);
            }
        } else if (e.autoRetryRunning()) {
            String p = e.connectProgress();
            miniStatus("● " + (p.length() > 0 ? p : "重连中…"), Ui.ACCENT);
        }
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
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, "/apk/home");
        // 首页也接管「手环主动消息」：只要 App 在前台，留言就能被提醒（按 id 去重）
        MessageActivity.installObserver(this);
        installNodeChooser();
        if (!legacy) {
            // 从课程表管理页切换回来 → 刷新周视图
            renderWeek();
            return;
        }
        SyncEngine e = SyncEngine.get(this);
        if (e.connected() && phase == PHASE_DONE && e.nickname.length() > 0
                && welcomeView != null) {
            welcomeView.setText("欢迎，" + e.nickname + "！");
        }
    }
}