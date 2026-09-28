# APK 跟踪信息（埋点）方案 · 访客日志 / 手环调试 / 运维口径

> 目标：把「**谁在用、什么机器、连没连上手环、卡在哪一步、什么版本**」全部变成可查字段。
> 数据出口三处：① 后台「访客统计 → 最近访客」；② 后台「消息投递」+ Mac 通知；③ PostgreSQL `visitor_logs` / `tracking_events`（长期归档与聚合）。
> 更新时间：2026-09-28。★=本期已实现，☆=待做（第二期）。

---

## 一、数据流总览

```
APK Analytics.pageView()                                    ┌─▶ visitor_logs（永久表，含 device jsonb）
   │  POST /api/activate?section=visitor-track               ├─▶ stats:pv/uv/pages（KV，7 天滚动）
   │  body { path, query, nickname, deviceId,               ├─▶ tracking_events（漏斗 / 用户画像）
   │         device{...}, app{...}, watch{...} }   ────▶ Vercel┤
   │                                                         └─▶ notify.pushNotification("page_visit")
   │                                                                  ├─▶ message_delivery（投递台账 = 后台「消息投递」）
   │                                                                  └─▶ Redis stream + pubsub
   │                                                                          └─▶ Mac ev_notifier
   │                                                                                ├─ 弹窗「安卓访问」+ 手机 / 手环 / APK
   │                                                                                ├─ 语音「安卓<机型>用户来自<地区>，访问<页名>」
   │                                                                                └─ 面板「访客日志」设备列 + 详情两段
   └── IP / 中文省市区：服务端从请求头 + ip_lookups 取，客户端不传
```

**关键点**：APK 只上报「本机拿得到的设备与运行状态」；地理位置、IP、运营商一律服务端补。

---

## 二、字段清单

### 2.1 手机（`body.device`）

| 字段 | 取值来源 | 用途 | 状态 |
|---|---|---|---|
| `model` | `Build.MODEL` | 播报「安卓<机型>」、按机型排障 | ★ |
| `brand` / `manufacturer` | `Build.BRAND` / `Build.MANUFACTURER` | 厂商分布 | ★ |
| `os` | `Build.VERSION.RELEASE` | 系统版本 | ★ |
| `sdk` | `Build.VERSION.SDK_INT` | 判断是否受「未知应用安装」「精确闹钟」等限制 | ★ |
| `os_brand` | 见 §4 三条线索 | `harmony` / `emui` / `android`，**识别"连不上手环"的高危人群** | ★ |

### 2.2 本 APK（`body.app`）

| 字段 | 来源 | 用途 | 状态 |
|---|---|---|---|
| `version` / `code` | `PackageInfo.versionName` / `versionCode` | 用户实际跑的是哪版 | ★ |
| `variant` | `Variant.name()` = `ev` / `evbox` | 同一套源码两个包名，必须区分 | ★ |
| `first_install` | `PackageInfo.firstInstallTime` | 装机时间、"装机多久了还没激活" | ★ |
| `last_update` | `PackageInfo.lastUpdateTime` | 与 `first_install` 不等 → 至少升级过一次 | ★ |
| 升级次数 | 本地计数器（见 §6.1） | 升级率 | ☆ |
| 使用次数 / 使用时长 | 前台生命周期累计（见 §6.2） | 活跃度、真实留存 | ☆ |
| 下载渠道 | 打包注入或服务端重定向（见 §6.4） | 分渠道转化 | ☆ |

### 2.3 手环 / EV 快应用（`body.watch`）

| 字段 | 来源 | 用途 | 状态 |
|---|---|---|---|
| `connected` | `SyncEngine.hasNode()` | 「连上手环」与「没连上」两档 | ★ |
| `model` | `SyncEngine.deviceName` | 手环型号（如"小米手环 10 Pro"） | ★ |
| `ev_version` / `ev_code` | `SyncEngine.versionName` / `versionCode` | EV 快应用版本（`list_schedules` 需 ≥1.6.139 这类判断靠它） | ★ |
| `node_id` | `SyncEngine.getNodeId()` | 定位具体设备；排查"配对的是哪台手环" | ★ |
| `nickname` | `SyncEngine.nickname` | 手环端昵称 | ★ |
| 连接次数 / 各步耗时 / 失败原因 | SyncEngine 四步回调（见 §6.3） | 连接成功率、卡在第几步 | ☆ |

### 2.4 页面与位置（服务端补齐）

`path`（只留路径，不带 `?`）、`query`（完整渠道参数串）、`referrer`、`ip`、`country/region/city/district`（中文，读 `ip_lookups`）、`isp/org/asn/lat/lon`（ip-api）。

---

## 三、存储

### 3.1 `visitor_logs`（PostgreSQL，永久表）

原有列：`id, ts, ip, path, ua, ref, country, region, city, visitor_hash, source, query, params`
本期新增 4 列（`ensureTable()` 里 `alter table ... add column if not exists` 自愈建列）：

