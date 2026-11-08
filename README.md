# Music Light Synk

Your lights take on the colours of the album art playing on your **Fire TV / Android TV**.

A small TV app reads the album art of whatever is playing (Spotify, Amazon Music, Apple Music,
YouTube, ...), extracts **4 corner + 4 side colours** (plus dominant / vibrant / average), and sends
them **once per track change** to any mix of:

- **Home Assistant**, through the **Music Light Synk integration** (install from HACS, pick your
  lights in the UI, done) or a blueprint if you prefer automations,
- **WLED directly** over your LAN (instant, skips Home Assistant), as album colours or an ambient
  gradient around the TV that can **pulse to the beat**.

No Raspberry Pi, no HDMI grabber, and no continuous colour stream, so slow cloud bulbs like Tuya
keep up.

```
 Spotify / Amazon Music / YouTube ... on the Fire TV
        |  Android media session: title, artist, album art (no screen recording needed)
        v
 Music Light Synk app  --  4 corners + 4 sides + dominant  -->  1 HTTP POST per track
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
4. **Home Assistant: install the integration with HACS.**

   [![Open in HACS](https://my.home-assistant.io/badges/hacs_repository.svg)](https://my.home-assistant.io/redirect/hacs_repository/?owner=Krishna9827&repository=Music-Light-Synk&category=integration)

   Click the button, or go to *HACS → ⋮ → Custom repositories* and add
   `https://github.com/Krishna9827/Music-Light-Synk` as type **Integration**. Then **Download**
   and **restart Home Assistant**. Then:

   [![Add integration](https://my.home-assistant.io/badges/config_flow_start.svg)](https://my.home-assistant.io/redirect/config_flow_start/?domain=music_light_synk)

   Name it (e.g. *Living room TV*), keep the webhook ID `music_light_synk` (what the app sends to
   out of the box), and **pick which lights follow which part of the artwork**. Done. No YAML, no
   blueprint. Press the device's **Test lights** button to check the mapping.
5. Play a song on the TV.

If you only have one or two lights, put them under **Dominant colour**.

**Prefer an automation?** The blueprint does the same light mapping:
[![Import blueprint](https://my.home-assistant.io/badges/blueprint_import.svg)](https://my.home-assistant.io/redirect/blueprint_import/?blueprint_url=https%3A%2F%2Fgithub.com%2FKrishna9827%2FMusic-Light-Synk%2Fblob%2Fmain%2Fhome-assistant%2Fblueprints%2Fautomation%2Fmusic_light_synk.yaml)
Use the integration **or** the blueprint on a given webhook ID, not both. Home Assistant allows
only one listener per webhook ID, and the integration refuses an ID the blueprint already uses.
Using MQTT instead of webhooks? Use `music_light_synk_mqtt.yaml` (see [MQTT](#mqtt)).

```
 artwork / TV screen          blueprint light groups
 +-----+---------+-----+
 | TL  |   Top   | TR  |      Top-left, Top edge, Top-right,
 +-----+---------+-----+      Left edge, Right edge,
 |Left | dominant|Right|      Bottom-left, Bottom edge, Bottom-right,
 +-----+---------+-----+      Dominant (whole artwork), Vibrant (accent)
 | BL  | Bottom  | BR  |
 +-----+---------+-----+
```

### WLED and other effect lights

By default the integration (and the blueprint) **switches any running effect to a solid colour**. Otherwise WLED keeps
running e.g. "Running" and only changes its colour. In the blueprint's **Effects** section you
can choose instead (integration: *Configure → Effects*; blueprint: *Effects* section):

- **Run an effect in the artwork colours**: pick one (Running, Breathe, Fade, Scan, Twinkle,
  Meteor, ...) or type any name your light lists. Lights without that effect get a solid colour.
  Many WLED effects use their own palette (often a rainbow). Add your WLED *Color palette*
  select entities, and they're switched to `* Color 1` while the effect runs, so it uses the
  album colour.
- **Leave effects alone**: the old behaviour.

On "Restore", the previous effect and palette come back when the music stops. Lights without
effects (most Tuya bulbs) are never sent an effect.

## Extras

All optional. They can be mixed: e.g. Tuya bulbs via Home Assistant **and** a WLED strip directly.

### Per-app profiles

Each app can have its own source: **Album art**, **Screen**, **Auto**, or **Ignore this app**.
Play something, then press **"This app uses: ..."** in the TV app to cycle through them. Or set
them over ADB:

```bash
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --es profiles "com.google.android.youtube.tv=screen;com.spotify.tv.android=artwork;com.netflix.ninja=off"
```

Ignored apps are invisible to the app. Starting Netflix after Spotify counts as "music stopped",
so your lights get restored.

### MQTT

For people who'd rather use their broker than webhooks. The app has a tiny built-in MQTT 3.1.1
client (user/password, optional TLS) and publishes:

| Topic | Retained | What |
|---|---|---|
| `<prefix>/event` | no | every payload. The MQTT blueprint listens here |
| `<prefix>/colors` | yes | last colours |
| `<prefix>/state` | yes | playing / app / title / artist |
| `<prefix>/availability` | yes | `online` / `offline` (last will) |

The prefix defaults to `music_light_synk`. Give each TV its own, e.g. `music_light_synk/bedroom`.
With **discovery** on (the default), Home Assistant creates a device with 11 colour sensors, a
"Now playing" sensor and a "Playing" binary sensor automatically. To drive lights, use the
**MQTT blueprint** `home-assistant/blueprints/automation/music_light_synk_mqtt.yaml`. It's identical
to the webhook one but listens on `<prefix>/event`.

```bash
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --ez mqtt_enabled true --es mqtt_host 192.168.1.10 --es mqtt_user mqtt --es mqtt_pass secret
```

### WLED direct (skips Home Assistant)

Talks to WLED straight over your Wi-Fi, so it's instant. Two modes:

- **Colours** (`wled_mode colors`): sets WLED colours 1/2/3 (default: dominant, vibrant, average)
  through the JSON API, so **WLED's own effects run in the album colours**. Optionally also pick
  the effect (`wled_fx`, WLED effect ID) and palette (`wled_pal`, e.g. `5` = "Colors Only").
  WLED's previous state is saved and restored when the music stops.
- **Ambient gradient** (`wled_mode ambient`): for a strip around the TV. Paints a smooth gradient
  through the 4 corner + 4 side colours and fades between tracks (UDP realtime / DNRGB). When the
  music stops, the app stops sending and WLED goes back to its own effect by itself about a second
  later.
  Tell it how the strip runs: LED counts `top,right,bottom,left` (0 for a side without LEDs),
  where LED 0 is, and the direction (as seen from the front):

```bash
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --es wled_mode ambient --es wled_hosts 192.168.1.60 --es wled_layout 40,22,40,22 \
  --es wled_start bottom_left --ez wled_clockwise true
```

In WLED, *Config → Sync Interfaces → Realtime → "Receive UDP realtime"* must be on (it is by
default). If the strip is also in the blueprint, remove it there so the two don't fight.

**Beat pulse** (ambient mode only): the gradient pulses on the kick drum. It uses Android's
`Visualizer` on the TV's audio output, so the app needs audio permission: turn it on in the app
and accept the prompt, or run `adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO`.
It runs at ~30 fps over UDP, which is why it's WLED-only: Home Assistant and cloud bulbs can't
keep up with that.
Honest caveats: some devices or apps hand the Visualizer silence, and **HDMI passthrough**
(Dolby/DTS bitstream to a soundbar) bypasses Android's mixer completely. The status line tells
you if it only hears silence.

```bash
adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --ez pulse true --ei pulse_strength 60
```

### Home Assistant integration

Installed via HACS (step 4 above). Each TV is one device with:

| Entity | What |
|---|---|
| `switch.<tv>_light_sync` | Turn the light control on/off, e.g. from a "movie night" scene. Turning it off gives the lights back |
| `button.<tv>_test_lights` | Rainbow test pattern on your mapped lights for 6 s, then restores them |
| `sensor.<tv>_top_left_colour` … (11) | State = hex colour; attributes `hs`, `rgb`, `brightness`, `raw_hex`, … |
| `sensor.<tv>_now_playing` | Title or `idle`, with app/artist/album attributes |
| `binary_sensor.<tv>_playing` | On while music plays |

Plus a `music_light_synk_event` on the event bus for every payload, handy as an automation trigger.

**Configure** (on the integration card) has four sections: **Lights** per zone, **Brightness and
fades** (follow the artwork or fixed, max brightness, fade time), **Effects** (solid / run an
effect / leave alone, plus WLED palettes), and **When the music stops** (restore / off / nothing).
Changes apply from the next track. No restart is needed.

Two TVs? Add the integration twice with different webhook IDs, and give each app its own ID.

**Coming from the blueprint?** Delete that automation (it holds the `music_light_synk` webhook),
then add the integration with the same ID. The app needs no change. Or keep the blueprint and give
the integration another ID, e.g. `music_light_synk_entities`, and set the app to
`music_light_synk,music_light_synk_entities`.

Without HACS: copy `custom_components/music_light_synk` into your HA `config/custom_components/`
and restart.

## Settings (on the TV app or over ADB)

The settings screen works with the remote's D-pad. Every setting can also be set over ADB
(see below), so you never have to type a URL with the remote.

| Setting | ADB extra | Default | Notes |
|---|---|---|---|
| Home Assistant URL | `ha_url` | – | `http://<ha-ip>:8123` |
| Webhook ID(s) | `webhook_id` | `music_light_synk` | Must match the blueprint. Comma separate to send to several (blueprint + integration) |
| Long-lived token | `token` | empty | Optional. If set, the app also updates `sensor.music_light_synk` (state = dominant hex, attributes = everything) for dashboards |
| Source | `source` | `auto` | `auto` = album art, or the screen corners if the app gives no art. `artwork` = album art only. `screen` = screen corners only |
| Edge / corner size | `corner_pct` | 25 % | Depth of the edge band that's sampled. Corners are squares of this size, sides are the band between them |
| Saturation boost | `saturation_pct` | 130 % | Bulbs look washed out without it |
| Minimum brightness | `min_brightness_pct` | 15 % | Brightness for black artwork |
| Re-sample screen every | `screen_interval` | 0 s | 0 = once per track. Set to e.g. 5 to also follow videos (only used in screen mode) |
| Send "stopped" after | `stop_delay` | 15 s | Pause this long and the blueprint restores your lights |
| Only these apps | `packages` | all | e.g. `com.spotify.tv.android,com.amazon.mp3` |
| Syncing on/off | `enabled` | on | |
| Restart capture after reboot | `boot_capture` | on | Only relevant for `auto`/`screen` |
| Per-app profiles | `profiles` | none | `pkg=artwork\|screen\|auto\|off;pkg=...` |
| MQTT on/off | `mqtt_enabled` | off | |
| MQTT broker | `mqtt_host`, `mqtt_port`, `mqtt_user`, `mqtt_pass`, `mqtt_tls` | –, 1883 | |
| MQTT topic prefix | `mqtt_topic` | `music_light_synk` | One per TV |
| MQTT discovery | `mqtt_discovery` | on | Creates HA entities automatically |
| WLED mode | `wled_mode` | `off` | `off` / `colors` / `ambient` |
| WLED devices | `wled_hosts` | – | IPs, comma separated |
| WLED strip layout | `wled_layout`, `wled_start`, `wled_clockwise` | –, `bottom_left`, true | Ambient mode. Counts `top,right,bottom,left` |
| WLED colours 1/2/3 | `wled_colors` | `dominant,vibrant,average` | Colours mode. Any slot names |
| WLED effect / palette | `wled_fx`, `wled_pal` | -1 (keep) | Colours mode. WLED IDs |
| WLED brightness | `wled_brightness_pct` | 100 | Scales everything sent to WLED |
| Restore WLED on stop | `wled_restore` | on | Colours mode |
| Beat pulse | `pulse`, `pulse_strength` | off, 60 % | Ambient mode, needs audio permission |

### ADB cheat sheet

```bash
adb connect 192.168.1.50
R="-n com.musiclightsynk/.AdbCommandReceiver"

# configure (any subset of the extras above)
adb shell am broadcast $R -a com.musiclightsynk.CONFIGURE --es ha_url http://192.168.1.10:8123 --es source artwork --ei corner_pct 30
adb shell am broadcast $R -a com.musiclightsynk.TEST      # rainbow test colours around the "screen"
adb shell am broadcast $R -a com.musiclightsynk.STATUS    # access granted? last result? now playing?
adb shell am start -n com.musiclightsynk/.CaptureConsentActivity   # (re)start screen capture

# permissions the setup script grants
adb shell cmd notification allow_listener com.musiclightsynk/com.musiclightsynk.MediaListenerService
adb shell appops set com.musiclightsynk PROJECT_MEDIA allow        # screen capture without a prompt
adb shell appops set com.musiclightsynk SYSTEM_ALERT_WINDOW allow  # re-open capture after reboot

adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO  # only for beat pulse

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
    "top": {}, "top_right": {}, "left": {}, "right": {},
    "bottom_left": {}, "bottom": {}, "bottom_right": {},
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
- The blueprint updates all groups **in parallel**, so 8 zones cost the same time as 1.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Media access: NOT granted" | Run the `allow_listener` command above (Fire TV hides that settings screen) |
| "No colours: X did not provide album art" | That app doesn't publish artwork. Use source `auto` with screen capture running |
| Screen colours are black | DRM-protected video (Netflix, Prime) blocks capture. Black frames are ignored on purpose |
| Nothing happens in Home Assistant | Run the `STATUS` broadcast. `HTTP 200` = HA got it, so check the automation trace. `ConnectException` = wrong URL/port |
| Same colours for the next song | That's on purpose: if the art barely changes (same album), the app doesn't resend it |
| Lights restore while music is playing | Raise "Send 'stopped' after" (some apps briefly pause between tracks) |
| `MQTT broker refused connection: bad username or password` | Check `mqtt_user` / `mqtt_pass` (the app doesn't retry a refused login) |
| WLED ambient does nothing | Check the layout is valid (`STATUS` shows it) and *Receive UDP realtime* is on in WLED |
| "Beat pulse hears silence" | The device or app blocks audio analysis, or HDMI passthrough is on. Use PCM/stereo output to test |
| Integration says the webhook ID is in use | The blueprint automation holds it. Delete that automation first, or use another ID (e.g. `music_light_synk_entities`) and add it to the app |

## How it's built

- `MediaListenerService`: a `NotificationListenerService`, used only because Android limits
  other apps' media sessions (`MediaSessionManager.getActiveSessions`) to notification
  listeners. The system keeps a granted listener bound, so Fire OS doesn't kill it.
- `ColorExtractor`: pure Kotlin, unit tested. For each corner / side it finds a "smoothed mode"
  (4-bit histogram, densest 3×3×3 neighbourhood). That handles gradients and ignores small logos
  better than a plain average. Colours become bulb-friendly HS at full value, with darkness
  moved into `brightness`.
- `SyncEngine`: decides when to send. It skips duplicates (same album art) and delays "stopped"
  so short gaps between tracks don't trigger it. It retries once on Wi-Fi hiccups.
- `ScreenCaptureService`: optional fallback for apps without album art. MediaProjection into a
  160×90 `ImageReader`. Between captures the virtual display has **no surface**, so nothing is
  rendered while idle. It is not a continuous grabber.
- `Mqtt.kt`: a ~250-line MQTT 3.1.1 publisher (QoS 0, retain, last will, keep-alive) plus
  Home Assistant discovery. No library.
- `Wled.kt`: strip layout → per-LED gradient, DNRGB packets, a renderer thread (~30 fps while
  fading or pulsing, 1 Hz keep-alive otherwise), the JSON API with save/restore, and the beat
  detector (bass energy vs. a moving average).
- `AudioPulse`: Android `Visualizer` on the output mix → beat detector → WLED renderer.
- Everything is plain Android framework code (no AndroidX, no libraries), minSdk 25 (Fire OS 6+),
  targetSdk 34. MQTT, WLED, colour extraction and payloads are framework-free Kotlin, so they
  are unit tested on the JVM.

## Testing

- `./gradlew testReleaseUnitTest`: 37 JVM tests. Colour extraction, payload schema, per-app
  profiles, MQTT packets against a fake broker (connect, auth refusal, retained topics,
  discovery), WLED layout orientation, DNRGB packets, the UDP renderer (fade in, stop, silence
  after stop), the JSON save → set → restore flow against a fake WLED, and beat detection.
- CI (`.github/workflows/build.yml`) builds the signed APK, checks both blueprints, checks the
  MQTT blueprint is regenerated (`scripts/build_blueprints.py --check`) and byte-compiles the integration.
  `validate.yml` runs Home Assistant's **hassfest** and the **HACS** validator.
- The integration's light control was run in a real Home Assistant 2026.2 with demo lights:
  - the setup wizard (name, webhook, lights per zone) and the sectioned *Configure* screen work,
    and clearing a zone really clears it;
  - every zone gets its colour and brightness, and a running effect is switched to solid;
  - "run an effect" (case-insensitive name) also sets the palette;
  - a repeated `session_start` doesn't overwrite the snapshot, and stop restores colours, effect
    and palette;
  - *Light sync* off gives the lights back and ignores new colours;
  - *Test lights* shows the pattern and restores after 6 s.
- End to end against **real** Mosquitto 2.0 + Home Assistant 2026.2:
  - The app's MQTT code logged in with a password and published. HA discovery created all 13
    entities, the MQTT blueprint set the lights, and "stopped" turned them off.
  - The custom integration was added through its config flow. It rejects bad IDs and the
    blueprint's ID, and refuses duplicates. Its entities filled from a webhook payload, flipped
    to idle/off on stop, and restored after a restart. The bus event reached an automation.
- The blueprint was run against a real Home Assistant 2026.2 with demo lights, using the
  payloads the app's own code generates. Each corner and side landed on the right light with the right
  hs and brightness. "stopped" restored the original states and deleted the snapshot scene.
  Fixed brightness, "turn off on stop", extra conditions, junk bodies and the
  `sensor.music_light_synk` REST mirror all behaved correctly. Effects were tested too: a
  running effect gets switched to solid, "run an effect" sets it (case-insensitive) plus the
  palette select, and "restore" brings back the old effect and palette. A repeated
  `session_start` doesn't overwrite the "before" snapshot.
- Not covered by these tests: anything that needs the physical TV or a real WLED. That includes
  whether the Fire TV `Visualizer` returns real audio, and the UDP/JSON packets on actual WLED
  firmware (they follow the documented protocols).

## Ideas for later

- Hue Entertainment API output (fast, like WLED UDP, for Hue lights).
- Per-app WLED mode (e.g. ambient for music, off for movies).
- Submit to the HACS default list (needs a brand icon in home-assistant/brands, repo topics and a
  description).

## License

[MIT](LICENSE)
