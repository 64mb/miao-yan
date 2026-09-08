# libgit2 packaging verification

Verified on 2026-09-07 with Xcode 26.6 (17F113), Apple Swift 6.3.3,
AppleClang 21.0.0, and CMake 4.4.3.

## Result

MiaoYan can consume modern libgit2 through a local Swift package without a
SwiftPM source target that contains unsafe build flags. The package checks in
a static universal XCFramework and exposes it as the `CLibGit2` product. Xcode
accepts the product in both Debug and AppStore configurations.

The artifact is libgit2 1.9.7, the latest stable upstream release at the time
of verification. It is built for macOS 11.5 and both `arm64` and `x86_64`.
HTTPS uses Apple's SecureTransport and SHA-256 uses CommonCrypto; there is no
OpenSSL runtime dependency. SSH, GSSAPI, and NTLM are disabled.

This change provides packaging and linkage only. It does not add Git UI or a
Git feature implementation to MiaoYan.

## Files

- `MiaoYan.xcodeproj/project.pbxproj` registers the local package and links the
  `CLibGit2` product to the macOS `MiaoYan` target.
- `Packages/CLibGit2/Package.swift` declares the local binary target and only
  safe system linker settings (`Security`, `CoreFoundation`, `iconv`, `z`).
- `Packages/CLibGit2/Artifacts/LibGit2.xcframework` is the checked-in static
  universal artifact and public headers/module map.
- `Packages/CLibGit2/Sources/CLibGit2Linkage/CLibGit2Linkage.swift` supplies a
  small Swift-visible version/feature probe and ensures required system
  libraries propagate to consumers.
- `Packages/CLibGit2/Tests/CLibGit2Tests/CLibGit2Tests.swift` verifies version,
  HTTPS/SSH feature bits, and runtime initialization.
- `Packages/CLibGit2/Tests/HTTPSCloneSmoke/main.c` calls `git_clone` directly.
- `Packages/CLibGit2/Licenses/` retains the upstream libgit2, llhttp, and PCRE2
  license texts.
- `scripts/build-libgit2-xcframework.sh` downloads and checksum-verifies the
  upstream archive, builds both architectures, audits symbols and temporary
  paths, and replaces the artifact only after checks pass.
- `scripts/test-libgit2-https.sh` compiles the tracked smoke program and clones
  a public repository over HTTPS without invoking `/usr/bin/git`.

## Pinned upstream input and build

Upstream tag: `v1.9.7` (tag commit
`49e408b3208bc3093757a1c2db938d3590f3f412`). The official GitHub source
archive SHA-256 is:

```text
1a4fbe7589e814777ae76b64734ad80f4ecad22cd33a22682a2aaea4ae5375e7
```

After installing CMake, regenerate the artifact from the repository root:

```bash
bash scripts/build-libgit2-xcframework.sh
```

The essential CMake choices made by the script are:

```text
BUILD_SHARED_LIBS=OFF
BUILD_TESTS=OFF
BUILD_CLI=OFF
BUILD_EXAMPLES=OFF
USE_HTTPS=SecureTransport
USE_SSH=OFF
USE_GSSAPI=OFF
USE_NTLMCLIENT=OFF
REGEX_BACKEND=builtin
USE_BUNDLED_ZLIB=OFF
CMAKE_DISABLE_FIND_PACKAGE_OpenSSL=TRUE
CMAKE_OSX_DEPLOYMENT_TARGET=11.5
```

CMake reported `HTTPS, using SecureTransport` and `SHA256, using
CommonCrypto`. The artifact audit found `_SSLCreateContext`,
`_SecTrustEvaluate`, and `_CC_SHA256_Init`, and found no `_BIO_`, `_EVP_`,
`_OPENSSL_`, or `_X509_` dependency. `lipo` reports `x86_64 arm64`.

The generated archive currently has SHA-256:

```text
7dddeed271aa999d71c4b5b8c39ddfaf69feb12e0e1d9371205199aa0809e95b
```

## Commands proved in this worktree

Package tests:

