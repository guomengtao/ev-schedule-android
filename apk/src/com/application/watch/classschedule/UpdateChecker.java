package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ProgressBar;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * App 自动升级（整包，脱离应用商店）。
 *
 * 流程：启动静默 GET 服务端 update-&lt;variant&gt;.json（Vercel/静态托管）
 *   → versionCode 对比 → 弹窗 → 下载（镜像优先、直连兜底，边下边算 sha256）
 *   → 写入 getExternalFilesDir → MiniFileProvider + ACTION_INSTALL_PACKAGE 触发系统安装。
 *
 * 安装触发：ACTION_INSTALL_PACKAGE（2026-10-05 由 ACTION_VIEW 迁移——泛化 VIEW 在
 * 部分机型会被 WPS 等文件 App 抢注处理器，出现"用 WPS 打开"装不上；
 * ACTION_INSTALL_PACKAGE 只有系统安装器响应）。注意它与 PackageInstaller.commit
 * 会话 API 不同（后者 EMUI 8 会静默丢弃）。见 docs/自动升级实现方案.md §7。
 *
 * 服务端 JSON 字段：versionCode / versionName / sha256 /
 *   downloadUrlMirror（国内镜像，优先尝试）/ downloadUrlOrigin（GitHub 直链，兜底）/
 *   isForce（弹窗不可取消）/ updateLog（弹窗正文）。
 */
public final class UpdateChecker {

    private static final String TAG = "EVUpdate";
    private static final String APK_NAME = "update.apk";
    private static final String IGNORE_PREF = "update_ignored_version";

    private UpdateChecker() {
    }

    /** 静默检查（首页启动调用）：网络失败/无更新都不打扰。 */
    public static void checkSilent(final Activity a) {
        check(a, false);
    }

    /** 手动检查（设置页入口调用）：失败、无更新、有更新均有提示。 */
    public static void checkManual(final Activity a) {
        check(a, true);
    }

    /**
     * 检查更新。manual=true 时（设置页手动入口）无更新/失败均给提示；
     * manual=false 时仅发现新版本才弹窗。
     */
    static void check(final Activity a, final boolean manual) {
        // JSON 按变体分文件（ev / evbox 各一条版本线），变体名运行期从清单 meta-data 读
        Net.get(Net.BASE + "/ev/update-" + Variant.name(a) + ".json", new Net.Cb() {
            @Override public void on(int code, String body) {
                JSONObject j = parse(code, body);
                if (j == null) {
                    if (manual) {
                        toast(a, "检查更新失败，请稍后重试");
                    }
                    return;
                }
                int remote = j.optInt("versionCode", -1);
                android.util.Log.i(TAG, "check: code=" + code + " remote=" + remote
                        + " local=" + localCode(a) + " ignored=" + ignoredVersion(a));
                if (remote <= localCode(a)) {
                    if (manual) {
                        toast(a, "已是最新版本");
                    }
                    return;
                }
                // 「忽略此版本」：静默检查时如果此版本已被用户忽略，不再弹窗
                if (!manual && remote == ignoredVersion(a)) {
                    return;
                }
                // 更新漏斗：发现新版本（channel=inapp 与下载页/手环入口区分）
                Analytics.pageView(a, "/apk/update-found?from=" + localCode(a)
                        + "&to=" + remote + "&c=inapp");
                // P3（§4.4）：同口径事件化（dedupeKey 按天+目标版本幂等，防每次启动重报）
                Analytics.event(a, "app_update_found",
                        Analytics.p("from_code", localCode(a), "to_code", remote),
                        Analytics.dedupeKey(a, "app_update_found", String.valueOf(remote)));
                show(a, j);
            }
        });
    }

