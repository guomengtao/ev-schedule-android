package com.application.watch.classschedule;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 手环快应用（EV 课程表 .rpk）版本门禁：检测 → 友好提示 → 下载最新版。
 *
 * <h3>为什么必须做</h3>
 * 2026-11-14 客诉（爱发电用户 waKT 连买三笔）暴露的不是激活失败，而是
 * <b>旧包没有成功态回显</b>：服务端写码已成功，手环界面仍停在「输入激活码」引导，
 * 用户判定自己没激活 → 再买一次。版本证据：其购买链接 {@code r=1.7.105}，
 * 而 version.js 历史最高只到 1.7.96、tag 从 v1.7.90 直接跳 v1.8.0 —— 一个无任何
 * Git 提交对应的历史渠道包。客服话术拦不住，因此在激活页做硬判断。
 *
 * <h3>阈值依据</h3>
 * <ul>
 *   <li><b>1.9.129</b>（code 1597）统一设备取号口径，修「签发与校验用不同设备号」</li>
 *   <li><b>1.9.143</b>（code 1611）补齐激活成功态回显 + 状态轮询 —— 门槛取此版本</li>
 * </ul>
 * 低于门槛不是「不能激活」，而是「激活了看不出来」，所以文案是提示更新而非阻断。
 *
 * <h3>三级降级</h3>
 * ① {@code Net.BASE/ev/rpk.json}（服务端可随时调阈值与地址，不必等用户升 APK）
 * → ② SharedPreferences 12h 缓存 → ③ {@link #FALLBACK} 内置常量。
 * 三级全失败则静默不显示，绝不因网络问题在激活页制造噪音。
 *
 * <h3>能力边界（刻意不做）</h3>
 * 安卓侧<b>没有</b>把 rpk 推到手环的通道（BLE 只传课程数据），装包必须由
 * AstroBox / Notify 完成。故只做「下载 + 去安装指引」，不虚构一键安装按钮。
 */
public final class RpkCheck {

    private static final String PREF = "ev_rpk_check";
    private static final String K_JSON = "cfg";
    private static final String K_TS = "ts";
    private static final long TTL_MS = 12L * 3600L * 1000L;
    private static final String TAG_CARD = "ev_rpk_upgrade_card";

    private static final int MIN_CODE_FALLBACK = 1611;
    private static final String MIN_NAME_FALLBACK = "1.9.143";
    private static final String LATEST_NAME_FALLBACK = "1.9.210";

    /** 内置兜底，与 app-auth/ev/rpk.json 保持一致（改一处记得改另一处）。 */
    private static final String FALLBACK =
            "{\"minAutoActivate\":{\"versionName\":\"1.9.143\",\"versionCode\":1611},"
                    + "\"latest\":{\"versionName\":\"1.9.210\",\"versionCode\":1681,"
                    + "\"page\":\"https://www.bandbbs.cn/resources/7829/\"}}";

    private RpkCheck() {
    }

    /**
     * 检测并插入升级提示卡。幂等：同一 host 只处理一次。
     * 版本够新 / 读不到版本 / 手环没连 —— 三种情况都不插入，保持激活页干净。
     */
    public static void attach(final Activity a, final LinearLayout host) {
        if (a == null || host == null || host.findViewWithTag(TAG_CARD) != null) {
            return;
        }
        final SyncEngine eng = SyncEngine.get(a);
        final int curCode = eng.versionCode;
        final String curName = eng.versionName == null ? "" : eng.versionName.trim();
        if (curCode <= 0 && curName.length() == 0) {
            return; // 还没握手，拿不到手环侧版本：不打扰
        }

        // 占位：立刻占住 tag 防重复插入，异步结果回来后再替换或移除
        final View ph = new View(a);
        ph.setTag(TAG_CARD);
        ph.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        host.addView(ph);

        final String deviceName = eng.deviceName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject cfg = loadConfig(a);
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) {
                            return;
                        }
                        ViewGroup parent = (ViewGroup) ph.getParent();
                        if (parent == null) {
                            return;
                        }
                        JSONObject min = cfg.optJSONObject("minAutoActivate");
                        JSONObject latest = cfg.optJSONObject("latest");
                        int minCode = min == null ? MIN_CODE_FALLBACK
                                : min.optInt("versionCode", MIN_CODE_FALLBACK);
                        String minName = min == null ? MIN_NAME_FALLBACK
                                : min.optString("versionName", MIN_NAME_FALLBACK);
                        String latestName = latest == null ? LATEST_NAME_FALLBACK
                                : latest.optString("versionName", LATEST_NAME_FALLBACK);

                        boolean stale = curCode > 0 ? (curCode < minCode)
                                : (compare(curName, minName) < 0);
                        if (!stale) {
                            parent.removeView(ph);
                            return;
                        }

                        String url = pickChannelUrl(cfg, deviceName);
                        String page = latest == null ? "" : latest.optString("page", "");
                        int idx = parent.indexOfChild(ph);
                        parent.removeView(ph);
                        parent.addView(buildCard(a, curName.length() > 0 ? curName
                                        : ("code " + curCode), minName, latestName, url, page, cfg),
                                idx < 0 ? parent.getChildCount() : idx);
                        Analytics.pageView(a, "/rpk/upgrade-prompt?cur="
                                + Uri.encode(curName) + "&min=" + Uri.encode(minName));
                    }
                });
            }
        }).start();
    }

    // ---------------- 配置加载 ----------------

    private static JSONObject loadConfig(Activity a) {
        SharedPreferences sp = a.getSharedPreferences(PREF, 0);
        long ts = sp.getLong(K_TS, 0L);
        String cached = sp.getString(K_JSON, "");
        if (cached.length() > 0 && System.currentTimeMillis() - ts < TTL_MS) {
            JSONObject o = parse(cached);
            if (o != null) {
                return o;
            }
        }
        String body = httpGet(Net.BASE + "/ev/rpk.json", 2500);
        JSONObject o = parse(body);
        if (o != null) {
            sp.edit().putString(K_JSON, body).putLong(K_TS, System.currentTimeMillis()).apply();
            return o;
        }
        o = parse(cached);
        if (o != null) {
            return o;
        }
        o = parse(FALLBACK);
        return o != null ? o : new JSONObject();
    }

    private static JSONObject parse(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() == 0 || t.charAt(0) != '{') {
            return null;
        }
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                return null;
            }
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * 按手环名选对应机型的 rpk 直链。选不中返回 null，退回米坛资源页
     * —— 宁可不给直链，也不推错机型包（渠道包混装会让手环显示错版）。
     */
    private static String pickChannelUrl(JSONObject cfg, String deviceName) {
        JSONObject ch = cfg.optJSONObject("channels");
        if (ch == null || TextUtils.isEmpty(deviceName)) {
            return null;
        }
        String s = deviceName.toLowerCase();
        String key = "";
        if (s.contains("10 pro") || s.contains("10pro")) {
            key = "band10pro";
        } else if (s.contains("9 pro") || s.contains("9pro")) {
            key = "band9pro";
        } else if (s.contains("band 10") || s.contains("band10")
                || s.contains("手环 10") || s.contains("手环10")) {
            key = "band10";
        } else if (s.contains("band 9") || s.contains("band9")
                || s.contains("手环 9") || s.contains("手环9")) {
            key = "band9";
        } else if (s.contains("band 11") || s.contains("band11")) {
            key = "band11";
        } else if (s.contains("s4")) {
            key = "watchs4";
        } else if (s.contains("watch")) {
            key = "watch";
        }
        String url = key.length() == 0 ? "" : ch.optString(key, "");
        return url.length() == 0 ? null : url;
    }

    // ---------------- UI ----------------

    private static View buildCard(final Activity a, final String curName, final String minName,
                                  final String latestName, final String rpkUrl,
                                  final String page, JSONObject cfg) {
        LinearLayout card = Ui.card(a);
        card.setTag(TAG_CARD);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(a);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(a, 8), Ui.dp(a, 8));
        dlp.rightMargin = Ui.dp(a, 8);
        dot.setLayoutParams(dlp);
        dot.setBackgroundDrawable(Ui.round(Ui.WARN, 4, 0, a));
        head.addView(dot);
        head.addView(Ui.textMedium(a, "手环课程表版本过旧", Ui.SP_BODY, Ui.WARN));
        card.addView(head);

        TextView body = Ui.text(a,
                "当前 v" + curName + " · 自动激活需 v" + minName + " 及以上（最新 v" + latestName + "）",
                Ui.SP_BODY, Ui.MUTED, false);
        body.setPadding(0, Ui.dp(a, 6), 0, 0);
        card.addView(body);

        TextView why = Ui.text(a,
                "旧版激活后不显示成功页，容易误以为没激活而重复下单。升级后再激活即可。",
                Ui.SP_BODY, Ui.MUTED, false);
        why.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        why.setPadding(0, Ui.dp(a, 4), 0, 0);
        card.addView(why);

        final TextView tip = Ui.text(a, "", Ui.SP_BODY, Ui.MUTED, false);
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tip.setPadding(0, Ui.dp(a, 8), 0, 0);

        final Button dl = Ui.button(a,
                rpkUrl != null ? ("下载最新版 v" + latestName) : "去下载最新版", true, null);
        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, Ui.dp(a, 10), 0, 0);
        btns.addView(dl, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(btns);
        card.addView(tip);

        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rpkUrl != null) {
                    startDownload(a, rpkUrl, "ev-course-v" + latestName + ".rpk", dl, tip);
                } else if (page.length() > 0) {
                    openUrl(a, page);
                } else {
                    Toast.makeText(a, "暂时拿不到下载地址，请到米坛搜索「Ev课程表」",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        if (page.length() > 0) {
            TextView byModel = Ui.text(a, "下载页（按手环型号选包）", Ui.SP_BODY, Ui.ACCENT, false);
            byModel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            byModel.setPadding(0, Ui.dp(a, 8), 0, 0);
            byModel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(a, page);
                }
            });
            card.addView(byModel);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 10);
        card.setLayoutParams(lp);
        return card;
    }

    private static void openUrl(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(a, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 下载 ----------------

    private static void startDownload(final Activity a, final String url, final String fileName,
                                      final Button btn, final TextView tip) {
        btn.setEnabled(false);
        btn.setText("准备下载…");
        tip.setVisibility(View.VISIBLE);
        tip.setText("");

        new Thread(new Runnable() {
            @Override
            public void run() {
                OutputStream out = null;
                Uri outUri = null;
                File outFile = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                        outUri = a.getContentResolver()
                                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (outUri != null) {
                            out = a.getContentResolver().openOutputStream(outUri);
                        }
                    }
                    if (out == null) {
                        // Android 9 及以下：写公共下载目录（清单已声明存储权限）
                        File dir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);
                        if (!dir.exists()) {
                            dir.mkdirs();
                        }
                        outFile = new File(dir, fileName);
                        out = new FileOutputStream(outFile);
                    }
                } catch (Throwable t) {
                    out = null;
                }

                if (out == null) {
                    // 落盘通道全失败：退回系统浏览器，用户仍能拿到文件
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btn.setEnabled(true);
                            btn.setText("用浏览器下载");
                            tip.setText("应用内保存失败，已改用浏览器下载。");
                            openUrl(a, url);
                        }
                    });
                    return;
                }

                final OutputStream fos = out;
                final Uri fUri = outUri;
                final File fFile = outFile;
                Net.download(url, fos, new Net.DlCb() {
                    @Override
                    public void onProgress(final long received, final long total) {
                        if (total <= 0) {
                            return;
                        }
                        final int pct = (int) (received * 100 / total);
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btn.setText("下载中 " + pct + "%");
                            }
                        });
                    }

                    @Override
                    public void onDone(final boolean ok, final String err, String shaHex) {
                        try {
                            fos.close();
                        } catch (Throwable ignored) {
                        }
                        a.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!ok) {
                                    btn.setEnabled(true);
                                    btn.setText("重试下载");
                                    tip.setText("下载失败（" + (err == null ? "网络异常" : err)
                                            + "），可点下面「下载页」用浏览器下载。");
                                    return;
                                }
                                btn.setText("已下载");
                                tip.setText("已存到「下载」目录。用 AstroBox / Notify 打开它装到手环，"
                                        + "装完回本页重新检测即可。");
                                Analytics.pageView(a, "/rpk/downloaded");
                                openRpk(a, fUri, fFile);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private static void openRpk(Activity a, Uri uri, File file) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (uri != null) {
                i.setDataAndType(uri, "application/octet-stream");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (file != null) {
                i.setDataAndType(Uri.fromFile(file), "application/octet-stream");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                return;
            }
            a.startActivity(Intent.createChooser(i, "用哪个工具安装到手环？"));
        } catch (Throwable t) {
            // 没有能打开 rpk 的工具属正常，提示已在 tip 里给出
        }
    }

    // ---------------- 版本比较 ----------------

    /** 语义化比较，仅在 versionCode 缺失时兜底。返回负数表示 a 低于 b。 */
    static int compare(String a, String b) {
        String x0 = a == null ? "" : a.replaceAll("[^0-9.]", "");
        String y0 = b == null ? "" : b.replaceAll("[^0-9.]", "");
        String[] x = x0.split("\\.");
        String[] y = y0.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length && x[i].length() > 0 ? safeInt(x[i]) : 0;
            int yi = i < y.length && y[i].length() > 0 ? safeInt(y[i]) : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }
}
