# APK UI / 通知 问题综合分析

> 时间：2026-09-27
> 范围：安卓 APK（`apk/src`）+ 后端（`app-auth` 的 `visitor-track` / `notify` / EvNotifier 推送）+ 后台「消息投递」追踪页
> 结论先行：4 个问题里，① 输入法、② 底栏图标、③ 打赏页是 **APK 端确凿可修的 bug/需求**；④ 通知要分两层看——**页面访问本来就不进「消息投递」追踪（不是丢通知）**，真正"收不到通知"另有链路要查。

---

## 一、调试页 / 其它页默认弹输入法

### 现状（来自 `apk/AndroidManifest.xml`）

| Activity | `windowSoftInputMode` | 是否含输入框 | 风险 |
|---|---|---|---|
| HomeActivity | （无，默认 `stateUnspecified`） | 无 EditText | 一般不出，但不保险 |
| MessageActivity | `stateHidden\|adjustResize` | 有（留言输入） | 已处理 |
| **DebugActivity** | （无） | **有 EditText `inputView`** | 🔴 必弹 |
| SettingsActivity | `stateHidden` | 无 | 已基本处理 |
| TransferActivity | `stateAlwaysHidden\|adjustResize` | 有 | ✅ 已处理 |
| FastActivateActivity | `stateAlwaysHidden\|adjustResize` | 有 | ✅ 已处理 |
| HomepageSettingsActivity | （无） | 无 | 不保险 |
| DonateActivity | （无） | 无 | 不保险 |
| BatteryGuideActivity | （无） | 无 | 不保险 |

根因：
1. **DebugActivity** 里 `new EditText(this)` 后直接 `root.addView(inputView)`（`DebugActivity.java:85-89`）。EditText 在 Activity 创建时会抢焦点，而该 Activity **没有** `windowSoftInputMode`，系统默认 `stateUnspecified` → 一进页面键盘就弹。
2. 其它未配置 IME 的页面（Home/HomepageSettings/Donate/BatteryGuide）走默认 `stateUnspecified`，虽然没输入框不会弹，但属于"未显式禁止"，不规范。
3. `TransferActivity` 之前用过的成熟做法（`TransferActivity.java` 早期提交）：`stateAlwaysHidden\|adjustResize` + 根布局 `setFocusableInTouchMode(true)` 并 `requestFocus()`，让 EditText 不自动抢焦点。

### 修复建议

P0（一行级，立刻做）：在 `AndroidManifest.xml` 给所有 Activity 显式加 `android:windowSoftInputMode="stateAlwaysHidden"`（有滚动/底部栏的保留 `adjustResize`，如 `...|adjustResize`）。最省事的做法是直接在 `<application>` 上加 `android:windowSoftInputMode="stateAlwaysHidden"` 作为默认值，个别需要 `adjustResize` 的再单独覆盖。

P0（DebugActivity 专属）：在 `setContentView` 前，让根布局抢焦点：
```java
root.setFocusableInTouchMode(true);
root.requestFocus();
```
这样 EditText 不会自动获得焦点，键盘不会随页面打开而弹出；用户点输入框时才弹。

---

## 二、底部三按钮加图标 + 做成公共组件

### 现状

- **公共组件已经存在**：底栏由 `Ui.bottomBar(Activity, current)` 唯一创建（`Ui.java:167-201`），三按钮是纯文字「首页 / 留言 / 设置」。
- **所有页面都已调用**：9 个 Activity 全部走 `Ui.wrapWithBottomBar(...)` 或 `Ui.fixedWithBottomBar(...)`（`Home`=0、`Message`=1、`Settings`/其它子页=2、`Debug`=-1）。所以"做成一个公共页面、其它页面都调用它"**已经满足**，无需再抽。
- 当前缺口只有两个：① 按钮**没有图标**（纯文字）；② 子页面（Donate/FastActivate/HomepageSettings/BatteryGuide）都传 `current=2`，会把"设置"高亮，可接受但可在子页传 `-1` 不高亮。

### 修复建议（加图标）

UI 是纯代码绘制、没有 `res/drawable`，加图标有两条路：

- **方案 A（最省事，推荐先做）**：按钮文字里带 emoji，如 `🏠 首页` / `💬 留言` / `⚙ 设置`。改动只在 `Ui.bottomBar` 里把 `names` 数组改成带 emoji 的字符串。零依赖、零资源。
- **方案 B（更正规）**：把每个 tab 从单个 `Button` 改成一个小 `LinearLayout`（上下排列：一个 `TextView` 当图标 + 一个 `TextView` 当文字），或 `Button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)`。图标来源可以是：
  - 程序化生成的 `VectorDrawable`（无 res 文件，代码里 `new VectorDrawable()` + `setTint`）；
  - 或放进 `apk/res/drawable` 用 aapt2 编译（需改 `build.sh` 把 res 编进资源表——当前 `build.sh` 没编 res，只编纯 Java）。

