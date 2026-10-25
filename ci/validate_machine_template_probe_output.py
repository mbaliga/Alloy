#!/usr/bin/env python3
"""Validate downloaded, explicitly unqualified offline template-probe evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

SOURCE = "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4"
PROFILE = "Bambu Lab A1 mini 0.4 nozzle"
G3_PROFILE_SHA256 = "6ea072f761a3a50dd33caafe5a6c44b0da4011698c97d34e20011849b231fd18"
FIXTURES = {
    "cube_20mm", "overhang_support", "thin_wall_frame", "travel_obstacle"
}
ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "app/src/androidTest/assets/profiles/a1-mini-machine-template-probe.json"


def expected_template_digests() -> dict:
    bundle = json.loads(BUNDLE.read_text(encoding="utf-8"))
    if (bundle.get("schema_version") != 1 or bundle.get("source_commit") != SOURCE
            or bundle.get("profile_name") != PROFILE):
        raise ValueError("pinned template probe bundle identity mismatch")
    templates, digests = bundle.get("templates"), bundle.get("sha256")
    keys = {"machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"}
    if not isinstance(templates, dict) or set(templates) != keys or not isinstance(digests, dict) or set(digests) != keys:
        raise ValueError("pinned template probe bundle must contain exactly four templates and digests")
    actual = {key: hashlib.sha256(templates[key].encode("utf-8")).hexdigest() for key in keys}
    if actual != digests:
        raise ValueError("pinned template probe bundle digest mismatch")
    return actual


def _validate_config_digest(directory: Path, fixture: dict, name: str, mode: str) -> str:
    filename = fixture.get("config_file")
    if filename != f"{name}.config.ini":
        raise ValueError(f"{mode} fixture {name} has an unexpected config filename")
    digest = fixture.get("config_sha256")
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError(f"{mode} fixture {name} has an invalid config digest")
    config_path = directory / filename
    if not config_path.is_file() or config_path.is_symlink():
        raise ValueError(f"{mode} fixture {name} config is missing or not a regular file")
    if hashlib.sha256(config_path.read_bytes()).hexdigest() != digest:
        raise ValueError(f"{mode} fixture {name} config digest mismatch")
    return digest


def validate(directory: Path) -> dict:
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("source_commit") != SOURCE:
        raise ValueError("template probe manifest identity mismatch")
    if manifest.get("scope") != "template-rendering-only" or manifest.get("qualified") is not False:
        raise ValueError("template probe evidence must remain explicitly unqualified")
    if (manifest.get("default_bbl_printer_mode") is not False
            or manifest.get("counterfactual") is not False
            or manifest.get("non_gating") is not True
            or manifest.get("profile_name") != PROFILE
            or manifest.get("profile_sha256") != G3_PROFILE_SHA256):
        raise ValueError("default template probe must identify false BBL mode and remain non-gating")
    template_digests = manifest.get("template_sha256")
    if not isinstance(template_digests, dict) or set(template_digests) != {
        "machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"
    }:
        raise ValueError("template probe must identify all four source template digests")
    if template_digests != expected_template_digests():
        raise ValueError("template probe digests do not match the pinned source bundle")
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
        if (fixture.get("rendered") is not True or fixture.get("qualified") is not False
                or fixture.get("counterfactual") is not False
                or fixture.get("bbl_printer_mode") is not False):
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
        _validate_config_digest(directory, fixture, name, "template probe")
        expected_files.add(filename)
    actual_files = {p.name for p in directory.iterdir() if p.is_file() and p.name.endswith(".gcode")}
    if actual_files != expected_files:
        raise ValueError("template probe directory contains unexpected G-code files")
    return manifest


def validate_end_only(directory: Path) -> dict:
    root = directory.parent
    manifest = json.loads((root / "end-only-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("source_commit") != SOURCE:
        raise ValueError("end-only template manifest identity mismatch")
    if manifest.get("scope") != "machine-end-template-only-render-diagnostic" or manifest.get("qualified") is not False:
        raise ValueError("end-only evidence must remain explicitly unqualified")
    if manifest.get("template_sha256") != expected_template_digests()["machine_end_gcode"]:
        raise ValueError("end-only evidence does not identify the pinned machine-end template")
    fixtures = manifest.get("fixtures")
    if not isinstance(fixtures, list) or len(fixtures) != len(FIXTURES):
        raise ValueError("end-only probe must contain exactly four fixture records")
    seen = set()
    expected_files = set()
    for fixture in fixtures:
        if not isinstance(fixture, dict):
            raise ValueError("invalid end-only fixture record")
        name = fixture.get("fixture")
        if name not in FIXTURES or name in seen:
            raise ValueError("unexpected or duplicate end-only fixture")
        seen.add(name)
        if fixture.get("rendered") is not True or fixture.get("qualified") is not False:
            raise ValueError(f"end-only fixture {name} is not an unqualified rendering result")
        filename = fixture.get("file")
        if filename != f"{name}.gcode":
            raise ValueError(f"end-only fixture {name} has an unexpected output filename")
        digest = fixture.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError(f"end-only fixture {name} has an invalid output digest")
        output = directory / filename
        if not output.is_file() or output.is_symlink():
            raise ValueError(f"end-only fixture {name} output is missing or not a regular file")
        if hashlib.sha256(output.read_bytes()).hexdigest() != digest:
            raise ValueError(f"end-only fixture {name} output digest mismatch")
        expected_files.add(filename)
    actual_files = {p.name for p in directory.iterdir() if p.is_file() and p.name.endswith(".gcode")}
    if actual_files != expected_files:
        raise ValueError("end-only probe directory contains unexpected G-code files")
    return manifest


def validate_counterfactual_bbl(directory: Path) -> dict:
    """Validate the test-only BBL mode render; never treat it as a qualified slice."""
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    expected = {
        "schema_version": 1,
        "source_commit": SOURCE,
        "scope": "counterfactual-bbl-identity-template-render-diagnostic",
        "counterfactual": True,
        "bbl_printer_mode": True,
        "production_runtime_changed": False,
        "non_gating": True,
        "qualified": False,
        "profile_name": PROFILE,
        "profile_sha256": G3_PROFILE_SHA256,
        "template_sha256": expected_template_digests(),
    }
    for key, value in expected.items():
        if manifest.get(key) != value:
            raise ValueError(f"counterfactual BBL manifest {key} mismatch")
    fixtures = manifest.get("fixtures")
    if not isinstance(fixtures, list) or len(fixtures) != len(FIXTURES):
        raise ValueError("counterfactual BBL probe must contain exactly four fixture records")
    seen = set()
    expected_files = set()
    for fixture in fixtures:
        if not isinstance(fixture, dict):
            raise ValueError("invalid counterfactual BBL fixture record")
        name = fixture.get("fixture")
        if name not in FIXTURES or name in seen:
            raise ValueError("unexpected or duplicate counterfactual BBL fixture")
        seen.add(name)
        if (fixture.get("rendered") is not True or fixture.get("qualified") is not False
                or fixture.get("counterfactual") is not True
                or fixture.get("bbl_printer_mode") is not True):
            raise ValueError(f"counterfactual BBL fixture {name} has invalid qualification labels")
        filename = fixture.get("file")
        if filename != f"{name}.gcode":
            raise ValueError(f"counterfactual BBL fixture {name} has an unexpected output filename")
        digest = fixture.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError(f"counterfactual BBL fixture {name} has an invalid output digest")
        output = directory / filename
        if not output.is_file() or output.is_symlink():
            raise ValueError(f"counterfactual BBL fixture {name} output is missing or not a regular file")
        if hashlib.sha256(output.read_bytes()).hexdigest() != digest:
            raise ValueError(f"counterfactual BBL fixture {name} output digest mismatch")
        _validate_config_digest(directory, fixture, name, "counterfactual BBL")
        expected_files.add(filename)
    actual_files = {p.name for p in directory.iterdir() if p.is_file() and p.name.endswith(".gcode")}
    if actual_files != expected_files:
        raise ValueError("counterfactual BBL directory contains unexpected G-code files")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    validate(args.directory)
    validate_end_only(args.directory / "end-only")
    validate_counterfactual_bbl(args.directory / "counterfactual-bbl")
    print("validated default, end-only, and counterfactual BBL unqualified template renderings")


if __name__ == "__main__":
    main()
