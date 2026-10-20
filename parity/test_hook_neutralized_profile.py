import json
from pathlib import Path
import tempfile
import unittest

from neutralize_bambu_motion_hooks import (
    HOOK_KEYS,
    MANIFEST_NAME,
    OUTPUT_NAME,
    create_neutral_profile,
)


class HookNeutralizedProfileTests(unittest.TestCase):
    def test_clears_only_motion_hooks_and_records_input_hashes(self):
        original = {
            "printer_model": "A1 mini",
            "time_lapse_gcode": "; SKIPTYPE: timelapse\\nM622 J1\\n; SKIPTYPE: head_wrap_detect\\nG39 S1\\nM623",
            "wrapping_detection_gcode": "M622 J1\\nG39 S1\\nM623",
            "other_setting": [1, 2, 3],
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "machine.json"
            source.write_text(json.dumps(original), encoding="utf-8")
            result_dir = root / "out"
            manifest = create_neutral_profile(source, result_dir)
            neutral = json.loads((result_dir / OUTPUT_NAME).read_text(encoding="utf-8"))
            persisted = json.loads((result_dir / MANIFEST_NAME).read_text(encoding="utf-8"))

        self.assertEqual(neutral["time_lapse_gcode"], "")
        self.assertEqual(neutral["wrapping_detection_gcode"], "")
        self.assertEqual({key: neutral[key] for key in original if key not in HOOK_KEYS},
                         {key: original[key] for key in original if key not in HOOK_KEYS})
        self.assertEqual(manifest["scope"], "hook-neutralized-g3-counterfactual-only")
        self.assertIs(manifest["qualified"], False)
        self.assertEqual(manifest["cleared_keys"], list(HOOK_KEYS))
        self.assertEqual(persisted, manifest)

    def test_refuses_profile_without_source_hook_or_required_keys(self):
        cases = [
            {"time_lapse_gcode": "", "wrapping_detection_gcode": ""},
            {"time_lapse_gcode": "hook"},
            {"time_lapse_gcode": "M622 J1", "wrapping_detection_gcode": ""},
        ]
        for profile in cases:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source = root / "machine.json"
                source.write_text(json.dumps(profile), encoding="utf-8")
                with self.assertRaises(ValueError):
                    create_neutral_profile(source, root / "out")


if __name__ == "__main__":
    unittest.main()
