#!/usr/bin/env python3
"""Fail-closed verifier for Alloy's pinned official OrcaSlicer source snapshot.

The `official-orca` migration must have a reproducible, license-bearing source
root before it can ever be wired into Android.  This guard verifies the tracked
Gitlink without requiring submodules locally, and performs stronger checkout
validation when `--source-root` is supplied (as it is in CI).

It intentionally does *not* prove that the source builds, that Alloy adapts it
correctly, or that it is safe for a physical printer.  Those are later G2–G4
and physical-printer gates.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
MODULE_NAME = "orca-official"
MODULE_PATH = "third_party/orca-official"
OFFICIAL_URL = "https://github.com/OrcaSlicer/OrcaSlicer.git"
OFFICIAL_SHA = "ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6"


def run_git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        raise ValueError(
            f"git {' '.join(args)} failed in {root}: {completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def normalize_url(value: str) -> str:
    return value.rstrip("/").removesuffix(".git")


def require_gitlink() -> None:
    module_file = ROOT / ".gitmodules"
    if not module_file.is_file():
        raise ValueError(".gitmodules is missing")
    configured_path = run_git(ROOT, "config", "-f", ".gitmodules", "--get", f"submodule.{MODULE_NAME}.path")
    configured_url = run_git(ROOT, "config", "-f", ".gitmodules", "--get", f"submodule.{MODULE_NAME}.url")
    if configured_path != MODULE_PATH:
        raise ValueError(f"submodule path is {configured_path!r}, expected {MODULE_PATH!r}")
    if normalize_url(configured_url) != normalize_url(OFFICIAL_URL):
        raise ValueError(f"submodule URL is {configured_url!r}, expected official OrcaSlicer")

    rows = run_git(ROOT, "ls-files", "--stage", "--", MODULE_PATH).splitlines()
    if len(rows) != 1:
        raise ValueError(f"expected one tracked Gitlink at {MODULE_PATH}, found {len(rows)}")
    metadata, tracked_path = rows[0].split("\t", 1)
    mode, object_id, stage = metadata.split()
    if tracked_path != MODULE_PATH or mode != "160000" or stage != "0":
        raise ValueError(f"{MODULE_PATH} is not a normal staged Gitlink: {rows[0]!r}")
    if object_id != OFFICIAL_SHA:
        raise ValueError(
            f"{MODULE_PATH} pins {object_id}, expected official baseline {OFFICIAL_SHA}"
        )


def require_checkout(source_root: Path) -> None:
    if not source_root.is_dir():
        raise ValueError(f"official source root is missing: {source_root}")
    actual_sha = run_git(source_root, "rev-parse", "HEAD")
    if actual_sha != OFFICIAL_SHA:
        raise ValueError(f"official checkout is {actual_sha}, expected {OFFICIAL_SHA}")
    actual_url = run_git(source_root, "config", "--get", "remote.origin.url")
    if normalize_url(actual_url) != normalize_url(OFFICIAL_URL):
        raise ValueError(f"official checkout remote is {actual_url!r}, expected official OrcaSlicer")
    if run_git(source_root, "status", "--porcelain"):
        raise ValueError("official checkout has uncommitted modifications")
    license_file = source_root / "LICENSE.txt"
    if not license_file.is_file() or "GNU Affero General Public License" not in license_file.read_text(errors="replace"):
        raise ValueError("official checkout is missing its AGPL LICENSE.txt")
    engine_root = source_root / "src/libslic3r"
    if not (engine_root / "CMakeLists.txt").is_file() or not any(engine_root.rglob("*.cpp")):
        raise ValueError("official checkout does not contain a usable libslic3r source root")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source-root",
        type=Path,
        help="initialized official submodule checkout to validate in addition to the Gitlink",
    )
    args = parser.parse_args()
    try:
        require_gitlink()
        if args.source_root is not None:
            require_checkout(args.source_root.resolve())
    except ValueError as error:
        print(f"official Orca snapshot validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    target = f" and checkout {args.source_root}" if args.source_root else ""
    print(f"validated official Orca source snapshot {OFFICIAL_SHA}{target}")


if __name__ == "__main__":
    main()
