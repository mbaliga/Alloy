#!/usr/bin/env python3
"""Fail closed unless a reviewed production-readiness record is complete.

The Android build and emulator suite prove that the candidate can be built and
tested. They do not prove Bambu toolpath parity or physical A1 Mini behavior.
This validator is intentionally small and schema-driven so a production tag
cannot accidentally bypass those external gates.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


REQUIRED_GATES = (
    "native_engine_source_build",
    "native_smoke_on_arm64_device",
    "a1_mini_profile_parity",
    "a1_profile_parity",
    "p1s_profile_parity",
    "a1_mini_g3_toolpath_parity",
    "a1_g3_toolpath_parity",
    "p1s_g3_toolpath_parity",
    "a1_mini_g4_profile_runtime_parity",
    "a1_g4_profile_runtime_parity",
    "p1s_g4_profile_runtime_parity",
    "bambu_treesupport3d_parity",
    "physical_a1_mini_transport",
    "physical_a1_transport",
    "physical_p1s_transport",
    "signed_install_and_upgrade",
    "interruption_and_recovery",
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
PRINTER_SPECIFIC_GATES = {
    "a1_mini_profile_parity": "a1-mini",
    "a1_profile_parity": "a1",
    "p1s_profile_parity": "p1s",
    "a1_mini_g3_toolpath_parity": "a1-mini",
    "a1_g3_toolpath_parity": "a1",
    "p1s_g3_toolpath_parity": "p1s",
    "a1_mini_g4_profile_runtime_parity": "a1-mini",
    "a1_g4_profile_runtime_parity": "a1",
    "p1s_g4_profile_runtime_parity": "p1s",
    "physical_a1_mini_transport": "a1-mini",
    "physical_a1_transport": "a1",
    "physical_p1s_transport": "p1s",
}


def fail(message: str) -> None:
    raise SystemExit(f"release readiness: {message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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
        if "REPLACE-WITH" in value or value.strip().upper().startswith("TODO"):
            fail(f"{field} still contains a placeholder")
    normalized_scope = document["device_scope"].lower()
    if ("a1 mini" not in normalized_scope or "n1" not in normalized_scope
            or re.search(r"\ba1\b", normalized_scope) is None
            or "p1s" not in normalized_scope):
        fail("device_scope must identify A1 Mini/N1, A1, and P1S")
    gates = document.get("gates")
    if not isinstance(gates, dict):
        fail("gates must be an object")
    missing = [gate for gate in REQUIRED_GATES if gates.get(gate) != "PASS"]
    if missing:
        fail("required gates are not PASS: " + ", ".join(missing))
    gate_evidence = document.get("gate_evidence")
    if not isinstance(gate_evidence, dict):
        fail("gate_evidence must map every PASS gate to a retained evidence file")
    for gate in REQUIRED_GATES:
        item = gate_evidence.get(gate)
        if not isinstance(item, dict):
            fail(f"gate_evidence.{gate} must identify a retained evidence file")
        expected_printer = PRINTER_SPECIFIC_GATES.get(gate)
        if expected_printer is not None and item.get("printer_id") != expected_printer:
            fail(f"gate_evidence.{gate}.printer_id must be {expected_printer}")
        evidence_name = item.get("path")
        expected_sha256 = item.get("sha256")
        if not isinstance(evidence_name, str) or not evidence_name.strip():
            fail(f"gate_evidence.{gate}.path must be a non-empty relative path")
        if Path(evidence_name).is_absolute():
            fail(f"gate_evidence.{gate}.path must be relative to the readiness record")
        if not isinstance(expected_sha256, str) or not SHA256_RE.fullmatch(expected_sha256):
            fail(f"gate_evidence.{gate}.sha256 must be a lowercase SHA-256 digest")
        evidence_path = (path.parent / evidence_name).resolve()
        if path.parent.resolve() not in evidence_path.parents:
            fail(f"gate_evidence.{gate}.path must stay beside the readiness record")
        if not evidence_path.is_file():
            fail(f"gate_evidence.{gate} file is missing: {evidence_name}")
        try:
            actual_sha256 = sha256_file(evidence_path)
        except OSError as error:
            fail(f"gate_evidence.{gate} file cannot be read: {error}")
        if actual_sha256 != expected_sha256:
            fail(f"gate_evidence.{gate} SHA-256 does not match: {evidence_name}")
    physical_gate_printers = {
        "physical_a1_mini_transport": "a1-mini",
        "physical_a1_transport": "a1",
        "physical_p1s_transport": "p1s",
    }
    physical_evidence = document.get("physical_acceptance_evidence", {})
    if not isinstance(physical_evidence, dict):
        fail("physical_acceptance_evidence must map printer IDs to retained acceptance records")
    for gate, printer in physical_gate_printers.items():
        if gates[gate] != "PASS":
            continue
        evidence_name = physical_evidence.get(printer)
        if not isinstance(evidence_name, str) or not evidence_name.strip():
            fail(f"physical_acceptance_evidence.{printer} is required when {gate} is PASS")
        evidence_path = (path.parent / evidence_name).resolve()
        if path.parent.resolve() not in evidence_path.parents:
            fail(f"physical_acceptance_evidence.{printer} must stay beside the readiness record")
        try:
            from ci.validate_physical_a1_acceptance import validate as validate_physical
            validate_physical(evidence_path, printer)
        except SystemExit as error:
            fail(f"physical acceptance evidence for {printer} is invalid: {error}")
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
