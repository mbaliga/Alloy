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
        self.assertIn("app/src/main/assets/models/**", pull_request)

    def test_export_3mf_uses_filename_relative_to_output_directory(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('--outputdir "$out"', text)
        self.assertIn('--export-3mf "$name.gcode.3mf"', text)
        self.assertNotIn('--export-3mf "$out/$name.gcode.3mf"', text)


if __name__ == "__main__":
    unittest.main()
