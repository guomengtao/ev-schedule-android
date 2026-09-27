# EV 课程表 ↔ 安卓 APK：interconnect 打通经验速查

> 用途：**以后再遇到连不上 / 收不到回包，先看这份**，不要从头推理。
> 结论日期：2026-09-26（小米手环 10 Pro + EV 1.6.103，真机双向打通）
> 详细背景见 `interconnect_image_demo 上手与自研APK对接分析.md`

---

## 零、一句话结论

**自研 APK 走小米官方穿戴 SDK（`xms-wearable-lib 1.4`）可以直连手环上的 EV 快应用**，不需要 AstroBox、不需要逆向协议栈。但必须同时满足 **包名一致 + 签名一致 + 先注册监听再发消息**。

---

## 一、最小成功公式（4 步）

```bash
# 0. 环境（只需一次）
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 1. 构建
cd apk && ANDROID_SDK_ROOT=$HOME/android-sdk bash apk/build.sh

# 2. 安装（签名没变时可直接覆盖；换签名必须先 uninstall）
~/android-sdk/platform-tools/adb install -r apk/EVSyncProbe.apk

# 3. App 内顺序（缺一不可）
2 查询设备+授权  →  4 注册监听  →  5/6 发消息

# 4. 看日志
~/android-sdk/platform-tools/adb logcat -s EVProbe AndroidRuntime
```

成功长这样：

```
<< RX #1  {"ok":true,"action":"ping","pong":true,"versionName":"1.6.103","versionCode":932}
<< RX #3  {"ok":true,"action":"export","version":1,"data":{"nickname":"123", ...}}
```

---

## 二、三条硬前提（改任何一条都会断）

| # | 前提 | 怎么满足 | 违反后果 |
|---|---|---|---|
| 1 | **包名一致**：APK `applicationId` == 快应用 `manifest.json.package` | 固定为 `com.application.watch.classschedule`。**包名绝不能带版本号** | 连不上；且系统会当成另一个 App，桌面多图标 |
| 2 | **签名一致**：APK 的签名证书 == rpk 的签名证书 | 用 `tom/class/class/sign/{private.pem,certificate.pem}` 签 APK（build.sh 自动找） | `SignatureVerifyFailedException: fingerprint verify failed`，**所有设备侧接口全部失败** |
| 3 | **装了小米运动健康并保持手环连接** | SDK 里**没有**"连接设备"能力，只能 `getConnectedNodes()` 查询 | 拿不到 nodeId，后面全废 |

链路：

```
我们的 APK ──AIDL(跨进程)──▶ 小米运动健康/小米穿戴 ──BLE──▶ 手环 ──▶ EV 快应用
```

---

## 三、踩坑清单（现象 → 根因 → 解法）

### 坑 1：App 一启动就闪退

| 项 | 内容 |
|---|---|
| 现象 | 点开图标闪一下就退出，日志里什么都没有 |
| 根因 | **打 dex 时把 SDK 的 class 当成"库"传给了 d8**，没作为程序输入 → 运行时 `NoClassDefFoundError: com.xiaomi.xms.wearable.Wearable` |
| 解法 | d8 必须把 SDK 的 `.class` / jar 作为**程序输入**，`--classpath` 只是"库"，不会进产物 |
| 证据 | 坏包 `classes.dex` 只有 15.7 KB / 20 个类；修好后 74 KB / **147 个类（其中 126 个是 SDK）** |

### 坑 2：签名校验失败（最容易误判成"通道不通"）

| 项 | 内容 |
|---|---|
| 现象 | `isWearAppInstalled` / `requestPermission` / `addListener` / `sendMessage` **全部**报 `SignatureVerifyFailedException: fingerprint verify failed` |
| 根因 | APK 用了自建 keystore，与 rpk 的证书不是同一把 |
| 解法 | 换成 rpk 的密钥签 APK：`openssl pkcs8` 转 DER → `apksigner sign --key ... --cert ...` |
| 判据 | **包名对不对，看有没有走到这一步**：走到签名校验，说明包名已经对了 |

### 坑 3：发出去 0 回包（最隐蔽，本次真正的元凶）

| 项 | 内容 |
|---|---|
| 现象 | `sendMessage 已受理`，但**一条 `<< RX` 都没有**，且 App 不崩溃 |
| 根因 | `OnMessageReceivedListener.onMessageReceived` 是 **AIDL 回调，跑在 Binder 线程**。代码第一件事是 `logView.append(...)` → 抛 `CalledFromWrongThreadException` |
| 解法 | 日志统一 `runOnUiThread` |
| 教训 | **回包早就到了，只是死在"把它记下来"那一步之前** —— 和插件当年 `push_log()` 死锁是**同一种死法**（插件是死锁，这里是线程错）。 |

