package com.application.watch.classschedule;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 支付会话：从「点去支付」到「拿到 18 位码」的那一段。
 *
 * 链路（全程 0 轮询 —— 不烧 Redis，也不打 Vercel 函数配额）：
 *
 *   ① POST /api/pay-session {deviceKey,nodeId,deviceId}  →  {sid, payUrl}
 *   ② 打开 payUrl（爱发电商品页，留言框已预填 EV-<sid>）
 *   ③ WSS 直连 Supabase 订阅 activation_events，按 sid 本地过滤
 *   ④ webhook 匹配到订单 → 写 paid 事件 → 这里收到 → GET pickup 取一次码
 *   ⑤ 5 分钟没到就停 WS，改「手动检查」（用户点一次 = 发一次请求）
 *
 * 为什么 sid 要本地过滤：事件表是 anon 可读的，所有客户端的 INSERT 都会广播过来；
 * 表里只有 sid/status/ts，过滤只是为了不做无用功，不是安全边界。
 *
 * 为什么取码要再回一次服务端：18 位码在 activation_codes 表对 anon 全拒（实测 401），
 * 且 pickup 是一次性的（取走即置 delivered），所以即使 WS 事件被别人看到也拿不走码。
 */
final class PaySession {

    private static final String TAG = "EvZeroPay";

    interface Cb {
        /** 会话建好、支付页可以打开了 */
        void onCreated(String sid, String payUrl);

        /** 拿到 18 位码（只回调一次） */
        void onPaid(String code18);

        /** 等待过程中的状态文案（副标题用，可忽略） */
        void onState(String text);

        /** 5 分钟未完成：已停 WS，页面转「手动检查」 */
        void onTimeout();

        /** 明确失败（网络错误、会话过期等），可重试 */
        void onFail(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean picking = new AtomicBoolean(false);
    private final AtomicBoolean done = new AtomicBoolean(false);

    private SupabaseRealtimeLite rt;
    private String sid = "";
    private Cb cb;
    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            if (done.get()) {
                return;
            }
            stopRealtime();
            Cb c = cb;
            if (c != null) {
                c.onTimeout();
            }
        }
    };

