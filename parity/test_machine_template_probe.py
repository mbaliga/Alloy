import copy
import hashlib
import json
from pathlib import Path
import unittest
from verify_machine_template_probe import KEYS, PROFILE, ROOT, SOURCE, verify_bundle


class MachineTemplateProbeTests(unittest.TestCase):
    def setUp(self):
        self.bundle = json.loads((ROOT / "app/src/androidTest/assets/profiles/a1-mini-machine-template-probe.json").read_text())
        self.source = {"name": PROFILE, **self.bundle["templates"]}

    def test_fixture_self_consistency_and_test_only_location(self):
        verify_bundle(self.bundle, self.source, SOURCE)
        self.assertFalse((ROOT / "app/src/main/assets/profiles/a1-mini-machine-template-probe.json").exists())

    def test_tampered_template_rejected_even_if_digest_updated(self):
        self.bundle["templates"]["machine_start_gcode"] += "\nG28\n"
        self.bundle["sha256"]["machine_start_gcode"] = hashlib.sha256(
            self.bundle["templates"]["machine_start_gcode"].encode()).hexdigest()
        with self.assertRaisesRegex(ValueError, "differs from pinned upstream"):
            verify_bundle(self.bundle, self.source, SOURCE)

    def test_missing_extra_or_corrupt_hooks_rejected(self):
        for mutation in ("missing", "extra", "digest", "empty"):
            with self.subTest(mutation=mutation):
                bundle = copy.deepcopy(self.bundle)
                if mutation == "missing": del bundle["templates"]["time_lapse_gcode"]
                elif mutation == "extra": bundle["templates"]["change_filament_gcode"] = "T1"
                elif mutation == "digest": bundle["sha256"]["machine_end_gcode"] = "0" * 64
                else: bundle["templates"]["machine_end_gcode"] = ""
                with self.assertRaises(ValueError): verify_bundle(bundle, self.source, SOURCE)

    def test_wrong_source_or_scope_rejected(self):
        with self.assertRaises(ValueError): verify_bundle(self.bundle, self.source, "0" * 40)
        for field, value in (("source_commit", "0" * 40), ("scope", "production"),
                             ("profile_name", "Bambu Lab A1"), ("repository", "elsewhere")):
            bundle = {**self.bundle, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_bundle(bundle, self.source, SOURCE)


if __name__ == "__main__": unittest.main()
