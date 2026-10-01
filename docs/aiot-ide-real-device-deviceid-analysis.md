# AIoT IDE 真机连接：设备 ID 判定与连接前提分析

> 场景：在小米 **AIoT IDE** 的「模拟器 → 设备管理 → 真机 → 连接」里，需要输入一个 `设备Id`。
> 拿到的候选值是手环生成的激活链接里的 `deviceId`：
> `/activate.html?deviceId=4c524543f4d7e7ed1b68c52cbab1b819&m=ap&p=Xiaomi%20Smart%20Band%2010%20Pro&o=0&v=1200&t=band&s=rect&w=336&h=480&a=2&l=zh&r=1.6.59&c=g`
> 要回答三个问题：① 这个值是不是要填的设备 ID？② `/activate.html` 是什么路径？③ 只开蓝牙能不能连上？
> 日期：2026-10-01

---

## 一、一句话结论

1. **是的，`4c524543f4d7e7ed1b68c52cbab1b819` 就是要填的设备 ID** —— 它和 EV 手环端 `@system.device.getDeviceId()` 是**同一个值**，也是 AIoT IDE 真机连接要匹配的带内设备标识。
2. `/activate.html` 不是「蓝牙连接」路径，而是 **EV 课程表（手环快应用）的高级版激活/渠道归因网页**，本仓文档也能佐证（见第四节）。
3. **只开蓝牙连不上。** 蓝牙只是最底层链路之一，还必须满足：手环与「小米运动健康」配对保持连接 + 手环开启开发者/互联互通能力 + 频道与对端约束，AIoT IDE 才能真正连上真机（详见第五节）。

---

## 二、这个 deviceId 是不是真机面板要填的？

### 2.1 它是「哪个」设备 ID —— 来源唯一

EV 手环端读取设备 ID 的唯一来源是系统接口：

```540:610:tom/class/class/src/src/pages/activation/activation.ux (示意，本仓外的项目结构)
device.getDeviceId({ success: function(data){ ... raw = data.deviceId ... } })
```

判定逻辑（代码实证）：

| 判定项 | 值 | 结论 |
|---|---|---|
| 长度 | 32 个十六进制字符 | 与 Vela `getDeviceId()` 返回格式一致 |
| 前缀 | 无 `uuid-` | 说明是**真机原始 ID**，**不是**「取不到 ID 时的本地回落 UUID」 |
| 来源 | 手环「高级版」页生成激活链接时自动带入 | 与 EV 内部 `deviceId` 同源 |

所以：**在 AIoT IDE 真机面板填 `4c524543f4d7e7ed1b68c52cbab1b819` 是正确的候选值。**

### 2.2 为什么 AIoT IDE 也要这个值

AIoT IDE 的多设备/互联互通调试，和本仓自研 APK 走的是**同一条链路**：`对端(App/IDE) ──AIDL──▶ 小米运动健康 ──BLE──▶ 手环 ──▶ EV 快应用`。链路里的 `nodeId` / 设备标识都以 `getDeviceId()` 为准，所以真机面板的「设备 ID」与 EV 激活链接里的 `deviceId` 是同一个概念、同一个值。

> ⚠️ 提示：不同版本 AIoT IDE 的「真机」面板可能还会要求额外信息（如调试助手 token、开放"互联互通"权限等）。这个 32 位值解决的是「**设备标识对齐**」这一步，不代表其它前提全部满足。

---

## 三、`deviceId` 这一长串参数的含义（逐项）

这段 URL 是 EV 手环端拼给网页渠道追踪 + 激活用的，`deviceId` 只是第一个参数，后面是设备信息与渠道参数：

| 参数 | 示例值 | 含义 |
|---|---|---|
| `deviceId` | `4c524543…b819` | 手环设备 ID（`getDeviceId()`），**你要关注/要填的就是它** |
| `m` | `ap` | method/入口渠道，标识从网页/ APK 打开 |
| `p` | `Xiaomi Smart Band 10 Pro` | 产品型号 |
| `o` | `0` | 系统/工厂类型序号 |
| `v` | `1200` | 平台版本（minPlatformVersion） |
| `t` | `band` | 设备类型：手环 |
| `s` | `rect` | 屏幕形状：矩形（rect） |
| `w` / `h` | `336` / `480` | 屏幕分辨率 336×480 |
| `a` | `2` | 扩展/版本辅助位 |
| `l` | `zh` | 语言：中文 |
| `r` | `1.6.59` | EV 快应用版本号 |
| `c` | `g` | 渠道标识（channel） |

> 结论：**这些后面的参数都不是连接用的**，只有 `deviceId` 与真机连接相关；其余是设备信息 + 版本 + 渠道归因，用于服务端鉴权与统计。

---

