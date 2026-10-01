package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 手环连接尝试日志（本地调试数据）。
 *
 * 每次连接结束（SyncEngine.finish）写一条：记录时间、手环型号、成功/失败、
 * 失败步（1-4）与原因、成功时的 EV 版本。仅供用户在「连接日志」页查看，
 * 不参与埋点上报（与 Stats 的聚合计数分工：Stats 出统计，ConnLog 出明细）。
 *
 * 设计约束：与 Stats 一致——绝不抛异常，坏了不影响主流程；新记录放数组头、上限 {@link #MAX}。
 * 存储专用 SharedPreferences("ev_conn_log")，键 "log" = JSON 数组字符串。
 */
final class ConnLog {

    private static final String PREF = "ev_conn_log";
    private static final String KEY = "log";
    private static final int MAX = 300;

    private ConnLog() {
    }

    /** 一次连接尝试的结果明细。 */
    static final class Entry {
        long ts;             // 尝试时间（epoch ms）
        boolean ok;          // 是否连接成功
        String model;        // 手环型号（如「小米手环9 NFC版」，来自 Node.name）
        String nodeId;       // XMS nodeId
        int step;            // 失败步 1-4；成功为 0
        String reason;       // 失败原因 / 成功时为空
        String versionName;  // 成功时 EV 快应用版本
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 追加一条连接尝试记录（新记录在最前）。 */
    static void record(Context c, boolean ok, String model, String nodeId,
                       int step, String reason, String versionName) {
        try {
            JSONArray next = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("ok", ok);
            o.put("model", nz(model));
            o.put("node", nz(nodeId));
            o.put("step", step);
            o.put("reason", cap(nz(reason)));
            o.put("ver", nz(versionName));
            next.put(o);
            JSONArray old = read(sp(c));
            for (int i = 0; i < old.length() && next.length() < MAX; i++) {
                next.put(old.opt(i));
            }
            sp(c).edit().putString(KEY, next.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 全部记录（新→旧）。 */
    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = read(sp(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.ts = o.optLong("ts");
                e.ok = o.optBoolean("ok");
                e.model = o.optString("model");
                e.nodeId = o.optString("node");
                e.step = o.optInt("step");
                e.reason = o.optString("reason");
                e.versionName = o.optString("ver");
                out.add(e);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 记录中出现过的手环型号（按首次出现顺序去重），用于「按型号筛选」。 */
    static List<String> models(Context c) {
        Set<String> set = new LinkedHashSet<>();
        for (Entry e : list(c)) {
            if (e.model != null && e.model.length() > 0) {
                set.add(e.model);
            }
        }
        return new ArrayList<>(set);
    }

    /** 清空全部记录。 */
    static void clear(Context c) {
        try {
            sp(c).edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray read(SharedPreferences p) {
        JSONArray arr = new JSONArray();
        try {
            String s = p.getString(KEY, "");
            if (s != null && s.length() > 0) {
                arr = new JSONArray(s);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cap(String s) {
        if (s.length() > 200) {
            s = s.substring(0, 200);
        }
        return s;
    }
}