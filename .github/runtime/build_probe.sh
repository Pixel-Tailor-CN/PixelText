#!/usr/bin/env bash
set -euo pipefail
# 独立 instrumentation 与被测 APK 用同一临时 CI 签名，应用仍为正常 release 配置。
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="${1:-$ROOT/artifacts/probe}"
mkdir -p "$OUT/assets" "$OUT/classes" "$OUT/dex"
MAPPING="$ROOT/app/build/intermediates/mapping/release/minifyReleaseWithR8/mapping.txt"
python3 "$ROOT/.github/runtime/prepare_probe.py" "$MAPPING" "$OUT/assets" > "$OUT/mapping-selection.json"
BT="$ANDROID_HOME/build-tools/36.0.0"
ANDROID_JAR=$(find "$ANDROID_HOME/platforms" -name android.jar | sort -V | tail -1)
cat > "$OUT/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="vip.mystery0.pixel.text.runtimeprobe">
  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="37" />
  <application android:label="PixelText CI Probe" />
  <instrumentation android:name="vip.mystery0.pixel.text.runtimeprobe.RuntimeProbe" android:targetPackage="vip.mystery0.pixel.text" />
</manifest>
XML
javac -source 11 -target 11 -cp "$ANDROID_JAR" -d "$OUT/classes" "$ROOT/.github/runtime/RuntimeProbe.java"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 31 --output "$OUT/dex" "$OUT/classes/vip/mystery0/pixel/text/runtimeprobe/RuntimeProbe.class"
"$BT/aapt2" link -o "$OUT/probe-unsigned.apk" --manifest "$OUT/AndroidManifest.xml" -I "$ANDROID_JAR" -A "$OUT/assets"
(cd "$OUT/dex" && zip -q "$OUT/probe-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/probe-unsigned.apk" "$OUT/probe-aligned.apk"
"$BT/apksigner" sign --ks "$SIGN_KEY_STORE_FILE" --ks-key-alias "$SIGN_KEY_ALIAS" --ks-pass "pass:$SIGN_KEY_STORE_PASSWORD" --key-pass "pass:$SIGN_KEY_PASSWORD" --out "$OUT/probe.apk" "$OUT/probe-aligned.apk"
