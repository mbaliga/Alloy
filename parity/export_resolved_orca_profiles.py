#!/usr/bin/env python3
"""Resolve a pinned Orca BBL profile set into standalone JSON files.

The CLI `--load-settings` path is easier to make reproducible with fully resolved
profiles than with system-profile inheritance that depends on an installation's
profile registry. This script reads only the supplied Orca checkout and never
fetches network profile data.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

DEFAULTS = {
    "machine": "Bambu Lab A1 mini 0.4 nozzle",
    "process": "0.20mm Standard @BBL A1M",
    "filament": "Bambu PLA Basic @BBL A1M",
}

# Exact leaf names from the pinned Orca profile snapshot. The P1S uses the
# X1C process and filament leaves in this snapshot; the machine profile remains
# P1S-specific. Keep this matrix diagnostic and source-pinned rather than
# silently guessing names from a printer label.
PRINTER_PROFILES = {
    "a1mini": {
        "machine": "Bambu Lab A1 mini 0.4 nozzle",
        "process": "0.20mm Standard @BBL A1M",
        "filament": "Bambu PLA Basic @BBL A1M",
    },
    "a1": {
        "machine": "Bambu Lab A1 0.4 nozzle",
        "process": "0.20mm Standard @BBL A1",
        "filament": "Bambu PLA Basic @BBL A1",
    },
    "p1s": {
        "machine": "Bambu Lab P1S 0.4 nozzle",
        "process": "0.20mm Standard @BBL X1C",
        "filament": "Bambu PLA Basic @BBL X1C",
    },
}


def load_kind(root: Path, kind: str) -> tuple[dict[str, dict], dict[str, Path]]:
    folder = root / "resources/profiles/BBL" / ("machine" if kind == "machine" else kind)
    profiles: dict[str, dict] = {}
    sources: dict[str, Path] = {}
    for path in sorted(folder.rglob("*.json")):
        try:
            data = json.loads(path.read_text(errors="replace"))
        except json.JSONDecodeError:
            continue
        name = data.get("name")
        if isinstance(name, str) and name:
            profiles[name] = data
            sources[name] = path
    return profiles, sources


def resolve(name: str, profiles: dict[str, dict], stack: tuple[str, ...] = ()) -> dict:
    if name in stack:
        raise RuntimeError("inheritance cycle: " + " -> ".join((*stack, name)))
    if name not in profiles:
        raise KeyError(f"profile not found: {name}")
    data = profiles[name]
    result: dict = {}
    parent = data.get("inherits")
    parents = [parent] if isinstance(parent, str) else list(parent or [])
    for item in parents:
        if item:
            result.update(resolve(str(item), profiles, (*stack, name)))
    result.update({k: v for k, v in data.items() if k != "inherits"})
    # Preserve the selected leaf identity even when a base profile supplied a name.
    result["name"] = name
    return result


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("orca_checkout", type=Path)
    p.add_argument("out", type=Path)
    p.add_argument("--machine", default=DEFAULTS["machine"])
    p.add_argument("--process", default=DEFAULTS["process"])
    p.add_argument("--filament", default=DEFAULTS["filament"])
    p.add_argument("--printer", choices=sorted(PRINTER_PROFILES), action="append",
                   help="export one or more documented printer profile sets")
    args = p.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    printer_ids = args.printer or [None]
    manifest = {"profile_source": "pinned Orca checkout", "printers": {}}
    loaded = {kind: load_kind(args.orca_checkout, kind) for kind in ("machine", "process", "filament")}

    for printer_id in printer_ids:
        selected = (PRINTER_PROFILES[printer_id] if printer_id else {
            "machine": args.machine,
            "process": args.process,
            "filament": args.filament,
        })
        output_dir = args.out / printer_id if printer_id else args.out
        output_dir.mkdir(parents=True, exist_ok=True)
        printer_manifest = {}
        for kind, name in selected.items():
            profiles, sources = loaded[kind]
            resolved = resolve(name, profiles)
            destination = output_dir / f"{kind}.json"
            destination.write_text(json.dumps(resolved, indent=2, ensure_ascii=False) + "\n")
            printer_manifest[kind] = {
                "name": name,
                "leaf_source": str(sources[name].relative_to(args.orca_checkout)),
                "output": str(destination.relative_to(args.out)),
                "resolved_key_count": len(resolved),
            }
            print(f"{printer_id or 'default'} {kind}: {name} -> {destination} ({len(resolved)} keys)")
        manifest["printers"][printer_id or "default"] = printer_manifest

    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
