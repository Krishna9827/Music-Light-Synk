import Foundation

/// The brain, ported from the Android app's SyncEngine: decides when to send colours (one command
/// per track / cover change), drives WLED first and talks to Home Assistant on its own queue,
/// listens through the microphone, and takes part in the device link (shared WLEDs, lending the
/// microphone). All state lives on [q]; the audio thread only touches the locked fields.
public final class Engine {
    public struct Status {
        public var nowPlaying = "Nothing playing"
        public var lastResult = "Nothing sent yet"
        public var palette: Palette?
        public var source: String?
        public var note: String?
        public var hearing = false
        public var level = 0.0
        public var peers: [String] = []
        public var renderer: String?
        public var controlURL: String?

        public init() {}
    }

    static let changeThreshold = 18
    static let helloMs: Int64 = 3000
    static let hubStartMs: Int64 = 3000
    static let peerPalTtlMs: Int64 = 10_000
    static let statusMinMs: Int64 = 3000

    public let settings: AppSettings
    public let platform: String
    public let appVersion: String
    public let defaultName: String
    /// Main thread, after any status change.
    public var onChange: (() -> Void)?

    private let q = DispatchQueue(label: "mls.engine")
    private let net = DispatchQueue(label: "mls.net")
    private let beatQ = DispatchQueue(label: "mls.beat")
    private let wled: WledController
    private var hub: LightHub
    private var timer: DispatchSourceTimer?
    private var control: ControlServer?

    // shared with the audio / link threads
    private let shared = NSLock()
    private var _status = Status()
    private var _link: Link?
    private var _beatUrls: [String] = []
    private var _haMusic = "off"
    private var _loudAt: Int64 = 0
    private var _lastBeatAt: Int64 = 0
    private var _lastLevel = -1.0
    private var _beatBusy = false
    private var _lastUiLevel: Int64 = 0
    private var selfId = ""

    // engine queue only
    private var current: NowPlaying?
    private var currentKey: String?
    private var sessionActive = false
    private var lastSent: Palette?
    private var lastSource: String?
    private var stopItem: DispatchWorkItem?
    private var artKey: String?
    private var artPalette: Palette?
    private var driven: Set<String> = []
    private var drivenPrio = 0
    private var playingSince: Int64 = 0
    private var soundSince: Int64 = 0
    private var lastPalette: Palette?
    private var hubShown: Palette?
    private var peerPal: (colors: [Slot: Rgb], playing: Bool, at: Int64)?
    private var lastHiAt: Int64 = 0
    private var lastPalAt: Int64 = 0
    private var lastStatusJson = ""
    private var lastStatusAt: Int64 = 0
    private var lastHaSent: Palette?
    private var lastHaAt: Int64 = 0
    private var screenPal: (palette: Palette, at: Int64)?
    private var screenRunning = false
    /// The playing track gave no cover (auto mode then follows the screen).
    private var noArtTrack: String?

    public init(settings: AppSettings = AppSettings(), platform: String, appVersion: String, defaultName: String, wledPort: UInt16 = WledPackets.udpPort) {
        self.settings = settings
        self.platform = platform
        self.appVersion = appVersion
        self.defaultName = defaultName
        wled = WledController(port: wledPort)
        hub = LightHub(selfId: "")
    }

    public var status: Status { shared.locked { _status } }

    /// The microphone should run (the mode lends it or listens with it).
    public var micWanted: Bool {
        settings.mode.micOn && (settings.audioSource == "mic" || (settings.audioSource == "output" && platform != "mac"))
    }

    /// The Mac's own sound should be captured instead of the microphone.
    public var outputWanted: Bool { platform == "mac" && settings.mode.micOn && settings.audioSource == "output" }

    /// The screen should be sampled (Mac): colours from the screen, all the time or as the fallback.
    public var screenWanted: Bool { platform == "mac" && settings.mode.lights && settings.sourceMode != "artwork" }

