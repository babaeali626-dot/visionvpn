# Revision 8 — Offline Implementation Report

**Source:** user-supplied `visionvpn-test.zip` snapshot (`visionvpn-test/` root).
**Delivery mode:** offline source archive only. No GitHub access was requested for this pass.
**Snapshot label:** the ZIP comment identifies `9bfd3c5f6900eccc3bf98ae3861f1ad26395beb6`; this is an archive label, not an independently verified Git commit because `.git` metadata is absent.

## Changes made

### 1. WARP configuration no longer relies on invented defaults

- Removed the default peer public key, endpoint, interface addresses, and reserved-byte tuple from `WarpAccountConfig`.
- `isRegistered` now requires three standard Base64 32-byte WireGuard keys, a syntactically valid host and port, valid IPv4 CIDR, optional valid IPv6 CIDR, exactly three in-range reserved bytes, an MTU in the expected range, and non-empty account/token fields.
- Removed the automatic consumer registration HTTP request and its hard-coded client-identification headers from `WarpManager`. This package does not establish that the old consumer registration endpoint is a supported public third-party enrollment contract, so it now reports a clear external provisioning block instead of synthesizing a ready account.
- `ChainManager` no longer auto-registers WARP. `RoutingGraph` and `ConfigBuilder` reject incomplete WARP configuration. The built-in WARP profile is marked unavailable until a complete provider-supplied configuration exists. Selecting the unconfigured WARP profile is rejected with an explanatory message.
- The WARP UI no longer presents an editable guessed endpoint or claims that keys will be registered automatically.

### 2. Configurable profile-test timeout

- Added `AppSettings.latencyProbeTimeoutSeconds` with a 5-second default and a 1–30 second bound.
- Added a Diagnostics & Testing slider to Settings.
- `VisionViewModel` now passes this configured timeout into `PingTester`.
- Native Libbox probe concurrency remains one. The UI explicitly says so; it does not claim the screenshot's 20 simultaneous tests are supported.
- Existing HTTP probe certificate validation and the separate native startup timeout are preserved.

### 3. Backward-compatible settings decode

- Bumped the `AppSettings` model schema to version 4.
- Legacy settings JSON missing the new timeout field decodes with the 5-second default.
- The repository normalizes the timeout to 1–30 seconds and upgrades the in-record settings version on load/save. The encrypted DataStore schema version was not changed because this is a defaulted JSON-field migration, not a storage-key migration.

### 4. Regression tests added/updated

- Updated WARP graph fixtures to use structurally complete synthetic test configuration rather than malformed sample keys.
- Added a test asserting that an empty WARP configuration is not considered registered and cannot be used to build a graph.
- Added a test for the 5-second default and backward decoding of legacy settings JSON.
- Added an assertion that the built-in WARP profile is not reported ready without a validated configuration.

## Deliberately not implemented

- No Proxy Unblocker or other feature designed to bypass network filtering/restrictions was added.
- No Hiddify-style two-role chain model was introduced. The current legacy sequential chain model remains; its semantics were not silently reinterpreted.
- No Smart Connect timer, profile auto-switching, parallel native probe pool, or manual speed-test downloader was added. Libbox's process-wide ownership constraint remains in effect.
- No TLS fragmentation, ECH, arbitrary Finalmask JSON, Fake DNS, mux, or LAN proxy exposure was enabled. These require exact pinned-core support and tests before UI exposure.
- No new DNS, IPv6, split-tunnel, or fail-closed behavior was changed.
- Psiphon official provisioning values were not supplied. The current Psiphon path remains blocked unless authorized provider configuration is entered; this package does not invent ClientVersion, channel/sponsor IDs, server entries, signatures, or URLs. Live Psiphon connectivity remains unverified.
- WARP provider enrollment/terms were not verified. This package does not claim automatic registration, MASQUE, AmneziaWG, WARP+, scanner, clean-IP, or live WARP connectivity.

## Source-review inventory

The offline snapshot tree was inventoried and a focused source review was performed across the app module, Gradle/workflow files, engineering report, chain/engine code, core config/exit-IP/probe/lifecycle/security code, data models and persistence, subscription parsers, ViewModel, Compose screens/navigation, and the named regression-test area. The relevant sections of the following files were examined for the changes in this delivery; this was not a line-by-line audit of every source file or every test case:

