#!/usr/bin/env python3
"""Prepare an Orca CLI-compatible wrapper around exact Bambu project settings."""

import argparse
import json
from pathlib import Path

try:  # Package import in tests; sibling import when run as a CI script.
    from .neutralize_bambu_motion_hooks import HOOK_KEYS, NEUTRAL_HOOK_VALUE
except ImportError:  # pragma: no cover - exercised by the workflow CLI
    from neutralize_bambu_motion_hooks import HOOK_KEYS, NEUTRAL_HOOK_VALUE


ORCA_COMPATIBILITY_FIELDS = (
    "compatible_printers",
    "compatible_printers_condition",
    "setting_id",
    "instantiation",
)


def prepare(
    project_settings_path: Path,
    process_profile_path: Path,
    output_path: Path,
    *,
    neutralize_motion_hooks: bool = False,
) -> None:
    project_settings = json.loads(project_settings_path.read_text(encoding="utf-8"))
    process_profile = json.loads(process_profile_path.read_text(encoding="utf-8"))

    if "type" in project_settings:
        raise ValueError("project_settings fixture must remain a project document")
    if project_settings.get("from") != "project":
        raise ValueError("project_settings fixture must retain its source provenance")

    compatible_printers = process_profile.get("compatible_printers")
    if not isinstance(compatible_printers, list) or not compatible_printers:
        raise ValueError("resolved Orca process profile has no compatible_printers metadata")

    wrapped = dict(project_settings)
    wrapped["type"] = "process"
    wrapped["from"] = "user"
    for key in ORCA_COMPATIBILITY_FIELDS:
        if key in process_profile:
            wrapped[key] = process_profile[key]
    if neutralize_motion_hooks:
        for key in HOOK_KEYS:
            if not isinstance(wrapped.get(key), str):
                raise ValueError(f"project_settings fixture must contain string {key}")
            wrapped[key] = NEUTRAL_HOOK_VALUE

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(wrapped, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project_settings", type=Path)
    parser.add_argument("process_profile", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--neutralize-motion-hooks",
        action="store_true",
        help="replace motion hooks with an inert comment for the diagnostic-only lane",
    )
    args = parser.parse_args()
    prepare(
        args.project_settings,
        args.process_profile,
        args.output,
        neutralize_motion_hooks=args.neutralize_motion_hooks,
    )
