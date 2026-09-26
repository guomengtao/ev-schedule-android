# interconnect_image_demo 上手分析：能否用它对接 EV 课程表（自研 APK）

> 目标：做一个**安卓 APK**，管理 `guomengtao/class-schedule`（EV 课程表快应用）的课表导入 / 导出。
> 第一步只想验证：**通道能不能连、demo 能不能用**——比如只读一下手环里的「昵称」或「版本号」。
> 分析对象：`open-vela/packages_apps` → `wearable/interconnect_image_demo`（官方文档附录里指向的「interconnect 开发测试 demo」）。
> 核对时间：2026-09-26（源码取自仓库 `dev` 分支，本地 EV 代码取自 `EvBox/reference/class-schedule`，v1.6.130）

---

## 一、结论先行

| 问题 | 结论 |
|---|---|
| demo 本身能跑吗？ | ✅ **能**。仓库里已附预编译的 `.rpk` + `.apk`，装完即可体验。 |
| 能直接拿 demo 的 APK 去读 EV 课程表吗？ | ❌ **不能**。官方 interconnect 要求「**同一个包名 + 同一签名**」，demo 是 `com.xiaomi.xms.wearable.demo`，EV 是 `com.application.watch.classschedule`，两者不配对。 |
| 那 demo 还有什么用？ | ✅ **两个用途**：① 验证「SDK + 蓝牙 + 你的机型」这条链路通不通；② 手表侧源码是**可直接抄的 interconnect 用法范式**。 |
| EV 课程表要改造吗？ | ❌ **不用**。EV（v1.6.130）**已经实现** interconnect 接收端，`ping` 直接返回版本号，`export` 直接返回昵称。 |
| 通道本身能不能通？ | ✅ **已被实测证明**：AstroBox 插件 v1.0.58 实测 `ping` / `export` / `update_settings` **三条全通**（手环 EV 1.6.103）。所以风险不是「能不能通」，而是「**官方 SDK 这条路能不能通**」。 |
| 「读昵称 / 版本号」难吗？ | ⭐ **很简单**——难点全在「包名 + 签名」这一关，过了这关，两条报文就搞定。 |

**一句话**：demo 可以简单上手，但**不能简单地直接接 EV**。它是一次「链路体检 + 抄代码」，真正对接需要你自己建 APK，并满足「包名 = 快应用包名 + 签名一致」这条硬约束。

---

## 二、demo 到底给了什么（文件级事实）

| 文件 / 目录 | 大小 | 是什么 | 能不能直接用 |
|---|---|---|---|
| `src/` | — | 快应用源码（`app.ux` / `manifest.json` / `pages/` / `i18n/` / `style/`） | ✅ **可读、可抄**（这就是最值钱的部分） |
| `src/pages/index/index.ux` | 7.3 KB | interconnect 的完整用法：连接、发请求、收分片、拼图 | ✅ 范式 |
| `src/pages/detail/detail.ux` | — | 图片全屏查看 | 与本项目无关 |
| `src/manifest.json` | 686 B | 包名 / 版本 / `features` 声明 | ✅ 参考 |
| `com.xiaomi.xms.wearable.demo.release.1.0.0.rpk` | 15.8 KB | **预编译快应用包**（手表侧） | ✅ 可直接装 |
| `app-debug.apk` | 8.3 MB | **预编译 Android 包**（手机侧） | ✅ 可直接装 |
| `android_program/XMS Wearable Demo.zip` | 22.3 MB | Android 工程源码 —— ⚠️ **是 zip，不是明文目录**，需下载解压才能看 | ⚠️ 要手动解压 |
| `android_program/libs/xms-wearable-lib_1.4_release.aar` | 100 KB | **核心 SDK**：小米穿戴第三方 App 能力开放接口 v1.4 | ✅ 这是你 APK 要引的库 |

> ⚠️ 注意：**Android 端源码不是以目录形式放在仓库里的**，而被压成了一个 22.3 MB 的 zip；仓库里能直接看到的只有 `libs/` 下的 AAR 和预编译产物。所以「分析 demo 代码」主要能分析的是**手表侧**，APK 侧要看 zip 内容或官方接口文档。

`src/manifest.json` 全文（关键信息）：

```json
{
  "package": "com.xiaomi.xms.wearable.demo",
  "name": "interconnect-demo",
  "versionName": "1.0.0",
  "versionCode": 1,
  "minPlatformVersion": 1200,
  "deviceTypeList": ["watch"],
  "features": [
    { "name": "system.router" },
    { "name": "system.interconnect" },
    { "name": "system.file" }
  ],
  "config": { "logLevel": "log", "designWidth": 480 },
  "router": { "entry": "pages/index", "pages": { "pages/index": {...}, "pages/detail": {...} } }
}
```

→ 想用 interconnect，**必须在 `manifest.json` 的 `features` 里声明 `system.interconnect`**。

---

## 三、手表侧代码拆解（可直接抄的范式）

`src/pages/index/index.ux` 里的关键片段：

```js
import interconnect from '@system.interconnect'

let conn = null          // ⚠️ 放在模块作用域，不是 private 数据

handleCreateConn() {
  conn = interconnect.instance()        // ① 取单例连接对象，无参数

  conn.getReadyState({                 // ② 查连接状态
    success: (data) => {
      if (data.status === 1)      this.statusText = '已连接'
      else if (data.status === 2) this.statusText = '连接失败'
    },
    fail: (data, code) => { this.statusText = '连接异常' }
  })

  conn.onmessage = (data) => {          // ③ 收手机发来的消息
    this.handleReceivedData(data?.data || '22')
  }
  conn.onopen  = () => { this.statusText = '已连接' }
  conn.onclose = (data) => { this.statusText = '已断开' }
  conn.onerror = (data) => { this.statusText = '连接错误' }
}

handleSendMessage() {
  conn.send({                           // ④ 发给手机：data 是【对象】，不是字符串
    data: { type: 'request_image', message: '请发送图片', t: Date.now() },
    success: () => { this.statusText = '已请求图片' },
    fail: (data) => { this.statusText = '发送失败' }
  })
}
```

