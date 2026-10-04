# EV 课程表 · 安卓同步器首页 UI 精细化优化方案

> 目标：把「同步器首页」从"能用"推进到"精致"——收敛字号梯度、补齐行高、统一间距栅格与圆角、放大触摸目标、恢复缺失的快捷操作区。
> 范围：`apk/src/com/application/watch/classschedule/HomeActivity.java`（首页）+ `Ui.java`（设计令牌层）。
> 原则：**先立令牌，再改页面**；数值全部落成常量，杜绝页面里写死魔法数。

---

## 一、现状审计（当前真实数值）

以下数值均从 `HomeActivity.java` 逐行提取，是本次改造的基线。

### 1.1 页面骨架（`buildUi()`）

```
FrameLayout 顶栏（"Ev课程表" + ⊕）   ← 插在 index 0
  ↓ space 10
buildHeader：问候 + 大标题 + 周导航
  ↓ space 12
dateStripView（7 个日期格）
  ↓ space 12
weekBox（课表名行 → space 8 → 周网格 → 备注行）
  ↓ space 12
miniBar（连接状态迷你条）
  ↓ space 8
errorCard（默认 GONE）
  ↓ space 6
Ui.wrapWithBottomBar(root, 0)
```

外层 `Ui.screen()` 内边距：**左右 20dp / 上下 12dp**。

### 1.2 逐元素字号与间距现状

