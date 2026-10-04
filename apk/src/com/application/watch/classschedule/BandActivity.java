package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 手环页（底栏第 3 tab，设备作用域）：连接管理 + 昵称 + 首页设置 + 腕聊入口。
 *
 * 与「设置」页的分工：凡是「写回手环才生效 / 设备仅有的」配置都在这里；
 * App 自身行为（后台常驻、上课提醒、主题、高级版、打赏、更新）在「设置」页。
 * 多手环：连接与记忆逻辑在 SyncEngine（preferredNodeId），这里提供查看与重置。
 *
 * ── 2026-10-05 全新界面 v1 落地（docs/手环页全新设计方案-v1.md）──
 * 病根：同一个「连接状态」首屏说三遍（设备卡 / ConnectionBar / 状态卡「连接」行），
 *       而唯一会造成断连的动作藏在一个 11sp 裸「▼」里（零文案、零确认）。
 * 三批：① 结构减法 ② 主次重排 ③ 边界打磨。零新增 token。
 */
public class BandActivity extends Activity {

    private int lastThemeVersion = 0;

    // ---- 设备主卡（唯一状态源）----
    private TextView heroNameView, heroStatusView, heroInfoView;
    private View statusDotView;
    private LinearLayout heroOpsBox;
    private Button heroPrimaryBtn, heroSecondaryBtn;

    // ---- 设备状态：2 列指标网格 ----
    private LinearLayout statusSection;
    private TextView mBatteryView, mChargeView, mWearView, mSleepView, mStorageView;

    // ---- 未连接引导卡 ----
    private LinearLayout guideCard;
    /** 未连接手环时整体隐藏的区块容器（腕聊 / 工具 / 昵称 均为「连上才有意义」） */
    private LinearLayout cfgBox;

    /** 昵称设置行的右侧当前值 */
    private TextView nickValueView;

    private TextView resultView;
    private Runnable heroTick;
    private Runnable flashHide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(buildTopBar());                 // 「我的手环」+ ⋯ 设备菜单（D9 / D4）
        root.addView(Ui.space(this, Ui.GAP_MD));

        // ===== 设备主卡：全页唯一状态源（D2）=====
        root.addView(buildHeroCard());
        root.addView(Ui.space(this, Ui.GAP_MD));

        // ===== 设备状态：2 列指标网格（D3）；未连接时整块收起（D8）=====
        statusSection = buildStatusSection();
        root.addView(statusSection);
        root.addView(Ui.space(this, Ui.GAP_MD));

        // ===== 未连接引导卡（D8）：仅未连接时显示 =====
        guideCard = buildGuideCard();
        root.addView(guideCard);
        root.addView(Ui.space(this, Ui.GAP_MD));

        // ===== 已连接才有意义的区块：腕聊 / 工具 / 昵称（未连接整块收起）=====
        cfgBox = new LinearLayout(this);
        cfgBox.setOrientation(LinearLayout.VERTICAL);
        cfgBox.addView(buildChatCard());             // D5：腕聊提为独立主操作卡
        cfgBox.addView(Ui.space(this, Ui.GAP_MD));
        cfgBox.addView(buildToolsGrid());            // D5：首页设置 / 工具箱 → 2 列工具卡
        cfgBox.addView(Ui.space(this, Ui.GAP_MD));
        cfgBox.addView(buildNickRow());              // D6：昵称卡 → 设置行（点开底部弹层）
        root.addView(cfgBox);

        // ===== 瞬时提示条（语义色，2.6s 自动收起）=====
        root.addView(Ui.space(this, Ui.GAP_SM));
        resultView = Ui.textLh(this, "", 12.5f, Ui.MUTED, false, Ui.LH_BODY);
        resultView.setVisibility(View.GONE);
        root.addView(resultView);

        // 全部区块建好后再统一刷新一次显隐（此前 cfgBox/guideCard 可能还不存在）
        refresh();
        updateHero();

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/band");

