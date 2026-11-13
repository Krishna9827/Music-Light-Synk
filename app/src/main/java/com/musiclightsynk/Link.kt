package com.musiclightsynk

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Device link: Music Light Synk apps on the same Wi-Fi (Fire TV, phone, later PC / Mac) share
 * what's playing and the beat, and agree on which one drives the WLED strips. No server, no
 * Home Assistant: JSON over UDP port 21330. "hi" is broadcast every few seconds so devices find
 * each other; everything else goes straight (unicast) to the devices found.
 *
 * Pure JVM code (no Android classes); unit tested.
 */

object LinkProtocol {
    const val PORT = 21330
    const val GROUP = "home"
    private const val VERSION = 1

    sealed class Msg {
        abstract val from: String
    }

    /** "I'm here." [platform] e.g. "tv" / "phone"; [listening] = backup mic is on. */
    data class Hi(override val from: String, val name: String, val platform: String, val listening: Boolean = false) : Msg()

    /** What this device plays: raw colours per slot. Sent on each change and every few seconds while playing. */
    data class Pal(override val from: String, val colors: Map<Slot, Rgb>, val playing: Boolean, val title: String? = null) : Msg()

    /** Live band levels, 20-45 times a second while the device hears music. */
    data class Nrg(override val from: String, val levels: AudioLevels) : Msg()

    /** "I'm driving the WLED strips" with priority [prio]; [playingForMs] breaks ties (newest wins). */
    data class Claim(override val from: String, val prio: Int, val playingForMs: Long) : Msg()

    /** The WLED device list; the newest [rev] wins on every device. */
    data class Wled(override val from: String, val devices: String, val rev: Long) : Msg()

