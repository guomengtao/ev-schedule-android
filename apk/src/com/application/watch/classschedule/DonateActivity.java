package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

/**
 * 打赏支持 —— 2026-10-07 按 **Stripe 设计系统** 重做视觉层（业务逻辑保持不变）。
 *
 * <p>设计语言（摘自 Stripe DESIGN.md）：
 * <ul>
 *   <li>白画布 {@code #FFFFFF} + 深海军蓝标题 {@code #061B31}（不用纯黑）；正文 slate {@code #64748D}</li>
 *   <li>主紫 {@code #533AFD}（CTA/链接），描边紫 {@code #B9B9F9}；边框 {@code #E5EDF5}</li>
 *   <li><b>weight 300 细字重</b>（sans-serif-light）作为签名——标题不用粗体，靠「轻」显高级</li>
 *   <li>品牌深色段 {@code #1C1E54}（页头 hero）+ ruby→magenta 渐变 {@code #EA2261→#F96BEE} 点缀</li>
 *   <li>保守圆角 4–8px（不用大圆角/胶囊）；蓝调阴影用 elevation 近似</li>
 * </ul>
 *
 * <p>跟随 App 浅/深色：浅色用 Stripe 白底，深色用 Stripe 的 brand-dark 深靛。二维码始终放在
 * 白底 tile 上，保证深色模式下也能扫。
 */
public class DonateActivity extends Activity {

    private int lastThemeVersion = 0;

    // 顺序：微信第一、支付宝第二、爱发电第三
    private static final String[][] PLATFORMS = {
            {"微信", "wxp://f2f0i7FYfQ5xBhxQtXDwsQpkyGZLB7UsjrRjsrXn02Qx8NQHjn8WlZEnozT2BURX_JRy"},
            {"支付宝", "https://qr.alipay.com/fkx11582syvbdeymczggr3c"},
            {"爱发电", "https://ifdian.net/a/peipeijin/plan"},
    };

    // Stripe 品牌常量（跨主题固定：hero 深色段 + 渐变点缀）
    private static final int BRAND_DARK = 0xFF1C1E54;
    private static final int RUBY = 0xFFEA2261;
    private static final int MAGENTA = 0xFFF96BEE;

    // ===== Stripe 令牌（依 App 浅/深色取档） =====
    private int CANVAS, CARD, BORDER, SURFACE;
    private int HEAD, LABEL, BODY;
    private int ACCENT, ACCENT_BORDER, OK, WARN, ERR;

