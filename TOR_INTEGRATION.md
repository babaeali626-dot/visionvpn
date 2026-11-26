# Tor integration

## Implementation source

SlipNet is used as an architectural reference only: explicit Tor states, transport capability declaration, bridge/profile management and visible bootstrap progress. No SlipNet source files or binaries are copied into this package.

The Tor daemon dependency is `info.guardianproject:tor-android:0.4.9.17` and `jtorctl:0.4.5.7`. Guardian Project describes `tor-android` as a native Android TorService with a local control socket and Android ABI binaries. The Maven Central metadata for 0.4.9.17 declares BSD 3-Clause.

## Lifecycle

The plugin configures Tor's app-private torrc, then binds to `org.torproject.jni.TorService` with `BIND_AUTO_CREATE` (the binding creates the service). It polls `status/bootstrap-phase`, observes the service's global status broadcasts, and requires the service's first-circuit `STATUS_ON` signal plus a successful TCP connection to `127.0.0.1` before exposing an outbound fragment.

States exposed to Flutter:

- STOPPED
- STARTING
- BOOTSTRAPPING
- CONNECTED
- RECONNECTING
- STOPPING
- ERROR

Only one start can be active per plugin instance. The client keeps one service binding while Tor runs. Stop unbinds; Guardian TorService runs its shutdown path when the final binding disappears. Hiddify's existing VPNService remains the only VPN service.

## Supported transports in this package

| Transport | Availability | Reason |
|---|---|---|
| Tor Direct | Implemented in source; runtime unverified | Native Tor engine is resolved as a Gradle dependency |
| Custom direct bridge | Implemented in source; runtime unverified | Validates and writes direct `Bridge host:port [fingerprint]` lines |
| obfs4 | Disabled | `obfs4proxy` Android binary is not included |
| Snowflake | Disabled | Snowflake client Android binary is not included |
| Meek | Disabled | Meek client Android binary is not included |

No UI option is marked available solely because a transport name exists. Adding a bridge line for a PT without its executable will be rejected.

## SOCKS and Core

The plugin reports the actual port from `TorService.getSocksPort()` and exports a sing-box SOCKS outbound only after the circuit is established and a TCP connect to the loopback port succeeds. The listener is explicitly configured on `127.0.0.1`; there is no LAN bind.

The actual Hiddify Core integration is a separate merge step because Core's source/schema was not available in the workspace and this adapter has not been built or run. The Core must append the returned outbound without deleting existing outbounds. Full Tor mode must reject UDP and route every remaining supported flow through the Tor outbound. DNS must use a remote resolver whose `detour` is the Tor outbound. No direct fallback is permitted. DNS/route fragments are not marked tested against the Hiddify-pinned Core build.

## Security boundaries

- Bridge profiles are stored encrypted using AES-GCM with an Android Keystore key.
- Raw Tor error detail is not sent to Flutter because it may contain bridge values.
- SOCKS is loopback-only.
- No second VPNService is declared.
- This package does not claim system-wide leak protection until Core integration, Android VPN tests and packet-level leak tests pass.
- Unsupported UDP is to be rejected in Full Tor mode; Tor is not a general-purpose UDP VPN.
