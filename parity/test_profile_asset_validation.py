import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))

import validate_profile_assets  # noqa: E402


PROFILE = ROOT / "app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json"


class ProfileAssetValidationTests(unittest.TestCase):
    def _validate_mutation(self, mutate):
        profile = json.loads(PROFILE.read_text(encoding="utf-8"))
        mutate(profile)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"
            path.write_text(json.dumps(profile), encoding="utf-8")
            with self.assertRaises(ValueError):
                validate_profile_assets.validate(path)

    def test_packaged_profile_is_valid(self):
        validate_profile_assets.validate(PROFILE)

    def test_rejects_non_finite_profile_number(self):
        self._validate_mutation(lambda profile: profile["process"].update(layer_height_mm=float("nan")))

    def test_rejects_boolean_and_out_of_range_numbers(self):
        self._validate_mutation(lambda profile: profile["printer"].update(bed_x_mm=True))
        self._validate_mutation(lambda profile: profile["filament"].update(nozzle_temperature_c=401))

    def test_rejects_unapproved_native_setting(self):
        self._validate_mutation(lambda profile: profile["native_settings"].update(unsupported_projection="1"))


if __name__ == "__main__":
    unittest.main()
