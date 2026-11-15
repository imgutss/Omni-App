#!/usr/bin/env bash
# Builds the sing-box-lx client for every ABI the app ships, as libsingbox.so.
#
# The pinned engine now supplies TUIC v5 only; removed AWG/WireGuard build tags are not compiled. The app spawns it the way it spawns GUTS and lets Xray dial the
# loopback SOCKS port it opens, so it is packaged as a native library and executed from there.
# Like the other Go engines it is built for linux (CGO-free), not android, with 16-KiB segment
# alignment so Android 15's 16-KiB-page devices can load it.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$ROOT_DIR/third_party/sing-box-lx"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-singbox}"
GO_BIN="${GO:-go}"

# Pinned upstream fork tag. Bump this (and the cache key follows the script hash) to update.
SINGBOX_REF="v1.14.4-lx.32"
SINGBOX_REPO="https://github.com/Leadaxe/sing-box-lx.git"
# Minimal tag set for the two protocols Omni rides on this client: TUIC v5 (with_quic) and
# AmneziaWG (with_wireguard + with_awg, gVisor netstack for the endpoint, uTLS for the handshake).
SINGBOX_TAGS="with_quic,with_utls"

if [[ ! -f "$SOURCE_DIR/go.mod" ]]; then
  echo "Fetching sing-box-lx $SINGBOX_REF"
  mkdir -p "$(dirname "$SOURCE_DIR")"
  git clone --quiet --depth 1 --branch "$SINGBOX_REF" "$SINGBOX_REPO" "$SOURCE_DIR"
  git -C "$SOURCE_DIR" submodule update --init --depth 1 \
    submodules/wireguard-go submodules/sing-tun submodules/gvisor submodules/utls
fi

mkdir -p "$OUTPUT_DIR"

build_one() {
  local abi="$1"
  local goarch="$2"
  local goarm="${3:-}"
  local target="$OUTPUT_DIR/$abi/libsingbox.so"
  mkdir -p "$(dirname "$target")"
  echo "Building sing-box-lx for $abi"
  (
    cd "$SOURCE_DIR"
    export CGO_ENABLED=0 GOOS=linux GOARCH="$goarch"
    if [[ -n "$goarm" ]]; then export GOARM="$goarm"; else unset GOARM || true; fi
    "$GO_BIN" build -trimpath -buildvcs=false \
      -tags "$SINGBOX_TAGS" \
      -ldflags='-s -w -buildid= -checklinkname=0 -R=16384' \
      -o "$target" ./cmd/sing-box
  )
  chmod 755 "$target"
  test -s "$target"
}

build_one arm64-v8a arm64
build_one armeabi-v7a arm 7
build_one x86_64 amd64
build_one x86 386

sha256sum "$OUTPUT_DIR"/*/libsingbox.so
