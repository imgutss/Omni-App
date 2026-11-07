# MasterDNS Android integration

This branch adds the official **MasterDnsVPN** client to the Android app only.

## Scope and compatibility

- Upstream: `masterking32/MasterDnsVPN`, revision `4144e544d220681a8e8fe808855ac09dcd07c31c`, MIT license. The source is vendored under `third_party/masterdnsvpn/` and the client is rebuilt for four Android ABIs by `compile-masterdns.sh`.
- Imports official client JSON, TOML, and Base64 JSON documents, plus Omni's own `masterdns://` sharing URI. The URI is **not** a WhiteDNS format or a claim of WhiteDNS compatibility.
- The editor retains the official document and resolver list. Domains, encryption settings, resolver syntax, and malformed input are checked before saving/starting.
- A profile is currently supported as one standalone primary MasterDNS outbound. Chains, Aether combinations, and multiple MasterDNS cores are refused rather than silently bypassing the requested path.
- At runtime the official client provides a loopback SOCKS5 listener. Xray uses that outbound; in VPN mode upstream UDP descriptors are passed through the same-UID SCM_RIGHTS endpoint to `VpnService.protect` before use.
- Ping, scanner, and batch speed paths use the real native core when available and report unavailable/no-result states honestly. They do not invent latency, speed, or connectivity.

## Verification recorded locally

- `GOTOOLCHAIN=local CGO_ENABLED=0 /home/user/.cache/omni-tools/go/bin/go test ./...` in `third_party/masterdnsvpn`: all upstream packages passed.
- `:app:testPlaystoreDebugUnitTest`: **968/968** tests passed after the lifecycle, resolver, and manifest changes.
- Four static native executables were rebuilt and ELF-inspected.
- A Playstore **debug** APK was assembled and its ZIP contents checked for `libmasterdns.so` on all four ABIs and the packaged license notices. That APK was a packaging check with only the locally staged generated dependencies; it is not a signed release or a complete release artifact set.

No Android device, emulator VPN session, Internet tunnel, or signed MasterDNS release has been verified. Do not describe the build as proof of connectivity. CI must regenerate the other native dependencies and sign any release; a device test with a valid MasterDNS server remains required.

## Build inputs

The normal Android generated inputs are still required, including `V2rayNG/app/libs/libv2ray.aar` and the other native helpers. They are intentionally not committed. Go must be available explicitly (the local toolchain used here is `/home/user/.cache/omni-tools/go/bin/go`). The two Android workflows contain the MasterDNS build/cache/stage/package checks.
