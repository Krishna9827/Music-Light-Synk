package com.musiclightsynk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Minimal Home Assistant HTTP client (webhook + optional REST state mirror). */
object HaClient {
    data class Result(val ok: Boolean, val code: Int, val message: String)

    fun postJson(
        url: String,
        body: String,
        bearerToken: String? = null,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 5000,
    ): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!bearerToken.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Drain the body so the connection can be reused by the keep-alive pool.
            try {
                (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            } catch (_: Exception) {
            }
            val hint = when (code) {
                401 -> " (token rejected)"
                404 -> " (wrong URL?)"
                405 -> " (webhook does not allow POST)"
                else -> ""
            }
            Result(code in 200..299, code, "HTTP $code$hint")
        } catch (e: Exception) {
            Result(false, -1, "${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * JSON bodies sent to Home Assistant. See README "Payload" for the schema.
 * Pure org.json - no Android framework calls - so it is unit tested on the JVM.
 */
object Payloads {

    fun colors(
        event: String,
        reason: String,
        sessionStart: Boolean,
        source: String,
        np: NowPlaying?,
        palette: Palette,
        device: String,
    ): JSONObject {
        val o = base(event, np, device)
        o.put("reason", reason)
        o.put("session_start", sessionStart)
        o.put("source", source)
        val colors = JSONObject()
        for (slot in Slot.values()) colors.put(slot.key, color(palette[slot]))
        o.put("colors", colors)
        return o
    }

    fun stopped(np: NowPlaying?, device: String): JSONObject = base("stopped", np, device).put("playing", false)

    /** App state for Home Assistant: media access, screen capture, source mode, settings. */
    fun status(np: NowPlaying?, device: String, status: JSONObject): JSONObject =
        base("status", np, device).put("status", status)

    fun color(c: LightColor): JSONObject = JSONObject()
        .put("hex", c.light.hex)
        .put("rgb", rgb(c.light))
        .put("hs", JSONArray().put(round1(c.hue)).put(round1(c.saturation)))
        .put("brightness", c.brightness)
        .put("brightness_pct", c.brightnessPct)
        .put("raw_hex", c.raw.hex)
        .put("raw_rgb", rgb(c.raw))

    /** Body for POST /api/states/sensor.music_light_synk (only used when a token is configured). */
    fun sensorState(payload: JSONObject): JSONObject {
        val event = payload.optString("event")
        val state = if (event == "stopped") {
            "idle"
        } else {
            payload.optJSONObject("colors")?.optJSONObject("dominant")?.optString("hex", "unknown") ?: "unknown"
        }
        val attrs = JSONObject(payload.toString())
        attrs.put("friendly_name", "Music Light Synk")
        attrs.put("icon", "mdi:palette")
        return JSONObject().put("state", state).put("attributes", attrs)
    }

    private fun base(event: String, np: NowPlaying?, device: String): JSONObject = JSONObject()
        .put("event", event)
        .put("playing", np?.playing ?: false)
        .put("app", np?.appLabel ?: JSONObject.NULL)
        .put("package", np?.packageName ?: JSONObject.NULL)
        .put("title", np?.title ?: JSONObject.NULL)
        .put("artist", np?.artist ?: JSONObject.NULL)
        .put("album", np?.album ?: JSONObject.NULL)
        .put("device", device)
        .put("timestamp", System.currentTimeMillis() / 1000)

    private fun rgb(c: Rgb) = JSONArray().put(c.r).put(c.g).put(c.b)

    private fun round1(v: Float): Double = (v * 10f).roundToInt() / 10.0
}
