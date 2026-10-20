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
import os
from pathlib import Path
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
    end_only_fixtures = {
        item["fixture"]: item for item in end_only_manifest["fixtures"]
    }
    reports: list[dict[str, Any]] = []
    for fixture in FIXTURES:
        desktop_path = reference_dir / "orca-reference" / fixture / f"{fixture}.gcode.3mf"
        probe_path = probe_dir / f"{fixture}.gcode"
        config_path = probe_dir / f"{fixture}.config.ini"
        end_only_path = probe_dir / "end-only" / f"{fixture}.gcode"
        end_only_config_path = probe_dir / "end-only" / f"{fixture}.config.ini"
        for path in (desktop_path, probe_path, config_path, end_only_path, end_only_config_path):
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f"template diagnostic input missing or empty: {path.name}")
        desktop = parse_metrics(desktop_path, None)
        rendered = parse_metrics(probe_path, None)
        end_only_rendered = parse_metrics(end_only_path, None)
        desktop_settings = merged_desktop_settings(desktop_path)
        profile_identity = verify(desktop_settings, read_native_settings(config_path))
        end_only_profile_identity = verify(
            desktop_settings, read_native_settings(end_only_config_path)
        )
        if end_only_profile_identity != profile_identity:
            raise RuntimeError(f"end-only render profile identity differs for {fixture}")
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
