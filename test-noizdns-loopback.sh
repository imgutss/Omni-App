#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GO_BIN="${GO:-go}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/omni-noizdns-loopback.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
export GOTOOLCHAIN=local CGO_ENABLED=0

build() {
  local source="$1" package="$2" output="$3"
  (cd "$source" && "$GO_BIN" build -trimpath -buildvcs=false -o "$output" "$package")
}

build "$ROOT_DIR/third_party/dnstt" ./dnstt-server "$WORK_DIR/dnstt-server"
build "$ROOT_DIR/third_party/slipnet/noizdns" ./cmd/noizdns-server "$WORK_DIR/noizdns-server"
build "$ROOT_DIR/third_party/slipnet/noizdns" ./cmd/omni-client "$WORK_DIR/omni-client"
"$WORK_DIR/dnstt-server" -gen-key -privkey-file "$WORK_DIR/server.key" -pubkey-file "$WORK_DIR/server.pub" >/dev/null

export OMNI_NOIZDNS_CLIENT="$WORK_DIR/omni-client"
export OMNI_DNSTT_SERVER="$WORK_DIR/dnstt-server"
export OMNI_NOIZDNS_SERVER="$WORK_DIR/noizdns-server"
export OMNI_LOOP_KEY="$WORK_DIR/server.key"
export OMNI_LOOP_PUB="$WORK_DIR/server.pub"
export OMNI_LOOP_LOG_DIR="$WORK_DIR"

# Each case exchanges a random payload through the actual DNS tunnel and two
# real SOCKS5 endpoints. No Internet service or Android VPN claim is involved.
python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
NOIZ=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
NOIZ=1 NONAUTH=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
NOIZ=1 NONAUTH=1 STEALTH=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
# A mixed SlipNet resolver list must run two independently tuned real tunnels
# behind the same local SOCKS5 endpoint, not collapse to one global mode.
NOIZ=1 MIXED=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
# SSH uses the raw DNSTT stream, including with one resolver; these cases
# catch accidentally falling back to the mobile client's SOCKS5 listener.
NOIZ=1 RAW=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
NOIZ=1 RAW=1 MIXED=1 python3 "$ROOT_DIR/tools/noizdns_loopback_test.py"
echo "All NoizDNS loopback cases passed; logs were in $WORK_DIR"
