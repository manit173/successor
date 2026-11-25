#!/bin/zsh
# Builds (skipping tests) and launches the Successor macOS app from source.
# Extra arguments go to the app, e.g. scripts/run.sh --page=successor --theme=dark
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mvn -q install -DskipTests
exec "$JAVA" --enable-native-access=ALL-UNNAMED -Xmx3g -cp "app/target/classes:$(cat app/target/cp.txt)" \
  com.successor.app.Launcher "$@"
