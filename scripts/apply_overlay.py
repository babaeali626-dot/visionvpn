#!/usr/bin/env python3
"""Copy the Vision Tor feature package into a Hiddify v5.9.15 checkout.

This script intentionally does not rewrite Hiddify's routing/core generation,
main navigation or Gradle build files: those must be reviewed against the
exact checked-out source before being changed.
"""
from pathlib import Path
import shutil, sys

overlay = Path(__file__).resolve().parents[1]
target = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
if not (target / "pubspec.yaml").exists() or not (target / "android/app/src/main/AndroidManifest.xml").exists():
    raise SystemExit("Target must be the root of an extracted Hiddify app repository.")
paths = ["packages/vision_tor", "branding/vision_vpn_logo_1024.png", "branding/vision_vpn_logo_512.png", "branding/ui_reference_aura_dashboard.jpg", "android/branding", "core", "README_FA.md", "README.md", "BUILD.md", "INSTALL.md", "TOR_INTEGRATION.md", "ARCHITECTURE.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", "THIRD_PARTY_NOTICES.md", "LICENSE_NOTICE.md", "LICENSE", "FEATURE_INVENTORY.csv", "RESEARCH_SOURCES.md", "PACKAGE_CONTENTS.txt", "INTEGRATION_CHECKLIST_FA.md", "INTEGRATION_STATUS_FA.md", ".github/workflows/android-ci.yml", ".github/workflows/android-build.yml", ".github/workflows/android-release.yml", ".github/workflows/code-analysis.yml", "scripts/check_tor_notices.py"]
conflicts = [str(target / rel) for rel in paths if (target / rel).exists()]
if conflicts:
    raise SystemExit("Refusing to overwrite existing paths; merge these manually first:\n" + "\n".join(conflicts))
for rel in paths:
    src = overlay / rel
    dst = target / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_dir(): shutil.copytree(src, dst)
    else: shutil.copy2(src, dst)
print("Copied the feature overlay. Follow INTEGRATION_CHECKLIST_FA.md before building.")
