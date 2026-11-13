package com.musiclightsynk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Where the Energy effect and the beat pulse get their audio from. */
enum class AudioSource(val key: String, val label: String) {
    AUTO("auto", "Auto (device audio, else mic)"),
    OUTPUT("output", "Device audio"),
    MIC("mic", "Microphone"),
    OFF("off", "Off");

    fun next(): AudioSource = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): AudioSource = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * Listens to the music and turns it into [AudioLevels] (lows / mids / highs + beat).
 *
 *  - Device audio: Android's Visualizer on the output mix (session 0), i.e. what this device
 *    plays. Works on Fire TV. HDMI passthrough (Dolby/DTS bitstream to a soundbar) bypasses the
 *    mixer, and some phones hand it silence.
 *  - Microphone: a phone near the speakers. In the background this only works while
 *    [ListenerService] (a microphone foreground service) runs.
 *  - Auto: device audio first; if that's unavailable or silent for a few seconds, the mic
 *    (when there is one and it's allowed).
 *
 * [start] / [stop] on the main thread. [onLevels] is called on the main thread (device audio)
 * or the mic thread, 20-45 times a second, with silent = true while nothing is heard.
 */
class AudioEngine(
    private val onLevels: (levels: AudioLevels, silent: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var requested = AudioSource.OFF
    private var viz: Visualizer? = null

    @Volatile
    private var micRunning = false
    private var micThread: Thread? = null

    private val meterLock = Any()
    private var meter = EnergyMeter(EnergyMeter.DEFAULT_MIN_PEAK)

    @Volatile
    private var lastSound = 0L
    private var startedAt = 0L
    private var silenceReported = false
    private var failedSource: AudioSource? = null
    private var failedAt = 0L
    private var lastStatus: String? = null

    /** "output", "mic" or null when not listening. */
    @Volatile
    var active: String? = null
        private set

    @Volatile
    var sensitivity = 1f
        set(v) {
            field = v.coerceIn(0.1f, 3f)
            synchronized(meterLock) { meter.sensitivity = field }
        }

    val isRunning: Boolean get() = active != null

    /** True when something other than silence was heard in the last [ms] milliseconds. */
    fun hearsSound(ms: Long = 3000): Boolean = isRunning && SystemClock.elapsedRealtime() - lastSound < ms

    /** Main thread. Same source again is a no-op. */
    fun start(context: Context, source: AudioSource) {
        if (source == AudioSource.OFF) {
            stop()
            return
        }
        if (source == requested && isRunning) return
        val now = SystemClock.elapsedRealtime()
        if (source == failedSource && now - failedAt < RETRY_MS) return // don't hammer a source that just failed
        stop()
        ctx = context.applicationContext
        requested = source
        startedAt = SystemClock.elapsedRealtime()
        lastSound = startedAt
        silenceReported = false
        val ok = when (source) {
            AudioSource.MIC -> startMic(context)
            AudioSource.OUTPUT -> startOutput(context)
            else -> startOutput(context) || startMic(context)
        }
        if (ok) {
            failedSource = null
            main.postDelayed(watchdog, WATCHDOG_MS)
        } else {
            requested = AudioSource.OFF
            failedSource = source
            failedAt = now
        }
    }

    private fun report(msg: String) {
        if (msg == lastStatus) return
        lastStatus = msg
        onStatus(msg)
    }

    fun stop() {
        main.removeCallbacks(watchdog)
        stopOutput()
        stopMic()
        requested = AudioSource.OFF
        active = null
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val c = ctx ?: return
            if (!isRunning) return
            val now = SystemClock.elapsedRealtime()
            val quietFor = now - lastSound
            if (requested == AudioSource.AUTO && active == SRC_OUTPUT && quietFor > AUTO_SWITCH_MS && micAllowed(c)) {
                Log.i(TAG, "Device audio is silent; listening with the microphone instead")
                stopOutput()
                if (!startMic(c)) {
                    startOutput(c) // no luck with the mic either: keep trying the output
                } else {
                    lastSound = now
                }
            } else if (!silenceReported && quietFor > SILENCE_REPORT_MS) {
                silenceReported = true
                report(
                    if (active == SRC_MIC) "Microphone hears silence (blocked in the background, or too far from the speakers)"
                    else "Device audio is silent: this app blocks audio analysis, or HDMI passthrough is on",
                )
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun deliver(e: BandEnergies) {
        val now = SystemClock.elapsedRealtime()
        if (e.total > EnergyMeter.DEFAULT_MIN_PEAK * 0.3f) lastSound = now
        val (levels, silent) = synchronized(meterLock) { meter.update(e, now) to meter.isSilent }
        onLevels(levels, silent)
    }

    private fun freshMeter() = synchronized(meterLock) {
        meter = EnergyMeter(EnergyMeter.DEFAULT_MIN_PEAK, sensitivity)
    }

    // ---- device audio (Visualizer) -----------------------------------------------------------

    private fun startOutput(c: Context): Boolean {
        if (!hasPermission(c)) {
            report("Audio needs the microphone/audio permission (button in the app, or adb shell pm grant)")
            return false
        }
        return try {
            freshMeter()
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = min(1024, Visualizer.getCaptureSizeRange()[1])
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform != null && viz != null) deliver(Bands.fromWaveform(waveform, samplingRate))
                }

                override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            viz = v
            active = SRC_OUTPUT
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Visualizer unavailable", e)
            stopOutput()
            if (requested == AudioSource.OUTPUT) report("Device audio unavailable: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun stopOutput() {
        val v = viz ?: return
        viz = null
        if (active == SRC_OUTPUT) active = null
        try {
            v.enabled = false
        } catch (_: Exception) {
        }
        try {
            v.release()
        } catch (_: Exception) {
        }
    }

    // ---- microphone ----------------------------------------------------------------------------

    private fun micAllowed(c: Context) =
        c.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && hasPermission(c)

    @SuppressLint("MissingPermission") // checked in micAllowed()
    private fun startMic(c: Context): Boolean {
        if (!micAllowed(c)) {
            if (requested == AudioSource.MIC) report("No microphone, or the microphone permission is off")
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(micSource(c), MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, MIC_N * 4))
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord unavailable", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        freshMeter()
        micRunning = true
        active = SRC_MIC
        micThread = thread(name = "mls-mic", isDaemon = true) {
            val buf = ShortArray(MIC_N)
            try {
                while (micRunning) {
                    var read = 0
                    while (read < MIC_N && micRunning) {
                        val r = rec.read(buf, read, MIC_N - read)
                        if (r <= 0) break
                        read += r
                    }
                    if (read < MIC_N) {
                        if (micRunning) SystemClock.sleep(50)
                        continue
                    }
                    deliver(Bands.fromPcm16(buf, MIC_RATE))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Microphone stopped", e)
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }
        }
        return true
    }

    /** Unprocessed audio (no voice filters / auto-gain) where the device offers it. */
    private fun micSource(c: Context): Int {
        if (Build.VERSION.SDK_INT >= 24) {
            val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                return MediaRecorder.AudioSource.UNPROCESSED
            }
        }
        return MediaRecorder.AudioSource.MIC
    }

    private fun stopMic() {
        micRunning = false
        micThread = null
        if (active == SRC_MIC) active = null
    }

    private fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MusicLightSynk"
        const val SRC_OUTPUT = "output"
        const val SRC_MIC = "mic"
        private const val MIC_RATE = 44100
        private const val MIC_N = 1024
        private const val WATCHDOG_MS = 1000L
        private const val AUTO_SWITCH_MS = 4000L
        private const val SILENCE_REPORT_MS = 8000L
        private const val RETRY_MS = 30_000L
    }
}
