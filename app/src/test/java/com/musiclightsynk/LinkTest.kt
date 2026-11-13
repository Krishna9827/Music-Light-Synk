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
            LinkProtocol.Hi("tv1", "Fire TV", "tv", room = "Living room", listening = false, src = "ph1"),
            LinkProtocol.Hi("ph1", "Pixel 8", "phone", listening = true, mic = true),
            LinkProtocol.Pal("tv1", LinkProtocol.colorsOf(p), playing = true, title = "Song - Artist"),
            LinkProtocol.Nrg("ph1", AudioLevels(0.5f, 0.25f, 0.125f, beat = true)),
            LinkProtocol.Claim("ph1", LightHub.PRIO_LISTENING, 12_345, setOf("192.168.1.60", "192.168.1.61")),
            LinkProtocol.Claim("ph1", 0, 0, emptySet()),
        )
        for (m in msgs) assertEquals(m, LinkProtocol.decode(LinkProtocol.encode(m)))
    }

    @Test
    fun olderAppsAreUnderstoodSafely() {
        // v0.6 claim without hosts: treated as claiming every WLED (never fight over strips)
        val old = LinkProtocol.decode("""{"v":1,"g":"home","id":"tv","t":"claim","prio":3,"age":10}""".toByteArray())
        assertNull((old as LinkProtocol.Claim).hosts)
        // v0.6 WLED list sync is ignored: every device keeps its own list now
        assertNull(LinkProtocol.decode("""{"v":1,"g":"home","id":"tv","t":"wled","devices":"1.2.3.4=top","rev":5}""".toByteArray()))
        // v0.6 hi without a room = the default room
        val hi = LinkProtocol.decode("""{"v":1,"g":"home","id":"tv","t":"hi","name":"TV","plat":"tv"}""".toByteArray()) as LinkProtocol.Hi
        assertTrue(LinkProtocol.sameRoom(hi.room, "Home"))
    }

    @Test
    fun roomsAndHostsCompareLoosely() {
        assertTrue(LinkProtocol.sameRoom("Living Room", " living room"))
        assertTrue(LinkProtocol.sameRoom("", "home"))
        assertFalse(LinkProtocol.sameRoom("Bedroom", "Living room"))
        assertEquals("192.168.1.60", LinkProtocol.normHost("http://192.168.1.60:80/"))
        assertEquals("wled-tv.local", LinkProtocol.normHost("WLED-TV.local"))
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
        assertNull(LinkProtocol.decode("""{"v":2,"g":"other","id":"x","t":"hi"}""".toByteArray()))
        assertNull(LinkProtocol.decode("""{"v":2,"g":"home","t":"hi"}""".toByteArray())) // no id
        assertNull(LinkProtocol.decode("""{"v":2,"g":"home","id":"x","t":"pal","pal":{"top":"#FF0000"}}""".toByteArray()))
        val loud = LinkProtocol.decode("""{"v":2,"g":"home","id":"x","t":"nrg","l":7,"m":-1,"h":0.5}""".toByteArray())
        assertEquals(AudioLevels(1f, 0f, 0.5f), (loud as LinkProtocol.Nrg).levels) // clamped to 0..1
    }

    // ---- who drives which WLED ---------------------------------------------------------------

    private val tvStrips = setOf("10.0.0.60", "10.0.0.61")
    private val bedroom = setOf("10.0.0.70")

    @Test
    fun aloneYouDriveAllYourStrips() {
        val hub = LightHub("me")
        assertEquals(tvStrips, hub.allowedHosts(LightHub.PRIO_LISTENING, 0, tvStrips, 1000))
        assertTrue(hub.allowedHosts(0, 0, tvStrips, 1000).isEmpty())
    }

    @Test
    fun aPhoneInAnotherRoomNeverGivesWayToTheTv() {
        val phone = LightHub("phone")
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 5_000, tvStrips, 1000)
        // the TV plays in the living room; the phone's own bedroom strip is unaffected, even as a listener
        assertEquals(bedroom, phone.allowedHosts(LightHub.PRIO_LISTENING, 60_000, bedroom, 1500))
        assertTrue(phone.othersOn(bedroom, 1500).isEmpty())
    }

    @Test
    fun onASharedStripThePlayerBeatsTheListener() {
        val phone = LightHub("phone")
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 5_000, setOf("10.0.0.60"), 1000)
        // the phone lists the TV strip and a lamp of its own: it keeps the lamp
        assertEquals(setOf("10.0.0.61"), phone.allowedHosts(LightHub.PRIO_LISTENING, 60_000, tvStrips, 1500))
        assertEquals(listOf("tv"), phone.othersOn(tvStrips, 1500))
        assertEquals(setOf("10.0.0.60"), phone.claimedByOthers(tvStrips, 1500))
        val tv = LightHub("tv")
        tv.onClaim("phone", LightHub.PRIO_LISTENING, 60_000, tvStrips, 1000)
        assertEquals(tvStrips, tv.allowedHosts(LightHub.PRIO_PLAYING, 5_000, tvStrips, 1500))
    }

    @Test
    fun anOlderAppWithoutHostsIsAssumedToDriveEverything() {
        val phone = LightHub("phone")
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 5_000, null, 1000)
        assertTrue(phone.allowedHosts(LightHub.PRIO_LISTENING, 0, bedroom, 1500).isEmpty())
    }

    @Test
    fun claimsExpireAndCanBeDropped() {
        val phone = LightHub("phone", ttlMs = 2500)
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 0, tvStrips, 1000)
        assertTrue(phone.allowedHosts(LightHub.PRIO_LISTENING, 0, tvStrips, 3000).isEmpty())
        assertEquals("TV went quiet", tvStrips, phone.allowedHosts(LightHub.PRIO_LISTENING, 0, tvStrips, 3600))
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 0, tvStrips, 4000)
        phone.onClaim("tv", 0, 0, emptySet(), 4100) // TV stopped playing
        assertEquals(tvStrips, phone.allowedHosts(LightHub.PRIO_LISTENING, 0, tvStrips, 4200))
        assertTrue(phone.othersOn(tvStrips, 4200).isEmpty())
    }

    @Test
    fun twoPlayersOnTheSameStripTheOneStartedLastWinsOnBothSides() {
        // TV has played for 10 min; the phone just started a song; both list the TV strips.
        val tv = LightHub("tv")
        val phone = LightHub("phone")
        tv.onClaim("phone", LightHub.PRIO_PLAYING, 2_000, tvStrips, 1000)
        phone.onClaim("tv", LightHub.PRIO_PLAYING, 600_000, tvStrips, 1000)
        assertTrue(tv.allowedHosts(LightHub.PRIO_PLAYING, 600_200, tvStrips, 1200).isEmpty())
        assertEquals(tvStrips, phone.allowedHosts(LightHub.PRIO_PLAYING, 2_200, tvStrips, 1200))
    }

    @Test
    fun exactTieGoesToTheLowerIdOnBothSides() {
        val a = LightHub("aaa")
        val b = LightHub("bbb")
        a.onClaim("bbb", LightHub.PRIO_LISTENING, 1000, bedroom, 0)
        b.onClaim("aaa", LightHub.PRIO_LISTENING, 1000, bedroom, 0)
        assertEquals(bedroom, a.allowedHosts(LightHub.PRIO_LISTENING, 1000, bedroom, 0))
        assertTrue(b.allowedHosts(LightHub.PRIO_LISTENING, 1000, bedroom, 0).isEmpty())
    }

    @Test
    fun ownClaimsAreIgnored() {
        val hub = LightHub("me")
        hub.onClaim("me", LightHub.PRIO_PLAYING, 0, bedroom, 0)
        assertEquals(bedroom, hub.allowedHosts(LightHub.PRIO_LISTENING, 0, bedroom, 10))
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
            tv.broadcast(LinkProtocol.Hi("tv", "Fire TV", "tv", room = "Living room"))
            val hi = atPhone.poll(2, TimeUnit.SECONDS)
            assertEquals("Fire TV", (hi as LinkProtocol.Hi).name)
            assertEquals("Living room", phone.peers().single().room)
            assertNull("own broadcast is ignored", atTv.poll(300, TimeUnit.MILLISECONDS))
            assertEquals(listOf("Fire TV"), phone.peers().map { it.name })

            // the phone now knows the TV and can send to it directly
            phone.sendToPeers(LinkProtocol.Nrg("phone", AudioLevels(0.7f, 0.2f, 0.1f, true)))
            val nrg = atTv.poll(2, TimeUnit.SECONDS) as LinkProtocol.Nrg
            assertEquals(0.7f, nrg.levels.lows, 0.001f)
            assertTrue(nrg.levels.beat)
            // the TV picks the phone's microphone: the phone streams only to devices that did
            tv.broadcastTargets = listOf(InetSocketAddress(lo, portPhone))
            tv.broadcast(LinkProtocol.Hi("tv", "Fire TV", "tv", src = "phone"))
            atPhone.poll(2, TimeUnit.SECONDS)
            assertEquals("phone", phone.peers().single().src)
            phone.sendToPeers(LinkProtocol.Nrg("phone", AudioLevels(0.3f, 0f, 0f))) { it.src == "phone" }
            assertTrue(atTv.poll(2, TimeUnit.SECONDS) is LinkProtocol.Nrg)
            phone.sendToPeers(LinkProtocol.Nrg("phone", AudioLevels(0.3f, 0f, 0f))) { it.src == "someone-else" }
            assertNull("not streamed to a device that didn't pick this mic", atTv.poll(300, TimeUnit.MILLISECONDS))
            // and the TV learnt the phone from that packet
            tv.sendToPeers(LinkProtocol.Claim("tv", LightHub.PRIO_PLAYING, 1000, tvStrips))
            assertEquals(LightHub.PRIO_PLAYING, (atPhone.poll(2, TimeUnit.SECONDS) as LinkProtocol.Claim).prio)
        } finally {
            tv.stop()
            phone.stop()
        }
        assertFalse(tv.isRunning)
    }
}
