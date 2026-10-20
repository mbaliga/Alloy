#!/usr/bin/env python3
"""Emit a non-gating comparison of the unqualified Bambu template probe.

This diagnostic compares the four captured native renders (with pinned Bambu
hooks installed by instrumentation) against a successful desktop reference
from the exact PR head. It does not run or replace the strict G3 gate.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "parity"))

from ci.compare_g3_artifacts import (  # noqa: E402
    FIXTURES,
    REFERENCE_ARTIFACT,
    download_artifact,
    github_get_json,
    safe_extract,
    select_reference_artifact,
    select_reference_run,
    validate_artifact_digest,
    validate_reference_manifest,
    wait_for_reference_run,
)
from ci.validate_machine_template_probe_output import (  # noqa: E402
    validate as validate_probe,
    validate_end_only,
    validate_counterfactual_bbl,
)
from compare_gcode import parse_metrics, relative_diff  # noqa: E402
from verify_g3_profile import merged_desktop_settings, read_native_settings, verify  # noqa: E402


SCOPE = "unqualified-template-render-vs-desktop-reference-diagnostic"


def _metric_delta(reference: float | int | None, candidate: float | int | None) -> dict[str, Any]:
    if reference is None or candidate is None:
        return {"desktop": reference, "template_probe": candidate, "delta": None, "relative_delta_percent": None}
    relative = relative_diff(float(reference), float(candidate))
    return {
        "desktop": reference,
        "template_probe": candidate,
        "delta": candidate - reference,
        "relative_delta_percent": relative * 100 if relative != float("inf") else None,
    }


def _feature_deltas(desktop: dict[str, float], probe: dict[str, float]) -> dict[str, dict[str, Any]]:
    return {
        role: _metric_delta(desktop.get(role, 0.0), probe.get(role, 0.0))
        for role in sorted(set(desktop) | set(probe))
    }


def fixture_diagnostic(desktop: Any, probe: Any, profile_identity: dict[str, Any]) -> dict[str, Any]:
    """Return measurements only; deliberately contains no pass/fail decision."""
    desktop_nominal = (
        desktop.nominal_linear_extrusion_seconds + desktop.nominal_linear_travel_seconds
        + desktop.nominal_arc_extrusion_seconds + desktop.nominal_arc_travel_seconds
    )
    probe_nominal = (
        probe.nominal_linear_extrusion_seconds + probe.nominal_linear_travel_seconds
        + probe.nominal_arc_extrusion_seconds + probe.nominal_arc_travel_seconds
    )
    desktop_path = desktop.linear_xyz_path_mm + desktop.arc_path_mm
    probe_path = probe.linear_xyz_path_mm + probe.arc_path_mm
    return {
        "comparison_kind": "measurement-only",
        "profile_identity": profile_identity,
        "metrics": {
            "print_time_seconds": _metric_delta(desktop.print_time_seconds, probe.print_time_seconds),
            "filament_mm": _metric_delta(desktop.filament_mm, probe.filament_mm),
            "filament_g": _metric_delta(desktop.filament_g, probe.filament_g),
            "layer_count": _metric_delta(desktop.layer_count, probe.layer_count),
            "extrusion_moves": _metric_delta(desktop.extrusion_moves, probe.extrusion_moves),
            "travel_moves": _metric_delta(desktop.travel_moves, probe.travel_moves),
            "positive_e_mm": _metric_delta(desktop.positive_e_mm, probe.positive_e_mm),
            "total_xyz_path_mm": _metric_delta(desktop_path, probe_path),
            "nominal_motion_seconds": _metric_delta(desktop_nominal, probe_nominal),
        },
        "deposited_feature_path_mm": _feature_deltas(
            desktop.feature_extrusion_path_mm, probe.feature_extrusion_path_mm
        ),
        "feature_positive_e_mm": _feature_deltas(
            desktop.feature_positive_e_mm, probe.feature_positive_e_mm
        ),
        "extrusion_accounting": {
            "desktop": desktop.extrusion_accounting,
            "template_probe": probe.extrusion_accounting,
        },
    }


def _counterfactual_metric_pair(default_false: Any, counterfactual_bbl: Any) -> dict[str, Any]:
    return {
        "default_false": default_false,
        "counterfactual_bbl": counterfactual_bbl,
        "delta_bbl_minus_default": (
            None if default_false is None or counterfactual_bbl is None
            else counterfactual_bbl - default_false
        ),
    }


def counterfactual_gcode_structure(text: str) -> dict[str, Any]:
    """Capture hook placement and pre-first-layer XYZE without interpreting firmware macros."""
    lines = text.splitlines()
    marker_re = re.compile(r"^\s*;\s*(LAYER_CHANGE|CHANGE_LAYER)\b", re.I)
    markers = [index for index, line in enumerate(lines) if marker_re.match(line)]
    first_layer = markers[0] if markers else len(lines)

    relative_xyz = False
    relative_e = True
    position = [0.0, 0.0, 0.0]
    e_position = 0.0
    xyz_known = False
    xyz_path = 0.0
    travel_path = 0.0
    positive_e = 0.0
    linear_moves = 0
    travel_moves = 0
    word_re = re.compile(r"([XYZEF])\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)", re.I)
    command_re = re.compile(r"^\s*(G0|G1|G28|G92)\b(.*)$", re.I)
    for line in lines[:first_layer]:
        command = line.split(";", 1)[0].strip()
        upper = command.upper()
        if upper.startswith("G90"):
            relative_xyz = False
            continue
        if upper.startswith("G91"):
            relative_xyz = True
            continue
        if upper.startswith("M82"):
            relative_e = False
            continue
        if upper.startswith("M83"):
            relative_e = True
            continue
        match = command_re.match(command)
        if not match:
            continue
        opcode, args = match.group(1).upper(), match.group(2)
        words = {name.upper(): float(value) for name, value in word_re.findall(args)}
        if opcode == "G28":
            axes = [axis for axis in "XYZ" if re.search(rf"\b{axis}\b", args, re.I)]
            if not axes:
                axes = list("XYZ")
            for axis in axes:
                position["XYZ".index(axis)] = 0.0
            xyz_known = len(axes) == 3
            continue
        if opcode == "G92":
            for axis in "XYZ":
                if axis in words:
                    position["XYZ".index(axis)] = words[axis]
            if "E" in words:
                e_position = words["E"]
            if all(axis in words for axis in "XYZ"):
                xyz_known = True
            continue
        linear_moves += 1
        previous = list(position)
        axis_present = any(axis in words for axis in "XYZ")
        for axis in "XYZ":
            if axis in words:
                index = "XYZ".index(axis)
                position[index] = position[index] + words[axis] if relative_xyz else words[axis]
        distance = math.dist(previous, position) if xyz_known else 0.0
        if axis_present and xyz_known:
            xyz_path += distance
        e_delta = 0.0
        if "E" in words:
            e_delta = words["E"] if relative_e else words["E"] - e_position
            e_position = e_position + words["E"] if relative_e else words["E"]
            if e_delta > 0:
                positive_e += e_delta
        if axis_present and e_delta <= 0 and xyz_known:
            travel_path += distance
            travel_moves += 1

    layer_records = []
    for layer_index, start in enumerate(markers):
        end = markers[layer_index + 1] if layer_index + 1 < len(markers) else len(lines)
        segment = lines[start:end]
        timelapse_lines = [start + offset for offset, line in enumerate(segment)
                           if "; SKIPPABLE_START" in line]
        notification_lines = [start + offset for offset, line in enumerate(segment)
                             if re.match(r"\s*M991\b", line, re.I)]
        extrusion_lines = []
        for offset, line in enumerate(segment):
            command = line.split(";", 1)[0]
            if not re.match(r"\s*G[01]\b", command, re.I):
                continue
            if any(float(value) > 0 for value in re.findall(
                    r"\bE\s*([-+]?(?:\d+(?:\.\d*)?|\.\d+))", command, re.I)):
                extrusion_lines.append(start + offset)
        first_hook = timelapse_lines[0] if timelapse_lines else None
        first_extrusion = extrusion_lines[0] if extrusion_lines else None
        layer_records.append({
            "layer_index": layer_index,
            "marker_line_1based": start + 1,
            "marker": lines[start].strip(),
            "timelapse_start_line_1based": first_hook + 1 if first_hook is not None else None,
            "timelapse_block_count": len(timelapse_lines),
            "layer_notification_line_1based": notification_lines[0] + 1 if notification_lines else None,
            "first_positive_extrusion_line_1based": first_extrusion + 1 if first_extrusion is not None else None,
            "timelapse_relative_to_first_extrusion": (
                "absent" if first_hook is None else
                "before" if first_extrusion is not None and first_hook < first_extrusion else
                "after_or_no_extrusion"
            ),
        })
    return {
        "measurement_only": True,
        "firmware_conditional_execution_interpreted": False,
        "first_layer_marker_line_1based": first_layer + 1 if markers else None,
        "before_first_layer": {
            "linear_move_count": linear_moves,
            "travel_move_count": travel_moves,
            "xyz_path_mm": xyz_path,
            "travel_only_xyz_path_mm": travel_path,
            "positive_e_mm": positive_e,
            "position_known": xyz_known,
        },
        "per_layer_hook_location": layer_records,
    }


def diagnostic_report(
    *, head_sha: str, base_sha: str, merge_sha: str,
    reference_run_id: int | None = None, artifact_id: int | None = None,
    artifact_sha256: str | None = None, fixtures: list[dict[str, Any]] | None = None,
    unavailable_reason: str | None = None, template_sha256: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Keep every report explicitly outside production qualification and G3."""
    report: dict[str, Any] = {
        "schema_version": 1,
        "diagnostic_only": True,
        "non_gating": True,
        "qualification": "PENDING",
        "direct_send_qualification": "PENDING",
        "strict_g3_gate": "separate; not modified by this diagnostic",
        "scope": SCOPE,
        "source_commit": "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4",
        "pr_head_sha": head_sha,
        "pr_base_sha": base_sha,
        "pr_merge_sha": merge_sha,
        "reference_artifact_name": REFERENCE_ARTIFACT,
        "reference_run_id": reference_run_id,
        "reference_artifact_id": artifact_id,
        "reference_artifact_sha256": artifact_sha256,
        "template_sha256": template_sha256,
        "diagnostic_status": "unavailable" if unavailable_reason else "complete",
    }
    if unavailable_reason:
        report["unavailable_reason"] = unavailable_reason
    else:
        report["fixtures"] = fixtures or []
    return report