- `ChainManager.kt`, `RoutingGraph.kt`, `LocalEngine.kt`, `LocalEngineManager.kt`, `PsiphonEngine.kt`, `WarpManager.kt`, `TorEngine.kt`, `Socks5Probe.kt`
- `ConfigBuilder.kt`, `ExitIpLookup.kt`, `IpAddressClassifier.kt`, `PingTester.kt`, `VpnCoreController.kt`, `LibboxBridge.kt`, `LibboxRuntime.kt`, `LogRedactor.kt`, `CryptoBox.kt`
- `ChainModels.kt`, `ConnectionProfileModels.kt`, `SettingsModels.kt`, `VpnModels.kt`, `VisionPreferencesRepository.kt`
- `SubscriptionFetcher.kt`, `SubscriptionFormatParser.kt`, `VisionViewModel.kt`, `HomeScreen.kt`, `ProfilesScreen.kt`, `ChainManagerScreen.kt`, `SettingsScreen.kt`, `VisionNavHost.kt`
- The regression-test directory and all nine required test filenames were inventoried. Targeted sections in the three test files changed for this delivery were examined and updated; this report does not claim that every assertion in all nine test files was exhaustively reviewed.

All nine images inside `KimiChat.zip` were extracted and inspected individually at full resolution. The four relevant supplemental WhiteVPN/Hiddify screenshots were also inspected. They are included in this package. The fifth separately uploaded screenshot was unrelated to VisionVPN and contained personal account details, so it was excluded.

## External source observations

- Psiphon's current configuration source says `PropagationChannelId`, `SponsorId`, `ClientVersion`, `TargetServerEntry`, remote server-list URLs, and the remote-list signature key are provider-network/client distribution parameters; the source comments identify the required values as supplied by or dependent on the Psiphon network. The Android MobileLibrary documentation describes using the Android library/AAR for embedded Android applications. This snapshot's process-based `libpsiphon.so` route has not been validated on a device.
- Hiddify's chain guide distinguishes an Extra Security layer after the main proxy from a Proxy Unblocker layer used to reach a filtered proxy. These are separate semantics; the current VisionVPN model does not express those roles independently. No filter-bypass implementation was added.
- The current moving Sing-box documentation was not substituted for the exact pinned v1.10.7 source. Features requiring additional version-specific proof were left unimplemented.

## Validation performed for this offline package

| Check | Result |
|---|---|
| ZIP integrity of original source snapshot and reference archive | Checked during extraction |
| Individual inspection of all 9 KimiChat images | Complete |
| Individual inspection of 4 relevant supplemental screenshots | Complete |
| Local source diff/whitespace check (`git diff --check` against a temporary local baseline created from the supplied ZIP) | PASS |
| XML resource well-formedness | PASS (10 XML files parsed) |
| Alignment verifier script unit tests (`python3 -m unittest scripts/test_verify_16kb_alignment.py -v`) | PASS (16/16) |
| Gradle build attempt (`bash ./gradlew :app:testDebugUnitTest assembleDebug --no-daemon`) | BLOCKED: wrapper JAR missing and no system Gradle executable |
| Project Gradle unit tests / Kotlin Android compile | Not run; blocked by missing build inputs listed below |
| Debug/release APK build and 16 KB binary gate | Not run; no APK was produced in this environment |
| Android emulator / physical-device tests | Not run |
| GitHub Actions CI / branch validation | Not run; offline-only delivery and no `.git` metadata |

### Build environment blockers

- No `gradle` executable was found.
- `gradlew` is present, but `gradle/wrapper/gradle-wrapper.jar` is absent.
- `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and no Android SDK path was found.
- `app/libs/libbox.aar` is absent from the source snapshot.
- Consequently, the Android build, unit test suite, packaged-ABI checks, APK verification, and device tests cannot be truthfully marked successful.

## Status by feature

| Feature | Status |
|---|---|
| WARP false-ready/default fallback hardening | `IMPLEMENTED BUT NOT TESTED` |
| WARP graph/config fail-closed validation | `IMPLEMENTED BUT NOT TESTED` |
| Profile-test timeout setting (5s default, 1–30s bound) | `IMPLEMENTED BUT NOT TESTED` |
| Legacy JSON default handling for timeout | `IMPLEMENTED BUT NOT TESTED` |
| Native test concurrency of 20 | `NOT IMPLEMENTED` (effective maximum remains 1) |
| Smart Connect scheduler and safe live switching | `NOT IMPLEMENTED` |
| Independent Proxy Unblocker/Extra Security chain roles | `NOT IMPLEMENTED` |
| Live Psiphon tunnel | `BLOCKED BY EXTERNAL REQUIREMENT` |
| Live WARP tunnel/enrollment | `BLOCKED BY EXTERNAL REQUIREMENT` |
| Android build, CI, emulator/device connectivity/leak testing | `BLOCKED BY EXTERNAL REQUIREMENT` |

## Branch/release status

This package is an offline source archive. It has no Git metadata, so the initial/final `test` branch SHA, `main` before/after SHA, working-tree status, and CI run URL cannot be verified. No checkout, commit, push, or GitHub modification was performed. The exact pinned Sing-box v1.10.7 source/docs were not independently fetched and reviewed in this offline pass, so no new version-dependent transport feature was added. No APK artifacts are included because no build was run.
