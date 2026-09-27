package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天页：与手环上的 EV 课程表互发消息。
 *
 * ⚠️ 现状说明（很重要）：
 *   手环上的 EV 目前【没有】 chat 分支。发过去会走到"无 action → 按 import 处理"，
 *   回包固定是 {"ok":false,"reason":"no courses"}。
 *   本页会把这种回包识别出来并明确提示 —— 它证明"手机→手环"是通的，
 *   只是手环还不认识这个指令，需要升级 EV 课程表（详见 聊天功能可行性分析.md）。
 *
 * 协议：
 *   手机 → 手环  {"action":"chat","id":"…","text":"…","ts":…}
 *   手环 → 手机  {"ok":true,"action":"chat_ack","id":"…","ts":…}   送达确认
 *   手环 → 手机  {"action":"chat","id":"…","text":"…","ts":…}      手环主动发来的消息
 */
public class ChatActivity extends Activity {

    private TextView msgView, stateView;
    private EditText inputView;
    private final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private long lastOkTs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "聊天"));
        root.addView(Ui.space(this, 6));

        stateView = Ui.text(this, "状态：未连接", 12f, Ui.MUTED, false);
        root.addView(stateView);
        root.addView(Ui.space(this, 8));

        msgView = Ui.mono(this, "");
        msgView.setTextSize(12f);
        msgView.setTextColor(Ui.TEXT);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(msgView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 输入行
        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        inputView = new EditText(this);
        inputView.setHint("说点什么…");
        inputView.setTextSize(13f);
        inputView.setTextColor(Ui.TEXT);
        inputView.setHintTextColor(Ui.MUTED);
        sendRow.addView(inputView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button send = Ui.button(this, "发送", true, new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        sendRow.addView(send);
        root.addView(sendRow);
        root.addView(Ui.space(this, 8));

        root.addView(Ui.grid(this,
                Ui.button(this, "手环在不在", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { ping(); }
                }),
                Ui.button(this, "回首页重连", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent it = new Intent(ChatActivity.this, HomeActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(it);
                    }
                })));

        root.addView(Ui.space(this, 6));
        root.addView(Ui.mono(this,
                "EV 侧未实现 chat 时，回包会是 {ok:false,reason:\"no courses\"}\n"
                        + "—— 那说明链路通了，只是手环还不认识这个指令。"));

        root.addView(Ui.space(this, 6));
        setContentView(Ui.wrapWithBottomBar(this, root, 1));
        refreshState();
    }

    private void refreshState() {
        SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            stateView.setText("状态：未连接（先回首页完成连接）");
            stateView.setTextColor(Ui.MUTED);
        } else if (lastOkTs == 0) {
            stateView.setText("状态：已连设备 " + e.deviceName + "，尚未通信");
            stateView.setTextColor(Ui.MUTED);
        }
    }

    // ======================= 发送 =======================

    private void sendMessage() {
        final String text = inputView.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        inputView.setText("");
        append("我  " + TS.format(new Date()) + "\n    " + text);

        JSONObject o = new JSONObject();
        try {
            o.put("action", "chat");
            o.put("id", UUID.randomUUID().toString().substring(0, 8));
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
        } catch (Throwable t) {
            append("！ 构造报文失败 " + t);
            return;
        }

        stateView.setText("正在发送…");
        stateView.setTextColor(Ui.ACCENT);
        SyncEngine.get(this).send(o.toString(), new SyncEngine.Reply() {
            @Override public void onReply(String json) { handleReply(json); }
            @Override public void onTimeout(String hint) {
                stateView.setText("手环无回应（可能已断开）");
                stateView.setTextColor(Ui.WARN);
                append("！ " + hint);
            }
            @Override public void onError(String msg) {
                stateView.setText("发送失败");
                stateView.setTextColor(Ui.ERR);
                append("！ " + msg);
            }
        });
    }

    private void handleReply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String action = o.optString("action");

            // 手环主动发来的聊天消息
            if ("chat".equals(action)) {
                incoming(o.optString("text"));
                return;
            }
            // 送达确认（EV 实现 chat 后才有）
            if ("chat_ack".equals(action)) {
                lastOkTs = System.currentTimeMillis();
                stateView.setText("已送达手环  " + TS.format(new Date()));
                stateView.setTextColor(Ui.OK);
                append("    ✓ 已送达");
                return;
            }
            // EV 未实现 chat：无 action 的报文被当成 import 处理，固定回这个
            if (!o.optBoolean("ok", true) && "no courses".equals(o.optString("reason"))) {
                stateView.setText("手环收到了，但不认识 chat 指令");
                stateView.setTextColor(Ui.WARN);
                append("    ⚠ 手环已收到并回包，但它把这条当成「导入课表」处理了\n"
                        + "      → 说明链路是通的，只是当前手环上的 EV 课程表还没有 chat 分支，需要升级 EV");
                return;
            }
            lastOkTs = System.currentTimeMillis();
            stateView.setText("回包 " + action + "  " + TS.format(new Date()));
            stateView.setTextColor(Ui.OK);
            append("    （回包）" + json);
        } catch (Throwable t) {
            append("    （无法解析的回包）" + json);
        }
    }

    /** 收到手环主动发来的消息：列表 + 弹窗 + 提示音 */
    private void incoming(String text) {
        lastOkTs = System.currentTimeMillis();
        stateView.setText("收到手环消息  " + TS.format(new Date()));
        stateView.setTextColor(Ui.OK);
        append("手环  " + TS.format(new Date()) + "\n    " + text);
        playTone();
        try {
            new AlertDialog.Builder(this)
                    .setTitle("来自手环")
                    .setMessage(text)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    private void playTone() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(this, u);
            if (r != null) {
                r.play();
            }
        } catch (Throwable ignored) {
        }
    }

    private void ping() {
        stateView.setText("正在探测…");
        stateView.setTextColor(Ui.ACCENT);
        SyncEngine.get(this).step4Ping(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                stateView.setText(ok ? "手环在线 · " + msg : "探测失败 · " + msg);
                stateView.setTextColor(ok ? Ui.OK : Ui.ERR);
            }
        });
    }

    private void append(String s) {
        msgView.append(s + "\n");
    }
}