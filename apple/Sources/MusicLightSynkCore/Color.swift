import Foundation

// Colour extraction, ported from the Android app's ColorExtractor.kt (same algorithm, same
// numbers): a 4-bit histogram "smoothed mode" per corner / side, bulb-friendly colours at full
// value with darkness moved into brightness. Pure Swift, unit tested.

public struct Rgb: Equatable, Hashable {
    public var r: Int
    public var g: Int
    public var b: Int

    public init(_ r: Int, _ g: Int, _ b: Int) {
        self.r = r
        self.g = g
        self.b = b
    }

    public init(argb: UInt32) {
        self.init(Int((argb >> 16) & 0xFF), Int((argb >> 8) & 0xFF), Int(argb & 0xFF))
    }

    public var hex: String { String(format: "#%02X%02X%02X", r, g, b) }

    /// 0xRRGGBB
    public var packed: Int { (r << 16) | (g << 8) | b }

    public func distance(to o: Rgb) -> Int {
        let dr = r - o.r, dg = g - o.g, db = b - o.b
        return Int((Double(dr * dr + dg * dg + db * db)).squareRoot().rounded())
    }

    public var luminance: Double { (0.2126 * Double(r) + 0.7152 * Double(g) + 0.0722 * Double(b)) / 255 }

    public static func parseHex(_ s: String) -> Rgb? {
        var h = s.trimmingCharacters(in: .whitespaces)
        if h.hasPrefix("#") { h.removeFirst() }
        guard h.count == 6, let v = Int(h, radix: 16) else { return nil }
        return Rgb((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)
    }
}

/// One colour ready for a smart bulb: [light] is the hue at full value (with a saturation boost),
/// [brightness] carries the darkness of the [raw] pixel colour.
public struct LightColor: Equatable {
    public var raw: Rgb
    public var light: Rgb
    /// 0..360 (Home Assistant hs_color[0])
    public var hue: Double
    /// 0..100 (Home Assistant hs_color[1])
    public var saturation: Double
    /// 1..255
    public var brightness: Int

    public var brightnessPct: Int { min(100, max(1, Int((Double(brightness) * 100 / 255).rounded()))) }
}

public enum Slot: String, CaseIterable {
    case topLeft = "top_left"
    case top
    case topRight = "top_right"
    case left
    case right
    case bottomLeft = "bottom_left"
    case bottom
    case bottomRight = "bottom_right"
    case dominant
    case vibrant
    case average

    public var key: String { rawValue }

    public var label: String {
        switch self {
        case .topLeft: return "Top left"
        case .top: return "Top"
        case .topRight: return "Top right"
        case .left: return "Left"
        case .right: return "Right"
        case .bottomLeft: return "Bottom left"
        case .bottom: return "Bottom"
        case .bottomRight: return "Bottom right"
        case .dominant: return "Dominant"
        case .vibrant: return "Vibrant"
        case .average: return "Average"
        }
    }

    public static func of(_ key: String) -> Slot? { Slot(rawValue: key.lowercased()) }
}

public struct Palette {
    public let colors: [Slot: LightColor]

    public init(_ colors: [Slot: LightColor]) { self.colors = colors }

    public subscript(slot: Slot) -> LightColor { colors[slot]! }

    /// Largest raw-colour distance between matching slots; Int.max if [other] is nil.
    public func maxDistance(to other: Palette?) -> Int {
        guard let other else { return Int.max }
        return Slot.allCases.map { self[$0].raw.distance(to: other[$0].raw) }.max() ?? 0
    }

    public var isBlank: Bool {
        Slot.allCases.allSatisfy { s in let c = self[s].raw; return max(c.r, c.g, c.b) < 10 }
    }

    public var rawColors: [Slot: Rgb] { colors.mapValues { $0.raw } }
}

public struct ExtractOptions: Equatable {
    public var cornerFraction: Double = 0.25
    public var saturationBoost: Double = 1.3
    public var minBrightness: Double = 0.15

    public init(cornerFraction: Double = 0.25, saturationBoost: Double = 1.3, minBrightness: Double = 0.15) {
        self.cornerFraction = cornerFraction
        self.saturationBoost = saturationBoost
        self.minBrightness = minBrightness
    }
}

public enum ColorExtractor {
    static let greyChroma = 0.07

