package com.application.watch.classschedule;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.notify.NotifyApi;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;

import java.nio.charset.Charset;
import java.util.List;

/**
 * 同步引擎：把"连上手环 EV 课程表"这件事封装成 4 个可观测的步骤。
 *
 * 顺序是硬规矩：拿 nodeId → 授权 → 注册监听 → 发消息。
 * 没有注册监听就发消息，必然 0 回包（本次踩坑 4）。
 */
public final class SyncEngine {

    public static final int PENDING = 0, RUNNING = 1, OK = 2, FAIL = 3;

    public interface Steps {
        void onUpdate(String[] labels, int[] states, String[] details);
        void onFinish(boolean ok, String hint);
    }

    public interface Reply {
        void onReply(String json);
        void onTimeout(String hint);
        void onError(String msg);
    }

    public interface Cb {
        void on(boolean ok, String msg);
    }

    /**
     * 无人认领的消息（手环主动 push）：当没有正在等待回包的请求时，
     * 不再直接丢弃，而是交给观察者处理（例如留言提醒）。
     */
    public interface Observer {
        void onMessage(String json);
    }

    private static SyncEngine inst;

    public static synchronized SyncEngine get(Context c) {
        if (inst == null) {
            inst = new SyncEngine(c.getApplicationContext());
        }
        return inst;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MessageApi api;
    private String nodeId;
    private boolean listening;
    private Reply pending;
    private Runnable timeoutTask;

    /** 最近一次成功同步到的手环信息 */
    public String deviceName = "";
    public String nickname = "";
    public String versionName = "";
    public int versionCode = 0;
    public int courseCount = 0;
    public String lastExportJson;

    // ======================= 多设备（多手环）支持 =======================
    // 一个账号下可能同时连着多台手环（如手环 + 手表），getConnectedNodes() 会返回多台。
    // 老代码直接取 nodes.get(0) → 多设备用户会连错机器。这里改成：记住上次选择；没记住就问用户。

    /** 设置项存储（与 SyncService 共用同一个文件） */
    public static final String PREFS = "ev_settings";
    /** 记住的手环 nodeId */
    public static final String KEY_PREFERRED_NODE = "preferred_node";

    public static final class DeviceInfo {
        public final String id;
        public final String name;

        DeviceInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 发现多台设备时交给 UI 选择；未注册则退回"取第一台"的旧行为 */
    public interface NodeChooser {
        void onNeedChoose(List<DeviceInfo> devices, String preferredId);
    }

    private NodeChooser chooser;
    private List<DeviceInfo> pendingDevices;
    private Steps pendingSteps;
    private String[] pendingLabels, pendingDetails;
    private int[] pendingStates;

    // 埋点统计：本次连接第一个失败步（1-4，0=没失败）与详情，供 Stats.connectEnd 落盘
    private int failStep;
    private String failDetail = "";

    public void setNodeChooser(NodeChooser c) {
        chooser = c;
    }

    public List<DeviceInfo> pendingDevices() {
        return pendingDevices;
    }

    public String preferredNodeId() {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PREFERRED_NODE, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public void setPreferredNodeId(String id) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PREFERRED_NODE, id == null ? "" : id).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 用户在 UI 里选好了设备：记住它并继续连接流程 */
    public void chooseNode(String id) {
        if (pendingDevices == null || id == null) {
            return;
        }
        DeviceInfo hit = null;
        for (int i = 0; i < pendingDevices.size(); i++) {
            if (id.equals(pendingDevices.get(i).id)) {
                hit = pendingDevices.get(i);
                break;
            }
        }
        if (hit == null) {
            return;
        }
        Steps s = pendingSteps;
        String[] labels = pendingLabels, details = pendingDetails;
        int[] states = pendingStates;
        pendingDevices = null;
        pendingSteps = null;
        if (s == null || labels == null || states == null || details == null) {
            return;
        }
        setPreferredNodeId(hit.id);
        useNode(hit, "(已记住)", s, labels, states, details);
    }

    /** 落定一台设备，进入第 3 步（授权） */
    private void useNode(DeviceInfo d, String note, Steps s,
                         String[] labels, int[] states, String[] details) {
        nodeId = d.id;
        deviceName = d.name;
        states[1] = OK;
        details[1] = d.name + (note == null ? "" : "  " + note);
        emit(s, labels, states, details);
        stepPerm(s, labels, states, details);
    }

    private SyncEngine(Context c) {
        ctx = c;
        try {
            api = Wearable.getMessageApi(c);
        } catch (Throwable t) {
            api = null;
        }
    }

    public boolean sdkReady() { return api != null; }
    public boolean hasNode() { return nodeId != null; }
    public String getNodeId() { return nodeId; }
    public boolean connected() { return hasNode() && versionName.length() > 0; }

    private final OnMessageReceivedListener rx = new OnMessageReceivedListener() {
        @Override
        public void onMessageReceived(String from, byte[] message) {
            final String text = (message == null) ? "" : new String(message, Charset.forName("UTF-8"));
            main.post(new Runnable() {
                @Override public void run() {
                    if (timeoutTask != null) {
                        main.removeCallbacks(timeoutTask);
                        timeoutTask = null;
                    }
                    Reply r = pending;
                    pending = null;
                    if (r != null) {
                        r.onReply(text);
                        return;
                    }
                    // 没有待回包的请求 → 这是手环主动 push，交观察者处理（不再静默丢弃）
                    if (observer != null) {
                        try {
                            observer.onMessage(text);
                        } catch (Throwable ignored) {
                        }
                    }
                    // 消息投递追踪：收到来自后台的推送时，自动回 ACK
                    ackIfNeeded(text);
                }
            });
        }
    };

    /** 如果消息体含 messageId，则回 ACK 到后台用于投递追踪 */
    private void ackIfNeeded(String text) {
        try {
            if (text == null || text.isEmpty()) {
                return;
            }
            org.json.JSONObject msg = new org.json.JSONObject(text);
            String messageId = msg.optString("messageId", null);
            if (messageId == null || messageId.isEmpty()) {
                return;
            }
            org.json.JSONObject ack = new org.json.JSONObject();
            ack.put("messageId", messageId);
            ack.put("deviceId", nodeId != null ? nodeId : "");
            Net.postJson(Net.BASE + "/api/notify/ack", ack.toString(), null);
        } catch (Throwable ignored) {
        }
    }

    private Observer observer;

    /** 注册/清除「手环主动消息」观察者（传 null 清除） */
    public void setObserver(Observer o) {
        observer = o;
    }

    private void ensureListener() {
        if (api == null || nodeId == null || listening) {
            return;
        }
        try {
            api.addListener(nodeId, rx).addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override public void onSuccess(Void v) { listening = true; }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 发一条报文，等待回包（6 秒无回应判超时） */
    public void send(final String json, final Reply cb) {
        if (api == null) {
            cb.onError("穿戴 SDK 不可用，请确认已安装「小米运动健康」");
            return;
        }
        if (nodeId == null) {
            cb.onError("还没有选中设备，请先完成连接");
            return;
        }
        lastSendAt = System.currentTimeMillis();
        ensureListener();
        pending = cb;
        timeoutTask = new Runnable() {
            @Override public void run() {
                timeoutTask = null;
                pending = null;
                cb.onTimeout("手环没有回应。请先在手表上打开一次「EV 课程表」，然后重试。");
            }
        };
        main.postDelayed(timeoutTask, 6000);
        try {
            api.sendMessage(nodeId, json.getBytes(Charset.forName("UTF-8")))
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) { /* 受理 != 送达 */ }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override                         public void onFailure(Exception e) {
                            if (timeoutTask != null) {
                                main.removeCallbacks(timeoutTask);
                                timeoutTask = null;
                            }
                            pending = null;
                            cb.onError(humanize(e));
                        }
                    });
        } catch (Throwable t) {
            cb.onError(humanize(t));
        }
    }

    public void ping(Reply cb) {
        send("{\"action\":\"ping\"}", cb);
    }

    public void export(Reply cb) {
        send("{\"action\":\"export\"}", cb);
    }

    /** 请求课程表清单（多课程表导出前置）：回包 {ok,action:"list_schedules",names:[...],current:N} */
    public void listSchedules(Reply cb) {
        send("{\"action\":\"list_schedules\"}", cb);
    }

    /** 导出指定第 index 套课程表（index 对应 allCourses_<index>） */
    public void exportSchedule(int index, Reply cb) {
        send("{\"action\":\"export\",\"scheduleIndex\":" + index + "}", cb);
    }

    /** 索取手环设备ID（APK 侧拿不到）：回包 {ok,action:"get_device_id",deviceId,deviceId4,fallback} */
    public void getDeviceId(Reply cb) {
        send("{\"action\":\"get_device_id\"}", cb);
    }

    /** 一键激活：把后端换来的 18 位激活码交给手环本地校验并落库 */
    public void activate(String code18, Reply cb) {
        send("{\"action\":\"activate\",\"code\":" + quote(code18) + "}", cb);
    }

    public void setNickname(String nick, Reply cb) {
        send("{\"action\":\"update_settings\",\"payload\":{\"nickname\":" + quote(nick) + "}}", cb);
    }

    public static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ======================= 前台保活心跳 =======================
    // 手环上的 EV 快应用闲置一会儿就会退出（症状：首页连得好好的，切到别的页操作就无回应）。
    // App 在前台期间每 60s 静默 ping 一次保活；ping 超时说明 EV 退了，悄悄拉起它，
    // 下一轮心跳自然恢复。全程无 UI、不改连接状态；有操作在途或 30s 内有真实收发时跳过，
    // 避免单 pending 槽位被心跳顶掉用户操作的回包。
    private static final long KEEPALIVE_MS = 60 * 1000;
    private long lastSendAt;
    private Runnable keepaliveTick;
    private boolean keepaliveOn;

    /** App 进前台时调用（EvApp 生命周期钩子）；重复调用安全。 */
    public void startKeepalive() {
        if (keepaliveOn) {
            return;
        }
        keepaliveOn = true;
        scheduleKeepalive(KEEPALIVE_MS);
    }

    /** App 退到后台时调用：心跳停止，留言接收交给常驻服务观察者。 */
    public void stopKeepalive() {
        keepaliveOn = false;
        if (keepaliveTick != null) {
            main.removeCallbacks(keepaliveTick);
            keepaliveTick = null;
        }
    }

    private void scheduleKeepalive(long delay) {
        if (!keepaliveOn) {
            return;
        }
        if (keepaliveTick != null) {
            main.removeCallbacks(keepaliveTick);
        }
        keepaliveTick = new Runnable() {
            @Override public void run() {
                keepaliveTick = null;
                if (!keepaliveOn) {
                    return;
                }
                boolean idle = (System.currentTimeMillis() - lastSendAt) > 30 * 1000;
                if (!connected()) {
                    // 离线自愈：心跳发现没连上（或连接中断）→ 自动重连一轮
                    android.util.Log.d("EVProbe", "keepalive: offline → autoReconnect");
                    autoReconnect();
                } else if (nodeId != null && pending == null && idle) {
                    android.util.Log.d("EVProbe", "keepalive: ping");
                    ping(new Reply() {
                        @Override public void onReply(String json) { /* 通道活着，什么都不做 */ }
                        @Override public void onTimeout(String hint) {
                            android.util.Log.d("EVProbe", "keepalive: EV 无回应，拉起");
                            try {
                                Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx));
                            } catch (Throwable ignored) {
                            }
                        }
                        @Override public void onError(String msg) { /* 静默 */ }
                    });
                } else {
                    android.util.Log.d("EVProbe", "keepalive: skip("
                            + (nodeId == null ? "未连接" : pending != null ? "操作在途" : "刚有收发") + ")");
                }
                scheduleKeepalive(KEEPALIVE_MS);
            }
        };
        main.postDelayed(keepaliveTick, delay);
    }

    // ======================= 状态条回调 + 离线自动重连 =======================
    // 供各页面的 ConnectionBar 使用：弱引用持有回调（页面销毁自动失效，不泄漏 Activity）。
    private java.lang.ref.WeakReference<Runnable> statusCb;
    private boolean autoRetryRunning;

    /** 注册状态条刷新回调；连接进度 / 心跳结果都会触发（主线程）。 */
    public void setStatusCallback(Runnable r) {
        statusCb = (r == null) ? null : new java.lang.ref.WeakReference<>(r);
        notifyStatus();
    }

    private void notifyStatus() {
        main.post(new Runnable() {
            @Override public void run() {
                Runnable r = (statusCb == null) ? null : statusCb.get();
                if (r != null) {
                    try {
                        r.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 离线时自动重连一轮。四步连接内层已带「自动拉起 EV ×3」，外层不再叠次数——
     *  一轮失败后状态条转「去连接调试」，心跳每 60s 会自动再触发新一轮。单例锁防多页面打架。 */
    public void autoReconnect() {
        if (autoRetryRunning || connected()) {
            return;
        }
        autoRetryRunning = true;
        notifyStatus();
        connect(new Steps() {
            @Override public void onUpdate(String[] labels, int[] st, String[] details) {
                notifyStatus();
            }
            @Override public void onFinish(boolean ok, String hint) {
                autoRetryRunning = false;
                notifyStatus();
            }
        });
    }

    /** 状态条用：是否正处于自动重连中。 */
    public boolean autoRetryRunning() {
        return autoRetryRunning;
    }

    // ======================= 四步连接 =======================

    public void connect(final Steps s) {
        // 重新连接：清掉上一次"等待用户选设备"的残留状态
        pendingDevices = null;
        pendingSteps = null;
        failStep = 0;
        failDetail = "";
        Stats.connectStart(ctx);
        final String[] labels = {"初始化穿戴服务", "查找已连接设备", "申请设备权限", "连接 EV 课程表"};
        final int[] states = {RUNNING, PENDING, PENDING, PENDING};
        final String[] details = {"", "", "", ""};
        emit(s, labels, states, details);
        stepService(s, labels, states, details);
    }

    private void emit(final Steps s, final String[] labels, final int[] states, final String[] details) {
        // 顺手记下本次连接「第一个失败步」及其详情（finish 拿不到 states，埋点统计在这里采集）
        try {
            if (failStep == 0) {
                for (int i = 0; i < states.length; i++) {
                    if (states[i] == FAIL) {
                        failStep = i + 1;
                        failDetail = details[i] == null ? "" : details[i];
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        main.post(new Runnable() {
            @Override public void run() {
                if (s != null) {
                    s.onUpdate(labels, states, details);
                }
            }
        });
        notifyStatus();
    }

    private void finish(final Steps s, final boolean ok, final String hint) {
        try {
            Stats.connectEnd(ctx, ok, failStep, failStep > 0 ? failDetail : hint);
        } catch (Throwable ignored) {
        }
        main.post(new Runnable() {
            @Override public void run() {
                if (s != null) {
                    s.onFinish(ok, hint);
                }
            }
        });
        notifyStatus();
    }

    // ======================= 异常翻译 =======================

    /**
     * 底层 SDK 抛的是英文异常（如 {@code IllegalStateException: not bond}），
     * 直接显示会把类名/堆栈前缀糊到用户脸上。这里统一翻译成短中文短语，
     * 用于「四步进度」里的 detail（要求短，一行放得下）。
     */
    static String humanize(Throwable t) {
        String raw = String.valueOf(t);                                   // java.lang.IllegalStateException: not bond
        String msg = String.valueOf(t == null ? null : t.getMessage());   // not bond
        String low = (raw + " " + msg).toLowerCase();
        if (low.contains("not bond") || low.contains("not bonded")) {
            return "手环未在本机配对";
        }
        if (low.contains("signature")) {
            return "签名校验未通过";
        }
        if (low.contains("permission") || low.contains("securityexception")) {
            return "权限被拒绝";
        }
        if (low.contains("timeout") || low.contains("timed out")) {
            return "超时未响应";
        }
        if (low.contains("disconnected") || low.contains("binder died")) {
            return "服务连接中断";
        }
        if (low.contains("not found") || low.contains("unavailable")) {
            return "服务不可用";
        }
        // 兜底：只有 message 足够短、且不含类名前缀时才敢直接用，否则给通用文案
        if (msg.length() > 0 && msg.length() <= 40 && msg.indexOf(':') < 0) {
            return msg;
        }
        return "穿戴服务不可用";
    }

    /** 首屏失败时的可执行建议（比 detail 长，讲清楚下一步该干什么） */
    static String hintFor(Throwable t) {
        if (t == null) {
            return "请安装并打开「小米运动健康」App，并让它在后台运行";
        }
        String low = (String.valueOf(t) + " " + String.valueOf(t.getMessage())).toLowerCase();
        if (low.contains("not bond") || low.contains("not bonded")) {
            return "这台手机还没和手环配对。请打开「小米运动健康」完成手环配对、保持连接后重试。";
        }
        if (low.contains("signature")) {
            return "APK 与手环端签名不一致，无法互通。请安装与手环匹配的版本。";
        }
        return "请安装并打开「小米运动健康」App，并让它在后台运行";
    }

    // 步骤 1：穿戴服务
    private void stepService(final Steps s, final String[] labels, final int[] states, final String[] details) {
        if (api == null) {
            states[0] = FAIL;
            details[0] = "SDK 不可用";
            emit(s, labels, states, details);
            finish(s, false, "请安装并打开「小米运动健康」App，并让它在后台运行");
            return;
        }
        try {
            Wearable.getServiceApi(ctx).getServiceApiLevel()
                    .addOnSuccessListener(new OnSuccessListener<Integer>() {
                        @Override public void onSuccess(Integer lv) {
                            states[0] = OK;
                            details[0] = "穿戴服务可用";
                            emit(s, labels, states, details);
                            stepNodes(s, labels, states, details);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            states[0] = FAIL;
                            details[0] = humanize(e);
                            emit(s, labels, states, details);
                            finish(s, false, hintFor(e));
                        }
                    });
        } catch (Throwable t) {
            states[0] = FAIL;
            details[0] = humanize(t);
            emit(s, labels, states, details);
            finish(s, false, hintFor(t));
        }
    }

    // 步骤 2：找设备
    private void stepNodes(final Steps s, final String[] labels, final int[] states, final String[] details) {
        states[1] = RUNNING;
        details[1] = "正在查找…";
        emit(s, labels, states, details);
        try {
            Wearable.getNodeApi(ctx).getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override public void onSuccess(List<Node> nodes) {
                            if (nodes == null || nodes.isEmpty()) {
                                states[1] = FAIL;
                                details[1] = "没有已连接设备";
                                emit(s, labels, states, details);
                                finish(s, false, "请在「小米运动健康」里确认手环已连接，并保持连接");
                                return;
                            }
                            java.util.List<DeviceInfo> list = new java.util.ArrayList<>();
                            for (int i = 0; i < nodes.size(); i++) {
                                Node n = nodes.get(i);
                                list.add(new DeviceInfo(n.id, n.name));
                            }
                            if (list.size() == 1) {
                                useNode(list.get(0), "(唯一设备)", s, labels, states, details);
                                return;
                            }
                            // 多台手环：优先用上次记住的那台；没记住就交给 UI 让用户选
                            final String pref = preferredNodeId();
                            for (int i = 0; i < list.size(); i++) {
                                if (list.get(i).id.equals(pref)) {
                                    useNode(list.get(i), "(已记住)", s, labels, states, details);
                                    return;
                                }
                            }
                            if (chooser == null) {
                                // 没有 UI 兜底：保持老行为（取第一台），但把提示写清楚
                                useNode(list.get(0), "(默认第一台)", s, labels, states, details);
                                return;
                            }
                            pendingDevices = list;
                            pendingSteps = s;
                            pendingLabels = labels;
                            pendingStates = states;
                            pendingDetails = details;
                            states[1] = RUNNING;
                            details[1] = "发现 " + list.size() + " 台设备，等待选择…";
                            emit(s, labels, states, details);
                            final List<DeviceInfo> forUi = list;
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (chooser != null) {
                                        chooser.onNeedChoose(forUi, pref);
                                    }
                                }
                            });
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            states[1] = FAIL;
                            details[1] = humanize(e);
                            emit(s, labels, states, details);
                            finish(s, false, "读取设备列表失败，请检查小米运动健康的手环连接");
                        }
                    });
        } catch (Throwable t) {
            states[1] = FAIL;
            details[1] = humanize(t);
            emit(s, labels, states, details);
            finish(s, false, "读取设备列表失败");
        }
    }

    // 步骤 3：权限
    private void stepPerm(final Steps s, final String[] labels, final int[] states, final String[] details) {
        states[2] = RUNNING;
        details[2] = "正在申请…";
        emit(s, labels, states, details);
        try {
            Wearable.getAuthApi(ctx)
                    .requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                    .addOnSuccessListener(new OnSuccessListener<Permission[]>() {
                        @Override public void onSuccess(Permission[] ps) {
                            states[2] = OK;
                            details[2] = "已授权";
                            emit(s, labels, states, details);
                            stepPing(s, labels, states, details);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            String m = String.valueOf(e);
                            boolean sig = m.contains("Signature") || m.contains("fingerprint");
                            states[2] = FAIL;
                            details[2] = humanize(e);
                            emit(s, labels, states, details);
                            finish(s, false, sig
                                    ? "签名校验未通过：本 APK 与手环 EV 课程表不是同一把签名"
                                    : "权限申请失败，请在小米运动健康中允许本应用访问");
                        }
                    });
        } catch (Throwable t) {
            states[2] = FAIL;
            details[2] = humanize(t);
            emit(s, labels, states, details);
            finish(s, false, "权限申请失败");
        }
    }

    // 步骤 4：ping 通 EV（EV 冷启动经常超过 6s 超时：无回应自动拉起 EV 再试，最多 3 次）
    private void stepPing(final Steps s, final String[] labels, final int[] states, final String[] details) {
        stepPingOnce(s, labels, states, details, 3);
    }

    private void stepPingOnce(final Steps s, final String[] labels, final int[] states,
                              final String[] details, final int left) {
        states[3] = RUNNING;
        details[3] = (left == 3) ? "正在唤醒手环上的 EV 课程表…"
                : ("EV 没应答，已自动拉起，第 " + (4 - left) + " 次尝试…");
        emit(s, labels, states, details);
        ping(new Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    if (!o.optBoolean("ok", false) || !o.optBoolean("pong", false)) {
                        states[3] = FAIL;
                        details[3] = "EV 返回异常";
                        emit(s, labels, states, details);
                        finish(s, false, "EV 课程表返回了异常回包，请确认手环上的版本不低于 1.6.62");
                        return;
                    }
                    versionName = o.optString("versionName");
                    versionCode = o.optInt("versionCode");
                    states[3] = OK;
                    details[3] = versionName + " (code " + versionCode + ")";
                    emit(s, labels, states, details);
                    finish(s, true, "");
                } catch (Throwable t) {
                    states[3] = FAIL;
                    details[3] = "回包无法解析";
                    emit(s, labels, states, details);
                    finish(s, false, "收到回包但无法解析，请更新手环上的 EV 课程表");
                }
            }

            @Override public void onTimeout(String hint) {
                retryPing(s, labels, states, details, left, hint);
            }

            @Override public void onError(String msg) {
                retryPing(s, labels, states, details, left, msg);
            }
        });
    }

    /** ping 无回应：拉起 EV 等它冷启动后重试；用完次数才判失败。 */
    private void retryPing(final Steps s, final String[] labels, final int[] states,
                           final String[] details, final int left, final String why) {
        if (left <= 1) {
            states[3] = FAIL;
            details[3] = "无回应（已自动拉起 EV 重试过）";
            emit(s, labels, states, details);
            finish(s, false, why);
            return;
        }
        try {
            Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx));
        } catch (Throwable ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override public void run() { stepPingOnce(s, labels, states, details, left - 1); }
        }, 3500);
    }

    // ======================= 单步动作（调试页用） =======================

    public void step1Service(final Cb cb) {
        if (api == null) {
            cb.on(false, "SDK 不可用");
            return;
        }
        try {
            Wearable.getServiceApi(ctx).getServiceApiLevel()
                    .addOnSuccessListener(new OnSuccessListener<Integer>() {
                        @Override public void onSuccess(Integer lv) { cb.on(true, "服务可用 (level " + lv + ")"); }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }

    /** 步骤 2：查已连接设备。⚠️ 必须带超时——小米运动健康偶尔不回调（蓝牙断/手环 USB 调试模式），
     *  不加超时会永远「卡在第二步」。 */
    public void step2Nodes(final Cb cb) {
        final boolean[] done = {false};
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!done[0]) {
                    done[0] = true;
                    cb.on(false, "查找设备超时：小米运动健康无响应。检查手环蓝牙是否连接（手环插着 USB 调试线时也可能这样），稍后重试");
                }
            }
        };
        main.postDelayed(timeout, 10000);
        try {
            Wearable.getNodeApi(ctx).getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override public void onSuccess(List<Node> nodes) {
                            if (done[0]) {
                                return;
                            }
                            done[0] = true;
                            main.removeCallbacks(timeout);
                            if (nodes == null || nodes.isEmpty()) {
                                cb.on(false, "没有已连接设备");
                                return;
                            }
                            Node pick = nodes.get(0);
                            String pref = preferredNodeId();
                            for (int i = 0; i < nodes.size(); i++) {
                                if (nodes.get(i).id.equals(pref)) {
                                    pick = nodes.get(i);
                                    break;
                                }
                            }
                            nodeId = pick.id;
                            deviceName = pick.name;
                            cb.on(true, deviceName + "  nodeId=" + nodeId
                                    + (nodes.size() > 1
                                        ? ("（共 " + nodes.size() + " 台，可在首页切换）") : ""));
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            if (done[0]) {
                                return;
                            }
                            done[0] = true;
                            main.removeCallbacks(timeout);
                            cb.on(false, humanize(e));
                        }
                    });
        } catch (Throwable t) {
            if (!done[0]) {
                done[0] = true;
                main.removeCallbacks(timeout);
            }
            cb.on(false, humanize(t));
        }
    }

    public void step3Perm(final Cb cb) {
        if (nodeId == null) {
            cb.on(false, "先执行步骤 2");
            return;
        }
        try {
            Wearable.getAuthApi(ctx)
                    .requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                    .addOnSuccessListener(new OnSuccessListener<Permission[]>() {
                        @Override public void onSuccess(Permission[] ps) { cb.on(true, "已授权"); }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }

    /** 调试页第 4 步：同四步连接一样带「无回应自动拉起 EV 重试」。 */
    public void step4Ping(final Cb cb) { step4PingN(cb, 3); }

    private void step4PingN(final Cb cb, final int left) {
        if (nodeId == null) {
            cb.on(false, "先执行步骤 2、3");
            return;
        }
        ping(new Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    versionName = o.optString("versionName");
                    versionCode = o.optInt("versionCode");
                    cb.on(o.optBoolean("pong", false), json);
                } catch (Throwable t) {
                    cb.on(false, json);
                }
            }
            @Override public void onTimeout(String hint) { retry4(cb, left, hint); }
            @Override public void onError(String msg) { retry4(cb, left, msg); }
        });
    }

    private void retry4(final Cb cb, final int left, final String why) {
        if (left <= 1) {
            cb.on(false, why + "（已自动拉起 EV 重试过；到手环上手动打开一次 EV 课程表再试）");
            return;
        }
        try {
            Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx));
        } catch (Throwable ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override public void run() { step4PingN(cb, left - 1); }
        }, 3500);
    }

    /**
     * 通用「发消息 + 无回应自动唤醒」：呼叫手环 / 同步等动作都走这里。
     * EV 快应用冷启动经常超过 6s 回包窗口，超时后自动 launchWearApp 再试（最多 3 次）。
     */
    public void sendWake(final String json, final Reply cb) { sendWakeN(json, cb, 3); }

    private void sendWakeN(final String json, final Reply cb, final int left) {
        send(json, new Reply() {
            @Override public void onReply(String r) { cb.onReply(r); }
            @Override public void onTimeout(String hint) {
                if (left <= 1) {
                    cb.onTimeout(hint + "（已自动拉起 EV 重试过）");
                    return;
                }
                try {
                    Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx));
                } catch (Throwable ignored) {
                }
                main.postDelayed(new Runnable() {
                    @Override public void run() { sendWakeN(json, cb, left - 1); }
                }, 3500);
            }
            @Override public void onError(String msg) { cb.onError(msg); }
        });
    }

    public void launchEv(final Cb cb) {
        if (nodeId == null) {
            cb.on(false, "先执行步骤 2");
            return;
        }
        try {
            Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx))
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) { cb.on(true, "已请求拉起，请看手环"); }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }

    public void notifyTest(final Cb cb) {
        if (nodeId == null) {
            cb.on(false, "先执行步骤 2");
            return;
        }
        try {
            Wearable.getNotifyApi(ctx).sendNotify(nodeId, "EV 同步器", "下行测试")
                    .addOnSuccessListener(new OnSuccessListener<Status>() {
                        @Override public void onSuccess(Status st) {
                            cb.on(st != null && st.isSuccess(),
                                    "status=" + (st == null ? "null" : st.getCode()));
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }

    /**
     * 把任意文本推成「手表通知」—— 走 {@link NotifyApi}（小米运动健康通知转发），
     * 完全不经过 EV 的 interconnect 点对点通道，因此**不依赖手环上是否装了 EV 课程表**。
     *
     * 与 {@link #notifyTest} 是同一通道，区别仅在于内容由调用方指定。
     * 典型用途：发留言时把正文同时推到手环，让没装 EV 的用户也能在手环看到。
     */
    public void notifyWatch(String title, String msg, final Cb cb) {
        if (nodeId == null) {
            cb.on(false, "先连接手环");
            return;
        }
        try {
            Wearable.getNotifyApi(ctx).sendNotify(nodeId, title, msg)
                    .addOnSuccessListener(new OnSuccessListener<Status>() {
                        @Override public void onSuccess(Status st) {
                            cb.on(st != null && st.isSuccess(),
                                    "status=" + (st == null ? "null" : st.getCode()));
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }

    public void evInstalled(final Cb cb) {
        if (nodeId == null) {
            cb.on(false, "先执行步骤 2");
            return;
        }
        try {
            Wearable.getNodeApi(ctx).isWearAppInstalled(nodeId)
                    .addOnSuccessListener(new OnSuccessListener<Boolean>() {
                        @Override public void onSuccess(Boolean b) { cb.on(Boolean.TRUE.equals(b), String.valueOf(b)); }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, humanize(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, humanize(t));
        }
    }
}