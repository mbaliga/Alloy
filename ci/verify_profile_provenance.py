#!/usr/bin/env python3
"""Verify that a normalized Alloy profile still names exact upstream blobs.

Usage:
  python3 ci/verify_profile_provenance.py <upstream-checkout> <profile.json> [...]

This intentionally validates provenance only. It proves the listed source
inputs and commit are real; slicer parity and physical-print promotion remain
separate gates.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path


def blob_sha(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha1(f"blob {len(data)}\0".encode("utf-8") + data).hexdigest()


def head(checkout: Path) -> str:
    result = subprocess.run(
        ["git", "-C", str(checkout), "rev-parse", "HEAD"],
        check=False, capture_output=True, text=True,
    )
    if result.returncode:
        raise ValueError(f"{checkout} is not a readable Git checkout")
    return result.stdout.strip().lower()


def verify(checkout: Path, profile_path: Path) -> None:
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    provenance = profile.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError(f"{profile_path}: missing provenance")
    revision = str(provenance.get("revision", "")).lower()
    if len(revision) != 40 or any(char not in "0123456789abcdef" for char in revision):
        raise ValueError(f"{profile_path}: provenance revision is not a commit SHA")
    checkout_head = head(checkout)
    if checkout_head != revision:
        raise ValueError(
            f"{profile_path}: checkout is {checkout_head}, expected pinned {revision}"
        )
    files = provenance.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError(f"{profile_path}: provenance has no source file list")
    for item in files:
        if not isinstance(item, dict):
            raise ValueError(f"{profile_path}: invalid provenance file entry")
        relative = str(item.get("path", ""))
        expected = str(item.get("sha", "")).lower()
        if not relative or "/../" in f"/{relative}" or relative.startswith("/"):
            raise ValueError(f"{profile_path}: unsafe source path {relative!r}")
        source = checkout / relative
        if not source.is_file():
            raise ValueError(f"{profile_path}: missing upstream source {relative}")
        actual = blob_sha(source)
        if actual != expected:
            raise ValueError(
                f"{profile_path}: {relative} blob mismatch: expected {expected}, got {actual}"
            )
        print(f"verified {profile_path.name}: {relative}")


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    checkout = Path(argv[1]).resolve()
    try:
        for value in argv[2:]:
            verify(checkout, Path(value).resolve())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"profile provenance verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
