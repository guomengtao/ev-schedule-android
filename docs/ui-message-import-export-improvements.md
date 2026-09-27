# EV 同步器 APK：问题诊断与改进方案

> 范围：`ev-schedule-android` 安卓同步器 APK（v0.5.11）。
> 对照对象：手环端 EV 主仓 `tom/class/class`（快应用，v1.6.139 构建产物）、AstroBox 插件 `app-auth/tools/ev-schedule-sync`。
> 日期：2026-09-27
> 本文只做分析与方案，不含代码改动。

---

## 〇、结论速览

| # | 问题 | 根因判断 | 性质 | 优先级 |
|---|---|---|---|---|
| 1 | 首页滚动失败，底部内容看不到 | `ScrollView` 的子 View 未给 `WRAP_CONTENT` 高度参数，被默认成 `MATCH_PARENT` → 内容被裁、不可滚；且底栏悬浮盖住内容尾部 | **纯 APK 代码 bug** | P0 |
| 2 | 底部三个常驻按钮"缺了" | 只有 首页/聊天/设置 三个页挂了底栏，`TransferActivity`/`DebugActivity` 完全没有；`wrapWithBottomBar` 还会误删内容最后一项 | **纯 APK 代码 bug** | P0 |
| 3 | 聊天 → 留言模式 | 需求变更（不做实时 IM），下行已有 `chat` 通道可用；缺"离线暂存 + 连接后补发/拉取" | **产品+APK 改造**（可选含 EV 小改） | P1 |
| 4 | 手机后台监控新留言 + 响铃/震动 | **可以**（前台服务 + 周期轮询）；**但"进程被杀后仍能收到"不可行**（无推送通道） | 需 APK 大改 + EV 加一个拉取接口 | P1 |
| 5 | 导入课程表：多选课 / 随机示例 JSON / 复制按钮 | 现状只能"选文件"，无粘贴框、无示例、无复制、无多选预览 | **纯 APK 代码改造** | P1 |
| 6 | 导出页读课程表名称列表失败（重点） | APK 解析逻辑与主仓回包**协议一致**；最可能是**手环上的 EV 是旧版（无 `list_schedules`）**，而 APK 失败时**静默无提示** | APK 健壮性 + 运行前提 | **P0** |

---

## 一、首页滚动失败、底部内容看不到

### 1.1 现象

- 首页内容超出一屏时**滚不动**，底部的"包名 / 版本号"、功能按钮看不到。
- 屏幕矮的机型尤其明显。

### 1.2 根因 A：`ScrollView` 子 View 缺高度参数（主因）

`Ui.wrapWithBottomBar()` 里这样把内容挂进 `ScrollView`：

```java
ScrollView scroll = new ScrollView(a);
scroll.setFillViewport(true);
scroll.addView(contentRoot);          // ← 没传 LayoutParams
```

`contentRoot` 是 `Ui.screen()` 产生的 `LinearLayout`。`ScrollView` 继承自 `FrameLayout`，而 `FrameLayout.generateDefaultLayoutParams()` 返回的是 **`MATCH_PARENT × MATCH_PARENT`**。于是：

- 子 View 高度被强制 = 视口高度；
- 再叠加 `setFillViewport(true)`，内容超长部分被**直接裁掉**，`ScrollView` 判定"没得滚" → **滚动失效**。

> 这是 Android 上非常经典的坑：**在代码里往 `ScrollView` 加子 View 而不显式给 `WRAP_CONTENT` 高度，就会滚不动。**

对应位置：

```202:219:apk/src/com/application/watch/classschedule/Ui.java
    public static ViewGroup wrapWithBottomBar(Activity a, LinearLayout contentRoot, int currentTab) {
        int count = contentRoot.getChildCount();
        if (count > 0) {
            contentRoot.removeViewAt(count - 1);
        }

        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.addView(contentRoot);

        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
```

### 1.3 根因 B：底栏悬浮覆盖内容尾部

`root` 是 `FrameLayout`：`ScrollView` 铺满整屏，底栏用 `gravity=BOTTOM` **叠在 `ScrollView` 之上**。因此滚动区最后一段内容会**被底栏压住**（即使滚动修好了也会看不全）。需要给内容区加等于底栏高度的**底部内边距**（或者把内容容器放在底栏之上的 `LinearLayout` 里）。

