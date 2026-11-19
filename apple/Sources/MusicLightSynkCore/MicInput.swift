import Foundation
import AVFoundation

/// Mono samples (any block size, any rate) -> [AudioLevels] (lows / mids / highs + beat) per
/// 1024 samples, ~43 times a second at 44.1 kHz. Used for the microphone and the Mac's own sound.
public final class AudioAnalyzer {
    private let lock = NSLock()
    private var meter = EnergyMeter()
    private var pending: [Float] = []
    private let n = 1024
    private let onLevels: (AudioLevels, Bool) -> Void

    public var sensitivity: Double = 1 {
        didSet { lock.lock(); meter.sensitivity = sensitivity; lock.unlock() }
    }

    /// [onLevels] runs on the thread that calls [feed]: (levels, silent).
    public init(onLevels: @escaping (AudioLevels, Bool) -> Void) { self.onLevels = onLevels }

    /// Start over (a new source): the auto-gain learns the new loudness.
    public func reset() {
        lock.lock()
        meter = EnergyMeter(sensitivity: sensitivity)
        pending.removeAll()
        lock.unlock()
    }

    /// One audio thread at a time.
    public func feed(_ samples: UnsafeBufferPointer<Float>, rate: Double) {
        lock.lock()
        pending.append(contentsOf: samples)
        var blocks: [[Float]] = []
        while pending.count >= n {
            blocks.append(Array(pending[0..<n]))
            pending.removeFirst(n)
        }
        lock.unlock()
        for block in blocks {
            let e = Bands.fromSamples(block, sampleRate: rate)
            lock.lock()
            let levels = meter.update(e, nowMs: monotonicMs())
            let silent = meter.isSilent
            lock.unlock()
            onLevels(levels, silent)
        }
    }
}

/// The microphone -> [AudioLevels] through [AudioAnalyzer].
/// On iOS the audio session lets other apps keep playing and keeps listening with the screen locked
/// (the app declares the "audio" background mode).
public final class MicInput {
    private let engine = AVAudioEngine()
    private let analyzer: AudioAnalyzer
    public private(set) var running = false
    public var lastError: String?

    public var sensitivity: Double {
        get { analyzer.sensitivity }
        set { analyzer.sensitivity = newValue }
    }

    /// [onLevels] runs on the audio thread: (levels, silent).
    public init(onLevels: @escaping (AudioLevels, Bool) -> Void) { analyzer = AudioAnalyzer(onLevels: onLevels) }

    public static func requestPermission(_ done: @escaping (Bool) -> Void) {
        #if os(iOS)
        AVAudioSession.sharedInstance().requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
        #else
        AVCaptureDevice.requestAccess(for: .audio) { ok in DispatchQueue.main.async { done(ok) } }
        #endif
    }

    public func start() {
        if running { return }
        do {
            #if os(iOS)
            let session = AVAudioSession.sharedInstance()
            // Measure the room without stopping the music other apps play, and without voice processing.
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothA2DP])
            try session.setActive(true)
            #endif
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            let rate = format.sampleRate
            analyzer.reset()
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                guard let self, let ch = buffer.floatChannelData?[0] else { return }
                self.analyzer.feed(UnsafeBufferPointer(start: ch, count: Int(buffer.frameLength)), rate: rate)
            }
            engine.prepare()
            try engine.start()
            running = true
            lastError = nil
        } catch {
            lastError = error.localizedDescription
            engine.inputNode.removeTap(onBus: 0)
            running = false
        }
    }

    public func stop() {
        if !running { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        running = false
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}
