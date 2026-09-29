package com.application.watch.classschedule;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 全 App 共用的自绘弹窗（脱离系统 AlertDialog 默认样式），各页确认/提示统一走这里：
 * 圆角卡 + 图标章 + 标题 + 正文 + 可选红色警示行 + 「取消」幽灵钮 + 主按钮（普通蓝 / 危险红）。
 *
 * 用法：
 *   Dialogs.confirm(a, R.drawable.ic_trash_2, 0, "删除课表", "「xx」", "不可撤销", "删除", true, onOk);
 *   Dialogs.info(a, R.drawable.ic_link, 0, "已同步", "课表已写入手环", "知道了");
 */
public final class Dialogs {

    /** 按钮回调 */
    public interface Action {
        void run();
    }

    private static final int RED = 0xFFE5484D;

    private Dialogs() {
    }

    /** 确认框（取消 + 主按钮）。iconRes 传 0 不显示图标章；danger=true 主按钮红色。 */
    public static Dialog confirm(final Activity a, int iconRes, int tint,
                                 String title, String msg, String danger,
                                 String okText, boolean dangerBtn, final Action onOk) {
        return build(a, iconRes, tint, title, msg, danger, okText, dangerBtn, onOk, null, true);
    }

    /** 确认框（带取消回调的重载） */
    public static Dialog confirm(final Activity a, int iconRes, int tint,
                                 String title, String msg, String danger,
                                 String okText, boolean dangerBtn,
                                 final Action onOk, final Action onCancel) {
        return build(a, iconRes, tint, title, msg, danger, okText, dangerBtn, onOk, onCancel, true);
    }

    /** 提示框（仅一个「知道了」主按钮） */
    public static Dialog info(final Activity a, int iconRes, int tint,
                              String title, String msg, String okText, final Action onOk) {
        return build(a, iconRes, tint, title, msg, null, okText, false, onOk, null, false);
    }

    private static Dialog build(final Activity a, int iconRes, int tint,
                                String title, String msg, String danger,
                                String okText, boolean dangerBtn,
                                final Action onOk, final Action onCancel, boolean showCancel) {
        final int accent = dangerBtn ? RED : Ui.ACCENT;
        final Dialog[] holder = new Dialog[1];

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(a, 22);
        box.setBackground(Ui.round(Ui.CARD, 22, 0, a));
        box.setPadding(pad, pad, pad, pad);

        if (iconRes != 0) {
            ImageView iv = new ImageView(a);
            iv.setImageResource(iconRes);
            iv.setColorFilter(accent);
            iv.setBackground(Ui.round((accent & 0x00FFFFFF) | 0x2E000000, 26, 0, a));
            iv.setPadding(Ui.dp(a, 13), Ui.dp(a, 13), Ui.dp(a, 13), Ui.dp(a, 13));
            box.addView(iv, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
            box.addView(Ui.space(a, 12));
        }

        box.addView(Ui.text(a, title, 16.5f, Ui.TEXT, true));
        box.addView(Ui.space(a, 6));
        if (msg != null && msg.length() > 0) {
            TextView m = Ui.text(a, msg, 13.5f, Ui.TEXT, false);
            m.setGravity(Gravity.CENTER);
            box.addView(m);
        }
        if (danger != null && danger.length() > 0) {
            TextView d = Ui.text(a, danger, 12f, RED, false);
            d.setGravity(Gravity.CENTER);
            d.setPadding(0, Ui.dp(a, 10), 0, 0);
            box.addView(d);
        }
        box.addView(Ui.space(a, 18));

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        if (showCancel) {
            Button cancel = Ui.button(a, "取消", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    if (onCancel != null) {
                        onCancel.run();
                    }
                }
            });
            cancel.setBackground(Ui.round(0x00000000, 12, Ui.LINE, a));
            cancel.setTextColor(Ui.TEXT);
            btns.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f));
            // ⚠️ 横向布局里不能用 Ui.space（MATCH_PARENT 宽会把主按钮挤出弹窗），用 margin 分隔
            LinearLayout.LayoutParams lp0 = (LinearLayout.LayoutParams) btns.getChildAt(0).getLayoutParams();
            lp0.rightMargin = Ui.dp(a, 10);
        }
        Button ok = Ui.button(a, okText, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (onOk != null) {
                    onOk.run();
                }
            }
        });
        ok.setBackground(Ui.round(accent, 12, 0, a));
        ok.setTextColor(0xFFFFFFFF);
        btns.addView(ok, new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f));
        box.addView(btns);

        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(a, 310), WindowManager.LayoutParams.WRAP_CONTENT);
        holder[0] = dlg;
        dlg.show();
        return dlg;
    }
}
