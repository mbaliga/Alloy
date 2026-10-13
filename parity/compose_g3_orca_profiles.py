#!/usr/bin/env python3
"""Compose Alloy's sparse G3 overlays onto pinned, resolved Orca profiles.

Orca's CLI imports profile files; passing a sparse project-settings fragment
can leave unspecified values at the installed default profile. Compose the
overlays first so the desktop reference is a concrete, standalone recipe.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def compose(base: dict, overlay: dict, *, label: str) -> dict:
    """Apply an explicit leaf overlay while retaining resolved base settings."""
    result = {key: value for key, value in base.items() if key != "inherits"}
    result.update({key: value for key, value in overlay.items() if key != "inherits"})
    if result.get("type") != base.get("type"):
        raise ValueError(f"{label} overlay changes profile type")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("profiles", type=Path, help="directory with resolved machine/process/filament JSON")
    parser.add_argument("project_settings", type=Path, help="checked-in G3 project settings overlay")
    parser.add_argument("filament_overlay", type=Path, help="checked-in G3 filament overlay")
    parser.add_argument("out", type=Path)
    args = parser.parse_args()

    machine = read_json(args.profiles / "machine.json")
    base_process = read_json(args.profiles / "process.json")
    base_filament = read_json(args.profiles / "filament.json")
    process_overlay = read_json(args.project_settings)
    filament_overlay = read_json(args.filament_overlay)

    if process_overlay.get("type") != "process" or filament_overlay.get("type") != "filament":
        raise ValueError("G3 overlays must be process and filament profiles respectively")
    if base_process.get("type") != "process" or base_filament.get("type") != "filament":
        raise ValueError("resolved Orca profile types are invalid")

    process = compose(base_process, process_overlay, label="process")
    filament = compose(base_filament, filament_overlay, label="filament")
    args.out.mkdir(parents=True, exist_ok=True)
    outputs = {"machine": machine, "process": process, "filament": filament}
    manifest = {"inputs": {}, "outputs": {}}
    for name, value in outputs.items():
        path = args.out / f"{name}.json"
        path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        manifest["outputs"][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name, path in {
        "project_settings": args.project_settings,
        "filament_overlay": args.filament_overlay,
        "resolved_machine": args.profiles / "machine.json",
        "resolved_process": args.profiles / "process.json",
        "resolved_filament": args.profiles / "filament.json",
    }.items():
        manifest["inputs"][name] = hashlib.sha256(path.read_bytes()).hexdigest()
    (args.out / "g3-profile-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Composed standalone G3 profiles in {args.out}")


if __name__ == "__main__":
    main()
