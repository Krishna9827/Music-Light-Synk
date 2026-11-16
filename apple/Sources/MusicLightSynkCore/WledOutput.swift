import Foundation
#if canImport(Darwin)
import Darwin
#endif

public struct WledConfig {
    public var devices: [WledDevice]
    public var brightness: Double
    public var restore: Bool
    public var pulse: Bool
    public var pulseStrength: Double
    public var energyBackground: Double
    public var energyBlur: Int

    public init(devices: [WledDevice], brightness: Double = 1, restore: Bool = true, pulse: Bool = false,
                pulseStrength: Double = 0.6, energyBackground: Double = 0.1, energyBlur: Int = 2) {
        self.devices = devices
        self.brightness = brightness
        self.restore = restore
        self.pulse = pulse
        self.pulseStrength = pulseStrength
        self.energyBackground = energyBackground
        self.energyBlur = energyBlur
    }
}

/// Realtime (UDP DNRGB) renderer: fades between tracks, beat pulse for plain gradients, Energy
/// bars at ~40 fps with fresh audio, a keep-alive frame every second otherwise (WLED leaves
/// realtime mode 2 s after the last packet). Thread-safe.
public final class WledRenderer {
    public struct Strip {
        public var base: [Int]
        public var effect: WledEffect
        public var bands: [Int]?
        public init(base: [Int], effect: WledEffect = WledEffect(), bands: [Int]? = nil) {
            self.base = base
            self.effect = effect
            self.bands = bands
        }
    }

    public struct Style {
        public var pulse = false
        public var pulseStrength = 0.6
        public var energyBackground = 0.1
        public var energyBlur = 2
    }

    final class Target {
        var strip: Strip
        var shown: [Double]
        var from: [Double]
        init(_ s: Strip) {
            strip = s
            shown = [Double](repeating: 0, count: s.base.count * 3)
            from = shown
        }
    }

    static let fadeMs = 700.0
    static let frameMs = 25
    static let keepAliveMs: Int64 = 1000
    static let audioStaleMs: Int64 = 2000

    private let q = DispatchQueue(label: "mls.wled.render")
    private let port: UInt16
    private let sock = UdpSocket()
    private var addrs: [String: sockaddr_in] = [:]
    private var targets: [String: Target] = [:]
    private var style = Style()
    private var fadeStart: Int64 = 0
    private var envelope = 0.0
    private var lastTick: Int64 = 0
    private var lastSend: Int64 = 0
    private var shownLevels = [0.0, 0.0, 0.0]
    private var timer: DispatchSourceTimer?
    private var stopItem: DispatchWorkItem?
    private let levelLock = NSLock()
    private var levels = AudioLevels.silent
    private var levelsAt: Int64 = .min / 2
    public private(set) var framesSent = 0

    public init(port: UInt16 = WledPackets.udpPort) { self.port = port }

    public var running: Bool { q.sync { !targets.isEmpty } }

    /// Show these strips (host -> strip), fading from what each shows now; others are released.
    public func show(_ strips: [String: Strip], style: Style, durationMs: Int = 0) {
        q.async { [self] in
            stopItem?.cancel()
            for host in targets.keys where strips[host] == nil { release(host) }
            for (host, strip) in strips {
                if let t = targets[host], t.shown.count == strip.base.count * 3 {
                    t.from = t.shown
                    t.strip = strip
                } else {
                    targets[host] = Target(strip)
                }
            }
            self.style = style
            fadeStart = monotonicMs()
            if targets.isEmpty { stopTimer() } else { startTimer() }
            if durationMs > 0 {
                let item = DispatchWorkItem { [weak self] in self?.stopNow() }
                stopItem = item
                q.asyncAfter(deadline: .now() + .milliseconds(durationMs), execute: item)
            }
        }
    }

    public func audio(_ l: AudioLevels) {
        levelLock.lock()
        levels = l
        levelsAt = monotonicMs()
        levelLock.unlock()
        if l.beat { q.async { self.envelope = 1 } }
    }

    public func stop() { q.async { self.stopNow() } }

    private func stopNow() {
        for host in Array(targets.keys) { release(host) }
        stopTimer()
        envelope = 0
        shownLevels = [0, 0, 0]
    }

    private func release(_ host: String) {
        guard let t = targets.removeValue(forKey: host) else { return }
        send(host, frameOf(t, 1), timeout: 1) // WLED reverts ~1 s later
    }

