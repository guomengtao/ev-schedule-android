# 首页 UI 布局优化方案

> 参考设计：`preview.html` 中提供的「Ev课程表 · 周总览」设计稿（浅色主题，390×844 手机尺寸，含周课表网格 + 小米手环卡片 + 底部 Pill 导航）。

---

## 一、设计对比总览

### 当前首页 vs 参考设计

| 维度 | 当前首页 | 参考设计 |
|------|---------|----------|
| **背景** | 单一纯色 `0xFFF4F7FB`（浅色） | `#F4F5FA` 画布色，外层 `#E9EAEF` 更柔和的灰底 |
| **卡片** | `round(CARD, 14, LINE)` 白色卡 + 1px 描边 | `box-shadow: 0 4px 12px rgba(26,30,51,0.06)` 无边框投影卡片 |
| **周日期** | 纯文字 `一 二 三 四 五 六 日`，今天蓝色高亮 | 独立 `day-cell` 圆角方块（43×62），选中态紫色填充，含今天标记 |
| **课表网格** | 7 列等宽，色块堆叠，无时间轴 | 8 列（时间列 + 7 天），时间列在左，行高固定 42/40px，对齐清晰 |
| **快捷操作** | 2×2 按钮网格（呼叫手环/上课了/留言/下课了） | 无此区块 |
| **设备卡片** | 无 | 小米手环 8 卡片：设备名、连接状态、电量、4 个操作按钮 |
| **底部导航** | 3 个 tab（首页/课程表/设置），渐进色底 | 4 个 tab（课表/周视图/消息/设置），Pill 胶囊容器 |
| **状态栏** | 无模拟状态栏 | 带信号/WiFi/电量图标的时间条 |
| **字号层级** | 标题 19sp，正文 12-13sp，小字 10.5sp | 标题 26sp，正文 13-15sp，辅助 9-11sp（层级更丰富） |
| **课程颜色** | 12 色哈希分配，色块全宽 | 7 色系统（蓝/绿/橙/紫/青/粉/红），色块内文字白色 |
| **圆角** | 卡片 14dp，按钮 12dp | 卡片 16-18dp，日期格 14dp，按钮 12dp，Pill 36dp |

---

## 二、核心改进方向

### 2.1 背景层次：增加画布层

**现状**：`screen()` 方法设置 `root.setBackgroundColor(BG)`，整个页面一种颜色。

**建议**：

```java
// Ui.java 新增
public static int CANVAS;  // 画布层颜色，比 BG 稍浅/稍深

// 浅色主题
0xFFE9EAEF  // CANVAS - 类似参考设计的 body background
0xFFF4F5FA  // BG     - 内容区底色

// 深色主题
0xFF060912  // CANVAS
0xFF0B1020  // BG
```

页面根容器用 `CANVAS`，内容区用 `BG`，形成 2 层背景深度。

---

### 2.2 卡片样式：从描边 → 投影

**现状**：`round(CARD, 14, LINE, c)` —— 白色卡片 + 1px 灰色描边，扁平感强。

**建议**：去掉描边，改用投影提升层次感。

```java
// Ui.java 新增 card 变体
public static LinearLayout cardElevated(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    l.setBackground(round(CARD, 16, 0, c));
    // Android P+ 原生投影
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        l.setOutlineAmbientShadowColor(0x1A1A334D);
        l.setOutlineSpotShadowColor(0x1A1A334D);
        l.setElevation(dp(c, 6));
    }
    return l;
}
```

> 参考设计使用 `box-shadow: 0 4px 12px rgba(26, 30, 51, 0.06)` 营造轻投影。

---

### 2.3 周日期条：独立日期方块

**现状**：7 个文字标签 `一 二 三 四 五 六 日`，今天用 `ACCENT` 颜色加粗。

**建议**：改为横向滑动的日期方块（43dp × 62dp），每个含星期、日期、状态点。

```java
// 新增 DateCellView 或直接在 HomeActivity 中构建
private View dateCell(int dayOfWeek, int dayOfMonth, boolean selected) {
    LinearLayout cell = new LinearLayout(this);
    cell.setOrientation(LinearLayout.VERTICAL);
    cell.setGravity(Gravity.CENTER);
    cell.setLayoutParams(new LinearLayout.LayoutParams(dp(43), dp(62)));

    if (selected) {
        cell.setBackground(Ui.round(Ui.ACCENT, 14, 0, this));
        cell.addView(Ui.text(this, weekLabel[dayOfWeek], 11f, 0xFFFFFFFF, false));
        cell.addView(Ui.text(this, String.valueOf(dayOfMonth), 15f, 0xFFFFFFFF, true));
    } else {
        cell.setBackground(Ui.round(Ui.CARD, 14, 0, this));
        cell.addView(Ui.text(this, weekLabel[dayOfWeek], 11f, Ui.MUTED, false));
        cell.addView(Ui.text(this, String.valueOf(dayOfMonth), 15f, Ui.TEXT, true));
    }
    return cell;
}
```

