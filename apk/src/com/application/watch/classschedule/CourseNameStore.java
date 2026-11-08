package com.application.watch.classschedule;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 课程名库（本地，用户可增删改查）。
 *
 * <p>为什么要有它：旧实现里课名候选来自 {@link CourseEditUtil#allCourseNames}，
 * 即「本机课表里出现过什么就有什么」——新装用户的候选是空的，等于对着空白打字，
 * 而手环端 {course-manager} 一直是一个可维护的课名清单。这里把清单显式化。
 *
 * <p>三条设计约束：
 * <ul>
 *   <li><b>不写死</b>：{@link #SEEDS} 只在课名库为空且无历史课名时做一次种子化，
 *       之后全部由用户增删改查控制，代码里不再引用它。</li>
 *   <li><b>向下兼容</b>：首次调用会把已有课表里的课名并入（{@link #mergeFromSchedules}），
 *       老用户升级后候选不会凭空消失。</li>
 *   <li><b>不与手环同步</b>：课名库是本机编辑辅助，不进同步协议。改课名会写回课表
 *       （sync 课表因此被标脏、下次同步推到手表），但清单本身不过网。</li>
 * </ul>
 */
public final class CourseNameStore {

    private static final String PREF = "ev_course_names";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SEEDED = "seeded";

    /** 首次种子化用的常用课名（与手环端 course-manager 的默认清单一致）。只用于「库空」那一次 */
    private static final String[] SEEDS = {
            "语文", "数学", "英语", "物理", "化学", "生物",
            "历史", "地理", "政治", "音乐", "美术", "体育",
    };

    private CourseNameStore() {
    }

    /** 一条课名记录：名称 + 使用次数（使用次数只用于排序，不影响功能） */
    public static final class Entry {
        public String name = "";
        public int use = 0;

        public Entry() {
        }

        public Entry(String n, int u) {
            name = n;
            use = u;
        }
    }

    // ======================= 读 =======================

    /** 全部课名，按使用次数降序、同次数按名称排序 */
    public static synchronized List<Entry> list(Context c) {
        List<Entry> out = read(c);
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int d = b.use - a.use;
                return d != 0 ? d : a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** 仅名称列表（chips / 自动补全用） */
    public static synchronized List<String> names(Context c) {
        List<String> out = new ArrayList<>();
        for (Entry e : list(c)) {
            out.add(e.name);
        }
        return out;
    }

    /** 库里是否已有该名（去重用） */
    public static synchronized boolean has(Context c, String name) {
        String n = norm(name);
        if (n.length() == 0) {
            return false;
        }
        for (Entry e : read(c)) {
            if (e.name.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** 该课名在已有课表里被多少门课使用（删除/改名前的告知用） */
    public static int usedInSchedules(Context c, String name) {
        String n = norm(name);
        int n2 = 0;
        if (n.length() == 0) {
            return 0;
        }
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(c)) {
                for (CourseCache.Course co : s.courses) {
                    if (co != null && n.equals(norm(co.name))) {
                        n2++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return n2;
    }

    // ======================= 写 =======================

    /**
     * 把一个已有课表里的课名并入库（幂等）。
     * 这是「向下兼容」的唯一入口：老用户升级后首次打开编辑页，历史课名自动出现在候选里。
     *
     * @return 新并入的条数
     */
    public static synchronized int mergeFromSchedules(Context c) {
        List<Entry> cur = read(c);
        List<Entry> add = new ArrayList<>();
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(c)) {
                for (CourseCache.Course co : s.courses) {
                    if (co == null) {
                        continue;
                    }
                    String n = norm(co.name);
                    if (n.length() == 0 || contains(cur, n) || contains(add, n)) {
                        continue;
                    }
                    add.add(new Entry(n, 1));
                }
            }
        } catch (Throwable ignored) {
        }
        if (add.isEmpty()) {
            return 0;
        }
        cur.addAll(add);
        write(c, cur);
        return add.size();
    }

    /**
     * 首次种子化：库为空 → 先并入历史课名，仍然为空才用内置常用课名。
     * 只跑一次（{@link #KEY_SEEDED} 标记），用户之后清空库不会再被塞回来。
     */
    public static synchronized void ensureSeeded(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (sp.getBoolean(KEY_SEEDED, false)) {
                mergeFromSchedules(c);
                return;
            }
            mergeFromSchedules(c);
            if (read(c).isEmpty()) {
                List<Entry> seed = new ArrayList<>();
                for (String s : SEEDS) {
                    seed.add(new Entry(s, 0));
                }
                write(c, seed);
            }
            sp.edit().putBoolean(KEY_SEEDED, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 新增课名。
     *
     * @return false = 已存在（不重复添加）
     */
    public static synchronized boolean add(Context c, String name) {
        String n = norm(name);
        if (n.length() == 0) {
            return false;
        }
        List<Entry> cur = read(c);
        if (contains(cur, n)) {
            return false;
        }
        cur.add(new Entry(n, 0));
        write(c, cur);
        return true;
    }

    /**
     * 改名：同时把已有课表里同名的课程一起改掉（否则用户改完课名，课表里还是旧名，很怪）。
     *
     * @return 被一并改写的课程条数（0 表示课表里没有用过这个名）
     */
    public static synchronized int rename(Context c, String from, String to) {
        String f = norm(from);
        String t = norm(to);
        if (f.length() == 0 || t.length() == 0 || f.equals(t)) {
            return 0;
        }
        List<Entry> cur = read(c);
        boolean hit = false;
        for (Entry e : cur) {
            if (e.name.equals(f)) {
                e.name = t;
                hit = true;
            }
        }
        // 改名后可能与已有项重名 → 合并掉重复项，保留 use 较大的
        List<Entry> dedup = new ArrayList<>();
        for (Entry e : cur) {
            Entry exist = find(dedup, e.name);
            if (exist == null) {
                dedup.add(e);
            } else {
                exist.use = Math.max(exist.use, e.use);
            }
        }
        write(c, dedup);
        if (!hit) {
            // 库里没有这个名字也照样加进去（用户在课表里直接改了名的情况）
            add(c, t);
        }
        return rewriteCourses(c, f, t);
    }

    /** 删除课名（只删清单，不动课表里已存在的课程） */
    public static synchronized void remove(Context c, String name) {
        String n = norm(name);
        List<Entry> cur = read(c);
        List<Entry> keep = new ArrayList<>();
        for (Entry e : cur) {
            if (!e.name.equals(n)) {
                keep.add(e);
            }
        }
        write(c, keep);
    }

    /** 清空课名库（用户显式操作；清空后不会被种子化再塞回来） */
    public static synchronized void clear(Context c) {
        write(c, new ArrayList<Entry>());
        try {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SEEDED, true).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记一次使用：库里有就 +1，没有就新增（用户直接打的课名也自动进库）。
     * 保存课程时调用，保证「打一次以后就能点」。
     */
    public static synchronized void bump(Context c, String name) {
        String n = norm(name);
        if (n.length() == 0) {
            return;
        }
        List<Entry> cur = read(c);
        Entry e = find(cur, n);
        if (e == null) {
            cur.add(new Entry(n, 1));
        } else {
            e.use++;
        }
        write(c, cur);
    }

    // ======================= 内部 =======================

    /** 把所有课表里的 from 改名为 to，返回改了几门课 */
    private static int rewriteCourses(Context c, String from, String to) {
        int changed = 0;
        try {
            for (ScheduleStore.Schedule s : ScheduleStore.list(c)) {
                boolean dirty = false;
                for (CourseCache.Course co : s.courses) {
                    if (co != null && from.equals(norm(co.name))) {
                        co.name = to;
                        dirty = true;
                        changed++;
                    }
                }
                if (dirty) {
                    ScheduleStore.updateCourses(c, s.id, s.courses);
                }
            }
        } catch (Throwable ignored) {
        }
        return changed;
    }

    private static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String n = norm(o.optString("n", ""));
                if (n.length() == 0) {
                    continue;
                }
                out.add(new Entry(n, o.optInt("u", 0)));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Entry> items) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : items) {
                JSONObject o = new JSONObject();
                o.put("n", e.name);
                o.put("u", e.use);
                arr.put(o);
            }
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static boolean contains(List<Entry> list, String name) {
        return find(list, name) != null;
    }

    private static Entry find(List<Entry> list, String name) {
        for (Entry e : list) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}