    private func startTimer() {
        if timer != nil { return }
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now(), repeating: .milliseconds(WledRenderer.frameMs))
        t.setEventHandler { [weak self] in self?.tick() }
        timer = t
        t.resume()
    }

    private func stopTimer() {
        timer?.cancel()
        timer = nil
    }

    private func tick() {
        let now = monotonicMs()
        let dt = lastTick == 0 ? 25.0 : Double(min(1000, max(1, now - lastTick)))
        lastTick = now
        envelope *= exp(-dt / 160)
        let fade = min(1, max(0, Double(now - fadeStart) / WledRenderer.fadeMs))
        let eased = fade * fade * (3 - 2 * fade)
        levelLock.lock()
        let l = levels, age = now - levelsAt
        levelLock.unlock()
        let bars: AudioLevels? = age <= WledRenderer.audioStaleMs ? smoothLevels(l, age: age) : nil
        let anyEnergy = bars != nil && targets.values.contains { $0.strip.effect.energy }
        let animating = fade < 1 || anyEnergy || (style.pulse && targets.values.contains { !$0.strip.effect.energy })
        if !animating && now - lastSend < WledRenderer.keepAliveMs { return }
        lastSend = now
        let factor = style.pulse ? (1 - style.pulseStrength) + style.pulseStrength * envelope : 1
        for (host, t) in targets {
            for i in 0..<t.strip.base.count {
                let c = t.strip.base[i]
                for ch in 0..<3 {
                    let v = Double((c >> (16 - ch * 8)) & 0xFF)
                    let k = i * 3 + ch
                    t.shown[k] = t.from[k] + (v - t.from[k]) * eased
                }
            }
            let frame: [Int]
            if t.strip.effect.energy, let b = bars {
                frame = EnergyEffect.render(base: frameOf(t, 1), bands: t.strip.bands, levels: b, effect: t.strip.effect,
                                            background: style.energyBackground, blur: style.energyBlur)
            } else {
                frame = frameOf(t, t.strip.effect.energy ? 1 : factor)
            }
            send(host, frame, timeout: 2)
        }
    }

    private func smoothLevels(_ l: AudioLevels, age: Int64) -> AudioLevels {
        let fall = age > 150 ? exp(-Double(age - 150) / 200) : 1
        let target = [l.lows * fall, l.mids * fall, l.highs * fall]
        for b in 0..<3 { shownLevels[b] += (target[b] - shownLevels[b]) * 0.6 }
        return AudioLevels(shownLevels[0], shownLevels[1], shownLevels[2])
    }

    private func frameOf(_ t: Target, _ factor: Double) -> [Int] {
        (0..<t.strip.base.count).map { i in
            func c(_ k: Int) -> Int { min(255, max(0, Int((t.shown[i * 3 + k] * factor).rounded()))) }
            return (c(0) << 16) | (c(1) << 8) | c(2)
        }
    }

    private func send(_ host: String, _ frame: [Int], timeout: Int) {
        guard let sock else { return }
        if addrs[host] == nil { addrs[host] = UdpSocket.resolve(host, port: port) }
        guard let a = addrs[host] else { return }
        var ok = true
        for p in WledPackets.dnrgb(frame, timeoutSec: timeout) where !sock.send(p, to: a) { ok = false }
        if ok { framesSent += 1 } else { addrs.removeValue(forKey: host) }
    }
}

/// Decides what each WLED gets: realtime strips (gradient / Energy) via the renderer, the rest via
/// the JSON API with the WLED's own state saved first and put back when the music stops.
public final class WledController {
    public static let previewMs = 6000
    public let renderer: WledRenderer
    private var saved: [String: [String: Any]] = [:]
    private var ledCounts: [String: Int] = [:]
    private let lock = NSLock()
    private let pool = DispatchQueue(label: "mls.wled.json", attributes: .concurrent)

    public init(port: UInt16 = WledPackets.udpPort) { renderer = WledRenderer(port: port) }

