package com.application.watch.classschedule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 多格式课程表解析器（纯 org.json，无 Android 依赖）。
 *
 * 同时服务于：
 *   - 安卓同步器「载入示例」功能（识别并规范化各平台原生格式）
 *   - JVM 单元测试（证明解析逻辑真的能跑通 6 种 demo）
 *
 * 输出契约（与安卓导入字段规范 SPEC_HINT 一致）：
 *   { name, day(1-7, 1=周一), time("HH:mm - HH:mm"), teacher?, location? }
 *
 * 源格式 day 约定：EV / 拾光 / WakeUp / 星链 / CSES 均为 0 基（0=周一）→ 统一 +1；
 * ICS 的 BYDAY 本身即 1 基（MO=1）→ 原样保留。
 */
public final class ScheduleImporter {

    private ScheduleImporter() {}

    public static JSONArray parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            if (t.contains("BEGIN:VCALENDAR")) {
                return parseIcs(t);
            }
            JSONObject o = new JSONObject(t);
            if (o.has("cses_version")) {
                return parseCses(o);
            }
            if (o.has("subjects")) {
                return parseStarlink(o);
            }
            if (o.has("courseList")) {
                return parseWakeUp(o);
            }
            if (o.has("timeSlots")) {
                return parseShiguang(o);
            }
            if (o.has("schedules")) {
                return parseEvSchedules(o);
            }
            if (o.has("courses")) {
                return parseEvCourses(o.opt("courses"));
            }
            if (o.has("schedule")) {
                return parseEvCourses(o.opt("schedule"));
            }
        } catch (JSONException ignored) {
            // 不是合法 JSON（也不是 ICS）→ 无法解析
        }
        return null;
    }

    // ---------- EV ----------

    private static JSONArray parseEvSchedules(JSONObject o) {
        JSONArray out = new JSONArray();
        JSONArray schs = o.optJSONArray("schedules");
        if (schs != null) {
            for (int i = 0; i < schs.length(); i++) {
                JSONObject s = schs.optJSONObject(i);
                if (s != null) {
                    collectEv(out, s.opt("courses"));
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    private static void collectEv(JSONArray out, Object courses) {
        if (courses instanceof JSONArray) {
            JSONArray arr = (JSONArray) courses;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) {
                    JSONObject n = normEv(c);
                    if (n != null) {
                        out.put(n);
                    }
                }
            }
        } else if (courses instanceof JSONObject) {
            JSONObject n = normEv((JSONObject) courses);
            if (n != null) {
                out.put(n);
            }
        }
    }

    private static JSONArray parseEvCourses(Object courses) {
        JSONArray out = new JSONArray();
        collectEv(out, courses);
        return out.length() > 0 ? out : null;
    }

    private static JSONObject normEv(JSONObject c) {
        String name = c.optString("name");
        String time;
        if (c.has("startTime") && c.has("endTime")) {
            time = c.optString("startTime") + " - " + c.optString("endTime");
        } else {
            time = c.optString("time");
        }
        int day = clampDay(toInt(c.opt("day")) + 1); // 0 基 → 1 基
        return norm(name, day, time, c.optString("teacher"), c.optString("location"));
    }

    // ---------- 拾光 sgschedule ----------

    private static JSONArray parseShiguang(JSONObject o) {
        Map<Integer, String[]> slots = new HashMap<>();
        JSONArray ts = o.optJSONArray("timeSlots");
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject s = ts.optJSONObject(i);
                if (s != null) {
                    int n = toInt(s.opt("number"));
                    slots.put(n, new String[]{s.optString("startTime"), s.optString("endTime")});
                }
            }
        }
        JSONArray out = new JSONArray();
        JSONArray cs = o.optJSONArray("courses");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String[] sSlot = slots.get(toInt(c.opt("startSection")));
                String[] eSlot = slots.get(toInt(c.opt("endSection")));
                if (sSlot == null || eSlot == null) {
                    continue;
                }
                int day = clampDay(toInt(c.opt("day")) + 1);
                String time = sSlot[0] + " - " + eSlot[1];
                JSONObject n = norm(c.optString("name"), day, time,
                        c.optString("teacher"), c.optString("position"));
                if (n != null) {
                    out.put(n);
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    // ---------- WakeUp ----------

    private static JSONArray parseWakeUp(JSONObject o) {
        JSONArray out = new JSONArray();
        JSONArray cs = o.optJSONArray("courseList");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                int day = clampDay(toInt(c.opt("day")) + 1);
                String time = c.optString("start") + " - " + c.optString("end");
                JSONObject n = norm(c.optString("name"), day, time,
                        c.optString("teacher"), c.optString("room"));
                if (n != null) {
                    out.put(n);
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    // ---------- 星链 Starlink ----------

    private static JSONArray parseStarlink(JSONObject o) {
        JSONArray out = new JSONArray();
        JSONArray cs = o.optJSONArray("subjects");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                int day = clampDay(toInt(c.opt("weekday")) + 1);
                String time = c.optString("beginTime") + " - " + c.optString("finishTime");
                JSONObject n = norm(c.optString("subjectName"), day, time,
                        c.optString("instructor"), c.optString("place"));
                if (n != null) {
                    out.put(n);
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    // ---------- CSES ----------

    private static JSONArray parseCses(JSONObject o) {
        JSONArray out = new JSONArray();
        JSONArray schs = o.optJSONArray("schedules");
        if (schs != null) {
            for (int i = 0; i < schs.length(); i++) {
                JSONObject s = schs.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                JSONArray cs = s.optJSONArray("courses");
                if (cs == null) {
                    continue;
                }
                for (int k = 0; k < cs.length(); k++) {
                    JSONObject c = cs.optJSONObject(k);
                    if (c == null) {
                        continue;
                    }
                    int day = clampDay(toInt(c.opt("day_of_week")) + 1);
                    String time = c.optString("start_time") + " - " + c.optString("end_time");
                    JSONObject n = norm(c.optString("name"), day, time,
                            c.optString("teacher"), c.optString("location"));
                    if (n != null) {
                        out.put(n);
                    }
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    // ---------- ICS / iCalendar ----------

    private static JSONArray parseIcs(String text) {
        JSONArray out = new JSONArray();
        String[] parts = text.split("BEGIN:VEVENT");
        for (String p : parts) {
            int end = p.indexOf("END:VEVENT");
            if (end < 0) {
                continue;
            }
            String ev = p.substring(0, end);
            String summary = field(ev, "SUMMARY");
            if (summary.isEmpty()) {
                continue;
            }
            String loc = field(ev, "LOCATION");
            String desc = field(ev, "DESCRIPTION");
            String dtstart = field(ev, "DTSTART");
            String dtend = field(ev, "DTEND");
            String time = icsTime(dtstart) + " - " + icsTime(dtend);
            String rrule = field(ev, "RRULE");

            String byday = null;
            if (!rrule.isEmpty()) {
                for (String tok : rrule.split(";")) {
                    if (tok.startsWith("BYDAY=")) {
                        byday = tok.substring(6);
                    }
                }
            }
            if (byday == null) {
                continue;
            }
            for (String d : byday.split(",")) {
                int dw = bydayNum(d.trim().toUpperCase());
                if (dw == 0) {
                    continue;
                }
                JSONObject c = norm(summary, dw, time, desc, loc);
                if (c != null) {
                    out.put(c);
                }
            }
        }
        return out.length() > 0 ? out : null;
    }

    private static String field(String block, String key) {
        int idx = block.indexOf(key + ":");
        if (idx < 0) {
            int i2 = block.indexOf(key + ";");
            if (i2 >= 0) {
                int c = block.indexOf(':', i2);
                if (c > i2) {
                    idx = c;
                }
            }
        }
        if (idx < 0) {
            return "";
        }
        int colon = block.indexOf(':', idx);
        int valStart = colon + 1;
        int e = block.indexOf("\n", valStart);
        String v = (e < 0) ? block.substring(valStart) : block.substring(valStart, e);
        return v.trim();
    }

    private static String icsTime(String dt) {
        int t = dt.indexOf('T');
        if (t < 0 || dt.length() < t + 5) {
            return "";
        }
        return dt.substring(t + 1, t + 3) + ":" + dt.substring(t + 3, t + 5);
    }

    private static int bydayNum(String tok) {
        if (tok.length() >= 2) {
            tok = tok.substring(tok.length() - 2); // 去掉前导序数（如 1MO → MO）
        }
        switch (tok) {
            case "MO": return 1;
            case "TU": return 2;
            case "WE": return 3;
            case "TH": return 4;
            case "FR": return 5;
            case "SA": return 6;
            case "SU": return 7;
            default: return 0;
        }
    }

    // ---------- 工具 ----------

    private static JSONObject norm(String name, int day, String time,
                                   String teacher, String location) {
        if (name == null || name.isEmpty() || time == null || time.isEmpty()) {
            return null;
        }
        JSONObject c = new JSONObject();
        try {
            c.put("name", name);
            c.put("day", clampDay(day));
            c.put("time", time);
            if (teacher != null && !teacher.isEmpty()) {
                c.put("teacher", teacher);
            }
            if (location != null && !location.isEmpty()) {
                c.put("location", location);
            }
        } catch (JSONException e) {
            return null;
        }
        return c;
    }

    private static int toInt(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clampDay(int d) {
        if (d < 1) {
            return 1;
        }
        if (d > 7) {
            return 7;
        }
        return d;
    }
}
