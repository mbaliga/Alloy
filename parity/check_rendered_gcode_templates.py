#!/usr/bin/env python3

from pathlib import Path
import re
import sys

PATTERNS = [
    re.compile(r"\{\s*(?:if|elsif|else|endif)\b", re.I),
    re.compile(r"\[[A-Za-z_][A-Za-z0-9_]*(?:\[[^\]]+\])?\]"),
]


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit("usage: check_rendered_gcode_templates.py <gcode> [...]")
    bad = []
    for raw_path in sys.argv[1:]:
        path = Path(raw_path)
        if not path.is_file() or path.stat().st_size == 0:
            raise SystemExit(f"missing/empty G-code: {path}")
        for lineno, raw in enumerate(path.read_text(errors="replace").splitlines(), 1):
            executable = raw.split(";", 1)[0]
            if any(pattern.search(executable) for pattern in PATTERNS):
                bad.append((path, lineno, raw[:240]))
    if bad:
        print("Unresolved template syntax found in executable G-code:", file=sys.stderr)
        for path, lineno, raw in bad[:100]:
            print(f"{path}:{lineno}: {raw}", file=sys.stderr)
        raise SystemExit(2)
    print("No unresolved template directives in executable G-code.")


if __name__ == "__main__":
    main()
