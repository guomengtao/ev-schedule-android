# EV同步器 UI 设计规范 v1（唯一真源）

> 生效：2026-11-05 17:04 +0800 · 单号 `evtask-D-evev-schedul-261105-47k334`
> 适用：`apk/src/com/application/watch/classschedule/**/*.java` 的**所有页面与组件**，含以后新增的页面。
> **本文是「以后设计各个页面」的唯一规范。** 任何新的「XX 页全新设计方案」不得另起炉灶，只能作为本文附录或修订。
> 更高层规则优先：总纲 §3.14 Lucide 图标铁律、`.trae/rules/project_rules.md` 的 Icon Policy（禁止 emoji / Unicode 符号当图标）。本文只管视觉与结构。
> **为什么不写进总纲**：这是单个 App 的项目级规范，不是跨仓铁律；按总纲 §3.5a「正文只写一份」与 §3.18「文档落盘铁律」，正文放本项目 `docs/`，其余位置只留指针。

---

## 0. 为什么要有这份规范（病因，别再重复）

| 现象 | 数据 / 出处 |
|---|---|
| 逐页改版、没有总设计系统 | `docs/` 里 20+ 份「XX 页全新设计方案 / 原型 / 精修二三四期」 |
| token 只做到静态层 | `Ui.java` 有颜色 / 字号 / 行高 / 间距 / 圆角，缺字体、elevation、motion、空状态 |
| 页面各自发明数值 | 宽口径 grep **487 处**样式字面量；脚本三条硬规则（颜色 / 字号 / 圆角）实测命中 **176 处、涉及 53 个文件**（`tools/check-ui-spec.py --all`） |
| 结果 | 单页都干净，连起来像不同产品拼的；上一轮诊断的「老土感」主要来自这层，不是缺 UI 库 |

**目标**：把「风格一致」变成**机器可校验**，而不是靠每次改版当下的审美。规范的价值不在写得漂亮，在于有人（包括 AI）改页面时能卡住他。

---

## 1. 选型边界（已定案，不再反复讨论）

- **不引入任何第三方 UI 库**：Compose + Material 3、MDC-Android（View 版）、XUI、QMUI_Android 都不引。
- 硬理由：`apk/build.sh` 是手工 `aapt2 → javac(JDK8) → d8 → apksigner` 链，无 Gradle / AndroidX / AppCompat / AAR 资源合并；引入任一 AAR 都要改 4 处并手工合并十几个传递依赖，约等于重写迷你 Gradle。目标机 Android 8 / EMUI 8 拿不到 Material You 动态取色；现包体 523KB。
- QMUI 的「全局配置表 + 状态栏沉浸式」**只借思路，自己写**，不引库。
- **Revisit 条件**：只有整体重写为 Gradle / Kotlin 工程时，才重新评估 Compose M3。

---

## 2. 唯一真源：`Ui.java`

所有颜色、字号、行高、间距、圆角、触摸热区只能从 `Ui.*` 取。**页面禁止自己发明数值。**

### 2.1 必用工厂（部分不只是审美，是已修 P0 的防回归线）

| 工厂 | 用途 | 绕过它的后果 |
|---|---|---|
| `Ui.screen(a)` | 页面根容器入口 | `applyTheme()` 必须在 `setContentView` 前 `setTheme`，否则系统控件（Switch / Dialog / 光标）不跟随深浅色 |
| `Ui.editText(c)` | **全仓输入框唯一入口** | 中文输入法全屏提取回归：黑屏 + 「完成」键闪烁（v0.5.178 / 179 已修） |
| `Ui.needRecreate(a, lastVersion)` | **onResume 唯一重建通道** | 键盘可见 / 30s 防抖失效，主题切换 + 输入法抖动导致整窗重建黑屏 |
| `Ui.textMedium*` / `textMediumLh` | 中文标题与强调的主用字重 | `bold` 是中文伪粗体，发糊；`sans-serif-medium` 才是真字重 |
| `Ui.card` / `cardElevated` / `round` / `button` / `row` / `divider` / `space` | 卡片、圆角、按钮、列表行、分隔、留白 | 各页自己画 → 圆角 / 描边 / 投影漂移 |
| `Ui.header` / `topBar` / `bottomBar` / `wrapWithBottomBar` / `fixedWithBottomBar` | 顶栏与底部导航 | 导航高度、选中态、返回行为不一致 |
| `Ui.dp` / `ensureTouch` / `courseColor` / `onCourseColor` / `readableAccent` | 单位换算、44dp 热区、课程色与对比度 | 小热区、课程色跨页不一致、色块上文字看不清 |

