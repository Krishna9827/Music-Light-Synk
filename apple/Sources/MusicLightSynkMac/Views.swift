import AppKit
import SwiftUI
import MusicLightSynkCore

struct MenuView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Music Light Synk").font(.headline)
            Text(model.status.nowPlaying).font(.subheadline).lineLimit(2)
            SwatchGrid(palette: model.status.palette)
            Picker("Mode", selection: Binding(get: { model.mode }, set: { model.setMode($0) })) {
                ForEach(DeviceMode.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Picker("Colours from", selection: Binding(get: { model.colourSource }, set: { model.setColourSource($0) })) {
                ForEach(ColourSources.all) { Text($0.label).tag($0.key) }
            }
            Picker("Listen to", selection: Binding(get: { model.audioSource }, set: { model.setAudioSource($0) })) {
                ForEach(AudioSources.all) { Text($0.label).tag($0.key) }
            }
            HStack {
                Text(model.micState).font(.caption)
                Spacer()
                LevelBar(level: model.status.level).frame(width: 90, height: 6)
            }
            Text(model.status.lastResult).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            if let note = model.notes {
                Text(note).font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            }
            if !model.status.peers.isEmpty {
                Text("Other devices: " + model.status.peers.joined(separator: ", ")).font(.caption)
            }
            Divider()
            HStack {
                Button("Test colours") { model.engine.sendTest() }
                Button("Settings…") {
                    openWindow(id: "settings")
                    NSApp.activate(ignoringOtherApps: true)
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 340)
    }
}

struct Choice: Identifiable {
    let key: String
    let label: String
    var id: String { key }
}

enum ColourSources {
    static let all = [
        Choice(key: "artwork", label: "Album art"),
        Choice(key: "auto", label: "Album art, else the screen"),
        Choice(key: "screen", label: "The screen (always)"),
    ]
}

enum AudioSources {
    static let all = [
        Choice(key: "mic", label: "Microphone"),
        Choice(key: "output", label: "This Mac's sound"),
        Choice(key: "off", label: "Off"),
    ]
}

/// The colours laid out like a screen: corners, sides and the dominant colour in the middle.
struct SwatchGrid: View {
    let palette: Palette?
    private let rows: [[Slot]] = [[.topLeft, .top, .topRight], [.left, .dominant, .right], [.bottomLeft, .bottom, .bottomRight]]

    var body: some View {
        VStack(spacing: 3) {
            ForEach(0..<3, id: \.self) { r in
                HStack(spacing: 3) {
                    ForEach(rows[r], id: \.self) { slot in
                        RoundedRectangle(cornerRadius: 5)
                            .fill(palette.map { $0[slot].light.color } ?? Color.gray.opacity(0.2))
                            .frame(height: 24)
                    }
                }
            }
        }
    }
}

struct LevelBar: View {
    let level: Double

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.gray.opacity(0.25))
                Capsule().fill(Color.accentColor).frame(width: g.size.width * min(1, max(0, level)))
            }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var haUrl = ""
    @State private var webhook = ""
    @State private var haMusic = "off"
    @State private var control = true
    @State private var wleds: [EditableWled] = []
    @State private var brightness = 100.0
    @State private var sensitivity = 100.0
    @State private var blur = 2.0
    @State private var background = 10.0
    @State private var spotify = true
    @State private var music = true
    @State private var browsers = true
    @State private var screenInterval = 5.0
    @State private var link = true
    @State private var deviceName = ""
    @State private var room = ""
    @State private var message: String?

    var body: some View {
        Form {
            Section("Home Assistant") {
                TextField("URL", text: $haUrl, prompt: Text("http://192.168.1.10:8123"))
                TextField("Webhook ID", text: $webhook, prompt: Text("music_light_synk_mac"))
                Picker("Home Assistant lights", selection: $haMusic) {
                    Text("Colours only").tag("off")
                    Text("Beat pulse").tag("beat")
                    Text("Energy (style picked in Home Assistant)").tag("level")
                }
                Toggle("Home Assistant may change settings over Wi-Fi", isOn: $control)
                if let url = model.status.controlURL { Text("Control address: \(url)").font(.caption).foregroundStyle(.secondary) }
            }
            Section("WLED devices (driven by this Mac)") {
                ForEach(wleds) { d in
                    WledRow(device: binding(d.id)) { wleds.removeAll { $0.id == d.id } }
                }
                Button("Add WLED") { wleds.append(EditableWled()) }
                Slider(value: $brightness, in: 10...100, step: 5) { Text("Brightness \(Int(brightness)) %") }
            }
            Section("Energy (bars to the beat)") {
                Slider(value: $sensitivity, in: 10...300, step: 10) { Text("Sensitivity \(Int(sensitivity)) %") }
                Slider(value: $blur, in: 0...10, step: 1) { Text("Blur \(Int(blur))") }
                Slider(value: $background, in: 0...100, step: 5) { Text("Background \(Int(background)) %") }
            }
            Section("What's playing") {
                Toggle("Spotify app", isOn: $spotify)
                Toggle("Apple Music app", isOn: $music)
                Toggle("Web players in Safari, Chrome, Edge or Brave (YouTube Music, YouTube, Spotify, Apple Music)", isOn: $browsers)
                Text("Web players need \"Allow JavaScript from Apple Events\": Safari → Settings → Advanced → Show features for web developers, then Develop menu; Chrome → View → Developer.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Screen colours") {
                Slider(value: $screenInterval, in: 0...60, step: 1) {
                    Text(screenInterval == 0 ? "Home Assistant lights: on track change only" : "Home Assistant lights: every \(Int(screenInterval)) s at most")
                }
                Text("With \"Colours from: the screen\" (menu), WLED follows the screen all the time. Slow bulbs in Home Assistant get the new colours at most this often. The first time, macOS asks to allow Screen & System Audio Recording (also used for \"Listen to: This Mac's sound\").")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Other devices") {
                Toggle("Device link (Fire TV / phone)", isOn: $link)
                TextField("This Mac's name", text: $deviceName, prompt: Text(Host.current().localizedName ?? "Mac"))
                TextField("Room", text: $room, prompt: Text("Home"))
            }
            HStack {
                if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
                Spacer()
                Button("Save") { save() }.keyboardShortcut(.defaultAction)
            }
        }
        .formStyle(.grouped)
        .frame(width: 540, height: 680)
        .onAppear(perform: load)
    }

    private func load() {
        let s = model.settings
        haUrl = s.haUrl
        webhook = s.webhookId
        haMusic = s.haMusic
        control = s.controlEnabled
        wleds = s.wledDevices.map(EditableWled.init)
        brightness = Double(s.wledBrightnessPct)
        sensitivity = Double(s.energySensitivityPct)
        blur = Double(s.energyBlur)
        background = Double(s.energyBackgroundPct)
        spotify = s.useSpotify
        music = s.useMusic
        browsers = s.useBrowsers
        screenInterval = Double(s.screenIntervalSec)
        link = s.linkEnabled
        deviceName = s.deviceName
        room = s.room
    }

    private func save() {
        let s = model.settings
        s.haUrl = haUrl
        s.webhookId = webhook
        s.haMusic = haMusic
        s.controlEnabled = control
        var list: [WledDevice] = []
        var errors: [String] = []
        for w in wleds {
            if w.host.trimmingCharacters(in: .whitespaces).isEmpty { continue } // an empty new row
            if let d = w.device { list.append(d) } else { errors.append("\(w.host): each side 0–4000 LEDs, at least 1 in total") }
        }
        if errors.isEmpty { s.wledDevicesRaw = WledDevices.format(list) }
        s.wledBrightnessPct = Int(brightness)
        s.energySensitivityPct = Int(sensitivity)
        s.energyBlur = Int(blur)
        s.energyBackgroundPct = Int(background)
        s.useSpotify = spotify
        s.useMusic = music
        s.useBrowsers = browsers
        s.screenIntervalSec = Int(screenInterval)
        s.linkEnabled = link
        s.deviceName = deviceName
        s.room = room
        model.settingsSaved()
        if errors.isEmpty {
            message = "Saved"
            load()
        } else {
            message = "WLED list not saved: " + errors.joined(separator: "; ")
        }
    }

    private func binding(_ id: UUID) -> Binding<EditableWled> {
        Binding(get: { wleds.first { $0.id == id } ?? EditableWled() },
                set: { v in if let i = wleds.firstIndex(where: { $0.id == id }) { wleds[i] = v } })
    }
}