    private static JSONObject parse(int code, String body) {
        if (code != 200 || body == null) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int localCode(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int ignoredVersion(Activity a) {
        return a.getSharedPreferences(IGNORE_PREF, android.content.Context.MODE_PRIVATE)
                .getInt("code", -1);
    }

    private static void ignoreVersion(Activity a, int code) {
        a.getSharedPreferences(IGNORE_PREF, android.content.Context.MODE_PRIVATE)
                .edit().putInt("code", code).apply();
    }

    private static void show(final Activity a, final JSONObject j) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                final boolean force = j.optBoolean("isForce", false) || j.optBoolean("force", false);
                String log = j.optString("updateLog", "");
                if (log.length() == 0) {
                    log = j.optString("log", "无详细更新说明");
                }
                final String ver = j.optString("versionName", "");
                final int code = j.optInt("versionCode", -1);
                final Dialog[] holder = new Dialog[1];

                // ---- 自绘升级卡（与 Dialogs 同一视觉语言）：版本章 + 标题 + 可滚动更新说明 + 主/次按钮 ----
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = Ui.dp(a, 22);
                box.setBackground(Ui.round(Ui.CARD, 22, 0, a));
                box.setPadding(pad, pad, pad, pad);

                LinearLayout head = new LinearLayout(a);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView chip = Ui.text(a, "v" + ver, 11.5f, 0xFFFFFFFF, true);
                chip.setBackground(Ui.round(Ui.ACCENT, 20, 0, a));
                chip.setPadding(Ui.dp(a, 10), Ui.dp(a, 3), Ui.dp(a, 10), Ui.dp(a, 3));
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                chipLp.rightMargin = Ui.dp(a, 9);
                head.addView(chip, chipLp);
                head.addView(Ui.text(a, "发现新版本", 16.5f, Ui.TEXT, true));
                box.addView(head);
                box.addView(Ui.space(a, 12));

                // 更新说明（长文案限高滚动，短文案自适应；左对齐易读）
                ScrollView sv = new ScrollView(a);
                sv.setVerticalScrollBarEnabled(false);
                TextView body = Ui.text(a, log, 13.5f, Ui.TEXT, false);
                body.setLineSpacing(Ui.dp(a, 3), 1f);
                sv.addView(body, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                int svH = (log.length() > 90)
                        ? Ui.dp(a, 170)
                        : LinearLayout.LayoutParams.WRAP_CONTENT;
                box.addView(sv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, svH));
                box.addView(Ui.space(a, 6));
                box.addView(Ui.text(a, "覆盖安装保留课表与手环配对数据", 11f, Ui.MUTED, false));
                box.addView(Ui.space(a, 14));

                LinearLayout btns = new LinearLayout(a);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                Button later = Ui.button(a, "稍后再说", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                    }
                });
                later.setBackground(Ui.round(0x00000000, 12, Ui.LINE, a));
                later.setTextColor(Ui.TEXT);
                btns.addView(later, new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f));
                LinearLayout.LayoutParams lp0 = (LinearLayout.LayoutParams) btns.getChildAt(0).getLayoutParams();
                lp0.rightMargin = Ui.dp(a, 10);
                Button go = Ui.button(a, "立即更新", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        if (!canInstall(a)) {
                            requestInstallPermission(a);
                            return;
                        }
                        downloadAndInstall(a, j);
                    }
                });
                go.setBackground(Ui.round(Ui.ACCENT, 12, 0, a));
                go.setTextColor(0xFFFFFFFF);
                btns.addView(go, new LinearLayout.LayoutParams(0, Ui.dp(a, 42), 1f));
                box.addView(btns);

                if (!force) {
                    box.addView(Ui.space(a, 8));
                    TextView ig = Ui.text(a, "忽略此版本", 12f, Ui.MUTED, false);
                    ig.setGravity(Gravity.CENTER);
                    ig.setClickable(true);
                    ig.setPadding(0, Ui.dp(a, 6), 0, 0);
                    ig.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            ignoreVersion(a, code);
                            toast(a, "已忽略此版本，不再提醒");
                            if (holder[0] != null) {
                                holder[0].dismiss();
                            }
                        }
                    });
                    box.addView(ig);
                }

                Dialog dlg = new Dialog(a);
                dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
                dlg.setContentView(box);
                dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dlg.getWindow().setDimAmount(0.55f);
                dlg.getWindow().setLayout(Ui.dp(a, 330), WindowManager.LayoutParams.WRAP_CONTENT);
                dlg.setCancelable(!force);
                dlg.setCanceledOnTouchOutside(!force);
                holder[0] = dlg;
                dlg.show();
            }
        });
    }

    // ---------- 安装权限（Android 8+ 需「允许安装未知应用」，华为上叫「外部来源应用下载」） ----------

    private static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT < 26) {
            return true; // Android 7 无此限制
        }
        try {
            return a.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void requestInstallPermission(final Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            toast(a, "授权「安装未知应用」后，回到本页再点一次「立即更新」");
        } catch (Throwable t) {
            toast(a, "请到 系统设置 → 应用 → 本应用 → 允许安装未知应用");
        }
    }

    // ---------- 下载（双 URL 降级）+ 安装 ----------

    private static void downloadAndInstall(final Activity a, final JSONObject j) {
        final String[] urls = pickUrls(j);
        if (urls.length == 0) {
            toast(a, "升级配置缺少下载地址，请检查 update.json");
            return;
        }
        final String sha = j.optString("sha256", "");
        final boolean force = j.optBoolean("isForce", false) || j.optBoolean("force", false);
        final Dialog[] holder = new Dialog[1];

        // ---- 自绘下载卡（与升级弹窗同一视觉语言）----
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 22);
        box.setBackground(Ui.round(Ui.CARD, 22, 0, a));
        box.setPadding(pad, pad, pad, pad);

        box.addView(Ui.text(a, "正在下载 v" + j.optString("versionName", ""), 16.5f, Ui.TEXT, true));
        box.addView(Ui.space(a, 4));
        box.addView(Ui.text(a, "镜像优先 · 完成后自动弹出安装", 11f, Ui.MUTED, false));
        box.addView(Ui.space(a, 16));

        // 圆角进度条（底轨=LINE，进度=ACCENT）
        GradientDrawable track = new GradientDrawable();
        track.setColor(Ui.LINE);
        track.setCornerRadius(Ui.dp(a, 5));
        GradientDrawable fillD = new GradientDrawable();
        fillD.setColor(Ui.ACCENT);
        fillD.setCornerRadius(Ui.dp(a, 5));
        ClipDrawable clip = new ClipDrawable(fillD, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressDrawable(layer);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 10)));
        box.addView(Ui.space(a, 10));

        final TextView pct = Ui.text(a, "0%", 15f, Ui.ACCENT, true);
        box.addView(pct);
        final TextView size = Ui.text(a, "准备中…", 11f, Ui.MUTED, false);
        box.addView(size);
        box.addView(Ui.space(a, 14));

        if (!force) {
            // 仅收起窗口；下载在后台继续（与旧 ProgressDialog 行为一致）
            Button cancel = Ui.button(a, "取消", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }
            });
            cancel.setBackground(Ui.round(0x00000000, 12, Ui.LINE, a));
            cancel.setTextColor(Ui.MUTED);
            box.addView(cancel, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 42)));
        }

        Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.getWindow().setDimAmount(0.55f);
        dlg.getWindow().setLayout(Ui.dp(a, 330), WindowManager.LayoutParams.WRAP_CONTENT);
        dlg.setCancelable(!force);
        dlg.setCanceledOnTouchOutside(!force);
        holder[0] = dlg;
        dlg.show();

        final DlUi ui = new DlUi();
        ui.dlg = dlg;
        ui.bar = bar;
        ui.pct = pct;
        ui.size = size;
        tryUrl(a, j, urls, 0, sha, ui);
    }

    /** 镜像优先、直连兜底：每个 URL 重新覆盖下载到本地文件，失败后换下一个。 */
    private static void tryUrl(final Activity a, final JSONObject j, final String[] urls,
                               final int i, final String sha, final DlUi ui) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        if (i >= urls.length) {
            hide(a, ui);
            showRetry(a, j);
            return;
        }
        try {
            final File apk = apkFile(a);
            apk.delete(); // 清掉上次可能残留的半包
            final OutputStream out = new FileOutputStream(apk);
            Net.download(urls[i], out, new Net.DlCb() {
                @Override public void onProgress(long received, long total) {
                    if (total <= 0) {
                        return;
                    }
                    final int pct = (int) (received * 100 / total);
                    final long rx = received;
                    final long tt = total;
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ui.dlg.isShowing()) {
                                ui.bar.setProgress(pct);
                                ui.pct.setText(pct + "%");
                                ui.size.setText(mb(rx) + " / " + mb(tt));
                            }
                        }
                    });
                }

                @Override public void onDone(boolean ok, String err, String shaHex) {
                    android.util.Log.i(TAG, "download[" + urls[i] + "] ok=" + ok
                            + " err=" + err + " sha=" + shaHex);
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                    if (!ok || (sha.length() > 0 && !sha.equalsIgnoreCase(shaHex))) {
                        apk.delete(); // 校验不过的包不能留在盘上
                        toast(a, (i == 0 && urls.length > 1)
                                ? "镜像下载失败，切换直连重试…" : "下载失败，正在重试…");
                        tryUrl(a, j, urls, i + 1, sha, ui);
                        return;
                    }
                    hide(a, ui);
                    // 更新漏斗：升级包校验通过、即将唤起安装
                    Analytics.pageView(a, "/apk/update-installed?to="
                            + j.optInt("versionCode", 0) + "&c=inapp");
                    // P3（§4.4）：升级完成事件（按天+目标版本幂等）
                    Analytics.event(a, "app_update_installed",
                            Analytics.p("to_code", j.optInt("versionCode", 0)),
                            Analytics.dedupeKey(a, "app_update_installed",
                                    String.valueOf(j.optInt("versionCode", 0))));
                    toast(a, "下载完成，请在系统弹窗中确认安装");
                    install(a, apk);
                }
            });
        } catch (Throwable t) {
            android.util.Log.e(TAG, "download prepare fail", t);
            tryUrl(a, j, urls, i + 1, sha, ui);
        }
    }

    /** 所有源都失败（或 sha 校验不过）→ 弹「重试」而不是只 toast，让用户一键重来。 */
    private static void showRetry(final Activity a, final JSONObject j) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                Dialogs.confirm(a, 0, Ui.ERR, "下载失败",
                        "镜像与直连均未成功（或文件校验不过），请检查网络后重试。",
                        null, "重试", false, new Dialogs.Action() {
                            @Override public void run() {
                                downloadAndInstall(a, j);
                            }
                        });
            }
        });
    }

    /** 升级包落盘位置：getExternalFilesDir（App 专属目录，无需任何存储权限，卸载即清） */
    private static File apkFile(Activity a) {
        return new File(a.getExternalFilesDir(null), APK_NAME);
    }

    /**
     * 触发系统安装：MiniFileProvider content:// URI + ACTION_INSTALL_PACKAGE。
     * 2026-10-05 修复「下载完被 WPS 打开」：原 ACTION_VIEW + setDataAndType(package-archive)
     * 是"用某 App 打开文件"的泛化意图，EMUI 上会被文件类 App（WPS 等）抢注处理器；
     * ACTION_INSTALL_PACKAGE 只匹配系统安装器，机制上杜绝被抢。
     * EMUI 8 实测：PackageInstaller.commit 被静默拦截（shell 正常），此传统路径
     * 各 ROM 均会弹安装确认。权限（canRequestPackageInstalls）已在弹窗点击时检查。
     */
    private static void install(final Activity a, final File apk) {
        try {
            Uri uri = Uri.parse("content://" + a.getPackageName() + ".updatefiles/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            android.util.Log.i(TAG, "install: 已唤起系统安装器(ACTION_INSTALL_PACKAGE) size=" + apk.length());
        } catch (Throwable t) {
            // 兜底：极少数 ROM 没有 ACTION_INSTALL_PACKAGE 处理器时退回老写法
            android.util.Log.e(TAG, "install: ACTION_INSTALL_PACKAGE 失败，回退 ACTION_VIEW", t);
            try {
                Uri uri = Uri.parse("content://" + a.getPackageName() + ".updatefiles/" + apk.getName());
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Throwable t2) {
                android.util.Log.e(TAG, "install: 唤起失败", t2);
                toast(a, "触发安装失败：" + t2.getMessage());
            }
        }
    }

    private static void hide(final Activity a, final DlUi ui) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (ui.dlg != null && ui.dlg.isShowing()) {
                        ui.dlg.dismiss();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 下载中窗口的控件集合（代替旧的 ProgressDialog） */
    private static class DlUi {
        Dialog dlg;
        ProgressBar bar;
        TextView pct;
        TextView size;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void toast(final Activity a, final String msg) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!a.isFinishing() && !a.isDestroyed()) {
                    Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 收集下载地址：镜像 + 直连，只收 https（targetSdk 28+ 禁明文），去重保序。 */
    private static String[] pickUrls(JSONObject j) {
        ArrayList<String> list = new ArrayList<String>();
        add(list, j.optString("downloadUrlMirror", ""));
        String origin = j.optString("downloadUrlOrigin", "");
        if (origin.length() == 0) {
            origin = j.optString("downloadUrl", "");
        }
        if (origin.length() == 0) {
            origin = j.optString("url", "");
        }
        add(list, origin);
        return list.toArray(new String[0]);
    }

    private static void add(ArrayList<String> l, String s) {
        if (s != null && s.startsWith("https://") && !l.contains(s)) {
            l.add(s);
        }
    }
}