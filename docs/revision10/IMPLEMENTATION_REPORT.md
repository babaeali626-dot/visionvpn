# VisionVPN Revision 10 — Corrective Source Delivery Report

**Delivery:** offline source ZIP for replacement of the project working tree.  
**Remote baseline checked at start:** `test` = `fa3dca7d916faf9ab18b5906da36dc1548f5a6d8`; `main` = `0883deb1a04d5cd92a12138af79f65502439b903`.  
**Git status:** this archive has no `.git` metadata. No commit or push was made; no remote branch was modified.  
**Scope:** fix the specifically reported test-lifecycle defect and implement a real, bounded set of requested diagnostics/settings/Smart Connect/exit-IP improvements without inventing provider credentials or unsupported Sing-box fields.

## Reference material reviewed

The supplied Revision 9.3 master prompt and the Revision 8 source snapshot/report were used as the implementation basis. The screenshot set preserved in `docs/revision8/references/` was reviewed as individual files, not only as a contact sheet:

**KimiChat / V2RayNG / Hiddify (9 images):**
- `01-v2rayng-general-settings.jpg`
- `02-v2rayng-vpn-dns-ipv6.jpg`
- `03-v2rayng-mtu-routing.jpg`
- `04-v2rayng-core-local-proxy.jpg`
- `05-v2rayng-socks-dns.jpg`
- `06-v2rayng-mux-fragment-subscription.jpg`
- `07-v2rayng-test-startup-settings.jpg`
- `08-v2rayng-vpn-mode.jpg`
- `09-hiddify-settings-navigation.jpg`

**WhiteVPN / Hiddify supplemental (4 images):**
- `10-whitevpn-connection-security.jpg`
- `11-whitevpn-testing-settings.jpg`
- `12-hiddify-chain-extra-security-off.jpg`
- `13-hiddify-chain-psiphon-extra-security.jpg`

The separately uploaded VisionVPN Home screenshot was also reviewed for the missing exit-IP value and the `Needs official Psiphon provisioning` state.

## Summary of source changes

### Implemented in source (not Android-build/device verified)

1. **Profile tests no longer cancel on screen navigation.** Removed the `ProfilesScreen` disposal callback that cancelled the ViewModel-owned latency-test job. Test progress remains in the ViewModel `StateFlow` and is restored when the user returns to Profiles. The explicit Cancel button remains. Starting a VPN still waits for the headless test service to cancel and close safely before claiming Libbox.
2. **WhiteVPN-style Diagnostics & Testing controls.** Added persisted settings with backwards-compatible JSON defaults: HTTPS probe timeout `5 s` (`1–30`), real concurrent profile probes `20` (`1–100`), and manual speed-download size `1 MiB` (`1–100`). Added an editable HTTPS delay-test URL, auto-sort preference, and explanatory units/behavior.
3. **Shared-core parallel profile testing.** Implemented a no-TUN diagnostic config with one Libbox service, one loopback inbound and one isolated outbound per candidate. HTTPS probes run in a bounded coroutine worker pool over those distinct inbounds. No independent Go/Libbox runtime is spawned per profile. Batches are capped at 100 candidates and processed sequentially for larger subscriptions. Cleanup closes the shared service in `NonCancellable` and retains the existing runtime quarantine behavior. The design is source-implemented but has not been validated against a packaged `libbox.aar` in this environment.
4. **Manual speed test.** Added a bounded Cloudflare speed-test download through the active VPN's loopback probe inbound. It streams in 32 KiB chunks, enforces `1–100 MiB`, validates byte count, uses normal HTTPS certificate validation, exposes progress, and supports cancellation. It is manual only; Smart Connect does not trigger the large download.
5. **Smart Connect on Home.** Added Home-screen enable/disable and explicit profile-selection UI. Selection is persisted only when the user presses Done (not on each checkbox, to avoid repeated VPN restarts). The generated Sing-box config uses the pinned upstream `urltest` outbound with the selected, validated candidates, configurable interval (default `60 s`), candidate cap and tolerance. It changes the actual final outbound for new connections. Manual profile selection disables Smart Connect. Deleted profiles are pruned. **Current support is intentionally limited to a single imported-proxy route**; enabling it with Tor/Psiphon/WARP/legacy chain roles is rejected rather than silently changing the route.
6. **Exit-IP lookup fallback and flag.** Added `https://api.ip.sb/geoip` as an additional HTTPS fallback after the existing country.is and Cloudflare endpoints. The lookup continues through the app's loopback proxy inbound (not the phone's direct network). Country code is parsed and converted to an ISO flag. A returned country is not inferred from the requested Psiphon/WARP region.
7. **Connection compatibility/security.** Changed new-install MTU default from `9000` to `1500`, bounded the persisted value to `1280–1500`, and wired it into generated TUN configuration. Added ECH Auto setting only through the upstream Sing-box v1.10.7 outbound TLS ECH object. Added persisted core log-level setting and opt-in auto-sort-after-test control.
8. **Backward compatibility and tests.** Bumped the AppSettings JSON schema to version 5 while preserving defaulted decoding for older records. Added/updated tests for defaults, selected Smart Connect outbounds, shared diagnostic inbound routing, ECH enablement, MTU and logging. The test suite was not executable in this local environment.