/// One WLED in the settings, as fields (no text format to get wrong). Options this editor
/// doesn't show (a palette's colours / effect, "keep", leds=) are kept from the saved device.
struct EditableWled: Identifiable {
    let id = UUID()
    var host = ""
    var part = "dominant"
    var top = 40, right = 22, bottom = 40, left = 22
    var start = Corner.bottomLeft
    var clockwise = true
    var energy = false, flip = false, mirror = false
    var base: WledDevice?

    init() {}

    init(_ d: WledDevice) {
        host = d.host
        base = d
        energy = d.effect.energy
        flip = d.effect.flip
        mirror = d.effect.mirror
        switch d.zone {
        case let .section(slot, _): part = slot.key
        case .palette: part = "palette"
        case let .ambient(l):
            part = "ambient"
            top = l.top
            right = l.right
            bottom = l.bottom
            left = l.left
            start = l.start
            clockwise = l.clockwise
        }
    }

    static let parts: [Choice] = Slot.allCases.map { Choice(key: $0.key, label: $0.label) } + [
        Choice(key: "palette", label: "WLED's own effect, album colours"),
        Choice(key: "ambient", label: "Ambient gradient (strip around the TV / monitor)"),
    ]

    /// nil when the LED counts make no strip.
    var device: WledDevice? {
        let h = host.trimmingCharacters(in: .whitespaces)
        let zone: WledZone
        switch part {
        case "ambient":
            guard let l = WledLayout.parse("\(top),\(right),\(bottom),\(left)", start: start, clockwise: clockwise) else { return nil }
            zone = .ambient(l)
        case "palette":
            if let b = base, case .palette = b.zone { zone = b.zone } else { zone = .palette(slots: WledZone.defaultSlots, fx: -1, pal: -1) }
        default:
            var keep = false
            if let b = base, case let .section(_, k) = b.zone { keep = k }
            zone = .section(Slot.of(part) ?? .dominant, keepEffect: keep)
        }
        return WledDevice(host: h, zone: zone, effect: WledEffect(energy: energy, flip: flip, mirror: mirror), leds: base?.leds ?? 0)
    }
}