### 1.4 附带 bug：`wrapWithBottomBar` 误删内容

该方法会强制 `removeViewAt(count - 1)`，约定"最后一个子 View 是底栏"。但实际调用：

- `HomeActivity` 从未 `addView(Ui.bottomBar(...))`，最后一个子 View 是"包名 版本号"那行 mono → **被误删**；
- `ChatActivity` 同理，最后是说明性 mono 文本 → **被误删**；
- `SettingsActivity` 确实先加了 `Ui.bottomBar(...)` 再包装，是唯一"符合约定"的，但那行 `addView` 是死代码。

```136:140:apk/src/com/application/watch/classschedule/HomeActivity.java
        root.addView(Ui.space(this, 8));
        root.addView(Ui.mono(this, "包名 " + getPackageName() + "  ·  v" + version()));

        setContentView(Ui.wrapWithBottomBar(this, root, 0));
    }
```

### 1.5 修复方案

1. 给内容容器显式设定高度参数：
   ```java
   scroll.addView(contentRoot, new FrameLayout.LayoutParams(
           FrameLayout.LayoutParams.MATCH_PARENT,
           FrameLayout.LayoutParams.WRAP_CONTENT));   // ← 关键
   ```
2. 内容区加底部 padding = 底栏高度（如 `dp(a, 56)`），避免被底栏压住。
3. 去掉"删最后一个子 View"的隐式约定：让底栏由 `wrapWithBottomBar` **唯一负责创建**，内容容器只放内容（不再要求调用方先 `addView(bottomBar)`）。
4. `SettingsActivity` 里多余的 `root.addView(Ui.bottomBar(this, 2));` 删掉。

---

## 二、底部三个按钮"任何时候必须常驻"

### 2.1 现状

| 页面 | 是否有底栏 | 说明 |
|---|---|---|
| `HomeActivity` | ✅（由 `wrapWithBottomBar` 生成） | 但误删了版本号行 |
| `ChatActivity` | ✅ | 但误删了说明文本 |
| `SettingsActivity` | ✅ | 有一行死代码 |
| `TransferActivity`（导入/导出） | ❌ **完全没有** | 用户在这里最需要切换 |
| `DebugActivity` | ❌ **完全没有** | — |

所以用户在导入/导出、调试页"看不到三个按钮" —— 这正是"缺少常驻按钮"的直接原因。

### 2.2 需求

**任何时候、任何页面，底部三个按钮都常驻。** 且"聊天"要改名为"留言"。

### 2.3 方案：统一到一个基类，底栏只在一处生成

- 新增 `BaseActivity`（或改造 `Ui`）：`setContentView(Ui.screenWithTabs(this, content, currentTab))`，**底栏由框架生成**，任何继承它的页面天然常驻。
- `TransferActivity` / `DebugActivity` 也改为继承基类并挂底栏（用 `FLAG_ACTIVITY_REORDER_TO_FRONT` 切换，避免回首页重跑连接）。
- 标签建议：**首页 / 留言 / 设置**（`聊天` → `留言`，`ChatActivity` 重命名为 `MessageActivity`，图标/文案同步）。
- 由底栏高度统一喂给内容区的底部 padding（解决 §1.3 遮挡问题）。

---

## 三、聊天 → 留言模式（重点）

### 3.1 产品定位：不做实时 IM

`docs/聊天功能可行性分析.md` 已论证：这条链路**无推送、无保活、无 ACK、经常断开**，在这上面承诺 IM 体验只会持续让用户失望。因此按 **「离线留言 / 消息通道」** 重构：

| | IM（原"聊天"，不推荐） | 留言模式（推荐） |
|---|---|---|
| 用户预期 | 随时可达、秒回 | 发得出去、稍后送达 |
| 断开时 | 觉得坏了 | 正常，显示"待发送/待同步" |
| 交互 | 你一句我一句 | 手机写留言 → 手环收到（震动）→ 手环可回留言 |

### 3.2 关键：**不连接也能留言**

"不连接状态也可以发布留言" 的实现完全在 **APK 本地**：

