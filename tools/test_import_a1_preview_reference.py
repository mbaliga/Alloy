#!/usr/bin/env python3
"""Regression tests for the dependency-free A1 visual-reference importer."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("import_a1_preview_reference.py")
SPEC = importlib.util.spec_from_file_location("import_a1_preview_reference", SCRIPT)
assert SPEC and SPEC.loader
IMPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORTER)


def mesh_object(object_id: int) -> str:
    return f"""
      <object id=\"{object_id}\" type=\"model\">
        <mesh>
          <vertices>
            <vertex x=\"0\" y=\"0\" z=\"0\"/>
            <vertex x=\"1\" y=\"0\" z=\"0\"/>
            <vertex x=\"0\" y=\"1\" z=\"0\"/>
          </vertices>
          <triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles>
        </mesh>
      </object>
    """


class DirectThreeMfImportTest(unittest.TestCase):
    def test_direct_3mf_has_expected_a1_objects_and_applies_components(self) -> None:
        model = f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
          <model xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">
            <resources>
              {mesh_object(1)}
              {mesh_object(2)}
              {mesh_object(3)}
              {mesh_object(4)}
              <object id=\"10\" type=\"model\">
                <components>
                  <component objectid=\"2\" transform=\"1 0 0 0 1 0 0 0 1 10 20 30\"/>
                  <component objectid=\"3\" transform=\"1 0 0 0 1 0 0 0 1 20 20 30\"/>
                  <component objectid=\"4\" transform=\"1 0 0 0 1 0 0 0 1 30 20 30\"/>
                </components>
              </object>
            </resources>
            <build><item objectid=\"10\"/></build>
          </model>
        """
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "reference.3mf"
            output = Path(directory) / "reference.mesh"
            with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("3D/3dmodel.model", model)

            vertices, faces = IMPORTER._load_3mf(source)
            compact = IMPORTER._compact_mesh(vertices, faces)
            IMPORTER._write(output, compact, "test fixture")

            self.assertEqual(len(faces), 3)
            self.assertEqual(len(vertices), 9)
            self.assertGreaterEqual(int(compact["vertices"]), 3)
            self.assertEqual(int(compact["triangles"]), 3)
            self.assertTrue(output.is_file())
            self.assertIn("version=1", output.read_text(encoding="ascii"))


if __name__ == "__main__":
    unittest.main()
