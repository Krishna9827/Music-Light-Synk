package com.musiclightsynk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sin

class WledTest {

    private val red = Rgb(255, 0, 0)
    private val green = Rgb(0, 255, 0)
    private val blue = Rgb(0, 0, 255)
    private val white = Rgb(255, 255, 255)

    /** Each corner a distinct colour, sides = their own colour too. */
    private val colors = mapOf(
        Slot.TOP_LEFT to red, Slot.TOP to Rgb(255, 128, 0), Slot.TOP_RIGHT to Rgb(255, 255, 0),
        Slot.RIGHT to green, Slot.BOTTOM_RIGHT to Rgb(0, 255, 255), Slot.BOTTOM to blue,
        Slot.BOTTOM_LEFT to Rgb(128, 0, 255), Slot.LEFT to white,
    )

    private fun rgb(c: Int) = Rgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test
    fun parseLayout() {
        val l = WledLayout.parse("40, 22,40,22", Corner.BOTTOM_LEFT, true)!!
        assertEquals(124, l.total)
        assertNull(WledLayout.parse("40,22,40", Corner.BOTTOM_LEFT, true))
        assertNull(WledLayout.parse("a,b,c,d", Corner.BOTTOM_LEFT, true))
        assertNull(WledLayout.parse("0,0,0,0", Corner.BOTTOM_LEFT, true))
        assertNotNull(WledLayout.parse("30,20,0,20", Corner.BOTTOM_LEFT, true)) // no strip at the bottom
    }

    @Test
    fun clockwiseFromBottomLeftGoesUpTheLeftSideFirst() {
        val l = WledLayout(top = 10, right = 6, bottom = 10, left = 6, start = Corner.BOTTOM_LEFT, clockwise = true)
        val f = l.render { colors.getValue(it) }
        assertEquals(32, f.size)
        // LED 0 is near the bottom-left corner, left side middle (white) is at LED 2-3, top-left reached at LED 5
        assertTrue(rgb(f[0]).distanceTo(colors.getValue(Slot.BOTTOM_LEFT)) < 80)
        assertTrue(rgb(f[3]).distanceTo(white) < 60)
        // then the top edge left -> right: middle of top is orange
        assertTrue(rgb(f[6 + 5]).distanceTo(Rgb(255, 128, 0)) < 40)
        // last LED is on the bottom edge, near the bottom-left corner again
        assertTrue(rgb(f[31]).distanceTo(colors.getValue(Slot.BOTTOM_LEFT)) < 80)
    }

    @Test
    fun counterClockwiseFromBottomLeftGoesAlongTheBottomFirst() {
        val l = WledLayout(10, 6, 10, 6, Corner.BOTTOM_LEFT, clockwise = false)
        val f = l.render { colors.getValue(it) }
        assertTrue(rgb(f[0]).distanceTo(colors.getValue(Slot.BOTTOM_LEFT)) < 80)
        assertTrue(rgb(f[5]).distanceTo(blue) < 60) // middle of the bottom edge
        assertTrue(rgb(f[10 + 3]).distanceTo(green) < 60) // middle of the right edge, going up
    }

    @Test
    fun cwAndCcwAreMirrorImages() {
        val cw = WledLayout(8, 4, 8, 4, Corner.TOP_LEFT, true).render { colors.getValue(it) }
        val ccw = WledLayout(8, 4, 8, 4, Corner.TOP_LEFT, false).render { colors.getValue(it) }
        // Same perimeter walked the other way round: ccw[i] == cw[n-1-i]
        for (i in cw.indices) assertEquals("led $i", cw[cw.size - 1 - i], ccw[i])
    }

