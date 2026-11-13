# Changelog

One version number for the whole project: the TV app (APK) and the Home Assistant
integration are released together. Every release on GitHub has the APK, the integration zip
and both blueprints attached.

## v0.8.0

- **Use another device's microphone**: a phone with its Backup listener on offers its microphone
  on the Wi-Fi, and the TV (or any device) can pick it by name as its audio source, in the app
  (*Beat & audio → Listen to*) or in Home Assistant (*Audio source*). For TVs with HDMI passthrough,
  where Android never hears the audio. The TV's Energy strips and beat pulse follow the phone's mic,
  also when the music comes from something else (then in the last album's colours). This replaces
  v0.7's automatic same-room beat sharing; rooms now only share album colours.
- **Beat sync for Home Assistant lights**: with *Beat to Home Assistant lights* on, every beat goes
  to the webhook and the integration pulses the lights picked under *Configure → Beat sync*
  (brightness up, back to the artwork's brightness). Lights that are off stay off; beats never pile
  up. New *Beat sync (Home Assistant lights)* switch; picking beat lights switches it on.
- Home Assistant's *Audio source* select lists the choices the app offers, including other
  devices' microphones by name; the *Audio* sensor shows when another device's mic is used.

## v0.7.0

**Fixes from testing v0.6**
- **No more lag**: the WLEDs get the colours the moment they're known. Home Assistant and MQTT now
  send on their own thread, so a slow or unreachable server can't delay the lights any more (it
  could add 30+ seconds before). WLEDs on the JSON API are updated in parallel.
- **Audio source stays where you put it**: Device audio, Microphone or Off. "Auto" is gone, and
  nothing switches it by itself any more. Defaults: device audio on a TV, microphone on a phone.
  The backup listener uses the microphone.
- **Screen sampling** looks again 1.2 s and 3.5 s after a track change (apps animate the new
  cover in, so the first look often still showed the old one).
- Flip and Mirror can be set before Energy is switched on.

**Phones are independent**
- Every device keeps **its own WLED list** (v0.6 copied one list to every device).
- Devices only take turns on a **WLED they both list**; a phone in another room with other lights
  never gives way to the TV. The beat and album colours are shared only within the same **Room**
  (new setting).
- **Home Assistant controls the app over Wi-Fi**, no ADB or Fire TV needed: the app runs a small
  control API (port 21331, secret token) and tells the integration where it is. Add the phone as
  its own device in HA (its own webhook ID) and every control works. ADB is now optional
  (needed only for *Grant TV permissions*).

## v0.6.0

**App (Fire TV and Android phones)**
- **Energy effect** for WLED strips, like LedFx's: bars for lows / mids / highs bounce with the
  music, in album colours, at ~40 fps. Per device, with LedFx's **Flip** and **Mirror**
  switches (standing bar from the bottom; TV backlight from both ends). Settings for
  sensitivity, blur and background brightness. No LedFx needed.
- **Audio source**: device audio (what the TV plays), the microphone, or auto (device audio,
  else mic). Our own FFT on the audio, with auto-gain so quiet and loud sources both work.
- **Phone app**: the same APK runs on Android phones, with a one-column layout.
- **Device link**: apps on the same Wi-Fi find each other, share the WLED device list (edit it
  anywhere), share the beat, and take turns driving the strips: a device playing music wins over
  a listening one, the most recent player wins between two.
- **Backup listener** (phones): the phone's mic hears music played elsewhere (iPhone, PC) and
  keeps the strips moving to the beat in the last album's colours; it hands over as soon as a
  device plays music itself. It can also send the beat to a TV whose audio is silent (HDMI
  passthrough).
- The beat pulse now uses the same audio engine (and works from the mic too).

**Home Assistant integration**
- New: **Audio source** select, **Energy sensitivity / blur / background brightness** numbers,
  **Device link** switch, **Audio** sensor (device audio / microphone / not listening) and
  **WLED driven by** sensor (with the linked devices).

## v0.5.0

**TV app**
- New look: card layout, clear focus highlight for the remote, status chips, and a 3×3
  colour preview laid out like the TV.
- **Several WLED devices, each showing its own part of the artwork**: any corner or side,
  dominant / vibrant / average, effect colours (WLED's effect keeps running in album colours),
  or an ambient gradient around the TV. Add, change and remove them from the TV.
- The app reports its state (media access, screen capture, colour source mode and all
  settings) to Home Assistant and MQTT.
- Fix: the ambient gradient now crossfades from the previous track instead of fading up from black.

**Home Assistant integration**
- Sensors: **Colour source** (album art via media access, or screen capture), **Media access**,
  **Screen capture**.
- Controls through the Android Debug Bridge integration: **Colour source mode**
  (auto / album art / screen capture), **Screen capture** on/off, TV syncing, beat pulse,
  WLED output, all number settings, WLED devices, per-app profiles, and buttons to grant
  permissions, refresh the app status and send test colours from the TV.
- Setup wizard has an optional third step to pick the Fire TV.

**Releases**
- GitHub Releases are created automatically when a new version lands on `main`.

## v0.4.0

- Home Assistant integration controls the lights itself (no blueprint needed): setup
  wizard, sectioned options, Light sync switch, Test lights button. Installable with HACS.

## v0.3.0

- MQTT output with Home Assistant discovery, direct WLED output (colours / ambient gradient),
  beat pulse, per-app profiles, first version of the Home Assistant integration.

## v0.2.0

- 4 side colours in addition to the 4 corners; WLED effect handling in the blueprint.

## v0.1.0

- First version: album art → 4 corner colours → Home Assistant webhook + blueprint.
