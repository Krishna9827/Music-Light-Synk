import MediaPlayer
import SwiftUI
import UIKit
import MusicLightSynkCore

@main
struct MusicLightSynkiOSApp: App {
    @StateObject private var model = PhoneModel()

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(model).onAppear { model.start() }
        }
    }
}

/// Glue between the engine, the microphone and Apple Music. Main thread.
final class PhoneModel: ObservableObject {
    let settings = AppSettings()
    let engine: Engine
    private var mic: MicInput!
    private var music: AppleMusicSource!
    private var started = false
    private var askingMic = false

    @Published var status = Engine.Status()
    @Published var mode: DeviceMode
    @Published var micState = "Microphone off"

    init() {
        if UserDefaults.standard.object(forKey: "link_enabled") == nil {
            // iPhones can't use the link's broadcasts without a special Apple permission; off by default.
            settings.linkEnabled = false
        }
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        let e = Engine(settings: settings, platform: "ios", appVersion: version, defaultName: UIDevice.current.name)
        engine = e
        mode = settings.mode
        mic = MicInput { [weak e] levels, silent in e?.levels(levels, silent: silent) }
        music = AppleMusicSource { [weak e] np in e?.nowPlaying(np) }
    }

    func start() {
        guard !started else { return }
        started = true
        engine.onChange = { [weak self] in self?.refresh() }
        engine.start()
        music.start()
        syncMic()
    }

    func refresh() {
        status = engine.status
        if mode != settings.mode { mode = settings.mode }
        syncMic()
    }

    func setMode(_ m: DeviceMode) {
        settings.mode = m
        mode = m
        engine.settingsChanged()
        syncMic()
    }

    func settingsSaved() {
        engine.settingsChanged()
        syncMic()
    }

    /// The microphone keeps the app running with the screen locked (audio background mode).
    func syncMic() {
        mic.sensitivity = Double(settings.energySensitivityPct) / 100
        if engine.micWanted {
            guard !mic.running, !askingMic else { return }
            askingMic = true
            MicInput.requestPermission { [weak self] ok in
                guard let self else { return }
                self.askingMic = false
                if ok { self.mic.start() }
                self.micState = !ok ? "Microphone not allowed: Settings → Music Light Synk → Microphone"
                    : (self.mic.running ? "Microphone on (keeps working with the screen locked)" : "Microphone: \(self.mic.lastError ?? "can't start")")
            }
        } else if mic.running {
            mic.stop()
            micState = "Microphone off"
        }
    }
}

/// What Apple Music plays (other apps don't share this on iPhone), asked every 2 s.
final class AppleMusicSource {
    private let player = MPMusicPlayerController.systemMusicPlayer
    private let onChange: (NowPlaying?) -> Void
    private var timer: Timer?
    private var last: NowPlaying?
    private var artKey: UInt64 = 0
    private var art: Data?

    init(onChange: @escaping (NowPlaying?) -> Void) { self.onChange = onChange }

    func start() {
        MPMediaLibrary.requestAuthorization { _ in }
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func poll() {
        guard MPMediaLibrary.authorizationStatus() == .authorized, let item = player.nowPlayingItem else {
            if last != nil { last = nil; onChange(nil) }
            return
        }
        if item.persistentID != artKey {
            artKey = item.persistentID
            art = item.artwork?.image(at: CGSize(width: 300, height: 300))?.pngData()
        }
        let np = NowPlaying(app: "Apple Music", package: "com.apple.Music", title: item.title, artist: item.artist,
                            album: item.albumTitle, playing: player.playbackState == .playing, artworkData: art)
        if np != last {
            last = np
            onChange(np)
        }
    }
}

extension Rgb {
    var color: Color { Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255) }
}
