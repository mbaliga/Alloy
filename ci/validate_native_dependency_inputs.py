#!/usr/bin/env python3
"""Validate the native archives, headers and shared libraries consumed by Alloy.

The pinned OrcaSlicer-Mobile checker describes its own upstream build graph and
expects optional Boost.Math archives which Boost 1.85 does not emit for this
source build.  Alloy owns this boundary, so validate the actual CMake contract
after copying the generated inputs into the app tree.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ABIS = {
    "arm64-v8a": "a64",
    "armeabi-v7a": "a32",
    "x86_64": "x64",
    "x86": "x32",
}


def require_file(path: Path, label: str) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f"{path}: required {label} is missing or empty")


def cmake_list(text: str, name: str) -> list[str]:
    match = re.search(rf"set\({name}\s+(.*?)\)", text, flags=re.DOTALL)
    if not match:
        raise ValueError(f"CMakeLists.native.txt: set({name} ...) is missing")
    return re.findall(r"[A-Za-z0-9_]+", match.group(1))


def main() -> None:
    requested = sys.argv[1:] or ["arm64-v8a"]
    unknown = [abi for abi in requested if abi not in ABIS]
    if unknown:
        raise SystemExit(f"unsupported ABI: {unknown}")

    cmake_path = ROOT / "app/CMakeLists.native.txt"
    cmake = cmake_path.read_text(encoding="utf-8")
    boost_names = cmake_list(cmake, "BOOST_LIBS")
    occt_names = cmake_list(cmake, "OCCT_LIBS")
    boost_version_match = re.search(r'set\(BOOST_VERSION\s+"([^"]+)"\)', cmake)
    if not boost_version_match:
        raise ValueError(f"{cmake_path}: Boost version is missing")
    boost_version = boost_version_match.group(1)

    for abi in requested:
        boost_root = ROOT / "app/src/main/jniImports/boost"
        boost_include = boost_root / "include" / f"boost-{boost_version}" / "boost"
        require_file(boost_include / "version.hpp", f"Boost {boost_version} headers")
        boost_lib_root = boost_root / "lib" / abi / "lib"
        for name in boost_names:
            require_file(
                boost_lib_root / f"libboost_{name}-clang-mt-{ABIS[abi]}-{boost_version}.a",
                f"Boost {name} archive",
            )

        tbb_root = ROOT / "app/src/main/jniImports/oneTBB"
        require_file(tbb_root / "include/tbb/scalable_allocator.h", "oneTBB headers")
        require_file(tbb_root / "include/oneapi/tbb/scalable_allocator.h", "oneAPI TBB compatibility headers")
        require_file(tbb_root / "lib" / abi / "libtbb.a", "oneTBB archive")
        require_file(tbb_root / "lib" / abi / "libtbbmalloc.a", "oneTBB malloc archive")

        occt_root = ROOT / "app/src/main/occt"
        require_file(occt_root / "include" / abi / "TDF_Label.hxx", "OCCT headers")
        require_file(occt_root / "src/DataExchange/TKDESTEP/STEPCAFControl/STEPCAFControl_Reader.hxx", "portable OCCT source headers")
        leaked = list((occt_root / "include").rglob("*.hxx")) + list((occt_root / "include").rglob("*.pxx"))
        for header in leaked:
            if "/OCCT/src/" in header.read_text(encoding="utf-8"):
                raise ValueError(f"{header}: generated OCCT header still contains an absolute source path")
        for name in occt_names:
            require_file(occt_root / "jniLibs" / abi / f"lib{name}.so", f"OCCT {name} shared library")
        print(f"validated Alloy native dependency inputs for {abi}: {len(boost_names)} Boost, {len(occt_names)} OCCT libraries")


if __name__ == "__main__":
    main()
