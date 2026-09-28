package com.application.watch.classschedule;

import android.content.Context;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用 JSON 编辑器（方案：编辑/新建/导入复用）。
 *
 * 能力：
 *   - 行号区（左）+ 代码区（右），共用一个 ScrollView → 滚动天然同步
 *   - 语法高亮：key 蓝 / 字符串值 绿 / 数字·布尔·null 紫 / 标点 默认文字色
 *   - 实时格式校验，失败时可取出「第 X 行第 Y 列」的错误信息
 *   - 对外：setJson / getJson / validate / getError / setOnChangeListener
 *
 * ⚠️ 课表 JSON 通常 < 100 行，每次输入重扫全文可忽略；不做增量解析。
 */
public class JsonEditorView extends FrameLayout {

    /** JSON key 颜色 */
    private static final int KEY = 0xFF2F6BFF;
    /** JSON 字符串值颜色 */
    private static final int STR = 0xFF16A34A;
    /** JSON 数字/布尔/null 颜色 */
    private static final int NUM = 0xFF8B5CF6;

    private TextView lineNumberView;
    private EditText editText;
    private boolean applying = false;
    private String lastError = "";

    // 工具栏与高度控制（组件内置：所有使用方——编辑/新建/导入——共享同一套能力）
    private android.widget.Button expandBtn;
    private int rows = 14;
    private boolean expanded = false;
    private static final int ROW_H_DP = 22;
    private static final int EXPANDED_ROWS = 26;

    public JsonEditorView(Context c) {
        super(c);
        init(c);
    }