| 列 | 类型 | 说明 |
|---|---|---|
| `device_model` | `varchar(64)` | 高频字段单独成列 → 面板展示、播报、按机型聚合 |
| `os_version` | `varchar(32)` | 系统版本 |
| `os_brand` | `varchar(16)` | `harmony` / `emui` / `android` |
| `device` | `jsonb` | 上面三段（device/app/watch）的**完整对象**，长尾字段全在这，不占列 |

`device` 示例：

```json
{
  "model": "PGT-AN00", "brand": "HUAWEI", "manufacturer": "HUAWEI",
  "os": "12", "sdk": 31, "os_brand": "harmony",
  "app_version": "0.5.44", "app_code": 45, "app_variant": "ev",
  "first_install": 1758000000, "last_update": 1758900000,
  "watch_model": "小米手环 10 Pro", "watch_ev_version": "1.6.145", "watch_ev_code": 974,
  "watch_connected": true, "watch_node_id": "2137618976", "nickname": "小明"
}
```

**写库策略**：埋点写入走 `background.run()`（响应之后执行），失败只打日志，绝不影响用户请求。
**限长**：`sanitizeDevice()` 白名单 + 逐字段截断 —— 埋点体是客户端可控 JSON，不能原样落库。

### 3.2 通知侧

`page_visit` 的 payload 会带上 `device_model / device_brand / os_version / os_brand / app_version / app_variant / watch_connected / watch_model / watch_ev_version`，供 Mac 端渲染与播报。

---

## 四、关键判定：是否华为鸿蒙（"连不上手环"的高危人群）

**为什么要单独判**：鸿蒙 NEXT 上 APK 只能跑在「卓易通」安卓沙箱里，而沙箱内的进程**连不上沙箱外鸿蒙原生「小米运动健康」的穿戴服务** → 必然卡在连接第一步。`os_brand=harmony` + `watch_connected=false` 基本就是这批用户，不用再逐个问。

判定顺序（全部包在 `try` 里，任何一步失败都继续往下）：

| 序 | 线索 | 命中 |
|---|---|---|
| 1 | `com.huawei.system.BuildEx#getOsBrand()` 反射调用 | 返回含 `harmony` → `harmony`；其他非空值（如 `emui`）原样返回 |
| 2 | `System.getProperty("os.harmony.version")` | 非空 → `harmony` |
| 3 | `Build.DISPLAY` 含 `harmony` | → `harmony` |
| 4 | `Build.MANUFACTURER` 含 `huawei` / `honor` | → `emui`（华为/荣耀的 Android 版） |
| — | 都不命中 | `android` |

> ⚠️ **只用于提示文案与运维统计，绝不能当功能开关**：判定失败会误伤能正常连手环的机器。

---

## 五、本期改动落点（文件级）

| 仓 | 文件 | 改了什么 |
|---|---|---|
| 本仓 | `apk/src/com/application/watch/classschedule/Analytics.java` | `pageView()` 增加 `device` / `app` / `watch` 三段；新增 `osBrand()` 鸿蒙判定 |
| app-auth | `lib/visitor-log.js` | `visitor_logs` 加 4 列；`logVisit()` 收 `entry.device` 落库；`listRecent()` 带出设备字段 |
| app-auth | `api/activate.js` | `sanitizeDevice()` 白名单解析；写入 `visitor_logs`；`page_visit` 推送带设备字段 |
| app-auth | `api/admin/health.js` | `handleVisitorRecent2` 透传 `deviceModel/osVersion/osBrand/device` |
| app-auth | `admin_Dx23.html` | 访客详情新增「Device Info」「Watch / EV」两段；语音播报改「安卓<机型>」 |
| app-auth | `tools/ev-notifier/ev_notifier.py` | 弹窗标题「安卓访问」+ 手机/手环/APK 三行；语音「安卓<机型>用户来自<地区>，访问<页名>」；面板「访客日志」设备列 + 详情两段 |

**播报文案对照**：

| 场景 | 播报 |
|---|---|
| APK 访问（有 `device_model`） | 安卓 Pixel 7 用户来自山东淄博，访问课程表 |
| 网页访问（无 `device_model`） | 山东淄博用户访问激活页面（保持原样，不会被误说成"安卓"） |

---

## 六、第二期：需要本地计数器的字段

这些字段**设备端一次算不出来**，必须在 APK 侧累积计数器（`SharedPreferences`），再随埋点一起上报。

### 6.1 升级次数（低成本，先做）

- **推荐**：本地存 `last_seen_version_code`；启动时若 `PackageInfo.versionCode` 与它不同 → `upgrade_count += 1` 并回写。
- **辅助**：`first_install` / `last_update` 已上报，服务端也能算「是否升级过」与装机时长，两套互相校验。
- **上报**：`app.upgrade_count`。

### 6.2 APK 使用次数 / 使用时长

