package com.musiclightsynk

import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Direct WLED output - no Home Assistant in the loop. Pure JVM code (no Android classes).
 *
 *  COLORS  : JSON API. Sets WLED colours 1/2/3 (e.g. dominant / vibrant / average) so WLED's own
 *            effects run in the album colours. WLED's previous state is saved and put back on stop.
 *  AMBIENT : UDP realtime (DNRGB). Paints a smooth gradient through the 4 corner + 4 side colours
 *            around the TV, fades between tracks, optionally pulses on the beat. When we stop
 *            sending, WLED returns to whatever it was doing by itself after ~2 s.
 */

enum class WledMode(val key: String, val label: String) {
    OFF("off", "Off"),
    COLORS("colors", "Colours (keeps WLED effects)"),
    AMBIENT("ambient", "Ambient gradient around TV");

    fun next(): WledMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): WledMode = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: OFF
    }
}

/** Corners numbered clockwise from top-left, so side k runs from corner k to corner k+1. */
enum class Corner(val key: String, val label: String) {
    TOP_LEFT("top_left", "Top left"),
    TOP_RIGHT("top_right", "Top right"),
    BOTTOM_RIGHT("bottom_right", "Bottom right"),
    BOTTOM_LEFT("bottom_left", "Bottom left");

    fun next(): Corner = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(key: String?): Corner = values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: BOTTOM_LEFT
    }
}

/**
 * How the LED strip runs around the TV. Counts may be 0 (e.g. no strip along the bottom).
 * [start] is where LED 0 sits; [clockwise] is the direction as seen from the front.
 */
data class WledLayout(
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    val start: Corner,
    val clockwise: Boolean,
) {
    val total: Int get() = top + right + bottom + left

    private fun count(side: Int) = when (side) {
        0 -> top
        1 -> right
        2 -> bottom
        else -> left
    }

    /**
     * Colour for every LED (packed 0xRRGGBB), interpolating corner -> side middle -> next corner.
     * [colorOf] gives the colour for a slot, already scaled for brightness.
     */
    fun render(colorOf: (Slot) -> Rgb): IntArray {
        val corners = arrayOf(Slot.TOP_LEFT, Slot.TOP_RIGHT, Slot.BOTTOM_RIGHT, Slot.BOTTOM_LEFT).map(colorOf)
        val mids = arrayOf(Slot.TOP, Slot.RIGHT, Slot.BOTTOM, Slot.LEFT).map(colorOf)
        val out = IntArray(total)
        var i = 0
        val s = start.ordinal
        for (step in 0 until 4) {
            // Clockwise: walk sides s, s+1, ... forwards. Counter-clockwise: sides s-1, s-2, ... backwards.
            val side = if (clockwise) (s + step) % 4 else (s - 1 - step + 8) % 4
            val n = count(side)
            for (k in 0 until n) {
                val f = (k + 0.5f) / n
                val u = if (clockwise) f else 1f - f
                out[i++] = sideColor(corners[side], mids[side], corners[(side + 1) % 4], u)
            }
        }
        return out
    }

    private fun sideColor(a: Rgb, mid: Rgb, b: Rgb, u: Float): Int =
        if (u < 0.5f) lerp(a, mid, u / 0.5f) else lerp(mid, b, (u - 0.5f) / 0.5f)

    companion object {
        /** "top,right,bottom,left" e.g. "40,22,40,22". Returns null if unusable. */
        fun parse(raw: String, start: Corner, clockwise: Boolean): WledLayout? {
            val n = raw.split(',', ';', ' ', 'x').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
            if (n.size != 4 || n.any { it < 0 } || n.sum() == 0 || n.sum() > 4000) return null
            return WledLayout(n[0], n[1], n[2], n[3], start, clockwise)
        }

        fun lerp(a: Rgb, b: Rgb, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            val r = (a.r + (b.r - a.r) * tt).roundToInt()
            val g = (a.g + (b.g - a.g) * tt).roundToInt()
            val bl = (a.b + (b.b - a.b) * tt).roundToInt()
            return (r shl 16) or (g shl 8) or bl
        }
    }
}

object WledPackets {
    const val UDP_PORT = 21324
    private const val DNRGB = 4
    private const val MAX_LEDS_PER_PACKET = 489

