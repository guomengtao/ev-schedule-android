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

    public JsonEditorView(Context c) {
        super(c);
        init(c);
    }

    public JsonEditorView(Context c, AttributeSet a) {
        super(c, a);
        init(c);
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

    // ======================= 初始化 =======================

    private void init(Context c) {
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
        addView(scroll);

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
