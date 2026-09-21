#!/usr/bin/env python3
"""Validate the native G3 evidence bundle before CI publishes it."""

from __future__ import annotations

import sys
from pathlib import Path


REQUIRED = (
    "cube_20mm.gcode",
    "cube_20mm.config.ini",
    "overhang_support.gcode",
    "overhang_support.config.ini",
    "thin_wall_frame.gcode",
    "thin_wall_frame.config.ini",
)


def fail(message: str) -> None:
    raise SystemExit(f"G3 evidence: {message}")


def validate(root: Path) -> None:
    if not root.is_dir():
        fail(f"evidence directory is missing: {root}")
    for name in REQUIRED:
        path = root / name
        if not path.is_file() or path.stat().st_size == 0:
            fail(f"required artifact is missing or empty: {name}")
    for name in ("cube_20mm.gcode", "overhang_support.gcode", "thin_wall_frame.gcode"):
        text = (root / name).read_text(encoding="utf-8", errors="replace")
        # SliceBeam emits ;LAYER_CHANGE while the fallback/test fixtures use
        # the older ;LAYER:<n> spelling. Accept both explicit layer contracts.
        if ";LAYER:" not in text and ";LAYER_CHANGE" not in text:
            fail(f"{name} has no layer markers")
        if "G90" not in text or "M104 S0" not in text:
            fail(f"{name} is missing the basic machine-safety markers")
    support_config = (root / "overhang_support.config.ini").read_text(encoding="utf-8", errors="replace")
    if "enable_support = 1" not in support_config or "support_type = " not in support_config:
        fail("overhang support config is not explicitly support-enabled")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_g3_evidence.py <evidence-directory>")
    validate(Path(sys.argv[1]))
    print("validated native G3 evidence bundle: cube, overhang, thin-wall, and configs")


if __name__ == "__main__":
    main()
