"""Guard the host dependencies required by the pinned Orca AppImage job."""

from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).parents[1] / ".github/workflows/desktop-orca-reference.yml"


class DesktopOrcaReferenceWorkflowTests(unittest.TestCase):
    def test_appimage_host_libraries_include_webkit_runtime(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        install = re.search(r"sudo apt-get install -y([^\n]+)", text)
        self.assertIsNotNone(install, "workflow must install AppImage host libraries")
        packages = set(install.group(1).split())
        self.assertTrue(
            {
                "libopengl0",
                "libglu1-mesa",
                "libwebkit2gtk-4.1-0",
                "libjavascriptcoregtk-4.1-0",
            }.issubset(packages),
            "OrcaSlicer AppImage needs OpenGL, WebKitGTK 4.1, and JavaScriptCoreGTK 4.1",
        )

    def test_failed_slice_still_uploads_diagnostics(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("- name: Slice all three fixtures with pinned A1 Mini profiles", text)
        self.assertIn("if: always()", text)
        self.assertIn("name: a1mini-orca-2.4.2-reference-slices", text)
        self.assertIn("runtime/orca-reference", text)

    def test_current_pull_request_and_fixture_changes_trigger_reference_slices(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        pull_request = text.split("  pull_request:", 1)[1].split("  workflow_dispatch:", 1)[0]
        self.assertIn("codex/fix-native-test-status", pull_request)
        self.assertIn("parity/test_generate_fixtures.py", pull_request)
        self.assertIn("parity/compare_gcode.py", pull_request)
        self.assertIn("parity/test_compare_gcode.py", pull_request)
        self.assertIn("ci/compare_g3_artifacts.py", pull_request)
        self.assertIn("ci/validate_g3_reference_transform.py", pull_request)
        self.assertIn("app/src/main/assets/models/**", pull_request)

    def test_export_3mf_uses_filename_relative_to_output_directory(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('--outputdir "$out"', text)
        self.assertIn('--export-3mf "$name.gcode.3mf"', text)
        self.assertNotIn('--export-3mf "$out/$name.gcode.3mf"', text)

    def test_reference_manifest_captures_exact_pr_tree_identity(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        for key in ("PR head SHA", "PR base SHA", "PR merge SHA"):
            self.assertIn(f'echo "{key}:', text)
        self.assertIn("ALLOY_PR_HEAD_SHA: ${{ github.event.pull_request.head.sha }}", text)
        self.assertIn("ALLOY_PR_BASE_SHA: ${{ github.event.pull_request.base.sha }}", text)
        self.assertIn("ALLOY_PR_MERGE_SHA: ${{ github.sha }}", text)

    def test_hook_neutralized_reference_is_separate_and_non_gating(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        strict = text.split("- name: Slice all three fixtures with pinned A1 Mini profiles", 1)[1]
        strict = strict.split("- name: Slice hook-neutralized counterfactual fixtures", 1)[0]
        diagnostic = text.split("- name: Slice hook-neutralized counterfactual fixtures", 1)[1]
        diagnostic = diagnostic.split("- name: Record authoritative baseline manifest", 1)[0]
        self.assertIn('MACHINE="$GITHUB_WORKSPACE/runtime/profiles/machine.json"', strict)
        self.assertIn('--load-settings "$MACHINE;$PROCESS"', strict)
        self.assertIn("continue-on-error: true", diagnostic)
        self.assertIn("neutralize_bambu_motion_hooks.py", diagnostic)
        self.assertIn("--load-settings \"$MACHINE;$PROCESS\"", diagnostic)
        self.assertIn("runtime/orca-reference-hook-neutralized", diagnostic)
        upload = text.split("- name: Upload Orca reference slices", 1)[1]
        self.assertIn("runtime/orca-reference-hook-neutralized", upload)


    def test_exact_project_settings_get_only_orca_profile_metadata_wrapper(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(text.count('settings["type"] = "process"'), 2)
        self.assertEqual(text.count('settings["from"] = "user"'), 2)
        self.assertEqual(text.count('PROJECT_SETTINGS="$GITHUB_WORKSPACE/app/src/androidTest/assets/profiles/g3-bambu-a1mini-project-settings.json"'), 2)
        self.assertEqual(text.count('PROCESS="$GITHUB_WORKSPACE/runtime/profiles/g3-project-process.json"'), 2)
        self.assertEqual(text.count('--load-settings "$MACHINE;$PROCESS"'), 2)
        self.assertEqual(text.count('raise SystemExit("project_settings fixture must remain an exact project document")'), 2)

        self.assertEqual(text.count('handle.write("\\\\n")'), 2)


if __name__ == "__main__":
    unittest.main()
