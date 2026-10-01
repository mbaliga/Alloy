#!/usr/bin/env python3
"""Record the direct CMake dependency delta for Alloy's official-Orca port.

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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("official_source", type=Path)
    parser.add_argument("legacy_source", type=Path)
    parser.add_argument("out_dir", type=Path)
    args = parser.parse_args()

    official_cmake = args.official_source / "src/libslic3r/CMakeLists.txt"
    legacy_cmake = args.legacy_source / "CMakeLists.txt"
    try:
        official = packages(official_cmake)
        legacy = packages(legacy_cmake)
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
    if "libnoise" not in missing_from_legacy:
        raise SystemExit(
            "official Orca port-surface audit failed: expected legacy gap libnoise is absent; "
            "review the source baseline and update this explicit migration inventory"
        )

    report = {
        "schema_version": 1,
        "scope": "direct libslic3r find_package CMake surface only",
        "official_cmake": official_cmake.as_posix(),
        "legacy_cmake": legacy_cmake.as_posix(),
        "official_required_packages": sorted(official),
        "legacy_required_packages": sorted(legacy),
        "required_by_official_missing_from_legacy": missing_from_legacy,
        "legacy_only_packages": legacy_only,
        "required_next_closure": {
            "package": "libnoise",
            "official_target": "noise::noise",
            "reason": "official libslic3r directly finds and links libnoise",
            "status": "unported",
        },
        "conclusion": "inventory only; no Android target or engine promotion",
    }
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "official-orca-port-surface.json").write_text(
        json.dumps(report, indent=2) + "\n"
    )
    lines = [
        "# Official Orca Android port surface",
        "",
        "- Scope: direct `find_package()` calls in `src/libslic3r/CMakeLists.txt` only.",
        f"- Official packages: {', '.join(report['official_required_packages'])}",
        f"- Legacy packages: {', '.join(report['legacy_required_packages'])}",
        f"- Required by official but absent from legacy: {', '.join(missing_from_legacy) or 'none'}",
        f"- Legacy-only packages: {', '.join(legacy_only) or 'none'}",
        "- Next required Android closure: `libnoise` / `noise::noise` (**unported**).",
        "- Conclusion: inventory only; it does not build, select, or verify an official engine.",
        "",
    ]
    (args.out_dir / "OFFICIAL_ORCA_PORT_SURFACE.md").write_text("\n".join(lines))
    print("\n".join(lines))


if __name__ == "__main__":
    main()