- 手机写留言时，**先落本地队列**（`SharedPreferences` 或 SQLite），状态标 `pending`；
- **能连上就立即发**，连不上就留在队列里，界面显示"待发送（手环未连接）"；
- 每次连接成功（`HomeActivity` 连上后）或 App 回到前台时，**自动补发所有 `pending` 留言**；
- 收到 EV 的 `chat_ack` 后把该条标记 `sent`。

> 注意：现有 `ChatActivity` 是"发一条就等 6 秒回包，超时就算失败"，**没有持久化**，所以完全做不到离线留言。

### 3.3 下行（手机 → 手环）——EV 侧已就绪

EV 主仓已实现 `chat` 收件：

```187:216:tom/class/class/src/app.ux
// ===== 聊天：收（手机 → 手环）=====
// 消息存进 ev_chat_inbox（聊天页读取），同时长震动提醒，并回送达确认。
function syncHandleChatIncoming(connect, msg) {
  var text = String(msg.text === undefined || msg.text === null ? "" : msg.text)
  var item = { from: "phone", text: text, ts: msg.ts || Date.now() }
  ...
  syncReply(connect, { ok: true, action: "chat_ack", id: msg.id || "", ts: Date.now() })
}
```

即：**"手机留言 → 手环存收件箱 + 长震动"这条下行能力已经存在**，手机端离线排队即可直接复用，EV 侧无需改动。

### 3.4 上行（手环 → 手机）与"连接后获取"

现状：EV 只能在**收到手机消息时**回包；要主动发，需要 `chat-bridge.js` 在 EV 启动时 `register(connect)` 过（见主仓 `src/data/chat-bridge.js`）。也就是说——**若 EV 未被唤醒过，它无法主动推**。

因此"等手环连接后可以获取"应当设计成**手机主动拉取**（这也正是后台监控的技术前提）：

```
手机 → 手环   {"action":"pull_messages","since":<ts>}
手环 → 手机   {"ok":true,"action":"pull_messages",
               "messages":[{"id":"...","from":"watch","text":"...","ts":123}]}
```

- EV 侧读现有的 `ev_chat_inbox`（`src/data/storage-tables.js` 已定义该表），按 `since` 过滤返回；
- 手机端按 `id`/`ts` **去重**，新条目才触发响铃 + 震动；
- 这样即使手环从不主动推，手机每次连接/轮询也能拿到手环端新留言。

> EV 侧新增 `pull_messages` 属于**跨仓改动**，落地前需按约定先取得确认。

### 3.5 ★ 后台监控可行性（用户特别关注的问题）

**问题：手机端能否后台经常监控是否有新留言，收到就响铃 + 震动？**

先说结论：**能，但有边界。** 这条链路是"**手机主动问、手环答**"，没有任何服务端推送，所以"监控"的本质就是**手机自己周期性地去拉**。

| App 状态 | 能否收到新留言并提示 | 说明 |
|---|---|---|
| 前台 | ✅ 完全可以 | 已有 `OnMessageReceivedListener`（Binder 回调，与 UI 无关） |
| 后台**进程存活**（有前台服务） | ✅ **可以** | 需 `FOREGROUND_SERVICE` 常驻通知；Binder 回调仍可触发 → 响铃 + 震动 |
| 后台被系统/ROM 回收 | ❌ **收不到** | 进程没了，没有任何通道能唤醒它 |

**可行方案（按推荐度）：**

1. **前台服务 + 周期轮询（推荐）**
   - 常驻一个 `startForeground()` 服务（一条低优先级常驻通知），保持进程与 `addListener` 存活；
   - 服务内每 **1~5 分钟**（可配置）发一次 `pull_messages`（或 `ping`）探活并拉取；
   - 拉到新留言 → 发 `Notification`（带 `NotificationChannel` 的声音 + 震动）+ `Vibrator` 震动；前台可见时再叠 `AlertDialog`。
   - Android 14（targetSdk 34）注意：需 `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC` 权限，另需 `POST_NOTIFICATIONS`（13+）、`VIBRATE`。
   - 前台服务期间系统不做 Doze 休眠限制，但 **MIUI 等 ROM 仍需用户把本 App 加入"省电白名单 / 自启动"**，否则照样被杀 → 需引导页。