    public var deviceName: String { settings.deviceName.isEmpty ? defaultName : settings.deviceName }

    // ---- public API (any thread) ---------------------------------------------------------------

    public func start() {
        q.async { [self] in
            ensureServices()
            if timer == nil {
                let t = DispatchSource.makeTimerSource(queue: q)
                t.schedule(deadline: .now() + 1, repeating: 1)
                t.setEventHandler { [weak self] in self?.heartbeat() }
                timer = t
                t.resume()
            }
            sendStatus(force: true)
        }
    }

    public func nowPlaying(_ np: NowPlaying?) { q.async { self.handleNowPlaying(np) } }

    /// A new screen picture's colours (Mac; any thread). Only complete, changed frames come here.
    public func screen(_ palette: Palette) { q.async { self.handleScreen(palette) } }

    /// The Mac's screen capture started or stopped (any thread).
    public func screenCapture(running: Bool) {
        q.async { [self] in
            screenRunning = running
            if !running {
                screenPal = nil
                if sessionActive && lastSource == "screen" && settings.sourceMode == "screen" { endSession("Screen capture stopped") }
            }
        }
    }

    public func settingsChanged() {
        q.async { [self] in
            artKey = nil
            ensureServices()
            hubShown = nil
            if settings.mode.lights {
                let screenMode = settings.sourceMode == "screen"
                if !screenMode && sessionActive && lastSource == "screen" && current?.playing != true {
                    endSession("Screen colours off")
                }
                if screenMode {
                    stopItem?.cancel()
                    stopItem = nil
                    if let sp = screenPal { sendPalette(current, sp.palette, source: "screen", reason: "screen", force: true) }
                } else if let np = current, np.playing {
                    process(np, trackChanged: true, force: true)
                }
            } else {
                endSession("Lights off") // "Microphone only" / "Off": give every light back now
            }
            reevaluateWled()
            sendStatus(force: false)
            if let l = shared.locked({ _link }) { sendHello(l, monotonicMs()) }
        }
    }

    public func sendTest() {
        q.async { [self] in
            let palette = ColorExtractor.testPalette(settings.extractOptions)
            let body = withStatus(Payloads.colors(event: "test", reason: "test", sessionStart: false, source: "test", np: current, palette: palette, device: deviceName))
            var parts: [String] = []
            if settings.isWledConfigured {
                parts.append(wled.onColors(settings.wledConfig, palette, saveState: true, preview: true))
            }
            if settings.isHaConfigured { postHa(body); parts.append("HA sending") }
            update { $0.lastResult = Engine.stamp("Test colours -> " + (parts.isEmpty ? "nothing set up yet" : parts.joined(separator: " | "))); $0.palette = palette; $0.source = "test" }
            if sessionActive, let np = current {
                q.asyncAfter(deadline: .now() + .milliseconds(WledController.previewMs)) { self.process(np, trackChanged: false, force: true) }
            }
        }
    }

    /// Audio thread: this device's microphone.
    public func levels(_ l: AudioLevels, silent: Bool) {
        let now = monotonicMs()
        let (link, music, uiDue) = shared.locked { () -> (Link?, String, Bool) in
            if !silent { _loudAt = now }
            let due = now - _lastUiLevel > 100
            if due { _lastUiLevel = now; _status.level = silent ? 0 : l.overall; _status.hearing = !silent }
            return (_link, _haMusic, due)
        }
        if uiDue { DispatchQueue.main.async { self.onChange?() } }
        if silent { return }
        wled.audio(l)
        if music == "level" { maybeLevel(l) } else if music == "beat" { maybeBeat(l) }
        let me = selfId
        link?.sendToPeers(.nrg(from: me, levels: l)) { $0.src == me }
    }

    // ---- what's playing ----------------------------------------------------------------------

