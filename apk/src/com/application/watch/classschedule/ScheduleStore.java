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
 * ★ ID 规则（多设备强隔离，2026-10-02 升级）：
 *   - local 课表：ev_local_ + 时间戳；deviceId 留空（不与任何手环绑定）。
 *   - sync 课表：ev_watch_<deviceId>::<名称> 作为唯一路由键，去重判据 = (deviceId, 名称)
 *     二元组。这样两台手环上同名的课表是两份独立 Schedule，永不互相覆盖。
 *   - 兼容：升级前老数据 id 为 ev_watch_<名称>（无 "::"），归 deviceId="legacy-unknown"；
 *     拿到真实 deviceId 后的下一次同步会就地把老记录升级为新 id（见 upsertFromWatch）。
 *   - 手环本地课表按名称管理（单机），多设备隔离完全在安卓侧：安卓把「设备A的课表」
 *     与「设备B的课表」存成两份，分别 import 回各自连接的设备（BLE 路由分开）。
 *
 * ⚠️ 所有方法吞异常（绝不能把 App 搞崩），但不再「无声失败」：
 *   - 读到损坏数据 → 备份原始内容到 KEY_CORRUPT + 置 lastReadCorrupt（UI 层提示后复位）
 *   - 写盘异常 → 置 lastWriteFailed（调用方可感知）
 */
public final class ScheduleStore {

    private static final String PREF = "ev_schedules";
    private static final String KEY_DATA = "data";
    /** 存储损坏时原始内容的备份 key（只备份一次，供人工找回；App 侧重新初始化） */
    private static final String KEY_CORRUPT = "corrupt_backup";

    /** 最近一次读取发现存储损坏并已备份重建（UI 层读后应提示用户并复位） */
    public static volatile boolean lastReadCorrupt = false;
    /** 最近一次写盘失败（SharedPreferences.apply 异步，这里只标记提交异常；UI 层可提示） */
    public static volatile boolean lastWriteFailed = false;

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_SYNC = "sync";

    private ScheduleStore() {
    }

    // ======================= 数据模型 =======================

    public static final class Schedule {
        public String id = "";
        public String name = "";
        public String source = SOURCE_LOCAL; // local | sync
        /** 归属手环的设备 ID（sync 课表必有；local 课表为空）。强隔离路由键的「设备」维。
         *  真实值 = 手环 get_device_id 返回的 32 位 ID；拿不到时 fallback 为 "legacy-unknown"。 */
        public String deviceId = "";
        /** 归属手环展示名（连接时缓存的 deviceName，如「小米手环 10 Pro」），仅用于显示 */
        public String deviceName = "";
        public long createdAt = 0;
        public long syncedAt = 0;
        /** 假期模式：true = 假期日不排课、调休日按目标星期几换课。**默认开启**（JSON 缺字段也按 true） */
        public boolean holiday = true;
        /** 上次同步完成时的课程表快照（三方合并的 base）；空 = 从未同步过 */
        public String baseJson = "";
        /** 是否有未同步改动（缓存值；真判据靠 base 对比，见 SyncCoordinator） */
        public boolean dirty = false;
        public final List<CourseCache.Course> courses = new ArrayList<>();

        public boolean isSync() {
            return SOURCE_SYNC.equals(source);
        }

        /** 课表列表页的副行：「仅本机 · 6门课」「来自手环 · 小米手环10 Pro··3f2a · 8门课 · 最后同步 xxx」
         *  sync 课表副行带设备名 + deviceId 后 4 位掩码，用户一眼分清属于哪台手环。 */
        public String sub() {
            StringBuilder sb = new StringBuilder();
            sb.append(isSync() ? "来自手环" : "仅本机")
              .append(" · ").append(courses.size()).append(" 门课");
            if (isSync() && deviceName != null && deviceName.length() > 0) {
                sb.append(" · ").append(deviceName);
                if (deviceId != null && deviceId.length() >= 4) {
                    sb.append("··").append(deviceId.substring(deviceId.length() - 4));
                }
            }
            if (isSync() && syncedAt > 0) {
                sb.append(" · 最后同步：").append(CourseCache.ago(syncedAt));
            }
            return sb.toString();
        }
    }

    // ======================= 读 =======================

    /** sync 课表 id 生成（多设备强隔离路由键）：ev_watch_<deviceId>::<名称>。
     *  deviceId 为空时退化为 "legacy-unknown"（升级前/离线拿不到设备 ID 的情况）。 */
    public static String watchId(String deviceId, String name) {
        String d = (deviceId == null) ? "" : deviceId;
        String n = (name == null || name.length() == 0) ? "手环课表" : name;
        return "ev_watch_" + (d.isEmpty() ? "legacy-unknown" : d) + "::" + n;
    }

