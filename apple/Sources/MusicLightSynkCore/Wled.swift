import Foundation

// WLED output, ported from the Android app's Wled.kt: the device list format
// ("host=zone[:options]; ..."), the strip layout around a TV, DNRGB realtime packets, the JSON
// API bodies, a realtime renderer (fades, beat pulse, Energy bars) and the controller that decides
// what each WLED gets.

public enum Corner: String, CaseIterable {
    case topLeft = "top_left", topRight = "top_right", bottomRight = "bottom_right", bottomLeft = "bottom_left"

    var index: Int { Corner.allCases.firstIndex(of: self)! }
}

/// How a strip runs around the TV: LED counts per side (may be 0), where LED 0 is, direction.
public struct WledLayout: Equatable {
    public var top: Int, right: Int, bottom: Int, left: Int
    public var start: Corner
    public var clockwise: Bool

    public var total: Int { top + right + bottom + left }

    func count(_ side: Int) -> Int { [top, right, bottom, left][side] }

    /// Colour for every LED, interpolating corner -> side middle -> next corner.
    public func render(_ colorOf: (Slot) -> Rgb) -> [Int] {
        let corners = [Slot.topLeft, .topRight, .bottomRight, .bottomLeft].map(colorOf)
        let mids = [Slot.top, .right, .bottom, .left].map(colorOf)
        var out: [Int] = []
        out.reserveCapacity(total)
        let s = start.index
        for step in 0..<4 {
            let side = clockwise ? (s + step) % 4 : (s - 1 - step + 8) % 4
            let n = count(side)
            for k in 0..<n {
                let f = (Double(k) + 0.5) / Double(n)
                let u = clockwise ? f : 1 - f
                out.append(u < 0.5
                    ? WledLayout.lerp(corners[side], mids[side], u / 0.5)
                    : WledLayout.lerp(mids[side], corners[(side + 1) % 4], (u - 0.5) / 0.5))
            }
        }
        return out
    }

    public static func lerp(_ a: Rgb, _ b: Rgb, _ t: Double) -> Int {
        let tt = min(1, max(0, t))
        func ch(_ x: Int, _ y: Int) -> Int { Int((Double(x) + Double(y - x) * tt).rounded()) }
        return (ch(a.r, b.r) << 16) | (ch(a.g, b.g) << 8) | ch(a.b, b.b)
    }

    public static func parse(_ raw: String, start: Corner, clockwise: Bool) -> WledLayout? {
        let parts = raw.split(whereSeparator: { ",; x/".contains($0) }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let n = parts.compactMap { Int($0) }
        guard n.count == 4, parts.count == 4, n.allSatisfy({ $0 >= 0 }), n.reduce(0, +) > 0, n.reduce(0, +) <= 4000 else { return nil }
        return WledLayout(top: n[0], right: n[1], bottom: n[2], left: n[3], start: start, clockwise: clockwise)
    }
}

public enum WledZone: Equatable {
    case section(Slot, keepEffect: Bool)
    case palette(slots: [Slot], fx: Int, pal: Int)
    case ambient(WledLayout)

    public static let defaultSlots: [Slot] = [.dominant, .vibrant, .average]

    public var key: String {
        switch self {
        case let .section(slot, _): return slot.key
        case .palette: return "palette"
        case .ambient: return "ambient"
        }
    }

    public var label: String {
        switch self {
        case let .section(slot, keep): return slot.label + (keep ? " (keeps effect)" : "")
        case let .palette(slots, _, _): return "Effect colours: " + slots.map { $0.label.lowercased() }.joined(separator: ", ")
        case let .ambient(l): return "Ambient gradient (\(l.top),\(l.right),\(l.bottom),\(l.left) LEDs)"
        }
    }
}

public struct WledDevice: Equatable {
    public var host: String
    public var zone: WledZone
    public var effect: WledEffect
    public var leds: Int

    public init(host: String, zone: WledZone, effect: WledEffect = WledEffect(), leds: Int = 0) {
        self.host = host
        self.zone = zone
        self.effect = effect
        self.leds = leds
    }

