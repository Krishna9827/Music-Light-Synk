// swift-tools-version:5.9
import PackageDescription

// Music Light Synk for Apple devices:
//  - MusicLightSynkCore: everything shared by the Mac and iPhone apps (colours, audio, WLED,
//    Home Assistant, device link), a Swift port of the Android app's engine.
//  - MusicLightSynk: the Mac menu-bar app (bundled into "Music Light Synk.app" by
//    scripts/build_mac_app.sh). The iPhone app is in ../ios (an XcodeGen project using the core).
let package = Package(
    name: "MusicLightSynk",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "MusicLightSynkCore", targets: ["MusicLightSynkCore"]),
        .executable(name: "MusicLightSynk", targets: ["MusicLightSynkMac"]),
    ],
    targets: [
        .target(name: "MusicLightSynkCore"),
        .executableTarget(name: "MusicLightSynkMac", dependencies: ["MusicLightSynkCore"]),
        .testTarget(name: "MusicLightSynkCoreTests", dependencies: ["MusicLightSynkCore"]),
    ]
)
