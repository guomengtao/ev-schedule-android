# APK 埋点（访问上报）方案

> 目标：用户打开首页等页面时**自动**把访问记录上报到我们网站后台；并区分 **未连上手环** / **已连上手环** 两档，附带 手机机型、手环机型、版本号、IP 等信息。
> 日期：2026-09-27
> 前提：后台**已有**访客/事件埋点能力，本方案尽量复用，不新造轮子。本文只做方案设计，未改代码。

---

## 一、后台已有什么（先复用，别重造）

### 1.1 现成的"页面访问"接口

`app-auth/api/activate.js` 里有一个 section 分支：

```292:295:app-auth/api/activate.js
module.exports = async (req, res) => {
  if (req.query && req.query.section === "visitor-track") {
    return handleVisitorTrack(req, res);
  }
```

`handleVisitorTrack` 做的事：

- 入参（body 或 query）：`{ path | href, ref, query }`；
- **IP 由服务端取**（`x-forwarded-for` / `x-real-ip`），**客户端不用传**（更可信，也避免被伪造）；
- 写 Redis：当日 PV/UV、热门页面 zset、最近 ~100 条 recent；
- 写永久表 `visitor_logs`（`visitorLog.logVisit`）；
- 写统一事件流 `tracking_events`（`kind="visit"`）；
- IP 限流 `checkVisitorIpRateLimit`（超限 429）；
- 异步补齐 IP 归属地（腾讯位置服务 + Vercel 头部）。

调用形态：

```
POST /api/activate?section=visitor-track
body: { "path": "/ev-schedule.html", "ref": "...", "query": "c=xxx" }
→ { "success": true, "isNewVisitor": true }
```

### 1.2 统一事件流（可承载自定义事件）

`lib/tracking.js` 的 `record(ev)`：

```87:100:app-auth/lib/tracking.js
// 统一入口：把一个业务事件镜像进 tracking_events
// ev = { ts, kind, ip, visitorHash, deviceId(full), redeemCode, outTradeNo, activationCode, channel, payload, dedupeKey }
async function record(ev) {
```

- 表 `tracking_events(ts, kind, device_full, device_norm, visitor_hash, ip, redeem_code, out_trade_no, activation_code, channel, payload jsonb, dedupe_key unique)`；
- 已有 `kind`：`visit / go_click / purchase_click / order / redeem / activation / failure`；
- `dedupe_key` 唯一 → 天然幂等（重复上报会被忽略）。

### 1.3 机型归一化工具（重要，避免又踩坑）

```70:73:app-auth/lib/tracking.js
// 机型取值：激活 URL 里 `m`（model）经常是 "ap" 这种垃圾值，真正的机型在 `p`（product，
// 如 "REDMI Watch 6"）。规则：长度 ≥ 3 才算有效，两者都有效时取更长的那个。
function pickModel(model, product) {
```

→ **手环机型**要按同样规则处理：优先 `product` 这类语义完整字段，`model` 常是垃圾值。

---

## 二、上报设计

分两类事件，走**两个不同的 section**，互不污染：

| 类别 | 接口 | 用途 |
|---|---|---|
| A. 页面访问 | `?section=visitor-track`（**已存在，直接复用**） | 首页/留言/设置/导入导出 等页面打开 |
| B. 客户端阶段事件 | `?section=client-event`（**新增**） | 连接成功/失败、手环信息、导入导出结果 |

### 2.1 A：页面访问（两档）

APK 每次进入页面（`onCreate`/`onResume` 去重）上报：

```
POST /api/activate?section=visitor-track
body: {
  "path": "/apk/home",                 // 用固定前缀区分 APK 来源
  "query": "v=0.5.13&connected=0",     // 连接档位 + 版本
  "ref": ""
}
```

- **未连上手环**：`connected=0`
- **已连上手环**：`connected=1`

> 说明：`visitor-track` 会把 `path` 与 `query` 拆开存（`path` 去参 → 避免"热门页面"被参数分裂），`query` 完整留存供归因。所以 `connected` 放 `query` 里，正好让后台既能按页聚合、又能区分连接档位。

### 2.2 B：客户端阶段事件（新增 section）

```
POST /api/activate?section=client-event
body: {
  "kind": "connect_ok" | "connect_fail" | "band_info" | "import_ok" | "import_fail" | "export_ok",
  "deviceId": "<可选，已连时带上，便于与激活漏斗对齐>",
  "channel": "apk",
  "payload": {
    "appVersion": "0.5.13",
    "appVersionCode": 14,
    "phoneModel": "Xiaomi 15",
    "osVersion": "15",
    "bandName": "小米手环 10 Pro",
    "evVersionName": "1.6.139",
    "evVersionCode": 139,
    "stage": "step3_permission",        // 失败时卡在哪一步
    "reason": "...",                    // 失败原因（脱敏）
    "courseCount": 23,
    "connected": 1
  },
  "dedupeKey": "apk:0.5.13:connect_ok:20260927"   // 幂等
}
```

