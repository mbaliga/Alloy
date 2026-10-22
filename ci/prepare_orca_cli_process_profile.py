#!/usr/bin/env python3
"""Prepare an Orca CLI-compatible wrapper around exact Bambu project settings."""

import json
from pathlib import Path
import sys


ORCA_COMPATIBILITY_FIELDS = (
    "compatible_printers",
    "compatible_printers_condition",
    "setting_id",
    "instantiation",
)


def prepare(project_settings_path: Path, process_profile_path: Path, output_path: Path) -> None:
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

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(wrapped, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: prepare_orca_cli_process_profile.py PROJECT_SETTINGS PROCESS_PROFILE OUTPUT")
    prepare(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
