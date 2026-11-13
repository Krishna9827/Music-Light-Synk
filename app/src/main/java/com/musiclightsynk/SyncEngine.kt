package com.musiclightsynk

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
 * WLED strips can also bounce with the music (Energy effect): audio comes from this device
 * ([AudioEngine]) or from another device on the [Link], and only one device drives the strips at
 * a time ([LightHub]): the one playing music, else a phone listening as backup.
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

    data class Status(
        val nowPlaying: String = "Nothing playing",
        val nowPackage: String? = null,
        val lastResult: String = "Nothing sent yet",
        val palette: Palette? = null,
        val source: String? = null,
        val note: String? = null,
        /** "output" / "mic" while listening to music, else null. */
        val audio: String? = null,
        /** Names of the other devices running the app. */
        val peers: List<String> = emptyList(),
        /** Who drives the WLED strips: "This device", another device's name, or null. */
        val renderer: String? = null,
        val listening: Boolean = false,
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
    private var lastStatusJson: String? = null

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
    private var selfId = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private var heartbeatScheduled = false
    private var lastHiAt = 0L
    private var lastPalAt = 0L

    // who drives WLED
    private var rendering = false
    private var renderPrio = 0
    private var playingSince = 0L
    private var listenerActive = false
    private var soundSince = 0L
    private var peerPal: LinkProtocol.Pal? = null
    private var peerPalAt = 0L
    private var lastPalette: Palette? = null
    private var hubShown: Palette? = null
    private val heartbeat = Runnable { onHeartbeat() }

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

    /** The phone's backup listener started / stopped ([ListenerService]). */
    fun setListener(ctx: Context, active: Boolean) {
        val app = ctx.applicationContext
        handler.post {
            appCtx = app
            listenerActive = active
            soundSince = 0L
            val prefs = Prefs(app)
            ensureLink(app, prefs)
            updateAudio(app, prefs)
            updateRendering(prefs)
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
            ensureLink(app, prefs)
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
            if (!prefs.isMqttConfigured) mqtt.close()
            if (!prefs.isWledConfigured) releaseWled()
            ensureLink(app, prefs)
            sendStatus(app, prefs, force = false)
            if (!prefs.enabled) {
                handler.removeCallbacks(pollTask)
                updateAudio(app, prefs)
                updateRendering(prefs)
                return@post
            }
            val np = current
            if (np != null && np.playing && prefs.profileFor(np.packageName) != AppProfile.OFF) {
                process(app, prefs, np, trackChanged = true, force = true)
                schedulePoll(prefs, np)
            }
            hubShown = null // re-send the hub's colours with the new settings
            updateAudio(app, prefs)
            updateRendering(prefs)
        }
    }

    /** Send the fixed rainbow test palette (every corner/side differs). [done] runs on the main thread. */
    fun sendTest(ctx: Context, done: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        handler.post {
            val prefs = Prefs(app)
            val palette = ColorExtractor.testPalette(prefs.extractOptions())
            val json = withStatus(app, prefs, Payloads.colors("test", "test", false, "test", current, palette, device))
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
        ensureLink(ctx, prefs)
        setStatus(status.copy(nowPlaying = np?.describe() ?: "Nothing playing", nowPackage = np?.packageName))
        if (!prefs.enabled) {
            handler.removeCallbacks(pollTask)
            updateAudio(ctx, prefs)
            return
        }

        if (np == null || !np.playing) {
            handler.removeCallbacks(pollTask)
            updateAudio(ctx, prefs)
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
        if (sessionStart) playingSince = SystemClock.elapsedRealtime()
        lastPalette = palette
        if (!force && !sessionStart && palette.maxDistanceTo(lastSent) < CHANGE_THRESHOLD) {
            // Lights already show these colours - don't spam slow bulbs with a duplicate command.
            setStatus(status.copy(palette = palette, source = source))
            return
        }
        val json = withStatus(ctxOrNull(), prefs, Payloads.colors("colors", reason, sessionStart, source, np, palette, device))
        val r = deliver(prefs, json, palette, sessionStart, preview = false)
        if (r.ok) {
            lastSent = palette
            sessionActive = true
        }
        sendPal(np, palette, playing = true)
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
        val prefs = Prefs(ctx)
        val r = deliver(prefs, withStatus(ctx, prefs, Payloads.stopped(current, device)), null, sessionStart = false, preview = false)
        sessionActive = false
        lastSent?.let { sendPal(current, it, playing = false) }
        lastSent = null
        currentKey = null
        lastSource = null
        setStatus(status.copy(lastResult = stamp("Playback stopped -> ${r.message}")))
        updateAudio(ctx, prefs)
        updateRendering(prefs) // a backup listener may take over now
    }

    private fun releaseWled() {
        lastWledCfg?.let { wled.release(it.restore) }
        lastWledCfg = null
        rendering = false
        renderPrio = 0
        hubShown = null
    }

    // ---- audio -------------------------------------------------------------------------------

    /**
     * Listen while this device plays music and something wants the levels (an Energy strip, the
     * beat pulse, or another device on the link), or all the time as the phone's backup listener.
     */
    private fun updateAudio(ctx: Context, prefs: Prefs) {
        val src = prefs.audioSource
        val playing = prefs.enabled && current?.playing == true &&
            current?.let { prefs.profileFor(it.packageName) } != AppProfile.OFF
        val wanted = (prefs.isWledConfigured && prefs.wledConfig().wantsAudio) || link?.peers()?.isNotEmpty() == true
        val want = when {
            !prefs.enabled || src == AudioSource.OFF -> AudioSource.OFF
            playing && wanted -> src
            listenerActive -> AudioSource.MIC
            else -> AudioSource.OFF
        }
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
        link?.sendToPeers(LinkProtocol.Nrg(selfId, l))
    }

    /** Link thread: another device's levels, used while this device hears nothing itself. */
    private fun onPeerLevels(from: String, l: AudioLevels) {
        val now = SystemClock.elapsedRealtime()
        if (now - localLoudAt < 500) return
        val current = peerNrgFrom
        if (current != null && current != from && now - peerNrgAt < 500) return // stick to one peer
        peerNrgFrom = from
        peerNrgAt = now
        wled.audio(l)
    }

    // ---- device link ---------------------------------------------------------------------------

    private fun ensureLink(ctx: Context, prefs: Prefs) {
        if (selfId.isEmpty()) {
            selfId = prefs.deviceId
            hub = LightHub(selfId)
        }
        val l = link
        if (prefs.linkEnabled && l == null) {
            val nl = Link(selfId) { m -> onLinkMessage(m) }
            if (nl.start()) {
                link = nl
                lastHiAt = 0L
                acquireMulticast(ctx)
                Log.i(TAG, "Device link on (id $selfId)")
            } else {
                Log.w(TAG, "Device link: UDP port ${LinkProtocol.PORT} unavailable")
            }
        } else if (!prefs.linkEnabled && l != null) {
            l.stop()
            link = null
            releaseMulticast()
            setStatus(status.copy(peers = emptyList()))
        }
        if (!heartbeatScheduled && (link != null || listenerActive || rendering)) {
            heartbeatScheduled = true
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
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
                    hub.onClaim(m.from, m.prio, m.playingForMs, now)
                    updateRendering(Prefs(ctx))
                }
                is LinkProtocol.Pal -> {
                    peerPal = m
                    peerPalAt = now
                    lastPalette = LinkProtocol.palette(m.colors, Prefs(ctx).extractOptions())
                    if (rendering && renderPrio == LightHub.PRIO_LISTENING) updateRendering(Prefs(ctx))
                }
                is LinkProtocol.Wled -> {
                    val prefs = Prefs(ctx)
                    if (m.rev > prefs.wledRev) {
                        Log.i(TAG, "WLED devices updated from ${link?.peer(m.from)?.name ?: m.from}")
                        prefs.applySyncedWled(m.devices, m.rev)
                        settingsChanged(ctx)
                        notifyUi()
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
        val l = link
        if (l != null) {
            if (now - lastHiAt >= HELLO_MS) sendHello(ctx, prefs, now)
            val sent = lastSent
            if (sessionActive && sent != null && now - lastPalAt >= HELLO_MS) sendPal(current, sent, playing = true)
        }
        updateRendering(prefs)
        updateAudio(ctx, prefs) // e.g. another device just appeared and wants our levels
        updateLinkStatus(now)
        if (link != null || listenerActive || rendering) {
            heartbeatScheduled = true
            handler.postDelayed(heartbeat, HEARTBEAT_MS)
        }
    }

    private fun sendHello(ctx: Context, prefs: Prefs, now: Long) {
        val l = link ?: return
        lastHiAt = now
        l.broadcast(LinkProtocol.Hi(selfId, deviceName(prefs), platform(ctx), listenerActive))
        if (prefs.wledRev > 0) l.sendToPeers(LinkProtocol.Wled(selfId, prefs.wledDevicesRaw, prefs.wledRev))
    }

    private fun sendPal(np: NowPlaying?, palette: Palette, playing: Boolean) {
        val l = link ?: return
        lastPalAt = SystemClock.elapsedRealtime()
        l.sendToPeers(LinkProtocol.Pal(selfId, LinkProtocol.colorsOf(palette), playing, np?.describe()))
    }

    private fun updateLinkStatus(now: Long) {
        val peers = link?.peers()?.map { it.name }?.sorted() ?: emptyList()
        val renderer = when {
            rendering -> "This device"
            else -> hub.otherRenderer(now)?.let { id -> link?.peer(id)?.name ?: id }
        }
        val a = audio.active
        if (peers != status.peers || renderer != status.renderer || a != status.audio) {
            setStatus(status.copy(peers = peers, renderer = renderer, audio = a))
            appCtx?.let { sendStatus(it, Prefs(it), force = false) } // keep Home Assistant's sensors current
        }
    }

    // ---- who drives WLED -----------------------------------------------------------------------

    /** Priority this device would drive WLED with right now (0 = it doesn't want to). */
    private fun wantedPrio(prefs: Prefs, now: Long): Int {
        if (!prefs.enabled || !prefs.isWledConfigured) return 0
        if (sessionActive) return LightHub.PRIO_PLAYING
        if (!listenerActive) return 0
        // Backup listener: music heard for a few seconds (not just a door slam), kept until
        // it has been quiet for the stop delay.
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

    private fun mayRender(prio: Int, now: Long): Boolean =
        if (link == null) prio > 0 else hub.mayRender(prio, playingFor(prio, now), now)

    /** Start, keep or stop driving WLED, and tell the other devices. Engine thread. */
    private fun updateRendering(prefs: Prefs) {
        val now = SystemClock.elapsedRealtime()
        val prio = wantedPrio(prefs, now)
        val may = mayRender(prio, now)
        val l = link
        if (may) l?.sendToPeers(LinkProtocol.Claim(selfId, prio, playingFor(prio, now)))
        when {
            may && prio == LightHub.PRIO_PLAYING -> {
                if (!rendering) {
                    // Took over (or got back) the strips mid-song: show this device's colours.
                    rendering = true
                    renderPrio = prio
                    lastSent?.let { p ->
                        val cfg = prefs.wledConfig()
                        lastWledCfg = cfg
                        wled.onColors(cfg, p, sessionStart = hub.otherRenderer(now) == null)
                    }
                }
                renderPrio = prio
                hubShown = null
            }
            may && prio == LightHub.PRIO_LISTENING -> {
                rendering = true
                renderPrio = prio
                showHubColours(prefs, now)
            }
            !may && rendering -> {
                rendering = false
                renderPrio = 0
                hubShown = null
                if (prio == 0) {
                    l?.sendToPeers(LinkProtocol.Claim(selfId, 0, 0))
                    stopWled(prefs)
                } else {
                    // Another device took over: stop streaming, but don't put WLED's old state back
                    // now; that could land after the other device's colours.
                    wled.ambient.stop()
                }
            }
            // We stepped aside earlier and nobody drives the strips any more: put WLED back as it was.
            !rendering && prio == 0 && wled.hasSavedState && hub.otherRenderer(now) == null -> stopWled(prefs)
        }
        if (!heartbeatScheduled && rendering) ensureLink(appCtx ?: return, prefs)
    }

    private fun stopWled(prefs: Prefs) {
        lastWledCfg?.let { wled.onStopped(it) } ?: wled.release(prefs.wledRestore)
    }

    /** As backup listener: the playing device's colours if one tells us, else the last colours seen. */
    private fun showHubColours(prefs: Prefs, now: Long) {
        val pal = peerPal
        val palette = if (pal != null && pal.playing && now - peerPalAt < PEER_PAL_TTL_MS) {
            LinkProtocol.palette(pal.colors, prefs.extractOptions())
        } else {
            lastPalette ?: ColorExtractor.testPalette(prefs.extractOptions())
        }
        val shown = hubShown
        if (shown != null && palette.maxDistanceTo(shown) < CHANGE_THRESHOLD) return
        val cfg = prefs.wledConfig()
        lastWledCfg = cfg
        val r = wled.onColors(cfg, palette, sessionStart = shown == null && hub.otherRenderer(now) == null)
        hubShown = palette
        setStatus(status.copy(lastResult = stamp("Backup listener -> ${r.message}"), palette = palette, source = "listener"))
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

    private fun platform(ctx: Context): String =
        if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) "tv" else "phone"

    private fun ctxOrNull(): Context? = appCtx

    private fun appVersion(ctx: Context): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Everything Home Assistant needs to show and control the app. No secrets (token, passwords). */
    private fun statusJson(ctx: Context, prefs: Prefs): JSONObject = JSONObject()
        .put("app_version", appVersion(ctx))
        .put("media_access", MediaListenerService.isEnabled(ctx))
        .put("media_connected", MediaListenerService.connected)
        .put("screen_capture", ScreenCaptureService.isRunning)
        .put("source_mode", prefs.sourceMode.key)
        .put("last_source", lastSource ?: JSONObject.NULL)
        .put("enabled", prefs.enabled)
        .put("audio", audio.active ?: JSONObject.NULL)
        .put("listening", listenerActive)
        .put("link_peers", org.json.JSONArray(status.peers))
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
                .put("mqtt_enabled", prefs.mqttEnabled),
        )

    /** Attach the current status to an outgoing payload (keeps HA in sync for free). */
    private fun withStatus(ctx: Context?, prefs: Prefs, json: JSONObject): JSONObject {
        if (ctx == null) return json
        val st = statusJson(ctx, prefs)
        lastStatusJson = st.toString()
        return json.put("status", st)
    }

    private fun sendStatus(ctx: Context, prefs: Prefs, force: Boolean) {
        val st = statusJson(ctx, prefs)
        val text = st.toString()
        if (!force && text == lastStatusJson) return
        if (!prefs.isHaConfigured && !prefs.isMqttConfigured) return
        lastStatusJson = text
        val json = Payloads.status(current, device, st)
        val body = json.toString()
        if (prefs.isHaConfigured) for (id in prefs.webhookIds) HaClient.postJson(prefs.webhookUrl(id), body)
        if (prefs.isMqttConfigured) mqtt.publish(prefs.mqttConfig(device, Build.MODEL ?: ""), json)
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
            val wasRendering = rendering && renderPrio == LightHub.PRIO_PLAYING
            // Taking the strips from another device: its colours are not WLED's "own" state to restore later.
            val takingOver = !wasRendering && hub.otherRenderer(SystemClock.elapsedRealtime()) != null
            val r = when {
                stopped -> if (rendering && renderPrio == LightHub.PRIO_PLAYING) {
                    rendering = false
                    renderPrio = 0
                    link?.sendToPeers(LinkProtocol.Claim(selfId, 0, 0))
                    wled.onStopped(cfg)
                } else {
                    null
                }
                palette == null -> null
                preview -> wled.onColors(cfg, palette, sessionStart, preview = true)
                // taking the strips over mid-song counts as a new session (save WLED's state first)
                claimWled(prefs) -> wled.onColors(cfg, palette, (sessionStart || !wasRendering) && !takingOver, preview = false)
                else -> WledController.Result(false, "WLED: ${status.renderer ?: "another device"} drives the strips")
            }
            if (r != null) {
                if (r.ok) ok = true
                parts += r.message
            }
        }
        return HaClient.Result(ok, if (ok) 200 else -1, parts.joinToString(" | "))
    }

    /** About to send this device's colours: may it drive WLED (it plays music)? Claims it if so. */
    private fun claimWled(prefs: Prefs): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!mayRender(LightHub.PRIO_PLAYING, now)) return false
        if (!rendering || renderPrio != LightHub.PRIO_PLAYING) {
            rendering = true
            renderPrio = LightHub.PRIO_PLAYING
            hubShown = null
            link?.sendToPeers(LinkProtocol.Claim(selfId, LightHub.PRIO_PLAYING, playingFor(LightHub.PRIO_PLAYING, now)))
            appCtx?.let { ensureLink(it, prefs) }
        }
        return true
    }

    private fun stamp(msg: String): String = "${timeFmt.format(Date())}  $msg"

    private fun setStatus(s: Status) {
        status = s
        notifyUi()
    }
}