- **做法**：在 `Application` 里注册 `ActivityLifecycleCallbacks`，统计 `onStart`（进入前台）→ 累计一次「使用次数」；`onStop` 时把前台时长累加到 `foreground_ms`。
- **注意**：`onStop` 不一定被调用（进程被杀）→ 用「进入前台时先补记上一次会话的时长」的写法，避免丢。
- **上报**：`app.usage_count`、`app.foreground_ms`（累计值，不是增量，服务端只存最新快照 + 时间戳）。
- **隐私**：只统计本机累计时长与次数，**不含任何操作内容**。

### 6.3 EV 连接次数 / 各步耗时 / 失败原因

- `SyncEngine.connect()` 已经是四步状态机（初始化穿戴服务 → 查找设备 → 申请权限 → ping EV），每步都有 `OK/FAIL` 与 `details` 文案。
- **做法**：在四步回调里落本地计数器：`connect_ok`、`connect_fail_step<N>`、每步耗时 ms；`humanize(e)` 的失败原因一并记录。
- **上报**：`watch.connect_total` / `connect_ok` / `last_fail_step` / `last_fail_reason`。
- **价值**：能直接回答"用户到底卡在第几步"，配合 `os_brand=harmony` 判定是否环境问题。

### 6.4 下载渠道（快应用那套在 APK 上不成立）

**快应用（手环）怎么做的**：靠 URL 参数 `?c=<渠道>`（激活页提取 `c` → 服务端落库 → 通知里显示）。因为快应用每次都是打开一个 URL，参数一直在。

**APK 为什么不成立**：安装包下载完 URL 就断了，APK 启动后**无法知道自己是从哪个链接装的**（没有 Google Play，读不到 install referrer）。

三条可行路线：

| 路线 | 做法 | 能统计 | 代价 |
|---|---|---|---|
| **A. 打包注入渠道** | `build.sh` 往 manifest 写 `ev.channel` meta-data，**不同渠道出不同的包** | 精确到"某个安装实例来自哪个渠道"的安装量/留存 | 每次发版要出 N 个包（脚本化即可） |
| **B. 服务端下载重定向** | `/dl/ev?c=ghproxy` → 302 到真实地址 + 计数 | 只统计**下载量**，关联不到安装实例 | 零 APK 改动，最省 |
| **C. 弱关联** | APK 首次启动上报「首启」事件，服务端按 (IP, 时间窗) 去猜下载记录 | 粗略 | 命中率受 NAT / 时间偏差影响 |

**建议**：先做 B（立刻有下载量口径）；如果确实要按渠道算安装与留存，再做 A，并在 `Analytics` 里加 `app.channel`（读 `ev.channel` meta-data，缺省 `unknown`）。

---

## 七、运维视角：这些字段能排什么障

| 用户现象 / 需求 | 查什么 | 结论 |
|---|---|---|
| "连不上手环，卡第一步" | `os_brand`、`watch_connected` | `harmony` + `false` → 卓易通沙箱环境问题，不是 App bug |
| "功能不对 / 界面是旧的" | `app_version`、`app_variant`、`watch.ev_version` | 版本不匹配（如 EV < 1.6.139 没有 `list_schedules`） |
| "连的是哪台手环" | `watch.node_id`、`watch.model`、`nickname` | 多手环场景定位 |
| "装了但没人用" | `first_install`、`last_update`、使用次数（☆） | 装机未激活 / 装了不用 |
| "通知没收到" | 后台「消息投递」状态 + `visit push rate-limited` 日志 | 推送被 15 条/分钟封顶拦截 / 投递失败（可补发） |
| "有人在刷" | 同 IP 高频 + `device_model` 为空 | 大概率是网页爬虫（APK 埋点必带机型） |
| 机型兼容性排查 | `device_model`、`os`、`sdk` | 哪类机器出问题 |

**埋点开关**：`Analytics.setEnabled(false)` 可整体关掉客户端埋点（排查期用）。

---

## 八、隐私边界（写死，别越界）

**只收**：设备品牌/型号/系统版本、APK 版本与运行统计、手环连接状态与版本、页面路径、IP 与由此推出的城市。

**绝不收**：IMEI / 序列号 / 手机号 / 通讯录 / 短信 / 精确定位 / 手环里的课程内容。

**保留**：`visitor_logs` 长期保留（趋势统计要用）；KV 的 `stats:pv/uv/pages` 7 天滚动过期；`message_delivery` 按现有 `cleanOld()` 策略清理。埋点请求本身受 `checkVisitorIpRateLimit`（120/min/IP）保护。

---

## 九、分期实施

| 期 | 内容 | 状态 |
|---|---|---|
| **P0** | 手机型号/系统/鸿蒙 + APK 版本/变体/装机时间 + 手环连接/型号/EV 版本 + 落库 + 播报「安卓<机型>」+ 面板展示 | ✅ 2026-09-28 |
| **P1** | 升级次数、使用次数、使用时长、EV 连接次数与失败步（§6.1–6.3） | ☆ |
| **P2** | 下载渠道（§6.4 路线 B → A）、渠道漏斗报表、按机型/系统的聚合看板 | ☆ |
