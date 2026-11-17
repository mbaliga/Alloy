#!/usr/bin/env python3
"""Validate reviewed, hardware-backed acceptance records for supported printers."""

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


PRINTERS = {
    "a1-mini": ("A1 Mini", "N1"),
    "a1": ("A1", None),
    "p1s": ("P1S", None),
}


def fail(message: str, printer: str) -> None:
    label = PRINTERS[printer][0]
    raise SystemExit(f"physical {label} acceptance: {message}")


def validate(path: Path, printer: str = "a1-mini") -> dict:
    if printer not in PRINTERS:
        raise ValueError(f"unsupported physical printer key: {printer}")
    name, model_code = PRINTERS[printer]
    if not path.is_file():
        fail(f"evidence file is missing: {path}", printer)
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"evidence file is not valid JSON: {error}", printer)
    if not isinstance(record, dict) or record.get("schema_version") != 1:
        fail("schema_version must be 1", printer)

    for field in ("evidence_id", "verified_at", "reviewer", "device_scope"):
        if not isinstance(record.get(field), str) or not record[field].strip():
            fail(f"{field} must be a non-empty string", printer)
    scope = record["device_scope"].lower()
    if printer == "a1":
        matches = re.search(r"\ba1\b", scope) is not None and "a1 mini" not in scope
    else:
        matches = name.lower() in scope
    if not matches or (model_code is not None and model_code.lower() not in scope):
        expected = f"Bambu Lab {name}" + (f" model code {model_code}" if model_code else "")
        fail(f"device_scope must identify {expected}", printer)

    for field in ("apk_sha256", "instrumentation_sha256", "signer_sha256",
                  "printer_certificate_sha256"):
        value = record.get(field)
        if not isinstance(value, str) or not SHA256.fullmatch(value):
            fail(f"{field} must be a 64-character SHA-256 digest", printer)

    cases = record.get("cases")
    if not isinstance(cases, dict):
        fail("cases must be an object", printer)
    missing = [case for case in REQUIRED_CASES if cases.get(case) != "PASS"]
    if missing:
        fail("required cases are not PASS: " + ", ".join(missing), printer)
    return record


def main() -> None:
    if len(sys.argv) not in (2, 3):
        fail("usage: validate_physical_a1_acceptance.py <evidence.json> [a1-mini|a1|p1s]", "a1-mini")
    printer = sys.argv[2] if len(sys.argv) == 3 else "a1-mini"
    record = validate(Path(sys.argv[1]), printer)
    print(f"validated physical {PRINTERS[printer][0]} acceptance {record['evidence_id']}")


if __name__ == "__main__":
    main()
