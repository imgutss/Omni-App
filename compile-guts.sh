#!/usr/bin/env bash
# Builds the guts client for every ABI the app ships, as libguts.so.
#
# The app spawns it the way it spawns the DNS tunnel engines and lets Xray dial the SOCKS port it
# opens, so it is packaged as a native library and executed from there. Like those engines it is
# built for linux and not for android: Android refuses to run a Go binary that is not linked against
# bionic from an app directory, but executes the arm64 binary in the native library directory, which
# is what the other engines here already rely on.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$ROOT_DIR/guts"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-guts}"
GO_BIN="${GO:-go}"

if [[ ! -f "$SOURCE_DIR/go.mod" ]]; then
  echo "guts source is missing under $SOURCE_DIR" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

build_one() {
  local abi="$1"
  local goarch="$2"
  local goarm="${3:-}"
  local target="$OUTPUT_DIR/$abi/libguts.so"
  mkdir -p "$(dirname "$target")"
  echo "Building guts for $abi"
  (
    cd "$SOURCE_DIR"
    export CGO_ENABLED=0 GOOS=linux GOARCH="$goarch"
    if [[ -n "$goarm" ]]; then export GOARM="$goarm"; else unset GOARM || true; fi
    # -R=16384 asks Go's own linker to align every segment to 16 KiB: Android 15 runs on devices with
    # 16 KiB pages, and their kernel will not load a program whose segments assume 4 KiB. The other
    # engines here are built the same way, and the build audit in CI refuses one that is not.
    "$GO_BIN" build -trimpath -buildvcs=false \
      -ldflags='-s -w -buildid= -R=16384' \
      -o "$target" ./cmd/gutsclient
  )
  chmod 755 "$target"
  test -s "$target"
}

build_one arm64-v8a arm64
build_one armeabi-v7a arm 7
build_one x86_64 amd64
build_one x86 386

sha256sum "$OUTPUT_DIR"/*/libguts.so
