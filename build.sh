#!/usr/bin/env bash
# Builds the flashable module zip without Gradle: the daemon (javac -> d8)
# and the Atrium Link app (aapt2 -> javac -> d8 -> zipalign -> apksigner),
# which the module installs.
#   ./build.sh          -> build/atrium-link.zip (and build/atrium-link.apk)
#   ./build.sh test     runs the protocol tests on this machine's JVM
#
# The app is signed with KEYSTORE (KS_PASS, KEY_ALIAS, KEY_PASS), else the
# SDK's debug key. Android only updates an app with one signed by the same
# key, so releases want a key that is kept.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
OUT="$HERE/build"
MAIN="$HERE/src/main/java/dev/atrium/link"

if [ "${1:-}" = test ]; then
    rm -rf "$OUT/test" && mkdir -p "$OUT/test"
    javac -nowarn -d "$OUT/test" "$MAIN/Protocol.java" "$MAIN/Session.java" "$MAIN/Crypto.java" "$MAIN/Link.java" \
        "$HERE"/src/test/java/dev/atrium/link/*.java
    java -cp "$OUT/test" dev.atrium.link.SessionTest
    exec java -cp "$OUT/test" dev.atrium.link.LinkTest
fi

jar=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
tools=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
d8=$tools/d8
[ -f "$jar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

rm -rf "$OUT/classes" "$OUT/module" && mkdir -p "$OUT/classes" "$OUT/module"
javac -nowarn --release 17 -cp "$jar" -d "$OUT/classes" "$MAIN"/*.java
"$d8" --release --min-api 33 --lib "$jar" --output "$OUT/module" $(find "$OUT/classes" -name '*.class')
mv "$OUT/module/classes.dex" "$OUT/module/link.dex"

# --- the app ---
APP="$HERE/app"
code=$("$HERE/scripts/version.sh" code)
[ "$code" = 0 ] && code=1  # aapt2 refuses 0
rm -rf "$OUT/app" && mkdir -p "$OUT/app/gen" "$OUT/app/classes" "$OUT/app/dex"
"$tools/aapt2" compile --dir "$APP/res" -o "$OUT/app/res.zip"
"$tools/aapt2" link -I "$jar" --manifest "$APP/AndroidManifest.xml" -R "$OUT/app/res.zip" --java "$OUT/app/gen" \
    --auto-add-overlay --min-sdk-version 33 --target-sdk-version 36 \
    --version-code "$code" --version-name "$("$HERE/scripts/version.sh" name)" -o "$OUT/app/base.apk"
javac -nowarn --release 17 -cp "$jar" -d "$OUT/app/classes" $(find "$APP/src" "$OUT/app/gen" -name '*.java')
"$d8" --release --min-api 33 --lib "$jar" --output "$OUT/app/dex" $(find "$OUT/app/classes" -name '*.class')
cp "$OUT/app/base.apk" "$OUT/app/unaligned.apk"
(cd "$OUT/app/dex" && zip -qj "$OUT/app/unaligned.apk" classes*.dex)
"$tools/zipalign" -f -p 4 "$OUT/app/unaligned.apk" "$OUT/app/aligned.apk"
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$tools/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:${KS_PASS:-android}" \
    --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" --key-pass "pass:${KEY_PASS:-${KS_PASS:-android}}" \
    --out "$OUT/atrium-link.apk" "$OUT/app/aligned.apk"
cp "$OUT/atrium-link.apk" "$OUT/module/atrium-link.apk"

cp "$HERE"/module/*.sh "$OUT/module/"
sed -e "s/@NAME@/$("$HERE/scripts/version.sh" name)/" -e "s/@CODE@/$("$HERE/scripts/version.sh" code)/" \
    "$HERE/module/module.prop.in" > "$OUT/module/module.prop"
rm -f "$OUT/atrium-link.zip"
(cd "$OUT/module" && zip -qr9 "$OUT/atrium-link.zip" .)
echo "$OUT/atrium-link.zip"