    @Test
    fun dnrgbPacketsSplitAt489Leds() {
        val frame = IntArray(600) { 0x102030 }
        val p = WledPackets.dnrgb(frame, 2)
        assertEquals(2, p.size)
        assertEquals(4 + 489 * 3, p[0].size)
        assertEquals(4, p[0][0].toInt())
        assertEquals(2, p[0][1].toInt())
        assertEquals(0, p[0][2].toInt())
        assertEquals(0, p[0][3].toInt())
        assertEquals(0x10, p[0][4].toInt())
        assertEquals(0x20, p[0][5].toInt())
        assertEquals(0x30, p[0][6].toInt())
        // second packet starts at LED 489 = 0x01E9
        assertEquals(0x01, p[1][2].toInt())
        assertEquals(0xE9.toByte(), p[1][3])
        assertEquals(4 + 111 * 3, p[1].size)
    }

    @Test
    fun ambientSendsFramesThenStops() {
        DatagramSocket(0).use { rx ->
            rx.soTimeout = 2000
            val ambient = WledAmbient(rx.localPort)
            ambient.show(mapOf("127.0.0.1" to WledAmbient.Strip(IntArray(10) { 0xFF0000 })), StripStyle())
            val buf = ByteArray(2048)
            var last = ByteArray(0)
            val t0 = System.currentTimeMillis()
            // the fade lasts ~700 ms at ~30 fps; collect until we see full red
            while (System.currentTimeMillis() - t0 < 2500) {
                val pkt = DatagramPacket(buf, buf.size)
                rx.receive(pkt)
                last = buf.copyOf(pkt.length)
                if ((last[4].toInt() and 0xFF) == 255) break
            }
            assertEquals(4, last[0].toInt())
            assertEquals(4 + 10 * 3, last.size)
            assertEquals(255, last[4].toInt() and 0xFF)
            assertTrue(ambient.framesSent > 5) // it faded in
            ambient.stop()
            Thread.sleep(200)
            // after stop: one final packet with a 1 s timeout, then silence
            rx.soTimeout = 1500
            val got = ArrayList<ByteArray>()
            try {
                while (true) {
                    val pkt = DatagramPacket(buf, buf.size)
                    rx.receive(pkt)
                    got += buf.copyOf(pkt.length)
                }
            } catch (_: java.net.SocketTimeoutException) {
            }
            assertTrue("no keep-alives after stop: ${got.size}", got.size <= 2)
            assertFalse(ambient.running)
            ambient.shutdown()
        }
    }

