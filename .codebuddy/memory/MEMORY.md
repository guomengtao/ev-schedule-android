# 项目长期记忆：EV 课程表 / 安卓同步器

## 本工作区
- `/Users/Banner/Documents/guomengtao/ev-schedule-android/` = **安卓同步器 App 的独立新仓**（按 `app-auth/docs/安卓同步器App-仓库选型与开发方案.md` 的推荐：方案乙 独立仓）。目前无代码、无 git。
- 相关本地目录：`app-auth`（`guomengtao/app-auth`，含 AstroBox 插件）、`EvBox/reference/class-schedule` 与 `tom/class/class`（均 `guomengtao/class-schedule` 的副本）、`xiaomi-shouhuan-10pro`、`EvBox/evbox`（手环快应用工具箱）。

## 项目三仓事实（重要）
| 仓 | 内容 | 运行环境 / 产物 |
|---|---|---|
| `guomengtao/class-schedule` | 手环端 EV 课程表快应用 | Vela 快应用，`.rpk`，包名 `com.application.watch.classschedule` |
| `guomengtao/app-auth` | 网站后台 + 桌面工具 + **AstroBox 插件 ev-schedule-sync** | Vercel / macOS / AstroBox；插件 Rust→`wasm32-wasip2`，产物 `.abp` |
| `guomengtao/ev-schedule-android` | 安卓 APK（本仓，待开发） | Kotlin/Gradle，独立签名与 Release |

- 通信链路：`Android App ──BLE(经典 SPP)──▶ 手环 Vela ──▶ EV 快应用`。
- 现有插件寄生在 AstroBox 宿主中，只调宿主 WIT：`transport::request/send`（`XIAOMI-VELA-V5-PROTOBUF`）、`interconnect::send-qaic-message`、`register::register-interconnect-recv`、`thirdpartyapp::*`。
- 自研 App 需自建蓝牙+协议栈；三条候选路线见 `app-auth/docs/安卓同步器App-仓库选型与开发方案.md` §3.2（A 复用 AstroBox CoreLib / B 自研协议栈 / C 官方穿戴 SDK XMS Wearable）。

## open-vela 快应用通信示例（参考资料）
`open-vela/packages_apps` → `wearable/`：
- `eventBus` —— 应用内发布/订阅（`src/common/pubSub.js`）。
- `interconnect_image_demo` —— **手表快应用 ↔ Android App**（`system.interconnect` + XMS Wearable），自带 `android_program/` 与 `app-debug.apk`。
- `parentChildComp` —— 页面内父子组件（props + 事件）。

## 约定 / 偏好
- 数据开放边界由手环侧（快应用）控制，守门人模型；插件只能通过 interconnect 请求。相关策略建议收敛成一张 `SYNC_ACCESS` 权限表。

## 安卓 APK 对接快应用：官方 interconnect 硬约束（重要）
- 官方文档（中/英原文均已核实）要求：**快应用与三方 Android App 必须「包名一致 + 签名一致」**，否则 `system.interconnect` 不配对。
  → 自研 APK 的 `applicationId` 必须是 `com.application.watch.classschedule`；EV 的 rpk 必须用**同一把 keystore** 签名（jks→p12→pem，拆 private.pem/certificate.pem 放快应用根目录 `/sign/debug`、`/sign/release`）。
- **AstroBox 插件不受此约束**：它走宿主 WIT 接口（`interconnect::send-qaic-message` + 底层 `XIAOMI-VELA-V5-PROTOBUF`），属旁路实现，不是官方配对通道。
- APK 侧官方 API（`xms-wearable-lib_1.4_release.aar`，《小米穿戴第三方APP能力开放接口 v1.4》）：
  `Wearable.getNodeApi(ctx).getConnectedNodes()`（免权限拿 nodeId）
  → `Wearable.getAuthApi(ctx).requestPermission(nodeId, Permission.DEVICE_MANAGER)`（首次默认授予 DEVICE_MANAGER + NOTIFY）
  → `Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])` / `addListener(nodeId, OnMessageReceivedListener{ onMessageReceived(nodeId, byte[]) })`。
