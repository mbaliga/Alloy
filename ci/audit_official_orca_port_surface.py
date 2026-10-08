#!/usr/bin/env python3
"""Inventory the official-to-legacy CMake surface for the Orca Android port.

This is deliberately a migration inventory, not a compatibility assertion.  It
reads the exact pinned official source and the legacy Android tree, writes an
auditable report, and fails only if either input no longer exposes the known
libslic3r CMake surface.  A clean report does not make the legacy target an
official engine or authorize a physical-print claim.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re


FIND_PACKAGE = re.compile(r"\bfind_package\s*\(\s*([A-Za-z0-9_]+)", re.IGNORECASE)
OFFICIAL_BASELINE_PACKAGES = {"CGAL", "OpenCV", "OpenCASCADE", "JPEG", "libnoise"}


def packages(cmake_path: Path) -> set[str]:
    if not cmake_path.is_file():
        raise ValueError(f"missing CMake file: {cmake_path}")
    return {match.group(1) for match in FIND_PACKAGE.finditer(cmake_path.read_text(errors="replace"))}


def official_cpp_sources(cmake_path: Path) -> set[str]:
    text = cmake_path.read_text(errors="replace")
    match = re.search(r"set\(lisbslic3r_sources\s*(.*?)\n\)", text, re.S)
    if not match:
        raise ValueError(f"missing official lisbslic3r_sources list: {cmake_path}")
    return set(re.findall(r"(?m)^\s*([A-Za-z0-9_./+-]+\.cpp)\s*$", match.group(1)))


def legacy_cpp_sources(cmake_path: Path) -> set[str]:
    text = cmake_path.read_text(errors="replace")
    start = text.find("add_library(slic3r")
    end = text.find("target_compile_definitions(slic3r", start)
    if start < 0 or end < 0:
        raise ValueError(f"missing legacy slic3r target source list: {cmake_path}")
    body = text[start:end]
    prefix = "src/main/jni/libslic3r/"
    return {
        path[len(prefix):]
        for path in re.findall(r"src/main/jni/libslic3r/[A-Za-z0-9_./+-]+\.cpp", body)
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("official_source", type=Path)
    parser.add_argument("legacy_source", type=Path)
    parser.add_argument("out_dir", type=Path)
    args = parser.parse_args()

    official_cmake = args.official_source / "src/libslic3r/CMakeLists.txt"
    legacy_cmake = args.legacy_source / "CMakeLists.txt"
    legacy_top_level = Path(__file__).resolve().parents[1] / "app/CMakeLists.native.txt"
    try:
        official = packages(official_cmake)
        legacy = packages(legacy_cmake)
        official_sources = official_cpp_sources(official_cmake)
        legacy_sources = legacy_cpp_sources(legacy_top_level)
    except ValueError as error:
        raise SystemExit(f"official Orca port-surface audit failed: {error}")

    missing_baseline = sorted(OFFICIAL_BASELINE_PACKAGES - official)
    if missing_baseline:
        raise SystemExit(
            "official Orca port-surface audit failed: pinned source no longer exposes "
            f"expected packages {missing_baseline}"
        )

    missing_from_legacy = sorted(official - legacy)
    legacy_only = sorted(legacy - official)
    official_source_paths = {args.official_source / "src/libslic3r" / path for path in official_sources}
    missing_official_files = sorted(path.as_posix() for path in official_source_paths if not path.is_file())
    if missing_official_files:
        raise SystemExit(
            "official Orca port-surface audit failed: listed official source files are missing: "
            + ", ".join(missing_official_files[:8])
        )

    legacy_source_paths = {args.legacy_source / path for path in legacy_sources}
    missing_legacy_files = sorted(path.as_posix() for path in legacy_source_paths if not path.is_file())
    if missing_legacy_files:
        raise SystemExit(
            "official Orca port-surface audit failed: listed legacy target files are missing: "
            + ", ".join(missing_legacy_files[:8])
        )

    official_not_selected_by_legacy = sorted(official_sources - legacy_sources)
    legacy_only_sources = sorted(legacy_sources - official_sources)
    parent_cmake_text = legacy_top_level.read_text(errors="replace")
    libnoise_parent_target = (
        'add_subdirectory("${ORCA_LIBNOISE_SOURCE_DIR}"' in parent_cmake_text
        and "noise::noise" in parent_cmake_text
    )

    report = {
        "schema_version": 1,
        "scope": "direct libslic3r packages and listed C++ source selection; inventory only",
        "official_cmake": official_cmake.as_posix(),
        "legacy_cmake": legacy_cmake.as_posix(),
        "official_required_packages": sorted(official),
        "legacy_required_packages": sorted(legacy),
        "direct_package_declaration_gaps": missing_from_legacy,
        "legacy_only_packages": legacy_only,
        "legacy_parent_dependency_targets": {
            "libnoise": "noise::noise" if libnoise_parent_target else None,
        },
        "target_source_selection": {
            "official_target": "libslic3r",
            "official_cpp_count": len(official_sources),
            "legacy_target": "slic3r",
            "legacy_cpp_count": len(legacy_sources),
            "same_relative_path_count": len(official_sources & legacy_sources),
            "official_sources_not_selected_by_legacy": official_not_selected_by_legacy,
            "legacy_only_sources": legacy_only_sources,
        },
        "official_libnoise_adapter": "implemented and configure-tested; not wired to official target",
        "conclusion": "inventory only; no separate official Android target or engine promotion",
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "official-orca-port-surface.json").write_text(
        json.dumps(report, indent=2) + "\n"
    )
    lines = [
        "# Official Orca Android port surface",
        "",
        "- Scope: direct packages plus C++ sources in official `libslic3r` and the current Android target.",
        f"- Official packages: {', '.join(report['official_required_packages'])}",
        f"- Legacy packages: {', '.join(report['legacy_required_packages'])}",
        f"- Direct package declaration gaps: {', '.join(missing_from_legacy) or 'none'}",
        f"- Legacy-only packages: {', '.join(legacy_only) or 'none'}",
        f"- Parent-provided libnoise target: `noise::noise` ({'present' if libnoise_parent_target else 'missing'}).",
        f"- Official C++ sources: {len(official_sources)}; legacy target sources: {len(legacy_sources)}; same relative paths: {len(official_sources & legacy_sources)}.",
        f"- Official sources not selected by the legacy target: {len(official_not_selected_by_legacy)}.",
        "- The libnoise CMake adapter is configure-tested but not yet wired to an official target.",
        "- Conclusion: inventory only; it does not build, select, or verify an official engine.",
        "",
    ]
    (args.out_dir / "OFFICIAL_ORCA_PORT_SURFACE.md").write_text("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
