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
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
 *   双向        {"action":"typing","state":"start|upd|stop","text":"…","ts":…}  输入态 + 草稿（P3）
 */
public class MessageActivity extends Activity {

    private int lastThemeVersion = 0;

    private static final String PREF = "ev_message_queue";
    private static final String KEY = "items";
    /** P1：快捷短语（两端各自本地维护；跨端下发 phrases 留到 P3） */
    private static final String PHRASE_KEY = "phrases";
    private static final String[] DEFAULT_PHRASES = {"在上课", "马上到", "稍后回你", "到了", "好的"};
    private static final int PHRASE_MAX_LEN = 20;

    // ---- P3 输入态（typing）参数（见 docs 方案 §3.3） ----
    /** 开关（双端可关）：关掉后既不显示对方输入态，也不上报自己的输入态 */
    private static final String TYPING_ENABLED_KEY = "typing_enabled";
    /** 发送节流：≥800ms 一条，且文本变化才发（interconnect 是事件推送，高频小包会丢） */
    private static final long TYPING_THROTTLE_MS = 800;
    /** 草稿文本上限（超出截断，避免报文过大） */
    private static final int TYPING_MAX_LEN = 40;
    /** 收到对方 typing 时若 now-ts>3s → 丢弃（乱序/延迟的草稿显示出来反而是错的） */
    private static final long TYPING_EXPIRE_MS = 3000;
    /** 对方 5s 无新包 → 自动清掉输入态（防「卡住一直显示正在输入」） */
    private static final long TYPING_CLEAR_MS = 5000;
    /** 已提醒过的消息 id（去重的唯一依据） */
    private static final String SEEN_KEY = "seen_ids";
    private static final int SEEN_MAX = 500;

    private LinearLayout listBox;
    private ScrollView scrollBox;   // P0：气泡流需在发送/接收后自动滚到底
    private LinearLayout phraseBox; // P1：快捷短语横滑条
    private TextView typingHint;    // P3：对方输入态提示（「对方正在输入：草稿」）
    private TextView stateView;
    private EditText inputView;
    private final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    // ---- P3 输入态运行时状态 ----
    private boolean typingEnabled = true;      // 双端可关
    private long lastTypingSentAt = 0;         // 发送节流
    private String lastTypingText = null;      // 文本没变不发
    private boolean peerTypingActive = false;  // 对方正在输入
    private String peerTypingText = "";        // 对方草稿（实时可见）
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable peerTypingClear;

    // ======================= 微信基准色板（2026-10-05 真机 1080×1920@3x 截图取样） =======================
    // 会话背景 #EDEDED / 对方气泡 #FFFFFF / 我方气泡 #95EC69（微信绿，**黑字**）
    // 正文 #191919 / 输入栏 #F7F7F7 / 时间戳 #B2B2B2 / 发送键 #07C160
    // ⚠️ 只在本页局部覆盖，绝不改 Ui 全局 token（那会影响首页/课程表/设置）。
    private int wxChtBg()    { return Ui.isDark() ? 0xFF111111 : 0xFFEDEDED; }
    private int wxNavBg()    { return Ui.isDark() ? 0xFF1E1E1E : 0xFFEDEDED; }
    private int wxInBubble() { return Ui.isDark() ? 0xFF2C2C2C : 0xFFFFFFFF; }
    private int wxOutBubble(){ return Ui.isDark() ? 0xFF3EB575 : 0xFF95EC69; }
    private int wxBodyText() { return Ui.isDark() ? 0xFFE5E5E5 : 0xFF191919; }
    private int wxTimeText() { return Ui.isDark() ? 0xFF7F7F7F : 0xFFB2B2B2; }
    private int wxBarBg()    { return Ui.isDark() ? 0xFF1E1E1E : 0xFFF7F7F7; }
    private int wxInputBg()  { return Ui.isDark() ? 0xFF2C2C2C : 0xFFFFFFFF; }
    private int wxSendGreen(){ return Ui.isDark() ? 0xFF3EB575 : 0xFF07C160; }

