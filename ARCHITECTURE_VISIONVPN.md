# Vision VPN architecture notes

- Flutter UI: `lib/features/tor/`
- Platform bridge: `visionvpn.tor/method`, `visionvpn.tor/events`
- Android Tor lifecycle: `android/.../tor/TorEngineManager.kt`
- Tor native daemon: Guardian Project `TorService` and bundled `libtor.so` from Maven AAR
- Existing Hiddify VPN lifecycle remains unchanged; no second `VpnService` is introduced.
- Core routing integration is deliberately deferred because `hiddify-core` is an empty submodule placeholder in the uploaded archive. Do not route production traffic through this build as if Tor were active.
