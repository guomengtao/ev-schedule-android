package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 标准版 / 高级版身份状态（单点判定）。
 * 方案：docs/安卓端版本身份与功能限制方案.md（Q1/Q2 已确认：UNKNOWN 失败开放、
 * 仅 EV 变体启用门禁、缓存按 deviceId 隔离）。
 *
 * 数据来源（按优先级，均落 SharedPreferences 缓存）：
 *   1. 手环 export scopes:["auth"] 回包 data.auth
 *      （手环端 app.ux SYNC_ACCESS.auth = explicit 只读，需手环 ≥1.7.96）
 *   2. 激活回包（FastActivateActivity：{ok,status,displayStatus,expireAt,months}）
 *
 * 判定规则（顺序）：
 *   未激活 / 已过期            → STANDARD
 *   expireAt>0 且已过          → STANDARD（高级版已到期）
 *   isPermanent                → PREMIUM
 *   其余（拿不到 / 换了手环）   → UNKNOWN（失败开放：不拦功能，只提示"连接手环后自动识别"）
 */
public final class AuthState {

    private AuthState() {
    }

    private static final String PREF = "ev_auth_cache";
    private static final String KEY = "cache";
    private static final String KEY_FETCH_AT = "fetchAt";
    private static final long REFRESH_MIN_MS = 5 * 60 * 1000L;

    private static volatile boolean fetching = false;

    /** 一次判定的结果快照 */
    public static class Snapshot {
        public final boolean known;    // 是否拿到了可信身份
        public final boolean premium;  // true=高级版（含试用中）
        public final String display;   // 设置页/受限页的展示文案

        Snapshot(boolean known, boolean premium, String display) {
            this.known = known;
            this.premium = premium;
            this.display = display;
        }
    }

    // ======================= 对外判定 =======================

    /**
     * 是否处于「标准版限制态」（驱动所有功能门禁）。
     * 仅已知身份且非高级版才拦（失败开放：UNKNOWN 不拦，避免误伤高级版用户）；
     * 仅 EV 变体启用（EvBox 工具箱没有高级版概念）。
     */
    public static boolean isStandardLocked(Context c) {
        Snapshot s = get(c);
        return s.known && !s.premium && Variant.isEv(c);
    }

    /** 身份展示文案（设置页「高级版」行 / 受限页副标题用） */
    public static String displayText(Context c) {
        return get(c).display;
    }

    // ======================= 快照读取与判定 =======================

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读缓存并判定：到期即降级；换了手环（deviceId 不一致）视为未知，重新识别 */
    public static Snapshot get(Context c) {
        try {
            String raw = pref(c).getString(KEY, "");
            if (raw.length() == 0) {
                return new Snapshot(false, false, "连接手环后自动识别");
            }
            JSONObject o = new JSONObject(raw);
            String cur = SyncEngine.get(c).currentDeviceId();
            String dev = o.optString("deviceId", "");
            if (cur.length() > 0 && dev.length() > 0 && !cur.equals(dev)) {
                return new Snapshot(false, false, "连接手环后自动识别");
            }
            boolean activated = o.optBoolean("isActivated", false);
            boolean expired = o.optBoolean("isExpired", false);
            long expireAt = o.optLong("expireAt", 0L);
            if (!activated || expired) {
                return new Snapshot(true, false, "标准版 · 点击升级");
            }
            if (expireAt > 0 && System.currentTimeMillis() > expireAt) {
                return new Snapshot(true, false, "高级版已到期 · 点击续期");
            }
            boolean permanent = o.optBoolean("isPermanent", false);
            String expireText = o.optString("expireText", "");
            String disp = permanent ? "高级版 · 永久有效"
                    : ("高级版" + (expireText.length() > 0 ? (" · " + expireText) : ""));
            return new Snapshot(true, true, disp);
        } catch (Throwable t) {
            return new Snapshot(false, false, "连接手环后自动识别");
        }
    }

    // ======================= 写入 =======================

    /** 手环 export(scopes:["auth"]) 回包的 data.auth 落库 */
    public static void applyWatchAuth(Context c, JSONObject auth) {
        try {
            JSONObject o = new JSONObject(auth.toString());
            o.put("deviceId", SyncEngine.get(c).currentDeviceId());
            o.put("fetchedAt", System.currentTimeMillis());
            save(c, o);
        } catch (Throwable ignored) {
        }
    }

    /** 激活回包落库（FastActivateActivity：{ok,status,displayStatus,expireAt,months}） */
    public static void applyActivateReply(Context c, JSONObject reply) {
        try {
            JSONObject o = new JSONObject();
            String status = reply.optString("status", "basic");
            o.put("status", status);
            o.put("displayStatus", reply.optString("displayStatus", "高级版"));
            o.put("isActivated", reply.optBoolean("ok", false));
            o.put("isPermanent", "permanent".equals(status));
            o.put("isExpired", false);
            o.put("isTrial", false);
            o.put("expireAt", reply.optLong("expireAt", 0L));
            o.put("deviceId", SyncEngine.get(c).currentDeviceId());
            o.put("fetchedAt", System.currentTimeMillis());
            save(c, o);
        } catch (Throwable ignored) {
        }
    }

    private static void save(Context c, JSONObject o) {
        try {
            pref(c).edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 从手环刷新（5 分钟节流） =======================

    /**
     * 已连接手环时拉一次授权状态。失败静默、维持失败开放；
     * 手环 <1.7.96 回包无 auth 字段 → 不落库，身份保持 UNKNOWN。
     */
    public static void maybeRefreshFromWatch(Context c) {
        if (fetching) {
            return;
        }
        final Context app = c.getApplicationContext();
        long last = pref(app).getLong(KEY_FETCH_AT, 0L);
        long now = System.currentTimeMillis();
        if (now - last < REFRESH_MIN_MS) {
            return;
        }
        SyncEngine e = SyncEngine.get(app);
        if (!e.connected()) {
            return;
        }
        fetching = true;
        pref(app).edit().putLong(KEY_FETCH_AT, now).apply();
        e.exportAuth(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                fetching = false;
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    JSONObject auth = (d == null) ? null : d.optJSONObject("auth");
                    if (auth != null) {
                        applyWatchAuth(app, auth);
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override public void onTimeout(String hint) {
                fetching = false;
            }

            @Override public void onError(String msg) {
                fetching = false;
            }
        });
    }

    // ======================= 统一升级弹窗 =======================

    /**
     * 标准版功能受限时的统一升级弹窗（所有门禁共用一份文案，跳「高级版一键激活」）。
     * 注意文案里带自救指引：老客户端（≤0.5.159）安装时若弹「选择打开方式」要选系统安装器。
     */
    public static void showUpgradeDialog(final Activity a) {
        Dialogs.confirm(a, 0, Ui.WARN, "升级高级版",
                "此功能属于高级版。升级后可使用导入课程、首页设置等全部功能。\n\n"
                        + "安装升级包时若弹出「选择打开方式」，请选「软件包安装程序 / Package Installer」。",
                null, "去升级", false, new Dialogs.Action() {
                    @Override public void run() {
                        a.startActivity(new Intent(a, FastActivateActivity.class));
                    }
                });
    }
}
