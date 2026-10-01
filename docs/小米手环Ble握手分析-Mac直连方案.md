# 小米手环 BLE 认证握手分析 —— Mac 直连方案

## 一、为什么要换思路

当前方案是在 Android 手机上通过独立 App（BandAuthProbe）直接连接手环，遇到的核心问题：

| 问题 | 详情 |
|---|---|
| **私有特征隐藏** | 手环9 在未认证状态下只暴露标准特征（2a00~2a03），FE95/FE90 鉴权通道不可见 |
| **SMP 绑定超时** | 手环不响应反射调用 `createBond(TRANSPORT_LE)` 的配对请求，超时后回退 BOND_NONE |
| **小米运动健康争抢连接** | `com.mi.health` 后台持续尝试重连，需反复 force-stop，且手环可能只信任已配对设备 |
| **Android 权限碎片化** | Android 8/9/10+ 各个版本的蓝牙权限模型不一致，反射调用隐藏 API 在不同机型表现不同 |
| **调试效率低** | 每次改代码需编译→签名→安装→手动点击按钮→看日志，循环周期 >2 分钟 |
| **ADB 多设备干扰** | 本机同时连着手机和模拟器，`adb shell input tap` 经常点错目标 |

**结论：在 Mac 上直接开发 BLE 客户端，绕过 Android 所有限制，是最快的验证路径。**

---

## 二、Mac 直连的技术可行性

### 2.1 硬件能力

Mac 的蓝牙芯片（Broadcom/Apple Silicon）完整支持 BLE 4.0/5.0，可以：
- 扫描附近 BLE 外设
- 发起 GATT 连接
- 枚举服务和特征
- 读写特征值、订阅通知
- 发起 SMP 配对

小米手环作为 BLE Peripheral，Mac 作为 BLE Central，完全合规。

### 2.2 关键前提：手环必须处于可连接状态

手环同一时间只能与一个 Central 保持 BLE 连接。如果手机上的小米运动健康或 BandAuthProbe 正连着 `04:34:C3:06:B8:85`，Mac 扫描不到它（不广播）。

**操作步骤：**
```bash
# 1. 关闭手机蓝牙，或
# 2. 在手机上强制停止小米运动健康
adb -s BTF4C17222009588 shell 'am force-stop com.mi.health'
# 3. 手环重启后进入可被发现状态
```

---

## 三、技术方案对比

### 方案 A：Python + bleak（推荐）

| 维度 | 评价 |
|---|---|
| 开发速度 | ⭐⭐⭐⭐⭐ 极快，几十行代码即可扫描+连接 |
| 调试体验 | ⭐⭐⭐⭐⭐ 交互式 REPL，print 即可看日志 |
| 社区支持 | ⭐⭐⭐⭐ bleak 是 asyncio 生态最活跃的 BLE 库 |
| 打包分发 | ⭐⭐⭐ py2app/cx_Freeze 可打包为独立 .app |
| 风险 | bleak 在某些 Mac 上首次运行需要辅助功能权限 |

**核心代码骨架：**
```python
import asyncio
from bleak import BleakScanner, BleakClient

BAND_MAC = "04:34:C3:06:B8:85"
AUTHKEY = bytes.fromhex("e2bfe55361716796bcde1b45749db7a9")

async def main():
    # 1. 扫描（手环不广播时可跳过，直接用 MAC 连接）
    devices = await BleakScanner.discover()
    for d in devices:
        print(d.address, d.name)

    # 2. 连接
    async with BleakClient(BAND_MAC) as client:
        print("connected:", client.is_connected)

        # 3. 枚举服务
        for svc in client.services:
            print(f"svc  {svc.uuid}")
            for ch in svc.characteristics:
                props = ch.properties
                print(f"  ch  {ch.uuid}  props={props}")
                # 4. 自动读取所有可读特征
                if "read" in props:
                    val = await client.read_gatt_char(ch.uuid)
                    print(f"    read -> {val.hex()}")

asyncio.run(main())
```

### 方案 B：Swift + CoreBluetooth

| 维度 | 评价 |
|---|---|
| 开发速度 | ⭐⭐⭐ 原生 API 较为冗长，回调嵌套 |
| 调试体验 | ⭐⭐⭐⭐ Xcode 调试器强大 |
| 权限管理 | ⭐⭐⭐ 需要在 Info.plist 声明蓝牙权限 |
| 分发 | ⭐⭐⭐⭐⭐ Xcode Archive 直接出 .app |
| 风险 | CBCentralManager 是 delegate 模式，异步流程不如 async/await 清晰 |

### 方案 C：Node.js + noble / @abandonware/noble

| 维度 | 评价 |
|---|---|
| 开发速度 | ⭐⭐⭐⭐ |
| 依赖 | 需要 Xcode CLI tools 编译 native 模块 |
| 稳定性 | ⭐⭐⭐ noble 维护断断续续 |
| 不推荐 | macOS 上有更简洁的 bleak |

### 方案 D：Rust + btleplug

| 维度 | 评价 |
|---|---|
| 性能 | ⭐⭐⭐⭐⭐ |
| 开发速度 | ⭐⭐ 学习曲线陡峭 |
| 适用场景 | 需高性能或后期嵌入到系统服务时 |

---

