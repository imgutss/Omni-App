#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$ROOT_DIR/third_party/cottendns"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-cottendns}"
GO_BIN="${GO:-go}"
EXPECTED_REV="7db7b73658c791cce62300d29647e4d369d6e1e4"

if [[ ! -f "$SOURCE_DIR/go.mod" || ! -d "$SOURCE_DIR/cmd/client" ]]; then
  echo "CottenDNS source is missing under $SOURCE_DIR" >&2
  exit 1
fi
if [[ "$(cat "$SOURCE_DIR/third_party_revision" 2>/dev/null || true)" != "$EXPECTED_REV" ]]; then
  echo "CottenDNS source revision does not match $EXPECTED_REV" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
build_one() {
  local abi="$1" goarch="$2" goarm="${3:-}"
  local target="$OUTPUT_DIR/$abi/libcottendns.so"
  mkdir -p "$(dirname "$target")"
  echo "Building CottenDNS for $abi"
  (
    cd "$SOURCE_DIR"
    export CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" GOTOOLCHAIN=local
    if [[ -n "$goarm" ]]; then export GOARM="$goarm"; fi
    "$GO_BIN" build -trimpath -buildvcs=false \
      -ldflags='-s -w -buildid= -R=16384' \
      -o "$target" ./cmd/client
  )
  chmod 755 "$target"
  test -s "$target"
}

build_one arm64-v8a arm64
build_one armeabi-v7a arm 7
build_one x86_64 amd64
build_one x86 386
sha256sum "$OUTPUT_DIR"/*/libcottendns.so
