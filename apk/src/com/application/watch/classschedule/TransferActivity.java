package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 导入 / 导出（按 mode 区分） */
public class TransferActivity extends Activity {

    private int lastThemeVersion = 0;

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_IMPORT = "import";
    public static final String MODE_EXPORT = "export";

    private static final int REQ_PICK = 1001;
    /** 导出页离线缓存（清单 + 上次读取的课表原文）：连接时写入，断开也能显示。 */
    private static final String EXP_PREFS = "ev_export_cache";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SimpleDateFormat FN =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final String[] WEEK = {
            "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final String[] PERIODS = {
            "08:00 - 08:45", "08:55 - 09:40", "10:00 - 10:45", "10:55 - 11:40",
            "14:00 - 14:45", "14:55 - 15:40", "16:00 - 16:45", "16:55 - 17:40"};
    private static final String[] SAMPLE_NAMES = {
            "高等数学", "大学英语", "线性代数", "概率论与数理统计", "大学物理",
            "数据结构", "操作系统", "数据库系统", "计算机网络", "体育", "大学语文"};
    private static final String[] SAMPLE_TEACHERS = {
            "张教授", "李教授", "王教授", "陈教授", "刘教授", "赵教授"};
    private static final String[] SAMPLE_ROOMS = {
            "A楼101", "B楼205", "数学楼301", "计算机楼201", "物理实验室102", "体育馆"};

    private String mode = MODE_EXPORT;
    private TextView titleView, infoView, resultView, scheduleStatus, previewInfo;
    private String lastExportJson;

    // ---- 导出：课程表清单 ----
    private LinearLayout scheduleBox;
    private String[] scheduleNames;
    private int selectedIndex = -1;
    private String selectedName = "";

    // ---- 导出：JSON 编辑 ----
    private LinearLayout exportCard;
    private EditText exportBox;

    // ---- 导入：粘贴 / 预览 ----
    private JsonEditorView importEditor;
    private LinearLayout previewBox, previewCard;
    private final List<CheckBox> courseChecks = new ArrayList<>();
    private JSONArray parsedCourses;

    // ---- 导入：课程表名称 + 同名检测 ----
    private EditText nameBox;
    private String[] knownNames;

