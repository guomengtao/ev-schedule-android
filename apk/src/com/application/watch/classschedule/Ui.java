package com.application.watch.classschedule;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 统一视觉 —— Token + Theme（2026-09-28 起）。
 *
 * ★ 设计令牌（Design Token）：所有颜色从这里的 token 取，**任何页面不许写死颜色**。
 *   两套主题：
 *     - 浅色「晴空蓝」（默认）：白天教室/户外高频扫一眼的课表场景，浅色可读性最好
 *     - 深色「夜幕蓝」（夜间）：跟随系统夜间模式自动切换
 *
 * ★ 兼容性：token 由 final 常量改为可变字段 + applyTheme() 刷新，
 *   全部 9 个 Activity 已经只走 Ui.text/card/button 等 API，**无需任何改动**。
 *
 * ★ 课程区分色：12 色调色板，按课程名 hash 稳定分配（同一门课永远同色）。
 */
public final class Ui {

    // ============ Token（由 applyTheme 按当前主题刷新；不要在别处写死颜色） ============
    public static int BG;
    public static int CANVAS;
    public static int CARD;
    public static int CARD2;
    public static int LINE;
    public static int TEXT;
    public static int MUTED;
    public static int ACCENT;
    public static int ACCENT_LIGHT;
    public static int OK;
    public static int WARN;
    public static int ERR;
    public static int ON_ACCENT;     // 主色底上的文字（保证对比度）
    public static int OK_LIGHT;      // 假期/成功语义淡背景
    public static int WARN_LIGHT;    // 调休/警告语义淡背景
    public static int themeVersion = 0;
    /** 上次生效色板的指纹：只有颜色真的变了才递增 themeVersion（见 applyTheme） */
    private static int lastPaletteFp = 0;
    private static boolean dark = true;

    // ============ 浅色「晴空蓝」 ============
    private static final int[] L = {
            0xFFF4F7FB, // BG
            0xFFE9EAEF, // CANVAS
            0xFFFFFFFF, // CARD
            0xFFEAF0F8, // CARD2（次级表面 / 非主按钮）
            0xFFE3EAF3, // LINE
            0xFF0F172A, // TEXT
            0xFF64748B, // MUTED
            0xFF2F6BFF, // ACCENT
            0xFFEBF0FF, // ACCENT_LIGHT（主色 8% 透明混白，用于今天列背景）
            0xFF146B41, // OK   v1.3 6.54:1（原 3.30 不达标）
            0xFF8A4A08, // WARN v1.3 6.85:1（原 2.15 不达标）
            0xFFB91C1C, // ERR  v1.3 6.47:1
            0xFFFFFFFF, // ON_ACCENT
            0x2E22C55E, // OK_LIGHT
            0x40FDE68A, // WARN_LIGHT
    };

    // ============ 深色「夜幕蓝」（ACCENT 提亮一档保证对比度） ============
    private static final int[] D = {
            0xFF0B1020, // BG
            0xFF060912, // CANVAS
            0xFF151C30, // CARD
            0xFF1C2542, // CARD2
            0xFF27314C, // LINE
            0xFFE9EEF9, // TEXT
            0xFF8695B4, // MUTED
            0xFF5B9BFF, // ACCENT
            0xFF1A2544, // ACCENT_LIGHT
            0xFF34C759, // OK
            0xFFFFB020, // WARN
            0xFFFF6B6B, // ERR
            0xFFFFFFFF, // ON_ACCENT
            0x2E22C55E, // OK_LIGHT
            0x40FDE68A, // WARN_LIGHT
    };

    /** 7 色语义课程区分色（浅/深两套主题共用；同一门课永远同色） */
    public static final int[] COURSE_COLORS = {
            0xFF5B6CF9, // c-blue
            0xFF33C77A, // c-green
            0xFFFF9E42, // c-orange
            0xFF9C6BF7, // c-purple
            0xFF35C4D6, // c-cyan
            0xFFFF7AA8, // c-pink
            0xFFFF6B6B, // c-red
    };

