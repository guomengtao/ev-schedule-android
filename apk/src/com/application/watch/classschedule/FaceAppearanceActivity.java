package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 表盘外观（独立页面）：给「EV 课程表」表盘换背景色 / 换背景图。
 *
 * ★ 为什么在手机端而不是手环端：手环上没有相册，也没有选色交互，
 *   这类操作只能发生在手机侧，手环只是被写入的一端。
 *
 * ★ 传输通道：SyncEngine.send() → 穿戴 SDK MessageApi（BLE），
 *   直接发 JSON 给手环快应用，无需 adb / 无需数据线。
 *
 * ★ 协议（与 class-schedule/src/app.ux 严格一致，改协议必须同步改两边）：
 *   {"action":"face_bg","color":"0xRRGGBB"}
 *   {"action":"face_img_start","size":<PNG 总字节>}
 *   {"action":"face_img_chunk","seq":<从1开始>,"data":"<hex>"}
 *   {"action":"face_img_end"}
 *
 * ★ 硬约束（实测得出，写进界面提示）：
 *   ① 图片必须 192×490，非等比只能居中裁剪，拉伸会变形；
 *   ② 必须压成索引色 PNG —— 浏览器/系统默认导出是 32 位 RGBA，
 *      一张普通照片会到 100KB+、上千分片；索引色 64 色可压到 1/50；
 *   ③ 表盘 lua 只在启动时读一次 cfg，写成功后必须切一次表盘才看到变化。
 */
public class FaceAppearanceActivity extends Activity {

    /** 手环表盘分辨率（硬约束） */
    private static final int OW = 192;
    private static final int OH = 490;

    /** 单帧报文的数据字节数：120 字节 → 240 hex 字符，实测稳定（993B/8 片、1433B/12 片均通过） */
    private static final int CHUNK_BYTES = 120;

    /** 每片之间留一点间隔，避免灌爆 BLE 队列 */
    private static final int CHUNK_GAP_MS = 40;

    private static final int REQ_PICK = 1001;

    /** 常用背景色（0xRRGGBB） */
    private static final int[] PRESET_COLORS = {
            0x1E3A52, 0x0B0F14, 0x1A1A2E, 0x2E1A1A, 0x1A2E1A, 0x2A1A0A,
            0x6D28D9, 0x2563EB, 0x0E7490, 0x059669, 0xCA8A04, 0xDC2626,
            0xDB2777, 0x475569, 0x8A8F98, 0xF0F0F0,
    };