    private func handleNowPlaying(_ np: NowPlaying?) {
        current = np
        update { $0.nowPlaying = np?.describe() ?? "Nothing playing" }
        guard settings.mode.lights else { return }
        if settings.sourceMode == "screen" {
            // The screen drives the lights whether or not music plays.
            currentKey = np?.trackKey
            return
        }
        guard let np, np.playing else {
            if sessionActive && stopItem == nil {
                let item = DispatchWorkItem { [weak self] in self?.endSession("Playback stopped") }
                stopItem = item
                q.asyncAfter(deadline: .now() + .seconds(settings.stopDelaySec), execute: item)
            }
            return
        }
        stopItem?.cancel()
        stopItem = nil
        let changed = np.trackKey != currentKey
        currentKey = np.trackKey
        process(np, trackChanged: changed, force: false)
    }

    private func process(_ np: NowPlaying, trackChanged: Bool, force: Bool) {
        let reason = trackChanged ? "track" : "update"
        if settings.sourceMode != "screen", let palette = artworkPalette(np) {
            noArtTrack = nil
            sendPalette(np, palette, source: "artwork", reason: reason, force: force)
            return
        }
        noArtTrack = np.trackKey
        if settings.sourceMode == "auto" {
            if let sp = screenPal {
                sendPalette(np, sp.palette, source: "screen", reason: reason, force: force)
            } else {
                update { $0.lastResult = Engine.stamp("No colours: \(np.app) gave no album art, and the screen gave no picture yet") }
            }
            return
        }
        update { $0.lastResult = Engine.stamp("No colours: \(np.app) gave no album art") }
    }

    /// Screen frames: in "screen" mode they drive the lights all the time; in "auto" mode while
    /// the playing app gives no cover. WLED follows every change; Home Assistant gets new colours
    /// at most every screen_interval seconds (0: only when the track changes).
    private func handleScreen(_ palette: Palette) {
        let now = monotonicMs()
        if palette.isBlank { return }
        screenPal = (palette, now)
        guard settings.mode.lights else { return }
        let mode = settings.sourceMode
        let follow = mode == "screen" || (mode == "auto" && current?.playing == true && noArtTrack != nil && noArtTrack == current?.trackKey)
        guard follow else { return }
        let iv = Int64(settings.screenIntervalSec) * 1000
        let haDue = !sessionActive || (iv > 0 && now - lastHaAt >= iv)
        sendPalette(current, palette, source: "screen", reason: sessionActive ? "screen_poll" : "screen", force: false, haAllowed: haDue)
    }

    private func artworkPalette(_ np: NowPlaying) -> Palette? {
        guard let key = np.artworkKey else { return nil }
        if key == artKey, let p = artPalette { return p }
        var data = np.artworkData
        if data == nil, let url = np.artworkURL { data = Artwork.download(url) }
        guard let d = data, let p = Artwork.palette(d, settings.extractOptions) else { return nil }
        artKey = key
        artPalette = p
        return p
    }

    private func sendPalette(_ np: NowPlaying?, _ palette: Palette, source: String, reason: String, force: Bool, haAllowed: Bool = true) {
        lastSource = source
        lastPalette = palette
        guard settings.hasOutput else {
            update { $0.palette = palette; $0.source = source; $0.lastResult = Engine.stamp("Not sent: set up WLED or Home Assistant first") }
            return
        }
        let start = !sessionActive
        if start { playingSince = monotonicMs() }
        // Lights already showing these colours get nothing (slow bulbs stay calm).
        let newColours = force || start || palette.maxDistance(to: lastSent) >= Engine.changeThreshold
        let haNow = settings.isHaConfigured && (force || start || (haAllowed && palette.maxDistance(to: lastHaSent) >= Engine.changeThreshold))
        if !newColours && !haNow {
            update { $0.palette = palette; $0.source = source }
            return
        }
        sessionActive = true
        var parts: [String] = []
        if newColours {
            lastSent = palette
            if settings.isWledConfigured { parts.append(driveWled(LightHub.prioPlaying, palette, sessionStart: start)) }
            sendPal(palette, playing: true, title: np?.describe())
        }
        if haNow {
            lastHaSent = palette
            lastHaAt = monotonicMs()
            postHa(withStatus(Payloads.colors(event: "colors", reason: reason, sessionStart: start, source: source, np: np, palette: palette, device: deviceName)))
            parts.append("HA sending")
        }
        update {
            if !parts.isEmpty { $0.lastResult = Engine.stamp("Sent \(source) colours -> " + parts.joined(separator: " | ")) }
            $0.palette = palette
            $0.source = source
        }
    }