> 参考设计的 `day-cell` 选中有紫色背景（`var(--primary)`）和白色文字。

---

### 2.4 周课表网格：加入时间轴

**现状**：纯 7 列堆叠课程色块，无时间列，上下不对齐。

**建议**：改为 8 列网格，左侧 30dp 时间列 + 7 天列。

```
  |  周一 | 周二 | 周三 | 周四 | 周五 | 周六 | 周日
--|------|------|------|------|------|------|-----
8 | 数学  |      | 英语  |      | 语文  |      |
--|------|------|------|------|------|------|-----
10|      | 物理  |      | 化学  |      |      |
--|------|------|------|------|------|------|-----
14| 体育  |      | 班会  |      | 自习  |      |
```

实现要点：
- 时间列固定宽度 30dp，行高 42dp，灰色小字显示时段。
- 课程块固定高度 40dp，与时间行对齐。
- 今天列背景加淡色蒙层（`rgba(ACCENT, 0.08)`）。
- 空课时段显示空白块（`background: white`），保证网格整齐。

```java
// 伪代码示意
private View weekGridWithTime(List<CourseCache.Course> all) {
    LinearLayout grid = new LinearLayout(this);
    grid.setOrientation(LinearLayout.HORIZONTAL);

    // 时间列
    LinearLayout timeCol = new LinearLayout(this);
    timeCol.setOrientation(LinearLayout.VERTICAL);
    for (String period : TIME_PERIODS) {
        timeCol.addView(timeSlot(period));
    }
    grid.addView(timeCol);

    // 7 天列
    for (int d = 0; d < 7; d++) {
        LinearLayout dayCol = new LinearLayout(this);
        dayCol.setOrientation(LinearLayout.VERTICAL);
        if (d == today) {
            dayCol.setBackground(/* rgba(ACCENT, 0.08) */);
        }
        for (String period : TIME_PERIODS) {
            CourseCache.Course c = findCourse(all, d, period);
            dayCol.addView(courseSlot(c));
        }
        grid.addView(dayCol);
    }
    return grid;
}
```

> 参考设计将课程块统一为 40px 高、圆角 5px、白色粗体字、彩色背景。

---

### 2.5 新增：设备信息卡片

**现状**：首页无设备信息展示，仅迷你状态条。

**建议**：在课表网格下方新增设备卡片，参考设计包含：

| 区域 | 内容 |
|------|------|
| 左侧 | 手环图标 + 设备名称 + 连接状态绿点 |
| 右侧 | 电量图标 + 百分比 |
| 操作区 | 4 个按钮：手环课程同步 / 呼叫手环 / 发消息给手环 / 连接手环 |

当前快捷操作区（呼叫手环/上课了/留言/下课了）可整合到设备卡片中。

```java
// HomeActivity.java 新增 buildDeviceCard()
private View buildDeviceCard() {
    LinearLayout card = Ui.cardElevated(this);
    card.setPadding(dp(16), dp(16), dp(16), dp(16));

    // 顶部行：设备信息 + 电量
    LinearLayout top = new LinearLayout(this); // horizontal
    // 手环图标 46×46 圆角方块，ACCENT 12% 透明度底色
    // 设备名 16sp bold + 连接状态绿点 7×7 + "已连接" 12sp green
    // 电量图标 + "82%" 11sp
    card.addView(top);

    // 操作网格 2×2
    card.addView(actionGrid());

    return card;
}
```

---

### 2.6 色彩系统优化：从 12 色 → 7 色语义色

**现状**：`COURSE_COLORS` 12 色调色板，按 `hashCode` 分配。

**建议**：参考设计使用 7 种语义色，每种有明确含义：

