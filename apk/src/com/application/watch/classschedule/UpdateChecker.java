package com.application.watch.classschedule;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;

/**
 * App 自动升级（整包，脱离应用商店）。
 *
 * 流程：启动静默 GET 服务端 update-&lt;variant&gt;.json（Vercel/静态托管）
 *   → versionCode 对比 → 弹窗 → 下载（镜像优先、直连兜底，边下边算 sha256）
 *   → PackageInstaller 流式安装（零临时文件，系统弹「是否安装」由用户确认）。
 *
 * 服务端 JSON 字段：versionCode / versionName / sha256 /
 *   downloadUrlMirror（国内镜像，优先尝试）/ downloadUrlOrigin（GitHub 直链，兜底）/
 *   isForce（弹窗不可取消）/ updateLog（弹窗正文）。
 * 设计依据见 docs/自动升级实现方案.md。
 */
public final class UpdateChecker {

    private static final int INSTALL_REQ = 7001;

    private UpdateChecker() {
    }

    /** 静默检查（首页启动调用）：网络失败/无更新都不打扰。 */
    public static void checkSilent(final Activity a) {
        check(a, false);
    }

    /**
     * 检查更新。manual=true 时（设置页手动入口，M2 接）无更新/失败均给提示；
     * manual=false 时仅发现新版本才弹窗。
     */
    public static void check(final Activity a, final boolean manual) {
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
                if (remote <= localCode(a)) {
                    if (manual) {
                        toast(a, "已是最新版本");
                    }
                    return;
                }
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
                AlertDialog.Builder b = new AlertDialog.Builder(a)
                        .setTitle("发现新版本 v" + j.optString("versionName", ""))
                        .setMessage(log)
                        .setCancelable(!force)
                        .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (!canInstall(a)) {
                                    requestInstallPermission(a);
                                    return;
                                }
                                downloadAndInstall(a, j);
                            }
                        });
                if (!force) {
                    b.setNegativeButton("稍后再说", null);
                }
                b.show();
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
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle("正在下载 v" + j.optString("versionName", ""));
        pd.setMax(100);
        pd.setCancelable(false);
        if (!force) {
            pd.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消",
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            d.dismiss();
                        }
                    });
        }
        pd.show();
        tryUrl(a, urls, 0, sha, pd);
    }

    /** 镜像优先、直连兜底：每个 URL 用一个独立安装 session，失败 abandon 后换下一个。 */
    private static void tryUrl(final Activity a, final String[] urls, final int i,
                               final String sha, final ProgressDialog pd) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        if (i >= urls.length) {
            hide(a, pd);
            toast(a, "下载失败：镜像与直连均不可用，请稍后重试");
            return;
        }
        try {
            PackageManager pm = a.getPackageManager();
            PackageInstaller.SessionParams sp =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            final PackageInstaller.Session s = pm.getPackageInstaller().openSession(
                    pm.getPackageInstaller().createSession(sp));
            final OutputStream out = s.openWrite("base.apk", 0, -1);
            Net.download(urls[i], out, new Net.DlCb() {
                @Override public void onProgress(long received, long total) {
                    if (total <= 0) {
                        return;
                    }
                    final int pct = (int) (received * 100 / total);
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (pd.isShowing()) {
                                pd.setProgress(pct);
                            }
                        }
                    });
                }

                @Override public void onDone(boolean ok, String err, String shaHex) {
                    if (!ok || (sha.length() > 0 && !sha.equalsIgnoreCase(shaHex))) {
                        close(s, out, true);
                        toast(a, (i == 0 && urls.length > 1)
                                ? "镜像下载失败，切换直连重试…" : "下载失败，正在重试…");
                        tryUrl(a, urls, i + 1, sha, pd);
                        return;
                    }
                    close(s, out, false);
                    hide(a, pd);
                    toast(a, "下载完成，请在系统弹窗中确认安装");
                    commit(a, s);
                }
            });
        } catch (Throwable t) {
            tryUrl(a, urls, i + 1, sha, pd);
        }
    }

    private static void close(PackageInstaller.Session s, OutputStream out, boolean abandon) {
        try {
            if (!abandon) {
                s.fsync(out); // 写 session 流必须 fsync，否则 commit 可能拿到不完整数据
            }
            out.close();
            if (abandon) {
                s.abandon();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void commit(final Activity a, final PackageInstaller.Session s) {
        try {
            Intent done = new Intent(a.getPackageName() + ".INSTALL_DONE")
                    .setPackage(a.getPackageName());
            s.commit(PendingIntent.getBroadcast(a, INSTALL_REQ, done,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                    .getIntentSender());
        } catch (Throwable t) {
            try {
                s.abandon();
            } catch (Throwable ignored) {
            }
            toast(a, "触发安装失败：" + t.getMessage());
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hide(final Activity a, final ProgressDialog pd) {
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (pd.isShowing()) {
                        pd.dismiss();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
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
