# Vision VPN security notes

- Tor SOCKS is configured for loopback (`127.0.0.1`) only.
- Custom bridge strings are stored with AES-256-GCM using a non-exportable Android Keystore key. A plaintext torrc file is created only for Tor startup and deleted after Tor reports its first circuit, or on stop/error.
- Only vanilla `Bridge host:port ...` lines are accepted. obfs4/Snowflake/Meek are not bundled or enabled.
- This build does not yet force Hiddify's core traffic through Tor. Do not assume full-device Tor, DNS leak protection, UDP blocking, or a Tor kill switch.
- No claim of leak-free operation is made. Test routing and DNS behavior on a controlled device before any production use.
