# Third-party notices

## Hiddify

This integration is designed for the Hiddify app v5.9.15 source tree. The final fork must retain the exact upstream `LICENSE.md`, attribution, source links and additional terms. The official repository is https://github.com/hiddify/hiddify-app . Do not represent Vision VPN as endorsed by Hiddify.

## Tor Android — Guardian Project

Dependency: `info.guardianproject:tor-android:0.4.9.17`
Repository: https://github.com/guardianproject/tor-android
License metadata: BSD 3-Clause (Maven Central package metadata).

## jtorctl — Guardian Project

Dependency: `info.guardianproject:jtorctl:0.4.5.7`
Repository: https://github.com/guardianproject/tor-android (included/associated control library; verify the exact published artifact's license metadata during dependency audit).

## Tor and bundled native libraries

The Tor Android AAR packages the Tor native engine and dependent native libraries. Before redistributing a built APK, preserve the license/notice material from the exact AAR and inspect the notices for Tor, libevent, OpenSSL, zlib and zstd versions embedded in that artifact. The package's Maven version is pinned to 0.4.9.17; do not silently upgrade it.

## SlipNet

No SlipNet source code or binary is copied. SlipNet was consulted only as an architectural reference. Its repository is https://github.com/anonvector/SlipNet .

## Flutter and AndroidX

These are build/runtime platform dependencies supplied by Flutter/AndroidX. Generate the final SBOM/license report from the resolved lockfiles and Gradle dependency graph before publishing.
