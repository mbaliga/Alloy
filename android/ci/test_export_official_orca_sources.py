#!/usr/bin/env python3
"""Tests for the pinned official source manifest consumed by CMake."""

from pathlib import Path
import tempfile
import unittest

from ci.export_official_orca_sources import render_source_manifest


class ExportOfficialOrcaSourcesTests(unittest.TestCase):
    def test_exports_sorted_absolute_cpp_sources_and_ignores_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            engine = root / "src/libslic3r"
            engine.mkdir(parents=True)
            (engine / "CMakeLists.txt").write_text(
                "set(lisbslic3r_sources\n Z.cpp\n A.cpp\n A.hpp\n)\n",
                encoding="utf-8",
            )
            (engine / "A.cpp").write_text("", encoding="utf-8")
            (engine / "Z.cpp").write_text("", encoding="utf-8")
            (engine / "A.hpp").write_text("", encoding="utf-8")

            text = render_source_manifest(root)
            self.assertLess(text.index("A.cpp"), text.index("Z.cpp"))
            self.assertIn((engine / "A.cpp").resolve().as_posix(), text)
            self.assertNotIn("A.hpp", text)

    def test_missing_listed_source_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            engine = root / "src/libslic3r"
            engine.mkdir(parents=True)
            (engine / "CMakeLists.txt").write_text(
                "set(lisbslic3r_sources\n Missing.cpp\n)\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "source files are missing"):
                render_source_manifest(root)


if __name__ == "__main__":
    unittest.main()
