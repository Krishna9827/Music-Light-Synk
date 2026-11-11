# Changelog

One version number for the whole project: the TV app (APK) and the Home Assistant
integration are released together. Every release on GitHub has the APK, the integration zip
and both blueprints attached.

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
