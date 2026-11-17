import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))

import identify_slicebeam_prusa_engine as audit  # noqa: E402


class ProvenanceLayoutTests(unittest.TestCase):
    def test_resolves_legacy_libslic3r_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "src/libslic3r/Print.cpp"
            path.parent.mkdir(parents=True)
            path.write_text("legacy", encoding="utf-8")
            resolved = audit.official_file_path(root, "Print.cpp")
            self.assertIsNotNone(resolved)
            self.assertEqual(resolved[1], "src/libslic3r/Print.cpp")

    def test_resolves_nested_libslic3r_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "src/libslic3r/src/libslic3r/Print.cpp"
            path.parent.mkdir(parents=True)
            path.write_text("nested", encoding="utf-8")
            resolved = audit.official_file_path(root, "Print.cpp")
            self.assertIsNotNone(resolved)
            self.assertEqual(resolved[1], "src/libslic3r/src/libslic3r/Print.cpp")


if __name__ == "__main__":
    unittest.main()