## Settings coverage in this delivery

| Area | What is changed or available | Status |
|---|---|---|
| Diagnostics & Testing | Timeout, HTTPS URL, concurrency, download MiB, manual streamed speed test, progress/cancel | `IMPLEMENTED BUT NOT TESTED` |
| Profile test lifecycle | Navigation-independent ViewModel ownership; explicit cancel retained | `IMPLEMENTED BUT NOT TESTED` |
| Smart Connect | Home toggle, user allowlist, 60-second default URLTest group, candidate cap, tolerance, manual pin behavior | `PARTIALLY IMPLEMENTED` — simple imported-proxy route only; current-member telemetry and round-robin candidate windows are not added |
| Connection Security | ECH Auto through pinned-core TLS field; certificate validation defaults preserved | `IMPLEMENTED BUT NOT TESTED` |
| TLS fragmentation / Fragment Connection / Finalmask | No active controls emitted into upstream 1.10.7 because those Hiddify-specific fields are not supported by that pinned upstream core | `NOT IMPLEMENTED` |
| VPN/TUN | MTU default/bounds and generated TUN effect | `IMPLEMENTED BUT NOT TESTED` |
| DNS / Routing / Split tunnel | Existing Revision 8 functionality retained; diagnostic config adds per-inbound DNS routing through each candidate's outbound | `IMPLEMENTED BUT NOT TESTED` for the new diagnostic path; no broad redesign |
| Profiles & Subscriptions | Existing groups retained; auto-sort-after-test is configurable | `IMPLEMENTED BUT NOT TESTED` |
| Core Logs | Persisted level (error/warn/info/debug/trace) wired into core JSON | `IMPLEMENTED BUT NOT TESTED` |
| Inbound / Local Proxy | Existing loopback probe inbound retained. No LAN-exposed SOCKS/HTTP listener added | `PARTIALLY IMPLEMENTED` |
| General / About | Existing app behavior retained; no full Hiddify-style category/navigation redesign in this delivery | `PARTIALLY IMPLEMENTED` |
| Hiddify Extra Security / Proxy Unblocker | No independent two-role data model or actual Proxy Unblocker route implemented in this delivery | `NOT IMPLEMENTED` |
| Psiphon | Existing standalone/post-core local SOCKS engine and official provisioning guard retained; no invented IDs/keys/server lists | `PARTIALLY IMPLEMENTED` / live connection `BLOCKED BY EXTERNAL REQUIREMENT` |
| WARP | Existing fail-closed provider-config validation retained; no undocumented consumer enrollment or fake profile | `BLOCKED BY EXTERNAL REQUIREMENT` |

## Important Hiddify / Psiphon compatibility finding

