# Build your connection

Four paths, with a setup guide for each. Start with the row that matches your server and network.

| Feature | What you need | Transport / scope | Setup |
| :--- | :--- | :--- | :--- |
| **NaiveProxy** | VPS, domain, valid TLS certificate, compatible Caddy forwardproxy | HTTPS / HTTP/2; TCP proxy | [English](naiveproxy.md) · [فارسی](naiveproxy.fa.md) |
| **TUIC v5** | UDP-reachable VPS, domain/certificate, TUIC v5 server | QUIC; TCP + UDP relay | [English](tuic.md) · [فارسی](tuic.fa.md) |
| **ShadowQUIC** | UDP-reachable VPS, ShadowQUIC server and matching JLS name | QUIC + JLS; TCP + UDP relay | [English](shadowquic.md) · [فارسی](shadowquic.fa.md) |
| **GUTS Fetch** | Google account, Apps Script Web app, personal device CA | HTTP/HTTPS in clients that trust your CA | [English](guts.md) · [فارسی](guts.fa.md) |

## GUTS Google Apps Script

**[Download Code.gs](https://github.com/imgutss/Omni-App/releases/download/v1.0.0/Code.gs)** · [View source](../guts/Code.gs) · [Google Apps Script](https://script.google.com/)

`Code.gs` is the **Google Apps Script source**, not an APK or a native GUTS executable. It implements **Fetch/certificate mode**: no exit node and no `/g` URL. Use it with **Certificate mode (Apps Script fetch)** enabled in Omni.

## Before you start

- Use a full Omni APK for all four features. `nolibs-guts` is a reduced build and does not include the other three engines.
- VPS examples target Linux. NaiveProxy/TUIC examples require Docker Engine and Compose v2; ShadowQUIC uses a Linux binary and systemd. Install Docker from its [official instructions](https://docs.docker.com/engine/install/).
- Each guide uses example domains, credentials and addresses that must be replaced. No example profile is a working public server.
- Keep the SSH rule in your firewall. Open only the documented ports in both the server firewall and your provider's firewall. These guides do not enable or reset your firewall.
- When using a domain for a direct connection, its A/AAAA records must point to the intended server. An ordinary HTTP CDN/proxy is not a generic QUIC/UDP relay.
- Protocols are not interchangeable: a TUIC configuration cannot be used as ShadowQUIC, and a Fetch door cannot act as a raw-TCP exit door.
- Examples are starting configurations, not a promise of connectivity on every network. Check the server logs, certificate, credentials and actual transport before changing advanced tuning.

Generate a fresh password on a trusted machine:

```sh
openssl rand -hex 32
```

Do not reuse the displayed placeholder passwords. Treat profile sharing links as credentials. None of these guides asks you to disable certificate verification for a public server.
