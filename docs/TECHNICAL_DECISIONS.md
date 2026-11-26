> **Status:** Historical log. TD-01 is superseded by Revision 11: Hiddify Core v5.9.15 now replaces the former standalone Sing-box v1.10.7 target. TD-06 is updated to Android API 36 / AGP 8.13.2; Tor remains a native-only AAR.

# VisionVPN — Technical Decisions Log

## TD-01: Previous standalone Sing-box `v1.10.7` pin (superseded by Revision 11)
- **Decision**: Retain pinned Sing-box `v1.10.7` until an online CI runner with Android SDK + physical device can validate `v1.14.4` `PlatformInterface` breaking changes and `libbox.aar` regeneration.
- **Rationale**: Blindly bumping `SING_BOX_VERSION` without compiling and testing `libbox.aar` risks runtime signature mismatches.

## TD-02: Standalone Native Binaries (`libpsiphon.so`, `liblyrebird.so`, `libsnowflake.so`) vs. Multiple `gomobile` AARs
- **Decision**: Keep `libbox.aar` as the sole `gomobile` AAR and package Tor pluggable transports and Psiphon as standalone cross-compiled executables in `jniLibs/<abi>/`.
- **Rationale**: Bundling more than one `gomobile` AAR in the same Android APK causes duplicate `libgojni.so` symbol collisions.

## TD-03: Capability-Based Multi-Hop Routing Graph
- **Decision**: Model every hop with explicit `NodeCapabilities` (`supportsTcp`, `supportsUdp`, `requiresUdpTransport`, `supportsUpstreamSocks`, `canBeStandalone`) rather than ad hoc booleans.
- **Rationale**: Automatically rejects all UDP-over-TCP mismatches (`Tor → WARP`, `Psiphon → WARP`, `Tor → Hysteria2`, `Psiphon → TUIC`, etc.) and detects loopback port collisions before launching any engine.

## TD-04: Fail-Closed Storage Migration Barrier
- **Decision**: `VisionPreferencesRepository` uses `MigrationState` (`NOT_STARTED`, `RUNNING`, `COMPLETE`, `FAILED`) and a `CompletableDeferred<Boolean>` barrier that blocks subsequent writes if migration fails.
- **Rationale**: Prevents partial saves from advancing schema version early or overwriting unreadable encrypted user credentials with default objects.

## TD-05: Android 16 KB Page-Size Release Gate
- **Decision (historical baseline)**: CI initially used NDK `28.0.13004108` (r28); Revision 11 upgrades the build to NDK r30 (`30.0.16248370`) and enforce `readelf -l` (`PT_LOAD` `0x4000`) + `zipalign -c -P 16 -v 4` on the final assembled APK.
- **Rationale**: Mandatory for API 35+ Android 15 devices and Google Play 16 KB compliance.


## TD-06: Consume Guardian Project Tor Android as a Native-Only AAR
- **Decision**: Resolve `tor-android:0.4.9.17` in a dedicated non-consumable/non-compile configuration, extract only `jni/<abi>/libtor.so` into generated `jniLibs`, and do not add the AAR to Android compile/runtime classpaths.
- **Rationale**: VisionVPN launches `libtor.so` directly with `ProcessBuilder` and does not use the AAR's Android wrapper classes, resources, or manifest. The published AAR declares `minCompileSdk=37`, while the previous application used `compileSdk=35` / AGP 8.7.3. Revision 11 moves the app to compileSdk/targetSdk 36 and AGP 8.13.2 while continuing native-only extraction. Native-only extraction avoids an unnecessary AGP/Gradle/Kotlin major migration without suppressing AAR metadata checks for actual Android dependencies. The extracted library remains subject to strict ABI and 16 KiB ELF/APK verification.