> 建议先上方案 A 验证观感，确认后再决定是否走方案 B 接正式图标资源。

---

## 三、打赏页：微信/支付宝顺序、二维码、打开按钮无效

### 现状（`DonateActivity.java`）

- **顺序不对**：`PLATFORMS` 数组当前是 `{爱发电, 支付宝, 微信}`（`DonateActivity.java:17-21`）。要改成 **微信第一、支付宝第二**（爱发电第三或保留）。
- **没有二维码**：现在每个渠道只显示一行 `mono(url)` 文本 + 「打开 / 复制链接」两个按钮，**没有任何二维码图像**。
- **「打开」微信不生效**：`open(url)` 用 `Intent.ACTION_VIEW` 打开 `wxp://...` 这种微信二维码 scheme（`DonateActivity.java:64-73`）。两个问题：
  1. **Android 11+ 包可见性**：`wxp://` 是自定义 scheme，Manifest 的 `<queries>` 只声明了 `com.xiaomi.wearable` / `com.mi.health`（`AndroidManifest.xml:16-19`），**没有声明微信/支付宝**。`resolveActivity()` 在 Android 11+ 上对未声明可见性的 scheme 会返回 `null` → 抛 `ActivityNotFoundException` → 被 catch 成"没有可打开该链接的应用"（或静默）。
  2. **微信 `wxp://` 不一定能被 ACTION_VIEW 直接拉起**：它是微信收款码二维码的 scheme，很多情况下系统没有能处理它的 Activity，需要微信 App 自身去扫。直接 `ACTION_VIEW wxp://` 经常"点了没反应"。

### 修复建议

P0（顺序）：把 `PLATFORMS` 改成 `[微信, 支付宝, 爱发电]`。

P0（打开按钮）：
- 在 `AndroidManifest.xml` 的 `<queries>` 里补：
  ```xml
  <intent>
    <action android:name="android.intent.action.VIEW" />
    <data android:scheme="wxp" />
  </intent>
  <intent>
    <action android:name="android.intent.action.VIEW" />
    <data android:scheme="alipays" />
  </intent>
  ```
  （支付宝 `qr.alipay.com` 是 https，浏览器可处理，不必声明；但 `alipays://` 可顺手补。）
- 微信"打开"改为**先尝试拉起微信 App**（`Intent(Intent.ACTION_MAIN).setPackage("com.tencent.mm")`），拉起后让用户去扫；同时保留"复制链接"作为兜底。直接 `ACTION_VIEW wxp://` 不可靠。

P1（二维码，需求"都显示一个生成的二维码"）：
- APK 当前**没有任何 QR 库**（`grep zxing|qrcode` 0 命中）。需要新接一个纯 Java 二维码库（推荐 `zxing-core`，约几百 KB，无原生依赖）。
- 集成步骤：
  1. 下载 `core-3.x.x.jar` 放到 `apk/libs/`；
  2. `build.sh` 的 `javac` 的 `-classpath` 加上 `libs/zxing-core.jar`；
  3. d8 阶段把 zxing 的 class 一并打进 dex（目前 d8 输入是 `find out/sdkclasses out/classes -name '*.class'`，需把 zxing 解压到某个 `out/xxxclasses` 目录或把 jar 加入输入）；
  4. `DonateActivity` 里用 `com.google.zxing.qrcode.QRCodeWriter` 生成 `BitMatrix` → 画成 `Bitmap` → 用 `ImageView` 显示（纯代码 UI 需要 `new ImageView(ctx)` + `setImageBitmap`，或自绘 `Drawable`）。
- **离线兜底**：二维码库是本地生成，不依赖网络，比"调二维码 API 取图"更适合手环配套场景。

---

## 四、通知 / 消息投递追踪（重点澄清）

### 先分清两套完全不同的系统

| 系统 | 入口 | 落哪里 | 后台能不能看 |
|---|---|---|---|
| **访客埋点（页面访问）** | APK `Analytics.pageView()` → `POST /api/activate?section=visitor-track` | `tracking_events(kind='visit')` + `visitor_logs`（永久表） | ✅ 后台「访客记录」tab |
| **消息投递（推送通知）** | 后端 `notify.pushNotification(type, payload)` → 写 `message_delivery` 表 + 推 Upstash stream | `message_delivery` 表 + Upstash `auth:notifications:stream` | ✅ 后台「消息投递」tab（你贴的那张图） |

**关键结论**：你贴的"消息投递"追踪页（`admin_Dx23.html` 的 `panel-delivery`，`section=delivery-query`）追踪的是**后者**——也就是 EvNotifier 推到你 Mac 的桌面通知（激活成功/失败、启动自检、购买点击等）。**页面访问（`visit`）根本不会调用 `pushNotification`**，所以"访问每个页面没有任何消息通知记录"是**预期行为，不是丢通知**。

