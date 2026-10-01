#!/bin/bash
# ============================================================
# USB 手环连接状态测试脚本
# 用途：通过 USB 连接安卓手机 → 构建安装 APK → 检查手环连接状态 → 截图保存
# 用法：bash tools/test-band-usb.sh
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_DIR="$PROJECT_DIR/apk"
SCREENSHOT_DIR="$PROJECT_DIR/screenshots"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

ADB="$HOME/android-sdk/platform-tools/adb"
PKG="com.application.watch.classschedule"

# --------------- 颜色输出 ---------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# --------------- 步骤 0：环境检查 ---------------
echo ""
echo "=============================================="
echo "  USB 手环连接状态测试"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================="
echo ""

mkdir -p "$SCREENSHOT_DIR"

# 检查 adb
if [ ! -x "$ADB" ]; then
    log_error "找不到 adb: $ADB"
    log_info "请确保 Android SDK 已安装在 $HOME/android-sdk"
    exit 1
fi
log_ok "adb 路径: $ADB ($($ADB --version | head -1))"

# --------------- 步骤 1：检查 USB 设备 ---------------
log_info "步骤 1/6: 检查 USB 连接的安卓设备..."
DEVICES=$("$ADB" devices -l 2>&1 | grep -v "List of devices" | grep "device" || true)

if [ -z "$DEVICES" ]; then
    log_error "没有检测到 USB 连接的安卓设备！"
    log_info "请确认："
    log_info "  1. 手机已通过 USB 线连接到 Mac"
    log_info "  2. 手机已开启「开发者选项」→「USB 调试」"
    log_info "  3. 手机已开启「USB 安装」（MIUI/ColorOS 等需要）"
    log_info "  4. USB 用途已设为「传输文件」模式"
    log_info "  5. 手机上已点击「允许 USB 调试」弹窗"
    exit 1
fi

DEVICE_SERIAL=$(echo "$DEVICES" | head -1 | awk '{print $1}')
DEVICE_MODEL=$(echo "$DEVICES" | sed -n 's/.*model:\([^ ]*\).*/\1/p')
log_ok "已检测到设备: $DEVICE_SERIAL (型号: ${DEVICE_MODEL:-未知})"

