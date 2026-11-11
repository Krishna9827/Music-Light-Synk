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
 * Every device gets its own zone (see [WledZone]):
 *  Section : one part of the artwork (a corner, a side, dominant, ...) as a solid colour, via the
 *            JSON API. WLED's previous state is saved and put back on stop.
 *  Palette : WLED colours 1/2/3 (e.g. dominant / vibrant / average) via the JSON API, so WLED's own
 *            effects run in the album colours. Also restored on stop.
 *  Ambient : UDP realtime (DNRGB). Paints a smooth gradient through the 4 corner + 4 side colours
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

/** What one WLED device shows. */
sealed class WledZone {
    /** One section's colour (a corner, a side, dominant, ...). Solid unless [keepEffect]. */
    data class Section(val slot: Slot, val keepEffect: Boolean = false) : WledZone()

    /** WLED colours 1/2/3 from three sections; WLED's own effect keeps running. */
    data class Palette(
        val slots: List<Slot> = DEFAULT_SLOTS,
        val fx: Int = -1,
        val pal: Int = -1,
    ) : WledZone()

    /** A strip around the TV: gradient through all 8 edge colours over UDP realtime. */
    data class Ambient(val layout: WledLayout) : WledZone()

    val label: String
        get() = when (this) {
            is Section -> slot.label + if (keepEffect) " (keeps effect)" else ""
            is Palette -> "Effect colours: " + slots.joinToString(", ") { it.label.lowercase() }
            is Ambient -> "Ambient gradient (${layout.top},${layout.right},${layout.bottom},${layout.left} LEDs)"
        }

    companion object {
        val DEFAULT_SLOTS = listOf(Slot.DOMINANT, Slot.VIBRANT, Slot.AVERAGE)
    }
}

data class WledDevice(val host: String, val zone: WledZone)

/**
 * WLED device list as one human-editable string (stored in prefs, sent over ADB, shown in HA):
 *
 *   192.168.1.60=ambient:40,22,40,22:bottom_left:cw ; 192.168.1.61=top_left ;
 *   192.168.1.62=left:keep ; 192.168.1.63=palette:dominant,vibrant,average:fx=28:pal=5
 *
 * Pure, unit tested.
 */
object WledDevices {
    data class Parsed(val devices: List<WledDevice>, val errors: List<String>)

    fun parse(raw: String): Parsed {
        val devices = ArrayList<WledDevice>()
        val errors = ArrayList<String>()
        for (entry in raw.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = entry.indexOf('=')
            if (eq <= 0) {
                errors += "'$entry': expected host=zone"
                continue
            }
            val host = entry.substring(0, eq).trim().removePrefix("http://").trimEnd('/')
            val parts = entry.substring(eq + 1).split(':').map { it.trim() }.filter { it.isNotEmpty() }
            val zone = parts.firstOrNull()?.lowercase()
            if (host.isEmpty() || zone == null) {
                errors += "'$entry': expected host=zone"
                continue
            }
            val opts = parts.drop(1)
            val parsed: WledZone? = when (zone) {
                "ambient", "gradient" -> parseAmbient(opts)
                "palette", "colors", "colours", "effect" -> parsePalette(opts)
                else -> slotOf(zone)?.let { WledZone.Section(it, opts.any { o -> o.equals("keep", true) }) }
            }
            if (parsed == null) errors += "'$entry': unknown zone or bad options" else devices += WledDevice(host, parsed)
        }
        return Parsed(devices, errors)
    }

    fun format(devices: List<WledDevice>): String = devices.joinToString("; ") { "${it.host}=${formatZone(it.zone)}" }

    fun formatZone(zone: WledZone): String = when (zone) {
        is WledZone.Section -> zone.slot.key + if (zone.keepEffect) ":keep" else ""
        is WledZone.Palette -> buildString {
            append("palette")
            if (zone.slots != WledZone.DEFAULT_SLOTS) append(":").append(zone.slots.joinToString(",") { it.key })
            if (zone.fx >= 0) append(":fx=").append(zone.fx)
            if (zone.pal >= 0) append(":pal=").append(zone.pal)
        }
        is WledZone.Ambient -> with(zone.layout) {
            "ambient:$top,$right,$bottom,$left:${start.key}:${if (clockwise) "cw" else "ccw"}"
        }
    }

