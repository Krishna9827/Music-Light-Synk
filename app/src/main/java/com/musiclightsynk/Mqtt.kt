package com.musiclightsynk

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/*
 * A deliberately tiny MQTT 3.1.1 publisher (QoS 0, retain, last will). That is all Home Assistant
 * needs, and it keeps the APK free of libraries. Pure JVM code, unit tested against a fake broker.
 */

/** Encoders for the handful of MQTT packets we send. */
object MqttPackets {
    fun connect(
        clientId: String,
        keepAliveSec: Int,
        username: String?,
        password: String?,
        willTopic: String?,
        willPayload: String?,
        willRetain: Boolean,
    ): ByteArray {
        val vh = ByteArrayOutputStream()
        writeString(vh, "MQTT")
        vh.write(4) // protocol level 3.1.1
        var flags = 0x02 // clean session
        if (willTopic != null && willPayload != null) {
            flags = flags or 0x04
            if (willRetain) flags = flags or 0x20
        }
        if (password != null && username != null) flags = flags or 0x40
        if (username != null) flags = flags or 0x80
        vh.write(flags)
        vh.write((keepAliveSec shr 8) and 0xFF)
        vh.write(keepAliveSec and 0xFF)
        writeString(vh, clientId)
        if (willTopic != null && willPayload != null) {
            writeString(vh, willTopic)
            writeBytes(vh, willPayload.toByteArray(Charsets.UTF_8))
        }
        if (username != null) writeString(vh, username)
        if (password != null && username != null) writeString(vh, password)
        return packet(0x10, vh.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        writeString(body, topic)
        body.write(payload)
        return packet(0x30 or (if (retain) 0x01 else 0), body.toByteArray())
    }

    val PINGREQ = byteArrayOf(0xC0.toByte(), 0)
    val DISCONNECT = byteArrayOf(0xE0.toByte(), 0)

    fun packet(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(remainingLength(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun remainingLength(n: Int): ByteArray {
        require(n in 0..268_435_455) { "packet too large" }
        val out = ByteArrayOutputStream()
        var x = n
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 0x80
            out.write(b)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) = writeBytes(out, s.toByteArray(Charsets.UTF_8))

    private fun writeBytes(out: ByteArrayOutputStream, b: ByteArray) {
        out.write((b.size shr 8) and 0xFF)
        out.write(b.size and 0xFF)
        out.write(b)
    }
}

/** The broker answered but said no (bad credentials etc.) - retrying won't help. */
class MqttRefusedException(message: String) : IOException(message)

class MqttClient(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String? = null,
    private val password: String? = null,
    private val keepAliveSec: Int = 120,
    private val tls: Boolean = false,
) : Closeable {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null

    /** Last time we successfully wrote to the broker (ms since epoch). */
    var lastIo = 0L
        private set

    val isConnected: Boolean get() = socket?.let { it.isConnected && !it.isClosed } == true

    /** True when the broker has probably dropped us (no traffic for longer than the keep-alive). */
    val isStale: Boolean get() = System.currentTimeMillis() - lastIo > keepAliveSec * 1000L

    @Throws(IOException::class)
    fun connect(willTopic: String?, willPayload: String?, timeoutMs: Int = 4000) {
        close()
        val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val o = s.getOutputStream()
            val i = BufferedInputStream(s.getInputStream())
            o.write(MqttPackets.connect(clientId, keepAliveSec, username, password, willTopic, willPayload, true))
            o.flush()
            val header = i.read()
            if (header != 0x20) throw IOException("expected CONNACK, got $header")
            val len = readVarInt(i)
            val body = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = i.read(body, read, len - read)
                if (n < 0) throw IOException("connection closed during CONNACK")
                read += n
            }
            val rc = if (len >= 2) body[1].toInt() else -1
            if (rc != 0) throw MqttRefusedException("broker refused connection: ${reason(rc)}")
            socket = s
            out = o
            input = i
            lastIo = System.currentTimeMillis()
        } catch (e: IOException) {
            try {
                s.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    @Throws(IOException::class)
    fun publish(topic: String, payload: String, retain: Boolean) {
        write(MqttPackets.publish(topic, payload.toByteArray(Charsets.UTF_8), retain))
    }

    @Throws(IOException::class)
    fun ping() = write(MqttPackets.PINGREQ)

    @Throws(IOException::class)
    private fun write(bytes: ByteArray) {
        val o = out ?: throw IOException("not connected")
        drain()
        o.write(bytes)
        o.flush()
        lastIo = System.currentTimeMillis()
    }

    /** We never subscribe; the only thing the broker sends is PINGRESP. Throw it away. */
    private fun drain() {
        val i = input ?: return
        try {
            var n = i.available()
            while (n > 0) {
                val skipped = i.skip(n.toLong())
                if (skipped <= 0) break
                n = i.available()
            }
        } catch (_: IOException) {
        }
    }

    override fun close() {
        val s = socket ?: return
        try {
            out?.write(MqttPackets.DISCONNECT)
            out?.flush()
        } catch (_: Exception) {
        }
        try {
            s.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
        input = null
    }

    private fun readVarInt(i: InputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val b = i.read()
            if (b < 0) throw IOException("connection closed")
            value += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) return value
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IOException("bad remaining length")
        }
    }

    private fun reason(rc: Int) = when (rc) {
        1 -> "unacceptable protocol version"
        2 -> "client id rejected"
        3 -> "server unavailable"
        4 -> "bad username or password"
        5 -> "not authorised"
        else -> "code $rc"
    }
}

data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    /** Topic prefix, e.g. "music_light_synk" -> music_light_synk/event, .../colors, .../state. */
    val prefix: String,
    val discovery: Boolean,
    val tls: Boolean,
    val deviceName: String,
    val model: String,
) {
    /** Stable id for discovery derived from the prefix, so two TVs with two prefixes don't clash. */
    val nodeId: String get() = prefix.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "music_light_synk" }

    val eventTopic get() = "$prefix/event"
    val colorsTopic get() = "$prefix/colors"
    val stateTopic get() = "$prefix/state"
    val availabilityTopic get() = "$prefix/availability"
    val statusTopic get() = "$prefix/status"
}

/**
 * Owns one MQTT connection and knows what to publish where:
 *  - <prefix>/event        every payload (not retained) - what the MQTT blueprint listens to
 *  - <prefix>/colors       last colour payload (retained) - discovery sensors read this
 *  - <prefix>/state        playing / app / title (retained)
 *  - <prefix>/availability online/offline (retained, offline is the last will)
 * plus Home Assistant MQTT discovery configs, so entities appear automatically.
 *
 * Not thread safe: call from one thread (the sync engine's).
 */
class MqttBridge(private val clientIdSuffix: String = "") {
    data class Result(val ok: Boolean, val message: String)

    private var client: MqttClient? = null
    private var connectedWith: MqttConfig? = null

    fun publish(cfg: MqttConfig, payload: JSONObject): Result {
        return try {
            publishOnce(cfg, payload)
            Result(true, "MQTT ok")
        } catch (e: MqttRefusedException) {
            close()
            Result(false, "MQTT ${e.message}")
        } catch (e: IOException) {
            // One reconnect + retry (Wi-Fi nap, broker restart, idle timeout).
            close()
            try {
                publishOnce(cfg, payload)
                Result(true, "MQTT ok (reconnected)")
            } catch (e2: Exception) {
                close()
                Result(false, "MQTT ${e2.javaClass.simpleName}: ${e2.message ?: "error"}")
            }
        } catch (e: Exception) {
            close()
            Result(false, "MQTT ${e.javaClass.simpleName}: ${e.message ?: "error"}")
        }
    }

    /** Keep the connection alive between tracks. Call every ~minute. */
    fun keepAlive() {
        val c = client ?: return
        try {
            if (c.isStale) close() else c.ping()
        } catch (_: Exception) {
            close()
        }
    }

    val isConnected: Boolean get() = client?.isConnected == true

    fun close() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        connectedWith = null
    }

    private fun ensureConnected(cfg: MqttConfig): MqttClient {
        val c = client
        if (c != null && c.isConnected && !c.isStale && connectedWith == cfg) return c
        close()
        val fresh = MqttClient(
            host = cfg.host,
            port = cfg.port,
            // <= 23 chars (the MQTT 3.1.1 guaranteed limit) and unique per topic prefix.
            clientId = ("mls_" + Integer.toHexString(cfg.nodeId.hashCode()) + clientIdSuffix).take(23),
            username = cfg.username,
            password = cfg.password,
            tls = cfg.tls,
        )
        fresh.connect(cfg.availabilityTopic, "offline")
        fresh.publish(cfg.availabilityTopic, "online", retain = true)
        if (cfg.discovery) {
            for ((topic, body) in discoveryMessages(cfg)) fresh.publish(topic, body.toString(), retain = true)
        }
        client = fresh
        connectedWith = cfg
        return fresh
    }

    private fun publishOnce(cfg: MqttConfig, payload: JSONObject) {
        val c = ensureConnected(cfg)
        val text = payload.toString()
        c.publish(cfg.eventTopic, text, retain = false)
        if (payload.has("colors")) c.publish(cfg.colorsTopic, text, retain = true)
        payload.optJSONObject("status")?.let { c.publish(cfg.statusTopic, it.toString(), retain = true) }
        c.publish(cfg.stateTopic, stateOf(payload).toString(), retain = true)
    }

    companion object {
        fun stateOf(payload: JSONObject): JSONObject {
            val s = JSONObject()
            for (k in listOf("event", "playing", "app", "package", "title", "artist", "album", "source", "device", "timestamp")) {
                if (payload.has(k)) s.put(k, payload.get(k))
            }
            return s
        }

        /** Home Assistant MQTT discovery: one sensor per colour slot, a now-playing sensor, a playing binary sensor. */
        fun discoveryMessages(cfg: MqttConfig): List<Pair<String, JSONObject>> {
            val node = cfg.nodeId
            val device = JSONObject()
                .put("identifiers", JSONArray().put(node))
                .put("name", "Music Light Synk")
                .put("manufacturer", "Music Light Synk")
                .put("model", cfg.model.ifBlank { "Android TV" })
            fun base(name: String, uid: String) = JSONObject()
                .put("name", name)
                .put("unique_id", "${node}_$uid")
                .put("availability_topic", cfg.availabilityTopic)
                .put("device", device)

            val out = ArrayList<Pair<String, JSONObject>>()
            for (slot in Slot.values()) {
                val cfgJson = base("${slot.label} colour", slot.key)
                    .put("state_topic", cfg.colorsTopic)
                    .put("value_template", "{{ value_json.colors.${slot.key}.hex }}")
                    .put("json_attributes_topic", cfg.colorsTopic)
                    .put("json_attributes_template", "{{ value_json.colors.${slot.key} | tojson }}")
                    .put("icon", "mdi:palette")
                out += "homeassistant/sensor/$node/${slot.key}/config" to cfgJson
            }
            out += "homeassistant/sensor/$node/now_playing/config" to base("Now playing", "now_playing")
                .put("state_topic", cfg.stateTopic)
                .put("value_template", "{{ (value_json.title or 'unknown') if value_json.playing else 'idle' }}")
                .put("json_attributes_topic", cfg.stateTopic)
                .put("icon", "mdi:music")
            out += "homeassistant/sensor/$node/source_mode/config" to base("Source mode", "source_mode")
                .put("state_topic", cfg.statusTopic)
                .put("value_template", "{{ value_json.source_mode }}")
                .put("icon", "mdi:image-filter-center-focus")
            out += "homeassistant/sensor/$node/colour_source/config" to base("Colour source", "colour_source")
                .put("state_topic", cfg.statusTopic)
                .put("value_template", "{{ value_json.last_source or 'none' }}")
                .put("icon", "mdi:palette-swatch")
            out += "homeassistant/binary_sensor/$node/media_access/config" to base("Media access", "media_access")
                .put("state_topic", cfg.statusTopic)
                .put("value_template", "{{ 'ON' if value_json.media_access else 'OFF' }}")
                .put("icon", "mdi:music-box-multiple")
            out += "homeassistant/binary_sensor/$node/screen_capture/config" to base("Screen capture", "screen_capture")
                .put("state_topic", cfg.statusTopic)
                .put("value_template", "{{ 'ON' if value_json.screen_capture else 'OFF' }}")
                .put("device_class", "running")
            out += "homeassistant/binary_sensor/$node/playing/config" to base("Playing", "playing")
                .put("state_topic", cfg.stateTopic)
                .put("value_template", "{{ 'ON' if value_json.playing else 'OFF' }}")
                .put("icon", "mdi:play-circle")
            return out
        }
    }
}
