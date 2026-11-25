#!/usr/bin/env python3
"""Fail-closed validation for the checked-in GMP/MPFR Android inputs.

Boost, oneTBB and OCCT are rebuilt in the hosted native job. GMP/MPFR have a
checked-in compatibility fallback and a source-built workflow path, so validate
their ABI, versions and content before CMake can consume them.
"""

from __future__ import annotations

import hashlib
import re
import struct
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ABIS = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
LIBRARIES = ("libgmp.so", "libgmpxx.so", "libmpfr.so")
HEADERS = ("gmp.h", "gmpxx.h", "mpfr.h", "mpf2mpfr.h")
GMP_URL = "https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2"
GMP_SHA256 = "eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c"
MPFR_URL = "https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2"
MPFR_SHA256 = "9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b"
EXPECTED_DIGESTS = {
    "arm64-v8a/libgmp.so": "c964318c9a7dfbfd26090a27971a4fe8c58d5733d95b5e9fce1b758aac789062",
    "arm64-v8a/libgmpxx.so": "583f2151ee200f68cf4558771fe29236b4cc780d8a2162d946417b74ec9c066e",
    "arm64-v8a/libmpfr.so": "684df892b9cc636f904587f52f5a946bdf2fea33a5ed9cf95f9d3f83c9a70a97",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_elf(path: Path, elf_class: int, machine: int) -> None:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError(f"{path}: not an ELF shared library")
    if data[4] != elf_class:
        raise ValueError(f"{path}: ELF class {data[4]} does not match ABI")
    actual_machine = struct.unpack_from("<H", data, 18)[0]
    if actual_machine != machine:
        raise ValueError(f"{path}: ELF machine {actual_machine} does not match ABI")


def header_version(path: Path, pattern: str) -> str:
    text = path.read_text(encoding="utf-8", errors="strict")
    match = re.search(pattern, text)
    if not match:
        raise ValueError(f"{path}: version marker is missing")
    return match.group(1)


def metadata_value(metadata: str, key: str) -> str | None:
    match = re.search(rf"^{re.escape(key)}=([^\n]+)$", metadata, flags=re.MULTILINE)
    return match.group(1).strip() if match else None


def main() -> None:
    explicit_source_built = "--source-built" in sys.argv[1:]
    requested = [value for value in sys.argv[1:] if value != "--source-built"] or list(ABIS)
    unknown = [abi for abi in requested if abi not in ABIS]
    if unknown:
        raise SystemExit(f"unsupported ABI: {unknown}")

    libs_root = ROOT / "app/src/main/jniLibs"
    headers_root = ROOT / "app/src/main/jniImports/gmp/include"
    metadata_path = ROOT / "app/src/main/jniImports/gmp/SOURCE_BUILD_METADATA.txt"
    metadata = metadata_path.read_text(encoding="utf-8") if metadata_path.is_file() else ""
    metadata_source_built = (
        metadata_value(metadata, "SOURCE_BUILD_MODE") == "official-source"
        and metadata_value(metadata, "ANDROID_ABI") == "arm64-v8a"
    )
    # A no-argument audit covers every checked-in ABI. The arm64 files may be
    # the generated official-source replacement while the emulator/legacy ABI
    # fallbacks remain on their pinned compatibility headers.
    source_built_arm64 = explicit_source_built or metadata_source_built
    for abi in requested:
        elf_class, machine = ABIS[abi]
        for name in LIBRARIES:
            path = libs_root / abi / name
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"{path}: required native input is missing or empty")
            require_elf(path, elf_class, machine)
        for name in HEADERS:
            path = headers_root / abi / name
            if not path.is_file() or path.stat().st_size == 0:
                raise ValueError(f"{path}: required native header is missing or empty")

        gmp = headers_root / abi / "gmp.h"
        mpfr = headers_root / abi / "mpfr.h"
        gmp_version = ".".join((
            header_version(gmp, r"__GNU_MP_VERSION\s+(\d+)"),
            header_version(gmp, r"__GNU_MP_VERSION_MINOR\s+(\d+)"),
            header_version(gmp, r"__GNU_MP_VERSION_PATCHLEVEL\s+(\d+)"),
        ))
        mpfr_version = header_version(mpfr, r'MPFR_VERSION_STRING\s+"([^"]+)"')
        expected_mpfr = "4.2.2" if abi == "arm64-v8a" and source_built_arm64 else "4.2.1"
        if gmp_version != "6.2.1" or mpfr_version != expected_mpfr:
            raise ValueError(f"{abi}: expected GMP 6.2.1 / MPFR {expected_mpfr}, got {gmp_version} / {mpfr_version}")
        print(f"validated {abi}: GMP {gmp_version}, MPFR {mpfr_version}")

    if source_built_arm64:
        if not metadata_path.is_file():
            raise ValueError(f"{metadata_path}: source-build metadata is missing")
        for marker in (
            "SOURCE_BUILD_MODE=official-source",
            "GMP_VERSION=6.2.1",
            f"GMP_SOURCE_URL={GMP_URL}",
            f"GMP_SOURCE_SHA256={GMP_SHA256}",
            "MPFR_VERSION=4.2.2",
            f"MPFR_SOURCE_URL={MPFR_URL}",
            f"MPFR_SOURCE_SHA256={MPFR_SHA256}",
            "ANDROID_ABI=arm64-v8a",
        ):
            if marker not in metadata:
                raise ValueError(f"{metadata_path}: missing source-build marker {marker}")
        for name, key in (
            ("libgmp.so", "GMP_SO_SHA256"),
            ("libgmpxx.so", "GMPXX_SO_SHA256"),
            ("libmpfr.so", "MPFR_SO_SHA256"),
        ):
            expected = metadata_value(metadata, key)
            actual = digest(libs_root / "arm64-v8a" / name)
            if expected is None or not re.fullmatch(r"[0-9a-f]{64}", expected) or actual != expected:
                raise ValueError(f"{metadata_path}: generated digest mismatch for {name}")
    elif "arm64-v8a" in requested:
        for relative, expected in EXPECTED_DIGESTS.items():
            path = libs_root / relative
            actual = digest(path)
            if actual != expected:
                raise ValueError(f"{path}: digest changed; refresh provenance deliberately before building")
    print("validated checked-in GMP/MPFR inputs")


if __name__ == "__main__":
    main()
