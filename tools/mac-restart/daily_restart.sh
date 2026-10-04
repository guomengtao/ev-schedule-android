#!/bin/bash
# 04:30 计划重启：先弹「可取消」提示（默认 60 秒）
#   · 点「取消本次重启」 → 中止本次（不重启、不打开自动登录）
#   · 60 秒无人处理 / 点「立即重启」 → 打标记 + 仅本次打开自动登录 + 重启
# 「按次自动登录」原理见 ev_restart_prompt.sh 与 log_boot.sh：这里写标记 + 打开
# autoLoginUser，开机后 log_boot.sh 检测到标记并立刻关掉自动登录 —— 因此手动重启/意外断电都不会免密。
#
# 可覆盖环境变量（仅供测试；launchd 不带环境变量，线上走默认值）：
#   EV_LOG / EV_MARKER / EV_PROMPT_SCRIPT / EV_SHUTDOWN / EV_DEFAULTS / EV_PROMPT_SECS
set -u

LOG=${EV_LOG:-/var/log/daily_reboot.log}
MARKER=${EV_MARKER:-/var/log/.ev_autologin_pending}
PROMPT=${EV_PROMPT_SCRIPT:-/usr/local/bin/ev_restart_prompt.sh}
SHUTDOWN=${EV_SHUTDOWN:-/sbin/shutdown}
DEFAULTS=${EV_DEFAULTS:-/usr/bin/defaults}

printf "[PLAN]   %s scheduled restart fired\n" "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG"

if [ -x "$PROMPT" ]; then
  REASON=$("$PROMPT" 2>&1)
  if [ $? -eq 0 ]; then
    printf "[CANCEL] %s user cancelled this scheduled restart (%s)\n" "$(date '+%Y-%m-%d %H:%M:%S')" "$REASON" >> "$LOG"
    exit 0
  fi
  printf "[PROMPT] %s continue -> restart (%s)\n" "$(date '+%Y-%m-%d %H:%M:%S')" "$REASON" >> "$LOG"
else
  printf "[PROMPT] %s prompt script missing -> restart without prompt\n" "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG"
fi

# 仅本次重启允许自动登录
touch "$MARKER"
"$DEFAULTS" write /Library/Preferences/com.apple.loginwindow autoLoginUser -string "Banner"
printf "[ALOGIN] %s auto-login armed for this boot only\n" "$(date '+%Y-%m-%d %H:%M:%S')" >> "$LOG"

# 确保日志真正刷入磁盘，避免重启还没写进去
sync
sleep 5
"$SHUTDOWN" -r now