The inspected Hiddify Sing-box v5 source is a substantially newer fork, not a drop-in compatible layer for VisionVPN's upstream Sing-box v1.10.7 Libbox binary. Its Psiphon integration is a native `type: psiphon` outbound, with configuration, per-network state/cache, scanner options and CDN-edge integration in the Go core. Its documented role defaults differ: a first-hop Psiphon (Proxy Unblocker) enables CDN-edge scanning by default; Psiphon behind a detour (Extra Security) does not scan by default and uses TCP-oriented protocols/tactics-off behavior. VisionVPN's current `PsiphonEngine` is a separate process/`libpsiphon.so` plus local SOCKS architecture, not that native Hiddify outbound. A cosmetic role selector would not implement the actual detour/scanner behavior and could create a TUN capture loop. Therefore this ZIP does **not** claim that Hiddify's two Psiphon roles or CDN scanner are complete.

Reviewed upstream identifiers:

- Hiddify `hiddify-sing-box` ref `v5` resolved to commit `4891a0c68ef27353dfaab3a35832239e60176e7f`.
- `CluvexStudio/psiphon-tunnel-core` master resolved to `da4b25312e670d502389656b7157ee488d74eaee`.
- Sing-box pinned version remains v1.10.7. Its upstream TLS ECH and URLTest support are used; Hiddify fork-only `tls_fragment` / `finalmask` fields are not injected.
- No Hiddify or Psiphon code was copied into this source tree, so no upstream source license was silently imported. Replacing the core with Hiddify v5 requires a separate Go/Libbox/Android AAR/ABI/license migration and CI build proof.

### Psiphon provider blocker

The current VisionVPN configuration still requires legitimate Psiphon `ClientVersion`, `PropagationChannelId`, `SponsorId`, and valid server-discovery material (embedded entries or signed remote server list and signature key). These must be supplied/authorized by the provider/client distribution. This delivery does not fabricate or copy identifiers from Hiddify or another client, bypass signature checking, or set `SkipVerify` to force a false-ready state. Live Psiphon network connectivity, CDN scan, Proxy Unblocker, and role-specific Hiddify scanner behavior remain blocked until a compatible engine build and authorized provisioning path exist.

### WARP provider blocker

Revision 8's removal of the undocumented registration request and guessed fallback keys is preserved. No WARP profile is marked registered without a complete, trustworthy WireGuard configuration. Live WARP enrollment/connection and Hiddify-specific WARP modes/scanner remain unverified and externally blocked.

## Validation actually performed

| Check | Result |
|---|---|
| `git diff --check` against a temporary local baseline | PASS |
| Android XML parsing | PASS — all 10 XML resources parsed |
| `python3 -m unittest scripts/test_verify_16kb_alignment.py -v` | PASS — 16/16 |
| Kotlin/JUnit Android compile | NOT RUN / BLOCKED — Android toolchain and dependencies unavailable |
| Standalone kotlinc syntax attempt | Timed out before producing diagnostics; not a compile result |
| Gradle wrapper | `gradle-wrapper.jar` is absent from supplied snapshot |
| System Gradle | Not installed |
| Android SDK | `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset; no SDK found |
| `app/libs/libbox.aar` | Absent from supplied snapshot |
| APK assembly / ABI / 16 KB APK gate | NOT RUN — no APK produced |
| GitHub Actions | NOT RUN — no commit was pushed |
| Android device/emulator traffic | NOT RUN |

No APK is included. The local checks do not prove the new Kotlin code compiles or that traffic works on a device. The next required verification is a full CI build/test on the `test` branch with the workflow-provided Android SDK, Gradle and `libbox.aar`, followed by a real device test.

## Branch and file delivery

- Base source corresponds to the `test` snapshot checked at `fa3dca7d916faf9ab18b5906da36dc1548f5a6d8` at the start of this work. The source ZIP intentionally excludes `.git`.
- `main` was observed at `0883deb1a04d5cd92a12138af79f65502439b903`; no Git write or branch mutation was performed.
- Source files changed: `ConfigBuilder.kt`, `ExitIpLookup.kt`, `PingTester.kt`, `VpnCoreController.kt`, `VisionPreferencesRepository.kt`, `SettingsModels.kt`, `SubscriptionProfileManager.kt`, `VisionNavHost.kt`, `HomeScreen.kt`, `ProfilesScreen.kt`, `SettingsScreen.kt`, `VisionViewModel.kt`, three test files, and the executable bit on `gradlew`.
- This is an offline replacement package, not a commit/push or APK release.
