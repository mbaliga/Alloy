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


if __name__ == "__main__":
    unittest.main()
