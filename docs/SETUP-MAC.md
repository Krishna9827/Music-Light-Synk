# Setup from a Mac, step by step

About 15 minutes. You need the Mac for the first setup only. After that the app runs on the Fire TV
by itself, including after reboots.

You'll need two IP addresses. Replace these examples everywhere below:

- Fire TV: `192.168.1.50` (step 2 shows where to find it)
- Home Assistant: `192.168.1.10` (the address you open HA with, or HA → Settings → System → Network)

> Use the HA **IP address**, not `homeassistant.local`. Fire TV usually can't resolve `.local` names.

---

## 1. Install adb on the Mac

Open **Terminal** (Cmd+Space, type "Terminal").

If you have [Homebrew](https://brew.sh):

```bash
brew install --cask android-platform-tools
adb version
```

No Homebrew? Install it first by pasting the one-liner from https://brew.sh, then run the two
commands above. If `adb version` prints a version, you're good.

## 2. Turn on ADB on the Fire TV Stick

With the Fire TV remote:

1. Home screen → **Settings** (gear icon, far right) → **My Fire TV** → **About**.
2. Highlight **Fire TV Stick 4K Max** and press the **centre/select button 7 times** until it says
   *"You are now a developer"*.
3. Press Back → **Developer Options** → **ADB Debugging** → **ON**.
4. Go to **About** → **Network** and write down the **IP Address** (e.g. `192.168.1.50`).

The Mac and the Fire TV must be on the same Wi-Fi.

## 3. Connect the Mac to the Fire TV

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

## 4. Download the APK

1. In a browser where you're logged in to GitHub, open the repo → **Actions** tab → click the
   latest **Build APK** run (green tick).
2. Scroll to **Artifacts** → click **music-light-synk-apk**. A zip downloads.
3. Double-click the zip in Downloads. You get **music-light-synk.apk**.

## 5. Install the app and grant permissions

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

**Open the app once:**
```bash
adb shell am start -n com.musiclightsynk/.MainActivity
```
The TV shows the Music Light Synk screen, with **"Media access: OK"** in green.

## 6. Tell the app where Home Assistant is

```bash
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE --es ha_url http://192.168.1.10:8123 --es webhook_id music_light_synk
```

It replies `data="Saved: ha_url=http://192.168.1.10:8123, webhook_id=music_light_synk"`.

**Optional:** start the screen-capture fallback.
```bash
adb shell am start -n com.musiclightsynk/.CaptureConsentActivity
```

## 7. Home Assistant: install the integration (HACS)

Everything on the HA side happens in the UI. No files, no YAML.

1. **HACS → ⋮ (top right) → Custom repositories**. Repository:
   `https://github.com/Krishna9827/Music-Light-Synk`, type **Integration** → **Add**.
   (No HACS yet? Install it first: https://hacs.xyz/docs/use/download/download/)
2. Search HACS for **Music Light Synk** → **Download** → **restart Home Assistant**
   (*Settings → System → ⋮ → Restart*).
3. **Settings → Devices & services → Add integration → Music Light Synk**.
4. Name it (e.g. *Living room TV*) and keep webhook ID **`music_light_synk`**. That's what the
   app already sends to, so nothing changes on the TV.
5. **Pick your lights** for each zone of the artwork (corners, edges, dominant, vibrant). Leave
   the rest empty. With only 1–2 lights, use **Dominant colour**. **Submit**.
6. Optional: on the integration card, **Configure** → *Brightness and fades* (Tuya: set **Fade
   time** to `0`), *Effects* (WLED), *When the music stops*.
7. On the new device, press **Test lights**. Your lights show the rainbow test pattern for 6 seconds.

> **Already using the blueprint?** Delete that automation first (*Settings → Automations →
> Music Light Synk → ⋮ → Delete*). It holds the `music_light_synk` webhook, and HA allows only one
> listener per webhook ID. The integration does the same job.

<details><summary>Rather use the blueprint instead of the integration?</summary>

**Settings → Automations & scenes → Blueprints → Import blueprint** and paste
`https://github.com/Krishna9827/Music-Light-Synk/blob/main/home-assistant/blueprints/automation/music_light_synk.yaml`.
Then **Create automation**, keep webhook ID `music_light_synk`, pick lights, **Save**.
</details>

## 8. Test

```bash
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.TEST
```

You should get `data="Test colours -> HTTP 200"`, and your lights show a rainbow around the
"screen": top-left red, top orange, top-right yellow, right green, bottom-right cyan, bottom blue,
bottom-left indigo, left pink, dominant purple.

Now play a song in Spotify / Amazon Music / Apple Music on the Fire TV. The lights should change
within a second or two of each new track.

## If something's off

Check the status:
```bash
adb shell am broadcast -f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.STATUS
```

| Status says | Meaning / fix |
|---|---|
| `media_access=NOT GRANTED` | Run the `allow_listener` command from step 5 again |
| `last=... HTTP 200` but lights don't change | HA got it. Check the device's **Light sync** switch is on, the lights are picked under *Configure → Lights*, and the webhook IDs match (blueprint users: check the automation's **Traces**) |
| `ConnectException` / `timeout` | Wrong HA IP or port in step 6, or HA isn't reachable from the TV |
| `No colours: X did not provide album art` | That app doesn't share artwork. Run the optional screen-capture command in step 6 |

For a live log, run `adb logcat -s MusicLightSynk` (Ctrl+C to stop).

## Later

- **Update the app:** download the new APK and run `adb install -r ...` again. Settings are kept.
- **Change settings:** use the app on the TV (D-pad friendly) or re-run the step 6 command with
  other extras, e.g. `--es source artwork` or `--ei stop_delay 30`. All options are in the README.
- You can turn ADB Debugging off again afterwards. The app doesn't need it to run.

## Updating to a new version

1. **App:** download the new APK from Actions and run `adb install -r ~/Downloads/music-light-synk.apk`.
2. **Integration:** HACS shows an update. Click **Update**, then restart HA. Your settings are kept.
   (Blueprint users: re-import the blueprint, then *Developer tools → YAML → Reload automations*.)

## Optional extras (all copy-paste)

These all go to the same receiver. `R` saves typing:

```bash
R="-f 0x20 -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE"
```

**Per-app profiles** (YouTube from the screen, Spotify from album art, ignore Netflix):
```bash
adb shell am broadcast $R --es profiles "com.google.android.youtube.tv=screen;com.spotify.tv.android=artwork;com.netflix.ninja=off"
```
Or play something and press **"This app uses: ..."** on the TV to cycle through them.

**WLED straight from the TV** (instant, no HA). Use your WLED's IP:
```bash
# album colours, WLED keeps its own effect
adb shell am broadcast $R --es wled_mode colors --es wled_hosts 192.168.1.60
# or: ambient gradient around the TV. LED counts top,right,bottom,left + where LED 0 is
adb shell am broadcast $R --es wled_mode ambient --es wled_hosts 192.168.1.60 --es wled_layout 40,22,40,22 --es wled_start bottom_left --ez wled_clockwise true
```
If the same WLED is also in the blueprint, remove it there.

**Beat pulse** (ambient mode only):
```bash
adb shell pm grant com.musiclightsynk android.permission.RECORD_AUDIO
adb shell am broadcast $R --ez pulse true --ei pulse_strength 60
```

**MQTT** instead of (or as well as) the webhook:
```bash
adb shell am broadcast $R --ez mqtt_enabled true --es mqtt_host 192.168.1.10 --es mqtt_user YOUR_MQTT_USER --es mqtt_pass YOUR_MQTT_PASSWORD
```
Then import the MQTT blueprint (*Blueprints → Import blueprint*):
`https://github.com/Krishna9827/Music-Light-Synk/blob/main/home-assistant/blueprints/automation/music_light_synk_mqtt.yaml`.
MQTT discovery also creates the colour sensors automatically.

**Keep the blueprint and the integration side by side** (e.g. blueprint for lights, integration
for dashboards): give the integration webhook ID `music_light_synk_entities` in step 7, then tell
the app to send to both:
```bash
adb shell am broadcast $R --es webhook_id music_light_synk,music_light_synk_entities
```

Check everything with the `STATUS` command from step 8. It now also lists MQTT, WLED, pulse and
profiles.