    /// [pixels]: 0xAARRGGBB, row by row (small image, ~64 px on the long side).
    public static func extract(pixels: [UInt32], width: Int, height: Int, options opts: ExtractOptions = ExtractOptions()) -> Palette {
        precondition(width > 0 && height > 0 && pixels.count >= width * height)
        let f = min(0.5, max(0.05, opts.cornerFraction))
        let cw = max(1, Int((Double(width) * f).rounded()))
        let ch = max(1, Int((Double(height) * f).rounded()))
        var sx0 = cw, sx1 = width - cw
        if sx1 <= sx0 { sx0 = 0; sx1 = width }
        var sy0 = ch, sy1 = height - ch
        if sy1 <= sy0 { sy0 = 0; sy1 = height }

        func h(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Histogram {
            Histogram.of(pixels, stride: width, x0, y0, x1, y1)
        }
        let all = h(0, 0, width, height)
        let dominant = all.dominant()
        var map: [Slot: LightColor] = [:]
        map[.topLeft] = toLight(h(0, 0, cw, ch).dominant(), opts)
        map[.top] = toLight(h(sx0, 0, sx1, ch).dominant(), opts)
        map[.topRight] = toLight(h(width - cw, 0, width, ch).dominant(), opts)
        map[.left] = toLight(h(0, sy0, cw, sy1).dominant(), opts)
        map[.right] = toLight(h(width - cw, sy0, width, sy1).dominant(), opts)
        map[.bottomLeft] = toLight(h(0, height - ch, cw, height).dominant(), opts)
        map[.bottom] = toLight(h(sx0, height - ch, sx1, height).dominant(), opts)
        map[.bottomRight] = toLight(h(width - cw, height - ch, width, height).dominant(), opts)
        map[.dominant] = toLight(dominant, opts)
        map[.vibrant] = toLight(all.vibrant() ?? dominant, opts)
        map[.average] = toLight(all.average(), opts)
        return Palette(map)
    }

    /// The rainbow test image (same as the Android app): each corner / side its own colour, purple middle.
    public static func testPalette(_ opts: ExtractOptions = ExtractOptions()) -> Palette {
        let n = 40
        let band = min(n / 2, max(1, Int((Double(n) * min(0.5, max(0.05, opts.cornerFraction))).rounded())))
        var px = [UInt32](repeating: 0, count: n * n)
        for y in 0..<n {
            for x in 0..<n {
                let l = x < band, r = x >= n - band, t = y < band, b = y >= n - band
                let c: UInt32
                if t && l { c = 0xFFE53935 } else if t && r { c = 0xFFFDD835 }
                else if b && l { c = 0xFF3949AB } else if b && r { c = 0xFF00ACC1 }
                else if t { c = 0xFFFB8C00 } else if b { c = 0xFF1E88E5 }
                else if l { c = 0xFFD81B60 } else if r { c = 0xFF43A047 }
                else { c = 0xFF8E24AA }
                px[y * n + x] = c
            }
        }
        return extract(pixels: px, width: n, height: n, options: opts)
    }

    public static func toLight(_ raw: Rgb, _ opts: ExtractOptions) -> LightColor {
        let hsv = rgbToHsv(raw)
        let chroma = Double(max(raw.r, raw.g, raw.b) - min(raw.r, raw.g, raw.b)) / 255
        let sat = chroma < greyChroma ? 0 : min(1, hsv.s * min(3, max(0.5, opts.saturationBoost)))
        let hue = sat == 0 ? 0 : hsv.h
        let light = hsvToRgb(hue, sat, 1)
        let minB = min(1, max(0, opts.minBrightness))
        let bri = min(255, max(1, Int(((minB + (1 - minB) * hsv.v) * 255).rounded())))
        return LightColor(raw: raw, light: light, hue: hue, saturation: sat * 100, brightness: bri)
    }

    public static func rgbToHsv(_ c: Rgb) -> (h: Double, s: Double, v: Double) {
        let r = Double(c.r) / 255, g = Double(c.g) / 255, b = Double(c.b) / 255
        let mx = max(r, g, b), mn = min(r, g, b), d = mx - mn
        var h: Double
        if d == 0 { h = 0 }
        else if mx == r { h = 60 * ((g - b) / d).truncatingRemainder(dividingBy: 6) }
        else if mx == g { h = 60 * ((b - r) / d + 2) }
        else { h = 60 * ((r - g) / d + 4) }
        if h < 0 { h += 360 }
        return (h, mx == 0 ? 0 : d / mx, mx)
    }

    public static func hsvToRgb(_ h: Double, _ s: Double, _ v: Double) -> Rgb {
        let hh = (h.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        let c = v * s
        let x = c * (1 - abs((hh / 60).truncatingRemainder(dividingBy: 2) - 1))
        let m = v - c
        let rgb: (Double, Double, Double)
        if hh < 60 { rgb = (c, x, 0) }
        else if hh < 120 { rgb = (x, c, 0) }
        else if hh < 180 { rgb = (0, c, x) }
        else if hh < 240 { rgb = (0, x, c) }
        else if hh < 300 { rgb = (x, 0, c) }
        else { rgb = (c, 0, x) }
        func chn(_ f: Double) -> Int { min(255, max(0, Int(((f + m) * 255).rounded()))) }
        return Rgb(chn(rgb.0), chn(rgb.1), chn(rgb.2))
    }

    /// 4-bit-per-channel histogram of one rectangle with a "smoothed mode" (see the Kotlin original).
    final class Histogram {
        static let buckets = 4096
        var count = [Int](repeating: 0, count: buckets)
        var sumR = [Int](repeating: 0, count: buckets)
        var sumG = [Int](repeating: 0, count: buckets)
        var sumB = [Int](repeating: 0, count: buckets)
        var occupied: [Int] = []
        var total = 0
        var allR = 0, allG = 0, allB = 0

        func add(_ argb: UInt32) {
            let r = Int((argb >> 16) & 0xFF), g = Int((argb >> 8) & 0xFF), b = Int(argb & 0xFF)
            let k = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4)
            if count[k] == 0 { occupied.append(k) }
            count[k] += 1
            sumR[k] += r; sumG[k] += g; sumB[k] += b
            total += 1
            allR += r; allG += g; allB += b
        }

        func average() -> Rgb {
            total == 0 ? Rgb(0, 0, 0) : Rgb(allR / total, allG / total, allB / total)
        }

        private func neighbours(_ k: Int, _ fn: (Int) -> Void) {
            let r = (k >> 8) & 0xF, g = (k >> 4) & 0xF, b = k & 0xF
            for dr in -1...1 {
                let rr = r + dr
                if rr < 0 || rr > 15 { continue }
                for dg in -1...1 {
                    let gg = g + dg
                    if gg < 0 || gg > 15 { continue }
                    for db in -1...1 {
                        let bb = b + db
                        if bb < 0 || bb > 15 { continue }
                        fn((rr << 8) | (gg << 4) | bb)
                    }
                }
            }
        }

        private func neighbourhoodCount(_ k: Int) -> Int {
            var n = 0
            neighbours(k) { n += count[$0] }
            return n
        }

        private func neighbourhoodMean(_ k: Int) -> Rgb {
            var n = 0, r = 0, g = 0, b = 0
            neighbours(k) { i in n += count[i]; r += sumR[i]; g += sumG[i]; b += sumB[i] }
            return n == 0 ? Rgb(0, 0, 0) : Rgb(r / n, g / n, b / n)
        }

        func dominant() -> Rgb {
            if total == 0 { return Rgb(0, 0, 0) }
            var best = -1, bestN = -1
            for k in occupied {
                let n = neighbourhoodCount(k)
                if n > bestN { bestN = n; best = k }
            }
            return neighbourhoodMean(best)
        }

        func vibrant() -> Rgb? {
            if total == 0 { return nil }
            let minPixels = max(2, Int((Double(total) * 0.03).rounded()))
            var best: Rgb?
            var bestScore = 0.0
            for k in occupied {
                if neighbourhoodCount(k) < minPixels { continue }
                let c = neighbourhoodMean(k)
                let hsv = ColorExtractor.rgbToHsv(c)
                let chroma = Double(max(c.r, c.g, c.b) - min(c.r, c.g, c.b)) / 255
                if chroma < 0.15 || hsv.v < 0.2 { continue }
                let score = hsv.s * hsv.v
                if score > bestScore { bestScore = score; best = c }
            }
            return best
        }

        static func of(_ px: [UInt32], stride: Int, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Histogram {
            let opaque = Histogram()
            for y in y0..<y1 {
                let row = y * stride
                for x in x0..<x1 where (px[row + x] >> 24) >= 0x80 { opaque.add(px[row + x]) }
            }
            if opaque.total > 0 { return opaque }
            let any = Histogram()
            for y in y0..<y1 {
                let row = y * stride
                for x in x0..<x1 { any.add(px[row + x]) }
            }
            return any
        }
    }
}
