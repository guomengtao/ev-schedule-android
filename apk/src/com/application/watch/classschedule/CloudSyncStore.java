package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 云端待推送队列（落盘，杀进程不丢）。
 *
 * <p>为什么单独一份存储、而不复用 Schedule.dirty：
 * dirty 只表达「有过改动」，无法表达「推给谁、基于哪个 rev、重试到第几次」。而静默上传必须能
 * 在断网时无限积压、联网时按序补推、失败后指数退避，且全程不打扰用户 —— 这些都是队列语义。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>同一 guid 已有 upsert 待推时，新改动<b>覆盖</b>旧项（只推最新态），但 baseRev 保留最早的那个，
 *       否则乐观锁会拿到一个「比云端还新」的基线，冲突判定失效；</li>
 *   <li>只有开启过云同步（cloudEnabled）的课表才允许入队，其余表零请求；</li>
 *   <li>任何失败都不弹提示，只在这个文件里累加 attempts / nextAt。</li>
 * </ol>
 */
public final class CloudSyncStore {

    private static final String PREF = "ev_cloud_queue";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_STATE = "state";

    public static final String OP_UPSERT = "upsert";
    public static final String OP_DELETE = "delete";

    /** 退避阶梯：30s → 2min → 10min → 1h（到顶后按 1h 重试，不放弃） */
    private static final long[] BACKOFF_MS = {30_000L, 120_000L, 600_000L, 3_600_000L};

    private CloudSyncStore() {
    }

    /** 队列表项 */
    public static final class Item {
        public String guid = "";
        public String op = OP_UPSERT;
        /** 本次改动基于的云端 rev；-1 表示「云端尚无此表」 */
        public int baseRev = -1;
        /** 整张课表快照 JSON（{"courses":[...]}）；OP_DELETE 时为空 */
        public String payload = "";
        public int attempts = 0;
        public long nextAt = 0;
        public long enqueuedAt = 0;

        boolean due(long now) {
            return nextAt <= now;
        }
    }

    /** 每张表的云端状态（供「云端状态」行与三级回执使用） */
    public static final class State {
        public int lastPushRev = 0;
        public int lastAckRev = 0;
        public String lastError = "";
        public long lastTryAt = 0;
        public long lastOkAt = 0;
    }

    // ======================= 入队 =======================

    /** 课表有本地改动：入队或覆盖同 guid 的待推项 */
    public static synchronized void enqueueUpsert(Context c, String guid, int baseRev, String payload) {
        if (TextUtils.isEmpty(guid) || TextUtils.isEmpty(payload)) {
            return;
        }
        JSONObject root = load(c);
        JSONArray items = itemsOf(root);
        try {
            int hit = indexOf(items, guid);
            if (hit >= 0) {
                // 覆盖内容，保留最早基线（见类注释规则 1），失败计数清零重新计
                JSONObject old = items.getJSONObject(hit);
                int keepBase = old.optInt("baseRev", baseRev);
                JSONObject o = new JSONObject();
                o.put("guid", guid);
                o.put("op", OP_UPSERT);
                o.put("baseRev", keepBase);
                o.put("payload", payload);
                o.put("attempts", 0);
                o.put("nextAt", 0);
                o.put("enqueuedAt", old.optLong("enqueuedAt", System.currentTimeMillis()));
                items.put(hit, o);
            } else {
                JSONObject o = new JSONObject();
                o.put("guid", guid);
                o.put("op", OP_UPSERT);
                o.put("baseRev", baseRev);
                o.put("payload", payload);
                o.put("attempts", 0);
                o.put("nextAt", 0);
                o.put("enqueuedAt", System.currentTimeMillis());
                items.put(o);
            }
            save(c, root);
        } catch (Throwable ignored) {
        }
    }

    /** 课表被本地删除：入队 delete（云端写墓碑，不是物理删） */
    public static synchronized void enqueueDelete(Context c, String guid, int baseRev) {
        if (TextUtils.isEmpty(guid)) {
            return;
        }
        JSONObject root = load(c);
        JSONArray items = itemsOf(root);
        try {
            int hit = indexOf(items, guid);
            JSONObject o = new JSONObject();
            o.put("guid", guid);
            o.put("op", OP_DELETE);
            o.put("baseRev", baseRev);
            o.put("payload", "");
            o.put("attempts", 0);
            o.put("nextAt", 0);
            o.put("enqueuedAt", System.currentTimeMillis());
            if (hit >= 0) {
                items.put(hit, o);
            } else {
                items.put(o);
            }
            save(c, root);
        } catch (Throwable ignored) {
        }
    }

    // ======================= 出队 / 读 =======================

