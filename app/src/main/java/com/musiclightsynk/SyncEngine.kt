package com.musiclightsynk

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The brain. Decides *when* to send colours so the lights get one command per change
 * (new track / new artwork) instead of a continuous stream - which is what makes cloud
 * bulbs like Tuya lag. Then fans each update out to every configured output.
 *
 * Lights first: WLED is driven straight from the engine thread the moment the colours are known.
 * Home Assistant and MQTT run on their own network thread, so a slow or unreachable server can
 * never hold the lights up.
 *
 * Every device has its own WLED list. With the device link on, devices that list the same WLED
 * take turns on it ([LightHub], per WLED host), and devices in the same room share the beat and
 * the album colours (a phone's mic can feed a TV that can't hear its own audio).
 *
 * All state below is only touched on the engine thread unless marked otherwise.
 */
object SyncEngine {
    private const val TAG = "MusicLightSynk"

    /** Raw RGB distance below which two palettes count as "the same" (e.g. next song, same album). */
    private const val CHANGE_THRESHOLD = 18
    private const val MQTT_KEEPALIVE_MS = 60_000L

    private const val SRC_ARTWORK = "artwork"
    private const val SRC_SCREEN = "screen"

    private const val HEARTBEAT_MS = 1000L
    private const val HELLO_MS = 3000L
    private const val HUB_START_MS = 3000L
    private const val PEER_PAL_TTL_MS = 10_000L
    private const val STATUS_MIN_INTERVAL_MS = 3000L

    /** After a track change in screen mode, look again: apps animate the new cover in. */
    private val SCREEN_FOLLOW_UPS_MS = longArrayOf(1200L, 3500L)

    data class Status(
        val nowPlaying: String = "Nothing playing",
        val nowPackage: String? = null,
        val lastResult: String = "Nothing sent yet",
        val palette: Palette? = null,
        val source: String? = null,
        val note: String? = null,
        /** "output" / "mic" while listening to music, else null. */
        val audio: String? = null,
        /** Names of the other devices running the app (with their room). */
        val peers: List<String> = emptyList(),
        /** Who drives this device's WLED strips: "This device", another device's name, or null. */
        val renderer: String? = null,
        val listening: Boolean = false,
        /** Where Home Assistant can reach this app over Wi-Fi, e.g. http://192.168.1.23:21331. */
        val controlUrl: String? = null,
    )

    private val thread = HandlerThread("mls-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val netThread = HandlerThread("mls-net").apply { start() }
    private val net = Handler(netThread.looper)
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

    private val wled = WledController()
    private var lastWledCfg: WledConfig? = null

    /** Main thread only (start/stop). */
    private val audio = AudioEngine(
        onLevels = { l, silent -> onLocalLevels(l, silent) },
        onStatus = { msg -> handler.post { setStatus(status.copy(note = msg)) } },
    )

    // audio routing: written from the audio / link threads
    @Volatile
    private var localLoudAt = 0L

    @Volatile
    private var peerNrgFrom: String? = null

    @Volatile
    private var peerNrgAt = 0L

    // device link
    @Volatile
    private var link: Link? = null
    private var hub = LightHub("")

    @Volatile
    private var selfId = ""

    @Volatile
    private var room = "Home"
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatScheduled = false
    private var lastHiAt = 0L
    private var lastPalAt = 0L

    // which of this device's WLEDs it drives right now (normalised hosts), and why
    private var driven: Set<String> = emptySet()
    private var drivenPrio = 0
    private var playingSince = 0L
    private var listenerActive = false
    private var soundSince = 0L
    private var peerPal: LinkProtocol.Pal? = null
    private var peerPalAt = 0L
    private var lastPalette: Palette? = null
    private var hubShown: Palette? = null

    // Home Assistant control over Wi-Fi
    private var control: ControlServer? = null
    private var controlUrl: String? = null
    private var lastAddrCheck = 0L

    // network thread only
    private val mqtt = MqttBridge()
    private var haNeedsSessionStart = false
    private var pendingStatus: PendingStatus? = null
    private var lastStatusAt = 0L
    private var lastStatusJson: String? = null // engine thread

    private val stopTask = Runnable { onStopTimeout() }
    private val pollTask = Runnable { onPoll() }
    private val screenFollowUp = Runnable { onScreenFollowUp() }
    private val heartbeat = Runnable { onHeartbeat() }
    private val statusTask = Runnable { flushStatus() }
    private val mqttKeepAlive = object : Runnable {
        override fun run() {
            mqtt.keepAlive()
            if (mqtt.isConnected) net.postDelayed(this, MQTT_KEEPALIVE_MS)
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

    /** The phone's backup listener started / stopped ([ListenerService]). */
    fun setListener(ctx: Context, active: Boolean) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            listenerActive = active
            soundSince = 0L
            val prefs = Prefs(app)
            ensureServices(app, prefs)
            updateAudio(app, prefs)
            reevaluateWled(prefs)
            setStatus(status.copy(listening = active))
        }
    }

    /**
     * Tell Home Assistant / MQTT what the app is doing (media access, screen capture, source
     * mode, settings). Sent only when something changed, unless [force].
     */
    fun reportStatus(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            val prefs = Prefs(app)
            ensureServices(app, prefs)
            sendStatus(app, prefs, force)
        }
    }