**四条必须记住的约定：**

1. 断言 `interconnect.instance()` **无参数**（单例）。
2. `getReadyState` 的 `status`：`1` 已连接、`2` 断开；`fail` 给 `(data, code)`（`1006` = 连接断开）。
3. 四个事件是**属性赋值式**（`conn.onmessage = fn`），**不是** `conn.on('message', fn)`。
4. `conn.send({ data: <Object> })` —— `data` 传**对象**，框架负责序列化；**发字符串会双重转义**（这条是 EV 项目踩过的坑，写在协议文档里）。
5. 收消息时取 `data.data` 才拿到业务载荷。

> 官方文档还提供了 `connect.diagnosis({ timeout })`：`0`=OK、`204`=连接超时、`1001`=对端 App 未安装、`1000`=其他。**这个接口比 `getReadyState` 更适合做「体检」**，建议你的 APK 第一步就用它。

---

## 四、最关键的卡点：官方 interconnect 的「包名 + 签名一致」

这是决定「demo 能否简单使用」的唯一硬门槛。官方文档（中/英）原文：

> 中文：*「interconnect 通信的前提是快应用与三方应用安卓端的**包名及签名保持一致**。」*
> *「快应用 `manifest.json` 里的 `package` 字段，必须与要接入的三方 App 安卓端包名一致。」*
> *「快应用的签名必须使用该三方 App 安卓端的签名。」*
>
> 英文：*"For interconnect communication, ensure that the **package names and signatures** of both the Quick App and the Android version of the third-party app are **consistent**."*

**由此推出对本项目的三条硬约束：**

| # | 约束 | 说明 |
|---|---|---|
| 1 | 你的 APK 包名必须是 **`com.application.watch.classschedule`** | 即与 EV 快应用的 `package` 完全一致 |
| 2 | EV 的 `.rpk` 必须用**你这个 APK 的 keystore** 签名 | `keystore.jks` → `keystore.p12` → `keystore.pem`，拆出 `private.pem` + `certificate.pem`，放进快应用根目录的 `/sign/debug` 与 `/sign/release` |
| 3 | 两端签名必须**同一把证书** | 否则 `onerror` 会报连接错误，或直接连不上 |

签名转换命令（官方给的流程，你自己在本地跑）：

```bash
keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 \
        -srcstoretype jks -deststoretype pkcs12
openssl pkcs12 -nodes -in keystore.p12 -out keystore.pem
# 再从 keystore.pem 里拆出：
#   -----BEGIN PRIVATE KEY-----  ... -----END PRIVATE KEY-----   → private.pem
#   -----BEGIN CERTIFICATE-----  ... -----END CERTIFICATE-----   → certificate.pem
```

> 官方也提供**在线签名生成工具**（WebAssembly，本地浏览器生成，不上传私钥），嫌命令行麻烦可以用它。

**为什么 AstroBox 插件不受这条约束？**
因为 AstroBox 走的是**宿主 WIT 接口**（`interconnect::send-qaic-message` + 底层 `XIAOMI-VELA-V5-PROTOBUF` 传输），属于**旁路实现**，不是官方 `system.interconnect` 配对通道。所以插件能连上 `com.application.watch.classschedule`，而官方 SDK 这条路必须满足包名 + 签名匹配。

> 这也解释了为什么「插件已经全通」但「自研 APK 仍是未知数」——两条路的技术前提完全不同。

---

## 五、逐项判定：demo 到底能不能「简单用」

| 想做的事 | 可行 | 原因 / 做法 |
|---|:--:|---|
| 装 demo 的 rpk + apk，看它自己能不能传图 | ✅ | 两者是配套的，包名签名天然一致 |
| 用 demo 的 APK 读 EV 的昵称 | ❌ | demo APK 包名 ≠ EV 包名，不配对 |
| 把 demo 的 rpk 改包名成 EV 的 | ❌ | 改了就不是同一个应用，且 EV 已发布，不能改包名 |
| 自己写 APK，包名设成 `com.application.watch.classschedule` | ✅ | 这是**正确路径**，但需自己写代码 |
| 用自研 APK 发 `ping` 拿**版本号** | ✅ | EV 侧已实现，回 `{ok:true,pong:true,versionName,versionCode}` |
| 用自研 APK 发 `export` 拿**昵称** | ✅ | 回包里 `data.nickname`，且 `profile` 域是**默认开放**的 |
| 用自研 APK 做导入 / 导出课表 | ✅ | EV 侧 `import` / `export` 已实现（协议 v1） |
| 完全不做任何改造，直接「下载 demo 接 EV」 | ❌ | 不存在这条捷径 |

**所以「demo 目前能不能简单使用」的准确答案是：**
> 作为**链路体检 + 代码范式**，能，且很值；作为**直接对接 EV 的成品**，不能。中间差一个「自己建 APK + 对齐包名签名」的工序。

---

## 六、第一步怎么走：从 zero 到「读到版本号」

### 阶段 0（半天）：先跑 demo，只验链路

目的：证明「你的手环 + 小米穿戴 App + 这台手机 + SDK」这条链路本身是活的。

1. 只拉需要的目录（避免 clone 整个 AOSP 级大仓）：
   ```bash
   git clone --depth 1 --filter=blob:none --sparse https://github.com/open-vela/packages_apps.git
   cd packages_apps
   git sparse-checkout set wearable/interconnect_image_demo
   cd wearable/interconnect_image_demo
   ```
