<div align="center">

<img src="docs/media/omni-hero.svg" alt="Omni — Your network. Your way." width="100%" />

[![Release](https://img.shields.io/github/v/release/imgutss/Omni-App?style=for-the-badge&label=OMNI&color=22c779&labelColor=101a15)](https://github.com/imgutss/Omni-App/releases/latest)
[![Android](https://img.shields.io/badge/MADE_FOR-ANDROID-22c779?style=for-the-badge&labelColor=101a15)](https://github.com/imgutss/Omni-App/releases/latest)
[![License](https://img.shields.io/badge/LICENSE-GPL_3.0-22c779?style=for-the-badge&labelColor=101a15)](LICENSE)

### Your network. Your way.

One place for your connections, profiles, and everyday network tools.
Thoughtfully designed for Android, down to the last detail.

**[Download Omni](https://github.com/imgutss/Omni-App/releases/latest)** &nbsp; · &nbsp; **[فارسی](README.fa.md)** &nbsp; · &nbsp; [Getting started](docs/GETTING_STARTED.md)

</div>

<br />

<img src="docs/media/omni-features.svg" alt="Designed for Android. Tools within reach. Set it your way." width="100%" />

<br />

## More ways to connect

Four distinctive tools, each with its own route from setup to your first profile.

<table>
<tr>
<td width="50%"><a href="docs/protocols/naiveproxy.md"><img src="docs/media/naiveproxy-card.svg" alt="NaiveProxy — HTTPS/HTTP2 setup" width="100%" /></a></td>
<td width="50%"><a href="docs/protocols/tuic.md"><img src="docs/media/tuic-card.svg" alt="TUIC v5 — QUIC server and client setup" width="100%" /></a></td>
</tr><tr>
<td width="50%"><a href="docs/protocols/shadowquic.md"><img src="docs/media/shadowquic-card.svg" alt="ShadowQUIC — server, JLS and Omni profile" width="100%" /></a></td>
<td width="50%"><a href="docs/protocols/guts.md"><img src="docs/media/guts-card.svg" alt="GUTS — Google Apps Script Fetch setup" width="100%" /></a></td>
</tr>
</table>

| Feature | The route | Build it |
| :--- | :--- | :--- |
| **NaiveProxy** | Chromium-based client, HTTPS/HTTP2, authenticated Caddy server | [Server + Omni profile](docs/protocols/naiveproxy.md) |
| **TUIC v5** | QUIC transport, UUID authentication, TCP and UDP relay | [Server + Omni profile](docs/protocols/tuic.md) |
| **ShadowQUIC** | Dedicated QUIC server, JLS name and per-user credentials | [Server + Omni profile](docs/protocols/shadowquic.md) |
| **GUTS Fetch** | Your Google Apps Script door and a personal device certificate | [Deploy + Omni profile](docs/protocols/guts.md) |

### GUTS, from Code.gs to your own door

[![Download Code.gs](https://img.shields.io/badge/DOWNLOAD-Code.gs-22c779?style=for-the-badge&labelColor=101a15&logo=googleappsscript&logoColor=white)](https://github.com/imgutss/Omni-App/releases/download/v1.0.0/Code.gs)

**[Download the Google Apps Script](https://github.com/imgutss/Omni-App/releases/download/v1.0.0/Code.gs)** · [Read the code](docs/guts/Code.gs) · [Open Apps Script](https://script.google.com/) · [Step-by-step guide](docs/protocols/guts.md)

Deploy **Code.gs**, set a private shared key, then add the `/exec` URL to GUTS in Omni. This file is for **Certificate / Fetch mode**: no VPS or exit node is required. It carries web HTTP/HTTPS for clients that trust your own local CA—not arbitrary TCP/UDP. Read the certificate and privacy notes in the guide before installing a CA.

[**Explore all setup guides →**](docs/protocols/README.md) &nbsp; · &nbsp; [راهنماهای فارسی](README.fa.md#چهار-مسیر-برای-اتصال)

## A little depth. A lot of control.

Soft lighting. Layered artwork. A palette that feels like yours. Omni brings a distinctive look to the tools you use every day, with **19 themes**, light and dark modes, and effects you can turn up or dial back.

<table>
<tr><td align="center"><b>Everything in its place</b></td><td align="center"><b>Your own atmosphere</b></td><td align="center"><b>Meet Omni Hub</b></td></tr>
<tr>
<td width="33%"><img src="docs/media/menu.jpg" alt="Omni menu in a violet theme" width="100%" /></td>
<td width="33%"><img src="docs/media/settings.jpg" alt="Omni settings in a blue theme" width="100%" /></td>
<td width="33%"><img src="docs/media/hub.jpg" alt="Omni Hub and its network tools" width="100%" /></td>
</tr>
</table>

## Built around your connections

**Bring your profiles.** Import a link, scan a QR code, or add a configuration. Keep subscriptions together and switch between profiles when you need to.

**Choose what goes where.** Fine-tune routing and per-app rules. Edit protocol settings, manage assets, and back up your configuration.

**Keep your tools close.** Open Omni Hub for DNS, routing, sync, privacy, and speed/ping utilities. Check logs or run diagnostics without leaving the app.

**Find your setup.** Work with Xray-based profiles, Aether/WARP, plain WireGuard, NaiveProxy, and the app's other supported engines.

## Pick your download

| Your device | Recommended APK |
| :--- | :--- |
| **Most current Android phones** | **[Full ARM64](https://github.com/imgutss/Omni-App/releases/download/v1.0.0/Omni_1.0.0_arm64-v8a.apk)** |
| Older 32-bit ARM phones | `armeabi-v7a` |
| Intel devices and emulators | `x86` or `x86_64` |
| Not sure about the architecture | `universal` |

[**See every download →**](https://github.com/imgutss/Omni-App/releases/latest)

The full build is the best starting point. Reduced `slim` and `nolibs-guts` builds are available for specialized setups. Every release includes `SHA256SUMS` for checking your download.

## Make your first connection

**01 — Install.** Choose the APK for your device. Updating an existing official installation keeps your local setup; you don't need to uninstall first.

**02 — Add a profile.** Import a configuration or subscription from a provider you trust.

**03 — Connect.** Select a profile, tap the power button, and approve Android's VPN permission when prompted.

You'll need your own working configuration. Omni doesn't include a subscription service; connection availability depends on your provider, settings, and network.

## Stay in the loop

[Report a bug](https://github.com/imgutss/Omni-App/issues/new/choose) · [Suggest an idea](https://github.com/imgutss/Omni-App/issues/new/choose) · [Build from source](docs/BUILDING.md) · [Contribute](CONTRIBUTING.md)

Please remove private keys, subscription links, and other sensitive information before sharing logs or screenshots. For sensitive reports, see the [security policy](SECURITY.md).

<div align="center">

<br />

**OMNI · IMGUTSS**

Your network. Your way.

[Privacy](docs/PRIVACY.md) &nbsp; · &nbsp; [License](LICENSE) &nbsp; · &nbsp; [Third-party notices](docs/CREDITS.md)

</div>
