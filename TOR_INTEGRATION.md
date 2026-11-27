# Vision VPN — Tor integration status

## Implemented in this source overlay

- Embedded Android Tor daemon via Guardian Project `tor-android` (BSD-3-Clause), not SlipNet source.
- Tor is started lazily by binding `org.torproject.jni.TorService`; it does not declare or create a second `VpnService`.
- SOCKS listener is explicitly configured on `127.0.0.1` only.
- State reporting: STOPPED, STARTING, BOOTSTRAPPING, CONNECTED, STOPPING, ERROR. Bootstrap percentage is read from Tor Control `status/bootstrap-phase` when available.
- Vanilla custom Bridge lines can be supplied. Input is constrained to `Bridge ` lines to prevent torrc directive injection.
- Tor configuration file is removed after successful startup and on stop.
- Flutter page is reachable from Settings → Tor / Vision VPN.

## Not yet implemented / not to be advertised as supported

- Tor as an actual Hiddify/sing-box routing outbound is not wired. The checked-in `hiddify-core` directory is an empty Git submodule placeholder in the uploaded ZIP, so the Go core source and its config generation cannot be safely patched or regression-tested here.
- Full-Tor kill-switch routing, DNS routing enforcement, UDP blocking, per-app Tor routing and fail-closed behavior are not yet integrated.
- Snowflake, obfs4 and Meek binaries/plugins are not included. These options are intentionally not exposed as enabled.
- Tor profile persistence and encrypted bridge storage are not yet implemented.
- No claim of leak-free operation is made.

## Build dependency

`info.guardianproject:tor-android:0.4.9.17` and `info.guardianproject:jtorctl:0.4.5.7` from Guardian Project Maven. Target Android API 24+ is required by tor-android; this app targets API 36. Supported ABIs documented upstream: arm64-v8a, armeabi-v7a, x86_64.

## Lifecycle caveat

Tor runs as a separate embedded daemon service, but Hiddify continues to own the only VPN service. The app should explicitly stop Tor when the user turns off the Tor feature. Force-stop/process death is handled by Android process teardown; no persistent boot auto-start is enabled.
