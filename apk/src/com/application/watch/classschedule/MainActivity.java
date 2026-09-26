package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xiaomi.xms.wearable.Status;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.notify.NotifyApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import com.xiaomi.xms.wearable.service.ServiceApi;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * EV 课程表 · interconnect 通道探针
 *
 * 硬前提（官方文档要求）：
 *   1) 包名 == 快应用 package == com.application.watch.classschedule
 *   2) 签名证书 == 快应用 rpk 的签名证书（同一把密钥）
 *   3) 手机已装「小米运动健康 / 小米穿戴」且已连上手环
 *   4) 手环上 EV 课程表运行过（接收器注册在 App.onCreate）
 *
 * 关于"回包不要被丢掉"：
 *   - onMessageReceived 回调来自 Binder 线程，**不能直接碰 UI**，全部走 runOnUiThread
 *   - 回包先原样打出（长度 + UTF-8 文本 + HEX），不做任何剥壳/裁剪再解析
 */
public class MainActivity extends Activity {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final String CRASH_FILE = "last_crash.txt";
    private static final String DEFAULT_URI = "com.application.watch.classschedule";

    private TextView logView;
    private EditText inputView;
    private EditText uriView;
    private MessageApi messageApi;
    private String nodeId;
    private boolean listenerBound = false;
    private boolean permissionOk = false;
    private int txCount = 0;
    private int rxCount = 0;

