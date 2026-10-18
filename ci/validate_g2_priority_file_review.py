#!/usr/bin/env python3
"""Validate bounded G2 review evidence against the pinned 479-file manifest."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs/G2_SOURCE_FILE_MANIFEST.json"
EVIDENCE = ROOT / "docs/G2_PRIORITY_FILE_DIFF_EVIDENCE.json"
EXPECTED = {
    "PrintConfig.cpp",
    "Config.cpp",
    "Preset.cpp",
    "PresetBundle.cpp",
    "GCode.cpp",
    "GCode/GCodeProcessor.cpp",
}


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def validate(manifest: dict, evidence: dict) -> None:
    refs = manifest["source_refs"]
    evidence_refs = evidence["source_refs"]
    assert evidence["evidence_only"] is True
    assert evidence["g2_status"] == "FAIL"
    assert evidence_refs["mobile_commit"] == refs["mobile_commit"]
    assert evidence_refs["candidate_commit"] == refs["best_history_candidate_commit"]
    assert evidence_refs["mobile_root"] == "app/src/main/jni/libslic3r/"
    assert evidence_refs["candidate_root"] == "src/libslic3r/"

    manifest_rows = {row["path"]: row for row in manifest["files"]}
    evidence_rows = evidence["files"]
    assert len(evidence_rows) == len(EXPECTED)
    assert {row["path"] for row in evidence_rows} == EXPECTED
    assert len({row["path"] for row in evidence_rows}) == len(evidence_rows)

    for row in evidence_rows:
        mapped = manifest_rows[row["path"]]
        assert row["mobile_blob_sha"] == mapped["mobile_blob_sha"]
        assert row["candidate_blob_sha"] == mapped["candidate_blob_sha"]
        assert row["origin_port_classification"] == "UNREVIEWED"
        assert row["mobile_line_count"] > 0
        assert row["candidate_line_count"] > 0
        assert row["mobile_line_count"] != row["candidate_line_count"]
        assert row["difference_category"].strip()
        assert row["evidence"]
        for item in row["evidence"]:
            assert item["source"] in {"mobile", "candidate"}
            assert item["start_line"] >= 1
            assert item["end_line"] >= item["start_line"]
            assert item["observation"].strip()


def main() -> int:
    validate(load(MANIFEST), load(EVIDENCE))
    print(json.dumps({"reviewed_files": len(EXPECTED), "origin_port_classification": "UNREVIEWED", "g2_status": "FAIL"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
