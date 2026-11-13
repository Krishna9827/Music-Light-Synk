package com.musiclightsynk

import android.content.Context

/**
 * Every setting that can be changed from outside the app: over ADB ([AdbCommandReceiver]) or
 * over Wi-Fi from Home Assistant ([ControlServer]). One table, so both accept the same keys.
 */
object AppSettings {
    /** [kind]: s = string, i = int, b = boolean. [apply] stores the value and returns how it was saved. */
    class Opt(val key: String, val kind: Char, val apply: (Prefs, Any) -> String)

    /** v0.3 single-WLED keys, still accepted: they rebuild the device list. */
    val LEGACY_WLED_KEYS = listOf(
        "wled_mode", "wled_hosts", "wled_layout", "wled_start", "wled_clockwise", "wled_colors", "wled_fx", "wled_pal",
    )

    val options: List<Opt> = listOf(
        Opt("ha_url", 's') { p, v -> p.haUrl = v as String; p.haUrl },
        Opt("webhook_id", 's') { p, v -> p.webhookId = v as String; p.webhookId },
        Opt("token", 's') { p, v -> p.token = v as String; if (p.token.isBlank()) "cleared" else "set" },
        Opt("source", 's') { p, v -> p.sourceMode = SourceMode.of(v as String); p.sourceMode.key },
        Opt("packages", 's') { p, v -> p.allowedPackagesRaw = v as String; p.allowedPackagesRaw.ifEmpty { "(all)" } },
        Opt("profiles", 's') { p, v -> p.profilesRaw = v as String; p.profilesRaw.ifEmpty { "(none)" } },
        Opt("corner_pct", 'i') { p, v -> p.cornerPct = v as Int; "${p.cornerPct}" },
        Opt("saturation_pct", 'i') { p, v -> p.saturationPct = v as Int; "${p.saturationPct}" },
        Opt("min_brightness_pct", 'i') { p, v -> p.minBrightnessPct = v as Int; "${p.minBrightnessPct}" },
        Opt("screen_interval", 'i') { p, v -> p.screenIntervalSec = v as Int; "${p.screenIntervalSec}" },
        Opt("stop_delay", 'i') { p, v -> p.stopDelaySec = v as Int; "${p.stopDelaySec}" },
        Opt("enabled", 'b') { p, v -> p.enabled = v as Boolean; "${p.enabled}" },
        Opt("boot_capture", 'b') { p, v -> p.bootCapture = v as Boolean; "${p.bootCapture}" },
        Opt("mqtt_enabled", 'b') { p, v -> p.mqttEnabled = v as Boolean; "${p.mqttEnabled}" },
        Opt("mqtt_host", 's') { p, v -> p.mqttHost = v as String; p.mqttHost },
        Opt("mqtt_port", 'i') { p, v -> p.mqttPort = v as Int; "${p.mqttPort}" },
        Opt("mqtt_user", 's') { p, v -> p.mqttUser = v as String; if (p.mqttUser.isBlank()) "(none)" else "set" },
        Opt("mqtt_pass", 's') { p, v -> p.mqttPassword = v as String; if (p.mqttPassword.isBlank()) "(none)" else "set" },
        Opt("mqtt_topic", 's') { p, v -> p.mqttTopic = v as String; p.mqttTopic },
        Opt("mqtt_discovery", 'b') { p, v -> p.mqttDiscovery = v as Boolean; "${p.mqttDiscovery}" },
        Opt("mqtt_tls", 'b') { p, v -> p.mqttTls = v as Boolean; "${p.mqttTls}" },
        Opt("wled_enabled", 'b') { p, v -> p.wledEnabled = v as Boolean; "${p.wledEnabled}" },
        Opt("wled_devices", 's') { p, v ->
            val parsed = WledDevices.parse(v as String)
            p.wledDevicesRaw = v
            p.wledDevicesRaw.ifEmpty { "(none)" } +
                if (parsed.errors.isEmpty()) "" else " | IGNORED: " + parsed.errors.joinToString(", ")
        },
        Opt("wled_mode", 's') { p, v -> p.legacyWledMode = WledMode.of(v as String); p.legacyWledMode.key },
        Opt("wled_hosts", 's') { p, v -> p.legacyWledHosts = v as String; p.legacyWledHosts },
        Opt("wled_layout", 's') { p, v -> p.legacyWledLayout = v as String; p.legacyWledLayout },
        Opt("wled_start", 's') { p, v -> p.legacyWledStart = Corner.of(v as String); p.legacyWledStart.key },
        Opt("wled_clockwise", 'b') { p, v -> p.legacyWledClockwise = v as Boolean; "${p.legacyWledClockwise}" },
        Opt("wled_colors", 's') { p, v -> p.legacyWledColors = v as String; p.legacyWledColors },
        Opt("wled_fx", 'i') { p, v -> p.legacyWledFx = v as Int; "${p.legacyWledFx}" },
        Opt("wled_pal", 'i') { p, v -> p.legacyWledPalette = v as Int; "${p.legacyWledPalette}" },
        Opt("wled_brightness_pct", 'i') { p, v -> p.wledBrightnessPct = v as Int; "${p.wledBrightnessPct}" },
        Opt("wled_restore", 'b') { p, v -> p.wledRestore = v as Boolean; "${p.wledRestore}" },
        Opt("pulse", 'b') { p, v -> p.pulseEnabled = v as Boolean; "${p.pulseEnabled}" },
        Opt("pulse_strength", 'i') { p, v -> p.pulseStrengthPct = v as Int; "${p.pulseStrengthPct}" },
        Opt("audio_source", 's') { p, v -> p.audioSourceRaw = v as String; p.audioSourceKey },
        Opt("ha_beats", 'b') { p, v -> p.haBeats = v as Boolean; "${p.haBeats}" },
        Opt("energy_sensitivity", 'i') { p, v -> p.energySensitivityPct = v as Int; "${p.energySensitivityPct}" },
        Opt("energy_blur", 'i') { p, v -> p.energyBlur = v as Int; "${p.energyBlur}" },
        Opt("energy_bg", 'i') { p, v -> p.energyBackgroundPct = v as Int; "${p.energyBackgroundPct}" },
        Opt("link_enabled", 'b') { p, v -> p.linkEnabled = v as Boolean; "${p.linkEnabled}" },
        Opt("device_name", 's') { p, v -> p.deviceName = v as String; p.deviceName.ifEmpty { "(model name)" } },
        Opt("room", 's') { p, v -> p.room = v as String; p.room },
    )