    // ===== ev-ui-android v1.1 · P1-① 课表块降饱和（2026-11-05 首页 Pilot）=====
    /**
     * 课程块浅底：不再整块铺高饱和纯色，改低饱和浅底 + 左侧 COURSE_BAR_DP 身份色条，
     * 课名走 COURSE_INK_COLORS 深字。原 COURSE_COLORS 语义不变，仍是「身份色」，
     * 供桌面小组件色条 / 编辑网格 / 色条本身继续取用（改它们会破坏既有对比度）。
     */
    public static final int[] COURSE_SURFACE_COLORS = {
            0xFFEDF1FE, // blue
            0xFFE7F7EE, // green
            0xFFFFF1E2, // orange
            0xFFF3ECFE, // purple
            0xFFE5F6F9, // cyan
            0xFFFDEBF2, // pink
            0xFFFDECEC, // red
    };
    /** v1.3：7 色 ink 全部压暗到与浅底 ≥4.5:1（原绿 3.93、橙 3.98、青 4.48 不达标）。
     *  实测：蓝 7.19 / 绿 5.90 / 橙 6.18 / 紫 7.62 / 青 6.00 / 粉 6.42 / 红 6.30。 */
    public static final int[] COURSE_INK_COLORS = {
            0xFF2F47A8, // blue
            0xFF146B41, // green
            0xFF8A4A08, // orange
            0xFF5B329E, // purple
            0xFF0F6577, // cyan
            0xFF933453, // pink
            0xFF963838, // red
    };
    /** 课程块左侧身份色条宽度（dp） */
    public static final int COURSE_BAR_DP = 4;

    /** 深色「夜幕蓝」主题下的课程块底（与 COURSE_SURFACE_COLORS 一一对应，避免深底上浮一块白） */
    public static final int[] COURSE_SURFACE_DARK_COLORS = {
            0xFF1B2545, 0xFF16302A, 0xFF332718, 0xFF271C40,
            0xFF153038, 0xFF331E29, 0xFF331E1E,
    };

    /** ev-ui-android v1.1 · P1-③ elevation 收敛三档（原散落 3/4/6/8dp） */
    public static final int E0 = 0, E1 = 4, E2 = 8;

    // ============ 排版 / 间距 / 圆角 令牌（2026-10-03 首页精细化新增）============
    /**
     * 字号阶梯：首页原散落 11 档（26/16/15/13/12.5/12/11.5/11/10.5/10/9），
     * 相邻档差 <1sp 视觉无差别纯属噪音 → 收敛为 7 档。新页面一律用这些常量。
     */
    public static final float SP_DISPLAY = 24f;   // 页面大标题（v1.3 22→24）
    public static final float SP_TITLE = 18f;     // 分区标题 / 顶栏（v1.3 17→18）
    public static final float SP_SUBTITLE = 15f;  // 次标题 / 日期数字
    public static final float SP_BODY = 13f;      // 正文 / 按钮 / 课表名
    public static final float SP_CAPTION = 11.5f; // 次要说明 / 备注 / 星期
    public static final float SP_MICRO = 10.5f;   // 时间轴 / 地点 / 表头
    public static final float SP_TAB = 10.5f;     // 底部导航
    public static final float SP_NUM = 16f;       // 日期/数值强调（正文加一级）

    /** 行高倍数：中文正文 1.5 起，小字 ~1.48，标题收紧到 1.27 */
    public static final float LH_DISPLAY = 1.27f;
    public static final float LH_TITLE = 1.41f;
    public static final float LH_SUBTITLE = 1.47f;
    public static final float LH_BODY = 1.54f;
    public static final float LH_CAPTION = 1.52f;
    public static final float LH_MICRO = 1.48f;
    /** 课程块内空间紧张，适度收紧 */
    public static final float LH_TIGHT = 1.33f;

    /** 间距栅格（4dp 基线）：首页所有间距都应落在这四档上 */
    public static final int GAP_XS = 4, GAP_SM = 8, GAP_MD = 12, GAP_LG = 16;

    /** 圆角三档（原 20/16/15/14/12/8 六种 → 收敛）。
     *  v1.3：卡片 14→18、控件 12→14、课程块 4→8，配合色条沿圆角裁剪，去掉「方盒子」感。 */
    public static final int R_CARD = 18, R_CTRL = 14, R_BLOCK = 8;

    /** 周课表网格节奏（2026-10-05 首页 UI 精细化）：行距单一真源，时间标签/课程块/空行全部对齐它 */
    public static final int ROW_PITCH = 48;      // 每行统一总高（时间列 / 课程块行 / 空格行）
    public static final int BLOCK_MARGIN = 1;    // 课程块左右外边距（与 COL_PAD 收口：横向缝 10→4dp）
    public static final int COL_PAD = 1;         // 天列内边距（与 BLOCK_MARGIN 共同收口）
    public static final int BLOCK_H_2LINE = 44;  // 双行块（课名+地点）高度
    public static final int BLOCK_H_1CHAR = 40;  // 单字块高度（近正方 1:1.18，从 28×48 竖长条改来）

