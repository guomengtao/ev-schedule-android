# 聊天 & 多课程表导出：与「主项目（EV 手环端）」匹配度分析

> 主项目 = `tom/class/class`（Vela 快应用，手环端 EV 课程表）。
> 我们 = `ev-schedule-android` 的安卓同步器 APK（通过 interconnect 与 EV 通信）。
> 分析依据：主项目 `src/app.ux`（消息分发器）+ `src/data/database.js` + `src/data/storage-tables.js`；我们 APK 的 `TransferActivity.java` + `ChatActivity.java` + `SyncEngine.java`。

## 〇、结论速览

| 功能 | 是否匹配主项目 | 说明 |
|---|---|---|
| 聊天（手机↔手环互发） | ✅ **已匹配** | EV 已实现 `chat` 收 + `chat_ack` 回；APK 协议一致。仅 APK 内注释过时。 |
| 多课程表导出（列全部→选一套→导出） | ❌ **未匹配** | 主项目内部有多套，但导出协议只返回**当前激活那一套**，不列全部、不支持选套；APK 也无选择器。 |

---

## 一、主项目真实能力梳理

### 1.1 多课程表数据模型（确实存在多套）

`src/data/storage-tables.js`：
- `allCourses`：描述写的是"所有课程数据（多课程表存储）"，列 `scheduleName/courses/createdAt/updatedAt`（`storage-tables.js:3-7`）。
- `currentScheduleIndex`：当前选中的课程表索引（`storage-tables.js:9-13`）。
- `scheduleNames`：所有课程表名称列表（`storage-tables.js:15-19`）。
- 存储实现：`database.js` 用 `allCourses_<index>` 逐套存（`database.js:175`），`getAllCoursesWithIndex(index)` 可取任意一套（`database.js:753-761`），`scheduleNames` 是名字数组。

→ **结论：主项目在数据层完整支持多套课程表**。

### 1.2 导出协议现状（只导「激活套」）

消息分发（`app.ux:630-637`）：

```630:637:tom/class/class/src/app.ux
    if (parsed.action === "export") {
      // scopes 支持数组；也兼容单字符串 scope
      var scopes = parsed.scopes
      if (!scopes && parsed.scope) {
        scopes = [parsed.scope]
      }
      syncHandleExport(connect, scopes)
      return
    }
```

`export` 的 `schedule` 域收集逻辑（`app.ux:439-444`）：

```439:444:tom/class/class/src/app.ux
  if (syncWantScope(list, "schedule")) {
    pending++
    database.getAllCourses(function (courses) {
      result.schedule = courses || []
      finish()
    })
  }
```

而 `database.getAllCourses()`（`database.js:663-669`）底层走 `getAllCoursesStorage(currentScheduleIndex)` —— **只取当前激活那一套**，且形状是「格式 A」：`[{day:"星期一", classes:[{name,time,...}]}, ...]`（按天分组）。

→ **结论：导出回包 `data.schedule` = 激活套、按天分组；不携带 scheduleName，也不提供多套列表/选套能力。**

### 1.3 聊天协议现状（已实现，且匹配 APK）

分发器（`app.ux:643-647`）已处理 `chat`：

```643:647:tom/class/class/src/app.ux
    if (parsed.action === "chat") {
      // 手机 → 手环：聊天消息。存收件箱 + 长震动，并回送达确认
      syncHandleChatIncoming(connect, parsed)
      return
    }
```

`app.ux:188-215` 实现：存 `ev_chat_inbox` + 长震动 + 回 ACK：

```214:214:tom/class/class/src/app.ux
  syncReply(connect, { ok: true, action: "chat_ack", id: msg.id || "", ts: Date.now() })
```

手环主动发消息走 `src/data/chat-bridge.js`（`chatBridge.send({action:"chat",...})`）—— 双向都通。

→ **结论：EV 的 chat 协议 = 手机发 `{action:"chat",id,text,ts}`；回 `{action:"chat_ack",id,ts}`；手环主动发 `{action:"chat",...}`。与 APK 完全一致。**

---

## 二、我们 APK 现状

### 2.1 导出（TransferActivity）

`readFromBand()`（`TransferActivity.java:99-139`）发 `{"action":"export"}`，回包按 `data.schedule` 逐"天"统计节数（`TransferActivity.java:114-126`）：

```114:126:apk/src/com/application/watch/classschedule/TransferActivity.java
                        JSONArray sch = d.optJSONArray("schedule");
                        int total = 0;
                        if (sch != null) {
                            for (int i = 0; i < sch.length(); i++) {
                                JSONObject day = sch.optJSONObject(i);
                                JSONArray cs = (day == null) ? null : day.optJSONArray("classes");
                                int n = (cs == null) ? 0 : cs.length();
                                total += n;
                                sb.append("  ").append(day == null ? "?" : day.optString("day"))
                                  .append(" ").append(n).append(" 节\n");
                            }
                        }
```

