#!/usr/bin/env python3
"""Verify 16 KiB ELF PT_LOAD and APK ZIP alignment for VisionVPN."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

PAGE_SIZE = 16 * 1024
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"}
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


def parse_program_headers(output: str) -> list[Segment]:
    segments = []
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] not in {"LOAD", "GNU_RELRO"}:
            continue
        # readelf flags may be one token (RW) or two (R E); alignment is last.
        if len(fields) < 8:
            raise ValueError(f"Malformed {fields[0]} program-header row: {line.strip()}")
        try:
            segments.append(Segment(
                kind=fields[0],
                offset=int(fields[1], 0),
                vaddr=int(fields[2], 0),
                filesz=int(fields[4], 0),
                memsz=int(fields[5], 0),
                align=int(fields[-1], 0),
            ))
        except ValueError as exc:
            raise ValueError(f"Cannot parse program-header row: {line.strip()}") from exc
    if not any(s.kind == "LOAD" for s in segments):
        raise ValueError("No PT_LOAD segments found")
    return segments


def validate_segments(segments: list[Segment], label: str) -> list[str]:
    errors = []
    loads = [s for s in segments if s.kind == "LOAD"]
    relros = [s for s in segments if s.kind == "GNU_RELRO"]
    for number, seg in enumerate(loads, 1):
        if seg.align < PAGE_SIZE:
            errors.append(
                f"{label}: PT_LOAD #{number} p_align=0x{seg.align:x}; "
                f"requires at least 0x{PAGE_SIZE:x} (16 KiB)"
            )
        if seg.align > 1 and seg.offset % seg.align != seg.vaddr % seg.align:
            errors.append(
                f"{label}: PT_LOAD #{number} violates p_offset/p_vaddr congruence "
                f"for p_align=0x{seg.align:x}"
            )
    for number, seg in enumerate(relros, 1):
        if seg.align not in (0, 1) and seg.align & (seg.align - 1):
            errors.append(f"{label}: GNU_RELRO #{number} has non-power-of-two p_align=0x{seg.align:x}")
        end = seg.vaddr + seg.memsz
        if not any(load.vaddr <= seg.vaddr and end <= load.vaddr + load.memsz for load in loads):
            errors.append(f"{label}: GNU_RELRO #{number} is outside every PT_LOAD memory range")
    return errors


def find_tool(env_name: str, command: str, candidates: list[Path]) -> str | None:
    configured = os.environ.get(env_name)
    if configured:
        return configured if Path(configured).is_file() else shutil.which(configured)
    for path in candidates:
        if path.is_file():
            return str(path)
    return shutil.which(command)


def find_readelf() -> str | None:
    candidates = []
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if ndk:
        candidates += list(Path(ndk).glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    if sdk:
        candidates += list(Path(sdk).glob("ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    return find_tool("READELF", "readelf", candidates) or find_tool("READELF", "llvm-readelf", candidates)


def find_zipalign() -> str | None:
    candidates = []
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = list(Path(sdk).glob("build-tools/*/zipalign"))
        candidates.sort(key=lambda p: tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[._-]", p.parent.name)), reverse=True)
    return find_tool("ZIPALIGN", "zipalign", candidates)


def discover_apks(root: Path = APK_ROOT) -> list[Path]:
    return sorted(p for p in root.rglob("*.apk") if p.is_file()) if root.is_dir() else []


def run(command: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(command, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)


def check_apk(apk: Path, readelf: str, zipalign: str, temp_dir: Path) -> tuple[dict, set[str], list[str]]:
    errors = []
    abis = set()
    apk_report = {"path": str(apk), "zipalign": {}, "libraries": []}

    aligned = run([zipalign, "-c", "-P", "16", "-v", "4", str(apk)])
    apk_report["zipalign"] = {
        "ok": aligned.returncode == 0,
        "exit_code": aligned.returncode,
        "output": aligned.stdout[-8000:],
    }
    if aligned.returncode:
        errors.append(f"{apk}: zipalign -P 16 failed: {aligned.stdout[-2500:]}")

    try:
        archive = zipfile.ZipFile(apk)
    except (OSError, zipfile.BadZipFile) as exc:
        return apk_report, abis, errors + [f"{apk}: invalid APK/ZIP: {exc}"]

    with archive:
        entries = [e for e in archive.infolist()
                   if not e.is_dir() and re.fullmatch(r"lib/[^/]+/[^/]+\.so", e.filename)]
        if not entries:
            errors.append(f"{apk}: no native libraries found under lib/<abi>/*.so")
        for index, entry in enumerate(entries):
            abi = entry.filename.split("/")[1]
            abis.add(abi)
            label = f"{apk}!/{entry.filename}"
            extracted = temp_dir / f"{apk.stem}-{index}-{Path(entry.filename).name}"
            try:
                extracted.write_bytes(archive.read(entry))
            except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
                errors.append(f"{label}: extraction failed: {exc}")
                continue
            result = run([readelf, "-lW", str(extracted)])
            if result.returncode:
                errors.append(f"{label}: readelf failed: {result.stdout[-2500:]}")
                continue
            try:
                segments = parse_program_headers(result.stdout)
            except ValueError as exc:
                errors.append(f"{label}: {exc}")
                continue
            segment_errors = validate_segments(segments, label)
            errors.extend(segment_errors)
            loads = [s for s in segments if s.kind == "LOAD"]
            relros = [s for s in segments if s.kind == "GNU_RELRO"]
            apk_report["libraries"].append({
                "path": entry.filename,
                "abi": abi,
                "pt_load_alignments": [f"0x{s.align:x}" for s in loads],
                "gnu_relro_count": len(relros),
                "ok": not segment_errors,
                "errors": segment_errors,
            })
    return apk_report, abis, errors


def main() -> int:
    apks = discover_apks()
    report = {
        "page_size_bytes": PAGE_SIZE,
        "apk_root": str(APK_ROOT),
        "expected_abis": sorted(EXPECTED_ABIS),
        "observed_abis": [],
        "apks": [],
        "errors": [],
    }
    if not apks:
        report["errors"].append(f"No APK files found under {APK_ROOT}")

    readelf = find_readelf()
    zipalign = find_zipalign()
    if apks and not readelf:
        report["errors"].append("readelf/llvm-readelf not found; install binutils or Android NDK.")
    if apks and not zipalign:
        report["errors"].append("zipalign not found; install Android build-tools.")

    observed = set()
    if apks and readelf and zipalign:
        with tempfile.TemporaryDirectory(prefix="visionvpn-16kb-") as tmp:
            temp_dir = Path(tmp)
            for apk in apks:
                apk_report, abis, errors = check_apk(apk, readelf, zipalign, temp_dir)
                report["apks"].append(apk_report)
                observed.update(abis)
                report["errors"].extend(errors)

    report["observed_abis"] = sorted(observed)
    missing = sorted(EXPECTED_ABIS - observed)
    if apks and missing:
        report["errors"].append("Expected ABI(s) missing from all APKs: " + ", ".join(missing))

    try:
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    except OSError as exc:
        print(f"ERROR: cannot write {REPORT}: {exc}", file=sys.stderr)
        return 2

    print(json.dumps({
        "apk_count": len(report["apks"]),
        "native_library_count": sum(len(a["libraries"]) for a in report["apks"]),
        "observed_abis": report["observed_abis"],
        "error_count": len(report["errors"]),
        "errors": report["errors"],
        "report": str(REPORT),
    }, indent=2))
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