后台侧落地：在 `api/activate.js` 增加一个 section 分支，直接调用 `tracking.record({ kind, deviceId, ip, visitorHash, channel, payload, dedupeKey })`。**无需改表**。

### 2.3 字段来源表

| 字段 | 来源 | 说明 |
|---|---|---|
| `ip` | **服务端**（`x-forwarded-for`） | 客户端不传 |
| `country/region/city/district` | 服务端 geo | 复用 `geoZh` / `geoDistrict` |
| `userAgent` | 服务端 headers | — |
| `phoneModel` | APK：`Build.MANUFACTURER + " " + Build.MODEL` | 如 `Xiaomi 15` |
| `osVersion` | APK：`Build.VERSION.RELEASE` | — |
| `appVersion/Code` | APK：`PackageInfo` | — |
| `bandName` | APK：XMS `Node.name`（`SyncEngine.deviceName`） | 断连时为空 |
| `bandModel` | APK：暂取 `Node.name`；如能取到更完整产品名则优先（参考 `pickModel` 规则） | 手环型号 |
| `evVersionName/Code` | APK：`ping` 回包 / `SyncEngine.versionName` | 已连时才有 |
| `connected` | APK：`SyncEngine.connected()` | 分档依据 |
| `stage/reason` | APK：连接 4 步的结果 | 失败定位 |
| `courseCount` | APK：export 统计 | 已连时才有 |

---

## 三、APK 侧实现落点

新增一个轻量类即可，**不引第三方 SDK**：

```
apk/src/com/application/watch/classschedule/Analytics.java
  - post(String section, JSONObject body)      // HttpURLConnection，异步线程，超时短(3s)，失败静默
  - pageView(String path, boolean connected)   // → visitor-track
  - event(String kind, JSONObject payload)     // → client-event
  - snapshot()                                 // 组装 手机/手环/版本 公共字段
```

打点位置：

| 位置 | 事件 |
|---|---|
| `HomeActivity.onCreate/onResume`（去重） | `pageView("/apk/home", connected)` |
| `HomeActivity.done()` / `fail()` | `connect_ok` / `connect_fail`（带 stage/reason） |
| `MessageActivity.onResume` | `pageView("/apk/message", connected)` |
| `SettingsActivity.onResume` | `pageView("/apk/settings", connected)` |
| `TransferActivity.onCreate` | `pageView("/apk/transfer?mode=import|export", connected)` |
| 导入/导出完成后 | `import_ok/import_fail/export_ok` |

注意事项：

- **必须异步**，不能阻塞 UI；失败只记 logcat，不打扰用户；
- 网络权限：Manifest 加 `android.permission.INTERNET`（当前**没有**，需新增）；
- 域名用 `https://app-auth.gudq.com`，失败要静默（离线可用是硬要求：不能因为上报失败影响连接/导入）；
- 去重：同一次 App 生命周期内同一页面只报一次；`dedupeKey` 交给后端兜底。

---

## 四、隐私与合规

- 只上报**设备/版本/机型**这类非个人可识别信息；不上传通讯录、位置、消息内容；
- IP 由服务端记录（用于归属地统计），客户端不主动采集位置；
- 提供"关闭埋点"开关（可放设置页），默认开启；
- 上报内容做**长度截断**（参考后端 `slice(0, 120/200)` 的做法），避免异常长字符串入库。

---

## 五、验收标准

| 项 | 期望 |
|---|---|
| 未连手环打开首页 | 后台 `visitor_logs` 出现 `/apk/home` 且 `query` 含 `connected=0` |
| 已连手环打开首页 | 同上，`connected=1` |
| 连接失败 | `tracking_events` 出现 `kind=connect_fail`，payload 含 `stage/reason` |
| 已连成功 | 出现 `connect_ok` 且 payload 含 `bandName/evVersionName/phoneModel` |
| 离线场景 | 上报失败不影响任何功能 |
| 重复上报 | 因 `dedupe_key` 唯一而幂等 |

---

## 六、工作量

| 阶段 | 内容 | 依赖 |
|---|---|---|
| P0 | 本文 + 确认后端新 section 与 `kind` 命名 | — |
| P1 | 后端：`api/activate.js` 加 `section=client-event` 分支（约 20 行，复用 `tracking.record`） | app-auth |
| P2 | APK：`Analytics.java` + Internet 权限 + 各页打点 | 本仓 |
| P3 | 后台看板：在现有"机型分布/生命周期"基础上加 APK 维度筛选（`channel=apk`） | app-auth（可选） |

---

## 七、本文核对过的路径

- 跨仓**只读**：`app-auth/api/activate.js`（`handleVisitorTrack`）、`api/visitor/ip.js`、`lib/tracking.js`（`record/pickModel/dedupe`）、`lib/validate.js`。
- 本仓：`apk/AndroidManifest.xml`（当前无 INTERNET 权限）、`SyncEngine.java`（已持有 `deviceName/versionName`）。
