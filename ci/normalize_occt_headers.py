#!/usr/bin/env python3
"""Make generated OCCT public headers portable inside the Alloy app tree.

OCCT's Android bootstrap copies generated public headers that may be thin
wrappers around files in the temporary source checkout.  Those wrappers are
useful during the upstream build but leak the checkout's absolute path into
the copied artifact.  Alloy keeps the pinned OCCT source tree beside the
generated headers and rewrites only that wrapper prefix to the repo-local
``src/`` path.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


OCCT_INCLUDE = re.compile(r'(#include\s+")[^"\n]*/OCCT/src/([^"\n]+)(")')
LEAK = re.compile(r'(?m)^\s*#include\s+"(?:/|[^"\n]*[\\/])[^"\n]*/OCCT/src/')


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: normalize_occt_headers.py <occt-root>")

    root = Path(sys.argv[1]).resolve()
    include_root = root / "include"
    source_root = root / "src"
    if not include_root.is_dir():
        raise SystemExit(f"{include_root}: OCCT include directory is missing")
    if not source_root.is_dir():
        raise SystemExit(f"{source_root}: pinned OCCT source directory is missing")

    changed = 0
    leaked = []
    for path in include_root.rglob("*"):
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        updated, count = OCCT_INCLUDE.subn(r'\1src/\2\3', text)
        if count:
            path.write_text(updated, encoding="utf-8")
            changed += count
        if LEAK.search(updated):
            leaked.append(str(path))

    if leaked:
        raise SystemExit("OCCT absolute source includes remain:\n" + "\n".join(leaked[:20]))
    print(f"normalized {changed} generated OCCT source includes under {include_root}")


if __name__ == "__main__":
    main()