    private val byKey = options.associateBy { it.key }

    val keys: List<String> get() = options.map { it.key }

    fun kindOf(key: String): Char? = byKey[key]?.kind

    /** Turn a loosely typed value (ADB extra, JSON) into what [kindOf] expects, or null if it can't be read. */
    fun coerce(kind: Char, v: Any?): Any? = when (kind) {
        's' -> v?.toString()
        'i' -> when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> Math.round(v).toInt()
            is Float -> Math.round(v)
            is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.let { Math.round(it).toInt() }
            else -> null
        }
        else -> when (v) {
            is Boolean -> v
            is Int -> v != 0
            is String -> when (v.trim().lowercase()) {
                "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    /**
     * Save [values] (key -> raw value; unknown keys are reported) and tell the engine.
     * Returns a one-line summary like "Saved: corner_pct=25".
     */
    fun apply(ctx: Context, values: Map<String, Any?>): String {
        val p = Prefs(ctx)
        val changed = mutableListOf<String>()
        val bad = mutableListOf<String>()
        for ((key, raw) in values) {
            val opt = byKey[key]
            if (opt == null) {
                bad += key
                continue
            }
            val v = coerce(opt.kind, raw)
            if (v == null) bad += key else changed += "$key=${opt.apply(p, v)}"
        }
        if (changed.isEmpty() && bad.isEmpty()) return "Nothing changed. Options: " + keys.joinToString(" ")
        if (LEGACY_WLED_KEYS.any { it in values } && "wled_devices" !in values) {
            p.applyLegacyWled()
            changed += "wled_devices=${p.wledDevicesRaw.ifEmpty { "(none)" }}"
        }
        if (changed.isNotEmpty()) {
            SyncEngine.settingsChanged(ctx)
            SyncEngine.notifyUi()
        }
        return "Saved: " + changed.joinToString(", ") + if (bad.isEmpty()) "" else " | could not read: " + bad.joinToString(", ")
    }
}