- 参考 demo：`open-vela/packages_apps` → `wearable/interconnect_image_demo`（官方 interconnect 测试 demo；手表侧 `src/pages/index/index.ux` 是即插即用的 API 范式；Android 源码在 `android_program/XMS Wearable Demo.zip` 里）。
- EV 侧接口：`{"action":"ping"}` → `{ok,pong,versionName,versionCode}`；`{"action":"export"}` → `data.nickname` 等。**最外层不要无条件剥 `data`**（业务回包自带 `data`）。
- **硬依赖「小米运动健康 / 小米穿戴」**：SDK 是纯 AIDL 客户端，AAR 的 manifest 自带 `<queries>` 声明 `com.xiaomi.wearable` 与 `com.mi.health`；SDK **没有「连接设备」能力**，`NodeApi.getConnectedNodes()` 只是查询，连接由穿戴 App 维护 → 拿不到 `nodeId` 就什么都做不了。
- 精确 API（javap 核实）：`Wearable.getNodeApi/getAuthApi/getMessageApi/getNotifyApi/getServiceApi(Context)`；`NodeApi.getConnectedNodes():Task<List<Node>>`、`isWearAppInstalled(String):Task<Boolean>`；`AuthApi.requestPermission(String, Permission...):Task<Permission[]>`；`MessageApi.sendMessage(String, byte[]):Task<Void>`、`addListener(String, OnMessageReceivedListener):Task<Void>`；`Permission.DEVICE_MANAGER/NOTIFY`；`Status.RESULT_*`（含 SIGNATURE_VERIFY_FAILED / APP_NOT_INSTALLED / DISCONNECTED）。

## 本仓 APK 构建方式（不要轻易改）
- 工程在 `apk/`，**不用 Gradle/AGP**：`aapt2 link → javac → d8 → zip dex → zipalign → apksigner`。
- 已装 SDK：`$HOME/android-sdk`（platforms;android-34，build-tools 34.0.0 与 36.0.0）。
- ⚠️ **javac 必须用 JDK 8**（`/Library/Java/JavaVirtualMachines/zulu-8.jdk`，用 `-source 1.8 -target 1.8 -bootclasspath <android.jar>`）：JDK 22 的 javac 产出的 class 会让 R8/d8 抛 `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`。
- ⚠️ **d8/apksigner 必须用 JDK 11+**（本机 JDK 22）：JDK 8 跑 d8 会 `UnsupportedClassVersionError (class file version 55.0)`。
- 签名密钥：`apk/keystore.jks`（`android`/`android`，别名 `evschedule`）——**EV 的 rpk 必须用同一把重签**才能被 interconnect 配对。
- ⚠️ **d8 的 `--classpath` 是"库"，不会打进 dex**：第三方 AAR 的 class 必须作为**程序输入**（列出 .class 或 jar）交给 d8，否则运行时 `NoClassDefFoundError` → 一启动就闪退（v0.1.0 就是这么挂的）。正确做法：先 `unzip` AAR 的 classes.jar，再和自己的 class 一起传给 d8。
- 排查闪退的固定动作：`adb logcat -s EVProbe AndroidRuntime`；APK 内已内置全局崩溃处理器，会把堆栈写 `getFilesDir()/last_crash.txt` 并在下次启动时显示。

## 签名：APK 必须与 rpk 用同一把密钥（真机实测证实）
- EV 快应用 rpk 的签名密钥在本地：`tom/class/class/sign/{private.pem,certificate.pem}`（`EvBox/evbox/sign`、`tom/class/.temp_class/sign` 为同内容副本）。证书 SHA-256 = `466a1e83dfbd4dca17a8adc87a9b6a305bfde7a72035a5de2f620832b2399a11`。
- 该证书 **subject/issuer DN 为空**（小米在线签名工具生成）。JDK 的 X.509 解析器会拒收：`CertificateParsingException: Empty issuer DN not allowed in X509Certificates`。
  → 解决：`apk/tools/BCSign.java`（BouncyCastle 注册为最高优先级 provider 后调 `com.android.apksigner.ApkSignerTool.main`），配合 `apk/tools/bcprov.jar`。
- 签名不一致时真机报 `com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException: fingerprint verify failed`，且**所有设备侧接口**（含 `isWearAppInstalled`/`requestPermission`/`addListener`/`sendMessage`）全部失败；而 `getServiceApiLevel`、`getConnectedNodes` 不受影响（本地查询）。
- 换签名后 Android 不允许覆盖安装，必须先 `adb uninstall <包名>`。

## APK 版本管理约定（用户要求，2026-09-26）
- 版本号存 `apk/version.env`（`VERSION_CODE=` / `VERSION_NAME=`）；`bash apk/build.sh` 构建后**自动 bump**（code+1、name 末位+1）并写回；`NO_BUMP=1` 可重出当前版本。
- 产物归档在 `apk/dist/EVSyncProbe-v<版本>.apk`，**只增不删**（保留历史包）；同时复制一份到 `apk/EVSyncProbe.apk` 作为固定安装路径。
- 版本号体现在 **① APK 文件名 ② `android:label`（桌面显示 `EV Probe v0.3.0`）③ versionName/versionCode**。
- ⚠️ **包名（applicationId）永远固定为 `com.application.watch.classschedule`，绝不能带版本号** —— interconnect 要求与快应用 `package` 完全一致，改了连不上，而且会被系统当成另一个 App（桌面出现多个图标）。
- XML 注释里不能出现连续两个短横线，否则 aapt2 报 `not well-formed`。

