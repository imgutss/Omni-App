# ShadowQUIC · QUIC with a matching JLS name

[فارسی](shadowquic.fa.md) · [All guides](README.md)

ShadowQUIC has its own server, user credentials and JLS settings. A TUIC server or WARP profile is not a substitute. This guide uses **v0.4.8**, matching Omni's pinned ShadowQUIC engine.

## 1 · Prepare the server

Use a Linux VPS with systemd, `curl`, `sha256sum` and sudo access. The example listens on **UDP/1443**; permit that port in the host/provider firewalls without removing SSH access. The server also needs outbound DNS and access to the configured JLS upstream, here `cloudflare.com:443`.

Download the matching official static release. These checksums correspond to the v0.4.8 release assets:

```sh
mkdir -p ~/omni-shadowquic && cd ~/omni-shadowquic
case "$(uname -m)" in
  x86_64)
    ARCH=x86_64
    EXPECTED=f1bb3f0ee2db8a65582e08469f1a3c9420f1645eea2fd1d40d9a411e27ab6194 ;;
  aarch64|arm64)
    ARCH=aarch64
    EXPECTED=fe688fbf83cf3ea5a666f1079485102804c30e46ff2dab007e2fcefa06089ae9 ;;
  *) echo 'Use the official release for your architecture'; exit 1 ;;
esac
curl -fL --retry 3 \
  "https://github.com/spongebob888/shadowquic/releases/download/v0.4.8/shadowquic-${ARCH}-linux-musl" \
  -o shadowquic
printf '%s  shadowquic\n' "$EXPECTED" | sha256sum -c - || exit 1
sudo install -m 0755 shadowquic /usr/local/bin/shadowquic
```

Do not continue if checksum verification fails. For a different release/architecture, use that release's own asset and checksum instead of mixing these values.

## 2 · Configure the server

```sh
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/shadowquic
curl -fL "$BASE/server.yaml" -o server.yaml
curl -fL "$BASE/omni-shadowquic.service" -o omni-shadowquic.service
openssl rand -hex 32
```

Edit [`server.yaml`](examples/shadowquic/server.yaml): choose a username, replace the placeholder password with the generated value, and keep or choose a reachable `jls-upstream.addr`. The JLS domain before `:443` must equal the **Server name (JLS)** entered in Omni. It is not necessarily your VPS's domain.

The sample uses `h3`, `bbr`, and disables 0-RTT initially. Keep log level `info`; verbose logs can contain connection details.

## 3 · Run as a dedicated user

Review the [systemd unit](examples/shadowquic/omni-shadowquic.service) before installing:

```sh
id -u omni-shadowquic >/dev/null 2>&1 || \
  sudo useradd --system --user-group --no-create-home --shell /usr/sbin/nologin omni-shadowquic
sudo install -d -o root -g omni-shadowquic -m 0750 /etc/omni-shadowquic
sudo install -o root -g omni-shadowquic -m 0640 server.yaml /etc/omni-shadowquic/server.yaml
sudo install -m 0644 omni-shadowquic.service /etc/systemd/system/omni-shadowquic.service
sudo systemctl daemon-reload
sudo systemctl enable --now omni-shadowquic
sudo systemctl status omni-shadowquic --no-pager
```

If you change to a privileged port below 1024, the provided unprivileged unit needs an appropriate bind capability. Start with 1443 rather than running the service as root to avoid that configuration step.

## 4 · Add it to Omni

Open **+ → Add [ShadowQUIC]**.

| Field | Example / value |
| :--- | :--- |
| Address / Port | Real VPS IP or direct DNS name / `1443` |
| Username / Password | The user configured on the server |
| Server name (JLS) | `cloudflare.com` if the sample upstream was retained |
| ALPN | `h3` |
| ShadowQUIC parameters | JSON below |

```json
{"congestion_control":"bbr","over_stream":false,"zero_rtt":false,"keep_alive_interval":10000}
```

Save and connect. `over_stream` changes how relayed UDP is carried; it does not turn ShadowQUIC's outer transport into TCP.

Share-link template (replace the example IP/credentials and escape values):

```text
shadowquic://USERNAME:PASSWORD@192.0.2.10:1443?sni=cloudflare.com&alpn=h3&congestion_control=bbr&over_stream=0&zero_rtt=0#My-ShadowQUIC
```

`192.0.2.10` is a documentation address, not a working endpoint. Use Omni's Share action for correctly escaped real credentials. Use brackets around an IPv6 address.

## 5 · Diagnose

```sh
sudo journalctl -u omni-shadowquic -n 80 --no-pager
sudo ss -lunp | grep ':1443'
```

- Check UDP firewall rules, matching credentials, JLS domain and ALPN first.
- A listening socket proves only local startup; test the actual route from the phone.
- Wrong JLS names are not fixed by changing TLS/SNI values randomly.
- Inspect service logs before experimenting with MTU/0-RTT or advanced parameters. Do not share unredacted logs.
- Network compatibility and latency depend on the real path; no protocol guarantees better gaming ping.

[Official v0.4.8 server example](https://github.com/spongebob888/shadowquic/blob/v0.4.8/shadowquic/config_examples/server.yaml) · [Release assets](https://github.com/spongebob888/shadowquic/releases/tag/v0.4.8) · [Omni editor](../../V2rayNG/app/src/main/java/com/v2ray/ang/ui/server/ServerShadowQuicActivity.kt)
