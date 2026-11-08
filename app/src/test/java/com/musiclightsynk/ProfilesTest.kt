package com.musiclightsynk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    @Test
    fun parsesMixedSeparatorsAndIgnoresJunk() {
        val m = Profiles.parse(
            "com.google.android.youtube.tv=screen; com.spotify.tv.android = artwork,com.netflix.ninja=off\n" +
                "broken;=screen;com.x=default;com.y=nonsense",
        )
        assertEquals(AppProfile.SCREEN, m["com.google.android.youtube.tv"])
        assertEquals(AppProfile.ARTWORK, m["com.spotify.tv.android"])
        assertEquals(AppProfile.OFF, m["com.netflix.ninja"])
        assertNull(m["com.x"]) // default = no override
        assertNull(m["com.y"])
        assertEquals(3, m.size)
    }

    @Test
    fun formatRoundTrips() {
        val raw = "a.b=screen;c.d=off"
        assertEquals(raw, Profiles.format(Profiles.parse(raw)))
    }

    @Test
    fun profileSourceModes() {
        assertEquals(SourceMode.SCREEN, AppProfile.SCREEN.sourceMode)
        assertNull(AppProfile.DEFAULT.sourceMode)
        assertNull(AppProfile.OFF.sourceMode)
        // cycling visits every profile and comes back
        var p = AppProfile.DEFAULT
        val seen = mutableSetOf<AppProfile>()
        repeat(AppProfile.values().size) { seen += p; p = p.next() }
        assertEquals(AppProfile.DEFAULT, p)
        assertTrue(seen.containsAll(AppProfile.values().toList()))
    }
}
