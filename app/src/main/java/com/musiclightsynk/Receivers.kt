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
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONFIGURE -> resultData = configure(context, intent)
            ACTION_STATUS -> resultData = status(context)
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

    private fun configure(ctx: Context, i: Intent): String {
        val p = Prefs(ctx)
        val changed = mutableListOf<String>()
        i.getStringExtra("ha_url")?.let {
            p.haUrl = it
            changed += "ha_url=${p.haUrl}"
        }
        i.getStringExtra("webhook_id")?.let {
            p.webhookId = it
            changed += "webhook_id=${p.webhookId}"
        }
        i.getStringExtra("token")?.let {
            p.token = it
            changed += if (it.isBlank()) "token cleared" else "token set"
        }
        i.getStringExtra("source")?.let {
            p.sourceMode = SourceMode.of(it)
            changed += "source=${p.sourceMode.key}"
        }
        i.getStringExtra("packages")?.let {
            p.allowedPackagesRaw = it
            changed += "packages=${p.allowedPackagesRaw.ifEmpty { "(all)" }}"
        }
        intExtra(i, "corner_pct")?.let {
            p.cornerPct = it
            changed += "corner_pct=${p.cornerPct}"
        }
        intExtra(i, "saturation_pct")?.let {
            p.saturationPct = it
            changed += "saturation_pct=${p.saturationPct}"
        }
        intExtra(i, "min_brightness_pct")?.let {
            p.minBrightnessPct = it
            changed += "min_brightness_pct=${p.minBrightnessPct}"
        }
        intExtra(i, "screen_interval")?.let {
            p.screenIntervalSec = it
            changed += "screen_interval=${p.screenIntervalSec}"
        }
        intExtra(i, "stop_delay")?.let {
            p.stopDelaySec = it
            changed += "stop_delay=${p.stopDelaySec}"
        }
        boolExtra(i, "enabled")?.let {
            p.enabled = it
            changed += "enabled=$it"
        }
        boolExtra(i, "boot_capture")?.let {
            p.bootCapture = it
            changed += "boot_capture=$it"
        }
        if (changed.isEmpty()) {
            return "Nothing changed. String extras: ha_url webhook_id token source(auto|artwork|screen) packages; " +
                "numbers: corner_pct saturation_pct min_brightness_pct screen_interval stop_delay; " +
                "booleans: enabled boot_capture"
        }
        SyncEngine.settingsChanged(ctx)
        SyncEngine.notifyUi()
        return "Saved: " + changed.joinToString(", ")
    }

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
            "enabled=" + p.enabled,
            "now=" + s.nowPlaying,
            "last=" + s.lastResult,
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