def compare_probe_to_reference(
    probe_dir: Path,
    reference_dir: Path,
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    manifest = validate_probe(probe_dir)
    end_only_manifest = validate_end_only(probe_dir / "end-only")
    bbl_manifest = validate_counterfactual_bbl(probe_dir / "counterfactual-bbl")
    end_only_fixtures = {
        item["fixture"]: item for item in end_only_manifest["fixtures"]
    }
    bbl_fixtures = {item["fixture"]: item for item in bbl_manifest["fixtures"]}
    default_fixtures = {item["fixture"]: item for item in manifest["fixtures"]}
    reports: list[dict[str, Any]] = []
    for fixture in FIXTURES:
        desktop_path = reference_dir / "orca-reference" / fixture / f"{fixture}.gcode.3mf"
        probe_path = probe_dir / f"{fixture}.gcode"
        config_path = probe_dir / f"{fixture}.config.ini"
        end_only_path = probe_dir / "end-only" / f"{fixture}.gcode"
        end_only_config_path = probe_dir / "end-only" / f"{fixture}.config.ini"
        bbl_path = probe_dir / "counterfactual-bbl" / f"{fixture}.gcode"
        bbl_config_path = probe_dir / "counterfactual-bbl" / f"{fixture}.config.ini"
        for path in (desktop_path, probe_path, config_path, end_only_path, end_only_config_path,
                     bbl_path, bbl_config_path):
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f"template diagnostic input missing or empty: {path.name}")
        desktop = parse_metrics(desktop_path, None)
        rendered = parse_metrics(probe_path, None)
        end_only_rendered = parse_metrics(end_only_path, None)
        counterfactual_bbl_rendered = parse_metrics(bbl_path, None)
        desktop_settings = merged_desktop_settings(desktop_path)
        profile_identity = verify(desktop_settings, read_native_settings(config_path))
        end_only_profile_identity = verify(
            desktop_settings, read_native_settings(end_only_config_path)
        )
        if end_only_profile_identity != profile_identity:
            raise RuntimeError(f"end-only render profile identity differs for {fixture}")
        counterfactual_bbl_profile_identity = verify(
            desktop_settings, read_native_settings(bbl_config_path)
        )
        if counterfactual_bbl_profile_identity != profile_identity:
            raise RuntimeError(f"counterfactual BBL render profile identity differs for {fixture}")
        default_config_sha = default_fixtures[fixture]["config_sha256"]
        bbl_config_sha = bbl_fixtures[fixture]["config_sha256"]
        if bbl_config_sha != default_config_sha:
            raise RuntimeError(f"counterfactual BBL render changed non-mode config input for {fixture}")
        default_total_xyz = rendered.linear_xyz_path_mm + rendered.arc_path_mm
        bbl_total_xyz = counterfactual_bbl_rendered.linear_xyz_path_mm + counterfactual_bbl_rendered.arc_path_mm
        default_nominal = (rendered.nominal_linear_extrusion_seconds + rendered.nominal_linear_travel_seconds
                           + rendered.nominal_arc_extrusion_seconds + rendered.nominal_arc_travel_seconds)
        bbl_nominal = (counterfactual_bbl_rendered.nominal_linear_extrusion_seconds
                       + counterfactual_bbl_rendered.nominal_linear_travel_seconds
                       + counterfactual_bbl_rendered.nominal_arc_extrusion_seconds
                       + counterfactual_bbl_rendered.nominal_arc_travel_seconds)
        reports.append({
            "fixture": fixture,
            "probe_gcode_sha256": next(
                item["sha256"] for item in manifest["fixtures"] if item["fixture"] == fixture
            ),
            "measurements": fixture_diagnostic(desktop, rendered, profile_identity),
            "end_only_render_vs_desktop_full_hook_reference": {
                "comparison_kind": "measurement-only",
                "render_scope": "machine-end-template-only-render-diagnostic",
                "template_sha256": end_only_manifest["template_sha256"],
                "probe_gcode_sha256": end_only_fixtures[fixture]["sha256"],
                "profile_identity": end_only_profile_identity,
                "measurements": fixture_diagnostic(
                    desktop, end_only_rendered, end_only_profile_identity
                ),
                "interpretation_limit": (
                    "The native render installs the pinned machine-end template while retaining "
                    "the native baseline for other hooks; the desktop reference contains its full "
                    "hook set. This comparison does not isolate machine-end contribution or establish parity."
                ),
            },
            "counterfactual_bbl_mode_vs_default_false_mode": {
                "comparison_kind": "measurement-only-counterfactual",
                "render_scope": "same-fixture-and-templates-only-internal-bbl-identity-toggle",
                "counterfactual": True,
                "default_bbl_printer_mode": False,
                "counterfactual_bbl_printer_mode": True,
                "production_runtime_changed": False,
                "non_gating": True,
                "qualified": False,
                "profile_identity": counterfactual_bbl_profile_identity,
                "template_sha256": bbl_manifest["template_sha256"],
                "default_false_gcode_sha256": next(
                    item["sha256"] for item in manifest["fixtures"] if item["fixture"] == fixture
                ),
                "counterfactual_bbl_gcode_sha256": bbl_fixtures[fixture]["sha256"],
                "identical_config_sha256": default_config_sha,
                "metrics": {
                    "print_time_seconds": _counterfactual_metric_pair(
                        rendered.print_time_seconds, counterfactual_bbl_rendered.print_time_seconds
                    ),
                    "filament_mm": _counterfactual_metric_pair(
                        rendered.filament_mm, counterfactual_bbl_rendered.filament_mm
                    ),
                    "filament_g": _counterfactual_metric_pair(
                        rendered.filament_g, counterfactual_bbl_rendered.filament_g
                    ),
                    "layer_count": _counterfactual_metric_pair(
                        rendered.layer_count, counterfactual_bbl_rendered.layer_count
                    ),
                    "extrusion_moves": _counterfactual_metric_pair(
                        rendered.extrusion_moves, counterfactual_bbl_rendered.extrusion_moves
                    ),
                    "travel_moves": _counterfactual_metric_pair(
                        rendered.travel_moves, counterfactual_bbl_rendered.travel_moves
                    ),
                    "positive_e_mm": _counterfactual_metric_pair(
                        rendered.positive_e_mm, counterfactual_bbl_rendered.positive_e_mm
                    ),
                    "total_xyz_path_mm": _counterfactual_metric_pair(default_total_xyz, bbl_total_xyz),
                    "nominal_motion_seconds": _counterfactual_metric_pair(default_nominal, bbl_nominal),
                },
                "default_false_structure": counterfactual_gcode_structure(probe_path.read_text(encoding="utf-8")),
                "counterfactual_bbl_structure": counterfactual_gcode_structure(bbl_path.read_text(encoding="utf-8")),
                "interpretation_limit": (
                    "This counterfactual toggles the internal BBL-printer identity only for instrumentation. "
                    "It measures software scheduling/output differences and does not model firmware execution, "
                    "qualify templates, alter the production default, or gate strict G3."
                ),
            },
        })
    return reports, manifest["template_sha256"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--head-sha", default=os.environ.get("ALLOY_PR_HEAD_SHA"))
    parser.add_argument("--base-sha", default=os.environ.get("ALLOY_PR_BASE_SHA"))
    parser.add_argument("--merge-sha", default=os.environ.get("ALLOY_PR_MERGE_SHA"))
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"))
    args = parser.parse_args()
    args.report_dir.mkdir(parents=True, exist_ok=True)
    summary_path = args.report_dir / "summary.json"
    try:
        if not all((args.repository, args.head_sha, args.base_sha, args.merge_sha, args.token)):
            raise RuntimeError("exact PR head/base/merge and GitHub token are required")
        run = wait_for_reference_run(args.repository, args.head_sha, args.token)
        run_id = run.get("id")
        if not isinstance(run_id, int):
            raise RuntimeError("matching desktop reference run has no numeric ID")
        artifacts_url = (
            f"https://api.github.com/repos/{args.repository}/actions/runs/{run_id}/artifacts?per_page=100"
        )
        artifact_listing = github_get_json(artifacts_url, args.token)
        artifact = select_reference_artifact(artifact_listing.get("artifacts", []))
        archive_bytes = download_artifact(artifact["archive_download_url"], args.token)
        digest = validate_artifact_digest(artifact, archive_bytes)
        with tempfile.TemporaryDirectory(prefix="alloy-template-reference-") as temporary:
            reference_dir = Path(temporary) / "reference"
            safe_extract(archive_bytes, reference_dir)
            validate_reference_manifest(reference_dir, args.head_sha, args.base_sha, args.merge_sha)
            fixture_reports, template_digests = compare_probe_to_reference(args.probe_dir, reference_dir)
        report = diagnostic_report(
            head_sha=args.head_sha, base_sha=args.base_sha, merge_sha=args.merge_sha,
            reference_run_id=run_id, artifact_id=artifact.get("id"), artifact_sha256=digest,
            fixtures=fixture_reports, template_sha256=template_digests,
        )
    except Exception as error:
        # Artifact comparison is observational only. Preserve its failure as
        # data so it can never replace or fail the strict native/G3 workflow.
        report = diagnostic_report(
            head_sha=args.head_sha or "", base_sha=args.base_sha or "", merge_sha=args.merge_sha or "",
            unavailable_reason=f"{type(error).__name__}: {error}",
        )
    summary_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"template-render diagnostic only: {report['diagnostic_status']}")
    if report.get("unavailable_reason"):
        print(report["unavailable_reason"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
