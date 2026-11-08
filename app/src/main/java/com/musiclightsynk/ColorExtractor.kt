package com.musiclightsynk

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-Kotlin colour extraction (no Android classes) so it can be unit tested on the JVM.
 *
 * Input is an ARGB int array (the format of Bitmap.getPixels). The image is expected to be
 * small already (~64 px on the long side); callers downscale before calling [extract].
 */

data class Rgb(val r: Int, val g: Int, val b: Int) {
    val hex: String get() = String.format(Locale.US, "#%02X%02X%02X", r, g, b)

    fun distanceTo(o: Rgb): Int {
        val dr = r - o.r
        val dg = g - o.g
        val db = b - o.b
        return sqrt((dr * dr + dg * dg + db * db).toDouble()).roundToInt()
    }

    /** Relative luminance 0..1 (sRGB coefficients, no gamma - good enough for UI contrast). */
    val luminance: Float get() = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

    companion object {
        fun of(argb: Int) = Rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/**
 * One colour ready for a smart bulb.
 *
 * [raw] is what the image really looks like. [light] is the same hue at full value with a
 * saturation boost, because bulbs look washed out when fed the raw (often dark) pixel colour.
 * Darkness is moved into [brightness] instead.
 */
data class LightColor(
    val raw: Rgb,
    val light: Rgb,
    /** Hue in degrees, 0..360 (Home Assistant hs_color[0]). */
    val hue: Float,
    /** Saturation in percent, 0..100 (Home Assistant hs_color[1]). */
    val saturation: Float,
    /** Suggested bulb brightness 1..255. */
    val brightness: Int,
) {
    val brightnessPct: Int get() = (brightness * 100f / 255f).roundToInt().coerceIn(1, 100)
}

enum class Slot(val key: String, val label: String) {
    TOP_LEFT("top_left", "Top left"),
    TOP("top", "Top"),
    TOP_RIGHT("top_right", "Top right"),
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    BOTTOM_LEFT("bottom_left", "Bottom left"),
    BOTTOM("bottom", "Bottom"),
    BOTTOM_RIGHT("bottom_right", "Bottom right"),
    DOMINANT("dominant", "Dominant"),
    VIBRANT("vibrant", "Vibrant"),
    AVERAGE("average", "Average"),
}

class Palette(private val colors: Map<Slot, LightColor>) {
    operator fun get(slot: Slot): LightColor = colors.getValue(slot)

    /** Largest raw-colour distance between matching slots; Int.MAX_VALUE if [other] is null. */
    fun maxDistanceTo(other: Palette?): Int {
        if (other == null) return Int.MAX_VALUE
        return Slot.values().maxOf { this[it].raw.distanceTo(other[it].raw) }
    }

    /** True when the whole frame is (nearly) black, e.g. a DRM-protected video surface. */
    val isBlank: Boolean
        get() = Slot.values().all { s -> this[s].raw.let { max(it.r, max(it.g, it.b)) < 10 } }

    override fun toString(): String =
        Slot.values().joinToString(", ") { "${it.key}=${this[it].light.hex}" }
}

data class ExtractOptions(
    /**
     * Depth of the edge band, as a fraction of width/height (0.05..0.5). Corners are
     * cornerFraction x cornerFraction squares; the 4 sides are the parts of the band between them.
     */
    val cornerFraction: Float = 0.25f,
    /** Multiplier applied to saturation of the bulb colour (1.0 = unchanged). */
    val saturationBoost: Float = 1.3f,
    /** Brightness used for a fully black patch, 0..1. Brightness scales linearly up to 1.0. */
    val minBrightness: Float = 0.15f,
)

object ColorExtractor {

    /** Below this chroma (max-min channel, 0..1) a colour is treated as grey/white. */
    private const val GREY_CHROMA = 0.07f

    fun extract(pixels: IntArray, width: Int, height: Int, opts: ExtractOptions = ExtractOptions()): Palette {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size >= width * height) { "pixel array too small" }

        val f = opts.cornerFraction.coerceIn(0.05f, 0.5f)
        val cw = max(1, (width * f).roundToInt())
        val ch = max(1, (height * f).roundToInt())

        // Middle part of each edge, between the corners. With a 50 % band there is nothing
        // between the corners, so the side falls back to the whole edge.
        var sx0 = cw
        var sx1 = width - cw
        if (sx1 <= sx0) {
            sx0 = 0
            sx1 = width
        }
        var sy0 = ch
        var sy1 = height - ch
        if (sy1 <= sy0) {
            sy0 = 0
            sy1 = height
        }

        val tl = Histogram.of(pixels, width, 0, 0, cw, ch)
        val tr = Histogram.of(pixels, width, width - cw, 0, width, ch)
        val bl = Histogram.of(pixels, width, 0, height - ch, cw, height)
        val br = Histogram.of(pixels, width, width - cw, height - ch, width, height)
        val top = Histogram.of(pixels, width, sx0, 0, sx1, ch)
        val bottom = Histogram.of(pixels, width, sx0, height - ch, sx1, height)
        val left = Histogram.of(pixels, width, 0, sy0, cw, sy1)
        val right = Histogram.of(pixels, width, width - cw, sy0, width, sy1)
        val all = Histogram.of(pixels, width, 0, 0, width, height)

        val dominant = all.dominant()
        val map = linkedMapOf(
            Slot.TOP_LEFT to toLight(tl.dominant(), opts),
            Slot.TOP to toLight(top.dominant(), opts),
            Slot.TOP_RIGHT to toLight(tr.dominant(), opts),
            Slot.LEFT to toLight(left.dominant(), opts),
            Slot.RIGHT to toLight(right.dominant(), opts),
            Slot.BOTTOM_LEFT to toLight(bl.dominant(), opts),
            Slot.BOTTOM to toLight(bottom.dominant(), opts),
            Slot.BOTTOM_RIGHT to toLight(br.dominant(), opts),
            Slot.DOMINANT to toLight(dominant, opts),
            Slot.VIBRANT to toLight(all.vibrant() ?: dominant, opts),
            Slot.AVERAGE to toLight(all.average(), opts),
        )
        return Palette(map)
    }

    /**
     * Fixed test image used by the "send test" buttons: a rainbow around the edge
     * (each corner and side a different colour) and purple in the middle.
     */
    fun testPalette(opts: ExtractOptions = ExtractOptions()): Palette {
        val n = 40
        val band = (n * opts.cornerFraction.coerceIn(0.05f, 0.5f)).roundToInt().coerceIn(1, n / 2)
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val left = x < band
            val right = x >= n - band
            val top = y < band
            val bottom = y >= n - band
            px[y * n + x] = when {
                top && left -> 0xFFE53935.toInt() // red
                top && right -> 0xFFFDD835.toInt() // yellow
                bottom && left -> 0xFF3949AB.toInt() // indigo
                bottom && right -> 0xFF00ACC1.toInt() // cyan
                top -> 0xFFFB8C00.toInt() // orange
                bottom -> 0xFF1E88E5.toInt() // blue
                left -> 0xFFD81B60.toInt() // pink
                right -> 0xFF43A047.toInt() // green
                else -> 0xFF8E24AA.toInt() // purple (dominant)
            }
        }
        return extract(px, n, n, opts)
    }