2. 手表侧：用 **AstroBox 推送 `com.xiaomi.xms.wearable.demo.release.1.0.0.rpk`**（和推 EV 的方式一样）。
3. 手机侧：安装仓库根目录的 **`app-debug.apk`**（注意：需已安装「小米运动健康」并完成配对）。
4. 手表打开 demo → 点「**创建连接**」→ 看状态是否变「已连接」。
5. 点「**发送消息**」→ 手机端应回传图片，手表显示传输进度并渲染出图。
6. 同时把 `android_program/XMS Wearable Demo.zip` **下载解压**，这就是你后续 APK 的工程骨架参考。

判定：
- 出图了 → 链路通，可以进入阶段 1。
- 连不上 → 先解决环境（蓝牙、配对、小米运动健康、机型），**不要**继续往下写代码。

### 阶段 1（1–3 天）：建自己的最小 APK，打通 `ping`

**工程配置（关键三项）**

| 项 | 设置 |
|---|---|
| `applicationId` | **`com.application.watch.classschedule`**（= EV 快应用包名，硬要求） |
| 签名 | 用你自己的 keystore；**同一把**keystore 去签 EV 的 rpk |
| 依赖 | 引入 `xms-wearable-lib_1.4_release.aar`（放 `app/libs/`，`implementation files('libs/xms-wearable-lib_1.4_release.aar')`） |

**APK 侧要用的官方 API（来自《小米穿戴第三方APP能力开放接口 v1.4》）**

| API | 用途 | 权限 |
|---|---|---|
| `Wearable.getNodeApi(ctx).getConnectedNodes()` | 拿 `nodeId`（**不需要权限**） | — |
| `Wearable.getAuthApi(ctx).requestPermission(nodeId, DEVICE_MANAGER)` | 申请权限（**首次调用默认授予 DEVICE_MANAGER + NOTIFY**） | — |
| `Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])` | **手机 → 手环**发消息 | `DEVICE_MANAGER` |
| `messageApi.addListener(nodeId, listener)` → `onMessageReceived(nodeId, byte[])` | **手环 → 手机**收消息 | `DEVICE_MANAGER` |
| `Wearable.getServiceApi(ctx)` | 监听与小米穿戴 App 的连接状态 | — |

**骨架代码（Kotlin 风格，示意）**

```kotlin
// 1) 拿设备
Wearable.getNodeApi(ctx).getConnectedNodes()
    .addOnSuccessListener { nodes ->
        val nodeId = nodes.first().id
        // 2) 申请权限（首次会默认授予）
        Wearable.getAuthApi(ctx).requestPermission(nodeId, Permission.DEVICE_MANAGER)
            .addOnSuccessListener {
                val messageApi = Wearable.getMessageApi(ctx)
                // 3) 注册监听（先注册，再发送，避免漏掉回包）
                messageApi.addListener(nodeId, OnMessageReceivedListener { _, bytes ->
                    Log.i("EV", "回包: " + String(bytes, Charsets.UTF_8))
                })
                // 4) 发送 ping：报文就是 UTF-8 的 JSON 字符串
                val ping = """{"action":"ping"}""".toByteArray(Charsets.UTF_8)
                messageApi.sendMessage(nodeId, ping)
            }
    }
```

**期望回包（EV 侧已实现，`src/app.ux` L547-557）**

```
{ "ok": true, "action": "ping", "pong": true,
  "versionName": "1.6.130", "versionCode": 959 }
```

> 拿到这一行，就等于拿到了**版本号**，也等于**整条链路彻底打通**。

### 阶段 2（半天）：读昵称

```
发送：{"action":"export"}
回包：{ "ok": true, "action": "export", "version": 1,
        "scopes": [...],
        "data": { "nickname": "小明", "versionName": "1.6.130", "versionCode": 959,
                  "schedule": [...], "homepage": {...} } }
```

- 昵称在 `data.nickname`（来源 storage key `userNickname`）。
- ⚠️ **回包字段是 `data`**，而 EV 的 `export` 回包**自带 `data`** —— 解析时**不要无条件剥最外层 `data`**（这是插件踩过的坑 #4）。
- EV 侧聚合多个异步读取，**最长约 2 秒**才回；Android 侧等待建议 **≥ 3 秒**。

---

## 七、EV 侧现状：不用改代码，接口都齐了

本地 `class-schedule`（v1.6.130 / code 959）已实现：

| 能力 | 位置 | 说明 |
|---|---|---|
| 声明 `system.interconnect` | `src/manifest.json` | ✅ |
| 接收器注册 | `src/app.ux` → `initSyncReceiver()`，在 `onCreate` 里调用 | ✅ |
| 后台常驻 | `src/app.ux` → `startResident()`（`@system.resident`） | ✅ **自动开启**，无需用户手工设置「后台运行」 |
| `ping` | `src/app.ux` L547 | 回 `versionName` / `versionCode` |
| `export` | L559 | 回 `schedule` / `nickname` / `homepage` / `versionName` |
| `update_settings` | L568 | 改昵称 / 首页设置 / 模板 / 字号 |
| `import` | L572 | 覆盖式导入课表（写前自动备份 `astrobox_sync_backup`） |

**数据开放边界（守门人模型，`SYNC_ACCESS` 表）**：

```
schedule   任意读 + 可写
profile    任意读 + 可写     ← 昵称
homepage   任意读 + 可写
appearance 任意读 + 可写
version    任意读 + 只读     ← 版本号
pinned     需显式请求 + 只读
auth       禁止读 + 禁止写
```

→ **你要的「昵称」和「版本号」都是默认开放的**，不需要额外 `scopes`。

---