    /** 最小触摸热区（Material 建议值） */
    public static final int TOUCH_MIN = 44;

    public static boolean isDark() {
        return dark;
    }

    /**
     * 套用主题。每个页面入口（screen / wrapWithBottomBar / fixedWithBottomBar）都会调它。
     *
     * 主题源优先级：
     *   1. 「跟随手环」开启且已同步到手环主题色 → 用手环主题的整套色板
     *   2. 本地选过主题（静态内置 10 套，**不连手环也能用**）→ 用本地主题色板
     *   3. 都没有 → 系统夜间模式（浅色「晴空蓝」默认 / 深色「夜幕蓝」）
     */
    public static void applyTheme(Context c) {
        boolean night;
        try {
            int mode = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            night = (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        } catch (Throwable t) {
            night = true;
        }
        int[] custom = null;
        try {
            String tid = null;
            if (WatchAppearance.followEnabled(c) && WatchAppearance.themeId(c).length() > 0) {
                tid = WatchAppearance.themeId(c);           // 跟随手环：手环主题优先
            }
            if (tid == null && WatchAppearance.localTheme(c).length() > 0) {
                tid = WatchAppearance.localTheme(c);        // 本地静态主题（离线可用）
            }
            if (tid != null) {
                custom = WatchAppearance.palette(tid);
                if (custom != null) {
                    night = WatchAppearance.isDarkBg(custom[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        dark = night;
        int[] p = dark ? D : L;
        // custom palette (WatchAppearance) has 7 elements: {bg, card, card2, line, text, muted, accent}
        // built-in palette has 15: {bg, canvas, card, card2, line, text, muted, accent, accentLight, ok, warn, err, onAccent, okLight, warnLight}
        if (custom != null) {
            BG = custom[0];
            CANVAS = p[1];                          // custom palette has no canvas
            CARD = custom[1];
            CARD2 = custom[2];
            LINE = custom[3];
            TEXT = custom[4];
            MUTED = custom[5];
            ACCENT = custom[6];
            ACCENT_LIGHT = p[8];                    // custom palette has no accentLight
        } else {
            BG = p[0];
            CANVAS = p[1];
            CARD = p[2];
            CARD2 = p[3];
            LINE = p[4];
            TEXT = p[5];
            MUTED = p[6];
            ACCENT = p[7];
            ACCENT_LIGHT = p[8];
        }
        OK = p[9];
        WARN = p[10];
        ERR = p[11];
        // custom palette has no ON_ACCENT / OK_LIGHT / WARN_LIGHT → always use system palette
        // ★ 但 onAccent 不能直接沿用系统白字：自定义主题的 ACCENT 多为浅色（浅青/暖阳米/#8FB4FF…），
        //   白字压上去实测仅 1.87:1，10 套里 9 套不达标 → 按 ACCENT 亮度自动取深/浅字
        ON_ACCENT = (custom != null) ? inkOn(ACCENT) : p[12];
        OK_LIGHT = p[13];
        WARN_LIGHT = p[14];
        // 系统控件（Switch / AlertDialog / EditText 光标等）也要跟着换：
        // 必须在 setContentView 之前 setTheme —— 每个页面都是先走 Ui.screen()，恰好满足
        if (c instanceof Activity) {
            ((Activity) c).setTheme(dark
                    ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
        }
        // ★ 关键：只有色板真的变了才递增版本号。
        // 桌面 widget 的后台刷新（WeekWidget/NextWidget/TodayWidget）每分钟都会调到这里，
        // 旧实现在这里无条件 ++ 会让「主题没变」的页面在 onResume 时误判 → recreate()
        // → 输入法一弹/一收就整窗销毁重建（黑屏 + 完成键闪烁）的元凶之一。
        int fp = paletteFingerprint();
        if (fp != lastPaletteFp) {
            lastPaletteFp = fp;
            themeVersion++;
        }
    }

    /**
     * 给定底色，返回对比度更高的前景色（深墨 #0F172A 或 纯白）。
     * 自定义主题没有 onAccent，主按钮/选中态的字色必须跟着 ACCENT 亮度走，
     * 否则浅色主题下就是白字压浅底（实测 1.87:1，远低于 4.5:1 门槛）。
     */
    static int inkOn(int bg) {
        double l = relLuma(bg);
        double white = 1.05 / (l + 0.05);
        double black = (l + 0.05) / 0.05;
        return white >= black ? 0xFFFFFFFF : 0xFF0F172A;
    }

    /** WCAG 相对亮度 */
    private static double relLuma(int c) {
        double r = srgb(((c >> 16) & 0xFF) / 255.0);
        double g = srgb(((c >> 8) & 0xFF) / 255.0);
        double b = srgb((c & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double srgb(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** 当前生效色板（含深浅模式）的指纹，用于判断主题是否真的发生变化 */
    private static int paletteFingerprint() {
        int h = dark ? 1 : 0;
        h = h * 31 + BG;
        h = h * 31 + CANVAS;
        h = h * 31 + CARD;
        h = h * 31 + CARD2;
        h = h * 31 + LINE;
        h = h * 31 + TEXT;
        h = h * 31 + MUTED;
        h = h * 31 + ACCENT;
        h = h * 31 + ACCENT_LIGHT;
        h = h * 31 + OK;
        h = h * 31 + WARN;
        h = h * 31 + ERR;
        h = h * 31 + ON_ACCENT;
        h = h * 31 + OK_LIGHT;
        h = h * 31 + WARN_LIGHT;
        return h;
    }

    /**
     * 统一 EditText 工厂 —— 全仓输入框必须走这里创建。
     *
     * 中文输入法在「全屏提取（extract）」模式下会用自己的窗口整屏接管，
     * 页面上会盖出一块输入面板并带「完成」键；一旦窗口再被重建，
     * 视觉上就是「点一下键盘 → 黑屏 + 完成键闪烁」。
     * IME_FLAG_NO_EXTRACT_UI 禁掉整屏提取，IME_FLAG_NO_FULLSCREEN 禁掉全屏 IME。
     */
    public static EditText editText(Context c) {
        EditText e = new EditText(c);
        try {
            int flags = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            e.setImeOptions(flags);
        } catch (Throwable ignored) {
        }
        return e;
    }

    /** 上次因主题变化重建窗口的时刻（elapsedRealtime，防抖用） */
    private static long lastRecreateAt = 0;

    /**
     * onResume 主题重建守卫 —— 全仓 Activity 统一走这里，禁止直接 recreate()。
     *
     * 2026-10-18 复发复盘：v0.5.178 只把 applyTheme 的 themeVersion++ 改成「色板指纹变了才自增」，
     * 挡住了「桌面 widget 每分钟刷新制造假变化」，但挡不住**色板真的变化**
     * （「跟随手环」同步主题、系统深浅色切换、本地主题切换）——
     * 一旦真的变了，themeVersion 暴涨，此时中文输入法弹出/切换面板/点键引起的
     * onPause→onResume 抖动仍会触发 recreate()：窗口销毁重建 = 黑屏一瞬，
     * 输入法随新窗口重新附着 = 「完成」键闪烁。表现就是「昨天好了，今天又坏」。
     *
     * 两道闸：
     *   1. 键盘可见（正在打字）→ 不重建，主题等下次空闲或下次进页面再应用；
     *   2. 30 秒内已经重建过 → 不再重建，避免生命周期抖动窗口内连环重建。
     */
    public static boolean needRecreate(android.app.Activity a, int lastVersion) {
        if (lastVersion == 0 || lastVersion == themeVersion) return false;
        if (imeVisible(a)) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastRecreateAt != 0 && now - lastRecreateAt < 30000L) return false;
        lastRecreateAt = now;
        return true;
    }

    /** 输入法是否正在接收输入（键盘可见且页面有可编辑焦点） */
    public static boolean imeVisible(android.app.Activity a) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            a.getSystemService(Context.INPUT_METHOD_SERVICE);
            return imm != null && imm.isAcceptingText();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 课名 → 色板下标（三个数组共用同一 hash，保证底 / 字 / 色条永远对齐） */
    public static int courseIndex(String courseName) {
        int h = (courseName == null) ? 0 : courseName.hashCode();
        return (h & 0x7fffffff) % COURSE_COLORS.length;
    }

    /** 课程 → 区分色（稳定：同一门课在任何页面/任何手机上都是同一个颜色） */
    public static int courseColor(String courseName) {
        return COURSE_COLORS[courseIndex(courseName)];
    }

    /** ev-ui-android v1.1：课程块课名色（浅底深字，浅/深主题共用） */
    public static int courseInk(String courseName) {
        int i = courseIndex(courseName);
        // 深底用亮身份色，浅底用压暗的 ink，保证两套主题都有对比度
        return isDark() ? COURSE_COLORS[i] : COURSE_INK_COLORS[i];
    }

    /**
     * ev-ui-android v1.1：课程块底 = 低饱和浅底 + 左侧 COURSE_BAR_DP 身份色条。
     * 今天的课另外加 ACCENT 描边。页面只调这个工厂，不许自己拼颜色 / 圆角 / 描边。
     */
    /**
     * ev-ui-android v1.3：课程块底 = 低饱和浅底 + 左侧身份色条。
     *
     * 旧实现用 LayerDrawable 叠两张「各自四角带圆角」的 GradientDrawable：
     * 面层左内缩 4dp 后，它的左上/左下圆角会咬掉色条端头；底层色条自身也被
     * 圆角切出斜边 —— 真机表现为「粗糙一根边线，上下两端各一个刺头」，
     * 与附录 A 设计图（色条沿卡片圆角包边）完全不符。
     * 现在改用 CourseBlockDrawable：色条 = 左侧矩形 ∩ 卡片圆角轮廓，
     * 端头严格沿圆角收口，右边缘为垂直直线，无缺口、无尖角、无溢出。
     */
    public static android.graphics.drawable.Drawable courseBlockBg(Context c,
            String courseName, boolean current) {
        int i = courseIndex(courseName);
        return new CourseBlockDrawable(c,
                isDark() ? COURSE_SURFACE_DARK_COLORS[i] : COURSE_SURFACE_COLORS[i],
                COURSE_COLORS[i], current ? ACCENT : 0, 2);
    }

    /** 课程块专用 Drawable：圆角面 + 沿轮廓精确裁剪的身份色条 + 可选当前节描边。 */
    private static final class CourseBlockDrawable extends android.graphics.drawable.Drawable {
        private final float radius, barW, strokeW;
        private final int faceColor, barColor, strokeColor;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path card = new android.graphics.Path();
        private final android.graphics.Path barPath = new android.graphics.Path();

        CourseBlockDrawable(Context c, int faceColor, int barColor,
                            int strokeColor, int strokeDp) {
            this.faceColor = faceColor;
            this.barColor = barColor;
            this.strokeColor = strokeColor;
            radius = dp(c, R_BLOCK);
            barW = dp(c, COURSE_BAR_DP);
            strokeW = strokeColor == 0 ? 0f : dp(c, strokeDp);
        }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            card.reset();
            card.addRoundRect(new android.graphics.RectF(b), radius, radius,
                    android.graphics.Path.Direction.CW);
            barPath.reset();
            barPath.addRect(b.left, b.top, b.left + barW, b.bottom,
                    android.graphics.Path.Direction.CW);
            // 关键：交集让色条左上/左下角完全跟随卡片圆角，端头不再有斜切刺头
            barPath.op(card, android.graphics.Path.Op.INTERSECT);
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.RectF r = new android.graphics.RectF(getBounds());
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(faceColor);
            canvas.drawRoundRect(r, radius, radius, paint);
            paint.setColor(barColor);
            canvas.drawPath(barPath, paint);
            if (strokeColor != 0) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(strokeW);
                paint.setColor(strokeColor);
                float h = strokeW / 2f;
                canvas.drawRoundRect(new android.graphics.RectF(
                                r.left + h, r.top + h, r.right - h, r.bottom - h),
                        Math.max(0f, radius - h), Math.max(0f, radius - h), paint);
                paint.setStyle(android.graphics.Paint.Style.FILL);
            }
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static LinearLayout screen(Activity a) {
        applyTheme(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CANVAS);
        root.setPadding(dp(a, 20), dp(a, 12), dp(a, 20), dp(a, 12));
        return root;
    }

    public static TextView text(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) {
            // ev-ui-android v1.3：中文 BOLD 是伪粗体、发糊发硬；统一走真字重 medium，
            // 一处改动让全站标题/强调字重分层，页面零改动。
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        // v1.3：默认行高按字号取 LH_* 阶梯，全站中文不再「挤成一团」；
        // 单行 TextView 的 lineSpacing 不改变测量高度，紧凑布局零回归。
        setLineHeight(c, t, size, lineHeightFor(size));
        return t;
    }

    /** 字号 → 行高倍率（v1.3：让 Ui.text 默认路径也遵守 LH_* 阶梯） */
    private static float lineHeightFor(float sp) {
        if (sp >= SP_DISPLAY) return LH_DISPLAY;
        if (sp >= SP_NUM) return LH_TITLE;
        if (sp >= SP_SUBTITLE) return LH_SUBTITLE;
        if (sp >= SP_BODY) return LH_BODY;
        if (sp >= SP_CAPTION) return LH_CAPTION;
        return LH_MICRO;
    }

    /**
     * Medium 字重文本（中文精致度关键：中文 bold 是伪粗体发糊，
     * sans-serif-medium 是真字重，API 24+ 全覆盖）。
     * 标题/强调/表头等原 bold 场景的主用字重。
     */
    public static TextView textMedium(Context c, String s, float size, int color) {
        TextView t = text(c, s, size, color, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /**
     * 带行高的文本（中文精致度的关键）：Ui.text() 默认不设行高，中文会挤成一团。
     * add = sp × (mult − 1)，按当前 density 换算成 dp。
     */
    public static TextView textLh(Context c, String s, float sp, int color,
                                  boolean bold, float mult) {
        TextView t = text(c, s, sp, color, bold);
        setLineHeight(c, t, sp, mult);
        return t;
    }

    /** medium 字重 + 行高（中文标题主用：bold 是伪粗体，发糊）*/
    public static TextView textMediumLh(Context c, String s, float sp, int color, float mult) {
        TextView t = textMedium(c, s, sp, color);
        setLineHeight(c, t, sp, mult);
        return t;
    }

    /** 按「字号 × 倍数」设置行高；mult=1 表示用系统默认 */
    public static void setLineHeight(Context c, TextView t, float sp, float mult) {
        float add = sp * (mult - 1f);
        t.setLineSpacing(dp(c, (int) Math.round(add)), 1f);
    }

    /**
     * 把小视觉元素撑到 ≥TOUCH_MIN 热区。
     * ⚠️ 前提：调用方必须把 LayoutParams 一起放大到 TOUCH_MIN，
     * 固定 LP 下只加 padding 不会扩大点击区（padding 在背景之内）。
     */
    public static void ensureTouch(Context c, View v, int visualDp) {
        int pad = (TOUCH_MIN - visualDp) / 2;
        if (pad <= 0) {
            return;
        }
        int p = dp(c, pad);
        v.setPadding(v.getPaddingLeft() + p, v.getPaddingTop() + p,
                v.getPaddingRight() + p, v.getPaddingBottom() + p);
    }

    /**
     * 课程色底上的可读字色：按 WCAG 相对亮度选深/白。
     * 亮度 > 0.45 的底色（橙/绿/青/粉）用深字，否则白字——
     * 让 7 色课程块的白/深字对比度全部 ≥3:1（粗体大字标准）。
     */
    public static int onCourseColor(int bg) {
        return luminance(bg) > 0.45 ? 0xFF101828 : 0xFFFFFFFF;
    }

    /**
     * 高亮度课程色（橙/绿/青/粉）直接当文字色时，浅色主题下对比不足：
     * 混 35% 深色压一档；深色主题原色即可（亮色在暗底对比反而好）。
     * 用于 Today 插件 live 课名等"课程色当前景"的场景。
     */
    public static int readableAccent(int color, boolean dark) {
        if (dark || luminance(color) <= 0.45) {
            return color;
        }
        int r = (int) (((color >> 16) & 0xFF) * 0.65);
        int g = (int) (((color >> 8) & 0xFF) * 0.65);
        int b = (int) ((color & 0xFF) * 0.65);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** WCAG 相对亮度（0~1） */
    public static double luminance(int color) {
        double r = ((color >> 16) & 0xFF) / 255.0;
        double g = ((color >> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        // ev-ui-android v1.3：浅色用 E1 轻阴影替代 1dp 灰描边，去掉「白卡+描边」的平面年代感；
        // 深色下投影几乎不可见，仍保留细描边维持边界。
        l.setBackground(round(CARD, R_CARD, isDark() ? LINE : 0, c));
        if (!isDark()) {
            l.setElevation(dp(c, E1));
        }
        return l;
    }

    public static LinearLayout cardElevated(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        l.setBackground(round(CARD, R_CARD, 0, c));
        l.setElevation(dp(c, E2));   // v1.3：散落 6dp → 收敛到 E2
        return l;
    }

    public static GradientDrawable round(int fill, int radiusDp, int stroke, Context c) {
        return round(fill, radiusDp, stroke, stroke != 0 ? 1 : 0, c);
    }

    /** 可指定描边宽度的圆角矩形（当前节 2dp ACCENT 描边用） */
    public static GradientDrawable round(int fill, int radiusDp, int stroke,
                                         int strokeDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0 && strokeDp > 0) {
            g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        }
        return g;
    }

    public static Button button(Context c, String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        b.setBackground(round(primary ? ACCENT : CARD2, 12, primary ? 0 : LINE, c));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    /** 列表式可点击行（用于调试步骤 / 设置项） */
    public static LinearLayout row(Context c, String title, String sub, int tint,
                                  final View.OnClickListener click) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        outer.setBackground(round(CARD2, 12, LINE, c));

        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);

        TextView t = text(c, title, 13.5f, tint, true);
        inner.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 11.5f, MUTED, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            inner.addView(s);
        }
        outer.addView(inner);

        if (click != null) {
            outer.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { click.onClick(v); }
            });
        }
        return outer;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, h)));
        return v;
    }

    /** 两列按钮网格 */
    public static LinearLayout grid(Context c, View a, View b) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        r.addView(a, lp);
        r.addView(b, lp);
        return r;
    }

    public static TextView mono(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(MUTED);
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 19f, TEXT, true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /**
     * 子页面顶栏：Lucide「arrow-left」返回键 + 栏目标题，点返回即 finish()。
     * 仅非 tab 页使用；底部导航的三个主页（首页/课程表/设置）保持裸标题。
     * 需要动态改标题的页面：((TextView) header.getTag()).setText(...)
     */
    public static ViewGroup header(final Activity a, String title) {
        FrameLayout h = new FrameLayout(a);
        h.setMinimumHeight(dp(a, 52));
        // 标题绝对居中
        TextView t = text(a, title, 18f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        h.addView(t, tp);
        // 返回键固定居左。28dp 的点击区太小（用户反馈"有点小，加大至少 1 倍"）→ 56dp
        ImageView back = new ImageView(a);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(TEXT);
        back.setPadding(dp(a, 4), dp(a, 4), dp(a, 12), dp(a, 4));
        back.setClickable(true);
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.finish();
            }
        });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                dp(a, 56), dp(a, 56), Gravity.CENTER_VERTICAL);
        h.addView(back, bp);
        h.setTag(t);
        return h;
    }

    /** tab 主页表头：标题水平居中（无返回键），颜色随主题 token */
    public static View topBar(Context c, String title) {
        TextView t = text(c, title, 17f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 32)));
        return t;
    }

    /**
     * 底栏高度（dp）。内容区必须预留这么多底部留白，
     * 否则最后一段内容会被悬浮在底部的导航栏盖住（"底部的东西看不到"）。
     */
    public static final int BAR_HEIGHT_DP = 62;

    /** 底部固定导航：首页 / 课程表 / 设置（任何时候都常驻）。
     *  留言从底栏移除（低频功能），改由首页快捷按钮触达。
     *  每个 tab = 图标 + 文字 的竖向布局。
     *  图标：Lucide 规范单色线性图标（res/drawable/ic_tab_*.xml，24×24 描边 2），
     *  微信式选中态：不加背景块，图标换成实心版（ic_tab_*_filled.xml）+ 主色，文字变主色。 */
    public static LinearLayout bottomBar(final Activity a, int current) {
        final int[] icons = {R.drawable.ic_tab_home, R.drawable.ic_tab_schedule,
                R.drawable.ic_tab_watch, R.drawable.ic_tab_settings};
        final int[] iconsFilled = {R.drawable.ic_tab_home_filled, R.drawable.ic_tab_schedule_filled,
                R.drawable.ic_tab_watch_filled, R.drawable.ic_tab_settings_filled};
        final String[] labels = {"首页", "课表", "手环", "设置"};
        final Class[] targets = {HomeActivity.class, ScheduleListActivity.class,
                BandActivity.class, SettingsActivity.class};

        return buildBar(a, current, icons, iconsFilled, labels, targets);
    }

    private static LinearLayout buildBar(final Activity a, int current, int[] icons,
                                         int[] iconsFilled, String[] labels, Class[] targets) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 6));

