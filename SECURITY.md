# Security policy

## Current guarantees in this overlay's source

- SOCKS bind target is `127.0.0.1` only.
- Bridge profile payloads are encrypted at rest with AES-GCM using Android Keystore.
- The plugin refuses to expose an outbound before Tor reports CONNECTED and the local SOCKS port accepts a TCP connection.
- Error messages forwarded by this plugin to Flutter are generic; bridge lines are not intentionally logged by the plugin.
- Guardian TorService itself may emit internal diagnostics to Android logcat; those logs have not been audited on a device. The temporary app-private `torrc` can contain a bridge line during startup until the SOCKS port is discovered and the file is deleted.
- No replacement VPNService or direct fallback is implemented in this plugin.

## Not yet verified

- No Android device/emulator run was performed in this workspace.
- No logcat capture review or persistent-storage forensic review was performed.
- No packet capture or DNS leak test was performed.
- Hiddify Core route and DNS integration is not applied to its source.
- obfs4, Snowflake and Meek are disabled.
- Process survival under Android background restrictions and Hiddify VPN reconnect has not been device-tested.

Do not market the current overlay as a fully audited anonymous VPN. Report security issues privately to the project maintainer and do not attach private bridge credentials or raw traffic logs.