    /** 建会话 → 起 WS → 等推送。deviceKey/nodeId 为空的组合会被服务端拒绝 */
    void start(final DeviceProbe.Result probe, Cb callback) {
        this.cb = callback;
        JSONObject body = new JSONObject();
        try {
            body.put("deviceKey", probe.mac);
            body.put("nodeId", probe.nodeId);
            body.put("deviceId", probe.deviceId);
            body.put("source", "apk-zero");
        } catch (Throwable ignored) {
        }
        Net.postJson(ZeroCfg.API_PAY_SESSION, body.toString(), new Net.Cb() {
            @Override
            public void on(final int code, final String resp) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onCreated(code, resp);
                    }
                });
            }
        });
    }

    private void onCreated(int httpCode, String resp) {
        if (httpCode < 0 || TextUtils.isEmpty(resp)) {
            fail("网络请求失败，请检查网络后重试");
            return;
        }
        String newSid = "";
        String payUrl = "";
        try {
            JSONObject o = new JSONObject(resp);
            if (!o.optBoolean("success", false)) {
                fail("无法创建支付会话：" + o.optString("error", o.optString("reason", "未知原因")));
                return;
            }
            newSid = o.optString("sid", "");
            payUrl = o.optString("payUrl", "");
        } catch (Throwable t) {
            fail("服务器回包无法解析");
            return;
        }
        if (newSid.length() == 0 || payUrl.length() == 0) {
            fail("服务器没有返回有效的支付地址");
            return;
        }
        sid = newSid;
        startRealtime();
        main.removeCallbacks(timeoutTask);
        main.postDelayed(timeoutTask, ZeroCfg.PAY_WAIT_MS);
        if (cb != null) {
            cb.onCreated(sid, payUrl);
        }
    }

    /**
     * 仅调试用：给定 sid 直接开 WS 等待，不建会话（服务端未部署时也能验证传输链路）。
     * adb 触发方式见 ZeroActivateActivity.readDebugProbe()。
     */
    void startWithSid(String presetSid, Cb callback) {
        this.cb = callback;
        this.sid = presetSid == null ? "" : presetSid.trim();
        if (sid.length() == 0) {
            fail("调试 sid 为空");
            return;
        }
        startRealtime();
        main.removeCallbacks(timeoutTask);
        main.postDelayed(timeoutTask, ZeroCfg.PAY_WAIT_MS);
        if (cb != null) {
            cb.onCreated(sid, "");
        }
    }

    private void startRealtime() {        try {
            rt = new SupabaseRealtimeLite(ZeroCfg.SUPA_URL, ZeroCfg.SUPA_ANON_KEY,
                    ZeroCfg.SUPA_TABLE, new SupabaseRealtimeLite.Listener() {
                @Override
                public void onRow(String type, JSONObject record) {
                    if (record == null || done.get()) {
                        return;
                    }
                    if (!sid.equals(record.optString("sid", ""))) {
                        return;                      // 别人的会话，忽略
                    }
                    String status = record.optString("status", "");
                    Log.i(TAG, "收到匹配事件 sid=" + sid + " status=" + status);
                    if ("paid".equals(status) || "delivered".equals(status)) {
                        pickup("realtime");
                    }
                }

                @Override
                public void onConnectionChange(boolean connected) {
                    Log.d(TAG, "WS " + (connected ? "已连接" : "已断开"));
                }

                @Override
                public void onSubscribed() {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (cb != null && !done.get()) {
                                cb.onState("实时通道已就绪 · 付完款会自动激活");
                            }
                        }
                    });
                }
            });
            rt.start();
        } catch (Throwable t) {
            // WS 起不来不影响主流程：用户仍可点「我已付款，检查一下」
            Log.e(TAG, "realtime 启动失败: " + t.getMessage());
        }
    }

    /**
     * 取一次码。用户点「我已付款」走的也是这里 —— 与 WS 触发共用同一把锁，
     * 保证「推送先到、用户再点」不会把一次性取码接口打成两次。
     */
    void pickup(final String why) {
        if (done.get() || sid.length() == 0) {
            return;
        }
        if (!picking.compareAndSet(false, true)) {
            return;
        }
        Net.get(ZeroCfg.API_PAY_SESSION + "&sid=" + sid + "&pickup=1", new Net.Cb() {
            @Override
            public void on(final int code, final String resp) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        picking.set(false);
                        onPickup(code, resp, why);
                    }
                });
            }
        });
    }

    private void onPickup(int httpCode, String resp, String why) {
        if (done.get()) {
            return;
        }
        if (httpCode < 0 || TextUtils.isEmpty(resp)) {
            if (cb != null) {
                // 推送能到、取码接口却失败（部署未就绪/网络抖动）必须说出来，
                // 否则用户看到「已就绪」却永远等不到激活，比报错更糟。
                cb.onFail("已收到付款信号，但取码请求失败，请点「我已付款，检查一下」重试");
            }
            return;
        }
        try {
            JSONObject o = new JSONObject(resp);
            String status = o.optString("status", "");
            if ("paid".equals(status)) {
                String code18 = o.optString("activationCode", "");
                if (code18.length() == 18 && done.compareAndSet(false, true)) {
                    finish(true);
                    if (cb != null) {
                        cb.onPaid(code18);
                    }
                    return;
                }
                if (cb != null) {
                    cb.onFail("服务器返回的激活码格式不对，请联系客服");
                }
                return;
            }
            if ("already_picked".equals(status) || "delivered".equals(status)) {
                if ("manual".equals(why) && cb != null) {
                    cb.onFail("这张订单的激活码已经被取走了。如果是你本人操作，回上一页用 4 位兑换码重新激活即可");
                }
                return;
            }
            if ("expired".equals(status)) {
                if (cb != null) {
                    cb.onFail("支付会话已过期（超过 15 分钟），请重新点「去支付」");
                }
                return;
            }
            // waiting：还没收到付款。手动检查时给个明确反馈，避免用户以为没反应
            if ("manual".equals(why) && cb != null) {
                cb.onState("还没收到付款。付完款通常 3 秒内自动激活，也可以稍后再点一次");
            }
        } catch (Throwable t) {
            if (cb != null) {
                cb.onFail("服务器回包无法解析，请点「我已付款，检查一下」重试");
            }
        }
    }

    private void stopRealtime() {
        try {
            if (rt != null) {
                rt.stop();
                rt = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void finish(boolean ok) {
        main.removeCallbacks(timeoutTask);
        stopRealtime();
    }

    private void fail(String reason) {
        if (done.compareAndSet(false, true)) {
            finish(false);
        }
        if (cb != null) {
            cb.onFail(reason);
        }
    }

    /** 页面销毁时调用：必须停 WS，否则会漏一个线程 + 一条长连接 */
    void cancel() {
        done.set(true);
        main.removeCallbacks(timeoutTask);
        stopRealtime();
        cb = null;
    }

    String sid() {
        return sid;
    }
}
