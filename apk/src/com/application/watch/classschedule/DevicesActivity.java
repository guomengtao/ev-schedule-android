package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备列表页：一台手环一张卡，点进去只看这台设备的课表。
 *
 * <p>纯本地数据（ScheduleStore），零网络请求；连没连上只影响「在线」标记，
 * 不影响能不能看。这是「设备 → 课表」的上一层，取代原来把所有设备混在一个列表里折叠的做法。
 */
public class DevicesActivity extends Activity {

    /** 按设备聚合出来的一行 */
    private static final class Dev {
        String deviceId = "";
        String name = "";
        int count = 0;
        long lastAt = 0;
        boolean online = false;
    }

    private LinearLayout listBox;
    private int lastThemeVersion = 0;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScheduleStore.ensureInitialized(this);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        if (Ui.needRecreate(this, lastThemeVersion)) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, "/apk/devices");
        render();
    }

    private void buildUi() {
        LinearLayout root = Ui.screen(this);
        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(Ui.wrapWithBottomBar(this, root, 1));
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setMinimumWidth(Ui.dp(this, Ui.TOUCH_MIN));
        TextView back = Ui.textLh(this, "‹", 22f, Ui.ACCENT, false, Ui.LH_BODY);
        back.setGravity(Gravity.CENTER);
        left.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 28)));
        left.setClickable(true);
        left.setContentDescription("返回");
        left.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(left, new FrameLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_MIN), Ui.dp(this, Ui.TOUCH_MIN),
                Gravity.CENTER_VERTICAL | Gravity.START));

        TextView t = Ui.textMediumLh(this, "设备", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE);
        bar.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_VERTICAL));
        t.setGravity(Gravity.CENTER);
        return bar;
    }

    // ======================= 聚合 =======================

    /** 把所有「存进过手环」的课表按设备归并。本地课表没有设备归属，这里不显示。 */
    private List<Dev> collect() {
        List<ScheduleStore.Schedule> all = ScheduleStore.list(this);
        SyncEngine e = SyncEngine.get(this);
        String curDev = "";
        boolean connected = false;
        try {
            curDev = e.currentDeviceId();
            connected = e.connected();
        } catch (Throwable ignored) { }

        Map<String, Dev> map = new LinkedHashMap<String, Dev>();
        for (ScheduleStore.Schedule s : all) {
            if (!s.isSync()) continue;
            String key = (s.deviceId == null || s.deviceId.length() == 0
                    || "legacy-unknown".equals(s.deviceId)) ? "legacy" : s.deviceId;
            Dev d = map.get(key);
            if (d == null) {
                d = new Dev();
                d.deviceId = "legacy".equals(key) ? "" : key;
                d.name = s.deviceName == null ? "" : s.deviceName;
                map.put(key, d);
            }
            d.count++;
            if (s.syncedAt > d.lastAt) d.lastAt = s.syncedAt;
            if (d.name.length() == 0 && s.deviceName != null) d.name = s.deviceName;
        }

        List<Dev> out = new ArrayList<Dev>();
        for (Map.Entry<String, Dev> en : map.entrySet()) {
            Dev d = en.getValue();
            d.online = connected && d.deviceId.length() > 0 && d.deviceId.equals(curDev);
            if (d.name.length() == 0) {
                d.name = d.deviceId.length() == 0 ? "没认出是哪只手环" : ("手环 ··" + tail4(d.deviceId));
            }
            out.add(d);
        }
        // 在线置顶，其余按最近同步时间倒序
        java.util.Collections.sort(out, new java.util.Comparator<Dev>() {
            @Override public int compare(Dev a, Dev b) {
                if (a.online != b.online) return a.online ? -1 : 1;
                return Long.compare(b.lastAt, a.lastAt);
            }
        });
        return out;
    }

    private static String tail4(String id) {
        if (id == null || id.isEmpty()) return "";
        return id.length() <= 4 ? id : id.substring(id.length() - 4);
    }

    private static String when(long t) {
        if (t <= 0) return "还没同步过";
        long d = (System.currentTimeMillis() - t) / 1000;
        if (d < 60) return "刚刚同步";
        if (d < 3600) return (d / 60) + " 分钟前同步";
        if (d < 86400) return (d / 3600) + " 小时前同步";
        return (d / 86400) + " 天前同步";
    }

    // ======================= 渲染 =======================

    private void render() {
        if (listBox == null) return;
        listBox.removeAllViews();
        List<Dev> devs = collect();

        if (devs.isEmpty()) {
            listBox.addView(emptyState());
            return;
        }

        TextView hint = Ui.text(this, "点一台手环，只看它的课表", Ui.SP_MICRO, Ui.MUTED, false);
        hint.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), 0, Ui.dp(this, 8));
        listBox.addView(hint);

        for (Dev d : devs) {
            listBox.addView(devCard(d));
            listBox.addView(Ui.space(this, 8));
        }
    }

    private View devCard(final Dev d) {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = Ui.textMediumLh(this, d.name, Ui.SP_BODY, Ui.TEXT, Ui.LH_BODY);
        texts.addView(name);

        String sub = d.count + " 张课表 · " + when(d.lastAt);
        TextView subv = Ui.textLh(this, sub, 11.5f, Ui.MUTED, false, Ui.LH_BODY);
        if (d.online) {
            android.text.SpannableString ss = new android.text.SpannableString("● 在线 · " + sub);
            ss.setSpan(new android.text.style.ForegroundColorSpan(Ui.OK), 0, 1,
                    android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            subv.setText(ss);
        }
        texts.addView(subv);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(Ui.MUTED);
        row.addView(chev, new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16)));

        card.addView(row);
        card.setClickable(true);
        card.setContentDescription(d.name);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(DevicesActivity.this, ScheduleListActivity.class);
                i.putExtra(ScheduleListActivity.EXTRA_DEVICE_ID, d.deviceId);
                i.putExtra(ScheduleListActivity.EXTRA_DEVICE_NAME, d.name);
                startActivity(i);
            }
        });
        return card;
    }

    private View emptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 48), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView t1 = Ui.textMediumLh(this, "还没有设备", Ui.SP_SUBTITLE, Ui.TEXT, Ui.LH_SUBTITLE);
        t1.setGravity(Gravity.CENTER);
        box.addView(t1);

        TextView t2 = Ui.text(this, "把手环连上、再存一套课表进去，这里就会出现这台设备。",
                Ui.SP_MICRO, Ui.MUTED, false);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(t2);
        return box;
    }
}
