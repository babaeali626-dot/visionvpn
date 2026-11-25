# VisionVPN — Engineering Delivery Report (Revision 8 Offline Source Hardening)

> **Revision 8 delivery note:** this report extends the Revision 7 source snapshot. The edits in this offline pass are recorded in [`docs/revision8/IMPLEMENTATION_REPORT.md`](docs/revision8/IMPLEMENTATION_REPORT.md). The WARP false-ready/default-fallback issue was hardened, and a bounded profile-probe timeout setting was added. These changes are `IMPLEMENTED BUT NOT TESTED` until the Android project build and regression suite are run with the missing build inputs. No branch/CI/device claim is made.

- WARP is no longer automatically enrolled through the previously hard-coded undocumented consumer registration endpoint or spoofed client headers. It is considered available only when the complete provider-supplied WireGuard/account configuration passes structural validation.
- WARP graph/config construction now fails closed when that configuration is incomplete; UI status no longer claims that keys will be registered automatically.
- Profile HTTPS probe timeout is configurable from 1–30 seconds (default 5 seconds). The effective native Libbox probe concurrency remains **1**, not the 20 shown in the reference screenshot.
- Smart Connect, separate Hiddify chain roles, live WARP/Psiphon connectivity, device routing/leak tests, and Android build/CI validation are not complete. No network-filter bypass layer was added.
- This delivery is a source ZIP only. The input archive contains no `.git` metadata; remote branches and their SHAs were not queried or changed.

---

# VisionVPN — Engineering Delivery Report (Revision 7 Final Source Remediation)

This report strictly uses the five required status labels:
- `IMPLEMENTED AND TESTED`
- `IMPLEMENTED BUT NOT TESTED`
- `PARTIALLY IMPLEMENTED`
- `NOT IMPLEMENTED`
- `BLOCKED BY EXTERNAL REQUIREMENT`

No full Gradle build, project JUnit execution, GitHub Actions push, or Android device/emulator test was executed in this workspace. The workspace has no Android SDK, no `gradle` executable, no `gradle-wrapper.jar`, and no `app/libs/libbox.aar`. A limited set of pure Kotlin components was compiled and exercised with the installed Kotlin/JVM `1.9.0` compiler and the bundled coroutines runtime; this is not a substitute for the project Kotlin `2.0.21` Android build. All build/runtime/device items are separated honestly from source-level and isolated verification.

---

## A. Defect Remediation Table (Priorities 0–10)

