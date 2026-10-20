import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from compare_hook_neutralized_g3 import (
    FIXTURES,
    compare_neutralized_fixtures,
    _desktop_input,
    _native_input,
)


GCODE = """; time_lapse_gcode =
; wrapping_detection_gcode =
;LAYER_CHANGE
G90
G1 X1 Y1 F600
G1 X2 Y1 F600
G1 X2 Y2 E1 F600
"""


class HookNeutralizedG3Tests(unittest.TestCase):
    def _archive(self, path: Path, settings: dict[str, str], gcode: str = GCODE) -> None:
        with ZipFile(path, "w") as archive:
            archive.writestr("Metadata/project_settings.config", json.dumps(settings))
            archive.writestr("Metadata/plate_1.gcode", gcode)

    def _profile_bundle(self, reference_dir: Path) -> None:
        profile_dir = reference_dir / "profiles"
        neutral_dir = profile_dir / "hook-neutralized"
        neutral_dir.mkdir(parents=True)
        source = {
            "printer_model": "A1 mini",
            "time_lapse_gcode": "; SKIPTYPE: timelapse\\n; SKIPTYPE: head_wrap_detect",
            "wrapping_detection_gcode": "",
            "other_setting": "unchanged",
        }
        neutral = {**source, "time_lapse_gcode": ""}
        source_bytes = (json.dumps(source) + "\n").encode()
        neutral_bytes = (json.dumps(neutral, indent=2) + "\n").encode()
        source_path = profile_dir / "machine.json"
        source_path.write_bytes(source_bytes)
        neutral_path = neutral_dir / "machine-hooks-neutralized.json"
        neutral_path.write_bytes(neutral_bytes)
        manifest = {
            "scope": "hook-neutralized-g3-counterfactual-only",
            "qualified": False,
            "source_profile_sha256": hashlib.sha256(source_bytes).hexdigest(),
            "neutral_profile_sha256": hashlib.sha256(neutral_bytes).hexdigest(),
            "cleared_keys": ["time_lapse_gcode", "wrapping_detection_gcode"],
        }
        (neutral_dir / "machine-hooks-neutralized-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )

    def test_requires_both_desktop_settings_and_native_attestations_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = root / "valid.gcode.3mf"
            self._archive(valid, {"time_lapse_gcode": "", "wrapping_detection_gcode": ""})
            self.assertEqual(_desktop_input(valid)[0], "Metadata/plate_1.gcode")
            native = root / "native.gcode"
            native.write_text(GCODE, encoding="utf-8")
            self.assertEqual(_native_input(native), GCODE)

            for settings in (
                {"time_lapse_gcode": "M971", "wrapping_detection_gcode": ""},
                {"time_lapse_gcode": "", "wrapping_detection_gcode": "G39"},
            ):
                bad = root / "bad.gcode.3mf"
                self._archive(bad, settings)
                with self.subTest(settings=settings), self.assertRaises(ValueError):
                    _desktop_input(bad)
            native.write_text(GCODE.replace("; wrapping_detection_gcode =\n", ""), encoding="utf-8")
            with self.assertRaises(ValueError):
                _native_input(native)

    def test_emits_non_gating_report_for_four_matched_fixtures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native_dir = root / "native"
            reference_dir = root / "reference"
            report_dir = root / "report"
            native_dir.mkdir()
            self._profile_bundle(reference_dir)
            for fixture in FIXTURES:
                native = native_dir / f"{fixture}.gcode"
                native.write_text(GCODE, encoding="utf-8")
                fixture_dir = reference_dir / "orca-reference-hook-neutralized" / fixture
                fixture_dir.mkdir(parents=True)
                self._archive(fixture_dir / f"{fixture}.gcode.3mf", {
                    "time_lapse_gcode": "", "wrapping_detection_gcode": "",
                })
            result = compare_neutralized_fixtures(
                native_dir, reference_dir, report_dir, "a" * 64, 1234
            )
            persisted = json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(result, persisted)
        self.assertEqual(result["scope"], "hook-neutralized-counterfactual-diagnostic-only")
        self.assertEqual(result["qualification"], "not-a-production-parity-result")
        self.assertIs(result["thresholds_applied"], False)
        self.assertEqual([row["fixture"] for row in result["fixtures"]], list(FIXTURES))
        self.assertTrue(all(row["hooks_disabled_verified"] for row in result["fixtures"]))

    def test_rejects_remaining_skiptype_or_hook_motion(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bad.gcode.3mf"
            self._archive(archive, {"time_lapse_gcode": "", "wrapping_detection_gcode": ""})
            self._archive(archive, {"time_lapse_gcode": "", "wrapping_detection_gcode": ""},
                          GCODE + "; SKIPTYPE: timelapse\n")
            with self.assertRaises(ValueError):
                _desktop_input(archive)


if __name__ == "__main__":
    unittest.main()
