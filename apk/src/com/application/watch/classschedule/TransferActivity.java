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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

/**
 * 导入 / 导出（按 mode 区分）。
 *
 * <p>v1 视图层重做（方案 docs/导入导出页全新设计方案-v1.md）：
 * 一条动线（导出=选表→读→导出；导入=贴→预览→写回）、每张卡自带结果条的就地反馈、
 * 破坏性动作独立成行、全页字号回到 Ui 既有档位（消除 12.5f/12f/11f 共 12 处越界）。
 * 业务逻辑（读取 / 解析 / 摊平 / 导入 / 埋点）与重做前逐字节一致，只改「写进哪个 View」。
 */
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
    private TextView titleView;

    // ---- 反馈：每张卡自带结果条（原页面级反馈区已删除）----
    private TextView pickRes, exportRes, pasteRes, previewRes, nameErr;

    // ---- 导出：唯一读取按钮（清单读失败时文案自适应）----
    private Button readBtn;
    private boolean listFailed = false;

    private String lastExportJson;

    // ---- 导出：课程表清单 ----
    private LinearLayout scheduleBox;
    private TextView pickSub;
    private String[] scheduleNames;
    private int selectedIndex = -1;
    private String selectedName = "";

    // ---- 导出：JSON 编辑 ----
    private LinearLayout exportCard;
    private EditText exportBox;
    private TextView exportSub;

    // P3：最近一次从手环读出的课程总数（供导出成功事件的 course_count）
    private int lastExportTotal = 0;

    /** 未连接引导卡（连接后整张隐藏）；guideShown 记录上一次的显隐，用于识别「刚连上」 */
    private LinearLayout guideCard;
    private boolean guideShown = true;

    /** P3 埋点便捷入口：失败静默，绝不影响导入导出主流程 */
    private void ev(String kind, JSONObject payload) {
        try {
            Analytics.event(this, kind, payload);
        } catch (Throwable ignored) {
        }
    }

    // ---- 导入：粘贴 / 预览 ----
    private JsonEditorView importEditor;
    private LinearLayout previewBox, previewCard;
    private TextView previewInfo, previewSub;
    private final List<CheckBox> courseChecks = new ArrayList<>();
    private JSONArray parsedCourses;

    // ---- 导入：课程表名称 + 同名检测 ----
    private EditText nameBox;
    private String[] knownNames;

    /** 字段规范：从页面正文移入顶栏 ⋯ 弹层（页面只留必要的短提示） */
    private static final String SPEC_HINT =
            "字段规范\n\n"
                    + "name　　课程名（必填）\n"
                    + "day　　 星期：1-7 或 星期X（必填）\n"
                    + "time　　时间段：如 08:00 - 09:40（必填）\n"
                    + "teacher　老师（选填）\n"
                    + "location 教室（选填）\n"
                    + "notes　　备注（选填）";

    private static final String SPEC_OVERWRITE =
            "覆盖与备份\n\n"
                    + "导入会【覆盖】手环上 EV 课程表的当前课表。\n"
                    + "EV 侧写盘前会自动备份到 astrobox_sync_backup，需要时可回滚。\n\n"
                    + "也可以把这段 JSON 发给 AI 帮你规范整理后再导入。";

    private static final String SPEC_WHERE =
            "导出到哪\n\n"
                    + "「保存为文件」→ 存到「下载 / EVSync」目录\n"
                    + "「复制 JSON」→ 直接进剪贴板，可发到微信或用 AI 整理\n\n"
                    + "两者导出的都是同一次读取的原始数据。";

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
        addHeaderMore(headerBar);
        root.addView(headerBar);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // 未连接引导卡（连接状态全页唯一一处；已连接时不占位）
        guideCard = buildGuideCard();
        boolean guideHas = SyncEngine.get(this).hasNode();
        guideShown = !guideHas;
        guideCard.setVisibility(guideHas ? View.GONE : View.VISIBLE);
        root.addView(guideCard);

        if (MODE_IMPORT.equals(mode)) {
            buildImport(root);
        } else {
            buildExport(root);
        }

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

    // ======================= 通用小件 =======================

    /** 卡标题行；withRefresh=true 时右侧带「重新读取」图标（44dp 热区）*/
    private LinearLayout cardTitle(String title, boolean withRefresh) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, Ui.SP_BODY, Ui.TEXT, true);
        r.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (withRefresh) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(R.drawable.ic_refresh);
            iv.setColorFilter(Ui.MUTED);
            iv.setContentDescription("重新读取课程表清单");
            iv.setClickable(true);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { loadSchedules(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN));
            lp.leftMargin = Ui.dp(this, 4);
            lp.rightMargin = -Ui.dp(this, 10);
            r.addView(iv, lp);
        }
        return r;
    }

    /** 卡内结果条：CARD2 底 + R_CTRL 圆角，无内容时整条隐藏 */
    private TextView resultBar() {
        TextView t = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, 0, this));
        t.setVisibility(View.GONE);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    /** 结果条统一的写入口（空串 = 隐藏） */
    private void setRes(TextView bar, String msg, int color) {
        if (bar == null) {
            return;
        }
        if (msg == null || msg.length() == 0) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setText(msg);
        bar.setTextColor(color);
        bar.setVisibility(View.VISIBLE);
    }

    /** 破坏性动作按钮：透明底 + 2dp ERR 描边（不新增 token，复用既有 ERR 色与 R_CTRL） */
    private Button dangerButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_BODY);
        b.setTextColor(Ui.ERR);
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        b.setBackground(Ui.round(0, Ui.R_CTRL, Ui.ERR, 2, this));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    /** 小字文字链（全选 / 全不选） */
    private TextView linkText(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, s, Ui.SP_CAPTION, Ui.ACCENT, true);
        t.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    private void addHeaderMore(ViewGroup headerBar) {
        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_more_vertical);
        more.setColorFilter(Ui.MUTED);
        more.setContentDescription("说明与更多");
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showInfoMenu(); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                Gravity.CENTER_VERTICAL | Gravity.END);
        lp.rightMargin = Ui.dp(this, 8);
        headerBar.addView(more, lp);
    }

    /** 顶栏 ⋯：字段规范 / 覆盖说明 / 导出位置 / 生成示例数据（自测用） */
    private void showInfoMenu() {
        final boolean imp = MODE_IMPORT.equals(mode);
        String[] items = imp
                ? new String[]{"字段格式说明", "覆盖与备份说明", "生成示例数据（自测用）"}
                : new String[]{"字段格式说明", "导出到哪"};
        new AlertDialog.Builder(this)
                .setTitle(imp ? "导入说明" : "导出说明")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (w == 0) {
                            showTextDialog("字段格式说明", SPEC_HINT);
                        } else if (imp && w == 1) {
                            showTextDialog("覆盖与备份", SPEC_OVERWRITE);
                        } else if (imp) {
                            if (importEditor != null) {
                                importEditor.setJson(randomSampleJson());
                                parseFromText(importEditor.getJson(), "随机示例");
                            }
                        } else {
                            showTextDialog("导出到哪", SPEC_WHERE);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showTextDialog(String title, String body) {
        TextView t = Ui.text(this, body, Ui.SP_BODY, Ui.TEXT, false);
        t.setLineSpacing(Ui.dp(this, 3), 1.15f);
        int pad = Ui.dp(this, 20);
        t.setPadding(pad, Ui.dp(this, 6), pad, 0);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(t)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 未连接引导卡：全页唯一的连接状态来源 */
    private LinearLayout buildGuideCard() {
        LinearLayout g = Ui.card(this);
        g.setBackground(Ui.round(Ui.CARD2, Ui.R_CARD, Ui.LINE, this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        dot.setBackground(Ui.round(Ui.WARN, 4, 0, this));
        LinearLayout.LayoutParams dp8 = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        dp8.rightMargin = Ui.dp(this, 10);
        row.addView(dot, dp8);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "手环未连接", Ui.SP_BODY, Ui.TEXT, true));
        col.addView(Ui.text(this, "导入与导出都需要手环在线",
                Ui.SP_CAPTION, Ui.MUTED, false));
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = Ui.text(this, "去连接 ›", Ui.SP_CAPTION, Ui.ACCENT, true);
        go.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), 0, Ui.dp(this, 10));
        go.setClickable(true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(TransferActivity.this, BandActivity.class));
                } catch (Throwable ignored) {
                }
            }
        });
        row.addView(go);
        g.addView(row);
        return g;
    }

    // ======================= 布局：导出 =======================

    private void buildExport(LinearLayout root) {
        // ---- 卡1：选择要导出的课表（原 pick + read 两卡合并）----
        LinearLayout pick = Ui.card(this);
        pick.addView(cardTitle("选择要导出的课表", true));
        pickSub = Ui.text(this, "正在读取课程表清单…", Ui.SP_CAPTION, Ui.MUTED, false);
        pickSub.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 6));
        pick.addView(pickSub);

        scheduleBox = new LinearLayout(this);
        scheduleBox.setOrientation(LinearLayout.VERTICAL);
        scheduleBox.addView(Ui.text(this, "正在读取课程表清单…", Ui.SP_CAPTION, Ui.MUTED, false));
        pick.addView(scheduleBox);

        pickRes = resultBar();
        pick.addView(pickRes);
        pick.addView(Ui.space(this, Ui.GAP_SM));

        readBtn = Ui.button(this, "读取选中的课表", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listFailed) {
                    selectedIndex = -1;
                    selectedName = "";
                }
                readFromBand();
            }
        });
        pick.addView(readBtn);
        root.addView(pick);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // ---- 卡2：导出内容（读取成功后显示）----
        exportCard = Ui.card(this);
        exportCard.setVisibility(View.GONE);
        exportCard.addView(cardTitle("导出内容", false));
        exportSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        exportSub.setPadding(0, Ui.dp(this, 3), 0, 0);
        exportCard.addView(exportSub);
        exportCard.addView(Ui.space(this, Ui.GAP_SM));

        exportBox = new EditText(this);
        exportBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_MICRO);
        exportBox.setTextColor(Ui.TEXT);
        exportBox.setMinLines(6);
        exportBox.setGravity(Gravity.TOP | Gravity.START);
        // 代码区自成一块：CARD2 底 + R_CTRL 圆角（与原型一致，不再裸文本贴在卡上）
        exportBox.setBackground(Ui.round(Ui.CARD2, Ui.R_CTRL, Ui.LINE, this));
        exportBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        exportCard.addView(exportBox);

        exportRes = resultBar();
        exportCard.addView(exportRes);
        exportCard.addView(Ui.grid(this,
                Ui.button(this, "复制 JSON", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { copyExportJson(); }
                }),
                Ui.button(this, "保存为文件", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { saveToFile(); }
                })));

        // 破坏性动作独立成行：不与上面的安全动作并排
        exportCard.addView(Ui.space(this, Ui.GAP_MD));
        exportCard.addView(Ui.divider(this));
        exportCard.addView(Ui.space(this, Ui.GAP_MD));
        exportCard.addView(dangerButton("更新到手环（覆盖当前课表）",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { updateToBand(); }
                }));
        root.addView(exportCard);
    }

    // ======================= 布局：导入 =======================

    private void buildImport(LinearLayout root) {
        // ---- 卡1：课程 JSON ----
        LinearLayout paste = Ui.card(this);
        paste.addView(cardTitle("课程 JSON", false));
        TextView hint = Ui.text(this, "可直接编辑；工具栏在编辑器上方",
                Ui.SP_CAPTION, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 3), 0, 0);
        paste.addView(hint);
        paste.addView(Ui.space(this, Ui.GAP_SM));

        importEditor = new JsonEditorView(this);
        importEditor.setRows(10);
        paste.addView(importEditor);

        pasteRes = resultBar();
        paste.addView(pasteRes);
        paste.addView(Ui.grid(this,
                Ui.button(this, "解析并预览", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        parseFromText(importEditor.getJson(), "粘贴内容");
                    }
                }),
                Ui.button(this, "选择文件", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { pickFile(); }
                })));
        root.addView(paste);
        root.addView(Ui.space(this, Ui.GAP_SM));

        // ---- 卡2：预览与导入（解析成功后才出现）----
        previewCard = Ui.card(this);
        previewCard.setVisibility(View.GONE);
        previewInfo = Ui.text(this, "", Ui.SP_BODY, Ui.TEXT, true);
        previewCard.addView(previewInfo);
        previewSub = Ui.text(this, "", Ui.SP_CAPTION, Ui.MUTED, false);
        previewSub.setPadding(0, Ui.dp(this, 3), 0, 0);
        previewCard.addView(previewSub);
        previewCard.addView(Ui.space(this, Ui.GAP_XS));

        previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        previewCard.addView(previewBox);

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.addView(linkText("全选", new View.OnClickListener() {
            @Override public void onClick(View v) { setAllChecked(true); }
        }));
        links.addView(linkText("全不选", new View.OnClickListener() {
            @Override public void onClick(View v) { setAllChecked(false); }
        }));
        previewCard.addView(links);

        previewCard.addView(Ui.space(this, Ui.GAP_MD));
        previewCard.addView(Ui.divider(this));
        previewCard.addView(Ui.space(this, Ui.GAP_MD));

        // 名称紧邻导入按钮（原来夹在两卡中间，报错却写在页脚）
        previewCard.addView(Ui.text(this, "课程表名称（必填）",
                Ui.SP_CAPTION, Ui.TEXT, true));
        previewCard.addView(Ui.space(this, Ui.GAP_XS));
        nameBox = new EditText(this);
        nameBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_BODY);
        nameBox.setTextColor(Ui.TEXT);
        nameBox.setHintTextColor(Ui.MUTED);
        nameBox.setHint("例如：2026 秋季学期 / 暑假辅导班");
        previewCard.addView(nameBox);

        nameErr = Ui.text(this, "", Ui.SP_CAPTION, Ui.ERR, false);
        nameErr.setPadding(0, Ui.dp(this, 5), 0, 0);
        nameErr.setVisibility(View.GONE);
        previewCard.addView(nameErr);

        previewRes = resultBar();
        previewCard.addView(previewRes);
        previewCard.addView(Ui.space(this, Ui.GAP_SM));
        previewCard.addView(dangerButton("导入到手环（覆盖当前课表）",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmImportSelected(); }
                }));
        root.addView(previewCard);
    }

    // ======================= 导出：读取清单 =======================

    private void loadSchedules() {
        if (scheduleBox == null) {
            return;
        }
        listFailed = false;
        if (readBtn != null) {
            readBtn.setText("读取选中的课表");
        }
        if (pickSub != null) {
            pickSub.setText("正在读取课程表清单…");
        }
        setRes(pickRes, "", Ui.MUTED);
        scheduleBox.removeAllViews();
        scheduleBox.addView(Ui.text(this, "正在读取课程表清单…", Ui.SP_CAPTION, Ui.MUTED, false));
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
                    pickSub.setText("手环上共 " + names.length() + " 套 · 当前使用中："
                            + selectedName);
                    setRes(pickRes, "已读取 " + names.length() + " 套课程表", Ui.OK);
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
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_BODY);
            rb.setTextColor(Ui.TEXT);
            rb.setId(i + 1);
            rb.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
            final int idx = i;
            rb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedIndex = idx;
                    selectedName = scheduleNames[idx];
                    setRes(pickRes, "已选择：" + selectedName, Ui.OK);
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

    /** 清单读失败：不新设按钮，改为让唯一读取按钮文案自适应（D3） */
    private void failSchedules(String why) {
        if (scheduleBox == null) {
            return;
        }
        listFailed = true;
        scheduleBox.removeAllViews();
        scheduleBox.addView(Ui.text(this, why, Ui.SP_CAPTION, Ui.WARN, false));
        if (pickSub != null) {
            pickSub.setText("手环上读不到清单");
        }
        if (readBtn != null) {
            readBtn.setText("直接读取当前课表");
        }
        setRes(pickRes, "读不到清单 · 可先导出当前这一套", Ui.WARN);
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
            if (pickSub != null) {
                pickSub.setText("来自本地课表库 · 连接手环后可重新读取");
            }
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
            setRes(exportRes, "离线数据 · 上次读取于 "
                    + (lastAt > 0 ? fmtTime(lastAt) : "未知时间"), Ui.MUTED);
            setSubForOffline();
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
                    setRes(exportRes, "离线数据 · 来自本地课表库「" + s.name + "」", Ui.MUTED);
                    setSubForOffline();
                    break;
                }
            }
        }
    }

    private void setSubForOffline() {
        if (exportSub != null) {
            exportSub.setText((selectedName == null || selectedName.length() == 0 ? "手环课表" : selectedName)
                    + " · 上次连接时读取 · 存到「下载 / EVSync」");
        }
    }

    private static String fmtTime(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    private static String nowHM() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private static String shortJson(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() > 120 ? json.substring(0, 120) + "…" : json;
    }

    private void readFromBand() {
        setRes(exportRes, "正在读取手环数据…", Ui.MUTED);
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
                                SyncEngine.get(TransferActivity.this).currentDeviceId(),
                                SyncEngine.get(TransferActivity.this).currentDeviceName(),
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
                    if (d != null) {
                        JSONArray sch = d.optJSONArray("schedule");
                        int total = 0;
                        if (sch != null) {
                            for (int i = 0; i < sch.length(); i++) {
                                JSONObject day = sch.optJSONObject(i);
                                JSONArray cs = (day == null) ? null : day.optJSONArray("classes");
                                total += (cs == null) ? 0 : cs.length();
                            }
                            setExportJson(flattenFormatA(sch));
                        }
                        // P3：记住本次读出的课程数，供「保存/复制」成功事件带上 course_count
                        lastExportTotal = total;
                        ev("app_export_ok", Analytics.p("course_count", total, "target", "band_read"));
                        String ver = d.optString("versionName");
                        setRes(exportRes, "已读取 " + total + " 门课"
                                + (ver.length() > 0 ? " · 手环 EV " + ver : ""), Ui.OK);
                        if (exportSub != null) {
                            exportSub.setText((selectedName == null || selectedName.length() == 0
                                    ? "手环课表" : selectedName)
                                    + " · " + total + " 门课 · " + nowHM()
                                    + " 读取 · 存到「下载 / EVSync」");
                        }
                    } else {
                        setRes(exportRes, "回包无 data：可稍后重试", Ui.WARN);
                    }
                } catch (Throwable t) {
                    setRes(exportRes, "回包无法解析，可稍后重试", Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                setRes(exportRes, hint, Ui.WARN);
            }
            @Override public void onError(String msg) {
                setRes(exportRes, "读取失败：" + msg, Ui.ERR);
                // P3：读手环失败（导出主链路断在这）→ 失败合并桶
                ev("app_export_fail", Analytics.p("stage", "band_read", "reason", msg));
            }
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
            setRes(exportRes, "还没有可复制的 JSON", Ui.WARN);
            return;
        }
        copyToClipboard(exportBox.getText().toString(), "EV课程表");
        // P3：复制导出结果成功（导出落地口径之一）
        ev("app_export_ok", Analytics.p("course_count", lastExportTotal, "target", "clip"));
    }

    /** 快捷更新：把编辑框里的 JSON 直接导回手环（覆盖当前课表） */
    private void updateToBand() {
        if (exportBox == null) {
            return;
        }
        JSONArray courses = toCourseArray(parseLoose(exportBox.getText().toString()));
        if (courses == null || courses.length() == 0) {
            setRes(exportRes, "编辑框里的 JSON 解析不出课程数组", Ui.ERR);
            return;
        }
        final String payload = buildImportPayload(courses);
        if (payload == null) {
            setRes(exportRes, "构造报文失败", Ui.ERR);
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
                setRes(exportRes, "已复制 " + text.length() + " 个字符到剪贴板", Ui.OK);
            }
        } catch (Throwable t) {
            setRes(exportRes, "复制失败：" + t, Ui.ERR);
        }
    }

    private void saveToFile() {
        if (lastExportJson == null) {
            setRes(exportRes, "还没有可保存的数据，先读取", Ui.WARN);
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
                setRes(exportRes, "已保存：下载 / EVSync / " + name, Ui.OK);
                // P3：保存到下载目录成功（导出落地口径之一）
                ev("app_export_ok", Analytics.p("course_count", lastExportTotal, "target", "file"));
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "EVSync");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new RuntimeException("无法创建目录");
                }
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(lastExportJson.getBytes(UTF8));
                fos.close();
                setRes(exportRes, "已保存：" + f.getAbsolutePath(), Ui.OK);
                // P3：保存到应用目录成功（老系统路径）
                ev("app_export_ok", Analytics.p("course_count", lastExportTotal, "target", "file"));
            }
        } catch (Throwable t) {
            setRes(exportRes, "保存失败：" + t, Ui.ERR);
            // P3：保存失败 → 失败合并桶
            ev("app_export_fail", Analytics.p("stage", "save_file", "reason", String.valueOf(t)));
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
            setRes(pasteRes, "无法打开文件选择器：" + t, Ui.ERR);
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
            setRes(pasteRes, "读取文件失败：" + t, Ui.ERR);
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
            setRes(pasteRes, "没能从「" + srcName + "」解析出课程数组", Ui.ERR);
            return;
        }
        parsedCourses = courses;
        hideNameErr();
        renderPreview();
        previewCard.setVisibility(View.VISIBLE);
        setRes(pasteRes, "已解析出 " + courses.length() + " 门课程", Ui.OK);
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
            boolean complete = name.length() > 0 && day.length() > 0 && time.length() > 0;
            if (complete) {
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
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.SP_CAPTION);
            // 必填字段不完整的整行标黄（原先完整与不完整长得一模一样）
            cb.setTextColor(complete ? Ui.TEXT : Ui.WARN);
            cb.setChecked(true);
            previewBox.addView(cb);
            courseChecks.add(cb);
        }
        previewInfo.setText("待导入 " + courseChecks.size() + " 门");
        previewSub.setText("其中 " + valid + " 门字段完整 · 缺字段的已标黄");
    }

    private void setAllChecked(boolean v) {
        for (CheckBox cb : courseChecks) {
            cb.setChecked(v);
        }
    }

    private void showNameErr(String msg) {
        if (nameErr == null) {
            return;
        }
        nameErr.setText(msg);
        nameErr.setVisibility(View.VISIBLE);
    }

    private void hideNameErr() {
        if (nameErr != null) {
            nameErr.setVisibility(View.GONE);
        }
    }

    private void confirmImportSelected() {
        if (parsedCourses == null || parsedCourses.length() == 0) {
            setRes(pasteRes, "还没有可导入的课程，先点「解析并预览」", Ui.WARN);
            return;
        }
        final String name = (nameBox == null) ? "" : nameBox.getText().toString().trim();
        if (name.length() == 0) {
            // 错误就地显示在名称输入框下方（原来写在页面最底部）
            showNameErr("请先填写课程表名称（必填）");
            return;
        }
        hideNameErr();
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
            setRes(previewRes, "一门课都没勾选", Ui.WARN);
            return;
        }
        final JSONArray finalPicked = picked;
        int idx = indexOfName(name);
        if (idx >= 0) {
            setRes(previewRes, "检测到同名课程表，正在读取它的课程数…", Ui.MUTED);
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
            setRes(previewRes, "构造报文失败", Ui.ERR);
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
        setRes(previewRes, "正在导入到手环…", Ui.MUTED);
        SyncEngine.get(this).send(payload, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        int count = o.optInt("count", -1);
                        setRes(previewRes, "导入成功"
                                + (count >= 0 ? ("，共 " + count + " 门课") : ""), Ui.OK);
                        // P3（§4.4）：导入成功 → 实时推送（每 kind 每分钟 ≤5 条节流在服务端）
                        ev("app_import_ok", Analytics.p("course_count", count >= 0 ? count : 0,
                                "source", selectedName == null ? "" : selectedName));
                    } else {
                        setRes(previewRes, "手环拒绝：" + o.optString("reason"), Ui.ERR);
                        // P3：手环拒绝写入 → 失败合并桶（不逐条打扰）
                        ev("app_import_fail", Analytics.p("stage", "band_reject",
                                "reason", o.optString("reason")));
                    }
                } catch (Throwable t) {
                    setRes(previewRes, "回包无法解析", Ui.ERR);
                    ev("app_import_fail", Analytics.p("stage", "reply_parse",
                            "reason", String.valueOf(t)));
                }
            }
            @Override public void onTimeout(String hint) {
                setRes(previewRes, hint, Ui.WARN);
                ev("app_import_fail", Analytics.p("stage", "timeout", "reason", hint));
            }
            @Override public void onError(String msg) {
                setRes(previewRes, "导入失败：" + msg, Ui.ERR);
                ev("app_import_fail", Analytics.p("stage", "network", "reason", msg));
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
        // 「去连接 → 回来」：引导卡随之收敛；刚连上则刷新一次数据
        if (guideCard != null) {
            boolean show = !SyncEngine.get(this).hasNode();
            if (show != guideShown) {
                guideShown = show;
                guideCard.setVisibility(show ? View.VISIBLE : View.GONE);
                if (!show) {
                    if (MODE_IMPORT.equals(mode)) {
                        loadKnownNames();
                    } else {
                        loadSchedules();
                    }
                }
            }
        }
    }
}
