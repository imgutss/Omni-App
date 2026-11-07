# SlipNet-compatible engine sources

This directory contains source used by Omni's Android build, not a bundled
SlipNet AAR. Revisions are pinned and recorded beside each source tree.

## NoizDNS

- Upstream: https://github.com/anonvector/noizdns
- Revision: `7289a56185e174d4cbf2ffaea55523db8f1fd5af`
- License at this revision: MIT; see `noizdns/LICENSE`.
- Omni additions: `cmd/omni-client` is the process/lifecycle boundary. The
  `mobile/protect*.go` files add optional Android FD protection and the
  transport call sites use it. One malformed upstream `fmt.Errorf` format was
  corrected. NoizDNS's own client, cover-traffic, stealth, multi-resolver,
  authoritative, and SOCKS5 code remains in the pinned source.

## DNSTT dependency used by that NoizDNS revision

- Upstream: https://github.com/anonvector/dnstt
- Revision: `51bdba3948d44b3175bc88ea40b2116bb9daff07`
- License at this revision: AGPL-3.0; see `dnstt/COPYING`.
- It is kept as a separate source dependency because NoizDNS's `go.mod`
  replaces `www.bamsoftware.com/git/dnstt.git` with `../dnstt`.
- The AGPL license and complete corresponding source are distributed with the
  app source. The existing official DNSTT engine in `third_party/dnstt` is a
  different, public-domain upstream implementation and is not replaced.

No WhiteDNS source, binary, asset, or proprietary configuration material is
included. SlipNet's Android application code and AARs are not copied. The
`slipnet://` reader in the Android source is an independent implementation of
the documented exchange schema; a parsed profile is only exposed when an
engine and lifecycle exist for it.

Build with `compile-noizdns.sh`. The script verifies both revisions and emits
`libnoizdns.so` for all four Android ABIs.
