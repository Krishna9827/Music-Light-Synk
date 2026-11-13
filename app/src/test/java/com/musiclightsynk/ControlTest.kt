package com.musiclightsynk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class ControlTest {

    private class Reply(val code: Int, val body: JSONObject)

    private fun call(port: Int, method: String, path: String, body: String? = null, token: String? = null): Reply {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 2000
            c.readTimeout = 3000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            return Reply(code, JSONObject(text))
        } finally {
            c.disconnect()
        }
    }

    private fun <T> withServer(block: (port: Int, calls: List<Pair<String, JSONObject>>) -> T): T {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val server = ControlServer(port = 0, token = { "s3cret" }) { action, body ->
            calls += action to body
            "done $action"
        }
        assertTrue(server.lastError.toString(), server.start())
        try {
            return block(server.localPort, calls)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pingNeedsNoTokenButSaysNothingElse() = withServer { port, calls ->
        val r = call(port, "GET", "/api/ping")
        assertEquals(200, r.code)
        assertEquals("music_light_synk", r.body.getString("app"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun everythingElseNeedsTheRightToken() = withServer { port, calls ->
        assertEquals(401, call(port, "POST", "/api/configure", """{"corner_pct":25}""").code)
        assertEquals(401, call(port, "POST", "/api/configure", """{"corner_pct":25}""", token = "wrong").code)
        assertTrue("nothing ran without the token", calls.isEmpty())
    }

    @Test
    fun configureHandsTheJsonToTheApp() = withServer { port, calls ->
        val r = call(port, "POST", "/api/configure", """{"corner_pct":25,"audio_source":"mic","link_enabled":false}""", token = "s3cret")
        assertEquals(200, r.code)
        assertTrue(r.body.getBoolean("ok"))
        assertEquals("done configure", r.body.getString("message"))
        val (action, body) = calls.single()
        assertEquals("configure", action)
        assertEquals(25, body.getInt("corner_pct"))
        assertEquals("mic", body.getString("audio_source"))
        assertFalse(body.getBoolean("link_enabled"))
    }

    @Test
    fun actionsWithoutABodyWorkAndUnknownOnesDont() = withServer { port, calls ->
        assertEquals(200, call(port, "POST", "/api/test", token = "s3cret").code)
        assertEquals(200, call(port, "POST", "/api/status", "", token = "s3cret").code)
        assertEquals(404, call(port, "POST", "/api/reboot", "{}", token = "s3cret").code)
        assertEquals(404, call(port, "GET", "/api/test", token = "s3cret").code)
        assertEquals(400, call(port, "POST", "/api/configure", "[1,2]", token = "s3cret").code)
        assertEquals(listOf("test", "status"), calls.map { it.first })
    }

    // ---- settings values from ADB / JSON ---------------------------------------------------------

    @Test
    fun looselyTypedValuesAreRead() {
        assertEquals(25, AppSettings.coerce('i', 25))
        assertEquals(25, AppSettings.coerce('i', 25.4))
        assertEquals(25, AppSettings.coerce('i', "25"))
        assertEquals(true, AppSettings.coerce('b', "on"))
        assertEquals(false, AppSettings.coerce('b', 0))
        assertNull(AppSettings.coerce('b', "maybe"))
        assertEquals("x", AppSettings.coerce('s', "x"))
        assertEquals('s', AppSettings.kindOf("room"))
        assertEquals('i', AppSettings.kindOf("energy_blur"))
        assertNull(AppSettings.kindOf("not_a_setting"))
    }

    @Test
    fun audioSourceIsAlwaysOneFixedChoice() {
        assertEquals(AudioSource.MIC, AudioSource.of("mic"))
        assertEquals(AudioSource.OUTPUT, AudioSource.of(" Output "))
        assertNull("v0.6's auto means: use this device's default", AudioSource.of("auto"))
        assertEquals(listOf("output", "mic", "off"), AudioSource.values().map { it.key })
    }
}