    private func endSession(_ label: String) {
        stopItem?.cancel()
        stopItem = nil
        guard sessionActive else { return }
        sessionActive = false
        var parts: [String] = []
        if drivenPrio == LightHub.prioPlaying { parts.append(stopDriving(restore: true)) }
        if settings.isHaConfigured { postHa(withStatus(Payloads.stopped(current, device: deviceName))); parts.append("HA sending") }
        if let p = lastSent { sendPal(p, playing: false, title: nil) }
        lastSent = nil
        lastHaSent = nil
        lastHaAt = 0
        currentKey = nil
        update { $0.lastResult = Engine.stamp("\(label) -> " + parts.joined(separator: " | ")) }
        reevaluateWled()
    }

    // ---- Home Assistant ------------------------------------------------------------------------

    private func postHa(_ body: [String: Any]) {
        let urls = settings.isHaConfigured ? settings.webhookIds.map(settings.webhookUrl) : []
        var b = body
        if let c = controlBlock() { b["control"] = c }
        net.async {
            var parts: [String] = []
            for u in urls {
                var r = Http.request(u, method: "POST", json: b)
                if !r.ok && r.code < 0 { Thread.sleep(forTimeInterval: 0.8); r = Http.request(u, method: "POST", json: b) }
                parts.append("HA \(r.message)")
            }
            if body["event"] as? String != "status" {
                self.update { $0.lastResult = $0.lastResult.replacingOccurrences(of: "HA sending", with: parts.joined(separator: " | ")) }
            }
        }
    }

    private func controlBlock() -> [String: Any]? {
        guard settings.controlEnabled, let url = shared.locked({ _status.controlURL }) else { return nil }
        return ["url": url, "token": settings.controlToken]
    }

    private func maybeBeat(_ l: AudioLevels) {
        guard l.beat else { return }
        let now = monotonicMs()
        guard let urls = shared.locked({ () -> [String]? in
            if _beatUrls.isEmpty || now - _lastBeatAt < 200 || _beatBusy { return nil }
            _lastBeatAt = now
            _beatBusy = true
            return _beatUrls
        }) else { return }
        let body: [String: Any] = ["event": "beat", "level": (max(l.lows, l.overall) * 100).rounded() / 100, "device": deviceName]
        beatQ.async {
            for u in urls { _ = Http.request(u, method: "POST", json: body, timeout: 1.2) }
            self.shared.locked { self._beatBusy = false }
        }
    }

    private func maybeLevel(_ l: AudioLevels) {
        let now = monotonicMs()
        let level = (max(l.lows, l.mids * 0.8) * 100).rounded() / 100
        guard let urls = shared.locked({ () -> [String]? in
            if _beatUrls.isEmpty || now - _lastBeatAt < 120 || _beatBusy { return nil }
            if abs(level - _lastLevel) < 0.05 && now - _lastBeatAt < 1000 { return nil }
            _lastBeatAt = now
            _lastLevel = level
            _beatBusy = true
            return _beatUrls
        }) else { return }
        let body: [String: Any] = ["event": "level", "level": level, "beat": l.beat, "device": deviceName]
        beatQ.async {
            for u in urls { _ = Http.request(u, method: "POST", json: body, timeout: 1.2) }
            self.shared.locked { self._beatBusy = false }
        }
    }

    // ---- status --------------------------------------------------------------------------------

