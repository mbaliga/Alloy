#!/usr/bin/env python3
"""Create an audit-only Orca machine profile with Bambu motion hooks empty."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


HOOK_KEYS = ("time_lapse_gcode", "wrapping_detection_gcode")
REQUIRED_TIME_LAPSE_MARKERS = ("SKIPTYPE: timelapse", "SKIPTYPE: head_wrap_detect")
OUTPUT_NAME = "machine-hooks-neutralized.json"
MANIFEST_NAME = "machine-hooks-neutralized-manifest.json"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def create_neutral_profile(source: Path, output_dir: Path) -> dict[str, Any]:
    raw = source.read_bytes()
    try:
        profile = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("source machine profile is not valid UTF-8 JSON") from error
    if not isinstance(profile, dict):
        raise ValueError("source machine profile must be a JSON object")

    original_values: dict[str, str] = {}
    for key in HOOK_KEYS:
        value = profile.get(key)
        if not isinstance(value, str):
            raise ValueError(f"source machine profile must contain string {key}")
        original_values[key] = value
    # This lane is specifically meant to remove an extant Bambu time-lapse
    # block (which also contains head-wrap SKIPTYPE code in the pinned A1 Mini
    # snapshot), not silently compare two already-neutral profiles.
    if not original_values["time_lapse_gcode"].strip():
        raise ValueError("source time_lapse_gcode is already empty; no counterfactual to isolate")
    missing_markers = [marker for marker in REQUIRED_TIME_LAPSE_MARKERS
                       if marker not in original_values["time_lapse_gcode"]]
    if missing_markers:
        raise ValueError("source time_lapse_gcode lacks expected Bambu hook marker(s): "
                         + ", ".join(missing_markers))
    for key in HOOK_KEYS:
        profile[key] = ""

    output_dir.mkdir(parents=True, exist_ok=True)
    profile_path = output_dir / OUTPUT_NAME
    profile_bytes = (json.dumps(profile, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    profile_path.write_bytes(profile_bytes)
    manifest = {
        "scope": "hook-neutralized-g3-counterfactual-only",
        "qualified": False,
        "source_profile": str(source.name),
        "source_profile_sha256": sha256(raw),
        "neutral_profile": OUTPUT_NAME,
        "neutral_profile_sha256": sha256(profile_bytes),
        "cleared_keys": list(HOOK_KEYS),
        "cleared_source_values_nonempty": {
            key: bool(value.strip()) for key, value in original_values.items()
        },
        "unchanged_key_count": len(set(profile) - set(HOOK_KEYS)),
    }
    (output_dir / MANIFEST_NAME).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_profile", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    try:
        manifest = create_neutral_profile(args.source_profile, args.output_dir)
    except (OSError, ValueError) as error:
        raise SystemExit(f"hook-neutralized profile generation failed: {error}") from None
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
