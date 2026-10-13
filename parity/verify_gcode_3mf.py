#!/usr/bin/env python3
"""Validate an Orca G-code 3MF before admitting it as parity evidence."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compare_gcode import parse_metrics


def validate(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit(f"Missing or empty G-code 3MF: {path}")

    try:
        with ZipFile(path) as archive:
            bad_member = archive.testzip()
            if bad_member:
                raise SystemExit(f"Corrupt 3MF member: {bad_member}")
            gcode_members = sorted(
                name for name in archive.namelist()
                if name.lower().startswith("metadata/plate_")
                and name.lower().endswith(".gcode")
            )
            if not gcode_members:
                raise SystemExit(f"No Metadata/plate_N.gcode toolpath in {path}")
            if any(archive.getinfo(name).file_size == 0 for name in gcode_members):
                raise SystemExit(f"Empty plate toolpath in {path}")
    except BadZipFile as error:
        raise SystemExit(f"Invalid ZIP/3MF archive {path}: {error}") from error

    metrics = parse_metrics(path, gcode_members[0])
    if metrics.layer_count < 1 or metrics.extrusion_moves < 1 or metrics.positive_e_mm <= 0:
        raise SystemExit(
            f"No usable extrusion toolpath in {path}: "
            f"layers={metrics.layer_count}, extrusion_moves={metrics.extrusion_moves}, "
            f"positive_e_mm={metrics.positive_e_mm:.4f}"
        )

    print(
        f"Validated {path}: {len(gcode_members)} plate toolpath(s), "
        f"{metrics.layer_count} extruding Z levels, "
        f"{metrics.extrusion_moves} extrusion moves, "
        f"{metrics.positive_e_mm:.3f} mm positive E"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archives", nargs="+", type=Path)
    args = parser.parse_args()
    for archive in args.archives:
        validate(archive)


if __name__ == "__main__":
    main()