## 八、风险与待验证（按重要性排序）

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| 1 | ~~APK 包名必须叫 `com.application.watch.classschedule`~~ | — | **已决策（2026-09-26）：直接采纳、固化进工程，不再作为验证项**（见 §十 Q4） |
| 2 | 签名一致的**具体粒度**（是否只比证书指纹） | 签名不匹配就是连不上 | 用同一把 keystore 签两端；官方文档流程照做 |
| 3 | demo 的 Android 工程在 22 MB zip 里，**内容未核实** | 可能缺 Gradle 配置 / 需手动补 | 阶段 0 就下载解压，先看 `build.gradle` + `AndroidManifest.xml` 的包名 |
| 4 | 官方 SDK 是否依赖**小米运动健康** App | 用户装包门槛 | 首启检测 + 引导 |
| 5 | `sendMessage` 的 `byte[]` 与手表 `data.data` 的**类型映射**（字符串 or 对象） | 解析错就「收到但读不出」 | EV 侧已兼容字符串/对象两种形态，先按 **UTF-8 JSON 字符串**发 |
| 6 | interconnect 载荷上限未知 | 大课表可能被截断 | 分批发送；先用小课表验证 |
| 7 | 官方 SDK 这条路**本项目从未实测过** | 与 AstroBox 插件的成熟度差距 | 先做阶段 1 的 `ping`，最小代价证伪/证实 |

> 提醒：**AstroBox 插件路线已经全通**（v1.0.58 实测三链路通过）。如果阶段 1 的 `ping` 卡住，最快的兜底不是继续折腾 SDK，而是评估「直接复用 AstroBox 宿主能力」。

---

## 九、一句话行动清单

1. **今天**：clone `wearable/interconnect_image_demo` → 装它的 rpk + apk → 确认能传图（链路体检）。
2. **明天**：解压 `XMS Wearable Demo.zip`，摸清 Android 工程结构与包名；同时确认自己 keystore。
3. **本周**：建空 APK，包名设 `com.application.watch.classschedule`，引 `xms-wearable-lib_1.4_release.aar`，发 `{"action":"ping"}`。
4. **拿到 `pong:true` + `versionName` = 第一阶段成功。**
5. 然后发 `{"action":"export"}` 读昵称 → 再逐步做导入 / 导出。

---

## 附：参考来源

| 内容 | 来源 |
|---|---|
| demo 源码 / manifest / 目录结构 / so 附件 | `open-vela/packages_apps` → `wearable/interconnect_image_demo`（`dev`） |
| interconnect 配对规则、API 语义 | Xiaomi Vela 官方文档《设备通信 interconnect》（中/英） |
| APK 侧 API（NodeApi / AuthApi / MessageApi / ServiceApi / NotifyApi） | 《小米穿戴第三方APP能力开放接口文档 v1.4》（对应 `xms-wearable-lib_1.4_release.aar`） |
| EV 侧协议与实测结论 | 本地 `class-schedule/src/app.ux`、`docs/同步器对接协议文档.md`、`app-auth/docs/EV课程表同步器-2天调试复盘-核心卡点.md` |

---

# 第二部分：答疑、决策与已交付的 APK（2026-09-26 更新）

## 十、答疑与决策更新

### Q1：这个 APK 装在安卓手机上是独立的吗？

是**独立的 APK**——可独立安装、独立图标、独立分发、独立版本号。

但它**不是自包含的**：APK 里没有蓝牙/协议栈，它只是「小米穿戴服务」的一个**调用方**。真实链路是：

```
我们的 APK  ──AIDL(跨进程)──▶  小米运动健康/小米穿戴 App  ──BLE──▶  手环  ──▶  EV 快应用
```

所以：**装得上，但缺了那个 App 就用不了。**

### Q2：必须安装「小米运动健康」吗？—— 必须，这不是推测

把 AAR 解包后，它自己的 `AndroidManifest.xml` 就写死了依赖：

```xml
<queries>
    <package android:name="com.xiaomi.wearable" />   <!-- 小米穿戴 -->
    <package android:name="com.mi.health" />         <!-- 小米运动健康 -->
</queries>
```

完整证据链：

| # | 证据 | 说明 |
|---|---|---|
| 1 | AAR 的 `<queries>` 声明 | Android 11+ 必须显式声明才能「看见」这些 App —— SDK 的前提就是它们存在 |
| 2 | SDK 是纯 AIDL 客户端 | AAR 内含 `IPermissionCallback` / `IMessageCallback` / `INodeCallback` / `IDataCallback` 等跨进程接口，**服务端由穿戴 App 提供** |
| 3 | `ServiceApi` 的语义 | 官方文档：用于「监听**第三方 App 与小米穿戴 App** 的连接状态」 |
| 4 | 异常类与状态码 | `AppNotInstalledException`、`Status.RESULT_APP_NOT_INSTALLED`、`RESULT_SIGNATURE_VERIFY_FAILED` |
| 5 | 版本说明 | 接口文档 v1.3 注：「兼容**小米穿戴与小米健康合并**项目」→ 即今天的「小米运动健康」 |
| 6 | AAR 的 `minSdkVersion=19` | 与穿戴 App 的通信全靠它的系统服务 |

**结论：小米运动健康（或小米穿戴）是这套 SDK 的必要组件，不是可选项。**

### Q3：小米运动健康要保持和手环的连接，是核心前置条件吗？—— 是，而且是第一步

1. **SDK 里没有「连接手环」这个能力**。`NodeApi` 只能 `getConnectedNodes()` **查询已连接设备**，它不会去发起连接。连接的建立与维持 100% 由小米运动健康负责。
2. **拿不到 `nodeId` 就寸步难行**：`requestPermission(nodeId, ...)`、`sendMessage(nodeId, ...)`、`addListener(nodeId, ...)` 的**第一个参数就是 `nodeId`**。
3. 断开时 SDK 会抛 `DeviceDisconnectedException` / 返回 `Status.RESULT_DISCONNECTED`。

**结论：小米运动健康不连上手环，这个 APK 什么都做不了。这是铁的前置条件。**

### Q4：关于「APK 包名必须与快应用一致」的决策

