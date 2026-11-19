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

/// Glue between the engine, the microphone, the screen / sound capture and the now-playing
/// sources. Main thread.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let settings = AppSettings()
    let engine: Engine
    private var mic: MicInput!
    private var nowPlaying: NowPlayingMac!
    private let capture = MacCapture()
    private var output: AudioAnalyzer!
    private var askingMic = false
    private var started = false
    @Published private(set) var captureNote: String?

    @Published var status = Engine.Status()
    @Published var mode: DeviceMode
    @Published var micState = "Microphone off"
    @Published var sourceNote: String?
    @Published var colourSource: String
    @Published var audioSource: String

    init() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
        let e = Engine(settings: settings, platform: "mac", appVersion: version, defaultName: Host.current().localizedName ?? "Mac")
        engine = e
        mode = settings.mode
        colourSource = settings.sourceMode
        audioSource = settings.audioSource
        mic = MicInput { [weak e] levels, silent in e?.levels(levels, silent: silent) }
        output = AudioAnalyzer { [weak e] levels, silent in e?.levels(levels, silent: silent) }
        let s = settings
        capture.onPixels = { [weak e] px, w, h in
            e?.screen(ColorExtractor.extract(pixels: px, width: w, height: h, options: s.extractOptions))
        }
        capture.onSamples = { [weak self] samples, rate in self?.output.feed(samples, rate: rate) }
        capture.onState = { [weak self] running, error in
            guard let self else { return }
            self.captureNote = error
            self.engine.screenCapture(running: running)
            self.syncAudio()
        }
        nowPlaying = NowPlayingMac(settings: settings, onChange: { [weak e] np in e?.nowPlaying(np) },
                                   onNote: { [weak self] note in self?.sourceNote = note })
    }

    func start() {
        guard !started else { return }
        started = true
        engine.onChange = { [weak self] in self?.refresh() }
        engine.start()
        nowPlaying.start()
        syncAudio()
    }

    func refresh() {
        status = engine.status
        if mode != settings.mode { mode = settings.mode }
        if colourSource != settings.sourceMode { colourSource = settings.sourceMode }
        if audioSource != settings.audioSource { audioSource = settings.audioSource }
        syncAudio()
    }

    func setMode(_ m: DeviceMode) {
        settings.mode = m
        mode = m
        engine.settingsChanged()
        syncAudio()
    }

    func setColourSource(_ v: String) {
        settings.sourceMode = v
        colourSource = settings.sourceMode
        engine.settingsChanged()
        syncAudio()
    }

    func setAudioSource(_ v: String) {
        settings.audioSource = v
        audioSource = settings.audioSource
        engine.settingsChanged()
        syncAudio()
    }

    func settingsSaved() {
        engine.settingsChanged()
        nowPlaying.start()
        syncAudio()
    }

    /// Notes worth showing under the colours (capture problems, now-playing problems).
    var notes: String? {
        let n = [status.note, captureNote, sourceNote].compactMap { $0 }
        return n.isEmpty ? nil : n.joined(separator: "\n")
    }

    /// Microphone, the Mac's sound and the screen run when the settings use them (macOS asks for
    /// permission the first time).
    func syncAudio() {
        output.sensitivity = Double(settings.energySensitivityPct) / 100
        let wantOutput = engine.outputWanted
        let wantScreen = engine.screenWanted
        if wantOutput && !capture.running { output.reset() }
        capture.update(screen: wantScreen, audio: wantOutput)
        if wantOutput {
            if mic.running { mic.stop() }
            setMicState(capture.running ? "Listening to this Mac's sound" : "This Mac's sound: \(capture.lastError == nil ? "starting…" : "not available")")
            return
        }
        syncMic()
    }

    /// The microphone runs when the mode uses it (asks for permission the first time).
    private func syncMic() {
        mic.sensitivity = Double(settings.energySensitivityPct) / 100
        if engine.micWanted {
            guard !mic.running, !askingMic else { return }
            askingMic = true
            MicInput.requestPermission { [weak self] ok in
                guard let self else { return }
                self.askingMic = false
                if ok { self.mic.start() }
                self.setMicState(!ok ? "Microphone not allowed: System Settings → Privacy & Security → Microphone"
                    : (self.mic.running ? "Microphone on" : "Microphone: \(self.mic.lastError ?? "can't start")"))
            }
        } else {
            if mic.running { mic.stop() }
            setMicState(settings.mode.micOn ? "Not listening (audio source: off)" : "Microphone off")
        }
    }

    private func setMicState(_ s: String) {
        if micState != s { micState = s }
    }
}

extension Rgb {
    var color: Color { Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255) }
}
