# CLibGit2 package

This local Swift package exposes libgit2 1.9.7 to the macOS app as
`import CLibGit2`. The checked-in XCFramework contains a static universal
(`arm64` + `x86_64`) library built for the app's macOS 11.5 deployment target.

The binary is intentionally built with HTTPS through Apple's SecureTransport
and hashing through CommonCrypto. It has no OpenSSL or Homebrew runtime
dependency. SSH, GSSAPI, and NTLM are disabled; authenticated GitHub HTTPS
uses libgit2's credential callback surface instead.

`Package.swift` contains only supported SwiftPM declarations and safe linker
settings. In particular it has no `unsafeFlags`, so an Xcode target can depend
on the `CLibGit2` product without SwiftPM's unsafe-flags product rejection.

## Rebuild

Install CMake, then run from the repository root:

```bash
brew install cmake
bash scripts/build-libgit2-xcframework.sh
swift test --package-path Packages/CLibGit2
```

The build script downloads the pinned GitHub source archive, verifies its
SHA-256, builds both macOS architectures, checks the resulting symbol
dependencies, and replaces the checked-in XCFramework only after those checks
pass. Updating libgit2 requires updating the version and archive checksum in
the script and the expected version in the package test.

Licenses for libgit2 and the bundled llhttp and PCRE2 sources are retained in
`Licenses/`. The libgit2 license is GPLv2 with its explicit linking exception.

## HTTPS smoke test

Exercise a real HTTPS clone through libgit2 (the smoke program calls
`git_clone` directly and never invokes `/usr/bin/git`):

```bash
bash scripts/test-libgit2-https.sh
```