    /** Zones offered when cycling in the TV app: every section, then palette, then ambient. */
    fun nextZone(zone: WledZone): WledZone {
        val order: List<WledZone> = Slot.values().map { WledZone.Section(it) } +
            WledZone.Palette() + WledZone.Ambient(DEFAULT_LAYOUT)
        val idx = order.indexOfFirst { it::class == zone::class && (it !is WledZone.Section || it.slot == (zone as WledZone.Section).slot) }
        val next = order[(idx + 1) % order.size]
        // Keep the user's layout / palette settings when coming back round to them.
        return when {
            next is WledZone.Ambient && zone is WledZone.Ambient -> zone
            else -> next
        }
    }

    val DEFAULT_LAYOUT = WledLayout(40, 22, 40, 22, Corner.BOTTOM_LEFT, true)

    private fun slotOf(key: String): Slot? = Slot.values().firstOrNull { it.key.equals(key, ignoreCase = true) }

    private fun parseAmbient(opts: List<String>): WledZone? {
        var counts: String? = null
        var start = Corner.BOTTOM_LEFT
        var cw = true
        for (o in opts) {
            when {
                o.matches(Regex("\\d+(,\\d+){3}")) -> counts = o
                o.equals("cw", true) || o.equals("clockwise", true) -> cw = true
                o.equals("ccw", true) || o.equals("counterclockwise", true) -> cw = false
                Corner.values().any { it.key.equals(o, true) } -> start = Corner.of(o)
                else -> return null
            }
        }
        val layout = WledLayout.parse(counts ?: return null, start, cw) ?: return null
        return WledZone.Ambient(layout)
    }

    private fun parsePalette(opts: List<String>): WledZone? {
        var slots = WledZone.DEFAULT_SLOTS
        var fx = -1
        var pal = -1
        for (o in opts) {
            when {
                o.startsWith("fx=", true) -> fx = o.substring(3).toIntOrNull()?.coerceIn(0, 255) ?: return null
                o.startsWith("pal=", true) -> pal = o.substring(4).toIntOrNull()?.coerceIn(0, 255) ?: return null
                else -> {
                    val list = o.split(',').map { slotOf(it.trim()) ?: return null }
                    if (list.isEmpty() || list.size > 3) return null
                    slots = list
                }
            }
        }
        return WledZone.Palette(slots, fx, pal)
    }
}

/**
 * Ambient renderer on its own thread, for any number of WLED devices (each with its own
 * frame). Sends ~30 fps while fading or pulsing, otherwise a keep-alive frame every second
 * (WLED drops out of realtime mode 2 s after the last packet).
 */
