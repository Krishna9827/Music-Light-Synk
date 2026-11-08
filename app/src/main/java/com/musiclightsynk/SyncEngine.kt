package com.musiclightsynk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The brain. Decides *when* to send colours so the lights get one command per change
 * (new track / new artwork) instead of a continuous stream - which is what makes cloud
 * bulbs like Tuya lag. Then fans each update out to every configured output:
 * Home Assistant webhook(s), MQTT, and WLED directly.
 *
 * All state below is only touched on the engine thread.
 */
object SyncEngine {
    private const val TAG = "MusicLightSynk"

    /** Raw RGB distance below which two palettes count as "the same" (e.g. next song, same album). */
    private const val CHANGE_THRESHOLD = 18
    private const val MQTT_KEEPALIVE_MS = 60_000L

    private const val SRC_ARTWORK = "artwork"
    private const val SRC_SCREEN = "screen"

    data class Status(
        val nowPlaying: String = "Nothing playing",
        val nowPackage: String? = null,
        val lastResult: String = "Nothing sent yet",
        val palette: Palette? = null,
        val source: String? = null,
        val note: String? = null,
    )

    private val thread = HandlerThread("mls-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val device: String by lazy { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    @Volatile
    var status = Status()
        private set

    private var appCtx: Context? = null
    private var current: NowPlaying? = null
    private var currentKey: String? = null
    private var sessionActive = false
    private var lastSent: Palette? = null
    private var lastSource: String? = null
    private var stopScheduled = false
    private var artCacheKey: String? = null
    private var artCachePalette: Palette? = null

    private val mqtt = MqttBridge()
    private val wled = WledController()
    private var lastWledCfg: WledConfig? = null

    /** Main thread only. */
    private val pulse = AudioPulse(
        onBeat = { wled.ambient.beat() },
        onStatus = { msg -> handler.post { setStatus(status.copy(note = msg)) } },
    )

    private val stopTask = Runnable { onStopTimeout() }
    private val pollTask = Runnable { onPoll() }
    private val mqttKeepAlive = object : Runnable {
        override fun run() {
            mqtt.keepAlive()
            if (mqtt.isConnected) handler.postDelayed(this, MQTT_KEEPALIVE_MS)
        }
    }

    // ---- public API (any thread) -------------------------------------------------------------

    fun addListener(r: Runnable) {
        listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    /** Ask UIs to refresh (posted to the main thread). */
    fun notifyUi() {
        main.post { for (l in listeners) l.run() }
    }

    fun onNowPlaying(ctx: Context, np: NowPlaying?) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            handleNowPlaying(app, np)
        }
    }

