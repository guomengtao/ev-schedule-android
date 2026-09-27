# USB 真机调试全链路：<｜hy_place▁holder▁no▁813｜> / 测试 / 截屏 / 投屏 / 日志

> 目标：搞清楚「苹果笔记本 + 安卓手机（USB）」这条线，能不能把 `apk/build.sh` 打出来的
> 「Ev课程表」APK **秒装到手机、自动跑起来、抓到它的运行状态、截图/录屏回传 Mac**。
> 本文给出可落地的命令 + 本项目专属注意事项 + 建议新增的两个脚本改动。

---

## 0. TL;DR

| 需求 | 能不能做 | 怎么做（一句话） |
|---|---|---|
| 装到手机 | ✅ | `adb install -r -g apk/dist/EVSyncProbe-vX.apk` |
| 装完自动开 | ✅ | `adb install -r -g xxx.apk && adb shell am start -n com.application.watch.classschedule/.HomeActivity` |
| 打开指定页面 | ⚠️ 现在只有一个能开 | 其余 8 个 Activity `exported=false`，`am start` 会被拒（改法见 §5.1） |
| 看日志 | ⚠️ 目前基本是空的 | 本 App **完全不写 logcat**（grep 确认零命中），要先加日志桥（§7.2） |
| 源码断点 | ❌ 性价比极低 | 无 Gradle 工程，Android Studio 认不出源码模块（§7.4） |
| 截屏回 Mac | ✅ | `adb exec-out screencap -p > shot.png` |
| 录屏 | ✅ | `adb shell screenrecord --time-limit 30 /sdcard/d.mp4 && adb pull /sdcard/d.mp4` |
| 实时投屏 + 鼠标操控 | ✅ 需装 scrcpy | `brew install scrcpy && scrcpy` |
| 无线（免插拔） | ✅ Android 11+ | 一次配对后 `adb connect ip:port`（§7.5） |
| 手环联动调试 | ✅ | 同时看手机 logcat 和小米运动健康进程状态（§8） |

**结论**：USB 这条路足够撑起本项目 90% 的日常验证，缺的不是工具，是本 App 的两处"调试友好度"缺口
（`exported` 和 logcat 日志），建议先补上，成本 20 分钟。

---

## 1. 现场体检结果（实测，2026-09-28）

```
$ ~/android-sdk/platform-tools/adb --version
Android Debug Bridge version 1.0.41 / Version 37.0.1

$ adb devices -l
emulator-5554   device product:adb model:adb_board device:NuttX
emulator-5556   device product:adb model:adb_board device:NuttX

$ adb -s emulator-5554 shell getprop ro.build.version.release / ro.product.model
12.0.0  /  Emulator-Vela
```

三点要注意：

1. **adb 本体已经就绪**，就在 `~/android-sdk/platform-tools/adb`，不需要装，但**没进 PATH**
   （`which adb` 为空）→ 建议 `echo 'export PATH="$HOME/android-sdk/platform-tools:$PATH"' >> ~/.zshrc`。
2. **列表里这两台不是安卓手机**，是 `Emulator-Vela`（`device:NuttX`，很可能是 Vela 设备/模拟器，
   不属于 Android 运行环境，也没有标准 `pm` 命令）。也就是说：
   **你的安卓手机目前还没被 adb 认出来**，哪怕线插着。下一节就是解决这个。
3. `scrcpy` 未安装（投屏要现装）。

3. `scrcpy` 未安装（投屏要现装）。

### 1.1 补记：当天稍晚已跑通（BLN-AL20）

手机侧把 USB 用途切到 **「传输文件(MTP)」** + 开发者选项打开
**「USB 调试」/「仅充电」模式下允许 ADB 调试**、重插并确认 RSA 弹窗后，设备出现：

```
$ adb devices -l
BTF4C17222009588  device  usb:1048576X product:BLN-AL20 model:BLN_AL20 device:HWBLN-H   # ← 荣耀真机
emulator-5554     device  product:adb model:adb_board device:NuttX                     # ← Vela 设备，非模拟器
```

全链路一次通过（实测命令与输出）：

