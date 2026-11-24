# VisionVPN — Mandatory Reference Study

This document records how the four mandatory engineering references directly shaped VisionVPN's architecture, configuration schemas, platform bindings, and CI verification gates.

## 1. Psiphon Tunnel Core & Official Android Client
- **References**:
  - `https://github.com/Psiphon-Labs/psiphon-tunnel-core` (pinned tag `v2.0.28`; reviewed `psiphon/config.go`, `psiphon/remoteServerList.go`, `MobileLibrary/Android`, `ConsoleClient`)
  - `https://github.com/Psiphon-Inc/psiphon-android`
- **Key Findings Applied**:
  - `psiphon/config.go` requires `PropagationChannelId`, `SponsorId`, `ClientVersion`, and a valid server-discovery path (`TargetServerEntry` or `RemoteServerListURLs` paired with `RemoteServerListSignaturePublicKey`).
  - `RemoteServerListURLs` is serialized as `parameters.TransferURLs` (JSON array of objects containing `URL`, `OnlyAfterAttempts`, `SkipVerify`), with at least one entry having `OnlyAfterAttempts == 0`.
  - Applied in `ChainModels.kt` (`PsiphonTransferUrl`, `PsiphonChainConfig.hasServerDiscovery`) and `PsiphonEngine.kt` (`validateConfiguration()`, `buildPsiphonConfigJson()`).
  - Structured notices (`ListeningSocksProxyPort`, `AvailableEgressRegions`, `Tunnels`, `ActiveTunnel`, `UnavailableEgressRegion`) are separated so listener startup alone never triggers `CONNECTED`.
- **Architecture Decision & License**: Retained standalone `ConsoleClient` binary (`libpsiphon.so`) rather than a second `gomobile` AAR because Android APKs cannot bundle two `gomobile` runtimes (`libgojni.so` symbol clash with Sing-box `libbox.aar`). Licensed under **GPL-3.0-or-later** (documented in `THIRD_PARTY_NOTICES.md`).

## 2. Sing-box Core & Libbox (`v1.10.7`)
- **References**:
  - `https://github.com/SagerNet/sing-box` (`v1.10.7/experimental/libbox/platform.go`, `https://sing-box.sagernet.org/`)
- **Key Findings Applied**:
  - `PlatformInterface` requires explicit capability selectors (`usePlatformAutoDetectInterfaceControl`, `usePlatformDefaultInterfaceMonitor`, `usePlatformInterfaceGetter`), exact `openTun(TunOptions)` consumption, `autoDetectInterfaceControl(fd)` calling `VpnService.protect(fd)` and propagating failure, real `NetworkInterface` enumeration (`Index`, `MTU`, `Name`, `Flags`, `Addresses`), and `registerDefaultNetworkCallback` with stale-`onLost` protection.
  - Applied in `core/LibboxBridge.kt` and `core/ConfigBuilder.kt`.
- **License**: **GPL-3.0-or-later** (documented in `THIRD_PARTY_NOTICES.md`).

## 3. Orbot & Guardian Project Tor Android
- **References**:
  - `https://github.com/guardianproject/orbot-android`
  - `https://github.com/guardianproject/tor-android` (`0.4.9.17`)
  - `https://support.torproject.org/little-t-tor/circumvention/using-bridges/`
- **Key Findings Applied**:
  - Uses `info.guardianproject:tor-android:0.4.9.17` (`libtor.so`) executed as a managed child daemon with dedicated `DataDirectory`, `SocksPort`, `DNSPort`, and `ClientTransportPlugin` directives for `liblyrebird.so` (`obfs4`, `meek_lite`, `webtunnel`) and `libsnowflake.so` (`snowflake`).
  - Applied in `chain/TorEngine.kt` (generation-guarded bootstrap tracking, immediate `bootstrapped = false` reset on errors, graceful `waitFor(2s)` shutdown, SOCKS5 handshake & CONNECT probe) and `chain/TorBridgeValidator.kt` (semantic RFC 4291 IPv6 literal validation).
- **License**: **BSD-3-Clause / Tor License** (documented in `THIRD_PARTY_NOTICES.md`).

## 4. Android 16 KB Page-Size Compatibility Guide
- **Reference**:
  - `https://developer.android.com/guide/practices/page-sizes`
- **Key Findings Applied**:
  - Android 15+ devices support 16 KB memory pages; apps targeting API 35+ must align all 64-bit native ELF `PT_LOAD` segments to `16384` (`0x4000`) and align uncompressed native libraries inside the APK on 16 KB boundaries (`zipalign -c -P 16 -v 4`).
  - Upgraded CI NDK from r27 to **NDK r28 (`28.0.13004108`)** (16 KB default alignment) and added a dedicated **`Verify 16 KB ELF PT_LOAD and APK ZIP Alignment`** gate in `.github/workflows/android-build.yml` inspecting every `.so` extracted from the final APK (including `libbox.aar` and `tor-android` transitive native libraries).
