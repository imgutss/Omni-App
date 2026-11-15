#!/usr/bin/env bash
# Builds the official ShadowQUIC client for every ABI the app ships, as libshadowquic.so.
#
# The app spawns it the way it spawns GUTS and lets Xray dial the loopback SOCKS port it opens.
# It is built with the minimal feature set the client needs (the quinn-based shadowquic transport
# and the ring crypto backend) — no jemalloc, Lua plugin, TUN or REST API — which is the same
# minimal build upstream CI verifies, and keeps the cross-compile small and reliable. Segments are
# aligned to 16 KiB for Android 15's 16-KiB-page devices.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$ROOT_DIR/third_party/shadowquic"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-shadowquic}"

SHADOWQUIC_REF="v0.4.8"
SHADOWQUIC_REPO="https://github.com/spongebob888/shadowquic.git"

if [[ ! -d "$NDK_HOME" ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME" >&2
  exit 1
fi
if ! command -v cargo >/dev/null 2>&1; then
  echo "Rust: cargo not found. install the Rust toolchain" >&2
  exit 1
fi

if [[ ! -f "$SOURCE_DIR/Cargo.toml" ]]; then
  echo "Fetching shadowquic $SHADOWQUIC_REF"
  mkdir -p "$(dirname "$SOURCE_DIR")"
  git clone --quiet --depth 1 --branch "$SHADOWQUIC_REF" "$SHADOWQUIC_REPO" "$SOURCE_DIR"
fi

# Omni patch: teach the protect client the abstract namespace ('@' prefix), matching the abstract
# protect socket the app serves (filesystem sockets can be refused by SELinux on some devices).
PROTECT_SRC="$SOURCE_DIR/shadowquic/src/utils/protect_socket.rs"
PROTECT_PATCH="$ROOT_DIR/patches/shadowquic/protect_socket.rs"
if [[ -f "$PROTECT_PATCH" ]]; then
  if [[ ! -f "$PROTECT_SRC" ]]; then
    echo "shadowquic protect_socket.rs not found at $PROTECT_SRC (upstream layout changed?)" >&2
    exit 1
  fi
  cp "$PROTECT_PATCH" "$PROTECT_SRC"
  echo "[shadowquic] applied abstract-namespace protect patch"
fi

ABIS="armeabi-v7a arm64-v8a x86_64 x86"
API_LEVEL=29

triple_for () {
  case "$1" in
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    arm64-v8a)   echo "aarch64-linux-android" ;;
    x86)         echo "i686-linux-android" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    *) echo "unsupported abi: $1" >&2; exit 1 ;;
  esac
}

clang_for () {
  case "$1" in
    armeabi-v7a) echo "armv7a-linux-androideabi$API_LEVEL" ;;
    *)           echo "$(triple_for "$1")$API_LEVEL" ;;
  esac
}

case "$(uname -s)" in
  Darwin) HOST_TAG_CANDIDATES="darwin-arm64 darwin-x86_64" ;;
  *)      HOST_TAG_CANDIDATES="linux-x86_64 linux-arm64" ;;
esac

TOOLCHAIN=""
for candidate in $HOST_TAG_CANDIDATES; do
  if [[ -d "$NDK_HOME/toolchains/llvm/prebuilt/$candidate/bin" ]]; then
    TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$candidate/bin"
    break
  fi
done
if [[ -z "$TOOLCHAIN" ]]; then
  echo "no llvm toolchain under $NDK_HOME/toolchains/llvm/prebuilt" >&2
  exit 1
fi
SYSROOT="$(cd "$TOOLCHAIN/../sysroot" && pwd)"

mkdir -p "$OUTPUT_DIR"

for abi in $ABIS; do
  triple="$(triple_for "$abi")"
  clang_target="$(clang_for "$abi")"
  env_triple="$(echo "$triple" | tr 'a-z-' 'A-Z_')"
  under_triple="$(echo "$triple" | tr '-' '_')"

  if [[ ! -x "$TOOLCHAIN/$clang_target-clang" ]]; then
    echo "NDK clang not found: $TOOLCHAIN/$clang_target-clang" >&2
    exit 1
  fi
  rustup target add "$triple" >/dev/null 2>&1 || true

  echo "[shadowquic] building for $abi ($triple)"
  env \
    ANDROID_NDK_HOME="$NDK_HOME" \
    ANDROID_NDK_ROOT="$NDK_HOME" \
    "CARGO_TARGET_${env_triple}_LINKER=$TOOLCHAIN/$clang_target-clang" \
    "CARGO_TARGET_${env_triple}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384" \
    "CC_${under_triple}=$TOOLCHAIN/clang" \
    "CXX_${under_triple}=$TOOLCHAIN/clang++" \
    "CFLAGS_${under_triple}=--target=$clang_target --sysroot=$SYSROOT" \
    "CXXFLAGS_${under_triple}=--target=$clang_target --sysroot=$SYSROOT" \
    "AR_${under_triple}=$TOOLCHAIN/llvm-ar" \
    "BINDGEN_EXTRA_CLANG_ARGS_${under_triple}=--target=$clang_target --sysroot=$SYSROOT" \
    cargo build --release --locked --no-default-features --features shadowquic-quinn,ring \
      --manifest-path "$SOURCE_DIR/Cargo.toml" -p shadowquic --target "$triple" --bin shadowquic

  produced="$SOURCE_DIR/target/$triple/release/shadowquic"
  if [[ ! -f "$produced" ]]; then
    echo "shadowquic binary missing for $abi: $produced" >&2
    exit 1
  fi
  mkdir -p "$OUTPUT_DIR/$abi"
  cp "$produced" "$OUTPUT_DIR/$abi/libshadowquic.so"
  chmod 755 "$OUTPUT_DIR/$abi/libshadowquic.so"
done

sha256sum "$OUTPUT_DIR"/*/libshadowquic.so