    /** WLED "DNRGB" realtime packets: [4, timeout s, start hi, start lo, r, g, b, ...]. */
    fun dnrgb(frame: IntArray, timeoutSec: Int): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var start = 0
        while (start < frame.size) {
            val n = min(MAX_LEDS_PER_PACKET, frame.size - start)
            val p = ByteArray(4 + n * 3)
            p[0] = DNRGB.toByte()
            p[1] = timeoutSec.coerceIn(1, 255).toByte()
            p[2] = ((start shr 8) and 0xFF).toByte()
            p[3] = (start and 0xFF).toByte()
            for (i in 0 until n) {
                val c = frame[start + i]
                p[4 + i * 3] = ((c shr 16) and 0xFF).toByte()
                p[5 + i * 3] = ((c shr 8) and 0xFF).toByte()
                p[6 + i * 3] = (c and 0xFF).toByte()
            }
            packets += p
            start += n
        }
        return packets
    }
}

/** WLED JSON API (http://<host>/json/state). */
object WledJson {
    data class Result(val ok: Boolean, val message: String)

    fun colorsBody(cols: List<Rgb>, bri: Int, fx: Int, palette: Int): JSONObject {
        val seg = JSONObject().put("col", JSONArray().apply {
            for (c in cols) put(JSONArray().put(c.r).put(c.g).put(c.b))
        })
        if (fx >= 0) seg.put("fx", fx)
        if (palette >= 0) seg.put("pal", palette)
        // A segment *object* (no id) is applied to all selected segments.
        return JSONObject().put("on", true).put("bri", bri.coerceIn(1, 255)).put("seg", seg)
    }

    /** What to post back to undo our changes: the saved state minus preset/playlist pointers. */
    fun restoreBody(saved: JSONObject): JSONObject {
        val body = JSONObject(saved.toString())
        body.remove("ps")
        body.remove("pl")
        body.remove("nl")
        body.remove("udpn")
        body.remove("lor")
        return body
    }

    fun getState(host: String): JSONObject? = try {
        val conn = URL("http://$host/json/state").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 2500
            conn.readTimeout = 3000
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun post(host: String, body: JSONObject): Result {
        val r = HaClient.postJson("http://$host/json/state", body.toString(), null, connectTimeoutMs = 2500, readTimeoutMs = 3000)
        return Result(r.ok, r.message)
    }
}

/** Sends frames over UDP. Resolves each host once. */
class UdpSender {
    private var socket: DatagramSocket? = null
    private val addresses = HashMap<String, InetAddress?>()

    fun send(hosts: List<String>, packets: List<ByteArray>, port: Int = WledPackets.UDP_PORT): Boolean {
        val s = socket ?: DatagramSocket().also { socket = it }
        var any = false
        for (h in hosts) {
            val name = h.substringBefore(':')
            val addr = addresses.getOrPut(name) {
                try {
                    InetAddress.getByName(name)
                } catch (e: Exception) {
                    null
                }
            } ?: continue
            try {
                for (p in packets) s.send(DatagramPacket(p, p.size, addr, port))
                any = true
            } catch (_: Exception) {
                addresses.remove(name) // re-resolve next time (DHCP change)
            }
        }
        return any
    }

    fun close() {
        socket?.close()
        socket = null
        addresses.clear()
    }
}

/**
 * Ambient renderer on its own thread. Sends ~30 fps while fading or pulsing, otherwise a
 * keep-alive frame every second (WLED drops out of realtime mode 2 s after the last packet).
 */
class WledAmbient(private val udpPort: Int = WledPackets.UDP_PORT) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled").apply { isDaemon = true } }
    private val sender = UdpSender()

    // all fields below: renderer thread only
    private var hosts: List<String> = emptyList()
    private var shown = FloatArray(0)
    private var from = FloatArray(0)
    private var target = IntArray(0)
    private var fadeStart = 0L
    private var pulse = false
    private var pulseStrength = 0.6f
    private var envelope = 0f
    private var lastTick = 0L
    private var task: ScheduledFuture<*>? = null
    private var stopTask: ScheduledFuture<*>? = null

    @Volatile
    var running = false
        private set

    @Volatile
    var framesSent = 0L
        private set

