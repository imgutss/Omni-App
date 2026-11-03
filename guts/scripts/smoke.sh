#!/usr/bin/env bash
# End-to-end smoke test of guts without Google: an exit node, a door emulator that forwards like the
# Apps Script does, and the SOCKS5 client in between. Downloads a 2 MiB file and compares checksums.
set -euo pipefail
cd "$(dirname "$0")/.."

TMP=$(mktemp -d)
KEY="smoke-$$"
EXIT_PORT=${EXIT_PORT:-18080}
DOOR_PORT=${DOOR_PORT:-18090}
SOCKS_PORT=${SOCKS_PORT:-11080}
WEB_PORT=${WEB_PORT:-18081}

cleanup() { kill $EXIT_PID $DOOR_PID $CLIENT_PID $WEB_PID 2>/dev/null || true; rm -rf "$TMP"; }
trap cleanup EXIT

go build -o "$TMP/guts-exit" ./cmd/exitnode
go build -o "$TMP/guts-client" ./cmd/gutsclient

head -c 2000000 /dev/urandom > "$TMP/big.bin"
printf 'hello guts\n' > "$TMP/small.txt"

( cd "$TMP" && python3 -m http.server "$WEB_PORT" --bind 127.0.0.1 >/dev/null 2>&1 ) & WEB_PID=$!
"$TMP/guts-exit" -addr "127.0.0.1:$EXIT_PORT" -key "$KEY" >"$TMP/exit.log" 2>&1 & EXIT_PID=$!
sleep 0.5
python3 scripts/door_emulator.py "$DOOR_PORT" "http://127.0.0.1:$EXIT_PORT/g" "$KEY" >"$TMP/door.log" 2>&1 & DOOR_PID=$!
sleep 0.5
"$TMP/guts-client" -listen "127.0.0.1:$SOCKS_PORT" -door "http://127.0.0.1:$DOOR_PORT/g" -key "$KEY" -direct >"$TMP/client.log" 2>&1 & CLIENT_PID=$!
sleep 1

"$TMP/guts-client" -door "http://127.0.0.1:$DOOR_PORT/g" -key "$KEY" -direct -check

small=$(curl -sS --max-time 20 -x "socks5h://127.0.0.1:$SOCKS_PORT" "http://127.0.0.1:$WEB_PORT/small.txt")
[ "$small" = "hello guts" ] || { echo "small body differs: $small"; exit 1; }

curl -sS --max-time 180 -x "socks5h://127.0.0.1:$SOCKS_PORT" "http://127.0.0.1:$WEB_PORT/big.bin" -o "$TMP/out.bin"
if ! cmp -s "$TMP/big.bin" "$TMP/out.bin"; then echo "the 2 MiB body differs"; exit 1; fi

echo "smoke: ok — 2 MiB through the door, byte for byte"
