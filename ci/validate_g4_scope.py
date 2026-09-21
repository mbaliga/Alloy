#!/usr/bin/env python3
"""Enforce an explicit review decision for every non-projected A1 Mini field.

The static G4 audit intentionally reports the complete resolved Orca profile.
This validator prevents a new upstream field from disappearing silently: every
field not supported by the pinned SliceBeam/Prusa core must be listed in the
checked-in review scope, while required A1 Mini fields must remain supported.
This is a scope/audit gate, not proof of runtime or printer compatibility.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys


KINDS = ("printer", "process", "filament")


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: validate_g4_scope.py <audit.json> <scope.json>")
    audit_path, scope_path = map(Path, sys.argv[1:])
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    scope = json.loads(scope_path.read_text(encoding="utf-8"))
    if scope.get("schema_version") != 1:
        fail("G4 scope has an unsupported schema version")
    if not isinstance(scope.get("engine_commit"), str) or len(scope["engine_commit"]) != 40:
        fail("G4 scope must pin a SliceBeam commit")
    if scope["engine_commit"] != audit.get("slicebeam_commit"):
        fail("G4 scope engine commit does not match the audit")

    required = scope.get("required")
    excluded = scope.get("excluded")
    rationales = scope.get("rationales")
    if not isinstance(required, dict) or not isinstance(excluded, dict) or not isinstance(rationales, dict):
        fail("G4 scope requires required, excluded and rationales objects")

    for kind in KINDS:
        info = audit.get("audit", {}).get(kind)
        if not isinstance(info, dict):
            fail(f"G4 audit is missing {kind}")
        rows = info.get("keys")
        if not isinstance(rows, list):
            fail(f"G4 audit has no {kind} field rows")
        status_by_key = {}
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get("orca_key"), str):
                fail(f"G4 audit has an invalid {kind} row")
            key = row["orca_key"]
            if key in status_by_key:
                fail(f"G4 audit repeats {kind}.{key}")
            status_by_key[key] = row.get("status")

        required_keys = required.get(kind)
        if not isinstance(required_keys, list) or not required_keys:
            fail(f"G4 scope has no required {kind} fields")
        for key in required_keys:
            if key not in status_by_key:
                fail(f"G4 required field is absent from audit: {kind}.{key}")
            if status_by_key[key] != "supported":
                fail(f"G4 required field is not supported: {kind}.{key}")

        seen = {}
        groups = excluded.get(kind)
        if not isinstance(groups, dict) or not groups:
            fail(f"G4 scope has no excluded {kind} groups")
        for group, keys in groups.items():
            if group not in rationales:
                fail(f"G4 exclusion group has no rationale: {group}")
            if not isinstance(keys, list):
                fail(f"G4 exclusion group is not a list: {kind}.{group}")
            for key in keys:
                if not isinstance(key, str) or key in seen:
                    fail(f"G4 exclusion field is duplicated or invalid: {kind}.{key}")
                seen[key] = group
        for key, status in status_by_key.items():
            if status == "supported":
                if key in seen:
                    fail(f"supported field is incorrectly excluded: {kind}.{key}")
            elif key not in seen:
                fail(f"unreviewed unsupported field: {kind}.{key}")
        for key in seen:
            if key not in status_by_key:
                fail(f"G4 scope excludes a field absent from the audit: {kind}.{key}")
            if key in required_keys:
                fail(f"required field is incorrectly excluded: {kind}.{key}")

    print("validated explicit G4 scope: every unsupported resolved field is reviewed")


if __name__ == "__main__":
    main()
