# 分析：APK 能否写入手环「日历 / 闹钟」实现定时提醒

> 日期：2026-09-28
> 范围：`ev-schedule-android`（安卓同步器 APK）→ 小米手环（Vela 快应用 EV 课程表）
> 结论先行：**直接写手环系统日历 / 手环系统闹钟：不可行。** 但已有可靠的替代路径（手机侧闹钟 + 推手环通知 / 振动），以及需要改 EV 快应用的手环本地提醒方案。

---

## 一、结论总览

| 方案 | 可行性 | 说明 |
|---|---|---|
| 写手环系统日历（Calendar） | ❌ 不可行 | 无任何 API 通道，手环日历是系统私有应用 |
| 写手环系统闹钟（Alarm） | ❌ 不可行 | 同上；Vela 快应用无对外闹钟写入接口 |
| 手机 AlarmManager + `NotifyApi.sendNotify` 推手环 | ✅ 可行（已跑通） | 闹钟在手机侧，到点推手环系统通知，`Reminders.java` 已实现 |
| interconnect `chat` action 触发手环振动 | ✅ 可行（已跑通） | EV 收到 chat 后 `vibrateLong` + 落收件箱 |
| EV 快应用本地「resident + 定时器 + 振动」提醒 | ⚠️ 可行，需改手环侧 | 不依赖手机存活，通过 `update_settings` 下发提醒配置 |
| 快应用 `system.alarm` / `system.calendar` 特性 | ⚠️ 未验证，不建议 | 历史上声明过但从未调用，已被删除（见下文） |

---

## 二、为什么「写手环日历 / 闹钟」不可行

### 2.1 手机侧 SDK 没有入口

`xms-wearable-lib 1.4` 对外只暴露 5 个 API（`apk/libs/xms-wearable.jar`，能力清单见 `docs/interconnect 打通经验速查.md`）：

- `NodeApi` — 查询已连接手环
- `AuthApi` — 授权
- `MessageApi` — 与**快应用**互发消息（interconnect）
- `ServiceApi` — 服务探针
- `NotifyApi` — 手机→手环**系统通知转发**（单向、无 ack）

SDK 本质是一条「消息 / 通知转发管道」，**不存在任何写手环日历、闹钟的接口**。手环的系统日历和闹钟是 Vela 系统私有应用，三方 APK 无法触碰。

### 2.2 interconnect 通道的硬约束

即使想「借道」快应用转发，interconnect 也必须满足（`docs/interconnect 打通经验速查.md`、`docs/interconnect_image_demo 上手与自研APK对接分析.md`）：

1. APK applicationId == 快应用 `package`（`com.application.watch.classschedule`），且签名一致，否则 `SignatureVerifyFailedException`；
2. 只能与**快应用**通信，不能直达手环系统服务；
3. 链路 100% 依赖「小米运动健康」保持蓝牙连接。

所以 APK 最多能「请求 EV 快应用做某事」，而不能直接操作手环系统。

### 2.3 手环侧（EV 快应用）也没有系统日历/闹钟能力

对 EV 源码仓（`tom/class/class`，只读检查）的结论：

- `src/manifest.json` 声明的 features：`system.router / storage / prompt / device / vibrator / request / app / resident / interconnect / clipboard / file`。**没有 `system.alarm`、没有 `system.calendar`**。
- 全源码无任何系统日历、系统闹钟、系统通知的 API 调用；系统能力止步于 `vibrator`（振动）、`resident`（后台常驻保活 interconnect）、页面级定时器。
- **历史线索**：旧版 manifest 曾声明过 `system.alarm` / `system.calendar`，但 QA 报告（`class-schedule-QA-20260912-1853.md`）明确记录「未在代码中 require，属冗余声明」，随后被提交 `0ae8ed1` 删除。即这两个 feature 名在 Vela 声明空间里存在过，但**从未被实际调用验证**。
- 诚实声明：本地资料无法 100% 证明这两个特性在小米手环 Vela 上运行时是否真的可用。若强行验证，唯一办法是恢复声明 + require 试错，但风险（手环审核/稳定性）与收益不成比例，**不建议走这条路**。

