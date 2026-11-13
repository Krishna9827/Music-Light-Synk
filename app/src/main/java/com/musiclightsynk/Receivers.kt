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
        // Phone backup listener: allowed after boot on older Android; newer versions need the app opened once.
        if (p.listenerEnabled) ListenerService.start(context)
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

    /** Every key in [AppSettings] that the broadcast carries as an extra. */
    @Suppress("DEPRECATION")
    private fun configure(ctx: Context, i: Intent): String {
        val values = AppSettings.keys.filter { i.hasExtra(it) }.associateWith { i.extras?.get(it) }
        return AppSettings.apply(ctx, values)
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
            "profiles=" + p.profilesRaw.ifEmpty { "(none)" },
            "mqtt=" + if (p.isMqttConfigured) "${p.mqttHost}:${p.mqttPort} ${p.mqttTopic}" else "off",
            "wled=" + if (p.isWledConfigured) p.wledDevicesRaw else "off",
            "pulse=" + p.pulseEnabled,
            "audio=" + p.audioSourceKey + " (" + (s.audio?.let { if (it == "peer") "${s.audioPeerName}'s mic" else it } ?: "not listening") + ")",
            "ha_beats=" + p.haBeats,
            "link=" + if (!p.linkEnabled) "off" else s.peers.joinToString(",").ifEmpty { "no other devices" },
            "wled_driver=" + (s.renderer ?: "-"),
            "enabled=" + p.enabled,
            "now=" + s.nowPlaying,
            "last=" + s.lastResult,
            "note=" + (s.note ?: "-"),
        ).joinToString(" | ")
    }
}
