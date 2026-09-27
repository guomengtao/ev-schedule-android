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
                }
            });
        }
    };

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
                        @Override public void onFailure(Exception e) {
                            if (timeoutTask != null) {
                                main.removeCallbacks(timeoutTask);
                                timeoutTask = null;
                            }
                            pending = null;
                            cb.onError(String.valueOf(e));
                        }
                    });
        } catch (Throwable t) {
            cb.onError(String.valueOf(t));
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

    // ======================= 四步连接 =======================

    public void connect(final Steps s) {
        final String[] labels = {"初始化穿戴服务", "查找已连接设备", "申请设备权限", "连接 EV 课程表"};
        final int[] states = {RUNNING, PENDING, PENDING, PENDING};
        final String[] details = {"", "", "", ""};
        emit(s, labels, states, details);
        stepService(s, labels, states, details);
    }

    private void emit(final Steps s, final String[] labels, final int[] states, final String[] details) {
        main.post(new Runnable() {
            @Override public void run() {
                if (s != null) {
                    s.onUpdate(labels, states, details);
                }
            }
        });
    }

    private void finish(final Steps s, final boolean ok, final String hint) {
        main.post(new Runnable() {
            @Override public void run() {
                if (s != null) {
                    s.onFinish(ok, hint);
                }
            }
        });
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
                            details[0] = String.valueOf(e.getMessage());
                            emit(s, labels, states, details);
                            finish(s, false, "请安装并打开「小米运动健康」App，并让它在后台运行");
                        }
                    });
        } catch (Throwable t) {
            states[0] = FAIL;
            details[0] = String.valueOf(t);
            emit(s, labels, states, details);
            finish(s, false, "穿戴服务不可用，请打开「小米运动健康」");
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
                            Node n = nodes.get(0);
                            nodeId = n.id;
                            deviceName = n.name;
                            states[1] = OK;
                            details[1] = n.name;
                            emit(s, labels, states, details);
                            stepPerm(s, labels, states, details);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            states[1] = FAIL;
                            details[1] = String.valueOf(e.getMessage());
                            emit(s, labels, states, details);
                            finish(s, false, "读取设备列表失败，请检查小米运动健康的手环连接");
                        }
                    });
        } catch (Throwable t) {
            states[1] = FAIL;
            details[1] = String.valueOf(t);
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
                            details[2] = String.valueOf(e.getMessage());
                            emit(s, labels, states, details);
                            finish(s, false, sig
                                    ? "签名校验未通过：本 APK 与手环 EV 课程表不是同一把签名"
                                    : "权限申请失败，请在小米运动健康中允许本应用访问");
                        }
                    });
        } catch (Throwable t) {
            states[2] = FAIL;
            details[2] = String.valueOf(t);
            emit(s, labels, states, details);
            finish(s, false, "权限申请失败");
        }
    }

    // 步骤 4：ping 通 EV
    private void stepPing(final Steps s, final String[] labels, final int[] states, final String[] details) {
        states[3] = RUNNING;
        details[3] = "正在唤醒手环上的 EV 课程表…";
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
                states[3] = FAIL;
                details[3] = "无回应";
                emit(s, labels, states, details);
                finish(s, false, hint);
            }

            @Override public void onError(String msg) {
                states[3] = FAIL;
                details[3] = msg;
                emit(s, labels, states, details);
                finish(s, false, msg);
            }
        });
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
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
        }
    }

    public void step2Nodes(final Cb cb) {
        try {
            Wearable.getNodeApi(ctx).getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override public void onSuccess(List<Node> nodes) {
                            if (nodes == null || nodes.isEmpty()) {
                                cb.on(false, "没有已连接设备");
                                return;
                            }
                            nodeId = nodes.get(0).id;
                            deviceName = nodes.get(0).name;
                            cb.on(true, deviceName + "  nodeId=" + nodeId);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
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
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
        }
    }

    public void step4Ping(final Cb cb) {
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
            @Override public void onTimeout(String hint) { cb.on(false, hint); }
            @Override public void onError(String msg) { cb.on(false, msg); }
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
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
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
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
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
                        @Override public void onFailure(Exception e) { cb.on(false, String.valueOf(e)); }
                    });
        } catch (Throwable t) {
            cb.on(false, String.valueOf(t));
        }
    }
}
