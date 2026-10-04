# 首页 UI 精细化优化方案

> 范围：`HomeActivity` 首页（EV 新首页）。
> 现状：首页已引入 `Ui` 设计令牌体系（字号 7 档 / 行高 / 4dp 间距栅格 / 圆角三档 / 触摸热区 44dp），
> 但代码里仍残留一批硬编码值（裸 `sp`、裸 `dp`、裸色值），且部分用 `Ui.text`（无行高）而非 `Ui.textLh`，
> 导致同一页面字号/间距/层级不完全统一、"中文挤成一团"。
> 目标：把剩余硬编码全部收敛到 Token，统一布局节奏与信息层级，做一次"零破坏性的精细化收尾"。

---

## 一、当前首页结构（自上而下）

| 层级 | 内容 | 关键实现 |
|------|------|----------|
| 顶栏 52dp | 居中「Ev课程表」+ 右上 ⊕（44dp 热区） | `buildUi()` 末尾 `root.addView(header, 0, …)` |
| 头部块 | 「Hi，同学」+「本周课表 / 回到本周」+ ‹ 周日期 › | `buildHeader()` |
| 周日期条 | 7 格（星期+日号），64dp 高，weight 均分 | `renderDateStrip()` |
| 周课表网格 | 课表名行（🔁+名 + [单] + ✏️）+ 带时间轴网格 + note | `renderWeek()` / `weekGridWithTime()` |
| 快捷操作 | 2×2：呼叫手环 / 上课了 / 留言 / 下课了 | `buildQuickBox()` |
| 连接迷你条 | ● 正在连接… | `miniBarView` |
| 错误卡 | 重试 / 打开手环 EV（默认隐藏） | `errorCard` |

> 底部还叠了 `Ui.wrapWithBottomBar`（底部导航栏），首页自身内容区需在安全内边距内即可。

---

## 二、问题清单：硬编码值 → 应收敛到 Token

> 行号为当前文件行号，会随改动漂移，请以 **符号名** 为准（如 `renderDateStrip`、`renderWeek`）。

### 2.1 裸字号（sp）

| 位置 | 当前值 | 语义 | 建议 |
|------|--------|------|------|
| `renderDateStrip` 日号 | `16f` | 日期条日号数字 | 新增令牌 `SP_NUM=16f`（正文强调级），用 `Ui.SP_NUM` |
| `renderWeek` 假期条 | `12.5f` | 假期/调休提示条 | `Ui.SP_CAPTION`（11.5 更贴合提示定位），若嫌小用 `Ui.SP_BODY` |
| `renderWeek` "连接手环后会自动同步课表" `12.5f` | `12.5f` | 空态占位 | `Ui.SP_CAPTION` |
| `quickCell` 文字 | `SP_CAPTION` | 已合规 | — |

### 2.2 行高缺失（用 `Ui.text` 而非 `Ui.textLh`）

| 位置 | 问题 | 建议 |
|------|------|------|
| 假期条 / 调休条 | `Ui.text(…, 12.5f, …)` 无行高，中文挤压 | 改 `Ui.textLh(…, SP_CAPTION, …, LH_CAPTION)` |
| 空态占位 | `Ui.text(…, 12.5f, …)` | 同上 |

### 2.3 裸间距（dp）

| 位置 | 当前值 | 语义 | 建议映射 |
|------|--------|------|----------|
| `renderDateStrip` 日期格间 `6`dp | `Ui.dp(6)` | 列间距 | 用 `GAP_SM` 体系，抽成语义量 |
| `renderDateStrip` 内 `dd.setPadding(0,2,0,0)` | `2` | 日号与星期间距 | 并入 `GAP_XS` 相邻值，或用 `Ui.dp + GAP_XS` |
| 时间轴表头 `timeSpacer` 36dp / 时间列宽 | `36` | 时间列宽 | 建议抽 `TIME_COL_W = 36`（首页语义常量）并用 `Ui.dp` |
| `weekGridWithTime` header 后 `space(4)` | `4` | 网格内间距 | `GAP_XS` 规范化 |
| 假期条 `padding(10,8,10,8)` | `10/8` | 提示条内边距 | `GAP_MD/GAP_SM` |
| `showPlusMenu` `pad=6` / 背景圆角 `12` / 行高 44 | `6/12/44` | 下拉菜单 | 圆角 `R_CTRL`，行高 `TOUCH_MIN` |

### 2.4 裸色值

