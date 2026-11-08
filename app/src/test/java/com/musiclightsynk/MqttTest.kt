package com.musiclightsynk

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MqttTest {

    @Test
    fun remainingLengthVarInt() {
        assertArrayEquals(byteArrayOf(0), MqttPackets.remainingLength(0))
        assertArrayEquals(byteArrayOf(127), MqttPackets.remainingLength(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), MqttPackets.remainingLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), MqttPackets.remainingLength(16_383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), MqttPackets.remainingLength(16_384))
    }

    @Test
    fun connectPacketMatchesSpec() {
        val p = MqttPackets.connect("abc", 60, "u", "p", "t/avail", "offline", true)
        assertEquals(0x10, p[0].toInt())
        // variable header: "MQTT", level 4, flags, keepalive 60
        val body = p.copyOfRange(2, p.size)
        assertArrayEquals(byteArrayOf(0, 4, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 4), body.copyOfRange(0, 7))
        val flags = body[7].toInt() and 0xFF
        assertEquals(0x80 or 0x40 or 0x20 or 0x04 or 0x02, flags)
        assertEquals(60, ((body[8].toInt() and 0xFF) shl 8) or (body[9].toInt() and 0xFF))
        assertEquals(p.size - 2, p[1].toInt())
    }

    @Test
    fun publishPacketRetainFlag() {
        val p = MqttPackets.publish("a/b", "hi".toByteArray(), retain = true)
        assertEquals(0x31, p[0].toInt())
        assertEquals(2 + 3 + 2, p[1].toInt())
        assertEquals("a/b", String(p, 4, 3))
        assertEquals("hi", String(p, 7, 2))
        assertEquals(0x30, MqttPackets.publish("a", ByteArray(0), retain = false)[0].toInt())
    }

    /** Minimal fake broker: accepts one client, answers CONNACK, records PUBLISHes. */
    private class FakeBroker(private val connackCode: Int = 0) : AutoCloseable {
        val server = ServerSocket(0)
        val published = CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        var clientId: String? = null
        var username: String? = null
        val connected = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        while (true) {
                            val header = input.read()
                            if (header < 0) break
                            val len = readLen(input)
                            val body = ByteArray(len)
                            input.readFully(body)
                            when (header and 0xF0) {
                                0x10 -> {
                                    val flags = body[7].toInt() and 0xFF
                                    var pos = 10
                                    fun str(): String {
                                        val l = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
                                        val v = String(body, pos + 2, l)
                                        pos += 2 + l
                                        return v
                                    }
                                    clientId = str()
                                    if (flags and 0x04 != 0) { str(); str() }
                                    if (flags and 0x80 != 0) username = str()
                                    out.write(byteArrayOf(0x20, 2, 0, connackCode.toByte()))
                                    out.flush()
                                    connected.countDown()
                                }
                                0x30 -> {
                                    val tl = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                                    val topic = String(body, 2, tl)
                                    val payload = String(body, 2 + tl, body.size - 2 - tl)
                                    published += Triple(topic, payload, header and 1 == 1)
                                }
                                0xC0 -> {
                                    out.write(byteArrayOf(0xD0.toByte(), 0))
                                    out.flush()
                                }
                                0xE0 -> return@use
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun readLen(i: InputStream): Int {
            var mult = 1
            var v = 0
            while (true) {
                val b = i.read()
                v += (b and 0x7F) * mult
                if (b and 0x80 == 0) return v
                mult *= 128
            }
        }

        val port get() = server.localPort

        override fun close() = server.close()
    }

    private fun cfg(port: Int, discovery: Boolean = true) = MqttConfig(
        host = "127.0.0.1", port = port, username = "ha", password = "secret", prefix = "music_light_synk/living",
        discovery = discovery, tls = false, deviceName = "Amazon AFTKRT", model = "AFTKRT",
    )

    private val playing = NowPlaying("com.spotify.tv.android", "Spotify", "Song", "Artist", "Album", true, null, null)

    private fun payload() = Payloads.colors("colors", "track", true, "artwork", playing, ColorExtractor.testPalette(), "Amazon AFTKRT")

    @Test
    fun bridgePublishesEventColorsStateAndDiscovery() {
        FakeBroker().use { broker ->
            val bridge = MqttBridge()
            val r = bridge.publish(cfg(broker.port), payload())
            assertTrue(r.message, r.ok)
            assertTrue("connected", broker.connected.await(2, TimeUnit.SECONDS))
            assertEquals("ha", broker.username)
            assertTrue("client id ${broker.clientId}", broker.clientId!!.length <= 23)
            Thread.sleep(300)
            val topics = broker.published.map { it.first }
            assertTrue("$topics", topics.contains("music_light_synk/living/availability"))
            assertTrue("$topics", topics.contains("homeassistant/sensor/music_light_synk_living/top_left/config"))
            assertTrue("$topics", topics.contains("homeassistant/binary_sensor/music_light_synk_living/playing/config"))
            val event = broker.published.first { it.first == "music_light_synk/living/event" }
            assertFalse("event must not be retained", event.third)
            assertEquals("colors", JSONObject(event.second).getString("event"))
            val colors = broker.published.first { it.first == "music_light_synk/living/colors" }
            assertTrue("colors must be retained", colors.third)
            val state = JSONObject(broker.published.first { it.first == "music_light_synk/living/state" }.second)
            assertTrue("playing", state.getBoolean("playing"))
            assertEquals("Song", state.getString("title"))
            assertFalse("state topic carries no colours", state.has("colors"))
            // second publish reuses the connection and doesn't resend discovery
            val before = broker.published.size
            bridge.publish(cfg(broker.port), Payloads.stopped(null, "x"))
            Thread.sleep(300)
            val newTopics = broker.published.drop(before).map { it.first }
            assertEquals(listOf("music_light_synk/living/event", "music_light_synk/living/state"), newTopics)
            bridge.close()
        }
    }

    @Test
    fun refusedConnectionIsReported() {
        FakeBroker(connackCode = 4).use { broker ->
            val r = MqttBridge().publish(cfg(broker.port), payload())
            assertFalse(r.ok)
            assertTrue(r.message, r.message.contains("bad username or password"))
        }
    }

    @Test
    fun unreachableBrokerFailsFast() {
        val port = ServerSocket(0).use { it.localPort } // closed port
        val t0 = System.currentTimeMillis()
        val r = MqttBridge().publish(cfg(port), payload())
        assertFalse(r.ok)
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
    }

    @Test
    fun discoveryTemplatesPointAtTheRightSlot() {
        val msgs = MqttBridge.discoveryMessages(cfg(1883))
        assertEquals(Slot.values().size + 2, msgs.size)
        val top = msgs.first { it.first.endsWith("/top/config") }.second
        assertEquals("{{ value_json.colors.top.hex }}", top.getString("value_template"))
        assertEquals("music_light_synk/living/colors", top.getString("state_topic"))
        assertEquals("music_light_synk_living_top", top.getString("unique_id"))
    }
}
