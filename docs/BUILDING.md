# Build & release

The app project is `V2rayNG/`. Initialize the pinned submodules with `git submodule update --init --recursive`. Android/NDK, Rust, Go, dependency pins and build commands are specified in `.github/workflows/omni-build.yml` and the engine build scripts.

## Official release workflow

Use **Actions → Omni APK → Run workflow**, version `1.0.0`. Official publication requires both the build and Android native UI smoke job. Never publish an APK on the basis of a screenshot alone.

`ui_only=true` restores hash-pinned, previously audited native binaries. Its guard rejects changes to protected native/core sources or dependencies. Only exact project source/update/privacy URLs have a narrowly tested normalization exception; runtime/native configuration remains pinned. Engine changes require a full native build.

The native baseline is the verified **1.0.0 release in this repository**, with its exact source commit and four APK checksums recorded in `tools/omni-ui-native-base.json`. All four baseline artifacts must pass SHA-256 verification before extraction. Future UI-only builds do not depend on downloading an old application release from the legacy repository. Keep the pinned 1.0.0 artifacts and source available for reproducible restoration.

## Signing

Official upgrades must keep the existing private Android signing identity. The CI repository needs four protected Actions secrets:

- `OMNI_KEYSTORE_BASE64`
- `OMNI_KEYSTORE_PASSWORD`
- `OMNI_KEY_ALIAS`
- `OMNI_KEY_PASSWORD`

Do not commit, print, upload as an artifact, or replace the signing key with an unrelated key. Private keys cannot be read back through the GitHub Secrets API. Keep a secure backup outside Git.

The expected official certificate SHA-256 is:

```
1ECDAC1CAC8E8E318EE05056AE890FFC59EAD7F6F450905AFEC287C9CC18ACF9
```

## Tests

```sh
python3 -m unittest discover -s tools -p test_omni_branding_guard.py
cd V2rayNG
./gradlew :app:testPlaystoreDebugUnitTest :app:testFdroidDebugUnitTest
```

The workflow additionally checks signatures/native payloads and exercises the actual signed APK on an Android 15 emulator. The UI test emits real PNG/XML captures and the 43-destination screen map. Private activity captures on a rooted debug emulator do not establish normal user navigation into those private activities. Real tunnel connectivity, physical-device audio/haptics and broad network compatibility need separate testing.

For a local development build follow the Gradle/native scripts rather than pasting production signing secrets into a shell history.
