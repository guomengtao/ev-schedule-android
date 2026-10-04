# Mac Air 每日 04:30 自动重启方案

> 目标：每天凌晨 4:30 自动重启一次 Mac Air，并把每次重启时间记录下来，方便人工确认"是否真的执行了"。

---

## 一、方案原理

> **先说明一个关键点：你的 Mac Air 夜间会睡眠。** launchd 的定时任务在系统睡眠时**不会准时触发**，只会等系统被唤醒后又过了该时间才补触发。所以必须**先用 `pmset` 在 04:25 把机器唤醒**，才能保证 04:30 能准时重启。整体流程分四步：

1. **唤醒（macOS 自身电源计划）**：用 `pmset repeat` 每天 04:25 自动唤醒/开机。这样到 04:30 时机器处于唤醒状态，launchd 才能准点触发。
2. **`restart.plist`（LaunchDaemon）**：在每天 04:30 触发，先写一条"准备重启"日志，然后立即执行 `sudo shutdown -r now` 重启系统。由于是系统级 daemon，以 `root` 身份运行，无需输密码。
3. **`boottime.plist`（LaunchDaemon）**：在每次开机后立即运行，把"本次开机时间"追加到日志，作为"重启确实发生了"的最终证据。

结合上面两条日志，就能完整对账：

```
04:30:12  →  准备重启（本次由计划触发）
04:31:05  →  系统开机（证明重启成功，而不是休眠/假醒）
```

只要某天**只看到"准备重启"却没有随后的"系统开机"**，就说明那次重启失败了，可据此排查。

---

## 二、文件结构

| 文件 | 作用 |
|------|------|
| （电源计划）`pmset repeat` | 每天 04:25 先把机器唤醒，保证 04:30 能准点触发 |
| `/Library/LaunchDaemons/com.user.dailyrestart.plist` | 每天 04:30 触发重启 |
| `/Library/LaunchDaemons/com.user.bootlog.plist` | 每次开机后记录开机时间 |
| `/usr/local/bin/daily_restart.sh` | 重启前写日志并执行重启 |
| `/usr/local/bin/log_boot.sh` | 开机后写"本次开机时间" |
| `/var/log/daily_reboot.log` | 统一日志文件（持久） |

> 日志不放在用户目录（`~/`），放在 `/var/log`，避免因用户不存在/权限导致写不进去。

---

## 三、实施步骤

### 0）先设置每天 04:25 自动唤醒（关键）

> 因为电脑睡眠，这步必须做，否则 04:30 的 launchd 可能不执行。

```bash
# 每天（周一~周日都含）04:25 唤醒/开机
sudo pmset repeat wakeorpoweron MTWRFSU 04:25:00

# 确认电源计划已写入
pmset -g sched
```

> 注意：`pmset repeat` 的重复计划**固定天天**，如果你哪天不想重启（比如当天要用），可以临时清理：
> ```bash
> sudo pmset repeat cancel
> ```

### 1）创建日志脚本

新建脚本 `/usr/local/bin/log_boot.sh`：

```bash
#!/bin/bash
# 记录本次开机时间，作为"重启已发生"的证据
LOG=/var/log/daily_reboot.log
printf "[BOOT]  %s system booted\n" "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG"
```

新建脚本 `/usr/local/bin/daily_restart.sh`：

```bash
#!/bin/bash
# 04:30 触发：先记日志，再重启
LOG=/var/log/daily_reboot.log
printf "[PLAN]  %s scheduled restart fired\n" "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG"
# 确保日志真正刷入磁盘，避免重启还没写进去
sync
# 延时 5 秒，给日志落盘留时间
sleep 5
/sbin/shutdown -r now
```

赋予执行权限：

```bash
sudo chmod 755 /usr/local/bin/log_boot.sh /usr/local/bin/daily_restart.sh
```

### 2）创建两个 LaunchDaemon 配置文件

`/Library/LaunchDaemons/com.user.dailyrestart.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.user.dailyrestart</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/daily_restart.sh</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        <key>Hour</key>
        <integer>4</integer>
        <key>Minute</key>
        <integer>30</integer>
    </dict>
    <key>RunAtLoad</key>
    <false/>
</dict>
</plist>
```

`/Library/LaunchDaemons/com.user.bootlog.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.user.bootlog</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/log_boot.sh</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
</dict>
</plist>
```

> launchd 会在每次开机后自动加载 `/Library/LaunchDaemons` 下的 plist，`RunAtLoad=true` 保证一开机就执行 `log_boot.sh`。

### 3）加载并启用

