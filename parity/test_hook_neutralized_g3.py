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
from neutralize_bambu_motion_hooks import HOOK_KEYS, NEUTRAL_HOOK_VALUE


GCODE = f"""; time_lapse_gcode = {NEUTRAL_HOOK_VALUE}
; wrapping_detection_gcode = {NEUTRAL_HOOK_VALUE}
;LAYER_CHANGE
G90
G1 X1 Y1 F600
G1 X2 Y1 F600
G1 X2 Y2 E1 F600
"""
HOOK_TEXT = "; SKIPTYPE: timelapse\nM622 J1\n; SKIPTYPE: head_wrap_detect\nG39 S1\nM623\n"
STRICT_GCODE = """;LAYER_CHANGE
; SKIPTYPE: timelapse
G1 X20 Y20 F6000
; SKIPTYPE: head_wrap_detect
G39 S1
; SKIPPABLE_END
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

    def _profile_bundle(self, reference_dir: Path) -> dict[str, str]:
        profile_dir = reference_dir / "profiles"
        neutral_dir = profile_dir / "hook-neutralized"
        neutral_dir.mkdir(parents=True)
        source = {
            "printer_model": "A1 mini",
            "time_lapse_gcode": HOOK_TEXT,
            "wrapping_detection_gcode": "",
            "other_setting": "unchanged",
        }
        neutral = {**source, **{key: NEUTRAL_HOOK_VALUE for key in HOOK_KEYS}}
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
            "neutralized_keys": list(HOOK_KEYS),
            "neutralization_value": NEUTRAL_HOOK_VALUE,
            "neutralization_kind": "comment-only-sentinel-to-prevent-empty-setting-fallback",
        }
        (neutral_dir / "machine-hooks-neutralized-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        return source

    def test_requires_both_desktop_settings_and_native_inert_attestations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            valid = root / "valid.gcode.3mf"
            self._archive(valid, {key: NEUTRAL_HOOK_VALUE for key in HOOK_KEYS})
            self.assertEqual(_desktop_input(valid)[0], "Metadata/plate_1.gcode")
            native = root / "native.gcode"
            native.write_text(GCODE, encoding="utf-8")
            self.assertEqual(_native_input(native), GCODE)
            empty_attestations = GCODE.replace(
                f" = {NEUTRAL_HOOK_VALUE}\n", " =\n"
            )
            native.write_text(empty_attestations, encoding="utf-8")
            self.assertEqual(_native_input(native), empty_attestations)

            for settings in (
                # Regression for Orca's empty-value fallback observed in the
                # retained 398994 reference: emitted config restored this hook.
                {"time_lapse_gcode": HOOK_TEXT, "wrapping_detection_gcode": NEUTRAL_HOOK_VALUE},
                {"time_lapse_gcode": NEUTRAL_HOOK_VALUE, "wrapping_detection_gcode": "G39"},
            ):
                bad = root / "bad.gcode.3mf"
                self._archive(bad, settings)
                with self.subTest(settings=settings), self.assertRaises(ValueError):
                    _desktop_input(bad)
            native.write_text(GCODE.replace(
                f"; wrapping_detection_gcode = {NEUTRAL_HOOK_VALUE}\n", ""
            ), encoding="utf-8")
            with self.assertRaises(ValueError):
                _native_input(native)

    def test_emits_non_gating_report_for_four_matched_fixtures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native_dir = root / "native"
            reference_dir = root / "reference"
            report_dir = root / "report"
            native_dir.mkdir()
            source_profile = self._profile_bundle(reference_dir)
            for fixture in FIXTURES:
                native = native_dir / f"{fixture}.gcode"
                native.write_text(GCODE, encoding="utf-8")
                strict_dir = reference_dir / "orca-reference" / fixture
                strict_dir.mkdir(parents=True)
                self._archive(strict_dir / f"{fixture}.gcode.3mf", {
                    "time_lapse_gcode": source_profile["time_lapse_gcode"],
                    "wrapping_detection_gcode": source_profile["wrapping_detection_gcode"],
                }, STRICT_GCODE)
                fixture_dir = reference_dir / "orca-reference-hook-neutralized" / fixture
                fixture_dir.mkdir(parents=True)
                self._archive(fixture_dir / f"{fixture}.gcode.3mf", {
                    key: NEUTRAL_HOOK_VALUE for key in HOOK_KEYS
                })
            result = compare_neutralized_fixtures(
                native_dir, reference_dir, report_dir, "a" * 64, 1234
            )
            persisted = json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(result, persisted)
        self.assertEqual(result["scope"], "hook-neutralized-counterfactual-diagnostic-only")
        self.assertEqual(result["qualification"], "not-a-production-parity-result")
        self.assertIs(result["thresholds_applied"], False)
        self.assertEqual(result["neutralization_value"], NEUTRAL_HOOK_VALUE)
        self.assertIn("retract_when_changing_layer", result["other_profile_controls_unchanged"])
        self.assertIn("does not model firmware execution", result["interpretation_limit"])
        self.assertEqual([row["fixture"] for row in result["fixtures"]], list(FIXTURES))
        self.assertTrue(all(row["hooks_disabled_verified"] for row in result["fixtures"]))
        row = result["fixtures"][0]
        self.assertEqual(row["strict_desktop"]["travel_moves"], row["hook_neutral_desktop"]["travel_moves"] + 1)
        self.assertEqual(row["desktop_hook_effect_strict_minus_neutral"]["travel_moves"], 1.0)
        self.assertIn("timelapse", row["conditional_motion_by_type"])
        layer_diagnostics = row["largest_layer_travel_deltas"]
        self.assertEqual(layer_diagnostics["limit_per_comparison"], 12)
        self.assertIn("not firmware-executed", layer_diagnostics["interpretation"])
        strict_neutral = layer_diagnostics["strict_desktop_to_hook_neutral_desktop"]
        self.assertLessEqual(len(strict_neutral), 12)
        self.assertEqual(strict_neutral[0]["layer"], 0)
        self.assertEqual(strict_neutral[0]["delta_after_minus_before"]["moves"], -1.0)
        neutral_native = layer_diagnostics["hook_neutral_desktop_to_native"]
        self.assertEqual(len(neutral_native), 1)
        self.assertTrue(all(
            value == 0.0
            for value in neutral_native[0]["delta_after_minus_before"].values()
        ))

    def test_allows_machine_end_commands_when_hook_settings_are_neutral(self):
        machine_end = (
            "M1002 judge_flag timelapse_record_flag\n"
            "M971 S11 C11 O0\n"
            "M1004 S5 P1\n"
            "G39 S1\nG39.3 S1\nM622 J1\nM623\nG392 S0\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "valid.gcode.3mf"
            self._archive(archive, {key: NEUTRAL_HOOK_VALUE for key in HOOK_KEYS},
                          GCODE + machine_end)
            self.assertIn(machine_end.strip(), _desktop_input(archive)[1])

            native = Path(directory) / "native.gcode"
            native_gcode = GCODE.replace(
                f" = {NEUTRAL_HOOK_VALUE}\n", " =\n"
            ) + machine_end
            native.write_text(native_gcode, encoding="utf-8")
            self.assertEqual(_native_input(native), native_gcode)

    def test_rejects_hook_markers_in_profile_settings_even_when_gcode_has_commands(self):
        for settings in (
            {"time_lapse_gcode": HOOK_TEXT, "wrapping_detection_gcode": NEUTRAL_HOOK_VALUE},
            {"time_lapse_gcode": NEUTRAL_HOOK_VALUE, "wrapping_detection_gcode": "G39.3 S1"},
        ):
            with self.subTest(settings=settings), tempfile.TemporaryDirectory() as directory:
                archive = Path(directory) / "bad.gcode.3mf"
                self._archive(archive, settings, GCODE + "M971 S11 C11 O0\n")
                with self.assertRaises(ValueError):
                    _desktop_input(archive)

if __name__ == "__main__":
    unittest.main()
