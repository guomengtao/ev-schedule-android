#!/bin/bash
# EV Sync Probe —— 手工构建脚本（不依赖 Gradle / Android Studio）
#
# 版本管理：
#   - 版本号存在 version.env（VERSION_CODE / VERSION_NAME）
#   - 每次构建：用当前版本号打包 → 产出 dist/EVSyncProbe-v<版本>.apk（历史保留）
#   - 构建后自动 bump：VERSION_CODE+1、VERSION_NAME 末位+1，写回 version.env
#   - 包名（applicationId）固定为 com.application.watch.classschedule，【不】带版本号
#     （官方 interconnect 要求包名与快应用完全一致，改了就连不上）
#
# 依赖：
#   - Android SDK：platforms;android-34 + build-tools（34 或 36）
#   - JDK 8（仅用于 javac —— R8 不认 JDK 22 编译出的 class 文件）
#   - JDK 11+（用于 d8 / apksigner）
#   - EV 快应用 rpk 的签名密钥（private.pem + certificate.pem）
#
# 用法：bash build.sh
#   NO_BUMP=1 bash build.sh                       # 不改版本号（重出当前版本）
#   RPK_SIGN_DIR=/path/to/sign bash build.sh      # 手动指定 rpk 签名目录
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
[ -x "$BT/aapt2" ] || BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-34/android.jar"

cd "$(dirname "$0")"
HERE="$(pwd)"
GUOMENGTAO="$(cd "$HERE/../.." && pwd)"

# --- 构建变体（一套源码 → 多个包名的 APK）---
#   interconnect 要求 APK 包名 == 手环快应用包名，而「EV 课程表」与「EvBox 工具箱」
#   包名不同 → 必须分别打包。用法：APP_VARIANT=evbox bash build.sh
VARIANT="${APP_VARIANT:-ev}"
case "$VARIANT" in
  ev)
    APP_ID="com.application.watch.classschedule"
    # 桌面名：与手环快应用同品牌（用户装了能认出是「EV 课程表」的配套手机端）
    # 注意：桌面名【不带版本号】——版本号在 App 内首页显示即可，
    #       带版本号会导致每次升级桌面名都变，也不像正经产品名。
    LABEL_BASE="Ev课程表"
    PEER_PKG="com.application.watch.classschedule"
    VFILE="$HERE/version.env"
    OUT_NAME="EVSyncProbe"
    ;;
  evbox)
    APP_ID="com.application.watch.evbox"
    LABEL_BASE="EvBox Sync"
    PEER_PKG="com.application.watch.evbox"
    VFILE="$HERE/version-evbox.env"
    OUT_NAME="EvBoxSyncProbe"
    ;;
  *)
    echo "未知 APP_VARIANT: $VARIANT（可选：ev | evbox）"; exit 1;;
esac

# --- 读版本号（每个变体一条独立版本线）---
if [ ! -f "$VFILE" ]; then
  printf 'VERSION_CODE=1\nVERSION_NAME=0.1.0\n' > "$VFILE"
fi
# shellcheck disable=SC1090
. "$VFILE"

# --- 工具链探测 ---
JAVA8_HOME="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
JRE_HOME="$(/usr/libexec/java_home -v 22 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null \
            || /usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"

[ -f "$BT/aapt2" ]            || { echo "缺少 build-tools: $BT"; exit 1; }
[ -f "$AJAR" ]                || { echo "缺少 platform: $AJAR"; exit 1; }
[ -f libs/xms-wearable.jar ]  || { echo "缺少 libs/xms-wearable.jar（从 AAR 的 classes.jar 提取）"; exit 1; }
# 打赏页二维码需要的 zxing 核心库（纯 Java，随 APK 打包，离线可用）
if [ ! -f libs/zxing-core.jar ]; then
  curl -sL -o libs/zxing-core.jar \
    "https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar"
fi
[ -f libs/zxing-core.jar ] || { echo "缺少 libs/zxing-core.jar（二维码库下载失败）"; exit 1; }
[ -n "$JAVA8_HOME" ]          || { echo "需要 JDK 8 用于 javac（R8 不兼容 JDK 22 的 class 文件）"; exit 1; }
[ -n "$JRE_HOME" ]            || { echo "需要 JDK 11+（d8 / apksigner）"; exit 1; }

export JAVA_HOME="$JRE_HOME"
export PATH="$JRE_HOME/bin:$PATH"

