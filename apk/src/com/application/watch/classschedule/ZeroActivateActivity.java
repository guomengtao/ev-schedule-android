package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 「零操作激活」页（方案的 G1 + 通道 B 同页）。
 *
 * 它解决的不是「少点两下」，而是两件现有页面做不到的事：
 *   ① 手环重置/换机后 deviceId 变了，旧 18 位码失效、UUID 也变了 → 以前只能找人工；
 *      这里用**不变的** BLE MAC / nodeId 认出「这台手环买过」，当场按新 deviceId 重签一张码。
 *   ② 从本页点「去支付」的用户，付款后不用回来输兑换码 —— 爱发电留言框预填的会话号
 *      经 webhook → Supabase Realtime 推回来，码自动写进手环。
 *
 * 两条通道**平级**（方案 §1.4）：
 *   通道 A 去支付（自动激活）—— 只对从本页出发的付款成立
 *   通道 B 4 位兑换码       —— 对所有其他用户成立（别处买、代买、老用户重置）
 * 通道 B 不是兜底，是常态入口；所以两者同屏并列，不做「自动失败才给你输」。
 *
 * 刻意与 {@link FastActivateActivity} 分开成独立页：那一页 1061 行，承载深链、断点续传、
 * 写码与全套埋点，往里塞支付状态机会把既有验证主链路的回归风险抬起来，收益却为零。
 */
public class ZeroActivateActivity extends Activity {

    private static final Pattern REDEEM = Pattern.compile("^[A-Z0-9]{4}$");
    private static final String PREF = "ev_zero_activate";

    private static final int R_HIDE = -1, R_ACC = 0, R_OK = 1, R_WARN = 2, R_ERR = 3;
    private static final int ST_OK = 0, ST_LOADING = 1, ST_WARN = 2, ST_ERR = 3, ST_OFFLINE = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int lastThemeVersion = 0;

    // 设备行（本页唯一的连接状态来源，与快速激活页同一套语义）
    private View devDot;
    private TextView devStatus;
    private TextView devSub;

    private LinearLayout guideCard;
    private LinearLayout detectSection;      // 「正在识别这台手环」
    private LinearLayout ownedSection;       // 已购：自动恢复
    private LinearLayout notOwnedSection;    // 未购：双通道
    private LinearLayout waitSection;        // 支付等待中
    private LinearLayout okCard;             // 成功
    private TextView okSub;

    private LinearLayout resultBar;
    private TextView resultMark;
    private TextView resultText;

    private EditText codeInput;
    private Button payBtn;
    private Button manualBtn;
    private TextView waitHint;

