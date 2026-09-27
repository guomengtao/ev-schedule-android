# EvBox 工具箱 与 EV 课程表：能否共用一个安卓 APK？

> 问题：EvBox 工具箱（手环快应用）要不要跟 EV 课程表**共用同一个安卓 APK**（一套代码处理导入导出、设置等）？
> 日期：2026-09-27
> 结论先行：**「同一个 APK 同时服务两个快应用」在 interconnect 上做不到；但「同一套 APK 源码出两个包名变体」完全可行，且推荐这样做。**

---

## 〇、一句话结论

| 问题 | 结论 |
|---|---|
| 一个 APK（一个包名）能同时连 EV 课程表 + EvBox 工具箱吗？ | ❌ **不能**，包名配对是硬约束 |
| 一套代码能同时服务两边吗？ | ✅ **能**，构建两个包名变体即可 |
| 分开做两个 App 更简单稳定吗？ | 分开**更简单**，共用**更省维护**；本项目形态下**推荐共用源码 + 两个变体** |
| 想"手机只装一个 App 管多个快应用"呢？ | 只有 **AstroBox 插件**路线（走宿主 WIT，不受包名约束） |

---

## 一、硬约束（先看这条，它决定了一切）

interconnect 的配对规则：**三方 APK 的 `applicationId` 必须与快应用 `manifest.json` 的 `package` 完全一致**（并且签名一致）。

本项目实测/文档确认（`docs/interconnect 打通经验速查.md`）：

```47:48:ev-schedule-android/docs/interconnect 打通经验速查.md
| 1 | **包名一致**：APK `applicationId` == 快应用 `manifest.json.package` | 固定为 `com.application.watch.classschedule`。**包名绝不能带版本号** | 连不上；且系统会当成另一个 App，桌面多图标 |
| 2 | **签名一致**：APK 的签名证书 == rpk 的签名证书 | 用 `tom/class/class/sign/{private.pem,certificate.pem}` 签 APK（build.sh 自动找） | `SignatureVerifyFailedException: fingerprint verify failed`，**所有设备侧接口全部失败** |
```

再看 SDK 的发送 API：

```java
Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])   // 只有 nodeId + 字节流
```

**不接受"目标快应用包名"参数** —— 也就是说，路由是**隐式按包名匹配**的，一个 APK 只能对上"同名"的那一个快应用。

### 两边的关键事实对照

| 项 | EV 课程表 | EvBox 工具箱 |
|---|---|---|
| 快应用包名 | `com.application.watch.classschedule` | **`com.application.watch.evbox`** ← 不同 |
| 签名证书 SHA-256 | `466a1e83…9a11` | **`466a1e83…9a11`** ← 同一把 ✅ |
| 当前 APK | `ev-schedule-android`（包名 = classschedule） | ❌ 还没有 APK |

→ **同一个 APK 不可能服务两个快应用**：包名只能填一个。若强行改包名去连 EvBox，EV 课程表那边立刻断（且包名一改，签名校验虽然通过，但配对对象变成 EvBox）。

> 补充：APK 包名不是"随便起的标识"，它同时是**配对键**。所以"一个 App 两个手环应用"这条路在 interconnect 上不存在。

---

## 二、EvBox 工具箱现在到什么程度了

仓库：`EvBox/evbox`（包名 `com.application.watch.evbox`，v1.0.17/18，`minPlatformVersion 1050`）

已经**自己做了一套 interconnect 通道**（`src/data/sync-channel.js`，334 行，注释写明"移植自 reference/class-schedule/src/app.ux"）：

| 能力 | 实现 |
|---|---|
| 开关 | `sync_channel_enabled`，**默认关闭**（零耗电）；开启后才 `@system.resident` + `interconnect.instance()` |
| 动作 | `ping` / `export` / `update_settings` / **`chat_pull`** / **`chat_push`** / 无 action→import |
| 访问范围 | `profile` / **`countdown`** / `homepage` / `chat` / `version` / `auth`（`never`）—— **没有 `schedule`**，多了 `countdown`（倒数日） |
| 留言数据 | `sync_chat_list`：`[{id, from:"phone"|"device", text, ts, read}]`，上限 200，**明确定位为异步留言**（push 推入 / pull 取走） |
| 页面 | `sync-chat`「同步器留言」、`homepage-settings`、`backup-restore`、`activation`、`donate`、`settings` 等 |

**两个重要观察：**