    /** Settings changed: re-evaluate what's playing so the user sees the effect right away. */
    fun settingsChanged(ctx: Context) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            val prefs = Prefs(app)
            artCacheKey = null
            if (!prefs.isMqttConfigured) net.post { mqtt.close() }
            if (!prefs.isWledConfigured) releaseWled()
            ensureServices(app, prefs)
            main.post { audio.resetFailures() }
            sendStatus(app, prefs, force = false)
            hubShown = null // re-send the hub's colours with the new settings
            if (prefs.enabled) {
                val np = current
                if (np != null && np.playing && prefs.profileFor(np.packageName) != AppProfile.OFF) {
                    process(app, prefs, np, trackChanged = true, force = true)
                    schedulePoll(prefs, np)
                }
            } else {
                handler.removeCallbacks(pollTask)
            }
            updateAudio(app, prefs)
            reevaluateWled(prefs)
        }
    }

    /** Send the fixed rainbow test palette (every corner/side differs). [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            val prefs = Prefs(app)
            val palette = ColorExtractor.testPalette(prefs.extractOptions())
            val json = withStatus(app, prefs, Payloads.colors("test", "test", false, "test", current, palette, device))
            // Idle: WLED shows the test for a few seconds, then goes back to what it was doing.
            // During music: show the test, then put the music colours back.
            val msg = "Test colours -> " + deliver(prefs, json, palette, sessionStart = false, preview = !sessionActive, label = "Test colours")
            if (sessionActive) {
                handler.postDelayed({
                    val np = current
                    if (np != null && np.playing && sessionActive) process(app, Prefs(app), np, trackChanged = false, force = true)
                }, WledController.PREVIEW_MS)
            }
            setStatus(status.copy(lastResult = stamp(msg), palette = palette, source = "test"))
            if (done != null) main.post { done(msg) }
        }
    }

    // ---- engine thread: what's playing ---------------------------------------------------------

    private fun handleNowPlaying(ctx: Context, np: NowPlaying?) {
        val prefs = Prefs(ctx)
        current = np
        ensureServices(ctx, prefs)
        setStatus(status.copy(nowPlaying = np?.describe() ?: "Nothing playing", nowPackage = np?.packageName))
        if (!prefs.enabled) {
            handler.removeCallbacks(pollTask)
            updateAudio(ctx, prefs)
            return
        }

        if (np == null || !np.playing) {
            handler.removeCallbacks(pollTask)
            handler.removeCallbacks(screenFollowUp)
            if (sessionActive && !stopScheduled) {
                stopScheduled = true
                handler.postDelayed(stopTask, prefs.stopDelaySec * 1000L)
            }
            updateAudio(ctx, prefs)
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
        updateAudio(ctx, prefs)
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
            // Right after a track change the screen often still shows the old cover (apps
            // animate the new one in), or nothing new was drawn yet: look again shortly.
            if (trackChanged || force) scheduleScreenFollowUps()
        }

        if (palette == null || source == null) {
            val why = when {
                mode == SourceMode.ARTWORK -> "${np.appLabel} did not provide album art"
                ScreenCaptureService.instance == null -> "no album art and screen capture is off"
                else -> "no album art and the screen gave no picture yet (or it's DRM-protected video)"
            }
            setStatus(status.copy(lastResult = stamp("No colours: $why")))
            return
        }
        sendPalette(prefs, np, palette, source, if (trackChanged) "track" else "update", force)
    }

    private fun sendPalette(prefs: Prefs, np: NowPlaying, palette: Palette, source: String, reason: String, force: Boolean) {
        lastSource = source
        lastPalette = palette
        if (!prefs.hasOutput) {
            setStatus(status.copy(palette = palette, source = source, lastResult = stamp("Not sent: set up WLED, Home Assistant or MQTT first")))
            return
        }
        val sessionStart = !sessionActive
        if (sessionStart) playingSince = SystemClock.elapsedRealtime()
        if (!force && !sessionStart && palette.maxDistanceTo(lastSent) < CHANGE_THRESHOLD) {
            // Lights already show these colours - don't spam slow bulbs with a duplicate command.
            setStatus(status.copy(palette = palette, source = source))
            return
        }
        lastSent = palette
        sessionActive = true
        val label = "Sent $source colours"
        val json = withStatus(ctxOrNull(), prefs, Payloads.colors("colors", reason, sessionStart, source, np, palette, device))
        val msg = deliver(prefs, json, palette, sessionStart, preview = false, label = label)
        sendPal(np, palette, playing = true)
        Log.i(TAG, "colors ($source/$reason) -> $msg: $palette")
        setStatus(status.copy(lastResult = stamp("$label -> $msg"), palette = palette, source = source))
        appCtx?.let { updateAudio(it, prefs) }
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

    private fun scheduleScreenFollowUps() {
        handler.removeCallbacks(screenFollowUp)
        if (ScreenCaptureService.instance == null) return
        for (delay in SCREEN_FOLLOW_UPS_MS) handler.postDelayed(screenFollowUp, delay)
    }

    private fun onScreenFollowUp() {
        val ctx = appCtx ?: return
        val np = current ?: return
        if (!np.playing) return
        val prefs = Prefs(ctx)
        if (!prefs.enabled) return
        val mode = prefs.sourceModeFor(np.packageName)
        if (mode == SourceMode.ARTWORK || (mode == SourceMode.AUTO && lastSource == SRC_ARTWORK)) return
        screenPalette(prefs.extractOptions())?.let { sendPalette(prefs, np, it, SRC_SCREEN, "screen_settle", force = false) }
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
        val prefs = Prefs(ctx)
        sessionActive = false
        val msg = deliver(prefs, withStatus(ctx, prefs, Payloads.stopped(current, device)), null, sessionStart = false, preview = false, label = "Playback stopped")
        lastSent?.let { sendPal(current, it, playing = false) }
        lastSent = null
        currentKey = null
        lastSource = null
        handler.removeCallbacks(screenFollowUp)
        setStatus(status.copy(lastResult = stamp("Playback stopped -> $msg")))
        updateAudio(ctx, prefs)
        reevaluateWled(prefs) // a backup listener may light this device's strips now
    }

    // ---- outputs -------------------------------------------------------------------------------

    /**
     * Send one payload: WLED right now (engine thread), Home Assistant and MQTT on the network
     * thread. Returns the WLED part of the result; the rest is added to the status line when the
     * network thread is done.
     */
    private fun deliver(prefs: Prefs, json: JSONObject, palette: Palette?, sessionStart: Boolean, preview: Boolean, label: String): String {
        val parts = ArrayList<String>()
        if (prefs.isWledConfigured) {
            val stopped = json.optString("event") == "stopped"
            val r = when {
                stopped -> if (drivenPrio == LightHub.PRIO_PLAYING) stopDriving(prefs, restore = true) else null
                palette == null -> null
                preview -> wled.onColors(prefs.wledConfig().also { lastWledCfg = it }, palette, sessionStart, preview = true).message
                else -> driveWled(prefs, LightHub.PRIO_PLAYING, palette, sessionStart)
            }
            if (r != null) parts += r
        }
        if (prefs.isHaConfigured || prefs.isMqttConfigured) {
            parts += if (prefs.isHaConfigured) "HA sending" else "MQTT sending"
            postNetwork(prefs, json, label)
        }
        return parts.joinToString(" | ").ifEmpty { "nothing to send to" }
    }

    /** Hand a payload to the network thread (HA webhook(s), REST mirror, MQTT). */
    private fun postNetwork(prefs: Prefs, json: JSONObject, label: String) {
        val urls = if (prefs.isHaConfigured) prefs.webhookIds.map { it to prefs.webhookUrl(it) } else emptyList()
        val mirror = if (prefs.isHaConfigured && prefs.token.isNotBlank()) "${prefs.haUrl}/api/states/sensor.music_light_synk" to prefs.token else null
        val mqttCfg = if (prefs.isMqttConfigured) prefs.mqttConfig(device, Build.MODEL ?: "") else null
        val controlBlock = controlBlock(prefs)
        net.post {
            val parts = ArrayList<String>()
            val event = json.optString("event")
            if (urls.isNotEmpty()) {
                if (event == "colors" && haNeedsSessionStart) json.put("session_start", true)
                val haBody = JSONObject(json.toString()).also { if (controlBlock != null) it.put("control", controlBlock) }.toString()
                var anyOk = false
                for ((id, url) in urls) {
                    var r = HaClient.postJson(url, haBody)
                    if (!r.ok && r.code < 0) {
                        SystemClock.sleep(800) // one retry for flaky Wi-Fi (Fire TV radios like to nap)
                        r = HaClient.postJson(url, haBody)
                    }
                    if (r.ok) anyOk = true
                    parts += if (urls.size > 1) "$id: ${r.message}" else "HA ${r.message}"
                }
                haNeedsSessionStart = when (event) {
                    "stopped" -> false
                    "colors" -> !anyOk && (json.optBoolean("session_start") || haNeedsSessionStart)
                    else -> haNeedsSessionStart
                }
                if (mirror != null) {
                    val s = HaClient.postJson(mirror.first, Payloads.sensorState(json).toString(), mirror.second)
                    if (!s.ok) parts += "sensor ${s.message}"
                }
            }
            if (mqttCfg != null) {
                val r = mqtt.publish(mqttCfg, json)
                if (r.ok) {
                    net.removeCallbacks(mqttKeepAlive)
                    net.postDelayed(mqttKeepAlive, MQTT_KEEPALIVE_MS)
                }
                parts += r.message
            }
            if (parts.isNotEmpty() && event != "status") {
                handler.post {
                    val line = status.lastResult
                    // Replace the "HA sending" placeholder of this send with the real answer.
                    val updated = if (line.contains(label)) line.replace(Regex("(HA|MQTT) sending"), parts.joinToString(" | ")) else line
                    setStatus(status.copy(lastResult = updated))
                }
            }
        }
    }

    /** Where Home Assistant can control this app over Wi-Fi. Only ever sent to HA webhooks. */
    private fun controlBlock(prefs: Prefs): JSONObject? {
        val url = status.controlUrl ?: return null
        if (!prefs.controlEnabled) return null
        return JSONObject().put("url", url).put("token", prefs.controlToken)
    }

    // ---- status --------------------------------------------------------------------------------

    private fun appVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Everything Home Assistant needs to show and control the app. No secrets (token, passwords). */
    private fun statusJson(ctx: Context, prefs: Prefs): JSONObject = JSONObject()
        .put("app_version", appVersion(ctx))
        .put("platform", if (prefs.isTv) "tv" else "phone")
        .put("media_access", MediaListenerService.isEnabled(ctx))
        .put("media_connected", MediaListenerService.connected)
        .put("screen_capture", ScreenCaptureService.isRunning)
        .put("source_mode", prefs.sourceMode.key)
        .put("last_source", lastSource ?: JSONObject.NULL)
        .put("enabled", prefs.enabled)
        .put("audio", audio.active ?: JSONObject.NULL)
        .put("listening", listenerActive)
        .put("link_peers", JSONArray(status.peers))
        .put("wled_renderer", status.renderer ?: JSONObject.NULL)
        .put(
            "settings",
            JSONObject()
                .put("corner_pct", prefs.cornerPct)
                .put("saturation_pct", prefs.saturationPct)
                .put("min_brightness_pct", prefs.minBrightnessPct)
                .put("screen_interval", prefs.screenIntervalSec)
                .put("stop_delay", prefs.stopDelaySec)
                .put("boot_capture", prefs.bootCapture)
                .put("profiles", prefs.profilesRaw)
                .put("packages", prefs.allowedPackagesRaw)
                .put("wled_enabled", prefs.wledEnabled)
                .put("wled_devices", prefs.wledDevicesRaw)
                .put("wled_brightness_pct", prefs.wledBrightnessPct)
                .put("wled_restore", prefs.wledRestore)
                .put("pulse", prefs.pulseEnabled)
                .put("pulse_strength", prefs.pulseStrengthPct)
                .put("audio_source", prefs.audioSource.key)
                .put("energy_sensitivity", prefs.energySensitivityPct)
                .put("energy_blur", prefs.energyBlur)
                .put("energy_bg", prefs.energyBackgroundPct)
                .put("link_enabled", prefs.linkEnabled)
                .put("device_name", prefs.deviceName)
                .put("room", prefs.room)
                .put("mqtt_enabled", prefs.mqttEnabled),
        )

    /** Attach the current status to an outgoing payload (keeps HA in sync for free). */
    private fun withStatus(ctx: Context?, prefs: Prefs, json: JSONObject): JSONObject {
        if (ctx == null) return json
        val st = statusJson(ctx, prefs)
        lastStatusJson = st.toString()
        return json.put("status", st)
    }

    /** Queue a status report; the network thread sends at most one every few seconds (the newest). */
    private fun sendStatus(ctx: Context, prefs: Prefs, force: Boolean) {
        val st = statusJson(ctx, prefs)
        val text = st.toString()
        if (!force && text == lastStatusJson) return
        if (!prefs.isHaConfigured && !prefs.isMqttConfigured) return
        lastStatusJson = text
        val json = Payloads.status(current, device, st)
        val urls = if (prefs.isHaConfigured) prefs.webhookIds.map { prefs.webhookUrl(it) } else emptyList()
        val controlBlock = controlBlock(prefs)
        val haBody = JSONObject(json.toString()).also { if (controlBlock != null) it.put("control", controlBlock) }.toString()
        val pending = PendingStatus(urls, haBody, if (prefs.isMqttConfigured) prefs.mqttConfig(device, Build.MODEL ?: "") else null, json)
        net.post {
            pendingStatus = pending
            net.removeCallbacks(statusTask)
            val wait = if (force) 0L else (STATUS_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastStatusAt)).coerceAtLeast(0L)
            net.postDelayed(statusTask, wait)
        }
    }

    /** A status report waiting on the network thread (only the newest is sent). */
    private class PendingStatus(val urls: List<String>, val haBody: String, val mqtt: MqttConfig?, val json: JSONObject)

    /** Network thread. */
    private fun flushStatus() {
        val p = pendingStatus ?: return
        pendingStatus = null
        lastStatusAt = SystemClock.elapsedRealtime()
        for (url in p.urls) HaClient.postJson(url, p.haBody)
        if (p.mqtt != null) mqtt.publish(p.mqtt, p.json)
    }

    // ---- audio -------------------------------------------------------------------------------

    /**
     * Listen while this device plays music (until the stop delay ends the session) or while the
     * backup listener is on, using exactly the source the user picked. Nothing else starts or
     * switches it, so it never flips between device audio and the mic.
     */
    private fun updateAudio(ctx: Context, prefs: Prefs) {
        val playing = sessionActive || (current?.playing == true &&
            current?.let { prefs.profileFor(it.packageName) } != AppProfile.OFF)
        val active = prefs.enabled && (playing || listenerActive)
        val want = if (active) prefs.audioSource else AudioSource.OFF
        val sens = prefs.energySensitivityPct / 100f
        main.post {
            audio.sensitivity = sens
            audio.start(ctx, want)
        }
    }

    /** Audio thread (main or mic). */
    private fun onLocalLevels(l: AudioLevels, silent: Boolean) {
        if (silent) return
        localLoudAt = SystemClock.elapsedRealtime()
        wled.audio(l)
        val r = room
        link?.sendToPeers(LinkProtocol.Nrg(selfId, l)) { p -> p.room?.let { LinkProtocol.sameRoom(it, r) } == true }
    }

    /** Link thread: a same-room device's levels, used while this device hears nothing itself. */
    private fun onPeerLevels(from: String, l: AudioLevels) {
        val peerRoom = link?.peer(from)?.room ?: return
        if (!LinkProtocol.sameRoom(peerRoom, room)) return
        val now = SystemClock.elapsedRealtime()
        if (now - localLoudAt < 500) return
        val current = peerNrgFrom
        if (current != null && current != from && now - peerNrgAt < 500) return // stick to one peer
        peerNrgFrom = from
        peerNrgAt = now
        wled.audio(l)
    }

    // ---- device link and Wi-Fi control -----------------------------------------------------------

    /** Start / stop the device link and the control server to match the settings. */
    private fun ensureServices(ctx: Context, prefs: Prefs) {
        if (selfId.isEmpty()) {
            selfId = prefs.deviceId
            hub = LightHub(selfId)
        }
        room = prefs.room
        val l = link
        if (prefs.linkEnabled && l == null) {
            val nl = Link(selfId) { m -> onLinkMessage(m) }
            if (nl.start()) {
                link = nl
                lastHiAt = 0L
                acquireMulticast(ctx)
                Log.i(TAG, "Device link on (id $selfId, room ${prefs.room})")
            } else {
                Log.w(TAG, "Device link: UDP port ${LinkProtocol.PORT} unavailable (${nl.lastError})")
            }
        } else if (!prefs.linkEnabled && l != null) {
            l.stop()
            link = null
            releaseMulticast()
            setStatus(status.copy(peers = emptyList()))
        }

        val c = control
        if (prefs.controlEnabled && c == null) {
            val server = ControlServer(token = { Prefs(ctx).controlToken }) { action, body -> onControl(ctx, action, body) }
            if (server.start()) control = server else Log.w(TAG, "Wi-Fi control unavailable: ${server.lastError}")
        } else if (!prefs.controlEnabled && c != null) {
            c.stop()
            control = null
        }
        val now = SystemClock.elapsedRealtime()
        if (control == null || now - lastAddrCheck > 10_000L || status.controlUrl == null) {
            lastAddrCheck = now
            controlUrl = if (control != null) ControlServer.lanAddress()?.let { "http://$it:${ControlServer.PORT}" } else null
        }
        val url = controlUrl
        if (url != status.controlUrl) {
            setStatus(status.copy(controlUrl = url))
            if (url != null) sendStatus(ctx, prefs, force = true) // tell HA where to reach us
        }

        if (!heartbeatScheduled && (link != null || listenerActive || driven.isNotEmpty() || control != null)) {
            heartbeatScheduled = true
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
    }

    /** Control server thread: a request from Home Assistant. */
    private fun onControl(ctx: Context, action: String, body: JSONObject): String = when (action) {
        "configure" -> {
            val values = HashMap<String, Any?>()
            for (k in body.keys()) values[k] = body.opt(k)?.takeIf { it != JSONObject.NULL }
            AppSettings.apply(ctx, values)
        }
        "test" -> {
            sendTest(ctx)
            "Sending test colours"
        }
        "status" -> {
            reportStatus(ctx, force = true)
            "Status sent"
        }
        "capture" -> try {
            ctx.startActivity(CaptureConsentActivity.intent(ctx))
            "Requested screen capture"
        } catch (e: Exception) {
            "Could not start screen capture from the background: open the app on the device"
        }
        "stop_capture" -> {
            ScreenCaptureService.stop(ctx)
            "Stopped screen capture"
        }
        else -> "Unknown action $action"
    }

    /** Link thread. Levels go straight to the renderer; everything else via the engine thread. */
    private fun onLinkMessage(m: LinkProtocol.Msg) {
        if (m is LinkProtocol.Nrg) {
            onPeerLevels(m.from, m.levels)
            return
        }
        handler.post {
            val ctx = appCtx ?: return@post
            val now = SystemClock.elapsedRealtime()
            when (m) {
                is LinkProtocol.Claim -> {
                    hub.onClaim(m.from, m.prio, m.playingForMs, m.hosts, now)
                    reevaluateWled(Prefs(ctx))
                }
                is LinkProtocol.Pal -> {
                    val peerRoom = link?.peer(m.from)?.room
                    if (peerRoom != null && LinkProtocol.sameRoom(peerRoom, room)) {
                        peerPal = m
                        peerPalAt = now
                        if (drivenPrio == LightHub.PRIO_LISTENING) reevaluateWled(Prefs(ctx))
                    }
                }
                is LinkProtocol.Hi -> if (now - lastHiAt > 1000) sendHello(ctx, Prefs(ctx), now) // answer newcomers quickly
                is LinkProtocol.Nrg -> Unit
            }
        }
    }

    private fun onHeartbeat() {
        heartbeatScheduled = false
        val ctx = appCtx ?: return
        val prefs = Prefs(ctx)
        val now = SystemClock.elapsedRealtime()
        ensureServices(ctx, prefs)
        if (link != null) {
            if (now - lastHiAt >= HELLO_MS) sendHello(ctx, prefs, now)
            val sent = lastSent
            if (sessionActive && sent != null && now - lastPalAt >= HELLO_MS) sendPal(current, sent, playing = true)
        }
        reevaluateWled(prefs)
        updateLinkStatus(ctx, prefs, now)
        if (!heartbeatScheduled && (link != null || listenerActive || driven.isNotEmpty() || control != null)) {
            heartbeatScheduled = true
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
    }

    private fun sendHello(ctx: Context, prefs: Prefs, now: Long) {
        val l = link ?: return
        lastHiAt = now
        l.broadcast(LinkProtocol.Hi(selfId, deviceName(prefs), if (prefs.isTv) "tv" else "phone", prefs.room, listenerActive))
    }

    /** Share what's playing with devices in the same room. */
    private fun sendPal(np: NowPlaying?, palette: Palette, playing: Boolean) {
        val l = link ?: return
        lastPalAt = SystemClock.elapsedRealtime()
        val r = room
        l.sendToPeers(LinkProtocol.Pal(selfId, LinkProtocol.colorsOf(palette), playing, np?.describe())) { p ->
            p.room?.let { LinkProtocol.sameRoom(it, r) } == true
        }
    }

    private fun updateLinkStatus(ctx: Context, prefs: Prefs, now: Long) {
        val peers = link?.peers()?.map { p ->
            val rm = p.room
            if (rm == null || LinkProtocol.sameRoom(rm, prefs.room)) p.name else "${p.name} (${rm.trim()})"
        }?.sorted() ?: emptyList()
        val mine = myHosts(prefs)
        val renderer = when {
            driven.isNotEmpty() && driven.size < mine.size -> "This device (${driven.size} of ${mine.size} WLEDs)"
            driven.isNotEmpty() -> "This device"
            else -> hub.othersOn(mine, now).firstOrNull()?.let { id -> link?.peer(id)?.name ?: id }
        }
        val a = audio.active
        if (peers != status.peers || renderer != status.renderer || a != status.audio) {
            setStatus(status.copy(peers = peers, renderer = renderer, audio = a))
            sendStatus(ctx, prefs, force = false) // rate-limited on the network thread
        }
    }

    // ---- which WLEDs this device drives --------------------------------------------------------

    private fun myHosts(prefs: Prefs): Set<String> =
        if (!prefs.isWledConfigured) emptySet() else prefs.wledDevices.map { LinkProtocol.normHost(it.host) }.toSet()

    /** Priority this device would drive its WLEDs with right now (0 = it doesn't want to). */
    private fun wantedPrio(prefs: Prefs, now: Long): Int {
        if (!prefs.enabled || !prefs.isWledConfigured) return 0
        if (sessionActive) return LightHub.PRIO_PLAYING
        if (!listenerActive) return 0
        // Backup listener: music heard for a few seconds (not just a door slam), kept until it
        // has been quiet for the stop delay. Or a device in this room plays and shares its colours.
        val heard = now - localLoudAt < 1500
        if (heard && soundSince == 0L) soundSince = now
        val quietLimit = maxOf(prefs.stopDelaySec, 5) * 1000L
        if (!heard && now - localLoudAt > quietLimit) soundSince = 0L
        val listening = soundSince != 0L && now - soundSince >= HUB_START_MS
        val peerPlaying = peerPal?.playing == true && now - peerPalAt < PEER_PAL_TTL_MS
        return if (listening || peerPlaying) LightHub.PRIO_LISTENING else 0
    }

    private fun playingFor(prio: Int, now: Long): Long = when (prio) {
        LightHub.PRIO_PLAYING -> now - playingSince
        else -> if (soundSince == 0L) Long.MAX_VALUE / 4 else now - soundSince
    }

    /**
     * Light this device's WLEDs with [palette], except any another device has priority on.
     * Claims them on the link. Returns the WLED status text.
     */
    private fun driveWled(prefs: Prefs, prio: Int, palette: Palette, sessionStart: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        val cfg = prefs.wledConfig()
        lastWledCfg = cfg
        val mine = myHosts(prefs)
        val allowed = if (link == null) mine else hub.allowedHosts(prio, playingFor(prio, now), mine, now)
        if (allowed.isEmpty()) {
            if (driven.isNotEmpty()) wled.ambient.stop() // another device took them all over
            driven = emptySet()
            drivenPrio = 0
            val who = hub.othersOn(mine, now).firstOrNull()?.let { link?.peer(it)?.name ?: it } ?: "another device"
            return "WLED: $who is driving them"
        }
        val newly = allowed - driven
        // Taking strips over from another device: their colours are not the WLEDs' own state to restore later.
        val save = (sessionStart || newly.isNotEmpty()) && hub.claimedByOthers(newly, now).isEmpty()
        driven = allowed
        drivenPrio = prio
        claim(prio, now)
        val sub = cfg.copy(devices = cfg.devices.filter { LinkProtocol.normHost(it.host) in allowed })
        val r = wled.onColors(sub, palette, save)
        ensureHeartbeat()
        return if (allowed.size < mine.size) "${r.message} (${mine.size - allowed.size} WLED driven by another device)" else r.message
    }

    private fun claim(prio: Int, now: Long) {
        link?.sendToPeers(LinkProtocol.Claim(selfId, prio, playingFor(prio, now), driven))
    }

    /** Stop driving; [restore] puts WLEDs back as they were (except those another device drives). */
    private fun stopDriving(prefs: Prefs, restore: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        link?.sendToPeers(LinkProtocol.Claim(selfId, 0, 0, emptySet()))
        val cfg = lastWledCfg ?: prefs.wledConfig()
        val others = hub.claimedByOthers(myHosts(prefs), now)
        driven = emptySet()
        drivenPrio = 0
        hubShown = null
        return if (restore) wled.onStopped(cfg, others).message else {
            wled.ambient.stop()
            "WLED released"
        }
    }

    /** Start, keep, shrink or stop driving this device's WLEDs, and refresh the claim. Engine thread. */
    private fun reevaluateWled(prefs: Prefs) {
        val now = SystemClock.elapsedRealtime()
        val prio = wantedPrio(prefs, now)
        if (prio == 0) {
            if (driven.isNotEmpty()) stopDriving(prefs, restore = true) // listener went quiet, or syncing / WLED switched off
            return
        }
        val mine = myHosts(prefs)
        val allowed = if (link == null) mine else hub.allowedHosts(prio, playingFor(prio, now), mine, now)
        when (prio) {
            LightHub.PRIO_PLAYING -> {
                val p = lastSent ?: return
                if (allowed != driven) driveWled(prefs, prio, p, sessionStart = false) else claim(prio, now)
            }
            else -> {
                val palette = hubPalette(prefs, now)
                val shown = hubShown
                if (allowed != driven || drivenPrio != prio || shown == null || palette.maxDistanceTo(shown) >= CHANGE_THRESHOLD) {
                    val msg = driveWled(prefs, prio, palette, sessionStart = shown == null)
                    hubShown = palette
                    setStatus(status.copy(lastResult = stamp("Backup listener -> $msg"), palette = palette, source = "listener"))
                } else {
                    claim(prio, now)
                }
            }
        }
    }

    /** As backup listener: the colours a same-room device shares, else the last colours seen. */
    private fun hubPalette(prefs: Prefs, now: Long): Palette {
        val pal = peerPal
        if (pal != null && pal.playing && now - peerPalAt < PEER_PAL_TTL_MS) return LinkProtocol.palette(pal.colors, prefs.extractOptions())
        return lastPalette ?: ColorExtractor.testPalette(prefs.extractOptions())
    }

    private fun releaseWled() {
        val cfg = lastWledCfg ?: return
        link?.sendToPeers(LinkProtocol.Claim(selfId, 0, 0, emptySet()))
        wled.release(cfg.restore)
        lastWledCfg = null
        driven = emptySet()
        drivenPrio = 0
        hubShown = null
    }

    private fun ensureHeartbeat() {
        if (heartbeatScheduled) return
        heartbeatScheduled = true
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun acquireMulticast(ctx: Context) {
        if (multicastLock != null) return
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("mls-link").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No multicast lock; devices may find each other slower", e)
        }
    }

    private fun releaseMulticast() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun deviceName(prefs: Prefs): String = prefs.deviceName.ifBlank { Build.MODEL ?: device }

    private fun ctxOrNull(): Context? = appCtx

    private fun stamp(msg: String): String = "${timeFmt.format(Date())}  $msg"

    private fun setStatus(s: Status) {
        status = s
        notifyUi()
    }
}
