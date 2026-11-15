#!/usr/bin/env bash
# Host-side SCM_RIGHTS regression test for the exact patch copied into the Android ShadowQUIC build.
# Requires Linux and cargo. Does not require an Android SDK, NDK or a running VPN.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/src"
cp "$ROOT_DIR/patches/shadowquic/protect_socket.rs" "$TEST_DIR/src/lib.rs"
cat > "$TEST_DIR/Cargo.toml" <<'TOML'
[package]
name = "omni-protect-regression"
version = "0.1.0"
edition = "2024"
[dependencies]
sendfd = { version = "0.4.4", features = ["tokio"] }
tokio = { version = "1", features = ["net", "io-util", "time", "rt", "macros"] }
TOML
cargo test --manifest-path "$TEST_DIR/Cargo.toml"
