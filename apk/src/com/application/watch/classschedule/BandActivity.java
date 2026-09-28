package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 手环页（底栏第 3 tab，设备作用域）：连接管理 + 昵称 + 首页设置 + 留言入口。
 *
 * 与「设置」页的分工：凡是「写回手环才生效 / 设备仅有的」配置都在这里；
 * App 自身行为（后台常驻、上课提醒、主题、高级版、打赏、更新）在「设置」页。
 * 多手环：连接与记忆逻辑在 SyncEngine（preferredNodeId），这里提供查看与重置。
 */
public class BandActivity extends Activity {

    private int lastThemeVersion = 0;

    private TextView currentView, resultView, devStatusView;
    private EditText nickView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.topBar(this, "手环"));
        root.addView(Ui.space(this, 12));

        // ===== 设备头部：连接管理（多手环时在此查看/重置记忆） =====
        LinearLayout devCard = Ui.card(this);
        devStatusView = Ui.text(this, devText(), 12.5f, Ui.MUTED, false);
        devStatusView.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        devCard.addView(devStatusView);
        devCard.addView(Ui.button(this, "重新选择设备", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                SyncEngine.get(BandActivity.this).setPreferredNodeId("");
                devStatusView.setText(devText());
                resultView.setText("已清除记忆，下次连接会重新询问");
                resultView.setTextColor(Ui.OK);
            }
        }));
        root.addView(devCard);
        root.addView(Ui.space(this, 10));

        // ===== 昵称（写回手环） =====
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

        // ===== 首页设置（模板/字号，写回手环） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "首页设置", "显示开关 / 模板 / 字号（写回手环生效）", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, HomepageSettingsActivity.class));
                    }
                }));

        // ===== 留言（原底栏入口取消后的固定去处） =====
        root.addView(Ui.space(this, 6));
        root.addView(Ui.row(this, "留言", "给手环发消息 / 查看手环发来的留言", Ui.TEXT,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(BandActivity.this, MessageActivity.class));
                    }
                }));

        refresh();
        setContentView(Ui.wrapWithBottomBar(this, root, 2));
        Analytics.pageView(this, "/apk/band");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastThemeVersion != 0 && lastThemeVersion != Ui.themeVersion) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        refresh();
        devStatusView.setText(devText());
    }

    private String devText() {
        SyncEngine e = SyncEngine.get(this);
        if (e.connected()) {
            String name = (e.deviceName == null || e.deviceName.length() == 0)
                    ? "手环" : e.deviceName;
            return "● 已连接：" + name + "  ·  EV " + e.versionName;
        }
        return "○ 未连接：打开首页会自动连接手环；如有多台设备，连接时会询问选哪台";
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
        if (!SyncEngine.get(this).connected()) {
            resultView.setText("手环未连接：请先回首页连接手环");
            resultView.setTextColor(Ui.WARN);
            return;
        }
        resultView.setText("正在写入手环…");
        resultView.setTextColor(Ui.MUTED);
        SyncEngine.get(this).setNickname(nick, new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (o.optBoolean("ok", false)) {
                        SyncEngine.get(BandActivity.this).nickname =
                                nickView.getText().toString().trim();
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
