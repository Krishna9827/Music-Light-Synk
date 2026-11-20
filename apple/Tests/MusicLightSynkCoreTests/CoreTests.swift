import XCTest
@testable import MusicLightSynkCore

final class ColourTests: XCTestCase {
    /// The Android app's testPalette(), dumped from ColorExtractor.kt: both platforms must agree.
    func testSameColoursAsTheAndroidApp() {
        let expected: [(Slot, String, String, Int)] = [
            (.topLeft, "#FF0600", "#E53935", 233), (.top, "#FF8E00", "#FB8C00", 252),
            (.topRight, "#FFD000", "#FDD835", 253), (.left, "#FF005D", "#D81B60", 222),
            (.right, "#3EFF47", "#43A047", 174), (.bottomLeft, "#2241FF", "#3949AB", 184),
            (.bottom, "#0088FF", "#1E88E5", 233), (.bottomRight, "#00E3FF", "#00ACC1", 202),
            (.dominant, "#CA00FF", "#8E24AA", 183), (.vibrant, "#FF8E00", "#FB8C00", 252),
            (.average, "#FFA0D4", "#8B6379", 156),
        ]
        let p = ColorExtractor.testPalette()
        for (slot, light, raw, bri) in expected {
            XCTAssertEqual(p[slot].light.hex, light, slot.key)
            XCTAssertEqual(p[slot].raw.hex, raw, slot.key)
            XCTAssertEqual(p[slot].brightness, bri, slot.key)
        }
    }

    func testDistanceAndBlank() {
        let p = ColorExtractor.testPalette()
        XCTAssertEqual(p.maxDistance(to: p), 0)
        XCTAssertEqual(p.maxDistance(to: nil), Int.max)
        XCTAssertFalse(p.isBlank)
        let black = ColorExtractor.extract(pixels: [UInt32](repeating: 0xFF000000, count: 16), width: 4, height: 4)
        XCTAssertTrue(black.isBlank)
    }
}

final class AudioTests: XCTestCase {
    func sine(_ f: Double, amp: Double = 0.5) -> [Float] { (0..<1024).map { Float(amp * sin(2 * .pi * f * Double($0) / 44100)) } }

    func testBandsSeparateBassMidsTreble() {
        let bass = Bands.fromSamples(sine(90), sampleRate: 44100)
        XCTAssertGreaterThan(bass.lows, bass.mids * 5)
        let mid = Bands.fromSamples(sine(900), sampleRate: 44100)
        XCTAssertGreaterThan(mid.mids, mid.lows * 3)
        let high = Bands.fromSamples(sine(5000), sampleRate: 44100)
        XCTAssertGreaterThan(high.highs, high.mids * 3)
    }

    func testMeterBouncesAndSilenceGoesDark() {
        let m = EnergyMeter(minPeak: 0.01)
        var t: Int64 = 0
        var ups: [Double] = []
        for frame in 0..<400 {
            t += 25
            let kick = frame % 20 < 2
            let l = m.update(BandEnergies(lows: kick ? 0.4 : 0.05, mids: 0.08, highs: 0.03), nowMs: t)
            if frame > 100 && kick { ups.append(l.lows) }
        }
        XCTAssertGreaterThan(ups.reduce(0, +) / Double(ups.count), 0.6)
        var last = AudioLevels.silent
        for _ in 0..<100 { t += 25; last = m.update(BandEnergies(lows: 0, mids: 0, highs: 0), nowMs: t) }
        XCTAssertTrue(m.isSilent)
        XCTAssertEqual(last.overall, 0)
    }
}

final class WledTests: XCTestCase {
    func testDeviceListRoundTripsLikeTheAndroidApp() {
        let raw = "192.168.1.60=ambient:40,22,40,22:bottom_left:cw:energy:flip:mirror; 192.168.1.61=dominant:energy:leds=60; 192.168.1.62=left:keep; 192.168.1.63=palette:top,bottom:fx=0"
        let p = WledDevices.parse(raw)
        XCTAssertTrue(p.errors.isEmpty, "\(p.errors)")
        XCTAssertEqual(WledDevices.format(p.devices), raw)
        XCTAssertTrue(p.devices[0].realtime)
        XCTAssertTrue(p.devices[1].realtime)
        XCTAssertFalse(p.devices[2].realtime)
        XCTAssertEqual(WledDevices.parse("no-equals; 1.2.3.4=sideways; 1.2.3.5=ambient:1,2,3; 1.2.3.7=top").errors.count, 3)
    }

    func testAmbientIsForgivingAboutSpacesAndCorners() {
        let p = WledDevices.parse("10.0.0.61=ambient:30, 18, 30, 18:bottom left:anticlockwise:energy\n10.0.0.62=ambient:10,5,10,5:TR")
        XCTAssertTrue(p.errors.isEmpty, p.errors.joined())
        XCTAssertEqual(WledDevices.formatDevice(p.devices[0]), "ambient:30,18,30,18:bottom_left:ccw:energy")
        XCTAssertEqual(WledDevices.formatDevice(p.devices[1]), "ambient:10,5,10,5:top_right:cw")
    }

