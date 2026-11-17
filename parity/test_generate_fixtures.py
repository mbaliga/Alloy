"""Ensure desktop G3 fixtures are the same meshes Android actually slices."""

from pathlib import Path
import tempfile
import unittest

from generate_fixtures import generate


ROOT = Path(__file__).resolve().parents[1]


def vertex_cloud(path: Path) -> list[tuple[float, float, float]]:
    vertices = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.strip().split()
        if parts and parts[0] == "vertex":
            vertices.append(tuple(round(float(value), 6) for value in parts[1:4]))
    return sorted(vertices)


class FixtureGeometryTests(unittest.TestCase):
    def test_parity_stls_match_android_fixture_geometry(self):
        assets = ROOT / "app/src/main/assets/models"
        source_pairs = {
            "cube_20mm.stl": "box-20mm.stl",
            "overhang_support.stl": "overhang-support-fixture.stl",
            "thin_wall_frame.stl": "thin-wall-frame-fixture.stl",
            "travel_obstacle.stl": "travel_obstacle.stl",
        }
        with tempfile.TemporaryDirectory() as temporary:
            generated = Path(temporary)
            generate(generated)
            for fixture_name, asset_name in source_pairs.items():
                with self.subTest(fixture=fixture_name):
                    self.assertEqual(
                        vertex_cloud(assets / asset_name),
                        vertex_cloud(generated / fixture_name),
                    )


if __name__ == "__main__":
    unittest.main()