    public func statusJson() -> [String: Any] {
        let st = status
        let wledList: [[String: Any]] = settings.wledDevices.map { d in
            ["host": d.host, "zone": d.zone.key, "label": d.zone.label, "energy": d.effect.energy, "flip": d.effect.flip,
             "mirror": d.effect.mirror, "spec": "\(d.host)=\(WledDevices.formatDevice(d))"]
        }
        let what = platform == "ios" ? "iPhone" : "Mac"
        var sources = [["key": "mic", "label": "This \(what)'s microphone"]]
        if platform == "mac" { sources.append(["key": "output", "label": "This Mac's sound (what it plays)"]) }
        sources.append(["key": "off", "label": "Off"])
        let heard: Any = st.hearing ? (outputWanted ? "output" : "mic") : NSNull()
        return [
            "app_version": appVersion, "platform": platform, "media_access": true, "media_connected": true,
            "screen_capture": screenRunning, "source_mode": platform == "mac" ? settings.sourceMode : "artwork",
            "last_source": lastSource as Any? ?? NSNull(),
            "enabled": settings.mode.lights, "mode": settings.mode.rawValue, "modes": DeviceMode.allCases.map { $0.rawValue },
            "wled": wledList, "audio": heard, "audio_peer": NSNull(),
            "audio_sources": sources,
            "listening": settings.mode.micOn, "link_peers": st.peers, "wled_renderer": st.renderer as Any? ?? NSNull(),
            "settings": settings.snapshot(),
        ]
    }

    private func withStatus(_ body: [String: Any]) -> [String: Any] {
        var b = body
        b["status"] = statusJson()
        return b
    }

    private func sendStatus(force: Bool) {
        guard settings.isHaConfigured else { return }
        let st = statusJson()
        let text = (try? JSONSerialization.data(withJSONObject: st, options: .sortedKeys)).map { String(decoding: $0, as: UTF8.self) } ?? ""
        let now = monotonicMs()
        if !force && (text == lastStatusJson || now - lastStatusAt < Engine.statusMinMs) { return }
        lastStatusJson = text
        lastStatusAt = now
        postHa(Payloads.status(current, device: deviceName, status: st))
    }

    // ---- device link and Wi-Fi control ---------------------------------------------------------

    private func ensureServices() {
        if selfId.isEmpty {
            selfId = settings.deviceId
            hub = LightHub(selfId: selfId)
        }
        let link = shared.locked { _link }
        if settings.linkEnabled && link == nil {
            let l = Link(selfId: selfId) { [weak self] m in self?.onLinkMessage(m) }
            if l.start() { shared.locked { _link = l }; lastHiAt = 0 }
        } else if !settings.linkEnabled, let l = link {
            l.stop()
            shared.locked { _link = nil; _status.peers = [] }
        }
        if settings.controlEnabled && control == nil {
            let c = ControlServer(token: { [weak self] in self?.settings.controlToken ?? "" }) { [weak self] action, body in
                self?.onControl(action, body) ?? "gone"
            }
            if c.start() { control = c }
        } else if !settings.controlEnabled, let c = control {
            c.stop()
            control = nil
        }
        let url = control != nil ? UdpSocket.lanAddress().map { "http://\($0):\(ControlServer.port)" } : nil
        if url != status.controlURL { update { $0.controlURL = url } }
    }

    private func onControl(_ action: String, _ body: [String: Any]) -> String {
        switch action {
        case "configure":
            let msg = settings.configure(body)
            settingsChanged()
            DispatchQueue.main.async { self.onChange?() }
            return msg
        case "test":
            sendTest()
            return "Sending test colours"
        case "status":
            q.async { self.sendStatus(force: true) }
            return "Status sent"
        case "capture" where platform == "mac":
            if settings.sourceMode == "artwork" { settings.sourceMode = "auto" }
            settingsChanged()
            DispatchQueue.main.async { self.onChange?() }
            return "Screen colours on (\(settings.sourceMode))"
        case "stop_capture" where platform == "mac":
            settings.sourceMode = "artwork"
            settingsChanged()
            DispatchQueue.main.async { self.onChange?() }
            return "Screen colours off"
        default:
            return "Screen capture isn't available on this device"
        }
    }

