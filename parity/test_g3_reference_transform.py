"""Tests for the explicit test-only G3 travel-obstacle input transform."""

from pathlib import Path
import sys
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))

from validate_g3_reference_transform import (  # noqa: E402
    CONTRACT_PATH,
    _apply_transform,
    _compose,
    _parse_transform,
    load_contract,
    validate_reference_3mf,
    validate_reference_geometry,
)


def model_xml(rotation: str) -> bytes:
    return f'''<?xml version="1.0"?>
<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
  <resources>
    <object id="2"><components>
      <component objectid="1" transform="1 0 0 0 1 0 0 0 1 0 14 14"/>
    </components></object>
  </resources>
  <build><item objectid="2" transform="{rotation} 76 90 0"/></build>
</model>'''.encode("utf-8")


class G3ReferenceTransformTests(unittest.TestCase):
    def make_archive(self, path: Path, rotation: str) -> None:
        with ZipFile(path, "w") as archive:
            archive.writestr("3D/3dmodel.model", model_xml(rotation))

    def test_contract_is_fixture_scoped_and_pins_rotation(self):
        contract = load_contract()
        self.assertEqual("travel_obstacle", contract["fixture"])
        self.assertEqual("test-only strict G3 comparison input transform", contract["scope"])
        self.assertEqual(-90.0, contract["rotation_degrees"])
        self.assertEqual("3MF row-major 4x3 affine matrix with row-vector points",
                         contract["matrix_convention"])

    def test_3mf_transform_uses_row_vector_semantics(self):
        transform = _parse_transform("0 -1 0 1 0 0 0 0 1 76 90 0", "fixture")
        self.assertEqual((76.0, 89.0, 0.0), _apply_transform((1.0, 0.0, 0.0), transform))

    def test_nested_component_translation_is_rotated_by_build_transform(self):
        build = _parse_transform("0 -1 0 1 0 0 0 0 1 10 10 0", "build-item")
        component = _parse_transform("1 0 0 0 1 0 0 0 1 1 0 0", "component")
        effective = _compose(build, component)
        self.assertEqual((10.0, 9.0, 0.0), _apply_transform((0.0, 0.0, 0.0), effective))

    def test_transform_is_only_wired_to_test_evidence(self):
        native_test = (ROOT / "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("mesh = applyG3ReferenceTransform(assetName, mesh)", native_test)
        self.assertIn('if (!"travel_obstacle.stl".equals(assetName)) return mesh;', native_test)
        comparator = (ROOT / "ci/compare_g3_artifacts.py").read_text(encoding="utf-8")
        self.assertIn("validate_reference_3mf(", comparator)
        self.assertIn("validate_reference_geometry(", comparator)
        self.assertIn("orca-reference\" / \"travel_obstacle\"", comparator)
        workflow = (ROOT / ".github/workflows/parity-tools.yml").read_text(encoding="utf-8")
        self.assertIn("parity/test_g3_reference_transform.py", workflow)
        production_sources = ROOT / "app/src/main/java"
        for source in production_sources.rglob("*.java"):
            text = source.read_text(encoding="utf-8")
            self.assertNotIn("g3-travel-obstacle-transform.json", text, str(source))
            self.assertNotIn("applyG3ReferenceTransform", text, str(source))

    def test_reference_build_and_component_transforms_are_composed(self):
        with tempfile.TemporaryDirectory() as temporary:
            reference = Path(temporary) / "travel_obstacle.gcode.3mf"
            self.make_archive(reference, "-2.22044605e-16 -1 0 1 -2.22044605e-16 0 0 0 1")
            validate_reference_3mf(reference)

    def test_reference_wrong_rotation_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            reference = Path(temporary) / "travel_obstacle.gcode.3mf"
            self.make_archive(reference, "1 0 0 0 1 0 0 0 1")
            with self.assertRaisesRegex(RuntimeError, "rotation differs"):
                validate_reference_3mf(reference)

    def test_reference_ambiguous_build_fails_closed(self):
        xml = model_xml("-2.22044605e-16 -1 0 1 -2.22044605e-16 0 0 0 1")
        xml = xml.replace(b"</build>", b'<item objectid="2"/></build>')
        with tempfile.TemporaryDirectory() as temporary:
            reference = Path(temporary) / "travel_obstacle.gcode.3mf"
            with ZipFile(reference, "w") as archive:
                archive.writestr("3D/3dmodel.model", xml)
            with self.assertRaisesRegex(RuntimeError, "one build item"):
                validate_reference_3mf(reference)

    def make_geometry_archive(self, path: Path, vertices: list[tuple[float, float, float]]) -> None:
        model = b'''<?xml version="1.0"?>
<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
 xmlns:p="http://schemas.microsoft.com/3dmanufacturing/production/2015/06">
 <resources><object id="2"><components>
  <component objectid="1" p:path="/3D/Objects/mesh.model" transform="1 0 0 0 1 0 0 0 1 0 0 0"/>
 </components></object></resources>
 <build><item objectid="2" transform="0 -1 0 1 0 0 0 0 1 10 10 0"/></build>
</model>'''
        vertex_xml = "".join(f'<vertex x="{x}" y="{y}" z="{z}"/>' for x, y, z in vertices)
        face_xml = "".join(
            f'<triangle v1="{index}" v2="{index + 1}" v3="{index + 2}"/>'
            for index in range(0, len(vertices), 3)
        )
        mesh = (
            '<model xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>'
            f'<object id="1"><mesh><vertices>{vertex_xml}</vertices><triangles>{face_xml}</triangles></mesh></object>'
            '</resources></model>'
        )
        with ZipFile(path, "w") as archive:
            archive.writestr("3D/3dmodel.model", model)
            archive.writestr("3D/Objects/mesh.model", mesh)

    @staticmethod
    def write_ascii_stl(path: Path, vertices: list[tuple[float, float, float]]) -> None:
        lines = ["solid fixture"]
        for index in range(0, len(vertices), 3):
            lines.extend((" facet normal 0 0 1", "  outer loop"))
            lines.extend(f"   vertex {x} {y} {z}" for x, y, z in vertices[index:index + 3])
            lines.extend(("  endloop", " endfacet"))
        lines.append("endsolid fixture")
        path.write_text("\n".join(lines), encoding="utf-8")

    def test_reference_mesh_matches_transformed_source_despite_plate_translation(self):
        vertices = [(0, 0, 0), (2, 0, 0), (0, 1, 0)]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            reference = directory / "travel_obstacle.gcode.3mf"
            source = directory / "travel_obstacle.stl"
            self.make_geometry_archive(reference, vertices)
            self.write_ascii_stl(source, vertices)
            validate_reference_geometry(reference, source)

    def test_reference_mesh_mismatch_fails_closed(self):
        vertices = [(0, 0, 0), (2, 0, 0), (0, 1, 0)]
        different = [(0, 0, 0), (3, 0, 0), (0, 1, 0)]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            reference = directory / "travel_obstacle.gcode.3mf"
            source = directory / "travel_obstacle.stl"
            self.make_geometry_archive(reference, different)
            self.write_ascii_stl(source, vertices)
            with self.assertRaisesRegex(RuntimeError, "does not match the test-rotated bundled STL"):
                validate_reference_geometry(reference, source)


if __name__ == "__main__":
    unittest.main()
