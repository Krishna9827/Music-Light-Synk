package com.musiclightsynk

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Lets Home Assistant control the app over Wi-Fi, with no ADB: on phones (which HA can't reach
 * over ADB) and on the Fire TV alike. A tiny HTTP server on port 21331:
 *
 *   POST /api/configure   {"corner_pct": 25, "audio_source": "mic", ...}   (keys of [AppSettings])
 *   POST /api/test        send the test colours
 *   POST /api/status      push the app's status to Home Assistant now
 *   POST /api/capture     start / POST /api/stop_capture   stop screen capture
 *   GET  /api/ping        {"app": "music_light_synk"} (no token needed, tells nothing else)
 *
 * Every other request needs "Authorization: Bearer <token>". The app sends the URL and token to
 * Home Assistant only (in its webhook posts), never over MQTT or the device link.
 *
 * Pure JVM code; [handle] does the work. Unit tested.
 */
class ControlServer(
    private val port: Int = PORT,
    private val token: () -> String,
    private val handle: (action: String, body: JSONObject) -> String,
) {
    private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "mls-control").apply { isDaemon = true } }

    val isRunning: Boolean get() = server != null

    /** The port actually bound (useful when [port] is 0 in tests). */
    val localPort: Int get() = server?.localPort ?: -1

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val bindTo = InetSocketAddress(port)
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(bindTo)
            }
        } catch (e: Exception) {
            lastError = e.toString()
            return false
        }
        lastError = null
        server = s
        thread(name = "mls-control-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (e: Exception) {
                    if (s.isClosed) break else continue
                }
                workers.execute { serve(c) }
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 5000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return respond(sock, 400, "bad request")
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                var length = 0
                var auth = ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val name = line.substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':', "").trim()
                    when (name) {
                        "content-length" -> length = value.toIntOrNull() ?: 0
                        "authorization" -> auth = value
                    }
                }
                if (length < 0 || length > MAX_BODY) return respond(sock, 413, "body too large")
                val bodyBytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(bodyBytes, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (method == "GET" && path == "/api/ping") return respond(sock, 200, "ok", JSONObject().put("app", "music_light_synk"))
                if (!authorized(auth)) return respond(sock, 401, "missing or wrong token")
                if (method != "POST" || !path.startsWith("/api/")) return respond(sock, 404, "not found")
                val action = path.removePrefix("/api/")
                if (action !in ACTIONS) return respond(sock, 404, "unknown action $action")
                val body = try {
                    if (read == 0) JSONObject() else JSONObject(String(bodyBytes, 0, read, Charsets.UTF_8))
                } catch (e: Exception) {
                    return respond(sock, 400, "body must be a JSON object")
                }
                val message = try {
                    handle(action, body)
                } catch (e: Exception) {
                    return respond(sock, 500, e.message ?: e.javaClass.simpleName)
                }
                respond(sock, 200, message)
            } catch (_: Exception) {
            }
        }
    }

    private fun authorized(header: String): Boolean {
        val expected = token()
        if (expected.isEmpty() || !header.startsWith("Bearer ")) return false
        val given = header.removePrefix("Bearer ").trim()
        return MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }

    private fun respond(sock: Socket, code: Int, message: String, extra: JSONObject? = null) {
        val body = (extra ?: JSONObject()).put("ok", code == 200).put("message", message).toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val out = sock.getOutputStream()
        out.write(
            "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        out.write(body)
        out.flush()
    }

    /** One CRLF- or LF-terminated header line, at most 8 KB. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object {
        const val PORT = 21331
        private const val MAX_BODY = 64 * 1024
        val ACTIONS = setOf("configure", "test", "status", "capture", "stop_capture")

        /** This device's LAN IPv4 address (Wi-Fi / Ethernet), or null. */
        fun lanAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { ni -> if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