# --- 找 EV 快应用的签名密钥 ---
# 2026-10-04 修：2026-10-02 目录重组（快应用移入 ev/）后，旧候选路径全部失效 →
#   查找静默落空 → 退回自签 keystore.jks → interconnect 第③步 requestPermission
#   抛 SignatureVerifyFailedException，界面显示「签名校验未通过」，连手环必失败。
# 现按「两层候选」找：仓库既可能挂 `guomengtao/ev/ev-schedule-android`（则
#   apk/../.. = ev/），也可能挂 `guomengtao/ev-schedule-android`（则 apk/../.. = 项目根），
#   两种层级都列出来，命中即用；旧路径（tom/…、EvBox/…）保留兜底。
RPK_SIGN_DIR="${RPK_SIGN_DIR:-}"
if [ -z "$RPK_SIGN_DIR" ]; then
  UP2="$(cd "$HERE/../.." && pwd)"
  UP3="$(cd "$HERE/../../.." && pwd)"
  for d in "$UP2/class-schedule/sign" \
           "$UP2/evbox/sign" \
           "$UP3/ev/class-schedule/sign" \
           "$UP3/ev/evbox/sign" \
           "$UP3/tom/class/class/sign" \
           "$UP3/EvBox/evbox/sign"; do
    if [ -f "$d/private.pem" ] && [ -f "$d/certificate.pem" ]; then RPK_SIGN_DIR="$d"; break; fi
  done
fi

echo "=============================================="
echo " 同步器 APK 构建"
echo "   variant     : $VARIANT"
echo "   versionCode : $VERSION_CODE"
echo "   versionName : $VERSION_NAME"
echo "   包名        : ${APP_ID}（= 对端快应用包名，配对键，不带版本号）"
echo "   label       : $LABEL_BASE v$VERSION_NAME"
echo "   build-tools : $BT"
echo "   javac       : $JAVA8_HOME/bin/javac"
echo "   d8/apksigner: $JRE_HOME"
echo "   rpk 签名密钥: ${RPK_SIGN_DIR:-（未找到，将退回自签，interconnect 会失败）}"
if [ -n "$RPK_SIGN_DIR" ]; then
  echo "   rpk 证书指纹: $(openssl x509 -in "$RPK_SIGN_DIR/certificate.pem" -noout -fingerprint -sha256 | sed 's/.*=//')"
fi
echo "=============================================="

rm -rf out
mkdir -p out/gen out/classes out/dex out/sdkclasses dist

# 注入：包名（配对键）/ launcher 名称 / 变体信息（对端包名给 Variant.java 运行期读）
sed -e "s|package=\"com.application.watch.classschedule\"|package=\"$APP_ID\"|" \
    -e "s/android:label=\"EV Sync\"/android:label=\"$LABEL_BASE\"/" \
    -e "s|__VARIANT__|$VARIANT|" \
    -e "s|__PEER_PKG__|$PEER_PKG|" \
    -e "s|__APP_ID__|$APP_ID|" \
  AndroidManifest.xml > out/AndroidManifest.xml

# 自检：包名/占位符没替换干净就直接失败 —— 防止"两个变体其实是同一个包名"的静默错误
grep -q "package=\"$APP_ID\"" out/AndroidManifest.xml \
  || { echo "清单包名注入失败：期望 $APP_ID"; exit 1; }
if grep -q "__PEER_PKG__\|__VARIANT__\|__APP_ID__" out/AndroidManifest.xml; then
  echo "变体占位符未替换干净（__PEER_PKG__ / __VARIANT__ / __APP_ID__）"; exit 1
fi

echo "[1/7] aapt2 compile + link（编译资源与清单，注入版本号）..."
# 资源目录（应用图标等）→ 编译成 .flat 压缩包
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link \
  -o out/base.apk \
  -I "$AJAR" \
  --manifest out/AndroidManifest.xml \
  --java out/gen \
  --custom-package "com.application.watch.classschedule" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  out/res.zip

echo "[2/7] javac（必须用 JDK 8）..."
find src out/gen -name '*.java' > out/sources.txt
"$JAVA8_HOME/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$AJAR" \
  -classpath "libs/xms-wearable.jar:libs/zxing-core.jar" \
  -d out/classes @out/sources.txt

echo "[3/7] d8（生成 classes.dex）..."
# ⚠️ 关键：SDK 的 class 必须作为【程序输入】打进 dex，不能只当 --classpath（库）。
#    只当库会导致运行时 NoClassDefFoundError（App 一启动就闪退）。
( cd out/sdkclasses && unzip -oq "$HERE/libs/xms-wearable.jar" )
"$JRE_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 24 \
  --lib "$AJAR" \
  --output out/dex \
  $(find out/sdkclasses out/classes -name '*.class') \
  libs/zxing-core.jar

echo "[4/7] 打包 dex 到 APK ..."
cp out/base.apk out/unsigned.apk
( cd out/dex && zip -q ../unsigned.apk classes.dex )

echo "[5/7] zipalign ..."
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk

