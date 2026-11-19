import AppKit
import AVFoundation
import CoreMedia
import ScreenCaptureKit
import MusicLightSynkCore

/// One ScreenCaptureKit stream for two things (macOS 13+, "Screen & System Audio Recording"
/// permission):
/// - the screen, small (~96 px wide), at most twice a second -> its colours ([onPixels]);
///   macOS only delivers a frame when something on screen changed;
/// - the Mac's own sound, what it plays (not the microphone) -> [onSamples].
/// This app's own windows are left out of the picture. Callbacks run on the capture queue.
final class MacCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    var onPixels: (([UInt32], Int, Int) -> Void)?
    var onSamples: ((UnsafeBufferPointer<Float>, Double) -> Void)?
    /// Main thread: running or not, and why not.
    var onState: ((Bool, String?) -> Void)?

    private let queue = DispatchQueue(label: "mls.capture")
    private var stream: SCStream?
    private var wantScreen = false
    private var wantAudio = false
    private var starting = false
    private var generation = 0
    private(set) var running = false
    private(set) var lastError: String?
    private var failedAt: Date?

    static var permitted: Bool { CGPreflightScreenCaptureAccess() }

    /// Main thread. Starts, restarts or stops the stream for what's wanted now.
    func update(screen: Bool, audio: Bool) {
        let same = screen == wantScreen && audio == wantAudio
        if same && (running || starting || (!screen && !audio)) { return }
        // After a failure, try again only every 30 s (or when what's wanted changes).
        if same, let f = failedAt, Date().timeIntervalSince(f) < 30 { return }
        failedAt = nil
        wantScreen = screen
        wantAudio = audio
        generation += 1
        let gen = generation
        let old = stream
        stream = nil
        if let old { old.stopCapture { _ in } }
        guard screen || audio else {
            if running || starting {
                running = false
                starting = false
                onState?(false, nil)
            }
            return
        }
        if !MacCapture.permitted {
            // Asks once (macOS shows the prompt, the app may need a restart after allowing).
            _ = CGRequestScreenCaptureAccess()
        }
        starting = true
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { [weak self] content, error in
            DispatchQueue.main.async { self?.begin(gen, content, error, screen: screen, audio: audio) }
        }
    }

    private func begin(_ gen: Int, _ content: SCShareableContent?, _ error: Error?, screen: Bool, audio: Bool) {
        guard gen == generation else { return }
        guard let content, let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else {
            fail(gen, MacCapture.describe(error) ?? "no screen to capture")
            return
        }
        let me = content.applications.filter { $0.processID == ProcessInfo.processInfo.processIdentifier }
        let filter = SCContentFilter(display: display, excludingApplications: me, exceptingWindows: [])
        let config = SCStreamConfiguration()
        let w = 96
        config.width = screen ? w : 16
        config.height = screen ? max(1, Int((Double(w) * Double(display.height) / Double(max(1, display.width))).rounded())) : 9
        config.minimumFrameInterval = CMTime(value: 1, timescale: screen ? 2 : 1)
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.showsCursor = false
        config.queueDepth = 3
        if audio {
            config.capturesAudio = true
            config.excludesCurrentProcessAudio = true
            config.sampleRate = 48_000
            config.channelCount = 2
        }
        let s = SCStream(filter: filter, configuration: config, delegate: self)
        do {
            try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
            if audio { try s.addStreamOutput(self, type: .audio, sampleHandlerQueue: queue) }
        } catch {
            fail(gen, MacCapture.describe(error) ?? "can't capture")
            return
        }
        stream = s
        s.startCapture { [weak self] error in
            DispatchQueue.main.async {
                guard let self, gen == self.generation else { return }
                self.starting = false
                if let error {
                    self.stream = nil
                    self.fail(gen, MacCapture.describe(error) ?? "can't capture")
                } else {
                    self.running = true
                    self.lastError = nil
                    self.onState?(true, nil)
                }
            }
        }
    }

    private func fail(_ gen: Int, _ why: String) {
        guard gen == generation else { return }
        starting = false
        running = false
        failedAt = Date()
        lastError = MacCapture.permitted ? why
            : "Allow Music Light Synk under System Settings → Privacy & Security → Screen & System Audio Recording, then quit and reopen it"
        onState?(false, lastError)
    }

    static func describe(_ error: Error?) -> String? {
        guard let error else { return nil }
        return (error as NSError).localizedDescription
    }

    // ---- SCStreamDelegate / SCStreamOutput -------------------------------------------------------

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        DispatchQueue.main.async {
            guard stream === self.stream else { return }
            self.stream = nil
            self.fail(self.generation, MacCapture.describe(error) ?? "capture stopped")
        }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard sampleBuffer.isValid else { return }
        if type == .screen {
            if wantScreen { screenFrame(sampleBuffer) }
        } else if type == .audio {
            audioFrame(sampleBuffer)
        }
    }

    private func screenFrame(_ sb: CMSampleBuffer) {
        // Only complete frames carry a new picture (idle ones mean "nothing changed").
        guard let infos = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = infos.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pb) else { return }
        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb), row = CVPixelBufferGetBytesPerRow(pb)
        guard w > 0, h > 0 else { return }
        let p = base.assumingMemoryBound(to: UInt8.self)
        var px = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let o = y * row + x * 4 // BGRA
                px[y * w + x] = 0xFF00_0000 | (UInt32(p[o + 2]) << 16) | (UInt32(p[o + 1]) << 8) | UInt32(p[o])
            }
        }
        onPixels?(px, w, h)
    }

    private func audioFrame(_ sb: CMSampleBuffer) {
        guard let asbd = sb.formatDescription?.audioStreamBasicDescription,
              asbd.mFormatID == kAudioFormatLinearPCM, asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0, asbd.mBitsPerChannel == 32
        else { return }
        let interleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0
        let channels = max(1, Int(asbd.mChannelsPerFrame))
        let rate = asbd.mSampleRate
        try? sb.withAudioBufferList { list, _ in
            guard let first = list.first, let data = first.mData else { return }
            let count = Int(first.mDataByteSize) / MemoryLayout<Float>.size
            let floats = data.assumingMemoryBound(to: Float.self)
            if interleaved && channels > 1 {
                // Left channel of interleaved frames.
                let frames = count / channels
                var mono = [Float](repeating: 0, count: frames)
                for i in 0..<frames { mono[i] = floats[i * channels] }
                mono.withUnsafeBufferPointer { onSamples?($0, rate) }
            } else {
                onSamples?(UnsafeBufferPointer(start: floats, count: count), rate)
            }
        }
    }
}
