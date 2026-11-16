import AppKit
import Foundation
import MusicLightSynkCore

/// What's playing on the Mac, asked with AppleScript every 2 s (only apps that are running are
/// asked, so nothing gets launched): Spotify, Apple Music, and YouTube Music in Safari or a
/// Chrome-family browser (the browser needs "Allow JavaScript from Apple Events").
/// macOS asks once per app for permission to control it.
final class NowPlayingMac {
    struct Browser {
        let bundleId: String
        let name: String
        let safari: Bool
    }

    static let browsers = [
        Browser(bundleId: "com.apple.Safari", name: "Safari", safari: true),
        Browser(bundleId: "com.google.Chrome", name: "Google Chrome", safari: false),
        Browser(bundleId: "com.microsoft.edgemac", name: "Microsoft Edge", safari: false),
        Browser(bundleId: "com.brave.Browser", name: "Brave Browser", safari: false),
    ]

    /// Reads navigator.mediaSession from the YouTube Music tab (single quotes only: it sits inside an AppleScript string).
    static let js = "(function(){var m=navigator.mediaSession&&navigator.mediaSession.metadata;if(!m){return '';}" +
        "var v=document.querySelector('video');var a=(m.artwork&&m.artwork.length)?m.artwork[m.artwork.length-1].src:'';" +
        "return JSON.stringify({t:m.title,a:m.artist,al:m.album,art:a,p:!!(v&&!v.paused)});})()"

    private let settings: AppSettings
    private let onChange: (NowPlaying?) -> Void
    private let onNote: (String?) -> Void
    private var timer: Timer?
    private var last: NowPlaying?
    private var lastArtTrack: String?
    private var lastArtData: Data?
    private var lastArtUrl: String?

    init(settings: AppSettings, onChange: @escaping (NowPlaying?) -> Void, onNote: @escaping (String?) -> Void) {
        self.settings = settings
        self.onChange = onChange
        self.onNote = onNote
    }

    func start() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.poll() }
        poll()
    }

    private func running(_ bundleId: String) -> Bool {
        NSWorkspace.shared.runningApplications.contains { $0.bundleIdentifier == bundleId }
    }

    private func run(_ source: String) -> NSAppleEventDescriptor? {
        var err: NSDictionary?
        let r = NSAppleScript(source: source)?.executeAndReturnError(&err)
        if let err, let msg = err[NSAppleScript.errorMessage] as? String {
            if msg.localizedCaseInsensitiveContains("JavaScript") {
                onNote("For YouTube Music, turn on \"Allow JavaScript from Apple Events\" in your browser (Safari: Develop menu; Chrome: View → Developer)")
            } else if (err[NSAppleScript.errorNumber] as? Int) == -1743 {
                onNote("Allow Music Light Synk to control your music apps: System Settings → Privacy & Security → Automation")
            }
            return nil
        }
        return r
    }

    private func poll() {
        var found: [NowPlaying] = []
        if settings.useSpotify, running("com.spotify.client"), let np = spotify() { found.append(np) }
        if settings.useMusic, running("com.apple.Music"), let np = appleMusic() { found.append(np) }
        if settings.useBrowsers {
            for b in NowPlayingMac.browsers where running(b.bundleId) {
                if let np = youTubeMusic(b) { found.append(np) }
            }
        }
        // Prefer what's playing; keep the current app while it plays.
        let playing = found.filter { $0.playing }
        let choice = playing.first { $0.package == last?.package } ?? playing.first ?? found.first { $0.package == last?.package } ?? found.first
        if choice != last {
            last = choice
            onChange(choice)
        }
    }

    private func lines(_ d: NSAppleEventDescriptor?) -> [String]? {
        guard let s = d?.stringValue, !s.isEmpty else { return nil }
        return s.components(separatedBy: "\n")
    }

    private func spotify() -> NowPlaying? {
        let src = """
        tell application "Spotify"
            set st to player state as string
            if st is "stopped" then return st
            set t to current track
            return st & linefeed & (name of t) & linefeed & (artist of t) & linefeed & (album of t) & linefeed & (artwork url of t)
        end tell
        """
        guard let l = lines(run(src)), l.count >= 5 else { return nil }
        return NowPlaying(app: "Spotify", package: "com.spotify.client", title: l[1], artist: l[2], album: l[3],
                          playing: l[0] == "playing", artworkURL: l[4].isEmpty ? nil : l[4])
    }

    private func appleMusic() -> NowPlaying? {
        let src = """
        tell application "Music"
            set st to player state as string
            if st is "stopped" then return st
            set t to current track
            return st & linefeed & (name of t) & linefeed & (artist of t) & linefeed & (album of t)
        end tell
        """
        guard let l = lines(run(src)), l.count >= 4 else { return nil }
        var np = NowPlaying(app: "Music", package: "com.apple.Music", title: l[1], artist: l[2], album: l[3], playing: l[0] == "playing")
        let key = np.trackKey
        if key != lastArtTrack {
            lastArtTrack = key
            lastArtData = run("tell application \"Music\" to get raw data of artwork 1 of current track")?.data
            lastArtUrl = nil
            if lastArtData == nil {
                // Streamed songs often have no readable artwork: ask Apple's search, then report again.
                let artist = l[2], album = l[3]
                DispatchQueue.global().async {
                    let url = NowPlayingMac.itunesArtwork(artist: artist, album: album)
                    DispatchQueue.main.async {
                        guard self.lastArtTrack == key, let url else { return }
                        self.lastArtUrl = url
                        self.last = nil // report the track again, now with its cover
                        self.poll()
                    }
                }
            }
        }
        np.artworkData = lastArtData
        np.artworkURL = lastArtUrl
        return np
    }

    private func youTubeMusic(_ b: Browser) -> NowPlaying? {
        let exec = b.safari ? "do JavaScript \"\(NowPlayingMac.js)\" in t" : "execute t javascript \"\(NowPlayingMac.js)\""
        let src = """
        tell application "\(b.name)"
            repeat with w in windows
                repeat with t in tabs of w
                    if URL of t contains "music.youtube.com" then return \(exec)
                end repeat
            end repeat
        end tell
        return ""
        """
        guard let json = run(src)?.stringValue, let data = json.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let art = o["art"] as? String
        return NowPlaying(app: "YouTube Music", package: "music.youtube.com", title: o["t"] as? String, artist: o["a"] as? String,
                          album: o["al"] as? String, playing: o["p"] as? Bool ?? false, artworkURL: (art?.isEmpty ?? true) ? nil : art)
    }

    /// Apple's public iTunes search, for Apple Music streams whose artwork AppleScript can't read.
    /// Blocking: call off the main thread.
    static func itunesArtwork(artist: String, album: String) -> String? {
        guard !album.isEmpty, let term = "\(artist) \(album)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let o = Http.getJson("https://itunes.apple.com/search?entity=album&limit=1&term=\(term)", timeout: 4),
              let first = (o["results"] as? [[String: Any]])?.first, let art = first["artworkUrl100"] as? String else { return nil }
        return art.replacingOccurrences(of: "100x100", with: "600x600")
    }
}
