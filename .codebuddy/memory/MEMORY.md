# 项目长期记忆：EV 课程表 / 安卓同步器

## 四仓
- `guomengtao/class-schedule`：手环 EV 课程表快应用（Vela，`com.application.watch.classschedule`）；本地活跃副本 `~/Documents/guomengtao/tom/class/class`（**别改错**）。
- `guomengtao/app-auth`：后台 + EvNotifier + AstroBox 插件（Vercel/macOS），本地 `~/Documents/guomengtao/app-auth`。
- `guomengtao/ev-schedule-android`（本仓）：安卓同步器 APK，无 Gradle。
- `guomengtao/EvBox/evbox`：手环 EvBox 工具箱快应用（`com.application.watch.evbox`，与 EV 同签名）。
- ★ interconnect 一个包名只能对一个快应用（APK applicationId == 快应用 package）；EV 与 EvBox 包名不同 → **两个 APK**。全局地图 `~/PROJECT-MAP.md`。

## interconnect 硬约束
- 快应用 + 三方 APK 必须 **包名一致 + 签名一致**；EV rpk 用同一把 keystore。
- APK 走 `xms-wearable-lib_1.4_release.aar`：`getConnectedNodes()`→`requestPermission`→`addListener`→`sendMessage`；硬依赖手机「小米运动健康」。顺序：查设备+授权 → 注册监听 → 发消息（不注册监听必 0 回包）。
- EV 接口：`ping`/`export`（格式 A 按天分组）/`import`（一条课一对象）/`list_schedules`/`export?scheduleIndex`/`get_device_id`/`activate`。
- 签名不一致报 `SignatureVerifyFailedException`；AstroBox 插件走 WIT 旁路不受包名约束。

## App 结构（安卓同步器）
- Activity：Home/Debug/Settings/Transfer/Message/FastActivate(高级版一键激活)/HomepageSettings/Donate/BatteryGuide，全部挂底栏。
- `SyncEngine` 单例：4 步 + 单一 listener + 6s 超时；多手环 `NodeChooser`/`preferredNode`。
- `Ui.java` 统一视觉（Token 化，**新代码禁写死颜色**，一律 `Ui.TEXT/MUTED/ACCENT/...`）；`wrapWithBottomBar`/`fixedWithBottomBar` 是唯一底栏入口；底栏 3 tab（图标+文字）。浅色晴空蓝/深色夜幕蓝，`applyTheme()` 刷新。12 色课程色 `Ui.courseColor(name)`。
- 工具类：`Net`、`Analytics`（页面访问+设备/手环上下文埋点）。

## 激活体系
- 激活码 = **18 位纯数字**（`PPCCCCMMDDDD` 结构，确定性生成 → 同码同设备复用幂等）；兑换码 = 4 位 `[A-Z0-9]`（爱发电购买）；设备ID sha256 后存 `used_device_id` → **一码一机**；NA 设备限额默认 5；IP/设备双限流；全量失败记录 + tracking 漏斗。
- 后端 `POST /api/activate` `{deviceId,redeemCode,deviceInfo}` → `{activationCode}`；手环端 `get_device_id`/`activate`（本地校验落库）；18 位码无签名可伪造（TODO 加固需同步升 EV）。
- 安卓 FastActivate：取设备ID → 输 4 位兑换码 → 换 18 位码 → activate 写手环。**已知欠缺**（2026-09-29 文档）：单 EditText 非四格、无未连接暂存、无购买直达按钮、写入失败码不留存、多手环无目标确认。改进方案见 `docs/apk-fast-activate-flow-analysis.md`（D1 暂存兑换码+18位码补写 / D2 多手环确认 / D3 chooser 直达爱发电 / D4 多手环=多码 / D5 手环端只加推荐卡，原流程保留兜底）。
- 手环 activation.ux：二维码1=爱发电购买（`/go/ev-timetable?deviceId=…&m=&r=&c=`），二维码2=`/activate.html?deviceId=`，18 格手输。网站 `activate.html`/`user-guide.html` 待把「APK 激活」置顶推荐。

## APK 产品定位 / 本地数据
- 桌面名「Ev课程表」（不拼版本号）。改名前提：首屏给课表内容。
- 阶段 0（推荐）：连上拉课表 → 本地只读缓存 → 首屏渲染+桌面插件+上课提醒；不搞后台自动同步（硬底线）。阶段 1 编辑再定 SSOT。
- 上课提醒唯一可靠路径 = CourseCache 本地数据 + AlarmManager（`Reminders`/`ReminderReceiver`/`BootReceiver`，prefs `ev_remind`）；实时连手环提醒不可行（前台服务被杀/Doze）。
- 桌面插件 `TodayWidgetProvider`(4×2)/`NextWidgetProvider`(4×1)：RemoteViews 白名单控件限制；刷新=30min 定时 + `CourseCache.save()`。
- 主题跟随手环：`WatchAppearance`（prefs `ev_watch_appearance`，follow 默认开）+ 本地 10 套主题离线内置；`Ui.applyTheme` 按 follow>local_theme>系统 判源。
- EV appearance 协议（rpk ≥1.6.145）：export 平铺 `homepageTemplate/baseFontSize/weekviewTemplate/appTheme`；`update_settings` 可写 nickname/homepage/模板/字号/appTheme/pinned。主题 10 套：blue/green/red/dark/gray/purple/light/warm/forest/amber。

