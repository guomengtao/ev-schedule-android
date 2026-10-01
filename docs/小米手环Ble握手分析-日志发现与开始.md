# 小米手环 BLE 握手分析：日志发现与开始

> 本文档记录分析小米手环 9 NFC 版私有 BLE 认证握手过程中，从手机端获得的日志路径、设备身份信息与连接状态机，作为后续握手逆向的起点。

## 〇、目标设备身份信息（握手参数）

分析目标为 **小米手环9 NFC版**，身份参数已确认：

| 字段 | 值 |
|---|---|
| did（设备ID） | `913024617` |
| 序列号 SN | `55452/DY**********69`（中间被小米脱敏） |
| 型号 model | `miwear.watch.n66nfc` |
| 设备类型 | `bracelet` |
| 固件版本 | `3.1.32` |
| authkey | `e2bfe55361716796bcde1b45749db7a9` |
| MAC | `**:**:24:9D:93`（系统脱敏，BandAuthProbe 实时扫描可得完整值） |

## 一、日志文件路径（手机端）

用户提到的 `/storage/emulated/0/Download/wearablelog` **不存在**。真正的日志由小米运动健康（`com.mi.health`）自身实时写入，路径：

```
/storage/emulated/0/Android/data/com.mi.health/files/log/
```

| 文件 | 大小 | 关注点 |
|---|---|---|
| `XiaomiFit.device.log` | 4.9 MB | 设备连接 / 认证 / 同步核心日志（重点） |
| `XiaomiFit.main.log` | 7.0 MB | 主流程日志 |
| `Transfer.device.log` | 445 KB | 设备数据传输日志（`Device-Sync`） |
| `XiaomiFit.pushservice.log` | 198 KB | 推送服务 |

> 注意：这些日志 **实时写入并轮转**（`.bak.1` 为旧档）。分析前应先 pull 一份快照，避免被覆盖。

### 本地已保存的分析用副本

| 原文件 | 本地副本 |
|---|---|
| `Transfer.device.log` | `bandauth/logs/Transfer.device.log` |
| `XiaomiFit.device.log` | `/tmp/mifit_logs/XiaomiFit.device.log` |
| `XiaomiFit.main.log` | `/tmp/mifit_logs/XiaomiFit.main.log` |

### 相关目录

- `com.xiaomi.xms.wearable.demo`：`/storage/emulated/0/Android/data/com.xiaomi.xms.wearable.demo/`（仅 cache，已空）
- `devicelog`：`.../files/devicelog/`（空目录）

## 二、手环身份信息（来自日志 reportDeviceActive）

日志第 ~4207 行连接成功后的 `reportDeviceActive` 完整泄露：

| 字段 | 值 |
|---|---|
| 设备名 | 小米手环 9 NFC 版 |
| 产品型号 | `miwear.watch.n66nfc` |
| 设备类型 | `bracelet`（手环） |
| 产品 ID（did） | `913024617` |
| 固件版本 | `hardware_version=3.1.32` |
| 序列号 | `sn=55452/DY**********69` |

## 三、连接状态机（XMS 私有协议）

日志可见完整生命周期：

```
STATUS_NOT_CONNECTED(1)
  → STATUS_CONNECTING(2)
  → STATUS_CONNECTED(3)
  → STATUS_CONNECT_STOP(4)
```

- 认证速度极快：`CONNECTING → CONNECTED` 仅约 **0.32 秒**（例：13:52:05.355 → 13:52:05.681）。
- Mi Fit 通过 **XMS 私有协议**（`com.xiaomi.xms.wearable.WearableXmsService`）连接手环，约每 5 分钟同步一次。

## 四、关键组件线索

日志中反复出现的 XMS 组件（用于后续定向逆向）：

- `com.xiaomi.xms.wearable.WearableXmsService` —— 穿戴 SDK 主服务
- `com.xiaomi.xms.wearable.extensions.DeviceModelExtKt` —— 设备模型扩展
- `com.xiaomi.xms.wearable.utils.UtilsKt` —— 工具类
- `NetProxyManager` / `NetProxyComponent` —— 连接成功后的网络代理（JNI）
- `MiLinkComponent` —— MiLink 客户端
- `DeviceManagerRemoteImpl` —— 设备状态管理（RPC）

## 五、握手阶段目标

下一阶段需要定位以下信息以还原私有认证握手：

1. **XMS 使用的私有 GATT 服务/特征 UUID**（通常形如 `0000FE0x-...` / `000xFE7D-...` 之类的小米私有特征）。
2. **认证握手命令字节流**（connect→connected 之间发出的 XMS 私有指令 + 载荷）。
3. **authkey 如何被加密写入**（与 AES/自定义算法结合，产生 token 或握手 SKey）。
4. 结合已解码的 HCI 日志（`/tmp/btsnoop_decomp.bin`，曾定位 MAC `6F:8F:3C:1C:DC:7B`）交叉验证链路层字节。

### 已确认的认知

- 小米手环 9 NFC 版强制走认证；任何未完成认证的连接会被以 `GATT_AUTH_FAIL` / `status=133` 或 `0x0100` 拒绝。
- 标准 Android `createBond()` + `setPin()` 无法绕过硬环（华为 user 版实测 `BT_STATUS_AUTH_FAILURE`），因为认证发生在链路层之上的私有 GATT 握手。

## 六、本机蓝牙状态（关键）

`dumpsys bluetooth_manager` 显示本机已绑定 **两个** 小米手环：

| 绑定设备 | 类型 |
|---|---|
| `Xiaomi Smart Band 9 B885` | 手环 9（DUAL，经典+LE） |
| `Xiaomi Smart Band 10 Pro 3ADC` | 手环 10 Pro |

