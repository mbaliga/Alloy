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
LIBNOISE_MODULE_NAME = "orca-deps-libnoise"
LIBNOISE_MODULE_PATH = "third_party/orca-deps-libnoise"
LIBNOISE_URL = "https://github.com/SoftFever/Orca-deps-libnoise.git"
LIBNOISE_SHA = "f25d5331570ae109f0e645cb729ecab155612714"


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


def require_gitlink(name: str, path: str, url: str, revision: str) -> None:
    module_file = ROOT / ".gitmodules"
    if not module_file.is_file():
        raise ValueError(".gitmodules is missing")
    configured_path = run_git(ROOT, "config", "-f", ".gitmodules", "--get", f"submodule.{name}.path")
    configured_url = run_git(ROOT, "config", "-f", ".gitmodules", "--get", f"submodule.{name}.url")
    if configured_path != path:
        raise ValueError(f"submodule {name} path is {configured_path!r}, expected {path!r}")
    if normalize_url(configured_url) != normalize_url(url):
        raise ValueError(f"submodule {name} URL is {configured_url!r}, expected {url!r}")

    rows = run_git(ROOT, "ls-files", "--stage", "--", path).splitlines()
    if len(rows) != 1:
        raise ValueError(f"expected one tracked Gitlink at {path}, found {len(rows)}")
    metadata, tracked_path = rows[0].split("\t", 1)
    mode, object_id, stage = metadata.split()
    if tracked_path != path or mode != "160000" or stage != "0":
        raise ValueError(f"{path} is not a normal staged Gitlink: {rows[0]!r}")
    if object_id != revision:
        raise ValueError(
            f"{path} pins {object_id}, expected source revision {revision}"
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


def require_libnoise_checkout(source_root: Path) -> None:
    if not source_root.is_dir():
        raise ValueError(f"libnoise source root is missing: {source_root}")
    actual_sha = run_git(source_root, "rev-parse", "HEAD")
    if actual_sha != LIBNOISE_SHA:
        raise ValueError(f"libnoise checkout is {actual_sha}, expected {LIBNOISE_SHA}")
    actual_url = run_git(source_root, "config", "--get", "remote.origin.url")
    if normalize_url(actual_url) != normalize_url(LIBNOISE_URL):
        raise ValueError(f"libnoise checkout remote is {actual_url!r}, expected pinned official dependency")
    if run_git(source_root, "status", "--porcelain"):
        raise ValueError("libnoise checkout has uncommitted modifications")
    required = (source_root / "CMakeLists.txt", source_root / "src/noise.h", source_root / "README.md")
    if not all(path.is_file() for path in required):
        raise ValueError("libnoise checkout is missing its CMake/source/provenance surface")
    if "OrcaSlicer" not in (source_root / "README.md").read_text(errors="replace"):
        raise ValueError("libnoise checkout README does not retain OrcaSlicer provenance")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source-root",
        type=Path,
        help="initialized official submodule checkout to validate in addition to the Gitlink",
    )
    parser.add_argument(
        "--libnoise-source-root",
        type=Path,
        help="initialized Orca-pinned libnoise checkout to validate in addition to its Gitlink",
    )
    args = parser.parse_args()
    try:
        require_gitlink(MODULE_NAME, MODULE_PATH, OFFICIAL_URL, OFFICIAL_SHA)
        require_gitlink(LIBNOISE_MODULE_NAME, LIBNOISE_MODULE_PATH, LIBNOISE_URL, LIBNOISE_SHA)
        if args.source_root is not None:
            require_checkout(args.source_root.resolve())
        if args.libnoise_source_root is not None:
            require_libnoise_checkout(args.libnoise_source_root.resolve())
    except ValueError as error:
        print(f"official Orca snapshot validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    targets = []
    if args.source_root:
        targets.append(str(args.source_root))
    if args.libnoise_source_root:
        targets.append(str(args.libnoise_source_root))
    target = f" and checkout(s) {', '.join(targets)}" if targets else ""
    print(f"validated official Orca source snapshot {OFFICIAL_SHA} + libnoise {LIBNOISE_SHA}{target}")


if __name__ == "__main__":
    main()
