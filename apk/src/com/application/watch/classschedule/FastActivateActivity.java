package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 高级版「快速激活」。
 *
 * 流程（省掉手环上扫两个码 + 手输 18 位）：
 *   0. 购买：按钮直达爱发电（/go/ev-timetable 短链，自带点击统计），获得 4 位兑换码
 *   1. 从手环取设备ID（get_device_id）
 *   2. 四格输入 4 位兑换码（支持粘贴自动分格、自动大写、自动跳格）
 *   3. 调后端 /api/activate 换 18 位激活码
 *   4. 把 18 位码交给手环（activate），手环本地校验 + 落库
 *
 * 断点续传（SharedPreferences ev_pending_activate）：
 *   - 未连接手环时提交 → 只暂存兑换码，连接后自动继续
 *   - 换到 18 位码但写入手环失败 → 暂存 18 位码，重连后直接补写（不再打服务端）
 *   - 18 位码由服务端按 deviceId 确定性生成，暂存丢失也可重新兑换取回（幂等）
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

    private TextView deviceView, statusView, resultView;
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
        root.addView(Ui.header(this, "高级版"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "一键激活：只需填 4 位兑换码", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));
        ConnectionBar.attach(this, root); // 自动连接状态条（与首页同一数据源）
        root.addView(Ui.space(this, 8));

        // ===== 步骤①：购买 =====
        LinearLayout buy = Ui.card(this);
        buy.addView(Ui.text(this, "① 购买兑换码", 12.5f, Ui.TEXT, true));
        buy.addView(Ui.space(this, 6));
        buy.addView(Ui.text(this, "前往爱发电下单后即可获得 4 位兑换码（大写字母/数字）",
                11.5f, Ui.MUTED, false));
        buy.addView(Ui.space(this, 10));
        buy.addView(Ui.button(this, "前往爱发电购买", true, new View.OnClickListener() {
            @Override public void onClick(View v) { openBuy(); }
        }));
        root.addView(buy);
        root.addView(Ui.space(this, 10));

        // ===== 设备卡 =====
        LinearLayout info = Ui.card(this);
        deviceView = Ui.text(this, "设备ID：读取中…", 13f, Ui.TEXT, true);
        info.addView(deviceView);
        statusView = Ui.text(this, "当前状态：读取中…", 12f, Ui.MUTED, false);
        statusView.setPadding(0, Ui.dp(this, 6), 0, 0);
        info.addView(statusView);
        info.addView(Ui.space(this, 10));
        info.addView(Ui.button(this, "重新读取设备ID", false, new View.OnClickListener() {
            @Override public void onClick(View v) { loadDeviceId(); }
        }));
        info.addView(Ui.space(this, 8));
        info.addView(Ui.button(this, "呼叫手环（响铃找表）", false, new View.OnClickListener() {
            @Override public void onClick(View v) { callBand(); }
        }));
        root.addView(info);
        root.addView(Ui.space(this, 10));

        // ===== 步骤②：四格兑换码 =====
        LinearLayout act = Ui.card(this);
        act.addView(Ui.text(this, "② 输入 4 位兑换码", 12.5f, Ui.TEXT, true));
        act.addView(Ui.space(this, 10));
        act.addView(buildCodeBoxes());
        act.addView(Ui.space(this, 10));
        act.addView(Ui.text(this, "支持长按粘贴，自动大写", 11f, Ui.MUTED, false));
        act.addView(Ui.space(this, 10));
        activateBtn = Ui.button(this, "一键激活", true, new View.OnClickListener() {
            @Override public void onClick(View v) { activate(); }
        });
        act.addView(activateBtn);
        root.addView(act);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        resultView.setTextIsSelectable(true);
        root.addView(resultView);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this,
                "前置条件：\n"
                        + "· 需安卓手机（iOS 暂不支持）\n"
                        + "· 手机已安装「小米运动健康」并连接手环\n"
                        + "· 手环已安装 EV 课程表\n\n"
                        + "说明：\n"
                        + "· 激活码为 18 位数字，由服务端按你的设备ID生成，本页自动写入，无需手输\n"
                        + "· 一个兑换码只能激活一台手环；未连接手环时会先暂存，连接后自动继续\n"
                        + "· 换手机不用重新激活（激活码跟随手环）"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));

        root.setFocusableInTouchMode(true);
        root.requestFocus();

        loadDeviceId();
        handleDeepLink(getIntent());
        // P3（§4.4-E2）：激活页此前没有 pageView，是 App 侧访问埋点的最大盲区
        Analytics.pageView(this, "/apk/activate");
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
        resultView.setText("已从链接自动填入兑换码 " + code
                + (deviceId.length() > 0 ? "，正在激活…" : "，等待读取设备ID…"));
        resultView.setTextColor(Ui.ACCENT);
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
        btnHandler.removeCallbacks(btnReset);
        if (activateBtn != null) {
            activateBtn.setText("一键激活");
            activateBtn.setEnabled(true);
        }
    }

    // ======================= 呼叫手环 =======================

    /** 推一条手表通知让手环响铃/震动，帮用户在蓝牙范围内找到手环。 */
    private void callBand() {
        if (!SyncEngine.get(this).hasNode()) {
            resultView.setText("手环未连接，无法呼叫。请先回首页连接手环");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在呼叫手环…");
        resultView.setTextColor(Ui.ACCENT);
        SyncEngine.get(this).notifyWatch("呼叫手环", "🔔 你的手环在这里！—— 来自手机 Ev课程表同步器",
                new SyncEngine.Cb() {
                    @Override public void on(final boolean ok, final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (ok) {
                                    resultView.setText("已发送呼叫，看一下手环（会弹通知并震动）");
                                    resultView.setTextColor(Ui.OK);
                                } else {
                                    resultView.setText("呼叫失败：" + msg);
                                    resultView.setTextColor(Ui.ERR);
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
            resultView.setText("无法打开浏览器，请手动访问爱发电搜索「EV课程表」购买");
            resultView.setTextColor(Ui.WARN);
        }
    }

    // ======================= 四格输入 =======================

    private View buildCodeBoxes() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundDrawable(Ui.round(Ui.CARD2, 10, 1, this));
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 54), 1f);
            if (i > 0) {
                lp.leftMargin = Ui.dp(this, 10);
            }
            cell.setLayoutParams(lp);

            final int idx = i;
            EditText b = new EditText(this);
            b.setTextSize(24f);
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
            resultView.setText("检测到未完成的激活，但它属于另一台手环（"
                    + mask(pendDev) + "），请确认后重新输入兑换码");
            resultView.setTextColor(Ui.WARN);
            return;
        }

        if (code.length() == 18 && deviceKnown && !code.equals(resumedCode)) {
            resumedCode = code;
            resultView.setText("发现未完成的激活，正在写入手环…");
            resultView.setTextColor(Ui.ACCENT);
            writeToBand(code);
            return;
        }

        if (redeem.length() == 4 && deviceKnown && !redeem.equals(resumedRedeem)) {
            resumedRedeem = redeem;
            fillBoxes(redeem);
            resultView.setText("发现未完成的激活，正在继续…");
            resultView.setTextColor(Ui.ACCENT);
            activate();
            return;
        }

        if (!deviceKnown && redeem.length() == 4 && collectCode().length() < 4) {
            fillBoxes(redeem);
        }
    }

    // ======================= 设备ID =======================

    private void loadDeviceId() {
        if (!SyncEngine.get(this).hasNode()) {
            deviceView.setText("设备ID：（未连接手环）");
            statusView.setText("请先回首页完成连接（需要小米运动健康）");
            statusView.setTextColor(Ui.WARN);
            tryResume();
            maybeDeepLinkActivate();
            return;
        }
        deviceView.setText("设备ID：读取中…");
        SyncEngine.get(this).getDeviceId(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)
                            || !"get_device_id".equals(o.optString("action"))) {
                        deviceView.setText("设备ID：读取失败（手环 EV 版本过低？）");
                        return;
                    }
                    deviceId = o.optString("deviceId");
                    deviceId4 = o.optString("deviceId4");
                    boolean fallback = o.optBoolean("fallback", false);
                    String name = SyncEngine.get(FastActivateActivity.this).deviceName;
                    deviceView.setText("设备ID：" + mask(deviceId)
                            + (fallback ? "（临时标识）" : ""));
                    statusView.setText((name.length() > 0 ? "目标手环：" + name + "　" : "")
                            + (fallback
                            ? "设备标识为临时值，激活后重装应用可能失效"
                            : "设备ID已就绪，可输入兑换码"));
                    statusView.setTextColor(fallback ? Ui.WARN : Ui.OK);
                    tryResume();
                    maybeDeepLinkActivate();
                } catch (Throwable t) {
                    deviceView.setText("设备ID：回包无法解析");
                }
            }
            @Override public void onTimeout(String hint) {
                deviceView.setText("设备ID：读取超时");
                statusView.setText(hint);
                statusView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                deviceView.setText("设备ID：读取失败");
                statusView.setText(msg);
                statusView.setTextColor(Ui.ERR);
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
            resultView.setText("兑换码必须是 4 位大写字母或数字（A-Z, 0-9）");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        setBtnBusy();
        if (!SyncEngine.get(this).hasNode()) {
            // 未连接：先暂存兑换码，连接后自动继续
            savePending(code, "", null);
            resultView.setText("手环未连接，兑换码已暂存。\n连上手环回到本页后会自动继续激活。");
            resultView.setTextColor(Ui.WARN);
            resetBtn();
            return;
        }
        if (TextUtils.isEmpty(deviceId)) {
            resultView.setText("还没有取到设备ID，正在重试读取…");
            resultView.setTextColor(Ui.WARN);
            resetBtn();
            loadDeviceId();
            return;
        }

        resultView.setText("正在向服务器换取激活码…");
        resultView.setTextColor(Ui.ACCENT);

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
            resultView.setText("网络请求失败，请检查网络后重试");
            resultView.setTextColor(Ui.ERR);
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
                resultView.setText("激活失败：" + err);
                resultView.setTextColor(Ui.ERR);
                return;
            }
        } catch (Throwable t) {
            resetBtn();
            resultView.setText("服务器回包无法解析");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        if (activationCode == null || activationCode.length() != 18) {
            resetBtn();
            resultView.setText("服务器没有返回有效的 18 位激活码");
            resultView.setTextColor(Ui.ERR);
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
        resultView.setText("已获得激活码，正在写入到手环…");
        resultView.setTextColor(Ui.ACCENT);
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
                        resultView.setText("激活成功！" + (disp.length() > 0 ? ("　" + disp)
                                : (status.length() > 0 ? ("　" + status) : ""))
                                + "\n可在手环上打开「EV 课程表 → 高级版」查看有效期。");
                        resultView.setTextColor(Ui.OK);
                        statusView.setText("已激活" + (disp.length() > 0 ? ("：" + disp) : ""));
                        statusView.setTextColor(Ui.OK);
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
                        resultView.setText("手环拒绝激活：" + o.optString("reason")
                                + "\n激活码已暂存，解决后回到本页会自动重试。");
                        resultView.setTextColor(Ui.ERR);
                    }
                } catch (Throwable t) {
                    resetBtn();
                    resultView.setText("手环回包无法解析：" + json);
                    resultView.setTextColor(Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                resetBtn();
                resultView.setText(hint + "\n激活码已暂存，重连手环后回到本页会自动补写。");
                resultView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                resetBtn();
                resultView.setText("写入失败：" + msg + "\n激活码已暂存，重连手环后回到本页会自动补写。");
                resultView.setTextColor(Ui.ERR);
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