按你的意见：**不再作为「待验证项」，直接采纳并固化到工程里**。

已完成：
- manifest `package` = `applicationId` = **`com.application.watch.classschedule`**（与 EV 快应用完全一致）
- 写进构建脚本与文档，后续不再测这一条

仍需你确认的唯一一点：**签名一致** —— `apk/keystore.jks` 必须**同时**用于签 EV 的 `.rpk`。

---

## 十一、已交付的 APK

### 产物信息

| 项 | 值 |
|---|---|
| 文件 | **`apk/dist/EVSyncProbe-v0.3.0.apk`**（按版本归档，历史保留）<br>同时复制一份到 `apk/EVSyncProbe.apk`（固定路径，方便安装） |
| 大小 | ≈36 KB |
| 包名 | `com.application.watch.classschedule` ✅ 与 EV 快应用一致（**固定，不带版本号**） |
| versionName / versionCode | 0.3.0 / 3（由 `apk/version.env` 管理，每次构建自动 +1） |
| minSdk / targetSdk | 24（Android 7.0）/ 34 |
| 签名方案 | APK Signature Scheme **v2 + v3** |
| 签名证书 | **与 EV 快应用 rpk 同一把**（`tom/class/class/sign/`） |
| 证书 SHA-256 | `466a1e83dfbd4dca17a8adc87a9b6a305bfde7a72035a5de2f620832b2399a11` |
| 证书 SHA-1 | `ecea528a53ee883bf771b4bf2812751862245ca9` |
| APK SHA-256 | `63150cadea430a364307c6f2373097f36f6f3e4f4c9e8112c92effc2e3869352` |

> ⚠️ **签名必须与 rpk 一致**（官方 interconnect 硬要求，真机已实测证实）。
> `build.sh` 会自动在 `tom/class/class/sign`、`EvBox/reference/class-schedule/sign`、`EvBox/evbox/sign` 里找 `private.pem` + `certificate.pem`；
> 也可用 `RPK_SIGN_DIR=/path/to/sign bash build.sh` 指定。
> 找不到时退回自建 `apk/keystore.jks`（仅用于跑通界面，**interconnect 一定会失败**）。

### 界面

```
[5 发送 PING]        [6 读取昵称]
[1 检测穿戴服务]      [2 查询已连接设备]
[3 申请权限]          [4 注册消息监听]
[发送自定义 JSON]     [清空日志]
-------------------------------------
日志区（可选中复制）
```

### 使用步骤（严格按顺序）

| 步 | 操作 | 期望日志 |
|---|---|---|
| 0 | 手机装好「小米运动健康」并**连上手环** | — |
| 1 | 安装 `EVSyncProbe.apk` 并打开 | 显示本 APK 包名 |
| 2 | 点「1 检测穿戴服务」 | `ServiceApiLevel = x` / `ServiceApi 已连接` |
| 3 | 点「2 查询已连接设备」 | `设备: id=… name=…` + `手环上 EV 课程表已安装? true` |
| 4 | 点「4 注册消息监听」 | `监听已注册，可以发消息了` |
| 5 | 点「3 申请权限」 | `已授权: [DEVICE_MANAGER]` |
| 6 | 点「5 发送 PING」 | `<< RX … {"ok":true,"action":"ping","pong":true,"versionName":"…","versionCode":…}` |
| 7 | 点「6 读取昵称」 | `<< RX … {"ok":true,"action":"export","data":{"nickname":"…",…}}` |

**判定对照表**

| 现象 | 结论 / 下一步 |
|---|---|
| 步骤 6、7 都收到 `ok:true` | ✅ **官方 SDK 路线打通**，可以开始做导入/导出功能 |
| 步骤 2 报失败 / `ServiceApiLevel` 拿不到 | 没装小米运动健康，或服务未启动 |
| 步骤 3 设备列表为空 | 小米运动健康没连上手环 —— 先解决这个 |
| 步骤 6 `sendMessage` 失败且含 `SIGNATURE` | 签名不一致 → 用同一把 keystore 重签 rpk |
| 步骤 6 一直无回包 | 检查手环 EV 版本（需 ≥1.6.62 才支持 ping/export）、或考虑退回 AstroBox 插件路线 |

### 工程结构

```
apk/
├── AndroidManifest.xml              # 包名 / queries / 权限声明
├── src/com/application/watch/classschedule/
│   └── MainActivity.java            # 纯 Java 构建 UI（无 XML 布局）
├── libs/xms-wearable.jar            # 由 AAR 的 classes.jar 提取
├── build.sh                         # 手工构建脚本（无需 Gradle/Studio）
├── keystore.jks                     # 自动生成的签名密钥（★ 必须与 rpk 共用）
└── EVSyncProbe.apk                  # 产物
```

### 重新构建

```bash
export ANDROID_SDK_ROOT="$HOME/android-sdk"
bash apk/build.sh
```

---

## 十二、构建踩坑记录（复现时看这里）

| 坑 | 现象 | 解决 |
|---|---|---|
| **JDK 22 的 javac 产出的 class 会让 R8 崩溃** | `d8` 报 `NullPointerException: Cannot invoke "String.length()" … null`，报错定位到某个 `MainActivity$N.class` | **javac 必须用 JDK 8**（`-source 1.8 -target 1.8 -bootclasspath android.jar`）；d8 反向要求 JDK 11+ |
| JDK 8 跑不了 d8 | `UnsupportedClassVersionError … class file version 55.0` | `build.sh` 里**分别指定两个 JDK**：JDK 8 编译、JDK 22 跑 d8/apksigner |
| JDK 22 下 `-source 8` 报 `package android.app does not exist` | 缺 bootclasspath | 显式加 `-bootclasspath "$AJAR"` |
| `d8 --output` 目录必须已存在 | `Invalid output: ./out` | 先 `mkdir -p` |
| Android 端源码不在仓库明文目录里 | 只能看到 zip 与 AAR | 用 GitHub blob API 单独取 AAR；要看源码得下 22 MB 的 `XMS Wearable Demo.zip` |