    fun encode(m: Msg): ByteArray {
        val o = JSONObject().put("v", VERSION).put("g", GROUP).put("id", m.from)
        when (m) {
            is Hi -> o.put("t", "hi").put("name", m.name).put("plat", m.platform).put("listen", m.listening)
            is Pal -> {
                val cols = JSONObject()
                for ((slot, c) in m.colors) cols.put(slot.key, c.hex)
                o.put("t", "pal").put("pal", cols).put("playing", m.playing)
                if (m.title != null) o.put("title", m.title.take(120))
            }
            is Nrg -> o.put("t", "nrg").put("l", r3(m.levels.lows)).put("m", r3(m.levels.mids)).put("h", r3(m.levels.highs))
                .put("b", m.levels.beat)
            is Claim -> o.put("t", "claim").put("prio", m.prio).put("age", m.playingForMs)
            is Wled -> o.put("t", "wled").put("devices", m.devices).put("rev", m.rev)
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** Null for anything that isn't a valid message from our group. */
    fun decode(bytes: ByteArray, length: Int = bytes.size): Msg? = try {
        val o = JSONObject(String(bytes, 0, length, Charsets.UTF_8))
        val id = o.optString("id")
        if (o.optString("g") != GROUP || id.isEmpty() || o.optInt("v") < 1) {
            null
        } else {
            when (o.optString("t")) {
                "hi" -> Hi(id, o.optString("name", id), o.optString("plat"), o.optBoolean("listen"))
                "pal" -> {
                    val cols = o.getJSONObject("pal")
                    val map = LinkedHashMap<Slot, Rgb>()
                    for (s in Slot.values()) parseHex(cols.optString(s.key))?.let { map[s] = it }
                    if (map.size == Slot.values().size) Pal(id, map, o.optBoolean("playing"), o.optString("title").ifEmpty { null }) else null
                }
                "nrg" -> Nrg(id, AudioLevels(f01(o, "l"), f01(o, "m"), f01(o, "h"), o.optBoolean("b")))
                "claim" -> Claim(id, o.getInt("prio"), o.optLong("age"))
                "wled" -> Wled(id, o.getString("devices"), o.getLong("rev"))
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Rebuild a palette from raw colours with this device's own light settings. */
    fun palette(colors: Map<Slot, Rgb>, opts: ExtractOptions): Palette =
        Palette(colors.mapValues { (_, raw) -> ColorExtractor.toLight(raw, opts) })

    fun colorsOf(p: Palette): Map<Slot, Rgb> = Slot.values().associateWith { p[it].raw }

    private fun r3(v: Float) = (v.coerceIn(0f, 1f) * 1000).roundToInt() / 1000.0
    private fun f01(o: JSONObject, k: String) = o.optDouble(k, 0.0).toFloat().coerceIn(0f, 1f)

    private fun parseHex(s: String): Rgb? {
        val h = s.removePrefix("#")
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return Rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }
}

/**
 * Decides which device drives the WLED strips, so two devices never fight over them.
 * Every device that wants to render claims a priority about once a second:
 *  - [PRIO_PLAYING]   it plays music itself and knows the artwork colours,
 *  - [PRIO_LISTENING] a backup listener (phone mic) hears music but doesn't know the colours.
 * The highest priority wins; between equals, the one that started playing most recently; then
 * the lower id. Claims expire after [ttlMs]. Pure, unit tested.
 */
class LightHub(private val selfId: String, private val ttlMs: Long = 2500) {
    private class Claim(val prio: Int, val playingForMs: Long, val atMs: Long)

    private val claims = HashMap<String, Claim>()

    @Synchronized
    fun onClaim(id: String, prio: Int, playingForMs: Long, nowMs: Long) {
        if (id == selfId) return
        if (prio <= 0) claims.remove(id) else claims[id] = Claim(prio, playingForMs, nowMs)
    }

    /** Whether this device may drive WLED now, wanting priority [prio]. */
    @Synchronized
    fun mayRender(prio: Int, playingForMs: Long, nowMs: Long): Boolean {
        if (prio <= 0) return false
        expire(nowMs)
        return claims.none { (id, c) -> beats(c.prio, c.playingForMs + (nowMs - c.atMs), id, prio, playingForMs) }
    }

    /** Id of the device driving WLED other than us, or null. */
    @Synchronized
    fun otherRenderer(nowMs: Long): String? {
        expire(nowMs)
        return claims.entries.sortedWith(
            compareByDescending<Map.Entry<String, Claim>> { it.value.prio }.thenBy { it.value.playingForMs }.thenBy { it.key },
        ).firstOrNull()?.key
    }

    private fun expire(nowMs: Long) {
        claims.entries.removeAll { nowMs - it.value.atMs > ttlMs }
    }

    private fun beats(prio: Int, age: Long, id: String, myPrio: Int, myAge: Long): Boolean = when {
        prio != myPrio -> prio > myPrio
        abs(age - myAge) > SAME_START_MS -> age < myAge
        else -> id < selfId
    }

    companion object {
        const val PRIO_PLAYING = 3
        const val PRIO_LISTENING = 1
        private const val SAME_START_MS = 1500L
    }
}

/**
 * The UDP socket: receives on [port], broadcasts "hi", sends everything else to the peers
 * found. [onMessage] runs on the receiver thread.
 */
class Link(
    private val selfId: String,
    private val port: Int = LinkProtocol.PORT,
    private val onMessage: (LinkProtocol.Msg) -> Unit,
) {
    class Peer(val id: String, @Volatile var address: InetSocketAddress, @Volatile var name: String, @Volatile var platform: String) {
        @Volatile
        var lastSeenMs = 0L

        @Volatile
        var listening = false
    }

    private var socket: DatagramSocket? = null
    private val peers = ConcurrentHashMap<String, Peer>()

    /** Where "hi" goes. Default: every IPv4 broadcast address of this device, plus 255.255.255.255. */
    @Volatile
    var broadcastTargets: List<InetSocketAddress>? = null

    val isRunning: Boolean get() = socket != null

    /** Why [start] failed, if it did. */
    @Volatile
    var lastError: String? = null
        private set

    /** Peers heard from in the last 10 s. */
    fun peers(nowMs: Long = now()): List<Peer> = peers.values.filter { nowMs - it.lastSeenMs < PEER_TTL_MS }

    fun peer(id: String): Peer? = peers[id]

    @Synchronized
    fun start(): Boolean {
        if (socket != null) return true
        val listenOn = InetSocketAddress(port) // (inside apply, "port" would be the socket's remote port)
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(listenOn)
            }
        } catch (e: Exception) {
            lastError = e.toString()
            return false
        }
        lastError = null
        socket = s
        thread(name = "mls-link", isDaemon = true) { receive(s) }
        return true
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        peers.clear()
    }

    fun broadcast(m: LinkProtocol.Msg) {
        val data = LinkProtocol.encode(m)
        for (t in broadcastTargets ?: defaultBroadcast()) send(data, t)
    }

    /** To every peer seen recently (optionally only those matching [filter]). */
    fun sendToPeers(m: LinkProtocol.Msg, filter: (Peer) -> Boolean = { true }) {
        val list = peers().filter(filter)
        if (list.isEmpty()) return
        val data = LinkProtocol.encode(m)
        for (p in list) send(data, p.address)
    }

    private fun send(data: ByteArray, to: InetSocketAddress) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(data, data.size, to))
        } catch (_: Exception) {
        }
    }

    private fun receive(s: DatagramSocket) {
        val buf = ByteArray(8192)
        while (!s.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (e: Exception) {
                if (s.isClosed) return else continue
            }
            val m = LinkProtocol.decode(pkt.data, pkt.length) ?: continue
            if (m.from == selfId) continue // our own broadcast
            val from = InetSocketAddress(pkt.address, pkt.port)
            val p = peers.getOrPut(m.from) { Peer(m.from, from, m.from, "") }
            p.address = from
            p.lastSeenMs = now()
            if (m is LinkProtocol.Hi) {
                p.name = m.name
                p.platform = m.platform
                p.listening = m.listening
            }
            try {
                onMessage(m)
            } catch (_: Exception) {
            }
        }
    }

    private fun defaultBroadcast(): List<InetSocketAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        } catch (_: Exception) {
        }
        try {
            out += InetAddress.getByName("255.255.255.255")
        } catch (_: Exception) {
        }
        return out.map { InetSocketAddress(it, port) }
    }

    private fun now() = System.nanoTime() / 1_000_000

    companion object {
        const val PEER_TTL_MS = 10_000L
    }
}