    /**
     * 手环 → 手机：收消息
     * ⚠️ 这个回调在 Binder 线程执行；日志必须切回 UI 线程，否则 TextView 抛
     *    CalledFromWrongThreadException —— 那正是"回包看起来被丢掉"的经典死法。
     */
    private final OnMessageReceivedListener rxListener = new OnMessageReceivedListener() {
        @Override
        public void onMessageReceived(String from, byte[] message) {
            final int len = (message == null) ? 0 : message.length;
            final byte[] copy = (message == null) ? new byte[0] : message.clone();
            rxCount++;
            final int n = rxCount;
            // 先把"收到了"这件事发出去，任何后续解析失败都不会让它消失
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    log("<< RX #" + n + "  from=" + from + "  len=" + len);
                    log("   UTF8: " + new String(copy, UTF8));
                    log("   HEX : " + toHex(copy, 512));
                }
            });
        }
    };

    private final OnFailureListener onFail = new OnFailureListener() {
        @Override
        public void onFailure(Exception e) {
            log("!! FAIL: " + e);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildUi();
        installCrashHandler();

        log("EV Sync Probe v" + appVersionName());
        log("本 APK 包名: " + getPackageName());
        showLastCrashIfAny();

        try {
            messageApi = Wearable.getMessageApi(getApplicationContext());
            log("小米穿戴 SDK 已加载（MessageApi 可用）");
        } catch (Throwable t) {
            messageApi = null;
            log("!! 加载小米穿戴 SDK 失败: " + t);
        }
        log("顺序：2 查询设备+授权 → 4 注册监听 → 5 发送 PING");
        log("若一直无回包：先在手表上打开 EV 课程表，或用「手表通知」验证下行");
        log("");
    }

    private String appVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String toHex(byte[] b, int max) {
        if (b == null || b.length == 0) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02X", b[i]));
            if (i != n - 1) {
                sb.append(' ');
            }
        }
        if (b.length > max) {
            sb.append(" …(+").append(b.length - max).append("B)");
        }
        return sb.toString();
    }

    // ======================= 崩溃兜底 =======================

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    String body = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + "\n" + Log.getStackTraceString(ex);
                    FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), CRASH_FILE));
                    fos.write(body.getBytes(UTF8));
                    fos.close();
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(thread, ex);
                }
            }
        });
    }

    private void showLastCrashIfAny() {
        try {
            File f = new File(getFilesDir(), CRASH_FILE);
            if (!f.exists()) {
                return;
            }
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            in.read(buf);
            in.close();
            log("========== 上次崩溃记录（已清除）==========");
            log(new String(buf, UTF8));
            log("==========================================");
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ======================= UI =======================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(8);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("EV 课程表 · interconnect 探针");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(title);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        inputView = new EditText(this);
        inputView.setText("{\"action\":\"ping\"}");
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        root.addView(inputView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        uriView = new EditText(this);
        uriView.setText(DEFAULT_URI);
        uriView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        root.addView(uriView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(row(
                btn("2 查询设备+授权", new View.OnClickListener() {
                    @Override public void onClick(View v) { listNodes(); }
                }),
                btn("4 注册监听", new View.OnClickListener() {
                    @Override public void onClick(View v) { addListener(); }
                })
        ));
        root.addView(row(
                btn("5 发送 PING", new View.OnClickListener() {
                    @Override public void onClick(View v) { send("{\"action\":\"ping\"}"); }
                }),
                btn("PING 重试×3", new View.OnClickListener() {
                    @Override public void onClick(View v) { sendPingRetry(); }
                })
        ));
        root.addView(row(
                btn("6 读取昵称", new View.OnClickListener() {
                    @Override public void onClick(View v) { send("{\"action\":\"export\"}"); }
                }),
                btn("手表通知(验下行)", new View.OnClickListener() {
                    @Override public void onClick(View v) { sendNotify(); }
                })
        ));
        root.addView(row(
                btn("拉起手表 EV", new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                }),
                btn("移除监听", new View.OnClickListener() {
                    @Override public void onClick(View v) { removeListener(); }
                })
        ));
        root.addView(row(
                btn("1 检测服务", new View.OnClickListener() {
                    @Override public void onClick(View v) { checkService(); }
                }),
                btn("3 申请权限", new View.OnClickListener() {
                    @Override public void onClick(View v) { requestPermission(); }
                })
        ));
        root.addView(row(
                btn("发送自定义 JSON", new View.OnClickListener() {
                    @Override public void onClick(View v) { send(inputView.getText().toString()); }
                }),
                btn("清空日志", new View.OnClickListener() {
                    @Override public void onClick(View v) { logView.setText(""); }
                })
        ));

        setContentView(root);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View left, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** 所有日志统一走 UI 线程，避免 Binder 线程碰 UI 抛异常把回包"吃掉" */
    private void log(final String s) {
        Log.i("EVProbe", s);
        if (logView == null) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logView.append(TS.format(new Date()) + "  " + s + "\n");
            }
        });
    }

    private String permNames(Permission[] ps) {
        if (ps == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i] == null ? "null" : ps[i].getName());
        }
        return sb.append("]").toString();
    }

    // ======================= 业务动作 =======================

    private void checkService() {
        if (!ensureSdk()) return;
        log("[1] 检测穿戴服务 ...");
        try {
            final Context ctx = getApplicationContext();
            ServiceApi serviceApi = Wearable.getServiceApi(ctx);
            serviceApi.registerServiceConnectionListener(new OnServiceConnectionListener() {
                @Override public void onServiceConnected() { log("    ServiceApi 已连接"); }
                @Override public void onServiceDisconnected() { log("    ServiceApi 已断开"); }
            });
            serviceApi.getServiceApiLevel()
                    .addOnSuccessListener(new OnSuccessListener<Integer>() {
                        @Override public void onSuccess(Integer level) { log("    ServiceApiLevel = " + level); }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private void listNodes() {
        if (!ensureSdk()) return;
        log("[2] 查询已连接设备 ...");
        try {
            Wearable.getNodeApi(getApplicationContext()).getConnectedNodes()
                    .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                        @Override public void onSuccess(List<Node> nodes) {
                            if (nodes == null || nodes.isEmpty()) {
                                log("    没有已连接设备！→ 先在「小米运动健康」里连上手环");
                                return;
                            }
                            nodeId = nodes.get(0).id;
                            for (Node n : nodes) {
                                log("    设备: id=" + n.id + "  name=" + n.name);
                            }
                            log("    已选定 nodeId = " + nodeId);
                            requestPermission();
                            checkWearAppInstalled(nodeId);
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private void checkWearAppInstalled(String id) {
        try {
            Wearable.getNodeApi(getApplicationContext()).isWearAppInstalled(id)
                    .addOnSuccessListener(new OnSuccessListener<Boolean>() {
                        @Override public void onSuccess(Boolean installed) {
                            log("    手环上 EV 课程表已安装? " + installed);
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    /** 一次申请两个权限：DEVICE_MANAGER（收发消息）+ NOTIFY（手表通知） */
    private void requestPermission() {
        if (!ensureSdk() || !hasNode()) return;
        log("[3] 申请权限（DEVICE_MANAGER + NOTIFY）...");
        try {
            Wearable.getAuthApi(getApplicationContext())
                    .requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
                    .addOnSuccessListener(new OnSuccessListener<Permission[]>() {
                        @Override public void onSuccess(Permission[] granted) {
                            permissionOk = true;
                            log("    已授权: " + permNames(granted));
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private void addListener() {
        if (!ensureSdk() || !hasNode()) return;
        if (listenerBound) {
            log("[4] 已经注册过了（同一 nodeId 只能注册一次），直接发消息即可。");
            return;
        }
        log("[4] 注册消息监听 ...");
        try {
            messageApi.addListener(nodeId, rxListener)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            listenerBound = true;
                            log("    监听已注册，可以发消息了");
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private void removeListener() {
        if (!ensureSdk() || !hasNode()) return;
        log("[*] 移除消息监听 ...");
        try {
            messageApi.removeListener(nodeId)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            listenerBound = false;
                            log("    已移除监听");
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private void launchEv() {
        if (!ensureSdk() || !hasNode()) return;
        final String uri = uriView.getText().toString().trim();
        log("[*] launchWearApp(nodeId, \"" + uri + "\") ...");
        try {
            Wearable.getNodeApi(getApplicationContext()).launchWearApp(nodeId, uri)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            log("    已请求拉起（看手环是否真的打开 EV）");
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    /**
     * 零风险下行验证：让【手表】弹一条通知。
     * 表端应用无感知，不经过 EV 的 interconnect，因此可以单独证明「手机 → 手环」通不通。
     */
    private void sendNotify() {
        if (!ensureSdk() || !hasNode()) return;
        log("[*] 发送手表通知（验证手机→手环）...");
        try {
            Wearable.getNotifyApi(getApplicationContext())
                    .sendNotify(nodeId, "EV Probe", "下行测试 " + TS.format(new Date()))
                    .addOnSuccessListener(new OnSuccessListener<Status>() {
                        @Override public void onSuccess(Status st) {
                            boolean ok = (st != null) && st.isSuccess();
                            log("    sendNotify status=" + (st == null ? "null" : st.getCode()) + " success=" + ok);
                            log(ok ? "    → 请查看手环是否弹出通知；弹了说明「手机→手环」通"
                                   : "    → 未成功，看 code 判断原因");
                        }
                    })
                    .addOnFailureListener(onFail);
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    /** ping 连发 3 次（间隔 1.2s）—— 手环侧可能处于冷启动/唤醒窗口，单次容易丢 */
    private void sendPingRetry() {
        log("[*] 连续发送 ping ×3 ...");
        for (int i = 0; i < 3; i++) {
            final int idx = i + 1;
            logView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    log("    ping 第 " + idx + "/3 次");
                    send("{\"action\":\"ping\"}");
                }
            }, 1200L * i);
        }
    }

    private void send(String json) {
        if (!ensureSdk() || !hasNode()) return;
        if (TextUtils.isEmpty(json)) { log("报文为空"); return; }
        if (!permissionOk) {
            log("提示：尚未授权。仍继续发送 ...");
        }
        if (!listenerBound) {
            log("提示：尚未注册监听（点「4」），回包可能收不到。仍继续发送 ...");
        }
        try {
            final byte[] bytes = json.getBytes(UTF8);
            txCount++;
            log(">> TX #" + txCount + "  len=" + bytes.length + "  " + json);
            messageApi.sendMessage(nodeId, bytes)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            log("   sendMessage 已受理（受理 != 送达；已发 " + txCount + " 次 / 已收 " + rxCount + " 次）");
                            if (rxCount == 0 && txCount >= 3) {
                                log("   提示：已发 " + txCount + " 次仍 0 回包 → 试「手表通知」验证下行，"
                                        + "或先在手环打开 EV 课程表");
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            log("   sendMessage 失败: " + e);
                        }
                    });
        } catch (Throwable t) {
            log("!! 调用异常: " + t);
        }
    }

    private boolean ensureSdk() {
        if (messageApi == null) {
            log("SDK 未就绪，本操作不可用");
            return false;
        }
        return true;
    }

    private boolean hasNode() {
        if (TextUtils.isEmpty(nodeId)) {
            log("请先点「2 查询设备+授权」拿到 nodeId");
            return false;
        }
        return true;
    }
}
