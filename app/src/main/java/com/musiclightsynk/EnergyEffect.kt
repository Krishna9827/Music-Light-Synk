package com.musiclightsynk

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Per-strip effect options. */
data class WledEffect(
    /** Beat-reactive bars instead of a still colour. */
    val energy: Boolean = false,
    /** Start from the other end (e.g. LED 0 at the top of a standing bar). */
    val flip: Boolean = false,
    /** Two mirrored halves: from the middle outward, or with [flip] from both ends inward (like LedFx). */
    val mirror: Boolean = false,
)

/**
 * The "Energy" effect, like LedFx's: bars for lows / mids / highs grow along the strip with the
 * music. Pure function of its inputs; unit tested.
 *
 * Direction (p = 0 is where the bars start), the same as LedFx's flip / mirror switches:
 *  - mirror off, flip off : from LED 0 to the end          (a standing bar with LED 0 at the bottom)
 *  - mirror off, flip on  : from the last LED back to LED 0
 *  - mirror on,  flip off : from the middle out to both ends
 *  - mirror on,  flip on  : from both ends toward the middle (TV back, "bounces from both sides")
 */
object EnergyEffect {
    /** Soft edge of each bar, as a fraction of the axis. */
    private const val EDGE = 0.06f

    /** Position of LED [i] of [n] along the effect axis, 0..1. */
    fun axis(i: Int, n: Int, flip: Boolean, mirror: Boolean): Float {
        var x = (i + 0.5f) / n
        if (mirror) x = abs(2f * x - 1f) // 0 in the middle, 1 at both ends
        if (flip) x = 1f - x
        return x.coerceIn(0f, 1f)
    }

    /** 1 well inside the bar, fading to 0 at its tip; level 0 lights nothing. */
    private fun coverage(p: Float, level: Float): Float = ((level - p) / EDGE).coerceIn(0f, 1f)

    /**
     * @param base   the strip's normal colour per LED (packed 0xRRGGBB): the ambient gradient, or one
     *               colour for a single-section strip. Used when [bands] is null, and for the background.
     * @param bands  three colours for lows / mids / highs (packed), or null to bounce in [base]'s colours.
     * @param bg     background brightness 0..1 where no bar is.
     * @param blur   number of smoothing passes across neighbouring LEDs.
     */
    fun render(
        base: IntArray,
        bands: IntArray?,
        levels: AudioLevels,
        effect: WledEffect,
        bg: Float,
        blur: Int,
    ): IntArray {
        val n = base.size
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val bgK = bg.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = axis(i, n, effect.flip, effect.mirror)
            val cl = coverage(p, levels.lows)
            val cm = coverage(p, levels.mids)
            val ch = coverage(p, levels.highs)
            var fr: Float
            var fg: Float
            var fb: Float
            if (bands != null) {
                // "lighten" blend: overlapping bars keep their colours instead of washing to white
                fr = max(cl * red(bands[0]), max(cm * red(bands[1]), ch * red(bands[2])))
                fg = max(cl * green(bands[0]), max(cm * green(bands[1]), ch * green(bands[2])))
                fb = max(cl * blue(bands[0]), max(cm * blue(bands[1]), ch * blue(bands[2])))
            } else {
                val k = max(cl, max(cm * 0.75f, ch * 0.5f))
                fr = k * red(base[i])
                fg = k * green(base[i])
                fb = k * blue(base[i])
            }
            r[i] = max(fr, bgK * red(base[i]))
            g[i] = max(fg, bgK * green(base[i]))
            b[i] = max(fb, bgK * blue(base[i]))
        }
        repeat(blur.coerceIn(0, 10)) {
            smooth(r)
            smooth(g)
            smooth(b)
        }
        return IntArray(n) { i -> pack(r[i], g[i], b[i]) }
    }

    /** Three band colours from the artwork: the strip's own colour, then vibrant, then a contrast. */
    fun bandColours(palette: Palette, primary: Slot, scale: (LightColor) -> Rgb): IntArray {
        val lows = scale(palette[primary])
        var mids = scale(palette[Slot.VIBRANT])
        if (mids.distanceTo(lows) < 40) mids = scale(palette[Slot.DOMINANT])
        val edges = listOf(
            Slot.TOP_LEFT, Slot.TOP, Slot.TOP_RIGHT, Slot.LEFT, Slot.RIGHT,
            Slot.BOTTOM_LEFT, Slot.BOTTOM, Slot.BOTTOM_RIGHT, Slot.AVERAGE,
        )
        val highs = edges.map { scale(palette[it]) }
            .maxByOrNull { min(it.distanceTo(lows), it.distanceTo(mids)) } ?: lows
        return intArrayOf(packRgb(lows), packRgb(mids), packRgb(highs))
    }

    private fun smooth(a: FloatArray) {
        if (a.size < 3) return
        var prev = a[0]
        for (i in 1 until a.size - 1) {
            val cur = a[i]
            a[i] = (prev + 2f * cur + a[i + 1]) / 4f
            prev = cur
        }
    }

    private fun red(c: Int) = ((c shr 16) and 0xFF).toFloat()
    private fun green(c: Int) = ((c shr 8) and 0xFF).toFloat()
    private fun blue(c: Int) = (c and 0xFF).toFloat()

    private fun pack(r: Float, g: Float, b: Float): Int =
        (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)

    fun packRgb(c: Rgb): Int = (c.r shl 16) or (c.g shl 8) or c.b
}
