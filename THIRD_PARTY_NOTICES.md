# Third-Party Open-Source Notices

VisionVPN integrates and depends on the following open-source projects. All upstream copyright notices and license obligations must be preserved when distributing compiled binaries.

| Component | Upstream Repository | License | Usage in VisionVPN |
| :--- | :--- | :--- | :--- |
| **Hiddify Core (`hiddify-core.aar`)** | https://github.com/hiddify/hiddify-core | GPL-3.0-or-later | Primary VPN TUN, proxy routing, Hiddify balancer and native monitoring core (v5.9.15; Hiddify fork of Sing-box v1.13.0) |
| **Tor Android (`tor-android`)** | https://github.com/guardianproject/tor-android | BSD-3-Clause / Tor License | Embedded Tor daemon (`libtor.so`, extracted from the native-only `tor-android:0.4.9.17` AAR) |
| **Lyrebird** | https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird | BSD-3-Clause | Tor pluggable transports (`obfs4`, `meek_lite`, `webtunnel`) |
| **Snowflake** | https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake | BSD-3-Clause | Tor Snowflake WebRTC pluggable transport |
| **Psiphon Tunnel Core** | https://github.com/Psiphon-Labs/psiphon-tunnel-core | GPL-3.0-or-later | Local Psiphon tunnel engine (`libpsiphon.so`, v2.0.43) |
| **AndroidX / Jetpack Compose / Material 3** | https://developer.android.com/jetpack | Apache-2.0 | UI, Navigation, DataStore, CameraX, Lifecycle |
| **Kotlinx Coroutines & Serialization** | https://github.com/Kotlin | Apache-2.0 | Asynchronous concurrency & JSON serialization |
| **OkHttp** | https://github.com/square/okhttp | Apache-2.0 | HTTPS subscriptions, WARP API, Clash API WebSocket |
| **ZXing Core** | https://github.com/zxing/zxing | Apache-2.0 | QR code decoding |

## GPL-3.0 Compliance Note
Because Hiddify Core (including its Sing-box-derived core) and `psiphon-tunnel-core` are licensed under **GNU GPLv3**, any public distribution of compiled VisionVPN APKs bundling these components must comply with GPLv3 source-availability and notice requirements.