| 位置 | 当前值 | 语义 | 建议 |
|------|--------|------|------|
| 假期条文字 `0xFF166534` / 底 `0x2E22C55E` | 绿 | 假期语义绿 | 用 `Ui.OK` / `Ui.OK` 淡背景（新增 `Ui.OK_LIGHT`） |
| 调休条文字 `0xFF92400E` / 底 `0x40FDE68A` | 橙 | 调休语义橙 | 用 `Ui.WARN` / 新增 `Ui.WARN_LIGHT` |
| 选中态文字 `0xFFFFFFFF`（日期/星期/表头/课表名[单]开关） | 白 | 跟随主题 | 新增 `Ui.ON_ACCENT`（主色上文字），正式替代硬白 |
| 假期 badge `0xFF7BD88F`/`0xFFFFC53D` 等 | 绿/黄 | 休/班角标 | 收敛到 `OK_LIGHT`/`WARN_LIGHT` 或 `OK`/`WARN` 亮态 |

---

## 三、新增设计令牌（补进 `Ui.java`，向后兼容）

```java
// ============ 首页精细化补充令牌 ============
public static final float SP_NUM   = 16f;  // 日期/数值强调（正文加一级）
public static int    ON_ACCENT;            // 主色底上的文字（浅主题近白/深主题近白）
public static int    OK_LIGHT;             // 假期绿淡背景
public static int    WARN_LIGHT;           // 调休橙淡背景
```

- `ON_ACCENT`：浅/深主题都取 `ADD4`，`applyTheme()` 里分别赋值（浅 `0xFFFFFFFF`、深 `0xFFFFFFFF`，跟随主色对比度定）。
- `OK_LIGHT` / `WARN_LIGHT`：浅色主题用当前 `0x2E22C55E / 0x40FDE68A` 系的 25% 透明版；深色主题用各自亮色 18~22% 透明版，保证暗底不刺眼。

> 遵循既有铁律：**所有颜色从 token 取，任何页面不写死颜色**。独立主题（`WatchAppearance` 自选色板）只有 7 色，`ON_ACCENT`/`OK_LIGHT`/`WARN_LIGHT` 在 `custom != null` 分支里用系统 `D/L` 兜底即可。

---

## 四、布局与间距优化

### 4.1 统一首页水平留白
- 现状：`buildUi()` 把 `Ui.screen(20/12)` 覆盖成 `16/8`（见 `root.setPadding`）。
- 建议：在 `Ui` 增加 `screenHome()`（水平 16 / 上 8），语义化首页专属留白，避免每页再裸覆盖。

### 4.2 模块垂直节奏（自上而下间距）
用 4dp 栅格统一：**顶栏 → 8dp → 头部 → 16dp → 日期条 → 16dp → 课表网格 → 16dp → 快捷 2×2 → 16dp → 迷你条**。
- 头部与日期条之间当前 `GAP_LG`（16）已合适，保持。
- 快捷区与迷你条之间加分隔视觉：迷你条用 `CARD2` 或左侧主色小竖条（`LINE` 分隔），避免"三张卡片"连排像拼接。

### 4.3 信息层级再分配
1. **主视觉 = 周课表网格**：保持当前权重，日期条/标题级联可再降一档对比（`MUTED` 弱化非本周日期）。
2. **快捷 2×2**：图标 24dp + 文案 Caption，靠 `CARD2` 底衬与课表主卡（`CARD+R_CARD`）区分主次。
3. **迷你条**：Caption + `MUTED`，始终一行不换行；连接失败时整卡可点 → 错误卡。

### 4.4 触摸热区核对
- [单]/✏️ 已 44×44，prev/next 已 44×44，⊕ 已 44×44 —— 达标。
- `errorCard` 两个按钮、`quickCell` 整格点击 —— 热区远大于 44，达标。
- 唯一需确认：日期条格子（高 64 已达标）、周标签（padding `GAP_SM`）触达舒适度，可加一点点击反馈（README 已用按压态，日期格可补 `RippleDrawable`）。

---

## 五、字号 / 行高 / 字重细则

