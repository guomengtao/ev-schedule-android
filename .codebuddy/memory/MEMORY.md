# 项目长期记忆：EV 课程表 / 安卓同步器

## 四仓
| 仓 | 内容 | 环境 |
|---|---|---|
| `guomengtao/class-schedule` | 手环 EV 课程表快应用 | Vela，包名 `com.application.watch.classschedule` |
| `guomengtao/app-auth` | 后台 + EvNotifier + AstroBox 插件 | Vercel/macOS |
| `guomengtao/ev-schedule-android`（本仓） | 安卓同步器 APK | 无 Gradle 构建脚本 |
| `guomengtao/EvBox/evbox`（本地） | 手环 EvBox 工具箱快应用 | Vela，包名 `com.application.watch.evbox`，**与 EV 同签名** |

- 本地目录：`app-auth`、`tom/class/class`（EV 活跃副本，别改错）、`EvBox/evbox`。
- ★ interconnect **一个包名只能对一个快应用**（配对键 = APK applicationId == 快应用 package；SDK `sendMessage` 不带目标包）。EV 与 EvBox 包名不同 → **两个 APK**。
- 全局地图 `~/PROJECT-MAP.md`。

## interconnect 硬约束
- 快应用 + 三方 APK 必须 **包名一致 + 签名一致**；EV rpk 用同一把 keystore。
- APK 走 `xms-wearable-lib_1.4_release.aar`：`getConnectedNodes()`→`requestPermission`→`addListener`→`sendMessage`；硬依赖手机「小米运动健康」（AAR queries `com.xiaomi.wearable`/`com.mi.health`）。
- 调用顺序：查设备+授权 → 注册监听 → 发消息（不注册监听必 0 回包）。
- EV 接口：`ping`→`{pong,versionName,versionCode}`；`export`→格式 A（按天分组）；`import` 只认「一条课一个对象」；`list_schedules`/`export?scheduleIndex`；`get_device_id`；`activate`。
- AstroBox 插件走 WIT 旁路，不受包名约束。
- 签名不一致报 `SignatureVerifyFailedException`；`getServiceApiLevel`/`getConnectedNodes` 不受影响。

## App 结构（安卓同步器）
- Activity：Home(首页)、Debug(调试)、Settings(设置)、Transfer(导入/导出)、Message(留言)、FastActivate(高级版一键激活)、HomepageSettings(首页设置)、Donate(打赏)、BatteryGuide(省电引导)。
- `SyncEngine` 单例：4 步 + 单一 listener + 6s 超时；多手环 `NodeChooser`/`preferredNode`。
- `Ui.java` 统一深空蓝视觉；`Ui.wrapWithBottomBar`/`fixedWithBottomBar` 是**唯一**底栏创建入口（调用方别再 addView 底栏）。底栏 3 tab = 🏠首页/💬留言/⚙设置（`Ui.bottomBar`，2026-09-27 改成图标+文字竖向 tab，emoji 零资源依赖）。tab=-1 不高亮（调试页）。两者预留 `BAR_HEIGHT_DP`(62dp) 底部留白。
- 所有 9 个 Activity 都挂底栏（含 Transfer/Debug）。
- 工具类：`Net`(POST JSON)、`Analytics`(页面访问 → `?section=visitor-track`)。