    private func onLinkMessage(_ m: LinkProtocol.Msg) {
        q.async { [self] in
            let now = monotonicMs()
            switch m {
            case let .claim(from, prio, age, hosts):
                hub.onClaim(id: from, prio: prio, playingForMs: age, hosts: hosts, nowMs: now)
                reevaluateWled()
            case let .pal(from, colors, playing, _):
                if let room = shared.locked({ _link })?.peer(from)?.room, LinkProtocol.sameRoom(room, settings.room) {
                    peerPal = (colors, playing, now)
                    if drivenPrio == LightHub.prioListening { reevaluateWled() }
                }
            case .hi:
                if now - lastHiAt > 1000, let l = shared.locked({ _link }) { sendHello(l, now) }
            case .nrg:
                break
            }
        }
    }

    private func heartbeat() {
        let now = monotonicMs()
        ensureServices()
        if let l = shared.locked({ _link }) {
            if now - lastHiAt >= Engine.helloMs { sendHello(l, now) }
            if sessionActive, let p = lastSent, now - lastPalAt >= Engine.helloMs { sendPal(p, playing: true, title: current?.describe()) }
        }
        reevaluateWled()
        let musicOn = settings.mode.lights && settings.haMusic != "off" && settings.isHaConfigured && (sessionActive || hearingMusic(now))
        let urls = musicOn ? settings.webhookIds.map(settings.webhookUrl) : []
        let peers = (shared.locked { _link }?.peers() ?? []).map { p -> String in
            if let r = p.room, !LinkProtocol.sameRoom(r, settings.room) { return "\(p.name) (\(r))" }
            return p.name
        }.sorted()
        let mine = myHosts()
        let renderer: String? = !driven.isEmpty ? "This device"
            : hub.othersOn(mine, nowMs: now).first.map { id in shared.locked { _link }?.peer(id)?.name ?? id }
        shared.locked { _beatUrls = urls; _haMusic = settings.haMusic }
        if peers != status.peers || renderer != status.renderer { update { $0.peers = peers; $0.renderer = renderer } }
        sendStatus(force: false)
    }

    private func sendHello(_ l: Link, _ now: Int64) {
        lastHiAt = now
        let micOn = settings.mode.micOn
        l.broadcast(.hi(from: selfId, name: deviceName, platform: platform, room: settings.room, listening: micOn, mic: micOn, src: ""))
    }

    private func sendPal(_ p: Palette, playing: Bool, title: String?) {
        guard let l = shared.locked({ _link }) else { return }
        lastPalAt = monotonicMs()
        let room = settings.room
        l.sendToPeers(.pal(from: selfId, colors: p.rawColors, playing: playing, title: title)) { peer in
            peer.room.map { LinkProtocol.sameRoom($0, room) } ?? false
        }
    }

    // ---- which WLEDs this device drives --------------------------------------------------------

    private func myHosts() -> Set<String> {
        settings.isWledConfigured ? Set(settings.wledDevices.map { LinkProtocol.normHost($0.host) }) : []
    }

    private func hearingMusic(_ now: Int64) -> Bool {
        guard settings.mode.micOn else { soundSince = 0; return false }
        let loudAt = shared.locked { _loudAt }
        let heard = now - loudAt < 1500
        if heard && soundSince == 0 { soundSince = now }
        if !heard && now - loudAt > Int64(max(settings.stopDelaySec, 5)) * 1000 { soundSince = 0 }
        return soundSince != 0 && now - soundSince >= Engine.hubStartMs
    }

    private func wantedPrio(_ now: Int64) -> Int {
        guard settings.mode.lights, settings.isWledConfigured else { return 0 }
        if sessionActive { return LightHub.prioPlaying }
        let peerPlaying = settings.mode.micOn && (peerPal.map { $0.playing && now - $0.at < Engine.peerPalTtlMs } ?? false)
        return hearingMusic(now) || peerPlaying ? LightHub.prioListening : 0
    }

