#!/usr/bin/env python3
"""Measure hook-neutralized desktop/native G3 output without a gate."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
from zipfile import BadZipFile, ZipFile
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "ci"))
sys.path.insert(0, str(REPO_ROOT / "parity"))

from compare_g3_artifacts import (  # noqa: E402
    REFERENCE_ARTIFACT,
    download_artifact,
    github_get_json,
    safe_extract,
    select_reference_artifact,
    validate_artifact_digest,
    validate_reference_manifest,
    wait_for_reference_run,
)
from compare_gcode import parse_metrics, travel_diagnostic  # noqa: E402


FIXTURES = ("cube_20mm", "overhang_support", "thin_wall_frame", "travel_obstacle")
NEUTRAL_REFERENCE_ROOT = "orca-reference-hook-neutralized"
NO_HOOK_MARKERS = (
    "; SKIPPABLE_START",
    "; SKIPTYPE: timelapse",
    "; SKIPTYPE: head_wrap_detect",
    "M1004 S5 P1",
    "G39.3 S1",
)
EMPTY_HOOK_ASSIGNMENTS = ("; time_lapse_gcode =", "; wrapping_detection_gcode =")


def _fail(message: str) -> None:
    raise ValueError(message)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_neutral_profile_bundle(reference_dir: Path) -> dict[str, Any]:
    profile_path = reference_dir / "profiles" / "hook-neutralized" / "machine-hooks-neutralized.json"
    manifest_path = reference_dir / "profiles" / "hook-neutralized" / "machine-hooks-neutralized-manifest.json"
    source_path = reference_dir / "profiles" / "machine.json"
    for path in (profile_path, manifest_path, source_path):
        if not path.is_file() or path.stat().st_size == 0:
            _fail(f"hook-neutralized desktop profile bundle is missing {path.name}")
    try:
        neutral = json.loads(profile_path.read_bytes())
        source = json.loads(source_path.read_bytes())
        manifest = json.loads(manifest_path.read_bytes())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"hook-neutralized desktop profile bundle is invalid JSON: {error}")
    if manifest.get("scope") != "hook-neutralized-g3-counterfactual-only" or manifest.get("qualified") is not False:
        _fail("desktop profile manifest has the wrong scope or qualification state")
    if manifest.get("source_profile_sha256") != _sha256(source_path):
        _fail("desktop profile manifest does not bind the pinned resolved machine profile")
    if manifest.get("neutral_profile_sha256") != _sha256(profile_path):
        _fail("desktop profile manifest does not bind the hook-neutralized machine profile")
    if manifest.get("cleared_keys") != ["time_lapse_gcode", "wrapping_detection_gcode"]:
        _fail("desktop profile manifest does not name exactly the two cleared hook keys")
    if not isinstance(source, dict) or not isinstance(neutral, dict):
        _fail("desktop machine profiles must be JSON objects")
    for key in ("time_lapse_gcode", "wrapping_detection_gcode"):
        if neutral.get(key) != "":
            _fail(f"hook-neutralized desktop machine profile has non-empty {key}")
    source_without_hooks = {key: value for key, value in source.items()
                            if key not in {"time_lapse_gcode", "wrapping_detection_gcode"}}
    neutral_without_hooks = {key: value for key, value in neutral.items()
                             if key not in {"time_lapse_gcode", "wrapping_detection_gcode"}}
    if source_without_hooks != neutral_without_hooks:
        _fail("counterfactual desktop profile changed a setting other than the two hook keys")
    return neutral


def _desktop_input(path: Path, neutral_profile: dict[str, Any] | None = None) -> tuple[str, str]:
    try:
        with ZipFile(path) as archive:
            settings = json.loads(archive.read("Metadata/project_settings.config"))
            gcode_names = sorted(
                name for name in archive.namelist()
                if name.startswith("Metadata/plate_") and name.endswith(".gcode")
            )
            if len(gcode_names) != 1:
                _fail(f"{path.name} must contain exactly one plate G-code")
            gcode = archive.read(gcode_names[0]).decode("utf-8", errors="replace")
    except (BadZipFile, KeyError, UnicodeDecodeError, json.JSONDecodeError) as error:
        _fail(f"cannot inspect hook-neutralized desktop archive {path.name}: {error}")
    for key in ("time_lapse_gcode", "wrapping_detection_gcode"):
        if settings.get(key) != "":
            _fail(f"desktop counterfactual does not have empty {key}")
        if neutral_profile is not None and settings.get(key) != neutral_profile.get(key):
            _fail(f"desktop slice does not match the neutralized profile value for {key}")
    for marker in NO_HOOK_MARKERS:
        if marker in gcode:
            _fail(f"desktop counterfactual still emits motion-hook marker {marker!r}")
    return gcode_names[0], gcode


def _native_input(path: Path) -> str:
    if not path.is_file() or path.stat().st_size == 0:
        _fail(f"native counterfactual is missing: {path.name}")
    gcode = path.read_text(encoding="utf-8", errors="replace")
    for key in EMPTY_HOOK_ASSIGNMENTS:
        if not re.search(r"^" + re.escape(key) + r"\s*$", gcode, re.MULTILINE):
            _fail(f"native counterfactual does not attest empty hook setting {key}")
    for marker in NO_HOOK_MARKERS:
        if marker in gcode:
            _fail(f"native counterfactual still emits motion-hook marker {marker!r}")
    return gcode


def compare_neutralized_fixtures(
    native_dir: Path,
    reference_dir: Path,
    report_dir: Path,
    reference_sha256: str,
    reference_run_id: int,
) -> dict[str, Any]:
    report_dir.mkdir(parents=True, exist_ok=True)
    neutral_profile = validate_neutral_profile_bundle(reference_dir)
    fixtures: list[dict[str, Any]] = []
    for fixture in FIXTURES:
        desktop_path = reference_dir / NEUTRAL_REFERENCE_ROOT / fixture / f"{fixture}.gcode.3mf"
        native_path = native_dir / f"{fixture}.gcode"
        _, desktop_gcode = _desktop_input(desktop_path, neutral_profile)
        native_gcode = _native_input(native_path)
        desktop = parse_metrics(desktop_path, None)
        native = parse_metrics(native_path, None)
        desktop_travel = sum(v.get("distance_mm", 0.0) for v in desktop.travel_by_layer_feedrate.values())
        native_travel = sum(v.get("distance_mm", 0.0) for v in native.travel_by_layer_feedrate.values())
        desktop_travel_seconds = sum(v.get("nominal_seconds", 0.0) for v in desktop.travel_by_layer_feedrate.values())
        native_travel_seconds = sum(v.get("nominal_seconds", 0.0) for v in native.travel_by_layer_feedrate.values())
        fixtures.append({
            "fixture": fixture,
            "hooks_disabled_verified": True,
            "desktop_gcode_sha256": hashlib.sha256(desktop_gcode.encode("utf-8")).hexdigest(),
            "native_gcode_sha256": hashlib.sha256(native_gcode.encode("utf-8")).hexdigest(),
            "desktop": {
                "travel_moves": desktop.travel_moves,
                "travel_path_mm": desktop_travel,
                "nominal_travel_seconds": desktop_travel_seconds,
                "linear_xyz_path_mm": desktop.linear_xyz_path_mm,
                "nominal_linear_extrusion_seconds": desktop.nominal_linear_extrusion_seconds,
            },
            "native": {
                "travel_moves": native.travel_moves,
                "travel_path_mm": native_travel,
                "nominal_travel_seconds": native_travel_seconds,
                "linear_xyz_path_mm": native.linear_xyz_path_mm,
                "nominal_linear_extrusion_seconds": native.nominal_linear_extrusion_seconds,
            },
            "travel_delta_diagnostic": travel_diagnostic(desktop, native),
        })
    result = {
        "scope": "hook-neutralized-counterfactual-diagnostic-only",
        "qualification": "not-a-production-parity-result",
        "g3_gate_modified": False,
        "thresholds_applied": False,
        "desktop_reference_run_id": reference_run_id,
        "desktop_artifact": REFERENCE_ARTIFACT,
        "desktop_artifact_sha256": reference_sha256,
        "fixtures": fixtures,
    }
    (report_dir / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--merge-sha", required=True)
    parser.add_argument("--token", required=True)
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    try:
        run = wait_for_reference_run(args.repository, args.head_sha, args.token)
        run_id = run.get("id")
        if not isinstance(run_id, int):
            _fail("matching desktop reference run has no numeric ID")
        listing = github_get_json(
            f"https://api.github.com/repos/{args.repository}/actions/runs/{run_id}/artifacts?per_page=100",
            args.token,
        )
        artifact = select_reference_artifact(listing.get("artifacts", []))
        archive_bytes = download_artifact(artifact["archive_download_url"], args.token)
        digest = validate_artifact_digest(artifact, archive_bytes)
        with tempfile.TemporaryDirectory(prefix="alloy-hook-neutral-reference-") as temporary:
            extracted = Path(temporary) / "reference"
            safe_extract(archive_bytes, extracted)
            validate_reference_manifest(extracted, args.head_sha, args.base_sha, args.merge_sha)
            report = compare_neutralized_fixtures(
                args.native_dir, extracted, args.report_dir, digest, run_id
            )
        print(json.dumps({"scope": report["scope"], "fixtures": len(report["fixtures"])}))
        return 0
    except (RuntimeError, OSError, KeyError, ValueError, json.JSONDecodeError) as error:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "error.txt").write_text(f"{error}\n", encoding="utf-8")
        print(f"hook-neutralized diagnostic unavailable: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