    /** 取出下一个到期项；没有返回 null */
    public static synchronized Item nextDue(Context c) {
        JSONArray items = itemsOf(load(c));
        long now = System.currentTimeMillis();
        Item best = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Item it = read(o);
            if (!it.due(now)) {
                continue;
            }
            if (best == null || it.enqueuedAt < best.enqueuedAt) {
                best = it;
            }
        }
        return best;
    }

    /** 全部待推项（调试 / 「云端状态」行展示用） */
    public static synchronized List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        JSONArray items = itemsOf(load(c));
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) {
                out.add(read(o));
            }
        }
        return out;
    }

    public static synchronized int pendingCount(Context c) {
        return itemsOf(load(c)).length();
    }

    // ======================= 结果回写 =======================

    /** 推送成功：移除该项，记录 rev */
    public static synchronized void markSuccess(Context c, String guid, int rev) {
        JSONObject root = load(c);
        JSONArray items = itemsOf(root);
        try {
            int hit = indexOf(items, guid);
            if (hit >= 0) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    if (i != hit) {
                        kept.put(items.get(i));
                    }
                }
                root.put(KEY_ITEMS, kept);
            }
            State st = state(c, guid);
            st.lastPushRev = rev;
            st.lastAckRev = Math.max(st.lastAckRev, rev);
            st.lastError = "";
            st.lastOkAt = System.currentTimeMillis();
            st.lastTryAt = st.lastOkAt;
            putState(root, guid, st);
            save(c, root);
        } catch (Throwable ignored) {
        }
    }

    /** 推送失败：累加 attempts、按阶梯推迟；永不打扰用户 */
    public static synchronized void markFailure(Context c, String guid, String error) {
        JSONObject root = load(c);
        JSONArray items = itemsOf(root);
        try {
            int hit = indexOf(items, guid);
            if (hit >= 0) {
                JSONObject o = items.getJSONObject(hit);
                int attempts = o.optInt("attempts", 0) + 1;
                long wait = BACKOFF_MS[Math.min(attempts - 1, BACKOFF_MS.length - 1)];
                o.put("attempts", attempts);
                o.put("nextAt", System.currentTimeMillis() + wait);
                items.put(hit, o);
            }
            State st = state(c, guid);
            st.lastError = error == null ? "" : error;
            st.lastTryAt = System.currentTimeMillis();
            putState(root, guid, st);
            save(c, root);
        } catch (Throwable ignored) {
        }
    }

    /** acK 上限：安卓端已落盘到本地的最高 rev（网页端「已同步到安卓」用） */
    public static synchronized void markAcked(Context c, String guid, int rev) {
        JSONObject root = load(c);
        State st = state(c, guid);
        st.lastAckRev = Math.max(st.lastAckRev, rev);
        putState(root, guid, st);
        save(c, root);
    }

    public static synchronized State state(Context c, String guid) {
        State st = new State();
        try {
            JSONObject states = load(c).optJSONObject(KEY_STATE);
            if (states == null) {
                return st;
            }
            JSONObject o = states.optJSONObject(guid);
            if (o == null) {
                return st;
            }
            st.lastPushRev = o.optInt("lastPushRev", 0);
            st.lastAckRev = o.optInt("lastAckRev", 0);
            st.lastError = o.optString("lastError", "");
            st.lastTryAt = o.optLong("lastTryAt", 0);
            st.lastOkAt = o.optLong("lastOkAt", 0);
        } catch (Throwable ignored) {
        }
        return st;
    }

    public static synchronized void clear(Context c) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit();
        } catch (Throwable ignored) {
        }
    }

    // ======================= 内部 =======================

    private static JSONObject load(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_ITEMS, "");
            String rawState = sp.getString(KEY_STATE, "");
            JSONObject root = new JSONObject();
            JSONArray items = new JSONArray();
            if (!TextUtils.isEmpty(raw)) {
                try {
                    items = new JSONArray(raw);
                } catch (Throwable ignored) {
                }
            }
            JSONObject states = new JSONObject();
            if (!TextUtils.isEmpty(rawState)) {
                try {
                    states = new JSONObject(rawState);
                } catch (Throwable ignored) {
                }
            }
            root.put(KEY_ITEMS, items);
            root.put(KEY_STATE, states);
            return root;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void save(Context c, JSONObject root) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
              .putString(KEY_ITEMS, root.optJSONArray(KEY_ITEMS) == null
                      ? "[]" : root.optJSONArray(KEY_ITEMS).toString())
              .putString(KEY_STATE, root.optJSONObject(KEY_STATE) == null
                      ? "{}" : root.optJSONObject(KEY_STATE).toString())
              .commit();
        } catch (Throwable ignored) {
        }
    }

    private static JSONArray itemsOf(JSONObject root) {
        JSONArray items = root.optJSONArray(KEY_ITEMS);
        return items == null ? new JSONArray() : items;
    }

    private static int indexOf(JSONArray items, String guid) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && guid.equals(o.optString("guid", ""))) {
                return i;
            }
        }
        return -1;
    }

    private static Item read(JSONObject o) {
        Item it = new Item();
        it.guid = o.optString("guid", "");
        it.op = o.optString("op", OP_UPSERT);
        it.baseRev = o.optInt("baseRev", -1);
        it.payload = o.optString("payload", "");
        it.attempts = o.optInt("attempts", 0);
        it.nextAt = o.optLong("nextAt", 0);
        it.enqueuedAt = o.optLong("enqueuedAt", 0);
        return it;
    }

    private static void putState(JSONObject root, String guid, State st) {
        try {
            JSONObject states = root.optJSONObject(KEY_STATE);
            if (states == null) {
                states = new JSONObject();
                root.put(KEY_STATE, states);
            }
            JSONObject o = new JSONObject();
            o.put("lastPushRev", st.lastPushRev);
            o.put("lastAckRev", st.lastAckRev);
            o.put("lastError", st.lastError == null ? "" : st.lastError);
            o.put("lastTryAt", st.lastTryAt);
            o.put("lastOkAt", st.lastOkAt);
            states.put(guid, o);
        } catch (Throwable ignored) {
        }
    }
}
