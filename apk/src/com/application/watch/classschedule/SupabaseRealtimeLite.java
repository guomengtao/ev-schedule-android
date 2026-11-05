package com.application.watch.classschedule;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Supabase Realtime 长连接（移植自 {@code ev-ops-android/.../SupabaseRealtime.java}）。
 *
 * 为什么移植而不是重写：那份实现是**在其大屏上跑了很久的生产代码**，手写 RFC6455
 * （握手 / 分帧 / 掩码 / ping-pong / SNI）都在真机上趟过坑；本项目构建链路同样没有
 * 第三方库（dx/d8 对 java-websocket 有 IncompatibleClassChangeError 老问题），照抄最稳。
 * 与原件相比只改了三处：包名、默认表名（activation_events）、以及下面这条：
 *
 * ⚠️ **新增 onSubscribed() 回调（坑 pit-20261105-98）**：
 * `phx_reply status:ok` 只代表 join 请求被接受，复制槽还没挂上；真正就绪是紧随其后的一条
 * system 消息 `{"event":"system","payload":{"message":"Subscribed to PostgreSQL","status":"ok"}}`。
 * 调用方必须等这个回调再信任事件流（我们用它决定「现在开始计时」而不是「现在开始付款」）。
 *
 * 鉴权用 anon key：受 RLS 约束只能读事件表，事件表里只有 sid/status/ts，**知道 sid 也换不到码**。
 */
class SupabaseRealtimeLite {

    private static final String TAG = "EvZeroRealtime";

    interface Listener {
        /** 表有一行变更（INSERT/UPDATE/DELETE），record 是变更后的整行 */
        void onRow(String type, JSONObject record);

        /** 传输层连上/断开 */
        void onConnectionChange(boolean connected);

        /** 订阅**真正**就绪（收到 Subscribed to PostgreSQL），此后事件才可靠 */
        void onSubscribed();
    }

    private final String wsUrl;
    private final String table;
    private final Listener listener;
    private WsClient ws;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private int ref = 0;
    private ScheduledExecutorService heartbeat;
    private ScheduledExecutorService reconnect;

    SupabaseRealtimeLite(String supabaseUrl, String anonKey, String table, Listener l) {
        this.table = (table == null || table.trim().length() == 0) ? "activation_events" : table.trim();
        String raw = supabaseUrl == null ? "" : supabaseUrl.trim();
        String url;
        if (raw.startsWith("wss://") || raw.startsWith("ws://")) {
            url = raw;
        } else {
            String h = raw.replaceFirst("^https://", "").replaceFirst("^http://", "");
            url = "wss://" + h + "/realtime/v1/websocket?apikey="
                    + (anonKey == null ? "" : anonKey) + "&vsn=1.0.0";
        }
        this.wsUrl = url;
        this.listener = l;
    }

