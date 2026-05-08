#!/usr/bin/env bash
# ===================================================================
# Manual APK build: aapt → javac → kotlinc → dx → apksigner
# Usage: bash android/build-apk.sh
# ===================================================================
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"

ANDROID_SDK="/usr/lib/android-sdk"
ANDROID_JAR="$ANDROID_SDK/platforms/android-23/android.jar"
BT29="$ANDROID_SDK/build-tools/29.0.3"
DX="$ANDROID_SDK/build-tools/debian/dx"
KOTLINC="/usr/bin/kotlinc"
KT_STDLIB="/usr/share/kotlin/kotlinc/lib/kotlin-stdlib.jar"

SRC="$PROJECT/app/src/main/java"
RES="$PROJECT/app/src/main/res"
MANIFEST="$PROJECT/app/src/main/AndroidManifest.xml"
BUILD="$PROJECT/build/manual"
GEN="$BUILD/gen"
R_CLASSES="$BUILD/r_classes"
OUT="$PROJECT/app-debug.apk"

BCPROV_ORIG="/tmp/bcprov-jdk15on-1.70.jar"
BCPROV_ANDROID="$BUILD/bcprov-android.jar"

# -------------------------------------------------------------------
echo "==> [1/8] Downloading BouncyCastle..."
if [ ! -f "$BCPROV_ORIG" ]; then
    curl -fsSL --progress-bar \
        "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.70/bcprov-jdk15on-1.70.jar" \
        -o "$BCPROV_ORIG"
fi
echo "    ✓ BouncyCastle downloaded"

# Strip multi-release (Java 9+) classes incompatible with dx
rm -rf "$BUILD" && mkdir -p "$BUILD" "$GEN" "$R_CLASSES"
echo "    Stripping Java 11 classes from BouncyCastle..."
rm -rf /tmp/bcprov_extracted && mkdir /tmp/bcprov_extracted
unzip -q "$BCPROV_ORIG" -d /tmp/bcprov_extracted
rm -rf /tmp/bcprov_extracted/META-INF/versions
cd /tmp/bcprov_extracted && jar cf "$BCPROV_ANDROID" . && cd "$PROJECT"
echo "    ✓ bcprov-android.jar ($(du -sh "$BCPROV_ANDROID" | cut -f1))"

# -------------------------------------------------------------------
echo "==> [2/8] Generating R.java (aapt)..."
"$BT29/aapt" package -f \
    -M "$MANIFEST" \
    -S "$RES" \
    -I "$ANDROID_JAR" \
    -J "$GEN" \
    -F "$BUILD/resources.apk"
echo "    ✓ R.java and resources.apk generated"

# -------------------------------------------------------------------
echo "==> [3/8] Compiling R.java (javac)..."
javac -source 1.8 -target 1.8 \
    -cp "$ANDROID_JAR" \
    -d "$R_CLASSES" \
    "$GEN/R.java" 2>&1 | grep -v "warning:" || true
echo "    ✓ R.class compiled"

# -------------------------------------------------------------------
echo "==> [4/8] Compiling Kotlin sources..."
$KOTLINC \
    -classpath "$ANDROID_JAR:$BCPROV_ANDROID:$R_CLASSES" \
    -jvm-target 1.8 \
    -d "$BUILD/app.jar" \
    "$SRC/com/vpn/Crypto.kt" \
    "$SRC/com/vpn/Protocol.kt" \
    "$SRC/com/vpn/MyVpnService.kt" \
    "$SRC/com/vpn/MainActivity.kt"
echo "    ✓ Kotlin compiled → app.jar"

# -------------------------------------------------------------------
echo "==> [5/8] Converting to DEX (dx)..."
"$DX" --dex \
    --output="$BUILD/classes.dex" \
    "$BUILD/app.jar" \
    "$R_CLASSES" \
    "$BCPROV_ANDROID" \
    "$KT_STDLIB"
echo "    ✓ classes.dex ($(du -sh "$BUILD/classes.dex" | cut -f1))"

# -------------------------------------------------------------------
echo "==> [6/8] Assembling APK..."
mkdir -p "$BUILD/apk_contents"
cd "$BUILD/apk_contents"
unzip -oq ../resources.apk
cp ../classes.dex .
zip -rq ../unsigned.apk .
cd "$PROJECT"
echo "    ✓ unsigned.apk assembled"

# -------------------------------------------------------------------
echo "==> [7/8] Aligning APK (zipalign)..."
"$BT29/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
echo "    ✓ APK aligned"

# -------------------------------------------------------------------
echo "==> [8/8] Signing APK..."
KEYSTORE="$BUILD/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" \
        2>/dev/null
fi

"$BT29/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUT" \
    "$BUILD/aligned.apk"

echo
echo "============================================================"
echo "  ✅  APK built:  android/app-debug.apk"
echo "  Size: $(du -sh "$OUT" | cut -f1)"
echo "============================================================"
echo
echo "  Install via USB:  adb install android/app-debug.apk"
echo "  Or copy the APK to your Android device and open it."
echo