> 通用原则（务必刻在心里）：**不要让"处理成功与否"决定"有没有收到"的可见性。先留痕，再解释。**
> 具体做法：收到后**无条件**先打 `len` + `HEX` + `UTF-8`，之后才做 JSON 解析。

### 坑 4：也是 0 回包，但原因是没注册监听

| 项 | 内容 |
|---|---|
| 现象 | 同上 |
| 根因 | 没点「4 注册监听」就发消息；同一 nodeId 只认一次注册 |
| 解法 | 严格顺序：`2 查询设备+授权 → 4 注册监听 → 发消息` |
| 证据 | 本次日志：注册监听前发的 2 次 export 全丢；注册后第一次 ping 立刻回 |

### 坑 5：重复注册

| 项 | 内容 |
|---|---|
| 现象 | `IllegalStateException: you have registered` |
| 解法 | 本地记 `listenerBound` 标志；需要重注册就先 `removeListener(nodeId)` |

### 坑 6：导入成功但课程 0 条（数据形状不匹配）

| 项 | 内容 |
|---|---|
| 现象 | 回包 `{"ok":false,"reason":"convert empty"}`，手环课表没变 |
| 根因 | **EV 的 `export` 产出是「格式 A」（按天分组 `{day, classes:[…]}`），而 `import` 只认「一条课一个对象」**（顶层必须有 `name` + `time`）。把 export 的 JSON 直接回灌 → 每一项都被跳过 |
| 解法 | 导入前 `flattenFormatA()`：把格式 A 摊平成扁平数组，再包成 `{"action":"import","payload":{"courses":[…]}}` |

### 坑 7：d8 崩溃（编译期）

| 项 | 内容 |
|---|---|
| 现象 | `NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`，定位到某个 `MainActivity$N.class` |
| 根因 | **JDK 22 的 javac 产出的 class 文件，R8 解析不了** |
| 解法 | **javac 必须用 JDK 8**（`-source 1.8 -target 1.8 -bootclasspath android.jar`）；d8/apksigner 反过来要 JDK 11+。build.sh 里两个 JDK 分开指定 |

### 坑 8：apksigner 拒收 rpk 的证书

| 项 | 内容 |
|---|---|
| 现象 | `CertificateParsingException: Empty issuer DN not allowed in X509Certificates` |
| 根因 | rpk 证书由小米在线工具生成，**issuer/subject DN 是空的**，JDK 自带的 X.509 解析器拒收 |
| 解法 | `apk/tools/BCSign.java`：把 BouncyCastle 注册为最高优先级 provider，再调 `ApkSignerTool.main` |

### 坑 9：XML 注释里的两个短横线

| 项 | 内容 |
|---|---|
| 现象 | aapt2 报 `not well-formed (invalid token)` |
| 根因 | 注释里写了 `--version-code`，**XML 注释中不允许出现连续的 `--`** |
| 解法 | 注释里别写 `--` |

### 坑 10：git 推不上去

| 项 | 内容 |
|---|---|
| 现象 | HTTPS 推 GitHub：`Empty reply from server` / 443 连不上（75 秒超时） |
| 解法 | `gh auth setup-git` + remote 改成 SSH：`git@github.com:guomengtao/ev-schedule-android.git` |

---

## 四、排查方法论（照做，不要凭感觉改代码）

1. **先证明哪一段不通，再动手改。**
2. 二分手段（从外到内）：

| 手段 | 验证哪一段 | 成本 |
|---|---|---|
| `getServiceApiLevel()` / `ServiceApi` 回调 | 小米运动健康在不在、服务可用吗 | 极低 |
| `getConnectedNodes()` | 手环连上了吗（`nodeId` 有没有） | 极低 |
| `isWearAppInstalled()` / `requestPermission()` | 包名 + 签名对不对 | 极低 |
| **`NotifyApi.sendNotify()`（让手环弹通知）** | **手机 → 手环 整个下行** | 极低、**不碰任何数据** |
| `launchWearApp()`（uri 用包名） | 能不能唤起手环上的 EV | 极低 |
| `{"action":"ping"}` | 双向通道 + EV 版本 | 低 |
| `{"action":"import"}` 塞 1 门课 | EV 到底有没有收到（**看手环课表变没变**） | 会**覆盖**课表，先备份 |

