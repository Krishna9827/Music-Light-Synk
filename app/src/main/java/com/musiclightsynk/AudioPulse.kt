package com.musiclightsynk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log

/**
 * Beat detection from the device's audio output using Android's Visualizer on the global
 * output mix (session 0). Needs RECORD_AUDIO (grant via the app or `adb shell pm grant`).
 *
 * Caveats: session 0 is deprecated and some devices hand it silence; HDMI passthrough
 * (Dolby/DTS bitstream to a soundbar) bypasses the mixer, so there is nothing to analyse.
 * We detect silence and report it rather than pretending.
 *
 * Must be started/stopped on the main thread (Visualizer callbacks use the caller's looper).
 */
class AudioPulse(
    private val onBeat: () -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var viz: Visualizer? = null
    private val detector = BeatDetector()
    private var startedAt = 0L
    private var lastNonSilent = 0L
    private var silenceReported = false

    val isRunning: Boolean get() = viz != null

    fun start(ctx: Context): Boolean {
        if (viz != null) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Beat pulse needs audio permission (button in the app, or adb shell pm grant)")
            return false
        }
        return try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    val now = SystemClock.elapsedRealtime()
                    val e = BeatDetector.bassEnergy(fft, samplingRate)
                    if (e > 0f) lastNonSilent = now
                    if (detector.feed(e, now)) onBeat()
                    if (!silenceReported && now - startedAt > 6000 && now - lastNonSilent > 6000) {
                        silenceReported = true
                        onStatus("Beat pulse hears silence: this device/app blocks audio analysis (or HDMI passthrough is on)")
                    }
                }
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            viz = v
            startedAt = SystemClock.elapsedRealtime()
            lastNonSilent = startedAt
            silenceReported = false
            true
        } catch (e: Throwable) {
            Log.w("MusicLightSynk", "Visualizer unavailable", e)
            onStatus("Beat pulse unavailable on this device: ${e.message ?: e.javaClass.simpleName}")
            stop()
            false
        }
    }

    fun stop() {
        val v = viz ?: return
        viz = null
        try {
            v.enabled = false
        } catch (_: Exception) {
        }
        try {
            v.release()
        } catch (_: Exception) {
        }
    }
}
