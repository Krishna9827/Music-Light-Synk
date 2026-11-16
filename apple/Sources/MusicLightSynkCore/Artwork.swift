import Foundation
import CoreGraphics
import ImageIO

/// Album art -> palette: decode (PNG / JPEG / TIFF / ...), shrink to ~64 px, extract.
public enum Artwork {
    static let side = 64

    /// 0xAARRGGBB pixels of the image scaled to fit [side] x [side], or nil if it can't be read.
    public static func pixels(_ data: Data) -> (pixels: [UInt32], width: Int, height: Int)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return nil }
        return pixels(img)
    }

    public static func pixels(_ img: CGImage) -> (pixels: [UInt32], width: Int, height: Int)? {
        let scale = Double(side) / Double(max(img.width, img.height))
        let w = max(1, Int((Double(img.width) * min(1, scale)).rounded()))
        let h = max(1, Int((Double(img.height) * min(1, scale)).rounded()))
        var raw = [UInt8](repeating: 0, count: w * h * 4)
        let ok: Bool = raw.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        if !ok { return nil }
        var out = [UInt32](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let r = UInt32(raw[i * 4]), g = UInt32(raw[i * 4 + 1]), b = UInt32(raw[i * 4 + 2]), a = UInt32(raw[i * 4 + 3])
            out[i] = (a << 24) | (r << 16) | (g << 8) | b
        }
        return (out, w, h)
    }

    public static func palette(_ data: Data, _ opts: ExtractOptions) -> Palette? {
        guard let p = pixels(data) else { return nil }
        return ColorExtractor.extract(pixels: p.pixels, width: p.width, height: p.height, options: opts)
    }

    /// Download (blocking; background queues only).
    public static func download(_ url: String) -> Data? {
        guard url.hasPrefix("http://") || url.hasPrefix("https://") else { return nil }
        let r = Http.request(url, timeout: 6)
        guard r.ok, let d = r.body, d.count < 10_000_000 else { return nil }
        return d
    }
}
