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