    /** Settings changed: re-evaluate what's playing so the user sees the effect right away. */
    fun settingsChanged(ctx: Context) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            val prefs = Prefs(app)
            artCacheKey = null
            if (!prefs.isMqttConfigured) mqtt.close()
            if (!prefs.isWledConfigured) releaseWled()
            if (!prefs.enabled) {
                handler.removeCallbacks(pollTask)
                updatePulse(prefs, false)
                return@post
            }
            val np = current
            if (np != null && np.playing && prefs.profileFor(np.packageName) != AppProfile.OFF) {
                process(app, prefs, np, trackChanged = true, force = true)
                schedulePoll(prefs, np)
            }
            updatePulse(prefs, np?.playing == true)
        }
    }

    /** Send the fixed rainbow test palette (every corner/side differs). [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        handler.post {
            val prefs = Prefs(app)
            val palette = ColorExtractor.testPalette(prefs.extractOptions())
            val json = Payloads.colors("test", "test", false, "test", current, palette, device)
            // Idle: WLED shows the test for a few seconds, then goes back to what it was doing.
            // During music: show the test, then put the music colours back.
            val r = deliver(prefs, json, palette, sessionStart = false, preview = !sessionActive)
            if (sessionActive) {
                handler.postDelayed({
                    val np = current
                    if (np != null && np.playing && sessionActive) process(app, Prefs(app), np, trackChanged = false, force = true)
                }, WledController.PREVIEW_MS)
            }
            val msg = "Test colours -> ${r.message}"
            setStatus(status.copy(lastResult = stamp(msg), palette = palette, source = "test"))
            if (done != null) main.post { done(msg) }
        }
    }

    // ---- engine thread -----------------------------------------------------------------------

    private fun handleNowPlaying(ctx: Context, np: NowPlaying?) {
        val prefs = Prefs(ctx)
        current = np
        setStatus(status.copy(nowPlaying = np?.describe() ?: "Nothing playing", nowPackage = np?.packageName))
        if (!prefs.enabled) {
            handler.removeCallbacks(pollTask)
            updatePulse(prefs, false)
            return
        }

        if (np == null || !np.playing) {
            handler.removeCallbacks(pollTask)
            updatePulse(prefs, false)
            if (sessionActive && !stopScheduled) {
                stopScheduled = true
                handler.postDelayed(stopTask, prefs.stopDelaySec * 1000L)
            }
            return
        }

        if (stopScheduled) {
            handler.removeCallbacks(stopTask)
            stopScheduled = false
        }
        val trackChanged = np.trackKey != currentKey
        currentKey = np.trackKey
        process(ctx, prefs, np, trackChanged, force = false)
        schedulePoll(prefs, np)
        updatePulse(prefs, true)
    }

    private fun process(ctx: Context, prefs: Prefs, np: NowPlaying, trackChanged: Boolean, force: Boolean) {
        val opts = prefs.extractOptions()
        val mode = prefs.sourceModeFor(np.packageName)
        var palette: Palette? = null
        var source: String? = null

        if (mode != SourceMode.SCREEN) {
            palette = artworkPalette(ctx, np, opts)
            if (palette != null) source = SRC_ARTWORK
        }
        if (palette == null && mode != SourceMode.ARTWORK) {
            // Without artwork, only grab the screen when something actually changed. Periodic
            // re-sampling (if enabled) is handled by the poll task.
            if (!(trackChanged || force || !sessionActive || lastSource != SRC_SCREEN)) return
            palette = screenPalette(opts)
            if (palette != null) source = SRC_SCREEN
        }

        if (palette == null || source == null) {
            val why = when {
                mode == SourceMode.ARTWORK -> "${np.appLabel} did not provide album art"
                ScreenCaptureService.instance == null -> "no album art and screen capture is off"
                else -> "no album art and the screen is black (DRM/protected video?)"
            }
            setStatus(status.copy(lastResult = stamp("No colours: $why")))
            return
        }
        sendPalette(prefs, np, palette, source, if (trackChanged) "track" else "update", force)
    }

    private fun sendPalette(prefs: Prefs, np: NowPlaying, palette: Palette, source: String, reason: String, force: Boolean) {
        lastSource = source
        val sessionStart = !sessionActive
        if (!force && !sessionStart && palette.maxDistanceTo(lastSent) < CHANGE_THRESHOLD) {
            // Lights already show these colours - don't spam slow bulbs with a duplicate command.
            setStatus(status.copy(palette = palette, source = source))
            return
        }
        val json = Payloads.colors("colors", reason, sessionStart, source, np, palette, device)
        val r = deliver(prefs, json, palette, sessionStart, preview = false)
        if (r.ok) {
            lastSent = palette
            sessionActive = true
        }
        Log.i(TAG, "colors ($source/$reason) -> ${r.message}: $palette")
        setStatus(status.copy(lastResult = stamp("Sent $source colours -> ${r.message}"), palette = palette, source = source))
    }

    private fun artworkPalette(ctx: Context, np: NowPlaying, opts: ExtractOptions): Palette? {
        np.art?.let { bmp -> Artwork.palette(bmp, opts)?.let { return it } }
        val uri = np.artUri ?: return null
        val key = "$uri|$opts"
        if (key == artCacheKey) return artCachePalette
        val bmp = Artwork.load(ctx, uri) ?: return null
        val p = Artwork.palette(bmp, opts)
        bmp.recycle()
        artCacheKey = key
        artCachePalette = p
        return p
    }

    private fun screenPalette(opts: ExtractOptions): Palette? {
        val svc = ScreenCaptureService.instance ?: return null
        val frame = svc.capture() ?: return null
        val p = ColorExtractor.extract(frame.pixels, frame.width, frame.height, opts)
        return if (p.isBlank) null else p
    }

    private fun schedulePoll(prefs: Prefs, np: NowPlaying) {
        handler.removeCallbacks(pollTask)
        val interval = prefs.screenIntervalSec
        if (interval <= 0) return
        val mode = prefs.sourceModeFor(np.packageName)
        val usingScreen = mode == SourceMode.SCREEN || (mode == SourceMode.AUTO && lastSource == SRC_SCREEN)
        if (usingScreen) handler.postDelayed(pollTask, interval * 1000L)
    }

    private fun onPoll() {
        val ctx = appCtx ?: return
        val np = current ?: return
        if (!np.playing) return
        val prefs = Prefs(ctx)
        if (!prefs.enabled) return
        screenPalette(prefs.extractOptions())?.let { sendPalette(prefs, np, it, SRC_SCREEN, "screen_poll", force = false) }
        schedulePoll(prefs, np)
    }

    private fun onStopTimeout() {
        stopScheduled = false
        val ctx = appCtx ?: return
        if (!sessionActive) return
        val r = deliver(Prefs(ctx), Payloads.stopped(current, device), null, sessionStart = false, preview = false)
        sessionActive = false
        lastSent = null
        currentKey = null
        lastSource = null
        setStatus(status.copy(lastResult = stamp("Playback stopped -> ${r.message}")))
    }

    /** Beat pulse runs only while playing, in WLED ambient mode, with pulse switched on. */
    private fun updatePulse(prefs: Prefs, playing: Boolean) {
        val ctx = appCtx ?: return
        val want = playing && prefs.enabled && prefs.pulseEnabled &&
            prefs.isWledConfigured && prefs.wledMode == WledMode.AMBIENT
        wled.ambient.setPulse(want, prefs.pulseStrengthPct / 100f)
        main.post {
            if (want && !pulse.isRunning) pulse.start(ctx) else if (!want && pulse.isRunning) pulse.stop()
        }
    }

    private fun releaseWled() {
        lastWledCfg?.let { wled.release(it) }
        lastWledCfg = null
    }

    /** Send one payload to every configured output. ok = at least one output accepted it. */
    private fun deliver(prefs: Prefs, json: JSONObject, palette: Palette?, sessionStart: Boolean, preview: Boolean): HaClient.Result {
        if (!prefs.hasOutput) {
            return HaClient.Result(false, 0, "not sent: set up Home Assistant, MQTT or WLED first")
        }
        val parts = ArrayList<String>()
        var ok = false
        val body = json.toString()
        val stopped = json.optString("event") == "stopped"

        if (prefs.isHaConfigured) {
            val ids = prefs.webhookIds
            for (id in ids) {
                var r = HaClient.postJson(prefs.webhookUrl(id), body)
                if (!r.ok && r.code < 0) {
                    // One retry for flaky Wi-Fi (Fire TV radios like to nap).
                    SystemClock.sleep(800)
                    r = HaClient.postJson(prefs.webhookUrl(id), body)
                }
                if (r.ok) ok = true
                parts += if (ids.size > 1) "$id: ${r.message}" else "HA ${r.message}"
            }
            val token = prefs.token
            if (token.isNotBlank()) {
                val s = HaClient.postJson(
                    "${prefs.haUrl}/api/states/sensor.music_light_synk",
                    Payloads.sensorState(json).toString(),
                    token,
                )
                if (!s.ok) parts += "sensor ${s.message}"
            }
        }

        if (prefs.isMqttConfigured) {
            val r = mqtt.publish(prefs.mqttConfig(device, Build.MODEL ?: ""), json)
            if (r.ok) {
                ok = true
                handler.removeCallbacks(mqttKeepAlive)
                handler.postDelayed(mqttKeepAlive, MQTT_KEEPALIVE_MS)
            }
            parts += r.message
        }

        if (prefs.isWledConfigured) {
            val cfg = prefs.wledConfig()
            lastWledCfg = cfg
            val r = when {
                stopped -> wled.onStopped(cfg)
                palette != null -> wled.onColors(cfg, palette, sessionStart, preview)
                else -> null
            }
            if (r != null) {
                if (r.ok) ok = true
                parts += r.message
            }
        }
        return HaClient.Result(ok, if (ok) 200 else -1, parts.joinToString(" | "))
    }

    private fun stamp(msg: String): String = "${timeFmt.format(Date())}  $msg"

    private fun setStatus(s: Status) {
        status = s
        notifyUi()
    }
}
