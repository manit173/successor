#!/bin/zsh
# Builds dist/Successor.app and dist/Successor-1.0.0.dmg: a native macOS app with its own trimmed Java runtime.
#
#   - The runtime holds only the JDK modules the jars need (found by jdeps), compressed.
#   - The synthetic sample is bundled. The real dataset (data/processed, built by scripts/build_data.sh) is NOT put
#     inside the app, because its FBref part may not be redistributed. Instead it is copied to
#     ~/Library/Application Support/Successor/data on this Mac, where the app looks first.
#   - An ahead-of-time class cache (JEP 483/514) is recorded from a training run that visits every page (--tour), so
#     Spring Boot and JavaFX start from pre-loaded classes. A Successor window appears for ~20 s while it records.
#
# Usage: scripts/package-mac.sh [app] [--no-aot] [--skip-tests] [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

APP_ONLY=0 AOT=1 TESTS=1 BUILD=1
for a in "$@"; do
  case "$a" in
    app) APP_ONLY=1 ;;
    --no-aot) AOT=0 ;;
    --skip-tests) TESTS=0 ;;
    --no-build) BUILD=0 ;;
    *) echo "unknown option: $a"; exit 2 ;;
  esac
done
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk/bin/jpackage ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk
fi
for tool in jpackage jlink jdeps java; do
  [[ -x "${JAVA_HOME:-}/bin/$tool" ]] || { echo "$tool not found: install JDK 25+ (brew install openjdk) or set JAVA_HOME"; exit 1; }
done
export PATH="$JAVA_HOME/bin:$PATH"
VERSION=1.0.0
APP=dist/Successor.app

if [[ $BUILD == 1 ]]; then
  echo "1/6  Building$([[ $TESTS == 1 ]] && print ' and testing' || true)"
  if [[ $TESTS == 1 ]]; then mvn -q install; else mvn -q install -DskipTests; fi
else
  echo "1/6  Skipping the build"
fi

echo "2/6  Collecting the jars and the sample data"
INPUT=target/jpackage-input
rm -rf "$INPUT" && mkdir -p "$INPUT"
cp app/target/successor.jar "$INPUT/"
mvn -q -pl app dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$PWD/$INPUT"
cp -R data/sample "$INPUT/sample"

echo "3/6  Drawing the icon"
ICONSET=target/Successor.iconset
rm -rf "$ICONSET" && mkdir -p "$ICONSET"
java scripts/IconGen.java target/icon-1024.png >/dev/null
for size in 16 32 128 256 512; do
  sips -z $size $size target/icon-1024.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
  sips -z $((size * 2)) $((size * 2)) target/icon-1024.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o target/Successor.icns

echo "4/6  Linking a minimal Java runtime"
MODULES=$(jdeps --print-module-deps --ignore-missing-deps --multi-release 25 --class-path "$INPUT/*" "$INPUT"/*.jar \
  2>/dev/null | tail -1)
RUNTIME=target/runtime
rm -rf "$RUNTIME"
# bin/java is kept (no --strip-native-commands): the AOT cache is assembled by a child java process
jlink --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output "$RUNTIME"
echo "     $MODULES ($(du -sh "$RUNTIME" | cut -f1))"

echo "5/6  Packaging the app"
rm -rf dist && mkdir -p dist
jpackage --type app-image --name Successor --app-version $VERSION --vendor "Successor team" \
  --input "$INPUT" --main-jar successor.jar --main-class com.successor.app.Launcher --runtime-image "$RUNTIME" \
  --icon target/Successor.icns --mac-package-identifier com.successor.app --mac-package-name Successor \
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Xmx3g" \
  --java-options '-Dsuccessor.sample=$APPDIR/sample' --dest dist

if [[ -f data/processed/players.csv ]]; then
  SUPPORT="$HOME/Library/Application Support/Successor/data"
  mkdir -p "$SUPPORT"
  cp data/processed/players.csv data/processed/player_seasons.csv data/processed/calibration.properties \
     data/processed/league_strength.properties "$SUPPORT/"
  echo "     real dataset installed for this Mac in $SUPPORT"
else
  echo "     no data/processed: the app will use the bundled synthetic sample (run scripts/build_data.sh first)"
fi

if [[ $AOT == 1 ]]; then
  echo "     recording the AOT class cache (a Successor window opens and visits every page)"
  CACHE="$APP/Contents/app/successor.aot"
  JAVA_TOOL_OPTIONS="-XX:AOTCacheOutput=$PWD/$CACHE" "$APP/Contents/MacOS/Successor" --tour=2500 \
    >target/aot-training.log 2>&1 || true
  if [[ -s "$CACHE" ]]; then
    # $APPDIR is expanded by the launcher; if the cache ever fails to validate, the JVM ignores it and starts normally
    print -r -- 'java-options=-XX:AOTCache=$APPDIR/successor.aot' >> "$APP/Contents/app/Successor.cfg"
    echo "     cache: $(du -sh "$CACHE" | cut -f1)"
  else
    echo "     AOT training did not produce a cache (see target/aot-training.log); continuing without it"
  fi
fi
# files were added after jpackage signed the bundle: re-seal it (ad hoc), or macOS reports the app as damaged
codesign --force --deep --sign - "$APP" >/dev/null 2>&1
codesign --verify --deep --strict "$APP"
echo "     $APP ($(du -sh "$APP" | cut -f1))"

if [[ $APP_ONLY == 0 ]]; then
  echo "6/6  Building the disk image"
  jpackage --type dmg --app-image "$APP" --name Successor --app-version $VERSION --dest dist
  DMG=dist/Successor-$VERSION.dmg
  # jpackage writes zlib (UDZO); LZMA (ULMO, macOS 10.15+) is smaller for the same contents
  hdiutil convert "$DMG" -format ULMO -o target/Successor-lzma.dmg -ov -quiet && mv target/Successor-lzma.dmg "$DMG"
  echo "     $DMG ($(du -sh "$DMG" | cut -f1))"
fi
echo "Done. Open with: open $APP"
