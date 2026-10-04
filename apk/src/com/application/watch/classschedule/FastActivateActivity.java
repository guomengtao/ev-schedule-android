package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 高级版「快速激活」。
 *
 * 流程（省掉手环上扫两个码 + 手输 18 位）：
 *   0. 购买：入口直达爱发电（/go/ev-timetable 短链，自带点击统计），获得 4 位兑换码
 *   1. 从手环取设备ID（get_device_id）
 *   2. 四格输入 4 位兑换码（支持粘贴自动分格、自动大写、自动跳格）
 *   3. 调后端 /api/activate 换 18 位激活码
 *   4. 把 18 位码交给手环（activate），手环本地校验 + 落库
 *
 * 断点续传（SharedPreferences ev_pending_activate）：
 *   - 未连接手环时提交 → 只暂存兑换码，连接后自动继续
 *   - 换到 18 位码但写入手环失败 → 暂存 18 位码，重连后直接补写（不再打服务端）
 *   - 18 位码由服务端按 deviceId 确定性生成，暂存丢失也可重新兑换取回（幂等）
 *
 * ⚠️ UI 层约定（按「高级版页全新设计方案 v1」重做，2026-10-05）：
 *   · 输入卡是首屏第一张卡；购买入口降为下方设置行
 *   · 连接状态只有一个来源（本页设备行）——不再挂连接状态条组件
 *   · 反馈就地化：没有页面级反馈 View，一律走卡内结果条 + 按钮自反馈
 *   · 逻辑（激活/暂存/续传/深链/埋点）与重做前完全一致，只改「写进哪个 View」
 */
public class FastActivateActivity extends Activity {

    private int lastThemeVersion = 0;

    private static final Pattern REDEEM = Pattern.compile("^[A-Z0-9]{4}$");
    private static final String BUY_URL = "https://app-auth.gudq.com/go/ev-timetable";

    private static final String PREF = "ev_pending_activate";
    private static final String K_REDEEM = "redeem";
    private static final String K_DEVICE = "deviceId";
    private static final String K_CODE = "activationCode";
    private static final String K_TS = "ts";
    // P1（§4.1）：深链溯源参数。存 prefs 而不是实例字段 —— 深链进来时手环没连上会走「暂存」，
    // 页面重建后实例字段会丢，prefs 不会；激活时也就能一直带着来源。键名加 trace 前缀避免和上面撞。
    private static final String K_TRACE_UID = "traceUid";
    private static final String K_TRACE_CHANNEL = "traceChannel";
    private static final String K_TRACE_ORDER = "traceOrder";

    // ===== 结果条级别（替代原 页面级反馈 View 的散装 setTextColor）=====
    private static final int R_HIDE = -1, R_ACC = 0, R_OK = 1, R_WARN = 2, R_ERR = 3;
    // ===== 设备行状态 =====
    private static final int ST_OK = 0, ST_LOADING = 1, ST_WARN = 2, ST_ERR = 3, ST_OFFLINE = 4;

    // ===== 设备行（本页唯一的连接状态来源）=====
    private View devDot;
    private TextView devStatus;
    private TextView devSub;
    private ImageView devMore;
    // ===== 未连接引导卡（硬前提前置到这里，不再埋在最底部 mono 里）=====
    private LinearLayout guideCard;
    // ===== 主卡 / 输入区 / 成功卡 / 购买行 =====
    private LinearLayout mainCard;
    private LinearLayout inputSection;
    private LinearLayout okCard;
    private LinearLayout buyRow;
    // ===== 卡内结果条（替代原 页面级反馈 View）=====
    private LinearLayout resultBar;
    private TextView resultMark;
    private TextView resultText;

    private EditText[] boxes = new EditText[4];
    private String deviceId = "";
    private String deviceId4 = "";
    /** 防止自动续传在同一设备上反复触发 */
    private String resumedRedeem = "";
    private String resumedCode = "";
    /** 四格分发中标记，防止 TextWatcher 递归 */
    private boolean distributing = false;
    // P3/A6：深链溯源参数快照（onBackend 里 clearTrace 会清 prefs，写入成功的事件要带上）
    private String lastTraceUid = "";
    private String lastTraceChannel = "";
    private String lastTraceOrder = "";

