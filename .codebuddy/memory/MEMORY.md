# 项目长期记忆：EV 课程表 / 安卓同步器

## 项目三仓
| 仓 | 内容 | 运行环境 |
|---|---|---|
| `guomengtao/class-schedule` | 手环端 EV 课程表快应用 | Vela，`.rpk`，包名 `com.application.watch.classschedule` |
| `guomengtao/app-auth` | 网站后台 + 桌面工具 + AstroBox 插件 ev-schedule-sync | Vercel / macOS；插件 Rust→wasm32-wasip2，产物 `.abp` |
| `guomengtao/ev-schedule-android`（本仓） | 安卓同步器 APK | Kotlin/Java + 无 Gradle 构建脚本 |

- 本地相关目录：`app-auth`、`tom/class/class`（EV 主项目活跃副本）、`xiaomi-shouhuan-10pro`、`EvBox/evbox`。
- 全局项目地图：`/Users/Banner/Documents/guomengtao/PROJECT-MAP.md`（跨项目工作后必须更新）。
- ⚠️ `tom/class/class` 是活跃副本，另一个克隆只作对照，别改错。

## interconnect 硬约束（官方文档核实）
- 快应用与三方 APK 必须「**包名一致 + 签名一致**」→ APK applicationId 恒为 `com.application.watch.classschedule`；EV rpk 必须用同一把 keystore 签名。
- APK 侧走官方 `xms-wearable-lib_1.4_release.aar`：`getConnectedNodes()`（免权限）→ `requestPermission(nodeId, DEVICE_MANAGER, NOTIFY)` → `addListener` → `sendMessage`。
- 硬依赖手机侧「小米运动健康」（AAR manifest queries `com.xiaomi.wearable` / `com.mi.health`）；SDK 无连接能力，连接由运动健康维护。
- AstroBox 插件不受此约束（走宿主 WIT 旁路：`transport::request/send` 等）。
- EV 接口：`{"action":"ping"}` → `{ok,pong,versionName,versionCode}`；`{"action":"export"}` → 格式 A（`{day,classes:[]}` 按天分组）；`import` 只认「一条课一个对象」（需 `name`+`time`），export 回灌前必须摊平。
- 签名不一致报 `SignatureVerifyFailedException: fingerprint verify failed`，设备侧接口全挂；`getServiceApiLevel`/`getConnectedNodes` 不受影响（本地查询）。

## 已打通基线（真机实测）
- 链路：自研 APK ─XMS SDK─▶ 小米运动健康 ─BLE─▶ 小米手环 10 Pro ─▶ EV 快应用；ping/export 正常。
- 基线：nodeId 2137618976、EV 1.6.103(932)、权限 data_manager+notify。
- 三条硬前提：包名一致 + rpk 同 key 签名 + 调用顺序 `查询设备+授权 → 注册监听 → 发消息`（不注册监听必 0 回包）。
- `OnMessageReceivedListener` 在 Binder 线程，UI 更新须 `runOnUiThread`。

## App 结构（v0.5.0 起）
- Activity：`HomeActivity`（首页，4 步连接进度卡+失败指引）、`DebugActivity`（单步调试）、`SettingsActivity`（昵称）、`TransferActivity`（导入/导出复用）。
- `SyncEngine.java` 单例：4 步 = 初始化穿戴服务(`getServiceApiLevel`) → 查找设备(`getConnectedNodes`) → 申请权限 → ping EV；单一 listener + 6s 超时。
- `Ui.java` 统一深空蓝视觉；设计文档 `apk/首页与多步骤调试页设计.md`。

## ⚠️ 仓库文件命名雷区（2026-09-27 踩坑）
- **git 跟踪的文件不要用中文名**（尤其 root 下的 .md）。macOS 的 NFC/NFD Unicode 规范化会让 git 索引与磁盘文件名错配，表现为：① `git add 中文名` 静默失效（staged 为空）；② `git status` 反复出现物理不存在的 `?? apk/<中文名>` 幽灵 untracked 条目（find 确认无此文件，无害但刷屏）。
- 中文 .md 文档改用 **ASCII 文件名**（如 `harmonyos-ble-adapter-plan.md`）。已存在的 `纯血鸿蒙适配方案-直连蓝牙与备选.md` 已改用 ASCII 名重新入库。

## 构建方式（不要轻易改）
- `apk/`，无 Gradle：`aapt2 link → javac → d8 → zip dex → zipalign → apksigner`；SDK 在 `$HOME/android-sdk`。
- ⚠️ javac 用 JDK 8（zulu-8，JDK22 产出的 class 让 d8 NPE）；d8/apksigner 用 JDK 11+。
- ⚠️ d8 的 `--classpath` 不打进 dex：AAR 的 classes.jar 必须作为输入传给 d8，否则运行时闪退。
- 排闪退：`adb logcat -s EVProbe AndroidRuntime`；崩溃写 `last_crash.txt`。
- 签名：`apk/keystore.jks`（android/android，别名 evschedule）；rpk 密钥 `tom/class/class/sign/{private.pem,certificate.pem}`（空 DN 证书，需 `apk/tools/BCSign.java` + bcprov.jar 走 BouncyCastle）。证书 SHA-256 = `466a1e83dfbd4dca17a8adc87a9b6a305bfde7a72035a5de2f620832b2399a11`。
- 换签名必须先 `adb uninstall`；XML 注释不能含 `--`。

## 版本管理约定
- 版本号在 `apk/version.env`；`bash apk/build.sh` 成功后自动 bump（`NO_BUMP=1` 重出当前版）；产物归档 `apk/dist/`（只增不删）+ 复制到 `apk/EVSyncProbe.apk`。
- 版本体现在文件名 + `android:label`（build.sh sed 注入，改 label 要同步改 sed 模式）+ versionName/Code。
- ⚠️ 包名永不带版本号。只改文档没重建时，手动把 version.env patch 位 +1。

## 仓库与推码
- GitHub `guomengtao/ev-schedule-android`（private，main），用 `gh` CLI；remote = `git@github.com:guomengtao/ev-schedule-android.git`，⚠️ 推送必须 SSH（HTTPS 不通）。
- ⚠️ 绝不入库：`*.jks`/`*.keystore`/`*.pk8`/`*.p12`/`private.pem`/`certificate.pem`/`/sign/`；`.gitignore` 还排除 `apk/out|dist/`、`*.apk`、`*.idsig`、`tools/libs/xms_*` 等。

## ★ 用户明确要求的项目规则
1. 每完成实质改动立即 commit + push（SSH），不用等用户问。规则文件 `.codebuddy/rules/auto-commit.md`，提交前自检密钥与大文件。
2. 版本号 patch 位每次改动 +1（构建自动；纯文档手动）。
3. **项目根目录尽可能保持干净整洁**：源码/构建产物集中在 `apk/`；零散的分析、说明、方案类文档统一收进 `docs/`（或按 `apk/` 下已有设计文档那样就近放），不要直接堆在仓库根。新建文档前先看根目录是否已够乱、能否归并到已有文件。

## ★★ 跨仓改动：必须先取得用户同意（用户 2026-09-26 明确提出）
- 工作区之外（如 `tom/class/class`）的写操作，必须先说明「改哪个文件、改什么」并取得同意；跨仓只读可放行但要告知读过哪些路径。

## 其他约定
- 数据开放边界由手环侧守门人模型控制（interconnect 通道）；策略建议收敛成 `SYNC_ACCESS` 权限表。当前：schedule/profile/homepage/appearance 默认读+可写；pinned 需显式请求+只读（数据在 `src/data/pin-helper.js`）；auth 禁读写。
