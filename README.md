# VisionVPN (Android • Kotlin • Jetpack Compose • Sing-box v1.10.7)

A production-structured, security-hardened Android VPN client built with **Kotlin**, **Jetpack Compose**, **Material 3**, and **Gradle Kotlin DSL**, styled in a **Vision UI Dark** glassmorphic theme with full **English (LTR)** and **Persian / Vazirmatn (RTL)** support.

## Core Architecture & Security Guarantees
- **Sing-box Core (`v1.10.7`):** Integrated via [SagerNet/sing-box](https://github.com/SagerNet/sing-box) (`libbox.aar`) and `LibboxBridge` with mandatory API signature verification, `ConnectivityManager.NetworkCallback` default-interface monitoring, `getConnectionOwnerUid`, socket protection (`VpnService.protect`), and Clash API WebSocket traffic telemetry (`ws://127.0.0.1:9090/traffic`).
- **True Sequential Multi-Hop Routing (`RoutingGraph`):** Supports verified chains such as `VLESS → WARP → Psiphon`, `VLESS → Psiphon → WARP`, `VLESS → Tor`, `VLESS → Custom Exit`, `Tor → Server`, and standalone `Tor` or `Psiphon` modes. Every local engine hop receives its own dedicated Sing-box loopback inbound (`mixed-upstream-<idx>`) bound strictly to its immediate predecessor tag, while circular or protocol-incompatible topologies (e.g., `Tor → WARP` UDP-over-TCP) are explicitly rejected.
- **First-Class Built-In Tor & Psiphon Exit Modes:** Selectable directly under **Settings → VPN Routing & Exit Provider** (synchronized with Chain Manager) without complex setup:
  - **Tor Mode + Bridge Manager:** Powered by [guardianproject/tor-android](https://github.com/guardianproject/tor-android) (`0.4.9.17`) + standalone `liblyrebird.so` (`obfs4`, `meek_lite`, `webtunnel`) and `libsnowflake.so` (`snowflake`). Supports direct Tor, built-in transports, and validated single/multi-line custom bridges with real `Bootstrapped 100%` readiness checks, no direct fallback in Bridge Mode, and zero bridge-secret logging.
  - **Psiphon Mode + Full Country Selector:** Powered by [Psiphon-Labs/psiphon-tunnel-core](https://github.com/Psiphon-Labs/psiphon-tunnel-core) with a searchable official ISO 3166-1 alpha-2 country list (`AUTO` + 35+ countries), structured JSON notice parsing (`Connecting` / `Established` / `Degraded` / `Failed`), and distinction between requested vs. connected tunnel state.
- **Leak Prevention & Hardening:**
  - **Fail-Closed Mode & Tor UDP Blocking:** Blocks raw UDP on TUN when exiting through Tor, prevents silent Direct fallback on engine failure, and suppresses ISP/direct DNS in strict privacy modes.
  - **IPv6 Leak Protection:** Always captures `::/0` on TUN and blocks IPv6 + `AAAA` queries when `Block IPv6 (Leak-Proof)` is active.
  - **At-Rest Encryption:** Proxy credentials, WireGuard private keys, WARP tokens, subscription URLs, and custom Tor bridges are encrypted via Android Keystore **AES-256-GCM** (`CryptoBox.kt`).
  - **Network Security:** Global cleartext traffic is disabled via `network_security_config.xml` (loopback `127.0.0.1` only for Clash API).

## Production Release Signing
Release signing is strictly separated from Debug signing in `app/build.gradle.kts`. Configure these repository secrets in GitHub (**Settings → Secrets and variables → Actions**):
- `VISION_KEYSTORE_BASE64`: Base64-encoded `.jks` release keystore
- `VISION_KEYSTORE_PASSWORD`: Keystore password
- `VISION_KEY_ALIAS`: Signing key alias
- `VISION_KEY_PASSWORD`: Signing key password

## Push to GitHub & Download the Auto-Built APK
```bash
cd vision-vpn
git init -b main
git add .
git commit -m "VisionVPN v1.1.0: hardened multi-hop, Tor bridges, Psiphon regions & tests"
git remote add origin https://github.com/<YOUR_USERNAME>/VisionVPN.git
git push -u origin main
```
Open the **Actions** tab on GitHub, select **Build & Test VisionVPN Android APK**, wait for unit tests and builds to finish, and download **VisionVPN-Release-APK** (or **VisionVPN-Debug-APK**) from **Artifacts**.