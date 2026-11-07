# NaiveProxy · HTTPS, with Chromium's client stack

[فارسی](naiveproxy.fa.md) · [All guides](README.md)

Omni uses the genuine NaiveProxy client. This integration supports **HTTPS/HTTP/2**, not Naive QUIC/HTTP3. The server must include the Naive-compatible forwardproxy implementation; a normal HTTPS website or a plain Caddy installation is not enough.

## 1 · Prepare the server

You need a Linux VPS, a domain such as `naive.example.com` pointing directly to it, Docker/Compose, and free TCP ports **80** and **443**. Allow those ports in the host/provider firewall. Keep your SSH access.

Download the four example files to one directory:

```sh
mkdir -p ~/omni-naive && cd ~/omni-naive
BASE=https://raw.githubusercontent.com/imgutss/Omni-App/main/docs/protocols/examples/naive
for file in Dockerfile compose.yaml Caddyfile index.html; do
  curl -fL "$BASE/$file" -o "$file" || exit 1
done
```

Inspect the files before building. The [Dockerfile](examples/naive/Dockerfile) builds Caddy with the Naive-compatible forwardproxy module described in the [NaiveProxy server documentation](https://github.com/klzgrad/naiveproxy#server-setup).

## 2 · Set the domain and credentials

Edit [`Caddyfile`](examples/naive/Caddyfile):

- `you@example.com` → your certificate contact email.
- `naive.example.com` → your real domain.
- `omni` → your chosen proxy username.
- `REPLACE_WITH_A_LONG_RANDOM_PASSWORD` → a new strong random password.

```sh
chmod 600 Caddyfile
docker compose build
docker compose run --rm --no-deps naive caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose up -d
docker compose logs --tail=80 naive
```

Caddy obtains the TLS certificate automatically when DNS, ports and ACME access are correct. The named `caddy_data` volume keeps certificate state; do not delete it casually. Inspect/retain the built image ID for repeatable deployments. Updating the builder/module later can change the resulting image.

The sample serves a small welcome page and accepts authenticated proxy requests. HTTP/3 is disabled in this example so Caddy does not also occupy UDP/443.

## 3 · Add it to Omni

Open **+ → Add [NaiveProxy]**.

| Field | Example / value |
| :--- | :--- |
| Remarks | Your chosen name |
| Address | `naive.example.com` |
| Port | `443` |
| Username / Password | Exactly the values in Caddyfile |
| SNI | `naive.example.com` |

Save, select the profile, and connect. If you dial an IP address instead, SNI must still be the valid certificate domain. Certificate verification remains enabled.

Link format (replace every example value, percent-encode special characters):

```text
naive+https://USERNAME:PASSWORD@naive.example.com:443?sni=naive.example.com#My-Naive
```

The app's Share action produces the escaped link for a saved profile.

## 4 · Check the result

- `docker compose ps` and `docker compose logs --tail=80 naive` show service state.
- `curl -I https://naive.example.com` checks the website/TLS path, **not** authenticated proxy connectivity.
- A credential/authentication failure usually means a mismatch between the profile and Caddyfile. A certificate failure needs DNS/SNI/certificate correction, not an insecure bypass.
- If Caddy cannot bind 80/443, another service may already own the port. Plan port/domain ownership before running multiple examples on the same server.
- Naive's HTTPS CONNECT path is not a generic UDP tunnel. Do not infer working UDP or gaming performance from a successful TCP connection.

## Maintain

Keep Caddy's data volume, protect the config/password and update intentionally. After an update validate the config and check the logs again. Redact credentials and addresses before posting diagnostics.

[Client implementation](../../V2rayNG/app/src/main/java/com/v2ray/ang/ui/server/ServerNaiveActivity.kt) · [Official NaiveProxy](https://github.com/klzgrad/naiveproxy)