echo "[6/7] 准备签名工具（BouncyCastle 包装 apksigner）..."
mkdir -p tools/bcclasses
if [ ! -f tools/bcprov.jar ]; then
  curl -sL -o tools/bcprov.jar \
    "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.78.1/bcprov-jdk18on-1.78.1.jar"
fi
javac -nowarn -cp "$BT/lib/apksigner.jar:tools/bcprov.jar" -d tools/bcclasses tools/BCSign.java
SIGNER=(-cp "tools/bcclasses:$BT/lib/apksigner.jar:tools/bcprov.jar" BCSign)

echo "[7/7] 签名 ..."
OUT="dist/${OUT_NAME}-v${VERSION_NAME}.apk"
if [ -n "$RPK_SIGN_DIR" ]; then
  openssl pkcs8 -topk8 -nocrypt -in "$RPK_SIGN_DIR/private.pem" -outform DER -out out/rpk-key.pk8
  "$JRE_HOME/bin/java" "${SIGNER[@]}" sign \
    --key out/rpk-key.pk8 \
    --cert "$RPK_SIGN_DIR/certificate.pem" \
    --v4-signing-enabled false \
    --out "$OUT" out/aligned.apk
  rm -f out/rpk-key.pk8
else
  [ -f keystore.jks ] || "$JAVA8_HOME/bin/keytool" -genkeypair -v \
    -keystore keystore.jks -storepass android -keypass android \
    -alias evschedule -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=EV Schedule, OU=Dev, O=EV, L=Beijing, ST=Beijing, C=CN" >/dev/null 2>&1
  "$BT/apksigner" sign --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias evschedule --v4-signing-enabled false --out "$OUT" out/aligned.apk
fi
rm -f "$OUT.idsig"

# 便于固定路径安装（可覆盖安装的前提是签名没变）
cp "$OUT" "$OUT_NAME.apk"

# --- 生成升级描述文件（App 自动升级用，见 docs/自动升级实现方案.md）---
#   发布物共两件：本 JSON → 上传 Vercel/静态服务器；APK → 上传 GitHub Release。
#   双地址：downloadUrlMirror（ghproxy 镜像前缀，App 优先尝试）+ downloadUrlOrigin（GitHub 直链，兜底）。
#   GH_REPO / GH_PROXY 可用环境变量覆盖；GH_REPO 未设置时 URL 留空，发布时手补。
GH_REPO="${GH_REPO:-}"
GH_PROXY="${GH_PROXY:-https://ghproxy.net}"     # 2026-09-28 实测：ghproxy.com 超时，ghproxy.net 稳定
# 未显式指定 GH_REPO 时，从本仓库 git remote 自动解析 owner/repo（APK 托管在源码仓库自己的 Release）
if [ -z "$GH_REPO" ]; then
  RU="$(git -C "$HERE" config --get remote.origin.url 2>/dev/null || true)"
  case "$RU" in
    *github.com*) GH_REPO="$(echo "$RU" | sed -E 's#.*github\.com[:/]##; s#\.git$##')" ;;
  esac
fi
UPDATE_LOG="${UPDATE_LOG:-}"                      # 本次更新说明，弹窗正文展示
SHA256=$(shasum -a 256 "$OUT" | cut -d' ' -f1)
ORIGIN=""
if [ -n "$GH_REPO" ]; then
  ORIGIN="https://github.com/$GH_REPO/releases/download/v${VERSION_NAME}/${OUT_NAME}-v${VERSION_NAME}.apk"
fi
cat > "dist/update-${VARIANT}.json" <<EOF
{"versionCode":$VERSION_CODE,"versionName":"$VERSION_NAME","sha256":"$SHA256",
 "downloadUrlMirror":"${GH_PROXY:+$GH_PROXY/}$ORIGIN","downloadUrlOrigin":"$ORIGIN",
 "isForce":false,"updateLog":"$UPDATE_LOG"}
EOF

echo
echo "产物: $HERE/$OUT"
ls -la "$OUT" "$OUT_NAME.apk"
"$JRE_HOME/bin/java" "${SIGNER[@]}" verify --print-certs "$OUT" | head -4
echo
"$BT/aapt2" dump badging "$OUT" 2>/dev/null | head -4
echo
echo "历史包:"
ls -1t dist/*.apk 2>/dev/null | head -10

# --- 版本号自动 +1（写回 version.env，供下次构建使用）---
if [ "${NO_BUMP:-0}" != "1" ]; then
  IFS=. read -r MA MI PA <<< "$VERSION_NAME"
  NEXT_CODE=$((VERSION_CODE + 1))
  NEXT_NAME="${MA:-0}.${MI:-0}.$(( ${PA:-0} + 1 ))"
  printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$NEXT_CODE" "$NEXT_NAME" > "$VFILE"
  echo
  echo "版本号已 bump：下次构建 = v$NEXT_NAME (code $NEXT_CODE)"
fi
