#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="$ROOT_DIR/third_party/slipnet/noizdns"
DNSTT_SOURCE_DIR="$ROOT_DIR/third_party/slipnet/dnstt"
OUTPUT_DIR="${1:-$ROOT_DIR/libs-noizdns}"
GO_BIN="${GO:-go}"
EXPECTED_REV="7289a56185e174d4cbf2ffaea55523db8f1fd5af"
EXPECTED_DNSTT_REV="51bdba3948d44b3175bc88ea40b2116bb9daff07"

if [[ ! -f "$SOURCE_DIR/go.mod" || ! -d "$SOURCE_DIR/cmd/omni-client" ]]; then
  echo "NoizDNS source is missing under $SOURCE_DIR" >&2
  exit 1
fi
if [[ "$(cat "$SOURCE_DIR/third_party_revision" 2>/dev/null || true)" != "$EXPECTED_REV" ]]; then
  echo "NoizDNS source revision does not match $EXPECTED_REV" >&2
  exit 1
fi
if [[ "$(cat "$DNSTT_SOURCE_DIR/third_party_revision" 2>/dev/null || true)" != "$EXPECTED_DNSTT_REV" ]]; then
  echo "SlipNet DNSTT dependency revision does not match $EXPECTED_DNSTT_REV" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
build_one() {
  local abi="$1"
  local goarch="$2"
  local goarm="${3:-}"
  local target="$OUTPUT_DIR/$abi/libnoizdns.so"
  mkdir -p "$(dirname "$target")"
  echo "Building NoizDNS for $abi"
  (
    cd "$SOURCE_DIR"
    export CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" GOTOOLCHAIN=local
    if [[ -n "$goarm" ]]; then export GOARM="$goarm"; fi
    "$GO_BIN" build -trimpath -buildvcs=false \
      -ldflags='-s -w -buildid=' \
      -o "$target" ./cmd/omni-client
  )
  chmod 755 "$target"
  test -s "$target"
}

build_one arm64-v8a arm64
build_one armeabi-v7a arm 7
build_one x86_64 amd64
build_one x86 386
sha256sum "$OUTPUT_DIR"/*/libnoizdns.so