1. EvBox 的留言模型比 EV 课程表**更完整**：它已经有 `id` 和 `read`，而且**有 `chat_pull`（拉取）** —— 正好补上了我们之前发现 EV 课程表缺的"出站箱"。
2. EvBox 的通道注释写的是"与同步器（**AstroBox 插件**）"，说明它最初是面向**插件**路线设计的（插件走宿主通道，**不受包名约束**）。

---

## 三、四种方案对比

| 方案 | 可行性 | 稳定性 | 维护成本 | 用户侧 | 说明 |
|---|---|---|---|---|---|
| **A. 两个独立 APK（两套代码）** | ✅ | 高 | **高**（复制两份代码，改一处要改两处） | 装 2 个 App | 最省心、最不容易互相踩 |
| **B. 一套源码 + 两个包名变体**（推荐） | ✅ | 高 | **低**（单份代码，只做配置差异化） | 装 2 个 App（可共存） | 无 Gradle，`build.sh` 注入包名即可 |
| **C. 一个 APK 同时连两个快应用** | ❌ | — | — | — | 违反包名硬约束，**不可行** |
| **D. 换通道：AstroBox 插件 / BLE 直连** | ✅ | 中 | 中高 | 需装 AstroBox | 唯一能"一个手机入口管多个快应用"的形态 |

**C 为什么不行？** 不是"难"，而是**协议层没有这个入口**：`sendMessage` 不带目标包名，配对由"APK 包名 == 快应用包名"隐式决定。想让一个 APK 服务两个快应用，除非两个快应用用**同一个包名**（那就是同一个应用了）。

---

## 四、推荐：方案 B（一套源码，两个包名变体）

### 4.1 为什么适合这个项目

1. **两边签名是同一把** → 两个变体共用同一个 keystore，互不冲突（不同包名可共存）。
2. **技术上高度同构**：APK 侧真正"值钱"的部分（`SyncEngine` 传输 4 步、`Net`、`Notifications`、`SyncService`、`Ui`、`Analytics`、去重逻辑）**与包名无关**，可以直接共用。
3. **构建无 Gradle**，`apk/build.sh` 本来就是 `sed` 注入 label 的方式：

```80:81:ev-schedule-android/apk/build.sh
sed "s/android:label=\"EV Sync\"/android:label=\"EV Sync v$VERSION_NAME\"/" \
  AndroidManifest.xml > out/AndroidManifest.xml
```

   同一个位置再多注入 `package`，就得到第二个变体 —— **改动量极小**。

### 4.2 必须按变体差异化的清单

| # | 差异项 | EV 课程表 | EvBox 工具箱 |
|---|---|---|---|
| 1 | **applicationId（配对键）** | `com.application.watch.classschedule` | `com.application.watch.evbox` |
| 2 | label / 图标 | EV Sync | EvBox Sync |
| 3 | `launchWearApp(nodeId, PKG)` 的 PKG | classschedule | evbox |
| 4 | **可用数据域** | schedule / profile / homepage / appearance / pinned / version | profile / **countdown** / homepage / chat / version |
| 5 | **动作集合** | `ping`/`export`/`import`/`update_settings`/`list_schedules`/`get_device_id`/`activate`/`chat` | `ping`/`export`/`import`(设置)/`update_settings`/**`chat_pull`**/**`chat_push`** |
| 6 | 业务页 | 导入/导出**课程表**、留言、首页设置、激活 | 导入/导出**倒数日/设置**、留言、首页设置、激活 |
| 7 | 版本线 | `version.env` | **另一条独立版本线**（别共用，否则互相顶版本） |
| 8 | 日志 TAG | `EVProbe` | `EvBoxProbe` |

> 建议：把第 4/5 项做成**配置对象**（如 `Profile.java`：`APP_ID / PEER_PKG / SCOPES / ACTIONS / TABS`），UI 按"可用域"动态渲染 —— 这样 EvBox 变体里不会出现"导入课程表"这种无意义入口。

### 4.3 落地步骤（渐进，每步可验收）

| 阶段 | 做什么 | 状态 |
|---|---|---|
| **P0** | 变体机制：`build.sh` 支持 `APP_VARIANT=ev\|evbox`，注入包名 / label / 对端包名（manifest meta-data）；两个变体各自独立版本文件 | ✅ **已完成**（见 §4.4） |
| P1 | 源码**物理分层**：`src/common/`（共用基础设施）+ `src/ev/` + `src/evbox/`（专属页面）；EvBox 侧把「留言」接到它自己的 `chat_pull`/`chat_push`，「设置」按它的域（`profile`/`countdown`/`homepage`）重做 | 待做 |
| P2 | 各自治业务页：EvBox 做倒数日导入导出；EV 保持课表导入导出 | 待做 |
| P3 | 激活体系复用（`productId` 区分产品线） | 待做 |