    fun toLight(raw: Rgb, opts: ExtractOptions): LightColor {
        val hsv = rgbToHsv(raw)
        val chroma = (max(raw.r, max(raw.g, raw.b)) - min(raw.r, min(raw.g, raw.b))) / 255f
        val sat = if (chroma < GREY_CHROMA) 0f else min(1f, hsv[1] * opts.saturationBoost.coerceIn(0.5f, 3f))
        val hue = if (sat == 0f) 0f else hsv[0]
        val light = hsvToRgb(hue, sat, 1f)
        val minB = opts.minBrightness.coerceIn(0f, 1f)
        val bri = ((minB + (1f - minB) * hsv[2]) * 255f).roundToInt().coerceIn(1, 255)
        return LightColor(raw = raw, light = light, hue = hue, saturation = sat * 100f, brightness = bri)
    }

    /** Returns [h 0..360, s 0..1, v 0..1]. */
    fun rgbToHsv(c: Rgb): FloatArray {
        val r = c.r / 255f
        val g = c.g / 255f
        val b = c.b / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return Rgb(ch(r), ch(g), ch(b))
    }

    /**
     * 4-bit-per-channel colour histogram of one rectangle. The representative colour is a
     * "smoothed mode": the bucket whose 3x3x3 neighbourhood holds the most pixels, averaged over
     * that neighbourhood. This survives gradients and noise far better than a plain average,
     * and ignores small text/logos in a corner.
     */
    private class Histogram private constructor() {
        val count = IntArray(BUCKETS)
        val sumR = LongArray(BUCKETS)
        val sumG = LongArray(BUCKETS)
        val sumB = LongArray(BUCKETS)
        val occupied = ArrayList<Int>()
        var total = 0
        var allR = 0L
        var allG = 0L
        var allB = 0L