    // ===== 深链自动填码（evsched://activate?code=XXXX）与按钮状态 =====
    private Button activateBtn;
    /** 深链带来的兑换码；空串表示本次进入没有深链 */
    private String deepLinkCode = "";
    /** 深链兑换码只自动触发一次激活，防止回环 */
    private boolean deepLinkConsumed = false;
    private final Handler btnHandler = new Handler();
    private final Runnable btnReset = new Runnable() {
        @Override public void run() { resetBtn(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);

        // ===== 顶栏 + 右侧「常见问题」=====
        ViewGroup header = Ui.header(this, "高级版");
        header.addView(buildHeaderMore(), headerMoreLp());
        root.addView(header);
        root.addView(Ui.space(this, Ui.GAP_XS));

        // ===== 成功卡（默认隐藏；成功后显示在最上方，同时收起输入区）=====
        okCard = buildOkCard();
        okCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.bottomMargin = Ui.dp(this, Ui.GAP_SM);
        root.addView(okCard, okLp);

        // ===== 主卡：设备行 + 输入区（本页主角，首屏第一张）=====
        mainCard = Ui.card(this);
        mainCard.addView(buildDeviceRow());
        guideCard = buildGuideCard();
        guideCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gLp.topMargin = Ui.dp(this, Ui.GAP_MD);
        mainCard.addView(guideCard, gLp);
        mainCard.addView(Ui.space(this, Ui.GAP_MD));
        mainCard.addView(Ui.divider(this));
        mainCard.addView(Ui.space(this, Ui.GAP_MD));

        inputSection = new LinearLayout(this);
        inputSection.setOrientation(LinearLayout.VERTICAL);
        inputSection.addView(Ui.textMedium(this, "输入 4 位兑换码", Ui.SP_TITLE, Ui.TEXT));
        inputSection.addView(subText("在爱发电订单里查看", true));
        inputSection.addView(Ui.space(this, Ui.GAP_MD));
        inputSection.addView(buildCodeBoxes());
        inputSection.addView(Ui.space(this, Ui.GAP_SM));
        inputSection.addView(subText("支持长按粘贴，自动分格、自动大写", false));
        resultBar = buildResultBar();
        resultBar.setVisibility(View.GONE);
        inputSection.addView(resultBar);
        activateBtn = Ui.button(this, "一键激活", true, new View.OnClickListener() {
            @Override public void onClick(View v) { activate(); }
        });
        inputSection.addView(activateBtn);
        mainCard.addView(inputSection);
        root.addView(mainCard);

        // ===== 购买入口（次级：主按钮位置留给「一键激活」）=====
        buyRow = buildBuyRow();
        LinearLayout.LayoutParams buyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        buyLp.topMargin = Ui.dp(this, Ui.GAP_MD);
        root.addView(buyRow, buyLp);

        setContentView(Ui.wrapWithBottomBar(this, root, 2));

        root.setFocusableInTouchMode(true);
        root.requestFocus();

        loadDeviceId();
        handleDeepLink(getIntent());
        // P3（§4.4-E2）：激活页此前没有 pageView，是 App 侧访问埋点的最大盲区
        Analytics.pageView(this, "/apk/activate");
    }

    // ======================= 顶栏 / 次级入口 =======================

    private View buildHeaderMore() {
        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vertical);
        more.setColorFilter(Ui.TEXT);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setContentDescription("常见问题");
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFaq(); }
        });
        return more;
    }

    private FrameLayout.LayoutParams headerMoreLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = Ui.dp(this, 4);
        return lp;
    }

    /** 原等宽字体说明两段（前置条件 + 说明）压成 4 问 4 答；硬前提已提到未连接引导卡 */
    private void showFaq() {
        new AlertDialog.Builder(this)
                .setTitle("常见问题")
                .setMessage("兑换码怎么来？\n"
                        + "在爱发电下单后自动发放，4 位大写字母或数字。\n\n"
                        + "一个兑换码能激活几台手环？\n"
                        + "一台。激活码跟随手环，换手机不用重新激活。\n\n"
                        + "激活码多长？需要我手输吗？\n"
                        + "18 位数字，由服务端按你的设备 ID 生成，本页自动写入。\n\n"
                        + "iOS 能用吗？\n"
                        + "暂不支持，需要安卓手机。")
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 设备行 ⋯：两个都依赖连接的动作。
     * ⚠️ 未连接时整个 ⋯ 隐藏 —— ringBand() 在 hasNode()==false 时必然失败，
     *    把「不可执行的动作」摆成按钮 = 引导用户去撞墙；此时唯一正确动作是「去首页连接」。
     */
    private void showDevMenu() {
        final String[] items = {"重新读取设备ID", "呼叫手环（响铃找表）"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            loadDeviceId();
                        } else {
                            callBand();
                        }
                    }
                })
                .show();
    }

    // ======================= 设备行 =======================

    private View buildDeviceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        devDot = new View(this);
        devDot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)));
        devDot.setBackgroundDrawable(Ui.round(Ui.MUTED, 4, 0, this));
        row.addView(devDot);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        row.addView(col, colLp);

        devStatus = Ui.textMedium(this, "正在读取设备ID…", Ui.SP_BODY, Ui.MUTED);
        devStatus.setSingleLine(true);
        devStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(devStatus);

        devSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        devSub.setPadding(0, Ui.dp(this, 2), 0, 0);
        devSub.setVisibility(View.GONE);
        col.addView(devSub);

        devMore = new ImageView(this);
        devMore.setImageResource(R.drawable.ic_more_vertical);
        devMore.setColorFilter(Ui.MUTED);
        devMore.setScaleType(ImageView.ScaleType.CENTER);
        devMore.setContentDescription("设备操作");
        devMore.setClickable(true);
        devMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDevMenu(); }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN));
        mlp.leftMargin = Ui.dp(this, Ui.GAP_XS);
        row.addView(devMore, mlp);

        return row;
    }

    /** 设备行唯一写入口 —— 圆点颜色 + 主文案 + 副文案一处收敛 */
    private void setDeviceState(int state, String main, String sub) {
        int dotColor, txtColor;
        switch (state) {
            case ST_OK:      dotColor = Ui.OK;     txtColor = Ui.OK;     break;
            case ST_LOADING: dotColor = Ui.ACCENT; txtColor = Ui.ACCENT; break;
            case ST_WARN:    dotColor = Ui.WARN;   txtColor = Ui.WARN;   break;
            case ST_ERR:     dotColor = Ui.ERR;    txtColor = Ui.ERR;    break;
            default:         dotColor = Ui.MUTED;  txtColor = Ui.WARN;   break;
        }
        if (devDot != null) {
            devDot.setBackgroundDrawable(Ui.round(dotColor, 4, 0, this));
        }
        if (devStatus != null) {
            devStatus.setText(main);
            devStatus.setTextColor(txtColor);
        }
        if (devSub != null) {
            if (TextUtils.isEmpty(sub)) {
                devSub.setText("");
                devSub.setVisibility(View.GONE);
            } else {
                devSub.setText(sub);
                devSub.setVisibility(View.VISIBLE);
            }
        }
        // 设备动作仅在有连接时有意义（见 showDevMenu 注释）
        if (devMore != null) {
            devMore.setVisibility(SyncEngine.get(this).hasNode() ? View.VISIBLE : View.GONE);
        }
    }

    // ======================= 未连接引导卡 =======================

    private LinearLayout buildGuideCard() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        g.setBackgroundDrawable(Ui.round(Ui.WARN_LIGHT, Ui.R_CTRL, 0, this));

        g.addView(Ui.textMedium(this, "激活前需要先连上手环", Ui.SP_BODY, Ui.TEXT));
        g.addView(Ui.space(this, Ui.GAP_SM));
        g.addView(bullet("手机已安装「小米运动健康」并连接手环"));
        g.addView(bullet("手环已安装 EV 课程表"));
        g.addView(Ui.space(this, Ui.GAP_MD));
        g.addView(Ui.button(this, "去首页连接", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(FastActivateActivity.this, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
        }));
        return g;
    }

    /** 未连接引导卡的显隐（唯一入口） */
    private void setGuideVisible(boolean visible) {
        if (guideCard != null) {
            guideCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private TextView bullet(String s) {
        TextView t = Ui.text(this, "· " + s, Ui.SP_CAPTION, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 3), 0, 0);
        return t;
    }

    // ======================= 结果条 / 成功卡 / 购买行 =======================

    private LinearLayout buildResultBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        bar.setBackgroundDrawable(Ui.round(Ui.ACCENT_LIGHT, Ui.R_CTRL, 0, this));

        resultMark = Ui.textMedium(this, "·", Ui.SP_BODY, Ui.ACCENT);
        bar.addView(resultMark);

        resultText = Ui.text(this, "", 12.5f, Ui.ACCENT, false);
        Ui.setLineHeight(this, resultText, 12.5f, Ui.LH_BODY);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, Ui.GAP_SM);
        bar.addView(resultText, lp);
        return bar;
    }

    private LinearLayout buildOkCard() {
        LinearLayout card = Ui.card(this);

        TextView badge = Ui.textMedium(this, "\u2713", 24f, Ui.OK);
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundDrawable(Ui.round(Ui.OK_LIGHT, 24, 0, this));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = Ui.dp(this, 12);
        card.addView(badge, blp);

        TextView title = Ui.textMedium(this, "激活成功", Ui.SP_TITLE, Ui.TEXT);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        tlp.topMargin = Ui.dp(this, Ui.GAP_MD);
        card.addView(title, tlp);

        okSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        okSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.topMargin = Ui.dp(this, Ui.GAP_XS);
        card.addView(okSub, slp);

        Button home = Ui.button(this, "回首页", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(FastActivateActivity.this, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(this, Ui.GAP_MD);
        card.addView(home, hlp);
        return card;
    }

    private TextView okSub;

    private LinearLayout buildBuyRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        r.setBackgroundDrawable(Ui.round(Ui.CARD, Ui.R_CARD, Ui.LINE, this));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBuy(); }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        r.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView t1 = Ui.text(this, "还没有兑换码？前往爱发电购买", Ui.SP_BODY, Ui.TEXT, false);
        col.addView(t1);
        TextView t2 = Ui.text(this, "4 位大写字母或数字", Ui.SP_CAPTION, Ui.MUTED, false);
        t2.setPadding(0, Ui.dp(this, 1), 0, 0);
        col.addView(t2);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Ui.MUTED);
        r.addView(chev, new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        return r;
    }

    /**
     * 结果条唯一写入口。原实现是散在 8 处的 页面级反馈 View.setText + setTextColor，
     * 且与设备卡状态行构成两条反馈通道 —— 现在全部收敛到卡内这一条。
     */
    private void showResult(int level, String msg) {
        if (resultBar == null) {
            return;
        }
        if (level == R_HIDE || TextUtils.isEmpty(msg)) {
            resultBar.setVisibility(View.GONE);
            return;
        }
        int bg, fg;
        String mark;
        switch (level) {
            case R_OK:   bg = Ui.OK_LIGHT;     fg = Ui.OK;     mark = "\u2713"; break;
            case R_WARN: bg = Ui.WARN_LIGHT;   fg = Ui.WARN;   mark = "!";      break;
            case R_ERR:  bg = Ui.CARD2;        fg = Ui.ERR;    mark = "\u2715"; break;
            default:     bg = Ui.ACCENT_LIGHT; fg = Ui.ACCENT; mark = "\u22EF"; break;
        }
        resultBar.setBackgroundDrawable(Ui.round(bg, Ui.R_CTRL, 0, this));
        if (resultMark != null) {
            resultMark.setText(mark);
            resultMark.setTextColor(fg);
        }
        if (resultText != null) {
            resultText.setText(msg);
            resultText.setTextColor(fg);
        }
        resultBar.setVisibility(View.VISIBLE);
    }

    private TextView subText(String s, boolean withTopGap) {
        TextView t = Ui.text(this, s, Ui.SP_CAPTION, Ui.MUTED, false);
        if (withTopGap) {
            t.setPadding(0, Ui.dp(this, 3), 0, 0);
        }
        return t;
    }

    // ======================= 深链自动填码 =======================

    /**
     * 处理 evsched://activate?code=XXXX&u=&c=&o= 深链：自动填入四格，
     * 等「设备ID就绪」或「确认手环未连接」后自动触发一次激活（见 maybeDeepLinkActivate）。
     * P1（§4.1）：u（用户识别码）/ c（渠道）/ o（订单号）会被存下来随激活上报；老链接（只有 code）照样能用。
     */
    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        Uri data = intent.getData();
        if (!"activate".equals(data.getHost())) {
            return;
        }
        String code = data.getQueryParameter("code");
        if (code == null) {
            return;
        }
        code = code.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (code.length() != 4) {
            return;
        }
        deepLinkCode = code;
        deepLinkConsumed = false;
        // P1（§4.1）：把链接里的溯源参数存下来（u=用户识别码 / c=渠道 / o=订单号），
        // 激活时随请求上报 —— 这样「链接被点开 → 激活」能确定性地归到同一订单/渠道。
        // 只保留白名单字符，链接来自私信，必须假定是脏的；没有的键写空串（= 老链接，行为不变）。
        pending().edit()
                .putString(K_TRACE_UID, sanitizeTrace(data.getQueryParameter("u"), 32))
                .putString(K_TRACE_CHANNEL, sanitizeTrace(data.getQueryParameter("c"), 32))
                .putString(K_TRACE_ORDER, sanitizeTrace(data.getQueryParameter("o"), 64))
                .apply();
        fillBoxes(code);
        showResult(R_ACC, "已从链接自动填入兑换码 " + code
                + (deviceId.length() > 0 ? "，正在激活…" : "，等待读取设备ID…"));
        maybeDeepLinkActivate();
    }

    /** 深链参数清洗：只留 [A-Za-z0-9_-] 并限长（链接来自私信，一律按不可信输入处理） */
    private static String sanitizeTrace(String v, int max) {
        if (v == null) return "";
        String s = v.replaceAll("[^A-Za-z0-9_-]", "");
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 清掉已用完的深链溯源参数，避免用户之后再手输别的码时被错误归因 */
    private void clearTrace() {
        pending().edit()
                .putString(K_TRACE_UID, "")
                .putString(K_TRACE_CHANNEL, "")
                .putString(K_TRACE_ORDER, "")
                .apply();
    }

    /** 深链兑换码自动激活：设备ID已就绪或确认未连接（走暂存）时才触发，且只触发一次 */
    private void maybeDeepLinkActivate() {
        if (deepLinkCode.length() == 0 || deepLinkConsumed) {
            return;
        }
        if (collectCode().length() != 4) {
            return;
        }
        boolean node = SyncEngine.get(this).hasNode();
        if (node && deviceId.length() == 0) {
            return; // 设备ID还在读取，等 onReply 后再触发
        }
        deepLinkConsumed = true;
        activate();
    }

    // ======================= 按钮状态 =======================

    /** 已连接 → 「一键激活」；未连接 → 「暂存兑换码」（把暂存能力显式说出来） */
    private void updateActivateBtn() {
        if (activateBtn == null) {
            return;
        }
        btnHandler.removeCallbacks(btnReset);
        activateBtn.setText(SyncEngine.get(this).hasNode() ? "一键激活" : "暂存兑换码");
        activateBtn.setEnabled(true);
    }

    /** 点「一键激活」后按钮进入激活中状态（文字变化 + 禁点），15 秒无回包强制恢复 */
    private void setBtnBusy() {
        if (activateBtn != null) {
            activateBtn.setText("激活中…");
            activateBtn.setEnabled(false);
        }
        btnHandler.removeCallbacks(btnReset);
        btnHandler.postDelayed(btnReset, 15000);
    }

    private void resetBtn() {
        updateActivateBtn();
    }

    // ======================= 呼叫手环 =======================

    /** 呼叫手环：双通道（① 系统通知卡 + ② EV {@code action=call}），一路断开另一路兜底。 */
    private void callBand() {
        if (!SyncEngine.get(this).hasNode()) {
            showResult(R_WARN, "手环未连接，无法呼叫。请先回首页连接手环");
            return;
        }
        showResult(R_ACC, "正在呼叫手环…");
        SyncEngine.get(this).ringBand(new SyncEngine.Cb() {
            @Override public void on(final boolean ok, final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (ok) {
                            showResult(R_OK, "已发送呼叫，看一下手环");
                        } else {
                            showResult(R_ERR, "呼叫失败：" + msg);
                        }
                    }
                });
            }
        });
    }

    // ======================= 购买入口 =======================

    private void openBuy() {
        try {
            StringBuilder url = new StringBuilder(BUY_URL).append("?c=apk-fast");
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                url.append("&r=").append(Uri.encode(pi.versionName));
            } catch (Throwable ignored) {
            }
            if (deviceId.length() > 0) {
                url.append("&deviceId=").append(Uri.encode(deviceId));
            }
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, "前往爱发电购买"));
        } catch (Throwable t) {
            showResult(R_WARN, "无法打开浏览器，请手动访问爱发电搜索「EV课程表」购买");
        }
    }

    // ======================= 四格输入 =======================

    private View buildCodeBoxes() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundDrawable(boxBg(false));
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 56), 1f);
            if (i > 0) {
                lp.leftMargin = Ui.dp(this, Ui.GAP_SM);
            }
            cell.setLayoutParams(lp);

            final int idx = i;
            EditText b = new EditText(this);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.SP_DISPLAY);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(Ui.TEXT);
            b.setHintTextColor(Ui.MUTED);
            b.setHint("·");
            b.setGravity(Gravity.CENTER);
            b.setBackgroundDrawable(null);
            b.setSingleLine(true);
            b.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            b.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
            // 聚焦高亮：2dp 主色描边（原实现四格外观完全一致，看不出焦点在哪格）
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    cell.setBackgroundDrawable(boxBg(hasFocus));
                }
            });
            b.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
                @Override public void onTextChanged(CharSequence s, int st, int bf, int af) {
                    if (distributing) {
                        return;
                    }
                    // 粘贴多位 → 从当前格往后分发
                    if (s.length() > 1) {
                        distribute(idx, s.toString());
                        return;
                    }
                    if (s.length() == 1) {
                        String up = s.toString().toUpperCase();
                        if (!up.equals(s.toString())) {
                            distributing = true;
                            boxes[idx].setText(up);
                            distributing = false;
                            boxes[idx].setSelection(1);
                        }
                    }
                    if (s.length() == 1 && idx < 3) {
                        boxes[idx + 1].requestFocus();
                    }
                    // 当前格清空 → 焦点回退一格
                    if (s.length() == 0 && idx > 0) {
                        boxes[idx - 1].requestFocus();
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            cell.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            row.addView(cell);
            boxes[i] = b;
        }
        return row;
    }

    /** 输入格背景：白底 + 2dp 描边（聚焦时主色） */
    private android.graphics.drawable.GradientDrawable boxBg(boolean focused) {
        return Ui.round(Ui.CARD, Ui.R_CTRL, focused ? Ui.ACCENT : Ui.LINE, 2, this);
    }

    /** 把（可能是粘贴进来的）一串字符从第 from 格开始分发到后面的格子 */
    private void distribute(int from, String text) {
        distributing = true;
        String up = text.toUpperCase().replaceAll("[^A-Z0-9]", "");
        int i = from;
        for (int k = 0; k < up.length() && i < 4; k++, i++) {
            boxes[i].setText(String.valueOf(up.charAt(k)));
        }
        distributing = false;
        int last = Math.min(from + up.length(), 4) - 1;
        if (last >= from) {
            boxes[last].requestFocus();
        }
    }

    private String collectCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(boxes[i].getText().toString().trim().toUpperCase());
        }
        return sb.toString();
    }

    private void fillBoxes(String code) {
        distributing = true;
        for (int i = 0; i < 4; i++) {
            boxes[i].setText(i < code.length() ? String.valueOf(code.charAt(i)) : "");
        }
        distributing = false;
    }

    // ======================= 暂存 / 续传 =======================

    private SharedPreferences pending() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void savePending(String redeem, String pendDevice, String code18) {
        pending().edit()
                .putString(K_REDEEM, redeem == null ? "" : redeem)
                .putString(K_DEVICE, pendDevice == null ? "" : pendDevice)
                .putString(K_CODE, code18 == null ? "" : code18)
                .putLong(K_TS, System.currentTimeMillis())
                .apply();
    }

    private void clearPending() {
        pending().edit().clear().apply();
        resumedRedeem = "";
        resumedCode = "";
    }

    /**
     * 尝试续传：有暂存的 18 位码 → 直接补写；只有兑换码 → 自动兑换并写入。
     * 每个凭证在同一 deviceId 上只自动触发一次，失败后靠用户点按钮重试。
     */
    private void tryResume() {
        SharedPreferences p = pending();
        String redeem = p.getString(K_REDEEM, "");
        String pendDev = p.getString(K_DEVICE, "");
        String code = p.getString(K_CODE, "");

        boolean deviceKnown = deviceId.length() > 0;
        boolean deviceMatch = !deviceKnown || pendDev.length() == 0 || pendDev.equals(deviceId);
        if (!deviceMatch) {
            showResult(R_WARN, "检测到未完成的激活，但它属于另一台手环（"
                    + mask(pendDev) + "），请确认后重新输入兑换码");
            return;
        }

        if (code.length() == 18 && deviceKnown && !code.equals(resumedCode)) {
            resumedCode = code;
            showResult(R_ACC, "发现未完成的激活，正在写入手环…");
            writeToBand(code);
            return;
        }

        if (redeem.length() == 4 && deviceKnown && !redeem.equals(resumedRedeem)) {
            resumedRedeem = redeem;
            fillBoxes(redeem);
            showResult(R_ACC, "发现未完成的激活，正在继续…");
            activate();
            return;
        }

        if (!deviceKnown && redeem.length() == 4 && collectCode().length() < 4) {
            fillBoxes(redeem);
            showResult(R_ACC, "兑换码已暂存，连上手环后回到本页会自动继续激活");
        }
    }

    // ======================= 设备ID =======================

    private void loadDeviceId() {
        if (!SyncEngine.get(this).hasNode()) {
            setDeviceState(ST_OFFLINE, "未连接手环",
                    "先填码也行 —— 连上手环回到本页会自动继续激活");
            setGuideVisible(true);
            updateActivateBtn();
            tryResume();
            maybeDeepLinkActivate();
            return;
        }
        setGuideVisible(false);
        setDeviceState(ST_LOADING, "正在读取设备ID…", "");
        updateActivateBtn();
        SyncEngine.get(this).getDeviceId(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)
                            || !"get_device_id".equals(o.optString("action"))) {
                        setDeviceState(ST_ERR, "设备ID读取失败", "手环 EV 版本可能过低，请先更新");
                        return;
                    }
                    deviceId = o.optString("deviceId");
                    deviceId4 = o.optString("deviceId4");
                    boolean fallback = o.optBoolean("fallback", false);
                    String name = SyncEngine.get(FastActivateActivity.this).deviceName;
                    setDeviceState(fallback ? ST_WARN : ST_OK,
                            name.length() > 0 ? "已连接 · " + name : "已连接",
                            "设备ID " + mask(deviceId) + (fallback ? "（临时标识）" : ""));
                    if (fallback) {
                        showResult(R_WARN, "设备标识为临时值，激活后重装应用可能失效");
                    }
                    tryResume();
                    maybeDeepLinkActivate();
                } catch (Throwable t) {
                    setDeviceState(ST_ERR, "设备ID回包无法解析", "");
                }
            }
            @Override public void onTimeout(String hint) {
                setDeviceState(ST_WARN, "设备ID读取超时", hint);
            }
            @Override public void onError(String msg) {
                setDeviceState(ST_ERR, "设备ID读取失败", msg);
            }
        });
    }

    private static String mask(String id) {
        if (id == null || id.length() == 0) {
            return "（空）";
        }
        if (id.length() <= 6) {
            return id;
        }
        return "…" + id.substring(id.length() - 6);
    }

    // ======================= 激活 =======================

    private void activate() {
        String code = collectCode();
        if (!REDEEM.matcher(code).matches()) {
            showResult(R_ERR, "兑换码必须是 4 位大写字母或数字（A-Z, 0-9）");
            return;
        }
        setBtnBusy();
        if (!SyncEngine.get(this).hasNode()) {
            // 未连接：先暂存兑换码，连接后自动继续
            savePending(code, "", null);
            showResult(R_WARN, "手环未连接，兑换码已暂存。连上手环回到本页后会自动继续激活。");
            resetBtn();
            return;
        }
        if (TextUtils.isEmpty(deviceId)) {
            showResult(R_WARN, "还没有取到设备ID，正在重试读取…");
            resetBtn();
            loadDeviceId();
            return;
        }

        showResult(R_ACC, "正在向服务器换取激活码…");

        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
            body.put("redeemCode", code);
            body.put("deviceInfo", deviceInfo());
            // P1（§4.1）：带上深链溯源参数。三者都为空时**一个键都不加** ——
            // 保证「手输激活码」的报文形状与改造前完全一致（服务端按可缺省处理）。
            // ⚠️ 刻意不改 deviceInfo.source（仍是 "apk"）：服务端 activationClient() 靠它判客户端类型，
            //    渠道归因走独立的 channel 字段，两件事不混在一个字段里。
            String tUid = sanitizeTrace(pending().getString(K_TRACE_UID, ""), 32);
            String tChn = sanitizeTrace(pending().getString(K_TRACE_CHANNEL, ""), 32);
            String tOrd = sanitizeTrace(pending().getString(K_TRACE_ORDER, ""), 64);
            if (tUid.length() > 0) body.put("uid", tUid);
            if (tChn.length() > 0) body.put("channel", tChn);
            if (tOrd.length() > 0) body.put("orderNo", tOrd);
        } catch (Throwable ignored) {
        }

        Net.postJson(Net.BASE + "/api/activate", body.toString(), new Net.Cb() {
            @Override public void on(final int httpCode, final String resp) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { onBackend(httpCode, resp); }
                });
            }
        });
    }

    private JSONObject deviceInfo() {
        JSONObject d = new JSONObject();
        try {
            String model = Build.MANUFACTURER + " " + Build.MODEL;
            d.put("model", Build.MODEL);
            d.put("product", model);
            d.put("os", "android");
            d.put("romVersion", Build.VERSION.RELEASE);
            d.put("source", "apk");
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            d.put("appVersion", pi.versionName);
        } catch (Throwable ignored) {
        }
        return d;
    }

    private void onBackend(int httpCode, String resp) {
        if (httpCode < 0 || resp == null) {
            resetBtn();
            showResult(R_ERR, "网络请求失败，请检查网络后重试");
            return;
        }
        String activationCode = null;
        try {
            JSONObject o = new JSONObject(resp);
            if (o.optBoolean("success", false)) {
                activationCode = o.optString("activationCode");
            } else {
                String err = o.optString("error");
                if (TextUtils.isEmpty(err)) {
                    err = "服务器返回失败";
                }
                resetBtn();
                showResult(R_ERR, "激活失败：" + err);
                return;
            }
        } catch (Throwable t) {
            resetBtn();
            showResult(R_ERR, "服务器回包无法解析");
            return;
        }
        if (activationCode == null || activationCode.length() != 18) {
            resetBtn();
            showResult(R_ERR, "服务器没有返回有效的 18 位激活码");
            return;
        }
        // 换码成功先落暂存：写入手环失败也能断点续传
        savePending(collectCode(), deviceId, activationCode);
        // P3/A6：clearTrace 会把溯源参数从 prefs 清掉，先抓快照留给写入成功事件
        lastTraceUid = sanitizeTrace(pending().getString(K_TRACE_UID, ""), 32);
        lastTraceChannel = sanitizeTrace(pending().getString(K_TRACE_CHANNEL, ""), 32);
        lastTraceOrder = sanitizeTrace(pending().getString(K_TRACE_ORDER, ""), 64);
        // P1（§4.1）：溯源参数已经用掉，清掉避免影响后续手输激活的归因
        clearTrace();
        writeToBand(activationCode);
    }

    private void writeToBand(final String code18) {
        showResult(R_ACC, "已获得激活码，正在写入到手环…");
        SyncEngine.get(this).activate(code18, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        String disp = o.optString("displayStatus");
                        String status = o.optString("status");
                        clearPending();
                        fillBoxes("");
                        resetBtn();
                        showActivated(disp.length() > 0 ? disp : status);
                        // P3/A6：App 端激活闭环确认（服务端发码 ✓ + 手环落盘 ✓ 两个节点）。
                        //   dedupeKey 按激活码幂等：同一码的重试成功只记一次。
                        Analytics.event(FastActivateActivity.this, "app_activate_ok",
                                Analytics.p("activation_code", code18,
                                        "uid", lastTraceUid,
                                        "channel", lastTraceChannel,
                                        "order_no", lastTraceOrder),
                                Analytics.dedupeKey(FastActivateActivity.this,
                                        "app_activate_ok", code18));
                    } else {
                        resetBtn();
                        showResult(R_ERR, "手环拒绝激活：" + o.optString("reason")
                                + "\n激活码已暂存，解决后回到本页会自动重试。");
                    }
                } catch (Throwable t) {
                    resetBtn();
                    showResult(R_ERR, "手环回包无法解析：" + json);
                }
            }
            @Override public void onTimeout(String hint) {
                resetBtn();
                showResult(R_WARN, hint + "\n激活码已暂存，重连手环后回到本页会自动补写。");
            }
            @Override public void onError(String msg) {
                resetBtn();
                showResult(R_ERR, "写入失败：" + msg + "\n激活码已暂存，重连手环后回到本页会自动补写。");
            }
        });
    }

    /**
     * 成功态：整块换成「成功卡 + 已激活的设备行」。
     * 原实现只是往 页面级反馈 View 写一行 12.5sp 绿字 —— 一个付费转化页的终点必须有明确的样子和出口。
     */
    private void showActivated(String disp) {
        if (okCard != null) {
            if (okSub != null) {
                okSub.setText(disp.length() > 0 ? disp + " · 共 365 天" : "激活码已写入手环");
            }
            okCard.setVisibility(View.VISIBLE);
        }
        if (inputSection != null) {
            inputSection.setVisibility(View.GONE);
        }
        if (buyRow != null) {
            buyRow.setVisibility(View.GONE);
        }
        setGuideVisible(false);
        String name = SyncEngine.get(this).deviceName;
        setDeviceState(ST_OK,
                name.length() > 0 ? "已激活 · " + name : "已激活",
                "设备ID " + mask(deviceId) + " · 可在手环「EV 课程表 → 高级版」查看");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        loadDeviceId();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // App 已在前台时再次点深链 → 走这里（不重建 Activity）
        setIntent(intent);
        handleDeepLink(intent);
    }
}
