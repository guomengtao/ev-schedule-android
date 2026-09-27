package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** 打赏支持：与手环 EV 主项目 donate 页一致的三个渠道。 */
public class DonateActivity extends Activity {

    // 顺序：微信第一、支付宝第二、爱发电第三
    private static final String[][] PLATFORMS = {
            {"微信", "wxp://f2f0i7FYfQ5xBhxQtXDwsQpkyGZLB7UsjrRjsrXn02Qx8NQHjn8WlZEnozT2BURX_JRy"},
            {"支付宝", "https://qr.alipay.com/fkx11582syvbdeymczggr3c"},
            {"爱发电", "https://ifdian.net/a/peipeijin/plan"},
    };

    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "打赏支持"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "感谢支持，这是持续维护的动力", 11.5f, Ui.MUTED, false));
        root.addView(Ui.space(this, 10));

        for (int i = 0; i < PLATFORMS.length; i++) {
            final String name = PLATFORMS[i][0];
            final String url = PLATFORMS[i][1];
            LinearLayout card = Ui.card(this);
            card.addView(Ui.text(this, name, 15f, Ui.ACCENT, true));
            card.addView(Ui.space(this, 6));

            // 生成的二维码（扫码即可，无需手输/手点 scheme）
            Bitmap qr = qrBitmap(url, Ui.dp(this, 180));
            if (qr != null) {
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                        Ui.dp(this, 180), Ui.dp(this, 180));
                ip.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                iv.setLayoutParams(ip);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(qr);
                card.addView(iv);
                card.addView(Ui.space(this, 6));
            }

            TextView u = Ui.mono(this, url);
            u.setTextSize(10.5f);
            card.addView(u);
            card.addView(Ui.space(this, 10));
            card.addView(Ui.grid(this,
                    Ui.button(this, "打开", true, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(name, url); }
                    }),
                    Ui.button(this, "复制链接", false, new View.OnClickListener() {
                        @Override public void onClick(View v) { copy(name, url); }
                    })));
            root.addView(card);
            root.addView(Ui.space(this, 10));
        }

        resultView = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 6));
        root.addView(Ui.mono(this,
                "微信链接为收款码 scheme，若无法直接打开，可点「打开」拉起微信后用「扫一扫」扫码，或复制链接"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
    }

    /** wxp:// 这类微信收款码 scheme 无法被 ACTION_VIEW 直接拉起，
     *  改为拉起微信 App（需在 Manifest <queries> 声明 com.tencent.mm 可见性），
     *  让用户用「扫一扫」扫码；失败则回退到复制链接。 */
    private void open(String name, String url) {
        try {
            if (url.startsWith("wxp://")) {
                Intent wx = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
                if (wx != null) {
                    wx.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(wx);
                    resultView.setText("已拉起微信，请用「扫一扫」扫描本页二维码");
                    resultView.setTextColor(Ui.OK);
                    return;
                }
                throw new android.content.ActivityNotFoundException("微信未安装");
            }
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            copy(name, url);
            resultView.setText("打不开，已复制「" + name + "」链接：" + t.getMessage());
            resultView.setTextColor(Ui.WARN);
        }
    }

    private void copy(String name, String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(name, url));
                resultView.setText("已复制「" + name + "」链接");
                resultView.setTextColor(Ui.OK);
            }
        } catch (Throwable t) {
            resultView.setText("复制失败：" + t);
            resultView.setTextColor(Ui.ERR);
        }
    }

    /** 用 zxing 把链接生成为二维码 Bitmap（库随 APK 打包，离线可用）。 */
    private static Bitmap qrBitmap(String content, int sizePx) {
        if (content == null || content.length() == 0 || sizePx <= 0) {
            return null;
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (WriterException | RuntimeException e) {
            return null;
        }
    }
}