    /// Blocking (JSON calls run in parallel, a few seconds at most). Returns a status line.
    @discardableResult
    public func onColors(_ cfg: WledConfig, _ palette: Palette, saveState: Bool, preview: Bool = false) -> String {
        var strips: [String: WledRenderer.Strip] = [:]
        var json: [WledDevice] = []
        var notes: [String] = []
        func scale(_ c: LightColor) -> Rgb { WledController.scaled(c, cfg.brightness) }
        for d in cfg.devices {
            switch d.zone {
            case let .ambient(layout):
                strips[d.host] = WledRenderer.Strip(base: layout.render { scale(palette[$0]) }, effect: d.effect)
            case let .section(slot, _):
                if d.realtime, let n = ledCount(d) {
                    strips[d.host] = WledRenderer.Strip(base: [Int](repeating: scale(palette[slot]).packed, count: n), effect: d.effect,
                                                        bands: EnergyEffect.bandColours(palette, primary: slot, scale: scale))
                } else {
                    if d.realtime { notes.append("\(d.host) LED count unknown, solid colour") }
                    json.append(d)
                }
            case .palette:
                json.append(d)
            }
        }
        renderer.show(strips, style: WledRenderer.Style(pulse: cfg.pulse && !preview, pulseStrength: cfg.pulseStrength,
                                                        energyBackground: cfg.energyBackground, energyBlur: cfg.energyBlur),
                      durationMs: preview ? WledController.previewMs : 0)
        if !json.isEmpty && cfg.restore && (saveState || preview) {
            let toSave = json.filter { d in lock.locked { saved[d.host] == nil } }
            parallel(toSave) { d in
                if let st = Http.getJson("http://\(d.host)/json/state") { self.lock.locked { self.saved[d.host] = st } }
            }
        }
        var parts: [String] = strips.isEmpty ? [] : ["realtime x\(strips.count)"]
        var results = [String](repeating: "timed out", count: json.count)
        let rl = NSLock()
        parallel(Array(json.enumerated())) { pair in
            let (i, d) = pair
            guard let body = WledJson.bodyFor(d.zone, palette: palette, master: cfg.brightness) else { return }
            let r = Http.request("http://\(d.host)/json/state", method: "POST", json: body, timeout: 3)
            rl.locked { results[i] = r.message }
        }
        for (i, d) in json.enumerated() { parts.append("\(d.host) \(results[i])") }
        parts += notes
        if preview && !json.isEmpty {
            pool.asyncAfter(deadline: .now() + .milliseconds(WledController.previewMs)) { _ = self.restoreAll(skip: []) }
        }
        return "WLED " + (parts.isEmpty ? "no devices" : parts.joined(separator: ", "))
    }

    public func audio(_ l: AudioLevels) { renderer.audio(l) }

    /// Music stopped: let go and put JSON devices back, except [othersDrive] (normalised hosts).
    @discardableResult
    public func onStopped(_ cfg: WledConfig, othersDrive: Set<String> = []) -> String {
        renderer.stop()
        if cfg.restore { return restoreAll(skip: othersDrive) }
        lock.locked { saved.removeAll() }
        return "WLED released"
    }

    public func yield() { renderer.stop() }

    private func restoreAll(skip: Set<String>) -> String {
        let states = lock.locked { () -> [String: [String: Any]] in let s = saved; saved.removeAll(); return s }
        if states.isEmpty { return "WLED nothing to restore" }
        let list = states.filter { !skip.contains(LinkProtocol.normHost($0.key)) }.map { ($0.key, $0.value) }
        parallel(list) { pair in
            let (host, state) = pair
            _ = Http.request("http://\(host)/json/state", method: "POST", json: WledJson.restoreBody(state), timeout: 3)
            if let pl = state["pl"] as? Int, pl >= 0 { _ = Http.request("http://\(host)/json/state", method: "POST", json: ["ps": pl], timeout: 3) }
        }
        return "WLED restored \(list.count)"
    }

    private func ledCount(_ d: WledDevice) -> Int? {
        if d.leds > 0 { return d.leds }
        if let n = lock.locked({ ledCounts[d.host] }) { return n }
        guard let info = Http.getJson("http://\(d.host)/json/info"), let leds = info["leds"] as? [String: Any],
              let n = (leds["count"] as? NSNumber)?.intValue, (1...4000).contains(n) else { return nil }
        lock.locked { ledCounts[d.host] = n }
        return n
    }

    private func parallel<T>(_ items: [T], _ fn: @escaping (T) -> Void) {
        let g = DispatchGroup()
        for item in items { pool.async(group: g) { fn(item) } }
        _ = g.wait(timeout: .now() + 7)
    }

    public static func scaled(_ c: LightColor, _ master: Double) -> Rgb {
        let f = Double(c.brightness) / 255 * master
        func ch(_ v: Int) -> Int { min(255, max(0, Int((Double(v) * f).rounded()))) }
        return Rgb(ch(c.light.r), ch(c.light.g), ch(c.light.b))
    }
}

extension NSLock {
    func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
