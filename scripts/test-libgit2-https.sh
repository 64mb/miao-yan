#!/bin/bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
xcframework_slice="$repository_root/Packages/CLibGit2/Artifacts/LibGit2.xcframework/macos-arm64_x86_64"
smoke_source="$repository_root/Packages/CLibGit2/Tests/HTTPSCloneSmoke/main.c"
temporary_directory="${TMPDIR:-/tmp}"
temporary_directory="${temporary_directory%/}"
smoke_root="$(mktemp -d "$temporary_directory/miaoyan-libgit2-https.XXXXXX")"
trap 'rm -rf "$smoke_root"' EXIT

smoke_binary="$smoke_root/https-clone-smoke"
clone_destination="$smoke_root/repository"
clone_url="${1:-https://github.com/libgit2/TestGitRepository.git}"

xcrun clang \
  -I "$xcframework_slice/Headers" \
  "$smoke_source" \
  "$xcframework_slice/libgit2.a" \
  -framework CoreFoundation \
  -framework Security \
  -liconv \
  -lz \
  -o "$smoke_binary"

"$smoke_binary" "$clone_url" "$clone_destination"