struct WledRow: View {
    @Binding var device: EditableWled
    let onRemove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                TextField("WLED IP", text: $device.host, prompt: Text("192.168.1.60")).frame(width: 130)
                Picker("Shows", selection: $device.part) {
                    ForEach(EditableWled.parts) { Text($0.label).tag($0.key) }
                }
                .labelsHidden()
                Button(action: onRemove) { Image(systemName: "trash") }.buttonStyle(.borderless).help("Remove this WLED")
            }
            if device.part == "ambient" {
                HStack(spacing: 6) {
                    Text("LEDs:").font(.caption)
                    count("top", $device.top)
                    count("right", $device.right)
                    count("bottom", $device.bottom)
                    count("left", $device.left)
                }
                HStack {
                    Picker("LED 1 at", selection: $device.start) {
                        ForEach(Corner.allCases, id: \.self) { Text($0.rawValue.replacingOccurrences(of: "_", with: " ")).tag($0) }
                    }
                    Picker("then", selection: $device.clockwise) {
                        Text("clockwise").tag(true)
                        Text("anticlockwise").tag(false)
                    }
                }
                .font(.caption)
                Text("Seen from the front. LED 1 is where the data wire enters; a side without LEDs gets 0.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            if device.part != "palette" {
                HStack {
                    Toggle("Energy", isOn: $device.energy)
                    Toggle("Flip", isOn: $device.flip)
                    Toggle("Mirror", isOn: $device.mirror)
                }
                .font(.caption)
            }
        }
        .padding(.vertical, 2)
    }

    private func count(_ label: String, _ value: Binding<Int>) -> some View {
        HStack(spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            TextField(label, value: value, format: .number).frame(width: 46).labelsHidden()
        }
    }
}
