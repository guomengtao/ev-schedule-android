package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    /** 取景层：选图后先调整裁剪范围，确认后才发送（编码与 BLE 链路不变） */
    private Bitmap cropSrc;
    private View cropOverlay;
    private CropView cropView;

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
        imgCard.addView(Ui.text(this, "从相册选图后可拖动、缩放调整取景范围，确认后才压缩发送到手表。",
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
        root.addView(Ui.space(this, 10));

        // ======================= 使用前提（两个条件必须同时满足） =======================
        LinearLayout reqCard = Ui.card(this);
        reqCard.addView(Ui.text(this, "使用前提", 13f, Ui.TEXT, true));
        reqCard.addView(Ui.space(this, 6));
        reqCard.addView(Ui.text(this, "两个条件必须同时满足，缺一个都不会生效。", 11f, Ui.MUTED, false));
        reqCard.addView(Ui.space(this, 8));
        reqCard.addView(Ui.text(this, "① 手环表盘必须选「EV 课程表」", 12f, Ui.TEXT, false));
        reqCard.addView(Ui.text(this, "手环里表盘有很多，但只有「EV 课程表」这一款能读本功能写入的背景。"
                + "选成其他表盘，背景不会出现任何变化。", 11f, Ui.MUTED, false));
        reqCard.addView(Ui.space(this, 8));
        reqCard.addView(Ui.text(this, "② 课程表快应用需 1.9.222 及以上", 12f, Ui.TEXT, false));
        reqCard.addView(Ui.text(this, "背景图能力从 1.9.222 起才可用（1.9.220/221 写入会失败）。"
                + "本机安装的同步器版本不低于 v0.6.57。", 11f, Ui.MUTED, false));
        reqCard.addView(Ui.space(this, 8));
        reqCard.addView(Ui.text(this, "③ 写完之后：切一次表盘才生效", 12f, Ui.WARN, true));
        reqCard.addView(Ui.text(this, "表盘只在启动时读一次配置，所以写完当时屏幕不会变化，"
                + "按侧键回到表盘列表、切到别的表盘再切回来即可。", 11f, Ui.MUTED, false));
        root.addView(reqCard);
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
                            result("背景色已写入：" + o.optString("color") + "。下一步：切一次表盘", Ui.OK);
                            popup("背景色已写入", "【还差最后一步】\n"
                                    + "请按侧键回到手环表盘列表，切到别的表盘再切回来，颜色才会变。\n\n"
                                    + "表盘只在启动时读一次配置，所以现在屏幕上还不会变。", true);
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
        // 选图与取景是纯本地操作，不该被「手环没连上」挡住；连接检查放到发送前。
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
        result("正在读取图片…", Ui.MUTED);
        try {
            Bitmap src = decodeSampled(uri);
            if (src == null) {
                result("这张图读不出来，换一张试试", Ui.ERR);
                return;
            }
            showCrop(src);
        } catch (Throwable t) {
            result("读取失败：" + t, Ui.ERR);
        }
    }

    /** 降采样解码：4000×3000 原图直接进内存会 OOM；1600px 足够输出 192×490。 */
    private Bitmap decodeSampled(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, o);
        if (in != null) {
            in.close();
        }
        int max = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (max / (sample * 2) >= 1600) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        InputStream in2 = getContentResolver().openInputStream(uri);
        Bitmap b = BitmapFactory.decodeStream(in2, null, o2);
        if (in2 != null) {
            in2.close();
        }
        return b;
    }

    // ======================= 取景裁剪 =======================

    /**
     * 选图后不直接发送：先给一个可拖动 / 缩放的取景层。
     * 虚线框内是最终写入手环的部分；框外模糊压暗；右下角实时显示 1:1 实际效果。
     * 这样用户不用先单独截图，也不需要接受固定的居中裁剪。
     */
    private void showCrop(Bitmap src) {
        cropSrc = src;
        if (cropOverlay != null) {
            closeCrop();
            cropSrc = src;
        }

        LinearLayout layer = new LinearLayout(this);
        layer.setOrientation(LinearLayout.VERTICAL);
        layer.setBackgroundColor(0xFF0B0F14);
        layer.setPadding(dp(14), dp(10), dp(14), dp(12));

        // 不用 Ui.header：它的返回箭头会直接 finish 掉整个页面，而不是关闭取景层。
        layer.addView(Ui.text(this, "调整取景范围", 16f, Ui.TEXT, true));
        layer.addView(Ui.space(this, 4));
        layer.addView(Ui.text(this, "拖动图片、双指缩放。虚线框里就是最终传到手环的部分；"
                + "框外是模糊的，右下角是实际效果。", 11f, Ui.MUTED, false));
        layer.addView(Ui.space(this, 6));

        cropView = new CropView(this);
        layer.addView(cropView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        layer.addView(Ui.space(this, 8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(wideButton("重置", new View.OnClickListener() {
            @Override public void onClick(View v) { cropView.resetTransform(); }
        }));
        row.addView(wideButton("旋转 90°", new View.OnClickListener() {
            @Override public void onClick(View v) { rotateSrc(); }
        }));
        row.addView(wideButton("取消", new View.OnClickListener() {
            @Override public void onClick(View v) { closeCrop(); }
        }));
        layer.addView(row);
        layer.addView(Ui.space(this, 6));
        layer.addView(Ui.button(this, "使用这张 → 发送到手环", true, new View.OnClickListener() {
            @Override public void onClick(View v) { commitCrop(); }
        }));

        ViewGroup content = (ViewGroup) getWindow().getDecorView()
                .findViewById(android.R.id.content);
        content.addView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cropOverlay = layer;
        sizeView.setText("取景中：调整好范围后点「使用这张」");
    }

    private View wideButton(String label, View.OnClickListener l) {
        View b = Ui.button(this, label, false, l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void closeCrop() {
        if (cropOverlay != null) {
            try {
                ((ViewGroup) cropOverlay.getParent()).removeView(cropOverlay);
            } catch (Throwable ignored) {
            }
        }
        cropOverlay = null;
        cropView = null;
        cropSrc = null;
    }

    private void rotateSrc() {
        try {
            Bitmap s = cropSrc;
            Matrix r = new Matrix();
            r.postRotate(90);
            cropSrc = Bitmap.createBitmap(s, 0, 0, s.getWidth(), s.getHeight(), r, true);
            cropView.onSourceChanged();
        } catch (Throwable t) {
            result("旋转失败：" + t, Ui.ERR);
        }
    }

    /** 确认后把当前取景矩阵映射成 192×490 位图，后续量化 / 编码 / 发送完全复用原链路。 */
    private void commitCrop() {
        if (cropSrc == null || cropView == null) {
            return;
        }
        try {
            RectF box = cropView.box();
            Matrix fm = new Matrix(cropView.imageMatrix());
            Matrix t = new Matrix();
            t.setTranslate(-box.left, -box.top);
            t.postScale(OW / box.width(), OH / box.height());
            fm.postConcat(t);

            Bitmap fitted = Bitmap.createBitmap(OW, OH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fitted);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(cropSrc, fm, p);

            int[] pal = new int[64];
            byte[] idx = quantize(fitted, pal);
            byte[] png = encodeIndexedPng(OW, OH, idx, pal);
            int chunks = (png.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
            if (!ensureConnected()) {
                popup("还没连上手环", "请先回首页完成连接，再回来点「使用这张」。\n\n"
                        + "取景范围会保留，不会丢。", false);
                return;
            }
            sizeView.setText("已压缩：" + png.length + " 字节 · " + chunks + " 个分片 · " + OW + "×" + OH);
            closeCrop();
            sendImage(png, chunks);
        } catch (Throwable t) {
            result("裁剪失败：" + t, Ui.ERR);
        }
    }

    @Override
    public void onBackPressed() {
        if (cropOverlay != null) {
            closeCrop();
            return;
        }
        super.onBackPressed();
    }

    /** 取景 View：框内清晰、框外模糊压暗，支持单指拖动与双指缩放。 */
    private final class CropView extends View {
        private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint dim = new Paint();
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Matrix m = new Matrix();
        private Bitmap blur;
        private Bitmap blurOf;
        private float baseScale = 1f;
        private float scale = 1f;
        private float offX = 0f;
        private float offY = 0f;
        private float lastX;
        private float lastY;
        private ScaleGestureDetector det;

        CropView(android.content.Context c) {
            super(c);
            dim.setColor(0x99000000);
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(dp(2));
            dash.setColor(0xFF4ADE9B);
            dash.setPathEffect(new DashPathEffect(new float[]{dp(9), dp(6)}, 0));
            solid.setStyle(Paint.Style.STROKE);
            solid.setStrokeWidth(dp(3));
            solid.setColor(0xFF4ADE9B);
            det = new ScaleGestureDetector(c, new ScaleGestureDetector.OnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    scale = Math.max(1f, Math.min(4f, scale * d.getScaleFactor()));
                    clampOffset();
                    invalidate();
                    return true;
                }

                @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                    return true;
                }

                @Override public void onScaleEnd(ScaleGestureDetector d) {
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = dp(16);
            float availW = w - pad * 2;
            float availH = h - pad * 2;
            float bw = Math.min(availW * 0.90f, availH * 0.90f * OW / (float) OH);
            float bh = bw * OH / (float) OW;
            box.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            computeBase();
        }

        void onSourceChanged() {
            blur = null;
            blurOf = null;
            computeBase();
            scale = 1f;
            offX = 0f;
            offY = 0f;
            invalidate();
        }

        void resetTransform() {
            scale = 1f;
            offX = 0f;
            offY = 0f;
            invalidate();
        }

        private void computeBase() {
            Bitmap s = cropSrc;
            if (s == null || box.width() <= 0) {
                return;
            }
            baseScale = Math.max(box.width() / s.getWidth(), box.height() / s.getHeight());
        }

        Matrix imageMatrix() {
            computeMatrix();
            return new Matrix(m);
        }

        RectF box() {
            return new RectF(box);
        }

        private void computeMatrix() {
            Bitmap s = cropSrc;
            if (s == null) {
                return;
            }
            float sc = baseScale * scale;
            float iw = s.getWidth() * sc;
            float ih = s.getHeight() * sc;
            m.setScale(sc, sc);
            m.postTranslate(box.centerX() - iw / 2 + offX, box.centerY() - ih / 2 + offY);
        }

        /** 钳制偏移，保证裁剪框内永远被图片填满，不会拖出黑边。 */
        private void clampOffset() {
            Bitmap s = cropSrc;
            if (s == null) {
                return;
            }
            float sc = baseScale * scale;
            float mx = Math.max(0, (s.getWidth() * sc - box.width()) / 2);
            float my = Math.max(0, (s.getHeight() * sc - box.height()) / 2);
            offX = Math.max(-mx, Math.min(mx, offX));
            offY = Math.max(-my, Math.min(my, offY));
        }

        /** 缩到 1/16 再按 filter 放大绘制 = 兼容所有 API 的低成本模糊，不用 RenderScript。 */
        private Bitmap blurBitmap(Bitmap s) {
            if (blur != null && blurOf == s) {
                return blur;
            }
            int bw = Math.max(1, s.getWidth() / 16);
            int bh = Math.max(1, s.getHeight() / 16);
            blur = Bitmap.createScaledBitmap(s, bw, bh, true);
            blurOf = s;
            return blur;
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap s = cropSrc;
            if (s == null) {
                return;
            }
            c.drawColor(0xFF11151B);
            computeMatrix();

            Bitmap bl = blurBitmap(s);
            // 先铺一层固定 cover 的模糊底：图片被拖到边缘时框外不会露出黑块。
            float bs = Math.max(getWidth() / (float) bl.getWidth(),
                    getHeight() / (float) bl.getHeight());
            Matrix cover = new Matrix();
            cover.setScale(bs, bs);
            cover.postTranslate((getWidth() - bl.getWidth() * bs) / 2,
                    (getHeight() - bl.getHeight() * bs) / 2);
            c.drawBitmap(bl, cover, imgPaint);
            // 再叠一层跟随取景变换的模糊图，让框外与框内的位置尽量对应。
            Matrix bm = new Matrix(m);
            bm.preScale(s.getWidth() / (float) bl.getWidth(),
                    s.getHeight() / (float) bl.getHeight());
            c.drawBitmap(bl, bm, imgPaint);

            int save = c.save();
            c.clipRect(box);
            c.drawBitmap(s, m, imgPaint);
            c.restoreToCount(save);

            c.drawRect(0, 0, getWidth(), box.top, dim);
            c.drawRect(0, box.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, box.top, box.left, box.bottom, dim);
            c.drawRect(box.right, box.top, getWidth(), box.bottom, dim);

            c.drawRect(box, dash);
            float k = dp(16);
            c.drawLine(box.left, box.top, box.left + k, box.top, solid);
            c.drawLine(box.left, box.top, box.left, box.top + k, solid);
            c.drawLine(box.right, box.top, box.right - k, box.top, solid);
            c.drawLine(box.right, box.top, box.right, box.top + k, solid);
            c.drawLine(box.left, box.bottom, box.left + k, box.bottom, solid);
            c.drawLine(box.left, box.bottom, box.left, box.bottom - k, solid);
            c.drawLine(box.right, box.bottom, box.right - k, box.bottom, solid);
            c.drawLine(box.right, box.bottom, box.right, box.bottom - k, solid);

            drawPreview(c, s);
        }

        /** 右下角 1:1 预览：与最终 192×490 输出用同一个取景矩阵，所见即所得。 */
        private void drawPreview(Canvas c, Bitmap s) {
            float ph = Math.min(getHeight() * 0.24f, dp(170));
            float pw = ph * OW / (float) OH;
            float px = getWidth() - pw - dp(12);
            float py = getHeight() - ph - dp(12);
            RectF dst = new RectF(px, py, px + pw, py + ph);

            Matrix pm = new Matrix(m);
            Matrix t = new Matrix();
            t.setTranslate(-box.left, -box.top);
            t.postScale(pw / box.width(), ph / box.height());
            t.postTranslate(px, py);
            pm.postConcat(t);

            c.save();
            c.clipRect(dst);
            c.drawColor(0xFF000000);
            c.drawBitmap(s, pm, imgPaint);
            c.restoreToCount(c.getSaveCount() - 1);

            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(2));
            edge.setColor(0xFFFFFFFF);
            c.drawRect(dst, edge);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            det.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!det.isInProgress() && e.getPointerCount() == 1) {
                        offX += e.getX() - lastX;
                        offY += e.getY() - lastY;
                        lastX = e.getX();
                        lastY = e.getY();
                        clampOffset();
                        invalidate();
                    }
                    return true;
                default:
                    return true;
            }
        }
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
                                // 以 ok 为准：手环侧写入成功即视为成功。
                                // bytes/sizeMatch 是辅助字段，不同版本可能缺失或为 0，
                                // 不能因为「读不到字节数」就误报成传输失败（真机上已复现过误报）。
                                if (!o.optBoolean("ok", false)) {
                                    String miss = o.optString("missing", "");
                                    String reason = o.optString("reason", "未知原因");
                                    result("写入失败：" + reason, Ui.ERR);
                                    popup("写入未成功", "手环回复：" + reason
                                            + (miss.length() > 0 ? "\n缺少分片：" + miss : "")
                                            + "\n\n请确认：① 手环表盘是「EV 课程表」；"
                                            + "② 快应用 ≥ 1.9.222；③ 传图期间停在本应用界面。"
                                            + "\n\n可以直接再试一次。", false);
                                    return;
                                }
                                int bytes = o.optInt("bytes", 0);
                                boolean match = o.optBoolean("sizeMatch", true);
                                String size = bytes > 0 ? (" · " + bytes + " 字节") : "";
                                result("已写入手环" + size + "。下一步：切一次表盘", Ui.OK);
                                popup("已写入手环" + size, match
                                        ? "背景已完整写入。\n\n【还差最后一步】\n"
                                          + "请按侧键回到手环表盘列表，切到别的表盘再切回来 —— "
                                          + "背景就会变成你刚选的样子。\n\n"
                                          + "不做这一步，手环上不会发生任何变化，"
                                          + "因为表盘只在启动时读一次配置。"
                                        : "背景已写入，但字节数与发送值略有出入，若显示异常请重传一次。\n\n"
                                          + "【还差最后一步】切到别的表盘再切回来，才会看到新背景。", true);
                            } catch (Throwable t) {
                                result("回包无法解析：" + json, Ui.ERR);
                                popup("已发送，但回执无法解析",
                                        "图片可能已经写入。\n\n请直接到手环上切一次表盘看看效果；"
                                                + "如果没变化，再重传一次。\n\n原始回执：" + json, true);
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

    /**
     * 结果弹窗。
     * 页面底部的 resultView 在滚动后基本看不到，关键结论必须用弹窗送达，
     * 否则「传成功了但提示看不见 / 看到的是没变化的屏幕」会被误判成功能坏了。
     */
    private void popup(String title, String msg, boolean ok) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    new android.app.AlertDialog.Builder(FaceAppearanceActivity.this)
                            .setTitle((ok ? "✅ " : "⚠️ ") + title)
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
