package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 首页：
 *   1. 进来就自动连接手环（4 步，带进度与预计剩余时间）
 *   2. 连上后自动读取昵称与版本号，显示「欢迎，XXX！」
 *   3. 失败时给出可执行的指引（打开 EV / 检查小米运动健康）
 *   4. 提供 导入 / 导出 / 设置 / 调试 四个入口
 */
public class HomeActivity extends Activity {

    private static final int PHASE_CONNECT = 1, PHASE_PROFILE = 2, PHASE_DONE = 3, PHASE_ERR = 4;
    private static final String[] STEP_LABELS =
            {"初始化穿戴服务", "查找已连接设备", "申请设备权限", "连接 EV 课程表"};

    private TextView welcomeView, statusView, estimateView, hintView;
    private LinearLayout stepsView, errorCard, actionsView;
    /** 首屏课表卡（阶段 0：本地只读缓存渲染；手上没连也能显示上次的数据） */
    private LinearLayout timetableCard;
    /** false = 只看今天，true = 整周 */
    private boolean showWeek = false;
    private final TextView[] stepRows = new TextView[4];
    private final int[] states = new int[4];
    private int phase = PHASE_CONNECT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean ticking = false;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) {
                return;
            }
            int left = estimateSeconds();
            if (left > 0) {
                estimateView.setText("预计还需 ~" + left + " 秒");
                ui.postDelayed(this, 1000);
            } else {
                estimateView.setText("马上就好…");
                ui.postDelayed(this, 1000);
            }
        }
    };

    private static final int REQ_NOTIF = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        // 常驻前台服务：进程活着才能在后台收到手环推来的留言（可在设置页关闭）
        SyncService.startIfEnabled(this);
        requestNotifPermission();
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
            new android.app.AlertDialog.Builder(this)
                    .setTitle("发现 " + devices.size() + " 台已连接设备，请选择")
                    .setSingleChoiceItems(names, checked,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int which) {
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

    // ======================= UI =======================

    private void buildUi() {
        LinearLayout root = Ui.screen(this);

        root.addView(Ui.title(this, "EV 课程表"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "安卓同步器 v" + version(), 12f, Ui.MUTED, false));
        root.addView(Ui.space(this, 14));

        // 欢迎卡
        LinearLayout welcome = Ui.card(this);
        welcomeView = Ui.text(this, "欢迎！", 22f, Ui.TEXT, true);
        welcome.addView(welcomeView);
        statusView = Ui.text(this, "正在连接手环…", 12.5f, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 4), 0, 0);
        welcome.addView(statusView);
        root.addView(welcome);
        root.addView(Ui.space(this, 10));

        // 课表卡（阶段 0：由本地只读缓存渲染 —— 只有 EV 变体才有 schedule 域）
        timetableCard = Ui.card(this);
        timetableCard.setVisibility(View.GONE);
        root.addView(timetableCard);
        root.addView(Ui.space(this, 10));
        if (Variant.isEv(this) && CourseCache.savedAt(this) > 0) {
            // 进来先给看上次的课表，连接成功后会被刷新
            renderTimetable(true);
        }

        // 进度卡
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

        // 错误卡（默认隐藏）
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

        // 功能入口
        actionsView = new LinearLayout(this);
        actionsView.setOrientation(LinearLayout.VERTICAL);
        // 「导入/导出课程表」是 EV 课程表专属（EvBox 工具箱没有 schedule 域）
        if (Variant.isEv(this)) {
            actionsView.addView(Ui.grid(this,
                    Ui.button(this, "导入课程表", true, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(TransferActivity.MODE_IMPORT); }
                    }),
                    Ui.button(this, "导出课程表", true, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(TransferActivity.MODE_EXPORT); }
                    })));
        }
        actionsView.addView(Ui.grid(this,
                Ui.button(this, "设置（昵称）", false, new View.OnClickListener() {
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

    // ======================= 流程 =======================

    private void startConnect() {
        phase = PHASE_CONNECT;
        for (int i = 0; i < states.length; i++) {
            states[i] = SyncEngine.PENDING;
        }
        errorCard.setVisibility(View.GONE);
        actionsView.setVisibility(View.GONE);
        welcomeView.setText("欢迎！");
        statusView.setText("正在连接手环…");
        for (int i = 0; i < 4; i++) {
            stepRows[i].setText(stepLine(i, SyncEngine.PENDING, ""));
            stepRows[i].setTextColor(stepColor(SyncEngine.PENDING));
        }
        startTicking();

        SyncEngine.get(this).connect(new SyncEngine.Steps() {
            @Override public void onUpdate(String[] labels, int[] st, String[] details) {
                for (int i = 0; i < 4; i++) {
                    states[i] = st[i];
                    stepRows[i].setText(stepLine(i, st[i], details[i]));
                    stepRows[i].setTextColor(stepColor(st[i]));
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

    /** 连上后再拉一次 export，用于取昵称 / 版本号 / 课表统计 */
    private void loadProfile() {
        phase = PHASE_PROFILE;
        statusView.setText("已连接，正在读取资料…");
        estimateView.setText("预计还需 ~2 秒");
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
                        // 阶段 0：本次读到的课表存一份本地只读缓存（供首屏 / 后续插件与提醒使用）
                        if (Variant.isEv(HomeActivity.this)) {
                            CourseCache.save(HomeActivity.this, sch, "");
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

    private void done() {
        phase = PHASE_DONE;
        stopTicking();
        SyncEngine e = SyncEngine.get(this);
        welcomeView.setText("欢迎，" + (e.nickname.length() > 0 ? e.nickname : "同学") + "！");
        statusView.setText("已连接 " + e.deviceName + "  ·  " + e.versionName
                + " (code " + e.versionCode + ")"
                + (Variant.isEv(this) ? ("  ·  课表 " + e.courseCount + " 节") : ""));
        statusView.setTextColor(Ui.OK);
        estimateView.setText("连接完成");
        estimateView.setTextColor(Ui.OK);
        errorCard.setVisibility(View.GONE);
        actionsView.setVisibility(View.VISIBLE);
        if (Variant.isEv(this)) {
            renderTimetable(false);
        }
    }

    private void fail(String hint) {
        phase = PHASE_ERR;
        stopTicking();
        estimateView.setText("连接未完成");
        estimateView.setTextColor(Ui.ERR);
        statusView.setText("未连接");
        statusView.setTextColor(Ui.ERR);
        hintView.setText(hint);
        errorCard.setVisibility(View.VISIBLE);
        actionsView.setVisibility(View.VISIBLE);
        // 连不上也把上次缓存的课表显示出来（脚注标明陈旧），总比一片空白有用
        if (Variant.isEv(this) && CourseCache.savedAt(this) > 0) {
            renderTimetable(true);
        }
    }

    // ======================= 首屏课表（阶段 0：只读） =======================

    /**
     * 渲染课表卡。数据取自本地缓存，**手环仍是唯一真源**，手机端不可编辑。
     *
     * @param stale true = 这份数据不是本次刚拉到的（未连接 / 正在连接），脚注需标明
     */
    private void renderTimetable(boolean stale) {
        if (timetableCard == null) {
            return;
        }
        timetableCard.removeAllViews();

        List<CourseCache.Course> all = CourseCache.load(this);
        String head;
        List<CourseCache.Course> todayList = null;
        if (showWeek) {
            head = "本周课表";
        } else {
            int today = CourseCache.todayIndex();
            todayList = CourseCache.coursesOfDay(all, today);
            head = "今日课程　" + CourseCache.WEEK[today];
        }

        // 标题 + 今日/本周切换
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.addView(Ui.text(this, head, 12.5f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(Ui.button(this, showWeek ? "只看今天" : "查看本周", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showWeek = !showWeek;
                        renderTimetable(phase != PHASE_DONE);
                    }
                }));
        timetableCard.addView(headRow);
        timetableCard.addView(Ui.space(this, 8));

        if (all.isEmpty()) {
            timetableCard.addView(Ui.text(this, "还没有课表数据，连接手环后会自动显示",
                    12.5f, Ui.MUTED, false));
        } else if (showWeek) {
            timetableCard.addView(weekGrid(all));
        } else if (todayList.isEmpty()) {
            timetableCard.addView(Ui.text(this, "今天没有课", 13f, Ui.MUTED, false));
        } else {
            for (int i = 0; i < todayList.size(); i++) {
                if (i > 0) {
                    timetableCard.addView(Ui.space(this, 6));
                }
                timetableCard.addView(courseRow(todayList.get(i), false));
            }
        }

        String note = footNote(stale);
        if (note.length() > 0) {
            timetableCard.addView(Ui.space(this, 8));
            timetableCard.addView(Ui.mono(this, note));
        }
        timetableCard.setVisibility(View.VISIBLE);
    }

    private String footNote(boolean stale) {
        long at = CourseCache.savedAt(this);
        if (at <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String n = CourseCache.scheduleName(this);
        if (n.length() > 0) {
            sb.append(n).append("　·　");
        }
        sb.append("更新于 ").append(CourseCache.ago(at));
        if (stale) {
            sb.append("　（未连接，显示的是上次的数据）");
        }
        return sb.toString();
    }

    private View courseRow(CourseCache.Course c, boolean withDay) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        row.setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, this));

        StringBuilder first = new StringBuilder();
        if (withDay && c.day >= 0) {
            first.append(c.dayLabel()).append("　");
        }
        first.append(c.time);
        if (c.name.length() > 0) {
            first.append("　").append(c.name);
        }
        row.addView(Ui.text(this, first.toString(), 13f, Ui.TEXT, true));

        String sub = c.sub();
        if (sub.length() > 0) {
            TextView t = Ui.text(this, sub, 11f, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(this, 2), 0, 0);
            row.addView(t);
        }
        return row;
    }

    /**
     * 周视图：7 列（一~日），每列纵向堆当天的课。
     * 色块底色 = 课程区分色（按课程名 hash 稳定分配），今天列标题高亮。
     */
    private View weekGrid(List<CourseCache.Course> all) {
        int today = CourseCache.todayIndex();
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        String[] heads = {"一", "二", "三", "四", "五", "六", "日"};
        for (int d = 0; d < 7; d++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView h = Ui.text(this, heads[d], 10f,
                    d == today ? Ui.ACCENT : Ui.MUTED, d == today);
            h.setGravity(android.view.Gravity.CENTER);
            col.addView(h);
            col.addView(Ui.space(this, 4));
            List<CourseCache.Course> day = CourseCache.coursesOfDay(all, d);
            if (day.isEmpty()) {
                TextView empty = Ui.text(this, "—", 10f, Ui.MUTED, false);
                empty.setGravity(android.view.Gravity.CENTER);
                empty.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                col.addView(empty);
            } else {
                for (int i = 0; i < day.size(); i++) {
                    if (i > 0) {
                        col.addView(Ui.space(this, 3));
                    }
                    col.addView(courseBlock(day.get(i)));
                }
            }
            grid.addView(col, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return grid;
    }

    /** 单门课的小色块（周视图用） */
    private View courseBlock(CourseCache.Course c) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Ui.round(Ui.courseColor(c.name), 7, 0, this));
        b.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));
        b.addView(Ui.text(this, shortTime(c.time), 8f, 0xB3FFFFFF, false));
        b.addView(Ui.text(this, c.name, 10f, 0xFFFFFFFF, true));
        if (c.location.length() > 0) {
            b.addView(Ui.text(this, c.location, 8f, 0xB3FFFFFF, false));
        }
        return b;
    }

    /** "08:00 - 08:45" → "08:00"（窄列里放不下完整区间） */
    private static String shortTime(String t) {
        if (t == null) {
            return "";
        }
        int i = t.indexOf('-');
        String s = (i > 0 ? t.substring(0, i) : t).trim();
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    private void launchEv() {
        SyncEngine.get(this).launchEv(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                statusView.setText((ok ? "已请求拉起" : "拉起失败") + "：" + msg);
                statusView.setTextColor(ok ? Ui.OK : Ui.ERR);
            }
        });
    }

    private void open(String mode) {
        Intent i = new Intent(this, TransferActivity.class);
        i.putExtra(TransferActivity.EXTRA_MODE, mode);
        startActivity(i);
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
        Analytics.pageView(this, "/apk/home");
        // 首页也接管「手环主动消息」：只要 App 在前台，留言就能被提醒（按 id 去重）
        MessageActivity.installObserver(this);
        installNodeChooser();
        SyncEngine e = SyncEngine.get(this);
        if (e.connected() && phase == PHASE_DONE && e.nickname.length() > 0) {
            welcomeView.setText("欢迎，" + e.nickname + "！");
        }
    }
}