> `Ui.applyTheme()` / `themeVersion` 由 `screen()` 内部调用，页面**不要自己调**；主题变化只认 `needRecreate()`。

### 2.2 新增代码禁止的写法

```java
t.setTextColor(0xFF2F6BFF);        // ✗ 写死颜色 → Ui.ACCENT
t.setTextSize(14f);                 // ✗ 写死字号 → Ui.SP_BODY
g.setCornerRadius(Ui.dp(c, 10));    // ✗ 写死圆角 → Ui.R_CTRL
v.setElevation(Ui.dp(c, 6));        // ✗ 任意 elevation → Ui.cardElevated() 或 E_*（P1 落地后）
Ui.text(c, "标题", 17f, Ui.TEXT, true); // ✗ 中文标题用 bold → Ui.textMedium(...)
```

---

## 3. Token 表（v1 现行值）

### 3.1 语义色（15 个，浅色「晴空蓝」/ 深色「夜幕蓝」）

| Token | 浅色 | 深色 | 用途 |
|---|---|---|---|
| `BG` | `#F4F7FB` | `#0B1020` | 页面背景 |
| `CANVAS` | `#E9EAEF` | `#060912` | `screen()` 根容器底色 |
| `CARD` | `#FFFFFF` | `#151C30` | 卡片表面 |
| `CARD2` | `#EAF0F8` | `#1C2542` | 次级表面 / 非主按钮 |
| `LINE` | `#E3EAF3` | `#27314C` | 描边 / 分隔线（0.5~1dp） |
| `TEXT` | `#0F172A` | `#E9EEF9` | 主文字 |
| `MUTED` | `#64748B` | `#8695B4` | 次要文字 / 说明 |
| `ACCENT` | `#2F6BFF` | `#5B9BFF` | 主色 / 主按钮 / 选中态 |
| `ACCENT_LIGHT` | `#EBF0FF` | `#1A2544` | 今天列 / 主色淡底 |
| `OK` / `WARN` / `ERR` | `#16A34A` / `#F59E0B` / `#DC2626` | `#34C759` / `#FFB020` / `#FF6B6B` | 成功 / 警告 / 失败 |
| `ON_ACCENT` | `#FFFFFF` | `#FFFFFF` | 主色底上的文字 |
| `OK_LIGHT` / `WARN_LIGHT` | 带 alpha 淡底 | 同左 | 假期 / 调休语义底 |

### 3.2 排版（8 档字号 + 行高倍数）

| Token | 值 | 行高 | 用途 |
|---|---|---|---|
| `SP_DISPLAY` | 22 | `LH_DISPLAY` 1.27 | 页面大标题 |
| `SP_TITLE` | 17 | `LH_TITLE` 1.41 | 分区标题 / 顶栏 |
| `SP_SUBTITLE` | 15 | `LH_SUBTITLE` 1.47 | 次标题 / 日期 |
| `SP_BODY` | 13 | `LH_BODY` 1.54 | 正文 / 按钮 / 课名 |
| `SP_CAPTION` | 11.5 | `LH_CAPTION` 1.52 | 次要说明 / 星期 |
| `SP_MICRO` / `SP_TAB` | 10.5 | `LH_MICRO` 1.48 | 时间轴 / 底部导航 |
| `SP_NUM` | 16 | — | 日期 / 数值强调 |

> 中文字重只走 `Ui.textMedium*`；`bold=true` 仅限数值强调。**禁止**在页面里出现 `if (Ui.isDark())` 的颜色分支，一律用 token。