| Priority | Severity | Root Cause | Implemented Fix | Regression Test | Status |
|---|---|---|---|---|---|
| **P0** | Critical | Headless probes and VPN startup were not coordinated across all native Libbox ownership transitions; failure paths could release a lease before native cleanup was confirmed. | Extended the token lease state machine with `BLACKHOLE_STARTING`, `BLACKHOLE_ACTIVE`, and `FAULTED`; `releaseVpnLease()` refuses release while a blocking TUN is installed. Native service ownership is registered before `start()`, and cleanup failures retain the object/descriptor and quarantine the process-wide runtime. Headless cleanup failures stop further batch scheduling. ViewModel cancellation now joins the active probe before requesting VPN startup. | `StabilitySecurityAndProfilesTest`: lease retention, quarantine wrapper, faulted batch stop, setup retry, 250-profile bounded pool. | `IMPLEMENTED BUT NOT TESTED` |
| **P1** | High | Earlier Fail-Closed cleanup closed the active TUN before establishing a replacement; child-engine or native cleanup errors could also be reported as stopped without confirmed shutdown. | Blackhole handover now establishes a replacement full-device IPv4/IPv6 TUN first and closes the old native service/descriptors only after the OS accepts the replacement. It intentionally applies no split-tunnel allow/disallow rules while locked down, so bypass apps and the app UID are captured too. All startup/verification/engine failure paths after TUN establishment attempt the blackhole transition. Disconnect retains the service/lease and reports retry when child or native cleanup cannot be confirmed. A blocking TUN cannot lose its lease until its descriptor is confirmed closed. | Unit-level state-machine tests cover lease retention and failed handover; full OS packet-drop/seamless-handover behavior remains device-only. | `IMPLEMENTED BUT NOT TESTED` |
| **P2** | High | `SplitTunnelMode.ONLY_SELECTED` fell back to `addDisallowedApplication(self)` when 0 valid external apps were selected, silently widening VPN to all other apps. | Added `LibboxBridge.resolveSplitTunnelPlan()` to deduplicate, strip `selfPackageName`, verify installed packages, and fail closed with an actionable error when `ONLY_SELECTED` resolves to 0 valid external apps. | `StabilitySecurityAndProfilesTest`: empty, self-only, uninstalled, mixed, and `BYPASS_SELECTED` cases. | `IMPLEMENTED BUT NOT TESTED` |
| **P3** | High | `ConfigBuilder.validateProfile()` checked REALITY `pbk` with a permissive regex/length >= 6, accepted odd-length hex `sid`, and tested with a non-32-byte fixture. | Enforced strict Base64URL (`RawURLEncoding`) 32-byte X25519 decode for `public_key`, empty or even-length hex <= 16 chars for `short_id`, supported uTLS fingerprints, and `insecure = false` for REALITY. Replaced test fixture with a deterministic 32-byte Base64URL key. | `StabilitySecurityAndProfilesTest`: 32-byte key decode, odd/non-hex/overlong `sid`, unsupported uTLS, generated JSON verification. | `IMPLEMENTED BUT NOT TESTED` |
| **P4** | High | `SubscriptionFetcher` enabled `followRedirects(true)` and did not validate per-hop redirects, A/AAAA DNS answers, or DNS rebinding. | Disabled automatic redirects (`followRedirects(false)`, `followSslRedirects(false)`), implemented manual bounded redirects (`MAX_REDIRECTS = 4`) with per-hop HTTPS/userinfo/IANA validation, and installed `SafePublicDns` on OkHttp so exact validated `InetAddress` lists are used and mixed public/private records are rejected. | `StabilitySecurityAndProfilesTest`: HTTP, userinfo, localhost, RFC1918, metadata, documentation, and mixed DNS record rejection. | `IMPLEMENTED BUT NOT TESTED` |
| **P5** | Medium | The initial classifier missed IPv6 space outside Global Unicast, the Dummy IPv6 Prefix, and NAT64 representations that could wrap a private IPv4 destination. | `IpAddressClassifier.kt` now requires IPv6 Global Unicast (`2000::/3`) unless an IANA globally-reachable exception applies; explicitly rejects special-purpose/non-global allocations and validates the embedded IPv4 address for `64:ff9b::/96` NAT64. It remains a no-DNS literal parser and is wired into the exit-IP and subscription destination checks. | Isolated Kotlin harness exercised public/private IPv4, TEST-NET, IPv6 boundaries, IANA exceptions, Dummy Prefix, and public/private NAT64 cases; project JUnit remains pending. | `IMPLEMENTED BUT NOT TESTED` |
| **P6** | Medium | `Socks5Probe.verifyTlsOverSocks5()` was unused; `TorEngine.isHealthy()` and `PsiphonEngine.isHealthy()` only checked TCP-level `CONNECT`. | Wired rate-limited (20s cached) `Socks5Probe.verifyTlsOverSocks5()` (remote `ATYP=0x03` + system CA/HTTPS hostname verification) into `TorEngine.isHealthy()` and `PsiphonEngine.isHealthy()`, updating status labels accurately. | Existing `Socks5ProbeTest` + `TorBridgeAndEngineTest` + `PsiphonEngineTest`. | `IMPLEMENTED BUT NOT TESTED` |
| **P7** | Medium | `VisionViewModel.setProfileAllowInsecure()` had no UI entry point for explicit user opt-in when an untrusted import requested `allowInsecure`. | Added an explicit per-profile security icon button and MITM-risk confirmation `AlertDialog` in `ProfilesScreen.kt` (wired via `VisionNavHost.kt`) for profiles with `importedInsecureRequested || allowInsecure`. Remains `false` by default on all imports. | `StabilitySecurityAndProfilesTest`: untrusted URI/Clash/Sing-box import flags. | `IMPLEMENTED BUT NOT TESTED` |
| **P8–P10** | Medium | Chain UDP matrix, local profile consistency, and privacy defaults required end-to-end audit. | Preserved pre-core/post-core engine ordering, conservative UDP capability matrix, unified `BuiltInProfilesCatalog`, locked English LTR + Vision Dark UI with storage backward compatibility, and `LogRedactor` secret stripping. Build/workflow files kept untouched. | `RoutingGraphAndConfigBuilderTest` + `StabilitySecurityAndProfilesTest`. | `IMPLEMENTED BUT NOT TESTED` |

---

