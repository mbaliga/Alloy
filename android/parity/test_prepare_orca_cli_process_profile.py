"""Check the Orca CLI wrapper preserves Bambu project print settings."""

import json
from pathlib import Path
import tempfile
import unittest

from ci.prepare_orca_cli_process_profile import ORCA_COMPATIBILITY_FIELDS, prepare
from ci.neutralize_bambu_motion_hooks import HOOK_KEYS, NEUTRAL_HOOK_VALUE


class PrepareOrcaCliProcessProfileTests(unittest.TestCase):
    def test_wrapper_changes_only_profile_provenance_and_compatibility_metadata(self):
        project = {
            "from": "project",
            "name": "project_settings",
            "layer_height": "0.2",
            "bed_temperature": "35",
            "support_type": "tree(auto)",
        }
        resolved = {
            "type": "process",
            "from": "system",
            "compatible_printers": ["Bambu Lab A1 mini 0.4 nozzle"],
            "compatible_printers_condition": "",
            "setting_id": "GP000",
            "instantiation": "true",
            "layer_height": "0.16",
        }
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project_path = root / "project.json"
            profile_path = root / "resolved-process.json"
            output_path = root / "out" / "process.json"
            project_path.write_text(json.dumps(project), encoding="utf-8")
            profile_path.write_text(json.dumps(resolved), encoding="utf-8")
            prepare(project_path, profile_path, output_path)
            result = json.loads(output_path.read_text(encoding="utf-8"))

        self.assertEqual(result["type"], "process")
        self.assertEqual(result["from"], "user")
        self.assertEqual(result["compatible_printers"], resolved["compatible_printers"])
        self.assertEqual(result["setting_id"], "GP000")
        self.assertEqual(result["layer_height"], project["layer_height"])
        self.assertEqual(result["bed_temperature"], project["bed_temperature"])
        self.assertEqual(result["support_type"], project["support_type"])
        for key in ORCA_COMPATIBILITY_FIELDS:
            if key not in resolved:
                self.assertNotIn(key, result)

    def test_missing_printer_compatibility_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root / "project.json"
            profile = root / "profile.json"
            output = root / "out.json"
            project.write_text(json.dumps({"from": "project", "layer_height": "0.2"}), encoding="utf-8")
            profile.write_text(json.dumps({"compatible_printers": []}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "compatible_printers"):
                prepare(project, profile, output)

    def test_diagnostic_neutralizes_wrapper_hooks_without_mutating_project_source(self):
        project = {
            "from": "project",
            "time_lapse_gcode": "M1004 S5 P1",
            "wrapping_detection_gcode": "G39.3 S1",
            "layer_height": "0.2",
        }
        resolved = {"compatible_printers": ["Bambu Lab A1 mini 0.4 nozzle"]}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project_path = root / "project.json"
            profile_path = root / "resolved-process.json"
            output_path = root / "out" / "process.json"
            project_path.write_text(json.dumps(project), encoding="utf-8")
            profile_path.write_text(json.dumps(resolved), encoding="utf-8")
            original_project_bytes = project_path.read_bytes()
            prepare(
                project_path,
                profile_path,
                output_path,
                neutralize_motion_hooks=True,
            )
            process = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(project_path.read_bytes(), original_project_bytes)

        self.assertEqual({key: process[key] for key in HOOK_KEYS}, {
            key: NEUTRAL_HOOK_VALUE for key in HOOK_KEYS
        })
        self.assertEqual(process["layer_height"], project["layer_height"])

    def test_default_wrapper_preserves_project_motion_hooks(self):
        project = {
            "from": "project",
            "time_lapse_gcode": "M1004 S5 P1",
            "wrapping_detection_gcode": "G39.3 S1",
        }
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project_path = root / "project.json"
            profile_path = root / "resolved-process.json"
            output_path = root / "out.json"
            project_path.write_text(json.dumps(project), encoding="utf-8")
            profile_path.write_text(json.dumps({
                "compatible_printers": ["Bambu Lab A1 mini 0.4 nozzle"]
            }), encoding="utf-8")
            prepare(project_path, profile_path, output_path)
            process = json.loads(output_path.read_text(encoding="utf-8"))
        self.assertEqual({key: process[key] for key in HOOK_KEYS}, {
            key: project[key] for key in HOOK_KEYS
        })


if __name__ == "__main__":
    unittest.main()