    /// Driven over UDP realtime rather than the JSON API.
    public var realtime: Bool {
        switch zone {
        case .ambient: return true
        case .section: return effect.energy
        case .palette: return false
        }
    }
}

public enum WledDevices {
    public static let defaultLayout = WledLayout(top: 40, right: 22, bottom: 40, left: 22, start: .bottomLeft, clockwise: true)

    public struct Parsed {
        public var devices: [WledDevice]
        public var errors: [String]
    }

    public static func parse(_ raw: String) -> Parsed {
        var devices: [WledDevice] = []
        var errors: [String] = []
        let entries = raw.split(whereSeparator: { $0 == ";" || $0 == "\n" }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        for entry in entries {
            guard let eq = entry.firstIndex(of: "="), eq != entry.startIndex else {
                errors.append("'\(entry)': expected host=zone")
                continue
            }
            var host = String(entry[..<eq]).trimmingCharacters(in: .whitespaces)
            if host.hasPrefix("http://") { host.removeFirst(7) }
            while host.hasSuffix("/") { host.removeLast() }
            let parts = entry[entry.index(after: eq)...].split(separator: ":").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            guard !host.isEmpty, let zone = parts.first?.lowercased() else {
                errors.append("'\(entry)': expected host=zone")
                continue
            }
            var effect = WledEffect()
            var leds = 0
            var badLeds = false
            var opts: [String] = []
            for o in parts.dropFirst() {
                switch o.lowercased() {
                case "energy": effect.energy = true
                case "flip": effect.flip = true
                case "mirror": effect.mirror = true
                default:
                    if o.lowercased().hasPrefix("leds=") {
                        if let n = Int(o.dropFirst(5)) { leds = min(4000, max(0, n)) } else { badLeds = true }
                    } else {
                        opts.append(o)
                    }
                }
            }
            if badLeds {
                errors.append("'\(entry)': leds= needs a number")
                continue
            }
            let parsed: WledZone?
            switch zone {
            case "ambient", "gradient": parsed = parseAmbient(opts)
            case "palette", "colors", "colours", "effect": parsed = parsePalette(opts)
            default: parsed = Slot.of(zone).map { WledZone.section($0, keepEffect: opts.contains { $0.lowercased() == "keep" }) }
            }
            guard let z = parsed else {
                errors.append("'\(entry)': unknown zone or bad options")
                continue
            }
            if case .palette = z {
                devices.append(WledDevice(host: host, zone: z))
            } else {
                devices.append(WledDevice(host: host, zone: z, effect: effect, leds: leds))
            }
        }
        return Parsed(devices: devices, errors: errors)
    }

    public static func format(_ devices: [WledDevice]) -> String {
        devices.map { "\($0.host)=\(formatDevice($0))" }.joined(separator: "; ")
    }

    public static func formatDevice(_ d: WledDevice) -> String {
        var s = formatZone(d.zone)
        if case .palette = d.zone { return s }
        if d.effect.energy { s += ":energy" }
        if d.effect.flip { s += ":flip" }
        if d.effect.mirror { s += ":mirror" }
        if d.leds > 0 { s += ":leds=\(d.leds)" }
        return s
    }

    public static func formatZone(_ zone: WledZone) -> String {
        switch zone {
        case let .section(slot, keep): return slot.key + (keep ? ":keep" : "")
        case let .palette(slots, fx, pal):
            var s = "palette"
            if slots != WledZone.defaultSlots { s += ":" + slots.map { $0.key }.joined(separator: ",") }
            if fx >= 0 { s += ":fx=\(fx)" }
            if pal >= 0 { s += ":pal=\(pal)" }
            return s
        case let .ambient(l):
            return "ambient:\(l.top),\(l.right),\(l.bottom),\(l.left):\(l.start.rawValue):\(l.clockwise ? "cw" : "ccw")"
        }
    }

    static func parseAmbient(_ opts: [String]) -> WledZone? {
        var counts: String?
        var start = Corner.bottomLeft
        var cw = true
        // Forgiving about how people type it: "40, 22, 40, 22", "bottom left", "bottom-left", "bl".
        let short: [String: Corner] = ["tl": .topLeft, "tr": .topRight, "br": .bottomRight, "bl": .bottomLeft]
        for o in opts {
            let lo = o.lowercased().trimmingCharacters(in: .whitespaces)
            let flat = lo.replacingOccurrences(of: " ", with: "")
            let corner = lo.replacingOccurrences(of: " ", with: "_").replacingOccurrences(of: "-", with: "_")
            if flat.range(of: #"^\d+([,x;/]\d+){3}$"#, options: .regularExpression) != nil { counts = flat }
            else if ["cw", "clockwise"].contains(flat) { cw = true }
            else if ["ccw", "counterclockwise", "counter-clockwise", "anticlockwise", "anti-clockwise"].contains(flat) { cw = false }
            else if let c = Corner(rawValue: corner) ?? short[flat] { start = c }
            else { return nil }
        }
        guard let c = counts, let layout = WledLayout.parse(c, start: start, clockwise: cw) else { return nil }
        return .ambient(layout)
    }

    static func parsePalette(_ opts: [String]) -> WledZone? {
        var slots = WledZone.defaultSlots
        var fx = -1, pal = -1
        for o in opts {
            let lo = o.lowercased()
            if lo.hasPrefix("fx=") {
                guard let v = Int(lo.dropFirst(3)) else { return nil }
                fx = min(255, max(0, v))
            } else if lo.hasPrefix("pal=") {
                guard let v = Int(lo.dropFirst(4)) else { return nil }
                pal = min(255, max(0, v))
            } else {
                let list = o.split(separator: ",").map { Slot.of($0.trimmingCharacters(in: .whitespaces)) }
                guard !list.isEmpty, list.count <= 3, !list.contains(where: { $0 == nil }) else { return nil }
                slots = list.compactMap { $0 }
            }
        }
        return .palette(slots: slots, fx: fx, pal: pal)
    }
}

public enum WledPackets {
    public static let udpPort: UInt16 = 21324
    static let maxLedsPerPacket = 489

    /// DNRGB realtime packets: [4, timeout s, start hi, start lo, r, g, b, ...].
    public static func dnrgb(_ frame: [Int], timeoutSec: Int) -> [[UInt8]] {
        var packets: [[UInt8]] = []
        var start = 0
        while start < frame.count {
            let n = min(maxLedsPerPacket, frame.count - start)
            var p = [UInt8](repeating: 0, count: 4 + n * 3)
            p[0] = 4
            p[1] = UInt8(min(255, max(1, timeoutSec)))
            p[2] = UInt8((start >> 8) & 0xFF)
            p[3] = UInt8(start & 0xFF)
            for i in 0..<n {
                let c = frame[start + i]
                p[4 + i * 3] = UInt8((c >> 16) & 0xFF)
                p[5 + i * 3] = UInt8((c >> 8) & 0xFF)
                p[6 + i * 3] = UInt8(c & 0xFF)
            }
            packets.append(p)
            start += n
        }
        return packets
    }
}

/// WLED JSON API bodies (http://<host>/json/state).
public enum WledJson {
    public static func colorsBody(_ cols: [Rgb], bri: Int, fx: Int, pal: Int) -> [String: Any] {
        var seg: [String: Any] = ["col": cols.map { [$0.r, $0.g, $0.b] }]
        if fx >= 0 { seg["fx"] = fx }
        if pal >= 0 { seg["pal"] = pal }
        return ["on": true, "bri": min(255, max(1, bri)), "seg": seg]
    }

    /// The saved state minus preset / playlist pointers.
    public static func restoreBody(_ saved: [String: Any]) -> [String: Any] {
        var b = saved
        for k in ["ps", "pl", "nl", "udpn", "lor"] { b.removeValue(forKey: k) }
        return b
    }

    public static func bodyFor(_ zone: WledZone, palette: Palette, master: Double) -> [String: Any]? {
        switch zone {
        case let .section(slot, keep):
            let c = palette[slot]
            return colorsBody([c.light], bri: Int((Double(c.brightness) * master).rounded()), fx: keep ? -1 : 0, pal: -1)
        case let .palette(slots, fx, pal):
            let bri = Int((Double(palette[slots[0]].brightness) * master).rounded())
            return colorsBody(slots.map { palette[$0].light }, bri: bri, fx: fx, pal: pal)
        case .ambient:
            return nil
        }
    }
}