```bash
# 先卸载（若已存在）
sudo launchctl bootout system /Library/LaunchDaemons/com.user.dailyrestart.plist 2>/dev/null
sudo launchctl bootout system /Library/LaunchDaemons/com.user.bootlog.plist 2>/dev/null

# 再加载
sudo launchctl bootstrap system /Library/LaunchDaemons/com.user.dailyrestart.plist
sudo launchctl bootstrap system /Library/LaunchDaemons/com.user.bootlog.plist

# 确认已加载
sudo launchctl print system/com.user.dailyrestart
sudo launchctl print system/com.user.bootlog
```

### 4）验证是否触发

使用 launchd 的 `kickstart` 手动触发一次（**会真的重启，请先保存工作**）：

```bash
sudo launchctl kickstart -k system/com.user.dailyrestart
```

重启后查看日志确认：

```bash
cat /var/log/daily_reboot.log
```

应看到类似两行：

```
[PLAN]  2026-09-30 04:30:01 scheduled restart fired
[BOOT]  2026-09-30 04:31:08 system booted
```

---

## 四、日常确认执行情况

### 1）查看日志

```bash
cat /var/log/daily_reboot.log
```

### 2）每天是否执行（对账脚本）

可选，放到 cron / launchd，或手动跑：

```bash
#!/bin/bash
LOG=/var/log/daily_reboot.log
echo "最近 7 天重启对账："
for i in $(seq 0 6); do
  d=$(date -v-${i}d '+%Y-%m-%d')
  plan=$(grep "$d" "$LOG" | grep -c "\[PLAN\]")
  boot=$(grep "$d" "$LOG" | grep -c "\[BOOT\]")
  printf "  %s 计划触发=%s  开机=%s  %s\n" "$d" "$plan" "$boot" \
    "$([ "$plan" -ge 1 ] && [ "$boot" -ge 1 ] && echo '✓ 正常' || echo '!! 异常')"
done
```

### 3）系统侧佐证

- 系统统一日志中找重启/开机记录：
  ```bash
  log show --last 7d --predicate 'eventMessage CONTAINS "Previous shutdown cause"' --style compact
  log show --last 7d --predicate '(process == "kernel") && (eventMessage CONTAINS "system boot")' --style compact
  ```
- 本机已运行时长：
  ```bash
  uptime
  sysctl kern.boottime
  ```

---

## 五、注意事项与故障排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 每天到 04:30 不重启 | **睡眠导致 launchd 没触发** | 关键点：确认第 0 步 `pmset repeat` 已生效；另外不要在 04:30 前后手动让机器进入深度休眠/关机 |
| 04:25 到了没唤醒 | 接电/电池策略、`pmset repeat` 被清掉 | `pmset -g sched` 检查计划仍在；确认不是关机或电池严重耗尽 |
| 日志只有 `[PLAN]` 没有 `[BOOT]` | 重启失败，或重启后没进系统 | 看 `/Library/Logs/DiagnosticReports`、console.app 的 panic 记录 |
| 提示权限不足 | plist 或脚本属主/权限不对 | 确保属主 root:wheel，权限 644(plist) / 755(脚本) |
| 想改时间 | 修改 plist 里 `Hour/Minute`（和 `pmset` 的唤醒时间） | 改完重新 `bootout` + `bootstrap` |
| 想彻底停用 | - | `sudo pmset repeat cancel` + `sudo launchctl bootout system /Library/LaunchDaemons/com.user.dailyrestart.plist` |

> 强调：**因为你的 Mac 会睡眠，"04:25 唤醒 + 04:30 重启"是必须组合，缺了唤醒那步，重启大概率不执行。** 设置后请先手动验证一次（见"验证是否触发"第 3 步）。

---

## 六、摘要

- 用一个苹果电源计划 `pmset repeat` + 两个 LaunchDaemon 实现：**04:25 先唤醒 → 04:30 重启 → 开机时回写日志**。
- 日志集中在 `/var/log/daily_reboot.log`，格式带 `[PLAN]` / `[BOOT]` 标记，方便一眼对账每天是否真正重启。
- 提供了手动触发、日志查看、对账脚本、故障排查清单，保证可确认、可回滚、可排障。

---

## 七、部署实际执行记录（2026-09-30 完成）

### 安装完成的组件