    /** 队列项：{id, dir:"out"|"in", text, ts, status:"pending"|"sent"} —— 按时间顺序 */
    private JSONArray items = new JSONArray();
    private boolean sending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.setBackgroundColor(wxChtBg());
        View nav = Ui.header(this, "留言");
        nav.setBackgroundColor(wxNavBg());
        root.addView(nav);
        root.addView(Ui.space(this, 2));

        // 状态行：微信式「干净」——仅未连接 / 有待发送时显示，一切正常则隐藏（不留常驻说明）
        stateView = Ui.text(this, "", 11f, wxTimeText(), false);
        stateView.setGravity(android.view.Gravity.CENTER);
        stateView.setVisibility(View.GONE);
        root.addView(stateView);
        root.addView(Ui.space(this, 4));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scrollBox = new ScrollView(this);
        scrollBox.setBackgroundColor(wxChtBg());
        scrollBox.addView(listBox);
        root.addView(scrollBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // P3：对方输入态提示（钉在短语条上方；有内容才显示）
        typingHint = Ui.text(this, "", 11.5f, Ui.ACCENT, false);
        typingHint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        typingHint.setVisibility(View.GONE);
        root.addView(typingHint);

        // P1：快捷短语横滑条（钉在输入栏上方，与手环端 phrase-swiper 交互一致）
        HorizontalScrollView phraseScroll = new HorizontalScrollView(this);
        phraseScroll.setHorizontalScrollBarEnabled(false);
        phraseBox = new LinearLayout(this);
        phraseBox.setOrientation(LinearLayout.HORIZONTAL);
        phraseScroll.addView(phraseBox);
        root.addView(phraseScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.space(this, 6));

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inputView = new EditText(this);
        inputView.setHint("发消息…");
        inputView.setTextSize(15f);
        inputView.setTextColor(wxBodyText());
        inputView.setHintTextColor(wxTimeText());
        inputView.setBackground(Ui.round(wxInputBg(), 6, 0, this));
        inputView.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        inputView.setMaxLines(4);
        // P3：输入变化 → 节流上报输入态（草稿实时同步给手环）
        inputView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                onTypingChanged(s == null ? "" : s.toString());
            }
        });
        sendRow.addView(inputView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button send = Ui.button(this, "发送", true, new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        send.setTextColor(0xFFFFFFFF);
        send.setBackground(Ui.round(wxSendGreen(), 6, 0, this));   // 微信发送键绿
        send.setPadding(Ui.dp(this, 15), Ui.dp(this, 9), Ui.dp(this, 15), Ui.dp(this, 9));
        sendRow.addView(send);
        root.addView(sendRow);
        root.addView(Ui.space(this, 6));

        // 按钮精简：诊断类动作移除（补发自动进行）。
        // P1.1：把「长按短语可编辑/删除」常驻写在这里 —— 短语的删除入口不能只靠用户猜长按。
        LinearLayout footRow = new LinearLayout(this);
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        footRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        footRow.addView(Ui.text(this, "长按短语可编辑", 10.5f, Ui.MUTED, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView typingToggle = Ui.text(this, "", 11f, Ui.MUTED, false);
        typingToggle.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        typingToggle.setClickable(true);
        updateTypingToggleLabel(typingToggle);
        typingToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                typingEnabled = !typingEnabled;
                saveTypingEnabled();
                updateTypingToggleLabel(typingToggle);
                if (!typingEnabled) {          // 关掉：立刻清掉本端显示的对方输入态
                    peerTypingActive = false;
                    peerTypingText = "";
                    renderTypingHint();
                }
            }
        });
        footRow.addView(typingToggle);
        TextView clearLink = Ui.text(this, "清空记录", 11.5f, Ui.MUTED, false);
        clearLink.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        clearLink.setClickable(true);
        clearLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearAll(); }
        });
        footRow.addView(clearLink);
        root.addView(footRow);

        renderPhrases();
        typingEnabled = loadTypingEnabled();
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
        boolean show = true;
        if (!e.hasNode()) {
            stateView.setText(pending > 0 ? ("未连接手环　·　待发送 " + pending + " 条") : "未连接手环");
            stateView.setTextColor(wxTimeText());
        } else if (pending > 0) {
            stateView.setText("待发送 " + pending + " 条");
            stateView.setTextColor(Ui.WARN);
        } else {
            show = false;   // 一切正常：微信式干净界面，不显示常驻状态行
        }
        stateView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private int countPending() {
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && "out".equals(o.optString("dir"))
                    && "pending".equals(o.optString("status", "pending"))) {
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
            // P2：三态 —— pending(待发送) / sent(已送达) / read(已读，手环已回执)
            String status = o.optString("status", "pending");
            listBox.addView(bubbleRow(out, status, o.optString("text"), maxW));
            listBox.addView(Ui.space(this, 10));
        }
        scrollToBottom();
    }

    /** 居中时间分割（10.5sp 灰字，上下留白） */
    private View timeDivider(long ts) {
        TextView t = Ui.text(this, wxFmt(ts), 12f, wxTimeText(), false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** 单条气泡行：头像 + 气泡（我方在右、对方在左），对方一侧用 spacer 把气泡推向另一侧 */
    private View bubbleRow(boolean out, String status, String text, int maxW) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);

        int av = Ui.dp(this, 40);                     // 微信头像 40dp
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(out ? R.drawable.ic_smartphone : R.drawable.ic_tab_watch);
        avatar.setColorFilter(out ? wxSendGreen() : Ui.MUTED);
        avatar.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        avatar.setBackground(Ui.round(out ? 0xFFDCF3E3 : Ui.CARD2, 4, 0, this));  // 微信=圆角方块
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(av, av);
        alp.gravity = android.view.Gravity.TOP;

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));  // 微信 12×9
        bubble.setBackground(Ui.round(out ? wxOutBubble() : wxInBubble(), 5, 0, this));       // 微信圆角≈5dp、无描边

        // 微信正文：我方绿底 / 对方白底**都用深色字**（#191919），绝非白字
        TextView body = Ui.textLh(this, text, 16f, wxBodyText(), false, Ui.LH_BODY);
        body.setMaxWidth(maxW);
        bubble.addView(body);

        if (out) {
            // 我方气泡内的送达状态（P2 三态）：
            //   待发送 = 尚未确认；已送达 = 手环回了 chat_ack；已读 = 手环回了 chat_read（真·看见）
            //   ⚠️「已读」只可能来自手环上报，手机端绝不推断（手环没进页面就永远停在「已送达」）。
            String stLabel;
            int stColor;
            if ("read".equals(status)) {
                stLabel = "已读";
                stColor = 0xAA0B6B33;      // 深绿：已读（绿底上可读）
            } else if ("sent".equals(status)) {
                stLabel = "已送达";
                stColor = 0x99000000;      // 半透明黑：已送达
            } else {
                stLabel = "待发送";
                stColor = 0xAAA03000;      // 暗棕红：待发送
            }
            TextView st = Ui.text(this, stLabel, 10f, stColor, false);
            st.setPadding(0, Ui.dp(this, 3), 0, 0);
            st.setGravity(android.view.Gravity.END);
            bubble.addView(st);
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);   // 微信头像-气泡间距≈10dp

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

    /** 微信式时间分割：今天 → HH:mm；昨天 → 昨天 HH:mm；今年 → M月d日 HH:mm；跨年 → yyyy年M月d日 HH:mm */
    private String wxFmt(long ts) {
        if (ts <= 0) return "";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        String hm = String.format(Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        if (now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) {
            return hm;
        }
        java.util.Calendar y = java.util.Calendar.getInstance();
        y.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (y.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && y.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "昨天 " + hm;
        }
        if (now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)) {
            return (c.get(java.util.Calendar.MONTH) + 1) + "月"
                    + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 " + hm;
        }
        return c.get(java.util.Calendar.YEAR) + "年" + (c.get(java.util.Calendar.MONTH) + 1) + "月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 " + hm;
    }

    // ======================= 发送 =======================

    private void sendMessage() {
        final String text = inputView.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        inputView.setText("");
        sendText(text);
    }

    /** 输入框与快捷短语共用的发送通道（P1 抽出） */
    private void sendText(String text) {
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
        stopUserTyping();   // P3：本端已发出 → 立即通知对方清掉草稿
        flush();
    }

    // ======================= 输入态（P3：typing，双端可关） =======================

    private boolean loadTypingEnabled() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(TYPING_ENABLED_KEY, true);
    }

    private void saveTypingEnabled() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(TYPING_ENABLED_KEY, typingEnabled).apply();
    }

    private void updateTypingToggleLabel(TextView tv) {
        tv.setText(typingEnabled ? "输入态 开" : "输入态 关");
        tv.setTextColor(typingEnabled ? Ui.ACCENT : Ui.MUTED);
    }

    /** 输入框内容变化：节流（≥800ms）+ 文本变化才发；空串发 stop。 */
    private void onTypingChanged(String text) {
        if (!typingEnabled) {
            return;
        }
        String t = text == null ? "" : text;
        if (t.length() > TYPING_MAX_LEN) {
            t = t.substring(0, TYPING_MAX_LEN);
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingSentAt < TYPING_THROTTLE_MS) {
            return;                       // 节流：小包太密会丢
        }
        if (t.equals(lastTypingText)) {
            return;                       // 文本没变不发
        }
        lastTypingSentAt = now;
        lastTypingText = t;
        sendTyping(t.length() == 0 ? "stop" : "upd", t);
    }

    /** 发送成功后立即通知对方清掉输入态（避免草稿残留在对面）。 */
    private void stopUserTyping() {
        if (!typingEnabled) {
            return;
        }
        lastTypingText = "";
        lastTypingSentAt = System.currentTimeMillis();
        sendTyping("stop", null);
    }

    /** 发一条 typing 报文：走「无状态发送」，绝不占用 pending/超时窗口，也不阻塞留言发送。 */
    private void sendTyping(String state, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("action", "typing");
            o.put("state", state);
            if (text != null) {
                o.put("text", text);
            }
            o.put("ts", System.currentTimeMillis());
            SyncEngine.get(this).sendStateless(o.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 对方输入态（由手环 typing 报文驱动）。
     * @return true = 本条是 typing（已消费，调用方不要再送进留言流/不提醒）
     */
    boolean onPeerTyping(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"typing".equals(o.optString("action"))) {
                return false;
            }
            if (!typingEnabled) {
                return true;              // 已关：消费掉但不显示
            }
            long ts = o.optLong("ts", 0);
            if (ts > 0 && System.currentTimeMillis() - ts > TYPING_EXPIRE_MS) {
                return true;              // 过期/乱序 → 丢弃
            }
            if ("stop".equals(o.optString("state", "upd"))) {
                peerTypingActive = false;
                peerTypingText = "";
            } else {
                String t = o.optString("text", "");
                if (t.length() > TYPING_MAX_LEN) {
                    t = t.substring(0, TYPING_MAX_LEN);
                }
                peerTypingActive = true;
                peerTypingText = t;
            }
            renderTypingHint();
            schedulePeerTypingClear();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void renderTypingHint() {
        if (typingHint == null) {
            return;
        }
        if (!peerTypingActive) {
            typingHint.setVisibility(View.GONE);
            return;
        }
        typingHint.setText(peerTypingText.length() > 0
                ? ("对方正在输入：" + peerTypingText)
                : "对方正在输入…");
        typingHint.setVisibility(View.VISIBLE);
    }

    /** 5s 无新包 → 自动清掉输入态（防「卡住一直显示正在输入」）。 */
    private void schedulePeerTypingClear() {
        if (peerTypingClear != null) {
            uiHandler.removeCallbacks(peerTypingClear);
        }
        peerTypingClear = new Runnable() {
            @Override public void run() {
                peerTypingActive = false;
                peerTypingText = "";
                renderTypingHint();
            }
        };
        uiHandler.postDelayed(peerTypingClear, TYPING_CLEAR_MS);
    }

    // ======================= 快捷短语（P1：纯本地） =======================

    /** 读取短语列表（无记录/损坏时回落到默认五条） */
    private JSONArray loadPhrases() {
        try {
            JSONArray arr = new JSONArray(
                    getSharedPreferences(PREF, MODE_PRIVATE).getString(PHRASE_KEY, ""));
            if (arr.length() > 0) {
                return arr;
            }
        } catch (Throwable ignored) {
        }
        JSONArray def = new JSONArray();
        for (String p : DEFAULT_PHRASES) {
            def.put(p);
        }
        return def;
    }

    private void savePhrases(JSONArray arr) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(PHRASE_KEY, arr.toString()).apply();
    }

    /** 重建横滑短语条：若干短语 chip + 末尾「＋自定义」 */
    private void renderPhrases() {
        if (phraseBox == null) {
            return;
        }
        phraseBox.removeAllViews();
        JSONArray list = loadPhrases();
        for (int i = 0; i < list.length(); i++) {
            String p = list.optString(i);
            if (p.length() > 0) {
                phraseBox.addView(phraseChip(p));
            }
        }
        phraseBox.addView(addChip());
    }

    /** 短语 chip：点击＝直接发送，长按＝编辑/删除 */
    private View phraseChip(final String p) {
        TextView t = Ui.text(this, p, 12.5f, Ui.TEXT, false);
        t.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        t.setBackground(Ui.round(Ui.CARD2, 15, Ui.LINE, this));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendText(p); }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showPhraseMenu(p); return true; }
        });
        return t;
    }

    /** 末尾「＋自定义」chip：新增短语 */
    private View addChip() {
        TextView t = Ui.text(this, "＋ 自定义", 12.5f, Ui.ACCENT, true);
        t.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        t.setBackground(Ui.round(Ui.ACCENT_LIGHT, 15, 0, this));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editPhrase(null); }
        });
        return t;
    }

    /** 长按短语 → 编辑 / 删除 */
    private void showPhraseMenu(final String p) {
        new AlertDialog.Builder(this)
                .setTitle(p)
                .setItems(new String[]{"编辑", "删除"},
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            editPhrase(p);
                        } else {
                            deletePhrase(p);
                        }
                    }
                })
                .show();
    }

    /** 新增（oldText=null）或编辑短语；编辑时对话框内直接给「删除」入口（不必去猜长按） */
    private void editPhrase(final String oldText) {
        final EditText et = new EditText(this);
        et.setText(oldText == null ? "" : oldText);
        et.setHint("最多 " + PHRASE_MAX_LEN + " 字");
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(Ui.MUTED);
        et.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        if (oldText != null && oldText.length() > 0) {
            et.setSelection(oldText.length());
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(oldText == null ? "新增快捷短语" : "编辑快捷短语")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        savePhrase(oldText, et.getText().toString());
                    }
                })
                .setNegativeButton("取消", null);
        if (oldText != null) {
            // 编辑时直接给「删除」入口——不必让用户去猜长按
            b.setNeutralButton("删除", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    deletePhrase(oldText);
                }
            });
        }
        b.show();
    }

    /** 保存短语：oldText 非空＝就地替换该条，否则追加为新增 */
    private void savePhrase(String oldText, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.length() == 0) {
            return;
        }
        if (v.length() > PHRASE_MAX_LEN) {
            v = v.substring(0, PHRASE_MAX_LEN);
        }
        JSONArray list = loadPhrases();
        JSONArray next = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < list.length(); i++) {
            String cur = list.optString(i);
            if (oldText != null && !replaced && cur.equals(oldText)) {
                next.put(v);
                replaced = true;
            } else {
                next.put(cur);
            }
        }
        if (!replaced) {
            next.put(v);   // 新增，或原项已不存在
        }
        savePhrases(next);
        renderPhrases();
    }

    private void deletePhrase(String p) {
        JSONArray list = loadPhrases();
        JSONArray next = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < list.length(); i++) {
            String cur = list.optString(i);
            if (!removed && cur.equals(p)) {
                removed = true;   // 只删第一条匹配
            } else {
                next.put(cur);
            }
        }
        savePhrases(next);
        renderPhrases();
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
                // P3：输入态（typing）优先消费 —— 只更新「对方正在输入」提示，绝不进留言流/不提醒
                if (host instanceof MessageActivity
                        && ((MessageActivity) host).onPeerTyping(json)) {
                    return;
                }
                // P2：先看是不是手环的「已读回执」（chat_read）。
                //     是 → handleUnsolicited 内部会升级本地状态（sent→read）并 return，
                //     这里再补一次重渲染，让气泡状态实时可见。
                boolean read = isReadReceipt(json);
                handleUnsolicited(host, json);
                if (read && host instanceof MessageActivity) {
                    MessageActivity ma = (MessageActivity) host;
                    ma.load();
                    ma.render();
                    ma.refreshState();
                }
            }
        });
    }

    /** 是否手环已读回执（{"action":"chat_read",...}） */
    private static boolean isReadReceipt(String json) {
        try {
            return "chat_read".equals(new JSONObject(json).optString("action"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * P2 已读回执：手环**进入留言页并渲染完**后上报 {"action":"chat_read","ids":[...]}，
     * 把这些 id 对应的「我方 out 消息」从 sent 升为 read（只升不降，幂等）。
     *
     * ⚠️ 纪律（方案验收核心）：已读**只能**由手环上报驱动，手机端绝不因「已发出」而推断。
     *    手环没开机 / 没进页面 → 永远停在「已送达」，这才是真实状态。
     *
     * @return true = 本条是已读回执（已消费，调用方不要再把它送进留言流）
     */
    private static synchronized boolean applyReadReceipt(Context ctx, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"chat_read".equals(o.optString("action"))) {
                return false;
            }
            JSONArray ids = o.optJSONArray("ids");
            if (ids == null || ids.length() == 0) {
                return true;    // 是已读回执但无 ids：消费掉，不进留言流、不提醒
            }
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr;
            try {
                arr = new JSONArray(sp.getString(KEY, "[]"));
            } catch (Throwable t) {
                arr = new JSONArray();
            }
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null || !"out".equals(it.optString("dir"))) {
                    continue;
                }
                String id = it.optString("id");
                for (int j = 0; j < ids.length(); j++) {
                    if (id.equals(ids.optString(j)) && !"read".equals(it.optString("status"))) {
                        it.put("status", "read");
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                sp.edit().putString(KEY, arr.toString()).apply();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录已提醒过的 id；返回 true = 首次见到（应提醒），false = 重复（应丢弃） */

    /**
     * 处理手环主动发来的留言。
     * ⚠️ 按 id 去重：同一条只入库 + 提醒一次，重复到达直接丢弃 —— **禁止重复提醒**。
     */
    static void handleUnsolicited(final Context ctx, String json) {
        try {
            // P2 已读回执（{"action":"chat_read",...}）：升级本地 out 消息状态后消费掉，不进留言流
            if (applyReadReceipt(ctx, json)) {
                return;
            }
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