#!/bin/bash
# ev_restart_prompt.sh —— 计划重启前的「可取消」提示（每日 04:30 定时任务的前置）
#
# 退出码约定：
#   0 = 用户点了「取消本次重启」→ 调用方必须中止本次重启
#   1 = 继续重启（用户点了「立即重启」／60 秒超时无人处理／弹窗无法显示）
#
# 设计要点：
#   · 默认 60 秒（用户 2026-10-05 指定：1 分钟无人处理就重启）
#   · **默认按钮是「取消本次重启」** —— 半夜误按回车不会把机器重启掉
#   · LaunchDaemon 在系统域、看不到 GUI，必须用 launchctl asuser 切到已登录用户会话才显示弹窗
#   · 先 caffeinate -u 唤醒屏幕，否则弹窗看不见（睡屏状态下会直接走超时→重启）
#   · ⚠️ 若此刻停在登录窗（屏幕锁定），弹窗可能不可见 → 同样走超时→重启（符合「无人处理就重启」）
#
# 可覆盖环境变量（仅供测试/调参，launchd 不带环境变量时一律用默认值）：
#   EV_PROMPT_SECS=60  等待秒数
#   EV_PROMPT_UID     目标用户 uid（默认取 /dev/console 的属主）
#   EV_OSA             osascript 路径（测试时可替换为假脚本）
set -u

SECS=${EV_PROMPT_SECS:-60}
CONSOLE_UID=$(stat -f%u /dev/console 2>/dev/null)
TARGET_UID=${EV_PROMPT_UID:-${CONSOLE_UID:-0}}
OSA=${EV_OSA:-/usr/bin/osascript}

MSG="Ev课程表 · 计划重启\\n\\n本机将在 ${SECS} 秒后自动重启（每日 04:30 定时任务）。\\n\\n· 点「取消本次重启」→ 今晚不重启\\n· 不做任何处理 → ${SECS} 秒后照常重启\\n\\n触发时间：$(date '+%Y-%m-%d %H:%M:%S')"

run_osa() {
  if [ "$(id -u)" = "0" ] && [ "$TARGET_UID" != "0" ]; then
    /bin/launchctl asuser "$TARGET_UID" "$OSA" "$@" 2>&1
  else
    "$OSA" "$@" 2>&1
  fi
}

# 唤醒屏幕，避免弹窗在睡屏上看不见（失败无所谓）
/usr/bin/caffeinate -u -t 2 >/dev/null 2>&1 &

OUT=$(run_osa -e "display dialog \"${MSG}\" buttons {\"取消本次重启\", \"立即重启\"} default button 1 cancel button 1 with title \"Ev 定时重启\" giving up after ${SECS}")
RC=$?

case "$OUT" in
  *"取消本次重启"*) echo "user-cancel"; exit 0 ;;
  *"立即重启"*)     echo "user-restart-now"; exit 1 ;;
esac

# 超时（giving up:true）或弹窗压根没显示出来 → 按「无人处理」处理：照常重启
if [ $RC -ne 0 ]; then
  echo "no-gui-or-error: $OUT"
else
  echo "timeout"
fi
run_osa -e "display notification \"60 秒内未收到处理，正在按计划重启\" with title \"Ev 定时重启\"" >/dev/null 2>&1
exit 1
