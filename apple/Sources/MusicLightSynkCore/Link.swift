import Foundation

// Device link, compatible with the Android app (Link.kt, protocol v2): JSON over UDP 21330,
// "hi" broadcast every few seconds, everything else unicast to the devices found.

public enum LinkProtocol {
    public static let port: UInt16 = 21330
    static let group = "home"
    static let version = 2

    public enum Msg: Equatable {
        /// [mic]: its microphone is on and others may use it; [src]: whose microphone it listens to.
        case hi(from: String, name: String, platform: String, room: String, listening: Bool, mic: Bool, src: String)
        case pal(from: String, colors: [Slot: Rgb], playing: Bool, title: String?)
        case nrg(from: String, levels: AudioLevels)
        /// [hosts] nil = an older app that didn't say (treated as every WLED).
        case claim(from: String, prio: Int, playingForMs: Int64, hosts: Set<String>?)

        public var from: String {
            switch self {
            case let .hi(f, _, _, _, _, _, _), let .pal(f, _, _, _), let .nrg(f, _), let .claim(f, _, _, _): return f
            }
        }
    }

    public static func encode(_ m: Msg) -> [UInt8] {
        var o: [String: Any] = ["v": version, "g": group, "id": m.from]
        switch m {
        case let .hi(_, name, platform, room, listening, mic, src):
            o["t"] = "hi"; o["name"] = name; o["plat"] = platform; o["room"] = room
            o["listen"] = listening; o["mic"] = mic; o["src"] = src
        case let .pal(_, colors, playing, title):
            o["t"] = "pal"
            var cols: [String: Any] = [:]
            for (s, c) in colors { cols[s.key] = c.hex }
            o["pal"] = cols
            o["playing"] = playing
            if let title { o["title"] = String(title.prefix(120)) }
        case let .nrg(_, l):
            o["t"] = "nrg"; o["l"] = r3(l.lows); o["m"] = r3(l.mids); o["h"] = r3(l.highs); o["b"] = l.beat
        case let .claim(_, prio, age, hosts):
            o["t"] = "claim"; o["prio"] = prio; o["age"] = age
            if let hosts { o["hosts"] = hosts.sorted() }
        }
        let data = (try? JSONSerialization.data(withJSONObject: o)) ?? Data()
        return [UInt8](data)
    }

    public static func decode(_ bytes: [UInt8]) -> Msg? {
        guard let o = (try? JSONSerialization.jsonObject(with: Data(bytes))) as? [String: Any],
              o["g"] as? String == group, let id = o["id"] as? String, !id.isEmpty,
              (o["v"] as? Int ?? 0) >= 1 else { return nil }
        func s(_ k: String) -> String { o[k] as? String ?? "" }
        func b(_ k: String) -> Bool { o[k] as? Bool ?? false }
        func f(_ k: String) -> Double { min(1, max(0, (o[k] as? NSNumber)?.doubleValue ?? 0)) }
        switch s("t") {
        case "hi":
            return .hi(from: id, name: o["name"] as? String ?? id, platform: s("plat"), room: s("room"), listening: b("listen"), mic: b("mic"), src: s("src"))
        case "pal":
            guard let cols = o["pal"] as? [String: Any] else { return nil }
            var map: [Slot: Rgb] = [:]
            for slot in Slot.allCases { if let h = cols[slot.key] as? String, let c = Rgb.parseHex(h) { map[slot] = c } }
            guard map.count == Slot.allCases.count else { return nil }
            let title = s("title")
            return .pal(from: id, colors: map, playing: b("playing"), title: title.isEmpty ? nil : title)
        case "nrg":
            return .nrg(from: id, levels: AudioLevels(f("l"), f("m"), f("h"), beat: b("b")))
        case "claim":
            guard let prio = (o["prio"] as? NSNumber)?.intValue else { return nil }
            let hosts = (o["hosts"] as? [String]).map { Set($0.map(normHost)) }
            return .claim(from: id, prio: prio, playingForMs: (o["age"] as? NSNumber)?.int64Value ?? 0, hosts: hosts)
        default:
            return nil
        }
    }

    public static func normHost(_ h: String) -> String {
        var s = h.trimmingCharacters(in: .whitespaces).lowercased()
        if s.hasPrefix("http://") { s.removeFirst(7) }
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix(":80") { s.removeLast(3) }
        return s
    }

    public static func sameRoom(_ a: String, _ b: String) -> Bool { roomKey(a) == roomKey(b) }

    static func roomKey(_ r: String) -> String {
        let k = r.lowercased().replacingOccurrences(of: " ", with: "")
        return k.isEmpty ? "home" : k
    }

    public static func palette(_ colors: [Slot: Rgb], _ opts: ExtractOptions) -> Palette {
        Palette(colors.mapValues { ColorExtractor.toLight($0, opts) })
    }

    static func r3(_ v: Double) -> Double { (min(1, max(0, v)) * 1000).rounded() / 1000 }
}

/// Which device drives each WLED (same rules as the Android app's LightHub): per host, the
/// highest priority wins; between equals the one that started playing last; then the lower id.
public final class LightHub {
    public static let prioPlaying = 3
    public static let prioListening = 1
    static let sameStartMs: Int64 = 1500

    struct Claim {
        var prio: Int
        var playingForMs: Int64
        var hosts: Set<String>?
        var atMs: Int64
        func covers(_ h: String) -> Bool { hosts == nil || hosts!.contains(h) }
    }

