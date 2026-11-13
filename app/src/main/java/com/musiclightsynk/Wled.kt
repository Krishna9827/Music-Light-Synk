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
 *
 * Any Section or Ambient device can also run the Energy effect ([WledEffect]): bars bounce with
 * the music. That always goes over UDP realtime (a Section strip then needs its LED count, read
 * from /json/info or given as leds=N).
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

    fun getState(host: String): JSONObject? = get(host, "/json/state")

    /** Number of LEDs the WLED drives (/json/info -> leds.count), or null if unreachable. */
    fun ledCount(host: String): Int? =
        get(host, "/json/info")?.optJSONObject("leds")?.optInt("count", 0)?.takeIf { it in 1..4000 }

    private fun get(host: String, path: String): JSONObject? = try {
        val conn = URL("http://$host$path").openConnection() as HttpURLConnection
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

/**
 * One WLED and what it shows. [effect] applies to Section and Ambient zones (a Palette zone runs
 * WLED's own effect). [leds] overrides the LED count for an Energy section strip (0 = ask WLED).
 */
data class WledDevice(
    val host: String,
    val zone: WledZone,
    val effect: WledEffect = WledEffect(),
    val leds: Int = 0,
) {
    /** Driven over UDP realtime rather than the JSON API. */
    val realtime: Boolean get() = zone is WledZone.Ambient || (zone is WledZone.Section && effect.energy)

    val effectLabel: String
        get() = if (!effect.energy || zone is WledZone.Palette) "" else buildString {
            append("Energy")
            when {
                effect.mirror && effect.flip -> append(", from both ends")
                effect.mirror -> append(", from the middle")
                effect.flip -> append(", from the last LED")
            }
        }
}

/**
 * WLED device list as one human-editable string (stored in prefs, sent over ADB, shown in HA):
 *
 *   192.168.1.60=ambient:40,22,40,22:bottom_left:cw ; 192.168.1.61=top_left ;
 *   192.168.1.62=left:keep ; 192.168.1.63=palette:dominant,vibrant,average:fx=28:pal=5 ;
 *   192.168.1.64=dominant:energy ; 192.168.1.65=ambient:40,22,40,22:bottom_left:cw:energy:mirror:flip
 *
 * Effect options (any order, after the zone): energy, flip, mirror, leds=N.
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
            var effect = WledEffect()
            var leds = 0
            val opts = ArrayList<String>()
            for (o in parts.drop(1)) {
                when {
                    o.equals("energy", true) -> effect = effect.copy(energy = true)
                    o.equals("flip", true) -> effect = effect.copy(flip = true)
                    o.equals("mirror", true) -> effect = effect.copy(mirror = true)
                    o.startsWith("leds=", true) -> leds = o.substring(5).toIntOrNull()?.coerceIn(0, 4000) ?: -1
                    else -> opts += o
                }
            }
            if (leds < 0) {
                errors += "'$entry': leds= needs a number"
                continue
            }
            val parsed: WledZone? = when (zone) {
                "ambient", "gradient" -> parseAmbient(opts)
                "palette", "colors", "colours", "effect" -> parsePalette(opts)
                else -> slotOf(zone)?.let { WledZone.Section(it, opts.any { o -> o.equals("keep", true) }) }
            }
            when (parsed) {
                null -> errors += "'$entry': unknown zone or bad options"
                // WLED's own effect runs on a palette zone; bar options don't apply
                is WledZone.Palette -> devices += WledDevice(host, parsed)
                else -> devices += WledDevice(host, parsed, effect, leds)
            }
        }
        return Parsed(devices, errors)
    }

    fun format(devices: List<WledDevice>): String = devices.joinToString("; ") { "${it.host}=${formatDevice(it)}" }

    /** Zone plus effect options, i.e. everything after "host=". */
    fun formatDevice(d: WledDevice): String = buildString {
        append(formatZone(d.zone))
        if (d.zone !is WledZone.Palette) {
            if (d.effect.energy) append(":energy")
            if (d.effect.flip) append(":flip")
            if (d.effect.mirror) append(":mirror")
            if (d.leds > 0) append(":leds=").append(d.leds)
        }
    }

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

/** Look of the realtime strips: beat pulse for plain gradients, background and blur for Energy bars. */
data class StripStyle(
    val pulse: Boolean = false,
    val pulseStrength: Float = 0.6f,
    /** Energy: brightness of the strip's colour where no bar is, 0..1. */
    val energyBackground: Float = 0.1f,
    /** Energy: smoothing passes across neighbouring LEDs. */
    val energyBlur: Int = 2,
)

/**
 * Realtime (UDP) renderer on its own thread, for any number of WLED devices, each with its own
 * frame. Sends ~30 fps while fading or pulsing and ~40 fps while Energy bars move with fresh
 * audio, otherwise a keep-alive frame every second (WLED drops out of realtime mode 2 s after
 * the last packet).
 */
class WledAmbient(private val udpPort: Int = WledPackets.UDP_PORT) {
    /** What one strip shows: its base colours per LED, plus the Energy effect if switched on. */
    class Strip(
        val base: IntArray,
        val effect: WledEffect = WledEffect(),
        /** Lows / mids / highs bar colours, or null for bars in the base colours. */
        val bands: IntArray? = null,
    )

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled").apply { isDaemon = true } }
    private val sender = UdpSender()

    private class Target(var strip: Strip) {
        var shown = FloatArray(strip.base.size * 3)
        var from = FloatArray(strip.base.size * 3)
    }

    // all fields below: renderer thread only
    private val targets = LinkedHashMap<String, Target>()
    private var fadeStart = 0L
    private var style = StripStyle()
    private var envelope = 0f
    private var lastTick = 0L
    private val shownLevels = FloatArray(3)
    private var task: ScheduledFuture<*>? = null
    private var stopTask: ScheduledFuture<*>? = null

    // written by the audio source, read by the renderer
    @Volatile
    private var levels = AudioLevels.SILENT

    @Volatile
    private var levelsAt = Long.MIN_VALUE / 2

    @Volatile
    var running = false
        private set

    @Volatile
    var framesSent = 0L
        private set

    /**
     * Show new strips (host -> strip), fading from what each strip shows now. Devices missing
     * from [strips] are released. Safe from any thread.
     */
    fun show(strips: Map<String, Strip>, style: StripStyle, durationMs: Long = 0) {
        exec.execute {
            stopTask?.cancel(false)
            for (host in targets.keys - strips.keys) release(host)
            for ((host, strip) in strips) {
                val t = targets[host]
                if (t == null || t.shown.size != strip.base.size * 3) {
                    targets[host] = Target(strip) // new device or new LED count: fade in from black
                } else {
                    System.arraycopy(t.shown, 0, t.from, 0, t.shown.size)
                    t.strip = strip
                }
            }
            this.style = style
            fadeStart = now()
            running = targets.isNotEmpty()
            if (running) reschedule(0)
            if (durationMs > 0) stopTask = exec.schedule({ stopNow() }, durationMs, TimeUnit.MILLISECONDS)
        }
    }

    fun setPulse(enabled: Boolean, strength: Float) = exec.execute {
        style = style.copy(pulse = enabled, pulseStrength = strength.coerceIn(0f, 1f))
        if (running) reschedule(0)
    }

    fun beat() = exec.execute {
        envelope = 1f
    }

    /** Latest audio levels for Energy strips (any thread, ~20-50 times a second). */
    fun audio(l: AudioLevels) {
        val t = now()
        val wasStale = t - levelsAt > AUDIO_STALE_MS
        levels = l
        levelsAt = t
        if (l.beat) beat()
        if (wasStale) exec.execute { if (running) reschedule(0) } // leave the 1 s keep-alive pace now
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
        shownLevels.fill(0f)
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
        val st = style
        val factor = if (st.pulse) (1f - st.pulseStrength) + st.pulseStrength * envelope else 1f
        val audioAge = t - levelsAt
        val audioLive = audioAge <= AUDIO_STALE_MS
        val bars = if (audioLive) smoothLevels(audioAge) else null
        var anyEnergy = false
        var anyPulse = false
        for ((host, target) in targets) {
            val base = target.strip.base
            for (i in base.indices) {
                val c = base[i]
                for (ch in 0..2) {
                    val v = ((c shr (16 - ch * 8)) and 0xFF).toFloat()
                    val k = i * 3 + ch
                    target.shown[k] = target.from[k] + (v - target.from[k]) * eased
                }
            }
            val effect = target.strip.effect
            val frame = if (effect.energy && bars != null) {
                anyEnergy = true
                EnergyEffect.render(frameOf(target, 1f), target.strip.bands, bars, effect, st.energyBackground, st.energyBlur)
            } else {
                // Energy strips without audio show their colours steadily
                if (!effect.energy) anyPulse = true
                frameOf(target, if (effect.energy) 1f else factor)
            }
            if (sender.send(listOf(host), WledPackets.dnrgb(frame, 2), udpPort)) framesSent++
        }
        reschedule(
            when {
                anyEnergy -> ENERGY_FRAME_MS
                fade < 1f || (st.pulse && anyPulse) -> FRAME_MS
                else -> KEEPALIVE_MS
            },
        )
    }

    /** Glide toward the latest levels so 20 Hz audio still looks smooth at 40 fps; fade out if the feed pauses. */
    private fun smoothLevels(ageMs: Long): AudioLevels {
        val l = levels
        val fall = if (ageMs > 150) exp(-(ageMs - 150) / 200f) else 1f
        val target = floatArrayOf(l.lows * fall, l.mids * fall, l.highs * fall)
        for (b in 0..2) shownLevels[b] += (target[b] - shownLevels[b]) * 0.6f
        return AudioLevels(shownLevels[0], shownLevels[1], shownLevels[2])
    }

    private fun frameOf(t: Target, factor: Float): IntArray = IntArray(t.strip.base.size) { i ->
        val r = (t.shown[i * 3] * factor).roundToInt().coerceIn(0, 255)
        val g = (t.shown[i * 3 + 1] * factor).roundToInt().coerceIn(0, 255)
        val b = (t.shown[i * 3 + 2] * factor).roundToInt().coerceIn(0, 255)
        (r shl 16) or (g shl 8) or b
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        const val FADE_MS = 700L
        const val FRAME_MS = 33L
        const val ENERGY_FRAME_MS = 25L
        const val KEEPALIVE_MS = 1000L

        /** Energy strips go steady when no audio has arrived for this long. */
        const val AUDIO_STALE_MS = 2000L
    }
}

data class WledConfig(
    val devices: List<WledDevice>,
    val brightness: Float,
    val restore: Boolean,
    val pulse: Boolean,
    val pulseStrength: Float,
    val energyBackground: Float = 0.1f,
    val energyBlur: Int = 2,
) {
    /** Some device is driven over UDP realtime (gradient or Energy). */
    val hasRealtime: Boolean get() = devices.any { it.realtime }

    /** Beat pulse has something to pulse: a plain (non-Energy) gradient strip. */
    val hasPulseTarget: Boolean get() = devices.any { it.zone is WledZone.Ambient && !it.effect.energy }

    /** Some strip runs the Energy effect and wants audio levels. */
    val hasEnergy: Boolean get() = devices.any { it.realtime && it.effect.energy }

    /** Wants audio at all (pulse or Energy). */
    val wantsAudio: Boolean get() = hasEnergy || (pulse && hasPulseTarget)

    fun style(preview: Boolean = false) = StripStyle(pulse && !preview, pulseStrength, energyBackground, energyBlur)
}

/** Decides what to send to each WLED device for each engine event. Call from one thread. */
class WledController(udpPort: Int = WledPackets.UDP_PORT) {
    data class Result(val ok: Boolean, val message: String)

    val ambient = WledAmbient(udpPort)
    private val saved = HashMap<String, JSONObject>()
    private val ledCounts = HashMap<String, Int>()
    private val restoreExec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mls-wled-restore").apply { isDaemon = true } }
    private val jsonExec = Executors.newFixedThreadPool(4) { r -> Thread(r, "mls-wled-json").apply { isDaemon = true } }

    /** Run [fn] for every item at once (one slow or offline WLED must not hold up the others). */
    private fun <T, R> parallel(items: List<T>, fn: (T) -> R): List<R?> {
        val futures = items.map { item -> jsonExec.submit<R> { fn(item) } }
        return futures.map { f ->
            try {
                f.get(JSON_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                f.cancel(true)
                null
            }
        }
    }

    fun onColors(cfg: WledConfig, palette: Palette, sessionStart: Boolean, preview: Boolean = false): Result {
        val strips = LinkedHashMap<String, WledAmbient.Strip>()
        val json = ArrayList<WledDevice>()
        val notes = ArrayList<String>()
        fun scale(c: LightColor) = scaled(c, cfg.brightness)
        for (d in cfg.devices) {
            when (val z = d.zone) {
                is WledZone.Ambient -> strips[d.host] = WledAmbient.Strip(z.layout.render { scale(palette[it]) }, d.effect)
                is WledZone.Section -> {
                    val n = if (d.realtime) ledCount(d) else null
                    if (n == null) {
                        if (d.realtime) notes += "${d.host} LED count unknown, solid colour"
                        json += d
                    } else {
                        val colour = EnergyEffect.packRgb(scale(palette[z.slot]))
                        strips[d.host] = WledAmbient.Strip(IntArray(n) { colour }, d.effect, EnergyEffect.bandColours(palette, z.slot, ::scale))
                    }
                }
                is WledZone.Palette -> json += d
            }
        }
        ambient.show(strips, cfg.style(preview), if (preview) PREVIEW_MS else 0)

        if (json.isNotEmpty() && cfg.restore && (sessionStart || preview)) {
            synchronized(this) {
                val toSave = json.filter { it.host !in saved }
                parallel(toSave) { WledJson.getState(it.host) }.forEachIndexed { i, st -> if (st != null) saved[toSave[i].host] = st }
            }
        }
        val parts = ArrayList<String>()
        var ok = strips.isNotEmpty()
        if (strips.isNotEmpty()) parts += "realtime x${strips.size}"
        val results = parallel(json) { d -> WledJson.post(d.host, bodyFor(d.zone, palette, cfg.brightness)) }
        json.forEachIndexed { i, d ->
            val r = results[i]
            if (r?.ok == true) ok = true
            parts += "${d.host} ${r?.message ?: "timed out"}"
        }
        parts += notes
        if (preview && json.isNotEmpty()) restoreExec.schedule({ restoreAll() }, PREVIEW_MS, TimeUnit.MILLISECONDS)
        return Result(ok, "WLED " + parts.joinToString(", ").ifEmpty { "no devices" })
    }

    /** Audio levels for Energy strips. Any thread. */
    fun audio(levels: AudioLevels) = ambient.audio(levels)

    /**
     * Music stopped: let go of every strip and put JSON devices back as they were, except
     * [othersDrive] (normalised hosts another device has taken over; their old state is dropped).
     */
    fun onStopped(cfg: WledConfig, othersDrive: Set<String> = emptySet()): Result {
        ambient.stop()
        if (cfg.restore) return restoreAll(othersDrive) // no-op unless a JSON device saved a state
        synchronized(this) { saved.clear() }
        return Result(true, "WLED released")
    }

    /** WLED switched off or devices removed: let go of everything. */
    fun release(restore: Boolean) {
        ambient.stop()
        if (restore) restoreAll()
    }

    private fun ledCount(d: WledDevice): Int? {
        if (d.leds > 0) return d.leds
        ledCounts[d.host]?.let { return it }
        return WledJson.ledCount(d.host)?.also { ledCounts[d.host] = it }
    }

    @Synchronized
    private fun restoreAll(skip: Set<String> = emptySet()): Result {
        if (saved.isEmpty()) return Result(true, "WLED nothing to restore")
        val msgs = ArrayList<String>()
        for ((host, state) in saved) {
            if (LinkProtocol.normHost(host) in skip) continue
            msgs += WledJson.post(host, WledJson.restoreBody(state)).message
            val pl = state.optInt("pl", -1)
            if (pl >= 0) WledJson.post(host, JSONObject().put("ps", pl)) // resume the playlist
        }
        saved.clear()
        return Result(true, "WLED restored " + msgs.joinToString(", "))
    }

    companion object {
        const val PREVIEW_MS = 6000L
        private const val JSON_TIMEOUT_MS = 6500L

        /** A light colour scaled by its own brightness and the master brightness, as sent over UDP. */
        fun scaled(c: LightColor, master: Float): Rgb {
            val f = (c.brightness / 255f) * master
            return Rgb(
                (c.light.r * f).roundToInt().coerceIn(0, 255),
                (c.light.g * f).roundToInt().coerceIn(0, 255),
                (c.light.b * f).roundToInt().coerceIn(0, 255),
            )
        }

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
}