> 本工程**不使用 Gradle / AGP**，用 `aapt2 + javac + d8 + zipalign + apksigner` 手工构建，全程无网络依赖，产物 ≈17 KB，内容仅 6 个文件。

---

# 第三部分：真机实测与签名修复（2026-09-26）

## 十三、真机实测结果

### 实测日志（v0.2.0 · 小米手环 10 Pro）

```
18:44:52.844  小米穿戴 SDK 已加载（MessageApi 可用）
18:44:59.036      ServiceApi 已连接 —— 小米穿戴/运动健康 App 可用
18:44:59.049      ServiceApiLevel = 1
18:45:01.302      设备: id=2137618976  name=小米手环10 Pro
18:45:01.303      已选定 nodeId = 2137618976
18:45:01.337  !! FAIL: PermissionDeniedException: permission denied
18:45:02.838     sendMessage 失败: SignatureVerifyFailedException: fingerprint verify failed
18:45:03.425     sendMessage 失败: SignatureVerifyFailedException: fingerprint verify failed
18:45:04.412  !! FAIL: SignatureVerifyFailedException: fingerprint verify failed
18:45:05.117  !! FAIL: SignatureVerifyFailedException: fingerprint verify failed
```

### 解读

| 现象 | 结论 |
|---|---|
| SDK 加载成功、`ServiceApiLevel = 1` | 小米运动健康在、服务可用 → **§十 Q2 验证成立** |
| `getConnectedNodes` 返回真实设备 | 手环连接正常 → **§十 Q3 验证成立**（不连上就什么都做不了） |
| 没有报包名相关错误 | 包名一致这一条**已通过**，不再是卡点 |
| 之后**所有**设备侧操作都报 `SignatureVerifyFailedException: fingerprint verify failed` | 唯一剩下的关卡：**签名不一致** |

### 根因（真机完整证实了官方那条规则）

| | 证书 SHA-256 |
|---|---|
| EV 快应用 rpk（`<class-schedule>/sign/certificate.pem`） | `46:6A:1E:83:…:9A:11` |
| 我们的 APK（旧，自建 keystore） | `EB:09:C8:3A:…:9D:F9` |
| **不一致 → 校验拒绝** | |

## 十四、修复：用 rpk 同一把密钥签 APK

`build.sh` 现在会自动查找 rpk 的签名目录并用它签 APK：

| 项 | 值 |
|---|---|
| 证书 SHA-256 | `466a1e83dfbd4dca17a8adc87a9b6a305bfde7a72035a5de2f620832b2399a11`（**= rpk 的**） |
| 证书 SHA-1 | `ecea528a53ee883bf771b4bf2812751862245ca9` |
| APK SHA-256 | `63150cadea430a364307c6f2373097f36f6f3e4f4c9e8112c92effc2e3869352` |
| 大小 | 37,289 字节 |

### 附带解决的坑：空 DN 证书

rpk 的证书 issuer/subject DN 是**空的**（小米在线签名工具生成），JDK 自带的 X.509 解析器直接拒收：

```
java.security.cert.CertificateParsingException: Empty issuer DN not allowed in X509Certificates
```

解决：`apk/tools/BCSign.java` —— 把 BouncyCastle 注册为最高优先级 provider 再调用 apksigner，BC 不做这个校验。

### 升级方式（签名变了，必须先卸载再装）

```bash
~/android-sdk/platform-tools/adb uninstall com.application.watch.classschedule
~/android-sdk/platform-tools/adb install apk/EVSyncProbe.apk
```

> 因为签名从「自建 keystore」换成了「rpk 的证书」，Android 不允许跨签名覆盖安装。

### 下一步验证清单

| # | 动作 | 期望 |
|---|---|---|
| 1 | 重装后点 `2` | 设备列表仍正常 |
| 2 | 点 `4` 注册监听 | 不再报 `SignatureVerifyFailedException` |
| 3 | 点 `3` 申请权限 | `已授权: [DEVICE_MANAGER]` |
| 4 | 点 `5` 发送 PING | 收到 `{"ok":true,"pong":true,"versionName":…}` |
| 5 | 点 `6 读取昵称` | 收到 `data.nickname` |

> 若步骤 4 仍报签名错，说明手环上那个 rpk 不是用这把密钥签的 → 需要用当前密钥重出 rpk 并重装（注意：换签名重装会清数据，可先用 EV 自带的备份功能导出）。

---

# 第四部分：版本管理 + 回包排查（2026-09-26）

## 十五、版本管理（已实现）

### 能力

| 需求 | 实现 |
|---|---|
| 每次打包保留历史包 | 产物输出到 `apk/dist/EVSyncProbe-v<版本>.apk`，**只增不删** |
| 版本号自动 +1 | 构建后自动写回 `apk/version.env`（`VERSION_CODE+1`、`VERSION_NAME` 末位 +1） |
| 一眼看出装的是哪版 | `android:label` 注入版本号 → 桌面显示 `EV Probe v0.3.0`；App 内首行也显示版本 |

`apk/version.env`：

```
VERSION_CODE=4
VERSION_NAME=0.3.1      # 下次构建用这一版
```

### ⚠️ 关于「包名加版本号」

**包名不能带版本号。** 官方 interconnect 要求 APK 包名与快应用 `package` **完全一致**，而且这一条上一轮已被真机证实（包名一致才走到签名校验那一步）。一旦包名加版本号：

- 与快应用不再匹配 → `interconnect` 直接连不上
- 每次改包名都会被系统当成**另一个 App**，旧包不会被覆盖，桌面出现一堆图标

