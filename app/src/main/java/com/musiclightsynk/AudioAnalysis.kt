package com.musiclightsynk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Audio -> three band levels (lows / mids / highs, 0..1) for the Energy effect.
 * Pure Kotlin, no Android: the same code runs for the TV's output (Visualizer FFT), a phone
 * microphone (PCM), and levels received from another device over the link. Unit tested.
 */

/** Loudness of the three bands after auto-gain, each 0..1, plus a kick-drum beat flag. */
data class AudioLevels(val lows: Float, val mids: Float, val highs: Float, val beat: Boolean = false) {
    val overall: Float get() = max(lows, max(mids, highs))

    companion object {
        val SILENT = AudioLevels(0f, 0f, 0f)
    }
}

/** Raw band energies (mean spectral magnitude per band), before any normalisation. */
data class BandEnergies(val lows: Float, val mids: Float, val highs: Float) {
    val total: Float get() = lows + mids + highs
}

object Bands {
    const val LOW_HZ = 20f
    const val MID_HZ = 250f
    const val HIGH_HZ = 2000f
    const val TOP_HZ = 8000f

    /** From a magnitude spectrum where bin k is at k * sampleRate / fftSize. */
    fun fromMagnitudes(mags: FloatArray, sampleRateHz: Float, fftSize: Int): BandEnergies {
        val binHz = sampleRateHz / fftSize
        fun mean(fromHz: Float, toHz: Float): Float {
            val lo = max(1, (fromHz / binHz).roundToInt())
            val hi = min(mags.size - 1, (toHz / binHz).roundToInt())
            if (hi < lo) return 0f
            var s = 0f
            for (k in lo..hi) s += mags[k]
            return s / (hi - lo + 1)
        }
        return BandEnergies(mean(LOW_HZ, MID_HZ), mean(MID_HZ, HIGH_HZ), mean(HIGH_HZ, TOP_HZ))
    }

    /**
     * From an Android Visualizer waveform (unsigned 8-bit PCM, power-of-two length). We run our
     * own FFT on it: the Visualizer's 8-bit FFT output rounds quiet treble down to zero.
     */
    fun fromWaveform(wave: ByteArray, samplingRateMilliHz: Int): BandEnergies {
        val n = wave.size
        if (n < 64 || (n and (n - 1)) != 0 || samplingRateMilliHz <= 0) return BandEnergies(0f, 0f, 0f)
        val s = FloatArray(n) { ((wave[it].toInt() and 0xFF) - 128) / 128f }
        return fromMagnitudes(Fft.magnitudes(s), samplingRateMilliHz / 1000f, n)
    }

    /** From 16-bit microphone samples (power-of-two count). */
    fun fromPcm16(pcm: ShortArray, sampleRateHz: Int): BandEnergies {
        val n = pcm.size
        if (n < 64 || (n and (n - 1)) != 0 || sampleRateHz <= 0) return BandEnergies(0f, 0f, 0f)
        val s = FloatArray(n) { pcm[it] / 32768f }
        return fromMagnitudes(Fft.magnitudes(s), sampleRateHz.toFloat(), n)
    }
}

object Fft {
    /** In-place iterative radix-2 FFT. [re] and [im] must have the same power-of-two length. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Hann-windowed magnitude spectrum (n/2 bins) of real samples in -1..1. Scaled so a
     * full-scale sine gives about 0.5 in its bin.
     */
    fun magnitudes(samples: FloatArray): FloatArray {
        val n = samples.size
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) {
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat()
            re[i] = samples[i] * w
        }
        transform(re, im)
        val out = FloatArray(n / 2)
        val scale = 2f / n
        for (k in 0 until n / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        return out
    }
}

/**
 * Turns raw band energies into lively 0..1 levels:
 *  - auto-gain per band (a slowly decaying peak), so quiet and loud sources both use the full range,
 *  - a slowly rising floor, so transients (kicks, snares) jump above steady sound,
 *  - fast attack / slower decay, so bars snap up and fall back smoothly,
 *  - a noise gate ([minPeak]) so silence stays dark instead of amplifying hiss.
 *
 * [minPeak] is the quietest band energy treated as sound ([DEFAULT_MIN_PEAK] suits both the
 * Visualizer waveform and a phone mic, which share the same FFT scale).
 */
class EnergyMeter(
    private val minPeak: Float,
    var sensitivity: Float = 1f,
    private val peakTauMs: Float = 4000f,
    private val floorTauMs: Float = 2500f,
    private val attack: Float = 0.65f,
    private val decayTauMs: Float = 140f,
) {
    private val peak = FloatArray(3) { minPeak }
    private val floor = FloatArray(3)
    private val smooth = FloatArray(3)
    private var lastMs = Long.MIN_VALUE
    private var quietSince = Long.MIN_VALUE
    private val beats = BeatDetector(minEnergy = minPeak * 0.5f)

    /** True after ~1.5 s of (near) silence. */
    var isSilent = true
        private set

    fun update(e: BandEnergies, nowMs: Long): AudioLevels {
        val dt = if (lastMs == Long.MIN_VALUE) 25f else (nowMs - lastMs).coerceIn(1, 1000).toFloat()
        lastMs = nowMs
        val raw = floatArrayOf(e.lows, e.mids, e.highs)
        val out = FloatArray(3)
        for (b in 0..2) {
            val v = raw[b].coerceAtLeast(0f)
            peak[b] = max(max(v, peak[b] * exp(-dt / peakTauMs)), minPeak)
            floor[b] = if (v < floor[b]) v else floor[b] + (v - floor[b]) * (1f - exp(-dt / floorTauMs))
            val absolute = v / peak[b]
            val transient = ((v - floor[b]) / (peak[b] - floor[b] + 1e-9f)).coerceIn(0f, 1f)
            val level = ((0.4f * absolute + 0.6f * transient) * sensitivity).coerceIn(0f, 1f)
            smooth[b] = if (level > smooth[b]) {
                smooth[b] + (level - smooth[b]) * attack
            } else {
                smooth[b] + (level - smooth[b]) * (1f - exp(-dt / decayTauMs))
            }
            out[b] = smooth[b]
        }
        val loud = e.total > minPeak * 0.3f
        if (loud) quietSince = Long.MIN_VALUE else if (quietSince == Long.MIN_VALUE) quietSince = nowMs
        isSilent = quietSince != Long.MIN_VALUE && nowMs - quietSince > 1500
        val beat = beats.feed(e.lows, nowMs)
        if (isSilent) return AudioLevels(0f, 0f, 0f)
        return AudioLevels(out[0], out[1], out[2], beat)
    }

    companion object {
        const val DEFAULT_MIN_PEAK = 0.002f
    }
}