### 2.4 APK 侧现状（避免混淆）

APK 已有 `apk/src/com/application/watch/classschedule/Reminders.java`：用手机本地 `AlarmManager.setExactAndAllowWhileIdle` 排「下一节课 − 提前量」的闹钟，到点发手机系统通知 + 可选推手环。这是**手机侧**闹钟，与手环系统闹钟无关。APK 内没有 CalendarContract 写入代码。

---

## 三、可行的「某时刻手环提醒」方案

### 方案 1：手机闹钟 + `NotifyApi.sendNotify`（推荐，零改动已有）

- 流程：APK `AlarmManager` 到点 → `SyncEngine.notifyWatch()` → 手环收到系统通知（震动 + 亮屏）。
- 已有限制（`docs/watch-notify-downlink.md`）：best-effort 无 ack、正文截断、需手环授予通知权限、运动健康保持连接。
- 优点：**不改手环侧任何代码**，当前架构直接可用。
- 缺点：依赖手机存活 + 蓝牙链路；无法保证必达。

### 方案 2：interconnect `chat` 触发振动

- 到点时手机发 `{"action":"chat", id, text, ts}`，EV 侧 `syncHandleChatIncoming` 落收件箱并 `vibrateLong`（800ms×2）。
- 比方案 1 多了：**强振动** + EV 内持久收件箱（可在手环上回看）。
- 缺点：要求 EV 常驻（app.ux 已自动 `startResident()` 保活，风险可控）；同样依赖手机在线。

### 方案 3：EV 本地提醒（最可靠，需改手环侧）

- 思路：APK 通过 `update_settings` 扩展一个新 scope 下发「提醒配置」（事件时间、提前量、开关）→ EV 用 `@system.resident` + `setInterval` + `@system.vibrator` 在手环本地到点自震。
- 优点：**不依赖手机存活**，手环独立完成提醒；符合「课表唯一真源在手环」的定位。
- 缺点：需要改 EV 快应用（跨仓，须用户同意）；Vela 后台定时器精度与常驻策略需实测。历史上 `tom/class/class/docs/方案B-详细实施计划.md`、`background-running-dev-plan.md` 设计过该功能（后产品下线），技术路径已被验证。

### 不推荐：`system.alarm` / `system.calendar` 特性试错

理由见 2.3：从未验证、已被删除、收益低。

---

## 四、建议

1. **短期**：沿用方案 1（现有 `Reminders.java` + `notifyWatch`），把「定时提醒」作为手机闹钟驱动、手环通知呈现的体验交付。
2. **中期**：如需「消息在 EV 内可见 + 强振动」，复用 `chat` 通道（方案 2），只需 APK 侧加一个提醒触发器。
3. **长期**：若「不依赖手机的可靠提醒」成为硬需求，再立项方案 3（EV 侧本地提醒 + `SYNC_ACCESS` 新增 `reminders` scope），走跨仓评审。
4. **明确放弃**：手环系统日历 / 闹钟写入方向。

---

## 附：关键事实来源

| 事实 | 出处 |
|---|---|
| SDK 仅 5 个 API、无日历/闹钟接口 | `apk/libs/xms-wearable.jar`；`docs/interconnect 打通经验速查.md` |
| interconnect 包名+签名硬约束 | `docs/interconnect_image_demo 上手与自研APK对接分析.md` |
| NotifyApi 单向无 ack、正文截断 | `docs/watch-notify-downlink.md` |
| EV manifest 无 alarm/calendar feature | `tom/class/class/src/manifest.json` |
| 历史 feature 声明未用且已删 | `tom/class/class/docs/qa-reports/class-schedule-QA-20260912-1853.md`；提交 `0ae8ed1` |
| chat 收到即长振动 + 收件箱 | `tom/class/class/src/app.ux`（`syncHandleChatIncoming`） |
| APK 手机侧闹钟已有实现 | `apk/src/com/application/watch/classschedule/Reminders.java` |
