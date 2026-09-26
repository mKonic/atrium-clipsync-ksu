#!/usr/bin/env bash
# Builds the flashable module zip without Gradle: javac -> d8 -> zip.
#   ./build.sh          -> build/atrium-clipsync.zip
#   ./build.sh test     runs the protocol tests on this machine's JVM
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
OUT="$HERE/build"
MAIN="$HERE/src/main/java/dev/atrium/clipsync"

if [ "${1:-}" = test ]; then
    rm -rf "$OUT/test" && mkdir -p "$OUT/test"
    javac -nowarn -d "$OUT/test" "$MAIN/Protocol.java" "$MAIN/Session.java" \
        "$HERE"/src/test/java/dev/atrium/clipsync/*.java
    exec java -cp "$OUT/test" dev.atrium.clipsync.SessionTest
fi

jar=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
d8=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/d8
[ -f "$jar" ] || { echo "no android.jar under $SDK/platforms" >&2; exit 1; }

rm -rf "$OUT/classes" "$OUT/module" && mkdir -p "$OUT/classes" "$OUT/module"
javac -nowarn --release 17 -cp "$jar" -d "$OUT/classes" "$MAIN"/*.java
"$d8" --release --min-api 33 --lib "$jar" --output "$OUT/module" $(find "$OUT/classes" -name '*.class')
mv "$OUT/module/classes.dex" "$OUT/module/clipsync.dex"

cp "$HERE"/module/*.sh "$OUT/module/"
sed -e "s/@NAME@/$("$HERE/scripts/version.sh" name)/" -e "s/@CODE@/$("$HERE/scripts/version.sh" code)/" \
    "$HERE/module/module.prop.in" > "$OUT/module/module.prop"
rm -f "$OUT/atrium-clipsync.zip"
(cd "$OUT/module" && zip -qr9 "$OUT/atrium-clipsync.zip" .)
echo "$OUT/atrium-clipsync.zip"
