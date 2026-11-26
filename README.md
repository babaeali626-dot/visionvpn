# Vision VPN — Hiddify Tor Integration Overlay

This ZIP contains an Android Tor integration feature pack for a Hiddify v5.9.15 checkout: an Android Flutter plugin, a Tor dashboard UI, the Vision VPN logo, SOCKS outbound fragments, four GitHub Actions workflows, tests, and documentation.

**This is not a complete Hiddify source tree or an APK.** The workspace could not resolve GitHub during this session, so the official Hiddify source and its Core submodule could not be downloaded or baseline-built. Apply this overlay to the exact upstream checkout using `INTEGRATION_CHECKLIST_FA.md`.

The Tor engine uses Guardian Project's `tor-android` AAR, not copied SlipNet code. Direct Tor and direct bridge relays are enabled. obfs4, Snowflake and Meek remain disabled until real compatible Android PT binaries and their licenses are added. The Tor daemon exposes a loopback SOCKS5 listener only. Full Core routing is not yet applied to Hiddify's code generator; the supplied Core JSON files are integration fragments and must be checked against the pinned Core schema.
