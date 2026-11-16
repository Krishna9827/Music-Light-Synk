import Foundation

/// What a music app is playing right now.
public struct NowPlaying: Equatable {
    public var app: String
    /// Bundle id / identifier of the source, e.g. com.spotify.client
    public var package: String
    public var title: String?
    public var artist: String?
    public var album: String?
    public var playing: Bool
    /// http(s) URL of the cover, if the app gives one.
    public var artworkURL: String?
    /// The cover image itself (PNG / JPEG / TIFF data), if the app gives that instead.
    public var artworkData: Data?

    public init(app: String, package: String, title: String?, artist: String?, album: String?, playing: Bool,
                artworkURL: String? = nil, artworkData: Data? = nil) {
        self.app = app
        self.package = package
        self.title = title
        self.artist = artist
        self.album = album
        self.playing = playing
        self.artworkURL = artworkURL
        self.artworkData = artworkData
    }

    public var trackKey: String { "\(package)|\(title ?? "")|\(artist ?? "")|\(album ?? "")" }

    /// Changes when the cover changes (used to cache the palette).
    public var artworkKey: String? {
        if let u = artworkURL { return u }
        if let d = artworkData { return "\(trackKey)#\(d.count)" }
        return nil
    }

    public func describe() -> String {
        let track = [title, artist].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " - ")
        return "\(app): \(track.isEmpty ? "(no track info)" : track) (\(playing ? "playing" : "paused"))"
    }
}

/// JSON bodies for Home Assistant's webhook, the same schema as the Android app (README "Payload").
public enum Payloads {
    public static func colors(event: String, reason: String, sessionStart: Bool, source: String,
                              np: NowPlaying?, palette: Palette, device: String) -> [String: Any] {
        var o = base(event, np, device)
        o["reason"] = reason
        o["session_start"] = sessionStart
        o["source"] = source
        var cols: [String: Any] = [:]
        for s in Slot.allCases { cols[s.key] = color(palette[s]) }
        o["colors"] = cols
        return o
    }

    public static func stopped(_ np: NowPlaying?, device: String) -> [String: Any] {
        var o = base("stopped", np, device)
        o["playing"] = false
        return o
    }

    public static func status(_ np: NowPlaying?, device: String, status: [String: Any]) -> [String: Any] {
        var o = base("status", np, device)
        o["status"] = status
        return o
    }

    public static func color(_ c: LightColor) -> [String: Any] {
        [
            "hex": c.light.hex,
            "rgb": [c.light.r, c.light.g, c.light.b],
            "hs": [round1(c.hue), round1(c.saturation)],
            "brightness": c.brightness,
            "brightness_pct": c.brightnessPct,
            "raw_hex": c.raw.hex,
            "raw_rgb": [c.raw.r, c.raw.g, c.raw.b],
        ]
    }

    static func base(_ event: String, _ np: NowPlaying?, _ device: String) -> [String: Any] {
        [
            "event": event,
            "playing": np?.playing ?? false,
            "app": np?.app as Any? ?? NSNull(),
            "package": np?.package as Any? ?? NSNull(),
            "title": np?.title as Any? ?? NSNull(),
            "artist": np?.artist as Any? ?? NSNull(),
            "album": np?.album as Any? ?? NSNull(),
            "device": device,
            "timestamp": Int(Date().timeIntervalSince1970),
        ]
    }

    static func round1(_ v: Double) -> Double { (v * 10).rounded() / 10 }
}
