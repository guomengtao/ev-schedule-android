package com.application.watch.classschedule;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;
import java.util.Set;

/**
 * 设备探针：一次性收集「能证明这台手环是谁」的三个标识。
 *
 * | 标识 | 来源 | 稳定性 | 权限 |
 * |---|---|---|---|
 * | nodeId `913024617` | 小米穿戴服务（SDK Node.id） | 跨重置、跨换机稳定（11-04 实证） | 无 |
 * | BLE MAC `04:34:C3:06:B8:85` | 系统蓝牙 bonding 列表 | 出厂烧录，跨重置/换机稳定（11-04 实证） | Android 12+ 需 BLUETOOTH_CONNECT |
 * | deviceId（32 位） | 手环 `get_device_id` | **重置就变**，只用于「码内设备段校验」 | 无 |
 *
 * 三者分工刻意不同（见方案 §1.2）：
 *   · MAC / nodeId 回答「这台手环买过没有」——它们不变，才有「重置后自动恢复」；
 *   · deviceId 回答「这张码是不是这台手环的」——它必须变，否则重置后旧码仍然有效。
 *
 * 探针**不阻塞**：拿不到 MAC（未授权/未配对）不影响主流程，nodeId 足够单独判定归属；
 * 两者都拿不到时才算失败，页面会引导用户走 4 位兑换码通道。
 */
final class DeviceProbe {

    /** 广播名关键词：小米手环在系统里的名字形如「Xiaomi Smart Band 9 B885」 */
    private static final String[] BAND_NAME_HINTS = {
            "smart band", "mi band", "xiaomi", "redmi", "小米手环", "手环"
    };

    /** 脱敏/随机地址前缀：02:00:00:00:00:00 是 Android 给未授权应用的占位值 */
    private static final String ZERO_MAC = "020000000000";

    static final class Result {
        /** 12 位大写 hex，无分隔符；空串表示没取到 */
        String mac = "";
        /** 原始形态 04:34:C3:06:B8:85，仅用于界面展示 */
        String macRaw = "";
        /** 小米穿戴服务给的设备 ID */
        String nodeId = "";
        /** 手环上报的设备 ID（重置会变） */
        String deviceId = "";
        String deviceName = "";
        /** true = 有蓝牙权限问题（非致命，页面据此提示一次） */
        boolean macDenied = false;
        /** 命中的地址不是公共地址（可能是随机地址/占位值） */
        boolean macRandom = false;

        /** 有没有可用于「查已购」的稳定标识 */
        boolean hasStableKey() {
            return mac.length() > 0 || nodeId.length() > 0;
        }
    }

    private DeviceProbe() {
    }

    /**
     * 同步收集（bounded：只读缓存与系统 bonding 列表，无扫描、无网络，主线程可调）。
     * 扫描兜底刻意不做——BLE 扫描要 12 秒且要位置权限，而 bonding 列表命中率在实测机型上足够。
     */
    static Result collect(Context c) {
        Result r = new Result();
        SyncEngine eng = SyncEngine.get(c);
        r.nodeId = safe(eng.getNodeId());
        r.deviceId = safe(eng.currentDeviceId());
        r.deviceName = safe(eng.currentDeviceName());
        readBondedMac(c, r);
        return r;
    }

    // ======================= MAC =======================

    private static void readBondedMac(Context c, Result r) {
        BluetoothAdapter ad = null;
        try {
            ad = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
        }
        if (ad == null) {
            return;                                  // 无蓝牙硬件/被系统禁用
        }
        if (Build.VERSION.SDK_INT >= 31 && !hasConnectPermission(c)) {
            r.macDenied = true;                      // 不弹窗、不阻塞，只是这一路没有
            return;
        }
        BluetoothDevice best = null;
        int bestScore = 0;
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded == null) {
                return;
            }
            for (BluetoothDevice d : bonded) {
                if (d == null) {
                    continue;
                }
                int score = scoreOf(d);
                if (score > bestScore) {
                    bestScore = score;
                    best = d;
                }
            }
        } catch (SecurityException se) {
            r.macDenied = true;                      // 权限被撤销（Android 12 上运行时撤回）
            return;
        } catch (Throwable ignored) {
            return;
        }
        if (best == null) {
            return;
        }
        String raw = safe(best.getAddress());
        r.macRaw = raw;
        r.mac = normalizeMac(raw);
        if (r.mac.length() == 0) {
            r.macRandom = true;                      // 占位值 / 随机地址，不作为归属键
        }
    }

    /** 打分而不是「第一个匹配就返回」：一台手机常同时配着手环和耳机，按可信度取最高的那个 */
    private static int scoreOf(BluetoothDevice d) {
        int score = 0;
        String name = "";
        try {
            name = safe(d.getName()).toLowerCase(Locale.ROOT);
        } catch (SecurityException ignored) {
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < BAND_NAME_HINTS.length; i++) {
            if (name.contains(BAND_NAME_HINTS[i])) {
                score += 10;                         // 名字命中
                break;
            }
        }
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null) {
                int dc = bc.getDeviceClass();
                if (dc == 0x0704) {
                    score += 6;                      // WATCH
                } else if ((dc & 0x1F00) == 0x0700) {
                    score += 3;                      // WEARABLE
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
                score += 2;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (normalizeMac(d.getAddress()).length() == 0) {
                score = 0;                           // 地址不可用的设备不参与竞争
            }
        } catch (Throwable ignored) {
        }
        return score;
    }

    /** Android 12+ 的运行时权限：没有它连 getBondedDevices() 都会抛 SecurityException */
    static boolean hasConnectPermission(Context c) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        try {
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 归一成 12 位大写 hex。**不是公共地址就返回空串**：
     *   首字节最低两位为 00 = 公共地址（出厂烧录，不变）
     *   01 = 可解析私有地址（会轮换）；11 = 静态随机地址（开机重生成）
     * 这两种都不能当归属键——今天记住它，明天就对不上了。
     */
    static String normalizeMac(String raw) {
        String s = safe(raw).trim().toUpperCase(Locale.ROOT).replace(":", "").replace("-", "").replace(" ", "");
        if (s.length() != 12) {
            return "";
        }
        for (int i = 0; i < 12; i++) {
            char ch = s.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F');
            if (!hex) {
                return "";
            }
        }
        if (ZERO_MAC.equals(s)) {
            return "";
        }
        int first;
        try {
            first = Integer.parseInt(s.substring(0, 2), 16);
        } catch (Throwable t) {
            return "";
        }
        if ((first & 0x03) != 0) {
            return "";
        }
        return s;
    }

    /** 04:34:C3:06:B8:85 ← 0434C306B885；异常输入原样返回 */
    static String prettyMac(String mac12) {
        String s = safe(mac12);
        if (s.length() != 12) {
            return s;
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s, i, i + 2);
        }
        return sb.toString();
    }

    /** 界面展示用脱敏：只留后 4 位 */
    static String tail(String v) {
        String s = safe(v);
        if (s.length() <= 4) {
            return s;
        }
        return "…" + s.substring(s.length() - 4);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
