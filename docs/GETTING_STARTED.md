# Getting started

1. Get a signed APK from [Omni Releases](https://github.com/imgutss/Omni-App/releases/latest). Most modern phones use the full arm64-v8a build. Compare the file's SHA-256 with SHA256SUMS.
2. Install over an existing official Omni version when possible. The application ID remains `net.omnigate.app`; the version code increases for 1.0.0. Back up important profiles before an upgrade. Do not uninstall unless you understand that local data may be lost.
3. Import a trusted profile or subscription using the actual link/QR/file tools. Select the profile and grant Android's VPN permission when connecting.
4. Use **Omni Hub** for DNS Lab, Routing Studio, Sync Center, Speed & Ping Lab, and Privacy & Backup. They were moved out of the menu, not removed from the app.
5. If a connection fails, inspect the real logs and configuration. Invalid credentials, inaccessible endpoints or network blocks are not fixed by changing the theme.

## Safe troubleshooting

Describe the Android version, device, app build, protocol and reproducible steps. Redact URLs, credentials, private keys, addresses you do not want public and personal traffic from screenshots/logs. Do not post a complete private backup.

## Checking a download

On a computer:

```sh
sha256sum Omni_1.0.0_arm64-v8a.apk
# Compare this exact value with the matching entry in SHA256SUMS.
```

Architecture-specific, universal and reduced variants are separate files with separate checksums. An APK being signed does not by itself prove it came from this project; use the official release and verify provenance.
