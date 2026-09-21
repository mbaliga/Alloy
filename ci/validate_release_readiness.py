#!/usr/bin/env python3
"""Fail closed unless a reviewed production-readiness record is complete.

The Android build and emulator suite prove that the candidate can be built and
tested. They do not prove Bambu toolpath parity or physical A1 Mini behavior.
This validator is intentionally small and schema-driven so a production tag
cannot accidentally bypass those external gates.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


REQUIRED_GATES = (
    "native_engine_source_build",
    "native_smoke_on_arm64_device",
    "a1_mini_profile_parity",
    "g3_toolpath_parity",
    "g4_profile_runtime_parity",
    "bambu_treesupport3d_parity",
    "physical_a1_mini_transport",
    "signed_install_and_upgrade",
    "interruption_and_recovery",
)


def fail(message: str) -> None:
    raise SystemExit(f"release readiness: {message}")


def validate(path: Path) -> dict:
    if not path.is_file():
        fail(f"evidence file is missing: {path}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"evidence file is not valid JSON: {error}")
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        fail("schema_version must be 1")
    for field in ("evidence_id", "verified_at", "reviewer", "device_scope"):
        value = document.get(field)
        if not isinstance(value, str) or not value.strip():
            fail(f"{field} must be a non-empty string")
    gates = document.get("gates")
    if not isinstance(gates, dict):
        fail("gates must be an object")
    missing = [gate for gate in REQUIRED_GATES if gates.get(gate) != "PASS"]
    if missing:
        fail("required gates are not PASS: " + ", ".join(missing))
    if gates["physical_a1_mini_transport"] == "PASS":
        evidence_name = document.get("physical_acceptance_evidence")
        if not isinstance(evidence_name, str) or not evidence_name.strip():
            fail("physical_acceptance_evidence is required when physical transport is PASS")
        evidence_path = (path.parent / evidence_name).resolve()
        if path.parent.resolve() not in evidence_path.parents:
            fail("physical_acceptance_evidence must stay beside the readiness record")
        try:
            from ci.validate_physical_a1_acceptance import validate as validate_physical
            validate_physical(evidence_path)
        except SystemExit as error:
            fail(f"physical acceptance evidence is invalid: {error}")
    return document


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: validate_release_readiness.py <evidence.json>")
    document = validate(Path(sys.argv[1]))
    print(
        "validated production readiness evidence "
        f"{document['evidence_id']} for {document['device_scope']}"
    )


if __name__ == "__main__":
    main()
