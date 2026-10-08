#!/usr/bin/env python3
"""Check extraction of the official and Android CMake source lists."""

from pathlib import Path
import tempfile
import unittest

from ci.audit_official_orca_port_surface import official_cpp_sources, legacy_cpp_sources


class OfficialOrcaPortSurfaceTests(unittest.TestCase):
    def test_extracts_official_cpp_list_and_legacy_target_list(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            official = root / "official-CMakeLists.txt"
            official.write_text(
                "set(lisbslic3r_sources\n"
                "    Mesh.cpp\n"
                "    Support/Tree.cpp\n"
                "    Mesh.hpp\n"
                ")\n"
            )
            legacy = root / "legacy-CMakeLists.txt"
            legacy.write_text(
                "add_library(slic3r SHARED\n"
                "    src/main/jni/libslic3r/Mesh.cpp\n"
                "    src/main/jni/libslic3r/LegacyOnly.cpp\n"
                ")\n"
                "target_compile_definitions(slic3r PRIVATE TEST)\n"
            )

            self.assertEqual(
                {"Mesh.cpp", "Support/Tree.cpp"},
                official_cpp_sources(official),
            )
            self.assertEqual(
                {"Mesh.cpp", "LegacyOnly.cpp"},
                legacy_cpp_sources(legacy),
            )

    def test_missing_official_source_list_is_an_explicit_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cmake = Path(directory) / "CMakeLists.txt"
            cmake.write_text("project(no_source_list)\n")
            with self.assertRaisesRegex(ValueError, "missing official lisbslic3r_sources"):
                official_cpp_sources(cmake)


if __name__ == "__main__":
    unittest.main()