    /** 按 (设备, 课表名) 定位一套 sync 课表；deviceId 为空时兼容升级前老格式 ev_watch_<名称>。
     *  用于列表渲染与「本机已存」判重，避免出现跨设备误匹配。 */
    public static Schedule findByDeviceName(Context c, String deviceId, String name) {
        Schedule hit = find(c, watchId(deviceId, name));
        if (hit != null) {
            return hit;
        }
        Schedule legacy = find(c, "ev_watch_" + (name == null ? "" : name));
        return legacy;
    }

    /** 连接落定、拿到真实设备 ID 后，把升级前遗留的 sync 课表（deviceId 空或 "legacy-unknown"）
     *  归位到当前设备：re-id 为 ev_watch_<deviceId>::<名称>，并按 name 消重（新 id 已存在则删旧的）。
     *  只跑一次（deviceId 非空才生效），避免把「设备A的课表」误并到「设备B」。 */
    public static synchronized void migrateLegacyToDevice(Context c, String devId, String devName) {
        if (devId == null || devId.isEmpty()) {
            return;
        }
        try {
            JSONObject root = root(c);
            JSONArray arr = root.optJSONArray("schedules");
            if (arr == null) {
                return;
            }
            JSONArray deleteIds = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !SOURCE_SYNC.equals(o.optString("source", ""))) {
                    continue;
                }
                String did = o.optString("deviceId", "");
                if (!did.isEmpty() && !"legacy-unknown".equals(did)) {
                    continue; // 已是某真实设备，不动
                }
                String name = o.optString("name", "");
                String newId = watchId(devId, name);
                if (find(c, newId) != null && !newId.equals(o.optString("id"))) {
                    deleteIds.put(o.optString("id")); // 新 id 已存在 → 删这条旧的
                    continue;
                }
                o.put("id", newId);
                o.put("deviceId", devId);
                o.put("deviceName", devName == null ? "" : devName);
                changed = true;
            }
            if (deleteIds.length() > 0) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    String id = (o == null) ? "" : o.optString("id", "");
                    boolean drop = false;
                    for (int k = 0; k < deleteIds.length(); k++) {
                        if (deleteIds.optString(k).equals(id)) {
                            drop = true;
                            break;
                        }
                    }
                    if (!drop) {
                        kept.put(o);
                    }
                }
                root.put("schedules", kept);
                changed = true;
            }
            if (changed) {
                saveRoot(c, root);
            }
        } catch (Throwable ignored) {
        }
    }

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
     * 手环同步回一套课表（多设备强隔离版）：以 (deviceId, 名称) 为唯一路由键，
     * 只写「这台设备」的这份，绝不碰别的设备的课表。连接建立后 deviceId 已由
     * SyncEngine 缓存（get_device_id）；拿不到时为 "legacy-unknown"，离线也能入库。
     *
     * 兼容升级前数据：老格式 id 为 ev_watch_<名称>（无 "::"、deviceId 为空），
     * 本方法在拿到真实 deviceId 后会就地把那条老记录升级为新 id，不重复建副本。
     *
     * 激活跟随策略（防跨设备抢位）：仅当当前激活的也是【同一台设备】的 sync 课表时，
     * 才跟随切换到最新同步的这套；否则只更新数据、不抢激活（避免偶发同步晃掉常连设备的当前课表）。
     *
     * @param deviceId   手环设备 ID（SyncEngine.currentDeviceId()，可能为空→legacy-unknown）
     * @param deviceName 手环展示名（SyncEngine.currentDeviceName()）
     * @param watchName  手环侧课表名（list_schedules 的 names[i]；空时退化为「手环课表」）
     * @param dayGrouped 手环 export 的 data.schedule（按天分组原始结构）
     * @return 这套课表的 id
     */
    public static synchronized String upsertFromWatch(Context c, String deviceId, String deviceName,
                                                     String watchName, JSONArray dayGrouped) {
        String name = (watchName == null || watchName.length() == 0) ? "手环课表" : watchName;
        String dev = (deviceId == null) ? "" : deviceId;
        String id = watchId(dev, name);
        Schedule s = find(c, id);
        if (s == null && dev.length() > 0) {
            // 已知设备 + 老数据可能以旧格式（ev_watch_<名称>）或 legacy-unknown 形式存过 → 就地升级它
            Schedule old = find(c, "ev_watch_" + name);
            if (old == null) {
                old = find(c, watchId("legacy-unknown", name));
            }
            if (old != null && (old.deviceId == null || old.deviceId.isEmpty()
                    || "legacy-unknown".equals(old.deviceId))) {
                s = old;
            }
        }
        boolean upgradeLegacy = (s != null && (s.deviceId == null || s.deviceId.isEmpty()));
        if (s == null) {
            s = new Schedule();
            s.createdAt = System.currentTimeMillis();
        }
        s.id = id;
        s.name = name;
        s.source = SOURCE_SYNC;
        s.deviceId = dev.isEmpty() ? "legacy-unknown" : dev;
        s.deviceName = (deviceName == null) ? "" : deviceName;
        s.syncedAt = System.currentTimeMillis();
        s.courses.clear();
        s.courses.addAll(CourseCache.flatten(dayGrouped));
        upsert(c, s);
        // 激活跟随：仅同一设备的 sync 课表才跟随（upgradeLegacy 的说明见上方注释）
        Schedule cur = active(c);
        if (cur == null || (cur.isSync() && s.deviceId.equals(cur.deviceId))) {
            setActive(c, id);
        }
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

    /** 本地课表已上传到手环：改身份为 sync，id 切换为 ev_watch_<deviceId>::<名称>（带设备维度）。
     *  @param deviceId   目标手环设备 ID（SyncEngine.currentDeviceId()）
     *  @param deviceName 目标手环展示名 */
    public static synchronized void markSynced(Context c, String localId, String deviceId, String deviceName) {
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
            String dev = (deviceId == null) ? "" : deviceId;
            String newId = watchId(dev, s.name);
            s.deviceId = dev.isEmpty() ? "legacy-unknown" : dev;
            s.deviceName = (deviceName == null) ? "" : deviceName;
            // 若同名 sync 课表已存在（同设备）：直接删掉本地记录（手环侧才是真源），避免重复 id
            Schedule dup = find(c, newId);
            if (dup != null && !localId.equals(dup.id)) {
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
                    o.put("deviceId", s.deviceId);
                    o.put("deviceName", s.deviceName);
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

    /** 重命名课表（可视化编辑页改课表名用）；若是激活课表，同步更新插件缓存里的名字 */
    public static synchronized void rename(Context c, String id, String newName) {
        try {
            Schedule s = find(c, id);
            if (s == null || newName == null || newName.trim().length() == 0) {
                return;
            }
            s.name = newName.trim();
            upsert(c, s);
            if (id.equals(activeId(c)) && !s.courses.isEmpty()) {
                CourseCache.saveFlat(c, s.courses, s.name);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 更新某套课表的课程（首页手动同步刷新用）；本地编辑调用 → 标记 dirty（有未同步改动） */
    public static synchronized void updateCourses(Context c, String id, List<CourseCache.Course> courses) {
        Schedule s = find(c, id);
        if (s == null || courses == null) {
            return;
        }
        s.courses.clear();
        s.courses.addAll(courses);
        s.dirty = true;
        if (s.isSync()) {
            s.syncedAt = System.currentTimeMillis();
        }
        upsert(c, s);
        if (id.equals(activeId(c))) {
            CourseCache.saveFlat(c, s.courses, s.name);
        }
    }

    /** 同步完成：写入合并结果 + 记录 base 快照 + 清 dirty（三方合并成功后调用） */
    public static synchronized void commitSync(Context c, String id,
                                               List<CourseCache.Course> courses, String baseJson) {
        Schedule s = find(c, id);
        if (s == null || courses == null) {
            return;
        }
        s.courses.clear();
        s.courses.addAll(courses);
        s.baseJson = (baseJson == null) ? "" : baseJson;
        s.dirty = false;
        if (s.isSync()) {
            s.syncedAt = System.currentTimeMillis();
        }
        upsert(c, s);
        if (id.equals(activeId(c))) {
            CourseCache.saveFlat(c, s.courses, s.name);
        }
    }

    /** 标记某套课表有未同步改动（断连暂存，连上补发） */
    public static synchronized void markDirty(Context c, String id) {
        Schedule s = find(c, id);
        if (s == null) {
            return;
        }
        s.dirty = true;
        upsert(c, s);
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
                try {
                    return new JSONObject(raw);
                } catch (Throwable bad) {
                    // 数据损坏：不静默清空——先备份原始内容（只备份一次），再重建空库。
                    // 否则用户表现为「课表列表无故清空」，排查时连证据都没有。
                    lastReadCorrupt = true;
                    try {
                        if (sp.getString(KEY_CORRUPT, "").length() == 0) {
                            sp.edit().putString(KEY_CORRUPT, raw).apply();
                        }
                        sp.edit().remove(KEY_DATA).apply();
                    } catch (Throwable ignored) {
                    }
                }
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
            lastWriteFailed = false;
        } catch (Throwable ignored) {
            lastWriteFailed = true;
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
            o.put("deviceId", s.deviceId == null ? "" : s.deviceId);
            o.put("deviceName", s.deviceName == null ? "" : s.deviceName);
            o.put("createdAt", s.createdAt);
            o.put("syncedAt", s.syncedAt);
            o.put("holiday", s.holiday);
            o.put("baseJson", s.baseJson == null ? "" : s.baseJson);
            o.put("dirty", s.dirty);
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
            s.deviceId = o.optString("deviceId", "");
            s.deviceName = o.optString("deviceName", "");
            s.createdAt = o.optLong("createdAt", 0);
            s.syncedAt = o.optLong("syncedAt", 0);
            // 假期模式默认开启：旧 JSON / 导入的 JSON 没有这个字段也按 true
            s.holiday = o.optBoolean("holiday", true);
            s.baseJson = o.optString("baseJson", "");
            s.dirty = o.optBoolean("dirty", false);
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