    void start() {
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    void stop() {
        running.set(false);
        if (reconnect != null) {
            reconnect.shutdownNow();
            reconnect = null;
        }
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
        if (ws != null) {
            ws.close();
            ws = null;
        }
    }

    boolean isConnected() {
        return connected.get();
    }

    private void connect() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ws = new WsClient();
                    ws.connect();
                } catch (Throwable t) {
                    Log.e(TAG, "connect: " + (t == null ? "?" : t.getMessage()));
                    scheduleReconnect();
                }
            }
        }).start();
    }

    private void sendJoin() {
        ref++;
        try {
            JSONObject cfg = new JSONObject();
            JSONArray changes = new JSONArray();
            JSONObject ch = new JSONObject();
            ch.put("event", "*");
            ch.put("schema", "public");
            ch.put("table", table);
            changes.put(ch);
            cfg.put("postgres_changes", changes);

            JSONObject p = new JSONObject();
            p.put("config", cfg);

            JSONObject m = new JSONObject();
            // 带 schema 的 topic 与 11-05 服务端探针一致（realtime:public:<table>），
            // 与 ev-ops 原件（realtime:<table>）的差别只在这里，两种写法 Supabase 都接受。
            m.put("topic", "realtime:public:" + table);
            m.put("event", "phx_join");
            m.put("payload", p);
            m.put("ref", String.valueOf(ref));
            if (ws != null) {
                ws.sendText(m.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (ws != null && ws.isOpen()) {
                    ref++;
                    try {
                        JSONObject m = new JSONObject();
                        m.put("topic", "phoenix");
                        m.put("event", "heartbeat");
                        m.put("payload", new JSONObject());
                        m.put("ref", String.valueOf(ref));
                        ws.sendText(m.toString());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, 0, 25, TimeUnit.SECONDS);
    }

    private void handle(String message) {
        try {
            JSONObject m = new JSONObject(message);
            String event = m.optString("event", "");
            String topic = m.optString("topic", "");
            if ("system".equals(event)) {
                JSONObject pl = m.optJSONObject("payload");
                String sysMsg = pl == null ? "" : pl.optString("message", "");
                if (sysMsg.contains("Subscribed to PostgreSQL")) {
                    Log.i(TAG, "订阅就绪（Subscribed to PostgreSQL）");
                    if (listener != null) {
                        listener.onSubscribed();
                    }
                }
                return;
            }
            if ("heartbeat".equals(event) && "phoenix".equals(topic)) {
                if (ws != null) {
                    JSONObject reply = new JSONObject();
                    reply.put("topic", "phoenix");
                    reply.put("event", "phx_reply");
                    reply.put("payload", new JSONObject());
                    reply.put("ref", m.optString("ref", ""));
                    ws.sendText(reply.toString());
                }
                return;
            }
            if ("postgres_changes".equals(event)) {
                JSONObject pl = m.optJSONObject("payload");
                JSONObject data = pl == null ? null : pl.optJSONObject("data");
                if (data == null || listener == null) {
                    return;
                }
                String type = data.optString("type", "UPDATE");
                JSONObject rec = data.optJSONObject("record");
                if (rec == null) {
                    rec = data.optJSONObject("old");
                }
                if (rec != null) {
                    listener.onRow(type, rec);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (reconnect != null && !reconnect.isShutdown()) {
            return;
        }
        reconnect = Executors.newSingleThreadScheduledExecutor();
        reconnect.schedule(new Runnable() {
            @Override
            public void run() {
                if (running.get() && (ws == null || !ws.isOpen())) {
                    connect();
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    // ======================= 最小 RFC6455 客户端 =======================

    private class WsClient {
        private Socket sock;
        private OutputStream out;
        private DataInputStream in;
        private String host;
        private final SecureRandom rnd = new SecureRandom();
        private volatile boolean stop;
        private boolean open;

        void connect() throws Exception {
            boolean tls = wsUrl.startsWith("wss://");
            String rest = wsUrl.substring(wsUrl.indexOf("//") + 2);
            int qi = rest.indexOf('?');
            String pathPart = qi >= 0 ? rest.substring(qi) : "";
            String hp = qi >= 0 ? rest.substring(0, qi) : rest;
            int sl = hp.indexOf('/');
            if (sl < 0) {
                sl = hp.length();
            }
            String hostPort = hp.substring(0, sl);
            String hpath = sl < hp.length() ? hp.substring(sl) : "/";
            int co = hostPort.indexOf(':');
            String h;
            int port;
            if (co >= 0) {
                h = hostPort.substring(0, co);
                port = Integer.parseInt(hostPort.substring(co + 1));
            } else {
                h = hostPort;
                port = tls ? 443 : 80;
            }
            this.host = h;
            String fullPath = hpath + pathPart;
            Socket base;
            if (tls) {
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket s = (SSLSocket) f.createSocket();
                // 裸 SSLSocket 必须显式设 SNI + HTTPS 端点识别，否则握手“成功”但 101 永远不来
                SSLParameters sp = s.getSSLParameters();
                try {
                    sp.setServerNames(Collections.<javax.net.ssl.SNIServerName>singletonList(new SNIHostName(h)));
                } catch (Throwable ignored) {
                }
                try {
                    sp.setEndpointIdentificationAlgorithm("HTTPS");
                } catch (Throwable ignored) {
                }
                s.setSSLParameters(sp);
                s.connect(new InetSocketAddress(h, port), 15000);
                s.setSoTimeout(15000);
                s.startHandshake();
                base = s;
            } else {
                base = new Socket(h, port);
                base.setSoTimeout(15000);
            }
            out = base.getOutputStream();
            in = new DataInputStream(base.getInputStream());
            sock = base;
            sendHandshake(fullPath);
            if (!readHandshake()) {
                Log.e(TAG, "握手失败(非 101)");
                close();
                scheduleReconnect();
                return;
            }
            open = true;
            connected.set(true);
            if (listener != null) {
                listener.onConnectionChange(true);
            }
            sendJoin();
            startHeartbeat();
            readLoop();
        }

        boolean isOpen() {
            return open && !stop;
        }

        private void sendHandshake(String fullPath) throws Exception {
            byte[] nonce = new byte[16];
            rnd.nextBytes(nonce);
            String key = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP);
            StringBuilder sb = new StringBuilder();
            sb.append("GET ").append(fullPath).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host).append("\r\n");
            sb.append("Upgrade: websocket\r\n");
            sb.append("Connection: Upgrade\r\n");
            sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            sb.append("Sec-WebSocket-Version: 13\r\n");
            sb.append("Origin: https://").append(host).append("\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private boolean readHandshake() throws Exception {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int st = 0;
            try {
                int c;
                while ((c = in.read()) != -1) {
                    bo.write(c);
                    if (c == '\r' && st == 2) {
                        st = 3;
                    } else if (c == '\n' && st == 3) {
                        break;
                    } else if (c == '\n' && st == 1) {
                        st = 2;
                    } else if (c == '\r') {
                        st = 1;
                    } else {
                        st = 0;
                    }
                    if (bo.size() > 4096) {
                        break;
                    }
                }
            } catch (SocketTimeoutException toe) {
                Log.e(TAG, "握手读取超时，已收 " + bo.size() + " 字节");
                throw toe;
            }
            return bo.toString("UTF-8").contains(" 101 ");
        }

        private void readLoop() {
            StringBuilder msg = new StringBuilder();
            boolean inMsg = false;
            try {
                while (!stop) {
                    int b0;
                    try {
                        b0 = in.read();
                    } catch (SocketTimeoutException timeout) {
                        continue;                    // 空闲读超时正常，心跳由定时器负责
                    }
                    if (b0 < 0) {
                        break;
                    }
                    try {
                        int b1 = in.read();
                        if (b1 < 0) {
                            break;
                        }
                        int opcode = b0 & 0x0f;
                        int len = b1 & 0x7f;
                        boolean masked = (b1 & 0x80) != 0;
                        if (len == 126) {
                            len = (in.read() << 8) | in.read();
                        } else if (len == 127) {
                            long l = 0;
                            for (int i = 0; i < 8; i++) {
                                l = (l << 8) | in.read();
                            }
                            len = (int) l;
                        }
                        byte[] mk = null;
                        if (masked) {
                            mk = new byte[4];
                            in.readFully(mk);
                        }
                        byte[] payload = new byte[len];
                        in.readFully(payload);
                        if (masked) {
                            for (int i = 0; i < len; i++) {
                                payload[i] = (byte) (payload[i] ^ mk[i & 3]);
                            }
                        }
                        if (opcode == 0x8) {
                            break;
                        } else if (opcode == 0x9) {
                            sendPong(payload);
                        } else if (opcode == 0xA) {
                            // pong: ignore
                        } else if (opcode == 0x1 || opcode == 0x0) {
                            if (opcode == 0x1) {
                                msg.setLength(0);
                                inMsg = true;
                            }
                            if (inMsg) {
                                msg.append(new String(payload, StandardCharsets.UTF_8));
                            }
                            boolean fin = (b0 & 0x80) != 0;
                            if (fin && inMsg) {
                                inMsg = false;
                                handle(msg.toString());
                            }
                        }
                    } catch (SocketTimeoutException t2) {
                        inMsg = false;
                        msg.setLength(0);
                        continue;
                    }
                }
            } catch (Throwable t) {
                if (!stop) {
                    Log.e(TAG, "readLoop: " + (t == null ? "?" : t.getMessage()));
                }
            } finally {
                close();
            }
        }

        synchronized void sendText(String s) throws Exception {
            if (out == null) {
                return;
            }
            byte[] p = s.getBytes(StandardCharsets.UTF_8);
            int len = p.length;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bo.write(0x81);
            if (len < 126) {
                bo.write(0x80 | len);
            } else if (len < 65536) {
                bo.write(0x80 | 126);
                bo.write((len >> 8) & 0xff);
                bo.write(len & 0xff);
            } else {
                bo.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    bo.write((int) (((long) len >> (8 * i)) & 0xff));
                }
            }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            bo.write(mask);
            byte[] maskedBytes = new byte[len];
            for (int i = 0; i < len; i++) {
                maskedBytes[i] = (byte) (p[i] ^ mask[i & 3]);
            }
            bo.write(maskedBytes);
            out.write(bo.toByteArray());
            out.flush();
        }

        private void sendPong(byte[] payload) {
            try {
                int len = payload.length;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bo.write(0x8A);
                if (len < 126) {
                    bo.write(0x80 | len);
                } else if (len < 65536) {
                    bo.write(0x80 | 126);
                    bo.write((len >> 8) & 0xff);
                    bo.write(len & 0xff);
                } else {
                    bo.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) {
                        bo.write((int) (((long) len >> (8 * i)) & 0xff));
                    }
                }
                byte[] mask = new byte[4];
                rnd.nextBytes(mask);
                bo.write(mask);
                byte[] m = new byte[len];
                for (int i = 0; i < len; i++) {
                    m[i] = (byte) (payload[i] ^ mask[i & 3]);
                }
                bo.write(m);
                out.write(bo.toByteArray());
                out.flush();
            } catch (Throwable ignored) {
            }
        }

        void close() {
            stop = true;
            open = false;
            connected.set(false);
            if (listener != null) {
                listener.onConnectionChange(false);
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (sock != null) {
                    sock.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
