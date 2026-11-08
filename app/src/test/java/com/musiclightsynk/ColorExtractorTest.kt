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
    fun nineZonesMapToCornersSidesAndMiddle() {
        // 60x60 image as a 3x3 grid of 20 px cells; a 1/3 band makes each slot exactly one cell.
        val cells = arrayOf(
            arrayOf(Rgb(230, 30, 30), Rgb(250, 140, 0), Rgb(250, 220, 50)),
            arrayOf(Rgb(215, 25, 95), Rgb(140, 35, 170), Rgb(60, 160, 70)),
            arrayOf(Rgb(55, 70, 170), Rgb(30, 135, 230), Rgb(0, 170, 190)),
        )
        val px = image(60, 60) { x, y -> cells[y / 20][x / 20].let { argb(it.r, it.g, it.b) } }
        val p = ColorExtractor.extract(px, 60, 60, ExtractOptions(cornerFraction = 1f / 3f))
        near(cells[0][0], p[Slot.TOP_LEFT].raw)
        near(cells[0][1], p[Slot.TOP].raw)
        near(cells[0][2], p[Slot.TOP_RIGHT].raw)
        near(cells[1][0], p[Slot.LEFT].raw)
        near(cells[1][2], p[Slot.RIGHT].raw)
        near(cells[2][0], p[Slot.BOTTOM_LEFT].raw)
        near(cells[2][1], p[Slot.BOTTOM].raw)
        near(cells[2][2], p[Slot.BOTTOM_RIGHT].raw)
    }

    @Test
    fun sidesFallBackToWholeEdgeWhenBandIsHalfTheImage() {
        // Top half red, bottom half blue; with a 50 % band there is no "middle" of an edge.
        val px = image(32, 32) { _, y -> if (y < 16) argb(220, 20, 20) else argb(20, 40, 220) }
        val p = ColorExtractor.extract(px, 32, 32, ExtractOptions(cornerFraction = 0.5f))
        near(Rgb(220, 20, 20), p[Slot.TOP].raw)
        near(Rgb(20, 40, 220), p[Slot.BOTTOM].raw)
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
    fun testPaletteHasEightDistinctEdgeColoursAndPurpleMiddle() {
        for (corner in listOf(0.15f, 0.25f, 0.4f)) {
            val p = ColorExtractor.testPalette(ExtractOptions(cornerFraction = corner))
            val edges = listOf(
                Slot.TOP_LEFT, Slot.TOP, Slot.TOP_RIGHT, Slot.LEFT,
                Slot.RIGHT, Slot.BOTTOM_LEFT, Slot.BOTTOM, Slot.BOTTOM_RIGHT,
            ).map { p[it].light.hex }
            assertEquals("corner=$corner $edges", 8, edges.toSet().size)
            // With thick bands the middle is smaller than a corner, so only check normal sizes.
            if (corner <= 0.25f) near(Rgb(0x8E, 0x24, 0xAA), p[Slot.DOMINANT].raw)
        }
    }
}