        heroTick = new Runnable() {
            @Override public void run() { updateHero(); }
        };
        SyncEngine.get(this).addStatusCallback(heroTick);
    }

    // ======================= 顶栏 + 设备菜单（D4 / D9）=======================

    /** 顶栏：左「我的手环」标题 + 右 ⋯（44dp 热区 / 34dp 视觉，与首页/课表库同一套）。
     *  原来那个 11sp 的裸「▼」（点击 = 清除设备记忆并重连）语义不明、零文案，收进这里。 */
    private View buildTopBar() {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);

        TextView t = Ui.textMediumLh(this, "我的手环", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
        bar.addView(t, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER_VERTICAL));

        android.widget.FrameLayout hit = new android.widget.FrameLayout(this);
        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vertical);
        more.setColorFilter(Ui.ACCENT);
        more.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        more.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        hit.addView(more, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34), android.view.Gravity.CENTER));
        hit.setClickable(true);
        hit.setContentDescription("设备操作");
        hit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDeviceMenu(v); }
        });
        bar.addView(hit, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END));
        return bar;
    }

    /** ⋯ 设备菜单（沿用首页 / 课表库的既有菜单样式：白底圆角单框 + 文字行 + 细分隔线）。
     *  ⚠️ 方案 D4 原画三项（切换手环 / 清除记忆并重连 / 连接调试），落地合并为**两项**：
     *  当前实现里「切换手环」与「清除记忆并重连」是**同一个动作**（都走
     *  {@code setPreferredNodeId("") + autoReconnect()}，多手环时重新询问选哪台），
     *  按 P2「一件事只说一处」不摆两个同义项。若将来加了设备列表页，可拆回两项。 */
    private void showDeviceMenu(View anchor) {
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

        // 有副作用的动作：琥珀 + 一句后果说明（原来它藏在没人认识的裸「▼」里）
        addMenuItem(panel, pw, "清除记忆并重连", "会忘记当前设备，重新选择", Ui.WARN, new Runnable() {
            @Override public void run() { clearMemoryAndReconnect(); }
        });
        panel.addView(menuDivider());
        // 排障入口：连不上时更需要它（D7：从常驻页底收进菜单）
        addMenuItem(panel, pw, "连接调试", "连不上时分步排查", Ui.TEXT, new Runnable() {
            @Override public void run() {
                startActivity(new Intent(BandActivity.this, DebugActivity.class));
            }
        });

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        pw.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
                loc[0] + anchor.getWidth() - panel.getMeasuredWidth(),
                loc[1] + anchor.getHeight() + Ui.dp(this, 6));
    }

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

    /** 菜单里的一行：主文案（可染色）+ 可选副文案。 */
    private void addMenuItem(LinearLayout panel, final android.widget.PopupWindow pw,
                             String label, String sub, int color, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, Ui.GAP_LG), Ui.dp(this, 6),
                Ui.dp(this, Ui.GAP_LG), Ui.dp(this, 6));
        TextView t = Ui.textLh(this, label, Ui.SP_BODY, color, false, Ui.LH_BODY);
        row.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = Ui.textLh(this, sub, Ui.SP_MICRO, Ui.MUTED, false, Ui.LH_MICRO);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            row.addView(s);
        }
        row.setClickable(true);
        panel.addView(row, new LinearLayout.LayoutParams(
                Ui.dp(this, 200), Ui.dp(this, Ui.TOUCH_MIN)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pw.dismiss();
                action.run();
            }
        });
    }

    /** 清除设备记忆并重连（多手环时会重新询问选哪台）。 */
    private void clearMemoryAndReconnect() {
        SyncEngine.get(this).setPreferredNodeId("");
        SyncEngine.get(this).autoReconnect();
        flash("已清除设备记忆，正在重新连接…", Ui.ACCENT);
        updateHero();
    }

    // ======================= 设备主卡（D2）=======================

    /** 设备主卡：左表盘图形 + 右（名称 / 状态点+状态 / 元信息），下方两枚等高操作按钮。
     *  全页只剩这一个「连接状态」来源（ConnectionBar 与状态卡的「连接」行都已删除）。 */
    private View buildHeroCard() {
        LinearLayout hero = Ui.card(this);

        LinearLayout hRow = new LinearLayout(this);
        hRow.setOrientation(LinearLayout.HORIZONTAL);
        hRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 左：表盘线稿（无背景，主色）
        ImageView watchIv = new ImageView(this);
        watchIv.setImageResource(R.drawable.ic_tab_watch);
        watchIv.setColorFilter(Ui.ACCENT);
        watchIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        watchIv.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 60));
        wp.rightMargin = Ui.dp(this, Ui.GAP_MD);
        hRow.addView(watchIv, wp);

        // 右：名称 / 状态行 / 元信息
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        heroNameView = Ui.textMediumLh(this, "未连接手环", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
        info.addView(heroNameView);

        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        sRow.setPadding(0, Ui.dp(this, 4), 0, 0);
        statusDotView = new View(this);
        statusDotView.setBackground(Ui.round(Ui.MUTED, 4, 0, this));   // 8dp 圆 = 半径 4
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        dp.rightMargin = Ui.dp(this, 6);
        sRow.addView(statusDotView, dp);
        heroStatusView = Ui.textMediumLh(this, "未连接", 12.5f, Ui.MUTED, Ui.LH_BODY);
        sRow.addView(heroStatusView);
        info.addView(sRow);

        heroInfoView = Ui.textLh(this, "打开小米运动健康连接手环", Ui.SP_CAPTION, Ui.MUTED,
                false, Ui.LH_CAPTION);
        heroInfoView.setPadding(0, Ui.dp(this, 3), 0, 0);
        info.addView(heroInfoView);

        hRow.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(hRow);

        // 操作区：两枚等高按钮（已连接 = 呼叫手环 + 同步课表；未连接 = 单枚「同步手环课表」）
        hero.addView(Ui.space(this, Ui.GAP_MD));
        heroOpsBox = new LinearLayout(this);
        heroOpsBox.setOrientation(LinearLayout.HORIZONTAL);

        heroPrimaryBtn = Ui.button(this, "同步手环课表", true, new View.OnClickListener() {
            @Override public void onClick(View v) { manualSync(); }
        });
        heroOpsBox.addView(heroPrimaryBtn, new LinearLayout.LayoutParams(
                0, Ui.dp(this, 38), 1f));

        heroSecondaryBtn = Ui.button(this, "同步课表", false, new View.OnClickListener() {
            @Override public void onClick(View v) { manualSync(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 38), 1f);
        sp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        heroOpsBox.addView(heroSecondaryBtn, sp);

        hero.addView(heroOpsBox);
        return hero;
    }

    // ======================= 设备状态：2 列指标网格（D3）=======================

    /** 一格指标：上=数值（电量用主色 17sp 做锚点），下=标签。 */
    private View metricCell(TextView value, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        c.addView(value);
        TextView l = Ui.textLh(this, label, Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        l.setPadding(0, Ui.dp(this, 3), 0, 0);
        c.addView(l);
        return c;
    }

    private View hLine() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        return v;
    }

    private View vLine() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                Math.max(1, Ui.dp(this, 1)), LinearLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    /** 两列一行。 */
    private View metricRow(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 56), 1f);
        r.addView(a, lp);
        r.addView(vLine());
        r.addView(b, lp);
        return r;
    }

    /** 设备状态卡：删掉重复的「连接」行（状态只从设备卡读），收成 2 列指标网格。 */
    private LinearLayout buildStatusSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.textMediumLh(this, "设备状态", Ui.SP_BODY, Ui.TEXT, Ui.LH_BODY));
        box.addView(Ui.space(this, Ui.GAP_SM));

        LinearLayout card = Ui.card(this);
        // 电量：唯一用主色大数字的项（页内锚点）
        mBatteryView = Ui.textMedium(this, "—", Ui.SP_TITLE, Ui.ACCENT);
        mChargeView = Ui.textMedium(this, "—", 12.5f, Ui.TEXT);
        mWearView = Ui.textMedium(this, "—", 12.5f, Ui.TEXT);
        mSleepView = Ui.textMedium(this, "—", 12.5f, Ui.TEXT);
        mStorageView = Ui.textMedium(this, "—", 12.5f, Ui.TEXT);

        card.addView(metricRow(metricCell(mBatteryView, "电量"), metricCell(mChargeView, "充电")));
        card.addView(hLine());
        card.addView(metricRow(metricCell(mWearView, "佩戴"), metricCell(mSleepView, "睡眠")));
        card.addView(hLine());
        LinearLayout storageCell = new LinearLayout(this);
        storageCell.setOrientation(LinearLayout.VERTICAL);
        storageCell.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        storageCell.addView(mStorageView);
        TextView sl = Ui.textLh(this, "存储空间", Ui.SP_CAPTION, Ui.MUTED, false, Ui.LH_CAPTION);
        sl.setPadding(0, Ui.dp(this, 3), 0, 0);
        storageCell.addView(sl);
        card.addView(storageCell, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        box.addView(card);
        return box;
    }

    // ======================= 腕聊主操作卡（D5）=======================

    private View buildChatCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 13), Ui.dp(this, Ui.GAP_MD), Ui.dp(this, 13));
        card.setBackground(Ui.round(Ui.ACCENT_LIGHT, Ui.R_CARD,
                (Ui.ACCENT & 0x00FFFFFF) | 0x38000000, this));
        card.setMinimumHeight(Ui.dp(this, 64));
        card.setClickable(true);
        card.setContentDescription(MessageActivity.CHAT_NAME);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(BandActivity.this, MessageActivity.class));
            }
        });

        android.widget.FrameLayout icBox = new android.widget.FrameLayout(this);
        icBox.setBackground(Ui.round(Ui.ACCENT, Ui.R_CTRL, 0, this));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_tab_message);
        ic.setColorFilter(Ui.ON_ACCENT);
        ic.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        icBox.addView(ic, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38), android.view.Gravity.CENTER));
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38));
        ibp.rightMargin = Ui.dp(this, Ui.GAP_MD);
        card.addView(icBox, ibp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(Ui.textMediumLh(this, MessageActivity.CHAT_NAME, Ui.SP_SUBTITLE,
                Ui.ACCENT, Ui.LH_SUBTITLE));
        TextView sub = Ui.textLh(this, "手环 ↔ 手机 实时聊天", Ui.SP_CAPTION, Ui.MUTED,
                false, Ui.LH_CAPTION);
        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
        info.addView(sub);
        card.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Ui.ACCENT);
        card.addView(chev, new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18)));
        return card;
    }

    // ======================= 2 列工具卡（D5）=======================

    private View toolCell(int iconRes, String title, String sub, final Class<?> target) {
        LinearLayout cell = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.FrameLayout icBox = new android.widget.FrameLayout(this);
        icBox.setBackground(Ui.round(Ui.ACCENT_LIGHT, 10, 0, this));
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Ui.ACCENT);
        ic.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        icBox.addView(ic, new android.widget.FrameLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32), android.view.Gravity.CENTER));
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        ibp.rightMargin = Ui.dp(this, Ui.GAP_SM);
        row.addView(icBox, ibp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(Ui.textMediumLh(this, title, 12.5f, Ui.TEXT, Ui.LH_BODY));
        TextView s = Ui.textLh(this, sub, Ui.SP_MICRO, Ui.MUTED, false, Ui.LH_MICRO);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        info.addView(s);
        row.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        cell.addView(row);
        cell.setClickable(true);
        cell.setContentDescription(title);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(BandActivity.this, target));
            }
        });
        return cell;
    }

    private View buildToolsGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = Ui.dp(this, Ui.GAP_SM);
        grid.addView(toolCell(R.drawable.ic_tab_home, "首页设置", "模板 / 字号",
                HomepageSettingsActivity.class), lp);
        grid.addView(toolCell(R.drawable.ic_timer, "工具箱", "找手机 / 静音",
                ToolboxActivity.class), lp2);
        return grid;
    }

    // ======================= 昵称设置行（D6）=======================

    private View buildNickRow() {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 24));   // 卡内已有 12dp 上下 padding，合起来 ≥ 44dp 热区

        row.addView(Ui.textLh(this, "手环昵称", Ui.SP_BODY, Ui.TEXT, false, Ui.LH_BODY),
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        nickValueView = Ui.textLh(this, "—", 12.5f, Ui.MUTED, false, Ui.LH_BODY);
        nickValueView.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        row.addView(nickValueView, vp);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Ui.MUTED);
        row.addView(chev, new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16)));

        card.addView(row);
        card.setClickable(true);
        card.setContentDescription("编辑手环昵称");
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNickSheet(); }
        });
        return card;
    }

    /** 昵称编辑底部弹层（展示归展示、编辑归编辑）。 */
    private void showNickSheet() {
        final android.app.Dialog d = new android.app.Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.round(Ui.CARD, 18, 0, this));
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, Ui.GAP_LG),
                Ui.dp(this, 18), Ui.dp(this, Ui.GAP_LG));

        panel.addView(Ui.textMediumLh(this, "手环昵称", Ui.SP_SUBTITLE, Ui.TEXT, Ui.LH_SUBTITLE));
        TextView sd = Ui.textLh(this, "显示在手环首页的名字", Ui.SP_CAPTION, Ui.MUTED,
                false, Ui.LH_CAPTION);
        sd.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, Ui.GAP_MD));
        panel.addView(sd);

        final EditText input = new EditText(this);
        input.setTextSize(14f);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("请输入昵称");
        String nick = SyncEngine.get(this).nickname;
        if (nick != null && nick.length() > 0) {
            input.setText(nick);
        }
        input.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        input.setPadding(Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_MD),
                Ui.dp(this, Ui.GAP_MD), Ui.dp(this, Ui.GAP_MD));
        panel.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        panel.addView(Ui.space(this, Ui.GAP_LG));
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = Ui.button(this, "取消", false, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        Button ok = Ui.button(this, "保存", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                saveNick(input.getText().toString().trim());
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, Ui.dp(this, Ui.TOUCH_MIN), 1f);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                0, Ui.dp(this, Ui.TOUCH_MIN), 1f);
        bp2.leftMargin = Ui.dp(this, Ui.GAP_SM);
        ops.addView(cancel, bp);
        ops.addView(ok, bp2);
        panel.addView(ops);

        d.setContentView(panel);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.BOTTOM);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = w.getAttributes();
            attrs.dimAmount = 0.28f;
            w.setAttributes(attrs);
        }
        d.show();
    }

    // ======================= 未连接引导卡（D8）=======================

    private LinearLayout buildGuideCard() {
        LinearLayout card = Ui.card(this);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 20));

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.ic_tab_watch);
        art.setColorFilter(Ui.MUTED);
        card.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));

        TextView t = Ui.textMediumLh(this, "还没有连接手环", Ui.SP_SUBTITLE, Ui.TEXT, Ui.LH_SUBTITLE);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, Ui.GAP_MD), 0, 0);
        card.addView(t);

        TextView s = Ui.textLh(this,
                "先在「小米运动健康」里配对手环，回到这里点「同步手环课表」即可把课表读进手机。",
                12.5f, Ui.MUTED, false, Ui.LH_BODY);
        s.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, Ui.GAP_SM);
        card.addView(s, sp);

        Button go = Ui.button(this, "去同步手环课表", true, new View.OnClickListener() {
            @Override public void onClick(View v) { manualSync(); }
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, Ui.TOUCH_MIN));
        gp.topMargin = Ui.dp(this, Ui.GAP_LG);
        card.addView(go, gp);

        // 排障文字链：连不上时它反而更显眼（D7 / R2 —— 常驻入口收进菜单后，这里兜底）
        TextView link = Ui.textLh(this, "连不上？打开连接调试", Ui.SP_MICRO, Ui.ACCENT,
                false, Ui.LH_MICRO);
        link.setGravity(android.view.Gravity.CENTER);
        link.setPadding(0, Ui.dp(this, Ui.GAP_MD), 0, 0);
        link.setClickable(true);
        link.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(BandActivity.this, DebugActivity.class));
            }
        });
        card.addView(link);
        return card;
    }

    // ======================= 快捷操作（自首页设备卡迁入）=======================

    /** 从手环拉当前课表写入本地多课表存储（先问课表名，失败用兜底名）。 */
    private void manualSync() {
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            flash("手环未连接，先连接手环", Ui.WARN);
            return;
        }
        flash("正在从手环读取课表…", Ui.ACCENT);
        e.sendWake("{\"action\":\"export\"}", new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    final org.json.JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        flash("手环回包里没课表数据", Ui.WARN);
                        return;
                    }
                    e.lastExportJson = json;
                    e.listSchedules(new SyncEngine.Reply() {
                        @Override public void onReply(String j2) {
                            String name = "";
                            try {
                                JSONObject o2 = new JSONObject(j2);
                                org.json.JSONArray names = o2.optJSONArray("names");
                                int cur = o2.optInt("current", 0);
                                if (names != null && cur >= 0 && cur < names.length()) {
                                    name = names.optString(cur);
                                }
                            } catch (Throwable ignored) {
                            }
                            ScheduleStore.upsertFromWatch(BandActivity.this,
                                    SyncEngine.get(BandActivity.this).currentDeviceId(),
                                    SyncEngine.get(BandActivity.this).currentDeviceName(), name, sch);
                            flash("课表已同步 ✓" + (name.length() > 0 ? "（" + name + "）" : ""), Ui.OK);
                        }
                        @Override public void onTimeout(String h) { store(sch); }
                        @Override public void onError(String m) { store(sch); }
                    });
                } catch (Throwable t) {
                    flash("同步失败：回包无法解析", Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                flash("读取超时：" + hint, Ui.WARN);
            }
            @Override public void onError(String msg) {
                flash("同步失败：" + msg, Ui.ERR);
            }
        });
    }

    private void store(org.json.JSONArray sch) {
        ScheduleStore.upsertFromWatch(BandActivity.this,
                SyncEngine.get(BandActivity.this).currentDeviceId(),
                SyncEngine.get(BandActivity.this).currentDeviceName(), "", sch);
        flash("课表已同步 ✓（课表名未取到，用默认名）", Ui.OK);
    }

    /** 呼叫手环：双通道（① 系统通知卡 + ② EV {@code action=call}），一路断开另一路兜底。 */
    private void callBand() {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            flash("手环未连接，无法呼叫", Ui.WARN);
            return;
        }
        flash("正在呼叫手环…", Ui.ACCENT);
        e.ringBand(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String info) {
                if (ok) {
                    flash("呼叫已送达 ✓（" + info + "）看看手腕吧", Ui.OK);
                } else {
                    flash("呼叫失败：" + info + "。可到「连接调试」拉起 EV 后重试", Ui.WARN);
                }
            }
        });
    }

    /** 快速留言：入队，连上后自动补发（与留言页同一份存储）。 */
    private void quickMessage() {
        final EditText input = new EditText(this);
        input.setHint("写一条留言给手环…");
        input.setTextSize(14f);
        new android.app.AlertDialog.Builder(this)
                .setTitle("发消息给手环")
                .setView(input)
                .setPositiveButton("发送", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String text = input.getText().toString().trim();
                        if (text.length() == 0) {
                            return;
                        }
                        MessageActivity.enqueueOutgoing(BandActivity.this, text);
                        flash("留言已入队（连上手环后自动补发）", Ui.OK);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 四步连接，进度逐行写进结果区。 */
    private void startConnect() {
        final SyncEngine e = SyncEngine.get(this);
        flash("连接中…", Ui.ACCENT);
        e.connect(new SyncEngine.Steps() {
            @Override public void onUpdate(String[] labels, int[] states, String[] details) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < labels.length; i++) {
                    String mark = states[i] == SyncEngine.OK ? "✅"
                            : states[i] == SyncEngine.FAIL ? "❌"
                            : states[i] == SyncEngine.RUNNING ? "⏳" : "·";
                    sb.append(mark).append(' ').append(labels[i]);
                    if (details[i] != null && details[i].length() > 0) {
                        sb.append("：").append(details[i]);
                    }
                    sb.append('\n');
                }
                flash(sb.toString().trim(), Ui.TEXT);
            }
            @Override public void onFinish(boolean ok, String hint) {
                if (ok) {
                    flash("已连接 " + e.deviceName + " · v" + e.versionName
                            + scheduleSetsSuffix(e), Ui.OK);
                    updateHero();
                    refresh();
                } else {
                    flash("连接未完成：" + hint + "\n可到「连接调试」分步排查", Ui.ERR);
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
        refresh();
        updateHero();
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            e.requestBattery();     // 老路径（EV 上报），有结果经状态回调刷新头部
            e.queryDeviceState();   // SDK 直读：电量/连接/充电/佩戴/睡眠
            e.requestSysinfo();     // 手环端要一次存储/型号
            e.refreshBandScheduleCount(); // 刷新手环真实课表套数（顶部「课表 N 套」）
        }
    }

    // ======================= 状态刷新 =======================

    /** 设备主卡按连接状态刷新（名称/状态点/状态行/元信息/操作按钮）。 */
    private void updateHero() {
        SyncEngine e = SyncEngine.get(this);
        boolean on = e.connected();
        if (on) {
            String name = (e.deviceName == null || e.deviceName.length() == 0)
                    ? "手环" : e.deviceName;
            heroNameView.setText(name);
            heroNameView.setTextColor(Ui.TEXT);
            // 状态行：状态点 +「已连接 · 电量 85%」（电量未知则只报「已连接」，绝不给假数据）
            statusDotView.setBackground(Ui.round(Ui.OK, 4, 0, this));
            heroStatusView.setText("已连接" + (e.batteryPercent > 0
                    ? " · 电量 " + e.batteryPercent + "%" : ""));
            heroStatusView.setTextColor(Ui.OK);
            // 元信息独立次行：只报 EV 版本 + 课表套数（电量已上移到状态行/指标网格）
            heroInfoView.setText("EV " + e.versionName + scheduleSetsSuffix(e));
            heroInfoView.setTextColor(Ui.MUTED);
            // 两枚等高按钮：呼叫手环（主）+ 同步课表（次）
            heroPrimaryBtn.setText("呼叫手环");
            heroPrimaryBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { callBand(); }
            });
            heroSecondaryBtn.setVisibility(View.VISIBLE);
        } else {
            heroNameView.setText("未连接手环");
            heroNameView.setTextColor(Ui.MUTED);
            statusDotView.setBackground(Ui.round(Ui.MUTED, 4, 0, this));
            heroStatusView.setText("未连接");
            heroStatusView.setTextColor(Ui.MUTED);
            heroInfoView.setText("打开小米运动健康连接手环");
            heroInfoView.setTextColor(Ui.MUTED);
            heroPrimaryBtn.setText("同步手环课表");
            heroPrimaryBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { manualSync(); }
            });
            heroSecondaryBtn.setVisibility(View.GONE);
        }
        // 未连接：设备状态区整块收起（D8）、配置区收起、引导卡出现
        if (statusSection != null) {
            statusSection.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (guideCard != null) {
            guideCard.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        applyConnectionVisibility(on);
        updateStatus();
    }

    /** 课表套数后缀（顶部/连接完成文案共用）：优先手环真实套数（list_schedules 权威值，
     *  由 {@code refreshBandScheduleCount} 刷新），未知时退回本机已存的本设备 sync 套数；
     *  都拿不到则不带后缀 —— 只报「套」，不再显示「节」。
     *  {@code bandScheduleCount >= 0} 是「已知」判据（见 SyncEngine）。 */
    private String scheduleSetsSuffix(SyncEngine e) {
        int n = e.bandScheduleCount;
        if (n < 0) {
            String dev = e.currentDeviceId();
            int local = 0;
            for (ScheduleStore.Schedule s : ScheduleStore.list(this)) {
                if (s.isSync() && (dev == null || dev.length() == 0 || dev.equals(s.deviceId))) {
                    local++;
                }
            }
            n = (local > 0) ? local : -1;
        }
        return (n >= 0) ? (" · 课表 " + n + " 套") : "";
    }

    /** 按 SyncEngine 的最新设备状态刷新指标网格。未取到的项一律显示「—」，绝不给假数据。 */
    private void updateStatus() {
        if (mBatteryView == null) {
            return;
        }
        SyncEngine e = SyncEngine.get(this);

        mBatteryView.setText(e.batteryPercent > 0 ? (e.batteryPercent + "%") : "—");

        if (e.bandChargingKnown) {
            mChargeView.setText(e.bandCharging ? "充电中" : "未充电");
            mChargeView.setTextColor(e.bandCharging ? Ui.ACCENT : Ui.TEXT);
        } else {
            mChargeView.setText("—");
            mChargeView.setTextColor(Ui.TEXT);
        }

        mWearView.setText(e.bandWearingKnown ? (e.bandWearing ? "佩戴中" : "未佩戴") : "—");
        mWearView.setTextColor(Ui.TEXT);

        if (e.bandSleepingKnown) {
            mSleepView.setText(e.bandSleeping ? "睡眠中" : "清醒");
        } else {
            mSleepView.setText("—");
        }
        mSleepView.setTextColor(Ui.TEXT);

        mStorageView.setText(e.bandStorageKnown
                ? (fmtBytes(e.bandAvailStorage) + " 可用 / " + fmtBytes(e.bandTotalStorage))
                : "—");
    }

    /** 字节数人性化（B / KB / MB / GB）。 */
    private static String fmtBytes(long b) {
        if (b <= 0) return "—";
        if (b >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return (b / 1024) + " KB";
        return b + " B";
    }

    /** 未连接手环时隐藏「连接后才有意义」的区块（腕聊 / 工具 / 昵称），连上后自动恢复。 */
    private void applyConnectionVisibility(boolean connected) {
        if (cfgBox == null) {
            return;
        }
        cfgBox.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    /** 瞬时提示：语义色，2.6s 后自动收起（稳态不占位）。 */
    private void flash(String msg, int color) {
        if (resultView == null) {
            return;
        }
        resultView.setText(msg);
        resultView.setTextColor(color);
        resultView.setVisibility(View.VISIBLE);
        if (flashHide != null) {
            resultView.removeCallbacks(flashHide);
        }
        flashHide = new Runnable() {
            @Override public void run() {
                if (resultView != null) {
                    resultView.setVisibility(View.GONE);
                }
            }
        };
        resultView.postDelayed(flashHide, 2600);
    }

    private void refresh() {
        SyncEngine e = SyncEngine.get(this);
        String nick = e.nickname;
        if (nickValueView != null) {
            nickValueView.setText((nick == null || nick.length() == 0) ? "未设置" : nick);
        }
    }

    /** 保存昵称（底部弹层「保存」按钮）。 */
    private void saveNick(String nick) {
        if (TextUtils.isEmpty(nick)) {
            flash("昵称不能为空", Ui.WARN);
            return;
        }
        if (!SyncEngine.get(this).connected()) {
            flash("手环未连接：请先回首页连接手环", Ui.WARN);
            return;
        }
        flash("正在写入手环…", Ui.MUTED);
        SyncEngine.get(this).setNickname(nick, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        SyncEngine.get(BandActivity.this).nickname = (nick == null) ? "" : nick;
                        flash("已保存，手环首页昵称已更新", Ui.OK);
                        refresh();
                    } else {
                        flash("手环拒绝：" + o.optString("reason"), Ui.ERR);
                    }
                } catch (Throwable t) {
                    flash("回包无法解析：" + json, Ui.ERR);
                }
            }

            @Override public void onTimeout(String hint) {
                flash(hint, Ui.ERR);
            }

            @Override public void onError(String msg) {
                flash("写入失败：" + msg, Ui.ERR);
            }
        });
    }
}