    private static final String JSON_SPEC_HINT =
            "字段规范：name 课程名(必填) · day 星期(1-7 或 星期X) · time 时间段(如 08:00 - 09:40)\n"
                    + "teacher 老师 · location 教室 · notes 备注。\n"
                    + "改完直接点「更新到手环」即写入（覆盖当前课表）；也可把这段 JSON 发给 AI 帮你规范整理。";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_IMPORT.equals(m)) {
            mode = MODE_IMPORT;
        }

        LinearLayout root = Ui.screen(this);
        ViewGroup headerBar = Ui.header(this,
                MODE_IMPORT.equals(mode) ? "导入课程表" : "导出课程表");
        titleView = (TextView) headerBar.getTag(); // 升级结果页会动态改标题
        root.addView(headerBar);
        root.addView(Ui.space(this, 8));
        ConnectionBar.attach(this, root);
        root.addView(Ui.space(this, 8));

        if (MODE_IMPORT.equals(mode)) {
            buildImport(root);
        } else {
            buildExport(root);
        }

        root.addView(Ui.space(this, 8));
        resultView.setTextIsSelectable(true);

        // tab=-1：导入/导出不是底栏三页之一，不高亮任何 tab —— 否则点「首页」会被当成当前页而失效
        setContentView(Ui.wrapWithBottomBar(this, root, -1));

        // 进页面不自动弹出输入法（把焦点交给根布局，EditText 不抢焦点）
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        Analytics.pageView(this, MODE_IMPORT.equals(mode) ? "/apk/transfer/import" : "/apk/transfer/export");

        if (!MODE_IMPORT.equals(mode)) {
            loadSchedules();
        } else {
            if (SyncEngine.get(this).hasNode()) {
                loadKnownNames();
            } else {
                // 未连接：直接用本地缓存还原导出页（连接时读取的数据已自动落盘）
                restoreCachedExport();
            }
        }
    }

    // ======================= 布局：导出 =======================

    private void buildExport(LinearLayout root) {
        LinearLayout pick = Ui.card(this);
        pick.addView(Ui.text(this, "手环上的课程表（选择要导出的那一套）", 12.5f, Ui.TEXT, true));
        pick.addView(Ui.space(this, 8));

        scheduleBox = new LinearLayout(this);
        scheduleBox.setOrientation(LinearLayout.VERTICAL);
        scheduleBox.addView(Ui.text(this, "正在读取课程表清单…", 12f, Ui.MUTED, false));
        pick.addView(scheduleBox);

        scheduleStatus = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        scheduleStatus.setPadding(0, Ui.dp(this, 6), 0, 0);
        pick.addView(scheduleStatus);

        pick.addView(Ui.space(this, 8));
        pick.addView(Ui.grid(this,
                Ui.button(this, "重新读取清单", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { loadSchedules(); }
                }),
                Ui.button(this, "读取当前课表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        selectedIndex = -1;
                        selectedName = "";
                        readFromBand();
                    }
                })));
        root.addView(pick);
        root.addView(Ui.space(this, 10));

        LinearLayout read = Ui.card(this);
        infoView = Ui.text(this, "选好课程表后，点下面读取", 13f, Ui.TEXT, false);
        read.addView(infoView);
        read.addView(Ui.space(this, 10));
        read.addView(Ui.button(this, "读取该课程表数据", true, new View.OnClickListener() {
            @Override public void onClick(View v) { readFromBand(); }
        }));
        root.addView(read);
        root.addView(Ui.space(this, 10));

        // JSON 编辑卡（读取成功后显示）
        exportCard = Ui.card(this);
        exportCard.setVisibility(View.GONE);
        exportCard.addView(Ui.text(this,
                "JSON（可复制出去改，再点「更新到手环」）", 12.5f, Ui.TEXT, true));
        exportCard.addView(Ui.space(this, 8));
        exportBox = new EditText(this);
        exportBox.setTextSize(11f);
        exportBox.setTextColor(Ui.TEXT);
        exportBox.setMinLines(6);
        exportBox.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        exportCard.addView(exportBox);
        exportCard.addView(Ui.space(this, 8));
        exportCard.addView(Ui.grid(this,
                Ui.button(this, "复制 JSON", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { copyExportJson(); }
                }),
                Ui.button(this, "更新到手环", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { updateToBand(); }
                })));
        exportCard.addView(Ui.space(this, 6));
        exportCard.addView(Ui.mono(this, JSON_SPEC_HINT));
        root.addView(exportCard);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "导出结果保存到「下载 / EVSync」"));
    }

    // ======================= 布局：导入 =======================

    private void buildImport(LinearLayout root) {
        LinearLayout paste = Ui.card(this);
        paste.addView(Ui.text(this, "课程 JSON（可直接编辑，工具栏在上方）", 12.5f, Ui.TEXT, true));
        paste.addView(Ui.space(this, 8));
        importEditor = new JsonEditorView(this);
        importEditor.setRows(10);
        paste.addView(importEditor);
        paste.addView(Ui.space(this, 8));
        paste.addView(Ui.grid(this,
                Ui.button(this, "解析并预览", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { parseFromText(importEditor.getJson(), "粘贴内容"); }
                }),
                Ui.button(this, "生成示例课表", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        importEditor.setJson(randomSampleJson());
                        parseFromText(importEditor.getJson(), "随机示例");
                    }
                })));
        paste.addView(Ui.space(this, 8));
        paste.addView(Ui.button(this, "选择文件", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        }));
        root.addView(paste);
        root.addView(Ui.space(this, 10));

        // 课程表名称（必填）
        LinearLayout nameCard = Ui.card(this);
        nameCard.addView(Ui.text(this, "课程表名称（必填）", 12.5f, Ui.TEXT, true));
        nameCard.addView(Ui.space(this, 6));
        nameBox = new EditText(this);
        nameBox.setTextSize(13f);
        nameBox.setTextColor(Ui.TEXT);
        nameBox.setHintTextColor(Ui.MUTED);
        nameBox.setHint("例如：2026 秋季学期 / 暑假辅导班");
        nameCard.addView(nameBox);
        nameCard.addView(Ui.space(this, 4));
        nameCard.addView(Ui.mono(this, "导入时会先检查手环上是否已有同名课程表"));
        root.addView(nameCard);
        root.addView(Ui.space(this, 10));

        // 预览卡（默认隐藏）
        previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setVisibility(View.GONE);
        previewInfo = Ui.text(this, "", 12.5f, Ui.TEXT, true);
        previewCard.addView(previewInfo);
        previewCard.addView(Ui.space(this, 8));
        previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        previewCard.addView(previewBox);
        previewCard.addView(Ui.space(this, 8));
        previewCard.addView(Ui.grid(this,
                Ui.button(this, "全选", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { setAllChecked(true); }
                }),
                Ui.button(this, "全不选", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { setAllChecked(false); }
                })));
        previewCard.addView(Ui.button(this, "导入到手环（覆盖当前课表）", true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmImportSelected(); }
                }));
        root.addView(previewCard);
        root.addView(Ui.space(this, 10));

        infoView = Ui.text(this, "准备就绪", 12.5f, Ui.TEXT, false);
        root.addView(infoView);
        root.addView(Ui.space(this, 8));
        resultView = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(resultView);
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this,
                "必填：name + day(1-7 或 星期X) + time\n"
                        + "提示：导入会【覆盖】手环当前课表，EV 侧会自动备份到 astrobox_sync_backup"));
    }

    // ======================= 导出：读取清单 =======================

    private void loadSchedules() {
        if (scheduleBox == null) {
            return;
        }
        scheduleBox.removeAllViews();
        scheduleBox.addView(Ui.text(this, "正在读取课程表清单…", 12f, Ui.MUTED, false));
        scheduleStatus.setText("");
        SyncEngine.get(this).listSchedules(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)
                            || !"list_schedules".equals(o.optString("action"))) {
                        failSchedules("手环拒绝了清单请求（回包：" + shortJson(json) + "）");
                        return;
                    }
                    JSONArray names = o.optJSONArray("names");
                    if (names == null || names.length() == 0) {
                        failSchedules("手环返回的课程表清单为空");
                        return;
                    }
                    scheduleNames = new String[names.length()];
                    for (int i = 0; i < names.length(); i++) {
                        scheduleNames[i] = names.optString(i);
                    }
                    int cur = o.optInt("current", 0);
                    selectedIndex = (cur >= 0 && cur < names.length()) ? cur : 0;
                    selectedName = scheduleNames[selectedIndex];
                    renderScheduleList();
                    scheduleStatus.setTextColor(Ui.OK);
                    scheduleStatus.setText("共 " + names.length() + " 套，当前激活："
                            + selectedName);
                } catch (Throwable t) {
                    failSchedules("清单回包无法解析：" + shortJson(json));
                }
            }
            @Override public void onTimeout(String hint) {
                if (!SyncEngine.get(TransferActivity.this).hasNode()) {
                    restoreCachedExport();
                } else {
                    failSchedules("读取清单超时。请确认手环已连接，且手环上的 EV 课程表已升级到 1.6.139 及以上");
                }
            }
            @Override public void onError(String msg) {
                if (!SyncEngine.get(TransferActivity.this).hasNode()) {
                    restoreCachedExport();
                } else {
                    failSchedules("读取清单失败：" + msg);
                }
            }
        });
    }

    private void renderScheduleList() {
        scheduleBox.removeAllViews();
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        for (int i = 0; i < scheduleNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(scheduleNames[i] + (i == selectedIndex ? "　（当前激活）" : ""));
            rb.setTextSize(13f);
            rb.setTextColor(Ui.TEXT);
            rb.setId(i + 1);
            rb.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
            final int idx = i;
            rb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedIndex = idx;
                    selectedName = scheduleNames[idx];
                    scheduleStatus.setTextColor(Ui.OK);
                    scheduleStatus.setText("已选择：" + selectedName);
                }
            });
            group.addView(rb);
            if (i == selectedIndex) {
                rb.setChecked(true);
            }
        }
        scheduleBox.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void failSchedules(String why) {
        if (scheduleBox == null) {
            return;
        }
        scheduleBox.removeAllViews();
        scheduleBox.addView(Ui.text(this,
                "没能读出课程表清单。\n" + why, 12f, Ui.WARN, false));
        scheduleStatus.setTextColor(Ui.WARN);
        scheduleStatus.setText("可点「读取当前课表」先导出当前这一套");
    }

    /**
     * 未连接手环时还原导出页。清单与「课程表管理 → 手环课表」**同源**：
     * 都来自 ScheduleStore 里 source=sync 的课表（连接时自动同步落库的那份），
     * 不再单独维护一份清单缓存（此前两处会不一致）。课表数据优先用上次
     * 连接读取的原文缓存（EXP_PREFS），没有则由当前激活的手环课表现生成。
     */
    private void restoreCachedExport() {
        List<ScheduleStore.Schedule> watch = new ArrayList<>();
        for (ScheduleStore.Schedule s : ScheduleStore.list(this)) {
            if (s.isSync()) {
                watch.add(s);
            }
        }
        String lastJson = getSharedPreferences(EXP_PREFS, 0).getString("last_export_json", null);
        long lastAt = getSharedPreferences(EXP_PREFS, 0).getLong("last_export_at", 0);
        if (watch.isEmpty() && lastJson == null) {
            failSchedules("手环未连接，本地也没有历史缓存。连接手环读取一次后会自动保存到本地");
            return;
        }
        if (!watch.isEmpty()) {
            scheduleNames = new String[watch.size()];
            String activeId = ScheduleStore.activeId(this);
            int act = -1;
            for (int i = 0; i < watch.size(); i++) {
                scheduleNames[i] = watch.get(i).name;
                if (watch.get(i).id.equals(activeId)) {
                    act = i;
                }
            }
            selectedIndex = act;
            selectedName = (act >= 0) ? scheduleNames[act] : "";
            renderScheduleList();
            scheduleStatus.setTextColor(Ui.MUTED);
            scheduleStatus.setText("清单与「课程表管理 → 手环课表」一致 · 连接手环后可重新读取");
        }
        if (lastJson != null) {
            // 优先还原上次从手环读到的原文（含昵称/版本等导出信息）
            lastExportJson = lastJson;
            SyncEngine.get(this).lastExportJson = lastJson;
            try {
                JSONObject o = new JSONObject(lastJson);
                JSONObject d = o.optJSONObject("data");
                if (d != null) {
                    org.json.JSONArray sch = d.optJSONArray("schedule");
                    if (sch != null) {
                        setExportJson(flattenFormatA(sch));
                    }
                }
            } catch (Throwable ignored) {
            }
            showSaveButton();
            infoView.setText("以下为上次连接时读取的手环课表"
                    + (lastAt > 0 ? "（" + fmtTime(lastAt) + "）" : "")
                    + "；连接手环后可重新读取最新数据");
            resultView.setText("离线模式：数据已保存到本地，不会丢失");
            resultView.setTextColor(Ui.MUTED);
        } else if (!watch.isEmpty()) {
            // 没有原文缓存：由当前激活的手环课表生成同样格式的 JSON
            for (ScheduleStore.Schedule s : watch) {
                if (s.id.equals(ScheduleStore.activeId(this))) {
                    try {
                        org.json.JSONArray flat = new org.json.JSONArray();
                        for (CourseCache.Course c : s.courses) {
                            JSONObject co = new JSONObject();
                            co.put("name", c.name);
                            co.put("day", c.day + 1);
                            co.put("time", c.time);
                            co.put("teacher", c.teacher);
                            co.put("location", c.location);
                            flat.put(co);
                        }
                        setExportJson(flat);
                    } catch (Throwable ignored) {
                    }
                    infoView.setText("数据来自本地课表库「" + s.name + "」（与课程表管理一致）；"
                            + "连接手环后可重新读取原文");
                    showSaveButton();
                    break;
                }
            }
        }
    }

    private static String fmtTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    private static String shortJson(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() > 120 ? json.substring(0, 120) + "…" : json;
    }

    private void readFromBand() {
        infoView.setText("正在读取手环数据…");
        resultView.setText("");
        SyncEngine.Reply cb = new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                lastExportJson = json;
                SyncEngine.get(TransferActivity.this).lastExportJson = json;
                // 连上就读到本地：课程写进多课表存储（断开也显示不丢），原文缓存供离线导出
                try {
                    JSONObject od = new JSONObject(json);
                    JSONObject dd = od.optJSONObject("data");
                    org.json.JSONArray schCache = (dd == null) ? null : dd.optJSONArray("schedule");
                    if (schCache != null) {
                        ScheduleStore.upsertFromWatch(TransferActivity.this,
                                selectedName == null ? "" : selectedName, schCache);
                    }
                } catch (Throwable ignored) {
                }
                getSharedPreferences(EXP_PREFS, 0).edit()
                        .putString("last_export_json", json)
                        .putString("last_export_name", selectedName == null ? "" : selectedName)
                        .putLong("last_export_at", System.currentTimeMillis())
                        .apply();
                try {
                    JSONObject o = new JSONObject(json);
                    JSONObject d = o.optJSONObject("data");
                    StringBuilder sb = new StringBuilder();
                    if (d != null) {
                        sb.append("昵称：").append(d.optString("nickname")).append('\n');
                        sb.append("版本：").append(d.optString("versionName"))
                          .append(" (code ").append(d.optInt("versionCode")).append(")\n");
                        JSONArray sch = d.optJSONArray("schedule");
                        int total = 0;
                        if (sch != null) {
                            for (int i = 0; i < sch.length(); i++) {
                                JSONObject day = sch.optJSONObject(i);
                                JSONArray cs = (day == null) ? null : day.optJSONArray("classes");
                                int n = (cs == null) ? 0 : cs.length();
                                total += n;
                                sb.append("  ").append(day == null ? "?" : day.optString("day"))
                                  .append(" ").append(n).append(" 节\n");
                            }
                        }
                        sb.append("合计：").append(total).append(" 节");
                        if (sch != null) {
                            setExportJson(flattenFormatA(sch));
                        }
                    } else {
                        sb.append("回包无 data：").append(shortJson(json));
                    }
                    if (selectedName != null && selectedName.length() > 0) {
                        sb.insert(0, "课程表：" + selectedName + "\n");
                    }
                    infoView.setText(sb.toString());
                    resultView.setText("共 " + json.length() + " 字节，可保存到文件");
                    showSaveButton();
                } catch (Throwable t) {
                    infoView.setText("回包无法解析");
                    resultView.setText(json);
                }
            }
            @Override public void onTimeout(String hint) { infoView.setText(hint); }
            @Override public void onError(String msg) { infoView.setText("读取失败：" + msg); }
        };
        if (selectedIndex >= 0) {
            SyncEngine.get(this).sendWake("{\"action\":\"export\",\"scheduleIndex\":" + selectedIndex + "}", cb);
        } else {
            SyncEngine.get(this).sendWake("{\"action\":\"export\"}", cb);
        }
    }

    // ======================= 导出：JSON 编辑 / 复制 / 更新 =======================

    /** 把导出结果摊平成可编辑的规范 JSON（含 day/time 字段），放进编辑框 */
    private void setExportJson(JSONArray flat) {
        if (exportBox == null) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("courses", flat);
            exportBox.setText(o.toString(2));
            exportCard.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private void copyExportJson() {
        if (exportBox == null || TextUtils.isEmpty(exportBox.getText().toString())) {
            resultView.setText("还没有可复制的 JSON");
            return;
        }
        copyToClipboard(exportBox.getText().toString(), "EV课程表");
    }

    /** 快捷更新：把编辑框里的 JSON 直接导回手环（覆盖当前课表） */
    private void updateToBand() {
        if (exportBox == null) {
            return;
        }
        JSONArray courses = toCourseArray(parseLoose(exportBox.getText().toString()));
        if (courses == null || courses.length() == 0) {
            resultView.setText("编辑框里的 JSON 解析不出课程数组");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        final String payload = buildImportPayload(courses);
        if (payload == null) {
            resultView.setText("构造报文失败");
            resultView.setTextColor(Ui.ERR);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认更新？")
                .setMessage("将把编辑框里的 " + courses.length() + " 门课写回首环，"
                        + "【覆盖】当前课表。\nEV 侧写盘前会自动备份。\n\n确定继续？")
                .setPositiveButton("更新到手环", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doImport(payload); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static Object parseLoose(String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (Throwable ignored) {
        }
        try {
            return new JSONArray(text);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void copyToClipboard(String text, String label) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                infoView.setText("已复制 " + text.length() + " 个字符到剪贴板，可到别处粘贴编辑");
                infoView.setTextColor(Ui.OK);
            }
        } catch (Throwable t) {
            infoView.setText("复制失败：" + t);
            infoView.setTextColor(Ui.ERR);
        }
    }

    /** 剪贴板 → 粘贴框，并自动解析预览（与 JSON 编辑器的粘贴同款行为）。 */
    private void pasteToBox() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            String text = (clip != null && clip.getItemCount() > 0)
                    ? String.valueOf(clip.getItemAt(0).coerceToText(this)) : "";
            if (text.trim().length() == 0) {
                resultView.setText("剪贴板是空的");
                resultView.setTextColor(Ui.WARN);
                return;
            }
            importEditor.setJson(text);
            parseFromText(text, "粘贴内容");
        } catch (Throwable t) {
            resultView.setText("粘贴失败");
            resultView.setTextColor(Ui.ERR);
        }
    }

    private void showSaveButton() {
        LinearLayout root = (LinearLayout) resultView.getParent();
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v instanceof Button
                    && "保存到下载目录".equals(((Button) v).getText().toString())) {
                return;
            }
        }
        root.addView(Ui.button(this, "保存到下载目录", true, new View.OnClickListener() {
            @Override public void onClick(View v) { saveToFile(); }
        }), root.getChildCount() - 2);
    }

    private void saveToFile() {
        if (lastExportJson == null) {
            resultView.setText("还没有数据");
            return;
        }
        String safe = (selectedName == null) ? "" : selectedName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String name = "ev-export-" + (safe.length() > 0 ? safe + "-" : "") + FN.format(new Date()) + ".json";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EVSync");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new RuntimeException("MediaStore insert 返回 null");
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(lastExportJson.getBytes(UTF8));
                os.flush();
                os.close();
                resultView.setText("已保存：下载 / EVSync / " + name);
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "EVSync");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new RuntimeException("无法创建目录");
                }
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(lastExportJson.getBytes(UTF8));
                fos.close();
                resultView.setText("已保存：" + f.getAbsolutePath());
            }
            resultView.setTextColor(Ui.OK);
        } catch (Throwable t) {
            resultView.setText("保存失败：" + t);
            resultView.setTextColor(Ui.ERR);
        }
    }

    // ======================= 导入：粘贴 / 复制 / 示例 =======================

    /** 生成一份随机示例课表 JSON（格式与 EV import 完全兼容，可直接导入做链路自测） */
    private String randomSampleJson() {
        Random r = new Random();
        int n = 4 + r.nextInt(4); // 4~7 门
        JSONArray courses = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject c = new JSONObject();
            try {
                c.put("name", SAMPLE_NAMES[r.nextInt(SAMPLE_NAMES.length)]);
                c.put("day", 1 + r.nextInt(7));
                c.put("time", PERIODS[r.nextInt(PERIODS.length)]);
                c.put("teacher", SAMPLE_TEACHERS[r.nextInt(SAMPLE_TEACHERS.length)]);
                c.put("location", SAMPLE_ROOMS[r.nextInt(SAMPLE_ROOMS.length)]);
            } catch (Throwable ignored) {
            }
            courses.put(c);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("courses", courses);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain"});
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            infoView.setText("无法打开文件选择器：" + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            String text = new String(bos.toByteArray(), UTF8);
            importEditor.setJson(text);
            parseFromText(text, uri.getLastPathSegment());
        } catch (Throwable t) {
            infoView.setText("读取文件失败：" + t);
        }
    }

    /** 解析文本框内容 → 渲染多选预览（默认全选） */
    private void parseFromText(String text, String srcName) {
        JSONArray courses = null;
        if (!TextUtils.isEmpty(text)) {
            try {
                JSONObject o = new JSONObject(text);
                courses = toCourseArray(o.has("data") ? o.opt("data") : o);
            } catch (Throwable ignored) {
                try {
                    courses = toCourseArray(new JSONArray(text));
                } catch (Throwable ignored2) {
                }
            }
        }
        if (courses == null || courses.length() == 0) {
            previewCard.setVisibility(View.GONE);
            infoView.setTextColor(Ui.ERR);
            infoView.setText("没能从「" + srcName + "」解析出课程数组");
            return;
        }
        parsedCourses = courses;
        renderPreview();
        previewCard.setVisibility(View.VISIBLE);
        infoView.setTextColor(Ui.TEXT);
    }

    private void renderPreview() {
        previewBox.removeAllViews();
        courseChecks.clear();
        int valid = 0;
        for (int i = 0; i < parsedCourses.length(); i++) {
            JSONObject c = parsedCourses.optJSONObject(i);
            if (c == null) {
                continue;
            }
            String name = c.optString("name");
            String day = dayLabel(c.opt("day"));
            String time = c.optString("time");
            if (name.length() > 0 && day.length() > 0 && time.length() > 0) {
                valid++;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(name.length() > 0 ? name : "（缺 name）");
            sb.append("　·　").append(day.length() > 0 ? day : "（缺 day）");
            sb.append("　·　").append(time.length() > 0 ? time : "（缺 time）");
            if (c.has("location")) {
                sb.append("　·　").append(c.optString("location"));
            }
            if (c.has("teacher")) {
                sb.append("　·　").append(c.optString("teacher"));
            }
            CheckBox cb = new CheckBox(this);
            cb.setText(sb.toString());
            cb.setTextSize(11.5f);
            cb.setTextColor(Ui.TEXT);
            cb.setChecked(true);
            previewBox.addView(cb);
            courseChecks.add(cb);
        }
        previewInfo.setText("待导入 " + courseChecks.size() + " 门（其中 " + valid
                + " 门必填字段完整，默认全选）");
    }

    private void setAllChecked(boolean v) {
        for (CheckBox cb : courseChecks) {
            cb.setChecked(v);
        }
    }

    private void confirmImportSelected() {
        if (parsedCourses == null || parsedCourses.length() == 0) {
            infoView.setText("还没有可导入的课程，先解析");
            return;
        }
        final String name = (nameBox == null) ? "" : nameBox.getText().toString().trim();
        if (name.length() == 0) {
            infoView.setText("请先填写课程表名称（必填）");
            infoView.setTextColor(Ui.ERR);
            return;
        }
        JSONArray picked = new JSONArray();
        for (int i = 0; i < parsedCourses.length() && i < courseChecks.size(); i++) {
            if (courseChecks.get(i).isChecked()) {
                JSONObject c = parsedCourses.optJSONObject(i);
                if (c != null) {
                    picked.put(c);
                }
            }
        }
        if (picked.length() == 0) {
            infoView.setText("一门课都没勾选");
            infoView.setTextColor(Ui.WARN);
            return;
        }
        final JSONArray finalPicked = picked;
        int idx = indexOfName(name);
        if (idx >= 0) {
            infoView.setText("检测到同名课程表，正在读取它的课程数…");
            infoView.setTextColor(Ui.MUTED);
            SyncEngine.get(this).exportSchedule(idx, new SyncEngine.Reply() {
                @Override public void onReply(String json) {
                    showImportConfirm(name, countCourses(json), finalPicked);
                }
                @Override public void onTimeout(String hint) { showImportConfirm(name, -1, finalPicked); }
                @Override public void onError(String msg) { showImportConfirm(name, -1, finalPicked); }
            });
        } else {
            showImportConfirm(name, -1, finalPicked);
        }
    }

    private void showImportConfirm(String name, int existingCount, JSONArray picked) {
        final String payload = buildImportPayload(picked, name);
        if (payload == null) {
            infoView.setText("构造报文失败");
            infoView.setTextColor(Ui.ERR);
            return;
        }
        String dup = existingCount >= 0
                ? ("检测到同名课程表「" + name + "」已存在，当前有 " + existingCount + " 门课。\n")
                : "";
        String title = existingCount >= 0 ? "同名课程表已存在，是否覆盖？" : "确认导入？";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(dup
                        + "将导入选中的 " + picked.length() + " 门课到「" + name + "」，"
                        + "并【覆盖】手环上 EV 课程表的当前课表。\n"
                        + "EV 侧写盘前会自动备份到 astrobox_sync_backup。\n\n确定继续？")
                .setPositiveButton(existingCount >= 0 ? "覆盖导入" : "导入并覆盖",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { doImport(payload); }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private int indexOfName(String name) {
        if (knownNames == null || name == null) {
            return -1;
        }
        for (int i = 0; i < knownNames.length; i++) {
            if (name.equalsIgnoreCase(knownNames[i] == null ? "" : knownNames[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static int countCourses(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject d = o.optJSONObject("data");
            JSONArray sch = (d == null) ? null : d.optJSONArray("schedule");
            int n = 0;
            if (sch != null) {
                for (int i = 0; i < sch.length(); i++) {
                    JSONObject day = sch.optJSONObject(i);
                    JSONArray cs = (day == null) ? null : day.optJSONArray("classes");
                    n += (cs == null) ? 0 : cs.length();
                }
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 后台静默拉一次课程表清单，供导入时的同名检测使用 */
    private void loadKnownNames() {
        SyncEngine.get(this).listSchedules(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)) {
                        return;
                    }
                    JSONArray names = o.optJSONArray("names");
                    if (names == null) {
                        return;
                    }
                    knownNames = new String[names.length()];
                    for (int i = 0; i < names.length(); i++) {
                        knownNames[i] = names.optString(i);
                    }
                } catch (Throwable ignored) {
                }
            }
            @Override public void onTimeout(String hint) { /* 静默 */ }
            @Override public void onError(String msg) { /* 静默 */ }
        });
    }

    private String buildImportPayload(JSONArray courses) {
        return buildImportPayload(courses, "");
    }

    /**
     * scheduleName 目前 EV 侧 import 会忽略（写当前激活套）；
     * 带上它是为后续「按名新建/覆盖」协议预留，不影响现有导入。
     */
    private String buildImportPayload(JSONArray courses, String scheduleName) {
        try {
            JSONObject body = new JSONObject();
            body.put("courses", courses);
            if (scheduleName != null && scheduleName.length() > 0) {
                body.put("scheduleName", scheduleName);
            }
            JSONObject o = new JSONObject();
            o.put("action", "import");
            o.put("payload", body);
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void doImport(String payload) {
        infoView.setText("正在导入到手环…");
        infoView.setTextColor(Ui.TEXT);
        SyncEngine.get(this).send(payload, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        int count = o.optInt("count", -1);
                        infoView.setText("导入成功" + (count >= 0 ? ("，共 " + count + " 门课") : ""));
                        infoView.setTextColor(Ui.OK);
                        resultView.setText(json);
                    } else {
                        infoView.setText("手环拒绝：" + o.optString("reason"));
                        infoView.setTextColor(Ui.ERR);
                        resultView.setText(json);
                    }
                } catch (Throwable t) {
                    infoView.setText("回包无法解析");
                    resultView.setText(json);
                }
            }
            @Override public void onTimeout(String hint) {
                infoView.setText(hint);
                infoView.setTextColor(Ui.WARN);
            }
            @Override public void onError(String msg) {
                infoView.setText("导入失败：" + msg);
                infoView.setTextColor(Ui.ERR);
            }
        });
    }

    // ======================= 数据转换 =======================

    /** day 兼容 1-7 / 星期X / 英文 */
    private static String dayLabel(Object day) {
        if (day == null) {
            return "";
        }
        if (day instanceof Number) {
            int d = ((Number) day).intValue();
            return (d >= 1 && d <= 7) ? WEEK[d - 1] : "";
        }
        String s = String.valueOf(day).trim();
        if (s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '7') {
            return WEEK[s.charAt(0) - '1'];
        }
        return s;
    }

    /**
     * EV 的 export 产出是「格式 A」（按天分组），而 import 只认「一条课一个对象」。
     * 直接回灌会导致每一项顶层缺 name/time 而被整批跳过 —— 必须先摊平。
     */
    private static JSONArray toCourseArray(Object o) {
        if (o == null) {
            return null;
        }
        try {
            if (o instanceof JSONArray) {
                JSONArray arr = (JSONArray) o;
                if (arr.length() > 0 && arr.optJSONObject(0) != null
                        && arr.optJSONObject(0).has("classes")) {
                    return flattenFormatA(arr);
                }
                return arr;
            }
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                if (j.has("courses")) {
                    return toCourseArray(j.opt("courses"));
                }
                if (j.has("schedule")) {
                    return toCourseArray(j.opt("schedule"));
                }
                if (j.has("schedules")) {
                    return toCourseArray(j.opt("schedules"));
                }
                if (j.has("payload")) {
                    return toCourseArray(j.opt("payload"));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONArray flattenFormatA(JSONArray arr) {
        JSONArray out = new JSONArray();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject day = arr.optJSONObject(i);
                if (day == null) {
                    continue;
                }
                String d = day.optString("day");
                JSONArray cs = day.optJSONArray("classes");
                if (cs == null) {
                    continue;
                }
                for (int k = 0; k < cs.length(); k++) {
                    JSONObject c = cs.optJSONObject(k);
                    if (c == null) {
                        continue;
                    }
                    JSONObject f = new JSONObject();
                    f.put("name", c.optString("name"));
                    f.put("day", d);
                    f.put("time", c.optString("time"));
                    if (c.has("teacher")) {
                        f.put("teacher", c.optString("teacher"));
                    }
                    if (c.has("location")) {
                        f.put("location", c.optString("location"));
                    }
                    if (c.has("notes")) {
                        f.put("notes", c.optString("notes"));
                    }
                    out.put(f);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
    }
}