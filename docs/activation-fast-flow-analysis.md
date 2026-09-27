# 高级版「4 位兑换码 → 一键激活」流程分析与改造方案

> 目标：用户**只填 4 位兑换码**，网站自动按设备 ID 生成激活码，再**直接写入手环**完成激活，省掉现有"扫两个码 + 手输 18 位"的繁琐操作。
> 日期：2026-09-27
>
> **实现状态（2026-09-27 更新）**：方案已落地。
> - 手环端 EV（commit `b15432c`，rpk 1.6.140）：新增 `get_device_id`、`activate` 两个动作，`import` 支持 `scheduleName`。
> - APK（v0.5.14）：`SyncEngine.getDeviceId()/activate()`、新页 `FastActivateActivity`（高级版一键激活）、`HomepageSettingsActivity`（首页设置）、`DonateActivity`（打赏支持）；Manifest 已加 `INTERNET`。
> - 后端 `/api/activate` 未改（已可用）。

---

## 〇、先纠正一个数字（重要）

用户描述里说"16 位激活码"，**实际是 18 位**（纯数字）。这一点直接影响协议与 UI 设计，必须先对齐：

| 项 | 实际规格 | 出处 |
|---|---|---|
| 激活码 | **18 位纯数字**，格式化写法 `6-6-6`（EV 手环）或 `4-4-4-4-2` | `tom/class/class/src/lib/crypto.js`（`code.length !== 18`）、`app-auth/lib/crypto.js`（`padStart(18,"0")`） |
| 编码内容 | 12 字符 `PPCCCCMMDDDD`：产品ID(2) + 兑换码(4) + 月数(2) + 设备ID后4位(4) | `parseInput12` / `encode` |
| 兑换码 | **4 位** `[A-Z0-9]` | `app-auth/lib/validate.js` `REDEEM_CODE_PATTERN` |
| 设备 ID | EV 取 `@system.device.getDeviceId()`；取不到回落本地 UUID（`uuid-<32hex>`） | `activation.ux` `fetchDeviceId` / `utils/device-uuid.js` |

---

## 一、现状梳理

### 1.1 现有激活流程（手环端 EV）

`src/pages/activation/activation.ux` 是三步式：

1. **购买兑换码** —— 扫码去爱发电买，得到 4 位兑换码。
2. **获取激活码** —— 扫码打开 `https://app-auth.gudq.com/activate.html?deviceId=<设备ID>&m=&p=&r=&c=`，在网页里填 兑换码 + 设备ID，网页调后端换取 **18 位激活码**。
3. **输入激活码** —— 在**手环上用数字键盘手输 18 位**。

校验（手环本地离线完成）：

```540:610:tom/class/class/src/pages/activation/activation.ux
      var deviceIdLast4 = deviceId.substring(deviceId.length - 4)
      if (decrypted.deviceId !== deviceIdLast4) {
        self.verifySuccess = false
        self.verifyResult = '设备ID不匹配'
```

```387:401:tom/class/class/src/pages/activation/activation.ux
  fetchDeviceId() {
    var self = this
    try {
      var device = require("@system.device")
      device.getDeviceId({
        success: function(data) {
          var raw = data.deviceId || ''
          if (!raw || raw === 'NA' || raw === 'unknown' || raw === 'null') {
```

### 1.2 后端现有能力（已具备，几乎不用改）

`app-auth/api/activate.js`：

```
POST https://app-auth.gudq.com/api/activate
body: { "deviceId":"<原始设备ID>", "redeemCode":"<4位>", "deviceInfo":{...} }

成功: { "success": true, "activationCode": "<18位数字>" }
失败: 400 { success:false, error:"..." } / 429（限流，带 Retry-After）
```

内部关键点：

- `validateDeviceId()`：拦 `NA / 0000 / UUID` 之外的无效值 → 返回 `code: DEVICE_ID_INVALID/EMPTY`；
- `validateRedeemCode()`：4 位 `[A-Z0-9]`；
- 一码一机：`auth:redeem:<code>` 记录 `used_device_id`(sha256)，同设备可复用（追加 `auth:activation:<code>:<seq>`），异设备拒绝（NA 设备有次数上限 `NA_USAGE_LIMIT_DEFAULT=5`）；
- 限流：`checkIpRateLimit` / `checkDeviceRateLimit`；
- 激活码生成：`crypto.generateActivationCode(productId, deviceId, months, redeemCode)` → 18 位。

### 1.3 已知安全限制（不是本次引入的）

激活码是**纯数学编码、无签名**，手环离线解码即可，理论上可被伪造：