    func testEnergyDirectionsMatchLedFx() {
        XCTAssertLessThan(EnergyEffect.axis(0, 10, flip: false, mirror: false), 0.1)
        XCTAssertLessThan(EnergyEffect.axis(9, 10, flip: true, mirror: false), 0.1)
        XCTAssertLessThan(EnergyEffect.axis(4, 10, flip: false, mirror: true), 0.15) // mirror: from the middle
        XCTAssertLessThan(EnergyEffect.axis(0, 10, flip: true, mirror: true), 0.15)  // mirror + flip: from both ends
        let lit = EnergyEffect.render(base: [Int](repeating: 0xFF0000, count: 20), bands: nil, levels: AudioLevels(0.5, 0, 0),
                                      effect: WledEffect(energy: true), background: 0, blur: 0).map { $0 != 0 }
        XCTAssertTrue(lit[0..<9].allSatisfy { $0 })
        XCTAssertTrue(lit[12..<20].allSatisfy { !$0 })
    }

    func testLayoutAndPackets() {
        let l = WledLayout(top: 4, right: 2, bottom: 4, left: 2, start: .bottomLeft, clockwise: true)
        let frame = l.render { ColorExtractor.testPalette()[$0].light }
        XCTAssertEqual(frame.count, 12)
        let packets = WledPackets.dnrgb([Int](repeating: 0x112233, count: 600), timeoutSec: 2)
        XCTAssertEqual(packets.count, 2)
        XCTAssertEqual(packets[0].count, 4 + 489 * 3)
        XCTAssertEqual(Array(packets[1][0..<4]), [4, 2, 1, 233]) // starts at LED 489
    }
}

final class LinkTests: XCTestCase {
    func testMessagesRoundTripAndMatchTheAndroidFormat() {
        let msgs: [LinkProtocol.Msg] = [
            .hi(from: "mac1", name: "MacBook", platform: "mac", room: "Home", listening: true, mic: true, src: ""),
            .pal(from: "mac1", colors: ColorExtractor.testPalette().rawColors, playing: true, title: "Song"),
            .nrg(from: "mac1", levels: AudioLevels(0.5, 0.25, 0.125, beat: true)),
            .claim(from: "mac1", prio: 3, playingForMs: 1000, hosts: ["10.0.0.60"]),
        ]
        for m in msgs { XCTAssertEqual(LinkProtocol.decode(LinkProtocol.encode(m)), m) }
        // a hi exactly as the Android app sends it
        let android = #"{"v":2,"g":"home","id":"tv1","t":"hi","name":"Fire TV","plat":"tv","room":"Home","listen":false,"mic":false,"src":"mac1"}"#
        XCTAssertEqual(LinkProtocol.decode(Array(android.utf8)), .hi(from: "tv1", name: "Fire TV", platform: "tv", room: "Home", listening: false, mic: false, src: "mac1"))
    }

    func testPerStripElection() {
        let phone = LightHub(selfId: "phone")
        phone.onClaim(id: "tv", prio: LightHub.prioPlaying, playingForMs: 5000, hosts: ["10.0.0.60"], nowMs: 1000)
        XCTAssertEqual(phone.allowedHosts(prio: LightHub.prioListening, playingForMs: 0, mine: ["10.0.0.60", "10.0.0.70"], nowMs: 1500), ["10.0.0.70"])
        XCTAssertEqual(phone.allowedHosts(prio: LightHub.prioListening, playingForMs: 0, mine: ["10.0.0.60"], nowMs: 4000), ["10.0.0.60"]) // expired
    }

    func testUdpLoopback() throws {
        let rx = try XCTUnwrap(UdpSocket(bindPort: 0))
        let tx = try XCTUnwrap(UdpSocket())
        let addr = try XCTUnwrap(UdpSocket.resolve("127.0.0.1", port: rx.localPort))
        XCTAssertTrue(tx.send(LinkProtocol.encode(.nrg(from: "x", levels: AudioLevels(0.3, 0, 0))), to: addr))
        let got = try XCTUnwrap(rx.receive())
        XCTAssertEqual(LinkProtocol.decode(got.bytes), .nrg(from: "x", levels: AudioLevels(0.3, 0, 0)))
    }
}

final class SettingsAndControlTests: XCTestCase {
    func freshSettings() -> AppSettings {
        let suite = "mls-test-\(UUID().uuidString)"
        return AppSettings(UserDefaults(suiteName: suite)!)
    }

    func testConfigureUsesTheAndroidKeys() {
        let s = freshSettings()
        let msg = s.configure(["mode": "mic", "ha_music": "level", "energy_blur": 4, "wled_devices": "10.0.0.60=dominant:energy", "nope": 1])
        XCTAssertEqual(s.mode, .mic)
        XCTAssertEqual(s.haMusic, "level")
        XCTAssertEqual(s.energyBlur, 4)
        XCTAssertEqual(s.wledDevices.count, 1)
        XCTAssertTrue(msg.contains("not available here: nope"), msg)
        XCTAssertEqual(s.snapshot()["mode"] as? String, "mic")
    }

