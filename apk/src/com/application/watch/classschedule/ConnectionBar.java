package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 连接状态条（严格单行），各页面共享：
 *   已连接   🔗 已连接 · {手环型号} · v{EV版本}   —— 整条可点 → 手环页
 *   重连中   ⟳ 重连中…（自动拉起手环 EV）          —— 纯展示
 *   离线     ⛓ 离线 · 自动重连未成功，点此去连接调试 —— 整条可点 → 调试页
 *
 * 数据源 = SyncEngine 单例（连接流程回调 + 60s 保活心跳自动刷新），
 * 本类无任何独立连接逻辑；回调经弱引用注册，页面销毁自动失效（强引用挂在 View tag 上）。
 */
public final class ConnectionBar {

    private ConnectionBar() {
    }

    /** 构建状态条并加入 parent；进页面若离线会自动触发一轮重连。 */
    public static void attach(final Activity a, LinearLayout parent) {
        LinearLayout bar = Ui.card(a);
        bar.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_unlink);
        icon.setColorFilter(Ui.MUTED);
        bar.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)));

        final TextView tv = Ui.text(a, "● 检查连接状态…", 12f, Ui.MUTED, false);
        tv.setPadding(Ui.dp(a, 8), 0, 0, 0);
        bar.addView(tv);

        bar.setClickable(true);
        bar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go(a); }
        });
        parent.addView(bar);

        Runnable r = new Runnable() {
            @Override public void run() { refresh(a, icon, tv); }
        };
        bar.setTag(r); // 强引用随 View 生命周期走；引擎侧是弱引用，页面销毁后自动失效
        SyncEngine.get(a).setStatusCallback(r);
        SyncEngine.get(a).autoReconnect(); // 进页面发现离线就自动重连一轮
    }

    private static void go(Activity a) {
        SyncEngine e = SyncEngine.get(a);
        if (e.autoRetryRunning()) {
            return; // 重连中不给点，避免打断
        }
        if (e.connected()) {
            a.startActivity(new Intent(a, BandActivity.class));
        } else {
            a.startActivity(new Intent(a, DebugActivity.class));
        }
    }

    private static void refresh(Activity a, ImageView icon, TextView tv) {
        SyncEngine e = SyncEngine.get(a);
        if (e.connected()) {
            icon.setImageResource(R.drawable.ic_link);
            icon.setColorFilter(Ui.OK);
            tv.setText("已连接 · " + e.deviceName + " · v" + e.versionName);
            tv.setTextColor(Ui.OK);
        } else if (e.autoRetryRunning()) {
            icon.setImageResource(R.drawable.ic_refresh_cw);
            icon.setColorFilter(Ui.ACCENT);
            tv.setText("重连中…（自动拉起手环 EV，无需操作）");
            tv.setTextColor(Ui.ACCENT);
        } else {
            icon.setImageResource(R.drawable.ic_unlink);
            icon.setColorFilter(Ui.MUTED);
            tv.setText("离线 · 自动重连未成功，点此去连接调试");
            tv.setTextColor(Ui.MUTED);
        }
    }
}
