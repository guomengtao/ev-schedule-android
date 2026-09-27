package com.application.watch.classschedule;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** 极简 HTTP 封装：只做「POST JSON → 拿回包字符串」，在子线程执行，回调用主线程由调用方处理。 */
public final class Net {

    public static final String BASE = "https://app-auth.gudq.com";

    public interface Cb {
        /** code < 0 表示网络/异常失败；body 可能为 null */
        void on(int code, String body);
    }

    private Net() {
    }

    public static void postJson(final String url, final String json, final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                int code = -1;
                String body = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(9000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] data = json.getBytes(Charset.forName("UTF-8"));
                    c.setFixedLengthStreamingMode(data.length);
                    OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.flush();
                    os.close();
                    code = c.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
                    body = readAll(is);
                } catch (Throwable t) {
                    code = -1;
                    body = null;
                } finally {
                    try {
                        if (c != null) {
                            c.disconnect();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (cb != null) {
                    cb.on(code, body);
                }
            }
        }).start();
    }

    private static String readAll(InputStream is) {
        if (is == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }
}