| 区块 | 元素 | 字号 | 间距 / 尺寸 | 圆角 | 备注 |
|---|---|---|---|---|---|
| 顶栏 | 标题 "Ev课程表" | 16f medium 居中 | ⊕ 钮 36×36，padding 8，rightMargin 12 | 圆钮 r20 | ⊕ 背景 `ACCENT_LIGHT` |
| 头部 | 问候 "Hi，同学" | **12.5f** MUTED | space 10 上方 | — | — |
| 头部 | 主标题 "本周课表" | **26f** medium | compound 间距 8 | — | 26sp 偏大 |
| 周导航 | 周标签 "9.28-10.04" | **12f** MUTED | padding 6/8/6/8 | — | 可点回本周 |
| 周导航 | ‹ › 箭头 | 图标 | 钮 **32×32**，padding 7 | r15 | 背景 `CARD2` |
| 日期条 | 星期（含休/班） | **11f** medium | cell 高 **62dp**，格间隙 **4dp** | r14 | 选中 `ACCENT` 实心 |
| 日期条 | 日期数字 | **15f** bold | paddingTop **1dp** | r14 | 非选中 elevation 4 |
| 课表名行 | 课表名 | **13f** medium | compound 间距 6 | r8 水波纹 | 整行可点 |
| 课表名行 | `[单]` 钮 | **12f** medium | **30×30**，leftMargin 8 | r14 | < 44dp 建议值 |
| 课表名行 | `✏️` 钮 | 图标 | **30×30**，padding 7，leftMargin 6 | r14 | < 44dp 建议值 |
| 周网格 | 外框 `cardElevated` | — | padding **10dp**，elevation 6 | r16 | — |
| 周网格 | 时间列 | 占位 **33dp** / 标签 **34dp** | 行高 **44dp** | — | — |
| 周网格 | 时间表头 | **10.5f** medium | padding 2/2 | — | — |
| 周网格 | 时间标签 | **10f** MUTED | 34×44 | — | — |
| 周网格 | 课程块课名 | **10f**（单字 16f）medium | 块高 **44dp**，padding 1/2/1/2，margin 2 | **无圆角** | lineSpacing +1dp |
| 周网格 | 课程块地点 | **9f** | — | — | **过小，低于可读下限** |
| 周网格 | 备注 note | **11.5f** MUTED | paddingTop 6 | — | — |
| 迷你条 | 状态文案 | **12f** MUTED | card padding 12/**8** | r14 | — |
| 错误卡 | 提示文案 | **13f** WARN | lineSpacing +2dp，space 10 | r14 | 下接 2 按钮 `Ui.grid` |
| 按钮 | `Ui.button` | **13f** | padding 10 | r12 | — |
| 底栏 | tab 文字 | **10.5f** | 图标 22dp，padding 4/6/4/6 | — | `BAR_HEIGHT_DP=62` |

### 1.3 诊断出的 7 个硬伤

1. **字号档位失控**：全页出现 `26 / 16 / 15 / 13 / 12.5 / 12 / 11.5 / 11 / 10.5 / 10 / 9` 共 **11 档**，相邻档差 <1sp（12.5 vs 12、11.5 vs 11），视觉上完全同大小，纯属噪音。
2. **行高全面缺失**：`Ui.text()` 从不设 `lineSpacing`，中文默认行高过挤；全页只有课程块（+1dp）和错误卡（+2dp）手动补过，其余全是系统默认。
3. **间距不成栅格**：`12 / 10 / 8 / 6 / 4 / 2 / 1` 混用，没有 4dp 基线约束，区块节奏忽紧忽松。
4. **圆角 6 种**：`20 / 16 / 15 / 14 / 12 / 8`，同层级卡片（迷你条 r14 vs 网格 r16）还不一致。
5. **触摸目标偏小**：`[单]` 与 `✏️` 仅 **30×30dp**，低于 Material 建议的 44dp 最小热区。
6. **功能缺失**：类注释承诺「快捷操作 2×2（呼叫手环 / 上课了 / 留言 / 下课了）」，但 `buildUi()` **根本没有渲染**，`quickBox` 字段声明后从未使用 —— 该区块实际不存在。
7. **课程块无圆角 + 地点 9sp**：块与块之间只靠 2dp margin 分割，视觉糊成一片；9sp 地点文字在正常视距下不可读。

---

## 二、设计令牌提案（先把尺子立起来）

### 2.1 字号阶梯：11 档 → 7 档

| 令牌 | 用途 | 目标值 | 吸纳的现状档位 |
|---|---|---|---|
| `SP_DISPLAY` | 页面大标题 | **22sp** | 26 |
| `SP_TITLE` | 分区标题 / 顶栏 | **17sp** | 16 |
| `SP_SUBTITLE` | 次标题、日期数字 | **15sp** | 15 |
| `SP_BODY` | 正文、按钮、课表名 | **13sp** | 13 / 13.5 / 12.5 |
| `SP_CAPTION` | 次要说明、备注 | **11.5sp** | 12 / 11.5 / 11 |
| `SP_MICRO` | 时间轴、星期、地点 | **10.5sp** | 10.5 / 10 |
| `SP_TAB` | 底部导航文字 | **10.5sp** | 10.5 |

> 取消 9sp（`SP_MICRO` 为下限），取消 12.5 / 11.5 这类半档中的冗余项。

### 2.2 行高 multiplier（本次改造的核心增量）

中文排版行高建议：正文 1.5、小字 1.45、标题 1.25。换算成 `setLineSpacing(add, 1f)` 的 add 值：

`add = sp × (multiplier − 1) × density`

| 字号 | 目标行高 | multiplier | 单行 add(dp) | 说明 |
|---|---|---|---|---|
| 22sp（大标题） | 28sp | 1.27 | +6 | 标题收紧，避免与日期条抢空间 |
| 17sp（顶栏） | 24sp | 1.41 | +7 | — |
| 15sp（日期数字） | 22sp | 1.47 | +7 | — |
| 13sp（正文） | 20sp | **1.54** | +7 | 中文正文黄金值 |
| 11.5sp（备注） | 17.5sp | 1.52 | +6 | — |
| 10.5sp（时间/星期） | 15.5sp | 1.48 | +5 | 小字也要呼吸感 |
| 10.5sp（课程块课名） | 14sp | **1.33** | +3.5 | 块内空间紧张，适度收紧 |

### 2.3 间距栅格（4dp 基线）

| 令牌 | 值 | 用途 |
|---|---|---|
| `GAP_XS` | **4dp** | 图标与文字、块间最小缝 |
| `GAP_SM` | **8dp** | 元素内相关间距 |
| `GAP_MD` | **12dp** | 卡内 padding、相邻区块 |
| `GAP_LG` | **16dp** | 页面级区块分隔、内容左右边距 |

**页面边距**：`Ui.screen()` 由 `20 / 12 / 20 / 12` → **左右 16dp / 上下 8dp**。
**区块间距**：统一 16dp（现状 12 / 10 / 8 / 6 混用 → 全部归到 16 或 8）。

### 2.4 圆角：6 种 → 3 种

| 令牌 | 值 | 用途 |
|---|---|---|
| `R_CARD` | **16dp** | 大卡片（周网格、错误卡、迷你条） |
| `R_CTRL` | **12dp** | 控件（按钮、日期格、输入框） |
| `R_BLOCK` | **6dp** | 课程块等密集小元件 |

圆形钮用 `radius = size/2`（如 ⊕ 40dp → r20）。

### 2.5 触摸目标

所有可点元素热区 **≥ 44×44dp**。`[单]` / `✏️` 视觉保持 30dp，用 7dp padding 把热区撑到 44dp。

---

## 三、分区改造方案（现状 → 目标，逐项可落地）

### 3.1 顶栏（`buildUi()` 内 `header`）

| 项 | 现状 | 目标 |
|---|---|---|
| 标题字号 | 16f medium | **17sp medium**（`SP_TITLE`），行高 24sp |
| 顶栏高度 | 未约束 | **固定 52dp**，标题绝对居中 |
| ⊕ 钮尺寸 | 36×36，padding 8 | **热区 44×44**，视觉 24dp 图标 + padding 10 |
| ⊕ rightMargin | 12dp | **8dp**（使视觉边缘与内容 16dp 边距对齐：8 + 图标内缩） |
| ⊕ 背景 | `ACCENT_LIGHT` r20 | 保持，r20（= 40/2） |

### 3.2 问候 + 主标题（`buildHeader()`）

| 项 | 现状 | 目标 |
|---|---|---|
| 上方留白 | space 10 | **8dp**（`GAP_SM`） |
| 问候字号 | 12.5f MUTED | **13sp**（`SP_BODY`），颜色 MUTED |
| 问候↔标题间距 | 0（直接堆叠） | **4dp**（`GAP_XS`） |
| 主标题字号 | **26f** | **22sp**（`SP_DISPLAY`），行高 28sp |
| 主标题↔日期条 | space 12 | **16dp**（`GAP_LG`） |
| 标题 compound 间距 | 8dp | 保持 8dp |
| 周标签字号 | 12f | **11.5sp**（`SP_CAPTION`），padding 8/8/8/8 |
| ‹ › 钮 | 32×32，padding 7 | **44×44 热区**，视觉 32dp + padding 6，r15 → **r12** |

> 26sp → 22sp 是本次视觉幅度最大的一处：26sp 与下方 62dp 日期条同屏时严重抢焦点，降到 22sp 后主视觉回归到课表网格本身。

### 3.3 日期条（`renderDateStrip()`）

| 项 | 现状 | 目标 |
|---|---|---|
| cell 高度 | 62dp | **64dp**（容纳 11.5sp 星期 + 16sp 数字 + 行高） |
| 格间隙 | 4dp | **6dp**（`GAP_XS` 上一档，7 格均分后仍不挤） |
| 星期字号 | 11f medium | **11.5sp medium**，行高 17sp |
| 日期字号 | 15f bold | **16sp medium**（去 bold 改 medium，中文伪粗体发糊），行高 22sp |
| 星期↔数字间距 | paddingTop 1dp | **2dp** |
| 圆角 | r14 | **r12**（`R_CTRL`） |
| 阴影 | elevation 4 | **elevation 2**（减轻厚重感，选中态靠 `ACCENT` 实心区分） |
| 休/班角标 | 同行追加文本（宽度会抖） | 改为**右上 6dp 小圆点**，避免"三 休"与"三"宽度不一致导致整行晃动 |

### 3.4 课表名行（`renderWeek()` 内 `nameRow`）

| 项 | 现状 | 目标 |
|---|---|---|
| 课表名字号 | 13f medium | **15sp medium**（`SP_SUBTITLE`），行高 22sp |
| 图标↔文字 | 6dp | **8dp**（`GAP_SM`） |
| `[单]` 钮 | 30×30 | **44×44 热区**（视觉 30dp + padding 7），r14 → **r12**，字号 12 → **13sp** |
| `✏️` 钮 | 30×30，padding 7 | **44×44 热区**，r14 → **r12** |
| 两钮 leftMargin | 8 / 6 | 统一 **8dp**（`GAP_SM`） |
| 名行↔网格 | space 8 | **12dp**（`GAP_MD`） |

### 3.5 周网格（`weekGridWithTime()`）

| 项 | 现状 | 目标 |
|---|---|---|
| 外框 padding | 10dp | **12dp**（`GAP_MD`），r16 保持，elevation 6 → **4** |
| 时间列宽 | 占位 33 / 标签 34 | 统一 **36dp**（容纳 10.5sp"08:00"级文本 + 两侧 4dp） |
| 表头字号 | 10.5f medium | **11.5sp medium**，行高 17sp，行高容器 **28dp** |
| 时间标签字号 | 10f | **10.5sp**（`SP_MICRO`），行高 15sp |
| 课程块高度 | 44dp | **48dp**（容纳课名 2 行 + 地点 1 行 + 行高） |
| 课程块 padding | 1/2/1/2 | **2/4/2/4**（左右 4dp 给文字留白） |
| 课程块 margin | 2dp | **3dp** |
| 课程块圆角 | 无 | **6dp**（`R_BLOCK`） |
| 课名字号 | 10f（单字 16f） | **11.5sp**（单字 15sp），行高 16sp |
| 地点字号 | **9f** | **10.5sp**（`SP_MICRO`），行高 14sp |

> 课程块加 6dp 圆角 + 上下 4dp 内边距，是让 7 色色块从"糊成一片"变成"颗颗分明"的关键。

### 3.6 迷你条 / 错误卡

| 项 | 现状 | 目标 |
|---|---|---|
| 迷你条文案 | 12f | **11.5sp**（`SP_CAPTION`），行高 17sp |
| 迷你条 padding | 12 / **8** | **12 / 10**，r14 → **r12**（`R_CTRL`） |
| 错误卡文案 | 13f WARN，lineSpacing +2 | **13sp**（`SP_BODY`），行高 20sp（add ≈ +7dp） |
| 错误卡内 space | 10dp | **12dp**（`GAP_MD`） |
| 按钮网格 margin | 4dp | **6dp** |

### 3.7 恢复缺失的「快捷操作 2×2」（`quickBox`）

**这是唯一的功能性缺口**：`quickBox` 字段已声明，`quickSend()`（呼叫手环 / 上课了 / 下课了）与 `快速留言` 逻辑都已实现，但 `buildUi()` 未渲染任何入口，用户从首页根本点不到。

新增区块（位于 `weekBox` 之后、迷你条之前）：

| 项 | 目标值 |
|---|---|
| 容器 | `Ui.card()`，padding **12dp**，r16 |
| 布局 | 2×2 网格，行列 gap **8dp** |
| 单格 | 图标 **24dp** + 文字 **11.5sp**，上下间距 **4dp**，热区 **≥72×64dp** |
| 四入口 | 呼叫手环 / 上课了 / 留言 / 下课了 |
| 图标 | 复用现有 `ic_bell` `ic_bell_ring` `ic_tab_message` `ic_volume_x` |
| 选中/按压 | `RippleDrawable` + `CARD2` 底，r12 |

### 3.8 备注行（`note`）

| 项 | 现状 | 目标 |
|---|---|---|
| 字号 | 11.5f | **11.5sp**（`SP_CAPTION`），行高 17sp |
| paddingTop | 6dp | **8dp**（`GAP_SM`） |

---

## 四、代码落地（在 `Ui.java` 增补令牌层）

> ⚠️ **兼容性红线**：`Ui` 的 `text / card / button / row` 被全部 9 个 Activity 共用。
> **不要改现有方法签名**，只做「新增」——改完首页后其余页面零回归风险。

### 4.1 新增字号与间距常量

```java
// ---- 字号阶梯（7 档，取代散落的 11 档魔法数）----
public static final float SP_DISPLAY = 22f;   // 页面大标题
public static final float SP_TITLE   = 17f;   // 分区标题 / 顶栏
public static final float SP_SUBTITLE= 15f;   // 次标题 / 日期数字
public static final float SP_BODY    = 13f;   // 正文 / 按钮 / 课表名
public static final float SP_CAPTION = 11.5f; // 次要说明 / 备注
public static final float SP_MICRO   = 10.5f; // 时间轴 / 星期 / 地点
public static final float SP_TAB     = 10.5f; // 底部导航

// ---- 间距栅格（4dp 基线）----
public static final int GAP_XS = 4, GAP_SM = 8, GAP_MD = 12, GAP_LG = 16;

// ---- 圆角（3 档）----
public static final int R_CARD = 16, R_CTRL = 12, R_BLOCK = 6;

// ---- 最小触摸热区 ----
public static final int TOUCH_MIN = 44;
```

### 4.2 新增带行高的文本工厂（本次改造的核心工具）

```java
/**
 * 带行高的文本（中文精致度的关键）。
 * mult：行高倍数，正文 1.5 / 小字 1.45 / 标题 1.25。
 */
