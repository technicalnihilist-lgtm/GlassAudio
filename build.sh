#!/usr/bin/env bash
#
# Glass Audio - APK build script (no Gradle, no Android Studio needed).
#
#   ./build.sh
#
# Requirements: a JDK 17+ and an Android SDK with build-tools + a platform.
#   export ANDROID_HOME=$HOME/Android/Sdk
#   sdkmanager "platforms;android-34" "build-tools;34.0.0"
#
# Output: build/GlassAudio.apk (signed, installable)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
OUT="$HERE/build"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"
PLATFORM="${PLATFORM:-android-34}"
BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$ANDROID_HOME/platforms/$PLATFORM/android.jar"

MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

# Demo signing key. It is generated on first run so the APK is installable.
# REPLACE IT with your own key before publishing anywhere (see README).
KS="$HERE/keystore/glassaudio.jks"
KS_PASS="${KS_PASS:-glassaudio}"
KS_ALIAS="${KS_ALIAS:-glassaudio}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$ANDROID_JAR"; do
  [ -e "$f" ] || { echo "Missing: $f"; echo "Install with: sdkmanager \"platforms;$PLATFORM\" \"build-tools;$BUILD_TOOLS\""; exit 1; }
done
command -v javac >/dev/null || { echo "javac not found - install a JDK 17"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/6 compiling resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "==> 2/6 linking resources + assets"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res/resources.zip" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "==> 3/6 compiling java"
javac -nowarn --release 11 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  $(find "$APP/src" "$OUT/gen" -name '*.java')

echo "==> 4/6 dexing"
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  --min-api "$MIN_SDK" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "==> 5/6 packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 signing"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  echo "    generating demo keystore (replace for real releases)"
  keytool -genkeypair -v \
    -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Glass Audio, OU=Mobile, O=GlassAudio, L=, S=, C=IN" >/dev/null 2>&1
fi

"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --min-sdk-version "$MIN_SDK" \
  --out "$OUT/GlassAudio.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/GlassAudio.apk" | head -3

rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo
echo "Done: $OUT/GlassAudio.apk  ($(du -h "$OUT/GlassAudio.apk" | cut -f1))"
echo "Install with: adb install -r \"$OUT/GlassAudio.apk\""
