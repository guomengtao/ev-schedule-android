package com.application.watch.classschedule;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * 极简 HTTP 封装（纯 framework，无 okhttp）：
 *   postJson  —— POST JSON → 拿回包字符串（激活码校验等）
 *   get       —— GET → 拿回包字符串（自动升级 update.json 检查）
 *   download  —— GET → 流式写入调用方给的 OutputStream（自动升级 APK 下载；
 *                手动跟随重定向，兼容镜像 302 跨协议跳转；顺带算 SHA-256）
 * 网络均在子线程执行，回调也从子线程触发，UI 操作由调用方自行 runOnUiThread。
 */
public final class Net {

    public static final String BASE = "https://app-auth.gudq.com";

    public interface Cb {
        /** code < 0 表示网络/异常失败；body 可能为 null */
        void on(int code, String body);
    }

    public interface DlCb {
        /** total 为 -1 表示服务端未给长度（chunked），此时不更新进度百分比 */
        void onProgress(long received, long total);

        /** ok=false 时 err 为异常信息；成功时 sha256hex 是整包 SHA-256（十六进制小写） */
        void onDone(boolean ok, String err, String sha256hex);
    }

    private Net() {
    }

    public static void postJson(final String url, final String json, final Cb cb) {
        exec("POST", url, json, cb);
    }

    public static void get(final String url, final Cb cb) {
        exec("GET", url, null, cb);
    }

    private static void exec(final String method, final String url, final String json, final Cb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                int code = -1;
                String body = null;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod(method);
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(9000);
                    if (json != null) {
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        byte[] data = json.getBytes(Charset.forName("UTF-8"));
                        c.setFixedLengthStreamingMode(data.length);
                        OutputStream os = c.getOutputStream();
                        os.write(data);
                        os.flush();
                        os.close();
                    }
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

    /**
     * 下载到 out（不关闭 out —— PackageInstaller session 流由调用方 fsync 后关闭）。
     * 手动跟随重定向（上限 5 次）：镜像服务会 302 到 objects.githubusercontent.com，
     * 且可能出现 HttpURLConnection 默认不跟随的跨协议跳转。
     */
    public static void download(final String url, final OutputStream out, final DlCb cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    URL u = new URL(url);
                    HttpURLConnection c = null;
                    long total = -1;
                    int redirects = 0;
                    while (true) {
                        c = (HttpURLConnection) u.openConnection();
                        c.setRequestMethod("GET");
                        c.setConnectTimeout(12000);
                        c.setReadTimeout(20000);
                        c.setInstanceFollowRedirects(false);
                        int rc = c.getResponseCode();
                        if (rc >= 300 && rc < 400) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            c = null;
                            if (loc == null || ++redirects > 5) {
                                throw new java.io.IOException("重定向异常");
                            }
                            u = new URL(u, loc);
                            continue;
                        }
                        if (rc < 200 || rc >= 300) {
                            throw new java.io.IOException("HTTP " + rc);
                        }
                        total = c.getContentLengthLong();
                        break;
                    }
                    InputStream is = c.getInputStream();
                    byte[] buf = new byte[16384];
                    long received = 0;
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        received += n;
                        if (cb != null) {
                            cb.onProgress(received, total);
                        }
                    }
                    is.close();
                    if (cb != null) {
                        cb.onDone(true, null, hex(md.digest()));
                    }
                } catch (Throwable t) {
                    if (cb != null) {
                        cb.onDone(false, t.getMessage(), null);
                    }
                }
            }
        }).start();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
