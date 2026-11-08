package com.musiclightsynk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorExtractorTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun image(w: Int, h: Int, fn: (x: Int, y: Int) -> Int) =
        IntArray(w * h) { i -> fn(i % w, i / w) }

    private fun near(expected: Rgb, actual: Rgb, tol: Int = 12) {
        assertTrue("expected ~$expected but was $actual", expected.distanceTo(actual) <= tol)
    }

    @Test
    fun solidColourEverywhere() {
        val px = image(64, 64) { _, _ -> argb(200, 30, 30) }
        val p = ColorExtractor.extract(px, 64, 64)
        for (slot in Slot.values()) near(Rgb(200, 30, 30), p[slot].raw)
        // Bulb colour is full-value red
        assertEquals(255, p[Slot.TOP_LEFT].light.r)
        assertTrue(p[Slot.TOP_LEFT].light.g < 40)
        assertTrue(abs(p[Slot.TOP_LEFT].hue - 0f) < 1f)
        assertEquals(100f, p[Slot.TOP_LEFT].saturation, 0.5f)
    }

    @Test
    fun quadrantsMapToCorners() {
        val red = Rgb(220, 20, 20)
        val green = Rgb(20, 200, 40)
        val blue = Rgb(20, 40, 220)
        val yellow = Rgb(240, 200, 10)
        val px = image(64, 64) { x, y ->
            val c = when {
                x < 32 && y < 32 -> red
                y < 32 -> green
                x < 32 -> blue
                else -> yellow
            }
            argb(c.r, c.g, c.b)
        }
        val p = ColorExtractor.extract(px, 64, 64)
        near(red, p[Slot.TOP_LEFT].raw)
        near(green, p[Slot.TOP_RIGHT].raw)
        near(blue, p[Slot.BOTTOM_LEFT].raw)
        near(yellow, p[Slot.BOTTOM_RIGHT].raw)
    }

    @Test
    fun horizontalGradientCornersFollowTheEdges() {
        // Blue on the left fading to orange on the right - a typical "gradient" cover.
        val px = image(64, 64) { x, _ ->
            val t = x / 63f
            argb((20 + 220 * t).toInt(), (40 + 100 * t).toInt(), (200 - 180 * t).toInt())
        }
        val p = ColorExtractor.extract(px, 64, 64)
        val tl = p[Slot.TOP_LEFT].raw
        val tr = p[Slot.TOP_RIGHT].raw
        assertTrue("left should be blue: $tl", tl.b > tl.r)
        assertTrue("right should be orange: $tr", tr.r > tr.b)
        near(tl, p[Slot.BOTTOM_LEFT].raw, 4)
        near(tr, p[Slot.BOTTOM_RIGHT].raw, 4)
    }

    @Test
    fun smallLogoInCornerDoesNotWin() {
        // Mostly purple corner with a small white logo inside it.
        val px = image(64, 64) { x, y -> if (x in 2..7 && y in 2..5) argb(255, 255, 255) else argb(90, 30, 140) }
        val p = ColorExtractor.extract(px, 64, 64)
        near(Rgb(90, 30, 140), p[Slot.TOP_LEFT].raw)
    }

    @Test
    fun blackGivesDimWhiteNotRandomHue() {
        val px = image(32, 32) { _, _ -> argb(6, 3, 2) }
        val opts = ExtractOptions(minBrightness = 0.15f)
        val p = ColorExtractor.extract(px, 32, 32, opts)
        val c = p[Slot.TOP_LEFT]
        assertEquals(0f, c.saturation, 0.01f)
        assertTrue("brightness ${c.brightness}", c.brightness in 38..45)
        assertTrue(p.isBlank)
    }

    @Test
    fun darkButColouredStaysColoured() {
        val px = image(32, 32) { _, _ -> argb(10, 20, 80) } // deep navy
        val p = ColorExtractor.extract(px, 32, 32)
        val c = p[Slot.DOMINANT]
        assertTrue("hue ${c.hue}", c.hue in 220f..240f)
        assertTrue(c.saturation > 80f)
        assertTrue("should be dim: ${c.brightness}", c.brightness < 110)
        assertFalse(p.isBlank)
    }

    @Test
    fun vibrantFindsTheColourfulPatch() {
        // Grey cover with an orange stripe covering ~10% of the image.
        val px = image(64, 64) { _, y -> if (y in 30..36) argb(250, 120, 10) else argb(120, 120, 120) }
        val p = ColorExtractor.extract(px, 64, 64)
        near(Rgb(120, 120, 120), p[Slot.DOMINANT].raw)
        near(Rgb(250, 120, 10), p[Slot.VIBRANT].raw)
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val px = image(32, 32) { x, _ -> if (x < 4) argb(0, 0, 0, 0) else argb(0, 150, 150) }
        val p = ColorExtractor.extract(px, 32, 32, ExtractOptions(cornerFraction = 0.25f))
        near(Rgb(0, 150, 150), p[Slot.TOP_LEFT].raw)
    }

    @Test
    fun hsvRoundTrip() {
        for (c in listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 179, 0), Rgb(30, 136, 229))) {
            val hsv = ColorExtractor.rgbToHsv(c)
            near(c, ColorExtractor.hsvToRgb(hsv[0], hsv[1], hsv[2]), 2)
        }
    }

    @Test
    fun paletteDistance() {
        val a = ColorExtractor.testPalette()
        val b = ColorExtractor.testPalette()
        assertEquals(0, a.maxDistanceTo(b))
        assertEquals(Int.MAX_VALUE, a.maxDistanceTo(null))
        val red = ColorExtractor.extract(image(8, 8) { _, _ -> argb(255, 0, 0) }, 8, 8)
        assertTrue(a.maxDistanceTo(red) > 100)
    }

    @Test
    fun testPaletteHasFourDistinctCorners() {
        val p = ColorExtractor.testPalette()
        val corners = listOf(Slot.TOP_LEFT, Slot.TOP_RIGHT, Slot.BOTTOM_LEFT, Slot.BOTTOM_RIGHT).map { p[it].light.hex }
        assertEquals(4, corners.toSet().size)
    }
}
