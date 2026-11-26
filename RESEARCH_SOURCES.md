# Research sources checked on 2026-11-26

- Hiddify latest release page: https://github.com/hiddify/hiddify-app/releases — v5.9.15, short commit `365a2f5`.
- Hiddify Android manifest: https://github.com/hiddify/hiddify-app/blob/main/android/app/src/main/AndroidManifest.xml — the project already declares `.bg.VPNService`; the Tor plugin does not add another VPNService.
- Hiddify pubspec: https://github.com/hiddify/hiddify-app/blob/main/pubspec.yaml — Flutter version constraint reported as 3.38.5 in current repository metadata.
- Hiddify Makefile: https://github.com/hiddify/hiddify-app/blob/main/Makefile — Android preparation/build convention.
- Guardian Project Tor Android: https://github.com/guardianproject/tor-android — native TorService, supported ABIs, Maven dependency guidance.
- Maven Central Tor Android: https://central.sonatype.com/artifact/info.guardianproject/tor-android — latest inspected artifact `0.4.9.17`, BSD 3-Clause.
- SlipNet: https://github.com/anonvector/SlipNet — reference only; no source copied.

The latest Hiddify release's full source, tag object SHA, and exact submodule commit were not available for local inspection in this Workspace. Do not treat repository `main` files as proof of every file path at tag v5.9.15.
