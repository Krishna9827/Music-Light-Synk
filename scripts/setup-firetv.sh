#!/usr/bin/env bash
# One-shot Fire TV / Android TV setup for Music Light Synk.
#
#   ./scripts/setup-firetv.sh <fire-tv-ip> [path/to/music-light-synk.apk]
#
# Optional environment variables (you'll be asked if HA_URL is missing):
#   HA_URL      e.g. http://192.168.1.10:8123
#   WEBHOOK_ID  default: music_light_synk  (must match the blueprint)
#   HA_TOKEN    optional long-lived token -> also updates sensor.music_light_synk
#   SOURCE      auto | artwork | screen     (default: auto)
#
# Needs `adb` (Android platform-tools) and "ADB debugging" enabled on the TV:
#   Fire TV: Settings > My Fire TV > About > click the device name 7x to unlock
#            Developer options, then Developer options > ADB debugging = ON.
set -euo pipefail

PKG="com.musiclightsynk"
LISTENER="$PKG/$PKG.MediaListenerService"
RECEIVER="$PKG/.AdbCommandReceiver"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

[[ $# -ge 1 ]] || die "usage: $0 <fire-tv-ip> [apk]"
command -v adb >/dev/null || die "adb not found. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools"

HOST="$1"
[[ "$HOST" == *:* ]] || HOST="$HOST:5555"
APK="${2:-}"
if [[ -z "$APK" ]]; then
  for candidate in music-light-synk.apk app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/debug/app-debug.apk; do
    [[ -f "$candidate" ]] && APK="$candidate" && break
  done
fi

ADB=(adb -s "$HOST")

step "Connecting to $HOST (accept the 'Allow USB debugging?' prompt on the TV)"
adb connect "$HOST" || true
for _ in $(seq 1 30); do
  state="$(adb -s "$HOST" get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] && break
  sleep 2
done
[[ "$("${ADB[@]}" get-state 2>/dev/null || true)" == "device" ]] || die "TV did not authorise ADB. Accept the prompt on the TV and re-run."

if [[ -n "$APK" ]]; then
  step "Installing $APK"
  "${ADB[@]}" install -r "$APK"
else
  echo "(no APK given/found - assuming the app is already installed)"
fi
"${ADB[@]}" shell pm path "$PKG" >/dev/null || die "$PKG is not installed. Pass the APK path as the 2nd argument."

step "Granting media-session access (notification listener)"
"${ADB[@]}" shell cmd notification allow_listener "$LISTENER" 2>/dev/null || true
current="$("${ADB[@]}" shell settings get secure enabled_notification_listeners | tr -d '\r')"
if [[ "$current" != *"$LISTENER"* ]]; then
  # Older Fire OS without `cmd notification`: write the setting directly.
  if [[ "$current" == "null" || -z "$current" ]]; then new="$LISTENER"; else new="$current:$LISTENER"; fi
  "${ADB[@]}" shell settings put secure enabled_notification_listeners "$new"
fi
echo "  enabled listeners: $("${ADB[@]}" shell settings get secure enabled_notification_listeners | tr -d '\r')"

step "Allowing screen capture without a prompt (only used when an app has no album art)"
"${ADB[@]}" shell appops set "$PKG" PROJECT_MEDIA allow || echo "  (not supported on this device - you'll get a one-time prompt instead)"
"${ADB[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

step "Opening the app once (takes it out of Android's 'stopped' state)"
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2

if [[ -z "${HA_URL:-}" ]]; then
  read -r -p "Home Assistant URL (e.g. http://192.168.1.10:8123): " HA_URL
fi
WEBHOOK_ID="${WEBHOOK_ID:-music_light_synk}"
SOURCE="${SOURCE:-auto}"

step "Configuring: $HA_URL  webhook=$WEBHOOK_ID  source=$SOURCE"
args=(--es ha_url "$HA_URL" --es webhook_id "$WEBHOOK_ID" --es source "$SOURCE")
[[ -n "${HA_TOKEN:-}" ]] && args+=(--es token "$HA_TOKEN")
"${ADB[@]}" shell am broadcast -f 0x20 -n "$RECEIVER" -a "$PKG.CONFIGURE" "${args[@]}"

if [[ "$SOURCE" != "artwork" ]]; then
  step "Starting screen capture"
  "${ADB[@]}" shell am start -n "$PKG/.CaptureConsentActivity" >/dev/null || true
  sleep 2
fi

step "Sending test colours (a rainbow around the edges, purple in the middle)"
"${ADB[@]}" shell am broadcast -f 0x20 -n "$RECEIVER" -a "$PKG.TEST"

step "Status"
"${ADB[@]}" shell am broadcast -f 0x20 -n "$RECEIVER" -a "$PKG.STATUS"

cat <<EOF

Done. Play something in Spotify / Amazon Music / Apple Music / YouTube on the TV and your
lights should follow the artwork. Live log:
  adb -s $HOST logcat -s MusicLightSynk
EOF