## 构建（apk/，无 Gradle）
- 链：`aapt2 link → javac(JDK8，JDK22 会让 d8 NPE) → d8 → zipalign → apksigner`；SDK `$HOME/android-sdk`。
- 真机：荣耀 7X `BLN-AL20`，adb 序列号 `BTF4C17222009588`（手环插 USB 会以 `emulator-5554` 出现，**必须 -s 指定**）。变体：`bash build.sh`（EV）/`APP_VARIANT=evbox bash build.sh`；同签名（`tom/class/class/sign`）。
- ⚠️ aapt2 `--custom-package`；清单 sed 注入自检；`Variant.java` 运行期读 meta-data；AAR/jar 必须作为 d8 输入；libs/zxing-core.jar（gitignore 排除，build.sh 自动下载）。
- ⚠️ 提交前验「干净检出能编译」：`git archive HEAD apk` 到 /tmp 再 cp 本次文件构建（2026-09-28 main 曾编译不过）。
- ★★ **桌面插件军规**（2026-09-29 周插件两连坏，详见 ev-schedule-android `docs/week-widget-regression-analysis.md`）：① widget 布局只准 RemoteViews 白名单控件（LinearLayout/FrameLayout/RelativeLayout/GridLayout/TextView/Button/ImageView/ProgressBar 等，**禁 Space/纯 View/自定义 View**——EMUI 桌面直接拒载）；② provider 代码**禁用** `setInt(id,"setGravity",…)`/`setTextViewTextSize` 等 ReflectionAction（EMUI 8 Host 拒绝，整个 apply 失败弹「加载窗口小工具时出现问题」）；③ 崩溃发生在桌面进程、EMUI 吞异常，编译过+App 跑通≠插件能渲染——**修复后必须真机截图回归**（install -r → 触发 refreshAll → HOME+swipe → screencap；覆盖安装后坏绑定可能需删除重加）；④ 定位手法=RemoteViews 内容二分（换已验证布局→只挂点击→分批恢复 actions）。
- EV 仓构建坑：`npm run clean` 被 IDE 批量删除保护拦 → `node -e fs.rmSync` 删 `.temp_class`，再 `node scripts/bump-version.js && npx aiot release --enable-jsc`；工具链尾部的 rimraf 报错可忽略（rpk 已产出）。

## 埋点 / 通知
- `POST /api/activate?section=visitor-track`：`visitor_logs`（含 device/app/watch 白名单字段）+ `tracking_events` + PV/UV；`page_visit` 类型推 Mac（全局 15 条/分钟限流）；APK `os_brand`（harmony/emui/android）只作文案。
- 消息投递追踪 = `notify.pushNotification` → EvNotifier（Mac LaunchAgent `com.evnotifier.agent`，跑 `tools/ev-notifier/ev_notifier.py`；Redis stream `auth:notifications:stream`；收不到先查 LaunchAgent 存活 + Upstash 可达性）。详见 `docs/apk-tracking-telemetry-spec.md`。
- app-auth 改动推 main 后靠 Vercel 自动部署生效；`bash scripts/bump-version.sh` 自动 bump。

## 留言（chat）
- 下行 `{"action":"chat",id,text,ts}` → EV 存 `ev_chat_inbox`；上行只有实时 push，**不轮询**；`MessageActivity.markSeen()` 全局去重。

## 用户规则 / 仓库
- ★ 每完成实质改动**立即 commit+push（SSH，HTTPS 不通）**；版本号 patch +1（构建自动）；根目录保持干净，文档归 `docs/`。
- ★★ 跨仓（`tom/class/class` 等）写操作**必须先取得用户同意**。
- ⚠️ git 跟踪的文件不要中文名（NFC/NFD 错配）；中文 .md 用 ASCII 文件名。
- ⚠️ 多会话共享仓：**禁止 `git stash`/`pop`**；提交前 `git status --short` 分清别人的 WIP，只 add 自己的路径。
- 数据开放边界由手环侧守门人模型控制（interconnect 通道）；策略建议收敛成 `SYNC_ACCESS` 权限表。手环日历/闹钟写入不可行（SDK 无接口），定时提醒=手机 AlarmManager + sendNotify/chat。