```bash
cd apk && ANDROID_SDK_ROOT=$HOME/android-sdk bash build.sh      # → dist/EVSyncProbe-v0.5.25.apk
adb -s BTF4C17222009588 install -r -g dist/EVSyncProbe-v0.5.25.apk   # Success
adb -s BTF4C17222009588 shell am start -W -n com.application.watch.classschedule/.HomeActivity
#   Status: ok   ThisTime: 764   TotalTime: 764   （冷启动首帧 764ms）
adb -s BTF4C17222009588 exec-out screencap -p > ev-home.png     # PNG image data, 1080 x 1920
adb -s BTF4C17222009588 shell dumpsys package com.application.watch.classschedule | grep versionName
#   versionName=0.5.25   ← 与刚构建产物一致，"同步安装"验证通过
```

真机截图暴露出两个问题，**已在 v0.5.27 修复**（同一套"构建→安装→截图→dump"流程验证）：

1. **异常直接糊到 UI 上**（已修）：首页第 1 步原本显示
   `初始化穿戴服务 — java.lang.IllegalStateException: not bond`。
   修法：在 `SyncEngine` 加 `humanize(Throwable)` / `hintFor(Throwable)`，
   把 SDK 的英文异常翻译成短中文短语（`not bond` → `手环未在本机配对`、
   `signature` → `签名校验未通过`、`SecurityException` → `权限被拒绝`…），
   并给对应的可执行建议；`String.valueOf(e/t)` 在 **所有** 面向 UI 的路径上都被替换掉，
   避免将来某个新页面又把 raw exception 露出来。
2. **底栏遮挡**（已修）：根因是 `Ui.BAR_HEIGHT_DP` 写死 62dp，而实际底栏
   （emoji 19sp + 文字 + padding）在这台机上实测 **86dp**，少预留 24dp。
   修法：`Ui.syncPaddingToBar()` 在布局完成后按 **实测** `bar.getHeight()` 设 paddingBottom，
   常量退化为首帧前的估计值 —— 以后改底栏样式不会再静默漏出一截内容。

   验证方式（比目测可靠）：`adb shell uiautomator dump` 后比对最后一个内容元素的
   `bounds` bottom 与底栏 top —— 修之前 `1713 > 1662`（重叠 17dp），修之后 `1608 < 1662`。

---

## 2. 环境准备：让安卓手机出现在 adb 列表里

### 2.1 手机侧（一次配置，长期有效）

1. 设置 → 关于手机 → **连点「版本号」7 次** → 提示已开启开发者选项。
2. 设置 → 系统与更新/更多设置 → 开发者选项 → 打开 **「USB 调试」**。
3. 同一页打开 **「USB 安装」**（MIUI/澎湃 OS 叫这个；OPPO/vivo 叫「通过 USB 安装应用」）。
   ⚠️ **这是新手第一大坑**：不开它，`adb install` 会卡住或报 `INSTALL_FAILED_USER_RESTRICTED`，
   而 `adb devices` 明明显示 `device`。
4. 插线后，下拉通知栏把 **USB 用途改成「传输文件/Android Auto」**（有些机型停在「仅充电」时 adb 不稳）。
5. 首次连接，手机会弹 **「允许 USB 调试吗？（RSA 指纹）」→ 勾始终允许 → 确定**。

小米/红米额外备注：如果调试中 `adb install` 被 MIUI 二次拦截，去开发者选项里
**关掉「MIUI 优化」**并重启；另外可能需要登录小米账号才能 USB 安装。

### 2.2 Mac 侧

```bash
# 1) adb 进 PATH（zsh）
echo 'export PATH="$HOME/android-sdk/platform-tools:$PATH"' >> ~/.zshrc
source ~/.zshrc

# 2) 可选：投屏神器
brew install scrcpy
# （scrcpy 依赖 adb，装完共用同一份 platform-tools）
```

### 2.3 验证

```bash
adb devices -l
# 正常应出现一行：  <10位序列号>  device product:xxx model:M2102K1C device:xxx transport_id:N
# 若显示 unauthorized → 手机上没点允许 → adb kill-server 后重插再来
# 若显示 offline      → 换线/换口（很多 Type-C 数据线只充电），或 adb kill-server
```

---

## 3. 一条命令：构建 → 安装 → 打开

`build.sh` 每次会把产物同时拷一份到 `apk/EVSyncProbe.apk`（固定路径，方便脚本用），
版本号在 `apk/version.env`（每次构建自动 patch+1）。建议在 `apk/tools/dev.sh` 里放：