    private TextView resultView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);       // 套用主题（系统控件主题化，须在 setContentView 前）
        applyStripeTokens();                       // 依当前 light/dark 计算 Stripe 色板
        root.setBackgroundColor(CANVAS);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 12));

        // —— 品牌 hero（深靛 + 渐变点缀）——
        root.addView(hero());

        // —— 就地反馈（初始隐藏）——
        resultView = Ui.text(this, "", 13f, BODY, false);
        resultView.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 12);
        root.addView(resultView, rlp);

        // —— 三个渠道卡 ——
        for (int i = 0; i < PLATFORMS.length; i++) {
            final String name = PLATFORMS[i][0];
            final String url = PLATFORMS[i][1];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 16);
            root.addView(platformCard(name, url), lp);
        }

        root.addView(Ui.space(this, 12));

        // 底栏高亮「设置」（本页从设置进入；历史上误用了 index 2=手环）
        ViewGroup rootView = Ui.wrapWithBottomBar(this, root, 3);
        styleBottomBar(rootView, 3);
        setContentView(rootView);
    }

    // ==================================================================
    // Stripe 色板
    // ==================================================================

    private void applyStripeTokens() {
        if (Ui.isDark()) {
            // Stripe brand-dark：深靛段
            CANVAS = 0xFF151745; CARD = 0xFF1C1E54; BORDER = 0xFF2E3070; SURFACE = 0xFF23265E;
            HEAD = 0xFFFFFFFF; LABEL = 0xFFD6D7F5; BODY = 0xFFA9ABDE;
            ACCENT = 0xFF665EFD; ACCENT_BORDER = 0xFF3D3F8F;
            OK = 0xFF34D399; WARN = 0xFFE0B25C; ERR = 0xFFFF7A9C;
        } else {
            CANVAS = 0xFFFFFFFF; CARD = 0xFFFFFFFF; BORDER = 0xFFE5EDF5; SURFACE = 0xFFF6F9FC;
            HEAD = 0xFF061B31; LABEL = 0xFF273951; BODY = 0xFF64748D;
            ACCENT = 0xFF533AFD; ACCENT_BORDER = 0xFFB9B9F9;
            OK = 0xFF108C3D; WARN = 0xFF9B6829; ERR = 0xFFEA2261;
        }
    }

    // ==================================================================
    // 视图构件（Stripe 版式）
    // ==================================================================

    /** 页头 hero：深靛卡 + 返回键 + 26sp/300 细字重标题 + 副文案 + ruby→magenta 渐变线 */
    private View hero() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(BRAND_DARK, 8, 0, this));
        box.setElevation(Ui.dp(this, 6));          // Stripe 蓝调阴影的近似
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 18));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(0xFFFFFFFF);
        back.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        back.setClickable(true);
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        bp.bottomMargin = Ui.dp(this, 6);
        box.addView(back, bp);

        box.addView(light("打赏支持", 26f, 0xFFFFFFFF, -0.02f));

        TextView sub = light("感谢支持，这是持续维护的动力", 14f, 0xC7FFFFFF, 0f);
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(sub);

        View bar = new View(this);
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{RUBY, MAGENTA});
        g.setCornerRadius(Ui.dp(this, 2));
        bar.setBackground(g);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 3));
        gp.topMargin = Ui.dp(this, 16);
        box.addView(bar, gp);
        return box;
    }

    /** 单个渠道卡：白卡(深色下 brand-dark) + 22sp 细字重渠道名 + 白底二维码 tile + mono 链接 + 按钮 */
    private View platformCard(final String name, final String url) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(CARD, 8, BORDER, this));
        c.setElevation(Ui.dp(this, 3));
        c.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 16));

        c.addView(light(name, 22f, HEAD, -0.01f));

        // 二维码：始终白底 tile（深色模式下也能扫）
        Bitmap qr = qrBitmap(url, Ui.dp(this, 168));
        if (qr != null) {
            LinearLayout tile = new LinearLayout(this);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Ui.round(0xFFFFFFFF, 6, BORDER, this));
            tile.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(qr);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 168), Ui.dp(this, 168)));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.gravity = Gravity.CENTER_HORIZONTAL;
            tp.topMargin = Ui.dp(this, 14);
            c.addView(tile, tp);
        }

        // 不再展示裸露的 URL 链接文案（2026-10-07 用户要求去掉；二维码 + 按钮已足够）

        // 按钮行：主紫 + 描边紫 ghost（4px 圆角，Stripe 保守圆角）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button open = filled("打开", new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(name, url);
            }
        });
        Button copy = outlined("复制链接", new View.OnClickListener() {
            @Override public void onClick(View v) {
                copy(name, url);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l2.leftMargin = Ui.dp(this, 10);
        row.addView(open, l1);
        row.addView(copy, l2);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Ui.dp(this, 16);
        c.addView(row, rp);
        return c;
    }

    /** 主按钮：Stripe 主紫实底 + 白字 + 4px 圆角 */
    private Button filled(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.DEFAULT);
        b.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        b.setBackground(Ui.round(ACCENT, 4, 0, this));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    /** 次按钮：Stripe ghost —— 底同卡色 + 1px 描边紫 + 紫字 */
    private Button outlined(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(ACCENT);
        b.setTypeface(Typeface.DEFAULT);
        b.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        b.setBackground(Ui.round(CARD, 4, ACCENT_BORDER, this));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    /** 细字重文本（sans-serif-light ≈ weight 300，Stripe 的签名字重） */
    private TextView light(String s, float sp, int color, float tracking) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        if (tracking != 0f) {
            t.setLetterSpacing(tracking);
        }
        return t;
    }

    /** 就地反馈文案：显示并着色（OK/WARN/ERR） */
    private void feedback(CharSequence text, int color) {
        resultView.setText(text);
        resultView.setTextColor(color);
        resultView.setVisibility(View.VISIBLE);
    }

    /**
     * 把 {@link Ui#wrapWithBottomBar} 生成的底栏重着色为 Stripe：
     * 底 = CARD，选中 = 主紫，未选中 = BODY。仅作用于本页。
     */
    private void styleBottomBar(ViewGroup rootView, int current) {
        for (int i = 0; i < rootView.getChildCount(); i++) {
            View ch = rootView.getChildAt(i);
            if (!(ch instanceof LinearLayout)) {
                continue;
            }
            LinearLayout bar = (LinearLayout) ch;
            if (bar.getChildCount() != 4) {
                continue;
            }
            bar.setBackgroundColor(CARD);
            for (int j = 0; j < bar.getChildCount(); j++) {
                View tabV = bar.getChildAt(j);
                if (!(tabV instanceof LinearLayout)) {
                    continue;
                }
                LinearLayout tab = (LinearLayout) tabV;
                boolean active = (j == current);
                int color = active ? ACCENT : BODY;
                if (tab.getChildCount() >= 2) {
                    View ic = tab.getChildAt(0);
                    if (ic instanceof ImageView) {
                        ((ImageView) ic).setColorFilter(color);
                    }
                    View lb = tab.getChildAt(1);
                    if (lb instanceof TextView) {
                        ((TextView) lb).setTextColor(color);
                    }
                }
            }
        }
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
                    feedback("已拉起微信，请用「扫一扫」扫描本页二维码", OK);
                    return;
                }
                throw new android.content.ActivityNotFoundException("微信未安装");
            }
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            copy(name, url);
            feedback("打不开，已复制「" + name + "」链接：" + t.getMessage(), WARN);
        }
    }

    private void copy(String name, String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(name, url));
                feedback("已复制「" + name + "」链接", OK);
            }
        } catch (Throwable t) {
            feedback("复制失败：" + t, ERR);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
    }
}