## 四、推荐方案：Python + bleak

### 4.1 环境准备

```bash
# 创建虚拟环境
python3 -m venv band-ble
source band-ble/bin/activate

# 安装依赖
pip install bleak

# 验证（扫描附近设备）
python3 -c "
import asyncio
from bleak import BleakScanner
async def scan():
    for d in await BleakScanner.discover(): print(d.address, d.name)
asyncio.run(scan())
"
```

### 4.2 分阶段目标

#### 阶段 1：验证连接（10 分钟）

1. 确保手环不与其他设备连接（关闭手机蓝牙）
2. 确保手环处于亮屏/可发现状态
3. 运行扫描脚本，确认能看到 `Xiaomi Smart Band 9`
4. 直接连接，枚举所有服务和特征
5. 对比 Mac 上枚举结果与 Android 上的差异

#### 阶段 2：尝试读取私有特征（20 分钟）

1. 连接后列出所有 service UUID 和 characteristic UUID
2. 特别关注：
   - `0000fee1-...`（小米私有服务）
   - `0000fe95-...`（认证特征）
   - `0000fe90-...`（通知特征）
   - `0000ff01/ff02/ff03-...`（数据通道）
3. 如果这些特征可见，直接尝试读取
4. 如果不可见，进入阶段 3

#### 阶段 3：SMP 配对（关键，30 分钟）

Mac 上的 SMP 配对比 Android 简单得多，因为：
- macOS CoreBluetooth 不会像 Android 那样弹出系统配对对话框
- bleak 可以直接调用配对 API（如果暴露了的话）
- 也可以通过底层 HCI 命令发起配对

备选方案：如果 bleak 不支持 SMP，用 PyObjC 直接调用 CoreBluetooth 的配对。

#### 阶段 4：复现认证握手（40 分钟）

一旦能连接并能看到 FE95 特征，按以下流程操作：

```
Central(Mac)                        Peripheral(Band9)
     |                                      |
     |--- Connect GATT -------------------->|
     |<-- Services/Characteristics ---------|
     |--- Enable CCCD on FE90 ------------>|
     |--- Write Init frame to FE95 ------->|
     |<-- Band responds with challenge ----|
     |--- Write SendKey frame to FE95 ---->|   (AES/ECB encrypted)
     |<-- Band responds ACK ---------------|
     |--- 连接进入全功能模式 --------------|
```

### 4.3 认证帧格式（待逆向确认）

基于反编译 `com.mi.health` 的初步推断：

```
Init 帧:
  02 00 00 00 A0          (固定 5 字节)

SendKey 帧:
  02 LL                   (type=02, len=LL)
  [authkey 16 字节]        (e2bfe55361716796bcde1b45749db7a9)
  [随机/校验 4 字节]       (待确认)
```

加密算法：`AES/ECB/NoPadding`

---

## 五、与 Android 方案对比

| 维度 | Android App | Mac Python |
|---|---|---|
| 连接手环 | 需要 force-stop Mi Fit | 直接连（手环需断开手机） |
| 服务枚举 | `status=133` 认证失败 | 可能更多特征可见 |
| SMP 配对 | 反射隐藏 API，超时 | CoreBluetooth 原生支持 |
| 日志查看 | 导出文件或 uiautomator | `print()` 直接看 |
| 开发循环 | 编译→签名→安装（>1分钟） | 修改代码→运行（秒级） |
| 权限问题 | Android 版本碎片化 | macOS 权限模型简单 |
| 最终产物 | APK（手机上用） | Python 脚本/独立 App |

---

## 六、实施建议

### 优先级：**立即尝试方案 A（Python + bleak）**

理由：
1. **投入产出比最高**：30 行代码就能验证手环是否在 Mac 上可见
2. **快速试错**：如果 Mac 也看不到 FE95，说明问题不在 Android，而在手环的认证状态机——需要先走 SMP 配对流程
3. **代码可复用**：Python 脚本后续可直接作为协议逆向的自动化测试工具
4. **无编译等待**：改一行跑一次，调试效率提升 10 倍

### 如果 bleak 不够用

可以降级到直接调用 macOS IOKit/IOBluetooth API（通过 PyObjC），或使用 `lightblue`（跨平台蓝牙库）。极端情况下，可以用 Wireshark + nRF Sniffer 抓取 Mac 与手环之间的 BLE 通信，直接分析原始链路层数据包。

### 环境就绪检查清单

```bash
# 1. 确认 Python ≥ 3.8
python3 --version

# 2. 确认 bleak 可安装
pip3 install bleak

# 3. 确认系统蓝牙可用
system_profiler SPBluetoothDataType | head -20

# 4. 确认手环在附近且未被其他设备占用
#    方法：手机蓝牙关闭 → 手环亮屏 → Mac 扫描
```

---

## 七、后续行动计划

1. ✅ 整理此分析文档
2. ⏭ 环境准备：安装 bleak，验证 Mac 蓝牙可用
3. ⏭ 阶段 1：扫描手环并连接
4. ⏭ 阶段 2：枚举 GATT 服务和特征
5. ⏭ 阶段 3：尝试 SMP 配对
6. ⏭ 阶段 4：发送 authkey 握手帧
7. ⏭ 成功后：将握手流程回写到 Android App