```bash
#!/bin/bash
# 用法：bash apk/tools/dev.sh           # EV 变体：构建+安装+启动+清logcat
#       APP_VARIANT=evbox bash apk/tools/dev.sh
set -euo pipefail
cd "$(dirname "$0")/.."                  # → apk/
VARIANT="${APP_VARIANT:-ev}"
PKG="com.application.watch.classschedule"
[ "$VARIANT" = evbox ] && PKG="com.application.watch.evbox"

bash build.sh                                                   # 1. 构建（自动 bump 版本号）
APK="$(ls -t dist/*-v*.apk | grep -i "^dist/$( [ "$VARIANT" = ev ] && echo EVSync || echo EvBoxSync )" | head -1)"

adb install -r -g "$APK"                                        # 2. -r 覆盖安装 / -g 自动授予清单权限
adb shell am force-stop "$PKG"                                  # 3. 冷启动
adb logcat -c                                                   # 4. 清掉历史日志
adb shell am start -n "$PKG/.HomeActivity"                      # 5. 起来
echo "=== 已安装版本 ==="
adb shell dumpsys package "$PKG" | grep -m1 "versionName"
```

> `-g` 这里很有用：它会一次性授予 `POST_NOTIFICATIONS / VIBRATE / FOREGROUND_SERVICE` 等清单权限，
> 省得每次新装都要手动点授权（留言提醒和前台服务都需要它们）。

---

## 4. 安装环节：本项目特有的两个雷

### 4.1 签名必须前后一致，否则不让覆盖安装

本 APK 用的是 **EV 快应用的 rpk 签名密钥**（`private.pem + certificate.pem`，
`build.sh` 自动从 `tom/class/class/sign` 找）。这意味着：

- 只要还用同一把 key，`adb install -r` 可以无限覆盖升级，**数据不丢**（小米健康配对状态、留言队列都在）。
- 一旦 key 换了 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

```bash
# 卸载重装（会清空 App 数据，测首次启动/首次授权时用）
adb uninstall com.application.watch.classschedule
adb install -r -g apk/dist/EVSyncProbe-v0.5.26.apk

# 只清数据、不重装（回到"第一次打开"的状态）
adb shell pm clear com.application.watch.classschedule
```

### 4.2 装在手机上的到底是哪一版？（桌面名不带版本号）

按之前定的产品规则，**桌面名是「Ev课程表」，不带版本号**，所以装完很难肉眼判断版本。用这两条确认：

```bash
adb shell dumpsys package com.application.watch.classschedule | grep -E "versionCode|versionName"
adb shell dumpsys package com.application.watch.classschedule | grep -m1 firstInstallTime
```

### 4.3 INSTALL_FAILED 速查

| 报错 | 原因 | 处理 |
|---|---|---|
| `UPDATE_INCOMPATIBLE` | 换了签名 key | 用回原 key；或先 uninstall |
| `USER_RESTRICTED` | 手机「USB 安装」没开 / MIUI 拦截 | 开发者选项打开 USB 安装；关 MIUI 优化 |
| `NEWER_SDK / OLDER_SDK` | minSdk 24 vs 手机系统太老 | 换机（本包 minSdk=24，安卓 7 起，一般没问题） |
| `NO_MATCHING_ABIS` | 打进来 native so 而 ABI 不符 | 本包无 so，不会发生 |
| `UNKNOWN_APP_INSTALLER` | MIUI 要求账号登录 | 登录小米账号 |
| 卡住不动 | 手机弹了安装确认框没点 | 看手机屏幕点一下（scrcpy 投出来更方便） |

---

## 5. 打开任意页面 / 自动化操作

### 5.1 现状：只有首页能被 adb 直接唤起

`apk/AndroidManifest.xml` 里 **只有 `HomeActivity` 是 `exported="true"`**（它是 launcher），
其余 8 个 Activity 都没写 `exported`，按 Android 12+ 规则等价于 `exported="false"`。
`adb shell am start` 是以 **shell(UID 2000)** 身份发起跨进程调用，会被拒绝：

```
SecurityException: Permission Denial: starting ... not exported from uid 10xxx
```

**改法**：给调试常用的几个页面加 `android:exported="true"`（自用工具，无 intent-filter 的话外部只能显式指定类名调用，风险可接受；不想要可以给 DebugActivity 单独加 `android:permission="android.permission.DUMP"` 之类门槛）。

```xml
<activity android:name="...DebugActivity"    android:exported="true" ... />
<activity android:name="...TransferActivity" android:exported="true" ... />
<activity android:name="...MessageActivity"  android:exported="true" ... />
```