## B. Authoritative References Used (Verified 2026-11-25)
- **IANA IPv4 Special-Purpose Address Registry**: `https://www.iana.org/assignments/iana-ipv4-special-registry/iana-ipv4-special-registry.xhtml` (RFC 6890, RFC 1918, RFC 6598, RFC 5737, RFC 7723, RFC 8155).
- **IANA IPv6 Special-Purpose Address Registry**: `https://www.iana.org/assignments/iana-ipv6-special-registry/iana-ipv6-special-registry.xhtml` (RFC 6890, RFC 4193, RFC 3849, RFC 9637).
- **Sing-box v1.10.7 Pinned Target**: `https://github.com/SagerNet/sing-box/tree/v1.10.7` & `https://sing-box.sagernet.org/configuration/shared/tls/`.
- **Android VpnService & Builder**: `https://developer.android.com/reference/android/net/VpnService`, `https://developer.android.com/reference/android/net/VpnService.Builder`.
- **OkHttp 4.12.0**: `https://github.com/square/okhttp/tree/parent-4.12.0`.

---

## C. Build, CI & Device Verification Boundaries
- **Source/Offline Checks (`git diff --check`, 16/16 `scripts/test_verify_16kb_alignment.py`, XML well-formedness, Kotlin delimiter scan)**: `IMPLEMENTED AND TESTED`
- **Isolated pure-Kotlin component checks (LibboxRuntime lease/quarantine, LocalEngineManager stop retry, IpAddressClassifier boundary/NAT64 cases) using Kotlin/JVM 1.9.0**: `IMPLEMENTED AND TESTED` (limited harnesses only; not a project build)
- **Gradle Unit Tests & Debug APK (`./gradlew :app:testDebugUnitTest assembleDebug --no-daemon`)**: `BLOCKED BY EXTERNAL REQUIREMENT` (no Android SDK, no Gradle executable, no wrapper JAR, and no local `libbox.aar`; project Kotlin 2.0.21 build cannot be confirmed).
- **GitHub Actions CI (`.github/workflows/android-build.yml`) & 16 KB ABI APK Gate**: `BLOCKED BY EXTERNAL REQUIREMENT` (workflow preserved untouched; must be triggered by pushing `test` to GitHub).
- **Android Device / Emulator Verification Matrix (VPN connect/disconnect, Fail-Closed blackhole TUN, OS Always-on/Lockdown, `ONLY_SELECTED` per-app routing, 250-profile bulk test, Tor/Psiphon/WARP chains, DNS/IPv6/UDP leak tests)**: `BLOCKED BY EXTERNAL REQUIREMENT` (requires physical Android device or emulator).
- **Live Psiphon Connectivity**: `BLOCKED BY EXTERNAL REQUIREMENT` (requires official Psiphon Inc. provisioning credentials and CI-built `libpsiphon.so`).
- **Branch Safety**: These final edits were made only in the uploaded source workspace. No GitHub push or branch update was performed; the ZIP must be uploaded to the remote `test` branch. Remote `main` was not modified.


---

## D. Revision 7 Final Source-Level Corrections

- `LibboxRuntime.markFaulted()` no longer reads the mutable blocking-TUN flag as an unsynchronized default-argument expression; callers may explicitly update it while the state lock is held, otherwise the current value is preserved.
- `LibboxBridge.startCore()` records the newly created native service before invoking `start()`. If `start()` fails after `openTun()` has already established a TUN, Fail-Closed mode attempts a seamless blocking-TUN handover; if that handover fails, the prior VPN descriptor/service/lease remain retained for explicit Disconnect.
- Fail-Closed transition uses a replacement TUN with full IPv4/IPv6 default routes and no per-app exclusions. It does not persist or mutate the user's split-tunnel preferences.
- `LocalEngineManager` retains ownership of child processes until `stop()` succeeds. Failed cleanup remains `FAILED / Cleanup Required`; cancellation no longer overwrites that condition with a false `STOPPED` state. The foreground service and Home disconnect action now remain available while a child process still needs cleanup. `VisionViewModel.stopVpn()` also cancels a pending start coroutine before dispatching Disconnect, preventing a late connect request.
- `VpnCoreController` performs child/native cleanup in `NonCancellable` sections and retains TUN/lease ownership when cleanup is not confirmed. A stale `VpnService` reference is cleared only after Libbox cleanup succeeds.
- `IpAddressClassifier` conservatively requires IPv6 Global Unicast or an explicit IANA global-reachability exception, and the well-known NAT64 prefix is accepted only when its embedded IPv4 is globally reachable.

These changes are source-level fixes. Do not interpret them as evidence that Android's actual packet-routing, native Libbox close behavior, or seamless TUN handover has been verified on a device.
