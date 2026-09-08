#!/bin/bash

set -euo pipefail

libgit2_version="1.9.7"
libgit2_archive_sha256="1a4fbe7589e814777ae76b64734ad80f4ecad22cd33a22682a2aaea4ae5375e7"
macos_deployment_target="11.5"

for required_command in cmake curl xcodebuild xcrun; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Missing required command: $required_command" >&2
    exit 1
  fi
done

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
package_root="$repository_root/Packages/CLibGit2"
artifact_path="$package_root/Artifacts/LibGit2.xcframework"
temporary_directory="${TMPDIR:-/tmp}"
temporary_directory="${temporary_directory%/}"
libgit2_build_root="$(mktemp -d "$temporary_directory/miaoyan-libgit2.XXXXXX")"
trap 'rm -rf "$libgit2_build_root"' EXIT

archive_path="$libgit2_build_root/libgit2-$libgit2_version.tar.gz"
source_path="$libgit2_build_root/libgit2-$libgit2_version"

curl --fail --location --silent --show-error \
  "https://github.com/libgit2/libgit2/archive/refs/tags/v$libgit2_version.tar.gz" \
  --output "$archive_path"

actual_sha256="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
if [ "$actual_sha256" != "$libgit2_archive_sha256" ]; then
  echo "libgit2 archive checksum mismatch" >&2
  echo "expected: $libgit2_archive_sha256" >&2
  echo "actual:   $actual_sha256" >&2
  exit 1
fi

tar -xzf "$archive_path" -C "$libgit2_build_root"

common_cmake_arguments=(
  -G "Unix Makefiles"
  -DCMAKE_BUILD_TYPE=Release
  "-DCMAKE_OSX_DEPLOYMENT_TARGET=$macos_deployment_target"
  -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=TRUE
  "-DCMAKE_C_FLAGS=-ffile-prefix-map=$source_path=libgit2-$libgit2_version -fmacro-prefix-map=$source_path=libgit2-$libgit2_version -fdebug-prefix-map=$source_path=libgit2-$libgit2_version"
  -DBUILD_SHARED_LIBS=OFF
  -DBUILD_TESTS=OFF
  -DBUILD_CLI=OFF
  -DBUILD_EXAMPLES=OFF
  -DUSE_HTTPS=SecureTransport
  -DUSE_SSH=OFF
  -DUSE_GSSAPI=OFF
  -DUSE_NTLMCLIENT=OFF
  -DREGEX_BACKEND=builtin
  -DUSE_BUNDLED_ZLIB=OFF
  -DENABLE_REPRODUCIBLE_BUILDS=OFF
)

for architecture in arm64 x86_64; do
  architecture_build_path="$libgit2_build_root/build-$architecture"
  architecture_install_path="$libgit2_build_root/install-$architecture"

  cmake -S "$source_path" -B "$architecture_build_path" \
    "${common_cmake_arguments[@]}" \
    "-DCMAKE_OSX_ARCHITECTURES=$architecture" \
    "-DCMAKE_INSTALL_PREFIX=$architecture_install_path"
  cmake --build "$architecture_build_path" --target install --parallel
done

headers_path="$libgit2_build_root/install-arm64/include"
cat > "$headers_path/module.modulemap" <<'MODULEMAP'
module CLibGit2 {
    header "git2.h"
    export *
    link "git2"
}
MODULEMAP

universal_library_path="$libgit2_build_root/libgit2.a"
xcrun lipo -create \
  "$libgit2_build_root/install-arm64/lib/libgit2.a" \
  "$libgit2_build_root/install-x86_64/lib/libgit2.a" \
  -output "$universal_library_path"
xcrun lipo "$universal_library_path" -verify_arch arm64 x86_64

undefined_symbols="$(xcrun nm -u "$universal_library_path")"
if ! grep -q '_SSLCreateContext' <<< "$undefined_symbols" || \
   ! grep -q '_SecTrustEvaluate' <<< "$undefined_symbols" || \
   ! grep -q '_CC_SHA256_Init' <<< "$undefined_symbols"; then
  echo "SecureTransport/CommonCrypto symbols are missing" >&2
  exit 1
fi
if grep -Eq '_(BIO|EVP|OPENSSL|X509)_' <<< "$undefined_symbols"; then
  echo "Unexpected OpenSSL symbol dependency" >&2
  exit 1
fi
if strings "$universal_library_path" | grep -F "$libgit2_build_root" >/dev/null; then
  echo "Temporary build paths leaked into the static library" >&2
  exit 1
fi

generated_artifact_path="$libgit2_build_root/LibGit2.xcframework"
xcodebuild -create-xcframework \
  -library "$universal_library_path" \
  -headers "$headers_path" \
  -output "$generated_artifact_path"

case "$artifact_path" in
  "$repository_root"/Packages/CLibGit2/Artifacts/LibGit2.xcframework) ;;
  *)
    echo "Refusing to replace unexpected artifact path: $artifact_path" >&2
    exit 1
    ;;
esac

rm -rf "$artifact_path"
mkdir -p "$(dirname "$artifact_path")"
cp -R "$generated_artifact_path" "$artifact_path"

cp "$source_path/COPYING" "$package_root/Licenses/libgit2-COPYING"
cp "$source_path/deps/llhttp/LICENSE-MIT" "$package_root/Licenses/llhttp-LICENSE-MIT"
cp "$source_path/deps/pcre2/LICENCE.md" "$package_root/Licenses/pcre2-LICENCE.md"

echo "Built $artifact_path"
echo "libgit2 $libgit2_version; macOS $macos_deployment_target; arm64 + x86_64"
echo "HTTPS: SecureTransport; SSH: disabled; OpenSSL: disabled"
