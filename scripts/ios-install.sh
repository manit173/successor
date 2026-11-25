#!/bin/zsh
# Builds the iPhone app and installs it on a connected iPhone (or runs the tests in the simulator).
#
# Usage: scripts/ios-install.sh <TEAM_ID>     build, sign with your (free) Apple ID team and install on the iPhone
#        scripts/ios-install.sh --test        run the Swift tests in the iPhone simulator
#
# One-time setup for a device: Xcode → Settings → Accounts → add your Apple ID; on the iPhone, Settings → Privacy &
# Security → Developer Mode → on; connect it by cable and tap "Trust". With a free Apple ID the app runs for 7 days,
# then run this script again.
set -euo pipefail
cd "$(dirname "$0")/../ios"
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
xcodegen generate --quiet
if [[ "${1:-}" == "--test" ]]; then
  SIM=$(xcrun simctl list devices available | grep -m1 -oE "iPhone[^(]*\(([0-9A-F-]{36})\)" | grep -oE "[0-9A-F-]{36}")
  xcodebuild test -project Successor.xcodeproj -scheme Successor -destination "platform=iOS Simulator,id=$SIM" \
    -derivedDataPath build | grep -E "Test Case|Executed|TEST (SUCCEEDED|FAILED)|error:"
  exit 0
fi
TEAM=${1:?usage: scripts/ios-install.sh <TEAM_ID> (Xcode → Settings → Accounts → your team)}
LIST=$(mktemp -t devices)
xcrun devicectl list devices --json-output "$LIST" >/dev/null 2>&1 || true
DEVICE=$(python3 -c '
import json, sys
try:
    for d in json.load(open(sys.argv[1]))["result"]["devices"]:
        h = d.get("hardwareProperties", {})
        if h.get("reality") == "physical" and h.get("deviceType") == "iPhone":
            print(d["identifier"]); break
except Exception:
    pass' "$LIST")
rm -f "$LIST"
[[ -n "$DEVICE" ]] || { echo "no iPhone connected: plug it in, unlock it and tap Trust"; exit 1; }
xcodebuild build -project Successor.xcodeproj -scheme Successor -configuration Release -destination "generic/platform=iOS" \
  -derivedDataPath build DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic -allowProvisioningUpdates \
  | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
xcrun devicectl device install app --device "$DEVICE" build/Build/Products/Release-iphoneos/Successor.app
echo "Installed. First launch: iPhone Settings → General → VPN & Device Management → trust your Apple ID."
