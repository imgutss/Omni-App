# DNSTT upstream

- Project: https://www.bamsoftware.com/software/dnstt/
- Source: https://www.bamsoftware.com/git/dnstt.git
- Tag: `v1.20260501.0`
- Revision: `0c5c52a57d899c05428c116898941761a2ed83c2`
- License: public domain; see `COPYING`.

The Android app builds the official `dnstt-client` engine. The small app-owned
Android patch adds an optional `-fd-control-unix-socket` hook. When supplied, it
protects upstream UDP/TCP sockets through the same-UID SCM_RIGHTS endpoint before
connect/bind; the protocol and default behavior are otherwise unchanged. DNSTT
still supplies a local TCP forwarder, not a TUN or SOCKS server. Omni therefore
uses it only with a SOCKS5 service on the tunnel server's configured remote end.

No WhiteDNS source, binary, asset, or proprietary configuration format is used.
