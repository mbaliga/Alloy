#!/usr/bin/env python3
"""Record the state of an unqualified offline template probe attempt."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

SOURCE = "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4"
STATES = {"initialized", "running", "failed", "validated"}


def record(directory: Path, state: str, exit_code: int | None = None) -> None:
    if state not in STATES:
        raise ValueError(f"unsupported probe-attempt state: {state}")
    directory.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "source_commit": SOURCE,
        "scope": "template-rendering-only",
        "qualified": False,
        "state": state,
    }
    if exit_code is not None:
        payload["exit_code"] = exit_code
    (directory / "attempt.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("state", choices=sorted(STATES))
    parser.add_argument("--exit-code", type=int)
    args = parser.parse_args()
    record(args.directory, args.state, args.exit_code)


if __name__ == "__main__":
    main()