    private let selfId: String
    private let ttlMs: Int64
    private var claims: [String: Claim] = [:]
    private let lock = NSLock()

    public init(selfId: String, ttlMs: Int64 = 2500) {
        self.selfId = selfId
        self.ttlMs = ttlMs
    }

    public func onClaim(id: String, prio: Int, playingForMs: Int64, hosts: Set<String>?, nowMs: Int64) {
        lock.lock(); defer { lock.unlock() }
        if id == selfId { return }
        if prio <= 0 || hosts?.isEmpty == true { claims.removeValue(forKey: id) }
        else { claims[id] = Claim(prio: prio, playingForMs: playingForMs, hosts: hosts, atMs: nowMs) }
    }

    public func allowedHosts(prio: Int, playingForMs: Int64, mine: Set<String>, nowMs: Int64) -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        if prio <= 0 || mine.isEmpty { return [] }
        expire(nowMs)
        var taken = Set<String>()
        for (id, c) in claims where beats(c.prio, c.playingForMs + (nowMs - c.atMs), id, prio, playingForMs) {
            guard let h = c.hosts else { return [] }
            taken.formUnion(h)
        }
        return mine.subtracting(taken)
    }

    public func claimedByOthers(_ hosts: Set<String>, nowMs: Int64) -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        expire(nowMs)
        return hosts.filter { h in claims.values.contains { $0.covers(h) } }
    }

    public func othersOn(_ hosts: Set<String>, nowMs: Int64) -> [String] {
        lock.lock(); defer { lock.unlock() }
        expire(nowMs)
        return claims.filter { (_, c) in hosts.contains { c.covers($0) } }
            .sorted { a, b in a.value.prio != b.value.prio ? a.value.prio > b.value.prio : (a.value.playingForMs != b.value.playingForMs ? a.value.playingForMs < b.value.playingForMs : a.key < b.key) }
            .map { $0.key }
    }

    private func expire(_ nowMs: Int64) { claims = claims.filter { nowMs - $0.value.atMs <= ttlMs } }

    private func beats(_ prio: Int, _ age: Int64, _ id: String, _ myPrio: Int, _ myAge: Int64) -> Bool {
        if prio != myPrio { return prio > myPrio }
        if abs(age - myAge) > LightHub.sameStartMs { return age < myAge }
        return id < selfId
    }
}

/// The UDP socket of the link: receives on [port], broadcasts "hi", unicasts the rest.
public final class Link {
    /// A copy of what we know about another device (safe to keep and read anywhere).
    public struct Peer {
        public let id: String
        public var address: sockaddr_in
        public var name: String
        public var platform = ""
        public var room: String?
        public var mic = false
        public var src = ""
        public var listening = false
        public var lastSeenMs: Int64 = 0
        init(id: String, address: sockaddr_in) {
            self.id = id
            self.address = address
            name = id
        }
    }

    public static let peerTtlMs: Int64 = 10_000
    private let selfId: String
    private let port: UInt16
    private let onMessage: (LinkProtocol.Msg) -> Void
    private var socket: UdpSocket?
    private var peers: [String: Peer] = [:]
    private let lock = NSLock()
    private var running = false
    /// Where "hi" goes (tests point it at localhost); nil = every broadcast address.
    public var broadcastTargets: [sockaddr_in]?

    public init(selfId: String, port: UInt16 = LinkProtocol.port, onMessage: @escaping (LinkProtocol.Msg) -> Void) {
        self.selfId = selfId
        self.port = port
        self.onMessage = onMessage
    }

    public var isRunning: Bool { running }

    public func start() -> Bool {
        if running { return true }
        guard let s = UdpSocket(bindPort: port, broadcast: true) else { return false }
        socket = s
        running = true
        Thread.detachNewThread { [weak self] in self?.receiveLoop(s) }
        return true
    }

    public func stop() {
        running = false
        socket = nil
        lock.lock(); peers.removeAll(); lock.unlock()
    }

    public func peers(nowMs: Int64 = monotonicMs()) -> [Peer] {
        lock.lock(); defer { lock.unlock() }
        return peers.values.filter { nowMs - $0.lastSeenMs < Link.peerTtlMs }
    }

    public func peer(_ id: String) -> Peer? {
        lock.lock(); defer { lock.unlock() }
        return peers[id]
    }

    public func broadcast(_ m: LinkProtocol.Msg) {
        guard let s = socket else { return }
        let bytes = LinkProtocol.encode(m)
        for t in broadcastTargets ?? UdpSocket.broadcastAddresses(port: port) { s.send(bytes, to: t) }
    }

    public func sendToPeers(_ m: LinkProtocol.Msg, where filter: (Peer) -> Bool = { _ in true }) {
        guard let s = socket else { return }
        let list = peers().filter(filter)
        if list.isEmpty { return }
        let bytes = LinkProtocol.encode(m)
        for p in list { s.send(bytes, to: p.address) }
    }

    private func receiveLoop(_ s: UdpSocket) {
        while running && socket === s {
            guard let r = s.receive(), let m = LinkProtocol.decode(r.bytes), m.from != selfId else { continue }
            lock.lock()
            var p = peers[m.from] ?? Peer(id: m.from, address: r.from)
            p.address = r.from
            p.lastSeenMs = monotonicMs()
            if case let .hi(_, name, platform, room, listening, mic, src) = m {
                p.name = name; p.platform = platform; p.room = room; p.listening = listening; p.mic = mic; p.src = src
            }
            peers[m.from] = p
            lock.unlock()
            onMessage(m)
        }
    }
}
