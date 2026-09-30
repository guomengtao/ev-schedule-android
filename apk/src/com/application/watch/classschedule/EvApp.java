package com.application.watch.classschedule;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 应用入口。只干两件事，都为了埋点统计：
 *   1. {@link Stats#onProcessStart}：升级次数 / 打开次数（进程启动即一次「打开」）；
 *   2. Activity 生命周期 → 累计「前台时长」。
 *
 * ⚠️ onCreate 里**绝不能抛异常**（抛了整个 App 起不来），所以整段包在 try 里；
 *    埋点统计的价值远低于能正常启动，任何时候都以不崩为第一优先。
 *
 * 统计口径：进入前台（第一个 Activity onStart）开始计时，全部 Activity 都 onStop 时结算。
 * 进程被系统直接杀掉不会回调 onStop → 那一小段尾巴丢掉，这是可接受的近似（见 docs/apk-tracking-telemetry-spec.md §6.2）。
 */
public class EvApp extends Application implements Application.ActivityLifecycleCallbacks {

    private int startedCount;   // 当前处于「已 onStart 未 onStop」的 Activity 数
    private long fgStart;       // 本次前台开始时间（0 = 不在前台计时中）

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Stats.onProcessStart(this);
            registerActivityLifecycleCallbacks(this);
            // P3（§4.4）：app_open 只落库不推送（与 page_visit 高度重复，推了纯噪音）；
            //   open_count/upgrade_count 在 body.app 段里，服务端据此算日活与升级确认。
            Analytics.event(this, "app_open", null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        try {
            if (startedCount == 0) {
                fgStart = System.currentTimeMillis();
                // 进前台：启动连接保活心跳（每 60s 静默 ping，防手环 EV 闲置退出）
                SyncEngine.get(this).startKeepalive();
            }
            startedCount++;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        try {
            if (startedCount > 0) {
                startedCount--;
            }
            if (startedCount == 0 && fgStart > 0) {
                Stats.addForegroundMs(this, System.currentTimeMillis() - fgStart);
                fgStart = 0;
                // 退后台：心跳停止（留言接收由常驻服务观察者接管）
                SyncEngine.get(this).stopKeepalive();
            }
        } catch (Throwable ignored) {
        }
    }

    // 其余生命周期回调本模块用不到
    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
