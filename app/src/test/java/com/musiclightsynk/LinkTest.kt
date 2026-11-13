package com.musiclightsynk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LinkTest {

    // ---- protocol ------------------------------------------------------------------------------

    @Test
    fun everyMessageRoundTrips() {
        val p = ColorExtractor.testPalette()
        val msgs = listOf(
            LinkProtocol.Hi("tv1", "Fire TV", "tv", listening = false),
            LinkProtocol.Pal("tv1", LinkProtocol.colorsOf(p), playing = true, title = "Song - Artist"),
            LinkProtocol.Nrg("ph1", AudioLevels(0.5f, 0.25f, 0.125f, beat = true)),
            LinkProtocol.Claim("ph1", LightHub.PRIO_LISTENING, 12_345),
            LinkProtocol.Wled("tv1", "192.168.1.60=dominant:energy", 1_700_000_000_000),
        )
        for (m in msgs) assertEquals(m, LinkProtocol.decode(LinkProtocol.encode(m)))
    }

    @Test
    fun paletteSurvivesTheTripWithTheReceiversSettings() {
        val p = ColorExtractor.testPalette()
        val back = LinkProtocol.palette(LinkProtocol.colorsOf(p), ExtractOptions())
        assertEquals(0, p.maxDistanceTo(back))
    }

    @Test
    fun strangersAndGarbageAreIgnored() {
        assertNull(LinkProtocol.decode("hello".toByteArray()))
        assertNull(LinkProtocol.decode("""{"v":1,"g":"other","id":"x","t":"hi"}""".toByteArray()))
        assertNull(LinkProtocol.decode("""{"v":1,"g":"home","t":"hi"}""".toByteArray())) // no id
        assertNull(LinkProtocol.decode("""{"v":1,"g":"home","id":"x","t":"pal","pal":{"top":"#FF0000"}}""".toByteArray()))
        val loud = LinkProtocol.decode("""{"v":1,"g":"home","id":"x","t":"nrg","l":7,"m":-1,"h":0.5}""".toByteArray())
        assertEquals(AudioLevels(1f, 0f, 0.5f), (loud as LinkProtocol.Nrg).levels) // clamped to 0..1
    }

    // ---- who drives WLED ---------------------------------------------------------------------

    @Test
    fun aloneYouRender() {
        val hub = LightHub("me")
        assertTrue(hub.mayRender(LightHub.PRIO_LISTENING, 0, 1000))
        assertFalse(hub.mayRender(0, 0, 1000))
    }

    @Test
    fun theDevicePlayingMusicBeatsTheBackupListener() {
        val phone = LightHub("phone")
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 5_000, 1000)
        assertFalse(phone.mayRender(LightHub.PRIO_LISTENING, 60_000, 1500))
        assertEquals("tv", phone.otherRenderer(1500))
        val tv = LightHub("tv")
        tv.onClaim("phone", LightHub.PRIO_LISTENING, 60_000, 1000)
        assertTrue(tv.mayRender(LightHub.PRIO_PLAYING, 5_000, 1500))
    }

    @Test
    fun claimsExpireAndCanBeDropped() {
        val phone = LightHub("phone", ttlMs = 2500)
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 0, 1000)
        assertFalse(phone.mayRender(LightHub.PRIO_LISTENING, 0, 3000))
        assertTrue("TV went quiet", phone.mayRender(LightHub.PRIO_LISTENING, 0, 3600))
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 0, 4000)
        phone.onClaim("tv", 0, 0, 4100) // TV stopped playing
        assertTrue(phone.mayRender(LightHub.PRIO_LISTENING, 0, 4200))
        assertNull(phone.otherRenderer(4200))
    }

    @Test
    fun twoPlayersTheOneStartedLastWinsOnBothSides() {
        // TV has played for 10 min; the phone just started a song.
        val tv = LightHub("tv")
        val phone = LightHub("phone")
        tv.onClaim("phone", LightHub.PRIO_PLAYING, 2_000, 1000)
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 600_000, 1000)
        assertFalse(tv.mayRender(LightHub.PRIO_PLAYING, 600_200, 1200))
        assertTrue(phone.mayRender(LightHub.PRIO_PLAYING, 2_200, 1200))
    }

    @Test
    fun exactTieGoesToTheLowerIdOnBothSides() {
        val a = LightHub("aaa")
        val b = LightHub("bbb")
        a.onClaim("bbb", LightHub.PRIO_LISTENING, 1000, 0)
        b.onClaim("aaa", LightHub.PRIO_LISTENING, 1000, 0)
        assertTrue(a.mayRender(LightHub.PRIO_LISTENING, 1000, 0))
        assertFalse(b.mayRender(LightHub.PRIO_LISTENING, 1000, 0))
    }

    @Test
    fun ownClaimsAreIgnored() {
        val hub = LightHub("me")
        hub.onClaim("me", LightHub.PRIO_PLAYING, 0, 0)
        assertTrue(hub.mayRender(LightHub.PRIO_LISTENING, 0, 10))
    }

    // ---- sockets -------------------------------------------------------------------------------

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    @Test
    fun twoDevicesFindEachOtherAndTalk() {
        val portTv = freePort()
        val portPhone = freePort()
        val atTv = LinkedBlockingQueue<LinkProtocol.Msg>()
        val atPhone = LinkedBlockingQueue<LinkProtocol.Msg>()
        val tv = Link("tv", portTv) { atTv += it }
        val phone = Link("phone", portPhone) { atPhone += it }
        val lo = InetAddress.getLoopbackAddress()
        // On a real network "hi" is broadcast on the shared port; here each side "broadcasts" to the other.
        tv.broadcastTargets = listOf(InetSocketAddress(lo, portPhone), InetSocketAddress(lo, portTv))
        phone.broadcastTargets = listOf(InetSocketAddress(lo, portTv))
        try {
            val tvUp = tv.start()
            assertTrue(tv.lastError.toString(), tvUp)
            val phoneUp = phone.start()
            assertTrue(phone.lastError.toString(), phoneUp)
            tv.broadcast(LinkProtocol.Hi("tv", "Fire TV", "tv"))
            val hi = atPhone.poll(2, TimeUnit.SECONDS)
            assertEquals("Fire TV", (hi as LinkProtocol.Hi).name)
            assertNull("own broadcast is ignored", atTv.poll(300, TimeUnit.MILLISECONDS))
            assertEquals(listOf("Fire TV"), phone.peers().map { it.name })

            // the phone now knows the TV and can send to it directly
            phone.sendToPeers(LinkProtocol.Nrg("phone", AudioLevels(0.7f, 0.2f, 0.1f, true)))
            val nrg = atTv.poll(2, TimeUnit.SECONDS) as LinkProtocol.Nrg
            assertEquals(0.7f, nrg.levels.lows, 0.001f)
            assertTrue(nrg.levels.beat)
            // and the TV learnt the phone from that packet
            tv.sendToPeers(LinkProtocol.Claim("tv", LightHub.PRIO_PLAYING, 1000))
            assertEquals(LightHub.PRIO_PLAYING, (atPhone.poll(2, TimeUnit.SECONDS) as LinkProtocol.Claim).prio)
        } finally {
            tv.stop()
            phone.stop()
        }
        assertFalse(tv.isRunning)
    }
}
