#!/bin/zsh
# Exports the dataset for the iPhone app: the Java engine computes cohorts, league strength and projections and
# writes them, with golden answers for the Swift tests, to ios/Data/real (git-ignored: the real data is licensed for
# local use only). Without a real dataset the app uses the committed synthetic sample in ios/Data/sample.
#
# Usage: scripts/export_phone.sh            (real data from data/processed)
#        scripts/export_phone.sh --sample   (regenerate ios/Data/sample from data/sample)
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk/bin/java ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mvn -q -pl engine install -DskipTests
if [[ "${1:-}" == "--sample" ]]; then
  "$JAVA" -cp engine/target/classes com.successor.engine.phone.PhoneExport data/sample ios/Data/sample
else
  [[ -f data/processed/players.csv ]] || { echo "no data/processed: run scripts/build_data.sh first"; exit 1; }
  "$JAVA" -Xmx6g -cp engine/target/classes com.successor.engine.phone.PhoneExport data/processed ios/Data/real
fi