| 元素 | 字号 | 字重 | 行高 | 说明 |
|------|------|------|------|------|
| 顶栏「Ev课程表」 | `SP_TITLE` | Medium | `LH_TITLE` | 已合规 |
| 首页大标题「本周课表」 | `SP_DISPLAY`(22) | Medium | `LH_DISPLAY` | 已合规，勿再上调（26 会抢焦点） |
| 问候「Hi，同学」 | `SP_BODY` | 常规 | `LH_BODY` | 已合规 |
| 日期数字 | `SP_NUM`(16) | Medium | `LH_SUBTITLE` | 新增令牌后引用 |
| 星期 / 周范围标签 | `SP_CAPTION` | Medium | `LH_MICRO` | 已合规 |
| 假期/调休提示条 | `SP_CAPTION` | 常规 | `LH_CAPTION` | 补行高 + 收字号 |
| 课表名 | `SP_SUBTITLE` | Medium | `LH_SUBTITLE` | 已合规 |
| 网格表头/时间 | `SP_MICRO` | Medium/常规 | `LH_MICRO` | 已合规 |
| 快捷操作文案 | `SP_CAPTION` | 常规 | `LH_CAPTION` | 已合规 |
| 迷你条 | `SP_CAPTION` | 常规 | `LH_CAPTION` | 已合规 |

> 字重已统一走 `textMedium`（sans-serif-medium）而非 `bold`，避免伪粗体发糊 —— 保持。

---

## 六、颜色 / 主题化

1. **语义色表意化**：假期绿 = `OK`，调休橙 = `WARN`，去掉散落的 `0xFF166534`/`0xFF92400E`。
2. **主色上文字**：所有选中态/开关开启态的 `0xFFFFFFFF` 文本改用 `Ui.ON_ACCENT`，深色主题可根据主色调整保证对比度。
3. **半透明底**：`0x2E22C55E`（绿 18% 透明）/ `0x40FDE68A`（橙 25%）抽成 `OK_LIGHT` / `WARN_LIGHT`，深浅主题各一套，避免暗底下发灰。

---

## 七、实施落地清单（按文件）

### `Ui.java`
1. 新增 `SP_NUM`、`ON_ACCENT`、`OK_LIGHT`、`WARN_LIGHT` 令牌与 `applyTheme()` 赋值。
2. 新增 `screenHome()`（水平 16 / 上 8）与 `screenLegacy()` 区分（可选）。

### `HomeActivity.java`
1. `renderDateStrip()`：
   - 日号 `16f` → `Ui.SP_NUM`；
   - 选中态硬白 → `Ui.ON_ACCENT`；
   - 补日期格点击 `RippleDrawable` 按压反馈。
2. `renderWeek()`：
   - 假期/调休条：`Ui.text(12.5f)` → `Ui.textLh(SP_CAPTION, LH_CAPTION)`，文字色改 `Ui.OK`/`Ui.WARN`，底色改 `OK_LIGHT`/`WARN_LIGHT`，内边距用 `GAP_MD/GAP_SM`；
   - 空态占位 `12.5f` → `SP_CAPTION`；
   - 课表名行 [单] 开关选中态硬白 → `Ui.ON_ACCENT`。
3. `weekGridWithTime()`：
   - 时间列宽 `36` 抽 `TIME_COL_W` 常量；
   - 表头空格 `space(4)` → `GAP_XS` 统一；
   - 表头/角标色用 token（`OK`/`WARN` + 主题感知），去掉硬编码十六进制。
4. `buildQuickBox()`：图标 24dp 保留，文字间距/换行核对（两字文案避免换行）。

> 改完全局搜索 `HomeActivity` 内残留的：裸 `f` 字号、`Ui.text(`（漏行高）、`0xFF`/`0x` 色值、裸 `Ui.dp(裸数字)`，逐一收敛。

---

## 八、验收清单

- [ ] 首页无任何写死的 sp 字号（全走 `Ui.SP_*`）。
- [ ] 首页无任何写死的十六进制色（全走 `Ui.*` token）。
- [ ] 所有中文多行文本均经 `Ui.textLh` 设行高，中文不贴挤。
- [ ] 所有可点元素热区 ≥ 44dp，且有点击按压反馈。
- [ ] 浅色「晴空蓝」/ 深色「夜幕蓝」/ 自定义主题下，`ON_ACCENT`/`OK_LIGHT`/`WARN_LIGHT` 均对比可读。
- [ ] 假期/调休/断网/无课表 空态均正常。
- [ ] 布局：顶部纵向不溢出，底部被导航栏遮挡部分已加安全内边距。

---

## 备注（不涉及：避免过度工程）

- **不动** `Ui.screen()` 全局行为、底部导航、课表数据层。
- 纯粹是"把散装值装进 token + 统一节奏/层级"的精细化收尾，**不影响功能与同步逻辑**。
- 若独立主题（`WatchAppearance`）暴露 7 色之外还需语义色，后续再加独立色板字段，本期先用系统色兜底。