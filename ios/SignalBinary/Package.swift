// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "SignalBinary",
    platforms: [.iOS(.v17)],
    products: [.library(name: "SignalBinary", targets: ["LibSignalFfi"])],
    targets: [.binaryTarget(
        name: "LibSignalFfi",
        url: "https://build-artifacts.signal.org/libraries/libsignal-client-ios-build-v0.108.6.xcframework.zip",
        checksum: "6af2d98f46530109503e4437e1189b26a6eab7119c724d2f8ca2607b2f33d56a"
    )]
)
