#!/usr/bin/env python3
"""Verify that Alloy's Java native declarations have matching JNI exports.

The Android build can spend hours compiling the pinned slicer. This small
check catches a high-impact failure mode first: a Java method being renamed or
added without updating the C++ JNI bridge (or vice versa).
"""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r/Native.java"
CPP = ROOT / "app/src/main/jni/slicebeam/beam_native.cpp"
LOADER = ROOT / "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r/OCCTLoader.java"
CMAKE = ROOT / "app/CMakeLists.native.txt"


def jni_escape(name: str) -> str:
    """Encode the Java identifier portion of a JNI long symbol."""
    return name.replace("_", "_1")


def main() -> None:
    java = JAVA.read_text(encoding="utf-8")
    cpp = CPP.read_text(encoding="utf-8")
    methods = re.findall(r"\bpublic\s+static\s+native\s+[^;()]+?\s+(\w+)\s*\(", java)
    if not methods:
        raise SystemExit(f"{JAVA}: no native methods found")
    if len(methods) != len(set(methods)):
        raise SystemExit(f"{JAVA}: duplicate native method declaration")

    prefix = "Java_ru_ytkab0bp_slicebeam_slic3r_Native_"
    expected = {prefix + jni_escape(method) for method in methods}
    exports = set(re.findall(r"\bJava_ru_ytkab0bp_slicebeam_slic3r_Native_[A-Za-z0-9_]+", cpp))
    missing = sorted(expected - exports)
    if missing:
        raise SystemExit(f"{CPP}: missing JNI exports: {missing}")

    loader = LOADER.read_text(encoding="utf-8")
    cmake = CMAKE.read_text(encoding="utf-8")
    cmake_match = re.search(r"set\(OCCT_LIBS\s+(.*?)\)\s*", cmake, re.DOTALL)
    if cmake_match is None:
        raise SystemExit(f"{CMAKE}: OCCT library list is missing")
    occt_libraries = set(re.findall(r"\b[A-Z][A-Za-z0-9]+\b", cmake_match.group(1)))
    loaded_libraries = set(re.findall(r'"([A-Z][A-Za-z0-9]+)"', loader))
    missing_libraries = sorted(occt_libraries - loaded_libraries)
    if missing_libraries:
        raise SystemExit(f"{LOADER}: CMake OCCT libraries are not loaded: {missing_libraries}")
    unlinked_libraries = sorted(loaded_libraries - occt_libraries)
    if unlinked_libraries:
        raise SystemExit(f"{CMAKE}: Java OCCT libraries are not linked or packaged: {unlinked_libraries}")

    # The C++ bridge may intentionally retain additional upstream exports for
    # future features; only the Alloy-owned Java seam is required here.
    print(f"validated {len(methods)} Java native declarations against JNI exports")


if __name__ == "__main__":
    main()
