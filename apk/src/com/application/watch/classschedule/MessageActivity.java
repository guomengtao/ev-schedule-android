package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 留言页（原"聊天"改为"留言模式"）。
 *
 * 定位：不是实时 IM，而是「离线留言 / 消息通道」。
 *
 * 关键行为：
 *   1. 不连接也能留言 —— 先落本机队列（SharedPreferences），再尝试发送；
 *   2. 连上手环后自动补发所有「待发送」留言；
 *   3. 收到手环的留言就响铃 + 震动（前台场景）；后台能力见 docs 方案。
 *
 * 协议：
 *   手机 → 手环  {"action":"chat","id":"…","text":"…","ts":…}
 *   手环 → 手机  {"ok":true,"action":"chat_ack","id":"…","ts":…}   送达确认
 *   手环 → 手机  {"action":"chat","id":"…","text":"…","ts":…}      手环发来的留言
 */
public class MessageActivity extends Activity {

    private int lastThemeVersion = 0;

    private static final String PREF = "ev_message_queue";
    private static final String KEY = "items";
    /** 已提醒过的消息 id（去重的唯一依据） */
    private static final String SEEN_KEY = "seen_ids";
    private static final int SEEN_MAX = 500;

    private LinearLayout listBox;
    private ScrollView scrollBox;   // P0：气泡流需在发送/接收后自动滚到底
    private TextView stateView;
    private EditText inputView;
    private final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    /** 队列项：{id, dir:"out"|"in", text, ts, status:"pending"|"sent"} —— 按时间顺序 */
    private JSONArray items = new JSONArray();
    private boolean sending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "留言"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "离线留言：不连接也能写，连上手环后自动送达", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 8));

        stateView = Ui.text(this, "状态：未连接", 12f, Ui.MUTED, false);
        root.addView(stateView);
        root.addView(Ui.space(this, 8));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scrollBox = new ScrollView(this);
        scrollBox.addView(listBox);
        root.addView(scrollBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        inputView = new EditText(this);
        inputView.setHint("写一条留言…");
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
        root.addView(Ui.space(this, 6));

        // 按钮精简：诊断类动作移除（补发自动进行），只留一个低调的「清空记录」
        TextView clearLink = Ui.text(this, "清空记录", 11.5f, Ui.MUTED, false);
        clearLink.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        clearLink.setClickable(true);
        clearLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearAll(); }
        });
        root.addView(clearLink);

        setContentView(Ui.fixedWithBottomBar(this, root, -1));
        installObserver(this);
        load();
        render();
        refreshState();
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
        Analytics.pageView(this, "/apk/message");
        // 回到本页：重装观察者（弹窗落到当前可见页面）+ 重新载入（其它页面可能已收过留言）
        installObserver(this);
        load();
        render();
        // 刷新状态并尝试补发（断线期间写的留言在此刻发出）
        refreshState();
        flush();
    }

    // ======================= 状态 / 渲染 =======================

    private void refreshState() {
        SyncEngine e = SyncEngine.get(this);
        int pending = countPending();
        if (!e.hasNode()) {
            stateView.setText("状态：未连接手环" + (pending > 0 ? ("　·　待发送 " + pending + " 条") : ""));
            stateView.setTextColor(Ui.MUTED);
        } else {
            stateView.setText("状态：已连 " + e.deviceName
                    + (pending > 0 ? ("　·　待发送 " + pending + " 条") : "　·　全部已送达"));
            stateView.setTextColor(pending > 0 ? Ui.WARN : Ui.OK);
        }
    }

    private int countPending() {
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && "out".equals(o.optString("dir"))
                    && !"sent".equals(o.optString("status"))) {
                n++;
            }
        }
        return n;
    }

    /**
     * 微信式气泡流（P0）：最新在下；我方右对齐（主色气泡白字），对方左对齐（浅灰气泡深字）；
     * 与上一条间隔 > 5 分钟才插一条居中时间分割；双方各有头像（手机 / 手表）。
     * 消息量小（<100 条）→ 维持现有全量重建，暂不上 RecyclerView（方案 §3.1）。
     */
    private void render() {
        listBox.removeAllViews();
        if (items.length() == 0) {
            TextView empty = Ui.text(this,
                    "还没有留言\n断开也能写，连上手环后会自动送达", 12.5f, Ui.MUTED, false);
            empty.setPadding(0, Ui.dp(this, 24), 0, 0);
            empty.setGravity(android.view.Gravity.CENTER);
            listBox.addView(empty);
            return;
        }
        // 气泡最大宽度：约屏宽 2/3，避免长句铺满整行（微信同款观感）
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.66f);
        long prevTs = 0;
        for (int i = 0; i < items.length(); i++) {   // 正序：最新在下
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                continue;
            }
            long ts = o.optLong("ts");
            if (prevTs == 0 || ts - prevTs > 5 * 60 * 1000L) {
                listBox.addView(timeDivider(ts));
            }
            prevTs = ts;

            boolean out = "out".equals(o.optString("dir"));
            boolean sent = "sent".equals(o.optString("status"));
            listBox.addView(bubbleRow(out, sent, o.optString("text"), maxW));
            listBox.addView(Ui.space(this, 6));
        }
        scrollToBottom();
    }

    /** 居中时间分割（10.5sp 灰字，上下留白） */
    private View timeDivider(long ts) {
        TextView t = Ui.text(this, fmt(ts), 10.5f, Ui.MUTED, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 单条气泡行：头像 + 气泡（我方在右、对方在左），对方一侧用 spacer 把气泡推向另一侧 */
    private View bubbleRow(boolean out, boolean sent, String text, int maxW) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);

        int av = Ui.dp(this, 34);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(out ? R.drawable.ic_smartphone : R.drawable.ic_tab_watch);
        avatar.setColorFilter(out ? Ui.ACCENT : Ui.MUTED);
        avatar.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        avatar.setBackground(Ui.round(out ? Ui.ACCENT_LIGHT : Ui.CARD2, 17, 0, this));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(av, av);
        alp.gravity = android.view.Gravity.BOTTOM;

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        bubble.setBackground(Ui.round(out ? Ui.ACCENT : Ui.CARD, 14, out ? 0 : Ui.LINE, this));

        TextView body = Ui.textLh(this, text, 14f, out ? Ui.ON_ACCENT : Ui.TEXT, false, Ui.LH_BODY);
        body.setMaxWidth(maxW);
        bubble.addView(body);

        if (out) {
            // 我方气泡内的送达状态（已送达 = 手环回了 chat_ack；待发送 = 尚未确认）
            TextView st = Ui.text(this, sent ? "已送达" : "待发送", 10f,
                    sent ? 0xB3FFFFFF : 0xFFFFE08A, false);
            st.setPadding(0, Ui.dp(this, 3), 0, 0);
            st.setGravity(android.view.Gravity.END);
            bubble.addView(st);
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);

        View spacer = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);

        if (out) {                 // 我：spacer | 气泡 | 头像
            row.addView(spacer, slp);
            row.addView(bubble, blp);
            row.addView(avatar, alp);
        } else {                   // 对方：头像 | 气泡 | spacer
            row.addView(avatar, alp);
            row.addView(bubble, blp);
            row.addView(spacer, slp);
        }
        return row;
    }

    /** 布局完成后滚到底部（最新消息） */
    private void scrollToBottom() {
        if (scrollBox == null) {
            return;
        }
        scrollBox.post(new Runnable() {
            @Override public void run() {
                scrollBox.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String fmt(long ts) {
        return ts > 0 ? TS.format(new Date(ts)) : "";
    }

    // ======================= 发送 =======================

    private void sendMessage() {
        final String text = inputView.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        inputView.setText("");
        JSONObject item = new JSONObject();
        try {
            item.put("id", UUID.randomUUID().toString().substring(0, 8));
            item.put("dir", "out");
            item.put("text", text);
            item.put("ts", System.currentTimeMillis());
            item.put("status", "pending");
        } catch (Throwable ignored) {
            return;
        }
        items.put(item);
        save();
        render();
        refreshState();
        flush();
    }

    /** 逐条补发所有 pending 留言（SyncEngine 同一时刻只等一个回包，必须串行） */
    private void flush() {
        if (sending) {
            return;
        }
        JSONObject pending = firstPending();
        if (pending == null) {
            return;
        }
        final SyncEngine e = SyncEngine.get(this);
        if (!e.hasNode()) {
            stateView.setText("手环未连接，留言已保存，连上后会自动发送");
            stateView.setTextColor(Ui.WARN);
            return;
        }

        sending = true;
        final String id = pending.optString("id");
        final String text = pending.optString("text");
        final long ts = pending.optLong("ts");

        // 同时推一条「手表通知」（NotifyApi，不经 EV）—— 手环即使没装 EV 课程表也能看到留言。
        // 用 notified 标记保证每条留言只推一次（避免 flush 重发时重复弹通知）。
        if (!pending.optBoolean("notified", false)) {
            e.notifyWatch("手机留言", text, new SyncEngine.Cb() {
                @Override public void on(boolean ok, String m) { /* 尽力推送，不阻塞留言发送 */ }
            });
            try { pending.put("notified", true); save(); } catch (Throwable ignored) {}
        }

        JSONObject o = new JSONObject();
        try {
            o.put("action", "chat");
            o.put("id", id);
            o.put("text", text);
            o.put("ts", ts);
        } catch (Throwable t) {
            sending = false;
            return;
        }

        stateView.setText("正在送达…");
        stateView.setTextColor(Ui.ACCENT);
        e.send(o.toString(), new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                sending = false;
                if (isAck(json, id)) {
                    markSent(id);
                    save();
                    render();
                    refreshState();
                    flush(); // 继续下一条
                } else {
                    // 老版本 EV 会把未知 action 当 import 处理，回 no courses —— 视作未送达
                    stateView.setText("手环未确认收妥，留言保留，稍后重试");
                    stateView.setTextColor(Ui.WARN);
                    refreshState();
                }
            }
            @Override public void onTimeout(String hint) {
                sending = false;
                stateView.setText("手环无回应，留言保留为待发送");
                stateView.setTextColor(Ui.WARN);
                refreshState();
            }
            @Override public void onError(String msg) {
                sending = false;
                stateView.setText("暂无法送达：" + msg);
                stateView.setTextColor(Ui.ERR);
                refreshState();
            }
        });
    }

    private boolean isAck(String json, String id) {
        try {
            JSONObject o = new JSONObject(json);
            if ("chat_ack".equals(o.optString("action"))) {
                String ackId = o.optString("id");
                return ackId.length() == 0 || ackId.equals(id);
            }
            // 部分实现只回 {ok:true}
            return o.optBoolean("ok", false) && !"no courses".equals(o.optString("reason"));
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONObject firstPending() {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && "out".equals(o.optString("dir"))
                    && !"sent".equals(o.optString("status"))) {
                return o;
            }
        }
        return null;
    }

    private void markSent(String id) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) {
                try { o.put("status", "sent"); } catch (Throwable ignored) {}
                return;
            }
        }
    }

    private void clearAll() {
        items = new JSONArray();
        save();
        render();
        refreshState();
    }

    // ======================= 接收（去重是硬要求：同一条只提醒一次） =======================

    /** 当前页面安装「手环主动消息」观察者（每次 onResume 重装，保证弹窗落在可见页面上） */
    static void installObserver(final Activity host) {
        SyncEngine.get(host).setObserver(new SyncEngine.Observer() {
            @Override public void onMessage(String json) {
                handleUnsolicited(host, json);
            }
        });
    }

    /**
     * 处理手环主动发来的留言。
     * ⚠️ 按 id 去重：同一条只入库 + 提醒一次，重复到达直接丢弃 —— **禁止重复提醒**。
     */
    static void handleUnsolicited(final Context ctx, String json) {
        try {
            // 工具箱遥控指令（{"action":"cmd",...}）优先消费，不进留言流
            if (CommandRouter.handle(ctx, json)) {
                return;
            }
            JSONObject o = new JSONObject(json);
            if (!"chat".equals(o.optString("action"))) {
                return;
            }
            final String text = o.optString("text");
            String id = o.optString("id");
            if (id.length() == 0) {
                // 兼容老 EV（无 id）：用 ts + 文本哈希兜底，仍能挡住绝大多数重复
                id = "in-" + o.optLong("ts", System.currentTimeMillis()) + "-" + text.hashCode();
            }
            if (!markSeen(ctx, id)) {
                return; // 已提醒过 → 丢弃
            }
            appendIncoming(ctx, id, text, o.optLong("ts", System.currentTimeMillis()));
            alert(ctx, text);
        } catch (Throwable ignored) {
        }
    }

    /** 记录已提醒过的 id；返回 true = 首次见到（应提醒），false = 重复（应丢弃） */
    private static synchronized boolean markSeen(Context ctx, String id) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(SEEN_KEY, "");
            Set<String> seen = new LinkedHashSet<>();
            if (raw.length() > 0) {
                Collections.addAll(seen, raw.split("\n"));
            }
            if (seen.contains(id)) {
                return false;
            }
            seen.add(id);
            while (seen.size() > SEEN_MAX) {
                Iterator<String> it = seen.iterator();
                it.next();
                it.remove();
            }
            StringBuilder sb = new StringBuilder();
            for (String s : seen) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s);
            }
            sp.edit().putString(SEEN_KEY, sb.toString()).apply();
            return true;
        } catch (Throwable t) {
            return true; // 存储异常时宁可提醒一次，也不要静默吞掉
        }
    }

    /** 把收到的留言写入本地记录（与留言列表共用同一份存储） */
    private static synchronized void appendIncoming(Context ctx, String id, String text, long ts) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr;
            try {
                arr = new JSONArray(sp.getString(KEY, "[]"));
            } catch (Throwable t) {
                arr = new JSONArray();
            }
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("dir", "in");
            item.put("text", text);
            item.put("ts", ts > 0 ? ts : System.currentTimeMillis());
            item.put("status", "sent");
            arr.put(item);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 提醒：响铃 + 震动 +（前台时）弹窗 */
    private static void alert(final Context ctx, final String text) {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(ctx, u);
            if (r != null) {
                r.play();
            }
        } catch (Throwable ignored) {
        }
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(
                            400, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(400);
                }
            }
        } catch (Throwable ignored) {
        }
        if (!(ctx instanceof Activity)) {
            // 后台（服务进程）：后台弹窗没意义，改用系统通知提醒
            Notifications.showMessage(ctx, "来自手环的留言", text);
            return;
        }
        final Activity a = (Activity) ctx;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing()) {
                        return;
                    }
                    new AlertDialog.Builder(a)
                            .setTitle("来自手环的留言")
                            .setMessage(text)
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void ping() {
        stateView.setText("正在探测…");
        stateView.setTextColor(Ui.ACCENT);
        SyncEngine.get(this).step4Ping(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                stateView.setText(ok ? "手环在线 · " + msg : "探测失败 · " + msg);
                stateView.setTextColor(ok ? Ui.OK : Ui.ERR);
                if (ok) {
                    flush();
                }
            }
        });
    }

    // ======================= 持久化 =======================

    /**
     * 首页「📝 快速留言」直发通道：写入本机队列 + 尽力立即送达。
     * 与留言页共用同一份队列存储；未连接时保持 pending，留言页下次打开会自动补发。
     */
    public static synchronized void enqueueOutgoing(Context ctx, String text) {
        if (ctx == null || text == null || text.length() == 0) {
            return;
        }
        final String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr;
            try {
                arr = new JSONArray(sp.getString(KEY, "[]"));
            } catch (Throwable t) {
                arr = new JSONArray();
            }
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("dir", "out");
            item.put("text", text);
            item.put("ts", System.currentTimeMillis());
            item.put("status", "pending");
            arr.put(item);
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable t) {
            return;
        }
        // 尽力推一条手表通知（不经 EV，没装 EV 的手环也能看到）
        SyncEngine e = SyncEngine.get(ctx);
        if (!e.hasNode()) {
            return; // 未连接：留在队列里，等连上后由留言页 flush
        }
        e.notifyWatch("手机留言", text, new SyncEngine.Cb() {
            @Override public void on(boolean ok, String m) { /* 尽力推送 */ }
        });
        JSONObject o = new JSONObject();
        try {
            o.put("action", "chat");
            o.put("id", id);
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
        } catch (Throwable t) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        e.send(o.toString(), new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject r = new JSONObject(json);
                    boolean ack = "chat_ack".equals(r.optString("action"))
                            || (r.optBoolean("ok", false)
                                && !"no courses".equals(r.optString("reason")));
                    if (ack) {
                        SharedPreferences sp =
                                appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                        JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject it = arr.optJSONObject(i);
                            if (it != null && id.equals(it.optString("id"))) {
                                it.put("status", "sent");
                                break;
                            }
                        }
                        sp.edit().putString(KEY, arr.toString()).apply();
                    }
                } catch (Throwable ignored) {
                }
            }
            @Override public void onTimeout(String hint) { /* 保持 pending，留言页补发 */ }
            @Override public void onError(String msg) { /* 保持 pending，留言页补发 */ }
        });
    }

    private void load() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        try {
            items = new JSONArray(sp.getString(KEY, "[]"));
        } catch (Throwable t) {
            items = new JSONArray();
        }
    }

    private void save() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY, items.toString()).apply();
    }
}