        fun add(argb: Int) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            if (count[k] == 0) occupied.add(k)
            count[k]++
            sumR[k] += r.toLong()
            sumG[k] += g.toLong()
            sumB[k] += b.toLong()
            total++
            allR += r
            allG += g
            allB += b
        }

        fun average(): Rgb =
            if (total == 0) Rgb(0, 0, 0)
            else Rgb((allR / total).toInt(), (allG / total).toInt(), (allB / total).toInt())

        private inline fun forNeighbours(k: Int, fn: (Int) -> Unit) {
            val r = (k shr 8) and 0xF
            val g = (k shr 4) and 0xF
            val b = k and 0xF
            for (dr in -1..1) {
                val rr = r + dr
                if (rr < 0 || rr > 15) continue
                for (dg in -1..1) {
                    val gg = g + dg
                    if (gg < 0 || gg > 15) continue
                    for (db in -1..1) {
                        val bb = b + db
                        if (bb < 0 || bb > 15) continue
                        fn((rr shl 8) or (gg shl 4) or bb)
                    }
                }
            }
        }

        private fun neighbourhoodCount(k: Int): Int {
            var n = 0
            forNeighbours(k) { n += count[it] }
            return n
        }

        private fun neighbourhoodMean(k: Int): Rgb {
            var n = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            forNeighbours(k) {
                n += count[it]
                r += sumR[it]
                g += sumG[it]
                b += sumB[it]
            }
            return if (n == 0L) Rgb(0, 0, 0) else Rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        fun dominant(): Rgb {
            if (total == 0) return Rgb(0, 0, 0)
            var best = -1
            var bestN = -1
            for (k in occupied) {
                val n = neighbourhoodCount(k)
                if (n > bestN) {
                    bestN = n
                    best = k
                }
            }
            return neighbourhoodMean(best)
        }

        /** Most colourful region covering at least ~3% of the image, or null if the image is grey. */
        fun vibrant(): Rgb? {
            if (total == 0) return null
            val minPixels = max(2, (total * 0.03f).roundToInt())
            var best: Rgb? = null
            var bestScore = 0f
            for (k in occupied) {
                if (neighbourhoodCount(k) < minPixels) continue
                val c = neighbourhoodMean(k)
                val hsv = rgbToHsv(c)
                val chroma = (max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b))) / 255f
                if (chroma < 0.15f || hsv[2] < 0.2f) continue
                val score = hsv[1] * hsv[2]
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            return best
        }

        companion object {
            const val BUCKETS = 4096

            fun of(px: IntArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Histogram {
                // Transparent artwork edges are ignored unless the whole patch is transparent.
                val opaque = Histogram()
                for (y in y0 until y1) {
                    val row = y * stride
                    for (x in x0 until x1) {
                        val c = px[row + x]
                        if ((c ushr 24) >= 0x80) opaque.add(c)
                    }
                }
                if (opaque.total > 0) return opaque
                val any = Histogram()
                for (y in y0 until y1) {
                    val row = y * stride
                    for (x in x0 until x1) any.add(px[row + x])
                }
                return any
            }
        }
    }
}
