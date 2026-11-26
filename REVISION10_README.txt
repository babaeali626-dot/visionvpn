VISIONVPN REVISION 10 — OFFLINE SOURCE PATCH

This ZIP contains the updated project source at the archive root.

REPLACEMENT INSTRUCTIONS
1. Back up your current repository folder.
2. Extract this ZIP over the project root containing app/, gradlew, settings.gradle.kts, etc.
3. Do NOT delete or replace the existing .git directory. The ZIP intentionally does not contain .git.
4. Review docs/revision10/IMPLEMENTATION_REPORT.md before building.
5. Build and run the full GitHub Actions Android workflow on branch test. No APK was built in the offline preparation environment.
6. Keep main untouched; commit/push these files only to test after reviewing the diff.

IMPORTANT LIMITATIONS
- Android Kotlin compilation, JUnit, APK assembly, CI and real-device connectivity were not verified here.
- Psiphon still needs official authorized provisioning and a compatible Android engine path. Hiddify's native Psiphon/CDN scanner and separate Proxy Unblocker role are NOT implemented in this patch.
- WARP remains blocked unless a complete legitimate provider configuration is available.
- Smart Connect currently supports only a single imported proxy route; it is not enabled for Tor/Psiphon/WARP chains.
- Hiddify-only tls_fragment/finalmask are not injected into the pinned upstream Sing-box 1.10.7 core.

See docs/revision10/IMPLEMENTATION_REPORT.md for the exact change list and validation boundaries.