    public JsonEditorView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
    }

    // ======================= 高度控制 =======================

    /** 以「行数」控制编辑区高度（默认 14 行）。 */
    public void setRows(int n) {
        rows = Math.max(4, n);
        expanded = false;
        if (expandBtn != null) {
            expandBtn.setText("展开");
        }
        applyHeight();
    }

    private void applyHeight() {
        int h = Ui.dp(getContext(), (expanded ? EXPANDED_ROWS : rows) * ROW_H_DP);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) {
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h));
        } else {
            lp.height = h;
            setLayoutParams(lp);
        }
    }

    private void toggleHeight() {
        expanded = !expanded;
        if (expandBtn != null) {
            expandBtn.setText(expanded ? "收起" : "展开");
        }
        applyHeight();
    }

    // ======================= 工具栏动作 =======================

    private void copyToClip() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("json", getJson()));
            toast("已复制 " + getJson().length() + " 字符");
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    private void pasteFromClip() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = cm.getPrimaryClip();
            String text = (clip != null && clip.getItemCount() > 0)
                    ? String.valueOf(clip.getItemAt(0).coerceToText(getContext())) : "";
            if (text.trim().length() == 0) {
                toast("剪贴板是空的");
                return;
            }
            setJson(text);
            toast("已粘贴 " + text.length() + " 字符");
        } catch (Throwable t) {
            toast("粘贴失败");
        }
    }

    private void clearAll() {
        setJson("");
        toast("已清空");
    }

    private void doFormat() {
        if (format()) {
            toast("已格式化");
        } else {
            toast("JSON 不合法，无法格式化");
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ======================= 对外 API =======================

    public void setJson(String json) {
        editText.setText(json == null ? "" : json);
        editText.setSelection(0);
    }

    public String getJson() {
        return editText.getText().toString();
    }

    /** 校验 JSON 合法性；失败时可 {@link #getError()} 取行号+原因 */
    public boolean validate() {
        String text = getJson().trim();
        if (text.length() == 0) {
            lastError = "JSON 不能为空";
            return false;
        }
        try {
            new JSONObject(text);
            lastError = "";
            return true;
        } catch (JSONException e) {
            try {
                new JSONArray(text);
                lastError = "";
                return true;
            } catch (JSONException e2) {
                lastError = positionError(text, e.getMessage());
                return false;
            }
        }
    }

    public String getError() {
        return lastError;
    }

    /** 美化缩进（解析 → toString(2) 回填）。非法时返回 false */
    public boolean format() {
        String text = getJson().trim();
        if (text.length() == 0) {
            return false;
        }
        try {
            Object o;
            try {
                o = new JSONObject(text);
            } catch (JSONException e) {
                o = new JSONArray(text);
            }
            String pretty = (o instanceof JSONObject)
                    ? ((JSONObject) o).toString(2)
                    : ((JSONArray) o).toString(2);
            editText.setText(pretty);
            return true;
        } catch (JSONException e) {
            lastError = positionError(text, e.getMessage());
            return false;
        }
    }

    public void setOnChangeListener(final Runnable l) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void afterTextChanged(Editable s) {
                if (l != null) {
                    l.run();
                }
            }
        });
    }

    /** 工具栏小按钮 */
    private android.widget.Button toolBtn(Context c, String label, final Runnable action) {
        android.widget.Button b = new android.widget.Button(c);
        b.setText(label);
        b.setTextSize(11);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        b.setBackground(Ui.round(Ui.CARD, 8, Ui.LINE, c));
        b.setTextColor(Ui.TEXT);
        b.setAllCaps(false);
        b.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            }
        });
        return b;
    }

    // ======================= 初始化 =======================

    private void init(Context c) {
        // 内层竖排：工具栏在代码区上方（FrameLayout 无法直接竖排，包一层）
        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);

        // 工具栏：复制 / 粘贴 / 清空 / 格式化 / 展开收起（所有使用方共享）
        LinearLayout tools = new LinearLayout(c);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), 0);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tools.addView(toolBtn(c, "复制", new Runnable() {
            @Override public void run() { copyToClip(); }
        }), tp);
        tools.addView(toolBtn(c, "粘贴", new Runnable() {
            @Override public void run() { pasteFromClip(); }
        }), tp);
        tools.addView(toolBtn(c, "清空", new Runnable() {
            @Override public void run() { clearAll(); }
        }), tp);
        tools.addView(toolBtn(c, "格式化", new Runnable() {
            @Override public void run() { doFormat(); }
        }), tp);
        expandBtn = toolBtn(c, "展开", new Runnable() {
            @Override public void run() { toggleHeight(); }
        });
        tools.addView(expandBtn, tp);
        inner.addView(tools);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);

        lineNumberView = new TextView(c);
        lineNumberView.setTextColor(Ui.MUTED);
        lineNumberView.setTextSize(13);
        lineNumberView.setTypeface(android.graphics.Typeface.MONOSPACE);
        lineNumberView.setGravity(Gravity.TOP | Gravity.RIGHT);
        lineNumberView.setPadding(0, Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10));
        // 行号区宽度固定，可容纳 4 位行号
        int lineW = Ui.dp(c, 36);
        row.addView(lineNumberView, new LinearLayout.LayoutParams(
                lineW, ViewGroup.LayoutParams.WRAP_CONTENT));

        editText = new EditText(c);
        editText.setBackground(null);
        editText.setTextSize(13);
        editText.setTypeface(android.graphics.Typeface.MONOSPACE);
        editText.setTextColor(Ui.TEXT);
        editText.setSingleLine(false);
        editText.setHorizontalScrollBarEnabled(false);
        editText.setVerticalScrollBarEnabled(false);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10));
        editText.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row.addView(editText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scroll.addView(row);
        inner.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(inner);
        applyHeight();

        // 背景与圆角（仿代码块）
        setBackground(Ui.round(Ui.CARD2, 10, Ui.LINE, c));
        setPadding(0, 0, 0, 0);

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
            @Override public void afterTextChanged(Editable s) {
                if (applying) {
                    return;
                }
                applying = true;
                try {
                    updateLineNumbers(s.toString());
                    applyHighlight(s);
                } finally {
                    applying = false;
                }
            }
        });
    }

    private void updateLineNumbers(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        StringBuilder sb = new StringBuilder(lines * 3);
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        lineNumberView.setText(sb.toString());
    }

    // ======================= 语法高亮 =======================

    private void applyHighlight(Editable e) {
        String text = e.toString();
        for (ForegroundColorSpan span : e.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            e.removeSpan(span);
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"') {
                int start = i;
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    if (d == '"') {
                        j++;
                        break;
                    }
                    j++;
                }
                int end = Math.min(j, n);
                // 判定是否为 key：字符串结束后第一个非空白字符是 ':'
                int k = end;
                while (k < n) {
                    char w = text.charAt(k);
                    if (w != ' ' && w != '\t' && w != '\n' && w != '\r') {
                        break;
                    }
                    k++;
                }
                boolean isKey = k < n && text.charAt(k) == ':';
                e.setSpan(new ForegroundColorSpan(isKey ? KEY : STR),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
            } else if (c == '-' || c == '+' || Character.isDigit(c)
                    || c == 't' || c == 'f' || c == 'n' || c == '.') {
                int start = i;
                int j = i;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == ',' || d == '}' || d == ']' || d == ':'
                            || d == ' ' || d == '\t' || d == '\n' || d == '\r') {
                        break;
                    }
                    j++;
                }
                if (j > start) {
                    String token = text.substring(start, j);
                    if (token.matches("[-+]?[0-9.eE+]+|true|false|null")) {
                        e.setSpan(new ForegroundColorSpan(NUM),
                                start, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
    }

    // ======================= 错误行号定位 =======================

    private static final Pattern CHAR_RE = Pattern.compile("character (\\d+)");

    private String positionError(String text, String msg) {
        if (msg == null) {
            return "JSON 格式错误";
        }
        int charIdx = -1;
        Matcher m = CHAR_RE.matcher(msg);
        if (m.find()) {
            try {
                charIdx = Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        if (charIdx < 0) {
            return msg;
        }
        int line = 1, col = 1;
        int max = Math.min(charIdx, text.length());
        for (int i = 0; i < max; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return "第 " + line + " 行第 " + col + " 列：" + msg;
    }
}
