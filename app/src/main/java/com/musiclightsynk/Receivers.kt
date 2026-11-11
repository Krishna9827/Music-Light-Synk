package com.musiclightsynk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** After a reboot: re-open screen capture if the user relies on it, and nudge the listener. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        MediaListenerService.requestRebindIfNeeded(context)
        val p = Prefs(context)
        if (p.enabled && p.bootCapture && p.sourceMode != SourceMode.ARTWORK && !ScreenCaptureService.isRunning) {
            try {
                // Allowed from the background once SYSTEM_ALERT_WINDOW is granted via appops (setup script).
                context.startActivity(CaptureConsentActivity.intent(context))
            } catch (e: Exception) {
                Log.w("MusicLightSynk", "Could not restart screen capture after boot", e)
            }
        }
    }
}

/**
 * Configure / test the app from a computer, because typing URLs with a TV remote is pain.
 * Protected by android.permission.DUMP, which ADB's shell user has and normal apps can't get.
 *
 *   adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.CONFIGURE \
 *       --es ha_url http://192.168.1.10:8123 --es webhook_id music_light_synk
 *   adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.TEST
 *   adb shell am broadcast -n com.musiclightsynk/.AdbCommandReceiver -a com.musiclightsynk.STATUS
 */
class AdbCommandReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONFIGURE = "com.musiclightsynk.CONFIGURE"
        const val ACTION_TEST = "com.musiclightsynk.TEST"
        const val ACTION_STATUS = "com.musiclightsynk.STATUS"
        const val ACTION_CAPTURE = "com.musiclightsynk.CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.musiclightsynk.STOP_CAPTURE"

        private val LEGACY_WLED_KEYS = listOf(
            "wled_mode", "wled_hosts", "wled_layout", "wled_start", "wled_clockwise", "wled_colors", "wled_fx", "wled_pal",
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIGURE -> resultData = configure(context, intent)
            ACTION_STATUS -> {
                resultData = status(context)
                SyncEngine.reportStatus(context, force = true) // also push it to Home Assistant / MQTT
            }
            ACTION_STOP_CAPTURE -> {
                ScreenCaptureService.stop(context)
                resultData = "Stopped screen capture"
            }
            ACTION_TEST -> {
                val pending = goAsync()
                SyncEngine.sendTest(context) { msg ->
                    pending.resultData = msg
                    pending.finish()
                }
            }
            ACTION_CAPTURE -> resultData = try {
                context.startActivity(CaptureConsentActivity.intent(context))
                "Requested screen capture"
            } catch (e: Exception) {
                "Could not start screen capture: ${e.message}"
            }
            else -> resultData = "Unknown action ${intent.action}"
        }
    }

    private class Opt(val key: String, val kind: Char, val apply: (Prefs, Any) -> String)

    /** Every setting that can be changed over ADB: s = string, i = int, b = boolean. */
    private val options: List<Opt> = listOf(
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
        // v0.3 single-WLED keys, still accepted: they rebuild the device list (see LEGACY_WLED_KEYS).
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
    )

    private fun configure(ctx: Context, i: Intent): String {
        val p = Prefs(ctx)
        val changed = mutableListOf<String>()
        val bad = mutableListOf<String>()
        for (opt in options) {
            if (!i.hasExtra(opt.key)) continue
            val value: Any? = when (opt.kind) {
                's' -> stringExtra(i, opt.key)
                'i' -> intExtra(i, opt.key)
                else -> boolExtra(i, opt.key)
            }
            if (value == null) bad += opt.key else changed += "${opt.key}=${opt.apply(p, value)}"
        }
        if (changed.isEmpty() && bad.isEmpty()) {
            return "Nothing changed. Options: " + options.joinToString(" ") { it.key }
        }
        if (LEGACY_WLED_KEYS.any { i.hasExtra(it) } && !i.hasExtra("wled_devices")) {
            p.applyLegacyWled()
            changed += "wled_devices=${p.wledDevicesRaw.ifEmpty { "(none)" }}"
        }
        SyncEngine.settingsChanged(ctx)
        SyncEngine.notifyUi()
        return "Saved: " + changed.joinToString(", ") + if (bad.isEmpty()) "" else " | could not read: " + bad.joinToString(", ")
    }

    @Suppress("DEPRECATION")
    private fun stringExtra(i: Intent, key: String): String? = i.extras?.get(key)?.toString()

    private fun status(ctx: Context): String {
        val p = Prefs(ctx)
        val s = SyncEngine.status
        return listOf(
            "media_access=" + when {
                MediaListenerService.connected -> "connected"
                MediaListenerService.isEnabled(ctx) -> "granted (not bound yet)"
                else -> "NOT GRANTED"
            },
            "screen_capture=" + if (ScreenCaptureService.isRunning) "running" else "off",
            "ha_url=" + p.haUrl.ifEmpty { "(not set)" },
            "webhook_id=" + p.webhookId,
            "token=" + if (p.token.isBlank()) "(none)" else "set",
            "source=" + p.sourceMode.key,
            "profiles=" + p.profilesRaw.ifEmpty { "(none)" },
            "mqtt=" + if (p.isMqttConfigured) "${p.mqttHost}:${p.mqttPort} ${p.mqttTopic}" else "off",
            "wled=" + if (p.isWledConfigured) p.wledDevicesRaw else "off",
            "pulse=" + p.pulseEnabled,
            "enabled=" + p.enabled,
            "now=" + s.nowPlaying,
            "last=" + s.lastResult,
            "note=" + (s.note ?: "-"),
        ).joinToString(" | ")
    }

    @Suppress("DEPRECATION")
    private fun intExtra(i: Intent, key: String): Int? {
        if (!i.hasExtra(key)) return null
        return when (val v = i.extras?.get(key)) {
            is Int -> v
            is Long -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun boolExtra(i: Intent, key: String): Boolean? {
        if (!i.hasExtra(key)) return null
        return when (val v = i.extras?.get(key)) {
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
}