| 组件 | 状态 | 说明 |
|------|------|------|
| `/usr/local/bin/daily_restart.sh` | ✅ 已安装 | 权限 `755 / root`，04:30 写日志并 `shutdown -r now` |
| `/usr/local/bin/log_boot.sh` | ✅ 已安装 | 权限 `755 / root`，每次开机写 `[BOOT]` 日志 |
| `/Library/LaunchDaemons/com.user.dailyrestart.plist` | ✅ 已加载 | 权限 `644 / root:wheel`，`StartCalendarInterval` = 04:30 |
| `/Library/LaunchDaemons/com.user.bootlog.plist` | ✅ 已加载 | 权限 `644 / root:wheel`，`state = running` |
| `pmset repeat wakeorpoweron MTWRFSU 04:25:00` | ✅ 已生效 | 每天 04:25 唤醒，输出 `wakepoweron at 4:25AM every day` |

> 以上为 2026-09-30 在本机实际执行的验证结果，`ls -l` / `pmset -g sched` / `launchctl print` 均已核对通过。

### 已生效的运行逻辑

```
每天 04:25  机器自动唤醒
每天 04:30  dailyrestart 写 [PLAN] 日志 → shutdown -r 重启
重启开机后   bootlog 写 [BOOT] 日志
```

### 日常使用命令（备忘）

```bash
cat /var/log/daily_reboot.log            # 查看历史重启记录
pmset -g sched                            # 确认电源计划仍在
sudo launchctl print system/com.user.dailyrestart   # 查看 daemon 状态
```

立即手动测试一次（会真的重启电脑）：

```bash
sudo launchctl kickstart -k system/com.user.dailyrestart
```

临时停用今天（当天不重启）：

```bash
sudo pmset repeat cancel && sudo launchctl bootout system /Library/LaunchDaemons/com.user.dailyrestart.plist
```

重新开启：

```bash
sudo launchctl bootstrap system /Library/LaunchDaemons/com.user.dailyrestart.plist
sudo pmset repeat wakeorpoweron MTWRFSU 04:25:00
```

---

## 八、按次自动登录：只有凌晨那次重启免密直进（2026-10-04 部署）

### 需求与前提

- 需求：**仅 04:30 计划重启**后自动登录进桌面；手动重启/意外断电等其他情况照常要密码。
- 前提：**FileVault 必须关闭**（macOS 硬限制：FV 开启时重启场景下自动登录被整体禁用，会卡在解锁屏）。
  实锤：2026-10-04 凌晨 04:30:02 计划重启成功，但开机脚本 08:26 才跑 —— 卡解锁屏 4 小时。
  → 2026-10-04 已执行 `sudo fdesetup disable`（`FileVault is Off`）+ `sudo sysadminctl -autologin set Banner -password …`（登记自动登录凭据，密码只进系统安全域，不落任何脚本）。

### 实现机制（开关在脚本里，密码不在）

自动登录本身是全局开关，没有"按次"原生选项；利用"凌晨重启由我们自己的 daemon 触发"这一事实做**临时开关**：

```
04:30  daily_restart.sh：touch 标记文件 + 写 autoLoginUser=Banner → shutdown -r
开机    loginwindow 因 autoLoginUser 存在 → 自动登录进桌面
+60s   log_boot.sh：检测到标记 → 消费标记 + 删除 autoLoginUser
之后    手动重启/断电重启 → autoLoginUser 不存在 → 照常要密码 ✓
```

| 文件 | 本节改动 |
|---|---|
| `/usr/local/bin/daily_restart.sh` | shutdown 前加：`touch /var/log/.ev_autologin_pending` + `defaults write …/loginwindow autoLoginUser -string Banner` |
| `/usr/local/bin/log_boot.sh` | 开机区分"计划重启（有标记）→ 消费标记；非计划 → 保持关闭"，并**延时 60 秒**后删除 autoLoginUser（不能太早，会跟 loginwindow 自动登录抢跑把人挡在登录窗） |

### 验证与备忘

```bash
cat /var/log/daily_reboot.log      # 新增 [ALOGIN] 三种行：armed / consumed / stays off
defaults read /Library/Preferences/com.apple.loginwindow autoLoginUser   # 平时应报"找不到 key"= 关闭态
```

- **明天 04:30 后确认**：日志出现 `[ALOGIN] … auto-login consumed` 且人不卡登录窗 = 全链路通。
- 若卡在登录窗 → 说明 `sysadminctl -autologin set` 那步没成功，重跑：
  `sudo sysadminctl -autologin set Banner -password '开机密码'`
- 立即实测一次（**会真的重启，先存工作**）：`sudo launchctl kickstart -k system/com.user.dailyrestart`
- 恢复"每次重启都要密码"：把 daily_restart.sh 里 `touch 标记 + defaults write` 两行注释掉即可。
- 彻底关闭自动登录能力：`sudo defaults delete /Library/Preferences/com.apple.loginwindow autoLoginUser`（键平时本就不存在）。

