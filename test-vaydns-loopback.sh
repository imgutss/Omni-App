#!/usr/bin/env bash
# Build and exercise the actual VayDNS server and Omni client over UDP DNS.
set -euo pipefail
cd "$(dirname "$0")"
GO="${GO:-/home/user/.cache/toolchains/go/bin/go}"
if [[ ! -x "$GO" ]]; then GO="$(command -v go)"; fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
(
  cd third_party/vaydns
  "$GO" build -buildvcs=false -o "$TMP/vaydns-server" ./vaydns-server
  "$GO" build -buildvcs=false -o "$TMP/omni-client" ./cmd/omni-client
)
"$TMP/vaydns-server" -gen-key -privkey-file "$TMP/server.key" -pubkey-file "$TMP/server.pub"
PUBLIC_KEY="$(tr -d '[:space:]' < "$TMP/server.pub")"
[[ "$PUBLIC_KEY" =~ ^[0-9a-fA-F]{64}$ ]]
python3 third_party/vaydns/e2e/omni_loopback.py \
  "$TMP/vaydns-server" "$TMP/omni-client" "$TMP/server.key" "$PUBLIC_KEY"