### 5.2 页面直达表（改完之后）

```bash
P=com.application.watch.classschedule
adb shell am start -n $P/.HomeActivity            # 首页（当前唯一可用）
adb shell am start -n $P/.DebugActivity           # 调试（4 步同步、单Frame volume）
adb shell am start -n $P/.TransferActivity        # 导入/导出 JSON
adb shell am start -n $P/.MessageActivity         # 留言
adb shell am start -n $P/.SettingsActivity        # 设置
adb shell am start -n $P/.HomepageSettingsActivity# 首页设置
adb shell am start -n $P/.FastActivateActivity    # 高级版一键激活
adb shell am start -n $P/.DonateActivity          # 打赏二维码
adb shell am start -n $P/.BatteryGuideActivity    # 省电引导
```

常用变体：

```bash
# 带测速：返回 TotalTime（首帧耗时），做冷/热启动对比很方便
adb shell am start -W -S -n $P/.HomeActivity
# -S = 先 force-stop（冷启动）；不加 -S = 热启动
```

### 5.3 模拟输入（写冒烟脚本不用碰手机）

```bash
W=$(adb shell wm size | awk -F'[:x ]' '{print $3}'); H=$(adb shell wm size | awk -F'[:x ]' '{print $4}')
adb shell input tap $((W/2)) $((H*7/10))          # 点屏幕 70% 高度（通常是底栏区域）
adb shell input text 'hello%swatch'               # %s = 空格
adb shell input keyevent KEYCODE_BACK             # 返回键
adb shell input swipe 500 1500 500 600 300        # 上滑（300ms）
```

配 `adb exec-out screencap` 就可以写出"点一下→截一张→比对文本"的半自动回归，比手点稳。

---

## 6. 截屏 / 录屏 / 投屏

### 6.1 单次截图（最省事）

```bash
# ✅ 推荐：直接 stdout 重定向，省掉 pull 那一步
adb exec-out screencap -p > ~/Desktop/ev-$(date +%H%M%S).png

# 传统做法（某些老设备/定制 ROM 上 exec-out 输出被 \r\n 污染时才用）
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png .
```

> ⚠️ **不要**用 `adb exec-out screencap -p | perl -pe 's/\x0D\x0A/\x0A/g' > shot.png`。
> 实测（2026-09-28 / 荣耀 BLN-AL20）这么干会把 PNG 内部合法的 `0d0a`（IHDR 里的 magic
> `89 50 4E 47 0D 0A 1A 0A`）也一起替换掉，文件变成 `file: data`，看图软件全打不开。
> 用文本方式处理二进制流本身就是错的。
> 如果确实遇到 `\r\n` 污染（个别 ROM/Windows 主机），正确姿势是绕开管道：
> `adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png .`

### 6.2 录屏

```bash
adb shell screenrecord --time-limit 30 --bit-rate 4000000 /sdcard/ev-flow.mp4
adb pull /sdcard/ev-flow.mp4 ~/Desktop/
```

- `screenrecord` 最长 180s，**不带音频**；想带解说就用 scrcpy 的录制（见下）。
- 演示「留言从手环上来 → 通知弹出」这种跨页、有时序的动作时，录屏比截图有效得多。

### 6.3 实时投屏 + 鼠标键盘操控（强烈建议装）

```bash
brew install scrcpy
scrcpy                      # 默认 1080p 窗口
scrcpy -m 1024              # 限制投屏分辨率，更流畅
scrcpy --record demo.mp4    # 边投边录
scrcpy -s <serial>          # 多设备时指定
```

macOS 上实测：投屏延迟 50-100ms，可以直接用鼠标点 App、用键盘打字，
**第一次 USB 安装的确认弹窗（见 §4.3）也能在电脑上点掉**，这是它最实用的地方。

### 6.4 布局/层级检查（无 Android Studio 时的替代）

```bash
# 当前栈顶 Activity + View 结构
adb shell dumpsys activity top
# UI 自动化层级 XML（含 text / resource-id / bounds，可用于断言）
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml && python3 -c "print(open('w.xml').read())" | head -40
```

以前那个「底栏把内容遮住」的问题（`docs/底部导航栏高度适配问题分析.md`），
用 `uiautomator dump` 看 bottom bar 的 bounds 与列表最后一个 item 的 bounds，一测就知道有没有重叠。

---

## 7. 调试

### 7.1 logcat 基础

