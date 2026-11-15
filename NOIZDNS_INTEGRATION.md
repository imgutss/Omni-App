# SlipNet-compatible NoizDNS integration

- The Android process is built from the pinned `anonvector/noizdns` revision
  `7289a56185e174d4cbf2ffaea55523db8f1fd5af` and its pinned DNSTT dependency
  `51bdba3948d44b3175bc88ea40b2116bb9daff07`.
- The NoizDNS revision carries its MIT license; the DNSTT dependency carries
  AGPL-3.0. Both source trees, notices, revision markers, and build scripts are
  kept in this repository. No WhiteDNS proprietary source, binary, or asset is
  used.

## Direct profiles and mixed resolver modes

`slipnet://` DNSTT and `sayedns` records are decoded directly. Resolver entries
retain their individual `:0`/`:1` authority markers in the saved profile and in
Omni URIs. A uniform list uses the ordinary NoizDNS mobile client. A mixed list
uses one real mobile tunnel per resolver, with the corresponding mode in each
child, behind an app-owned local SOCKS5 negotiator. This avoids applying a
single pool-wide KCP/poll/cover-traffic mode to unlike resolvers. SlipNet's
explicit global authoritative field remains an all-resolver override.

The negotiator races the local SOCKS5 greeting for fanout (or the configured
round-robin spread), then relays only the winning connection. It does not claim
that this is an Android VPN or an Internet connectivity test.

## Verification

`test-noizdns-loopback.sh` builds the real client/server and exercises UDP,
Noise, KCP, smux, local and remote SOCKS5, plus a mixed-authority two-resolver
case. JVM tests cover direct import, Omni URI round trips, malformed markers,
and native argument construction. Native four-ABI builds and host loopback
tests do not replace an Android device VPN test.
