package com.application.watch.classschedule;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 字段级三方合并同步器（唯一同步入口）。
 *
 * 核心：不按「整张课表」定谁为准，按「每条课、每个字段」三方（base/local/remote）合并。
 *   - 不同课程 → 各自保留
 *   - 同一课程不同字段 → 自动合并（改哪边取哪边）
 *   - 同一课程同一字段且值不同 → 冲突（默认手机优先，冲突数经 {@link MergeResult#conflicts} 暴露）
 *   - 删除 → 删除优先
 */
public final class SyncCoordinator {

    public interface Callback {
        void onDone(boolean ok, String msg);
    }

    public static final class MergeResult {
        public final List<CourseCache.Course> merged = new ArrayList<>();
        public int conflicts = 0;
        public boolean localChanged = false;
        public boolean remoteChanged = false;
    }

    private static boolean running = false;

    private SyncCoordinator() {
    }

    // ======================= 未同步课数 =======================

    /** 当前课表相对 base 快照有几门课未同步（0 = 已同步） */
    public static int unsavedCount(ScheduleStore.Schedule s) {
        if (s == null) {
            return 0;
        }
        return diffCount(parseBase(s.baseJson), alive(s.courses));
    }

    // ======================= 唯一同步入口 =======================

    /**
     * 触发一次完整同步：连上则拉 remote → 三方合并 → 上行合并结果 → 更新 base。
     * 未连接时只置 dirty（交「连接成功」补发），不阻塞。
     */
    public static void syncNow(final Context ctx, final Callback cb) {
        final SyncEngine e = SyncEngine.get(ctx);
        if (!e.hasNode()) {
            ScheduleStore.Schedule s = ScheduleStore.active(ctx);
            if (s != null) {
                ScheduleStore.markDirty(ctx, s.id);
            }
            if (cb != null) {
                cb.onDone(false, "手环未连接，已暂存，连上后自动同步");
            }
            return;
        }
        if (running) {
            if (cb != null) {
                cb.onDone(false, "同步进行中，请稍候");
            }
            return;
        }
        running = true;
        e.export(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
                    if (sch == null) {
                        finish(cb, false, "手环回包里没课表数据");
                        return;
                    }
                    doMerge(ctx, CourseCache.flatten(sch), cb);
                } catch (Throwable t) {
                    finish(cb, false, "回包无法解析");
                }
            }
            @Override public void onTimeout(String hint) { finish(cb, false, hint); }
            @Override public void onError(String msg) { finish(cb, false, "同步失败：" + msg); }
        });
    }

    private static void finish(Callback cb, boolean ok, String msg) {
        running = false;
        if (cb != null) {
            cb.onDone(ok, msg);
        }
    }

    private static void doMerge(final Context ctx, final List<CourseCache.Course> remote,
                                final Callback cb) {
        final ScheduleStore.Schedule s = ScheduleStore.active(ctx);
        if (s == null) {
            finish(cb, false, "没有可同步的课表");
            return;
        }
        final List<CourseCache.Course> base = parseBase(s.baseJson);
        final List<CourseCache.Course> local = alive(s.courses);
        final MergeResult r = merge(base, local, remote);

        if (!r.localChanged) {
            // 只有手环改动（或都没改）：采纳 remote 的合并结果，更新 base
            ScheduleStore.commitSync(ctx, s.id, r.merged, serializeBase(r.merged));
            finish(cb, true, r.remoteChanged ? "已拉取手环改动" : "已是最新，无需同步");
            return;
        }

        // 手机有改动：先落本地显示合并结果（dirty=true），上行成功才更新 base
        ScheduleStore.updateCourses(ctx, s.id, r.merged);
        final String payload = buildImport(r.merged);
        if (payload == null) {
            finish(cb, false, "构造同步报文失败");
            return;
        }
        SyncEngine.get(ctx).send(payload, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                boolean ok = false;
                boolean parsed = false;
                boolean hasOk = false;
                String reason = "";
                try {
                    JSONObject resp = new JSONObject(json);
                    parsed = true;
                    hasOk = resp.has("ok");
                    ok = resp.optBoolean("ok", false);
                    if (!ok) {
                        reason = resp.optString("reason", "").trim();
                    }
                } catch (Throwable ignored) {
                }
                if (ok) {
                    ScheduleStore.commitSync(ctx, s.id, r.merged, serializeBase(r.merged));
                    finish(cb, true, r.conflicts > 0
                            ? "已同步（" + r.conflicts + " 处冲突按手机版保留）" : "已同步 ✓");
                } else if (!parsed) {
                    // 回包连 JSON 都不是：这不是「手环拒绝」，别把排障方向带偏
                    finish(cb, false, "手环回包无法解析，本地已保留");
                } else if (!hasOk) {
                    // 合法 JSON 但无 ok 字段：回包格式不对，同样不是手环明确拒绝
                    finish(cb, false, "手环回包缺 ok 字段，本地已保留");
                } else {
                    // 真拒绝：把手环端给的 reason 透出来（no courses / convert empty /
                    // read failed / backup failed / write failed，见手环 app.ux syncHandleImport）
                    finish(cb, false, reason.isEmpty()
                            ? "手环拒绝了写入（未返回原因），本地已保留"
                            : "手环拒绝了写入：" + reason + "，本地已保留");
                }
            }
            @Override public void onTimeout(String hint) {
                finish(cb, false, "手环无回应，本地已保存，连上后自动重试");
            }
            @Override public void onError(String msg) {
                finish(cb, false, "同步失败：" + msg + "（本地已保存）");
            }
        });
    }

    // ======================= 三方合并 =======================

    public static MergeResult merge(List<CourseCache.Course> base,
                                    List<CourseCache.Course> local,
                                    List<CourseCache.Course> remote) {
        MergeResult r = new MergeResult();
        Map<String, CourseCache.Course> bm = byId(base);
        Map<String, CourseCache.Course> lm = byId(local);
        Map<String, CourseCache.Course> rm = byId(remote);

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(bm.keySet());
        ids.addAll(lm.keySet());
        ids.addAll(rm.keySet());

        for (String id : ids) {
            CourseCache.Course b = bm.get(id);
            CourseCache.Course l = lm.get(id);
            CourseCache.Course rem = rm.get(id);
            boolean lDel = l == null;   // local 里没有 = 本地删了
            boolean rDel = rem == null; // remote 里没有 = 手环删了

            if (b == null) {
                // base 没有：新增。两边新增同 id（罕见）以 local 为准。
                if (lDel && rDel) {
                    continue;
                }
                if (lDel) {
                    r.merged.add(rem);
                    r.remoteChanged = true;
                } else {
                    r.merged.add(l);
                    r.localChanged = true;
                }
                continue;
            }
            // base 里有
            if (lDel && rDel) {
                continue; // 都删
            }
            if (lDel) {
                r.localChanged = true;  // 本地删除（删除优先）
                continue;
            }
            if (rDel) {
                r.remoteChanged = true; // 手环删除（删除优先）
                continue;
            }
            r.merged.add(mergeCourse(b, l, rem, r));
        }
        CourseCache.sortForWeek(r.merged);
        return r;
    }

    /** 三处都有：逐字段合并，冲突手机优先 */
    private static CourseCache.Course mergeCourse(CourseCache.Course b, CourseCache.Course l,
                                                  CourseCache.Course rem, MergeResult r) {
        CourseCache.Course m = new CourseCache.Course();
        m.id = (l.id != null && l.id.length() > 0) ? l.id : rem.id;
        m.ensureId();
        m.name = field(b.name, l.name, rem.name, r);
        m.time = field(b.time, l.time, rem.time, r);
        m.teacher = field(b.teacher, l.teacher, rem.teacher, r);
        m.location = field(b.location, l.location, rem.location, r);
        m.day = field(b.day, l.day, rem.day, r);
        m.updatedAt = System.currentTimeMillis();
        if (!courseEquals(l, b)) {
            r.localChanged = true;
        }
        if (!courseEquals(rem, b)) {
            r.remoteChanged = true;
        }
        return m;
    }

    private static String field(String bv, String lv, String rv, MergeResult r) {
        String b = bv == null ? "" : bv;
        String l = lv == null ? "" : lv;
        String rr = rv == null ? "" : rv;
        boolean lc = !l.equals(b);
        boolean rc = !rr.equals(b);
        if (!lc && !rc) {
            return b;
        }
        if (lc && !rc) {
            return l;
        }
        if (!lc) {
            return rr;
        }
        if (l.equals(rr)) {
            return l;
        }
        r.conflicts++;   // 同字段两边都改且值不同 → 冲突，手机优先
        return l;
    }

    private static int field(int bv, int lv, int rv, MergeResult r) {
        boolean lc = lv != bv;
        boolean rc = rv != bv;
        if (!lc && !rc) {
            return bv;
        }
        if (lc && !rc) {
            return lv;
        }
        if (!lc) {
            return rv;
        }
        if (lv == rv) {
            return lv;
        }
        r.conflicts++;
        return lv;
    }

    // ======================= 工具 =======================

    private static boolean courseEquals(CourseCache.Course a, CourseCache.Course b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.day == b.day
                && eq(a.name, b.name)
                && eq(a.time, b.time)
                && eq(a.teacher, b.teacher)
                && eq(a.location, b.location);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static int diffCount(List<CourseCache.Course> base, List<CourseCache.Course> local) {
        Map<String, CourseCache.Course> bm = byId(base);
        Map<String, CourseCache.Course> lm = byId(local);
        int n = 0;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(bm.keySet());
        ids.addAll(lm.keySet());
        for (String id : ids) {
            CourseCache.Course b = bm.get(id);
            CourseCache.Course l = lm.get(id);
            if (b == null && l == null) {
                continue;
            }
            if (b == null || l == null || !courseEquals(b, l)) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, CourseCache.Course> byId(List<CourseCache.Course> list) {
        Map<String, CourseCache.Course> m = new LinkedHashMap<>();
        if (list == null) {
            return m;
        }
        for (CourseCache.Course c : list) {
            if (c == null) {
                continue;
            }
            c.ensureId();
            m.put(c.id, c);
        }
        return m;
    }

    private static List<CourseCache.Course> alive(List<CourseCache.Course> list) {
        List<CourseCache.Course> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (CourseCache.Course c : list) {
            if (c != null && !c.deleted) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<CourseCache.Course> parseBase(String baseJson) {
        List<CourseCache.Course> out = new ArrayList<>();
        if (baseJson == null || baseJson.length() == 0) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(baseJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(CourseCache.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String serializeBase(List<CourseCache.Course> courses) {
        try {
            JSONArray arr = new JSONArray();
            for (CourseCache.Course c : courses) {
                arr.put(CourseCache.toJson(c));
            }
            return arr.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 合并结果 → import 报文（带 id，手环透传保留，闭环对齐） */
    private static String buildImport(List<CourseCache.Course> courses) {
        try {
            JSONArray arr = new JSONArray();
            for (CourseCache.Course c : courses) {
                c.ensureId();
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("day", c.day + 1); // 0-6 → 1-7（import 协议认 1-7）
                o.put("time", c.time);
                o.put("teacher", c.teacher);
                o.put("location", c.location);
                arr.put(o);
            }
            JSONObject payload = new JSONObject();
            payload.put("courses", arr);
            JSONObject root = new JSONObject();
            root.put("action", "import");
            root.put("payload", payload);
            return root.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
