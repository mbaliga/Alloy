#!/usr/bin/env python3
"""Record and verify the provenance of native G3 inputs."""

from __future__ import annotations

import hashlib
import argparse
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any


MANIFEST_NAME = "g3-evidence-manifest.json"
SCHEMA_VERSION = 1
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(
    evidence_dir: Path,
    *,
    head_sha: str,
    base_sha: str,
    merge_sha: str,
    run_id: str,
    run_attempt: str,
) -> dict[str, Any]:
    if not evidence_dir.is_dir():
        raise ValueError("native G3 evidence directory is missing")
    files = {
        path.relative_to(evidence_dir).as_posix(): _sha256(path)
        for path in sorted(evidence_dir.rglob("*"))
        if path.is_file() and path.name != MANIFEST_NAME
    }
    if not files:
        raise ValueError("native G3 evidence directory is empty")
    for name, value in (("head SHA", head_sha), ("base SHA", base_sha), ("merge SHA", merge_sha)):
        if not COMMIT_SHA_RE.fullmatch(value):
            raise ValueError(f"invalid PR {name}")
    if not run_id.isdecimal() or not run_attempt.isdecimal():
        raise ValueError("invalid GitHub Actions run identity")
    return {
        "schema_version": SCHEMA_VERSION,
        "scope": "native-g3-evidence-inputs",
        "qualified_for_print": False,
        "pr_head_sha": head_sha,
        "pr_base_sha": base_sha,
        "pr_merge_sha": merge_sha,
        "workflow_run_id": run_id,
        "workflow_run_attempt": run_attempt,
        "files_sha256": files,
    }


def write_manifest(evidence_dir: Path, **identity: str) -> dict[str, Any]:
    manifest = build_manifest(evidence_dir, **identity)
    (evidence_dir / MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def validate_manifest(
    evidence_dir: Path,
    *,
    head_sha: str,
    base_sha: str,
    merge_sha: str,
) -> dict[str, Any]:
    path = evidence_dir / MANIFEST_NAME
    if not path.is_file():
        raise ValueError("native G3 evidence has no run-context manifest")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("native G3 evidence manifest is unreadable") from error
    expected = {
        "schema_version": SCHEMA_VERSION,
        "scope": "native-g3-evidence-inputs",
        "qualified_for_print": False,
        "pr_head_sha": head_sha,
        "pr_base_sha": base_sha,
        "pr_merge_sha": merge_sha,
    }
    if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("native G3 evidence manifest identity mismatch")
    if not str(manifest.get("workflow_run_id", "")).isdecimal() or not str(
        manifest.get("workflow_run_attempt", "")
    ).isdecimal():
        raise ValueError("native G3 evidence manifest has invalid workflow run identity")
    files = manifest.get("files_sha256")
    if not isinstance(files, dict) or not files:
        raise ValueError("native G3 evidence manifest has no file digests")
    actual_names = {
        item.relative_to(evidence_dir).as_posix()
        for item in evidence_dir.rglob("*")
        if item.is_file() and item.name != MANIFEST_NAME
    }
    for name, expected_digest in files.items():
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or not SHA256_RE.fullmatch(str(expected_digest)):
            raise ValueError("native G3 evidence manifest contains an unsafe path or digest")
    if set(files) != actual_names:
        raise ValueError("native G3 evidence file set differs from its manifest")
    for name, expected_digest in files.items():
        relative = PurePosixPath(name)
        candidate = evidence_dir.joinpath(*relative.parts)
        if not candidate.is_file() or _sha256(candidate) != expected_digest:
            raise ValueError(f"native G3 evidence digest mismatch: {name}")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--merge-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    args = parser.parse_args()
    manifest = write_manifest(
        args.evidence_dir,
        head_sha=args.head_sha,
        base_sha=args.base_sha,
        merge_sha=args.merge_sha,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
    )
    print(
        f"recorded {len(manifest['files_sha256'])} native G3 evidence digests "
        f"for run {manifest['workflow_run_id']}/{manifest['workflow_run_attempt']}"
    )


if __name__ == "__main__":
    main()
