// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CLibGit2",
    platforms: [.macOS("11.5")],
    products: [
        .library(name: "CLibGit2", targets: ["CLibGit2Binary", "CLibGit2Linkage"])
    ],
    targets: [
        .binaryTarget(
            name: "CLibGit2Binary",
            path: "Artifacts/LibGit2.xcframework"
        ),
        .target(
            name: "CLibGit2Linkage",
            dependencies: ["CLibGit2Binary"],
            linkerSettings: [
                .linkedFramework("CoreFoundation"),
                .linkedFramework("Security"),
                .linkedLibrary("iconv"),
                .linkedLibrary("z"),
            ]
        ),
        .testTarget(
            name: "CLibGit2Tests",
            dependencies: ["CLibGit2Binary", "CLibGit2Linkage"]
        ),
    ]
)
