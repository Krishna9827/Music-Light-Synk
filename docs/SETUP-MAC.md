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

This lets Home Assistant change the TV app's settings: colour source, screen capture, WLED, and
everything else. Skip it if you only want the lights to follow the music. You can add it later.

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
2. With the Fire TV added (step 7): press **Send test colours from TV** (under *Configuration*).
   Same rainbow, but this time it comes from the TV app, so the whole path works.
3. Play a song in Spotify / Amazon Music / Apple Music on the Fire TV. The lights change within a
   second or two of each new track, and **Colour source** shows *Album art (media access)*.

---

## What you can do from Home Assistant now

Everything is on the TV's device page. Controls marked *(Fire TV)* need step 7.

| What | Where |
|---|---|
| Where the colours come from | **Colour source** sensor: *Album art (media access)* or *Screen capture* |
| Is media access granted / screen capture running? | **Media access**, **Screen capture** binary sensors |
| Switch album art ↔ screen capture *(Fire TV)* | **Colour source mode**: Auto / Album art / Screen capture |
| Start / stop screen capture *(Fire TV)* | **Screen capture** switch |
| Pause everything | **TV syncing** switch *(Fire TV)* (the app) or **Light sync** switch (only HA's lights) |
| Beat pulse for WLED ambient strips *(Fire TV)* | **Beat pulse** switch, **Pulse strength** number |
| WLED devices and what each one shows *(Fire TV)* | **WLED devices** text (format below), **WLED output**, **WLED brightness** |
| Per-app profiles *(Fire TV)* | **Per-app profiles** text, e.g. `com.google.android.youtube.tv=screen;com.netflix.ninja=off` |
| Edge size, saturation, minimum brightness, re-sample interval, stop delay *(Fire TV)* | Numbers under *Configuration* |
| Fix permissions *(Fire TV)* | **Grant TV permissions** button |
| See the latest values *(Fire TV)* | **Refresh TV app status** button |

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
to keep the WLED's current effect. On the TV the same list is under **WLED devices** (Add →
press the zone button to cycle). If a WLED is also one of your Home Assistant light zones,
remove it there so the two don't fight.

---

## If something's off

Look at the TV's device page first:

| You see | Meaning / fix |
|---|---|
| **Media access** is off | Press **Grant TV permissions** (or run the `allow_listener` command from step 5) |
| **Colour source** says *Nothing yet* while music plays | The app doesn't see the song. Check *Media access*, then press **Refresh TV app status** |
| Lights don't change, but *Now playing* updates | Check **Light sync** is on and lights are picked under *Configure → Lights* |
| *Now playing* never updates | The TV can't reach HA: wrong HA IP/port in step 6, or the webhook IDs don't match |
| TV controls are *unavailable* | No Fire TV picked under *Configure → TV app control*, or the Android Debug Bridge entity is unavailable (see the note in step 7) |
| An app shows *did not provide album art* | That app doesn't share artwork. Set **Colour source mode** to *Auto* and turn on **Screen capture** |

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
