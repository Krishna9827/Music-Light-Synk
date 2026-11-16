import Foundation

/// Per-strip effect options (same as the Android app's WledEffect).
public struct WledEffect: Equatable {
    public var energy = false
    public var flip = false
    public var mirror = false

    public init(energy: Bool = false, flip: Bool = false, mirror: Bool = false) {
        self.energy = energy
        self.flip = flip
        self.mirror = mirror
    }
}

/// The Energy effect, like LedFx's: bars for lows / mids / highs grow along the strip.
/// Direction follows LedFx's flip / mirror switches (see EnergyEffect.kt).
public enum EnergyEffect {
    static let edge = 0.06

    public static func axis(_ i: Int, _ n: Int, flip: Bool, mirror: Bool) -> Double {
        var x = (Double(i) + 0.5) / Double(n)
        if mirror { x = abs(2 * x - 1) }
        if flip { x = 1 - x }
        return min(1, max(0, x))
    }

    static func coverage(_ p: Double, _ level: Double) -> Double { min(1, max(0, (level - p) / edge)) }

    /// [base]/[bands] are packed 0xRRGGBB; bands nil = bars in the base colours.
    public static func render(base: [Int], bands: [Int]?, levels: AudioLevels, effect: WledEffect, background: Double, blur: Int) -> [Int] {
        let n = base.count
        var r = [Double](repeating: 0, count: n), g = r, b = r
        let bg = min(1, max(0, background))
        for i in 0..<n {
            let p = axis(i, n, flip: effect.flip, mirror: effect.mirror)
            let cl = coverage(p, levels.lows), cm = coverage(p, levels.mids), ch = coverage(p, levels.highs)
            var fr: Double, fg: Double, fb: Double
            if let bands {
                fr = max(cl * red(bands[0]), cm * red(bands[1]), ch * red(bands[2]))
                fg = max(cl * green(bands[0]), cm * green(bands[1]), ch * green(bands[2]))
                fb = max(cl * blue(bands[0]), cm * blue(bands[1]), ch * blue(bands[2]))
            } else {
                let k = max(cl, cm * 0.75, ch * 0.5)
                fr = k * red(base[i]); fg = k * green(base[i]); fb = k * blue(base[i])
            }
            r[i] = max(fr, bg * red(base[i]))
            g[i] = max(fg, bg * green(base[i]))
            b[i] = max(fb, bg * blue(base[i]))
        }
        for _ in 0..<min(10, max(0, blur)) { smooth(&r); smooth(&g); smooth(&b) }
        return (0..<n).map { pack(r[$0], g[$0], b[$0]) }
    }

    /// Lows = the strip's colour, mids = vibrant (or dominant), highs = the most different edge colour.
    public static func bandColours(_ palette: Palette, primary: Slot, scale: (LightColor) -> Rgb) -> [Int] {
        let lows = scale(palette[primary])
        var mids = scale(palette[.vibrant])
        if mids.distance(to: lows) < 40 { mids = scale(palette[.dominant]) }
        let edges: [Slot] = [.topLeft, .top, .topRight, .left, .right, .bottomLeft, .bottom, .bottomRight, .average]
        let highs = edges.map { scale(palette[$0]) }.max { min($0.distance(to: lows), $0.distance(to: mids)) < min($1.distance(to: lows), $1.distance(to: mids)) } ?? lows
        return [lows.packed, mids.packed, highs.packed]
    }

    static func smooth(_ a: inout [Double]) {
        if a.count < 3 { return }
        var prev = a[0]
        for i in 1..<(a.count - 1) {
            let cur = a[i]
            a[i] = (prev + 2 * cur + a[i + 1]) / 4
            prev = cur
        }
    }

    static func red(_ c: Int) -> Double { Double((c >> 16) & 0xFF) }
    static func green(_ c: Int) -> Double { Double((c >> 8) & 0xFF) }
    static func blue(_ c: Int) -> Double { Double(c & 0xFF) }

    static func pack(_ r: Double, _ g: Double, _ b: Double) -> Int {
        func c(_ v: Double) -> Int { min(255, max(0, Int(v.rounded()))) }
        return (c(r) << 16) | (c(g) << 8) | c(b)
    }
}