## 仓库与推码约定
- GitHub 仓库：**`guomengtao/ev-schedule-android`**，**private**，分支 `main`。用 `gh` CLI 管理。
- ⚠️ **推送必须用 SSH**：HTTPS 推不动（443 连不上 / Empty reply from server）。remote = `git@github.com:guomengtao/ev-schedule-android.git`；必要时先 `gh auth setup-git`。
- ⚠️ **绝不入库**：`*.jks`、`*.keystore`、`*.pk8`、`*.p12`、`private.pem`、`certificate.pem`、`/sign/`。`apk/rpk-signing-key.pk8` 是 rpk 私钥导出物，构建时只在 `out/` 里临时生成并删除。
- `.gitignore` 还排除 `apk/out/`、`apk/dist/`、`*.apk`、`*.idsig`、`apk/tools/bcclasses/`、`apk/tools/bcprov.jar`（build.sh 自动从 Maven 下）、`tools/libs/xms_aar/`、`tools/libs/xms_classes/`、`tools/*.zip`、`.DS_Store`。

## 已打通（真机实测基线）
- 链路：`自研 APK ─官方 XMS Wearable SDK─▶ 小米运动健康 ─BLE─▶ 小米手环 10 Pro ─▶ EV 快应用`；`ping` / `export` 双向正常。
- 基线数据：nodeId 2137618976、手环 EV 1.6.103(code 932)、昵称 123、课表 21 节、字号 48、模板 default、权限 data_manager+notify。
- **三条硬前提**：包名一致 + 签名（rpk 同 key）+ **调用顺序 `2 查询设备+授权 → 4 注册监听 → 发消息`**（不注册监听就发，必然 0 回包）。
- ⚠️ **数据转换坑**：EV 的 `export` 产出是**格式 A**（`{day, classes:[]}` 按天分组），`import` 只认「一条课一个对象」（顶层需 `name`+`time`）。**把 export 的 JSON 直接回灌会整批跳过**（`convert empty`）——导入前必须摊平。
- ⚠️ `OnMessageReceivedListener` 在 **Binder 线程**，日志必须 `runOnUiThread`；回包先原样留痕（len+HEX+UTF-8）再解析。

## App 结构（v0.5.0 起）
- 4 个 Activity：`HomeActivity`（launcher/首页）、`DebugActivity`（多步骤调试）、`SettingsActivity`（昵称）、`TransferActivity`（导入/导出按 mode 复用）。`MainActivity.java` 已删除。
- `Ui.java` 统一视觉（深空蓝，对齐 EV 默认主题）；`SyncEngine.java` 是单例，封装四步连接 + 单步动作 + 回包路由（单一 listener + 6s 超时）。
- 清单 `android:label="EV Sync"`，build.sh 用 sed 注入版本号（**改 label 必须同步改 build.sh 的 sed 模式**）。
- 设计文档：`apk/首页与多步骤调试页设计.md`；浏览器预览：`apk/preview.html`（4 个状态标签）。
- 首页失败指引是核心设计：**每条错误都配一个用户能立刻执行的动作**（打开 EV / 检查小米运动健康 / 重试）。

## ★ 用户明确要求的项目规则（每次会话都要遵守）
1. **改动后自动提交 GitHub**：每完成一次实质改动（代码/文档/构建脚本），立即 commit + push 到 `guomengtao/ev-schedule-android`（private，main），**不用等用户再问**。
2. **推送必须走 SSH**（`git@github.com:guomengtao/ev-schedule-android.git`），HTTPS 推不动。
3. **版本号第三位（patch）每次改动 +1**：由 `bash apk/build.sh` 在构建成功后自动 bump；只改文档没重建时手动把 `version.env` 第三位 +1。
4. 规则文件在 `.codebuddy/rules/auto-commit.md`。提交前必做密钥与大文件自检。

## ★★ 跨仓改动：必须先取得用户同意（用户 2026-09-26 明确提出质疑）
- 我的工作区是 `ev-schedule-android`，但文件工具接受任意绝对路径、**没有硬沙箱**，技术上能改到用户 Mac 上其他项目（已实际改过 `tom/class/class` = EV 课程表主项目）。
- **用户对这件事很在意。以后凡是工作区之外的写操作，必须先说明「要改哪个文件、改什么」，取得同意后再动。** 不要再用"低风险"自行放行。
- 三种约束模式（用户可选，见 `tom/class/class/AGENT改动说明与访问范围.md` 第六节）：严格模式（每次等批准）/ 工作区模式（只改安卓仓，跨仓只出补丁）/ 现状模式（改前先说明再动）。
- 跨仓只读（搜索、读文档）可以放行，但也要让用户知道读过哪些路径。
- 说明文档：`tom/class/class/AGENT改动说明与访问范围.md`。
