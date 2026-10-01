package com.application.watch.classschedule;

/**
 * 极薄统一日志桥（docs/android-usb-debug-workflow.md §7.2）：
 * 全 App 的 logcat 出口，tag 固定 EVProbe，Mac 侧 `adb logcat -s EVProbe` 即可观测。
 * 当前只接了课程编辑器（真机回归排查用），其他模块按需接入。
 */
public final class EvLog {
    private static final String TAG = "EVProbe";

    private EvLog() {
    }

    public static void i(String m) {
        android.util.Log.i(TAG, m);
    }
}
