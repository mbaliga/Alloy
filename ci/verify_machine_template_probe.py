#!/usr/bin/env python3
"""Verify test-only machine templates against the exact pinned Orca checkout."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "parity"))
from export_resolved_orca_profiles import load_kind, resolve

SOURCE = "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4"
PROFILE = "Bambu Lab A1 mini 0.4 nozzle"
KEYS = {"machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"}
ROOT = Path(__file__).resolve().parents[1]
ASSET = Path("profiles/a1-mini-machine-template-probe.json")


def verify_bundle(bundle: dict, resolved: dict, source_commit: str) -> None:
    if source_commit != SOURCE or bundle.get("source_commit") != SOURCE:
        raise ValueError("template probe must use the pinned Orca source commit")
    if bundle.get("schema_version") != 1 or bundle.get("profile_name") != PROFILE:
        raise ValueError("template probe schema/profile mismatch")
    if bundle.get("scope") != "test-only; no packaging or printer dispatch":
        raise ValueError("template probe must remain test-only")
    if bundle.get("repository") != "https://github.com/OrcaSlicer/OrcaSlicer":
        raise ValueError("template probe source repository mismatch")
    if resolved.get("name") != PROFILE:
        raise ValueError("resolved source is not the selected A1 Mini profile")
    templates, digests = bundle.get("templates"), bundle.get("sha256")
    if not isinstance(templates, dict) or set(templates) != KEYS:
        raise ValueError("template probe must contain exactly the four reviewed hooks")
    if not isinstance(digests, dict) or set(digests) != KEYS:
        raise ValueError("template probe must contain exactly four template digests")
    for key in sorted(KEYS):
        value = templates[key]
        if not isinstance(value, str) or not value.strip() or value != resolved.get(key):
            raise ValueError(f"template probe differs from pinned upstream: {key}")
        if hashlib.sha256(value.encode("utf-8")).hexdigest() != digests[key]:
            raise ValueError(f"template probe digest mismatch: {key}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("orca_checkout", type=Path)
    args = parser.parse_args()
    sha = subprocess.check_output(["git", "-C", str(args.orca_checkout), "rev-parse", "HEAD"], text=True).strip()
    profiles, _ = load_kind(args.orca_checkout, "machine")
    bundle = json.loads((ROOT / "app/src/androidTest/assets" / ASSET).read_text())
    verify_bundle(bundle, resolve(PROFILE, profiles), sha)
    if (ROOT / "app/src/main/assets" / ASSET).exists():
        raise ValueError("unqualified templates must not be shipped in main app assets")
    print("verified four exact-source A1 Mini templates for offline instrumentation only")


if __name__ == "__main__":
    main()
