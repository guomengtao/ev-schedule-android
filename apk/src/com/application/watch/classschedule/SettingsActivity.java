package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 设置页：显示当前昵称，并允许写回手环 */
public class SettingsActivity extends Activity {

    private TextView currentView, resultView;
    private EditText nickView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "设置"));
        root.addView(Ui.space(this, 12));

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "当前昵称", 12f, Ui.MUTED, false));
        currentView = Ui.text(this, "—", 18f, Ui.TEXT, true);
        currentView.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(currentView);
        root.addView(card);
        root.addView(Ui.space(this, 10));

        LinearLayout edit = Ui.card(this);
        edit.addView(Ui.text(this, "修改昵称（会写入手环）", 12.5f, Ui.TEXT, true));
        edit.addView(Ui.space(this, 8));
        nickView = new EditText(this);
        nickView.setTextSize(14f);
        nickView.setTextColor(Ui.TEXT);
        nickView.setHint("请输入昵称");
        edit.addView(nickView);
        edit.addView(Ui.space(this, 10));
        edit.addView(Ui.button(this, "保存到手环", true, new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        }));
        root.addView(edit);
        root.addView(Ui.space(this, 10));

        resultView = Ui.text(this, "", 12.5f, Ui.MUTED, false);
        root.addView(resultView);

        root.addView(Ui.space(this, 8));
        SyncEngine e = SyncEngine.get(this);
        root.addView(Ui.mono(this, "手环 " + (e.connected()
                ? e.deviceName + " · EV " + e.versionName : "未连接")));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.mono(this, "本机 v" + version()));

        root.addView(Ui.space(this, 6));
        root.addView(Ui.bottomBar(this, 2));

        refresh();
        setContentView(Ui.wrapWithBottomBar(this, root, 2));
    }

    private String version() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void refresh() {
        SyncEngine e = SyncEngine.get(this);
        String nick = e.nickname;
        currentView.setText(nick == null || nick.length() == 0 ? "（未知，先回首页连接）" : nick);
        if (nick != null && nick.length() > 0 && nickView.getText().length() == 0) {
            nickView.setText(nick);
        }
    }

    private void save() {
        String nick = nickView.getText().toString().trim();
        if (TextUtils.isEmpty(nick)) {
            resultView.setText("昵称不能为空");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在写入手环…");
        resultView.setTextColor(Ui.MUTED);
        SyncEngine.get(this).setNickname(nick, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        SyncEngine.get(SettingsActivity.this).nickname = nickView.getText().toString().trim();
                        resultView.setText("已保存，手环首页昵称已更新");
                        resultView.setTextColor(Ui.OK);
                        refresh();
                    } else {
                        resultView.setText("手环拒绝：" + o.optString("reason"));
                        resultView.setTextColor(Ui.ERR);
                    }
                } catch (Throwable t) {
                    resultView.setText("回包无法解析：" + json);
                    resultView.setTextColor(Ui.ERR);
                }
            }
            @Override public void onTimeout(String hint) {
                resultView.setText(hint);
                resultView.setTextColor(Ui.ERR);
            }
            @Override public void onError(String msg) {
                resultView.setText("写入失败：" + msg);
                resultView.setTextColor(Ui.ERR);
            }
        });
    }
}