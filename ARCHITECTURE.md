# Architecture

```text
VisionTorDashboard (Flutter)
        |
VisionTorController (MethodChannel + EventChannel)
        |
VisionTorPlugin (Android)
        |---- EncryptedProfileStore (Android Keystore AES-GCM)
        |---- TorService (Guardian Project tor-android AAR)
        |---- Local SOCKS5 listener 127.0.0.1:<runtime port>
        |
TorOutboundAdapter (Flutter config fragment)
        |
Hiddify Core config generator (merge point still pending)
        |
Hiddify's existing Android VPNService (unchanged)
```

Responsibilities are separated between the Tor runtime, profile persistence, status events, UI and Core outbound adapter. Tor is inert until explicitly started. The Tor service is not a second VPNService and does not take ownership of Hiddify's TUN interface.

The exact app package name, native MainActivity, DI setup and Hiddify Core config-construction source were unavailable in this workspace. Therefore the package is namespaced (`net.visionvpn.tor`) and isolated; it avoids guessing or overwriting those upstream files.
