package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「找手机」响铃时的**全屏提示页** —— 出现在所有窗口之上（含锁屏），可**一键立即关闭**。
 *
 * 触发：CommandRouter.findPhone() 里 startActivity + 通知的 setFullScreenIntent。
 * 关闭方式（任一即可立即停止响铃/震动并关掉本页）：
 *   · 点「停止响铃」按钮
 *   · 物理/手势返回键
 *   · 若响铃因超时（FIND_MAX_MS）或通知栏被提前停掉 → 本页轮询到已停，自动关闭
 *
 * 对齐「兜底最长时间」：本页只负责展示与关闭，真正的时长上限由 CommandRouter 保证。
 */
public class FindPhoneActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable watch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏之上显示 + 亮屏（minSdk 24，用 window flag；API 27+ 另有官方 API）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        Ui.applyTheme(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF0B0F14);   // 报警页固定深底，保证任何主题下都醒目

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32));

        TextView icon = Ui.text(this, "🔔", 46f, 0xFFFFFFFF, false);
        icon.setGravity(Gravity.CENTER);
        inner.addView(icon);

        TextView title = Ui.text(this, "找手机", 27f, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 10), 0, 0);
        inner.addView(title);

        TextView hint = Ui.text(this,
                "手机正在响铃 / 震动，最多 " + CommandRouter.findMaxSeconds() + " 秒会自动停止",
                14f, 0xFF9AA4B2, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 30));
        inner.addView(hint);

        Button stop = Ui.button(this, "停止响铃", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                CommandRouter.stopFindPhone(FindPhoneActivity.this);
                finish();
            }
        });
        stop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        stop.setPadding(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));
        inner.addView(stop);

        root.addView(inner);
        setContentView(root);
        Analytics.pageView(this, "/apk/findphone");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 轮询：响铃一旦结束（超时/被通知栏停掉）→ 自动关掉本页，避免留在屏上显示过期状态
        watch = new Runnable() {
            @Override public void run() {
                if (!CommandRouter.isFinding() || isFinishing()) {
                    finish();
                    return;
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(watch, 500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (watch != null) {
            handler.removeCallbacks(watch);
            watch = null;
        }
    }

    @Override
    public void onBackPressed() {
        CommandRouter.stopFindPhone(this);
        finish();
    }

    /** 用户按 Home/切走时不停止响铃（找手机就是要一直响到找到为止，受 FIND_MAX_MS 上限约束） */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
    }
}
