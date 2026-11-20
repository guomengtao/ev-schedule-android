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
        /** 墓碑：deleted=true 的旧课程，合并末尾追加回 merged，保证「删了也还在」 */
        public final List<CourseCache.Course> tombstones = new ArrayList<>();
        public int conflicts = 0;
        public boolean localChanged = false;
        public boolean remoteChanged = false;
        /** 手环侧「对不上」的课程数（base 有、手环没有）；大面积出现即判为 id 错配 */
        public int remoteDeleted = 0;
        /** true = 本次合并被安全闸中止，merged 不可用，本地/手环都不许写 */
        public boolean aborted = false;
        public String abortReason = "";
    }

    /** 墓碑化：复制一份打 deleted=true，留在数组里（不物理移除，随时可恢复） */
    private static void tombstone(MergeResult r, CourseCache.Course b) {
        if (b == null) {
            return;
        }
        CourseCache.Course t = new CourseCache.Course();
        t.id = b.id;
        t.name = b.name;
        t.time = b.time;
        t.teacher = b.teacher;
        t.location = b.location;
        t.day = b.day;
        t.updatedAt = System.currentTimeMillis();
        t.deleted = true;
        r.tombstones.add(t);
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
        // 安全闸：大面积 id 错配时整体中止，本地与手环都不写（2026-11-20 事故后加）
        if (r.aborted) {
            finish(cb, false, r.abortReason);
            return;
        }

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
                boolean cleared = false;   // 手环端清空课表标志
                String reason = "";
                try {
                    JSONObject resp = new JSONObject(json);
                    parsed = true;
                    hasOk = resp.has("ok");
                    ok = resp.optBoolean("ok", false);
                    cleared = resp.optBoolean("cleared", false);
                    if (!ok) {
                        reason = resp.optString("reason", "").trim();
                    }
                } catch (Throwable ignored) {
                }
                if (ok) {
                    ScheduleStore.commitSync(ctx, s.id, r.merged, serializeBase(r.merged));
                    if (cleared) {
                        finish(cb, true, "已同步空课表（手环端已清空）");
                    } else {
                        finish(cb, true, r.conflicts > 0
                                ? "已同步（" + r.conflicts + " 处冲突按手机版保留）" : "已同步 ✓");
                    }
                } else if (!parsed) {
                    // 回包连 JSON 都不是：这不是「手环拒绝」，别把排障方向带偏
                    finish(cb, false, "手环回包无法解析，本地已保留");
                } else if (!hasOk) {
                    // 合法 JSON 但无 ok 字段：回包格式不对，同样不是手环明确拒绝
                    finish(cb, false, "手环回包缺 ok 字段，本地已保留");
                } else {
                    // 真拒绝：按 reason 分派文案。旧实现把所有失败一律叫「拒绝了写入」，
                    // 会把「手环没收到数据」和「手环存不下」混为一谈，排障方向被带偏。
                    finish(cb, false, reasonToUserText(reason));
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

    // 手环端回包 reason → 用户可见文案（见手环 app.ux syncHandleImport 的各分支）
    // 原则：说清「手环那边到底怎么了」，别把所有失败都叫「拒绝写入」
    private static String reasonToUserText(String reason) {
        String r = reason == null ? "" : reason.trim();
        if ("no courses".equals(r)) {
            return "手环未收到课程数据（课程为空或格式不匹配），本地已保留";
        }
        if ("convert empty".equals(r)) {
            return "手环端转换课程数据为空，请检查课程字段是否完整，本地已保留";
        }
        if ("read failed".equals(r)) {
            return "手环端读取课表失败，请重启手环 App 后重试，本地已保留";
        }
        if ("backup failed".equals(r)) {
            return "手环端备份失败（存储空间可能不足），本地已保留";
        }
        if ("write failed".equals(r)) {
            return "手环端写入课表失败（存储空间可能不足），本地已保留";
        }
        return r.isEmpty()
                ? "手环拒绝了写入（未返回原因），本地已保留"
                : "手环拒绝了写入：" + r + "，本地已保留";
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
            // ⚠️ 2026-11-20 数据丢失事故后的两条硬约束：
            // ① 删除一律「墓碑化」——把该课以 deleted=true 留在数组里，绝不物理移除。
            //    这样即使判断错了（最典型：手环回传的课程 id 与本地 gen 的对不上，
            //    全部命中 rDel），数据仍在本地可恢复，不会整表少 40 多门。
            // ② 手环侧「删太多」时直接中止合并 —— 大面积消失基本都是 id 错配，
            //    不是用户真删。宁可这次不合并，也不能把课删掉。
            if (rDel) {
                r.remoteChanged = true;
                if (!lDel) {
                    // 本地有、base 有、手环没有 → 不删本地，只打墓碑
                    r.remoteDeleted++;
                    tombstone(r, b);
                    continue;
                }
            }
            if (lDel) {
                r.localChanged = true;  // 本地删除 → 同样留墓碑，不物理移除
                tombstone(r, b);
                continue;
            }
            r.merged.add(mergeCourse(b, l, rem, r));
        }
        // 大面积「手环侧缺失」= 多半是 id 错配，不是用户真删：整体放弃这次合并
        if (!bm.isEmpty() && r.remoteDeleted > 0
                && r.remoteDeleted * 2 >= bm.size() && r.remoteDeleted >= 3) {
            r.aborted = true;
            r.abortReason = "手环上有 " + r.remoteDeleted + " 门课对不上（本地共 "
                    + bm.size() + " 门），判断为 id 错配而非删除，已中止合并，本地课表未改动";
            return r;
        }
        // 2026-11-19 修复「点同步偶尔多冒出一门课」：
        // 上面按 id 做三方合并，前提是同一门课在 base/local/remote 里 id 相同。
        // 但 id 是两端各自生成的：本地加课一个 id，手环侧若重写/补发又会带另一个 id。
        // 这种「同课不同 id」会同时命中 b==null 分支的两侧（一侧取 l、一侧取 rem），
        // 合并结果就是两条一模一样的课，并被 commitSync 写回，越同步越多。
        // 这里按业务键再收一次口：星期 + 课名 + 归一化起止时间（去空白）。
        // 键含课名，所以「同天同时段两门不同课（合班）」不会被误删。
        java.util.LinkedHashMap<String, CourseCache.Course> uniq = new java.util.LinkedHashMap<>();
        for (CourseCache.Course c : r.merged) {
            if (c == null) {
                continue;
            }
            String nm = (c.name == null) ? "" : c.name.trim();
            String tm = (c.time == null) ? "" : c.time.replaceAll("\\s+", "");
            String key = c.day + "|" + nm + "|" + tm;
            if (!uniq.containsKey(key)) {
                uniq.put(key, c);
            }
        }
        r.merged.clear();
        r.merged.addAll(uniq.values());
        r.merged.addAll(r.tombstones);
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
                // 墓碑不发给手环：它只在本机留档，不能把删掉的课又写回去
                if (c == null || c.deleted) {
                    continue;
                }
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