| 颜色 | 色值 | 用途 |
|------|------|------|
| 蓝 `c-blue` | `#5B6CF9` | 主色调，也用于部分课程 |
| 绿 `c-green` | `#33C77A` | 成功状态、体育类课程 |
| 橙 `c-orange` | `#FF9E42` | 警告、实验类课程 |
| 紫 `c-purple` | `#9C6BF7` | 选修课、艺术类 |
| 青 `c-cyan` | `#35C4D6` | 信息类课程 |
| 粉 `c-pink` | `#FF7AA8` | 活动类 |
| 红 `c-red` | `#FF6B6B` | 错误、考试 |

可在课表数据中增加 `color` 字段，或保持 hash 分配但缩小到 7 色。

---

### 2.7 底部导航：从横条 → Pill 胶囊

**现状**：3 个 tab（首页/课程表/设置），全宽横条，选中 tab 底色 `CARD2`。

**建议**：改为 Pill 胶囊式导航：

- 外层容器：`width: 100%; height: 62dp; border-radius: 36dp`，白色背景 + 投影。
- 4 个 tab：课表 / 周视图 / 消息 / 设置。
- 选中 tab：`ACCENT` 底色，白色文字，26dp 圆角。
- 未选中 tab：透明底色，`MUTED` 文字。

```java
// Ui.java bottomBar 改造
public static LinearLayout pillBar(final Activity a, int current, String[] labels, Class[] targets) {
    LinearLayout pill = new LinearLayout(a);
    pill.setOrientation(LinearLayout.HORIZONTAL);
    pill.setBackground(round(CARD, 36, LINE, a));
    pill.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
    pill.setElevation(dp(a, 8));

    for (int i = 0; i < labels.length; i++) {
        LinearLayout tab = new LinearLayout(a);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        boolean active = (i == current);
        tab.setBackground(round(active ? ACCENT : 0x00000000, 26, 0, a));

        tab.addView(text(a, labels[i], 10f, active ? 0xFFFFFFFF : MUTED, true));
        tab.setOnClickListener(/* navigate to targets[i] */);
        pill.addView(tab, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));
    }
    return pill;
}
```

> 参考设计的 `.pill` 容器 `border-radius: 36px`，内含 `border-radius: 26px` 的 tab 项。

---

### 2.8 主页头部：信息层级重组

**现状**：`EV 课程表` 标题 + 同步按钮 + 课表名 + 周视图。

**建议**：参考设计采用更自然的信息架构：

```
[头像 E]                    [通知铃铛] [搜索]
Hi，李雷
本周课表             ← 9.28-10.04 →
```

- 用户头像：圆形渐变头像 44×44dp，首字母。
- 问候语 + 姓名，14sp 灰色。
- 页面标题「本周课表」26sp bold。
- 周切换器：左右箭头 + 日期范围，替代当前的纯文字周切换。

```java
private View buildHeader() {
    LinearLayout header = new LinearLayout(this);
    // topRow: 头像 + 通知/搜索图标
    LinearLayout topRow = ...;
    topRow.addView(avatarCircle("李")); // 取昵称首字
    topRow.addView(notificationIcon());
    topRow.addView(searchIcon());

    // titleRow: 问候 + 标题 + 周切换
    LinearLayout titleRow = ...;
    titleRow.addView(greetingBlock("Hi，李雷", "本周课表"));
    titleRow.addView(weekSwitcher()); // < 9.28-10.04 >

    return header;
}
```

---

## 三、完整布局方案（从上到下）

```
┌─────────────────────────────┐
│ 状态栏 (9:41 | 信号 WiFi 电池)│  ← 新增
├─────────────────────────────┤
│ [头像]         [铃铛] [搜索] │  ← 新增头像+图标行
│ Hi，李雷                    │  ← 新增问候语
│ 本周课表        < 9.28-10.04 >│  ← 大标题 + 周切换
├─────────────────────────────┤
│  一  二  三  四  五 [六]  日 │  ← 日期方块条（今天选中）
│  ...  ...  ...  ... 28  29 │
├─────────────────────────────┤
│ 时间│一│二│三│四│五│六│日  │
│  8  │数│ │英│ │语│ │   │  ← 周课表网格（带时间轴）
│ 10  │ │物│ │化│ │ │   │
│ 14  │体│ │班│ │自│ │   │
├─────────────────────────────┤
│ 小米手环 8          🔋82%  │
│ ● 已连接                   │  ← 设备卡片
│ [手环课程同步] [呼叫手环]    │
│ [发消息给手环] [连接手环]    │
├─────────────────────────────┤
│ ┌─────────────────────────┐│
│ │ 课表 │ 周视图 │ 消息 │设置││  ← Pill 胶囊导航
│ └─────────────────────────┘│
└─────────────────────────────┘
```

