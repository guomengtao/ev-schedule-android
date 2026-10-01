#!/bin/bash
# BandAuthProbe - standalone build script.
#
# Independent from the EV Sync apk build. No xms-wearable, no zxing.
# Pure Java + android.jar. Reuses the main projects' keystore for signing
# (allowOverinstall only matters within the same app; this has its own package).
#
# Toolchain: mirrors apk/build.sh (aapt2 + JDK8 javac + d8 + apksigner).
# Prereqs: ~/android-sdk (android-34 + build-tools), JDK 8 for javac.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
[ -x "$BT/aapt2" ] || BT="$SDK/build-tools/36.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="$(cd "$(dirname "$0")/../apk" && pwd)/keystore.jks"

cd "$(dirname "$0")"
HERE="$(pwd)"

JAVA8_HOME="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
JRE_HOME="$(/usr/libexec/java_home -v 22 2>/dev/null || /usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"

[ -f "$BT/aapt2" ]   || { echo "missing build-tools: $BT"; exit 1; }
[ -f "$AJAR" ]       || { echo "missing platform: $AJAR"; exit 1; }
[ -f "$KEYSTORE" ]   || { echo "missing keystore: $KEYSTORE"; exit 1; }
[ -n "$JAVA8_HOME" ] || { echo "need JDK8 for javac"; exit 1; }
[ -n "$JRE_HOME" ]   || { echo "need JDK11+ (d8/apksigner)"; exit 1; }

export JAVA_HOME="$JRE_HOME"
export PATH="$JRE_HOME/bin:$PATH"

APP_ID="com.ev.bandauthprobe"
VCODE="${VERSION_CODE:-1}"
VNAME="${VERSION_NAME:-1.0.0}"

echo "== BandAuthProbe build =="
echo "   appId   : $APP_ID"
echo "   version : $VNAME ($VCODE)"
echo "   tools   : $BT / $JAVA8_HOME / $JRE_HOME"
echo "   keystore: $KEYSTORE"

rm -rf out
mkdir -p out/gen out/classes out/dex dist

sed -e "s|package=\"com.ev.bandauthprobe\"|package=\"$APP_ID\"|" \
  AndroidManifest.xml > out/AndroidManifest.xml

echo "[1/5] aapt2 compile + link..."
"$BT/aapt2" compile --dir res -o out/res.zip || "$BT/aapt2" compile --dir res --auto-add-overlay -o out/res.zip
"$BT/aapt2" link \
  -o out/base.apk \
  -I "$AJAR" \
  --manifest out/AndroidManifest.xml \
  --java out/gen \
  --custom-package "$APP_ID" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VCODE" \
  --version-name "$VNAME" \
  out/res.zip

echo "[2/5] javac (JDK8)..."
find src out/gen -name '*.java' > out/sources.txt
"$JAVA8_HOME/bin/javac" -nowarn -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$AJAR" \
  -d out/classes @out/sources.txt

echo "[3/5] d8..."
"$JRE_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 24 \
  --lib "$AJAR" \
  --output out/dex \
  $(find out/classes -name '*.class') 2>/dev/null || \
"$JRE_HOME/bin/java" -cp "$BT/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 24 --lib "$AJAR" --output out/dex \
  $(find out/classes -name '*.class')

echo "[4/5] package + zipalign..."
cp out/base.apk out/unsigned.apk
( cd out/dex && zip -q ../unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk

echo "[5/5] sign..."
OUT="dist/BandAuthProbe-v${VNAME}.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias evschedule --v4-signing-enabled false --out "$OUT" out/aligned.apk
rm -f "$OUT.idsig"
cp "$OUT" BandAuthProbe.apk

echo "== done: $OUT =="