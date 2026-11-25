#!/usr/bin/env python3
"""Verify Android APK ZIP alignment and 16 KiB ELF compatibility."""
from __future__ import annotations
import json, os, re, shutil, subprocess, sys, tempfile, zipfile
from dataclasses import dataclass
from pathlib import Path

PAGE_SIZE = 16 * 1024
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"}
PAGE_SIZE_ABIS = {"arm64-v8a", "x86_64"}  # x86_64 is for 16 KiB emulator testing
APK_ROOT = Path(os.environ.get("VISIONVPN_APK_ROOT", "app/build/outputs/apk"))
REPORT = Path(os.environ.get("VISIONVPN_16KB_REPORT", "16kb-alignment-report.json"))

@dataclass(frozen=True)
class Segment:
    kind: str
    offset: int
    vaddr: int
    filesz: int
    memsz: int
    align: int
    flags: str = ""

def parse_program_headers(output: str) -> list[Segment]:
    segments = []
    for line in output.splitlines():
        f = line.split()
        if not f or f[0] not in {"LOAD", "GNU_RELRO"}:
            continue
        if len(f) < 8:
            raise ValueError(f"Malformed {f[0]} program-header row: {line.strip()}")
        try:
            segments.append(Segment(f[0], int(f[1],0), int(f[2],0),
                int(f[4],0), int(f[5],0), int(f[-1],0), "".join(f[6:-1])))
        except ValueError as exc:
            raise ValueError(f"Cannot parse program-header row: {line.strip()}") from exc
    if not any(s.kind == "LOAD" for s in segments):
        raise ValueError("No PT_LOAD segments found")
    return segments

def validate_segments(segments: list[Segment], label: str, abi: str = "arm64-v8a") -> list[str]:
    errors = []
    if abi not in PAGE_SIZE_ABIS:
        # Android's 16 KiB page-size device support applies to arm64; x86_64
        # is included for emulator testing. Keep packaging/ABI checks for 32-bit ARM.
        return errors
    loads = [s for s in segments if s.kind == "LOAD"]
    relros = [s for s in segments if s.kind == "GNU_RELRO"]
    for n, seg in enumerate(loads, 1):
        if seg.align < PAGE_SIZE:
            errors.append(f"{label}: PT_LOAD #{n} p_align=0x{seg.align:x}; requires at least 0x{PAGE_SIZE:x} (16 KiB) for {abi}")
        if seg.align > 1 and seg.offset % seg.align != seg.vaddr % seg.align:
            errors.append(f"{label}: PT_LOAD #{n} violates p_offset/p_vaddr congruence for p_align=0x{seg.align:x}")
    # Android's RELRO acceptance rule: no GNU_RELRO, or RELRO end is 16 KiB
    # aligned, or it consumes the remainder of its containing writable LOAD.
    for n, relro in enumerate(relros, 1):
        end = relro.vaddr + relro.memsz
        if end % PAGE_SIZE == 0:
            continue
        writable = [p for p in loads if "W" in p.flags and
                    p.vaddr <= relro.vaddr and end <= p.vaddr + p.memsz]
        if any(end == p.vaddr + p.memsz for p in writable):
            continue
        reason = ("no containing writable PT_LOAD was found" if not writable
                  else "writable data follows GNU_RELRO in its containing PT_LOAD")
        errors.append(f"{label}: GNU_RELRO #{n} end=0x{end:x} is not 16 KiB safe: {reason}")
    return errors

def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)