public static TextView textLh(Context c, String s, float sp, int color,
                              boolean bold, float mult) {
    TextView t = text(c, s, sp, color, bold);
    float add = sp * (mult - 1f);
    t.setLineSpacing(dp(c, (int) Math.round(add)), 1f);
    return t;
}

/** medium 字重 + 行高（中文标题主用；bold 是伪粗体，发糊） */
public static TextView textMediumLh(Context c, String s, float sp, int color, float mult) {
    TextView t = textMedium(c, s, sp, color);
    float add = sp * (mult - 1f);
    t.setLineSpacing(dp(c, (int) Math.round(add)), 1f);
    return t;
}
```

### 4.3 新增热区保障

```java
/** 把小视觉元素撑到 ≥44dp 热区，视觉尺寸不变（用 padding 补） */
public static void ensureTouch(Context c, View v, int visualDp) {
    int pad = (TOUCH_MIN - visualDp) / 2;
    if (pad <= 0) return;
    int p = dp(c, pad);
    v.setPadding(v.getPaddingLeft() + p, v.getPaddingTop() + p,
                 v.getPaddingRight() + p, v.getPaddingBottom() + p);
}
```

### 4.4 首页替换对照（示例）

```java
// 主标题：26f 无行高  →  22sp + 1.27 行高
- pageTitleView = Ui.textMedium(this, "本周课表", 26f, Ui.TEXT);
+ pageTitleView = Ui.textMediumLh(this, "本周课表", Ui.SP_DISPLAY, Ui.TEXT, 1.27f);

