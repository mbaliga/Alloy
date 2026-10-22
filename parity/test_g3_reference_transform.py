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
    load_contract,
    validate_reference_3mf,
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
        self.assertEqual(90.0, contract["rotation_degrees"])

    def test_transform_is_only_wired_to_test_evidence(self):
        native_test = (ROOT / "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("mesh = applyG3ReferenceTransform(assetName, mesh)", native_test)
        self.assertIn('if (!"travel_obstacle.stl".equals(assetName)) return mesh;', native_test)
        comparator = (ROOT / "ci/compare_g3_artifacts.py").read_text(encoding="utf-8")
        self.assertIn("validate_reference_3mf(", comparator)
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


if __name__ == "__main__":
    unittest.main()