```127:136:app-auth/lib/crypto.js
// TODO(SECURITY-P4-3): encode currently has NO cryptographic signature.
// ...
//   1. When you can push a watch app update that calls computeChecksum()
```

→ 这是"手环无网络"逼出来的设计。**本次新流程不改变该现状**，但要在文末单列风险与后续加固建议。

---

## 二、新流程设计（4 位码 → 自动激活）

### 2.1 新旧对比

| | 现在 | 目标 |
|---|---|---|
| 用户操作 | 扫购买码 → 扫激活码 → 填设备ID+兑换码 → 手输 18 位 | **只输 4 位兑换码** |
| 设备 ID | 用户/网页从手环抄 | 程序自动从手环读取 |
| 18 位码 | 用户手输 | 程序自动获取并写入 |
| 入口 | 手环上的"高级版"页 | 手环页 **和/或** 手机 APK（推荐 APK 主导） |

### 2.2 角色分工（关键）

难点在于：**设备 ID 只存在于手环侧**，**兑换码只掌握在用户手里**，**激活码要写回手环**。三方缺一不可，所以必须约定一个"中转方"。两个选择：

- **路线 A（推荐）：手机 APK 当中转**
  - APK 从手环拿 deviceId → 用户只在手机上输 4 位码 → APK 调 `/api/activate` → 拿 18 位码 → APK 调手环写入。
  - 优点：手机有网络、能做输入、能展示结果；手环侧改动最小（只加两个协议动作）。
- **路线 B：手环 EV 自己联网**
  - Vela 手环**没有可用网络能力**（这正是激活码要离线校验的原因）→ **不可行**，排除。

### 2.3 目标时序（路线 A）

```
用户                 APK(手机)                      EV(手环)            后端 /api/activate
 │  打开「快速激活」      │                              │                     │
 │──────────────────────▶│ {"action":"get_device_id"}   │                     │
 │                       │─────────────────────────────▶│                     │
 │                       │◀── {ok, deviceId} ────────────│                     │
 │  输入 4 位兑换码       │                              │                     │
 │──────────────────────▶│                              │                     │
 │                       │ POST /api/activate {deviceId, redeemCode} ─────────▶ │
 │                       │◀──── {success, activationCode:"18位"} ────────────── │
 │                       │ {"action":"activate","code":"18位"}                 │
 │                       │─────────────────────────────▶│ 本地解码校验+写 auth_data
 │                       │◀── {ok, status, expireAt} ────│                     │
 │  看到「激活成功/有效期」 │                              │                     │
```

### 2.4 必须的接口/跨仓改动

| # | 改动 | 仓库 | 说明 |
|---|---|---|---|
| 1 | EV 新增 `{"action":"get_device_id"}` → `{ok,action:"get_device_id",deviceId:"<原始ID>",deviceId4:"<后4位>"}` | `tom/class/class`（**跨仓，需授权**） | 设备 ID 只能在手环取；也可并入 `ping` 回包省一次往返 |
| 2 | EV 新增 `{"action":"activate","code":"<18位>"}` → 复用 `auth-store.markActivatedV2` 逻辑，写 `auth_data`，回 `{ok,action:"activate",status:"active|permanent",expireAt}` | `tom/class/class`（**跨仓，需授权**） | **必须由 EV 自己落库**，否则 APK 与 EV 的 auth 逻辑会分叉 |
| 3 | 后端 `/api/activate` | `app-auth` | **已满足**，无需改。可选：新增 `scheduleName` 无关；可选增强 `deviceInfo` 富字段（手机机型/系统/APP 版本），供埋点 |
| 4 | APK 新增「快速激活」页 + `SyncEngine.getDeviceId()/activate(code)` | 本仓 | 见 2.5 |

> ⚠️ **改动 1、2 都在工作区之外（`tom/class/class`）**，按项目约定需先取得你的确认才能动手。

### 2.5 APK 侧 UI 设计（「快速激活」页）

```
标题：高级版
┌ 状态卡 ─────────────────────┐
│ 当前：标准版 / 高级版 剩余 N 天 │   ← 由 EV export/auth 回包提供
│ 设备ID：XXXXXX（后6位）        │   ← get_device_id
└─────────────────────────────┘
┌ 兑换码 ─────────────────────┐
│ [ 4 位兑换码输入框 ]           │
│ [ 一键激活 ]                   │
└─────────────────────────────┘
┌ 高级版能做什么（说明）────────┐
└─────────────────────────────┘
```

点击「一键激活」的执行链：`get_device_id` → `POST /api/activate` → `activate(code)` → 刷新状态。

### 2.6 异常分支

