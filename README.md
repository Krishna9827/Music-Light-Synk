# Music Light Synk

Your lights take on the colours of the album art playing on your **Fire TV / Android TV**.

A small TV app reads the album art of whatever is playing (Spotify, Amazon Music, Apple Music,
YouTube, ...), extracts **4 corner colours** (plus dominant / vibrant / average), and sends
**one webhook to Home Assistant per track change**. A Home Assistant blueprint maps those
colours to your lights.

No Raspberry Pi, no HDMI grabber, and no continuous colour stream, so slow cloud bulbs like Tuya
keep up.

```
 Spotify / Amazon Music / YouTube ... on the Fire TV
        |  Android media session: title, artist, album art (no screen recording needed)
        v
 Music Light Synk app  --  4 corners + dominant + vibrant  -->  1 HTTP POST per track
        |                                                       (LAN webhook, ~50 ms)
        v
 Home Assistant blueprint  -->  light.turn_on for every group in parallel  -->  Tuya / Hue / WLED / ...
        ^
        '-- "stopped" after 15 s of pause  -->  restore previous light state (or turn off)
```

## Does this already exist?

I checked before building. Nothing does "TV album art → a few colours → Home Assistant, sent
only when the track changes":

| Option | What it does | Why it doesn't fit |
|---|---|---|
| [HyperHDR](https://github.com/awawa-dev/HyperHDR) / [Hyperion Android grabber](https://github.com/abrenoch/hyperion-android-grabber) | Continuous screen grab streamed to LEDs | Built for movies. It streams colours many times a second, which Tuya/cloud bulbs can't follow, and you need a Pi or a server running |
| HA [Color Extractor](https://www.home-assistant.io/integrations/color_extractor) + `media_player.entity_picture` | One dominant colour from the artwork URL | Only works if your TV integration exposes album art. Fire TV through the [ADB integration](https://www.home-assistant.io/integrations/androidtv/) only gives a screenshot, refreshed every ~10 s by polling. One colour, not 4 |
| [ad-media-lights-sync](https://github.com/ericmatte/ad-media-lights-sync) (AppDaemon) | Same idea as Color Extractor | Same limitation: needs `entity_picture`, needs AppDaemon |
| Philips Hue Sync / Govee DreamView | Screen sync | Their own hardware only, and continuous |

So I built it.

## Install

You need a computer with `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools))
on the same Wi-Fi as the TV, just for the one-time setup.

**On a Mac? Follow [docs/SETUP-MAC.md](docs/SETUP-MAC.md).** It's every step as copy-paste
commands, with no script or git clone needed.

1. **Get the APK.** Open the repo's **Actions** tab, then the latest *Build APK* run, then the
   artifact `music-light-synk-apk`. Unzip it to get `music-light-synk.apk`. If you push a tag
   like `v0.1.0`, the APK is also attached to a GitHub Release.
2. **Enable ADB on the Fire TV.** Go to *Settings > My Fire TV > About* and click the device name
   7× to unlock Developer options. Then *Developer options > ADB debugging = ON*. Note the IP
   address (*About > Network*).
3. **Run the setup script** (installs the app, grants access, configures Home Assistant, sends
   test colours):
   ```bash
   HA_URL=http://192.168.1.10:8123 ./scripts/setup-firetv.sh 192.168.1.50 music-light-synk.apk
   ```
   On Windows:
   ```powershell
   .\scripts\setup-firetv.ps1 -TvIp 192.168.1.50 -Apk .\music-light-synk.apk -HaUrl http://192.168.1.10:8123
   ```
   Accept the *"Allow USB debugging?"* prompt on the TV the first time.
4. **Import the blueprint into Home Assistant.**
   [![Import blueprint](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2FKrishna9827%2FMusic-Light-Synk%2Fblob%2Fmain%2Fhome-assistant%2Fblueprints%2Fautomation%2Fmusic_light_synk.yaml)
   The button only works if the repo is **public** and the file is on `main`. Otherwise (e.g. a
   private repo) copy `home-assistant/blueprints/automation/music_light_synk.yaml` to
   `/config/blueprints/automation/music_light_synk/` (the File editor add-on is easiest) and
   reload automations.
   Then go to *Settings > Automations > Create automation > Music Light Synk*, keep the webhook
   ID `music_light_synk` (or change it in both places), and pick which lights get which corner.
5. Play a song on the TV.

If you only have one or two lights, put them under **Dominant colour**.

## Settings (on the TV app or over ADB)

The settings screen works with the remote's D-pad. Every setting can also be set over ADB
(see below), so you never have to type a URL with the remote.

| Setting | ADB extra | Default | Notes |
|---|---|---|---|
| Home Assistant URL | `ha_url` | – | `http://<ha-ip>:8123` |
| Webhook ID | `webhook_id` | `music_light_synk` | Must match the blueprint |
| Long-lived token | `token` | empty | Optional. If set, the app also updates `sensor.music_light_synk` (state = dominant hex, attributes = everything) for dashboards |
| Source | `source` | `auto` | `auto` = album art, or the screen corners if the app gives no art. `artwork` = album art only. `screen` = screen corners only |
| Corner patch size | `corner_pct` | 25 % | How much of each corner is sampled |
| Saturation boost | `saturation_pct` | 130 % | Bulbs look washed out without it |
| Minimum brightness | `min_brightness_pct` | 15 % | Brightness for black artwork |
| Re-sample screen every | `screen_interval` | 0 s | 0 = once per track. Set to e.g. 5 to also follow videos (only used in screen mode) |
| Send "stopped" after | `stop_delay` | 15 s | Pause this long and the blueprint restores your lights |
| Only these apps | `packages` | all | e.g. `com.spotify.tv.android,com.amazon.mp3` |
| Syncing on/off | `enabled` | on | |
| Restart capture after reboot | `boot_capture` | on | Only relevant for `auto`/`screen` |

### ADB cheat sheet

```bash
adb connect 192.168.1.50
R="-n com.musiclightsynk/.AdbCommandReceiver"

# configure (any subset of the extras above)
adb shell am broadcast $R -a com.musiclightsynk.CONFIGURE --es ha_url http://192.168.1.10:8123 --es source artwork --ei corner_pct 30
adb shell am broadcast $R -a com.musiclightsynk.TEST      # red/green/blue/amber test colours
adb shell am broadcast $R -a com.musiclightsynk.STATUS    # access granted? last result? now playing?
adb shell am start -n com.musiclightsynk/.CaptureConsentActivity   # (re)start screen capture

# permissions the setup script grants
adb shell cmd notification allow_listener com.musiclightsynk/com.musiclightsynk.MediaListenerService
adb shell appops set com.musiclightsynk PROJECT_MEDIA allow        # screen capture without a prompt
adb shell appops set com.musiclightsynk SYSTEM_ALERT_WINDOW allow  # re-open capture after reboot

adb logcat -s MusicLightSynk                                        # live log
```

The config receiver and capture activity are protected by `android.permission.DUMP`. Only the
ADB shell holds that permission, so other apps on the TV can't reconfigure the app or redirect
your token.

## Payload (for your own automations)

`POST /api/webhook/<webhook_id>`. A full example is in [`docs/payload-colors.json`](docs/payload-colors.json):

```jsonc
{
  "event": "colors",            // "colors" | "stopped" | "test"
  "reason": "track",            // "track" | "update" (art arrived late) | "screen_poll" | "test"
  "session_start": true,        // first colours after a stop -> blueprint snapshots your lights
  "source": "artwork",          // "artwork" | "screen"
  "app": "Spotify", "package": "com.spotify.tv.android",
  "title": "Blinding Lights", "artist": "The Weeknd", "album": "After Hours",
  "playing": true, "device": "Amazon AFTKRT", "timestamp": 1762590000,
  "colors": {
    "top_left": {
      "hs": [1.4, 99.9],        // -> light.turn_on hs_color (best for Tuya: it's native HSV)
      "brightness": 233,        // -> light.turn_on brightness (1-255, from how dark the art is)
      "brightness_pct": 91,
      "hex": "#FF0600", "rgb": [255, 6, 0],          // bulb colour (full value, saturation boosted)
      "raw_hex": "#E53935", "raw_rgb": [229, 57, 53] // what the artwork actually looks like
    },
    "top_right": {}, "bottom_left": {}, "bottom_right": {},
    "dominant": {}, "vibrant": {}, "average": {}
  }
}
```

Example of a hand-written automation:

```yaml
triggers:
  - trigger: webhook
    webhook_id: music_light_synk
    allowed_methods: [POST]
    local_only: true
conditions: "{{ trigger.json.event == 'colors' }}"
actions:
  - action: light.turn_on
    target: { entity_id: light.tv_backlight }
    data:
      hs_color: "{{ trigger.json.colors.dominant.hs }}"
      brightness: "{{ trigger.json.colors.dominant.brightness }}"
```

## Making Tuya fast

The app already sends at most one update per track. The rest of the lag is Tuya's cloud:

- **Switch to local control.** Install [Tuya Local](https://github.com/make-all/tuya-local)
  (or LocalTuya) from HACS. Commands go straight over your LAN instead of a round-trip through
  Tuya's cloud and its rate limits. This is the biggest improvement you can make.
- Set the blueprint **fade time to 0** for Tuya bulbs, because most of them ignore fades anyway.
- Don't put the same bulb in two groups, or it gets two commands per track.
- The blueprint updates all groups **in parallel**, so 4 corners cost the same time as 1.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Media access: NOT granted" | Run the `allow_listener` command above (Fire TV hides that settings screen) |
| "No colours: X did not provide album art" | That app doesn't publish artwork. Use source `auto` with screen capture running |
| Screen colours are black | DRM-protected video (Netflix, Prime) blocks capture. Black frames are ignored on purpose |
| Nothing happens in Home Assistant | Run the `STATUS` broadcast. `HTTP 200` = HA got it, so check the automation trace. `ConnectException` = wrong URL/port |
| Same colours for the next song | That's on purpose: if the art barely changes (same album), the app doesn't resend it |
| Lights restore while music is playing | Raise "Send 'stopped' after" (some apps briefly pause between tracks) |

## How it's built

- `MediaListenerService`: a `NotificationListenerService`, used only because Android limits
  other apps' media sessions (`MediaSessionManager.getActiveSessions`) to notification
  listeners. The system keeps a granted listener bound, so Fire OS doesn't kill it.
- `ColorExtractor`: pure Kotlin, unit tested. For each corner it finds a "smoothed mode"
  (4-bit histogram, densest 3×3×3 neighbourhood). That handles gradients and ignores small logos
  better than a plain average. Colours become bulb-friendly HS at full value, with darkness
  moved into `brightness`.
- `SyncEngine`: decides when to send. It skips duplicates (same album art) and delays "stopped"
  so short gaps between tracks don't trigger it. It retries once on Wi-Fi hiccups.
- `ScreenCaptureService`: optional fallback for apps without album art. MediaProjection into a
  160×90 `ImageReader`. Between captures the virtual display has **no surface**, so nothing is
  rendered while idle. It is not a continuous grabber.
- Everything is plain Android framework code (no AndroidX), minSdk 25 (Fire OS 6+), targetSdk 34.

## Testing

- `./gradlew testReleaseUnitTest`: colour extraction and payload schema tests (JVM).
- CI (`.github/workflows/build.yml`) builds the signed APK and lints the blueprint
  (`scripts/check_blueprint.py`).
- The blueprint was run against a real Home Assistant 2026.2 with demo lights, using the
  payloads the app's own code generates. Each corner landed on the right light with the right
  hs and brightness. "stopped" restored the original states and deleted the snapshot scene.
  Fixed brightness, "turn off on stop", extra conditions, junk bodies and the
  `sensor.music_light_synk` REST mirror all behaved correctly.
- Not yet tested on a physical Fire TV. Please report what your music apps do: does the status
  line show album art, or "did not provide album art"?

## Ideas for later

- MQTT transport, for people who prefer it over webhooks.
- A direct WLED/UDP mode that skips Home Assistant entirely for LED strips (instant).
- Beat "pulse" using Android's `Visualizer` API. It needs RECORD_AUDIO, so it's only for local
  bulbs; cloud Tuya can't keep up.
- Per-app profiles (e.g. screen mode for YouTube, artwork for Spotify).
- A small HA custom integration that exposes the 4 colours as entities.