    func testControlServerChecksTheToken() throws {
        let server = ControlServer(token: { "s3cret" }) { action, body in "done \(action) \(body.count)" }
        let port: UInt16 = 21_431
        XCTAssertTrue(server.start(port: port))
        defer { server.stop() }
        func call(_ path: String, token: String?) -> (Int, [String: Any]) {
            var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(path)")!)
            req.httpMethod = path == "/api/ping" ? "GET" : "POST"
            req.httpBody = path == "/api/ping" ? nil : Data(#"{"corner_pct":25}"#.utf8)
            if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
            let done = expectation(description: path)
            var out: (Int, [String: Any]) = (0, [:])
            URLSession.shared.dataTask(with: req) { data, resp, _ in
                out = ((resp as? HTTPURLResponse)?.statusCode ?? 0, (data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:])
                done.fulfill()
            }.resume()
            wait(for: [done], timeout: 5)
            return out
        }
        XCTAssertEqual(call("/api/ping", token: nil).0, 200)
        XCTAssertEqual(call("/api/configure", token: nil).0, 401)
        XCTAssertEqual(call("/api/configure", token: "wrong").0, 401)
        let ok = call("/api/configure", token: "s3cret")
        XCTAssertEqual(ok.0, 200)
        XCTAssertEqual(ok.1["message"] as? String, "done configure 1")
    }

    func testColourAndAudioSourceKeys() {
        let s = freshSettings()
        XCTAssertEqual(s.sourceMode, "artwork")
        XCTAssertEqual(s.audioSource, "mic")
        let msg = s.configure(["source": "auto", "screen_interval": 500, "audio_source": "output"])
        XCTAssertFalse(msg.contains("not available"), msg)
        XCTAssertEqual(s.sourceMode, "auto")
        XCTAssertEqual(s.screenIntervalSec, 120)
        XCTAssertEqual(s.audioSource, "output")
        XCTAssertTrue(s.configure(["source": "nonsense"]).contains("not available here: source"))
        XCTAssertEqual(s.sourceMode, "auto")
        XCTAssertEqual(s.snapshot()["source"] as? String, "auto")

        let mac = Engine(settings: s, platform: "mac", appVersion: "test", defaultName: "Mac")
        XCTAssertTrue(mac.outputWanted)
        XCTAssertFalse(mac.micWanted)
        XCTAssertTrue(mac.screenWanted)
        XCTAssertEqual((mac.statusJson()["audio_sources"] as? [[String: String]])?.map { $0["key"] ?? "" }, ["mic", "output", "off"])
        // An iPhone has no "Mac's sound": it keeps listening through the microphone.
        let phone = Engine(settings: s, platform: "ios", appVersion: "test", defaultName: "iPhone")
        XCTAssertFalse(phone.outputWanted)
        XCTAssertTrue(phone.micWanted)
        XCTAssertFalse(phone.screenWanted)
    }

    func testAnalyzerTurnsAnyBlockSizeIntoLevels() {
        var calls = 0
        let a = AudioAnalyzer { _, _ in calls += 1 }
        let samples = (0..<3000).map { Float(0.5 * sin(2 * .pi * 90 * Double($0) / 48000)) }
        samples.withUnsafeBufferPointer { all in
            a.feed(UnsafeBufferPointer(rebasing: all[0..<700]), rate: 48000)
            a.feed(UnsafeBufferPointer(rebasing: all[700..<3000]), rate: 48000)
        }
        XCTAssertEqual(calls, 2) // 3000 samples: two blocks of 1024, the rest waits
    }

    func testScreenModeDrivesWledWithoutMusic() throws {
        let rx = try XCTUnwrap(UdpSocket(bindPort: 0, receiveTimeoutMs: 3000))
        let s = freshSettings()
        _ = s.configure(["source": "screen", "screen_interval": 0, "mode": "lights", "link_enabled": false, "wled_restore": false,
                         "wled_devices": "127.0.0.1=ambient:4,2,4,2:bottom_left:cw"])
        s.controlEnabled = false
        let e = Engine(settings: s, platform: "mac", appVersion: "test", defaultName: "Test Mac", wledPort: rx.localPort)
        XCTAssertTrue(e.screenWanted)
        e.start()
        e.nowPlaying(nil) // nothing plays: the screen still drives the lights
        e.screen(ColorExtractor.testPalette(s.extractOptions))
        let got = try XCTUnwrap(rx.receive())
        XCTAssertEqual(got.bytes.first, 4) // DNRGB realtime
        let deadline = Date().addingTimeInterval(3)
        while !e.status.lastResult.contains("Sent screen colours") && Date() < deadline { usleep(20_000) }
        XCTAssertTrue(e.status.lastResult.contains("Sent screen colours"), e.status.lastResult)
        XCTAssertEqual(e.status.source, "screen")
        XCTAssertEqual(e.statusJson()["source_mode"] as? String, "screen")
    }
}
