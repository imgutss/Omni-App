# DNSTT integration

- Engine: official `dnstt-client` from <https://www.bamsoftware.com/git/dnstt.git>.
- Pinned upstream: tag `v1.20260501.0`, revision `0c5c52a57d899c05428c116898941761a2ed83c2`.
- License: public domain; the upstream `COPYING` is vendored with the source.
- Android patch: only the optional `-fd-control-unix-socket` hook, using the same-UID SCM_RIGHTS protection protocol as MasterDnsVPN. With no hook, upstream network behavior is unchanged.

## Runtime contract

`DnsttCoreManager` starts the real static executable as `libdnstt.so` for the device's ABI. The profile supports the official `udp`, `dot`, and `doh` inputs and the official uTLS distribution. The app owns only the loopback listener (`127.0.0.1:18001`) and VPN socket protection.

DNSTT itself is a **local TCP forwarder**, not a TUN or a SOCKS server. Omni's Xray outbound therefore sends a no-auth SOCKS5 session through that forwarder. The configured DNSTT server must forward its remote tunnel streams to a compatible **no-auth SOCKS5** service. A DNSTT server without that remote service is not a working Omni configuration; the editor and this document do not pretend otherwise. No remote proxy, server, or connectivity is bundled.

Only one standalone primary DNSTT profile is accepted. Chains, policy groups, routing placements, and combinations with another native core are refused rather than silently bypassed. Start readiness requires a real SOCKS5 no-auth greeting response through the tunnel, not just a bound local port. Measurements use the same live or bounded test process and report no-response/untested states.

## Build

```sh
GO=/path/to/go bash compile-dnstt.sh
cp -r libs-dnstt/. V2rayNG/app/libs/
```

The script verifies the pinned revision and builds static, stripped executables for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Both Android CI workflows restore/build/test the pinned Go packages, stage the four libraries, and check that every ABI-containing APK includes `libdnstt.so`.

## Verification boundary

- Pinned upstream Go tests pass, including the optional FD-protection tests.
- The four native outputs were inspected as static, stripped ELF executables.
- A host-only smoke test ran the official `dnstt-server` and the built `dnstt-client` over local UDP, with a real no-auth SOCKS5 server and echo target. SOCKS5 negotiation, CONNECT, and payload echo passed. This tests the native protocol path only; it is not an Android VPN or Internet-connectivity test.
- Android JVM suite after the integration: **983 tests / 105 suites, zero failures, errors, or skips**. `compilePlaystoreDebugKotlin` also passes.
- No Android device/emulator VPN run, Android socket-protection run, or Internet-connectivity verification has been performed. No such claim should be inferred from packaging or host smoke results.
