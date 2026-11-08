package com.musiclightsynk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PayloadsTest {

    private val np = NowPlaying(
        packageName = "com.spotify.tv.android",
        appLabel = "Spotify",
        title = "Blinding Lights",
        artist = "The Weeknd",
        album = "After Hours",
        playing = true,
        art = null,
        artUri = null,
    )

    @Test
    fun colorsPayloadHasEverySlotInHomeAssistantFriendlyShape() {
        val json = Payloads.colors("colors", "track", true, "artwork", np, ColorExtractor.testPalette(), "Amazon AFTKRT")
        assertEquals("colors", json.getString("event"))
        assertTrue(json.getBoolean("session_start"))
        assertEquals("artwork", json.getString("source"))
        assertEquals("Spotify", json.getString("app"))
        val colors = json.getJSONObject("colors")
        for (slot in Slot.values()) {
            val c = colors.getJSONObject(slot.key)
            assertTrue(c.getString("hex").matches(Regex("#[0-9A-F]{6}")))
            assertEquals(3, c.getJSONArray("rgb").length())
            val hs = c.getJSONArray("hs")
            assertEquals(2, hs.length())
            assertTrue(hs.getDouble(0) in 0.0..360.0)
            assertTrue(hs.getDouble(1) in 0.0..100.0)
            assertTrue(c.getInt("brightness") in 1..255)
        }
        // Test palette: top-left is red, top-right green
        assertTrue(colors.getJSONObject("top_left").getJSONArray("hs").getDouble(0) < 10.0)
        assertTrue(colors.getJSONObject("top_right").getJSONArray("hs").getDouble(0) in 100.0..140.0)
        writeSample("colors.json", json)
    }

    @Test
    fun stoppedPayload() {
        val json = Payloads.stopped(np.copy(playing = false), "Amazon AFTKRT")
        assertEquals("stopped", json.getString("event"))
        assertFalse(json.getBoolean("playing"))
        assertFalse(json.has("colors"))
        writeSample("stopped.json", json)
    }

    @Test
    fun nullTrackInfoBecomesJsonNull() {
        val json = Payloads.colors("test", "test", false, "test", null, ColorExtractor.testPalette(), "x")
        assertTrue(json.isNull("title"))
        assertTrue(json.isNull("app"))
    }

    @Test
    fun sensorStateMirrorsDominantHex() {
        val payload = Payloads.colors("colors", "track", false, "screen", np, ColorExtractor.testPalette(), "x")
        val st = Payloads.sensorState(payload)
        assertEquals(payload.getJSONObject("colors").getJSONObject("dominant").getString("hex"), st.getString("state"))
        assertEquals("Music Light Synk", st.getJSONObject("attributes").getString("friendly_name"))
        assertEquals("idle", Payloads.sensorState(Payloads.stopped(np, "x")).getString("state"))
        writeSample("sensor_state.json", st)
    }

    private fun writeSample(name: String, json: JSONObject) {
        val dir = File("build/sample-payloads")
        dir.mkdirs()
        File(dir, name).writeText(json.toString(2))
    }
}