---

## 九、重启前先弹窗、可取消（60 秒无人处理才重启，2026-10-05 部署）

### 需求

半夜 04:30 自动重启会打断正在用机器的人（2026-10-05 凌晨就是差点被重启，靠手动取消才拦下）。改为：**到点先弹提示窗，1 分钟内无人处理才重启；点「取消本次重启」则当晚不重启。**

### 结构

```
04:30  launchd(root, com.user.dailyrestart)
        └─ /usr/local/bin/daily_restart.sh
             ├─ 写 [PLAN] 日志
             ├─ 调 /usr/local/bin/ev_restart_prompt.sh   ← 弹窗，最多等 60 秒
             │    ├─ 点「取消本次重启」→ 返回 0 → daily_restart 直接 exit（不重启、不开自动登录）
             │    └─ 超时/点「立即重启」/弹窗显示不出来 → 返回 1 → 继续
             ├─ touch /var/log/.ev_autologin_pending + 打开 autoLoginUser（§八 按次自动登录）
             └─ /sbin/shutdown -r now
```

| 文件 | 作用 |
|---|---|
| `/usr/local/bin/ev_restart_prompt.sh` | 弹窗逻辑。退出码 **0 = 取消**、**1 = 继续重启**。默认 60 秒、**默认按钮＝「取消本次重启」**（半夜误按回车不会重启） |
| `/usr/local/bin/daily_restart.sh` | 先调提示脚本，按退出码决定是否重启（取消时**不写**自动登录标记，保证不误免密） |

### 三个关键实现点（踩坑记录）

1. **LaunchDaemon 在系统域、看不到 GUI** → 必须 `launchctl asuser <uid> osascript …` 切到已登录用户会话才能弹窗；uid 取 `stat -f%u /dev/console`。已实测：以 root 经 asuser 弹窗返回 `gave up:true`（弹窗正常显示并超时）。
2. **睡屏状态下弹窗看不见** → 弹窗前 `caffeinate -u -t 2` 先唤醒屏幕。
3. **不要用同步阻塞写法**：`display dialog` 是模态的，靠 `giving up after 60` 自带超时返回，**不能**自己写 loop 等（会卡住 launchd 任务）。

### 验证（已跑过的安全测试）

```bash
bash -n /usr/local/bin/daily_restart.sh /usr/local/bin/ev_restart_prompt.sh     # 语法
# 提示脚本四条分支（用假 osascript，不弹窗不重启）：cancel→0 / timeout→1 / now→1 / no-gui→1
# 重启脚本两条路径（用桩 shutdown + 桩 defaults + 临时 marker/log，绝不真重启）：
#   取消分支 → 不调 shutdown、不建自动登录标记 ✅
#   继续分支 → 建标记 + 写 autoLoginUser + 调 shutdown ✅
```

### 备忘与回滚

- 日志新增两种行：`[PROMPT] … continue -> restart (<reason>)` 与 `[CANCEL] … user cancelled this scheduled restart`。
- 改等待秒数：编辑 `ev_restart_prompt.sh` 里 `SECS=${EV_PROMPT_SECS:-60}`。
- **回滚成「到点直接重启」**：`sudo rm /usr/local/bin/ev_restart_prompt.sh`（daily_restart.sh 检测到提示脚本缺失会自动跳过提示、直接重启）。
- 临时停用整套计划重启：`sudo launchctl bootout system/com.user.dailyrestart`（要恢复：`sudo launchctl enable … && sudo launchctl bootstrap system /Library/LaunchDaemons/com.user.dailyrestart.plist`）。
- ⚠️ **唯一不确定项**：若此刻机器停在**登录窗**（睡眠中唤醒、屏幕锁定），弹窗可能显示不出来 → 会走「60 秒超时 → 照常重启」。这与「无人处理就重启」的约定一致，但如果你希望**锁屏时也别重启**，需要改成「锁屏就跳过今晚」（可在提示脚本里先判断 `python3 -c 'import Quartz'`/`ioreg` 锁屏状态，待定）。

### 源码归档（/usr/local/bin 里的真身同步存档）

两份系统脚本在仓库里有可追溯副本，改完记得同步：

```bash
ls tools/mac-restart/          # daily_restart.sh / ev_restart_prompt.sh
# 部署（与安装脚本一致）：
sudo install -m 755 tools/mac-restart/daily_restart.sh      /usr/local/bin/daily_restart.sh
sudo install -m 755 tools/mac-restart/ev_restart_prompt.sh  /usr/local/bin/ev_restart_prompt.sh
```