# Mac and iPhone apps

A first, smaller version of the app for your Mac and iPhone. They light your WLEDs and Home
Assistant lights just like the TV and Android apps, and they use the same Home Assistant
integration.

| | Mac | iPhone |
|---|---|---|
| Album-art colours | Spotify, Apple Music, YouTube Music in Safari / Chrome / Edge / Brave | Apple Music only (iOS doesn't let apps see what Spotify or YouTube Music play) |
| Lights to the beat (microphone) | yes | yes, also with the screen locked |
| WLED: parts, ambient gradient, Energy / Flip / Mirror | yes | yes |
| Home Assistant lights: colours, Beat pulse, Energy, Energy bars | yes | yes |
| Controlled from Home Assistant (Mode, WLED strips, ...) | yes, over Wi-Fi | yes, while the app runs |
| Lend its microphone to the TV | yes (device link) | not yet |

Not yet: the Mac's own sound output as audio source (it listens through the microphone), and
the device link on iPhone (Apple only allows the broadcasts it needs to apps it has approved).

---

## The Mac app

### 1. Install

1. Download **music-light-synk-mac.zip** from
   [Releases](https://github.com/Krishna9827/Music-Light-Synk/releases/latest), double-click it,
   and drag **Music Light Synk** into **Applications**.
2. Open it. macOS blocks apps from outside the App Store the first time: open **System Settings →
   Privacy & Security**, scroll down to *"Music Light Synk" was blocked*, press **Open Anyway**,
   and confirm. (Or in Terminal: `xattr -dr com.apple.quarantine "/Applications/Music Light Synk.app"`.)
3. A small **waveform icon** appears in the menu bar (top right). That's the app. There's no
   window in the Dock.

### 2. Set it up

Click the menu-bar icon → **Settings…**

1. **Home Assistant**: your HA URL (`http://192.168.1.10:8123`) and a webhook ID of its own, e.g.
   `music_light_synk_mac`. In Home Assistant, **Add integration → Music Light Synk** with that same
   webhook ID, pick the lights near the Mac, and leave the Fire TV step empty. The Mac's device page
   then gets the same controls as the TV (over Wi-Fi).
2. **WLED devices**: one per line, the same format as on the TV, e.g.
   `192.168.1.64=dominant:energy` or `192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror`.
   Or add them from Home Assistant (*Configure → WLED strips*) once step 1 is done.
3. **Save**.

### 3. Allow it to listen and to ask your music apps

macOS asks once for each:

- **Microphone**: say **Allow** (Mode *Lights + microphone* or *Microphone only*). Turned it down?
  *System Settings → Privacy & Security → Microphone → Music Light Synk*.
- **"Music Light Synk wants to control Spotify / Music / Safari"**: say **OK**. That's how it reads
  what's playing. Turned it down? *System Settings → Privacy & Security → Automation*.
- **Local network**: say **Allow** (to reach WLED and Home Assistant).

### 4. YouTube Music in the browser

The app reads YouTube Music from the browser tab, which needs one switch in the browser:

- **Safari**: *Settings → Advanced → Show features for web developers*, then menu **Develop → Allow
  JavaScript from Apple Events**.
- **Chrome / Edge / Brave**: menu **View → Developer → Allow JavaScript from Apple Events**.

Keep YouTube Music in a normal tab at `music.youtube.com`.

### 5. Use it

The menu-bar panel shows what's playing, the colours, **Mode**, the microphone level and the last
result.

- **Lights + microphone**: the Mac's lights follow the album art and bounce to the beat. When
  something else plays (your iPhone on the 2.1 speakers), the mic still drives them, in the last
  album's colours.
- **Microphone only**: the Mac just lends its mic. On the TV (or in Home Assistant, the TV's **Audio
  source**) pick *"(Mac name)'s microphone"*, and the TV's lights follow the Mac's mic. Same as with
  the Android phone.
- **Lights only**: album-art colours, no microphone.

To start it when you log in: *System Settings → General → Login Items → +* → Music Light Synk.

---

## The iPhone app

Apple only installs apps from the App Store, or ones you build yourself with Xcode on your Mac.
This is the second kind: about 10 minutes, free with your normal Apple ID.

### 1. Build and install

1. Install **Xcode** from the Mac App Store (free, large download).
2. Download **music-light-synk-iphone-project.zip** from
   [Releases](https://github.com/Krishna9827/Music-Light-Synk/releases/latest) and unzip it.
3. Open **ios/MusicLightSynk.xcodeproj** (inside the unzipped *Music Light Synk iPhone* folder).
4. Xcode → **Settings → Accounts → +** → sign in with your Apple ID (once).
5. In the project, select the **MusicLightSynkiOS** target → **Signing & Capabilities** → **Team**:
   pick *(your name) (Personal Team)*. If Xcode says the bundle identifier is taken, change
   `com.musiclightsynk.ios` to something of your own, e.g. `com.yourname.musiclightsynk`.
6. Connect the iPhone with a cable, unlock it, and pick it at the top of the Xcode window.
7. Press **Run** (▶). The first time:
   - iPhone: **Settings → Privacy & Security → Developer Mode → On** (it restarts).
   - iPhone: **Settings → General → VPN & Device Management** → your Apple ID → **Trust**.

With a free Apple ID the app runs for **7 days**; then connect the iPhone and press **Run** again
(your settings stay). A paid Apple Developer account makes it last a year.

### 2. Set it up

In the app:

1. **Home Assistant**: URL and a webhook ID of its own, e.g. `music_light_synk_iphone`, then add the
   integration once more in Home Assistant with that ID.
2. **WLED devices** (one per line), **Save**.
3. **Mode**: *Lights + microphone*. Allow the microphone, Apple Music and local network when asked.

The microphone keeps working with the screen locked (the red/orange indicator shows it's on).
Apple Music songs bring their album colours; with Spotify or YouTube Music the lights move to the
beat in the last colours.

---

## If something's off

| You see | Fix |
|---|---|
| Mac: "Allow JavaScript from Apple Events" note | Step 4 above, in the browser that plays YouTube Music |
| Mac: nothing playing, but Spotify plays | *System Settings → Privacy & Security → Automation → Music Light Synk → Spotify* on |
| Mac: "Microphone not allowed" | *System Settings → Privacy & Security → Microphone → Music Light Synk* on |
| Mac: the TV doesn't list the Mac's microphone | Mode includes the microphone; Mac and TV on the same Wi-Fi with **Device link** on |
| Mac: asks for Microphone / Automation again after an update | Normal for an app outside the App Store: allow again (or switch the old entry off and on in Privacy & Security) |
| iPhone app stopped after a week | Connect it and press Run in Xcode again (free Apple ID limit) |
| Home Assistant controls unavailable for the Mac / iPhone | HA URL and webhook ID set in the app; the iPhone app has to be running |
