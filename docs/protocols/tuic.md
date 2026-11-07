# TUIC v5 · A QUIC path for TCP and UDP

[فارسی](tuic.fa.md) · [All guides](README.md)

TUIC v5 needs a compatible server, a valid TLS certificate and reachable **UDP**. Omni supplies the client engine; it does not create a VPS or turn a blocked UDP route into a working one.

## 1 · Prepare Linux, DNS and ports

Install Docker Engine/Compose v2. Point `tuic.example.com` directly to your server's IPv4 address. The sample listens on IPv4; do not publish an unusable AAAA record. Allow **UDP/443** in host and provider firewalls. **TCP/80** must be free and reachable while issuing/renewing the HTTP-01 certificate.

```sh
mkdir -p ~/omni-tuic && cd ~/omni-tuic
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/tuic
curl -fL "$BASE/compose.yaml" -o compose.yaml
curl -fL "$BASE/config.json" -o config.json
docker compose pull
```

The example uses the official sing-box container. Record the pulled image digest and pin it for repeatable deployment; `latest` can change. Validate the configuration again before any engine update.

## 2 · Issue a certificate

Replace both variables before running. This standalone method needs TCP/80; it cannot run while another web server already owns that port.

```sh
DOMAIN=tuic.example.com
EMAIL=you@example.com
docker run --rm -it -p 80:80 \
  -v "$PWD/letsencrypt:/etc/letsencrypt" \
  certbot/certbot certonly --standalone --non-interactive --agree-tos \
  --email "$EMAIL" -d "$DOMAIN"
```

Keep `letsencrypt` private; it contains the certificate's private key. If you already operate a certificate manager, use its valid certificate and full key paths instead of issuing another one blindly.

## 3 · Configure and start TUIC

Generate a UUID and a separate random password:

```sh
docker run --rm ghcr.io/sagernet/sing-box:latest generate uuid
openssl rand -hex 32
```

Edit [`config.json`](examples/tuic/config.json): replace the placeholder UUID/password and **all** occurrences of `tuic.example.com`, including the certificate paths. Leave TLS enabled and keep `zero_rtt_handshake` off initially.

```sh
chmod 600 config.json
docker compose run --rm tuic check -c /etc/sing-box/config.json
docker compose up -d
docker compose logs --tail=80 tuic
```

The example uses port 443/UDP, `bbr`, ALPN `h3`, and a direct server outbound. A second QUIC service such as an HTTP/3 web server must not already own the same UDP socket.

## 4 · Add it to Omni

Open **+ → Add [TUIC v5]**.

| Field | Value |
| :--- | :--- |
| Address / Port | Real server domain or IP / `443` |
| UUID / Password | Exactly the server's user UUID/password |
| SNI | Certificate domain, e.g. `tuic.example.com` |
| ALPN | `h3` |
| Allow insecure | **Off** |
| TUIC parameters | JSON below |

```json
{"congestion_control":"bbr","udp_relay_mode":"native","zero_rtt_handshake":false}
```

Save and connect. `udp_relay_mode` controls relayed UDP behavior; it does not remove TUIC's own dependency on a working UDP connection.

Share-link template (replace and escape values):

```text
tuic://UUID:PASSWORD@tuic.example.com:443?sni=tuic.example.com&alpn=h3&congestion_control=bbr&udp_relay_mode=native&zero_rtt_handshake=0&allow_insecure=0#My-TUIC
```

Use Omni's Share action to escape a saved profile correctly. This is TUIC **v5**, not an interchangeable v4 configuration.

## 5 · Diagnose and maintain

- On the server, `sudo ss -lunp | grep ':443'` checks the UDP listener; it does not prove end-to-end reachability.
- A successful HTTPS page or TCP port check does **not** test QUIC/UDP. Check provider UDP rules and the real client connection.
- Authentication errors: compare UUID/password. TLS errors: compare the certificate domain, SNI, expiry and device clock. Keep insecure mode off.
- Renew the certificate before expiry using the same Certbot volume and your chosen renewal method, then restart/reload the service so it reads the renewed certificate. Test renewal and schedule it; the one-time issuance command does not schedule it.
- Leave 0-RTT off unless you understand replay risks. Tune congestion/relay settings only after a basic connection works.

[Official TUIC inbound fields](https://sing-box.sagernet.org/configuration/inbound/tuic/) · [TLS fields](https://sing-box.sagernet.org/configuration/shared/tls/) · [Omni editor](../../V2rayNG/app/src/main/java/com/v2ray/ang/ui/server/ServerTuicActivity.kt)
