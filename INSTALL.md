# Installation

This package is source code, not an installer. After integration and a successful signed build, install the APK matching the device ABI:

```bash
adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

For a universal build, use the universal APK produced by the project's supported Flutter/Gradle configuration. Verify the published SHA256 file before distribution. Do not install unsigned release artifacts.
