#!/usr/bin/env bash
# Builds "Music Light Synk.app" (universal: Apple silicon + Intel) and zips it.
# Usage: scripts/build_mac_app.sh <version> <build-number> <out.zip>
# Runs on macOS (GitHub Actions). The app is ad-hoc signed, not notarised: first launch needs
# System Settings → Privacy & Security → Open Anyway (see docs/SETUP-APPLE.md).
set -euo pipefail
VERSION="${1:?version}"
BUILD="${2:?build number}"
OUT="${3:?output zip}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac   # relative to where it was called from
cd "$ROOT/apple"

swift build -c release --arch arm64 --arch x86_64 --product MusicLightSynk
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/MusicLightSynk"

APP="$ROOT/build/Music Light Synk.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/MusicLightSynk"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" MacApp/Info.plist > "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist"
codesign --force --sign - "$APP"
codesign --verify --verbose "$APP"
lipo -info "$APP/Contents/MacOS/MusicLightSynk"

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
ditto -c -k --keepParent "$APP" "$OUT"
echo "Built $OUT"