    /** Show a new target frame (fades from what's on the strip now). Safe from any thread. */
    fun show(hosts: List<String>, frame: IntArray, pulse: Boolean, pulseStrength: Float, durationMs: Long = 0) {
        exec.execute {
            stopTask?.cancel(false)
            this.hosts = hosts
            this.pulse = pulse
            this.pulseStrength = pulseStrength.coerceIn(0f, 1f)
            if (shown.size != frame.size) {
                shown = FloatArray(frame.size * 3)
                from = FloatArray(frame.size * 3)
            }
            System.arraycopy(shown, 0, from, 0, shown.size)
            target = frame
            fadeStart = now()
            running = true
            reschedule(0)
            if (durationMs > 0) stopTask = exec.schedule({ stopNow() }, durationMs, TimeUnit.MILLISECONDS)
        }
    }

    fun setPulse(enabled: Boolean, strength: Float) = exec.execute {
        pulse = enabled
        pulseStrength = strength.coerceIn(0f, 1f)
        if (running) reschedule(0)
    }

    fun beat() = exec.execute {
        envelope = 1f
    }

    /** Stop sending; WLED goes back to its own effect ~1 s later. */
    fun stop() = exec.execute { stopNow() }

    fun shutdown() {
        exec.execute {
            stopNow()
            sender.close()
        }
        exec.shutdown()
    }

    private fun stopNow() {
        task?.cancel(false)
        task = null
        if (running && target.isNotEmpty()) {
            // Last frame with a 1 s timeout so WLED reverts quickly.
            sender.send(hosts, WledPackets.dnrgb(currentFrame(1f), 1), udpPort)
        }
        running = false
        shown = FloatArray(0)
        envelope = 0f
    }

