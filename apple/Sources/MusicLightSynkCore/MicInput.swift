import Foundation
import AVFoundation

/// The microphone -> [AudioLevels] (lows / mids / highs + beat), ~43 times a second.
/// On iOS the audio session lets other apps keep playing and keeps listening with the screen locked
/// (the app declares the "audio" background mode).
public final class MicInput {
    private let engine = AVAudioEngine()
    private let meterLock = NSLock()
    private var meter = EnergyMeter()
    private var pending: [Float] = []
    private let n = 1024
    private let onLevels: (AudioLevels, Bool) -> Void
    public private(set) var running = false
    public var lastError: String?

    public var sensitivity: Double = 1 {
        didSet { meterLock.lock(); meter.sensitivity = sensitivity; meterLock.unlock() }
    }

    /// [onLevels] runs on the audio thread: (levels, silent).
    public init(onLevels: @escaping (AudioLevels, Bool) -> Void) { self.onLevels = onLevels }

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
            meterLock.lock(); meter = EnergyMeter(sensitivity: sensitivity); meterLock.unlock()
            pending.removeAll()
            input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(n), format: format) { [weak self] buffer, _ in
                self?.consume(buffer, rate: rate)
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

    private func consume(_ buffer: AVAudioPCMBuffer, rate: Double) {
        guard let ch = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        pending.append(contentsOf: UnsafeBufferPointer(start: ch, count: count))
        while pending.count >= n {
            let block = Array(pending[0..<n])
            pending.removeFirst(n)
            let e = Bands.fromSamples(block, sampleRate: rate)
            meterLock.lock()
            let levels = meter.update(e, nowMs: monotonicMs())
            let silent = meter.isSilent
            meterLock.unlock()
            onLevels(levels, silent)
        }
    }
}
