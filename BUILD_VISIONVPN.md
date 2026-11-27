# Build Vision VPN

## Prerequisites
- Flutter 3.38.5 / Dart SDK matching `pubspec.yaml`
- JDK 17
- Android SDK 36 and NDK 28.2.13676358
- Git with access to `hiddify-core` submodule

## Get complete source
The supplied ZIP has an empty `hiddify-core` submodule directory. Android builds obtain the matching prebuilt core AAR from the exact `core.version` release URL via the upstream Makefile. If you intend to modify Go core source, initialize the submodule in a Git clone with `git submodule update --init --recursive`.

## Build

```bash
make android-prepare
flutter analyze
flutter test
flutter build apk --debug --split-per-abi
```

The resulting APKs are under `build/app/outputs/flutter-apk/`. A successful build has not been run in this workspace because Flutter and Android SDK are not installed. The Android Makefile preparation step downloads the matching prebuilt core AAR; Go-core source editing still requires the submodule.
