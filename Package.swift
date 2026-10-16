// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Pnale",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "Pnale", targets: ["Pnale"])],
    targets: [
        .target(name: "MarketCore"),
        .executableTarget(name: "Pnale", dependencies: ["MarketCore"]),
        .testTarget(name: "MarketCoreTests", dependencies: ["MarketCore"])
    ]
)