## 四、`/activate.html` 到底是什么路径（不是蓝牙路径）

`/activate.html` 是 **EV 高级版「4 位兑换码 → 一键激活」网页**的一部分，全链路属于「**激活/渠道**」，与「蓝牙连接」无关。由本仓文档 `docs/activation-fast-flow-analysis.md`、`docs/apk-fast-activate-flow-analysis.md` 可确认：

- 手环端激活页（`activation.ux`）拼出 ` ACTIVATION_URL = /activate.html?deviceId=…`，手机扫码打开 → 输入 4 位兑换码 → 网页调 `POST /api/activate {deviceId, redeemCode, deviceInfo}` → 后端按 deviceId 生成 **18 位激活码** 返显 → 再写回手环。
- 一码一机由服务端保证：`used_device_id`（deviceId 的 sha256 hash）比对；同设备复用放行、跨设备拒绝。
- `deviceId` 在这里的职责 = **给服务端做「这台手环」的唯一绑定键**。它本身不负责「连接」——连接走的是蓝牙/互联互通链路，网页不参与。

因此：**从这个链接里抠出 `deviceId` 填进 AIoT IDE 是正确的用法；但把这个链接当成「蓝牙连接入口」是误解**。

---

## 五、只开蓝牙能连上吗？—— 不能，缺三样东西

真机连接符合本仓已验证的互联互通链路：

```
AIoT IDE/自研APK ──AIDL(跨进程)──▶ 小米运动健康 ──BLE──▶ 手环 ──▶ EV 快应用
```

「蓝牙开着」只解锁了中间 `──BLE──` 这一小段，远远不够。**必须同时满足：**

| # | 前提 | 说明 |
|---|---|---|
| 1 | **手机蓝牙已开**【最基础】 | BLE 通信的物理层，缺则全无 |
| 2 | **手环已与「小米运动健康」/「小米穿戴」配对并保持连接** | AIoT IDE/自研 APK 本身**没有**主动"连接手环"的能力，它只能经健康 App 做桥，拿 `nodeId` 要靠这个 App |
| 3 | **手环开启开发者模式 / 互联互通（interconnect）能力** | 手环侧要开放互联互通（对应 `manifest.json` 的 `features: system.interconnect`）；未开则对端查不到、注册监听失败 |
| 4 | （按需）**包名 + 签名一致** | 若要在手环上唤起特定快应用（如 EV）并与其通信，需满足官方「快应用与三方应用包名 + 签名一致」的硬前提；仅做"链路体检"可不涉及 |

> 端口/开发者工具开启与否、是否用同一 Wi-Fi，取决于你用的 AIoT IDE 具体版本；但「蓝牙 + 健康 App 在连 + 手环开互联互通」这**三条是通用硬前提**，缺任一都连不上真机。

---

## 六、实际操作建议（连接顺序）

1. **填对的 ID**：`4c524543f4d7e7ed1b68c52cbab1b819`。
2. **开机状态**：确认手机蓝牙打开，手环与「小米运动健康」已配对、处于已连接状态（App 内显示已连接）。
3. **开能力**：手环设置→开发者选项，打开「开发者模式 / 互联互通调试」（对应 EV 依赖的 `system.interconnect`）。
4. **在 AIoT IDE 真机面板**输入上一步的 `deviceId`，发起连接；观察面板状态。
5. **验证链路**（参考 `docs/interconnect 打通经验速查.md` 的排查顺序）：先查端口/服务端到没到、再查 `nodeId` 有没有（手环连上了吗），最后才发消息——**先留痕，再解释**。

---

## 七、与已有结论的交叉印证

| 本条结论 | 印证来源 |
|---|---|
| `deviceId` 来自 `@system.device.getDeviceId()`，非回落 UUID | `docs/activation-fast-flow-analysis.md`「设备 ID」行 |
| `/activate.html` 是激活/渠道页 | `docs/apk-fast-activate-flow-analysis.md` 二/三节 |
| 真机链路靠小米运动健康做 BLE 桥、SDK 无直连能力 | `docs/interconnect 打通经验速查.md` 二、三节 |
| 互联互通需要 `system.interconnect` 能力 | `docs/interconnect_image_demo …分析.md` 三节 |

---

## 八、风险与不确定性说明

- 本文对「AIoT IDE 真机面板」的描述基于 Vela/FAST 统一的 `getDeviceId()` + 互联互通链路推导得出，**具体 UI 文案与端口要求以当前 AIoT IDE 版本为准**。
- 若面板除 `设备Id` 外还需「调试 token / 订阅码」等，请按官方 AIoT IDE 文档补充，不在本结论范围内。
- 跨设备（另一台手环）的 `deviceId` 会不同：标签里的 32 位值仅对应**这台**「小米手环 10 Pro」，换设备需重新获取。