import Foundation

// Audio -> lows / mids / highs levels (0..1) + beat, ported from the Android app's
// AudioAnalysis.kt and BeatDetector. Pure Swift, unit tested.

public struct AudioLevels: Equatable {
    public var lows: Double
    public var mids: Double
    public var highs: Double
    public var beat: Bool

    public init(_ lows: Double, _ mids: Double, _ highs: Double, beat: Bool = false) {
        self.lows = lows
        self.mids = mids
        self.highs = highs
        self.beat = beat
    }

    public var overall: Double { max(lows, mids, highs) }
    public static let silent = AudioLevels(0, 0, 0)
}

public struct BandEnergies: Equatable {
    public var lows: Double
    public var mids: Double
    public var highs: Double
    public var total: Double { lows + mids + highs }
}

public enum Bands {
    public static let lowHz = 20.0, midHz = 250.0, highHz = 2000.0, topHz = 8000.0

    /// Mean magnitude per band; bin k is at k * sampleRate / fftSize.
    public static func fromMagnitudes(_ mags: [Double], sampleRate: Double, fftSize: Int) -> BandEnergies {
        let binHz = sampleRate / Double(fftSize)
        func mean(_ from: Double, _ to: Double) -> Double {
            let lo = max(1, Int((from / binHz).rounded()))
            let hi = min(mags.count - 1, Int((to / binHz).rounded()))
            if hi < lo { return 0 }
            var s = 0.0
            for k in lo...hi { s += mags[k] }
            return s / Double(hi - lo + 1)
        }
        return BandEnergies(lows: mean(lowHz, midHz), mids: mean(midHz, highHz), highs: mean(highHz, topHz))
    }

    /// From float samples in -1..1 (power-of-two count).
    public static func fromSamples(_ samples: [Float], sampleRate: Double) -> BandEnergies {
        let n = samples.count
        guard n >= 64, n & (n - 1) == 0, sampleRate > 0 else { return BandEnergies(lows: 0, mids: 0, highs: 0) }
        return fromMagnitudes(Fft.magnitudes(samples.map(Double.init)), sampleRate: sampleRate, fftSize: n)
    }
}

public enum Fft {
    /// In-place iterative radix-2 FFT; arrays must have the same power-of-two length.
    public static func transform(_ re: inout [Double], _ im: inout [Double]) {
        let n = re.count
        precondition(n == im.count && n > 0 && n & (n - 1) == 0, "length must be a power of two")
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while j & bit != 0 { j ^= bit; bit >>= 1 }
            j ^= bit
            if i < j { re.swapAt(i, j); im.swapAt(i, j) }
        }
        var len = 2
        while len <= n {
            let ang = -2 * Double.pi / Double(len)
            let wr = cos(ang), wi = sin(ang)
            var i = 0
            while i < n {
                var cr = 1.0, ci = 0.0
                for k in 0..<(len / 2) {
                    let a = i + k, b = a + len / 2
                    let xr = re[b] * cr - im[b] * ci
                    let xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    let nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len <<= 1
        }
    }

    /// Hann-windowed magnitude spectrum (n/2 bins); a full-scale sine gives ~0.5 in its bin.
    public static func magnitudes(_ samples: [Double]) -> [Double] {
        let n = samples.count
        var re = [Double](repeating: 0, count: n)
        var im = [Double](repeating: 0, count: n)
        for i in 0..<n { re[i] = samples[i] * (0.5 - 0.5 * cos(2 * Double.pi * Double(i) / Double(n - 1))) }
        transform(&re, &im)
        let scale = 2.0 / Double(n)
        return (0..<(n / 2)).map { k in (re[k] * re[k] + im[k] * im[k]).squareRoot() * scale }
    }
}

/// Kick drum detection: bass energy well above its recent average.
public final class BeatDetector {
    private let historySize: Int
    private let sensitivity: Double
    private let minIntervalMs: Int64
    private let minEnergy: Double
    private var history: [Double]
    private var count = 0
    private var index = 0
    private var lastBeat: Int64 = .min / 2

    public init(historySize: Int = 43, sensitivity: Double = 1.4, minIntervalMs: Int64 = 250, minEnergy: Double = 1e-3) {
        self.historySize = historySize
        self.sensitivity = sensitivity
        self.minIntervalMs = minIntervalMs
        self.minEnergy = minEnergy
        history = [Double](repeating: 0, count: historySize)
    }

    public func feed(_ energy: Double, nowMs: Int64) -> Bool {
        let filled = min(count, historySize)
        var avg = 0.0
        for i in 0..<filled { avg += history[i] }
        avg = filled > 0 ? avg / Double(filled) : 0
        history[index] = energy
        index = (index + 1) % historySize
        count += 1
        if filled < 8 { return false }
        let isBeat = energy > minEnergy && energy > avg * sensitivity && nowMs - lastBeat >= minIntervalMs
        if isBeat { lastBeat = nowMs }
        return isBeat
    }
}

/// Raw band energies -> lively 0..1 levels: per-band auto-gain, a slowly rising floor (so
/// transients jump out), fast attack / slower decay, and a noise gate so silence stays dark.
public final class EnergyMeter {
    public static let defaultMinPeak = 0.002
    private let minPeak: Double
    public var sensitivity: Double
    private let peakTauMs = 4000.0, floorTauMs = 2500.0, attack = 0.65, decayTauMs = 140.0
    private var peak: [Double]
    private var floor = [0.0, 0.0, 0.0]
    private var smooth = [0.0, 0.0, 0.0]
    private var lastMs: Int64 = .min
    private var quietSince: Int64 = .min
    private let beats: BeatDetector
    public private(set) var isSilent = true

    public init(minPeak: Double = EnergyMeter.defaultMinPeak, sensitivity: Double = 1) {
        self.minPeak = minPeak
        self.sensitivity = sensitivity
        peak = [minPeak, minPeak, minPeak]
        beats = BeatDetector(minEnergy: minPeak * 0.5)
    }

    public func update(_ e: BandEnergies, nowMs: Int64) -> AudioLevels {
        let dt = lastMs == .min ? 25.0 : Double(min(1000, max(1, nowMs - lastMs)))
        lastMs = nowMs
        let raw = [e.lows, e.mids, e.highs]
        var out = [0.0, 0.0, 0.0]
        for b in 0..<3 {
            let v = max(0, raw[b])
            peak[b] = max(v, peak[b] * exp(-dt / peakTauMs), minPeak)
            floor[b] = v < floor[b] ? v : floor[b] + (v - floor[b]) * (1 - exp(-dt / floorTauMs))
            let absolute = v / peak[b]
            let transient = min(1, max(0, (v - floor[b]) / (peak[b] - floor[b] + 1e-9)))
            let level = min(1, max(0, (0.4 * absolute + 0.6 * transient) * sensitivity))
            smooth[b] = level > smooth[b]
                ? smooth[b] + (level - smooth[b]) * attack
                : smooth[b] + (level - smooth[b]) * (1 - exp(-dt / decayTauMs))
            out[b] = smooth[b]
        }
        let loud = e.total > minPeak * 0.3
        if loud { quietSince = .min } else if quietSince == .min { quietSince = nowMs }
        isSilent = quietSince != .min && nowMs - quietSince > 1500
        let beat = beats.feed(e.lows, nowMs: nowMs)
        if isSilent { return .silent }
        return AudioLevels(out[0], out[1], out[2], beat: beat)
    }
}

/// Milliseconds on a monotonic clock.
public func monotonicMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
