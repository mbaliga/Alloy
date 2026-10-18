"""Tests for the separately labeled, non-gating template render comparison."""

from types import SimpleNamespace
import unittest
from unittest.mock import patch
import sys
import tempfile
from pathlib import Path

from ci.compare_machine_template_probe import (
    SCOPE,
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
