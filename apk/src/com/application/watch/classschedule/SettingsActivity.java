package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页（App 级）—— 2026-10-07 按 **Linear 设计系统** 重做视觉层（信息架构与业务逻辑不变）。
 *
 * <p>设计语言（摘自 Linear DESIGN.md）：
 * <ul>
 *   <li>暗色原生画布 {@code #08090A}；表面 {@code #101113} / {@code #191A1B}；半透明白描边落到实色即 {@code #23252A}</li>
 *   <li>唯一彩色是「靛紫」强调：{@code #5E6AD2}（品牌）/ {@code #7170FF}（交互）——只用于开关、chevron 选中、可点强调</li>
 *   <li>文字四阶：{@code #F7F8F8} 主 / {@code #D0D6E0} 次 / {@code #8A8F98} 弱 / {@code #62666D} 最弱</li>
 *   <li>圆角收紧：卡片 12 / 控件 6；行高 54，发丝分隔线（比旧版「灰砖」更接近 Linear 的 whisper-thin 结构）</li>
 * </ul>
 *
 * <p>浅色模式对应 Linear 的 light neutrals（{@code #F7F8F8} 画布 / 白卡 / {@code #E6E6E6} 描边），
 * 因此本页跟随 App 的深/浅色切换，底栏也一并重着色，保证整屏语境一致。
 *
 * <p>反馈就地化沿用旧版：瞬时动作按钮变「已发送 ✓」；持续问题（缺权限 / 缺省电白名单）用琥珀副文案贴在该行。
 */
public class SettingsActivity extends Activity {

    // ===================== Linear 设计令牌（依 App 浅/深色取档） =====================
    private int BG, CARD, CARD2, LINE, SEP;
    private int TEXT, TEXT2, MUTED, FAINT;
    private int ACCENT, ACCENT_BG, OK, WARN;
    private static final int R_CARD = 12;   // 卡片（Linear panel）
    private static final int R_CTRL = 6;    // 控件 / 按钮（Linear comfortable）

    /** 程序化 setChecked 时置 true，避免触发 onCheckedChanged 造成循环 */
    private boolean switching;
    private int lastThemeVersion = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ===== 提醒组 =====
    private TextView remindSub, bgSub, holidaySub, leadValue;
    private LinearLayout remindSubArea;
    private Switch remindSwitch, pushSwitch, bgSwitch, holidaySwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);      // 套用主题（含系统控件主题化，须在 setContentView 前）
        applyLinearTokens();                      // 依当前 light/dark 计算 Linear 色板
        root.setBackgroundColor(BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 12));
        root.addView(header());

        // ==================== 分组 1 · 提醒 ====================
        root.addView(Ui.space(this, 22));
        root.addView(groupLabel("提醒"));
        LinearLayout remindCard = card();

        // —— 上课提醒：开关行 + 展开子区 ——
        remindSwitch = linearSwitch();
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
        remindCard.addView(row("上课提醒", remindSub, remindSwitch, null));

        // 子区（开关打开才有意义）：提前时间 / 推送到手环 / 测试·重排
        remindSubArea = new LinearLayout(this);
        remindSubArea.setOrientation(LinearLayout.VERTICAL);

        leadValue = valueLabel("");
        remindSubArea.addView(sep(16));
        remindSubArea.addView(subRow("提前时间", valueWithChevron(leadValue),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        pickLeadMinutes();
                    }
                }));

        remindSubArea.addView(sep(30));
        pushSwitch = linearSwitch();
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
        remindSubArea.addView(subRow("推送到手环", pushSwitch, null));

        remindSubArea.addView(sep(30));
        remindSubArea.addView(actionRow());
        remindCard.addView(remindSubArea);

        // —— 后台常驻提醒 ——
        addSep(remindCard, 16);
        bgSwitch = linearSwitch();
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
        remindCard.addView(row("后台常驻提醒", bgSub, bgSwitch, null));

        // —— 假期 / 调休 ——
        addSep(remindCard, 16);
        holidaySwitch = linearSwitch();
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
        remindCard.addView(row("假期 / 调休", holidaySub, holidaySwitch, null));

        root.addView(remindCard);

        // ==================== 分组 2 · 外观 ====================
        root.addView(Ui.space(this, 24));
        root.addView(groupLabel("外观"));
        LinearLayout lookCard = card();
        lookCard.addView(row("主题外观", null, valueWithChevron(valueLabel(themeLabel())),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, ThemePickerActivity.class));
                    }
                }));
        root.addView(lookCard);

        // ==================== 分组 3 · 支持与关于 ====================
        root.addView(Ui.space(this, 24));
        root.addView(groupLabel("支持与关于"));
        LinearLayout helpCard = card();
        addRow(helpCard, row("帮助与反馈", subLabel("常见问题 · QQ 群 · 提交截图反馈"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openFeedbackPage();
                    }
                }));
        addRow(helpCard, row("检查更新", null, chevronOnly(), new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpdateChecker.checkManual(SettingsActivity.this);
            }
        }));
        addRow(helpCard, row("打赏支持", subLabel("爱发电 / 微信 / 支付宝"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DonateActivity.class));
                    }
                }));
        // 「高级版一键激活」依赖 EV 的 activate 动作（EvBox 工具箱暂无此动作）
        if (Variant.isEv(this)) {
            addRow(helpCard, row("高级版", subLabel(AuthState.displayText(this)),
                    chevronOnly(), new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            startActivity(new Intent(SettingsActivity.this, FastActivateActivity.class));
                        }
                    }));
        }
        addRow(helpCard, row("调试", subLabel("连不上手环时分步排查"),
                chevronOnly(), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(SettingsActivity.this, DebugActivity.class));
                    }
                }));
        addRow(helpCard, row("Ev 课程表", null, valueLabel("v" + version()), null));
        root.addView(helpCard);

        root.addView(Ui.space(this, 12));

        refreshBg();
        refreshRemind();
        refreshHoliday();
        ViewGroup rootView = Ui.wrapWithBottomBar(this, root, 3);
        styleBottomBar(rootView, 3);              // 底栏一并重着色为 Linear
        setContentView(rootView);
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

    // ==================================================================
    // Linear 色板
    // ==================================================================

    private void applyLinearTokens() {
        if (Ui.isDark()) {
            BG = 0xFF08090A; CARD = 0xFF101113; CARD2 = 0xFF191A1B;
            LINE = 0xFF23252A; SEP = 0xFF1B1C1E;
            TEXT = 0xFFF7F8F8; TEXT2 = 0xFFD0D6E0; MUTED = 0xFF8A8F98; FAINT = 0xFF62666D;
            ACCENT = 0xFF7170FF; ACCENT_BG = 0xFF5E6AD2; OK = 0xFF27A644; WARN = 0xFFFFB020;
        } else {
            BG = 0xFFF7F8F8; CARD = 0xFFFFFFFF; CARD2 = 0xFFF3F4F5;
            LINE = 0xFFE6E6E6; SEP = 0xFFEDEEF0;
            TEXT = 0xFF0B0C0E; TEXT2 = 0xFF3C4048; MUTED = 0xFF6B7078; FAINT = 0xFF8A8F98;
            ACCENT = 0xFF5E6AD2; ACCENT_BG = 0xFF5E6AD2; OK = 0xFF14934E; WARN = 0xFFB45309;
        }
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
    // 视图构件（全部走 Linear 令牌）
    // ==================================================================

    /** 页头：左对齐大标题 + 副文案 + 发丝底线（Linear 版式），替换旧的居中 topBar */
    private View header() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = Ui.textMedium(this, "设置", 20f, TEXT);
        t.setLetterSpacing(-0.02f);
        col.addView(t);

        TextView sub = Ui.text(this, "提醒 · 外观 · 关于", 13f, MUTED, false);
        sub.setPadding(0, Ui.dp(this, 5), 0, 0);
        col.addView(sub);

        View hr = sep(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        lp.topMargin = Ui.dp(this, 14);
        col.addView(hr, lp);
        return col;
    }

    /** 分组标题：12sp / 510 字重（Linear caption）/ MUTED，微正字距 */
    private TextView groupLabel(String s) {
        TextView t = Ui.textMedium(this, s, 12f, MUTED);
        t.setLetterSpacing(0.03f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 2);
        lp.bottomMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 分组卡：CARD 实色 + 6~12 圆角 + 1dp LINE 描边（Linear surface，行自带内边距） */
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.round(CARD, R_CARD, LINE, this));
        return l;
    }

    /** 发丝分隔线（Linear whisper-thin），左侧缩进 insetDp 以对齐文字 */
    private View sep(int insetDp) {
        View v = new View(this);
        v.setBackgroundColor(SEP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
        lp.leftMargin = Ui.dp(this, insetDp);
        v.setLayoutParams(lp);
        return v;
    }

    private void addSep(LinearLayout parent, int insetDp) {
        parent.addView(sep(insetDp));
    }

    /** 往卡里加一行：非首行自动补一条发丝线 */
    private void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            addSep(card, 16);
        }
        card.addView(row);
    }

    /** 通用设置行：左标题(+副文案，副文案需外部持有引用以便刷新) + 右控件。click 为 null 表示不可点。 */
    private LinearLayout row(String title, TextView subView, View right,
                             View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 54));
        r.setPadding(Ui.dp(this, 16), Ui.dp(this, 13), Ui.dp(this, 16), Ui.dp(this, 13));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(Ui.textMedium(this, title, 14f, TEXT));
        if (subView != null) {
            subView.setPadding(0, Ui.dp(this, 3), 0, 0);
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

    /** 展开子区的子行（46dp，左缩进 30dp，次级文字色 TEXT2） */
    private LinearLayout subRow(String label, View right, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(this, 46));
        r.setPadding(Ui.dp(this, 30), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        TextView t = Ui.text(this, label, 13f, TEXT2, false);
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

    /** 低频动作行（测试提醒 · 重排提醒）——Linear ghost 小按钮 */
    private LinearLayout actionRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 30), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 14));

        TextView test = ghost("测试提醒");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.test(SettingsActivity.this);
                flash(v, "已发送 ✓");
            }
        });
        r.addView(test);

        TextView resched = ghost("重排提醒");
        resched.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Reminders.reschedule(SettingsActivity.this);
                flash(v, "已重排 ✓");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 10);
        r.addView(resched, lp);
        return r;
    }

    /** Linear ghost 按钮：CARD2 底 + 1dp LINE 描边 + 6dp 圆角，文字 TEXT2 */
    private TextView ghost(String s) {
        TextView t = Ui.textMedium(this, s, 12.5f, TEXT2);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.round(CARD2, R_CTRL, LINE, this));
        return t;
    }

    /** 按钮自反馈：2.5s 内变「已完成 ✓」（OK 色），之后复原 */
    private void flash(final View v, final CharSequence done) {
        if (!(v instanceof TextView) || !v.isEnabled()) {
            return;
        }
        final TextView t = (TextView) v;
        final CharSequence orig = t.getText();
        final int origColor = t.getCurrentTextColor();
        t.setText(done);
        t.setTextColor(OK);
        t.setEnabled(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                t.setText(orig);
                t.setTextColor(origColor);
                t.setEnabled(true);
            }
        }, 2500);
    }

    /** 副文案（12.5sp MUTED），文本由 refresh* 更新 */
    private TextView subLabel(String s) {
        return Ui.text(this, s, 12.5f, MUTED, false);
    }

    /** 右值文案（13sp FAINT） */
    private TextView valueLabel(String s) {
        return Ui.text(this, s, 13f, FAINT, false);
    }

    /** 「右值 + chevron」组合 */
    private LinearLayout valueWithChevron(TextView value) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        if (value != null) {
            h.addView(value);
        }
        h.addView(chevron(), chevronLp());
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
        ic.setColorFilter(FAINT);
        return ic;
    }

    private LinearLayout.LayoutParams chevronLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 15), Ui.dp(this, 15));
        lp.leftMargin = Ui.dp(this, 6);
        return lp;
    }

    /**
     * Linear 风格 Switch：开 = 靛紫轨道(#5E6AD2) + 白滑块；关 = 中性灰轨道 + 白/灰滑块。
     *
     * <p>⚠️ 不用 {@code setTrackTintList}：平台默认 track 的 9-patch 自带 ~40% alpha，
     * 着色后会被冲淡（实测「开」态轨道只剩 {@code #282C4D}/{@code #BFC4ED}，不是纯靛紫）。
     * 这里直接自绘 track / thumb（纯色 GradientDrawable），颜色 100% 还原设计稿。
     */
    private Switch linearSwitch() {
        Switch s = new Switch(this);
        int offTrack = Ui.isDark() ? 0xFF2A2C31 : 0xFFD5D7DB;
        int offThumb = Ui.isDark() ? 0xFF8A8F98 : 0xFFFFFFFF;

        StateListDrawable track = new StateListDrawable();
        track.addState(new int[]{android.R.attr.state_checked}, pill(ACCENT_BG));
        track.addState(new int[]{}, pill(offTrack));

        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{android.R.attr.state_checked}, circle(0xFFFFFFFF));
        thumb.addState(new int[]{}, circle(offThumb));

        s.setTrackDrawable(track);
        s.setThumbDrawable(thumb);
        s.setSwitchMinWidth(Ui.dp(this, 44));
        s.setSwitchPadding(Ui.dp(this, 8));
        return s;
    }

    /** 开关轨道：38×22 圆角矩形（圆角=半高，两端胶囊） */
    private Drawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(Ui.dp(this, 11));
        g.setSize(Ui.dp(this, 38), Ui.dp(this, 22));
        return g;
    }

    /** 开关滑块：22dp 圆 */
    private Drawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setSize(Ui.dp(this, 22), Ui.dp(this, 22));
        return g;
    }

    /**
     * 把 {@link Ui#wrapWithBottomBar} 生成的底栏重着色为 Linear：
     * 底 = CARD（中性），选中 = ACCENT，未选中 = FAINT，并把选中 tab 的文字设 medium。
     * 仅作用于本页，不影响其它页面共享的 Ui 底栏。
     */
    private void styleBottomBar(ViewGroup rootView, int current) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View ch = rootView.getChildAt(i);
            if (!(ch instanceof LinearLayout)) {
                continue;
            }
            LinearLayout bar = (LinearLayout) ch;
            if (bar.getChildCount() != 4) {          // 底栏恒为 4 个 tab
                continue;
            }
            bar.setBackgroundColor(CARD);
            for (int j = 0; j < bar.getChildCount(); j++) {
                View tabV = bar.getChildAt(j);
                if (!(tabV instanceof LinearLayout)) {
                    continue;
                }
                LinearLayout tab = (LinearLayout) tabV;
                boolean active = (j == current);
                int color = active ? ACCENT : FAINT;
                if (tab.getChildCount() >= 2) {
                    View ic = tab.getChildAt(0);
                    if (ic instanceof ImageView) {
                        ((ImageView) ic).setColorFilter(color);
                    }
                    View lb = tab.getChildAt(1);
                    if (lb instanceof TextView) {
                        TextView t = (TextView) lb;
                        t.setTextColor(color);
                        t.setTypeface(active
                                ? android.graphics.Typeface.create("sans-serif-medium",
                                        android.graphics.Typeface.NORMAL)
                                : android.graphics.Typeface.DEFAULT);
                    }
                }
            }
        }
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
            remindSub.setTextColor(MUTED);
        } else if (!Reminders.exactAllowed(this)) {
            remindSub.setText("未授予「闹钟和提醒」权限，可能有 ±1 分钟误差");
            remindSub.setTextColor(WARN);
        } else {
            remindSub.setText("提前 " + lead + " 分钟 · 手机通知" + (push ? " + 手环" : ""));
            remindSub.setTextColor(MUTED);
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
            bgSub.setTextColor(MUTED);
        } else if (!isIgnoringBattery(this)) {
            bgSub.setText("未加省电白名单，后台可能被清理");
            bgSub.setTextColor(WARN);
        } else {
            bgSub.setText("退到后台也能收新留言");
            bgSub.setTextColor(MUTED);
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
        holidaySub.setTextColor(MUTED);

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