---

## 四、设计 Token 扩展建议

在 `Ui.java` 中新增以下 Token：

```java
// ============ 新增 Token ============
public static int CANVAS;          // 外层画布色
public static int ACCENT_LIGHT;    // 主色浅色版，用于选中态背景
public static int SHADOW;          // 投影色
public static int WHITE;           // 纯白，课表网格空位用

// 新增课程语义色（替换 COURSE_COLORS）
public static final int[] COURSE_SEMANTIC = {
    0xFF5B6CF9, // c-blue
    0xFF33C77A, // c-green
    0xFFFF9E42, // c-orange
    0xFF9C6BF7, // c-purple
    0xFF35C4D6, // c-cyan
    0xFFFF7AA8, // c-pink
    0xFFFF6B6B, // c-red
};

// 浅色主题扩展
L[10] = 0xFFE9EAEF;  // CANVAS
L[11] = 0xFFEBF0FF;  // ACCENT_LIGHT (主色 8% 透明混白)
L[12] = 0x1A1A334D;  // SHADOW (6% 黑)
L[13] = 0xFFFFFFFF;  // WHITE

// 深色主题扩展
D[10] = 0xFF060912;  // CANVAS
D[11] = 0xFF1A2544;  // ACCENT_LIGHT
D[12] = 0x00000000;  // SHADOW (深色不用投影)
D[13] = 0xFF151C30;  // WHITE → CARD
```

---

## 五、字号与间距规范

| 用途 | 字号 | 字重 | 颜色 |
|------|------|------|------|
| 页面标题 | 26sp | 700 (bold) | TEXT |
| 问候语 | 14sp | 400 | MUTED |
| 设备名 | 16sp | 700 | TEXT |
| 卡片标题 | 15sp | 600 | TEXT |
| 正文 | 13sp | 400-500 | TEXT |
| 按钮文字 | 12-13sp | 600 | 白/TEXT |
| 辅助说明 | 11-12sp | 400-500 | MUTED |
| 日期数字 | 15sp | 700 | TEXT/白 |
| 星期标签 | 11sp | 500 | MUTED/白 |
| 课程块文字 | 9-10sp | 700 | 白 |
| 时间列文字 | 9sp | 500 | MUTED |
| 底部导航 | 10sp | 600 | MUTED/白 |

| 间距类型 | 值 |
|----------|-----|
| 页面水平内边距 | 20dp |
| 卡片间垂直间距 | 12dp |
| 卡片内边距 | 14-16dp |
| 日期方块间距 | 6dp |
| 课表列间距 | 3dp |
| 课程块间距 | 3dp |
| 按钮间距 | 10dp |
| 底部导航外边距 | 12dp 两侧 + 21dp 底部 |

---

## 六、实施优先级

| 优先级 | 改动项 | 影响范围 | 工作量 |
|--------|--------|---------|--------|
| **P0** | 周课表加入时间轴列 | HomeActivity.renderWeek() | 中 |
| **P0** | 日期方块条 | HomeActivity 新增方法 | 小 |
| **P1** | 设备信息卡片 | HomeActivity 新增方法 | 中 |
| **P1** | 卡片从描边改投影 | Ui.card() 变体 | 小 |
| **P1** | 头部重组（头像+问候+周切换） | HomeActivity.buildUi() | 中 |
| **P2** | Pill 胶囊导航 | Ui.bottomBar() 改造 | 中 |
| **P2** | 7 色语义色系统 | Ui.java COURSE_COLORS | 小 |
| **P3** | 两层背景（CANVAS + BG） | Ui.screen() | 小 |
| **P3** | 设计 Token 扩展 | Ui.java | 小 |

---

## 七、注意事项

1. **兼容性**：所有改动需同时支持浅色「晴空蓝」和深色「夜幕蓝」两套主题，以及「跟随手环」的 10 套自定义主题。
2. **EvBox 变体**：`HomeActivity` 有 `legacy` 分支，新 UI 仅用于 EV 变体，EvBox 保持现有连接工具型首页。
3. **底部导航 tab 数量**：当前 3 个 tab（首页/课程表/设置），参考设计 4 个（增加消息 tab）。消息 tab 是否加入需评估。
4. **性能**：课程块使用 `LinearLayout` 而非 `RecyclerView`，课程数量少（7天 × 5节 = 35个），性能可接受。
5. **用户规则**：代码注释使用英文，文档使用中文。