2. **WorkManager 周期任务（保底）**
   - 最小周期 **15 分钟**，且受 Doze 影响会延迟；只适合"低频兜底"，不适合"及时提醒"。
3. **打开 App 即拉取（一定要做）**
   - `onResume` 里触发一次 `pull_messages`，保证"只要用户打开 App，就能看到并提示离线期间的新留言"。
   - 这是**最可靠**的一层，不依赖任何后台保活。

**必须对用户说清楚的话：**
- **"后台常驻 + 轮询"可以做到"进程活着时收到就响铃震动"**；
- **"App 被杀后还能像微信一样被叫醒"做不到** —— 小米运动健康不提供这种推送，链路里也没有推送通道；
- 因此"实时"这个词不要出现在文案里，界面状态应体现"上次同步时间 / 待同步数量"。

### 3.6 落地步骤

1. APK：`ChatActivity` → `MessageActivity`（留言板），消息列表 + 输入框 + "待发送/已送达"状态 + 持久化队列。
2. APK：连接成功 / `onResume` → 自动 `flush()` 补发 pending 留言 + `pull_messages` 拉取。
3. APK（可选）：前台服务 + 周期轮询 + 通知（含声音/震动）。
4. EV：新增 `pull_messages` 接口（读 `ev_chat_inbox`）——**跨仓，需先获授权**。
5. 引导页：省电白名单 / 通知权限 / 自启动设置。

---

## 四、导入课程表：多选课 / 随机示例 JSON / 复制按钮（对标 AstroBox 插件）

### 4.1 现状

`TransferActivity` 导入侧只有"选择 JSON 文件"一个入口（`pickFile()`），解析后弹框确认，直接把**整个 `courses` 数组**发给手环。

- 不能**直接粘贴** JSON；
- 没有**示例/随机 JSON**；
- 没有**复制**按钮；
- 不能**多选**要导入的课；
- 结果反馈只有一行文字，缺少 AstroBox 插件那种"检测格式 + 课程预览列表"的观感。

### 4.2 目标 UX（对标插件 `app-auth/tools/ev-schedule-sync/preview.html`）

插件导入页的做法（值得照搬）：

- 顶部显示**检测到的格式**徽标；
- 文件选择区；
- 目标课程表名称输入；
- 导入模式（创建新表 / 合并到已有）；
- **课程预览列表**：每条课一行，含 `名称 / 周几 + 时间段 / 教室 / 老师`，计数"待导入 N 门课程"；
- 明确的主按钮"导入到 Band"。

### 4.3 新增能力清单

| 能力 | 做法 |
|---|---|
| 粘贴导入 | 加一个多行 `EditText`，可直接粘贴 JSON 文本，点"解析"预览 |
| 随机示例 JSON | 加"生成示例课表"按钮：内置一个随机生成的课表 JSON 填进文本框（可直接导入做链路自测） |
| 复制按钮 | `ClipboardManager` 一键把当前文本框 / 解析结果 JSON 复制到剪贴板，方便去别处编辑 |
| **多选课** | 解析后渲染**可勾选列表**（`CheckBox`，默认全选），只把勾选项组装成 `courses` 发给手环 |
| 课程预览 | 每条课显示 `名称 · 周X · 时间段 · 教室 · 老师`，与插件一致 |
| 结果反馈 | 导入成功后显示"共 N 门课"，失败显示明确原因 |

> 说明：EV 的 `import` **目前是覆盖当前课表**（写前自动备份到 `astrobox_sync_backup`），没有"合并"语义。因此当前 UI 应先做"多选 + 覆盖"，"合并到已有表"需 EV 侧支持，属后续项。

### 4.4 EV `import` 协议与字段要求（对接依据）

APK 发送：

```json
{"action":"import","payload":{"courses":[ ... ]}}
```

EV 侧解析（主仓 `src/app.ux`）：