    private TextView resultView;
    private TextView sizeView;
    private ProgressBar bar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.header(this, "表盘外观"));
        root.addView(Ui.space(this, 4));
        ConnectionBar.attach(this, root);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.text(this, "给「EV 课程表」表盘换背景。手环只是被写入的一端，操作都在手机上完成。",
                11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        // ======================= 背景色 =======================
        LinearLayout colorCard = Ui.card(this);
        colorCard.addView(Ui.text(this, "背景色", 13f, Ui.TEXT, true));
        colorCard.addView(Ui.space(this, 4));
        colorCard.addView(Ui.text(this, "点一下立即写入，一个报文搞定，不需要连数据线。",
                11f, Ui.MUTED, false));
        colorCard.addView(Ui.space(this, 10));
        for (int i = 0; i < PRESET_COLORS.length; i += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 4 && j < PRESET_COLORS.length; j++) {
                row.addView(swatch(PRESET_COLORS[j]));
            }
            colorCard.addView(row);
            colorCard.addView(Ui.space(this, 8));
        }
        root.addView(colorCard);
        root.addView(Ui.space(this, 4));
        root.addView(Ui.mono(this, "若手环上没变化，先确认当前用的是「EV 课程表」表盘，再切一次表盘。"));
        root.addView(Ui.space(this, 10));

        // ======================= 背景图 =======================
        LinearLayout imgCard = Ui.card(this);
        imgCard.addView(Ui.text(this, "背景图", 13f, Ui.TEXT, true));
        imgCard.addView(Ui.space(this, 4));
        imgCard.addView(Ui.text(this, "从相册选一张图，会自动裁成 192×490 并压成小体积，确认后发送到手表。",
                11f, Ui.MUTED, false));
        imgCard.addView(Ui.space(this, 8));
        sizeView = Ui.text(this, "", 11f, Ui.MUTED, false);
        imgCard.addView(sizeView);
        imgCard.addView(Ui.space(this, 6));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setVisibility(View.GONE);
        imgCard.addView(bar);
        imgCard.addView(Ui.space(this, 8));
        imgCard.addView(Ui.button(this, "从相册选择图片", true, new View.OnClickListener() {
            @Override public void onClick(View v) { pickImage(); }
        }));
        root.addView(imgCard);
        root.addView(Ui.space(this, 4));
        root.addView(Ui.mono(this, "建议用简洁、大色块的图。照片细节越多，分片越多、传得越久。"));
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "传图期间请停在手环本应用界面，息屏或切走会被系统回收。"));
        root.addView(Ui.space(this, 8));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 6));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/face-appearance");
    }

    // ======================= 色板 =======================

    private View swatch(final int rgb) {
        View v = new View(this);
        int size = dp(38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        v.setBackground(Ui.round(0xFF000000 | rgb, 10, 0, this));
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { sendColor(rgb); }
        });
        return v;
    }

    private void sendColor(int rgb) {
        if (!ensureConnected()) {
            return;
        }
        String hex = String.format("0x%06X", rgb & 0xFFFFFF);
        try {
            JSONObject req = new JSONObject();
            req.put("action", "face_bg");
            req.put("color", hex);
            result(hex + " 正在写入…", Ui.MUTED);
            SyncEngine.get(this).send(req.toString(), new SyncEngine.Reply() {
                @Override public void onReply(String json) {
                    try {
                        JSONObject o = new JSONObject(json);
                        if (o.optBoolean("ok", false)) {
                            result("背景色已写入：" + o.optString("color")
                                    + "  ·  在手环上切一次表盘即可看到", Ui.OK);
                        } else {
                            result("手环拒绝：" + o.optString("reason"), Ui.ERR);
                        }
                    } catch (Throwable t) {
                        result("回包无法解析：" + json, Ui.ERR);
                    }
                }
                @Override public void onTimeout(String hint) { result("手环没响应：" + hint, Ui.WARN); }
                @Override public void onError(String msg) { result("发送失败：" + msg, Ui.ERR); }
                @Override public void onWaking() { result("正在唤醒手环应用…", Ui.MUTED); }
            });
        } catch (Throwable t) {
            result("构造报文失败：" + t, Ui.ERR);
        }
    }

    // ======================= 选图 =======================

    private void pickImage() {
        if (!ensureConnected()) {
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(it, "选择背景图"), REQ_PICK);
        } catch (Throwable t) {
            result("打不开相册：" + t, Ui.ERR);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        result("正在处理图片…", Ui.MUTED);
        try {
            Bitmap src;
            InputStream in = getContentResolver().openInputStream(uri);
            src = BitmapFactory.decodeStream(in);
            if (in != null) {
                in.close();
            }
            if (src == null) {
                result("这张图读不出来，换一张试试", Ui.ERR);
                return;
            }
            Bitmap fitted = fit(src);
            int[] pal = new int[64];
            byte[] idx = quantize(fitted, pal);
            byte[] png = encodeIndexedPng(OW, OH, idx, pal);

            int chunks = (png.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            sizeView.setText("已压缩：" + png.length + " 字节 · " + chunks + " 个分片 · " + OW + "×" + OH);
            sendImage(png, chunks);
        } catch (Throwable t) {
            result("处理失败：" + t, Ui.ERR);
        }
    }

    /** 等比缩放后居中裁剪到 192×490（绝不拉伸变形） */
    private Bitmap fit(Bitmap src) {
        float s = Math.max(OW / (float) src.getWidth(), OH / (float) src.getHeight());
        int nw = Math.max(OW, Math.round(src.getWidth() * s));
        int nh = Math.max(OH, Math.round(src.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        int x = Math.max(0, (nw - OW) / 2);
        int y = Math.max(0, (nh - OH) / 2);
        return Bitmap.createBitmap(scaled, x, y, OW, OH);
    }

    /**
     * 4×4×4 均匀切分 + 每格取平均色，得到 64 色调色板与索引图。
     * 索引 = 格子编号（0..63），可直接当 PNG 的调色板下标。
     */
    private byte[] quantize(Bitmap bmp, int[] pal) {
        int n = OW * OH;
        int[] px = new int[n];
        bmp.getPixels(px, 0, OW, 0, 0, OW, OH);

        long[] sr = new long[64];
        long[] sg = new long[64];
        long[] sb = new long[64];
        int[] cnt = new int[64];
        byte[] idx = new byte[n];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int cell = ((r >> 6) << 4) | ((g >> 6) << 2) | (b >> 6);
            idx[i] = (byte) cell;
            sr[cell] += r;
            sg[cell] += g;
            sb[cell] += b;
            cnt[cell]++;
        }
        for (int c = 0; c < 64; c++) {
            if (cnt[c] > 0) {
                int r = (int) (sr[c] / cnt[c]);
                int g = (int) (sg[c] / cnt[c]);
                int b = (int) (sb[c] / cnt[c]);
                pal[c] = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                // 空格子用格心色填充，保证 PLTE 完整
                int r = ((c >> 4) & 3) * 64 + 32;
                int g = ((c >> 2) & 3) * 64 + 32;
                int b = (c & 3) * 64 + 32;
                pal[c] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return idx;
    }

    // ======================= 索引色 PNG 编码 =======================

    /** 8 位索引色 PNG（color type 3 + PLTE），体积约为 RGBA 的 1/4~1/50 */
    private byte[] encodeIndexedPng(int w, int h, byte[] idx, int[] pal) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, 8);

        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, w);
        putInt(ihdr, 4, h);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 3;   // color type: indexed
        ihdr[10] = 0;  // compression
        ihdr[11] = 0;  // filter
        ihdr[12] = 0;  // interlace
        chunk(out, "IHDR", ihdr);

        byte[] plte = new byte[pal.length * 3];
        for (int i = 0; i < pal.length; i++) {
            plte[i * 3] = (byte) ((pal[i] >> 16) & 0xFF);
            plte[i * 3 + 1] = (byte) ((pal[i] >> 8) & 0xFF);
            plte[i * 3 + 2] = (byte) (pal[i] & 0xFF);
        }
        chunk(out, "PLTE", plte);

        // 每行前置 filter=0
        byte[] raw = new byte[(w + 1) * h];
        for (int y = 0; y < h; y++) {
            raw[y * (w + 1)] = 0;
            System.arraycopy(idx, y * w, raw, y * (w + 1) + 1, w);
        }
        Deflater d = new Deflater(9);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int got = d.deflate(buf);
            if (got <= 0) {
                break;
            }
            z.write(buf, 0, got);
        }
        d.end();
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len, 0, 4);
        byte[] t = type.getBytes("US-ASCII");
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c, 0, 4);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    // ======================= 分片发送 =======================

    private void sendImage(final byte[] png, final int total) {
        bar.setVisibility(View.VISIBLE);
        bar.setMax(total);
        bar.setProgress(0);
        result("正在发送 " + png.length + " 字节 / " + total + " 片…", Ui.MUTED);

        final SyncEngine engine = SyncEngine.get(this);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject start = new JSONObject();
                    start.put("action", "face_img_start");
                    start.put("size", png.length);
                    engine.sendStateless(start.toString());
                    Thread.sleep(120);

                    for (int seq = 1; seq <= total; seq++) {
                        int from = (seq - 1) * CHUNK_BYTES;
                        int to = Math.min(png.length, from + CHUNK_BYTES);
                        JSONObject c = new JSONObject();
                        c.put("action", "face_img_chunk");
                        c.put("seq", seq);
                        c.put("data", hex(png, from, to - from));
                        engine.sendStateless(c.toString());
                        final int done = seq;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { bar.setProgress(done); }
                        });
                        Thread.sleep(CHUNK_GAP_MS);
                    }

                    // end 等回执：回执里带 bytes / sizeMatch / missing
                    JSONObject end = new JSONObject();
                    end.put("action", "face_img_end");
                    engine.send(end.toString(), new SyncEngine.Reply() {
                        @Override public void onReply(String json) {
                            try {
                                JSONObject o = new JSONObject(json);
                                if (!o.optBoolean("ok", false)) {
                                    String miss = o.optString("missing", "");
                                    result("写入失败：" + o.optString("reason")
                                            + (miss.length() > 0 ? "（缺片 " + miss + "）" : "")
                                            + "，请重试", Ui.ERR);
                                    return;
                                }
                                boolean match = o.optBoolean("sizeMatch", false);
                                int bytes = o.optInt("bytes", 0);
                                if (match) {
                                    result("已写入手环 · " + bytes + " 字节 · 校验一致\n"
                                            + "现在到手环上切一次表盘，就能看到新背景", Ui.OK);
                                } else {
                                    result("已写入 " + bytes + " 字节，但和发送字节数不一致，建议重传", Ui.WARN);
                                }
                            } catch (Throwable t) {
                                result("回包无法解析：" + json, Ui.ERR);
                            }
                        }
                        @Override public void onTimeout(String hint) { result("手环没响应：" + hint, Ui.WARN); }
                        @Override public void onError(String msg) { result("发送失败：" + msg, Ui.ERR); }
                    });
                } catch (Throwable t) {
                    result("发送中断：" + t, Ui.ERR);
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { bar.setVisibility(View.GONE); }
                    });
                }
            }
        }).start();
    }

    private static String hex(byte[] b, int off, int len) {
        char[] H = "0123456789abcdef".toCharArray();
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i * 2] = H[v >>> 4];
            out[i * 2 + 1] = H[v & 0x0F];
        }
        return new String(out);
    }

    // ======================= 杂项 =======================

    private boolean ensureConnected() {
        if (!SyncEngine.get(this).hasNode()) {
            result("还没连上手环，请先回首页完成连接", Ui.WARN);
            return false;
        }
        return true;
    }

    private void result(final String text, final int color) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (resultView != null) {
                    resultView.setText(text);
                    resultView.setTextColor(color);
                }
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