所以版本号只体现在 **① 文件名 ② android:label ③ versionName/versionCode** 三处。若确实想并存多个版本，用文件名区分（`dist/` 里已经这么做了）。

### 用法

```bash
bash apk/build.sh                 # 打包当前版本 → dist/，然后自动 bump
NO_BUMP=1 bash apk/build.sh       # 重出当前版本，不改版本号
```

## 十六、v0.3.0 真机日志解读

```
18:56:13.693  小米穿戴 SDK 已加载（MessageApi 可用）
18:56:18.734      设备: id=2137618976  name=小米手环10 Pro
18:56:23.276      已授权: [DEVICE_MANAGER]          ← 签名过了！
18:56:26.687      监听已注册，可以发消息了            ← 签名过了！
18:56:57.628      手环上 EV 课程表已安装? true        ← 签名过了！
18:56:31.170     sendMessage 已受理
18:56:35.684     sendMessage 已受理
...
（没有任何 << RX）
```

| 项 | 状态 |
|---|---|
| 签名一致 | ✅ **已解决**（`SignatureVerifyFailedException` 完全消失） |
| DEVICE_MANAGER 权限 | ✅ |
| 注册监听 | ✅（第二轮 `you have registered` 是重复注册，v0.3.0 已加保护） |
| EV 已安装检测 | ✅ true |
| **回包** | ❌ **一条都没有** |

### v0.3.0 顺带修的两处

1. `[4] 注册消息监听` 重复点击 → `IllegalStateException: you have registered`：现在会先判断，已注册就直接提示不再重复注册；并新增「移除监听」按钮。
2. `已授权: [Permission@564bc6a]` 打印的是对象地址：现在用 `Permission.getName()` 打印真名。
3. 「2 查询设备」现在会**自动接着申请权限**，少点一步。

## 十七、剩下的问题：发出去没有回包

### 已排除

签名、权限、监听注册、EV 安装、设备连接 —— 全部正常，且 `sendMessage` 全部返回「已受理」。

### 按可能性排序的假设

| # | 假设 | 为什么 | 怎么验 |
|---|---|---|---|
| **H1** | **手环上 EV 课程表没在运行** | EV 的接收器注册在 `App.onCreate()` 里；不运行 = 没人接。AstroBox 插件当年就是先 `launch-qa` 把 EV 拉起来再 ping 的 | **先在手表上手动打开一次 EV 课程表，再回来发 ping**（v0.3.0 也加了「拉起手表 EV 应用」按钮） |
| H2 | 手环上的 EV 版本太老，不认识 `ping` / `export` | `ping`/`export` 是协议 v1 才加的；旧版只认「无 action = import」 | 用「发送自定义 JSON」发一条 **import**（见下），看**手环课表是否真的变化** |
| H3 | 回包路由不到本 App | SDK 按包名/签名路由回包 | 若 H1/H2 都排除，基本可判定是宿主/固件侧问题 |

### H2 的决断实验（不需要写代码）

在「发送自定义 JSON」输入框里贴下面这段（**不带 action，按 import 处理**），点发送：

```json
{"courses":[{"name":"自检课","day":1,"time":"08:00 - 08:45"}]}
```

解读：

| 手环课表变化了吗 | 结论 |
|---|---|
| **变了** | 下行通道是通的 → 问题在 **EV 对 `ping`/`export` 的处理**（H2），或 EV 根本没在前台/没常驻（H1） |
| **没变** | 下行通道不通 → 问题在手机 → 手环这一段（H3 方向） |

> ⚠️ 这个 import 会**覆盖手环当前课表**（EV 侧写盘前会自动备份到 `astrobox_sync_backup`）。如果手环上有你不想丢的数据，先用 EV 自带的备份功能导一份，或先跳过这一步。

### 建议的动作顺序

1. **先在手表上打开一次 EV 课程表**（让它跑 `onCreate` 注册接收器），保持它在前台，然后立刻在手机上点「5 发送 PING」。
2. 还不行 → 点「拉起手表 EV 应用」再发（uri 默认填的是包名，可改，但它属于「应用自定义」，EV 目前没声明任何自定义 uri，所以未必生效）。
3. 还不行 → 做上面的 import 决断实验，二分定位是「下行不通」还是「EV 不认 ping/export」。

> 参考：AstroBox 插件当年的复盘结论是「平台支持、EV 支持、通道一直是通的，问题全在插件自己」。我们这边已经把插件踩过的坑（信封解包、`unwrap_envelope` 误剥 `data`）绕开了 —— APK 侧直接拿 `onMessageReceived` 的原始字节，不做多余剥壳。

---

## 十八、v0.3.1：修掉「回包会被吃掉」的隐患 + 零风险下行验证

### 关键修复：回调跑在 Binder 线程上

`OnMessageReceivedListener.onMessageReceived` 是 **AIDL 回调，运行在 Binder 线程**。而 v0.3.0 的代码里，这个回调做的第一件事是 `logView.append(...)` —— 在非 UI 线程操作 `TextView` 会抛 `CalledFromWrongThreadException`。

后果非常隐蔽：**回包其实到了，但死在「打日志」那一行，界面上一条都不留，看起来就像"根本没回包"** —— 和 AstroBox 插件当年 `push_log()` 死锁「死在打日志那一行之前」是同一种死法。**这正是「回复的信息被屏蔽/丢掉」的典型形态。**

v0.3.1 修复：
- 所有日志统一走 `runOnUiThread`
- 回调里**第一件事就是无条件记录「我收到了」**，再做任何后续处理

### 回包原样保留（对应「回复信息被丢掉」）

| 项 | v0.3.0 | v0.3.1 |
|---|---|---|
| 记录内容 | 只有 UTF-8 文本 | **len** + **UTF-8 文本** + **HEX**（前 512 B） |
| 时机 | 解析之后 | **先记「收到」，解析是后话** |
| 计数 | 无 | TX/RX 计数；连续 3 次 0 回包自动提示下一步 |

