"""Tests for the separately labeled, non-gating template render comparison."""

from types import SimpleNamespace
import unittest
from unittest.mock import patch
import sys
import tempfile
from pathlib import Path

from ci.compare_machine_template_probe import (
    FIXTURES,
    SCOPE,
    compare_probe_to_reference,
    diagnostic_report,
    fixture_diagnostic,
    main,
)


def metrics(**overrides):
    values = {
        "print_time_seconds": 100.0,
        "filament_mm": 50.0,
        "filament_g": 0.15,
        "layer_count": 10,
        "extrusion_moves": 100,
        "travel_moves": 25,
        "positive_e_mm": 51.0,
        "linear_xyz_path_mm": 1000.0,
        "arc_path_mm": 0.0,
        "nominal_linear_extrusion_seconds": 70.0,
        "nominal_linear_travel_seconds": 20.0,
        "nominal_arc_extrusion_seconds": 0.0,
        "nominal_arc_travel_seconds": 0.0,
        "feature_extrusion_path_mm": {"outer_wall": 100.0},
        "feature_positive_e_mm": {"outer_wall": 10.0},
        "extrusion_accounting": {"after_first_layer": {"positive_xyz_e_mm": 50.0}},
    }
    values.update(overrides)
    return SimpleNamespace(**values)