### 如果你"想要"页面访问也产生通知

那是**缺失的功能**，不是 bug：在 `api/activate.js` 的 `handleVisitorTrack` 里（或 APK `Analytics.pageView`）追加一次 `notify.pushNotification("visit", {...})`，访问记录就会出现在"消息投递"追踪里，并经 EvNotifier 推到 Mac。但通常没必要——访问量大会刷屏，建议只推送"连接成功 / 激活 / 异常"这类低频次、高价值事件。

### 如果你"真的收不到通知"（排除上面的误会）

"通知没有收到"若指**连购买/激活这类本该推送的也没收到**，按这条链路逐个查：

1. **EvNotifier 消费端是否在跑（最常见）**：通知最终由你 Mac 上的 EvNotifier（`tools/ev-notifier/ev_notifier.py`，LaunchAgent `com.evnotifier.agent`）从 Upstash stream 拉取并弹窗/语音。**Mac 上进程没起 / 崩溃 / 被系统杀**，服务端显示"已送达"你也看不到。验证：
   ```bash
   launchctl list | grep -i evnotifier
   ```
   没在跑就 `launchctl kickstart -k gui/$(id -u)/com.evnotifier.agent`。
2. **Upstash 是否可达**：`notify.pushNotification` 优先走 Upstash REST（`lib/notify.js:1039`）。Upstash 不可达时，fallback 写进 Postgres `kv_streams` 并 `markFailed`——**而 EvNotifier 只消费 Upstash，不读 Postgres**，所以这段兜底**不构成投递**，记录会进 ❌ 失败（后台可"补发"）。历史上有个更隐蔽的 bug：fallback 曾误调 `markPublished()`，让记录显示"已发布"却永远不被补发（**静默永久丢失**），现已改成 `markFailed()`（`lib/notify.js:1088-1119` 注释）。
3. **确认投递状态口径**：后台"已送达 ✅"是 `message_delivery.status='delivered'`，通常是 EvNotifier 拉到消息后用 `seq` 回执确认的。**服务端显示 delivered 但 Mac 没弹** → 基本锁定在消费端（第 1 点）。
4. **限流/静默**：`visitor-track` 有 120 次/分钟/IP 限流（`lib/rate-limit.js`，之前的 F6 修复），超了直接 429 丢记录；公司/校园共享出口 IP 易触发。但这只影响**访客埋点**，不影响推送通知。

### 建议的下一步

- 先确认你要的是"访问也推送通知"（功能缺失，按上面加一行）还是"本该推的没收到"（查 EvNotifier 进程 + Upstash 可达性 + 后台 failed 列表）。
- 后台「消息投递」页有「🔁 补发卡住的」按钮（`retry-stuck`，`admin_Dx23.html:11045` 调 `section=delivery-query&action=retry-stuck`），可把 `pending`/卡住的重新推一次，先验证链路。

---

## 五、涉及文件速查

| 问题 | 文件 | 位置 |
|---|---|---|
| ① 输入法 | `apk/AndroidManifest.xml` | 各 `<activity>` 的 `windowSoftInputMode` |
| ① 输入法 | `apk/src/.../DebugActivity.java` | `:85-89` EditText 抢焦点 |
| ② 底栏图标 | `apk/src/.../Ui.java` | `bottomBar()` `:167-201`，`names` 数组 `:168` |
| ③ 打赏顺序/打开 | `apk/src/.../DonateActivity.java` | `PLATFORMS` `:17-21`，`open()` `:64-73` |
| ③ 包可见性 | `apk/AndroidManifest.xml` | `<queries>` `:16-19` |
| ③ 二维码库 | `apk/build.sh` + `apk/libs/` | javac `-classpath` `:136`、d8 输入 `:147` |
| ④ 访客埋点 | `apk/src/.../Analytics.java` | `pageView()` `:35-52` |
| ④ 访客落库 | `app-auth/api/activate.js` | `handleVisitorTrack()` `:63-174` |
| ④ 推送投递 | `app-auth/lib/notify.js` | `pushNotification()` `:981-1128` |
| ④ 投递追踪页 | `app-auth/admin_Dx23.html` | `panel-delivery` `:4673`、`loadDeliveryMessages` `:10941` |

---

## 六、待你拍板

1. ① 输入法：直接给所有 Activity 加 `stateAlwaysHidden`（含 DebugActivity 根布局抢焦点）？
2. ② 底栏图标：先上 emoji 方案 A，还是直接做方案 B（正式图标资源）？
3. ③ 打赏：顺序 + 包可见性 + 微信拉起 App，这三件我可以直接改；二维码要不要现在就接 zxing（会改 `build.sh` + 加 ~几百 KB 依赖）？
4. ④ 通知：你到底是要"访问也推送通知"（我加一行），还是查"本该推的没收到"（我去查 EvNotifier 进程 / Upstash / failed 列表）？
