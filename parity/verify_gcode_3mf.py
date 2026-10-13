#!/usr/bin/env python3

from pathlib import Path
import sys
from zipfile import ZipFile


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: verify_gcode_3mf.py <file.gcode.3mf> [...]")
    for raw in sys.argv[1:]:
        path = Path(raw)
        if not path.is_file() or path.stat().st_size == 0:
            raise SystemExit(f"missing/empty archive: {path}")
        with ZipFile(path) as zf:
            members = zf.namelist()
            gcode = sorted(
                m for m in members
                if m.startswith("Metadata/plate_") and m.endswith(".gcode")
            )
            if not gcode:
                raise SystemExit(
                    f"No Metadata/plate_*.gcode in {path}; first members: {members[:30]}"
                )
            for member in gcode:
                if zf.getinfo(member).file_size == 0:
                    raise SystemExit(f"empty G-code member: {path}:{member}")
            print(f"{path}: {', '.join(gcode)}")


if __name__ == "__main__":
    main()
