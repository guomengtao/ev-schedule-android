package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 高级版「快速激活」。
 *
 * 流程（省掉手环上扫两个码 + 手输 18 位）：
 *   1. 从手环取设备ID（get_device_id）
 *   2. 用户只输 4 位兑换码
 *   3. 调后端 /api/activate 换 18 位激活码
 *   4. 把 18 位码交给手环（activate），手环本地校验 + 落库
 */
public class FastActivateActivity extends Activity {

    private static final Pattern REDEEM = Pattern.compile("^[A-Z0-9]{4}$");

    private TextView deviceView, statusView, resultView;
    private EditText codeView;
    private String deviceId = "";
    private String deviceId4 = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "高级版"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "一键激活：只需填 4 位兑换码", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

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
        root.addView(info);
        root.addView(Ui.space(this, 10));

        LinearLayout act = Ui.card(this);
        act.addView(Ui.text(this, "4 位兑换码", 12.5f, Ui.TEXT, true));
        act.addView(Ui.space(this, 6));
        codeView = new EditText(this);
        codeView.setTextSize(16f);
        codeView.setTextColor(Ui.TEXT);
        codeView.setHintTextColor(Ui.MUTED);
        codeView.setHint("例如 1BZR");
        codeView.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4),
                new android.text.InputFilter.AllCaps()});
        act.addView(codeView);
        act.addView(Ui.space(this, 10));
        act.addView(Ui.button(this, "一键激活", true, new View.OnClickListener() {
            @Override public void onClick(View v) { activate(); }
        }));
        root.addView(act);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        resultView.setTextIsSelectable(true);
        root.addView(resultView);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this,
                "说明：\n"
                        + "· 兑换码在爱发电购买后获得（4 位大写字母/数字）\n"
                        + "· 激活码为 18 位数字，由服务端按你的设备ID生成，本页自动写入，无需手输\n"
                        + "· 一个兑换码通常只能激活一台设备"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));

        root.setFocusableInTouchMode(true);
        root.requestFocus();

        loadDeviceId();
    }

    // ======================= 设备ID =======================

    private void loadDeviceId() {
        if (!SyncEngine.get(this).hasNode()) {
            deviceView.setText("设备ID：（未连接手环）");
            statusView.setText("请先回首页完成连接");
            statusView.setTextColor(Ui.WARN);
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
                    deviceView.setText("设备ID：" + mask(deviceId)
                            + (fallback ? "（临时标识）" : ""));
                    statusView.setText(fallback
                            ? "设备标识为临时值，激活后重装应用可能失效"
                            : "设备ID已就绪，可输入兑换码");
                    statusView.setTextColor(fallback ? Ui.WARN : Ui.OK);
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
        if (!SyncEngine.get(this).hasNode()) {
            resultView.setText("请先回首页连接手环");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        String code = codeView.getText().toString().trim().toUpperCase();
        if (!REDEEM.matcher(code).matches()) {
            resultView.setText("兑换码必须是 4 位大写字母或数字（A-Z, 0-9）");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        if (TextUtils.isEmpty(deviceId)) {
            resultView.setText("还没有取到设备ID，请点「重新读取设备ID」");
            resultView.setTextColor(Ui.ERR);
            return;
        }

        resultView.setText("正在向服务器换取激活码…");
        resultView.setTextColor(Ui.ACCENT);

        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", deviceId);
            body.put("redeemCode", code);
            body.put("deviceInfo", deviceInfo());
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
                resultView.setText("激活失败：" + err);
                resultView.setTextColor(Ui.ERR);
                return;
            }
        } catch (Throwable t) {
            resultView.setText("服务器回包无法解析");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        if (activationCode == null || activationCode.length() != 18) {
            resultView.setText("服务器没有返回有效的 18 位激活码");
            resultView.setTextColor(Ui.ERR);
            return;
        }
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
                        resultView.setText("激活成功！" + (disp.length() > 0 ? ("　" + disp)
                                : (status.length() > 0 ? ("　" + status) : ""))
                                + "\n可在手环上打开「EV 课程表 → 高级版」查看有效期。");
                        resultView.setTextColor(Ui.OK);
                        statusView.setText("已激活" + (disp.length() > 0 ? ("：" + disp) : ""));
                        statusView.setTextColor(Ui.OK);
                    } else {
                        resultView.setText("手环拒绝激活：" + o.optString("reason"));
                        resultView.setTextColor(Ui.ERR);
                    }
                } catch (Throwable t) {
                    resultView.setText("手环回包无法解析：" + json);
                    resultView.setTextColor(Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                resultView.setText(hint);
                resultView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                resultView.setText("写入失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }
}