- `syncExtractCourses` 宽容接受 `[]` / `{courses:[]}` / `{schedules:[{courses:[]}]}` / `{payload:{...}}`；
- 每条课**必须能给到 `day` + `name` + `time`**，否则会被跳过（`syncToFormatA` 里 `if (!day || !name)`、`if (!time) continue`）；
- `day` 支持 `1~7` / `星期一` / `Monday` 等（`syncNormalizeDay`）；
- `time` 支持：
  - `time: "08:00 - 09:40"`（直接给），或
  - `startTime` + `endTime`，或
  - `startPeriod` + `endPeriod`（节次，1~8，按 EV 内置作息表换算）。
- 可选字段：`teacher`、`location`（或 `classroom`/`room`）、`notes`（或 `note`）。

### 4.5 可直接内置的"随机示例 JSON"（格式样板）

```json
{
  "courses": [
    {"name": "高等数学", "day": 1, "time": "08:00 - 09:40", "teacher": "张教授", "location": "A楼101"},
    {"name": "大学英语", "day": 2, "time": "10:05 - 11:40", "teacher": "李教授", "location": "B楼205"},
    {"name": "线性代数", "day": 3, "time": "14:00 - 15:40", "teacher": "王教授", "location": "数学楼301"},
    {"name": "物理实验", "day": 5, "time": "08:00 - 10:30", "teacher": "陈教授", "location": "物理实验室102"}
  ]
}
```

> 这些字段组合与 `toCourseArray()` / `flattenFormatA()` 的现有解析完全兼容，APK 侧无需改转换逻辑即可直接导入。

---

## 五、导出页：课程表名称列表读取失败（重点）

### 5.1 现状代码路径

进入导出页 → `loadSchedules()` 先发 `list_schedules` 拉清单，再让用户选套导出：

```171:221:apk/src/com/application/watch/classschedule/TransferActivity.java
    private void loadSchedules() {
        if (scheduleSpinner == null) {
            return;
        }
        scheduleSpinner.setEnabled(false);
        SyncEngine.get(this).listSchedules(new SyncEngine.Reply() {
            @Override public void onReply(String json) {
                try {
                    JSONObject o = new JSONObject(json);
                    if (!o.optBoolean("ok", false)
                            || !"list_schedules".equals(o.optString("action"))) {
                        return;
                    }
                    JSONArray names = o.optJSONArray("names");
                    ...
```

### 5.2 与主仓协议逐字段核对

| 环节 | 主仓 EV（实际） | APK（期望） | 是否一致 |
|---|---|---|---|
| 请求 | 识别 `parsed.action === "list_schedules"` | 发 `{"action":"list_schedules"}` | ✅ |
| 回包顶层 | `syncReply(connect,{ok:true,action:"list_schedules",names,current})` → 接收端拿到**该 payload 本身** | 读顶层 `ok`/`action`/`names`/`current` | ✅ |
| 数据来源 | `storage.get("scheduleNames")` + `currentScheduleIndex` | — | ✅（键名确实是 `scheduleNames`） |

主仓实现：

```534:558:tom/class/class/src/app.ux
// 课程表清单：返回所有课程表名称 + 当前选中索引（多课程表导出前置）
function syncHandleListSchedules(connect) {
  var storage = require("@system.storage")
  storage.get({
    key: "scheduleNames",
    success: function (raw) {
      var names = []
      try { names = JSON.parse(raw || "[]") } catch (e) { names = [] }
      ...
          syncReply(connect, { ok: true, action: "list_schedules", names: names, current: cur })
```

**结论：APK 侧解析逻辑与主仓回包协议是一致的，代码层面"对接"没错。**

### 5.3 最可能的真因

1. **手环上的 EV 是旧版本（不含 `list_schedules`）** ← 首要怀疑。
   - 主仓代码里 `list_schedules` 是后加的（commit `c1687a8`），本地 `dist/` 里最新产物是 `...1.6.139.rpk`（09-27 11:52 构建）；
   - 但**没有任何记录表明这版 rpk 已装到真机手环上**；基线的真机 EV 是 `1.6.103(932)`。
   - 旧版 EV 收到 `list_schedules` 会走到"无 action → 按 import 处理"，回 `{ok:false,reason:"no courses"}`。
2. **APK 失败静默**：`loadSchedules()` 的 `onReply` 一旦 `ok!=true` 就 `return`；`onTimeout`/`onError` 也是空实现。→ **用户看不到任何报错**，下拉框就是灰的/空的，表现成"读取列表失败"。