| 情况 | 处理 |
|---|---|
| 取不到设备 ID（`NA` / 回落 UUID） | 后端会按 NA 规则处理（次数上限 5）；APK 提示"设备标识异常，请重启手环后重试" |
| 兑换码不存在/未同步 | 后端 400 `兑换码不存在或尚未同步到服务器` → 原样提示 |
| 一码已被其它设备用 | 后端 400 → 提示"该兑换码已被其他设备使用过，请联系作者解绑" |
| IP/设备限流 | 后端 429（带 `Retry-After`）→ 提示稍后重试 |
| 手环未连接 | 直接提示"先回首页连接手环" |
| EV 版本过旧（无 `get_device_id`/`activate`） | 回 `no courses` → 提示"请升级手环上的 EV 课程表" |

### 2.7 兼容与灰度

- **保留**现有"手输 18 位码"通道，老 EV / 老用户不受影响。
- 新流程只在 EV 支持 `get_device_id` + `activate` 时可用（用 `ping` 的 `versionName` 判定，建议 ≥ 下一个版本）。
- 建议先用手环端"高级版"页**跳转到 APK 快速激活**（`launchWearApp` 反向不行，改为 APK 主导入口）。

---

## 三、设置栏目：首页设置 / 高级版 / 打赏支持

主项目 `settings.ux` 的对应结构（3 个 group）：

| 栏目 | 主项目实现 | APK 落地方式 |
|---|---|---|
| **首页设置** | `openHomepageSettings` → `homepage-settings.ux`，字段 `homepage_settings`（`showQuickAdd/showCustomContent/customContent/showTime/showStatusBar/showPinnedBar/showDayNav*/showLabSection/timeFormat`）+ `homepage_template` + `baseFontSize` | EV 已支持读写：`export` 回包含 `homepage`；写入用 `{"action":"update_settings","payload":{"homepage":{...},"homepageTemplate":"...","baseFontSize":48}}`（见 `app.ux:560-611`）。APK 新页：开关列表 + 读/写 |
| **高级版** | `openActivation` → `activation.ux`（三步 + 18 位手输） | 换成**上面 2.5 的「快速激活」页**；入口挂在设置页 |
| **打赏支持** | `openDonate` → `donate.ux` | APK 新页：展示爱发电链接/二维码图片 + 说明（纯静态，无协议依赖） |

> 说明：**首页设置**的"功能与主项目一致"需要逐字段核对 `homepage-settings.ux` 的开关语义（尤其 `timeFormat` 取值）——建议在实现前再读一遍该页，避免字段名对了但取值错。

---

## 四、工作量与实施顺序（建议）

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 写本文 + 确认跨仓改动清单 | — |
| P1 | 后端：确认 `/api/activate` 直接可用（可加 `deviceInfo` 富字段） | app-auth |
| P2 | EV：`get_device_id` + `activate` 两个动作 | **跨仓需授权** |
| P3 | APK：`SyncEngine.getDeviceId()/activate()` + 「快速激活」页 | P2 |
| P4 | APK：设置页加三栏目（打赏 → 首页设置 → 高级版入口） | P3 |
| P5 | 真机联调 + 灰度 | P3/P4 |

---

## 五、风险与待确认

1. **跨仓改动**：EV 要加 2 个动作，需你确认后再动 `tom/class/class`。
2. **18 位 ≠ 16 位**：文档、UI、提示文案统一按 **18 位纯数字**（`6-6-6` 展示）。
3. **激活码无签名**：本次不修；若要修（`computeChecksum` 加到末 2 位），必须**同时**升级 EV，且会使旧码失效 —— 建议单独排期。
4. **"直接写入手环"的安全性**：`activate` 动作会改 `auth_data`，属于高权限写；应仅在 EV 侧做校验（解码 + 设备ID后4位匹配），APK 不自行落库。
5. **回执**：EV `activate` 回包应带 `status/expireAt`，让 APK 能立刻展示"高级版 剩余 N 天"。
6. 若手环侧也要提供入口，需要 EV 页面改动（跨仓）。

---

## 六、本文核对过的路径

- 跨仓**只读**：`tom/class/class/src/pages/activation/activation.ux`、`src/lib/crypto.js`、`src/data/auth-store.js`、`src/utils/device-uuid.js`、`src/pages/settings/settings.ux`、`src/app.ux`；`app-auth/api/activate.js`、`api/visitor/ip.js`、`lib/crypto.js`、`lib/validate.js`、`lib/tracking.js`。
- 本仓：`docs/ui-message-import-export-improvements.md`、`docs/聊天功能可行性分析.md`。
