import AppKit
import SwiftUI
import MusicLightSynkCore

@main
struct MusicLightSynkApp: App {
    @StateObject private var model = AppModel.shared

    init() {
        DispatchQueue.main.async { AppModel.shared.start() }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuView().environmentObject(model)
        } label: {
            Image(systemName: model.status.hearing ? "waveform.circle.fill" : "waveform.circle")
        }
        .menuBarExtraStyle(.window)

        Window("Music Light Synk", id: "settings") {
            SettingsView().environmentObject(model)
        }
        .windowResizability(.contentSize)
    }
}

/// Glue between the engine, the microphone and the now-playing sources. Main thread.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let settings = AppSettings()
    let engine: Engine
    private var mic: MicInput!
    private var nowPlaying: NowPlayingMac!
    private var askingMic = false
    private var started = false

    @Published var status = Engine.Status()
    @Published var mode: DeviceMode
    @Published var micState = "Microphone off"
    @Published var sourceNote: String?

    init() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        let e = Engine(settings: settings, platform: "mac", appVersion: version, defaultName: Host.current().localizedName ?? "Mac")
        engine = e
        mode = settings.mode
        mic = MicInput { [weak e] levels, silent in e?.levels(levels, silent: silent) }
        nowPlaying = NowPlayingMac(settings: settings, onChange: { [weak e] np in e?.nowPlaying(np) },
                                   onNote: { [weak self] note in self?.sourceNote = note })
    }

    func start() {
        guard !started else { return }
        started = true
        engine.onChange = { [weak self] in self?.refresh() }
        engine.start()
        nowPlaying.start()
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
        nowPlaying.start()
        syncMic()
    }

    /// The microphone runs when the mode uses it (asks for permission the first time).
    func syncMic() {
        mic.sensitivity = Double(settings.energySensitivityPct) / 100
        if engine.micWanted {
            guard !mic.running, !askingMic else { return }
            askingMic = true
            MicInput.requestPermission { [weak self] ok in
                guard let self else { return }
                self.askingMic = false
                if ok { self.mic.start() }
                self.micState = !ok ? "Microphone not allowed: System Settings → Privacy & Security → Microphone"
                    : (self.mic.running ? "Microphone on" : "Microphone: \(self.mic.lastError ?? "can't start")")
            }
        } else if mic.running {
            mic.stop()
            micState = "Microphone off"
        }
    }
}

extension Rgb {
    var color: Color { Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255) }
}