// 日期数字：15f bold  →  16sp medium + 1.47 行高
- TextView dd = Ui.text(this, day, 15f, sel ? 0xFFFFFFFF : Ui.TEXT, true);
+ TextView dd = Ui.textMediumLh(this, day, 16f, sel ? 0xFFFFFFFF : Ui.TEXT, 1.47f);

// 课程块课名：10f +1dp  →  11.5sp + 1.33 行高
- TextView tv = Ui.textMedium(this, disp, disp.length() <= 1 ? 16f : 10f, fg);
- tv.setLineSpacing(Ui.dp(this, 1), 1f);
+ TextView tv = Ui.textMediumLh(this, disp, disp.length() <= 1 ? 15f : 11.5f, fg, 1.33f);

// 地点：9f（不可读）→ 10.5sp
- TextView loc = Ui.text(this, ellip6(c.location), 9f, fg, false);
+ TextView loc = Ui.textLh(this, ellip6(c.location), Ui.SP_MICRO, fg, false, 1.33f);

// [单] / ✏️：30dp 热区 → 44dp
+ Ui.ensureTouch(this, singleBtn, 30);
+ Ui.ensureTouch(this, editBtn, 30);
```

---

## 五、验收清单

- [ ] 首页字号档位数 ≤ **7**（全页 grep 不到 `26f / 12.5f / 11f / 10f / 9f` 等散值）
- [ ] 首页所有中文 `TextView` **都设了行高**（`setLineSpacing` 或 `textLh`）
- [ ] 所有间距值落在 **4 的倍数**（`Ui.dp` 参数全为 4/8/12/16/…）
- [ ] 所有可点元素热区 **≥44dp**（`[单]`、`✏️`、`‹`、`›`、⊕ 逐项量）
- [ ] 圆角仅 **16 / 12 / 6** 三档（外加圆形钮 size/2）
- [ ] 课程块有 **6dp 圆角 + 4dp 上下内边距**
- [ ] 「快捷操作 2×2」**已渲染**，四个入口可点且行为正确
- [ ] 浅色「晴空蓝」与深色「夜幕蓝」**两套主题**均逐屏过一遍
- [ ] 对比度：正文 ≥4.5:1、大字 ≥3:1（`Ui.MUTED` 在两地主题下都要验）
- [ ] 其余 8 个 Activity **零回归**（因只新增方法、未改签名）

## 六、风险与回归要点

| 风险 | 说明 | 应对 |
|---|---|---|
| 字号放大导致换行 | 课名 10→11.5sp、地点 9→10.5sp 后可能溢出 48dp 块 | 块高已预留到 48dp；`ellip6` 截断逻辑保留，长名仍省略 |
| 课程块变高 | 44→48dp，多节课日列变长 | 网格可滚动（`wrapWithBottomBar` 已套 ScrollView），低屏幕机型验一屏可见行数 |
| 日期条 6dp 间隙 | 7 格 + 6 间隙可能超窄屏宽 | 保持 `weight=1 + 0dp` 均分（现状已是此方案），不要退回固定 43dp |
| `Ui.screen()` 改边距 | 影响全部页面 | 只改首页：在 `buildUi()` 内 `root.setPadding(...)` 覆盖，不动 `Ui.screen()` |
| 顶栏与大标题双行 | 顶栏 "Ev课程表" + 大标题 "本周课表" 并存 | 属设计选择：顶栏是 App 名，大标题是页名，保留；若嫌冗余可将顶栏降级为纯 ⊕ 行 |