```bash
SWIFTPM_MODULECACHE_OVERRIDE=/tmp/miaoyan-clibgit2-module-cache \
CLANG_MODULE_CACHE_PATH=/tmp/miaoyan-clibgit2-module-cache \
swift test --package-path Packages/CLibGit2 \
  --scratch-path /tmp/miaoyan-clibgit2-tests
```

Result: two tests passed, confirming libgit2 1.9.7, HTTPS enabled, SSH
disabled, and successful `git_libgit2_init`/`git_libgit2_shutdown`.

Direct HTTPS transport smoke:

```bash
bash scripts/test-libgit2-https.sh
```

Result:

```text
libgit2 1.9.7: HTTPS clone succeeded
```

The executable produced by that script links the checked-in static archive
and calls `git_clone` directly. Neither the program nor the package invokes a
Git CLI.

MiaoYan Debug build:

```bash
xcodebuild -quiet \
  -project MiaoYan.xcodeproj \
  -scheme MiaoYan \
  -configuration Debug \
  -derivedDataPath /tmp/miaoyan-libgit2-debug \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Result: succeeded. The product contains the libgit2 version/feature symbols;
`otool -L` shows `Security.framework` and `CoreFoundation.framework`, with no
libssl or libcrypto dylib.

Universal AppStore configuration build:

```bash
xcodebuild -quiet \
  -project MiaoYan.xcodeproj \
  -scheme MiaoYan \
  -configuration AppStore \
  -derivedDataPath /tmp/miaoyan-libgit2-appstore-universal \
  CODE_SIGNING_ALLOWED=NO \
  ONLY_ACTIVE_ARCH=NO \
  'ARCHS=arm64 x86_64' \
  build
```

Result: succeeded. `lipo` reports `x86_64 arm64` for the built app executable,
and `otool -L` again shows only Apple system Security/CoreFoundation for the
TLS-related linkage. `MiaoYan-AppStore.entitlements` already grants
`com.apple.security.network.client`, so the sandbox entitlement required for
outbound HTTPS is present.

## Why this avoids SwiftPM unsafe-flags rejection

SwiftPM rejects products whose transitive source targets apply
`unsafeFlags`. Here CMake runs only when a maintainer regenerates the binary;
it is not part of SwiftPM's build graph. `Package.swift` consumes the result
with `.binaryTarget` and uses only `.linkedFramework`/`.linkedLibrary`, which
are supported safe declarations. The clean Xcode product-resolution and app
builds are the practical acceptance proof.

## Risks and follow-up constraints

- The 5.8 MB XCFramework is checked into Git. Updating libgit2 requires
  changing the pinned version, source checksum, test expectation, rebuilding,
  and repeating all audits.
- SSH URLs are intentionally unsupported. Add a separately audited libssh2
  build only if SSH becomes a product requirement; do not silently add a
  Homebrew dependency.
- Authentication is not implemented by this packaging layer. Production code
  must provide libgit2 credential and certificate callbacks and should avoid
  credential-helper APIs if the no-subprocess invariant must be absolute.
- libgit2 is GPLv2 with its linking exception, while bundled llhttp and PCRE2
  have their own notices. The texts are retained in the package, but release
  owners should confirm the app's shipped acknowledgements/source-offer
  process with counsel.
- libgit2's `ENABLE_REPRODUCIBLE_BUILDS=ON` uses GNU `ar -D` syntax that Apple
  `ar` rejects. The script therefore leaves it off, pins and verifies source
  input, remaps temporary paths, and rejects path leaks, but byte-for-byte
  output may still change with Xcode/CMake versions.
- The project's AppStore configuration has `ONLY_ACTIVE_ARCH=YES`; the command
  above overrides it to prove both slices. The signed archive path in
  `scripts/build-appstore.sh` still requires maintainer credentials and must be
  checked as part of the normal App Store release process.
- An unrelated pre-existing AppStore concern was observed: the unsigned local
  configuration build still contained `Sparkle.framework` even though the
  project has a `Strip Sparkle for AppStore` script phase. This integration did
  not modify that release pipeline, but the signed archive should be inspected
  before submission.
