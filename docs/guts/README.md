# Code.gs · GUTS Fetch door

**[Download Code.gs](https://github.com/imgutss/Omni-App/releases/download/v1.0.0/Code.gs)** · [View source](Code.gs) · [Setup: English](../protocols/guts.md) · [آموزش فارسی](../protocols/guts.fa.md)

Google Apps Script source for Omni's **Certificate mode (Apps Script fetch)**. Set your own `AUTH_KEY`, then deploy as a Web app and use its `/exec` URL. This mode does not need an exit node or a `/g` URL.

The published template intentionally has an empty key. Do not publish your configured copy or key. Read the setup guide's certificate/privacy notes before installing a CA.

SHA-256 of the downloadable template:

```text
ccdd7f0803c7bdc0fbae43c535afa36c32e8e182293b2bda912a11694c05d2ae
```

On a computer, `sha256sum Code.gs` should produce that value **before** you edit the key. Editing the file changes its hash. The APK `SHA256SUMS` file covers the APK downloads separately.
