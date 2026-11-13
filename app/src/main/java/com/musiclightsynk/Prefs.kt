package com.musiclightsynk

import android.content.Context
import android.content.SharedPreferences
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

/** All user settings. Cheap to construct; reads straight from SharedPreferences every time. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("music_light_synk", Context.MODE_PRIVATE)

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

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = putBool(K_ENABLED, v)

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

    /** A local edit (app, ADB, Home Assistant): becomes the newest list for every linked device. */
    fun setWledDevices(devices: List<WledDevice>) {
        sp.edit().putString(K_WLED_DEVICES, WledDevices.format(devices)).putLong(K_WLED_REV, System.currentTimeMillis()).apply()
    }

    /** When the WLED list was last edited (ms since epoch; 0 = never). Newest wins across the link. */
    val wledRev: Long get() = sp.getLong(K_WLED_REV, 0L)

    /** A newer list from another device on the link. */
    fun applySyncedWled(raw: String, rev: Long) {
        sp.edit().putString(K_WLED_DEVICES, WledDevices.format(WledDevices.parse(raw).devices)).putLong(K_WLED_REV, rev).apply()
    }

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

    var audioSource: AudioSource
        get() = AudioSource.of(str(K_AUDIO_SOURCE, AudioSource.AUTO.key))
        set(v) = putStr(K_AUDIO_SOURCE, v.key)

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

    /** Phone as backup listener: keeps the mic on and drives WLED when nothing else does. */
    var listenerEnabled: Boolean
        get() = sp.getBoolean(K_LISTENER, false)
        set(v) = putBool(K_LISTENER, v)

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
        private const val K_WLED_REV = "wled_devices_rev"
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
