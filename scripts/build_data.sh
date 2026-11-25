#!/bin/zsh
# Downloads the raw sources (if needed), links them and writes data/processed/ plus data/processed/link_report.md.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
scripts/fetch_data.sh
mvn -q -pl engine install -DskipTests
"$JAVA" -Xmx3g -cp engine/target/classes com.successor.engine.data.DatasetBuilder data/raw data/processed