        for (int i = 0; i < icons.length; i++) {
            final int idx = i;
            final boolean active = (i == current);
            final int color = active ? ACCENT : MUTED;
            LinearLayout tab = new LinearLayout(a);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setClickable(true);
            // 微信式：选中不加背景块，只靠「图标实心化 + 主色」和文字变色区分（用户定稿）
            ImageView ic = new ImageView(a);
            ic.setImageResource(active ? iconsFilled[i] : icons[i]);
            ic.setColorFilter(color);
            int iconSize = dp(a, 22);
            tab.addView(ic, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView lb = active ? textMedium(a, labels[i], 10.5f, color)
                    : text(a, labels[i], 10.5f, color, false);
            lb.setGravity(Gravity.CENTER);
            tab.addView(lb);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == current) {
                        return;
                    }
                    Intent it = new Intent(a, targets[idx]);
                    it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    a.startActivity(it);
                }
            });
            bar.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    /**
     * 内容可滚动的页面：内容放进 ScrollView，底栏固定在屏幕底部。
     *
     * ⚠️ 两个关键点（都是之前"首页滚不动 / 底部看不到"的根因）：
     *   1) 给 ScrollView 的子 View 显式传 WRAP_CONTENT 高度。
     *      ScrollView 继承 FrameLayout，不传 LayoutParams 时默认是 MATCH_PARENT，
     *      子 View 会被强制成"一屏高"→ 超出部分被裁掉，滚动失效。
     *   2) 内容区预留 BAR_HEIGHT_DP 的底部留白，避免被悬浮底栏盖住。
     *
     * 底栏由本方法唯一创建；调用方【不要】再往 contentRoot 里加 bottomBar。
     */
    public static ViewGroup wrapWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        applyTheme(a);
        final LinearLayout bar = bottomBar(a, currentTab);
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(bar, barParams());
        syncPaddingToBar(contentRoot, bar);
        return root;
    }

    /**
     * 页面内部已有自己的滚动区（如留言列表、调试日志）时用这个：
     * 不套外层 ScrollView（避免两层纵向滚动嵌套），只把底栏钉在底部 +
     * 给内容区预留底部留白，让内部 weight=1 的滚动区自然收在底栏之上。
     */
    public static ViewGroup fixedWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        return fixedWithBar(a, contentRoot, bottomBar(a, currentTab));
    }

    private static ViewGroup wrapWithBar(Activity a, LinearLayout contentRoot, final LinearLayout bar) {
        applyTheme(a);
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(bar, barParams());
        syncPaddingToBar(contentRoot, bar);
        return root;
    }

    private static ViewGroup fixedWithBar(Activity a, LinearLayout contentRoot, final LinearLayout bar) {
        applyTheme(a);
        reserveBottomSpace(a, contentRoot);

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(contentRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bar, barParams());
        syncPaddingToBar(contentRoot, bar);
        return root;
    }

    private static void reserveBottomSpace(Activity a, LinearLayout contentRoot) {
        contentRoot.setPadding(contentRoot.getPaddingLeft(), contentRoot.getPaddingTop(),
                contentRoot.getPaddingRight(), dp(a, BAR_HEIGHT_DP));
    }

    /**
     * 用【实测】的底栏高度校准底部留白，而不是只信 BAR_HEIGHT_DP 常量。
     *
     * 底栏实际高度 = 图标(19sp emoji) + 文字 + 上下 padding，在某些机型/字体缩放下会超过
     * 常量的 62dp（实测 BLN-AL20 density=3 时是 86dp），于是「最后一行按钮被压掉半截」。
     * 这里在布局完成后按真实高度重设 paddingBottom，常量退化为"首次渲染前的估计值"。
     */
    private static void syncPaddingToBar(final LinearLayout contentRoot, final View bar) {
        bar.post(new Runnable() {
            @Override public void run() {
                int h = bar.getHeight();
                if (h <= 0) {
                    return;
                }
                contentRoot.setPadding(contentRoot.getPaddingLeft(), contentRoot.getPaddingTop(),
                        contentRoot.getPaddingRight(), h + dp(contentRoot.getContext(), 12));
            }
        });
    }

    private static FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }
}