# 获取手机详细信息
ANDROID_VERSION=$("$ADB" -s "$DEVICE_SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "未知")
MANUFACTURER=$("$ADB" -s "$DEVICE_SERIAL" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' || echo "未知")
log_info "安卓版本: $ANDROID_VERSION / 厂商: $MANUFACTURER"

# 检查小米运动健康是否安装
log_info "检查「小米运动健康」..."
MI_HEALTH=$("$ADB" -s "$DEVICE_SERIAL" shell pm list packages 2>/dev/null | grep -iE "com.mi.health|com.xiaomi.wearable" || true)
if [ -z "$MI_HEALTH" ]; then
    log_warn "未检测到「小米运动健康」—— 这是连接手环的必要条件！"
    log_warn "请先在手机上安装「小米运动健康」App"
else
    log_ok "小米运动健康已安装: $(echo "$MI_HEALTH" | sed 's/package://')"
fi

# --------------- 步骤 2：构建 APK ---------------
log_info "步骤 2/6: 构建最新版 APK..."
cd "$APK_DIR"

# 检查是否有未提交的源码改动
SRC_DIR="$APK_DIR/src"
if [ -d "$SRC_DIR" ]; then
    JAVA_FILES=$(find "$SRC_DIR" -name "*.java" -type f 2>/dev/null | wc -l | tr -d ' ')
    log_info "源码文件数: $JAVA_FILES"
fi

# 构建
if ANDROID_SDK_ROOT="$HOME/android-sdk" bash build.sh; then
    # 获取产物路径
    LATEST_APK=$(ls -t dist/EVSyncProbe-v*.apk 2>/dev/null | head -1)
    if [ -z "$LATEST_APK" ]; then
        log_error "构建完成但找不到产物 APK"
        exit 1
    fi
    log_ok "构建成功: $LATEST_APK"
else
    log_error "构建失败！请检查编译错误"
    exit 1
fi

cd "$PROJECT_DIR"

# --------------- 步骤 3：安装 APK ---------------
log_info "步骤 3/6: 安装 APK 到手机..."
if "$ADB" -s "$DEVICE_SERIAL" install -r -g "$APK_DIR/$LATEST_APK" 2>&1 | tail -1; then
    log_ok "APK 安装成功"
else
    log_error "APK 安装失败"
    log_info "尝试卸载后重装: adb uninstall $PKG"
    exit 1
fi

# 验证安装版本
INSTALLED_VERSION=$("$ADB" -s "$DEVICE_SERIAL" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | awk -F'=' '{print $2}')
log_ok "已安装版本: $INSTALLED_VERSION"

# --------------- 步骤 4：启动 App 并等待手环连接 ---------------
log_info "步骤 4/6: 启动 App（冷启动）..."
"$ADB" -s "$DEVICE_SERIAL" shell am force-stop "$PKG" 2>/dev/null || true
sleep 1

"$ADB" -s "$DEVICE_SERIAL" shell am start -W -n "$PKG/.HomeActivity" 2>&1 || true
sleep 2

log_info "=============================================="
log_info "  ⏳ 请在手机上观察 App 首页的手环连接状态"
log_info "  - 如果显示「已连接」+ 手环型号 → 正常 ✅"
log_info "  - 如果显示「未连接」或错误信息 → 异常 ❌"
log_info "  - 请等待连接步骤走完（约 10-15 秒）"
log_info "=============================================="

# 等待用户观察（给连接过程充足时间）
sleep 12

# --------------- 步骤 5：截图 ---------------
log_info "步骤 5/6: 截取手机屏幕..."

# 截图 1：首页（手环连接状态）
HOME_SHOT="$SCREENSHOT_DIR/ev-band-status_${TIMESTAMP}.png"
"$ADB" -s "$DEVICE_SERIAL" exec-out screencap -p > "$HOME_SHOT" 2>/dev/null
if [ -f "$HOME_SHOT" ] && [ -s "$HOME_SHOT" ]; then
    log_ok "首页截图已保存: $HOME_SHOT"
    FILE_SIZE=$(wc -c < "$HOME_SHOT" | tr -d ' ')
    log_info "  文件大小: ${FILE_SIZE} bytes"
else
    log_error "首页截图失败，尝试备用方法..."
    "$ADB" -s "$DEVICE_SERIAL" shell screencap -p /sdcard/ev_home.png
    "$ADB" -s "$DEVICE_SERIAL" pull /sdcard/ev_home.png "$HOME_SHOT"
    log_ok "备用截图已保存: $HOME_SHOT"
fi

# 尝试打开手环调试页并截图
log_info "尝试打开手环页 (BandActivity)..."
"$ADB" -s "$DEVICE_SERIAL" shell am start -n "$PKG/.BandActivity" 2>/dev/null || {
    log_warn "无法通过 adb 打开手环页（可能未导出），跳过手环页截图"
}
sleep 2

BAND_SHOT="$SCREENSHOT_DIR/ev-band-page_${TIMESTAMP}.png"
"$ADB" -s "$DEVICE_SERIAL" exec-out screencap -p > "$BAND_SHOT" 2>/dev/null
if [ -f "$BAND_SHOT" ] && [ -s "$BAND_SHOT" ]; then
    log_ok "手环页截图已保存: $BAND_SHOT"
fi

# 尝试打开调试页并截图
log_info "尝试打开调试页 (DebugActivity)..."
"$ADB" -s "$DEVICE_SERIAL" shell am start -n "$PKG/.DebugActivity" 2>/dev/null || {
    log_warn "无法通过 adb 打开调试页（可能未导出），跳过调试页截图"
}
sleep 2

DEBUG_SHOT="$SCREENSHOT_DIR/ev-debug_${TIMESTAMP}.png"
"$ADB" -s "$DEVICE_SERIAL" exec-out screencap -p > "$DEBUG_SHOT" 2>/dev/null
if [ -f "$DEBUG_SHOT" ] && [ -s "$DEBUG_SHOT" ]; then
    log_ok "调试页截图已保存: $DEBUG_SHOT"
fi

# --------------- 步骤 6：收集诊断信息 ---------------
log_info "步骤 6/6: 收集手环连接诊断信息..."

DIAG_FILE="$SCREENSHOT_DIR/ev-band-diagnosis_${TIMESTAMP}.txt"

{
    echo "=============================================="
    echo "  EV 同步器 - 手环连接诊断报告"
    echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "=============================================="
    echo ""
    echo "--- 设备信息 ---"
    echo "序列号: $DEVICE_SERIAL"
    echo "型号: ${DEVICE_MODEL:-未知}"
    echo "厂商: $MANUFACTURER"
    echo "安卓版本: $ANDROID_VERSION"
    echo ""
    echo "--- APK 信息 ---"
    echo "已安装版本: ${INSTALLED_VERSION:-未知}"
    echo "APK 产物: $LATEST_APK"
    echo ""
    echo "--- 小米运动健康 ---"
    echo "$MI_HEALTH"
    echo ""
    echo "--- 应用权限 ---"
    "$ADB" -s "$DEVICE_SERIAL" shell dumpsys package "$PKG" 2>/dev/null | grep -A5 "requested permissions" || echo "无"
    echo ""
    echo "--- 前台服务状态 ---"
    "$ADB" -s "$DEVICE_SERIAL" shell dumpsys activity services "$PKG" 2>/dev/null | head -20 || echo "无运行中的服务"
    echo ""
    echo "--- 省电白名单 ---"
    "$ADB" -s "$DEVICE_SERIAL" shell dumpsys deviceidle whitelist 2>/dev/null | grep "$PKG" || echo "未在白名单中"
    echo ""
    echo "--- 截图文件 ---"
    ls -la "$SCREENSHOT_DIR"/*_${TIMESTAMP}.* 2>/dev/null || echo "无截图"
} > "$DIAG_FILE"

log_ok "诊断报告已保存: $DIAG_FILE"

# --------------- 总结 ---------------
echo ""
echo "=============================================="
echo "  ✅ 测试完成"
echo "=============================================="
echo ""
echo "截图文件:"
ls -la "$SCREENSHOT_DIR"/*_${TIMESTAMP}.* 2>/dev/null || echo "  (无截图)"
echo ""
echo "下一步操作："
echo "  1. 检查截图中的手环连接状态"
echo "  2. 如果连接正常，将截图上传到 https://app-auth.gudq.com/android-apk.html"
echo "  3. 查看诊断报告: cat $DIAG_FILE"
echo ""
echo "用 Preview 打开截图:"
echo "  open $HOME_SHOT"
echo ""