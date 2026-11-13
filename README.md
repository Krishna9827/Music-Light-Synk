# Music Light Synk

Your lights take on the colours of the album art playing on your **Fire TV / Android TV**, and
WLED strips can **bounce to the beat** (like LedFx's Energy effect, without LedFx).

A small TV app reads the album art of whatever is playing (Spotify, Amazon Music, Apple Music,
YouTube, ...), extracts **4 corner + 4 side colours** (plus dominant / vibrant / average), and sends
them **once per track change** to any mix of:

- **Home Assistant**, through the **Music Light Synk integration** (install from HACS, pick your
  lights in the UI, done) or a blueprint if you prefer automations,
- **WLED directly** over your LAN (instant, skips Home Assistant): as many WLEDs as you like,
  each showing its own part of the artwork (a corner, a side, dominant, ...), WLED's own effects in
  album colours, or an ambient gradient around the TV. Any strip can run the **Energy** effect:
  bars for lows / mids / highs bounce with the music, with LedFx-style **flip** and **mirror**.

The same app runs on an **Android phone**, fully on its own: its own WLED list, its own room,
controlled from Home Assistant over Wi-Fi (no Fire TV or ADB needed). Devices in the same room can
share the beat, and the phone can be a **backup listener**: its mic hears music played anywhere
else (an iPhone, a PC on the speakers) and keeps its strips moving to the beat. A TV that can't
hear its own audio (HDMI passthrough to a soundbar) can use **the phone's microphone**, picked by
name in the app or in Home Assistant. And Home Assistant lights can **pulse to the beat** too.

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

1. **Get the APK** from the [latest release](https://github.com/Krishna9827/Music-Light-Synk/releases/latest)
   (`music-light-synk.apk`). Every release also has the integration zip and both blueprints.
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

Talks to WLED straight over your Wi-Fi, so it's instant. Add **as many WLED devices as you like**
and give each one a zone. On the TV: *WLED devices → Add*, then press the zone button to cycle.
From Home Assistant: the *WLED devices* text entity. Over ADB: `wled_devices`.

| Zone | What the WLED shows | How |
|---|---|---|
| `top_left`, `top`, `top_right`, `left`, `right`, `bottom_left`, `bottom`, `bottom_right`, `dominant`, `vibrant`, `average` | That part of the artwork as one solid colour. Add `:keep` to keep WLED's current effect and only change its colour | JSON API |
| `palette` | Colours 1/2/3 = dominant / vibrant / average, so **WLED's own effects run in the album colours**. Options: `:dominant,vibrant,top` (pick the 3), `:fx=28` (effect ID), `:pal=5` (palette ID) | JSON API |
| `ambient` | A strip around the TV: smooth gradient through all 8 edge colours, fading between tracks, optional beat pulse. Options: LED counts `top,right,bottom,left` (0 = no LEDs on that side), where LED 0 is, `cw`/`ccw` as seen from the front | UDP realtime (DNRGB) |

Format: `host=zone[:options]`, separated by `;`. Example: a strip around the TV, a lamp on the left,
a lamp on the right, and a shelf running its own effect in album colours:

```bash
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --es wled_devices '192.168.1.60=ambient:40,22,40,22:bottom_left:cw; 192.168.1.61=left; 192.168.1.62=right; 192.168.1.63=palette:fx=28'
```

JSON devices are put back the way they were when the music stops. Ambient strips go back to
their own effect by themselves about a second after the app stops sending. In WLED,
*Config → Sync Interfaces → Realtime → "Receive UDP realtime"* must be on for ambient strips (it
is by default). If a WLED is also in the blueprint or the integration's light zones, remove it
there so the two don't fight. (The v0.3 settings `wled_mode` / `wled_hosts` / `wled_layout` still
work over ADB and are converted to a device list.)

**Beat pulse** (ambient strips only): the gradient pulses on the kick drum. It uses Android's
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

### Energy: strips that bounce to the music

Like LedFx's *Energy* effect, built into the app: bars for **lows, mids and highs** grow along
the strip with the music, ~40 times a second over UDP. Turn it on per WLED device (*Energy: ON*
in the app, or `:energy` in the device list), then pick the direction with the same two switches
as LedFx:

| Flip | Mirror | Bars grow | e.g. |
|---|---|---|---|
| off | off | from LED 0 to the end | standing bar with LED 0 at the bottom |
| on | off | from the last LED back to LED 0 | standing bar with LED 0 at the top |
| off | on | from the middle out to both ends | |
| on | on | from both ends toward the middle | TV backlight, "bounces from both sides" |

- On a **section** zone (`dominant`, `left`, ...) the three bars use three album colours: the
  zone's own colour for lows, the vibrant colour for mids and a contrasting one for highs. The
  app reads the LED count from WLED (`/json/info`); add `:leds=60` to set it by hand.
- On an **ambient** strip the bars bounce in the gradient's own colours, so each part of the
  strip keeps its place in the artwork.
- *Beat & audio* settings: **Sensitivity** (how far the bars reach), **Blur** (soft edges),
  **Background brightness** (how much of the colour stays lit where no bar is).
- No audio for 2 s (paused, permission off, silent source): the strip shows its colours steadily.

```bash
# standing bar (bottom up) and the TV backlight (both ends inward)
adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
  --es wled_devices '192.168.1.64=dominant:energy; 192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror' \
  --ei energy_sensitivity 100 --ei energy_blur 2 --ei energy_bg 10
```

Where the audio comes from (`audio_source`), exactly one, never switched by the app:
**output** (device audio: what this device plays, Android's `Visualizer`; the default on a TV),
**mic** (the microphone; the default on a phone, since phones rarely let apps hear other apps'
audio), **another device's microphone** over Wi-Fi (listed by name, e.g. *Pixel 8's microphone*;
`peer:<id>` over ADB), or **off**. If the chosen source hears nothing, the app says so instead of
switching. With HDMI passthrough, where Android never sees the TV's audio, pick the phone's
microphone on the TV (next section).

### Phone and device link

Install the same APK on an Android phone (download it on the phone from
[Releases](https://github.com/Krishna9827/Music-Light-Synk/releases/latest), allow the install,
open it, then *Grant media access*). Everything the TV app does, the phone app does too, with
a one-column layout. On the phone, keep the colour source on album art: Spotify, YouTube Music
and co. hand the artwork straight to the app. Screen sampling on a portrait phone mostly sees the
status bar and buttons.

**Every device is independent.** Each has its own WLED list and lights its own strips, so a phone
in the bedroom with the bedroom lights works without the Fire TV, and the TV never touches them.

**Device link** (on by default) only matters where devices meet. Apps on the same Wi-Fi find
each other (UDP port 21330, no server, no Home Assistant), and
- **a WLED listed on two devices** is driven by one of them at a time: a device playing music
  wins over a listening one, and between two players the one that started last wins. Strips the
  other device doesn't list are never affected;
- **a phone's microphone can be another device's audio source**: set the phone's **Mode** to
  *Lights + microphone* or *Microphone only*, then on the TV (*Beat & audio → Listen to*, or HA's
  *Audio source* select) pick
  *Pixel 8's microphone (Wi-Fi)*. The phone streams its beat to the TV only; the TV's Energy strips,
  beat pulse and Home Assistant beats follow it, both while the TV plays (passthrough) and while
  the phone hears music from anything else (then in the last album's colours);
- **devices in the same room** (*Room*, in the *Other devices* card; default "Home") share the
  album colours, e.g. for a phone's backup listener next to the TV.

**Mode** (top of the app, and a select in Home Assistant) says what a device does, in one choice:

| Mode | Its own lights (WLED, HA, MQTT) | Its microphone |
|---|---|---|
| **Lights + microphone** (phones) | follow the music it plays or hears | on, other devices can use it |
| **Microphone only** (phones) | left alone (given back when you switch to it) | on, other devices can use it |
| **Lights only** | follow the music it plays | off |
| **Off** | left alone | off |

So: the phone as a mic for the TV only = phone *Microphone only* + TV *Listen to: phone's
microphone*. Both rooms to the phone's mic = phone *Lights + microphone*. Only the phone's lights =
phone *Lights + microphone*, TV on its own audio. With the microphone on (a notification shows it)
and lights on, the phone, when it hears music for a few seconds, drives *its own* WLEDs to the beat: in the colours a device in the same room is playing, else the last album's
colours. Where it shares a WLED with a device that starts playing, it hands that strip over, and
it lets go after the stop delay of quiet. Android only lets an app use the mic in the background
if it was started while on screen, so after a phone reboot, open the app once.

### Music effect on Home Assistant lights

Any light in Home Assistant can move with the music, not just WLED. On the device page, **Music
effect**:

- **Beat pulse**: brightness jumps on every beat and falls back;
- **Energy**: brightness follows the music's level;
- **Energy bars**: the lights fill up one after another, like an Energy bar made of bulbs (in the
  order you pick them, e.g. bottom to top).

Pick the lights and strength under *Configure → Music effect on these lights* (empty = every light
picked under *Lights*). The app sends a tiny `beat` or `level` message to the webhook (only what the
effect needs); the integration moves the lights' brightness around the artwork's brightness and
puts it back when the music stops. Lights that are off stay off, and nothing piles up: a beat
during a pulse is skipped, only the newest level is shown. The music comes from the device's audio
source, so a TV using the phone's microphone moves the TV's lights. Fast local lights (Hue, Zigbee,
ESPHome, WLED through HA) keep up; Tuya and other cloud bulbs lag behind.

**WLED strips from Home Assistant.** WLEDs the app drives itself (Energy bars, flip / mirror,
ambient gradient) each get controls on the device page: **WLED <name>: part** (any edge or corner,
dominant / vibrant / average, *WLED's own effect in album colours*, *Ambient gradient*) and
**Energy**, **Flip**, **Mirror** switches. Add the WLEDs you already have in Home Assistant under
*Configure → WLED strips (driven by the app)*. Don't also map such a WLED under *Lights*.

### Home Assistant integration

Installed via HACS (step 4 above). Each TV is one device with:

| Entity | What |
|---|---|
| `switch.<tv>_light_sync` | Turn the light control on/off, e.g. from a "movie night" scene. Turning it off gives the lights back |
| `button.<tv>_test_lights` | Rainbow test pattern on your mapped lights for 6 s, then restores them |
| `sensor.<tv>_colour_source` | Where the colours come from right now: **Album art (media access)** or **Screen capture** |
| `binary_sensor.<tv>_media_access` / `_screen_capture` | Whether the app has media access / is capturing the screen |
| `sensor.<tv>_top_left_colour` … (11) | State = hex colour; attributes `hs`, `rgb`, `brightness`, `raw_hex`, … |
| `sensor.<tv>_now_playing` | Title or `idle`, with app/artist/album attributes |
| `binary_sensor.<tv>_playing` | On while music plays |
| `sensor.<tv>_audio` | What the app listens to: **Device audio**, **Microphone**, **Another device's microphone** (attribute `other_device`) or **Not listening** |
| `sensor.<tv>_wled_driven_by` | Which device drives the WLED strips (*This device*, another device's name, or *Nobody*); attribute `linked_devices` |

**Control the app from Home Assistant: over Wi-Fi, phones and TVs alike.** The app runs a small
control API (port 21331, secret token) and tells the integration where it is in its webhook posts,
so the controls below work as soon as the app has reported once, with no ADB. HA only accepts a
plain `http://` address on your LAN, keeps it across restarts, and the token never goes over MQTT.
On a Fire TV you can also pick its [Android Debug Bridge](https://www.home-assistant.io/integrations/androidtv/)
entity (setup wizard's last step, or *Configure → App control*): that's needed only for the
*Grant TV permissions* button, and serves as a fallback.

| Entity | What |
|---|---|
| `select.<tv>_colour_source_mode` | **Auto** (album art, screen if none) / **Album art (media access)** / **Screen capture** |
| `switch.<tv>_screen_capture` | Start / stop screen capture |
| `select.<tv>_mode` | **Lights + microphone** / **Microphone only** / **Lights only** / **Off** (a TV: Lights / Off) |
| `select.<tv>_audio_source` | What Energy strips, the pulse and the music effect listen to: **Device audio** / **Microphone** / another device's microphone by name / **Off** |
| `select.<tv>_music_effect` | Music effect on HA lights: **Off** / **Beat pulse** / **Energy** / **Energy bars** |
| `select.<tv>_wled_<strip>_part`, `switch.<tv>_wled_<strip>_energy` / `_flip` / `_mirror` | Per WLED strip the app drives |
| Configuration: switches | WLED output, WLED gradient beat pulse, restore WLED on stop, restart capture after reboot, MQTT output, device link |
| Configuration: numbers | Edge / corner size, saturation boost, minimum brightness, screen re-sample interval, stop delay, WLED brightness, pulse strength, Energy sensitivity / blur / background brightness |
| Configuration: text | **WLED devices** (see [WLED direct](#wled-direct-skips-home-assistant)), **Per-app profiles** |
| Configuration: buttons | **Refresh TV app status**, **Send test colours from TV**, **Grant TV permissions** (media access + screen capture without a prompt; Fire TV with ADB only) |

The values always come from the app itself: it reports its state to HA whenever something
changes. So changes made on the TV, over ADB or from HA all show up in the same place.

Plus a `music_light_synk_event` on the event bus for every payload, handy as an automation trigger.

**Configure** (on the integration card) has five sections: **Lights** per zone, **Brightness and
fades** (follow the artwork or fixed, max brightness, fade time), **Effects** (solid / run an
effect / leave alone, plus WLED palettes), **When the music stops** (restore / off / nothing) and
**App control** (the optional Android Debug Bridge entity).
Changes apply from the next track. No restart is needed.

A TV and a phone (or two TVs)? Add the integration once per device with its own webhook ID (e.g.
`music_light_synk` for the TV, `music_light_synk_bedroom` for the phone), and set that ID in each
device's app under *Home Assistant*.

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
| Mode | `mode` | `lights` (phone with mic on: `lights_mic`) | `lights_mic` / `mic` / `lights` / `off`. Older `enabled` and `listener` still work |
| Restart capture after reboot | `boot_capture` | on | Only relevant for `auto`/`screen` |
| Per-app profiles | `profiles` | none | `pkg=artwork\|screen\|auto\|off;pkg=...` |
| MQTT on/off | `mqtt_enabled` | off | |
| MQTT broker | `mqtt_host`, `mqtt_port`, `mqtt_user`, `mqtt_pass`, `mqtt_tls` | –, 1883 | |
| MQTT topic prefix | `mqtt_topic` | `music_light_synk` | One per TV |
| MQTT discovery | `mqtt_discovery` | on | Creates HA entities automatically |
| WLED output on/off | `wled_enabled` | on | Master switch for direct WLED |
| WLED devices | `wled_devices` | none | `host=zone[:options]; ...`, see [WLED direct](#wled-direct-skips-home-assistant) |
| WLED brightness | `wled_brightness_pct` | 100 | Scales everything sent to WLED |
| Restore WLED on stop | `wled_restore` | on | JSON (section / palette) devices |
| Beat pulse | `pulse`, `pulse_strength` | off, 60 % | Ambient strips, needs audio permission |
| Audio source | `audio_source` | TV: `output`, phone: `mic` | `output` / `mic` / `off` / `peer:<id>` (another device's mic; HA and the app list them by name). Never switched automatically |
| Music data to Home Assistant | `ha_music` | `off` | `beat` / `level`; set by HA's *Music effect* select (v0.8's `ha_beats` = `beat`) |
| Energy sensitivity | `energy_sensitivity` | 100 % | 10–300. Higher = bars reach further |
| Energy blur | `energy_blur` | 2 | 0–10 smoothing passes across neighbouring LEDs |
| Energy background | `energy_bg` | 10 % | Brightness of the strip's colour where no bar is |
| Device link | `link_enabled` | on | Take turns on shared WLEDs; share beat / colours within a room |
| Room | `room` | `Home` | Only devices in the same room share album colours |
| Device name | `device_name` | model | Shown on other devices |

### ADB cheat sheet

```bash
adb connect 192.168.1.50
R="-n com.musiclightsynk/.AdbCommandReceiver"

# configure (any subset of the extras above)
adb shell am broadcast $R -a com.musiclightsynk.CONFIGURE --es ha_url http://192.168.1.10:8123 --es source artwork --ei corner_pct 30
adb shell am broadcast $R -a com.musiclightsynk.TEST      # rainbow test colours around the "screen"
adb shell am broadcast $R -a com.musiclightsynk.STATUS    # access granted? last result? now playing?
adb shell am start -n com.musiclightsynk/.CaptureConsentActivity   # (re)start screen capture
adb shell am broadcast $R -a com.musiclightsynk.STOP_CAPTURE         # stop screen capture

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
  "event": "colors",            // "colors" | "stopped" | "test" | "status" (app state only, no colours)
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

Every payload also carries a `status` object with the app's state: `media_access`,
`screen_capture`, `source_mode`, `last_source`, `app_version` and `settings` (no secrets). The
app also sends it alone as `"event": "status"` whenever it changes; see
[`docs/payload-status.json`](docs/payload-status.json). Blueprints and automations that check
`event` simply ignore it.

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
| "Device audio is silent" | The device or app blocks audio analysis, or HDMI passthrough is on. Use PCM/stereo output to test, or let a phone's backup listener send the beat |
| Energy strip shows still colours | No audio for 2 s: check *Beat & audio* says *Hearing: ...*, and that the app has audio permission |
| Devices don't find each other | Same Wi-Fi? Routers with "AP / client isolation" or a guest network block it. UDP port 21330 must be allowed between them |
| Two devices fight over a strip | Update both to v0.7 or later (they take turns per WLED); or remove that WLED from one device's list |
| Lights lag behind the music | Update to v0.7: WLED now gets the colours first, before Home Assistant / MQTT. If HA is unreachable from the device, *Last:* shows it |
| Home Assistant controls are *unavailable* for a phone | The app hasn't reported to HA yet: set the HA URL and that device's webhook ID in the app, and keep *Home Assistant may change settings over Wi-Fi* on |
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
  fading or pulsing, ~40 fps for Energy bars, 1 Hz keep-alive otherwise), the JSON API with
  save/restore, and the beat detector (bass energy vs. a moving average).
- `AudioAnalysis.kt` / `EnergyEffect.kt`: our own FFT → lows / mids / highs, an auto-gain meter
  (quiet and loud sources both use the full range; silence stays dark), and the bar renderer with
  LedFx's flip / mirror. Pure Kotlin, unit tested.
- `AudioEngine`: Android `Visualizer` waveform (device audio) or `AudioRecord` (mic) → meter.
- `Link.kt`: the device link (JSON over UDP, broadcast discovery, unicast after that) and the
  per-WLED election of which device drives it. `ListenerService` is the phone's mic foreground service.
- `ControlServer` / `AppSettings`: the Wi-Fi control API for Home Assistant and the one settings
  table that it and the ADB receiver share.
- `SyncEngine` drives WLED on its own thread the moment colours are known; Home Assistant and MQTT
  go out on a separate network thread, so a slow server never delays the lights.
- Everything is plain Android framework code (no AndroidX, no libraries), minSdk 25 (Fire OS 6+),
  targetSdk 34. MQTT, WLED, colour extraction and payloads are framework-free Kotlin, so they
  are unit tested on the JVM.

## Testing

- `./gradlew testReleaseUnitTest`: 77 JVM tests. Colour extraction, payload schema, per-app
  profiles, MQTT packets against a fake broker (connect, auth refusal, retained topics,
  discovery), WLED layout orientation, DNRGB packets, the UDP renderer (fade in, stop, silence
  after stop), the JSON save → set → restore flow against a fake WLED, and beat detection.
  For Energy: FFT bins and band split, the meter (kicks push the bars up, they fall back, a quiet
  mic still uses the full range, silence goes dark), flip / mirror in all four combinations,
  the device-list options, and an Energy strip over real UDP with its LED count from a fake
  WLED's `/json/info`. For the link: every message round-trips, strangers and junk are ignored,
  the per-WLED election (a phone in another room never gives way, a player beats a listener on a
  shared strip, newest player wins, ties, expiry, older apps), rooms, and two link sockets finding
  each other and talking. For Wi-Fi control: ping, token checks, configure, unknown actions,
  value coercion.
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
- The music effect was run in Home Assistant 2026.2 with demo lights: a beat pulsed exactly the
  picked lights (up, then back) and left an unpicked light and a switched-off one alone; ten beats
  in half a second didn't pile up; Energy bars on three lights showed level 0.5 as full / half / off,
  1.0 as all full, and returned to their brightness 3 s after the levels stopped. The *Audio source*
  select showed another device's microphone by name and sent `peer:<id>` when picked.
- Mode and the WLED strip controls were run against a stand-in for the app: per-strip *part* and
  Energy / Flip / Mirror entities appeared from the app's report, each change rewrote just that
  strip's entry (an ambient strip turned dominant kept Energy / Flip / Mirror), a strip removed in
  the app lost its controls, and the replaced *TV syncing* / *Beat sync* switches were removed.
- Wi-Fi control from HA was run in Home Assistant 2026.2 against a stand-in for the app's API: an
  entry with no ADB became controllable from the app's first status post, a public control address
  was refused, every control reached the API with the token, *Grant TV permissions* stayed
  ADB-only, and after an HA restart the saved address was used to ask for the status.
- TV app control from HA was tested with a stand-in for the Android Debug Bridge integration
  that runs the exact shell commands through POSIX shell parsing and answers like the app. Each
  control sent the right command; values with `;`, `=` and spaces arrived intact; and the
  app's status reply updated every entity. Without an ADB entity the controls are unavailable,
  not broken.
- Not covered by these tests: anything that needs the physical TV, a phone or a real WLED. That
  includes whether the Fire TV `Visualizer` returns real audio, how the bars feel with real music,
  the phone mic in the background, the link across a real router, and the UDP/JSON packets on
  actual WLED firmware (they follow the documented protocols).

## Ideas for later

- Hue Entertainment API output (fast, like WLED UDP, for Hue lights).
- Per-app WLED mode (e.g. ambient for music, off for movies).
- Submit to the HACS default list (needs a brand icon in home-assistant/brands, repo topics and a
  description).

## License

[MIT](LICENSE)
