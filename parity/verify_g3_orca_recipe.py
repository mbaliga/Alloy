#!/usr/bin/env python3
"""Fail if an emitted Orca G-code CONFIG_BLOCK differs from the G3 recipe."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from compose_g3_orca_profiles import read_json
from verify_g3_profile import FIELDS, normalize, read_desktop_gcode_settings


def verify(expected: dict, actual: dict) -> dict:
    checks = []
    for field, (desktop_key, _) in FIELDS.items():
        expected_value = expected.get(desktop_key)
        actual_value = actual.get(desktop_key)
        if expected_value is None or actual_value is None:
            checks.append({
                "field": field,
                "status": "fail",
                "expected": expected_value,
                "actual": actual_value,
                "detail": "recipe or emitted CONFIG_BLOCK is missing a source-backed field",
            })
            continue
        wanted = normalize(field, expected_value, "desktop", expected)
        got = normalize(field, actual_value, "desktop", actual)
        checks.append({"field": field, "status": "pass" if wanted == got else "fail",
                       "expected": wanted, "actual": got})
    failures = [item["field"] for item in checks if item["status"] != "pass"]
    return {"result": "fail" if failures else "pass", "checked_fields": len(checks),
            "failed_fields": failures, "checks": checks}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("gcode_3mf", type=Path)
    parser.add_argument("process", type=Path)
    parser.add_argument("filament", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    expected = read_json(args.process)
    expected.update(read_json(args.filament))
    actual = read_desktop_gcode_settings(args.gcode_3mf)
    report = verify(expected, actual)
    rendered = json.dumps(report, indent=2) + "\n"
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    raise SystemExit(0 if report["result"] == "pass" else 2)


if __name__ == "__main__":
    main()
