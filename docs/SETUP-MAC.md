# Setup from a Mac, step by step

About 20 minutes the first time. You need the Mac only for installing the app on the Fire TV.
After that the app runs by itself (also after reboots), and **everything else can be done from
Home Assistant**.

You'll need two IP addresses. Replace these examples everywhere below:

- Fire TV: `192.168.1.50` (step 2 shows where to find it)
- Home Assistant: `192.168.1.10` (the address you open HA with, or HA → Settings → System → Network)

> Use the HA **IP address**, not `homeassistant.local`. Fire TV usually can't resolve `.local` names.

**Already set up an older version?** Jump to [Updating](#updating-already-set-up).

---

## Part A: the Fire TV (from the Mac)

### 1. Install adb on the Mac

Open **Terminal** (Cmd+Space, type "Terminal").

If you have [Homebrew](https://brew.sh):

```bash
brew install --cask android-platform-tools
adb version
```

No Homebrew? Install it first by pasting the one-liner from https://brew.sh, then run the two
commands above. If `adb version` prints a version, you're good.

### 2. Turn on ADB on the Fire TV Stick

With the Fire TV remote:

1. Home screen → **Settings** (gear icon, far right) → **My Fire TV** → **About**.
2. Highlight **Fire TV Stick 4K Max** and press the **centre/select button 7 times** until it says
   *"You are now a developer"*.
3. Press Back → **Developer Options** → **ADB Debugging** → **ON**.
4. Go to **About** → **Network** and write down the **IP Address** (e.g. `192.168.1.50`).

The Mac and the Fire TV must be on the same Wi-Fi. Leave ADB Debugging **on**: Home Assistant
uses it to control the app (Part B).

### 3. Connect the Mac to the Fire TV

```bash
adb connect 192.168.1.50:5555
```

A popup appears on the TV, *"Allow USB debugging?"*. Tick **Always allow from this computer** and
press **OK**. Then check:

```bash
adb devices
```

You should see `192.168.1.50:5555   device`.

| You see | Fix |
|---|---|
| `unauthorized` | Accept the popup on the TV, then `adb disconnect` and run `adb connect ...` again |
| `failed to connect` / timeout | Wrong IP, ADB Debugging is off, Mac on a VPN, or router "AP/client isolation" is on |

### 4. Download the APK

Open https://github.com/Krishna9827/Music-Light-Synk/releases/latest and click
**music-light-synk.apk** under *Assets*. It lands in your Downloads folder.

### 5. Install the app and grant permissions

Copy-paste these one at a time.

**Install:**
```bash
adb install -r ~/Downloads/music-light-synk.apk
```
You should see `Success`. If the file is in a sub-folder, type `adb install -r ` (with a space), then
**drag the .apk from Finder into the Terminal window** to paste its path, and press Enter.

**Let it read what's playing** (the important one; Fire TV hides this switch in its menus):
```bash
adb shell cmd notification allow_listener com.musiclightsynk/com.musiclightsynk.MediaListenerService
```

**Allow screen capture without a popup** (only used when an app has no album art):
```bash
adb shell appops set com.musiclightsynk PROJECT_MEDIA allow
adb shell appops set com.musiclightsynk SYSTEM_ALERT_WINDOW allow
```

(Later, the **Grant TV permissions** button in Home Assistant does these three for you.)

### 6. Tell the app where Home Assistant is, and open it once

```bash
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE --es ha_url http://192.168.1.10:8123 --es webhook_id music_light_synk
adb shell am start -n com.musiclightsynk/.MainActivity
```

The first command replies `data="Saved: ha_url=http://192.168.1.10:8123, webhook_id=music_light_synk"`.
The TV then shows the Music Light Synk screen. At the top, *Status & permissions* should show
**● Media access: listening to media apps** with a green dot.

That's all the Mac is needed for.

---

## Part B: Home Assistant (all in the browser)

### 7. Add the Fire TV to Home Assistant (Android Debug Bridge)

Optional since v0.7: Home Assistant controls the app over Wi-Fi by itself. ADB adds the
**Grant TV permissions** button and a fallback. Skip it if you like; you can add it later.

1. **Settings → Devices & services → Add integration → Android Debug Bridge**.
2. Host: `192.168.1.50`, device type **Fire TV**, port `5555`. Leave the ADB key path and the
   ADB server fields empty (HA creates its own key). **Submit**.
3. The TV shows *"Allow USB debugging?"* again, this time for Home Assistant. Tick **Always allow**
   and press **OK**.
4. You now have a media player entity for the Fire TV (something like `media_player.fire_tv`).

> If that entity keeps showing *unavailable*, run `adb disconnect` on the Mac. Some Fire TV
> versions only like one ADB connection at a time.

### 8. Install Music Light Synk (HACS) and set it up

1. **HACS → ⋮ (top right) → Custom repositories**. Repository:
   `https://github.com/Krishna9827/Music-Light-Synk`, type **Integration** → **Add**.
   (No HACS yet? Install it first: https://hacs.xyz/docs/use/download/download/)
2. Search HACS for **Music Light Synk** → **Download** → **restart Home Assistant**
   (*Settings → System → ⋮ → Restart*).
3. **Settings → Devices & services → Add integration → Music Light Synk**. A 3-step wizard:
   1. **Name** (e.g. *Living room TV*) and webhook ID **`music_light_synk`**. That's what the app
      sends to, so nothing changes on the TV.
   2. **Lights**: pick lights for each part of the artwork (corners, edges, dominant, vibrant).
      Leave the rest empty. With only 1–2 lights, use **Dominant colour**.
   3. **Fire TV**: pick the entity from step 7 (or leave it empty if you skipped step 7).
4. Optional: on the integration card, **Configure** → *Brightness and fades* (Tuya: set **Fade
   time** to `0`), *Effects* (WLED), *When the music stops*, *TV app control*.

> **Already using the blueprint?** Delete that automation first (*Settings → Automations →
> Music Light Synk → ⋮ → Delete*). It holds the `music_light_synk` webhook, and HA allows only one
> listener per webhook ID. The integration does the same job.

<details><summary>Rather use the blueprint instead of the integration?</summary>

**Settings → Automations & scenes → Blueprints → Import blueprint** and paste
`https://github.com/Krishna9827/Music-Light-Synk/blob/main/home-assistant/blueprints/automation/music_light_synk.yaml`.
Then **Create automation**, keep webhook ID `music_light_synk`, pick lights, **Save**.
(The blueprint only drives lights. The TV controls below need the integration.)
</details>

### 9. Test

Open **Settings → Devices & services → Music Light Synk → your TV**:

1. Press **Test lights**. Your lights show a rainbow for 6 seconds and then go back: top-left red,
   top orange, top-right yellow, right green, bottom-right cyan, bottom blue, bottom-left indigo,
   left pink, dominant purple.
2. Press **Send test colours from TV** (under *Configuration*).
   Same rainbow, but this time it comes from the TV app, so the whole path works.
3. Play a song in Spotify / Amazon Music / Apple Music on the Fire TV. The lights change within a
   second or two of each new track, and **Colour source** shows *Album art (media access)*.

---

## What you can do from Home Assistant now

Everything is on the TV's device page. The controls work over Wi-Fi as soon as the app has
sent its first update (v0.7 and later); step 7 (ADB) is only needed for *Grant TV permissions*.

| What | Where |
|---|---|
| Where the colours come from | **Colour source** sensor: *Album art (media access)* or *Screen capture* |
| Is media access granted / screen capture running? | **Media access**, **Screen capture** binary sensors |
| Switch album art ↔ screen capture | **Colour source mode**: Auto / Album art / Screen capture |
| Start / stop screen capture | **Screen capture** switch |
| What the device does | **Mode**: Lights + microphone / Microphone only / Lights only / Off (a TV: Lights / Off) |
| Pause only HA's lights | **Light sync** switch |
| Each WLED strip the app drives | **WLED (name): part** (edge, corner, dominant, ambient gradient, ...) and **Energy / Flip / Mirror** switches |
| Beat pulse for WLED ambient strips | **Beat pulse** switch, **Pulse strength** number |
| WLED devices and what each one shows | **WLED devices** text (format below), **WLED output**, **WLED brightness** |
| Energy (bars to the beat) | `:energy`, `:flip`, `:mirror` in **WLED devices**; **Energy sensitivity**, **Energy blur**, **Energy background brightness** numbers |
| What the app listens to | **Audio** sensor; change it with **Audio source**: Device audio / Microphone / another device's microphone (by name) / Off |
| HA lights moving with the music | **Music effect**: Beat pulse / Energy / Energy bars; lights under *Configure → Music effect on these lights* (step 13) |
| Phone ↔ TV link | **WLED driven by** sensor (attribute `linked_devices`), **Device link** switch |
| Per-app profiles | **Per-app profiles** text, e.g. `com.google.android.youtube.tv=screen;com.netflix.ninja=off` |
| Edge size, saturation, minimum brightness, re-sample interval, stop delay | Numbers under *Configuration* |
| Fix permissions | **Grant TV permissions** button (needs step 7) |
| See the latest values | **Refresh TV app status** button |

The values come from the app itself, so changes made on the TV, over ADB or from Home Assistant
all show up in the same place.

**WLED devices** format: `IP=zone`, separated by `;`. For example, a strip around the TV, a lamp
on each side, and a shelf running its own effect in album colours:

```
192.168.1.60=ambient:40,22,40,22:bottom_left:cw; 192.168.1.61=left; 192.168.1.62=right; 192.168.1.63=palette
```

Zones: any corner or side (`top_left`, `top`, `right`, …), `dominant`, `vibrant`, `average`,
`palette` (WLED's own effect in album colours), or `ambient` (gradient strip: LED counts
top,right,bottom,left, then where LED 0 is, then `cw`/`ccw`). Add `:keep` to a corner/side zone
to keep the WLED's current effect. Add `:energy` to make a corner/side or ambient strip bounce to
the music, plus `:flip` and/or `:mirror` for the direction (Part C). On the TV the same list is
under **WLED devices** (Add → press the zone button to cycle). If a WLED is also one of your
Home Assistant light zones, remove it there so the two don't fight.

---

## Part C (optional): strips that bounce to the beat, and the phone

### 10. Energy effect on a WLED strip

On the TV app, under **WLED devices**, each device has **Energy / Flip / Mirror** buttons:

1. Press **Energy: ON**. The app asks for audio access once: accept. (Or from the Mac:
   `adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO`.)
2. Pick the direction. These are the same switches as in LedFx, so use what you had there:

   | Flip | Mirror | Bars grow | Use for |
   |---|---|---|---|
   | off | off | from LED 0 | standing bar with LED 0 at the bottom |
   | on | off | from the last LED | standing bar with LED 0 at the top |
   | off | on | from the middle outward | |
   | on | on | from both ends inward | TV backlight ("bounces from both sides") |

3. Play music. *Beat & audio* shows **Hearing: device audio**. Tune **Sensitivity** (how far the
   bars reach: start at 100 %, lower it if the bars hit the end all the time), **Blur** and
   **Background brightness**.

A corner/side zone (e.g. `dominant`) bounces in three album colours (lows / mids / highs). An
ambient strip bounces in its own gradient. The same from Home Assistant, in **WLED devices**:

```
192.168.1.64=dominant:energy; 192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror
```

> **Soundbar with Dolby / DTS (HDMI passthrough)?** Android never sees that audio, so the TV
> hears silence. Use your phone's microphone instead: step 12.

### 11. The phone (Android), e.g. for another room

The phone app works completely on its own: its own WLED lights, its own Home Assistant device,
no Fire TV needed.

1. On the phone, open https://github.com/Krishna9827/Music-Light-Synk/releases/latest and tap
   **music-light-synk.apk**. Allow the browser to install apps when asked, then **Install**.
2. Open **Music Light Synk** → *Status & permissions* → **Grant media access** → turn on
   *Music Light Synk* in the list (this lets it see what the phone plays and its album art).
3. **WLED devices**: add the WLEDs of that room (IP address → **Add**), pick each one's zone and
   **Energy / Flip / Mirror**. This list belongs to the phone only.
4. **Other devices** → **Room**: type the room, e.g. `Bedroom`, and press **Save room and name**.
   Devices in other rooms then never share album colours with it. (If the phone also lists a WLED
   the TV uses, they take turns on that one WLED only.)
5. **Beat & audio** → **Listen to: Microphone** (the default on a phone; phones rarely let apps hear
   other apps' audio). It stays on the source you pick.
6. **Home Assistant** card: your HA URL, and a webhook ID of its own, e.g.
   `music_light_synk_bedroom`. **Save**. Then in Home Assistant add the integration once more
   (**Add integration → Music Light Synk**) with that same webhook ID, pick the bedroom's lights,
   and leave the Fire TV step empty. As soon as the phone sends its first update, every control on
   its device page works over Wi-Fi.
7. **Mode** (top of the app): **Lights + microphone** (allow the microphone and notifications): the
   phone's WLEDs bounce to music played nearby on anything (iPhone, Mac, PC on the speakers) in the
   last album's colours, and the TV can use its mic. **Microphone only** if the phone should just
   lend its mic and leave its own lights alone. After a phone restart, open the app once so Android
   lets it use the mic again.

### 12. The phone's microphone for the TV (HDMI passthrough)

1. On the phone: **Mode → Microphone only** (just the TV's lights) or **Lights + microphone** (the
   phone's lights too). Leave the phone in the TV room.
2. On the TV: **Beat & audio** → press **Listen to** until it says **(phone name)'s microphone
   (Wi-Fi)**. Or in Home Assistant, on the TV's device page: **Audio source** → the phone's microphone.
3. Play music on the TV. *Beat & audio* shows **Hearing: (phone)'s microphone over Wi-Fi**, and the
   TV's Energy strips and beat pulse follow the phone's mic. It also works when the music comes from
   something else (iPhone, PC): the TV's strips then bounce in the last album's colours.

The name is the phone's *This device's name* (*Other devices* card).

### 13. Home Assistant lights with the music

1. In Home Assistant: **Music Light Synk → Configure → Music effect on these lights**: pick the
   lights (empty = all your Music Light Synk lights; for Energy bars in order, bottom to top) and
   the strength. **Submit**.
2. On the device page: **Music effect** → **Beat pulse** (jump on every beat), **Energy**
   (brightness follows the music) or **Energy bars** (the lights fill up one after another).
3. Play music. The music comes from that device's audio source, e.g. the phone's mic for the TV.

Fast local lights (Hue, Zigbee, ESPHome, WLED through HA) keep up; Tuya and other cloud bulbs lag
behind, so leave those out.

### 14. WLED strips from Home Assistant

**Configure → WLED strips (driven by the app)**: pick the WLEDs you already have in Home Assistant
and **Submit**: they're added to the app. Each gets controls on the device page: **WLED (name):
part** (which part of the artwork, or *Ambient gradient* / *WLED's own effect in album colours*)
and **Energy**, **Flip**, **Mirror**. Don't also pick such a WLED under *Lights*.

---

## If something's off

Look at the TV's device page first:

| You see | Meaning / fix |
|---|---|
| **Media access** is off | Press **Grant TV permissions** (or run the `allow_listener` command from step 5) |
| **Colour source** says *Nothing yet* while music plays | The app doesn't see the song. Check *Media access*, then press **Refresh TV app status** |
| Lights don't change, but *Now playing* updates | Check **Light sync** is on and lights are picked under *Configure → Lights* |
| *Now playing* never updates | The TV can't reach HA: wrong HA IP/port in step 6, or the webhook IDs don't match |
| Controls are *unavailable* | The app hasn't reported to HA yet: check the HA URL and webhook ID in the app, and that *Home Assistant may change settings over Wi-Fi* is ON. (*Grant TV permissions* needs step 7.) |
| An app shows *did not provide album art* | That app doesn't share artwork. Set **Colour source mode** to *Auto* and turn on **Screen capture** |
| Energy strip shows still colours | **Audio** says *Not listening* or the TV hears silence: grant audio access (step 10), or HDMI passthrough is on (use the phone's microphone, step 12) |
| The phone's microphone isn't in the TV's list | The phone's **Mode** must include the microphone, on the same Wi-Fi, with **Device link** on on both |
| The phone's own lights react while it's only a mic for the TV | Set the phone's **Mode** to **Microphone only** (its lights are given back right away) |
| HA lights don't move with the music | **Music effect** isn't Off, lights picked under *Configure → Music effect on these lights* and switched on, and the device's **Audio** sensor isn't *Not listening* |
| Phone doesn't find the TV | Same Wi-Fi, not a guest network, and no "AP / client isolation" on the router. **Device link** on on both, same **Room** for sharing the beat |
| Colours change late | Update to v0.7: the WLEDs now get the colours first. If *Last:* in the app shows an HA error, the device can't reach Home Assistant |

From the Mac, the app's own summary:
```bash
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.STATUS
```
And a live log: `adb logcat -s MusicLightSynk` (Ctrl+C to stop).

---

## Updating (already set up)

1. **App:** download the new APK from
   [Releases](https://github.com/Krishna9827/Music-Light-Synk/releases/latest) and install it over
   the old one. Settings are kept.
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r ~/Downloads/music-light-synk.apk
   ```
2. **Integration:** HACS shows an update. Click **Update**, then restart HA. Settings are kept.
   (Blueprint users: re-import the blueprint, then *Developer tools → YAML → Reload automations*.)

**Coming from v0.8:** the *TV syncing*, *Backup listener* and *Beat sync* switches became **Mode**
and **Music effect** (selects). Your settings carry over: a phone with the backup listener on starts
in *Lights + microphone*, and beat sync becomes Music effect *Beat pulse*. Old switches that show as
unavailable in Home Assistant can be deleted.

**Coming from v0.6:** each device now keeps its own WLED list (no more copying between TV and
phone): check the phone's list. Set a **Room** on the phone if it's somewhere else, and pick
**Listen to** (Auto is gone). In Home Assistant the phone can now have its own device (step 11).

**Coming from v0.5:** nothing to redo. Install the new APK and update the integration; the new
controls appear by themselves. Part C sets up the Energy effect and the phone.

**Coming from v0.4 or older:** do step 7 (Android Debug Bridge) if you haven't, then open
Music Light Synk → **Configure → TV app control** and pick the Fire TV. The device page then gets
all the controls from the table above. Your old WLED settings are converted to the new
**WLED devices** list automatically.

---

## Optional: settings over ADB from the Mac

Everything above can also be set from the Mac. Handy for scripts. `R` saves typing:

```bash
R="-f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE"
```

```bash
# colour source: auto | artwork | screen
adb shell am broadcast $R --es source auto
# WLED devices (same format as above)
adb shell am broadcast $R --es wled_devices '192.168.1.60=ambient:40,22,40,22:bottom_left:cw; 192.168.1.61=left'
# per-app profiles: YouTube from the screen, Spotify from album art, ignore Netflix
adb shell am broadcast $R --es profiles 'com.google.android.youtube.tv=screen;com.spotify.tv.android=artwork;com.netflix.ninja=off'
# beat pulse (ambient WLED strips; needs audio permission once)
adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO
adb shell am broadcast $R --ez pulse true --ei pulse_strength 60
# Energy: a strip bouncing from both ends, and how it looks
adb shell am broadcast $R --es wled_devices '192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror'
adb shell am broadcast $R --ei energy_sensitivity 100 --ei energy_blur 2 --ei energy_bg 10
# audio source: auto | output | mic | off
adb shell am broadcast $R --es audio_source auto
# start / stop screen capture
adb shell am start -n com.musiclightsynk/.CaptureConsentActivity
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.STOP_CAPTURE
```

**MQTT** instead of (or as well as) the webhook:
```bash
adb shell am broadcast $R --ez mqtt_enabled true --es mqtt_host 192.168.1.10 --es mqtt_user YOUR_MQTT_USER --es mqtt_pass YOUR_MQTT_PASSWORD
```
Then import the MQTT blueprint (*Blueprints → Import blueprint*):
`https://github.com/Krishna9827/Music-Light-Synk/blob/main/home-assistant/blueprints/automation/music_light_synk_mqtt.yaml`.
MQTT discovery also creates the colour and status sensors automatically.

**Blueprint and integration side by side** (e.g. blueprint for lights, integration for its
sensors and TV controls): give the integration webhook ID `music_light_synk_entities` in step 8,
then send to both:
```bash
adb shell am broadcast $R --es webhook_id music_light_synk,music_light_synk_entities
```

Every option is listed in the README under *Settings*.
