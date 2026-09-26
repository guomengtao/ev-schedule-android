package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
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
import java.util.Date;
import java.util.Locale;

/** 导入 / 导出（按 mode 区分） */
public class TransferActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_IMPORT = "import";
    public static final String MODE_EXPORT = "export";

    private static final int REQ_PICK = 1001;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SimpleDateFormat FN =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private String mode = MODE_EXPORT;
    private TextView titleView, infoView, resultView;
    private String pendingImportPayload;
    private String lastExportJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_IMPORT.equals(m)) {
            mode = MODE_IMPORT;
        }

        LinearLayout root = Ui.screen(this);
        titleView = Ui.title(this, MODE_IMPORT.equals(mode) ? "导入课程表" : "导出课程表");
        root.addView(titleView);
        root.addView(Ui.space(this, 12));

        LinearLayout card = Ui.card(this);
        infoView = Ui.text(this, "准备就绪", 13f, Ui.TEXT, false);
        card.addView(infoView);
        card.addView(Ui.space(this, 10));
        card.addView(Ui.button(this,
                MODE_IMPORT.equals(mode) ? "选择 JSON 文件" : "读取手环数据",
                true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { primary(); }
                }));
        root.addView(card);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12f, Ui.MUTED, false);
        resultView.setTextIsSelectable(true);
        root.addView(resultView);

        root.addView(Ui.space(this, 8));
        if (MODE_IMPORT.equals(mode)) {
            root.addView(Ui.mono(this,
                    "支持：[] / {courses} / {schedule} / {schedules} / {payload} / 格式 A / EV 导出包\n"
                            + "注意：导入会【覆盖】手环当前课表，EV 侧会自动备份到 astrobox_sync_backup"));
        } else {
            root.addView(Ui.mono(this, "导出结果会保存到「下载 / EVSync」目录"));
        }

        setContentView(root);
    }

    private void primary() {
        if (MODE_IMPORT.equals(mode)) {
            pickFile();
        } else {
            readFromBand();
        }
    }

    // ======================= 导出 =======================

    private void readFromBand() {
        infoView.setText("正在读取手环数据…");
        resultView.setText("");
        SyncEngine.get(this).export(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                lastExportJson = json;
                SyncEngine.get(TransferActivity.this).lastExportJson = json;
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
        });
    }

    private void showSaveButton() {
        LinearLayout root = (LinearLayout) resultView.getParent();
        // 避免重复添加
        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v instanceof android.widget.Button
                    && "保存到下载目录".equals(((android.widget.Button) v).getText().toString())) {
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
        String name = "ev-export-" + FN.format(new Date()) + ".json";
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

    // ======================= 导入 =======================

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
            prepareImport(new String(bos.toByteArray(), UTF8), uri.getLastPathSegment());
        } catch (Throwable t) {
            infoView.setText("读取文件失败：" + t);
        }
    }

    private void prepareImport(String text, String fileName) {
        JSONArray courses = null;
        try {
            JSONObject o = new JSONObject(text);
            courses = toCourseArray(o.has("data") ? o.opt("data") : o);
        } catch (Throwable ignored) {
            try {
                courses = toCourseArray(new JSONArray(text));
            } catch (Throwable ignored2) {
            }
        }
        if (courses == null || courses.length() == 0) {
            infoView.setText("没能从这个文件解析出课程数组");
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("courses", courses);
            JSONObject o = new JSONObject();
            o.put("action", "import");
            o.put("payload", payload);
            pendingImportPayload = o.toString();
        } catch (Throwable t) {
            infoView.setText("构造报文失败：" + t);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("文件：").append(fileName).append('\n');
        sb.append("解析到 ").append(courses.length()).append(" 门课\n");
        infoView.setText(sb.toString());

        new AlertDialog.Builder(this)
                .setTitle("确认导入？")
                .setMessage("导入会【覆盖】手环上 EV 课程表的当前课表。\n"
                        + "EV 侧写盘前会自动备份到 astrobox_sync_backup。\n\n确定继续？")
                .setPositiveButton("导入并覆盖", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doImport(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport() {
        if (pendingImportPayload == null) {
            return;
        }
        infoView.setText("正在导入到手环…");
        SyncEngine.get(this).send(pendingImportPayload, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        int count = o.optInt("count", -1);
                        infoView.setText("导入成功"
                                + (count >= 0 ? ("，共 " + count + " 门课") : ""));
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
}