class MachineTemplateProbeDiagnosticTests(unittest.TestCase):
    def _probe_inputs(self, root: Path, omit_end_only=None):
        probe_dir = root / "probe"
        reference_dir = root / "reference"
        (probe_dir / "end-only").mkdir(parents=True)
        for fixture in FIXTURES:
            desktop_dir = reference_dir / "orca-reference" / fixture
            desktop_dir.mkdir(parents=True)
            (desktop_dir / f"{fixture}.gcode.3mf").write_text("reference", encoding="utf-8")
            for relative in (
                f"{fixture}.gcode", f"{fixture}.config.ini",
                f"end-only/{fixture}.gcode", f"end-only/{fixture}.config.ini",
            ):
                path = probe_dir / relative
                if relative == f"end-only/{omit_end_only}.gcode":
                    continue
                path.write_text("probe", encoding="utf-8")
        return probe_dir, reference_dir

    def _comparison_patches(self):
        identity = {"result": "pass", "checked_fields": 62, "failed_fields": []}
        manifest = {
            "template_sha256": {"machine_end_gcode": "a" * 64},
            "fixtures": [{"fixture": fixture, "sha256": "b" * 64} for fixture in FIXTURES],
        }
        end_manifest = {
            "template_sha256": "c" * 64,
            "fixtures": [{"fixture": fixture, "sha256": "d" * 64} for fixture in FIXTURES],
        }

        def parse(path, _member):
            path_text = str(path)
            if "/end-only/" in path_text:
                return metrics(print_time_seconds=120.0)
            if path_text.endswith(".gcode"):
                return metrics(print_time_seconds=110.0)
            return metrics()

        return (
            patch("ci.compare_machine_template_probe.validate_probe", return_value=manifest),
            patch("ci.compare_machine_template_probe.validate_end_only", return_value=end_manifest),
            patch("ci.compare_machine_template_probe.parse_metrics", side_effect=parse),
            patch("ci.compare_machine_template_probe.merged_desktop_settings", return_value={}),
            patch("ci.compare_machine_template_probe.read_native_settings", return_value={}),
            patch("ci.compare_machine_template_probe.verify", return_value=identity),
        )

    def test_comparator_reports_four_labeled_end_only_measurements(self):
        with tempfile.TemporaryDirectory() as temporary:
            probe_dir, reference_dir = self._probe_inputs(Path(temporary))
            patches = self._comparison_patches()
            with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
                reports, _digests = compare_probe_to_reference(probe_dir, reference_dir)
        self.assertEqual(list(FIXTURES), [row["fixture"] for row in reports])
        for row in reports:
            end_only = row["end_only_render_vs_desktop_full_hook_reference"]
            self.assertEqual("measurement-only", end_only["comparison_kind"])
            self.assertEqual("machine-end-template-only-render-diagnostic", end_only["render_scope"])
            self.assertEqual("c" * 64, end_only["template_sha256"])
            self.assertEqual("d" * 64, end_only["probe_gcode_sha256"])
            self.assertEqual(20.0, end_only["measurements"]["metrics"]["print_time_seconds"]["delta"])
            self.assertIn("does not isolate machine-end contribution", end_only["interpretation_limit"])

    def test_comparator_fails_if_any_end_only_gcode_or_config_is_absent(self):
        for missing_name in ("cube_20mm.gcode", "cube_20mm.config.ini"):
            with self.subTest(missing=missing_name), tempfile.TemporaryDirectory() as temporary:
                probe_dir, reference_dir = self._probe_inputs(Path(temporary))
                (probe_dir / "end-only" / missing_name).unlink()
                patches = self._comparison_patches()
                with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
                    with self.assertRaisesRegex(RuntimeError, "template diagnostic input missing"):
                        compare_probe_to_reference(probe_dir, reference_dir)
    def test_fixture_reports_deltas_without_a_pass_fail_decision(self):
        report = fixture_diagnostic(
            metrics(),
            metrics(print_time_seconds=110.0, travel_moves=20,
                    linear_xyz_path_mm=1200.0,
                    feature_extrusion_path_mm={"outer_wall": 100.0, "support": 5.0}),
            {"result": "pass", "checked_fields": 62, "failed_fields": []},
        )
        self.assertEqual("measurement-only", report["comparison_kind"])
        self.assertNotIn("result", report)
        self.assertEqual(10.0, report["metrics"]["print_time_seconds"]["delta"])
        self.assertEqual(10.0, report["metrics"]["print_time_seconds"]["relative_delta_percent"])
        self.assertEqual(-5, report["metrics"]["travel_moves"]["delta"])
        self.assertEqual(5.0, report["deposited_feature_path_mm"]["support"]["template_probe"])

    def test_metric_with_no_reference_baseline_has_no_invented_percentage(self):
        report = fixture_diagnostic(
            metrics(feature_extrusion_path_mm={}),
            metrics(feature_extrusion_path_mm={"skirt": 12.0}),
            {"result": "pass", "checked_fields": 62, "failed_fields": []},
        )
        delta = report["deposited_feature_path_mm"]["skirt"]
        self.assertEqual(0.0, delta["desktop"])
        self.assertEqual(12.0, delta["delta"])
        self.assertIsNone(delta["relative_delta_percent"])

    def test_report_is_explicitly_non_gating_and_unqualified(self):
        report = diagnostic_report(
            head_sha="head", base_sha="base", merge_sha="merge",
            fixtures=[{"fixture": "cube_20mm"}], template_sha256={"machine_start_gcode": "digest"},
        )
        self.assertEqual(SCOPE, report["scope"])
        self.assertTrue(report["diagnostic_only"])
        self.assertTrue(report["non_gating"])
        self.assertEqual("PENDING", report["qualification"])
        self.assertEqual("PENDING", report["direct_send_qualification"])
        self.assertEqual("complete", report["diagnostic_status"])
        self.assertNotIn("result", report)

    def test_unavailable_report_preserves_error_without_failing_g3(self):
        report = diagnostic_report(
            head_sha="head", base_sha="base", merge_sha="merge",
            unavailable_reason="probe output missing",
        )
        self.assertEqual("unavailable", report["diagnostic_status"])
        self.assertEqual("probe output missing", report["unavailable_reason"])
        self.assertTrue(report["non_gating"])
        self.assertNotIn("result", report)

    def test_github_reference_failure_writes_unavailable_report_and_returns_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            report_dir = Path(temporary) / "report"
            argv = [
                "compare_machine_template_probe.py", "--probe-dir", temporary,
                "--report-dir", str(report_dir), "--repository", "mbaliga/Alloy",
                "--head-sha", "head", "--base-sha", "base", "--merge-sha", "merge",
                "--token", "test-token",
            ]
            with patch.object(sys, "argv", argv), patch(
                "ci.compare_machine_template_probe.wait_for_reference_run",
                side_effect=RuntimeError("no exact-head desktop reference"),
            ):
                self.assertEqual(0, main())
            import json
            report = json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual("unavailable", report["diagnostic_status"])
            self.assertIn("no exact-head", report["unavailable_reason"])
            self.assertTrue(report["non_gating"])


if __name__ == "__main__":
    unittest.main()
