package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

/**
 * 云同步接口层（P1）——与 {@link CloudSyncStore} 配对：
 * 队列负责「什么时候推」，这里只负责「怎么调」。
 *
 * 设计约束（见 docs/云同步与网页编辑-P1落地计划-20261120.md §4）：
 * 1. 统一前缀 /api/sched，鉴权走 Authorization: Bearer <token>；
 * 2. 全部异步、全部吞异常，回调里 code < 0 即「这次没成功」，调用方自行决定退避；
 * 3. 不引第三方库，复用 {@link Net}；
 * 4. 409 不是失败，是「云端更新了」——body 里带最新 rev/payload，由调用方决定合并。
 */
public final class CloudApi {

    private static final String API = Net.BASE + "/api/sched";

    /** 每张课表一个 token（/publish 下发），key = guid。 */
    private static final String PREF = "ev_cloud_tokens";

    private CloudApi() {
    }

    public interface Cb {
        /** code<0 网络失败；200 成功；409 冲突；其余按服务端约定 */
        void on(int code, String body);
    }

    /** 便捷：把 Net.Cb 转成这里语义一致的 Cb（其实同一个形状，保留给调用方读得懂）。 */
    private static Net.Cb wrap(final Cb cb) {
        if (cb == null) return null;
        return new Net.Cb() {
            @Override public void on(int code, String body) {
                cb.on(code, body);
            }
        };
    }

    // ---------------- token 存取 ----------------

    public static String token(Context c, String guid) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return sp.getString(guid, "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static void setToken(Context c, String guid, String token) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (token == null || token.length() == 0) {
                sp.edit().remove(guid).apply();
            } else {
                sp.edit().putString(guid, token).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 1. publish：开通某张表的云同步（一次性） ----------------

    /**
     * @param url 回填可复制的只读网址（服务端可能不返回，调用方可自行拼）
     * @param rev 回填云端首次 rev（本地记为基线，后续 If-Match 用）
     */
    public static void publish(Context c, String guid, String watchKey, String watchName,
                               String name, String payload, final PubCb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("watchKey", watchKey == null ? "" : watchKey);
            o.put("watchName", watchName == null ? "" : watchName);
            o.put("name", name == null ? "" : name);
            o.put("payload", payload == null ? "{}" : payload);
        } catch (Throwable ignored) {
        }
        Net.postJson(API + "/publish", o.toString(), new Net.Cb() {
            @Override public void on(int code, String body) {
                int rev = 0;
                String url = "";
                String token = "";
                try {
                    JSONObject r = new JSONObject(body == null ? "{}" : body);
                    rev = r.optInt("rev", 0);
                    url = r.optString("url", "");
                    token = r.optString("token", "");
                } catch (Throwable ignored) {
                }
                if (cb != null) cb.on(code, rev, token, url, body);
            }
        });
    }

    public interface PubCb {
        void on(int code, int rev, String token, String url, String body);
    }

    // ---------------- 2. push：队列消费 ----------------

    /**
     * @param op "upsert" | "delete"
     * @param baseRev 本地记录的云端基线；0 表示云端尚无此表（服务端据此判定「新建」而非冲突）
     */
    public static void push(Context c, String guid, String op, int baseRev, String payload, Cb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("op", op == null ? CloudSyncStore.OP_UPSERT : op);
            o.put("baseRev", baseRev);
            if (payload != null) o.put("payload", payload);
        } catch (Throwable ignored) {
        }
        Net.postJsonAuthed(API + "/push", o.toString(), token(c, guid), wrap(cb));
    }

    // ---------------- 3. pull：拉最新态 ----------------

    public static void pull(Context c, String guid, Cb cb) {
        Net.getAuthed(API + "/pull?guid=" + Uri.encode(guid == null ? "" : guid),
                token(c, guid), wrap(cb));
    }

    // ---------------- 4. state：三级回执 ----------------

    public static void state(Context c, String guid, Cb cb) {
        Net.getAuthed(API + "/state?guid=" + Uri.encode(guid == null ? "" : guid),
                token(c, guid), wrap(cb));
    }

    // ---------------- 5. ack：安卓端已落盘 ----------------

    public static void ack(Context c, String guid, int rev, Cb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("rev", rev);
        } catch (Throwable ignored) {
        }
        Net.postJsonAuthed(API + "/ack", o.toString(), token(c, guid), wrap(cb));
    }

    // ---------------- 6. share：分享开关（默认关） ----------------

    public static void share(Context c, String guid, boolean publicRead, Cb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("publicRead", publicRead);
        } catch (Throwable ignored) {
        }
        Net.postJsonAuthed(API + "/share", o.toString(), token(c, guid), wrap(cb));
    }

    // ---------------- 7. backup：手动备份一份 ----------------

    /** @param reason 约定 manual | auto | pre-push | pre-restore */
    public static void backup(Context c, String guid, String reason, Cb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("reason", reason == null ? "manual" : reason);
        } catch (Throwable ignored) {
        }
        Net.postJsonAuthed(API + "/backup", o.toString(), token(c, guid), wrap(cb));
    }

    // ---------------- 8. versions：历史版本列表 ----------------

    public static void versions(Context c, String guid, int limit, Cb cb) {
        Net.getAuthed(API + "/versions?guid=" + Uri.encode(guid == null ? "" : guid)
                + "&limit=" + Math.max(1, Math.min(100, limit)), token(c, guid), wrap(cb));
    }

    // ---------------- 9. restore：恢复到某版本 ----------------

    public static void restore(Context c, String guid, String versionId, Cb cb) {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("versionId", versionId);
        } catch (Throwable ignored) {
        }
        Net.postJsonAuthed(API + "/restore", o.toString(), token(c, guid), wrap(cb));
    }

    // ---------------- 便于队列消费的小工具 ----------------

    /**
     * 把一次 push 的结果回写到队列：
     * 200 → markSuccess(新 rev)；409 → 也算「推过一个版本」，基线由合并方修正后重新入队；
     * 其余（含 code<0）→ markFailure，进入退避。
     *
     * @return true 表示这次推成功（含 409 已回写）
     */
    public static boolean applyPushResult(Context c, String guid, int code, String body) {
        if (code == 200) {
            int rev = 0;
            try {
                rev = new JSONObject(body == null ? "{}" : body).optInt("rev", 0);
            } catch (Throwable ignored) {
            }
            CloudSyncStore.markSuccess(c, guid, rev);
            return true;
        }
        if (code == 409) {
            int rev = 0;
            try {
                rev = new JSONObject(body == null ? "{}" : body).optInt("rev", 0);
            } catch (Throwable ignored) {
            }
            CloudSyncStore.markSuccess(c, guid, rev);
            return true;
        }
        CloudSyncStore.markFailure(c, guid, "http " + code);
        return false;
    }
}