```213:220:apk/src/com/application/watch/classschedule/TransferActivity.java
                } catch (Throwable t) {
                    // 老版本 EV 不支持 list_schedules：退回单套模式
                    selectedIndex = -1;
                }
            }
            @Override public void onTimeout(String hint) { /* 静默，单套模式 */ }
            @Override public void onError(String msg) { /* 静默，单套模式 */ }
        });
    }
```

### 5.4 修复：**先读出列表**（用户强调的这一步）

1. **把清单显式渲染出来**：进入导出页自动 `list_schedules`，用**可见的单选列表**（`RadioGroup` 或列表行）展示 `课程表1` / `暑假辅导班课表` …，并标出"当前激活"那套；不再只是一个灰着的 `Spinner`。
2. **失败必须明说**：`ok!=true` / 超时 / 报 `no courses` 时，明确提示
   "手环 EV 版本过低（<1.6.139），请先升级手环上的 EV 课程表"，
   并在页面上给一个"读取当前课表"的**兜底按钮**（走 `{"action":"export"}`，至少能拿到激活套）。
3. **运行前提对齐**：确认手环上安装的是**含 `list_schedules` 的 EV**（用首页/调试页的 `ping` 看 `versionName`，需 ≥ 1.6.139）。
4. 顺手修：`loadSchedules()` 拉到清单后应显式 `selectedIndex=0` 兜底，避免 `names` 为空时下拉框一直灰。

### 5.5 验收步骤

| 步骤 | 期望 |
|---|---|
| 调试页 `ping` | `versionName ≥ 1.6.139`（否则先升级 EV rpk） |
| `adb logcat -s EVProbe` 看 `[SYNC-RX] action=list_schedules` | 手环确实收到并识别该动作 |
| 导出页进入即出现清单 | 能看到 `课程表1`、`暑假辅导班课表` 等多套名称 |
| 选一套 → 读取 | 返回该套数据，界面显示套名 + 每天节数统计 |
| 旧版 EV 场景 | 出现明确"版本过低"提示，而非静默失败 |

---

## 六、改动清单（文件级，供排期）

| 文件 | 改动 |
|---|---|
| `apk/src/.../Ui.java` | `wrapWithBottomBar` 修 `WRAP_CONTENT`；加底部 padding；底栏唯一生成；新增统一 `screenWithTabs` |
| 新增 `apk/src/.../BaseActivity.java` | 所有页面统一挂常驻底栏 |
| `HomeActivity.java` | 去掉重复 `bottomBar`；修正被误删的版本号行 |
| `MessageActivity.java`（原 `ChatActivity`） | 留言板：本地队列、状态、连接后补发 + 拉取 |
| 新增 留言存储 / 前台服务（可选） | `SharedPreferences`/SQLite 队列；`ForegroundService` + 通知渠道 + 震动 |
| `TransferActivity.java` | 导入页：粘贴框 / 随机示例 / 复制 / 多选预览；导出页：可见清单 + 失败提示 + 兜底 |
| `AndroidManifest.xml` | 新增 `POST_NOTIFICATIONS` / `VIBRATE` / `FOREGROUND_SERVICE(_DATA_SYNC)`；注册新 Activity/Service |
| 手环端 `tom/class/class`（**跨仓，需先授权**） | 新增 `pull_messages` 动作（读 `ev_chat_inbox`） |

---

## 七、附：本文核对过的路径

- 本仓（读写目标）：`apk/src/com/application/watch/classschedule/*.java`、`apk/AndroidManifest.xml`、`apk/version.env`、`apk/build.sh`。
- 跨仓**只读**参考：`tom/class/class/src/app.ux`、`src/data/{storage-tables.js,database.js,chat-bridge.js}`、`dist/com.application.watch.classschedule.debug.1.6.139.rpk`；`app-auth/tools/ev-schedule-sync/preview.html`、`app-auth/docs/astrobox-plugin-ev-schedule-sync.md`。

> 相关既有分析：`docs/聊天功能可行性分析.md`、`docs/chat-and-multi-export-match-analysis.md`、`apk/底部导航栏高度适配问题分析.md`。