```bash
adb logcat -c                                            # 清屏
adb logcat -s AndroidRuntime:E                            # 只看崩溃堆栈
adb logcat --pid=$(adb shell pidof $P) -v threadtime      # 只看本 App 进程
adb logcat -b crash                                      # 上次 ANR/崩溃的独立缓冲
adb logcat | grep -iE "xms|wearable|mi.health"            # 顺带看 SDK / 小米运动的日志
```

### 7.2 ⚠️ 本项目当前缺陷：logcat 基本是空的

全 `apk/src` 目录 grep `android.util.Log` → **零命中**。也就是说现在 App 运行的所有信息
（同步四步、ping/export/import、超时、错误）都只写进了 App 内部的日志面板，
**Mac 这边 logcat 一条也看不到**，debugger 也无从下手。这是目前真机调试最大的短板。

建议加一个极薄的统一日志桥（20 行搞定，之后所有类替换调用即可）：

```java
public final class EvLog {                       // src/com/application/watch/classschedule/EvLog.java
    private static final String TAG = "EVProbe";
    private EvLog() {}
    public static void i(String m){ android.util.Log.i(TAG, m); }
    public static void w(String m){ android.util.Log.w(TAG, m); }
    public static void e(String m, Throwable t){ android.util.Log.e(TAG, m, t); }
}
```

配合 `SyncEngine` 里现有的 `log(...)` 统一出口加一行 `EvLog.i(msg)`，
立刻就能在 Mac 上：

```bash
adb logcat -s EVProbe -v threadtime
```

这一步做完，`docs/interconnect 打通经验速查.md` 里那句 `adb logcat -s EVProbe AndroidRuntime`
才重新名副其实。

### 7.3 想用 `run-as` 看 App 私有数据？需要 debuggable

当前清单 `<application>` 没有 `android:debuggable`（默认 false），所以：

- ❌ `adb exec-out run-as com.application.watch.classschedule cat .../ev_settings.xml` → `not debuggable`
- ❌ `adb jdwp` / `adb shell am start -D` 挂调试器
- ❌ Android Studio Profiler 的 CPU/Memory 追踪

补法（dev 只用，别出正式包）：在 `build.sh` 的 **sed 注入那一步**加一条：

```bash
sed -e "s|android:theme=\"@android:style/Theme.Material.NoActionBar\"|android:theme=\"@android:style/Theme.Material.NoActionBar\" android:debuggable=\"${DEV_DEBUG:-false}\"|" \
    ...
```

然后 `DEV_DEBUG=true bash build.sh` 打调试包，之后：

```bash
# 直接把 App 的 SharedPreferences 掏出来看（记得 bug 排查：省电开关、preferredNode、留言队列）
adb exec-out run-as $P cat /data/data/$P/shared_prefs/ev_settings.xml
adb exec-out run-as $P cat /data/data/$P/shared_prefs/ev_message_queue.xml
```

> 更轻量的替代：`<profileable android:shell="true"/>`（API 29+），只开 Profiler 不开调试，
> 与 release 签名兼容性更好，建议和 debuggable 二选一组合使用。

### 7.4 Android Studio 在这里能干什么？

| 能力 | 行不行 | 说明 |
|---|---|---|
| 设备管理器 / Logcat / Device File Explorer | ✅ | 直接用，体验比命令行好，特别是 logcat 过滤 UI |
| Layout Inspector | ✅ | 无 Gradle 也能连进程（需要 debuggable 或 profileable） |
| CPU / Memory Profiler | ✅ | 需要上面那两个标记 |
| **源码断点调试** | ❌ | 本项目没有 Gradle 工程，AS 无法把 `apk/src/*.java` 与运行中的 dex 对应起来 |
| 直接 Run ▶ 到手机 | ❌ | 没有 Gradle 就 build 不出来，硬要用就得自己写 `local.properties` + 手糊 build.gradle，不值 |

一句话：**把 AS 当"带 GUI 的 logcat + Profiler"，构建仍走 `build.sh`**。

### 7.5 免插拔：无线调试（Android 11+）

```bash
# 1) 手机：开发者选项 → 无线调试 → 使用配对码配对
adb pair 192.168.1.23:37281      # 输入手机显示的配对码
adb connect 192.168.1.23:5555
adb devices                       # 出现 192.168.1.23:5555  device

# 老机型/已 root：adb tcpip 5555 → adb connect ip:5555（仍需先插一次线）
```

