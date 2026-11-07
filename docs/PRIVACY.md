# Omni privacy & data handling

**Project:** Omni — imgutss  
**Effective:** 7 November 2026

Omni is an open-source Android networking client. This document describes the app's data flows; it is not a claim that every external server or network is private or anonymous.

## On your device

Profiles, subscription settings, routing/per-app preferences, local assets and operational logs are used to run the functions you choose. Configurations can contain secrets. Hub metadata and its subscription-link copy use Android Keystore-backed protection; the underlying subscription engine also keeps an app-private record. Do not assume every local file is separately encrypted.

Android VPN, notification, camera, file-selection and installed-app access are used by the corresponding features and remain subject to Android permissions. Installing an update and uninstalling the app are different operations: uninstalling can delete app-private data.

## Network requests

Your selected VPN/proxy endpoints and DNS resolvers process traffic according to the imported configuration. Subscription providers receive refresh requests. Updates and downloaded rules/assets contact their configured hosts, which can include GitHub. Optional diagnostics, scans, speed/ping checks and pairing/sync actions can contact addresses selected by those tools. Operators of those services have their own logging and privacy practices.

Omni is not a VPN endpoint provider and cannot guarantee what a third-party endpoint records, how it routes traffic, or whether it is reachable.

## Sharing, backup and support

Exporting profiles, creating/sharing a backup, pairing, copying a subscription or sharing logs can disclose credentials or other sensitive information. Review the destination and the contents before sharing. Do not upload secrets to public issues. We may ask for a minimal, redacted diagnostic description; you control what you submit.

No public issue or repository is a secure secret-transfer channel. Revoke any token or private credential posted there accidentally.

## Control and contact

Remove profiles/subscriptions in the app, review Android permissions, and delete exported files separately if you no longer need them. A third-party service can retain its own data independently of your device.

Questions and security-report instructions: [Security policy](../SECURITY.md). General project support: [imgutss/Omni-App issues](https://github.com/imgutss/Omni-App/issues).

This policy can change with the software. The repository history records revisions. It does not replace third-party licenses or policies.