### 3.3 间距 / 圆角 / 热区 / 课表网格

| Token | 值 | 备注 |
|---|---|---|
| `GAP_XS / SM / MD / LG` | 4 / 8 / 12 / 16 dp | 4dp 基线，间距只允许这四档 |
| `R_CARD / R_CTRL / R_BLOCK` | 14 / 12 / 4 dp | 卡片 / 控件 / 课程块（块短边 ~34dp，R 不能再大） |
| `TOUCH_MIN` / `ensureTouch` | 44 dp | 最小触摸热区 |
| `ROW_PITCH` | 48 dp | 周课表行距唯一真源 |
| `BLOCK_H_2LINE / BLOCK_H_1CHAR` | 44 / 40 dp | 双行块 / 单字块 |
| `BLOCK_MARGIN / COL_PAD` | 1 / 1 dp | 天列与课程块横向缝 |

### 3.4 课程区分色（7 色，按课名 hash 稳定）

`#5B6CF9` `#33C77A` `#FF9E42` `#9C6BF7` `#35C4D6` `#FF7AA8` `#FF6B6B`

> **v1 待改（P1）**：这组高饱和纯色块是「老土 / 花」的最大来源。改为降饱和 + 浅底深字，或统一色相只用明度区分；只改 `Ui.COURSE_COLORS` 一组常量，页面零改动。文字色必须走 `Ui.onCourseColor(bg)`。

### 3.5 新增 token 的流程

**先在 `Ui.java` 定义 → 同步更新本文表格 → 才允许页面使用。** 禁止在页面里就地发明数值；禁止「先用着，回头再抽 token」。

---

## 4. v1 要补齐的四层（改动方向，不动存量）

| 层 | 现状 | v1 规则 |
|---|---|---|
| 字体 / 字重 | 无任何 `ttf/otf`，系统黑体；只有 NORMAL / BOLD / `sans-serif-medium` | 短期：`textMedium*` + 行高分层，标题加 `letterSpacing`；中期评估一份可变字重中文字体（先看包体） |
| Elevation | 散落 3/4/6/8 dp，无层级语义 | 收敛三档：`E0` 平（0）/ `E1` 卡片（4dp）/ `E2` 浮层与吸底条（8dp）。页面禁止直接 `setElevation` |
| Motion / 状态 | 仅少数 `RippleDrawable` | 点击必须有按压态；统一时长 120 / 200 / 300ms；页面转场与数值变化用同一条曲线；禁止无反馈点击 |
| 空状态 / 状态栏 / 反馈 | 裸文字空状态；EMUI 灰状态栏；结果条与触发控件分离 | 空状态统一「图标 24dp + 一句主文案 + 可选动作」；状态栏背景跟 `BG`，**不做透明状态栏**（EMUI 8 兼容坑）；反馈就地化：结果与触发控件同容器，禁止全局 `resultView`（设置页 v0.5.152 原则 / 高级版页方案 D6） |

---

## 5. 页面骨架（以后每个页面照这个写）

```java
// 1) onCreate：root 必须来自 Ui.screen
LinearLayout root = Ui.screen(this);
root.addView(Ui.header(this, "页面标题"));          // SP_TITLE + medium
LinearLayout card = Ui.card(this);                  // CARD + R_CARD + GAP_MD
card.addView(Ui.textMediumLh(this, "分区", Ui.SP_TITLE, Ui.TEXT, Ui.LH_TITLE));
// ... 内容：一律 Ui.* 工厂，状态四态见下
setContentView(Ui.wrapWithBottomBar(this, root, TAB_INDEX));

// 2) onResume：唯一重建通道
if (Ui.needRecreate(this, lastThemeVersion)) { recreate(); return; }
```

补充规则：

