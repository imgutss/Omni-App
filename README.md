<div align="center">

<img src="docs/media/omni-hero.svg" alt="Omni — by imgutss" width="100%" />

<br />

[![Android build](https://github.com/imgutss/Omni-App/actions/workflows/omni-build.yml/badge.svg)](https://github.com/imgutss/Omni-App/actions/workflows/omni-build.yml)
[![Latest release](https://img.shields.io/github/v/release/imgutss/Omni-App?style=flat-square&color=23d879&label=DOWNLOAD)](https://github.com/imgutss/Omni-App/releases/latest)
[![License](https://img.shields.io/badge/LICENSE-GPL--3.0-20382b?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/PLATFORM-ANDROID-20382b?style=flat-square)](V2rayNG/app/build.gradle.kts)

**Your connection. Your control.**

A native Android networking client with an expressive interface, real connection engines, and a practical toolbox.

[**Download**](https://github.com/imgutss/Omni-App/releases/latest) · [**فارسی**](README.fa.md) · [**Getting started**](docs/GETTING_STARTED.md) · [**Report an issue**](https://github.com/imgutss/Omni-App/issues/new/choose)

</div>

<br />

<img src="docs/media/omni-features.svg" alt="Native experience, networking tools, and configuration control" width="100%" />

## Meet Omni

**Omni — imgutss** brings the everyday controls together without turning the app into a web page or simulating a successful connection. Native profile editors, Android VPN services and actual core processes remain behind the interface.

- **An interface with depth.** Layered icons, 3D artwork, theme selection, light/dark appearance and adjustable effects.
- **A cleaner menu.** DNS Lab, Routing Studio, Sync Center, Speed & Ping Lab, and Privacy & Backup live inside **Omni Hub**, not in duplicate menu tiles.
- **Real configuration.** Subscriptions, QR/link import, protocol editors, routing rules, per-app selection, assets and backups.
- **Useful diagnostics.** Actual logs, connection checks, DNS utilities and WARP/Aether tools. Results depend on your configuration and network; no invented success metrics.

## Download the right build

Open the [latest release](https://github.com/imgutss/Omni-App/releases/latest) for signed APKs and `SHA256SUMS`.

| Device / use case | Build |
| :--- | :--- |
| **Most modern Android phones** | **Full `arm64-v8a` — recommended** |
| Older 32-bit ARM phones | `armeabi-v7a` |
| Intel device or emulator | `x86` / `x86_64` |
| Unsure about architecture | `universal` — larger download |
| Advanced reduced-size installs | `slim` / `nolibs-guts`; omitted binaries can limit features |

Keep the full build if you want the broadest available engine support. Upgrade in place using the same signing identity; **do not uninstall just to update**, because uninstalling can remove local configurations.

## Start in three steps

1. Install the appropriate signed APK from this repository's Releases.
2. Import a profile or subscription you trust, then select a configuration.
3. Connect and accept Android's VPN permission prompt. Use **Logcat** and **Omni Doctor** if the connection fails.

Omni does not provide a built-in subscription, promise unrestricted access, or turn an invalid endpoint into a working connection. See [Getting started](docs/GETTING_STARTED.md) and [Privacy](docs/PRIVACY.md).

## Inside the app

| Connection & configuration | Daily tools | Presentation |
| :--- | :--- | :--- |
| Native Android VPN service | Omni Hub | Native Compose |
| Xray-based profile editors | DNS tools & WARP utilities | 19 theme choices |
| Aether / WARP & plain WireGuard | Actual logs & profile checks | Light / dark modes |
| Genuine NaiveProxy support | Routing & per-app controls | Layered artwork & effects |
| Subscriptions / import / export | Backups & local settings | Accessible native controls |

Removed AmneziaWG and retired VLESS/Trojan discovery scanners are not part of this release. That does **not** remove VLESS/Trojan profile support.

## Trust & verification

- Verify downloads against the release's **SHA256SUMS**.
- Official upgrades keep the existing Android signing identity.
- Android build/unit checks and native UI smoke testing are available in [Actions](https://github.com/imgutss/Omni-App/actions).
- A passing emulator test is **not** a promise of error-free behavior, physical-device compatibility, exact visual parity or working VPN access on every network. Each release describes its tested scope.
- Never post live subscription URLs, private keys, tokens, unredacted logs or private backups in an issue.

## Build & contribute

The Android project is in [`V2rayNG/`](V2rayNG). Read [Build & release](docs/BUILDING.md), [Contributing](CONTRIBUTING.md), and the applicable `AGENTS.md` guides before changing code. Native dependencies are pinned or checked by the build pipeline; signing keys never belong in Git.

## Open source, with respect

The **Omni brand, presentation and project direction are maintained by imgutss**. This is an independently hosted Omni repository. It includes work derived from PattNG/v2rayNG and third-party engines and artwork; those authors' rights and notices remain intact.

See [Credits & third-party notices](docs/CREDITS.md), [GPL-3.0](LICENSE), and the in-app open-source license screen. “Independent repository” does not mean exclusive authorship of every dependency.

<div align="center">

---

**OMNI · BY IMGUTSS**<br />
Designed with depth. Released with source.

</div>
