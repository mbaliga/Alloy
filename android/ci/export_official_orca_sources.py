#!/usr/bin/env python3
"""Export the exact pinned official libslic3r C++ source list for CMake.

The generated CMake file is consumed only by Alloy's opt-in Android compile
probe. It does not modify the upstream checkout, link the target into Alloy,
or claim engine/runtime parity.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci.audit_official_orca_port_surface import official_cpp_sources


def render_source_manifest(official_source: Path) -> str:
    engine_root = official_source / "src/libslic3r"
    cmake_path = engine_root / "CMakeLists.txt"
    sources = sorted(official_cpp_sources(cmake_path))
    if not sources:
        raise ValueError("official libslic3r source list is empty")

    missing = [source for source in sources if not (engine_root / source).is_file()]
    if missing:
        raise ValueError("official libslic3r source files are missing: " + ", ".join(missing[:8]))

    absolute_sources = [f'    "{(engine_root / source).resolve().as_posix()}"' for source in sources]
    return "set(OFFICIAL_ORCA_CPP_SOURCES\n" + "\n".join(absolute_sources) + "\n)\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--official-source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    try:
        rendered = render_source_manifest(args.official_source.resolve())
    except ValueError as error:
        raise SystemExit(f"official Orca compile-probe manifest failed: {error}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    source_count = rendered.count(".cpp\"")
    print(f"exported {source_count} official C++ sources to {args.output}")


if __name__ == "__main__":
    main()
