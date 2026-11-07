#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_DIR="$ROOT_DIR/third_party/slipstream-rust"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-slipstream}"
EXPECTED_REV="60a5e38c7414b35e4c2fddf8d4d89a4341470c6a"
API_LEVEL=24
CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT_DIR/target/slipstream}"
export CARGO_TARGET_DIR

if [[ ! -d "${NDK_HOME:-}" ]]; then
  echo "Android NDK: NDK_HOME not found" >&2
  exit 1
fi
if [[ "$(cat "$CORE_DIR/third_party_revision" 2>/dev/null || true)" != "$EXPECTED_REV" ]]; then
  echo "Slipstream source revision does not match $EXPECTED_REV" >&2
  exit 1
fi
if [[ ! -f "$CORE_DIR/Cargo.lock" || ! -d "$CORE_DIR/vendor/picoquic" ]]; then
  echo "Slipstream/picoquic sources are incomplete" >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) HOST_CANDIDATES="darwin-arm64 darwin-x86_64" ;;
  *) HOST_CANDIDATES="linux-x86_64 linux-arm64" ;;
esac
TOOLCHAIN=""
for candidate in $HOST_CANDIDATES; do
  if [[ -d "$NDK_HOME/toolchains/llvm/prebuilt/$candidate/bin" ]]; then
    TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$candidate/bin"
    break
  fi
done
if [[ -z "$TOOLCHAIN" ]]; then
  echo "No NDK LLVM toolchain found" >&2
  exit 1
fi

triple_for() {
  case "$1" in
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    arm64-v8a) echo "aarch64-linux-android" ;;
    x86) echo "i686-linux-android" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *) echo "unsupported ABI $1" >&2; exit 1 ;;
  esac
}
clang_for() {
  case "$1" in
    armeabi-v7a) echo "armv7a-linux-androideabi$API_LEVEL" ;;
    *) echo "$(triple_for "$1")$API_LEVEL" ;;
  esac
}

mkdir -p "$OUTPUT_DIR"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  triple="$(triple_for "$abi")"
  clang_target="$(clang_for "$abi")"
  env_triple="$(echo "$triple" | tr 'a-z-' 'A-Z_')"
  under_triple="$(echo "$triple" | tr '-' '_')"
  rustup target add "$triple" >/dev/null 2>&1 || true
  echo "[slipstream] building $abi ($triple)"
  (
    unset OPENSSL_DIR OPENSSL_LIB_DIR OPENSSL_INCLUDE_DIR OPENSSL_ROOT_DIR
    export ANDROID_NDK_HOME="$NDK_HOME"
    export ANDROID_NDK_ROOT="$NDK_HOME"
    export ANDROID_ABI="$abi"
    export ANDROID_PLATFORM="android-$API_LEVEL"
    export BUILD_TYPE=Release
    export PICOQUIC_AUTO_BUILD=1
    export PICOQUIC_FETCH_PTLS=ON
    export PICOQUIC_BUILD_DIR="$CARGO_TARGET_DIR/picoquic-build/$abi"
    # Never let host pkg-config metadata (for example desktop Brotli) leak into Android.
    export PKG_CONFIG_PATH=""
    export PKG_CONFIG_LIBDIR="$CARGO_TARGET_DIR/pkgconfig-empty"
    mkdir -p "$PKG_CONFIG_LIBDIR"
    export RUST_ANDROID_GRADLE_CC="$TOOLCHAIN/$clang_target-clang"
    export RUST_ANDROID_GRADLE_AR="$TOOLCHAIN/llvm-ar"
    export CC="$TOOLCHAIN/$clang_target-clang"
    export CXX="$TOOLCHAIN/$clang_target-clang++"
    export AR="$TOOLCHAIN/llvm-ar"
    export RANLIB="$TOOLCHAIN/llvm-ranlib"
    export "CARGO_TARGET_${env_triple}_LINKER=$TOOLCHAIN/$clang_target-clang"
    export "CC_${under_triple}=$TOOLCHAIN/clang"
    export "CXX_${under_triple}=$TOOLCHAIN/clang++"
    export "CFLAGS_${under_triple}=--target=$clang_target"
    export "CXXFLAGS_${under_triple}=--target=$clang_target"
    export "AR_${under_triple}=$TOOLCHAIN/llvm-ar"
    cd "$CORE_DIR"
    cargo build --release --locked \
      --manifest-path "$CORE_DIR/Cargo.toml" \
      --target "$triple" \
      -p slipstream-client --bin slipstream-client \
      --features openssl-vendored,picoquic-minimal-build
  )
  produced="$CARGO_TARGET_DIR/$triple/release/slipstream-client"
  test -s "$produced"
  expected_machine="$(case "$abi" in
    arm64-v8a) echo 'AArch64' ;;
    armeabi-v7a) echo 'ARM' ;;
    x86) echo 'Intel 80386' ;;
    x86_64) echo 'Advanced Micro Devices X86-64' ;;
  esac)"
  header="$("$TOOLCHAIN/llvm-readelf" -h "$produced")"
  if ! grep -Eq 'Type:[[:space:]]+DYN' <<<"$header" || ! grep -Eq "Machine:[[:space:]]+$expected_machine" <<<"$header"; then
    echo "Slipstream $abi has the wrong ELF type or architecture" >&2
    exit 1
  fi
  alignments="$("$TOOLCHAIN/llvm-readelf" -lW "$produced" | awk '$1 == "LOAD" { print $NF }')"
  if [[ -z "$alignments" ]] || grep -Fvx '0x4000' <<<"$alignments" >/dev/null; then
    echo "Slipstream $abi does not use 16-KB LOAD alignment" >&2
    exit 1
  fi
  needed="$("$TOOLCHAIN/llvm-readelf" --dynamic-table "$produced" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
  echo "[slipstream] $abi needs: $needed"
  if [[ -z "$needed" ]] || grep -Evx 'libdl.so|libc.so' <<<"$needed" >/dev/null; then
    echo "Slipstream $abi has an unexpected dynamic dependency" >&2
    exit 1
  fi
  mkdir -p "$OUTPUT_DIR/$abi"
  cp "$produced" "$OUTPUT_DIR/$abi/libslipstream.so"
  chmod 755 "$OUTPUT_DIR/$abi/libslipstream.so"
done
sha256sum "$OUTPUT_DIR"/*/libslipstream.so
