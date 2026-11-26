from pathlib import Path

root = Path(__file__).resolve().parents[1]
checks = {
    "Tor Android dependency": "info.guardianproject:tor-android:0.4.9.17",
    "jtorctl dependency": "info.guardianproject:jtorctl:0.4.5.7",
    "BSD-3-Clause notice": "BSD 3-Clause",
    "Hiddify attribution": "hiddify/hiddify-app",
}
files = [
    root / "packages/vision_tor/android/build.gradle",
    root / "THIRD_PARTY_NOTICES.md",
    root / "README_FA.md",
]
joined = "\n".join(p.read_text(encoding="utf-8") for p in files if p.exists())
missing = [name for name, token in checks.items() if token not in joined]
if missing:
    raise SystemExit("Missing license/dependency notice(s): " + ", ".join(missing))
print("Tor dependency and attribution notices are present.")