def find_readelf() -> str | None:
    candidates = []
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if ndk: candidates += list(Path(ndk).glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    if sdk: candidates += list(Path(sdk).glob("ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    for p in candidates:
        if p.is_file(): return str(p)
    return os.environ.get("READELF") or shutil.which("llvm-readelf") or shutil.which("readelf")

def find_zipalign() -> str | None:
    candidates = []
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = list(Path(sdk).glob("build-tools/*/zipalign"))
        candidates.sort(key=lambda p: tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[._-]", p.parent.name)), reverse=True)
    for p in candidates:
        if p.is_file(): return str(p)
    return os.environ.get("ZIPALIGN") or shutil.which("zipalign")

def check_apk(apk: Path, readelf: str, zipalign: str, tmp: Path):
    errors, abis = [], set()
    result = run([zipalign, "-c", "-P", "16", "-v", "4", str(apk)])
    report = {"path": str(apk), "zipalign_ok": result.returncode == 0,
              "zipalign_output": result.stdout[-4000:], "libraries": []}
    if result.returncode:
        errors.append(f"{apk}: zipalign -P 16 failed: {result.stdout[-2000:]}")
    try:
        zf = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as exc:
        return report, abis, errors + [f"{apk}: invalid APK/ZIP: {exc}"]
    with zf:
        entries = [e for e in zf.infolist() if not e.is_dir() and re.fullmatch(r"lib/[^/]+/[^/]+\.so", e.filename)]
        if not entries: errors.append(f"{apk}: no native libraries found under lib/<abi>/*.so")
        for i, entry in enumerate(entries):
            abi = entry.filename.split("/")[1]
            abis.add(abi)
            label = f"{apk}!/{entry.filename}"
            lib = tmp / f"{apk.stem}-{i}-{Path(entry.filename).name}"
            try: lib.write_bytes(zf.read(entry))
            except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                errors.append(f"{label}: extraction failed: {exc}"); continue
            elf = run([readelf, "-lW", str(lib)])
            if elf.returncode:
                errors.append(f"{label}: readelf failed: {elf.stdout[-2000:]}"); continue
            try: segs = parse_program_headers(elf.stdout)
            except ValueError as exc:
                errors.append(f"{label}: {exc}"); continue
            lib_errors = validate_segments(segs, label, abi)
            errors.extend(lib_errors)
            loads = [s for s in segs if s.kind == "LOAD"]
            relros = [s for s in segs if s.kind == "GNU_RELRO"]
            report["libraries"].append({
                "path": entry.filename, "abi": abi,
                "page_size_check_required": abi in PAGE_SIZE_ABIS,
                "pt_load_alignments": [f"0x{s.align:x}" for s in loads],
                "gnu_relro_end_addresses": [f"0x{s.vaddr+s.memsz:x}" for s in relros],
                "ok": not lib_errors, "errors": lib_errors})
    return report, abis, errors

def main() -> int:
    apks = sorted(p for p in APK_ROOT.rglob("*.apk") if p.is_file()) if APK_ROOT.is_dir() else []
    report = {"page_size_bytes": PAGE_SIZE, "page_size_required_abis": sorted(PAGE_SIZE_ABIS),
              "packaged_abis_expected": sorted(EXPECTED_ABIS), "apk_root": str(APK_ROOT),
              "observed_abis": [], "apks": [], "errors": []}
    if not apks: report["errors"].append(f"No APK files found under {APK_ROOT}")
    readelf, zipalign = find_readelf(), find_zipalign()
    if apks and not readelf: report["errors"].append("readelf/llvm-readelf not found; install Android NDK.")
    if apks and not zipalign: report["errors"].append("zipalign not found; install Android build-tools.")
    observed = set()
    if apks and readelf and zipalign:
        with tempfile.TemporaryDirectory(prefix="visionvpn-16kb-") as d:
            for apk in apks:
                item, abis, errs = check_apk(apk, readelf, zipalign, Path(d))
                report["apks"].append(item); observed.update(abis); report["errors"].extend(errs)
    report["observed_abis"] = sorted(observed)
    missing = sorted(EXPECTED_ABIS - observed)
    if apks and missing: report["errors"].append("Expected packaged ABI(s) missing: " + ", ".join(missing))
    try:
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    except OSError as exc:
        print(f"ERROR: cannot write {REPORT}: {exc}", file=sys.stderr); return 2
    print(json.dumps({"apk_count": len(report["apks"]),
        "native_library_count": sum(len(x["libraries"]) for x in report["apks"]),
        "observed_abis": report["observed_abis"], "page_size_required_abis": sorted(PAGE_SIZE_ABIS),
        "error_count": len(report["errors"]), "errors": report["errors"], "report": str(REPORT)}, indent=2))
    return 1 if report["errors"] else 0

if __name__ == "__main__":
    raise SystemExit(main())