## APK 产品定位（2026-09-27 提出 / 2026-09-28 修订）
- 桌面名：**「Ev课程表」**（`build.sh` `LABEL_BASE`），且**不再拼版本号**（版本号在 App 内首页显示）。EvBox 变体仍 `EvBox Sync`。
- ⚠️ 改名前提：首屏必须给课表内容，否则名不副实、期望落差大。分析文档：`docs/apk-positioning-sync-vs-course-table.md`。
- ★ 核心判据（回答"会不会乱"，2026-09-28 与用户达成共识）：**不要把冲突恐惧变成产品障碍**。多源写入是已解问题（Git / 云文档），我们的数据形态与用法风险很低，不必过度设计。
  - **正确类比是 Git，不是腾讯文档**：我们是两台**离线**设备偶发蓝牙重连；云文档假设在线 + 实时 OT/CRDT 流式会话。对应做法 = 重连时**三方合并**（本地保留 last-synced 快照作为 merge base）。
  - **数据是离散记录列表**（一门门课），远好合并于连续文本：改动不同课程可自动合，真冲突（同一门课同一字段两边都改）极少。
  - **最低成本安全网 = 覆盖前显示差异摘要 + 用户确认**（本次将新增 N / 修改 N / 删除 N 门），几乎零成本，消灭全部"静默丢失"；配合 EV 侧已有的自动备份。**v1 做到这层即可**（用户立场："明确提醒即可"）。
  - 有余力再做 record 级自动合并（需**课程稳定 id**；若无 id 可用 name+day+time 指纹，代价是改名被识别为"删+增"）。
  - 唯一保留的硬底线：**不要后台自动同步**（这不是冲突问题，是用户预期问题——自动覆盖会让人措手不及）。
- 09-28 修订方向（讨论中，未最终拍板）：倾向「本地缓存副本」先行，而非立刻把手机变成真源。
  - **阶段 0（推荐先做，不定 SSOT）**：连上拉一次课表 → **本地只读缓存**（标更新时间）→ 首屏渲染 + 桌面插件 + 上课提醒。此阶段手机端不可写 → 不存在双源。
  - **阶段 1**：可视化编辑 → 需选定 SSOT，推荐「手机本地为真源，手环为受控副本」，配套：存量用户冷启动「从手环拉一次初始化」+ 推送前 hash 比对 + 显式反向刷新。
  - **阶段 2（可选）**：多套课表（一期手机只管当前激活套，避免复杂度平方级上涨）。
- 为什么要落本地：**可靠的上课提醒必须本地有数据 + 系统闹钟**。依赖实时连手环必挂——已论证前台服务会被 ROM 省电策略杀、被杀期间无通道唤醒、轮询最小 15 分钟且受 Doze 延迟（`docs/message-background-alert-review.md`）。即"本地持久化是提醒功能的技术前提"，而非为了做第二个产品。
- 「没有手环的用户」：**战略上放弃（不为其设计/获客），产品上不设限（做了本地数据后边际成本≈0）**。守住一条线：**不为通用课程表才有的东西写代码**（单双周/教学周/调休/考试安排）——那是无底洞且手环显示不了。
- 加分项复用：上课提醒到点可同时调 `notifyWatch`（sendNotify）推手环 → 双端提醒、不依赖 EV 常驻。
- ⚠️ 成本约束：项目**零 XML 布局**（`apk/res/` 只有 drawable），UI 全为手写 View。周视图网格需纯代码画；**AppWidget 强制需要 XML**（RemoteViews + AppWidgetProvider + receiver + `res/xml` 元数据）= 首次引入 XML 资源。
- ⚠️ **待用户确认的分叉点**：手环 EV 端能否自行增删改课程？能 → 双写真实存在，hash 比对+覆盖提示是必需品；不能（只能靠导入）→ 手机为真源近乎零成本。

## 构建（apk/，无 Gradle）
- 链：`aapt2 link → javac(JDK8) → d8(JDK11+) → zipalign → apksigner`。SDK `$HOME/android-sdk`。
- 多变体：`bash build.sh`（EV，`com.application.watch.classschedule`，`version.env`，`EVSyncProbe-v*.apk`）；`APP_VARIANT=evbox bash build.sh`（EvBox，`version-evbox.env`，`EvBoxSyncProbe-v*.apk`）。同签名（先找 `tom/class/class/sign`）。
- ⚠️ aapt2 必须 `--custom-package com.application.watch.classschedule`（变体改包名时 R.java 包名跟着变）。
- ⚠️ 清单 sed 注入后有自检（占位符没替换干净即报错退出）。
- ⚠️ `Variant.java` 运行期读 meta-data，别硬编码对端包名。
- ⚠️ javac 用 JDK8（JDK22 的 class 让 d8 NPE）；d8 的 `--classpath` 不打进 dex，AAR/三方 jar 必须作为输入传给 d8。
- ⚠️ 2026-09-27 起 `apk/libs/zxing-core.jar`（打赏页二维码用，build.sh 按需从 Maven 下载；gitignore 排除）。

