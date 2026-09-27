package com.application.watch.classschedule;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 多步骤调试页：每一步独立可点，逐步看连接状态。
 * 与首页的区别：这里不做自动化，纯粹是"哪一步不通"的定位工具。
 */
public class DebugActivity extends Activity {

    private static final int STEPS = 4;
    private static final String[] LABELS =
            {"1 初始化穿戴服务", "2 查找已连接设备", "3 申请设备权限", "4 连接 EV 课程表"};
    private static final String[] SUBS = {
            "检测小米穿戴服务是否可用",
            "拿到 nodeId（手环必须已连接）",
            "DEVICE_MANAGER + NOTIFY",
            "ping 手环上的 EV 课程表"
    };

    private final int[] states = new int[STEPS];
    private final String[] details = new String[STEPS];
    private final LinearLayout[] rows = new LinearLayout[STEPS];
    private TextView logView;
    private EditText inputView;
    private final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.addView(Ui.title(this, "调试"));
        root.addView(Ui.space(this, 4));
        root.addView(Ui.text(this, "按顺序点击任一步骤，逐步定位问题", 12f, Ui.MUTED, false));
        root.addView(Ui.space(this, 12));

        // 步骤列表
        for (int i = 0; i < STEPS; i++) {
            final int idx = i;
            states[i] = SyncEngine.PENDING;
            details[i] = "待执行";
            rows[i] = Ui.row(this, LABELS[i], SUBS[i] + " · " + details[i], Ui.MUTED,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { runStep(idx); }
                    });
            root.addView(rows[i]);
            root.addView(Ui.space(this, 6));
        }

        root.addView(Ui.grid(this,
                Ui.button(this, "全部执行", true, new View.OnClickListener() {
                    @Override public void onClick(View v) { runAll(); }
                }),
                Ui.button(this, "重置状态", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { reset(); }
                })));
        root.addView(Ui.grid(this,
                Ui.button(this, "EV 是否安装", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { evInstalled(); }
                }),
                Ui.button(this, "拉起手环 EV", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { launchEv(); }
                })));
        root.addView(Ui.grid(this,
                Ui.button(this, "手表通知(验下行)", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { notifyTest(); }
                }),
                Ui.button(this, "清屏", false, new View.OnClickListener() {
                    @Override public void onClick(View v) { logView.setText(""); }
                })));

        inputView = new EditText(this);
        inputView.setText("{\"action\":\"ping\"}");
        inputView.setTextSize(11f);
        inputView.setTextColor(Ui.TEXT);
        root.addView(inputView);

        root.addView(Ui.button(this, "发送自定义 JSON", true, new View.OnClickListener() {
            @Override public void onClick(View v) { sendRaw(); }
        }));

        root.addView(Ui.space(this, 8));
        logView = Ui.mono(this, "");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 调试页也保留常驻底栏（不属于三个 tab，故不高亮任何一项）
        setContentView(Ui.fixedWithBottomBar(this, root, -1));

        // 进页面不自动弹出输入法（把焦点交给根布局，EditText 不抢焦点）
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    // ======================= 步骤 =======================

    private void runStep(final int i) {
        setState(i, SyncEngine.RUNNING, "执行中…");
        final SyncEngine.Cb cb = new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                setState(i, ok ? SyncEngine.OK : SyncEngine.FAIL, msg);
                log((ok ? "[OK] " : "[FAIL] ") + LABELS[i] + " → " + msg);
            }
        };
        SyncEngine e = SyncEngine.get(this);
        switch (i) {
            case 0: e.step1Service(cb); break;
            case 1: e.step2Nodes(cb); break;
            case 2: e.step3Perm(cb); break;
            default: e.step4Ping(cb); break;
        }
    }

    private void runAll() {
        reset();
        log("—— 开始全部执行 ——");
        runStep(0);
        for (int i = 1; i < STEPS; i++) {
            final int idx = i;
            logView.postDelayed(new Runnable() {
                @Override public void run() {
                    if (states[idx - 1] == SyncEngine.OK) {
                        runStep(idx);
                    } else {
                        setState(idx, SyncEngine.PENDING, "前一步未完成，已跳过");
                    }
                }
            }, 1500L * i);
        }
    }

    private void reset() {
        for (int i = 0; i < STEPS; i++) {
            setState(i, SyncEngine.PENDING, "待执行");
        }
    }

    private void setState(int i, int state, String detail) {
        states[i] = state;
        details[i] = detail;
        int color = Ui.MUTED;
        if (state == SyncEngine.OK) {
            color = Ui.OK;
        } else if (state == SyncEngine.RUNNING) {
            color = Ui.ACCENT;
        } else if (state == SyncEngine.FAIL) {
            color = Ui.ERR;
        }
        String mark = (state == SyncEngine.OK) ? "✓ " : (state == SyncEngine.FAIL) ? "✕ " : "";
        rows[i].removeAllViews();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(Ui.text(this, mark + LABELS[i], 13.5f, color, true));
        TextView s = Ui.text(this, SUBS[i] + " · " + detail, 11.5f, Ui.MUTED, false);
        s.setPadding(0, Ui.dp(this, 2), 0, 0);
        inner.addView(s);
        rows[i].addView(inner);
    }

    private void evInstalled() {
        SyncEngine.get(this).evInstalled(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                log("EV 已安装? " + msg);
            }
        });
    }

    private void launchEv() {
        SyncEngine.get(this).launchEv(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) { log("拉起 EV → " + msg); }
        });
    }

    private void notifyTest() {
        SyncEngine.get(this).notifyTest(new SyncEngine.Cb() {
            @Override public void on(boolean ok, String msg) {
                log("手表通知 → " + msg + (ok ? "（请查看手环是否弹出通知）" : ""));
            }
        });
    }

    private void sendRaw() {
        final String json = inputView.getText().toString();
        if (TextUtils.isEmpty(json)) {
            log("报文为空");
            return;
        }
        log(">> TX  " + json);
        SyncEngine.get(this).send(json, new SyncEngine.Reply() {
            @Override public void onReply(String text) {
                log("<< RX  len=" + text.length());
                log("   " + (text.length() > 500 ? text.substring(0, 500) + " …" : text));
            }
            @Override public void onTimeout(String hint) { log("!! " + hint); }
            @Override public void onError(String msg) { log("!! " + msg); }
        });
    }

    private void log(String s) {
        logView.append(TS.format(new Date()) + "  " + s + "\n");
    }
}