    private DeviceProbe.Result probe;
    /**
     * 仅 adb 显式传入时生效的调试钩子（band-less 机台上验证通道 A 用）：
     *   adb shell am start -n com.application.watch.classschedule/.ZeroActivateActivity \
     *       --es __probe_mac 0434C306B885 --es __probe_node 913024617 --es __probe_devid test0001
     * 普通启动（点入口/深链）拿不到这些 extra，走真实探针，不受影响。
     */
    private DeviceProbe.Result debugProbe;
    /** 仅调试用：给定 sid 直接验 Realtime 传输（见 PaySession.startWithSid） */
    private String debugSid = "";
    private PaySession pay;
    private boolean busy;
    private boolean activated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "激活高级版"));
        root.addView(Ui.space(this, Ui.GAP_XS));

        okCard = buildOkCard();
        okCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.bottomMargin = Ui.dp(this, Ui.GAP_SM);
        root.addView(okCard, okLp);

        LinearLayout mainCard = Ui.card(this);
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

        detectSection = buildDetectSection();
        mainCard.addView(detectSection);

        ownedSection = buildOwnedSection();
        ownedSection.setVisibility(View.GONE);
        mainCard.addView(ownedSection);

        notOwnedSection = buildNotOwnedSection();
        notOwnedSection.setVisibility(View.GONE);
        mainCard.addView(notOwnedSection);

        waitSection = buildWaitSection();
        waitSection.setVisibility(View.GONE);
        mainCard.addView(waitSection);

        resultBar = buildResultBar();
        resultBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = Ui.dp(this, Ui.GAP_MD);
        mainCard.addView(resultBar, rLp);

        root.addView(mainCard);
        root.addView(Ui.space(this, Ui.GAP_MD));
        root.addView(buildFootnote());

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/activate-zero");
        readDebugProbe(getIntent());
    }

    /** 调试钩子：只有 adb 显式带 extra 时才构造假探针（见 debugProbe 字段注释） */
    private void readDebugProbe(Intent it) {
        if (it == null || !it.hasExtra("__probe_mac")) {
            return;
        }
        DeviceProbe.Result r = new DeviceProbe.Result();
        r.mac = DeviceProbe.normalizeMac(it.getStringExtra("__probe_mac"));
        r.macRaw = DeviceProbe.prettyMac(r.mac);
        r.nodeId = it.getStringExtra("__probe_node") == null ? "" : it.getStringExtra("__probe_node");
        r.deviceId = it.getStringExtra("__probe_devid") == null ? "" : it.getStringExtra("__probe_devid");
        r.deviceName = "调试探针";
        debugProbe = r;
        String sid = it.getStringExtra("__probe_sid");
        if (sid != null) {
            debugSid = sid.trim();
        }
    }

    // ======================= 卡片骨架 =======================

    private LinearLayout buildDetectSection() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(Ui.textMedium(this, "正在识别这台手环", Ui.SP_BODY, Ui.TEXT));
        s.addView(sub("用不变的蓝牙地址判断它以前买过没有", true));
        s.addView(Ui.space(this, Ui.GAP_SM));
        return s;
    }

    private LinearLayout buildOwnedSection() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(Ui.textMedium(this, "这台手环已经买过高级版", Ui.SP_BODY, Ui.TEXT));
        s.addView(sub("正在按当前设备重新签发激活码，不需要再付一次", true));
        s.addView(Ui.space(this, Ui.GAP_MD));
        Button again = Ui.button(this, "重新写入", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                queryOwned("manual");
            }
        });
        s.addView(again);
        return s;
    }

    private LinearLayout buildNotOwnedSection() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(Ui.textMedium(this, "激活高级版", Ui.SP_BODY, Ui.TEXT));
        s.addView(sub("两种方式，选一个即可", true));
        s.addView(Ui.space(this, Ui.GAP_MD));

        // ── 通道 A ──
        payBtn = Ui.button(this, "去支付 · 付完自动激活", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPay();
            }
        });
        s.addView(payBtn);
        s.addView(sub("打开爱发电付款，回来不用再输任何东西", true));

        // ── 分隔 ──
        s.addView(Ui.space(this, Ui.GAP_MD));
        s.addView(Ui.divider(this));
        s.addView(Ui.space(this, Ui.GAP_MD));

        // ── 通道 B ──
        s.addView(Ui.textMedium(this, "已经付过款？输入 4 位兑换码", Ui.SP_BODY, Ui.TEXT));
        s.addView(sub("在爱发电订单详情里查看，大写字母或数字", true));
        s.addView(Ui.space(this, Ui.GAP_SM));
        s.addView(buildCodeInput());
        s.addView(Ui.space(this, Ui.GAP_SM));
        Button go = Ui.button(this, "激活", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitCode();
            }
        });
        s.addView(go);
        return s;
    }

    private LinearLayout buildWaitSection() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.addView(Ui.textMedium(this, "等待付款结果", Ui.SP_BODY, Ui.TEXT));
        waitHint = sub("已经打开浏览器，付完款会自动激活，不用回来点任何东西", true);
        s.addView(waitHint);
        s.addView(Ui.space(this, Ui.GAP_MD));
        manualBtn = Ui.button(this, "我已付款，检查一下", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pay != null) {
                    setFeedback(R_ACC, "正在向服务器核对订单…");
                    pay.pickup("manual");
                }
            }
        });
        s.addView(manualBtn);
        s.addView(Ui.space(this, Ui.GAP_SM));

        LinearLayout again = new LinearLayout(this);
        again.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = Ui.text(this, "没付成？换回 4 位兑换码", Ui.SP_CAPTION, Ui.ACCENT, false);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pay != null) {
                    pay.cancel();
                    pay = null;
                }
                waitSection.setVisibility(View.GONE);
                notOwnedSection.setVisibility(View.VISIBLE);
                setFeedback(R_HIDE, null);
            }
        });
        again.addView(t);
        s.addView(again);
        return s;
    }

    private View buildCodeInput() {
        codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4),
                new InputFilter.AllCaps()});
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        codeInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setLetterSpacing(0.35f);
        codeInput.setHint("ABCD");
        codeInput.setHintTextColor(Ui.MUTED);
        codeInput.setTextColor(Ui.TEXT);
        codeInput.setBackgroundDrawable(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        codeInput.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        codeInput.setLayoutParams(lp);
        // 粘贴时容错：去掉空格与连字符、转大写
        codeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String raw = e.toString().toUpperCase();
                String clean = raw.replaceAll("[^A-Z0-9]", "");
                if (!clean.equals(raw)) {
                    codeInput.setText(clean);
                    codeInput.setSelection(clean.length());
                }
            }
        });
        return codeInput;
    }

    private View buildFootnote() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        f.addView(Ui.text(this, "找不到兑换码？在爱发电「我的订单」里点开那一单查看备注", Ui.SP_CAPTION, Ui.MUTED, false));
        f.addView(Ui.space(this, Ui.GAP_XS));
        TextView old = Ui.text(this, "也可以走原来的激活页（输入 4 位码 + 深链自动填）", Ui.SP_CAPTION, Ui.ACCENT, false);
        old.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        old.setClickable(true);
        old.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ZeroActivateActivity.this, FastActivateActivity.class));
            }
        });
        f.addView(old);
        return f;
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

        devStatus = Ui.textMedium(this, "正在检测手环…", Ui.SP_BODY, Ui.MUTED);
        devStatus.setSingleLine(true);
        devStatus.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(devStatus);

        devSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        devSub.setPadding(0, Ui.dp(this, 2), 0, 0);
        devSub.setVisibility(View.GONE);
        col.addView(devSub);
        return row;
    }

    private void setDeviceState(int state, String mainText, String subText) {
        int dotColor, txtColor;
        switch (state) {
            case ST_OK:
                dotColor = Ui.OK;
                txtColor = Ui.OK;
                break;
            case ST_LOADING:
                dotColor = Ui.ACCENT;
                txtColor = Ui.ACCENT;
                break;
            case ST_WARN:
                dotColor = Ui.WARN;
                txtColor = Ui.WARN;
                break;
            case ST_ERR:
                dotColor = Ui.ERR;
                txtColor = Ui.ERR;
                break;
            default:
                dotColor = Ui.MUTED;
                txtColor = Ui.WARN;
                break;
        }
        if (devDot != null) {
            devDot.setBackgroundDrawable(Ui.round(dotColor, 4, 0, this));
        }
        if (devStatus != null) {
            devStatus.setText(mainText);
            devStatus.setTextColor(txtColor);
        }
        if (devSub != null) {
            if (TextUtils.isEmpty(subText)) {
                devSub.setText("");
                devSub.setVisibility(View.GONE);
            } else {
                devSub.setText(subText);
                devSub.setVisibility(View.VISIBLE);
            }
        }
    }

    private LinearLayout buildGuideCard() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        g.setBackgroundDrawable(Ui.round(Ui.WARN_LIGHT, Ui.R_CTRL, 0, this));
        g.addView(Ui.textMedium(this, "先把手机和手环连上", Ui.SP_BODY, Ui.TEXT));
        g.addView(Ui.space(this, Ui.GAP_SM));
        g.addView(bullet("手机已安装「小米运动健康」并连接手环"));
        g.addView(bullet("手环已安装 EV 课程表"));
        g.addView(Ui.space(this, Ui.GAP_MD));
        g.addView(Ui.button(this, "去首页连接", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(ZeroActivateActivity.this, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
        }));
        return g;
    }

    private TextView bullet(String s) {
        TextView t = Ui.text(this, "· " + s, Ui.SP_CAPTION, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 3), 0, 0);
        return t;
    }

    private TextView sub(String s, boolean topGap) {
        TextView t = Ui.text(this, s, Ui.SP_CAPTION, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, topGap ? 4 : 0), 0, 0);
        return t;
    }

    // ======================= 结果条 =======================

    private LinearLayout buildResultBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        bar.setBackgroundDrawable(Ui.round(Ui.ACCENT_LIGHT, Ui.R_CTRL, 0, this));

        resultMark = Ui.textMedium(this, "\u22EF", Ui.SP_BODY, Ui.ACCENT);
        resultMark.setWidth(Ui.dp(this, 20));
        bar.addView(resultMark);

        resultText = Ui.text(this, "", Ui.SP_CAPTION, Ui.ACCENT, false);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, Ui.GAP_XS);
        bar.addView(resultText, lp);
        return bar;
    }

    private void setFeedback(int level, String msg) {
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
            case R_OK:
                bg = Ui.OK_LIGHT;
                fg = Ui.OK;
                mark = "\u2713";
                break;
            case R_WARN:
                bg = Ui.WARN_LIGHT;
                fg = Ui.WARN;
                mark = "!";
                break;
            case R_ERR:
                bg = Ui.CARD2;
                fg = Ui.ERR;
                mark = "\u2715";
                break;
            default:
                bg = Ui.ACCENT_LIGHT;
                fg = Ui.ACCENT;
                mark = "\u22EF";
                break;
        }
        resultBar.setBackgroundDrawable(Ui.round(bg, Ui.R_CTRL, 0, this));
        resultBar.setVisibility(View.VISIBLE);
        resultMark.setText(mark);
        resultMark.setTextColor(fg);
        resultText.setText(msg);
        resultText.setTextColor(fg);
    }

    private LinearLayout buildOkCard() {
        LinearLayout c = Ui.card(this);
        c.setBackgroundDrawable(Ui.round(Ui.OK_LIGHT, Ui.R_CARD, 0, this));
        TextView t = Ui.textMedium(this, "高级版已激活", Ui.SP_TITLE, Ui.OK);
        c.addView(t);
        okSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.OK, false);
        okSub.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(okSub);
        c.addView(Ui.space(this, Ui.GAP_MD));
        c.addView(Ui.button(this, "完成", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        return c;
    }

    // ======================= 主流程 =======================

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.needRecreate(this, lastThemeVersion)) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        detect();
    }

    @Override
    protected void onDestroy() {
        if (pay != null) {
            pay.cancel();
            pay = null;
        }
        super.onDestroy();
    }

    /** 第 1、2 步：连上了吗 → 能不能读到设备 ID / 蓝牙地址 */
    private void detect() {
        if (activated) {
            return;
        }
        hideAllSections();
        if (debugProbe != null) {
            probe = debugProbe;
            guideCard.setVisibility(View.GONE);
            detectSection.setVisibility(View.VISIBLE);
            setDeviceState(ST_WARN, "调试探针（未连手环）",
                    "蓝牙 " + probe.macRaw + " · 手环编号 " + probe.nodeId);
            if (!TextUtils.isEmpty(debugSid)) {
                startPay();
                return;
            }
            queryOwned("auto");
            return;
        }
        SyncEngine eng = SyncEngine.get(this);
        if (!eng.hasNode()) {
            setDeviceState(ST_OFFLINE, "未连接手环", "激活码要写进手环，所以得先连上它");
            guideCard.setVisibility(View.VISIBLE);
            setFeedback(R_HIDE, null);
            return;
        }
        guideCard.setVisibility(View.GONE);
        detectSection.setVisibility(View.VISIBLE);
        setDeviceState(ST_LOADING, "正在读取设备信息…", "");
        if (busy) {
            return;
        }
        busy = true;
        eng.getDeviceId(new SyncEngine.Reply() {
            @Override
            public void onReply(String json) {
                busy = false;
                String did = "";
                String did4 = "";
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        did = o.optString("deviceId", "");
                        did4 = o.optString("deviceId4", "");
                    }
                } catch (Throwable ignored) {
                }
                finishProbe(did, did4);
            }

            @Override
            public void onTimeout(String hint) {
                busy = false;
                finishProbe("", "");
            }

            @Override
            public void onError(String msg) {
                busy = false;
                finishProbe("", "");
            }
        });
    }

    private void finishProbe(String freshDeviceId, String did4) {
        probe = DeviceProbe.collect(this);
        if (freshDeviceId.length() > 0) {
            probe.deviceId = freshDeviceId;
        }
        String name = SyncEngine.get(this).deviceName;
        StringBuilder subText = new StringBuilder();
        if (probe.mac.length() > 0) {
            subText.append("蓝牙 ").append(DeviceProbe.prettyMac(probe.mac));
        } else if (probe.macDenied) {
            subText.append("蓝牙地址未授权（不影响识别）");
        } else if (probe.macRandom) {
            subText.append("蓝牙地址为随机地址（不可作依据）");
        }
        if (probe.nodeId.length() > 0) {
            if (subText.length() > 0) {
                subText.append(" · ");
            }
            subText.append("手环编号 ").append(probe.nodeId);
        } else if (did4.length() > 0) {
            if (subText.length() > 0) {
                subText.append(" · ");
            }
            subText.append("手环编号 …").append(did4);
        }
        setDeviceState(ST_OK, name.length() > 0 ? "已连接 · " + name : "已连接", subText.toString());

        if (!probe.hasStableKey()) {
            // 两个稳定标识都没拿到：不猜，直接给确定能用的那条路
            setDeviceState(ST_WARN, name.length() > 0 ? "已连接 · " + name : "已连接",
                    "没认出这台手环");
            showNotOwned("没认出这台手环，请用 4 位兑换码");
            return;
        }
        queryOwned("auto");
    }

    /** 第 3 步：这台手环买过没有？买过就直接签码 */
    private void queryOwned(final String why) {
        if (probe == null) {
            return;
        }
        detectSection.setVisibility(View.VISIBLE);
        if ("auto".equals(why)) {
            setFeedback(R_ACC, "正在查询购买记录…");
        } else {
            setFeedback(R_ACC, "正在重新核对购买记录…");
        }
        String url = ZeroCfg.API_DEVICE
                + "&key=" + Uri.encode(probe.mac)
                + "&nodeId=" + Uri.encode(probe.nodeId)
                + "&deviceId=" + Uri.encode(probe.deviceId);
        Net.get(url, new Net.Cb() {
            @Override
            public void on(final int httpCode, final String resp) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onOwnedResp(httpCode, resp, why);
                    }
                });
            }
        });
    }

    private void onOwnedResp(int httpCode, String resp, String why) {
        if (httpCode < 0 || TextUtils.isEmpty(resp)) {
            setFeedback(R_WARN, "网络不太好，暂时查不到购买记录。你可以直接用 4 位兑换码激活");
            showNotOwned(null);
            return;
        }
        boolean owned = false;
        String code18 = "";
        String reason = "";
        try {
            JSONObject o = new JSONObject(resp);
            owned = o.optBoolean("owned", false);
            code18 = o.optString("activationCode", "");
            reason = o.optString("reason", "");
        } catch (Throwable t) {
            setFeedback(R_WARN, "没拿到激活码，请用 4 位兑换码");
            showNotOwned(null);
            return;
        }
        if (owned && code18.length() == 18) {
            detectSection.setVisibility(View.GONE);
            ownedSection.setVisibility(View.VISIBLE);
            setFeedback(R_OK, "已认出这台手环，正在写入手环…");
            writeToBand(code18, "", true);
            return;
        }
        if (owned && "need_device_id".equals(reason)) {
            // 有过购买记录但手环没报设备 ID（老版本 EV）：提示升级手环应用
            setFeedback(R_WARN, "这台手环买过高级版，但还没认出它。先回首页同步一次，再回来");
            showNotOwned("也可以先用 4 位兑换码激活");
            return;
        }
        if (owned) {
            setFeedback(R_WARN, "购买记录存在但无法自动恢复，请用 4 位兑换码激活，或联系客服");
            showNotOwned(null);
            return;
        }
        setFeedback(R_HIDE, null);
        showNotOwned(null);
    }

    private void hideAllSections() {
        if (detectSection != null) {
            detectSection.setVisibility(View.GONE);
        }
        if (ownedSection != null) {
            ownedSection.setVisibility(View.GONE);
        }
        if (notOwnedSection != null) {
            notOwnedSection.setVisibility(View.GONE);
        }
        if (waitSection != null) {
            waitSection.setVisibility(View.GONE);
        }
    }

    private void showNotOwned(String hint) {
        hideAllSections();
        notOwnedSection.setVisibility(View.VISIBLE);
        if (!TextUtils.isEmpty(hint)) {
            setFeedback(R_WARN, hint);
        }
    }

    // ======================= 通道 A：去支付 =======================

    private void startPay() {
        if (probe == null || !probe.hasStableKey()) {
            setFeedback(R_ERR, "还没有识别出这台手环，请先回首页连接手环");
            return;
        }
        payBtn.setEnabled(false);
        setFeedback(R_ACC, "正在创建支付会话…");
        pay = new PaySession();
        PaySession.Cb cb = payCb();
        if (!TextUtils.isEmpty(debugSid)) {
            pay.startWithSid(debugSid, cb);          // 调试：跳过建会话，只验传输链路
            return;
        }
        pay.start(probe, cb);
    }

    private PaySession.Cb payCb() {
        return new PaySession.Cb() {
            @Override
            public void onCreated(String sid, String payUrl) {
                payBtn.setEnabled(true);
                // 进入等待态就把「正在识别这台手环」整块收掉：识别已经完成，
                // 留着会让用户以为还在读、也把等待区挤得没有呼吸感。
                detectSection.setVisibility(View.GONE);
                notOwnedSection.setVisibility(View.GONE);
                waitSection.setVisibility(View.VISIBLE);
                waitHint.setText("下单页的留言框已经填好了会话号，付完款会自动激活");
                setFeedback(R_OK, "会话已建立 · 请在浏览器里完成付款");
                if (!TextUtils.isEmpty(payUrl)) {
                    openBrowser(payUrl);
                }
            }

            @Override
            public void onPaid(String code18) {
                setFeedback(R_OK, "收到付款，正在写入手环…");
                writeToBand(code18, "", false);
            }

            @Override
            public void onState(String text) {
                setFeedback(R_ACC, text);
            }

            @Override
            public void onTimeout() {
                setFeedback(R_WARN, "等了 5 分钟还没收到付款结果。付完款可以点「我已付款，检查一下」");
                waitSection.setVisibility(View.VISIBLE);
            }

            @Override
            public void onFail(String reason) {
                payBtn.setEnabled(true);
                setFeedback(R_ERR, reason);
            }
        };
    }

    private void openBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(i, "前往爱发电付款"));
        } catch (Throwable t) {
            setFeedback(R_WARN, "打不开浏览器，请手动访问爱发电搜索「EV课程表」购买后回来输兑换码");
            waitSection.setVisibility(View.GONE);
            notOwnedSection.setVisibility(View.VISIBLE);
        }
    }

    // ======================= 通道 B：4 位兑换码 =======================

    private void submitCode() {
        String code = codeInput == null ? "" : codeInput.getText().toString().trim().toUpperCase();
        if (!REDEEM.matcher(code).matches()) {
            setFeedback(R_ERR, "兑换码是 4 位字母或数字，再检查一下");
            return;
        }
        if (probe == null || TextUtils.isEmpty(probe.deviceId)) {
            setFeedback(R_ERR, "还没认出这台手环。先回首页连上，再回来");
            return;
        }
        setFeedback(R_ACC, "正在向服务器换取激活码…");
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", probe.deviceId);
            body.put("redeemCode", code);
            body.put("deviceInfo", deviceInfo());
            // 双保险（可选字段）：服务端据此把「兑换码 ↔ 这台手环」建索引，
            // 下次换机/重置就能自动恢复。老客户端不传这两个字段，行为照旧。
            if (probe.mac.length() > 0) {
                body.put("deviceKey", probe.mac);
            }
            if (probe.nodeId.length() > 0) {
                body.put("nodeId", probe.nodeId);
            }
        } catch (Throwable ignored) {
        }
        Net.postJson(ZeroCfg.API_BASE + "/api/activate", body.toString(), new Net.Cb() {
            @Override
            public void on(final int httpCode, final String resp) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onActivateResp(httpCode, resp, code);
                    }
                });
            }
        });
    }

    private JSONObject deviceInfo() {
        JSONObject d = new JSONObject();
        try {
            d.put("model", Build.MODEL);
            d.put("product", Build.MANUFACTURER + " " + Build.MODEL);
            d.put("os", "android");
            d.put("romVersion", Build.VERSION.RELEASE);
            d.put("source", "apk");
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            d.put("appVersion", pi.versionName);
        } catch (Throwable ignored) {
        }
        return d;
    }

    private void onActivateResp(int httpCode, String resp, String redeem) {
        if (httpCode < 0 || TextUtils.isEmpty(resp)) {
            setFeedback(R_ERR, "网络请求失败，请检查网络后重试");
            return;
        }
        String code18 = "";
        try {
            JSONObject o = new JSONObject(resp);
            if (!o.optBoolean("success", false)) {
                String err = o.optString("error", "服务器返回失败");
                android.util.Log.w("EVActivate", "backend error: " + err);
                setFeedback(R_ERR, "没激活成功，再点一次");
                return;
            }
            code18 = o.optString("activationCode", "");
        } catch (Throwable t) {
            setFeedback(R_ERR, "没拿到激活码，再点一次");
            return;
        }
        if (code18.length() != 18) {
            setFeedback(R_ERR, "没拿到激活码，再点一次");
            return;
        }
        setFeedback(R_ACC, "已获得激活码，正在写入手环…");
        writeToBand(code18, redeem, false);
    }

    // ======================= 写入手环 =======================

    private void writeToBand(final String code18, final String redeemForBind, final boolean restored) {
        SyncEngine.get(this).activate(code18, new SyncEngine.Reply() {
            @Override
            public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        String disp = o.optString("displayStatus", o.optString("status", ""));
                        onActivated(disp, restored);
                        // 新 APK 才有的动作：把「兑换码 ↔ MAC/nodeId」的归属关系补登记，
                        // 服务端会校验兑换码真实存在才允许绑定（防凭空绑定）。
                        // 恢复模式不再绑（那条记录本来就是它），避免重复写。
                        if (!restored && !TextUtils.isEmpty(redeemForBind)) {
                            bindDevice(redeemForBind);
                        }
                    } else {
                        android.util.Log.w("EVActivate", "band refused: " + o.optString("reason", "未知原因"));
                        setFeedback(R_ERR, "手环没接受。换「快速激活」页再试一次");
                    }
                } catch (Throwable t) {
                    setFeedback(R_ERR, "手环没回答，再点一次");
                }
            }

            @Override
            public void onTimeout(String hint) {
                setFeedback(R_WARN, hint);
            }

            @Override
            public void onError(String msg) {
                setFeedback(R_ERR, "写入失败：" + msg);
            }
        });
    }

    private void onActivated(String disp, boolean restored) {
        activated = true;
        hideAllSections();
        if (okCard != null) {
            okSub.setText(restored
                    ? "这台手环之前买过，已按当前设备重新激活，没有重复收费"
                    : (disp.length() > 0 ? disp + " · 共 365 天" : "激活码已写入手环"));
            okCard.setVisibility(View.VISIBLE);
        }
        setDeviceState(ST_OK, "已激活", "可以在手环「EV 课程表 → 高级版」查看");
        setFeedback(R_HIDE, null);
        if (pay != null) {
            pay.cancel();
            pay = null;
        }
        Analytics.event(this, "app_activate_zero_ok",
                Analytics.p("restored", restored ? "1" : "0",
                        "device_key", probe == null ? "" : probe.mac));
    }

    /** 归属登记：失败不影响用户已激活的事实，所以整段静默 */
    private void bindDevice(String redeem) {
        if (probe == null) {
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("action", "bind");
            body.put("redeemCode", redeem);
            body.put("deviceKey", probe.mac);
            body.put("nodeId", probe.nodeId);
            body.put("deviceId", probe.deviceId);
        } catch (Throwable ignored) {
            return;
        }
        Net.postJson(ZeroCfg.API_DEVICE, body.toString(), null);
    }
}
