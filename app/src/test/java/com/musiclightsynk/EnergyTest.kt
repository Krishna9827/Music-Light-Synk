package com.musiclightsynk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EnergyTest {

    private fun sine(freq: Float, amp: Float = 0.5f, n: Int = 1024, sr: Float = 44100f) =
        FloatArray(n) { i -> (amp * sin(2 * PI * freq * i / sr)).toFloat() }

    // ---- FFT / bands ---------------------------------------------------------------------

    @Test
    fun fftFindsTheRightBin() {
        val mags = Fft.magnitudes(sine(1000f, 1f))
        val peakBin = mags.indices.maxByOrNull { mags[it] }!!
        assertEquals(1000f, peakBin * 44100f / 1024, 44100f / 1024) // within one bin
        assertTrue("full-scale sine ~0.5, was ${mags[peakBin]}", mags[peakBin] in 0.35f..0.6f)
    }

    @Test
    fun bandsSeparateBassMidsAndTreble() {
        fun bands(f: Float) = Bands.fromMagnitudes(Fft.magnitudes(sine(f)), 44100f, 1024)
        val bass = bands(90f)
        assertTrue("$bass", bass.lows > bass.mids * 5 && bass.lows > bass.highs * 5)
        val mid = bands(900f)
        assertTrue("$mid", mid.mids > mid.lows * 3 && mid.mids > mid.highs * 3)
        val high = bands(5000f)
        assertTrue("$high", high.highs > high.lows * 3 && high.highs > high.mids * 3)
    }

    @Test
    fun visualizerWaveformAndMicSamplesMapToTheSameBands() {
        val wave = ByteArray(1024) { i -> (128 + 100 * sin(2 * PI * 100 * i / 44100.0)).toInt().toByte() }
        val w = Bands.fromWaveform(wave, 44_100_000)
        assertTrue("$w", w.lows > w.mids * 5 && w.lows > w.highs * 5)
        val pcm = ShortArray(1024) { i -> (12000 * sin(2 * PI * 4000 * i / 44100.0)).toInt().toShort() }
        val m = Bands.fromPcm16(pcm, 44100)
        assertTrue("$m", m.highs > m.lows * 5 && m.highs > m.mids * 5)
        assertEquals(0f, Bands.fromWaveform(ByteArray(1000), 44_100_000).total, 0f) // not a power of two
    }

    // ---- EnergyMeter -------------------------------------------------------------------------

    @Test
    fun meterBouncesOnKicksAndFallsBetweenThem() {
        val m = EnergyMeter(minPeak = 0.01f)
        var t = 0L
        val ups = ArrayList<Float>()
        val downs = ArrayList<Float>()
        repeat(400) { frame ->
            t += 25
            val kick = frame % 20 < 2 // 2 frames of kick every 0.5 s
            val e = BandEnergies(if (kick) 0.4f else 0.05f, 0.08f, 0.03f)
            val l = m.update(e, t)
            if (frame > 100) (if (kick) ups else downs) += l.lows
        }
        assertTrue("kicks push the bar high: ${ups.average()}", ups.average() > 0.6)
        assertTrue("and it falls back between kicks: ${downs.average()}", downs.average() < ups.average() * 0.6)
        assertFalse(m.isSilent)
    }

    @Test
    fun quietSourcesStillUseTheFullRangeButSilenceStaysDark() {
        val m = EnergyMeter(minPeak = 0.003f)
        var t = 0L
        var maxLevel = 0f
        repeat(400) { frame ->
            t += 25
            val kick = frame % 20 < 2
            maxLevel = maxOf(maxLevel, m.update(BandEnergies(if (kick) 0.02f else 0.004f, 0.005f, 0.002f), t).lows)
        }
        assertTrue("a quiet mic still reaches high levels: $maxLevel", maxLevel > 0.6f)
        var last = AudioLevels.SILENT
        repeat(100) { t += 25; last = m.update(BandEnergies(0f, 0f, 0f), t) }
        assertTrue(m.isSilent)
        assertEquals(0f, last.overall, 0f)
    }

    @Test
    fun sensitivityScalesTheBars() {
        fun run(sens: Float): Float {
            val m = EnergyMeter(minPeak = 0.01f, sensitivity = sens)
            var t = 0L
            var sum = 0f
            repeat(200) { f -> t += 25; sum += m.update(BandEnergies(0.1f + 0.05f * sin(f * 0.3f), 0.05f, 0.02f), t).lows }
            return sum
        }
        assertTrue(run(0.5f) < run(1f))
    }

    // ---- Energy effect -----------------------------------------------------------------------

    @Test
    fun axisDirectionsMatchTheFourModes() {
        val n = 10
        // plain: LED 0 is the start
        assertTrue(EnergyEffect.axis(0, n, flip = false, mirror = false) < 0.1f)
        assertTrue(EnergyEffect.axis(9, n, flip = false, mirror = false) > 0.9f)
        // flip: last LED is the start
        assertTrue(EnergyEffect.axis(9, n, flip = true, mirror = false) < 0.1f)
        // mirror (LedFx): from the middle outward
        assertTrue(EnergyEffect.axis(4, n, false, true) < 0.15f && EnergyEffect.axis(5, n, false, true) < 0.15f)
        assertTrue(EnergyEffect.axis(0, n, false, true) > 0.85f && EnergyEffect.axis(9, n, false, true) > 0.85f)
        // mirror + flip (LedFx): both ends are the start, the middle is the far end
        assertTrue(EnergyEffect.axis(0, n, true, true) < 0.15f && EnergyEffect.axis(9, n, true, true) < 0.15f)
        assertTrue(EnergyEffect.axis(4, n, true, true) > 0.85f)
    }

    private fun lit(frame: IntArray) = frame.map { it != 0 }

    @Test
    fun barGrowsFromTheRightPlace() {
        val red = 0xFF0000
        val base = IntArray(20) { red }
        val half = AudioLevels(0.5f, 0f, 0f)
        val plain = lit(EnergyEffect.render(base, null, half, WledEffect(true), bg = 0f, blur = 0))
        assertTrue(plain.subList(0, 9).all { it })   // first half lit
        assertTrue(plain.subList(12, 20).none { it }) // far end dark
        val flipped = lit(EnergyEffect.render(base, null, half, WledEffect(true, flip = true), 0f, 0))
        assertTrue(flipped.subList(11, 20).all { it } && flipped.subList(0, 8).none { it })
        val bothEnds = lit(EnergyEffect.render(base, null, half, WledEffect(true, flip = true, mirror = true), 0f, 0))
        assertTrue("ends lit: $bothEnds", bothEnds[0] && bothEnds[19] && bothEnds[3] && bothEnds[16])
        assertFalse("middle dark", bothEnds[9] || bothEnds[10])
        val centre = lit(EnergyEffect.render(base, null, half, WledEffect(true, mirror = true), 0f, 0))
        assertTrue("middle lit: $centre", centre[9] && centre[10])
        assertFalse("ends dark", centre[0] || centre[19])
    }

    @Test
    fun silenceShowsOnlyTheDimBackground() {
        val base = IntArray(10) { 0x804020 }
        val f = EnergyEffect.render(base, null, AudioLevels.SILENT, WledEffect(true), bg = 0.1f, blur = 0)
        assertTrue(f.joinToString { Integer.toHexString(it) }, f.all { it == ((13 shl 16) or (6 shl 8) or 3) })
    }

    @Test
    fun bandColoursKeepTheirHueWhereOnlyOneBandReaches() {
        val bands = intArrayOf(0x0000FF, 0x00FF00, 0xFFFF00) // lows blue, mids green, highs yellow
        val levels = AudioLevels(lows = 0.9f, mids = 0.5f, highs = 0.2f)
        val f = EnergyEffect.render(IntArray(20) { 0 }, bands, levels, WledEffect(true), bg = 0f, blur = 0)
        assertEquals(0x0000FF, f[15]) // only lows reach 75 %
        assertEquals(0xFFFF00 or 0x0000FF, f[1]) // all three overlap near the start (lighten blend)
    }

    @Test
    fun bandColoursComeFromTheArtwork() {
        val p = ColorExtractor.testPalette()
        val bands = EnergyEffect.bandColours(p, Slot.LEFT) { it.light }
        assertEquals(EnergyEffect.packRgb(p[Slot.LEFT].light), bands[0])
        assertEquals(3, bands.toSet().size) // three different colours
    }
}
