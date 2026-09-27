package com.application.watch.classschedule;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * 构建变体信息（EV 课程表 / EvBox 工具箱）。
 *
 * 一套源码要出两个包名的 APK（interconnect 要求 APK 包名 == 快应用包名，
 * 而两个快应用包名不同），差异项由 build.sh 在打包时写进 AndroidManifest 的
 * &lt;meta-data&gt;，运行期统一从这里读 —— **代码里不要硬编码对端快应用包名**。
 */
public final class Variant {

    public static final String EV = "ev";
    public static final String EVBOX = "evbox";

    private static String variant;
    private static String peerPkg;

    private Variant() {
    }

    private static void load(Context c) {
        if (variant != null) {
            return;
        }
        variant = EV;
        peerPkg = "com.application.watch.classschedule";
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
            Bundle b = ai.metaData;
            if (b != null) {
                String v = b.getString("ev.variant");
                if (v != null && v.length() > 0) {
                    variant = v;
                }
                String p = b.getString("ev.peer_pkg");
                if (p != null && p.length() > 0) {
                    peerPkg = p;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 变体名：ev | evbox */
    public static String name(Context c) {
        load(c);
        return variant;
    }

    /** 对端手环快应用包名（launchWearApp / 文案提示用） */
    public static String peerPkg(Context c) {
        load(c);
        return peerPkg;
    }

    /** 是否 EV 课程表变体（决定首页是否显示"导入/导出课程表"等专属入口） */
    public static boolean isEv(Context c) {
        return EV.equals(name(c));
    }
}
