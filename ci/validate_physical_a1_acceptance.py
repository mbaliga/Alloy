#!/usr/bin/env python3
"""Validate the reviewed, hardware-backed A1 Mini acceptance record."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


REQUIRED_CASES = (
    "discovery", "upload", "start", "running", "completion",
    "pause_resume", "cancel", "recovery", "offline",
)
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"physical A1 Mini acceptance: {message}")


def validate(path: Path) -> dict:
    if not path.is_file():
        fail(f"evidence file is missing: {path}")
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"evidence file is not valid JSON: {error}")
    if not isinstance(record, dict) or record.get("schema_version") != 1:
        fail("schema_version must be 1")

    for field in ("evidence_id", "verified_at", "reviewer", "device_scope"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            fail(f"{field} must be a non-empty string")
    scope = record["device_scope"].lower()
    if "a1 mini" not in scope or "n1" not in scope:
        fail("device_scope must identify Bambu Lab A1 Mini model code N1")

    for field in ("apk_sha256", "instrumentation_sha256", "signer_sha256",
                  "printer_certificate_sha256"):
        value = record.get(field)
        if not isinstance(value, str) or not SHA256.fullmatch(value):
            fail(f"{field} must be a 64-character SHA-256 digest")

    cases = record.get("cases")
    if not isinstance(cases, dict):
        fail("cases must be an object")
    missing = [case for case in REQUIRED_CASES if cases.get(case) != "PASS"]
    if missing:
        fail("required cases are not PASS: " + ", ".join(missing))
    return record


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_physical_a1_acceptance.py <evidence.json>")
    record = validate(Path(sys.argv[1]))
    print(f"validated physical A1 Mini acceptance {record['evidence_id']}")


if __name__ == "__main__":
    main()