    private func playingFor(_ prio: Int, _ now: Int64) -> Int64 {
        prio == LightHub.prioPlaying ? now - playingSince : (soundSince == 0 ? Int64.max / 4 : now - soundSince)
    }

    private func driveWled(_ prio: Int, _ palette: Palette, sessionStart: Bool) -> String {
        let now = monotonicMs()
        let cfg = settings.wledConfig
        let mine = myHosts()
        let link = shared.locked { _link }
        let allowed = link == nil ? mine : hub.allowedHosts(prio: prio, playingForMs: playingFor(prio, now), mine: mine, nowMs: now)
        if allowed.isEmpty {
            if !driven.isEmpty { wled.yield() }
            driven = []
            drivenPrio = 0
            let who = hub.othersOn(mine, nowMs: now).first.map { link?.peer($0)?.name ?? $0 } ?? "another device"
            return "WLED: \(who) is driving them"
        }
        let newly = allowed.subtracting(driven)
        let save = (sessionStart || !newly.isEmpty) && hub.claimedByOthers(newly, nowMs: now).isEmpty
        driven = allowed
        drivenPrio = prio
        claim(prio, now)
        var sub = cfg
        sub.devices = cfg.devices.filter { allowed.contains(LinkProtocol.normHost($0.host)) }
        let msg = wled.onColors(sub, palette, saveState: save)
        return allowed.count < mine.count ? "\(msg) (\(mine.count - allowed.count) WLED driven by another device)" : msg
    }

    private func claim(_ prio: Int, _ now: Int64) {
        shared.locked { _link }?.sendToPeers(.claim(from: selfId, prio: prio, playingForMs: playingFor(prio, now), hosts: driven))
    }

    private func stopDriving(restore: Bool) -> String {
        let now = monotonicMs()
        shared.locked { _link }?.sendToPeers(.claim(from: selfId, prio: 0, playingForMs: 0, hosts: []))
        let others = hub.claimedByOthers(myHosts(), nowMs: now)
        driven = []
        drivenPrio = 0
        hubShown = nil
        if restore { return wled.onStopped(settings.wledConfig, othersDrive: others) }
        wled.yield()
        return "WLED released"
    }

    private func reevaluateWled() {
        let now = monotonicMs()
        let prio = wantedPrio(now)
        if prio == 0 {
            if !driven.isEmpty { _ = stopDriving(restore: true) }
            return
        }
        let mine = myHosts()
        let allowed = shared.locked({ _link }) == nil ? mine : hub.allowedHosts(prio: prio, playingForMs: playingFor(prio, now), mine: mine, nowMs: now)
        if prio == LightHub.prioPlaying {
            guard let p = lastSent else { return }
            if allowed != driven { _ = driveWled(prio, p, sessionStart: false) } else { claim(prio, now) }
            return
        }
        let palette: Palette
        if let pp = peerPal, pp.playing, now - pp.at < Engine.peerPalTtlMs {
            palette = LinkProtocol.palette(pp.colors, settings.extractOptions)
        } else {
            palette = lastPalette ?? ColorExtractor.testPalette(settings.extractOptions)
        }
        if allowed != driven || drivenPrio != prio || hubShown == nil || palette.maxDistance(to: hubShown) >= Engine.changeThreshold {
            let msg = driveWled(prio, palette, sessionStart: hubShown == nil)
            hubShown = palette
            update { $0.lastResult = Engine.stamp("Heard music -> \(msg)"); $0.palette = palette; $0.source = "listener" }
        } else {
            claim(prio, now)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private func update(_ change: (inout Status) -> Void) {
        shared.locked { change(&_status) }
        DispatchQueue.main.async { self.onChange?() }
    }

    static let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    static func stamp(_ s: String) -> String { "\(timeFmt.string(from: Date()))  \(s)" }
}
