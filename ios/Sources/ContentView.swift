import SwiftUI
import MusicLightSynkCore

struct ContentView: View {
    @EnvironmentObject var model: PhoneModel
    @State private var haUrl = ""
    @State private var webhook = ""
    @State private var haMusic = "off"
    @State private var wled = ""
    @State private var brightness = 100.0
    @State private var sensitivity = 100.0
    @State private var blur = 2.0
    @State private var background = 10.0
    @State private var message: String?

    private let rows: [[Slot]] = [[.topLeft, .top, .topRight], [.left, .dominant, .right], [.bottomLeft, .bottom, .bottomRight]]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(model.status.nowPlaying).font(.headline)
                    VStack(spacing: 3) {
                        ForEach(0..<3, id: \.self) { r in
                            HStack(spacing: 3) {
                                ForEach(rows[r], id: \.self) { slot in
                                    RoundedRectangle(cornerRadius: 5)
                                        .fill(model.status.palette.map { $0[slot].light.color } ?? Color.gray.opacity(0.2))
                                        .frame(height: 28)
                                }
                            }
                        }
                    }
                    Picker("Mode", selection: Binding(get: { model.mode }, set: { model.setMode($0) })) {
                        ForEach(DeviceMode.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    HStack {
                        Text(model.micState).font(.caption)
                        Spacer()
                        ProgressView(value: min(1, max(0, model.status.level))).frame(width: 80)
                    }
                    Text(model.status.lastResult).font(.caption).foregroundStyle(.secondary)
                    if let note = model.status.note { Text(note).font(.caption).foregroundStyle(.orange) }
                    Button("Send test colours") { model.engine.sendTest() }
                } footer: {
                    Text("Colours come from Apple Music's album art (other music apps don't share it on iPhone). Otherwise the lights move to the beat in the last colours.")
                }

                Section("Home Assistant") {
                    TextField("http://192.168.1.10:8123", text: $haUrl).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Webhook ID, e.g. music_light_synk_iphone", text: $webhook).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Picker("Home Assistant lights", selection: $haMusic) {
                        Text("Colours only").tag("off")
                        Text("Beat pulse").tag("beat")
                        Text("Energy").tag("level")
                    }
                }

                Section {
                    TextEditor(text: $wled).font(.system(.footnote, design: .monospaced)).frame(minHeight: 70)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    VStack(alignment: .leading) {
                        Text("Brightness \(Int(brightness)) %").font(.caption)
                        Slider(value: $brightness, in: 10...100, step: 5)
                    }
                } header: {
                    Text("WLED devices")
                } footer: {
                    Text("One per line: IP=part[:options], e.g. 192.168.1.60=dominant:energy or 192.168.1.61=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror")
                }

                Section("Energy (bars to the beat)") {
                    VStack(alignment: .leading) {
                        Text("Sensitivity \(Int(sensitivity)) %").font(.caption)
                        Slider(value: $sensitivity, in: 10...300, step: 10)
                        Text("Blur \(Int(blur))").font(.caption)
                        Slider(value: $blur, in: 0...10, step: 1)
                        Text("Background \(Int(background)) %").font(.caption)
                        Slider(value: $background, in: 0...100, step: 5)
                    }
                }

                Section {
                    Button("Save") { save() }
                    if let message { Text(message).font(.caption) }
                    if let url = model.status.controlURL {
                        Text("Home Assistant can change these settings over Wi-Fi at \(url) while the app runs.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Music Light Synk")
            .onAppear(perform: load)
        }
    }

    private func load() {
        let s = model.settings
        haUrl = s.haUrl
        webhook = s.webhookId
        haMusic = s.haMusic
        wled = s.wledDevicesRaw.replacingOccurrences(of: "; ", with: "\n")
        brightness = Double(s.wledBrightnessPct)
        sensitivity = Double(s.energySensitivityPct)
        blur = Double(s.energyBlur)
        background = Double(s.energyBackgroundPct)
    }

    private func save() {
        let s = model.settings
        s.haUrl = haUrl
        s.webhookId = webhook
        s.haMusic = haMusic
        let parsed = WledDevices.parse(wled)
        s.wledDevicesRaw = wled
        s.wledBrightnessPct = Int(brightness)
        s.energySensitivityPct = Int(sensitivity)
        s.energyBlur = Int(blur)
        s.energyBackgroundPct = Int(background)
        model.settingsSaved()
        message = parsed.errors.isEmpty ? "Saved" : "Saved, but ignored: " + parsed.errors.joined(separator: "; ")
        load()
    }
}