而 `6f:8f:3c:1c:dc:7b` 是先前直连测试时的**随机私有地址**，未绑定（`reason=256`=AUTH_FAIL），不是 Mi Fit 当前使用的手环 9 地址。

> 注：demo（`com.xiaomi.xms.wearable.demo`）曾作为 BLE 高占用 App 出现在 dumpsys 中。

## 七、GATT 特征 UUID（反编译 Mi Fit 初现）

对 `com.mi.health`（150MB APK）反编译提取的特征：

| UUID | 用途 |
|---|---|
| `0000FE95-0000-1000-8000-00805F9B34FB` | **小米私有特征**（认证/数据传输），位于 classes11.dex |
| `00002A19-0000-1000-8000...` | 电池电量（标准） |
| `00002A05-0000-1000-8000...` | 当前时间（标准） |
| `00002902-0000-1000-8000...` | 客户端特征配置（CCCD，sub 通知用） |
| `0000180F-0000-1000-8000...` | 电池服务（标准） |
| `00001801-0000-1000-8000...` | Generic Attribute（标准） |

> `FE95` 是小米可穿戴设备的**标志性私有 UUID**，认证握手即在此特征上交换命令帧。持握手算法在 `classes11.dex` 与 `libdevice-encrypt.so` / `libcentralclient.so`（native）中。

## 八、认证握手核心类（反编译已定位）

在 `com.mi.health` APK 的 dex 中发现华米/小米手环认证链路关键类与标志：

| 项 | 详情 |
|---|---|
| 认证模块 | `com.huami.bluetooth.profile.channel.module.auth.AuthModule` / `AuthModule2` |
| 认证密钥 | `huamiAuthKey`（即 authkey）、`authKeys`、`AuthKeys` |
| AES 密钥 | `mAESKey='`、`deriveAESKey`、`exportAESKey`、`getCurrentAesKeyOrToken: expireAt=` |
| 加密算法 | `AES/ECB/NoPadding`（`com.xiaomi.wearable.common.util.encrypt.AESUtils`） |
| 访问凭证 | `com.xiaomi.hm.health.bt.model.HMAccessInfo` |
| 传输包 | `com.huami.bluetooth.profile.channel.module.DeviceHttpRequest`/`DeviceHttpResponse` |
| 握手消息 | `com.xiaomi.mi_connect_service.proto.HandShakeProto`（HandShakeMessage） |
| Handshake ACK | `handShake:send ackString ackEvent:` |
| 传输通道 | `com.huami.bluetooth.profile.channel.module.settings/...`（SettingsGroupType 等业务） |
| 旧/新认证 | `AuthModule`（旧）与 `AuthModule2`（新，手环 9 系列） |

> 推断认证机制：**authkey (`huamiAuthKey`) 经 `AES/ECB/NoPadding` 派生 AES 会话密钥 → 在 `0000FE95` 私有特征上完成 `HandShake`（握手帧 + ACK）→ 认证成功后进入业务通道**。`HandShakeProto` 为 proto 序列化定义。

### 设备实测服务（`dumpsys bluetooth_manager` GATT Handle Map）

Mi Fit 连接手环 9（`...:24:9D:93`）时的私有服务：

| Handle | 服务 UUID |
|---|---|
| 40 | `0000FE35-0000-1000-8000-00805F9B34FB` |
| 51 | `0000046A-0000-1000-8000-00805F9B34FB` |

> 认证在 `FE35` 服务上进行，数据特征为 `FE95`。已见认证状态码：`AUTH_ERROR_WRONG_KEY` / `AUTH_CONFIRM_FAILED` / `AUTH_ERROR_GET_INFO_FAILED` / `AUTH_ERROR_NOKNOCK` / `AUTH_ERROR_TIMEOUT` 等。

## 九、后续行动计划

- [ ] 抓取 Mi Fit 连接手环瞬间的高质量 HCI 日志（PC 侧 Nordic nRF Sniffer 或 root 设备 btsnoop）
- [ ] 反编译 `com.mi.health` 中的 XMS SDK，定位私有特征 UUID 与握手算法
- [ ] 在 BandAuthProbe 中按抓到的字节流复现代专属 GATT 认证握手

## 十、已确认的认证握手地图（阶段结论）

**已确证**（通过 APK 反编译 + 设备实测双验证）：

1. **服务**：认证在私有服务 `0000FE35-0000-1000-8000-00805F9B34FB`（`MI_SERVICE_UUID` / `UUID_SERVICE_MILI_SERVICE`）上进行。
2. **特征**：私有数据特征 `0000FE95-0000-1000-8000-00805F9B34FB` 承担认证帧读写与通知。
3. **密钥**：`getHuamiAuthKey()` 获取 authkey；`mAESKey` 会话密钥；`deriveAESKey`/`exportAESKey` 派生。
4. **加密**：`AES/ECB/NoPadding`（`com.xiaomi.wearable.common.util.encrypt.AESUtils`）。
5. **握手**：`HandShakeProto$HandShakeMessage`（HandShakeMessage）—— proto 序列化的握手帧；`handShake:send ackString ackEvent:` 负责 ACK 交换。
6. **认证模块**：`AuthModule`（旧）/ `AuthModule2`（新，手环 9 系列）。

**待解**（需 HCI 抓包获取实时字节）：

- authkey 到会话 AES key 的具体派生算法（输入：authkey + randomMac + 设备信息）。
- 握手帧/MAC 校验的精确字节布局与命令 opcode。
- `FE95` 特征是否需先 write CCCD 订阅、以及 MTU/分段规则。

> 下一步核心动作：**用 nRF Sniffer 或 root 设备 HCI 日志，在 Mi Fit 绑定时抓取 `FE95` 上 authkey 握手那一刻的原始 ATT 字节**后，据其复现 BandAuthProbe 中的认证写入逻辑。