    private fun reschedule(delayMs: Long) {
        task?.cancel(false)
        task = exec.schedule({ tick() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        if (!running) return
        val t = now()
        val dt = if (lastTick == 0L) 33L else (t - lastTick).coerceIn(1, 1000)
        lastTick = t
        envelope *= exp(-dt / 160f)
        val fade = ((t - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        val eased = fade * fade * (3 - 2 * fade)
        for (i in target.indices) {
            val c = target[i]
            val tr = ((c shr 16) and 0xFF).toFloat()
            val tg = ((c shr 8) and 0xFF).toFloat()
            val tb = (c and 0xFF).toFloat()
            shown[i * 3] = from[i * 3] + (tr - from[i * 3]) * eased
            shown[i * 3 + 1] = from[i * 3 + 1] + (tg - from[i * 3 + 1]) * eased
            shown[i * 3 + 2] = from[i * 3 + 2] + (tb - from[i * 3 + 2]) * eased
        }
        val factor = if (pulse) (1f - pulseStrength) + pulseStrength * envelope else 1f
        if (sender.send(hosts, WledPackets.dnrgb(currentFrame(factor), 2), udpPort)) framesSent++
        reschedule(if (fade < 1f || pulse) FRAME_MS else KEEPALIVE_MS)
    }

    private fun currentFrame(factor: Float): IntArray = IntArray(target.size) { i ->
        val r = (shown[i * 3] * factor).roundToInt().coerceIn(0, 255)
        val g = (shown[i * 3 + 1] * factor).roundToInt().coerceIn(0, 255)
        val b = (shown[i * 3 + 2] * factor).roundToInt().coerceIn(0, 255)
        (r shl 16) or (g shl 8) or b
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        const val FADE_MS = 700L
        const val FRAME_MS = 33L
        const val KEEPALIVE_MS = 1000L
    }
}

data class WledConfig(
    val hosts: List<String>,
    val mode: WledMode,
    val layout: WledLayout?,
    val colorSlots: List<Slot>,
    val fx: Int,
    val palette: Int,
    val brightness: Float,
    val restore: Boolean,
    val pulse: Boolean,
    val pulseStrength: Float,
)

/** Decides what to send to WLED for each engine event. Call from one thread. */
class WledController(udpPort: Int = WledPackets.UDP_PORT) {
    data class Result(val ok: Boolean, val message: String)

    val ambient = WledAmbient(udpPort)
    private val saved = HashMap<String, JSONObject>()
    private val restoreExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled-restore").apply { isDaemon = true } }

    fun onColors(cfg: WledConfig, palette: Palette, sessionStart: Boolean, preview: Boolean = false): Result {
        if (cfg.mode != WledMode.AMBIENT) ambient.stop()
        return when (cfg.mode) {
            WledMode.OFF -> Result(false, "WLED off")
            WledMode.AMBIENT -> {
                val layout = cfg.layout ?: return Result(false, "WLED: set the LED layout (top,right,bottom,left)")
                val frame = layout.render { slot -> scaled(palette[slot], cfg.brightness) }
                ambient.show(cfg.hosts, frame, cfg.pulse && !preview, cfg.pulseStrength, if (preview) PREVIEW_MS else 0)
                Result(true, "WLED ambient ${layout.total} LEDs")
            }
            WledMode.COLORS -> {
                if (cfg.restore && (sessionStart || preview)) {
                    synchronized(this) {
                        for (h in cfg.hosts) if (h !in saved) WledJson.getState(h)?.let { saved[h] = it }
                    }
                }
                val cols = cfg.colorSlots.map { palette[it].light }
                val bri = (palette[cfg.colorSlots.firstOrNull() ?: Slot.DOMINANT].brightness * cfg.brightness).roundToInt()
                val body = WledJson.colorsBody(cols, bri, cfg.fx, cfg.palette)
                val results = cfg.hosts.map { WledJson.post(it, body) }
                if (preview) restoreExec.schedule({ restoreAll(cfg) }, PREVIEW_MS, TimeUnit.MILLISECONDS)
                Result(results.any { it.ok }, "WLED " + results.joinToString(", ") { it.message })
            }
        }
    }

    fun onStopped(cfg: WledConfig): Result {
        ambient.stop()
        if (cfg.restore) return restoreAll(cfg) // no-op unless colours mode saved a state
        return Result(true, "WLED released")
    }

    /** Settings changed to "off" or hosts removed: let go of everything. */
    fun release(cfg: WledConfig?) {
        ambient.stop()
        if (cfg != null && cfg.restore) restoreAll(cfg)
    }

    @Synchronized
    private fun restoreAll(cfg: WledConfig): Result {
        if (saved.isEmpty()) return Result(true, "WLED nothing to restore")
        val msgs = ArrayList<String>()
        for ((host, state) in saved) {
            msgs += WledJson.post(host, WledJson.restoreBody(state)).message
            val pl = state.optInt("pl", -1)
            if (pl >= 0) WledJson.post(host, JSONObject().put("ps", pl)) // resume the playlist
        }
        saved.clear()
        return Result(true, "WLED restored " + msgs.joinToString(", "))
    }

    private fun scaled(c: LightColor, master: Float): Rgb {
        val f = (c.brightness / 255f) * master
        return Rgb(
            (c.light.r * f).roundToInt().coerceIn(0, 255),
            (c.light.g * f).roundToInt().coerceIn(0, 255),
            (c.light.b * f).roundToInt().coerceIn(0, 255),
        )
    }

    companion object {
        const val PREVIEW_MS = 6000L
    }
}

/**
 * Beat detection on bass energy: a beat is energy well above the recent average.
 * Pure function of its inputs, unit tested.
 */
class BeatDetector(
    private val historySize: Int = 43,
    private val sensitivity: Float = 1.4f,
    private val minIntervalMs: Long = 250,
    private val minEnergy: Float = 1e-3f,
) {
    private val history = FloatArray(historySize)
    private var count = 0
    private var index = 0
    private var lastBeat = Long.MIN_VALUE / 2

    fun feed(energy: Float, nowMs: Long): Boolean {
        val filled = min(count, historySize)
        var avg = 0f
        for (i in 0 until filled) avg += history[i]
        avg = if (filled > 0) avg / filled else 0f
        history[index] = energy
        index = (index + 1) % historySize
        count++
        if (filled < 8) return false // still learning
        val isBeat = energy > minEnergy && energy > avg * sensitivity && nowMs - lastBeat >= minIntervalMs
        if (isBeat) lastBeat = nowMs
        return isBeat
    }

    companion object {
        /**
         * Bass energy (~30-160 Hz) from an Android Visualizer FFT frame
         * ([re0, reN/2, re1, im1, re2, im2, ...], signed bytes).
         */
        fun bassEnergy(fft: ByteArray, samplingRateMilliHz: Int): Float {
            val n = fft.size
            if (n < 4 || samplingRateMilliHz <= 0) return 0f
            val binHz = samplingRateMilliHz / 1000f / n
            val lo = max(1, (30f / binHz).roundToInt())
            val hi = min(n / 2 - 1, max(lo, (160f / binHz).roundToInt()))
            var e = 0f
            for (k in lo..hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                e += re * re + im * im
            }
            return e / (hi - lo + 1) / (128f * 128f)
        }
    }
}
