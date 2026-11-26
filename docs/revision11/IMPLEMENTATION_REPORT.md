# Revision 11 — Hiddify Core, Native Balancer and TLS Security

## Implemented in source

- Replaced the former Smart Connect `urltest` configuration with Hiddify Core's `type: balancer` outbound.
- Added the three requested strategies: `round-robin`, `consistent-hashing`, and `sticky-sessions`.
- Limited balancer members to the explicitly selected imported profiles. The current routing design deliberately does not rewrite Tor, Psiphon, WARP, or multi-hop chain graphs into a balancer.
- Moved health-check configuration out of the balancer object and into `experimental.monitoring` (`interval`, `urls`, timeout and idle settings). CI/unit tests verify that no `url`, `interval`, or `idle_timeout` is emitted inside the balancer itself.
- Updated the Android build workflow to clone Hiddify Core `v5.9.15`, run its upstream Android AAR build, and verify the generated `com.hiddify.core.libbox` and `com.hiddify.core.mobile` Java API classes, the `Mobile.setup(SetupOptions, PlatformInterface)` signature, and `setFixAndroidStack(boolean)`. The Core AAR build is configured with the 16 KB ELF page-size linker option through the upstream Android target.
- Updated the adapter to the actual Core v5 Android entry point: `com.hiddify.core.mobile.Mobile.setup(SetupOptions, PlatformInterface)`, with `SetupOptions` under `com.hiddify.core.mobile`. The per-service `Libbox.newService` API remains explicitly bound to the Android VpnService platform adapter.
- Updated reflection package names, ProGuard keep rules, app About screen, diagnostics wording and User-Agent to identify Hiddify Core.
- Enabled Hiddify Core's `FixAndroidStack` SetupOptions value on Android. This is a relevant compatibility workaround for Android Go stack handling, but it is not proof that the supplied Redmi crash has been fixed.
- Tightened TLS trick generation: mixed-case SNI is omitted for REALITY, and random padding is emitted only for non-REALITY TLS with a fixed uTLS fingerprint. Hysteria2/TUIC and random/no fingerprint profiles do not receive TLS padding. Fragmentation stays at the outbound dial level; TLS fragment and padding settings remain mutually exclusive.
- Kept the manually initiated profile measurement truthful: the value displayed in the profile list is HTTPS round-trip time (RTT) through that candidate's temporary loopback proxy, not ICMP ping.
- Studied the included Hiddify and V2RayNG screenshots when grouping the settings surface. Unsupported Finalmask, Fragment Connection, and standalone LAN SOCKS/HTTP listener controls are not presented as functional switches.

## Engine and build versions

- Hiddify Core tag: `v5.9.15` (verified as the latest Core release on 26 Nov 2026).
- Hiddify Core source's `go.mod` declares the Hiddify fork of Sing-box `v1.13.0` and Go `1.27.3`.
- Android library output expected by CI: `app/libs/hiddify-core.aar`.
- Android SDK: compile/target API 36; Gradle 8.13; Android Gradle Plugin 8.13.2; Kotlin 2.4.21; Android NDK r30 (`30.0.16248370`) for native builds and 16 KB alignment checks.
- AndroidX/Compose/network dependencies refreshed to the current stable set recorded in `app/build.gradle.kts` (Compose BOM `2026.11.01`, Core KTX `1.19.1`, Lifecycle `2.11.1`, Activity `1.13.0`, AppCompat `1.8.0`, Navigation `2.10.2`, Coroutines `1.11.0`, Serialization `1.13.0`, DataStore `1.3.0`, OkHttp `5.5.1`, CameraX `1.7.0`).
- Psiphon Tunnel Core CI source tag: `v2.0.43`. Tor Android remains pinned to `0.4.9.17`; Lyrebird/Snowflake remain pinned to their reviewed tags because this pass did not establish newer compatible release tags.
- AAR generation is performed by the upstream Hiddify Core `make android` target, not by copying an old Sing-box AAR or merely changing a Gradle version string.

## Important remaining limitations

1. **Specific Redmi crash is not symbolized.** The supplied `#00 pc 00000000005681c8` address alone is not enough to identify a source function. The new Core build plus Android stack workaround must be installed and tested first. If it still crashes, collect the full tombstone including signal, all backtrace frames, registers, memory maps, and the exact `libbox.so` build ID (`readelf -n libbox.so`). Symbolize against the exact unstripped library from that build.
2. **Native live monitoring is configured, but the UI stream is not wired.** Hiddify `experimental.monitoring` is enabled for engine health checks and balancer selection. The current app still displays its own HTTPS RTT test in the profile list; it does not yet consume Hiddify's per-outbound gRPC `OutboundInfo` stream to show native delay/IP/country values.
3. **Psiphon and WARP architecture are not converted to Hiddify-native providers.** Psiphon remains a separate local engine and WARP remains a separately built WireGuard/provider path. This revision does not claim those engines have been migrated to native Hiddify outbounds.
4. **Finalmask, Fragment Connection, LAN proxy server, and arbitrary Hiddify chain transports are not implemented.** They are called out as unavailable rather than represented by inactive settings.
5. **No APK or Android-device run was possible in the offline editing environment.** This archive contains source/configuration changes. GitHub Actions must build the Hiddify AAR, compile the Android app, and execute JVM tests. Then install the resulting debug APK and test on the Redmi Android 16 device.

## Validation status in this environment

- Source-level checks and workflow YAML parsing are run after authoring this revision.
- The full Gradle build, instrumented Android tests, AAR build, and device crash reproduction were not run here because the offline workspace has no Android SDK/NDK, no Gradle executable/wrapper JAR, and no prebuilt Hiddify Core AAR. CI is configured to provision these dependencies.
