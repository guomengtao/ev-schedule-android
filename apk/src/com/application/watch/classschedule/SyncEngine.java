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
    // 串行化发送队列：同一时刻只允许一个请求在途（pending 非空），后续请求排队而非覆盖 pending。
    // 真机踩坑：连接流程的 ping 在途时 cacheDeviceId() 发 get_device_id，后者覆盖了 pending，
    // 手环先回的 ping 回包被错发给 get_device_id 的 callback（action 不匹配）→ deviceId 恒空。
    private final java.util.ArrayDeque<Runnable> sendQueue = new java.util.ArrayDeque<Runnable>();

    /** 最近一次成功同步到的手环信息 */
    public String deviceName = "";
    public String nickname = "";
    public String versionName = "";
    public int versionCode = 0;
    public int courseCount = 0;
    public String lastExportJson;

    /** 手环真实设备 ID（多设备隔离路由键）。连接成功后由 cacheDeviceId() 异步填充，
     *  来源 get_device_id 回包；拿不到时留空，ScheduleStore 侧退化为 "legacy-unknown"。 */
    public String watchDeviceId = "";
    public String watchDeviceId4 = "";
    public boolean watchDeviceFallback = false;

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

    /** 当前连接手环的真实设备 ID（多设备强隔离路由键）；空 = 还没取到 / 手环版本过低 */
    public String currentDeviceId() { return watchDeviceId == null ? "" : watchDeviceId; }

    /** 当前连接手环的展示名（如「小米手环 10 Pro」） */
    public String currentDeviceName() { return deviceName == null ? "" : deviceName; }
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
                        pumpNext(); // 回包已消费，放行队列里的下一个请求
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
                    pumpNext();
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
                @Override public void onSuccess(Void v) {
                    listening = true;
                    cacheDeviceId(); // 监听就绪后再取真实设备 ID，保证回包能收到
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 发一条报文，等待回包（6 秒无回应判超时）。
     *  串行化：若已有请求在途（pending 非空），新请求排队等待而非覆盖，杜绝回包错配。 */
    public void send(final String json, final Reply cb) {
        if (api == null) {
            cb.onError("穿戴 SDK 不可用，请确认已安装「小米运动健康」");
            return;
        }
        if (nodeId == null) {
            cb.onError("还没有选中设备，请先完成连接");
            return;
        }
        Runnable task = new Runnable() {
            @Override public void run() { doSend(json, cb); }
        };
        if (pending != null) {
            sendQueue.addLast(task);
        } else {
            task.run();
        }
    }

    /** 真正的发送（仅在 pending 为空时调用） */
    private void doSend(String json, final Reply cb) {
        lastSendAt = System.currentTimeMillis();
        ensureListener();
        pending = cb;
        timeoutTask = new Runnable() {
            @Override public void run() {
                timeoutTask = null;
                pending = null;
                cb.onTimeout("手环没有回应。请先在手表上打开一次「EV 课程表」，然后重试。");
                pumpNext();
            }
        };
        main.postDelayed(timeoutTask, 6000);
        try {
            api.sendMessage(nodeId, json.getBytes(Charset.forName("UTF-8")))
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) { /* 受理 != 送达 */ }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            if (timeoutTask != null) {
                                main.removeCallbacks(timeoutTask);
                                timeoutTask = null;
                            }
                            pending = null;
                            cb.onError(humanize(e));
                            pumpNext();
                        }
                    });
        } catch (Throwable t) {
            if (timeoutTask != null) {
                main.removeCallbacks(timeoutTask);
                timeoutTask = null;
            }
            pending = null;
            cb.onError(humanize(t));
            pumpNext();
        }
    }

    /** 放行发送队列里的下一个请求（仅在无在途请求时） */
    private void pumpNext() {
        if (pending != null || sendQueue.isEmpty()) {
            return;
        }
        Runnable next = sendQueue.pollFirst();
        if (next != null) {
            next.run();
        }
    }

    public void ping(Reply cb) {
        send("{\"action\":\"ping\"}", cb);
    }

    public void export(Reply cb) {
        send("{\"action\":\"export\"}", cb);
    }

    /** 请求课程表清单（多课程表导出前置）：回包 {ok,action:"list_schedules",names:[...],current:N}
     *  ⭐ 任何调用方拿到结果都会顺手缓存「手环真实清单」，供课程表管理页按真实套数渲染。 */
    public void listSchedules(final Reply cb) {
        send("{\"action\":\"list_schedules\"}", new Reply() {
            @Override public void onReply(String json) {
                cacheBandList(json);
                cb.onReply(json);
            }
            @Override public void onTimeout(String hint) { cb.onTimeout(hint); }
            @Override public void onError(String msg) { cb.onError(msg); }
        });
    }

    private void cacheBandList(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray names = o.optJSONArray("names");
            if (names == null) {
                return;
            }
            bandScheduleCount = names.length();
            bandScheduleNames = new String[names.length()];
            for (int i = 0; i < names.length(); i++) {
                bandScheduleNames[i] = names.optString(i);
            }
            bandCurrent = o.optInt("current", -1);
        } catch (Throwable ignored) {
        }
        notifyStatus();
    }

    /** 导出指定第 index 套课程表（index 对应 allCourses_<index>） */
    public void exportSchedule(int index, Reply cb) {
        send("{\"action\":\"export\",\"scheduleIndex\":" + index + "}", cb);
    }

    /**
     * 索取手环设备ID（APK 侧拿不到）：回包 {ok,action:"get_device_id",deviceId,deviceId4,fallback}
     *
     * 📱 A4：取到后立刻缓存到 {@link Stats}（幂等，不改动调用方语义）。
     * 为什么在这里缓存而不是让调用方自己存：这是**唯一**能拿到手环真实 deviceId 的入口，
     * 而它是「这台手机 ↔ 这只手环」的合并钥匙（与激活用的同一个 deviceId），
     * 后续每次埋点上报都要用，不能只在激活页临时用一次就丢。
     */
    public void getDeviceId(Reply cb) {
        final Reply outer = (cb != null) ? cb : new Reply() {
            @Override public void onReply(String json) { }
            @Override public void onTimeout(String hint) { }
            @Override public void onError(String msg) { }
        };
        send("{\"action\":\"get_device_id\"}", new Reply() {
            @Override public void onReply(String json) {
                try {
                    Stats.cacheWatchDeviceId(ctx, json, nodeId);
                } catch (Throwable ignored) {
                }
                outer.onReply(json);
            }
            @Override public void onTimeout(String hint) {
                outer.onTimeout(hint);
            }
            @Override public void onError(String msg) {
                outer.onError(msg);
            }
        });
    }

    /** 连接落定后取手环真实设备 ID 并缓存；拿到后把升级前遗留的 legacy-unknown 课表
     *  升级归位到本设备（pullMissingFromWatch 以真实 deviceId 触发一次兼容迁移）。 */
    private void cacheDeviceId() {
        if (nodeId == null) {
            return;
        }
        android.util.Log.d("EVProbe", "cacheDeviceId: send get_device_id (nodeId=" + nodeId + ")");
        getDeviceId(new Reply() {
            @Override public void onReply(String json) {
                android.util.Log.d("EVProbe", "cacheDeviceId reply: " + json);
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    if (o.optBoolean("ok", false) && "get_device_id".equals(o.optString("action"))) {
                        watchDeviceId = o.optString("deviceId");
                        watchDeviceId4 = o.optString("deviceId4");
                        watchDeviceFallback = o.optBoolean("fallback", false);
                        ScheduleStore.migrateLegacyToDevice(ctx, watchDeviceId, deviceName); // 老数据归位
                        pullMissingFromWatch(ctx); // deviceId 已就位：把 legacy-unknown 记录升级
                    }
                } catch (Throwable ignored) {
                }
            }
            @Override public void onTimeout(String h) { android.util.Log.d("EVProbe", "cacheDeviceId timeout: " + h); }
            @Override public void onError(String m) { android.util.Log.d("EVProbe", "cacheDeviceId error: " + m); }
        });
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
                    // P4/A5（§7.1 遗留 #2）：空闲时补拿手环真实 deviceId —— 从未拿到过、
                    // 或换了手环（nodeId 与缓存不同）时借这次心跳的空闲窗口发一次
                    // get_device_id（不占用户操作：pending==null 才会走到这）。
                    if (Stats.needsDeviceIdRefresh(ctx, nodeId)) {
                        android.util.Log.d("EVProbe", "keepalive: refresh deviceId");
                        getDeviceId(new Reply() {
                            @Override public void onReply(String json) { }
                            @Override public void onTimeout(String hint) {
                                // 与 ping 分支同款自愈：EV 没回应多半是退了，拉起来，
                                // 下一轮心跳自然能刷新成功
                                android.util.Log.d("EVProbe", "keepalive: deviceId 无回应，拉起");
                                try {
                                    Wearable.getNodeApi(ctx).launchWearApp(nodeId, Variant.peerPkg(ctx));
                                } catch (Throwable ignored) {
                                }
                            }
                            @Override public void onError(String msg) { }
                        });
                    } else {
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
                    }
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
    // 多监听：ConnectionBar 与各页面的回调共存（弱引用持有，页面销毁自动失效不泄漏）。
    private final java.util.List<java.lang.ref.WeakReference<Runnable>> statusCbs =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean autoRetryRunning;
    private volatile String connectProgress = "";
    /** 手环上真实的课程表套数（list_schedules 的 names.length；-1=未知）。 */
    public int bandScheduleCount = -1;
    /** 手环上真实的课程表名字清单（null=未知）；bandCurrent = 手环当前激活的下标 */
    public String[] bandScheduleNames = null;
    public int bandCurrent = -1;
    private boolean pullingMissing = false;
    private int pulledCount = 0;
    private int pullFailCount = 0;

    /** 自动补齐的进度回调（仅注册方收到，主线程回调）；给「课程表」Tab 做加载/成功/失败提示用 */
    public interface PullCallback {
        /** 开始拉取 */
        void onStart();
        /** 结束：pulled=本次成功入库套数，failed=超时/失败项数（0 = 全部顺利或本机已齐） */
        void onDone(int pulled, int failed);
    }

    private volatile PullCallback pullCb = null;

    /** 是否正在自动补齐（UI 显示加载态用） */
    public boolean isPullingMissing() { return pullingMissing; }

    /** 注册状态刷新回调；连接进度 / 心跳 / 套数刷新都会触发（主线程）。重复注册会重复回调。 */
    public void addStatusCallback(Runnable r) {
        if (r != null) {
            statusCbs.add(new java.lang.ref.WeakReference<>(r));
        }
        notifyStatus();
    }

    public void removeStatusCallback(Runnable r) {
        for (java.lang.ref.WeakReference<Runnable> wr : statusCbs) {
            if (r.equals(wr.get())) {
                statusCbs.remove(wr);
                break;
            }
        }
    }

    private void notifyStatus() {
        main.post(new Runnable() {
            @Override public void run() {
                for (java.lang.ref.WeakReference<Runnable> wr : statusCbs) {
                    Runnable r = wr.get();
                    if (r == null) {
                        statusCbs.remove(wr); // 页面已销毁，顺手清掉
                        continue;
                    }
                    try {
                        r.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 离线时自动重连一轮。四步连接内层已带「自动拉起 EV ×3」，外层不再叠次数——
     *  一轮失败后状态条转「去连接调试」，心跳每 60s 会自动再触发新一轮。单例锁防多页面打架。
     *  重连成功后与首页一样：顺手把手环课表拉回本地保存。 */
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
                if (ok) {
                    pullAndStore(ctx);
                    requestBattery(); // EV 支持就显示电量，不支持静默
                }
                notifyStatus();
            }
        });
    }

    /** 状态条用：是否正处于自动重连中。 */
    public boolean autoRetryRunning() {
        return autoRetryRunning;
    }

    /** 状态条用：最近一次连接进度文案（如「连接中（2/4）：查找已连接设备」）。 */
    public String connectProgress() {
        return connectProgress;
    }

    /** 连接成功后把手环当前课表拉回本地保存（与首页连接成功后的动作一致）。
     *  静默版：结果只刷新状态回调，不弹 UI。 */
    public void pullAndStore(final Context c) {
        export(new Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    org.json.JSONObject d = o.optJSONObject("data");
                    final org.json.JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        notifyStatus();
                        return;
                    }
                    lastExportJson = json;
                    listSchedules(new Reply() {
                        @Override public void onReply(String j2) {
                            String name = "";
                            try {
                                org.json.JSONObject o2 = new org.json.JSONObject(j2);
                                org.json.JSONArray names = o2.optJSONArray("names");
                                int cur = o2.optInt("current", 0);
                                if (names != null && cur >= 0 && cur < names.length()) {
                                    name = names.optString(cur);
                                }
                                if (names != null) {
                                    bandScheduleCount = names.length(); // 手环真实套数
                                }
                            } catch (Throwable ignored) {
                            }
                            ScheduleStore.upsertFromWatch(c, currentDeviceId(), currentDeviceName(), name, sch);
                            notifyStatus();
                        }
                        @Override public void onTimeout(String h) { storeQuietly(c, sch); }
                        @Override public void onError(String m) { storeQuietly(c, sch); }
                    });
                } catch (Throwable t) {
                    notifyStatus();
                }
            }
            @Override public void onTimeout(String hint) { notifyStatus(); }
            @Override public void onError(String msg) { notifyStatus(); }
        });
    }

    /** 自动把「手环上有、本机还没有」的课表读到本机（按清单下标逐个 export → 入库）。
     *  全部标记为 source=sync，归属「手环课表」组；静默执行，单例锁防重复跑。 */
    public void pullMissingFromWatch(final Context c) {
        pullMissingFromWatch(c, null);
    }

    /** 同上，带进度回调（onStart/onDone 均在主线程；cb 只保留最近一次注册的）。 */
    public void pullMissingFromWatch(final Context c, final PullCallback cb) {
        if (nodeId == null || bandScheduleNames == null || pullingMissing) {
            return;
        }
        pullingMissing = true;
        pulledCount = 0;
        pullFailCount = 0;
        pullCb = cb;
        if (cb != null) {
            main.post(new Runnable() {
                @Override public void run() {
                    try { cb.onStart(); } catch (Throwable ignored) { }
                }
            });
        }
        pullNextMissing(c, 0);
    }

    private void pullNextMissing(final Context c, final int i) {
        if (i >= bandScheduleNames.length) {
            pullingMissing = false;
            final PullCallback cb = pullCb;
            pullCb = null;
            final int pulled = pulledCount;
            final int failed = pullFailCount;
            // ⚠️ 只在真的拉到课表时才 notify：否则「通知 → 回调 → 再补齐（瞬间完成）」会
            // 形成主线程死循环，界面直接卡死、所有按钮失灵。
            if (pulledCount > 0) {
                notifyStatus();
            }
            if (cb != null) {
                main.post(new Runnable() {
                    @Override public void run() {
                        try { cb.onDone(pulled, failed); } catch (Throwable ignored) { }
                    }
                });
            }
            return;
        }
        final String name = bandScheduleNames[i];
        if (ScheduleStore.findByDeviceName(c, currentDeviceId(), name) != null) { // 本机已有（按设备），跳过
            pullNextMissing(c, i + 1);
            return;
        }
        exportSchedule(i, new Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    org.json.JSONObject d = o.optJSONObject("data");
                    org.json.JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch != null) {
                        ScheduleStore.upsertFromWatch(c, currentDeviceId(), currentDeviceName(), name, sch);
                        pulledCount++;
                    }
                } catch (Throwable ignored) {
                }
                pullNextMissing(c, i + 1);
            }
            @Override public void onTimeout(String hint) { pullFailCount++; pullNextMissing(c, i + 1); }
            @Override public void onError(String msg) { pullFailCount++; pullNextMissing(c, i + 1); }
        });
    }

    /** 手环电量（0=未知；小米穿戴 SDK 无电量接口，只能由手环侧 EV 上报）。 */
    public int batteryPercent = 0;
    public int batteryDays = 0;

    /**
     * 向手环 EV 要电量（前向兼容：EV 侧暂不支持 → 超时静默失败，界面不显示，绝不给假数据）。
     * EV 以后支持 {"action":"get_battery"} 回 {ok,battery,days} 即可自动显示。
     */
    public void requestBattery() {
        if (nodeId == null) {
            return;
        }
        send("{\"action\":\"get_battery\"}", new Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    int p = o.optInt("battery", o.optInt("percent", 0));
                    if (p > 0) {
                        batteryPercent = p;
                        batteryDays = o.optInt("days", o.optInt("lastFullDays", 0));
                        notifyStatus();
                    }
                } catch (Throwable ignored) {
                }
            }
            @Override public void onTimeout(String h) { }
            @Override public void onError(String m) { }
        });
    }

    /** 向手环要一次清单，刷新真实套数（静默，结果经状态回调通知）。 */
    public void refreshBandScheduleCount() {
        if (nodeId == null) {
            return;
        }
        listSchedules(new Reply() {
            @Override public void onReply(String j) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(j);
                    org.json.JSONArray names = o.optJSONArray("names");
                    if (names != null) {
                        bandScheduleCount = names.length();
                    }
                } catch (Throwable ignored) {
                }
                notifyStatus();
            }
            @Override public void onTimeout(String h) { }
            @Override public void onError(String m) { }
        });
    }

    private void storeQuietly(Context c, org.json.JSONArray sch) {
        try {
            ScheduleStore.upsertFromWatch(c, currentDeviceId(), currentDeviceName(), "", sch);
        } catch (Throwable ignored) {
        }
        notifyStatus();
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
        // 记录最近连接进度（状态条与首页迷你条共用同一份文案）
        String running = null;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == RUNNING) {
                running = "连接中（" + (i + 1) + "/" + states.length + "）：" + labels[i];
                break;
            }
        }
        if (running != null) {
            connectProgress = running;
        }
        notifyStatus();
    }

    private void finish(final Steps s, final boolean ok, final String hint) {
        if (!ok) {
            // 连接失败：清掉上一轮缓存的连接痕迹。否则版本号还是旧值，
            // connected() 恒为 true，状态条会拿着死链数据谎报「已连接」（用户实测踩过）。
            versionName = "";
            versionCode = 0;
            courseCount = 0;
            connectProgress = "连接失败：" + hint;
        } else {
            connectProgress = "已连接 · " + versionName;
        }
        try {
            Stats.connectEnd(ctx, ok, failStep, failStep > 0 ? failDetail : hint);
        } catch (Throwable ignored) {
        }
        try {
            ConnLog.record(ctx, ok, deviceName, nodeId,
                    ok ? 0 : failStep,
                    ok ? "" : (failStep > 0 ? failDetail : hint),
                    ok ? versionName : "");
        } catch (Throwable ignored) {
        }
        // P4/A5：多手环历史清单——成功失败都算「见过这只手环」。
        //   失败时 versionName 已被上面清空，recordWatchSeen 对空值不覆盖，保留上次记录。
        try {
            Stats.recordWatchSeen(ctx, nodeId, deviceName, ok ? versionName : "", ok);
        } catch (Throwable ignored) {
        }
        // P3（§4.4）：连接结果事件。ok 只落库（connect 推送与 page_visit 重复）；
        //   fail 走服务端「失败合并桶」——每次尝试都落 tracking_events，但通知至多 1 小时合并一条。
        try {
            if (ok) {
                Analytics.event(ctx, "app_connect_ok", null);
            } else {
                Analytics.event(ctx, "app_connect_fail",
                        Analytics.p("stage", failStep, "reason", failStep > 0 ? failDetail : hint));
            }
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
        // 穿戴 SDK 通道没建立时会直接报 "sendMessage failed"：
        // 它短且无冒号，会掉进下面兜底分支把英文糊给用户，必须先拦掉
        if (low.contains("sendmessage failed") || low.contains("send message failed")) {
            return "手环通道未建立";
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
        if (low.contains("sendmessage failed") || low.contains("send message failed")) {
            return "手机到手环的通道还没建立。请在「小米运动健康」确认手环已连接，并在手表上打开一次「EV 课程表」，然后重试。";
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
                    cb.on(false, "小米运动健康与手环的连接已断开（长时间后台常见）。打开「小米运动健康」等它重新连上手环，再回来重试");
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