### 4.4 已实现的变体机制（P0）

```
# EV 课程表（包名 com.application.watch.classschedule）
cd apk && bash build.sh

# EvBox 工具箱（包名 com.application.watch.evbox）
cd apk && APP_VARIANT=evbox bash build.sh
```

| 机制 | 实现 |
|---|---|
| **包名注入** | `build.sh` 用 sed 把清单 `package=` 替换为变体包名，并**自检**（替换失败即报错退出）—— 防止"两个变体其实是同一个包名"的静默错误 |
| **对端包名** | 写进清单 `<meta-data ev.peer_pkg>`；`Variant.java` 运行期读取 → `SyncEngine.launchEv()` 不再硬编码 |
| **R 资源** | aapt2 加 `--custom-package com.application.watch.classschedule`，R.java 固定生成在同一 Java 包 → 两个变体的 `R.drawable.*` 都能编译（否则变体包名一变 R 就找不到） |
| **版本线** | `version.env`（ev）/ `version-evbox.env`（evbox），互不顶版本 |
| **产物** | `dist/EVSyncProbe-v<v>.apk` / `dist/EvBoxSyncProbe-v<v>.apk` + 根目录同名副本（已加 `.gitignore`） |
| **变体感知** | `Variant.isEv()`：EV 专属入口（导入/导出课程表、高级版一键激活）在 EvBox 变体里**自动隐藏** |

实测产物（2026-09-27）：

| 变体 | 包名 | label | 产物 |
|---|---|---|---|
| ev | `com.application.watch.classschedule` | EV Sync v0.5.20 | `dist/EVSyncProbe-v0.5.20.apk` |
| evbox | `com.application.watch.evbox` | EvBox Sync v0.1.0 | `dist/EvBoxSyncProbe-v0.1.0.apk` |

> **为什么 P0 没有立刻做"物理分层"**：目前还没有任何 EvBox 专属页面 —— 现在拆目录只是无意义的文件搬动。等 P1 出现专属页面时再拆，才是有信息量的分层。

### 4.4 风险与注意

1. **不要把 EV 专属逻辑带进 EvBox 变体**：EvBox 没有 `schedule` 域，误发 `import` 课表会回 `no known field`。
2. **版本线必须分开**（两个 `version.env`），否则一边构建把另一边版本号顶掉。
3. `minPlatformVersion` 不同（EvBox 1050），手环侧兼容性要各自确认。
4. 两个 App 会**同时出现在桌面**，用户可能困惑 → label 要能一眼区分（如「EV Sync」/「EvBox Sync」）。
5. 别忘 EvBox 通道**默认关闭**：APK 侧连接前要能识别"对端未开启同步通道"（`export` 无响应），并给出可执行指引。

---

## 五、什么时候应该改选"方案 A：两个独立 App"

出现以下任一情况，就别硬共用：

- 两边的**协议/页面差异超过 70%**（那共用只剩个壳，抽象成本大于收益）；
- 两边**发布节奏差别极大**（一个天天改、一个一年不动）；
- 需要**权限/仓库隔离**（不同人维护、不想互相影响）；
- 需要**完全不同的技术栈**（比如一个要接 BLE 直连、一个纯消息）。

当前判断：**差异集中在"数据域 + 少数页面"，传输与基础设施完全同构 → 方案 B 更划算。**

---

## 六、如果目标是"手机只装一个 App 管多个快应用"

那只有一个方向：**换通道**。

- **AstroBox 插件**（我们已有 `app-auth/tools/ev-schedule-sync`）：插件走宿主 WIT 通道，**不受包名约束**，天然可以服务多个快应用 → 这是"一个入口管多个"的**唯一现实形态**；
- 代价：用户必须安装 AstroBox 宿主；且插件侧 UI 能力弱于原生 APK。
- 所以合理分工是：**APK 负责"一个快应用"的深度整合（保活提醒、激活、导入导出），插件负责"跨快应用"的通用迁移**。

---

## 七、本文核对过的路径

- 本仓：`apk/build.sh`（label 注入）、`docs/interconnect 打通经验速查.md`、`docs/ui-message-import-export-improvements.md`、`docs/message-background-alert-review.md`。
- 跨仓**只读**：`EvBox/evbox/src/manifest.json`（包名）、`src/app.ux`（同步通道开关）、`src/data/sync-channel.js`（协议/域）、`src/data/sync-chat.js`（留言模型）、`sign/certificate.pem`（指纹）、`README.md`、`doc/EvBox方案设计.md`；`tom/class/class/sign/certificate.pem`（指纹对照）。
