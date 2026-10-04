package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置页（App 级）—— 2026-10-05 按《设置页全新设计方案 v1》重做视图层。
 *
 * <p>结构：3 个分组（提醒 / 外观 / 支持与关于），每组一张白卡；行 = 左标题(+副文案) + 右控件
 * (Switch / 值 + chevron)。**不再使用 {@code Ui.row} 灰砖**（灰砖底色 CARD2 与页面画布 CANVAS
 * 逐通道只差 (1,6,9)，卡片边界事实上消失）。
 *
 * <p>反馈就地化：删掉原来钉在页面顶端的 {@code resultView}——瞬时动作（测试/重排提醒）由
 * **按钮自身变成「已完成 ✓」**，持续问题（缺权限 / 缺省电白名单）用**琥珀副文案贴在该行**。
 *
 * <p>零新增 token：色 / 字号 / 圆角 / 间距全部取自 {@link Ui} 既有档位。
 */
public class SettingsActivity extends Activity {

    /** 程序化 setChecked 时置 true，避免触发 onCheckedChanged 造成循环 */
    private boolean switching;
    private int lastThemeVersion = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ===== 提醒组 =====
    private TextView remindSub, bgSub, holidaySub, leadValue;
    private LinearLayout remindSubArea;
    private android.widget.Switch remindSwitch, pushSwitch, bgSwitch, holidaySwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "设置"));

        // ==================== 分组 1 · 提醒 ====================
        root.addView(Ui.space(this, 12));
        root.addView(groupTitle("提醒"));
        LinearLayout remindCard = settingCard();

        // —— 上课提醒：开关行 + 展开子区 ——
        remindSwitch = new android.widget.Switch(this);
        remindSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        Reminders.setEnabled(SettingsActivity.this, on);
                        refreshRemind();
                    }
                });
        remindSub = subLabel("");
        remindCard.addView(settingRow("上课提醒", remindSub, remindSwitch, null));

        // 子区（开关打开才有意义）：提前时间 / 推送到手环 / 测试·重排
        remindSubArea = new LinearLayout(this);
        remindSubArea.setOrientation(LinearLayout.VERTICAL);
        remindSubArea.setBackgroundColor(Ui.CARD2);

        leadValue = valueLabel("");
        addTopLine(remindSubArea, 26);
        remindSubArea.addView(subRow("提前时间", valueWithChevron(leadValue),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickLeadMinutes();
                    }
                }));

        pushSwitch = new android.widget.Switch(this);
        pushSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        Reminders.setPushWatch(SettingsActivity.this, on);
                        refreshRemind();
                    }
                });
        addTopLine(remindSubArea, 26);
        remindSubArea.addView(subRow("推送到手环", pushSwitch, null));

        addTopLine(remindSubArea, 26);
        remindSubArea.addView(linkRow());
        remindCard.addView(remindSubArea);

        // —— 后台常驻提醒 ——
        addTopLine(remindCard, 14);
        bgSwitch = new android.widget.Switch(this);
        bgSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        applyBg(on);
                    }
                });
        bgSub = subLabel("");
        remindCard.addView(settingRow("后台常驻提醒", bgSub, bgSwitch, null));

        // —— 假期 / 调休 ——
        addTopLine(remindCard, 14);
        holidaySwitch = new android.widget.Switch(this);
        holidaySwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                        if (switching) {
                            return;
                        }
                        Holiday.setEnabled(SettingsActivity.this, on);
                        refreshHoliday();
                        // 插件上的假期/调休展示也要跟着变
                        TodayWidgetProvider.refreshAll(SettingsActivity.this);
                        NextWidgetProvider.refreshAll(SettingsActivity.this);
                        WeekWidgetProvider.refreshAll(SettingsActivity.this);
                    }
                });
        holidaySub = subLabel("");
        remindCard.addView(settingRow("假期 / 调休", holidaySub, holidaySwitch, null));

        root.addView(remindCard);

        // ==================== 分组 2 · 外观 ====================
        root.addView(Ui.space(this, 16));
        root.addView(groupTitle("外观"));
        LinearLayout lookCard = settingCard();
        lookCard.addView(settingRow("主题外观", null, valueWithChevron(valueLabel(themeLabel())),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, ThemePickerActivity.class));
                    }
                }));
        root.addView(lookCard);

        // ==================== 分组 3 · 支持与关于 ====================
        root.addView(Ui.space(this, 16));
        root.addView(groupTitle("支持与关于"));
        LinearLayout helpCard = settingCard();
        addRow(helpCard, settingRow("帮助与反馈", subLabel("常见问题 · QQ 群 · 提交截图反馈"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openFeedbackPage();
                    }
                }));
        addRow(helpCard, settingRow("检查更新", null, chevronOnly(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpdateChecker.checkManual(SettingsActivity.this);
            }
        }));
        addRow(helpCard, settingRow("打赏支持", subLabel("爱发电 / 微信 / 支付宝"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DonateActivity.class));
                    }
                }));
        // 「高级版一键激活」依赖 EV 的 activate 动作（EvBox 工具箱暂无此动作）
        if (Variant.isEv(this)) {
            addRow(helpCard, settingRow("高级版", subLabel("4 位兑换码一键激活"),
                    chevronOnly(), new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            startActivity(new Intent(SettingsActivity.this, FastActivateActivity.class));
                        }
                    }));
        }
        addRow(helpCard, settingRow("调试", subLabel("连不上手环时分步排查"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DebugActivity.class));
                    }
                }));
        addRow(helpCard, settingRow("Ev 课程表", null, valueLabel("v" + version()), null));
        root.addView(helpCard);

        root.addView(Ui.space(this, 12));

        refreshBg();
        refreshRemind();
        refreshHoliday();
        setContentView(Ui.wrapWithBottomBar(this, root, 3));
        Analytics.pageView(this, "/apk/settings");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
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

    /** 帮助与反馈：浏览器打开带参反馈页（src/版本/机型/渠道，服务端据此定位问题） */
    private void openFeedbackPage() {
        try {
            String ch = Variant.isEv(this) ? "ev-apk" : "evbox-apk";
            String url = "https://app-auth.gudq.com/feedback.html"
                    + "?src=app"
                    + "&v=" + java.net.URLEncoder.encode(version(), "UTF-8")
                    + "&m=" + java.net.URLEncoder.encode(android.os.Build.MODEL, "UTF-8")
                    + "&ch=" + ch;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            // 无浏览器等极端情况：退化为打开站点首页
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://app-auth.gudq.com/")));
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================================================================
    // 视图构件（全部复用 Ui 既有 token，不新增字号/色/圆角/间距档）
    // ==================================================================

    /** 分组标题：11.5sp / 600 MUTED（复用 SP_CAPTION） */
    private TextView groupTitle(String s) {
        TextView t = Ui.textMedium(this, s, Ui.SP_CAPTION, Ui.MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 7);
        t.setLayoutParams(lp);
        return t;
    }

    /** 分区卡：CARD 白底 + 1dp LINE 描边 + 圆角 R_CARD（不含内边距，行自己带） */
    private LinearLayout settingCard() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        return l;
    }

    /** 1dp 分隔线，左侧缩进 leftDp（14=行内缩进 / 26=子行缩进） */
    private void addTopLine(LinearLayout parent, int leftDp) {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        lp.leftMargin = Ui.dp(this, leftDp);
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    /** 往卡里加一行：非首行自动补一条分隔线 */
    private void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            addTopLine(card, 14);
        }
        card.addView(row);
    }

    /**
     * 通用设置行：左标题(+副文案，副文案需外部持有引用以便刷新) + 右控件。
     * click 为 null 表示不可点。
     */
    private LinearLayout settingRow(String title, TextView subView, View right,
                                    View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 52));
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(Ui.textMedium(this, title, Ui.SP_BODY, Ui.TEXT));
        if (subView != null) {
            subView.setPadding(0, Ui.dp(this, 1), 0, 0);
            left.addView(subView);
        }
        r.addView(left);
        if (right != null) {
            r.addView(right);
        }
        if (click != null) {
            r.setOnClickListener(click);
        }
        return r;
    }

    /** 展开子区的子行（46dp，左缩进 26dp） */
    private LinearLayout subRow(String key, View right, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 46));
        r.setPadding(Ui.dp(this, 26), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        TextView t = Ui.text(this, key, 12.5f, Ui.TEXT, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(t);
        if (right != null) {
            r.addView(right);
        }
        if (click != null) {
            r.setOnClickListener(click);
        }
        return r;
    }

    /** 低频动作文字链行（测试提醒 · 重排提醒），高 44dp 满足热区 */
    private LinearLayout linkRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 44));
        r.setPadding(Ui.dp(this, 26), 0, Ui.dp(this, 14), 0);

        TextView test = linkText("测试提醒");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.test(SettingsActivity.this);
                flashLink((TextView) v, "已发测试提醒 ✓");
            }
        });
        TextView resched = linkText("重排提醒");
        resched.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.reschedule(SettingsActivity.this);
                flashLink((TextView) v, "已重排 ✓");
            }
        });

        r.addView(test);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 20);
        r.addView(resched, lp);
        return r;
    }

    private TextView linkText(String s) {
        TextView t = Ui.textMedium(this, s, 12.5f, Ui.ACCENT);
        // 撑到 ≥44dp 热区（文字本身只占 ~17dp）
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        return t;
    }

    /** 按钮自反馈：2.5s 内变「已完成 ✓」（OK 色），之后复原 */
    private void flashLink(final TextView t, final CharSequence done) {
        if (!t.isEnabled()) {
            return;
        }
        final CharSequence orig = t.getText();
        t.setText(done);
        t.setTextColor(Ui.OK);
        t.setEnabled(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                t.setText(orig);
                t.setTextColor(Ui.ACCENT);
                t.setEnabled(true);
            }
        }, 2500);
    }

    /** 副文案（11.5sp MUTED），文本由 refresh* 更新 */
    private TextView subLabel(String s) {
        return Ui.text(this, s, Ui.SP_CAPTION, Ui.MUTED, false);
    }

    /** 右值文案（12.5sp MUTED） */
    private TextView valueLabel(String s) {
        return Ui.text(this, s, 12.5f, Ui.MUTED, false);
    }

    /** 「右值 + chevron」组合 */
    private LinearLayout valueWithChevron(TextView value) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        if (value != null) {
            h.addView(value);
        }
        h.addView(chevron(), chevronParams(5));
        return h;
    }

    /** 仅 chevron（可点行用） */
    private LinearLayout chevronOnly() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(chevron());
        return h;
    }

    private ImageView chevron() {
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_chevron_right);
        ic.setColorFilter(Ui.MUTED);
        return ic;
    }

    private LinearLayout.LayoutParams chevronParams(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 14), Ui.dp(this, 14));
        lp.leftMargin = Ui.dp(this, leftDp);
        return lp;
    }

    /** 当前主题名（跟随手环 / 本地主题名 / 跟随系统） */
    private String themeLabel() {
        try {
            if (WatchAppearance.followEnabled(this)) {
                return "跟随手环";
            }
            String id = WatchAppearance.localTheme(this);
            if (id != null && id.length() > 0) {
                String n = WatchAppearance.themeName(id);
                if (n != null && n.length() > 0) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return "跟随系统";
    }

    /** 提前时间：单选弹层（选项直接复用 Reminders.LEAD_STEPS，逻辑零改动） */
    private void pickLeadMinutes() {
        final int[] steps = Reminders.LEAD_STEPS;
        int cur = Reminders.leadMinutes(this);
        int checked = 0;
        final String[] items = new String[steps.length];
        for (int i = 0; i < steps.length; i++) {
            items[i] = steps[i] + " 分钟";
            if (steps[i] == cur) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("提前时间")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        Reminders.setLeadMinutes(SettingsActivity.this, steps[which]);
                        d.dismiss();
                        refreshRemind();
                    }
                })
                .show();
    }

    // ==================================================================
    // 状态刷新（副文案只说「补充信息」，不再复述「已开启 / 已关闭」）
    // ==================================================================

    private void refreshRemind() {
        boolean on = Reminders.enabled(this);
        boolean push = Reminders.pushWatch(this);
        int lead = Reminders.leadMinutes(this);

        remindSubArea.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            remindSub.setText("到点用系统闹钟提醒你");
            remindSub.setTextColor(Ui.MUTED);
        } else if (!Reminders.exactAllowed(this)) {
            remindSub.setText("未授予「闹钟和提醒」权限，可能有 ±1 分钟误差");
            remindSub.setTextColor(Ui.WARN);
        } else {
            remindSub.setText("提前 " + lead + " 分钟 · 手机通知" + (push ? " + 手环" : ""));
            remindSub.setTextColor(Ui.MUTED);
        }
        leadValue.setText(lead + " 分钟");

        switching = true;
        remindSwitch.setChecked(on);
        pushSwitch.setChecked(push);
        switching = false;
    }

    private void refreshBg() {
        boolean on = SyncService.enabled(this);
        if (!on) {
            bgSub.setText("仅 App 打开时提醒新留言");
            bgSub.setTextColor(Ui.MUTED);
        } else if (!isIgnoringBattery(this)) {
            bgSub.setText("未加省电白名单，后台可能被清理");
            bgSub.setTextColor(Ui.WARN);
        } else {
            bgSub.setText("退到后台也能收新留言");
            bgSub.setTextColor(Ui.MUTED);
        }
        switching = true;
        bgSwitch.setChecked(on);
        switching = false;
    }

    private void refreshHoliday() {
        boolean on = Holiday.enabled(this);
        String when;
        int ov = Holiday.resolveToday(this);
        if (ov == Holiday.HOLIDAY) {
            when = "今天放假，不排课";
        } else if (ov >= 0) {
            when = "今天调休，按" + CourseCache.WEEK[ov] + "课表";
        } else {
            when = "按系统日期上课";
        }
        holidaySub.setText(when);
        holidaySub.setTextColor(Ui.MUTED);

        switching = true;
        holidaySwitch.setChecked(on);
        switching = false;
    }

    /** Switch 回调入口：按目标状态应用（打开且未加白名单 → 顺手引导） */
    private void applyBg(boolean on) {
        SyncService.setEnabled(this, on);
        if (on) {
            SyncService.startIfEnabled(this);
            // 打开后台提醒后，顺手引导加省电白名单（否则服务仍可能被 ROM 清理）
            if (!isIgnoringBattery(this)) {
                startActivity(new Intent(SettingsActivity.this, BatteryGuideActivity.class));
            }
        } else {
            SyncService.stop(this);
        }
        refreshBg();
    }

    private static boolean isIgnoringBattery(android.content.Context c) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) c.getSystemService(android.content.Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