好处：`build.sh && adb install` 一条龙时不用起身找线，配合 scrcpy 完全"无线桌面端开发"。

---

## 8. 本项目专属：真机上的 interconnect 调试

> 这是本 App 与其它普通 Android App 最大的不同：**它本身没有业务数据源，真值是手环 + 小米运动健康**。
> 所以下面的命令比通用 CRUD 调试更重要。

```bash
P=com.application.watch.classschedule

# ① 前置检查：手机装没装「小米运动健康 / 小米穿戴」（AAR 强依赖，没有就一步都走不动）
adb shell pm list packages | grep -iE "wearable|mi.health|xiaomi"
adb shell pm dump com.mi.health | grep -m1 versionName

# ② 前台服务活着吗（留言上行靠它）
adb shell dumpsys activity services $P/.SyncService

# ③ 有没有被省电策略杀掉（BatteryGuideActivity 就是讲这个的）
adb shell dumpsys deviceidle whitelist | grep $P || echo "不在白名单"

# ④ 模拟切后台 / 息屏，验证留言能不能收到
adb shell am make-uid-idle $P
adb shell dumpsys appops get $P RUN_IN_BACKGROUND

# ⑤ 权限（重装后需要先给通，不然 Android 13+ 通知不弹）
adb shell pm grant $P android.permission.POST_NOTIFICATIONS

# ⑥ 手环侧也在 adb 上时的双端对照（像今天这种"上一台是 EV、另一台在 Vela 上"的情况）
adb devices -l
adb -s <android-serial> logcat -s EVProbe &
adb -s <watch-serial>   shell ...        # Vela shell 能力有限，一般只能看看属性

# ⑦ 抓"点一下 → 走完四步（查设备→授权→注册监听→发消息）"的完整链路
adb logcat -c && adb shell am start -n $P/.DebugActivity && adb logcat -s EVProbe
```

---

## 9. 建议的落地顺序（性价比从高到低）

| # | 动作 | 成本 | 收益 |
|---|---|---|---|
| 1 | adb 加入 PATH + 手机开「USB 安装/USB 调试」+ `brew install scrcpy` | 10 min | §3~§6 全部立马可用 |
| 2 | 新增 `apk/tools/dev.sh`（构建+安装+冷启动+版本号校验） | 15 min | 每次验证从 3 步变 1 步 |
| 3 | 加 `EvLog` 日志桥，`SyncEngine.log()` 出口接 logcat | 20 min | 真机能被"看见"，排查提速最大 |
| 4 | debug 构建：`android:debuggable` / `profileable` 由 build.sh 变量注入 | 15 min | run-as 查 prefs、Profiler 可用 |
| 5 | 给 Debug/Transfer/Message 加 `exported="true"` | 5 min | adb 可直达内页，配 `input tap` 写冒烟回归 |

前 2 步今天就能做完，后面的建议在下一次涉及 interconnect 排查前补上。

---

## 10. 命令速查

```bash
# —— 设备 ——
adb devices -l                       # 看连接
adb kill-server                      # 卡住时先杀 daemon
export ANDROID_SERIAL=<serial>       # 多设备时后续命令自动走这台

# —— 安装 / 卸载 ——
adb install -r -g dist/EVSyncProbe-v0.5.26.apk
adb uninstall com.application.watch.classschedule
adb shell pm clear com.application.watch.classschedule

# —— 版本校验（桌面名不带版本号，必须这么查）——
adb shell dumpsys package $P | grep -E "versionCode|versionName"

# —— 启动 / 停止 ——
adb shell am start -W -S -n $P/.HomeActivity
adb shell am force-stop $P

# —— 截屏 / 录屏 / 投屏 ——
adb exec-out screencap -p > shot.png
adb shell screenrecord --time-limit 30 /sdcard/d.mp4 && adb pull /sdcard/d.mp4
scrcpy -m 1024

# —— 日志 ——
adb logcat -c && adb logcat -s EVProbe -v threadtime
adb logcat -s AndroidRuntime:E
adb logcat -b crash

# —— 状态 ——
adb shell dumpsys activity top
adb shell dumpsys activity services $P/.SyncService
adb shell dumpsys deviceidle whitelist | grep $P
adb shell pm list packages | grep -i mi.health

# —— 私有数据（需 debuggable）——
adb exec-out run-as $P cat /data/data/$P/shared_prefs/ev_settings.xml
```
