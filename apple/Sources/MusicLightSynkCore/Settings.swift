import Foundation

/// What a device does (same as the Android app's DeviceMode).
public enum DeviceMode: String, CaseIterable {
    case lightsMic = "lights_mic"
    case mic
    case lights
    case off

    public var lights: Bool { self == .lightsMic || self == .lights }
    public var micOn: Bool { self == .lightsMic || self == .mic }

    public var label: String {
        switch self {
        case .lightsMic: return "Lights + microphone"
        case .mic: return "Microphone only (lend it to other devices)"
        case .lights: return "Lights only"
        case .off: return "Off"
        }
    }
}

/// All settings, in UserDefaults, with the Android app's keys so Home Assistant's controls work
/// the same on every device.
public final class AppSettings {
    let d: UserDefaults

    public init(_ defaults: UserDefaults = .standard) { d = defaults }

    private func str(_ k: String, _ def: String = "") -> String { d.string(forKey: k) ?? def }
    private func int(_ k: String, _ def: Int, _ range: ClosedRange<Int>) -> Int {
        min(range.upperBound, max(range.lowerBound, d.object(forKey: k) == nil ? def : d.integer(forKey: k)))
    }
    private func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }

    // Home Assistant
    public var haUrl: String {
        get { str("ha_url") }
        set { d.set(AppSettings.normalizeUrl(newValue), forKey: "ha_url") }
    }
    public var webhookId: String {
        get { str("webhook_id", "music_light_synk") }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).isEmpty ? "music_light_synk" : newValue.trimmingCharacters(in: .whitespaces), forKey: "webhook_id") }
    }
    public var webhookIds: [String] { webhookId.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
    public var isHaConfigured: Bool { !haUrl.isEmpty && !webhookIds.isEmpty }
    public func webhookUrl(_ id: String) -> String {
        "\(haUrl)/api/webhook/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)"
    }

    // What this device does
    public var mode: DeviceMode {
        get { DeviceMode(rawValue: str("mode")) ?? .lightsMic }
        set { d.set(newValue.rawValue, forKey: "mode") }
    }
    /// "beat" / "level" / "off": what Home Assistant gets for its music effect.
    public var haMusic: String {
        get { ["off", "beat", "level"].contains(str("ha_music")) ? str("ha_music") : "off" }
        set { d.set(["off", "beat", "level"].contains(newValue) ? newValue : "off", forKey: "ha_music") }
    }
    /// "mic" or "off" (a Mac / iPhone listens through its microphone).
    public var audioSource: String {
        get { str("audio_source", "mic") == "off" ? "off" : "mic" }
        set { d.set(newValue == "off" ? "off" : "mic", forKey: "audio_source") }
    }

    // Colours
    public var cornerPct: Int { get { int("corner_pct", 25, 10...50) } set { d.set(newValue, forKey: "corner_pct") } }
    public var saturationPct: Int { get { int("saturation_pct", 130, 50...250) } set { d.set(newValue, forKey: "saturation_pct") } }
    public var minBrightnessPct: Int { get { int("min_brightness_pct", 15, 1...60) } set { d.set(newValue, forKey: "min_brightness_pct") } }
    public var stopDelaySec: Int { get { int("stop_delay", 15, 0...300) } set { d.set(newValue, forKey: "stop_delay") } }
    public var extractOptions: ExtractOptions {
        ExtractOptions(cornerFraction: Double(cornerPct) / 100, saturationBoost: Double(saturationPct) / 100, minBrightness: Double(minBrightnessPct) / 100)
    }

    // WLED
    public var wledEnabled: Bool { get { bool("wled_enabled", true) } set { d.set(newValue, forKey: "wled_enabled") } }
    public var wledDevicesRaw: String {
        get { str("wled_devices") }
        set { d.set(WledDevices.format(WledDevices.parse(newValue).devices), forKey: "wled_devices") }
    }
    public var wledDevices: [WledDevice] { WledDevices.parse(wledDevicesRaw).devices }
    public var isWledConfigured: Bool { wledEnabled && !wledDevices.isEmpty }
    public var wledBrightnessPct: Int { get { int("wled_brightness_pct", 100, 1...100) } set { d.set(newValue, forKey: "wled_brightness_pct") } }
    public var wledRestore: Bool { get { bool("wled_restore", true) } set { d.set(newValue, forKey: "wled_restore") } }
    public var pulse: Bool { get { bool("pulse", false) } set { d.set(newValue, forKey: "pulse") } }
    public var pulseStrengthPct: Int { get { int("pulse_strength", 60, 10...100) } set { d.set(newValue, forKey: "pulse_strength") } }
    public var energySensitivityPct: Int { get { int("energy_sensitivity", 100, 10...300) } set { d.set(newValue, forKey: "energy_sensitivity") } }
    public var energyBlur: Int { get { int("energy_blur", 2, 0...10) } set { d.set(newValue, forKey: "energy_blur") } }
    public var energyBackgroundPct: Int { get { int("energy_bg", 10, 0...100) } set { d.set(newValue, forKey: "energy_bg") } }

    public var wledConfig: WledConfig {
        WledConfig(devices: wledDevices, brightness: Double(wledBrightnessPct) / 100, restore: wledRestore, pulse: pulse,
                   pulseStrength: Double(pulseStrengthPct) / 100, energyBackground: Double(energyBackgroundPct) / 100, energyBlur: energyBlur)
    }

    // Device link and Wi-Fi control
    public var linkEnabled: Bool { get { bool("link_enabled", true) } set { d.set(newValue, forKey: "link_enabled") } }
    public var deviceName: String { get { str("device_name") } set { d.set(String(newValue.prefix(40)), forKey: "device_name") } }
    public var room: String {
        get { let r = str("room", "Home"); return r.trimmingCharacters(in: .whitespaces).isEmpty ? "Home" : r }
        set { d.set(String(newValue.prefix(40)), forKey: "room") }
    }
    public var controlEnabled: Bool { get { bool("control_enabled", true) } set { d.set(newValue, forKey: "control_enabled") } }
    public var deviceId: String { persistentRandom("link_id", bytes: 5) }
    public var controlToken: String { persistentRandom("control_token", bytes: 16) }

    // Mac now-playing sources
    public var useSpotify: Bool { get { bool("src_spotify", true) } set { d.set(newValue, forKey: "src_spotify") } }
    public var useMusic: Bool { get { bool("src_music", true) } set { d.set(newValue, forKey: "src_music") } }
    public var useBrowsers: Bool { get { bool("src_browsers", true) } set { d.set(newValue, forKey: "src_browsers") } }

    public var hasOutput: Bool { isHaConfigured || isWledConfigured }

    private func persistentRandom(_ key: String, bytes: Int) -> String {
        if let v = d.string(forKey: key), !v.isEmpty { return v }
        var b = [UInt8](repeating: 0, count: bytes)
        for i in b.indices { b[i] = UInt8.random(in: 0...255) }
        let v = b.map { String(format: "%02x", $0) }.joined()
        d.set(v, forKey: key)
        return v
    }

    public static func normalizeUrl(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.isEmpty && !s.contains("://") { s = "http://" + s }
        return s
    }

    /// Apply settings from Home Assistant (same keys as the Android app; unknown ones are reported).
    public func configure(_ values: [String: Any]) -> String {
        var changed: [String] = []
        var bad: [String] = []
        func asInt(_ v: Any) -> Int? {
            if let n = v as? NSNumber { return n.intValue }
            if let s = v as? String { return Int(s.trimmingCharacters(in: .whitespaces)) }
            return nil
        }
        func asBool(_ v: Any) -> Bool? {
            if let b = v as? Bool { return b }
            if let n = v as? NSNumber { return n.intValue != 0 }
            if let s = v as? String { return ["1", "true", "on", "yes"].contains(s.lowercased()) ? true : (["0", "false", "off", "no"].contains(s.lowercased()) ? false : nil) }
            return nil
        }
        for (k, v) in values.sorted(by: { $0.key < $1.key }) {
            var ok = true
            switch k {
            case "ha_url": if let s = v as? String { haUrl = s } else { ok = false }
            case "webhook_id": if let s = v as? String { webhookId = s } else { ok = false }
            case "mode": if let s = v as? String, let m = DeviceMode(rawValue: s) { mode = m } else { ok = false }
            case "ha_music": if let s = v as? String { haMusic = s } else { ok = false }
            case "ha_beats": if let b = asBool(v) { haMusic = b ? "beat" : "off" } else { ok = false }
            case "audio_source": if let s = v as? String { audioSource = s } else { ok = false }
            case "enabled": if let b = asBool(v) { mode = b ? (mode.micOn ? .lightsMic : .lights) : (mode.micOn ? .mic : .off) } else { ok = false }
            case "wled_enabled": if let b = asBool(v) { wledEnabled = b } else { ok = false }
            case "wled_devices": if let s = v as? String { wledDevicesRaw = s } else { ok = false }
            case "wled_brightness_pct": if let n = asInt(v) { wledBrightnessPct = n } else { ok = false }
            case "wled_restore": if let b = asBool(v) { wledRestore = b } else { ok = false }
            case "pulse": if let b = asBool(v) { pulse = b } else { ok = false }
            case "pulse_strength": if let n = asInt(v) { pulseStrengthPct = n } else { ok = false }
            case "energy_sensitivity": if let n = asInt(v) { energySensitivityPct = n } else { ok = false }
            case "energy_blur": if let n = asInt(v) { energyBlur = n } else { ok = false }
            case "energy_bg": if let n = asInt(v) { energyBackgroundPct = n } else { ok = false }
            case "corner_pct": if let n = asInt(v) { cornerPct = n } else { ok = false }
            case "saturation_pct": if let n = asInt(v) { saturationPct = n } else { ok = false }
            case "min_brightness_pct": if let n = asInt(v) { minBrightnessPct = n } else { ok = false }
            case "stop_delay": if let n = asInt(v) { stopDelaySec = n } else { ok = false }
            case "link_enabled": if let b = asBool(v) { linkEnabled = b } else { ok = false }
            case "device_name": if let s = v as? String { deviceName = s } else { ok = false }
            case "room": if let s = v as? String { room = s } else { ok = false }
            default: ok = false
            }
            if ok { changed.append(k) } else { bad.append(k) }
        }
        return "Saved: " + changed.joined(separator: ", ") + (bad.isEmpty ? "" : " | not available here: " + bad.joined(separator: ", "))
    }

    /// The settings block of the status report (what Home Assistant's controls show).
    public func snapshot() -> [String: Any] {
        [
            "corner_pct": cornerPct, "saturation_pct": saturationPct, "min_brightness_pct": minBrightnessPct,
            "stop_delay": stopDelaySec, "wled_enabled": wledEnabled, "wled_devices": wledDevicesRaw,
            "wled_brightness_pct": wledBrightnessPct, "wled_restore": wledRestore, "pulse": pulse,
            "pulse_strength": pulseStrengthPct, "audio_source": audioSource, "energy_sensitivity": energySensitivityPct,
            "energy_blur": energyBlur, "energy_bg": energyBackgroundPct, "link_enabled": linkEnabled,
            "device_name": deviceName, "room": room, "mode": mode.rawValue, "ha_music": haMusic,
            "ha_beats": haMusic != "off",
        ]
    }
}
