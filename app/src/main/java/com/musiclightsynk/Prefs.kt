package com.musiclightsynk

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.net.URLEncoder

enum class SourceMode(val key: String, val label: String) {
    AUTO("auto", "Auto: album art, screen if no art"),
    ARTWORK("artwork", "Album art only"),
    SCREEN("screen", "Screen corners only");

    fun next(): SourceMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): SourceMode =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * What this device does, in one choice (phones get all four; a TV, with no microphone, gets
 * Lights / Off). [lights]: drives its own lights (WLED, Home Assistant, MQTT) from what it plays
 * or hears. [mic]: keeps its microphone on and lends it to other devices (e.g. the TV).
 */
enum class DeviceMode(val key: String, val label: String, val lights: Boolean, val mic: Boolean) {
    LIGHTS_MIC("lights_mic", "Lights + microphone", true, true),
    MIC("mic", "Microphone only (lend it to other devices)", false, true),
    LIGHTS("lights", "Lights only", true, false),
    OFF("off", "Off", false, false);

    companion object {
        fun of(key: String?): DeviceMode? = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/** All user settings. Cheap to construct; reads straight from SharedPreferences every time. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("music_light_synk", Context.MODE_PRIVATE)

    /** Fire TV / Android TV (no mic, plays the music itself) vs phone / tablet. */
    val isTv: Boolean by lazy { context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    /** Has a microphone the app can lend (phones). */
    val hasMic: Boolean by lazy { !isTv && context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }

    /** The modes this device offers. */
    val modes: List<DeviceMode> get() = if (hasMic) DeviceMode.values().toList() else listOf(DeviceMode.LIGHTS, DeviceMode.OFF)

    /**
     * What this device does. Older versions had "syncing" and "backup listener" switches; they are
     * read as a mode until one is chosen. A mode with the microphone also makes it the audio source.
     */
    var mode: DeviceMode
        get() {
            val m = DeviceMode.of(str(K_MODE)) ?: when {
                !sp.getBoolean(K_ENABLED, true) -> if (sp.getBoolean(K_LISTENER, false)) DeviceMode.MIC else DeviceMode.OFF
                sp.getBoolean(K_LISTENER, false) -> DeviceMode.LIGHTS_MIC
                else -> DeviceMode.LIGHTS
            }
            return if (m in modes) m else if (m.lights) DeviceMode.LIGHTS else DeviceMode.OFF
        }
        set(v) {
            val e = sp.edit().putString(K_MODE, v.key)
            if (v.mic) e.putString(K_AUDIO_SOURCE, AudioSource.MIC.key) // the lent microphone is also what it listens to
            e.apply()
        }

    private fun str(k: String, def: String = "") = sp.getString(k, def) ?: def
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v.trim()).apply()
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // ---- Home Assistant -------------------------------------------------------------------------

    var haUrl: String
        get() = str(K_URL)
        set(v) = sp.edit().putString(K_URL, normalizeUrl(v)).apply()

    /** One or more webhook IDs, comma separated (e.g. blueprint + custom integration). */
    var webhookId: String
        get() = str(K_WEBHOOK, DEFAULT_WEBHOOK)
        set(v) = putStr(K_WEBHOOK, v)

    val webhookIds: List<String>
        get() = webhookId.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Optional long-lived access token; when set we also mirror colours into sensor.music_light_synk. */
    var token: String
        get() = str(K_TOKEN)
        set(v) = putStr(K_TOKEN, v)

    val isHaConfigured: Boolean get() = haUrl.isNotBlank() && webhookIds.isNotEmpty()

    fun webhookUrl(id: String): String = "$haUrl/api/webhook/" + URLEncoder.encode(id, "UTF-8")

    // ---- colour source ------------------------------------------------------------------------

    var sourceMode: SourceMode
        get() = SourceMode.of(sp.getString(K_SOURCE, SourceMode.AUTO.key))
        set(v) = putStr(K_SOURCE, v.key)

    /** Per-app overrides, e.g. "com.google.android.youtube.tv=screen;com.netflix.ninja=off". */
    var profilesRaw: String
        get() = str(K_PROFILES)
        set(v) = putStr(K_PROFILES, Profiles.format(Profiles.parse(v)))

    fun profileFor(pkg: String): AppProfile = Profiles.parse(profilesRaw)[pkg] ?: AppProfile.DEFAULT

    fun setProfile(pkg: String, profile: AppProfile) {
        val map = Profiles.parse(profilesRaw).toMutableMap()
        if (profile == AppProfile.DEFAULT) map.remove(pkg) else map[pkg] = profile
        putStr(K_PROFILES, Profiles.format(map))
    }

    /** Effective source for an app: its profile, or the global source mode. */
    fun sourceModeFor(pkg: String): SourceMode = profileFor(pkg).sourceMode ?: sourceMode

    /** Drives its own lights (see [mode]). Kept for older ADB / Home Assistant commands ("enabled"). */
    var enabled: Boolean
        get() = mode.lights
        set(v) {
            mode = when {
                v && mode.mic -> DeviceMode.LIGHTS_MIC
                v -> DeviceMode.LIGHTS
                mode.mic -> DeviceMode.MIC
                else -> DeviceMode.OFF
            }
        }

    /** Re-open the screen-capture permission after a reboot (only matters for Screen/Auto). */
    var bootCapture: Boolean
        get() = sp.getBoolean(K_BOOT_CAPTURE, true)
        set(v) = putBool(K_BOOT_CAPTURE, v)

    var cornerPct: Int
        get() = sp.getInt(K_CORNER, 25).coerceIn(CORNER_RANGE)
        set(v) = putInt(K_CORNER, v.coerceIn(CORNER_RANGE))

    var saturationPct: Int
        get() = sp.getInt(K_SATURATION, 130).coerceIn(SATURATION_RANGE)
        set(v) = putInt(K_SATURATION, v.coerceIn(SATURATION_RANGE))

    var minBrightnessPct: Int
        get() = sp.getInt(K_MIN_BRIGHTNESS, 15).coerceIn(MIN_BRIGHTNESS_RANGE)
        set(v) = putInt(K_MIN_BRIGHTNESS, v.coerceIn(MIN_BRIGHTNESS_RANGE))

    /** 0 = sample the screen only when the track changes. */
    var screenIntervalSec: Int
        get() = sp.getInt(K_SCREEN_INTERVAL, 0).coerceIn(SCREEN_INTERVAL_RANGE)
        set(v) = putInt(K_SCREEN_INTERVAL, v.coerceIn(SCREEN_INTERVAL_RANGE))

    /** How long playback must stay paused/stopped before we tell Home Assistant "stopped". */
    var stopDelaySec: Int
        get() = sp.getInt(K_STOP_DELAY, 15).coerceIn(STOP_DELAY_RANGE)
        set(v) = putInt(K_STOP_DELAY, v.coerceIn(STOP_DELAY_RANGE))

    /** Comma separated package names. Empty = react to every media app. */
    var allowedPackagesRaw: String
        get() = str(K_PACKAGES)
        set(v) = putStr(K_PACKAGES, v)

    val allowedPackages: Set<String>
        get() = allowedPackagesRaw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun extractOptions() = ExtractOptions(
        cornerFraction = cornerPct / 100f,
        saturationBoost = saturationPct / 100f,
        minBrightness = minBrightnessPct / 100f,
    )

    // ---- MQTT ---------------------------------------------------------------------------------

    var mqttEnabled: Boolean
        get() = sp.getBoolean(K_MQTT_ENABLED, false)
        set(v) = putBool(K_MQTT_ENABLED, v)

    var mqttHost: String
        get() = str(K_MQTT_HOST)
        set(v) = putStr(K_MQTT_HOST, v)

    var mqttPort: Int
        get() = sp.getInt(K_MQTT_PORT, 1883).coerceIn(1, 65535)
        set(v) = putInt(K_MQTT_PORT, v.coerceIn(1, 65535))

    var mqttUser: String
        get() = str(K_MQTT_USER)
        set(v) = putStr(K_MQTT_USER, v)

    var mqttPassword: String
        get() = str(K_MQTT_PASS)
        set(v) = putStr(K_MQTT_PASS, v)

    /** Topic prefix; use a different one per TV, e.g. music_light_synk/bedroom. */
    var mqttTopic: String
        get() = str(K_MQTT_TOPIC, DEFAULT_MQTT_TOPIC).ifBlank { DEFAULT_MQTT_TOPIC }
        set(v) = putStr(K_MQTT_TOPIC, v.trim().trim('/'))

    var mqttDiscovery: Boolean
        get() = sp.getBoolean(K_MQTT_DISCOVERY, true)
        set(v) = putBool(K_MQTT_DISCOVERY, v)

    var mqttTls: Boolean
        get() = sp.getBoolean(K_MQTT_TLS, false)
        set(v) = putBool(K_MQTT_TLS, v)

    val isMqttConfigured: Boolean get() = mqttEnabled && mqttHost.isNotBlank()

    fun mqttConfig(device: String, model: String) = MqttConfig(
        host = mqttHost,
        port = mqttPort,
        username = mqttUser.ifBlank { null },
        password = mqttPassword.ifBlank { null },
        prefix = mqttTopic,
        discovery = mqttDiscovery,
        tls = mqttTls,
        deviceName = device,
        model = model,
    )

    // ---- WLED direct --------------------------------------------------------------------------

    /** Master switch for direct WLED output. */
    var wledEnabled: Boolean
        get() = sp.getBoolean(K_WLED_ENABLED, true)
        set(v) = putBool(K_WLED_ENABLED, v)

    /**
     * Every WLED device and the zone it shows, e.g.
     * "192.168.1.60=ambient:40,22,40,22:bottom_left:cw; 192.168.1.61=top_left". See [WledDevices].
     * Before v0.5 there was one WLED mode for all hosts; that is converted on the fly.
     */
    var wledDevicesRaw: String
        get() = if (sp.contains(K_WLED_DEVICES)) str(K_WLED_DEVICES) else WledDevices.format(legacyWledDevices())
        set(v) = setWledDevices(WledDevices.parse(v).devices)

    val wledDevices: List<WledDevice> get() = WledDevices.parse(wledDevicesRaw).devices

    /** This device's own list: never shared with other devices. */
    fun setWledDevices(devices: List<WledDevice>) = putStr(K_WLED_DEVICES, WledDevices.format(devices))

    var wledBrightnessPct: Int
        get() = sp.getInt(K_WLED_BRI, 100).coerceIn(1, 100)
        set(v) = putInt(K_WLED_BRI, v.coerceIn(1, 100))

    var wledRestore: Boolean
        get() = sp.getBoolean(K_WLED_RESTORE, true)
        set(v) = putBool(K_WLED_RESTORE, v)

    var pulseEnabled: Boolean
        get() = sp.getBoolean(K_PULSE, false)
        set(v) = putBool(K_PULSE, v)

    var pulseStrengthPct: Int
        get() = sp.getInt(K_PULSE_STRENGTH, 60).coerceIn(10, 100)
        set(v) = putInt(K_PULSE_STRENGTH, v.coerceIn(10, 100))

    val isWledConfigured: Boolean get() = wledEnabled && wledDevices.isNotEmpty()

    fun wledConfig() = WledConfig(
        devices = wledDevices,
        brightness = wledBrightnessPct / 100f,
        restore = wledRestore,
        pulse = pulseEnabled,
        pulseStrength = pulseStrengthPct / 100f,
        energyBackground = energyBackgroundPct / 100f,
        energyBlur = energyBlur,
    )

    // ---- Audio and the Energy effect ---------------------------------------------------------

    /**
     * Exactly one source, never switched automatically: "output" (device audio), "mic", "off", or
     * "peer:<id>" (another device's microphone over Wi-Fi). Default: device audio on a TV, the
     * microphone on a phone (phones rarely let apps hear other apps' audio). Older "auto" counts as
     * the default.
     */
    val audioSourceKey: String
        get() = normalizeAudioKey(str(K_AUDIO_SOURCE)) ?: defaultAudioSource.key

    /** The device whose microphone this one uses, or null. */
    val audioPeerId: String? get() = audioSourceKey.takeIf { it.startsWith(PEER_PREFIX) }?.removePrefix(PEER_PREFIX)

    /** What this device listens to itself (OFF while it uses another device's microphone). */
    var audioSource: AudioSource
        get() = AudioSource.of(audioSourceKey) ?: AudioSource.OFF
        set(v) = putStr(K_AUDIO_SOURCE, v.key)

    /** Free text from ADB / Home Assistant; anything unknown means the default. */
    var audioSourceRaw: String
        get() = audioSourceKey
        set(v) = putStr(K_AUDIO_SOURCE, normalizeAudioKey(v) ?: "")

    /** Use another device's microphone (see [audioPeerId]); [name] is shown while it's away. */
    fun setAudioPeer(id: String, name: String) {
        sp.edit().putString(K_AUDIO_SOURCE, PEER_PREFIX + id).putString(K_AUDIO_PEER_NAME, name).apply()
    }

    var audioPeerName: String
        get() = str(K_AUDIO_PEER_NAME).ifBlank { "other device" }
        set(v) = putStr(K_AUDIO_PEER_NAME, v.take(40))

    /**
     * What goes to Home Assistant for its music effect: "off", "beat" (every beat, for the beat
     * pulse) or "level" (the music's level a few times a second, for Energy / Energy bars).
     * Home Assistant sets it to match the effect picked there.
     */
    var haMusic: String
        get() = str(K_HA_MUSIC).takeIf { it in HA_MUSIC } ?: if (sp.getBoolean(K_HA_BEATS, false)) "beat" else "off"
        set(v) = putStr(K_HA_MUSIC, v.trim().lowercase().takeIf { it in HA_MUSIC } ?: "off")

    /** v0.8 switch: on = beats. */
    var haBeats: Boolean
        get() = haMusic != "off"
        set(v) {
            haMusic = if (v) "beat" else "off"
        }

    val defaultAudioSource: AudioSource get() = if (isTv) AudioSource.OUTPUT else AudioSource.MIC

    /** How far the bars reach for the same loudness, in %. */
    var energySensitivityPct: Int
        get() = sp.getInt(K_ENERGY_SENS, 100).coerceIn(SENSITIVITY_RANGE.first, SENSITIVITY_RANGE.last)
        set(v) = putInt(K_ENERGY_SENS, v.coerceIn(SENSITIVITY_RANGE.first, SENSITIVITY_RANGE.last))

    /** Softening across neighbouring LEDs, 0 = sharp bars. */
    var energyBlur: Int
        get() = sp.getInt(K_ENERGY_BLUR, 2).coerceIn(BLUR_RANGE.first, BLUR_RANGE.last)
        set(v) = putInt(K_ENERGY_BLUR, v.coerceIn(BLUR_RANGE.first, BLUR_RANGE.last))

    /** Brightness of the strip's colour where no bar is, in %. */
    var energyBackgroundPct: Int
        get() = sp.getInt(K_ENERGY_BG, 10).coerceIn(ENERGY_BG_RANGE.first, ENERGY_BG_RANGE.last)
        set(v) = putInt(K_ENERGY_BG, v.coerceIn(ENERGY_BG_RANGE.first, ENERGY_BG_RANGE.last))

    // ---- Device link -------------------------------------------------------------------------

    /** Share colours / beat with the app on other devices and take turns driving WLED. */
    var linkEnabled: Boolean
        get() = sp.getBoolean(K_LINK, true)
        set(v) = putBool(K_LINK, v)

    /** Name shown on other devices; empty = the device model. */
    var deviceName: String
        get() = str(K_DEVICE_NAME)
        set(v) = putStr(K_DEVICE_NAME, v.take(40))

    /** Random id for the link, made once. */
    val deviceId: String
        get() = str(K_DEVICE_ID).ifEmpty {
            java.util.UUID.randomUUID().toString().replace("-", "").take(10).also { putStr(K_DEVICE_ID, it) }
        }

    /** Devices in the same room share the beat and album colours. Different rooms stay apart. */
    var room: String
        get() = str(K_ROOM, "Home").ifBlank { "Home" }
        set(v) = putStr(K_ROOM, v.take(40))

    /** Home Assistant may change settings over Wi-Fi ([ControlServer]). */
    var controlEnabled: Boolean
        get() = sp.getBoolean(K_CONTROL, true)
        set(v) = putBool(K_CONTROL, v)

    /** Secret for [ControlServer], made once; only ever sent to Home Assistant. */
    val controlToken: String
        get() = str(K_CONTROL_TOKEN).ifEmpty {
            val rnd = java.security.SecureRandom()
            val bytes = ByteArray(16).also { rnd.nextBytes(it) }
            bytes.joinToString("") { "%02x".format(it) }.also { putStr(K_CONTROL_TOKEN, it) }
        }

    /** Phone as backup listener: keeps the mic on and drives this device's WLEDs when it hears music. */
    var listenerEnabled: Boolean
        get() = mode.mic
        set(v) {
            mode = when {
                v && mode.lights -> DeviceMode.LIGHTS_MIC
                v -> DeviceMode.MIC
                mode.lights -> DeviceMode.LIGHTS
                else -> DeviceMode.OFF
            }
        }

    // v0.3 settings: one mode for all hosts. Still accepted over ADB and converted to devices.
    var legacyWledMode: WledMode
        get() = WledMode.of(str(K_WLED_MODE, WledMode.OFF.key))
        set(v) = putStr(K_WLED_MODE, v.key)
    var legacyWledHosts: String
        get() = str(K_WLED_HOSTS)
        set(v) = putStr(K_WLED_HOSTS, v)
    var legacyWledLayout: String
        get() = str(K_WLED_LAYOUT)
        set(v) = putStr(K_WLED_LAYOUT, v)
    var legacyWledStart: Corner
        get() = Corner.of(str(K_WLED_START, Corner.BOTTOM_LEFT.key))
        set(v) = putStr(K_WLED_START, v.key)
    var legacyWledClockwise: Boolean
        get() = sp.getBoolean(K_WLED_CW, true)
        set(v) = putBool(K_WLED_CW, v)
    var legacyWledColors: String
        get() = str(K_WLED_COLORS, "dominant,vibrant,average")
        set(v) = putStr(K_WLED_COLORS, v)
    var legacyWledFx: Int
        get() = sp.getInt(K_WLED_FX, -1).coerceIn(-1, 255)
        set(v) = putInt(K_WLED_FX, v.coerceIn(-1, 255))
    var legacyWledPalette: Int
        get() = sp.getInt(K_WLED_PAL, -1).coerceIn(-1, 255)
        set(v) = putInt(K_WLED_PAL, v.coerceIn(-1, 255))

    fun legacyWledDevices(): List<WledDevice> {
        val hosts = legacyWledHosts.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val zone: WledZone = when (legacyWledMode) {
            WledMode.OFF -> return emptyList()
            WledMode.AMBIENT -> WledZone.Ambient(
                WledLayout.parse(legacyWledLayout, legacyWledStart, legacyWledClockwise) ?: return emptyList(),
            )
            WledMode.COLORS -> WledZone.Palette(
                legacyWledColors.split(',', ';', ' ').mapNotNull { k -> Slot.values().firstOrNull { it.key.equals(k.trim(), true) } }
                    .ifEmpty { WledZone.DEFAULT_SLOTS }.take(3),
                legacyWledFx,
                legacyWledPalette,
            )
        }
        return hosts.map { WledDevice(it, zone) }
    }

    /** After a legacy key was changed over ADB: rebuild the device list from the legacy settings. */
    fun applyLegacyWled() = setWledDevices(legacyWledDevices())

    /** Anything at all to send to? */
    val hasOutput: Boolean get() = isHaConfigured || isMqttConfigured || isWledConfigured

    companion object {
        const val DEFAULT_WEBHOOK = "music_light_synk"
        const val DEFAULT_MQTT_TOPIC = "music_light_synk"

        val CORNER_RANGE = 10..50
        val SATURATION_RANGE = 50..250
        val MIN_BRIGHTNESS_RANGE = 1..60
        val SCREEN_INTERVAL_RANGE = 0..120
        val STOP_DELAY_RANGE = 0..300
        val SENSITIVITY_RANGE = 10..300
        val BLUR_RANGE = 0..10
        val ENERGY_BG_RANGE = 0..100

        private const val K_URL = "ha_url"
        private const val K_WEBHOOK = "webhook_id"
        private const val K_TOKEN = "token"
        private const val K_SOURCE = "source"
        private const val K_PROFILES = "profiles"
        private const val K_ENABLED = "enabled"
        private const val K_BOOT_CAPTURE = "boot_capture"
        private const val K_CORNER = "corner_pct"
        private const val K_SATURATION = "saturation_pct"
        private const val K_MIN_BRIGHTNESS = "min_brightness_pct"
        private const val K_SCREEN_INTERVAL = "screen_interval"
        private const val K_STOP_DELAY = "stop_delay"
        private const val K_PACKAGES = "packages"
        private const val K_MQTT_ENABLED = "mqtt_enabled"
        private const val K_MQTT_HOST = "mqtt_host"
        private const val K_MQTT_PORT = "mqtt_port"
        private const val K_MQTT_USER = "mqtt_user"
        private const val K_MQTT_PASS = "mqtt_pass"
        private const val K_MQTT_TOPIC = "mqtt_topic"
        private const val K_MQTT_DISCOVERY = "mqtt_discovery"
        private const val K_MQTT_TLS = "mqtt_tls"
        private const val K_WLED_ENABLED = "wled_enabled"
        private const val K_WLED_DEVICES = "wled_devices"
        private const val K_WLED_MODE = "wled_mode"
        private const val K_WLED_HOSTS = "wled_hosts"
        private const val K_WLED_LAYOUT = "wled_layout"
        private const val K_WLED_START = "wled_start"
        private const val K_WLED_CW = "wled_clockwise"
        private const val K_WLED_COLORS = "wled_colors"
        private const val K_WLED_FX = "wled_fx"
        private const val K_WLED_PAL = "wled_pal"
        private const val K_WLED_BRI = "wled_brightness_pct"
        private const val K_WLED_RESTORE = "wled_restore"
        private const val K_PULSE = "pulse"
        private const val K_PULSE_STRENGTH = "pulse_strength"
        private const val K_ROOM = "room"
        private const val K_AUDIO_PEER_NAME = "audio_peer_name"
        private const val K_HA_BEATS = "ha_beats"
        private const val K_HA_MUSIC = "ha_music"
        private const val K_MODE = "mode"
        val HA_MUSIC = listOf("off", "beat", "level")
        const val PEER_PREFIX = "peer:"
        private val PEER_ID = Regex("[A-Za-z0-9]{1,40}")

        /** "output" / "mic" / "off" / "peer:<id>", or null for anything else. */
        fun normalizeAudioKey(raw: String?): String? {
            val v = raw?.trim() ?: return null
            if (v.startsWith(PEER_PREFIX, ignoreCase = true)) {
                val id = v.substring(PEER_PREFIX.length)
                return if (PEER_ID.matches(id)) PEER_PREFIX + id else null
            }
            return AudioSource.of(v)?.key
        }
        private const val K_CONTROL = "control_enabled"
        private const val K_CONTROL_TOKEN = "control_token"
        private const val K_AUDIO_SOURCE = "audio_source"
        private const val K_ENERGY_SENS = "energy_sensitivity"
        private const val K_ENERGY_BLUR = "energy_blur"
        private const val K_ENERGY_BG = "energy_bg"
        private const val K_LINK = "link_enabled"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_DEVICE_ID = "link_id"
        private const val K_LISTENER = "listener"

        fun normalizeUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.contains("://")) s = "http://$s"
            return s
        }
    }
}

/** Per-app override of the colour source. */
enum class AppProfile(val key: String, val label: String, val sourceMode: SourceMode?) {
    DEFAULT("default", "Default", null),
    ARTWORK("artwork", "Album art", SourceMode.ARTWORK),
    SCREEN("screen", "Screen", SourceMode.SCREEN),
    AUTO("auto", "Auto", SourceMode.AUTO),
    OFF("off", "Ignore this app", null);

    fun next(): AppProfile = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): AppProfile? = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/** "pkg=mode;pkg=mode" <-> map. Pure, unit tested. */
object Profiles {
    fun parse(raw: String): Map<String, AppProfile> {
        val out = LinkedHashMap<String, AppProfile>()
        for (entry in raw.split(';', ',', '\n')) {
            val parts = entry.split('=', ':')
            if (parts.size != 2) continue
            val pkg = parts[0].trim()
            val profile = AppProfile.of(parts[1]) ?: continue
            if (pkg.isEmpty() || profile == AppProfile.DEFAULT) continue
            out[pkg] = profile
        }
        return out
    }

    fun format(map: Map<String, AppProfile>): String = map.entries.joinToString(";") { "${it.key}=${it.value.key}" }
}
