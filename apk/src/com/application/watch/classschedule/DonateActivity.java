package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 打赏支持：与手环 EV 主项目 donate 页一致的三个渠道。 */
public class DonateActivity extends Activity {

    private static final String[][] PLATFORMS = {
            {"爱发电", "https://ifdian.net/a/peipeijin/plan"},
            {"支付宝", "https://qr.alipay.com/fkx11582syvbdeymczggr3c"},
            {"微信", "wxp://f2f0i7FYfQ5xBhxQtXDwsQpkyGZLB7UsjrRjsrXn02Qx8NQHjn8WlZEnozT2BURX_JRy"},
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
            TextView u = Ui.mono(this, url);
            u.setTextSize(10.5f);
            card.addView(u);
            card.addView(Ui.space(this, 10));
            card.addView(Ui.grid(this,
                    Ui.button(this, "打开", true, new View.OnClickListener() {
                        @Override public void onClick(View v) { open(url); }
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
        root.addView(Ui.mono(this, "微信链接为收款码 scheme，若无法直接打开，可复制链接后到微信内使用"));

        setContentView(Ui.wrapWithBottomBar(this, root, 2));
    }

    private void open(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            resultView.setText("没有可打开该链接的应用：" + t);
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
}