3. **回包一旦出现，先原样留痕**（len/HEX/UTF-8），再谈解析 —— 否则会被"解析失败"伪装成"没收到"。

---

## 五、速查表

### 文件位置

| 东西 | 位置 |
|---|---|
| rpk 签名密钥（**签 APK 必须用这把**） | `tom/class/class/sign/{private.pem, certificate.pem}`，证书指纹 `466a1e83…9a11` |
| 版本号 | `apk/version.env`（每次 build 自动 +1） |
| 产物（历史保留） | `apk/dist/EVSyncProbe-v<版本>.apk` |
| 最新包（固定路径） | `apk/EVSyncProbe.apk` |
| 构建脚本 | `apk/build.sh`（`NO_BUMP=1` 可重出当前版本） |
| 签名工具（空 DN 证书专用） | `apk/tools/BCSign.java` + `apk/tools/bcprov.jar` |

### 常用命令

```bash
# 构建
cd apk && ANDROID_SDK_ROOT=$HOME/android-sdk bash build.sh

# 安装 / 卸载（换签名必须先卸载）
adb install -r apk/EVSyncProbe.apk
adb uninstall com.application.watch.classschedule

# 日志
adb logcat -s EVProbe AndroidRuntime

# 推码（必须用 SSH）
git push origin main
```

### 报文格式

```jsonc
// 手机 → 手环
{"action":"ping"}
{"action":"export"}
{"action":"export","scopes":["schedule","profile","homepage","appearance","version"]}
{"action":"update_settings","payload":{"nickname":"小明"}}
{"action":"import","payload":{"courses":[{"name":"数学","day":1,"time":"08:00 - 08:45"}]}}

// 手环 → 手机
{"ok":true,"action":"ping","pong":true,"versionName":"1.6.103","versionCode":932}
{"ok":true,"action":"export","version":1,"data":{"schedule":[...],"nickname":"...","versionName":"...","baseFontSize":48}}
{"ok":true,"count":30}                       // import 回包
{"ok":false,"reason":"convert empty"}        // 失败
```

- 发送的 `byte[]` 就是 **UTF-8 的 JSON 字符串**（不要用对象序列化）
- **解析时不要无条件剥最外层 `data`** —— export 的业务回包自己就带 `data`（插件当年踩过）

### 关键 API

```java
Wearable.getNodeApi(ctx).getConnectedNodes()                 // 免权限，拿 nodeId
Wearable.getAuthApi(ctx).requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY)
Wearable.getMessageApi(ctx).addListener(nodeId, listener)    // 必须先注册
Wearable.getMessageApi(ctx).sendMessage(nodeId, byte[])
Wearable.getNotifyApi(ctx).sendNotify(nodeId, title, msg)    // 下行验证
Wearable.getNodeApi(ctx).launchWearApp(nodeId, "com.application.watch.classschedule")
Wearable.getNodeApi(ctx).isWearAppInstalled(nodeId)
```

---

## 六、当前实测基线（回归对照用）

| 项 | 值 |
|---|---|
| 手环 | 小米手环 10 Pro，`nodeId = 2137618976` |
| 手环上 EV 版本 | `1.6.103` / code `932` |
| 昵称 | `123` |
| 课表 | 5 天共 21 节 |
| 字号 / 首页模板 | `48` / `default` |
| 权限 | `data_manager` + `notify` 均已授权 |
| 下行验证 | `sendNotify status=0 success=true` |

---

## 七、EV 侧的数据开放边界（守门人模型）

**EV 是守门人**：它决定读什么、回什么、允许改什么。`SYNC_ACCESS` 表：

| 域 | 读 | 写 | 说明 |
|---|:--:|:--:|---|
| `schedule` | 默认 | ✅ | 课表（import 覆盖） |
| `profile` | 默认 | ✅ | 昵称 |
| `homepage` | 默认 | ✅ | 首页设置（**建议读-改-写，别只传部分字段**） |
| `appearance` | 默认 | ✅ | 模板 + 字号（20~76） |
| `version` | 默认 | ❌ | 版本号，**只读** |
| `pinned` | 需显式 `scopes` | ❌ | 钉首页 |
| `auth` | 禁止 | ❌ | 授权状态，永远拿不到 |

> 不开放 = 读不到、也改不掉。这条边界 100% 由手环侧控制。