1. **分组按语义，不按控件类型**；同一屏卡片间距统一 `GAP_MD`，卡片内 `GAP_SM`。
2. **状态四态**：加载（`MUTED` + 占位）／空（§4.4 结构）／成功（`OK` + `OK_LIGHT` 就地条）／失败（`ERR`，文案必须写用户能照做的下一步）。
3. **44dp 热区**：所有可点元素走 `Ui.ensureTouch(c, v, visualDp)`。
4. **深浅色**：不写颜色分支，不写 `Color.parseColor`。
5. **图标**：来自 Lucide 线性 vector（`apk/res/drawable/`），禁止 emoji / Unicode 符号 / 自造字形（总纲 §3.14）。

---

## 6. 硬编码禁令 + 存量豁免（规范可执行的关键）

- **新代码四条禁令**：写死颜色（`0xFF........` / `Color.parseColor`）、写死字号（`setTextSize(数字)`）、写死圆角（`setCornerRadius(数字)`）、任意 `setElevation(数字)`。
- **存量 grandfathering**：`--all` 当前命中 176 处，不要求一次性清理；**只在改到该文件或该行时顺手收敛**。`tools/check-ui-spec.py` 默认以 `git diff` 的**新增行**为界，存量不报、不阻断（无新增 Java 行时输出 `0 line(s) checked` 属正常）。
- **白名单（常量定义处）**：`Ui.java`、`WatchAppearance.java`。其他文件确需品牌色常量，必须写注释说明理由并登记到下表，否则算违规。

| 文件 | 例外内容 | 理由 | 登记日期 |
|---|---|---|---|
| （初始为空） | | | |

---

## 7. 机器校验与验收

```bash
python3 tools/check-ui-spec.py          # 只查新增行；有违规退出码 1（提交前应通过）
python3 tools/check-ui-spec.py --all    # 全量报告，只看迁移进度，不阻断
```

每次改 UI 的**五条验收**（缺一不算完成）：

1. 校验脚本通过，新增行零硬编码；
2. 真机浅色 / 深色各截一屏，归档 `apk/shots/<page>-<theme>-v<version>.png`（沿用现有命名）；
3. 中文输入法弹出 / 收起不黑屏、完成键不闪烁（`Ui.editText` 防线）；
4. 主题切换不触发窗口连环重建（`Ui.needRecreate` 防线）；
5. 可点元素 ≥44dp 热区，浅深两套文字对比度可读。

---

## 8. AI 会话工作流（这才让规范「以后」生效）

改任一页面前必走：

1. **读本文 + `Ui.java` token 段**（不要只读那一页的旧设计方案）；
2. 确认所需 token 已存在；不存在 → 先补 `Ui.java` 与本文 §3 表格；
3. 按 §5 骨架写页面，图标走 Lucide；
4. 跑 `python3 tools/check-ui-spec.py`；
5. 真机按 §7 五条验收；
6. 提交信息带 `ui-spec: v1`，便于回溯。

**禁止**：新开一份「XX 页全新设计方案」而不回流本文 —— 那正是风格漂移的源头。新方案只能作为本文附录或修订，并更新 §10 变更日志。
**多会话并行**：同一 token 的改动要 `ev-claim` 登记，避免两边各写一套色板。

---

## 9. 迁移路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 本文 + `.codebuddy/rules/ui-design-spec.md` 规则入口 + `tools/check-ui-spec.py`；**新代码立即强制** | ✅ 本次落地 |
| **P1** | `COURSE_COLORS` 降饱和；字重分层；`E0/E1/E2` 三档写进 `Ui.java`；状态栏背景统一；把校验规则扩到 elevation | ⬜ 下次改版 |
| **P2** | 可变字重字体（先评估包体）、motion 规范、空状态组件化、`ev map` 登记 lint 字段 | ⬜ 可选 |

**P0–P2 全程不引入第三方 UI 库**（§1 已定案）。

---

## 10. 变更日志

| 日期 (+0800) | 版本 | 改动 | 单号 |
|---|---|---|---|
| 2026-11-05 17:04 | v1 | 首版：确立 `Ui.java` 唯一真源、token 表、页面骨架、防回归硬约束、硬编码禁令 + 存量豁免、机器校验、AI 工作流、迁移路线 | `evtask-D-evev-schedul-261105-47k334` |
