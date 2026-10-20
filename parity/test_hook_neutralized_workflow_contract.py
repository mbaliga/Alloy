from pathlib import Path
import unittest


ROOT = Path(__file__).parents[1]


class HookNeutralizedWorkflowContractTests(unittest.TestCase):
    def test_native_diagnostic_is_separate_non_gating_and_after_strict_report(self):
        text = (ROOT / ".github/workflows/alloy-native-build.yml").read_text(encoding="utf-8")
        strict = text.split("- name: Compare native G3 output with exact-PR-head Orca reference", 1)[1]
        strict = strict.split("- name: Upload native G3 semantic comparison report", 1)[0]
        diagnostic = text.split("- name: Compare hook-neutralized G3 counterfactual (diagnostic only)", 1)[1]
        diagnostic = diagnostic.split("- name: Upload hook-neutralized G3 counterfactual diagnostic", 1)[0]
        upload = text.split("- name: Upload hook-neutralized G3 counterfactual diagnostic", 1)[1]
        upload = upload.split("- name: Upload native instrumentation report", 1)[0]
        self.assertIn("python3 ci/compare_g3_artifacts.py", strict)
        self.assertIn("--native-dir path-b-g3-evidence", strict)
        self.assertIn("continue-on-error: true", diagnostic)
        self.assertIn("python3 ci/compare_hook_neutralized_g3.py", diagnostic)
        self.assertIn("path-b-hook-neutralized-g3-diagnostic", diagnostic)
        self.assertIn("name: alloy-hook-neutralized-g3-counterfactual", upload)
        self.assertIn("if-no-files-found: warn", upload)


if __name__ == "__main__":
    unittest.main()
