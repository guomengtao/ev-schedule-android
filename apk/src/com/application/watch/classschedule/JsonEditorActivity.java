package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 通用 JSON 编辑页（编辑课表 / 新建课表 / 导入 复用）。
 *
 * 入参（Intent extras）：
 *   EXTRA_TITLE      — 页面标题
 *   EXTRA_JSON       — 初始 JSON 内容
 *   EXTRA_HINT       — 自定义提示文案（可选，默认「复制给 AI 协助编辑」）
 *   EXTRA_SAVE_LABEL — 保存按钮文字（可选，默认「更新」）
 *
 * 返回：
 *   RESULT_OK + RESULT_JSON = 用户编辑并通过校验的 JSON 文本
 *   RESULT_CANCELLED = 用户取消
 */
public class JsonEditorActivity extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_JSON = "json";
    public static final String EXTRA_HINT = "hint";
    public static final String EXTRA_SAVE_LABEL = "saveLabel";
    public static final String RESULT_JSON = "json";

    private static final String DEFAULT_HINT =
            "把 JSON 复制给 AI，说「帮我加一节周三第 3-4 节的体育课，在体育馆」即可快速编辑";

    private JsonEditorView editor;
    private TextView statusBar;
    private int lastThemeVersion = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        String json = getIntent().getStringExtra(EXTRA_JSON);
        editor.setJson(json);
        // 初始内容若已合法，先触发一次高亮+校验
        editor.setOnChangeListener(new Runnable() {
            @Override public void run() { refreshStatus(); }
        });
        refreshStatus();
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

    private void buildUi() {
        LinearLayout root = Ui.screen(this);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.length() == 0) {
            title = "编辑课表";
        }
        root.addView(Ui.header(this, title));
        root.addView(Ui.space(this, 8));

        // 编辑器（工具栏内置于组件：复制/粘贴/清空/格式化/高度切换）
        editor = new JsonEditorView(this);
        root.addView(editor);
        root.addView(Ui.space(this, 6));

        // AI 提示条
        String hint = getIntent().getStringExtra(EXTRA_HINT);
        if (hint == null || hint.length() == 0) {
            hint = DEFAULT_HINT;
        }
        TextView hintView = Ui.text(this, hint, 11.5f, Ui.MUTED, false);
        hintView.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        root.addView(hintView);
        root.addView(Ui.space(this, 8));

        // 实时校验条
        statusBar = Ui.text(this, "", 12f, Ui.MUTED, false);
        root.addView(statusBar);
        root.addView(Ui.space(this, 10));

        // 保存按钮
        String saveLabel = getIntent().getStringExtra(EXTRA_SAVE_LABEL);
        if (saveLabel == null || saveLabel.length() == 0) {
            saveLabel = "更新";
        }
        root.addView(Ui.button(this, saveLabel, true, new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndFinish(); }
        }));

        setContentView(Ui.wrapWithBottomBar(this, root, -1));
    }

    private void refreshStatus() {
        if (editor.validate()) {
            int lines = countLines(editor.getJson());
            statusBar.setText("✓ JSON 格式正确（" + lines + " 行）");
            statusBar.setTextColor(Ui.OK);
        } else {
            statusBar.setText("✕ " + editor.getError());
            statusBar.setTextColor(Ui.ERR);
        }
    }

    private int countLines(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private void saveAndFinish() {
        if (!editor.validate()) {
            refreshStatus();
            Toast.makeText(this, "JSON 格式有误，请看上方提示", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra(RESULT_JSON, editor.getJson());
        setResult(RESULT_OK, data);
        finish();
    }
}
