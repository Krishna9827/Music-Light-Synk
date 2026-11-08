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

    var haUrl: String
        get() = sp.getString(K_URL, "") ?: ""
        set(v) = sp.edit().putString(K_URL, normalizeUrl(v)).apply()

    var webhookId: String
        get() = sp.getString(K_WEBHOOK, DEFAULT_WEBHOOK) ?: DEFAULT_WEBHOOK
        set(v) = sp.edit().putString(K_WEBHOOK, v.trim()).apply()

    /** Optional long-lived access token; when set we also mirror colours into sensor.music_light_synk. */
    var token: String
        get() = sp.getString(K_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(K_TOKEN, v.trim()).apply()

    var sourceMode: SourceMode
        get() = SourceMode.of(sp.getString(K_SOURCE, SourceMode.AUTO.key))
        set(v) = sp.edit().putString(K_SOURCE, v.key).apply()

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** Re-open the screen-capture permission after a reboot (only matters for Screen/Auto). */
    var bootCapture: Boolean
        get() = sp.getBoolean(K_BOOT_CAPTURE, true)
        set(v) = sp.edit().putBoolean(K_BOOT_CAPTURE, v).apply()

    var cornerPct: Int
        get() = sp.getInt(K_CORNER, 25).coerceIn(CORNER_RANGE)
        set(v) = sp.edit().putInt(K_CORNER, v.coerceIn(CORNER_RANGE)).apply()

    var saturationPct: Int
        get() = sp.getInt(K_SATURATION, 130).coerceIn(SATURATION_RANGE)
        set(v) = sp.edit().putInt(K_SATURATION, v.coerceIn(SATURATION_RANGE)).apply()

    var minBrightnessPct: Int
        get() = sp.getInt(K_MIN_BRIGHTNESS, 15).coerceIn(MIN_BRIGHTNESS_RANGE)
        set(v) = sp.edit().putInt(K_MIN_BRIGHTNESS, v.coerceIn(MIN_BRIGHTNESS_RANGE)).apply()

    /** 0 = sample the screen only when the track changes. */
    var screenIntervalSec: Int
        get() = sp.getInt(K_SCREEN_INTERVAL, 0).coerceIn(SCREEN_INTERVAL_RANGE)
        set(v) = sp.edit().putInt(K_SCREEN_INTERVAL, v.coerceIn(SCREEN_INTERVAL_RANGE)).apply()

    /** How long playback must stay paused/stopped before we tell Home Assistant "stopped". */
    var stopDelaySec: Int
        get() = sp.getInt(K_STOP_DELAY, 15).coerceIn(STOP_DELAY_RANGE)
        set(v) = sp.edit().putInt(K_STOP_DELAY, v.coerceIn(STOP_DELAY_RANGE)).apply()

    /** Comma separated package names. Empty = react to every media app. */
    var allowedPackagesRaw: String
        get() = sp.getString(K_PACKAGES, "") ?: ""
        set(v) = sp.edit().putString(K_PACKAGES, v.trim()).apply()

    val allowedPackages: Set<String>
        get() = allowedPackagesRaw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val isConfigured: Boolean get() = haUrl.isNotBlank() && webhookId.isNotBlank()

    fun webhookUrl(): String = "$haUrl/api/webhook/" + URLEncoder.encode(webhookId, "UTF-8")

    fun extractOptions() = ExtractOptions(
        cornerFraction = cornerPct / 100f,
        saturationBoost = saturationPct / 100f,
        minBrightness = minBrightnessPct / 100f,
    )

    companion object {
        const val DEFAULT_WEBHOOK = "music_light_synk"

        val CORNER_RANGE = 10..50
        val SATURATION_RANGE = 50..250
        val MIN_BRIGHTNESS_RANGE = 1..60
        val SCREEN_INTERVAL_RANGE = 0..120
        val STOP_DELAY_RANGE = 0..300

        private const val K_URL = "ha_url"
        private const val K_WEBHOOK = "webhook_id"
        private const val K_TOKEN = "token"
        private const val K_SOURCE = "source"
        private const val K_ENABLED = "enabled"
        private const val K_BOOT_CAPTURE = "boot_capture"
        private const val K_CORNER = "corner_pct"
        private const val K_SATURATION = "saturation_pct"
        private const val K_MIN_BRIGHTNESS = "min_brightness_pct"
        private const val K_SCREEN_INTERVAL = "screen_interval"
        private const val K_STOP_DELAY = "stop_delay"
        private const val K_PACKAGES = "packages"

        fun normalizeUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isNotEmpty() && !s.contains("://")) s = "http://$s"
            return s
        }
    }
}
