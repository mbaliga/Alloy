import json
import unittest
from pathlib import Path

from compose_g3_orca_profiles import compose
from verify_g3_orca_recipe import verify
from verify_g3_profile import FIELDS

WORKFLOW = Path(__file__).parents[1] / ".github/workflows/desktop-orca-reference.yml"


class G3OrcaRecipeTests(unittest.TestCase):
    def test_checked_in_recipe_uses_explicit_cross_engine_tree_wall_count(self):
        root = Path(__file__).parents[1]
        project = json.loads((root / "app/src/androidTest/assets/profiles/g3-bambu-a1mini-project-settings.json").read_text())
        filament = json.loads((root / "parity/g3-bambu-filament-overlay.json").read_text())
        self.assertEqual("0", project["tree_support_wall_count"])
        self.assertEqual(["10"], filament["slow_down_min_speed"])

    def test_composition_preserves_base_and_applies_explicit_overlay(self):
        result = compose(
            {"type": "process", "name": "base", "layer_height": "0.2", "inherits": "parent"},
            {"type": "process", "name": "Alloy G3", "top_shell_layers": "4", "inherits": "ignored"},
            label="process",
        )
        self.assertEqual("0.2", result["layer_height"])
        self.assertEqual("4", result["top_shell_layers"])
        self.assertNotIn("inherits", result)

    def test_profile_type_change_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "changes profile type"):
            compose({"type": "process"}, {"type": "filament"}, label="process")

    def test_emitted_recipe_mismatch_fails_for_every_source_field(self):
        expected = {desktop_key: "1" for desktop_key, _ in FIELDS.values()}
        actual = dict(expected)
        actual["top_shell_layers"] = "5"
        report = verify(expected, actual)
        self.assertEqual("fail", report["result"])
        self.assertIn("top_solid_layers", report["failed_fields"])

    def test_missing_emitted_field_fails_closed(self):
        expected = {desktop_key: "1" for desktop_key, _ in FIELDS.values()}
        actual = dict(expected)
        actual.pop("nozzle_temperature")
        report = verify(expected, actual)
        self.assertEqual("fail", report["result"])
        self.assertIn("temperature", report["failed_fields"])

    def test_reference_workflow_composes_and_checks_the_effective_recipe(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("parity/compose_g3_orca_profiles.py", workflow)
        self.assertIn("runtime/g3-profiles/process.json", workflow)
        self.assertIn("parity/verify_g3_orca_recipe.py", workflow)
        self.assertIn("recipe-verification.json", workflow)


if __name__ == "__main__":
    unittest.main()