    /**
     * Tiny fake WLED: GET /json/state returns [state], POST records the body. Plain sockets,
     * because Android unit tests can't use the JDK's com.sun.net.httpserver.
     */
    private class FakeWled(private val state: String, private val info: String = "{}") : AutoCloseable {
        private val server = ServerSocket(0)
        val posts = CopyOnWriteArrayList<JSONObject>()
        val host get() = "127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        server.accept().use { sock ->
                            val input = sock.getInputStream().bufferedReader()
                            val requestLine = input.readLine() ?: return@use
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                            }
                            val body = if (requestLine.startsWith("GET /json/info")) info else if (requestLine.startsWith("GET")) state else {
                                val buf = CharArray(length)
                                var read = 0
                                while (read < length) read += input.read(buf, read, length - read)
                                posts += JSONObject(String(buf))
                                """{"success":true}"""
                            }
                            val bytes = body.toByteArray()
                            val out = sock.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes)
                            out.flush()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    @Test
    fun colorsModeSavesSetsAndRestoresWledState() {
        val original = """{"on":true,"bri":90,"ps":3,"pl":-1,"seg":[{"id":0,"fx":15,"pal":11,"col":[[1,2,3],[0,0,0],[0,0,0]]}]}"""
        FakeWled(original).use { wled ->
            val posts = wled.posts
            val cfg = WledConfig(
                devices = listOf(WledDevice(wled.host, WledZone.Palette(listOf(Slot.DOMINANT, Slot.VIBRANT, Slot.AVERAGE), fx = -1, pal = 5))),
                brightness = 1f, restore = true, pulse = false, pulseStrength = 0f,
            )
            val c = WledController()
            val palette = ColorExtractor.testPalette()
            val r = c.onColors(cfg, palette, sessionStart = true)
            assertTrue(r.message, r.ok)
            val set = posts.last()
            assertEquals(3, set.getJSONObject("seg").getJSONArray("col").length())
            assertEquals(5, set.getJSONObject("seg").getInt("pal"))
            assertFalse("fx -1 means keep the effect", set.getJSONObject("seg").has("fx"))
            // next track: no second save, just colours
            c.onColors(cfg, palette, sessionStart = false)
            val stop = c.onStopped(cfg)
            assertTrue(stop.message, stop.message.contains("restored"))
            val restore = posts.last()
            assertEquals(15, restore.getJSONArray("seg").getJSONObject(0).getInt("fx"))
            assertEquals(90, restore.getInt("bri"))
            assertFalse("preset pointer must not be re-applied", restore.has("ps"))
            assertEquals(3, posts.size)
        }
    }

    @Test
    fun beatDetectorFindsKickDrums() {
        val d = BeatDetector()
        var beats = 0
        // 20 frames/s for 10 s; a kick every 0.5 s (120 BPM) on top of low noise
        for (frame in 0 until 200) {
            val t = frame * 50L
            val kick = frame % 10 == 0
            val energy = if (kick) 1.0f else 0.12f + 0.02f * sin(frame * 0.7f)
            if (d.feed(energy, t)) beats++
        }
        assertTrue("beats=$beats", beats in 17..20)
    }

    @Test
    fun beatDetectorIgnoresSteadySoundAndSilence() {
        val d = BeatDetector()
        var beats = 0
        for (frame in 0 until 200) if (d.feed(0.3f, frame * 50L)) beats++
        for (frame in 200 until 400) if (d.feed(0f, frame * 50L)) beats++
        assertEquals(0, beats)
    }

    // ---- multiple devices / zones -------------------------------------------------------------

    @Test
    fun deviceSpecParsesEveryZoneKind() {
        val p = WledDevices.parse(
            "192.168.1.60=ambient:40,22,40,22:bottom_left:cw; 192.168.1.61=top_left ;" +
                "wled-left.local=left:keep\n192.168.1.63=palette:dominant,vibrant:fx=28:pal=5; http://10.0.0.9/=dominant",
        )
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(5, p.devices.size)
        val amb = p.devices[0].zone as WledZone.Ambient
        assertEquals(124, amb.layout.total)
        assertEquals(Corner.BOTTOM_LEFT, amb.layout.start)
        assertEquals(WledZone.Section(Slot.TOP_LEFT, false), p.devices[1].zone)
        assertEquals(WledZone.Section(Slot.LEFT, true), p.devices[2].zone)
        assertEquals(WledZone.Palette(listOf(Slot.DOMINANT, Slot.VIBRANT), 28, 5), p.devices[3].zone)
        assertEquals("10.0.0.9", p.devices[4].host)
    }

    @Test
    fun deviceSpecRoundTripsAndReportsErrors() {
        val raw = "192.168.1.60=ambient:40,22,0,22:top_right:ccw; 192.168.1.61=bottom; 192.168.1.62=palette; 192.168.1.63=palette:top,bottom:fx=0"
        val p = WledDevices.parse(raw)
        assertEquals(raw, WledDevices.format(p.devices))
        val bad = WledDevices.parse("no-equals; 1.2.3.4=sideways; 1.2.3.5=ambient:1,2,3; 1.2.3.6=palette:fx=abc; 1.2.3.7=top")
        assertEquals(4, bad.errors.size)
        assertEquals(listOf("1.2.3.7"), bad.devices.map { it.host })
    }

    @Test
    fun cyclingZonesVisitsEverySectionThenPaletteThenAmbient() {
        var z: WledZone = WledZone.Section(Slot.TOP_LEFT)
        val seen = ArrayList<String>()
        repeat(Slot.values().size + 2) {
            seen += WledDevices.formatZone(z).substringBefore(':')
            z = WledDevices.nextZone(z)
        }
        assertEquals(Slot.values().map { it.key } + listOf("palette", "ambient"), seen)
        assertEquals(WledZone.Section(Slot.TOP_LEFT), z) // and wraps round
    }

    @Test
    fun sectionBodySetsOneSolidColourOrKeepsTheEffect() {
        val palette = ColorExtractor.testPalette()
        val solid = WledController.bodyFor(WledZone.Section(Slot.RIGHT), palette, 1f)
        val seg = solid.getJSONObject("seg")
        assertEquals(1, seg.getJSONArray("col").length())
        assertEquals(0, seg.getInt("fx")) // Solid
        val rgb = seg.getJSONArray("col").getJSONArray(0)
        assertEquals(palette[Slot.RIGHT].light, Rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2)))
        assertEquals(palette[Slot.RIGHT].brightness, solid.getInt("bri"))
        val keep = WledController.bodyFor(WledZone.Section(Slot.RIGHT, keepEffect = true), palette, 0.5f)
        assertFalse(keep.getJSONObject("seg").has("fx"))
        assertEquals(Math.round(palette[Slot.RIGHT].brightness * 0.5f), keep.getInt("bri"))
    }

    @Test
    fun severalDevicesEachGetTheirOwnZone() {
        DatagramSocket(0).use { rx ->
            FakeWled("""{"on":true,"bri":10,"seg":[{"id":0,"fx":9}]}""").use { left ->
                FakeWled("""{"on":false,"bri":200,"seg":[{"id":0,"fx":0}]}""").use { right ->
                    val c = WledController(rx.localPort)
                    val cfg = WledConfig(
                        devices = listOf(
                            WledDevice("127.0.0.1", WledZone.Ambient(WledLayout(4, 2, 4, 2, Corner.BOTTOM_LEFT, true))),
                            WledDevice(left.host, WledZone.Section(Slot.LEFT)),
                            WledDevice(right.host, WledZone.Section(Slot.RIGHT)),
                        ),
                        brightness = 1f, restore = true, pulse = false, pulseStrength = 0f,
                    )
                    val palette = ColorExtractor.testPalette()
                    val r = c.onColors(cfg, palette, sessionStart = true)
                    assertTrue(r.message, r.ok)
                    // ambient strip got a 12-LED UDP frame
                    rx.soTimeout = 2000
                    val buf = ByteArray(1024)
                    val pkt = DatagramPacket(buf, buf.size)
                    rx.receive(pkt)
                    assertEquals(4 + 12 * 3, pkt.length)
                    // each JSON device got its own section colour
                    fun colourOf(w: FakeWled) = w.posts.last().getJSONObject("seg").getJSONArray("col").getJSONArray(0)
                        .let { Rgb(it.getInt(0), it.getInt(1), it.getInt(2)) }
                    assertEquals(palette[Slot.LEFT].light, colourOf(left))
                    assertEquals(palette[Slot.RIGHT].light, colourOf(right))
                    // stop restores both JSON devices to their own saved state
                    c.onStopped(cfg)
                    assertEquals(9, left.posts.last().getJSONArray("seg").getJSONObject(0).getInt("fx"))
                    assertEquals(false, right.posts.last().getBoolean("on"))
                    c.ambient.shutdown()
                }
            }
        }
    }

    // ---- Energy effect per device ---------------------------------------------------------------

    @Test
    fun effectOptionsParseAndRoundTrip() {
        val raw = "192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror; 192.168.1.61=dominant:energy:leds=60; " +
            "192.168.1.62=left:mirror; 192.168.1.63=palette"
        val p = WledDevices.parse(raw)
        assertTrue(p.errors.toString(), p.errors.isEmpty())
        assertEquals(WledEffect(energy = true, flip = true, mirror = true), p.devices[0].effect)
        assertTrue(p.devices[0].realtime)
        assertEquals(WledEffect(energy = true), p.devices[1].effect)
        assertEquals(60, p.devices[1].leds)
        assertTrue("an Energy section goes over UDP", p.devices[1].realtime)
        assertFalse("mirror alone keeps the JSON solid colour", p.devices[2].realtime)
        assertEquals(raw, WledDevices.format(p.devices))
        // bar options mean nothing on a palette zone (WLED runs its own effect there)
        val pal = WledDevices.parse("1.2.3.4=palette:energy:flip").devices.single()
        assertEquals(WledEffect(), pal.effect)
        assertEquals("palette", WledDevices.formatDevice(pal))
        assertEquals(1, WledDevices.parse("1.2.3.4=top:leds=lots").errors.size)
        assertEquals("Energy, from both ends", p.devices[0].effectLabel)
    }

    @Test
    fun configKnowsWhenAudioIsWanted() {
        val amb = WledDevice("a", WledZone.Ambient(WledDevices.DEFAULT_LAYOUT))
        val energy = WledDevice("b", WledZone.Section(Slot.DOMINANT), WledEffect(energy = true))
        val plain = WledDevice("c", WledZone.Section(Slot.LEFT))
        fun cfg(vararg d: WledDevice, pulse: Boolean = false) = WledConfig(d.toList(), 1f, true, pulse, 0.5f)
        assertFalse(cfg(plain).wantsAudio)
        assertFalse(cfg(amb).wantsAudio)
        assertTrue(cfg(amb, pulse = true).wantsAudio)
        assertTrue(cfg(energy).wantsAudio)
        assertFalse("nothing to pulse on an Energy-only setup", cfg(energy, pulse = true).hasPulseTarget)
    }

    @Test
    fun energySectionStripBouncesOverUdpWithItsLedCount() {
        DatagramSocket(0).use { rx ->
            FakeWled("""{"on":true,"bri":128,"seg":[{"id":0,"fx":9}]}""", """{"leds":{"count":30}}""").use { strip ->
                val c = WledController(rx.localPort)
                val cfg = WledConfig(
                    devices = listOf(WledDevice(strip.host, WledZone.Section(Slot.DOMINANT), WledEffect(energy = true))),
                    brightness = 1f, restore = true, pulse = false, pulseStrength = 0f, energyBackground = 0f, energyBlur = 0,
                )
                val r = c.onColors(cfg, ColorExtractor.testPalette(), sessionStart = true)
                assertTrue(r.message, r.ok)
                assertTrue("no JSON state change for a realtime strip", strip.posts.isEmpty())
                rx.soTimeout = 2000
                val buf = ByteArray(2048)
                fun next(): ByteArray {
                    val pkt = DatagramPacket(buf, buf.size)
                    rx.receive(pkt)
                    return buf.copyOf(pkt.length)
                }
                assertEquals(4 + 30 * 3, next().size) // LED count came from /json/info
                fun lit(pkt: ByteArray) = (0 until 30).count { i -> (0..2).any { (pkt[4 + i * 3 + it].toInt() and 0xFF) > 8 } }
                // music: low bar at ~40 %, then silence-level audio
                val t0 = System.currentTimeMillis()
                var bar = -1
                while (System.currentTimeMillis() - t0 < 2500) {
                    c.audio(AudioLevels(0.4f, 0f, 0f))
                    val pkt = next()
                    if (System.currentTimeMillis() - t0 > 900) { // fade-in done
                        bar = lit(pkt)
                        break
                    }
                }
                assertTrue("about 40 % of 30 LEDs lit, got $bar", bar in 9..15)
                val t1 = System.currentTimeMillis()
                var quiet = -1
                while (System.currentTimeMillis() - t1 < 1500) {
                    c.audio(AudioLevels(0.05f, 0f, 0f))
                    quiet = lit(next())
                }
                assertTrue("the bar falls back, got $quiet", quiet in 0..3)
                c.onStopped(cfg)
                c.ambient.shutdown()
            }
        }
    }
}
