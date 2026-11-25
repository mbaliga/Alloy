// swift-tools-version:5.9
// SPDX-License-Identifier: AGPL-3.0-only
import PackageDescription

// AlloyCore: the portable, dependency-free, Foundation-only logic core of the
// Alloy iOS companion. It is a faithful Swift port of the pure Java logic in
// android/app/src/main/java/com/mbaliga/alloy (G-code safety preflight, plate
// arrangement, STL reading). It contains NO engine-derived (libslic3r) code.
let package = Package(
    name: "AlloyCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "AlloyCore", targets: ["AlloyCore"])],
    targets: [
        .target(name: "AlloyCore"),
        .testTarget(name: "AlloyCoreTests", dependencies: ["AlloyCore"]),
    ]
)
