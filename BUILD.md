# Build guide

## Prerequisites

- Hiddify `v5.9.15` source with its required submodules.
- Flutter version pinned by upstream `pubspec.yaml` (the release metadata inspected during preparation declares Flutter `3.38.5`).
- Java 21, Android SDK and NDK versions required by the upstream tag.
- Internet access to resolve Flutter packages and Maven artifacts.

## Local build

```bash
make android-prepare
flutter analyze
flutter test
(cd packages/vision_tor && flutter test)
flutter build apk --debug --target lib/main.dart --split-per-abi
```

Release build:

```bash
flutter build apk --release --target lib/main_prod.dart --split-per-abi
```

Signing must use your own keystore. Never add it to Git. The provided `android-release.yml` accepts the GitHub Secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`; without those secrets, the workflow explicitly labels the generated release APKs unsigned.

## Status

These commands are documented from the upstream build conventions but were not executed in this Workspace. A successful build is not claimed. The Tor plugin's Kotlin compilation and runtime service binding must be verified after overlay application on the exact upstream Gradle/Flutter toolchain.