class WledAmbient(private val udpPort: Int = WledPackets.UDP_PORT) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled").apply { isDaemon = true } }
    private val sender = UdpSender()

    private class Target(var frame: IntArray) {
        var shown = FloatArray(frame.size * 3)
        var from = FloatArray(frame.size * 3)
    }

    // all fields below: renderer thread only
    private val targets = LinkedHashMap<String, Target>()
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

    /**
     * Show new frames (host -> one colour per LED), fading from what each strip shows now.
     * Devices missing from [frames] are released. Safe from any thread.
     */
    fun show(frames: Map<String, IntArray>, pulse: Boolean, pulseStrength: Float, durationMs: Long = 0) {
        exec.execute {
            stopTask?.cancel(false)
            for (host in targets.keys - frames.keys) release(host)
            for ((host, frame) in frames) {
                val t = targets[host]
                if (t == null || t.shown.size != frame.size * 3) {
                    targets[host] = Target(frame) // new device or new LED count: fade in from black
                } else {
                    System.arraycopy(t.shown, 0, t.from, 0, t.shown.size)
                    t.frame = frame
                }
            }
            this.pulse = pulse
            this.pulseStrength = pulseStrength.coerceIn(0f, 1f)
            fadeStart = now()
            running = targets.isNotEmpty()
            if (running) reschedule(0)
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

    private fun release(host: String) {
        val t = targets.remove(host) ?: return
        // Last frame with a 1 s timeout so WLED reverts quickly.
        sender.send(listOf(host), WledPackets.dnrgb(frameOf(t, 1f), 1), udpPort)
    }

    private fun stopNow() {
        task?.cancel(false)
        task = null
        for (host in targets.keys.toList()) release(host)
        running = false
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
        val factor = if (pulse) (1f - pulseStrength) + pulseStrength * envelope else 1f
        for ((host, target) in targets) {
            val frame = target.frame
            for (i in frame.indices) {
                val c = frame[i]
                for (ch in 0..2) {
                    val v = ((c shr (16 - ch * 8)) and 0xFF).toFloat()
                    val k = i * 3 + ch
                    target.shown[k] = target.from[k] + (v - target.from[k]) * eased
                }
            }
            if (sender.send(listOf(host), WledPackets.dnrgb(frameOf(target, factor), 2), udpPort)) framesSent++
        }
        reschedule(if (fade < 1f || pulse) FRAME_MS else KEEPALIVE_MS)
    }

    private fun frameOf(t: Target, factor: Float): IntArray = IntArray(t.frame.size) { i ->
        val r = (t.shown[i * 3] * factor).roundToInt().coerceIn(0, 255)
        val g = (t.shown[i * 3 + 1] * factor).roundToInt().coerceIn(0, 255)
        val b = (t.shown[i * 3 + 2] * factor).roundToInt().coerceIn(0, 255)
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
    val devices: List<WledDevice>,
    val brightness: Float,
    val restore: Boolean,
    val pulse: Boolean,
    val pulseStrength: Float,
) {
    val hasAmbient: Boolean get() = devices.any { it.zone is WledZone.Ambient }
}

/** Decides what to send to each WLED device for each engine event. Call from one thread. */
class WledController(udpPort: Int = WledPackets.UDP_PORT) {
    data class Result(val ok: Boolean, val message: String)

    val ambient = WledAmbient(udpPort)
    private val saved = HashMap<String, JSONObject>()
    private val restoreExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled-restore").apply { isDaemon = true } }

    fun onColors(cfg: WledConfig, palette: Palette, sessionStart: Boolean, preview: Boolean = false): Result {
        val frames = LinkedHashMap<String, IntArray>()
        val json = cfg.devices.filter { it.zone !is WledZone.Ambient }
        for (d in cfg.devices) {
            val z = d.zone as? WledZone.Ambient ?: continue
            frames[d.host] = z.layout.render { slot -> scaled(palette[slot], cfg.brightness) }
        }
        ambient.show(frames, cfg.pulse && !preview, cfg.pulseStrength, if (preview) PREVIEW_MS else 0)

        if (json.isNotEmpty() && cfg.restore && (sessionStart || preview)) {
            synchronized(this) {
                for (d in json) if (d.host !in saved) WledJson.getState(d.host)?.let { saved[d.host] = it }
            }
        }
        val parts = ArrayList<String>()
        var ok = frames.isNotEmpty()
        if (frames.isNotEmpty()) parts += "ambient x${frames.size}"
        for (d in json) {
            val r = WledJson.post(d.host, bodyFor(d.zone, palette, cfg.brightness))
            if (r.ok) ok = true
            parts += "${d.host} ${r.message}"
        }
        if (preview && json.isNotEmpty()) restoreExec.schedule({ restoreAll() }, PREVIEW_MS, TimeUnit.MILLISECONDS)
        return Result(ok, "WLED " + parts.joinToString(", ").ifEmpty { "no devices" })
    }

    fun onStopped(cfg: WledConfig): Result {
        ambient.stop()
        if (cfg.restore) return restoreAll() // no-op unless a JSON device saved a state
        return Result(true, "WLED released")
    }

    /** WLED switched off or devices removed: let go of everything. */
    fun release(restore: Boolean) {
        ambient.stop()
        if (restore) restoreAll()
    }

    @Synchronized
    private fun restoreAll(): Result {
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

        fun bodyFor(zone: WledZone, palette: Palette, master: Float): JSONObject = when (zone) {
            is WledZone.Section -> {
                val c = palette[zone.slot]
                WledJson.colorsBody(listOf(c.light), (c.brightness * master).roundToInt(), if (zone.keepEffect) -1 else 0, -1)
            }
            is WledZone.Palette -> {
                val bri = (palette[zone.slots.first()].brightness * master).roundToInt()
                WledJson.colorsBody(zone.slots.map { palette[it].light }, bri, zone.fx, zone.pal)
            }
            is WledZone.Ambient -> throw IllegalArgumentException("ambient zones go over UDP")
        }
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