> 这条对应插件 bug #3 的教训：**不要让"解析成功/失败"决定"有没有收到"**。先留痕，再解释。

### 新增：手表通知 —— 零风险验证「手机 → 手环」

`NotifyApi.sendNotify(nodeId, title, msg)` 让**手环弹一条通知**，**表端应用无感知**、完全不经过 EV 的 interconnect。

| 现象 | 结论 |
|---|---|
| 手环弹出通知 | 「手机 → 手环」链路是通的 → 问题在 EV 侧的收发 |
| 没弹 | 这一段本身就不通 → 与 EV 无关 |

**它不需要动任何课表数据**，比 import 实验安全得多。顺带把权限申请改成 `DEVICE_MANAGER + NOTIFY` 一起要。

### 新增：PING 重试 ×3

手环侧可能处于冷启动/唤醒窗口，单次发送容易丢（插件的复盘里「重试式 ping」也是遗留待办）。

### 对照测试素材（判定平台是否支持这条官方通道）

| 素材 | 位置 |
|---|---|
| 官方 demo 的 rpk（15,781 B） | `tools/xms-demo.rpk`（已下载） |
| 官方 demo 的 APK（8.3 MB） | `open-vela/packages_apps` → `wearable/interconnect_image_demo/app-debug.apk` |
| 官方 demo 的 Android 源码 | 同目录 `android_program/XMS Wearable Demo.zip`（22.3 MB） |

> 网络拉 GitHub raw 只有 ~30 KB/s，22 MB 的 zip 不现实。若要跑官方 demo 对照，`app-debug.apk` 建议用 GitHub 网页直连下载。

---

# 第五部分：通道打通 + 课表管理（2026-09-26 21:00）

## 十九、真机实测：ping / export 双向全通

```
20:42:08  >> TX #3  {"action":"ping"}
20:42:08  << RX #1  {"ok":true,"action":"ping","pong":true,"versionName":"1.6.103","versionCode":932}
20:42:20  >> TX #5  {"action":"export"}
20:42:21  << RX #3  {"ok":true,"action":"export","version":1,
                     "scopes":["schedule","profile","homepage","appearance","version"],
                     "data":{ "schedule":[...], "nickname":"123",
                              "versionName":"1.6.103","versionCode":932,
                              "baseFontSize":48,"homepageTemplate":"default","homepage":{...} }}
```

> **昵称 = `123`，版本号 = `1.6.103`（code 932）。**
> 一开项目时定的"第一步：只读一下昵称 / 版本号"——**达成**。

## 二十、为什么之前 0 回包、v0.3.1 就通了

v0.3.0 的 `onMessageReceived` 是 **AIDL 回调，跑在 Binder 线程**。而它做的第一件事是 `logView.append(...)` —— 在非 UI 线程操作 `TextView` 会抛 `CalledFromWrongThreadException`。

结果：**回包其实到了，但死在「打日志」那一行，界面上一条都不留，看起来就像"通道不通"**。

| | 插件当年（Rust/wasm） | 我们 APK（Java） |
|---|---|---|
| 死法 | `push_log()` 内部重复加锁 → **死锁** | Binder 线程碰 UI → **抛异常** |
| 共同点 | **都死在"把回包记下来"这一步之前** → 与"根本没收到"表象完全一致 | |

v0.3.1 起：日志统一 `runOnUiThread`，且**先无条件留痕**（len + HEX + UTF-8）再谈解析。

> 可复用教训（与插件复盘完全一致）：**不要让"处理成功与否"决定"有没有收到"的可见性。先留痕，再解释。**

## 二十一、调用顺序确认（缺一不可）

```
2 查询设备+授权（拿 nodeId）  →  4 注册监听  →  发消息
```

日志里 20:41:41 与 20:41:49 两次 export 都是**在注册监听之前**发的 → 0 回包；20:42:02 注册完，20:42:08 立刻通。

## 二十二、v0.4.0：从"探针"升级为课表管理工具

| 功能 | 说明 |
|---|---|
| **结构化解析** | export 回包自动提取打印：昵称 / 版本 / 字号 / 首页模板 / 每天几节 / 总节数 |
| **导出到文件** | 把 export 的完整 JSON 存到「下载/EVSync/ev-export-<时间>.json」 |
| **从文件导入(覆盖)** | 系统文件选择器 → 解析 → **弹确认框** → 发 `import` |
| **改昵称** | `{"action":"update_settings","payload":{"nickname":"…"}}` |
| 拉起手环 EV | `launchWearApp`（uri 直接用包名，**实测可打开 EV**） |

### ⚠️ 一个会让人白忙一场的数据转换坑（v0.4.0 已处理）

**EV 的 `export` 产出是「格式 A」（按天分组 `{day, classes:[…]}`），但 `import` 只认「一条课一个对象」。**

直接把 export 的 JSON 回灌给手环 → 每一项因为顶层没有 `name` / `time`，会被 `syncToFormatA()` **整批跳过** → 回包 `{"ok":false,"reason":"convert empty"}`，导入 0 条。

所以 v0.4.0 在导入时加了 `flattenFormatA()`：把格式 A 摊平成扁平课程数组，再封装成
`{"action":"import","payload":{"courses":[{name,day,time,teacher,location,notes}…]}}`。

## 二十三、当前实测基线（可用于回归）

| 项 | 值 |
|---|---|
| 手环 | 小米手环 10 Pro（nodeId `2137618976`） |
| 手环上 EV 版本 | `1.6.103` / code `932` |
| 昵称 | `123` |
| 课表 | 5 天共 **21 节**（一~四各 4 节，周五 5 节） |
| 字号 / 首页模板 | `48` / `default` |
| 权限 | `data_manager` + `notify` 均已授权 |
| 下行验证 | `sendNotify status=0 success=true` |
