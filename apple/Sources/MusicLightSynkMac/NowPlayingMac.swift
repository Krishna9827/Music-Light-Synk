import AppKit
import Foundation
import MusicLightSynkCore

/// What's playing on the Mac. Main thread.
///
/// - Spotify and Apple Music announce every play / pause / track change system-wide (distributed
///   notifications, no permission needed): that's the fast path and works even if AppleScript
///   doesn't. AppleScript (every 2 s, only for apps that are running) adds the cover and the
///   state at start-up. Covers it can't give come from Spotify's oEmbed / Apple's iTunes search.
/// - Web players (YouTube Music, YouTube, Spotify and Apple Music on the web) in Safari or a
///   Chrome-family browser, through the page's media session ("Allow JavaScript from Apple Events").
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

    static let spotifyId = "com.spotify.client"
    static let musicId = "com.apple.Music"

    /// Tabs worth asking (URL contains one of these).
    static let webUrls = ["music.youtube.com", "youtube.com/watch", "open.spotify.com", "music.apple.com"]

    /// Reads the page's media session. Returns "1{json}" while it plays, "0{json}" when paused, ""
    /// for nothing. Single quotes only: it sits inside an AppleScript string.
    static let js = "(function(){var s=navigator.mediaSession;var m=s&&s.metadata;if(!m){return '';}" +
        "var p=s.playbackState==='playing';var e=document.querySelectorAll('video,audio');" +
        "for(var i=0;i<e.length;i++){if(!e[i].paused&&!e[i].muted&&e[i].currentTime>0){p=true;}}" +
        "var a=(m.artwork&&m.artwork.length)?m.artwork[m.artwork.length-1].src:'';" +
        "return (p?'1':'0')+JSON.stringify({t:m.title,a:m.artist,al:m.album,art:a,h:location.hostname});})()"

    private let settings: AppSettings
    private let onChange: (NowPlaying?) -> Void
    private let onNote: (String?) -> Void
    private var timer: Timer?
    private var observers: [NSObjectProtocol] = []
    private var last: NowPlaying?
    /// From the system-wide announcements, per app.
    private var announced: [String: NowPlaying] = [:]
    private var spotifyTrackId: String?
    /// Covers found online, by track key (nil value: looked, none found).
    private var coverUrls: [String: String?] = [:]
    private var musicArt: (key: String, data: Data?)?
    private var errors: [String: String] = [:]

    init(settings: AppSettings, onChange: @escaping (NowPlaying?) -> Void, onNote: @escaping (String?) -> Void) {
        self.settings = settings
        self.onChange = onChange
        self.onNote = onNote
    }

    func start() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.poll() }
        if observers.isEmpty {
            let dnc = DistributedNotificationCenter.default()
            observers.append(dnc.addObserver(forName: Notification.Name("com.spotify.client.PlaybackStateChanged"), object: nil, queue: .main) { [weak self] n in
                self?.announcement(NowPlayingMac.spotifyId, "Spotify", n.userInfo)
            })
            observers.append(dnc.addObserver(forName: Notification.Name("com.apple.Music.playerInfo"), object: nil, queue: .main) { [weak self] n in
                self?.announcement(NowPlayingMac.musicId, "Music", n.userInfo)
            })
        }
        poll()
    }

    private func running(_ bundleId: String) -> Bool {
        NSWorkspace.shared.runningApplications.contains { $0.bundleIdentifier == bundleId }
    }

    // ---- system-wide announcements ---------------------------------------------------------------

    private func announcement(_ pkg: String, _ app: String, _ info: [AnyHashable: Any]?) {
        guard let info else { return }
        let state = (info["Player State"] as? String ?? "").lowercased()
        if state == "stopped" || state.isEmpty {
            announced[pkg] = nil
        } else {
            announced[pkg] = NowPlaying(app: app, package: pkg, title: info["Name"] as? String, artist: info["Artist"] as? String,
                                        album: info["Album"] as? String, playing: state == "playing")
            if pkg == NowPlayingMac.spotifyId { spotifyTrackId = info["Track ID"] as? String }
        }
        poll()
    }

    // ---- AppleScript -----------------------------------------------------------------------------

    private struct ScriptResult {
        var value: NSAppleEventDescriptor?
        var error: String?
        var code: Int?
    }

    private func run(_ source: String) -> ScriptResult {
        var err: NSDictionary?
        guard let script = NSAppleScript(source: source) else { return ScriptResult(value: nil, error: "can't compile", code: nil) }
        let r = script.executeAndReturnError(&err)
        if let err {
            return ScriptResult(value: nil, error: err[NSAppleScript.errorMessage] as? String ?? "AppleScript error",
                                code: err[NSAppleScript.errorNumber] as? Int)
        }
        return ScriptResult(value: r, error: nil, code: nil)
    }

    private func lines(_ r: ScriptResult) -> [String]? {
        guard let s = r.value?.stringValue, !s.isEmpty else { return nil }
        return s.components(separatedBy: "\n")
    }

    private func note(_ app: String, _ r: ScriptResult) {
        guard let e = r.error else { errors[app] = nil; return }
        if r.code == -1743 {
            errors[app] = "\(app): allow Music Light Synk under System Settings → Privacy & Security → Automation"
        } else if e.localizedCaseInsensitiveContains("JavaScript") {
            errors[app] = "\(app): turn on \"Allow JavaScript from Apple Events\" (Safari: Develop menu; Chrome / Edge / Brave: View → Developer)"
        } else {
            errors[app] = "\(app): \(e) (\(r.code.map { String($0) } ?? "?"))"
        }
    }

    /// Text of a property, "" when missing (ads, local files, radio...).
    private static func prop(_ expr: String, _ name: String) -> String {
        """
            set \(name) to ""
            try
                set \(name) to (\(expr)) as text
            end try
            if \(name) is missing value then set \(name) to ""
        """
    }

    private func spotify() -> NowPlaying? {
        // Compare the player state with the app's own constants: turning it into text gives a raw
        // code ("kPSP") instead of "playing" on some systems.
        let src = """
        tell application "Spotify"
            set ps to player state
            if ps is playing then
                set st to "playing"
            else if ps is paused then
                set st to "paused"
            else
                return "stopped"
            end if
            set t to current track
        \(NowPlayingMac.prop("name of t", "n"))
        \(NowPlayingMac.prop("artist of t", "ar"))
        \(NowPlayingMac.prop("album of t", "al"))
        \(NowPlayingMac.prop("artwork url of t", "u"))
        \(NowPlayingMac.prop("id of t", "i"))
            return st & linefeed & n & linefeed & ar & linefeed & al & linefeed & u & linefeed & i
        end tell
        """
        let r = run(src)
        note("Spotify", r)
        if let l = lines(r) {
            if l.count < 6 { return nil } // stopped
            spotifyTrackId = l[5].isEmpty ? spotifyTrackId : l[5]
            var np = NowPlaying(app: "Spotify", package: NowPlayingMac.spotifyId, title: l[1], artist: l[2], album: l[3], playing: l[0] == "playing")
            np.artworkURL = l[4].isEmpty ? cover(for: np) : l[4]
            return np
        }
        guard var np = announced[NowPlayingMac.spotifyId] else { return nil }
        np.artworkURL = cover(for: np)
        return np
    }

    private func appleMusic() -> NowPlaying? {
        let src = """
        tell application "Music"
            set ps to player state
            if ps is stopped then return "stopped"
            if ps is paused then
                set st to "paused"
            else
                set st to "playing"
            end if
            set t to current track
        \(NowPlayingMac.prop("name of t", "n"))
        \(NowPlayingMac.prop("artist of t", "ar"))
        \(NowPlayingMac.prop("album of t", "al"))
            return st & linefeed & n & linefeed & ar & linefeed & al
        end tell
        """
        let r = run(src)
        note("Apple Music", r)
        var np: NowPlaying
        if let l = lines(r) {
            if l.count < 4 { return nil } // stopped
            np = NowPlaying(app: "Music", package: NowPlayingMac.musicId, title: l[1], artist: l[2], album: l[3], playing: l[0] == "playing")
        } else if let a = announced[NowPlayingMac.musicId] {
            np = a
        } else {
            return nil
        }
        let key = np.trackKey
        if musicArt?.key != key {
            // The cover itself, for songs in the library (streamed ones often have none here).
            let art = r.error == nil ? run("tell application \"Music\" to get raw data of artwork 1 of current track").value?.data : nil
            musicArt = (key, art)
        }
        np.artworkData = musicArt?.data
        if np.artworkData == nil { np.artworkURL = cover(for: np) }
        return np
    }

    private func webPlayer(_ b: Browser) -> NowPlaying? {
        let exec = b.safari ? "do JavaScript \"\(NowPlayingMac.js)\" in t" : "execute t javascript \"\(NowPlayingMac.js)\""
        let match = NowPlayingMac.webUrls.map { "u contains \"\($0)\"" }.joined(separator: " or ")
        let src = """
        tell application "\(b.name)"
            set hit to ""
            set lastErr to ""
            repeat with w in windows
                repeat with t in tabs of w
                    set u to ""
                    try
                        set u to URL of t
                    end try
                    if u is missing value then set u to ""
                    if \(match) then
                        set r to ""
                        try
                            set r to \(exec)
                        on error msg
                            set lastErr to msg
                        end try
                        if r is missing value then set r to ""
                        if r starts with "1" then return r
                        if hit is "" then set hit to r
                    end if
                end repeat
            end repeat
            if hit is "" and lastErr is not "" then error lastErr
            return hit
        end tell
        """
        let r = run(src)
        note(b.name, r)
        guard var s = r.value?.stringValue, s.count > 1 else { return nil }
        let playing = s.removeFirst() == "1"
        guard let data = s.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let host = o["h"] as? String ?? ""
        let app: String
        if host.contains("music.youtube") { app = "YouTube Music" }
        else if host.contains("spotify") { app = "Spotify (web)" }
        else if host.contains("music.apple") { app = "Apple Music (web)" }
        else { app = "YouTube" }
        let art = o["art"] as? String
        return NowPlaying(app: app, package: host.isEmpty ? "web" : host, title: o["t"] as? String, artist: o["a"] as? String,
                          album: o["al"] as? String, playing: playing, artworkURL: (art?.isEmpty ?? true) ? nil : art)
    }

    // ---- covers found online ---------------------------------------------------------------------

    /// A cover URL for a track whose app gave none: looked up once per track, in the background;
    /// the track is reported again when it arrives.
    private func cover(for np: NowPlaying) -> String? {
        let key = np.trackKey
        if let known = coverUrls[key] { return known }
        coverUrls[key] = .some(nil)
        if coverUrls.count > 200 { coverUrls.removeAll() ; coverUrls[key] = .some(nil) }
        let spotifyId = np.package == NowPlayingMac.spotifyId ? spotifyTrackId : nil
        let artist = np.artist ?? "", album = np.album ?? "", title = np.title ?? ""
        DispatchQueue.global().async {
            var url: String?
            if let id = spotifyId { url = NowPlayingMac.spotifyCover(id) }
            if url == nil { url = NowPlayingMac.itunesCover(artist: artist, album: album, title: title) }
            guard let url else { return }
            DispatchQueue.main.async {
                self.coverUrls[key] = .some(url)
                self.last = nil // report the track again, now with its cover
                self.poll()
            }
        }
        return nil
    }

    /// Spotify's public oEmbed: "spotify:track:ID" -> the cover (no account needed). Blocking.
    static func spotifyCover(_ uri: String) -> String? {
        let p = uri.split(separator: ":")
        guard p.count == 3, p[0] == "spotify", p[1] != "local",
              let page = "https://open.spotify.com/\(p[1])/\(p[2])".addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let o = Http.getJson("https://open.spotify.com/oembed?url=\(page)", timeout: 4),
              let thumb = o["thumbnail_url"] as? String, thumb.hasPrefix("http") else { return nil }
        return thumb
    }

    /// Apple's public iTunes search: the album, else the song. Blocking.
    static func itunesCover(artist: String, album: String, title: String) -> String? {
        func search(_ entity: String, _ term: String) -> String? {
            guard !term.trimmingCharacters(in: .whitespaces).isEmpty,
                  let q = term.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
                  let o = Http.getJson("https://itunes.apple.com/search?entity=\(entity)&limit=1&term=\(q)", timeout: 4),
                  let first = (o["results"] as? [[String: Any]])?.first, let art = first["artworkUrl100"] as? String else { return nil }
            return art.replacingOccurrences(of: "100x100", with: "600x600")
        }
        if !album.isEmpty, let u = search("album", "\(artist) \(album)") { return u }
        if !title.isEmpty { return search("song", "\(artist) \(title)") }
        return nil
    }

    // ---- polling ---------------------------------------------------------------------------------

    private func poll() {
        var found: [NowPlaying] = []
        var problems: [String] = []
        if settings.useSpotify, running(NowPlayingMac.spotifyId) {
            if let np = spotify() { found.append(np) } else if let e = errors["Spotify"] { problems.append(e) }
        } else {
            announced[NowPlayingMac.spotifyId] = nil
        }
        if settings.useMusic, running(NowPlayingMac.musicId) {
            if let np = appleMusic() { found.append(np) } else if let e = errors["Apple Music"] { problems.append(e) }
        } else {
            announced[NowPlayingMac.musicId] = nil
        }
        if settings.useBrowsers {
            for b in NowPlayingMac.browsers where running(b.bundleId) {
                if let np = webPlayer(b) { found.append(np) } else if let e = errors[b.name] { problems.append(e) }
            }
        }
        // Prefer what's playing; keep the current source while it plays.
        let playing = found.filter { $0.playing }
        let choice = playing.first { $0.package == last?.package } ?? playing.first ?? found.first { $0.package == last?.package } ?? found.first
        if choice != last {
            last = choice
            onChange(choice)
        }
        // Problems matter only while they keep us from seeing something.
        onNote(choice?.playing == true || problems.isEmpty ? nil : problems.joined(separator: "\n"))
    }
}