## 仓库 / 推码 / 用户规则
- GitHub `guomengtao/ev-schedule-android`（private），**推送必须 SSH**（HTTPS 不通）。`.gitignore` 排除 `*.jks/*.keystore/private.pem/certificate.pem/dist/*.apk`。
- ★ 用户规则：每完成实质改动**立即 commit+push（SSH）**；版本号 patch 位每次 +1（构建自动）；**根目录保持干净**，零散文档归 `docs/`。
- ★★ 跨仓（`tom/class/class` 等）写操作**必须先取得用户同意**。

## 激活体系
- 激活码 = **18 位纯数字**（编码 `PPCCCCMMDDDD`）；兑换码 = 4 位 `[A-Z0-9]`（爱发电买）；设备ID 取后 4 位比对。
- 后端 `POST /api/activate` body `{deviceId,redeemCode,deviceInfo}` → `{activationCode}`；一码一机、NA 上限 5、IP/设备双限流。
- 离线校验（激活码无签名，可伪造，TODO 加固需同步升 EV）。

## 埋点 / EvNotifier
- `POST /api/activate?section=visitor-track` `{path,ref,query}` → `visitor_logs` + `tracking_events(kind=visit)` + PV/UV；IP 服务端取；限流 120/min/IP。
- **消息投递追踪**（后台「消息投递」tab）= `notify.pushNotification()` 推给 EvNotifier 的 Mac 通知（`message_delivery` 表 + Upstash `auth:notifications:stream`）。⚠️ **页面访问(visit) 与 消息投递 是两套系统**：visit 不进消息投递追踪（除非后端显式 pushNotification）。2026-09-27 起 `handleVisitorTrack` 已加按 IP 5 分钟节流的 `pushNotification("visit",…)`，让访问出现在消息投递追踪、又不刷屏 Mac 通知。
- EvNotifier：`tools/ev-notifier/ev_notifier.py`，LaunchAgent `com.evnotifier.agent` 直接跑该 .py（非 .app 副本）；改完 `launchctl kickstart -k gui/$(id -u)/com.evnotifier.agent` 生效。消息源 Redis stream（`auth:notifications:stream`）+ 补拉；设置 `~/.ev_notify_settings.json`。语音用 Edge TTS 晓晓（Homebrew python@3.14）。**收不到通知先查该 LaunchAgent 是否存活 + Upstash 可达性**（不可达时 fallback 写 Postgres 但 EvNotifier 不读 → markFailed，后台可补发）。

## 留言（chat）
- 下行 手机→手环 `{"action":"chat",id,text,ts}`（EV 存 `ev_chat_inbox`）；上行手环→手机只有实时 push，无队列 → **不轮询** `ev_chat_inbox`。
- 去重：`id` 每条唯一（EV 用 `时间戳-随机4位`）；APK `MessageActivity.markSeen()` 全局去重。
- 后台提醒：前台服务 `SyncService` + 系统通知（方案 B，v0.5.17）。

## 其他
- 数据开放边界由手环侧守门人模型控制（interconnect 通道）；策略建议收敛成 `SYNC_ACCESS` 权限表。
- ⚠️ 仓库文件命名雷区：git 跟踪的文件**不要用中文名**（macOS NFC/NFD 规范化导致 git 索引错配）；中文 .md 用 ASCII 文件名。
- ⚠️ IDE「批量删除保护」（~500 文件/次）会中断 aiot 构建：临时目录 >500 文件被删拦 → 构建中止；在 IDE 手动删临时目录再构建。