→ 解析假设正确（主项目确实回格式 A），但**只导激活套、无多套列表、无选套 UI、保存文件名不区分哪套**。

### 2.2 聊天（ChatActivity）

发消息（`ChatActivity.java:126-135`）构造 `{action:"chat",id,text,ts}`；`handleReply` 识别 `chat_ack`（`ChatActivity.java:165-170`）显示"已送达"，识别手环主动 `chat`，并对 `no courses` 做了降级提示（`ChatActivity.java:173-179`）。

⚠️ **过时注释**：`ChatActivity.java:28-38` 写明"手环上的 EV 目前【没有】chat 分支……回包固定是 no courses"，这与主项目现状（`app.ux:188-215` 已实现）**矛盾，是旧版本遗留注释，应更正**。

---

## 三、逐项差异表

| 维度 | 主项目（EV） | 我们 APK | 是否匹配 |
|---|---|---|---|
| chat 收消息 | `syncHandleChatIncoming` 存收件箱+长震+回 `chat_ack` | 发 `chat`，识别 `chat_ack`/`chat` | ✅ 匹配 |
| chat 主动发 | `chat-bridge.js` 可发 | 能接收 `chat` 弹窗+提示音 | ✅ 匹配 |
| 导出-单套 | `getAllCourses`（激活套，格式 A） | 读 `data.schedule` 按天统计 | ✅ 形状匹配 |
| 导出-列全部多套 | ❌ 协议未暴露 `scheduleNames`/列表 | ❌ 无列表 UI | ❌ 均未实现 |
| 导出-选某套导出 | ❌ `export` 不支持 `scheduleIndex` 参数 | ❌ 无选套入参 | ❌ 均未实现 |

---

## 四、多课程表导出：目标流程与落地方案

### 4.1 目标交互（用户诉求）

> 导出栏目先显示用户**所有多套课程**（如 课程表1 / 课程表2 / 考研表 …），用户**选中某一套**，再对该套执行导出。

### 4.2 协议改动（主项目 `app.ux` + `database.js`）

1. **新增 `list_schedules` 动作**：
   ```
   手机→手环  {"action":"list_schedules"}
   手环→手机  {"ok":true,"action":"list_schedules","names":["课程表1","课程表2"],"current":0}
   ```
   实现：`storage.get({key:"scheduleNames"})` + `currentScheduleIndex` 直接返回（无需逐套读）。
2. **`export` 支持选套**：增加可选字段 `scheduleIndex`（或 `scheduleName`）：
   - 有 `scheduleIndex` 时，`syncCollectScopes` 的 schedule 域改调 `database.getAllCoursesWithIndex(index)`（已存在，`database.js:753-761`），回该套格式 A；
   - 无该字段时保持现状（导激活套），向后兼容。

### 4.3 APK 改动（TransferActivity）

1. 进入导出页先 `{"action":"list_schedules"}` 拉名字列表，渲染成单选列表（RadioGroup / Spinner）。
2. 用户选定后，`{"action":"export","scheduleIndex":N}` 拉数据；标题/保存文件名带套名（如 `ev-export-课程表2-...json`）。
3. 解析逻辑不变（仍是格式 A 按天分组）。

### 4.4 工作量估计

- 主项目：约 20 行（list 动作 + export 分支）；`getAllCoursesWithIndex` 已现成。
- APK：约 40~60 行（列表 UI + 选套入参 + 文件名带套名）。
- 无需改数据模型，纯协议 + UI。

---

## 五、聊天：结论与待办

- **结论：已匹配**，无需为"能不能通"做改动。
- **待办（清理类，非功能缺陷）**：
  1. 更正 `ChatActivity.java:28-38` 的过时注释，改为"EV 已实现 chat，回 `chat_ack`"。
  2. `ChatActivity.java:173-179` 的 `no courses` 降级分支可保留作兜底（防止连到旧版 EV），但提示文案应改为"手环 EV 版本过旧，请升级"。

---

## 六、注意

- 导出回包 `data.schedule` 是**按天分组**的格式 A，不是「一条课一对象」。我们 APK 的导入侧 `toCourseArray`/`flattenFormatA` 已做摊平处理（`TransferActivity.java:316-386`），与导出侧形状无关，保持即可。
- 多套导出的"选套"参数建议用 `scheduleIndex`（整数，对应 `allCourses_<index>`），比 `scheduleName` 字符串更稳（避免重名/编码问题）。
- 改动主项目属跨仓写操作，落地前需按约定先取得确认（`tom/class/class` 不在本仓工作区）。
