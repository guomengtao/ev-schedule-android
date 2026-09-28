package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 多课表存储（方案 A 阶段 1）。
 *
 * ★ 与 {@link CourseCache} 的分工：
 *   - ScheduleStore 管「课表列表 + 当前激活哪一套」（本类是唯一真源）
 *   - CourseCache 管「当前激活课表的扁平课程副本」，供桌面插件 / 上课提醒直接读
 *   切换激活课表 / 同步到新课表时，本类负责把激活课表【直写】进 CourseCache，
 *   插件与提醒零改动。
 *
 * ★ ID 规则：
 *   - local 课表：ev_local_ + 时间戳；同步成功后替换为手环侧身份（ev_watch_ + 名称）
 *   - sync 课表：手环侧身份。手环协议暂未下发云端唯一 ID，用「ev_watch_ + 名称」做
 *     去重键（同名 = 同一套），与手环 list_schedules 的名称列表对齐。
 *
 * ⚠️ 所有方法吞异常：存储是锦上添花，绝不能把 App 搞崩。
 */
public final class ScheduleStore {

    private static final String PREF = "ev_schedules";
    private static final String KEY_DATA = "data";

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_SYNC = "sync";

    private ScheduleStore() {
    }

    // ======================= 数据模型 =======================

    public static final class Schedule {
        public String id = "";
        public String name = "";
        public String source = SOURCE_LOCAL; // local | sync
        public long createdAt = 0;
        public long syncedAt = 0;
        /** 假期模式：true = 假期日不排课、调休日按目标星期几换课。**默认开启**（JSON 缺字段也按 true） */
        public boolean holiday = true;
        public final List<CourseCache.Course> courses = new ArrayList<>();

        public boolean isSync() {
            return SOURCE_SYNC.equals(source);
        }

        /** 课表列表页的副行：「仅本机 · 6门课」「来自手环 · 8门课 · 最后同步 xxx」 */
        public String sub() {
            StringBuilder sb = new StringBuilder();
            sb.append(isSync() ? "来自手环" : "仅本机")
              .append(" · ").append(courses.size()).append(" 门课");
            if (isSync() && syncedAt > 0) {
                sb.append(" · 最后同步：").append(CourseCache.ago(syncedAt));
            }
            return sb.toString();
        }
    }

    // ======================= 读 =======================

