package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 帮助与反馈 / 留言板（App 内原生页，不再用浏览器打开 feedback.html）。
 *
 * <p>替代 {@link SettingsActivity#openFeedbackPage()} 的「网页跳转」方案：
 * 全部原生渲染（复用 Ui 设计令牌：晴空蓝/夜幕蓝双主题、卡片 18 圆角、发丝线、主色），
 * 留言通过 {@link Net} 调用网页后端 {@code /api/guestbook}（POST 投稿 + GET 留言墙），
 * 与网页共用同一面墙、保留蜜罐/限流/审核，且提交即 approved 立即可见。
 *
 * <p>页面结构（自下而上）：hero 引导 → 写留言表单（类型 chips / 昵称 / 内容 / 联系方式）
 * → 留言墙（卡片列表，可刷新）→ 常见问题（折叠）→ QQ 群卡片。
 *
 * <p>这是低频功能，按 App 规范不挂底部 4 tab（首页快捷按钮触达），只保留原生回头键顶栏。
 */
public class GuestbookActivity extends Activity {

    private int lastThemeVersion = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout wallBox;
    private TextView wallCount;
    private TextView statusMsg;
    private Button submitBtn;
    private EditText nameEdit, contentEdit, contactEdit;
    private String selKind = "";
    private TextView selectedChip = null;
    private boolean sending = false;
    private int wallSize = 0;

    private static final String[] KINDS = {
            "界面问题", "功能异常", "功能建议", "字体/显示", "同步问题", "其他"
    };

    private static final class FaqItem {
        final String q, a, tag;
        FaqItem(String q, String a, String tag) {
            this.q = q;
            this.a = a;
            this.tag = tag;
        }
    }

    /** 常见问题（与网页 faq.json 同源，随 App 离线可读） */
    private static final FaqItem[] FAQ = {
            new FaqItem("退不出去 / 退不出应用",
                    "已解决：新版首页已有「退出」按钮，更新到最新版即可。", "已解决"),
            new FaqItem("输入法显示不全 / 不能使用",
                    "临时办法：把课程字号调小一档、或横屏输入。该问题已列入修复计划，修复后会在本页与版本说明中标注。", "修复中"),
            new FaqItem("界面显示问题（错位 / 黑块 / 裁切）",
                    "请在上方留言板提交，并尽量描述清楚现象与所在页面，我们会尽快处理。", "收集信息中"),
            new FaqItem("字号太大 / 太小",
                    "可在 设置 → 课程字号 调整。目前字号档位有限，想要更多档位请在留言板告诉我们。", "已有设置"),
            new FaqItem("激活码无效 / 提示错误",
                    "请核对激活码输入（易混淆字符：0/O、1/I）；仍失败请到 激活指南 页查看流程，或在留言板提交（附设备码）。", "指引"),
            new FaqItem("手环连不上 / 连接失败",
                    "1) 确认手机已安装「小米运动健康」并与手环配对；2) 在 App「手环」页重试连接；3) 关闭电池优化（设置 → 省电引导）。仍失败请提交反馈并注明手环型号。", "指引"),
            new FaqItem("课表不同步 / 同步慢",
                    "1) 确认手环与手机蓝牙连接正常；2) 打开 App 首页下拉/手动同步；3) 检查「小米运动健康」是否在后台被限制。多套课表请在 课程表管理 里切换。", "指引"),
            new FaqItem("上课提醒不响",
                    "1) 系统设置里允许 App 的通知与闹钟（精确闹钟）权限；2) 确认课表里有当天的课；3) 重启手机后提醒会自动重排。", "指引"),
            new FaqItem("桌面插件不刷新 / 显示旧课表",
                    "插件由系统按周期刷新：进入 App 内点一下首页即可强制刷新；长按插件也可手动刷新。", "指引"),
            new FaqItem("课表数据丢失",
                    "数据仍保存在手环侧：在 App 里重新同步即可拉回。多套课表在 课程表管理 里查看；建议开启云备份（高级版）。", "指引"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.screen(this);
        root.setBackgroundColor(Ui.CANVAS);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        root.addView(Ui.header(this, "帮助与反馈"));
        root.addView(hero());
        root.addView(formCard());
        root.addView(wallHeader());
        wallBox = new LinearLayout(this);
        wallBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(wallBox);
        root.addView(faqSection());
        root.addView(qqCard());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout screen = new FrameLayout(this);
        screen.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.needRecreate(this, lastThemeVersion)) {
            recreate();
            return;
        }
        lastThemeVersion = Ui.themeVersion;
        Analytics.pageView(this, "/apk/guestbook");
        loadWall();
    }

    // ======================= 区块构建 =======================

    private View hero() {
        LinearLayout c = Ui.card(this);
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView t = Ui.textMedium(this, "有问题？写下来就好", 16f, Ui.TEXT);
        TextView s = Ui.text(this, "留言提交后立即上墙，不用等审核；也可看看别人遇过的问题。",
                12.5f, Ui.MUTED, false);
        s.setPadding(0, Ui.dp(this, 4), 0, 0);
        c.addView(t);
        c.addView(s);
        return c;
    }

    private View formCard() {
        LinearLayout c = Ui.card(this);

        c.addView(label("类型（选填）"));
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        for (final String k : KINDS) {
            final TextView chip = chipView(k);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    toggleKind(k, chip);
                }
            });
            chipRow.addView(chip);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chipRow);
        c.addView(hs);

        c.addView(label("昵称（选填）"));
        nameEdit = field("匿名", 40, true);
        c.addView(nameEdit);

        c.addView(label("留言内容（必填）"));
        contentEdit = Ui.editText(this);
        contentEdit.setHint("例如：工具页黑屏，手环 9 Pro，v1.7.6");
        contentEdit.setTextSize(13f);
        contentEdit.setTextColor(Ui.TEXT);
        contentEdit.setHintTextColor(Ui.MUTED);
        contentEdit.setBackground(Ui.round(inputBg(), 10, Ui.LINE, this));
        contentEdit.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        contentEdit.setMinLines(4);
        contentEdit.setGravity(Gravity.TOP);
        c.addView(contentEdit);

        c.addView(label("联系方式（选填 · 仅作者可见）"));
        contactEdit = field("QQ / 邮箱 / 手机", 120, true);
        c.addView(contactEdit);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        submitBtn = Ui.button(this, "发表留言", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                submit();
            }
        });
        row.addView(submitBtn);
        statusMsg = Ui.text(this, "", 12f, Ui.MUTED, false);
        statusMsg.setPadding(Ui.dp(this, 10), 0, 0, 0);
        row.addView(statusMsg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(row);
        return c;
    }

    private View wallHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        TextView t = Ui.textMedium(this, "留言墙", 14f, Ui.TEXT);
        h.addView(t);
        wallCount = Ui.text(this, "", 11.5f, Ui.MUTED, false);
        wallCount.setPadding(Ui.dp(this, 8), 0, 0, 0);
        h.addView(wallCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Ui.text(this, "↻ 刷新", 11.5f, Ui.ACCENT, false);
        refresh.setClickable(true);
        refresh.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                loadWall();
            }
        });
        h.addView(refresh);
        return h;
    }

    private View faqSection() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.textMedium(this, "常见问题", 14f, Ui.TEXT);
        title.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        wrap.addView(title);
        for (final FaqItem f : FAQ) {
            wrap.addView(faqItem(f));
        }
        return wrap;
    }

    private View qqCard() {
        LinearLayout c = Ui.card(this);
        TextView t = Ui.textMedium(this, "QQ 群 936886288", 14f, Ui.TEXT);
        c.addView(t);
        TextView note = Ui.text(this,
                "Ev课程表 用户群 · 加群需回答问题：本群讨论的软件名称（3 个字）",
                11.5f, Ui.MUTED, false);
        note.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        c.addView(note);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = ghostBtn("复制群号");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyGroup();
            }
        });
        row.addView(copy);
        TextView join = ghostBtn("一键加群");
        LinearLayout.LayoutParams jlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        join.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://qm.qq.com/q/zgHwj70bUQ")));
                } catch (Throwable ignored) {
                }
            }
        });
        row.addView(join, jlp);
        c.addView(row);
        return c;
    }

    // ======================= 小组件 =======================

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12f, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        return t;
    }

    private EditText field(String hint, int max, boolean single) {
        EditText e = Ui.editText(this);
        e.setHint(hint);
        e.setTextSize(13f);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setBackground(Ui.round(inputBg(), 10, Ui.LINE, this));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        if (!single) {
            e.setMinLines(2);
        }
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        return e;
    }

    private int inputBg() {
        return Ui.isDark() ? 0xFF0B1020 : 0xFFF3F4F8;
    }

    private TextView chipView(String k) {
        TextView t = Ui.text(this, k, 12.5f, Ui.MUTED, false);
        t.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        t.setBackground(Ui.round(Ui.CARD2, 15, Ui.LINE, this));
        t.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void toggleKind(String k, TextView chip) {
        if (selectedChip == chip) {
            selKind = "";
            styleChip(chip, false);
            selectedChip = null;
            return;
        }
        if (selectedChip != null) {
            styleChip(selectedChip, false);
        }
        selKind = k;
        styleChip(chip, true);
        selectedChip = chip;
    }

    private void styleChip(TextView t, boolean on) {
        t.setBackground(Ui.round(on ? Ui.ACCENT : Ui.CARD2, 15, on ? 0 : Ui.LINE, this));
        t.setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
    }

    private TextView ghostBtn(String s) {
        TextView t = Ui.textMedium(this, s, 12.5f, Ui.ACCENT);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.round(Ui.ACCENT_LIGHT, 12, 0, this));
        t.setClickable(true);
        return t;
    }

    private void setMsg(String s, int color) {
        if (statusMsg != null) {
            statusMsg.setText(s);
            statusMsg.setTextColor(color);
        }
    }

    // ======================= 提交 =======================

    private void submit() {
        if (sending) {
            return;
        }
        final String content = contentEdit.getText().toString().trim();
        if (TextUtils.isEmpty(content)) {
            setMsg("请先写点什么再发表～", Ui.WARN);
            return;
        }
        final String name = nameEdit.getText().toString().trim();
        final String contact = contactEdit.getText().toString().trim();
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("content", content);
            body.put("kind", selKind);
            body.put("contact_type", contact.isEmpty() ? "" : "app");
            body.put("contact", contact);
            body.put("hp", "");   // 蜜罐：留空（机器人填了会被服务端当成成功但不入库）
        } catch (Throwable ignored) {
        }

        sending = true;
        submitBtn.setEnabled(false);
        submitBtn.setText("发表中…");
        setMsg("", Ui.MUTED);

        final String url = Net.BASE + "/api/guestbook";
        Net.postJson(url, body.toString(), new Net.Cb() {
            @Override public void on(final int code, final String bodyStr) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        onSubmitted(code, bodyStr, content, name);
                    }
                });
            }
        });
    }

    private void onSubmitted(int code, String bodyStr, String content, String name) {
        sending = false;
        submitBtn.setEnabled(true);
        submitBtn.setText("发表留言");

        if (code < 0) {
            setMsg("网络异常，请稍后再试；紧急问题请加 QQ 群", Ui.ERR);
            return;
        }
        try {
            JSONObject j = new JSONObject(bodyStr);
            if (j.optBoolean("ok", false)) {
                JSONObject item = j.optJSONObject("item");
                if (item != null) {
                    try {
                        item.put("fresh", true);
                    } catch (Throwable ignored) {
                    }
                    prependCard(item);
                    setMsg("已发表，就在下方第一条", Ui.OK);
                    contentEdit.setText("");
                    contactEdit.setText("");
                    if (selectedChip != null) {
                        styleChip(selectedChip, false);
                        selectedChip = null;
                        selKind = "";
                    }
                } else {
                    setMsg("提交成功", Ui.OK);
                }
            } else {
                setMsg("提交失败：" + j.optString("error", "请稍后再试"), Ui.ERR);
            }
        } catch (Throwable t) {
            setMsg("返回解析失败，请稍后再试", Ui.ERR);
        }
    }

    // ======================= 留言墙 =======================

    private void loadWall() {
        if (wallBox == null) {
            return;
        }
        showWallLoading();
        Net.get(Net.BASE + "/api/guestbook?limit=100", new Net.Cb() {
            @Override public void on(final int code, final String bodyStr) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        onWallLoaded(code, bodyStr);
                    }
                });
            }
        });
    }

    private void showWallLoading() {
        wallBox.removeAllViews();
        wallBox.addView(emptyView("加载中…"));
    }

    private void onWallLoaded(int code, String bodyStr) {
        wallBox.removeAllViews();
        if (code < 0) {
            wallBox.addView(emptyView("加载失败，点右上角刷新重试"));
            setCount(0);
            return;
        }
        try {
            JSONObject j = new JSONObject(bodyStr);
            if (!j.optBoolean("success", false)) {
                wallBox.addView(emptyView("加载失败：" + j.optString("error", "未知错误")));
                setCount(0);
                return;
            }
            JSONArray items = j.optJSONArray("items");
            if (items == null || items.length() == 0) {
                wallBox.addView(emptyView("还没有人留言，来抢第一条"));
                setCount(0);
                return;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) {
                    wallBox.addView(card(it));
                }
            }
            setCount(items.length());
        } catch (Throwable t) {
            wallBox.addView(emptyView("加载失败，请重试"));
            setCount(0);
        }
    }

    private void prependCard(JSONObject item) {
        if (wallBox == null) {
            return;
        }
        if (wallBox.getChildCount() == 1) {
            View only = wallBox.getChildAt(0);
            if (only instanceof TextView) {
                wallBox.removeAllViews();
            }
        }
        wallBox.addView(card(item), 0);
        wallSize++;
        setCount(wallSize);
    }

    private void setCount(int n) {
        wallSize = n;
        if (wallCount != null) {
            wallCount.setText(n > 0 ? ("共 " + n + " 条") : "");
        }
    }

    private View emptyView(String s) {
        TextView t = Ui.text(this, s, 12.5f, Ui.MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        return t;
    }

    private View card(JSONObject g) {
        boolean fresh = g.optBoolean("fresh", false);
        LinearLayout c = Ui.card(this);
        if (fresh) {
            c.setBackground(Ui.round(Ui.CARD, Ui.R_CARD, Ui.ACCENT, this));
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        String name = g.optString("name", "");
        if (name.isEmpty()) {
            name = "匿名";
        }
        String initial = "匿".equals(name) ? "匿" : name.substring(0, 1);
        TextView av = Ui.textMedium(this, initial, 13f, Ui.ACCENT);
        av.setGravity(Gravity.CENTER);
        int avSz = Ui.dp(this, 30);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avSz, avSz);
        av.setLayoutParams(alp);
        av.setBackground(Ui.round(Ui.CARD2, 15, 0, this));
        head.addView(av);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 10), 0, 0, 0);
        col.addView(Ui.textMedium(this, name, 13f, Ui.TEXT));
        TextView tm = Ui.text(this, timeAgo(g.optString("created_at", "")), 11f, Ui.MUTED, false);
        tm.setPadding(0, Ui.dp(this, 2), 0, 0);
        col.addView(tm);
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String kind = g.optString("kind", "");
        if (kind != null && kind.length() > 0) {
            TextView kb = Ui.text(this, kind, 10.5f, Ui.ACCENT, false);
            kb.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
            kb.setBackground(Ui.round(Ui.ACCENT_LIGHT, 10, 0, this));
            head.addView(kb);
        }
        c.addView(head);

        TextView ct = Ui.textLh(this, g.optString("content", ""), 13f, Ui.TEXT, false, Ui.LH_BODY);
        ct.setPadding(0, Ui.dp(this, 8), 0, 0);
        c.addView(ct);
        return c;
    }

    // ======================= FAQ 折叠 =======================

    private View faqItem(final FaqItem f) {
        final LinearLayout c = Ui.card(this);
        c.setClickable(true);

        LinearLayout qrow = new LinearLayout(this);
        qrow.setOrientation(LinearLayout.HORIZONTAL);
        qrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView q = Ui.textMedium(this, f.q, 13f, Ui.TEXT);
        q.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        qrow.addView(q);
        TextView tag = Ui.text(this, f.tag, 10.5f, tagColor(f.tag), false);
        tag.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
        tag.setBackground(Ui.round(tagBg(f.tag), 10, 0, this));
        qrow.addView(tag);
        final TextView arrow = Ui.text(this, "›", 16f, Ui.MUTED, false);
        arrow.setPadding(Ui.dp(this, 8), 0, 0, 0);
        qrow.addView(arrow);
        c.addView(qrow);

        final TextView a = Ui.textLh(this, f.a, 12.5f, Ui.MUTED, false, Ui.LH_BODY);
        a.setPadding(0, Ui.dp(this, 8), 0, 0);
        a.setVisibility(View.GONE);
        c.addView(a);

        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean open = a.getVisibility() != View.VISIBLE;
                a.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow.setText(open ? "⌄" : "›");
            }
        });
        return c;
    }

    private int tagColor(String tag) {
        if ("已解决".equals(tag)) {
            return Ui.OK;
        }
        if ("修复中".equals(tag)) {
            return Ui.WARN;
        }
        return Ui.MUTED;
    }

    private int tagBg(String tag) {
        if ("已解决".equals(tag)) {
            return Ui.OK_LIGHT;
        }
        if ("修复中".equals(tag)) {
            return Ui.WARN_LIGHT;
        }
        return Ui.CARD2;
    }

    // ======================= QQ 群 =======================

    private void copyGroup() {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("QQ群", "936886288"));
                setMsg("群号已复制：936886288", Ui.OK);
                return;
            }
        } catch (Throwable ignored) {
        }
        setMsg("复制失败，请手动记录：936886288", Ui.ERR);
    }

    // ======================= 时间 =======================

    private String timeAgo(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "";
        }
        long t;
        try {
            t = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(iso).getTime();
        } catch (Throwable e) {
            try {
                t = new Date(iso).getTime();
            } catch (Throwable e2) {
                return "";
            }
        }
        long d = (System.currentTimeMillis() - t) / 1000;
        if (d < 60) {
            return "刚刚";
        }
        if (d < 3600) {
            return (d / 60) + " 分钟前";
        }
        if (d < 86400) {
            return (d / 3600) + " 小时前";
        }
        if (d < 86400L * 7) {
            return (d / 86400) + " 天前";
        }
        try {
            return new SimpleDateFormat("MM-dd", Locale.US).format(new Date(t));
        } catch (Throwable e) {
            return "";
        }
    }
}
