#!/usr/bin/env python3
"""Validate the retained, exact-ref G2 source-file inventory."""

from __future__ import annotations

import csv
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
JSON_PATH = ROOT / "docs/G2_SOURCE_FILE_MANIFEST.json"
CSV_PATH = ROOT / "docs/G2_SOURCE_FILE_MANIFEST.csv"
SHA1 = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_REFS = {
    "mobile_commit": "d996a9cadb65b354997f2d5d8734b46bb9ea4efd",
    "official_main_commit": "18b70844d1dfaeede60a834f5fec41dd81dfe0e2",
    "best_history_candidate_commit": "83738cacd84c616b62fc3cc15be8b65665569be8",
}
EXPECTED_UNMATCHED_SAMPLES = {
    "Config.cpp",
    "Feature/FuzzySkin/FuzzySkin.cpp",
    "PrintConfig.cpp",
    "SLA/IndexedMesh.cpp",
    "SVG.hpp",
    "Utils.hpp",
}


def validate() -> dict[str, int]:
    manifest = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1:
        raise ValueError("unsupported manifest schema")
    refs = manifest.get("source_refs", {})
    for key, value in EXPECTED_REFS.items():
        if refs.get(key) != value:
            raise ValueError(f"unexpected pinned source ref: {key}")

    rows = manifest.get("files")
    if not isinstance(rows, list) or len(rows) != 479:
        raise ValueError("manifest must contain exactly 479 mapped files")
    paths = [row.get("path") for row in rows]
    if paths != sorted(paths) or len(set(paths)) != len(paths):
        raise ValueError("manifest paths must be unique and sorted")

    exact = different = missing = main_exact = sampled = sampled_found = 0
    unmatched_samples = set()
    for row in rows:
        for key in ("mobile_blob_sha", "official_main_blob_sha"):
            if not SHA1.fullmatch(str(row.get(key, ""))):
                raise ValueError(f"{row.get('path')}: invalid {key}")
        candidate_sha = row.get("candidate_blob_sha")
        state = row.get("candidate_match_state")
        if candidate_sha is None:
            if state != "missing_at_candidate":
                raise ValueError(f"{row['path']}: missing candidate blob has wrong state")
            missing += 1
        elif not SHA1.fullmatch(str(candidate_sha)):
            raise ValueError(f"{row['path']}: invalid candidate blob SHA")
        elif candidate_sha == row["mobile_blob_sha"]:
            if state != "exact_blob_match":
                raise ValueError(f"{row['path']}: exact blob has wrong state")
            exact += 1
        else:
            if state != "different_blob":
                raise ValueError(f"{row['path']}: differing blob has wrong state")
            different += 1
        main_exact += row["official_main_blob_sha"] == row["mobile_blob_sha"]
        if row.get("reviewed_origin_port_classification") != "UNREVIEWED":
            raise ValueError(f"{row['path']}: classification must remain unreviewed")
        if row.get("sampled_for_history"):
            sampled += 1
            history_state = row.get("sampled_history_status")
            if row.get("sampled_history_interval") is not None:
                raise ValueError("expired G2 evidence must not imply retained interval bounds")
            if history_state == "exact_interval_found_details_unavailable":
                sampled_found += 1
            elif history_state == "no_exact_interval":
                unmatched_samples.add(row["path"])
            else:
                raise ValueError(f"{row['path']}: unexpected sampled history state")
        elif row.get("sampled_history_status") != "not_sampled":
            raise ValueError(f"{row['path']}: unsampled path has sampled history status")

    summary = manifest.get("audit_summary", {})
    if (exact, different, missing, main_exact, sampled, sampled_found) != (314, 157, 8, 42, 72, 66):
        raise ValueError("per-file counts do not match retained G2 run")
    if unmatched_samples != EXPECTED_UNMATCHED_SAMPLES:
        raise ValueError("sampled unmatched paths do not match retained G2 report")
    for key, actual in {
        "mapped_files": len(rows),
        "candidate_exact_files": exact,
        "candidate_different_files": different,
        "candidate_missing_paths": missing,
        "official_main_exact_files": main_exact,
        "sampled_files": sampled,
        "sampled_exact_interval_found": sampled_found,
    }.items():
        if summary.get(key) != actual:
            raise ValueError(f"audit summary count mismatch: {key}")

    if any(not line for line in CSV_PATH.read_text(encoding="utf-8").splitlines()):
        raise ValueError("CSV must not contain blank records")
    with CSV_PATH.open(newline="", encoding="utf-8") as stream:
        csv_rows = list(csv.DictReader(stream))
    if len(csv_rows) != len(rows):
        raise ValueError("CSV and JSON row counts differ")
    for json_row, csv_row in zip(rows, csv_rows):
        for key in (
            "path", "mobile_blob_sha", "official_main_blob_sha", "candidate_blob_sha",
            "candidate_match_state", "sampled_for_history", "sampled_history_status",
            "reviewed_origin_port_classification",
        ):
            expected = json_row.get(key)
            if key == "sampled_for_history":
                expected = str(bool(expected)).lower()
            elif expected is None:
                expected = ""
            if csv_row.get(key) != str(expected):
                raise ValueError(f"CSV/JSON mismatch for {json_row['path']} field {key}")
        if csv_row.get("sampled_history_interval") != "":
            raise ValueError("CSV must not invent unavailable sample interval bounds")

    return {
        "mapped_files": len(rows), "candidate_exact_files": exact,
        "candidate_different_files": different, "candidate_missing_paths": missing,
        "official_main_exact_files": main_exact, "sampled_files": sampled,
        "sampled_exact_interval_found": sampled_found,
    }


if __name__ == "__main__":
    try:
        print(json.dumps(validate(), sort_keys=True))
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"G2 source-file manifest validation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
