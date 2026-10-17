#!/usr/bin/env python3
"""Validate downloaded, explicitly unqualified offline template-probe evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

SOURCE = "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4"
FIXTURES = {
    "cube_20mm", "overhang_support", "thin_wall_frame", "travel_obstacle"
}


def validate(directory: Path) -> dict:
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("source_commit") != SOURCE:
        raise ValueError("template probe manifest identity mismatch")
    if manifest.get("scope") != "template-rendering-only" or manifest.get("qualified") is not False:
        raise ValueError("template probe evidence must remain explicitly unqualified")
    template_digests = manifest.get("template_sha256")
    if not isinstance(template_digests, dict) or set(template_digests) != {
        "machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"
    }:
        raise ValueError("template probe must identify all four source template digests")
    if any(not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value)
           for value in template_digests.values()):
        raise ValueError("invalid template digest")
    fixtures = manifest.get("fixtures")
    if not isinstance(fixtures, list) or len(fixtures) != len(FIXTURES):
        raise ValueError("template probe must contain exactly four fixture records")
    seen = set()
    expected_files = set()
    for fixture in fixtures:
        if not isinstance(fixture, dict):
            raise ValueError("invalid fixture record")
        name = fixture.get("fixture")
        if name not in FIXTURES or name in seen:
            raise ValueError("unexpected or duplicate fixture")
        seen.add(name)
        if fixture.get("rendered") is not True or fixture.get("qualified") is not False:
            raise ValueError(f"fixture {name} is not an unqualified rendering result")
        filename = fixture.get("file")
        if filename != f"{name}.gcode":
            raise ValueError(f"fixture {name} has an unexpected output filename")
        digest = fixture.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError(f"fixture {name} has an invalid output digest")
        output = directory / filename
        if not output.is_file() or output.is_symlink():
            raise ValueError(f"fixture {name} output is missing or not a regular file")
        actual = hashlib.sha256(output.read_bytes()).hexdigest()
        if actual != digest:
            raise ValueError(f"fixture {name} output digest mismatch")
        expected_files.add(filename)
    actual_files = {p.name for p in directory.iterdir() if p.is_file() and p.name.endswith(".gcode")}
    if actual_files != expected_files:
        raise ValueError("template probe directory contains unexpected G-code files")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    validate(args.directory)
    print("validated four unqualified offline machine-template renderings and SHA-256 digests")


if __name__ == "__main__":
    main()