    /** 全部课表（按 createdAt 升序，先旧后新）；永远非 null */
    public static List<Schedule> list(Context c) {
        List<Schedule> out = new ArrayList<>();
        try {
            JSONObject root = root(c);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(fromJson(o));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String activeId(Context c) {
        try {
            return root(c).optString("activeId", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前激活的课表；没有返回 null */
    public static Schedule active(Context c) {
        String id = activeId(c);
        List<Schedule> all = list(c);
        if (id.length() > 0) {
            for (Schedule s : all) {
                if (id.equals(s.id)) {
                    return s;
                }
            }
        }
        return all.isEmpty() ? null : all.get(all.size() - 1); // 兜底：最新一套
    }

    // ======================= 写 =======================

    /**
     * 首次启动迁移 + 兜底：
     *   1. 已有多课表数据 → 不动
     *   2. 旧版 CourseCache 有缓存 → 迁移为一套 sync 课表并激活
     *   3. 什么都没有 → 载入出厂默认课表（保证首页永远有课表可看）
     * 返回 true = 本次做了初始化。
     */
    public static synchronized boolean ensureInitialized(Context c) {
        try {
            if (!list(c).isEmpty()) {
                return false;
            }
            List<CourseCache.Course> legacy = CourseCache.load(c);
            if (!legacy.isEmpty()) {
                Schedule s = new Schedule();
                s.id = "ev_watch_legacy";
                String n = CourseCache.scheduleName(c);
                s.name = n.length() > 0 ? n : "手环课表";
                s.source = SOURCE_SYNC;
                s.createdAt = CourseCache.savedAt(c);
                s.syncedAt = CourseCache.savedAt(c);
                s.courses.addAll(legacy);
                upsert(c, s);
                setActive(c, s.id);
                return true;
            }
            Schedule def = defaultSchedule(c);
            if (def != null) {
                upsert(c, def);
                setActive(c, def.id);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 新增一套本地课表，返回新 id */
    public static synchronized String addLocal(Context c, String name, List<CourseCache.Course> courses) {
        Schedule s = new Schedule();
        s.id = "ev_local_" + System.currentTimeMillis();
        s.name = (name == null || name.length() == 0) ? "未命名课表" : name;
        s.source = SOURCE_LOCAL;
        s.createdAt = System.currentTimeMillis();
        if (courses != null) {
            s.courses.addAll(courses);
        }
        upsert(c, s);
        return s.id;
    }

    /**
     * 手环同步回一套课表：同 id 更新、新 id 追加；然后自动激活它（最新同步优先）。
     * 顺手直写 CourseCache，让插件/提醒立即跟上。
     *
     * @param watchName 手环侧课表名（list_schedules 的 names[i]；空时退化为「手环课表」）
     * @param dayGrouped 手环 export 的 data.schedule（按天分组原始结构）
     * @return 这套课表的 id
     */
    public static synchronized String upsertFromWatch(Context c, String watchName, JSONArray dayGrouped) {
        String name = (watchName == null || watchName.length() == 0) ? "手环课表" : watchName;
        String id = "ev_watch_" + name;
        Schedule s = find(c, id);
        if (s == null) {
            s = new Schedule();
            s.id = id;
            s.createdAt = System.currentTimeMillis();
        }
        s.name = name;
        s.source = SOURCE_SYNC;
        s.syncedAt = System.currentTimeMillis();
        s.courses.clear();
        s.courses.addAll(CourseCache.flatten(dayGrouped));
        upsert(c, s);
        setActive(c, id);
        return id;
    }

    /** 切换激活课表（直写 CourseCache → 插件/提醒联动刷新） */
    public static synchronized void setActive(Context c, String id) {
        try {
            Schedule s = find(c, id);
            if (s == null) {
                return;
            }
            JSONObject root = root(c);
            root.put("activeId", id);
            saveRoot(c, root);
            if (s.courses.isEmpty()) {
                // 用户显式切到空课表：清掉旧缓存，插件显示「无课」而不是残留旧课表
                CourseCache.clear(c);
                TodayWidgetProvider.refreshAll(c);
                NextWidgetProvider.refreshAll(c);
                WeekWidgetProvider.refreshAll(c);
                Reminders.reschedule(c);
            } else {
                CourseCache.saveFlat(c, s.courses, s.name);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 本地课表已上传到手环：改身份为 sync（id 切换为 ev_watch_ + 名称） */
    public static synchronized void markSynced(Context c, String localId) {
        try {
            Schedule s = find(c, localId);
            if (s == null || s.isSync()) {
                return;
            }
            JSONObject root = root(c);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                return;
            }
            String newId = "ev_watch_" + s.name;
            // 若同名 sync 课表已存在：直接删掉本地记录（手环侧才是真源），避免重复 id
            Schedule dup = find(c, newId);
            if (dup != null) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && !localId.equals(o.optString("id"))) {
                        kept.put(o);
                    }
                }
                root.put("schedules", kept);
                if (localId.equals(root.optString("activeId"))) {
                    root.put("activeId", newId);
                }
                saveRoot(c, root);
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && localId.equals(o.optString("id"))) {
                    o.put("id", newId);
                    o.put("source", SOURCE_SYNC);
                    o.put("syncedAt", System.currentTimeMillis());
                    break;
                }
            }
            if (localId.equals(root.optString("activeId"))) {
                root.put("activeId", newId);
            }
            saveRoot(c, root);
        } catch (Throwable ignored) {
        }
    }

    /** 更新某套课表的课程（首页手动同步刷新用） */
    public static synchronized void updateCourses(Context c, String id, List<CourseCache.Course> courses) {
        Schedule s = find(c, id);
        if (s == null || courses == null) {
            return;
        }
        s.courses.clear();
        s.courses.addAll(courses);
        if (s.isSync()) {
            s.syncedAt = System.currentTimeMillis();
        }
        upsert(c, s);
        if (id.equals(activeId(c))) {
            CourseCache.saveFlat(c, s.courses, s.name);
        }
    }

    /**
     * 删除一套课表。
     *   - 若删的是当前激活课表 → 自动切换到列表里最新的一套
     *   - 若删完列表为空 → 载入出厂默认课表兜底，保证首页永远有课表
     */
    public static synchronized void remove(Context c, String id) {
        try {
            JSONObject root = root(c);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                return;
            }
            JSONArray kept = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    removed = true;
                } else {
                    kept.put(o);
                }
            }
            if (!removed) {
                return;
            }
            root.put("schedules", kept);

            String activeId = root.optString("activeId", "");
            if (id.equals(activeId)) {
                if (kept.length() == 0) {
                    // 全删光：默认课表兜底
                    Schedule def = defaultSchedule(c);
                    if (def != null) {
                        kept.put(toJson(def));
                        root.put("schedules", kept);
                        root.put("activeId", def.id);
                        saveRoot(c, root);
                        CourseCache.saveFlat(c, def.courses, def.name);
                        return;
                    }
                    root.put("activeId", "");
                    CourseCache.clear(c);
                    TodayWidgetProvider.refreshAll(c);
                    NextWidgetProvider.refreshAll(c);
                    WeekWidgetProvider.refreshAll(c);
                    Reminders.reschedule(c);
                } else {
                    // 切到最新一套
                    JSONObject last = kept.optJSONObject(kept.length() - 1);
                    if (last != null) {
                        String newId = last.optString("id");
                        root.put("activeId", newId);
                        saveRoot(c, root);
                        Schedule ns = find(c, newId);
                        if (ns != null) {
                            CourseCache.saveFlat(c, ns.courses, ns.name);
                        }
                        return;
                    }
                }
            }
            saveRoot(c, root);
        } catch (Throwable ignored) {
        }
    }

    // ======================= 内部 =======================

    static Schedule find(Context c, String id) {
        if (id == null) {
            return null;
        }
        for (Schedule s : list(c)) {
            if (id.equals(s.id)) {
                return s;
            }
        }
        return null;
    }

    private static void upsert(Context c, Schedule s) {
        try {
            JSONObject root = root(c);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                arr = new JSONArray();
                root.put("schedules", arr);
            }
            JSONObject jo = toJson(s);
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && s.id.equals(o.optString("id"))) {
                    // JSONArray.put(i, v) 在 API 24+ 可用（minSdk 24 ✓）
                    arr.put(i, jo);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                arr.put(jo);
            }
            saveRoot(c, root);
        } catch (Throwable ignored) {
        }
    }

    /** 当前激活课表的假期模式开关（存在课程表 JSON 的 holiday 字段里，默认 true） */
    public static boolean holidayEnabled(Context c) {
        Schedule s = active(c);
        return s == null || s.holiday;
    }

    /** 开/关当前激活课表的假期模式（设置页的开关写这里） */
    public static void setHolidayEnabled(Context c, boolean on) {
        Schedule s = active(c);
        if (s == null || s.holiday == on) {
            return;
        }
        s.holiday = on;
        upsert(c, s);
    }

    private static JSONObject root(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_DATA, "");
            if (raw.length() > 0) {
                return new JSONObject(raw);
            }
        } catch (Throwable ignored) {
        }
        JSONObject o = new JSONObject();
        try {
            o.put("schedules", new JSONArray());
            o.put("activeId", "");
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static void saveRoot(Context c, JSONObject root) {
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DATA, root.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 读 res/raw/default_schedule.json（出厂默认课表） */
    private static Schedule defaultSchedule(Context c) {
        InputStream in = null;
        try {
            in = c.getResources().openRawResource(R.raw.default_schedule);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            JSONObject o = new JSONObject(new String(bos.toByteArray(), Charset.forName("UTF-8")));
            Schedule s = fromJson(o);
            if (s.id.length() == 0) {
                s.id = "ev_local_default";
            }
            if (s.createdAt == 0) {
                s.createdAt = System.currentTimeMillis();
            }
            return s.courses.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static JSONObject toJson(Schedule s) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("source", s.source);
            o.put("createdAt", s.createdAt);
            o.put("syncedAt", s.syncedAt);
            o.put("holiday", s.holiday);
            JSONArray cs = new JSONArray();
            for (CourseCache.Course co : s.courses) {
                cs.put(CourseCache.toJson(co));
            }
            o.put("courses", cs);
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static Schedule fromJson(JSONObject o) {
        Schedule s = new Schedule();
        try {
            s.id = o.optString("id");
            s.name = o.optString("name");
            s.source = o.optString("source", SOURCE_LOCAL);
            s.createdAt = o.optLong("createdAt", 0);
            s.syncedAt = o.optLong("syncedAt", 0);
            // 假期模式默认开启：旧 JSON / 导入的 JSON 没有这个字段也按 true
            s.holiday = o.optBoolean("holiday", true);
            JSONArray cs = o.optJSONArray("courses");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject co = cs.optJSONObject(i);
                    if (co != null) {
                        s.courses.add(CourseCache.fromJson(co));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }
}
