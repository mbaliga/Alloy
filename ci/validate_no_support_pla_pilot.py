#!/usr/bin/env python3
"""Validate a reviewed A1 Mini / PLA Basic / no-support pilot record.

This validates a complete, immutable evidence record. It never substitutes for
human review of the underlying measurements, photographs and printer telemetry.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
FIXTURES = (
    "box-20mm",
    "mounting-block",
    "thin-wall-frame",
    "travel-obstacle",
    "box-and-lid",
)
REQUIRED_CHECKS = (
    "native_smoke",
    "package_preflight",
    "first_layer",
    "dimensions",
    "surface_quality",
    "no_collision",
    "completion",
)


def fail(message: str) -> None:
    raise SystemExit(f"no-support PLA pilot: {message}")


def digest(record: dict, field: str) -> None:
    value = record.get(field)
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        fail(f"{field} must be a 64-character SHA-256 digest")


def validate(path: Path) -> dict:
    if not path.is_file():
        fail(f"evidence file is missing: {path}")
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"evidence file is not valid JSON: {error}")
    if not isinstance(record, dict) or record.get("schema_version") != 1:
        fail("schema_version must be 1")
    for field in ("evidence_id", "verified_at", "reviewer", "device_scope", "attestation"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            fail(f"{field} must be a non-empty string")
    scope = record["device_scope"].lower()
    for required in ("a1 mini", "n1", "0.4", "pla basic", "no supports"):
        if required not in scope:
            fail(f"device_scope must state {required!r}")
    for field in ("apk_sha256", "instrumentation_sha256", "signer_sha256", "profile_sha256"):
        digest(record, field)

    fixtures = record.get("fixtures")
    if not isinstance(fixtures, dict):
        fail("fixtures must be an object")
    for fixture in FIXTURES:
        entry = fixtures.get(fixture)
        if not isinstance(entry, dict):
            fail(f"fixtures.{fixture} must be an object")
        if entry.get("result") != "PASS":
            fail(f"fixtures.{fixture}.result must be PASS")
        digest(entry, "artifact_sha256")
        checks = entry.get("checks")
        if not isinstance(checks, dict):
            fail(f"fixtures.{fixture}.checks must be an object")
        missing = [check for check in REQUIRED_CHECKS if checks.get(check) != "PASS"]
        if missing:
            fail(f"fixtures.{fixture} checks are not PASS: {', '.join(missing)}")
    return record


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_no_support_pla_pilot.py <evidence.json>")
    record = validate(Path(sys.argv[1]))
    print(f"validated no-support PLA pilot {record['evidence_id']}")


if __name__ == "__main__":
    main()
