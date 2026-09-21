#!/usr/bin/env python3

import json
import tempfile
import unittest
from pathlib import Path

from audit_bambu_a1mini_templates import audit_profile, audit_template


SAFE_START = "; Alloy native start · Bambu Lab A1 Mini\nM140 S[first_layer_bed_temperature]\nM104 S[first_layer_temperature]\nG28\nG90\nM83\nG92 E0\nM107"
SAFE_END = "; Alloy native end\nG91\nG1 Z2 F600\nG90\nG1 X0 Y0 F9000\nM104 S0\nM140 S0\nM107\nM84"


class BambuA1MiniTemplateTests(unittest.TestCase):
    def test_safe_single_nozzle_templates_are_ready(self):
        report = audit_template("start_gcode", SAFE_START, {"first_layer_bed_temperature", "first_layer_temperature"})
        self.assertEqual(report["status"], "safe-adapter-ready")
        self.assertEqual(report["bambu_macros"], [])
        self.assertEqual(report["conditional_blocks"]["count"], 0)

    def test_bambu_template_is_review_only(self):
        report = audit_template(
            "machine_start_gcode",
            "M1002 gcode_claim_action : 2\nM140 S[bed_temperature_initial_layer_single]\n{if curr_bed_type==\"Textured PEI Plate\"}\nG29.2 Z{-0.02}\n{endif}",
            {"first_layer_bed_temperature"},
        )
        self.assertEqual(report["status"], "review-only")
        self.assertEqual(report["bambu_macros"][0]["command"], "M1002")
        self.assertGreater(report["conditional_blocks"]["count"], 0)

    def test_indexed_bambu_placeholder_fails_closed(self):
        report = audit_template(
            "machine_start_gcode",
            "M104 S[nozzle_temperature_initial_layer[initial_extruder]]",
            {"first_layer_temperature"},
        )
        self.assertEqual(report["status"], "review-only")
        self.assertEqual(report["unsupported_placeholders"][0]["root"], "first_layer_temperature")

    def test_profile_report_records_exact_template_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "resolved-printer.json"
            path.write_text(json.dumps({"name": "Bambu Lab A1 mini", "machine_start_gcode": SAFE_START, "machine_end_gcode": SAFE_END}))
            report = audit_profile(path)
            self.assertEqual(report["status"], "safe-adapter-ready")
            self.assertEqual(report["templates"]["end_gcode"]["byte_length"], len(SAFE_END.encode("utf-8")))
            self.assertEqual(len(report["templates"]["start_gcode"]["sha256"]), 64)


if __name__ == "__main__":
    unittest.main()
