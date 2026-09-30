import tempfile
import unittest
from pathlib import Path
import sys

# This module is invoked directly by local parity runs as well as through CI.
# Put the repository root on the import path so `python3 parity/<test>.py`
# exercises the same audit code instead of failing before the first test.
REPOSITORY = Path(__file__).resolve().parents[1]
if str(REPOSITORY) not in sys.path:
    sys.path.insert(0, str(REPOSITORY))

from ci import audit_bambu_treesupport3d as audit


class BambuTreeSupportAuditTests(unittest.TestCase):
    def test_ci_audit_is_pinned_to_the_same_source_contract(self):
        repository = Path(__file__).resolve().parents[1]
        workflow = (repository / ".github/workflows/path-b-g5-bambu-treesupport3d-audit.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn(f"BAMBU_TAG: {audit.PINNED_TAG}", workflow)
        self.assertIn(f"BAMBU_SHA: {audit.PINNED_COMMIT}", workflow)
        self.assertIn("ci/audit_bambu_treesupport3d.py", workflow)
        self.assertIn("path-b-g5-bambu-treesupport3d-audit", workflow)

    def test_include_surface_resolves_local_and_root_includes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Support").mkdir(parents=True)
            (root / "Geometry.hpp").write_text("geometry", encoding="utf-8")
            source = root / "Support/TreeSupport3D.cpp"
            source.write_text('#include "TreeSupport3D.hpp"\n#include "../Geometry.hpp"\n', encoding="utf-8")
            (root / "Support/TreeSupport3D.hpp").write_text("header", encoding="utf-8")
            result = audit.relative_include_surface(root, [source])
            self.assertEqual({
                "Geometry.hpp": audit.sha256(root / "Geometry.hpp"),
                "Support/TreeSupport3D.hpp": audit.sha256(root / "Support/TreeSupport3D.hpp"),
            }, result)

    def test_resolver_accepts_bambu_src_rooted_libslic3r_include(self):
        with tempfile.TemporaryDirectory() as directory:
            src = Path(directory) / "src"
            root = src / "libslic3r"
            support = root / "Support"
            support.mkdir(parents=True)
            entry = support / "TreeSupport3D.cpp"
            header = root / "Point.hpp"
            entry.write_text('#include "libslic3r/Point.hpp"\n', encoding="utf-8")
            header.write_text("point", encoding="utf-8")
            self.assertEqual(header.resolve(), audit.resolve_include(root, entry, "libslic3r/Point.hpp"))
            self.assertEqual({}, audit.unresolved_quoted_includes(root, [entry]))

    def test_transitive_surface_follows_quoted_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Support").mkdir(parents=True)
            entry = root / "Support/TreeSupport3D.cpp"
            header = root / "Support/TreeSupport3D.hpp"
            nested = root / "Geometry.hpp"
            entry.write_text('#include "TreeSupport3D.hpp"\n', encoding="utf-8")
            header.write_text('#include "../Geometry.hpp"\n', encoding="utf-8")
            nested.write_text("geometry", encoding="utf-8")
            result = audit.transitive_include_surface(root, [entry])
            self.assertEqual({
                "Geometry.hpp": audit.sha256(nested),
                "Support/TreeSupport3D.hpp": audit.sha256(header),
            }, result)

    def test_unresolved_quoted_includes_are_reported_by_including_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Support").mkdir(parents=True)
            entry = root / "Support/TreeSupport3D.cpp"
            entry.write_text('#include "MissingBambuCore.hpp"\n', encoding="utf-8")
            result = audit.unresolved_quoted_includes(root, [entry])
            self.assertEqual({"Support/TreeSupport3D.cpp": ["MissingBambuCore.hpp"]}, result)

    def test_audit_reports_missing_tree_support3d_route(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bambu = root / "bambu"
            alloy = root / "alloy"
            (bambu / "Support").mkdir(parents=True)
            alloy.mkdir()
            for relative in audit.SOURCE_FILES:
                path = bambu / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                content = ('#include "TreeSupport3D.hpp"\nvoid generate_tree_support_3D();\n'
                           if relative == "Support/TreeSupport.cpp" else "source\n")
                path.write_text(content, encoding="utf-8")
            report = audit.audit_sources(bambu, alloy)
            self.assertEqual("PORT_REQUIRED", report["status"])
            self.assertEqual(len(audit.SOURCE_FILES), len(report["required_source_mismatches_in_alloy"]))
            self.assertTrue(report["route_markers"]["bambu_routes_tree_organic_to_3d"])
            self.assertFalse(report["route_markers"]["alloy_has_tree_support3d_source"])

    def test_alloy_lightning_grounding_route_is_explicit(self):
        """Keep the Bambu-derived organic supplement on the native route.

        This is intentionally a source-contract test, not a parity claim. The
        runtime G3 export remains the evidence that the native engine executes;
        this guard prevents a future refactor from leaving the lightning pass
        compiled but disconnected from support toolpath emission.
        """
        repository = Path(__file__).resolve().parents[1]
        tree = (repository / "app/src/main/jni/libslic3r/Support/TreeSupport3D.cpp").read_text(
            encoding="utf-8"
        )
        common = (repository / "app/src/main/jni/libslic3r/Support/SupportCommon.cpp").read_text(
            encoding="utf-8"
        )
        header = (repository / "app/src/main/jni/libslic3r/Support/SupportCommon.hpp").read_text(
            encoding="utf-8"
        )
        self.assertIn('#include "Fill/Lightning/Generator.hpp"', tree)
        self.assertIn("static void organic_lightning_infill(", tree)
        self.assertIn("FillLightning::Generator generator", tree)
        self.assertIn("organic_lightning_infill(print_object, volumes, config", tree)
        self.assertIn("const std::vector<ExPolygons>       &lightning_infill_areas)", common)
        self.assertIn("!lightning_infill_areas[support_layer_id].empty()", common)
        self.assertIn("const std::vector<ExPolygons>     &lightning_infill_areas", header)
        self.assertIn("fill_expolygons_generate_paths", common)

    def test_alloy_organic_cooling_metadata_route_is_explicit(self):
        """Keep steep organic branch metadata connected to support emission.

        The current G-code consumer does not yet apply support overhang metadata,
        so this is a wiring guard rather than a claim of Bambu cooling parity.
        """
        repository = Path(__file__).resolve().parents[1]
        tree = (repository / "app/src/main/jni/libslic3r/Support/TreeSupport3D.cpp").read_text(
            encoding="utf-8"
        )
        common = (repository / "app/src/main/jni/libslic3r/Support/SupportCommon.cpp").read_text(
            encoding="utf-8"
        )
        header = (repository / "app/src/main/jni/libslic3r/Support/SupportCommon.hpp").read_text(
            encoding="utf-8"
        )
        self.assertIn("std::vector<ExPolygons>          &cooldown_areas", tree)
        self.assertIn("cooldown_areas[static_cast<size_t>(layer_idx + 1)]", tree)
        self.assertIn("cooldown_areas,\n            lightning_infill_areas", tree)
        self.assertIn("const std::vector<ExPolygons>       &cooldown_areas", common)
        self.assertIn("path->overhang_degree = 10", common)
        self.assertIn("const std::vector<ExPolygons>     &cooldown_areas", header)

    def test_alloy_cooling_consumer_honors_branch_metadata(self):
        """Keep the metadata route connected to the existing fan policy."""
        repository = Path(__file__).resolve().parents[1]
        gcode = (repository / "app/src/main/jni/libslic3r/GCode.cpp").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            "double path_overhang_degree, ExtrusionRole role",
            gcode,
        )
        self.assertIn(
            "path_overhang_degree > std::max(0, static_cast<int>(overhang_fan_threshold) - 1)",
            gcode,
        )
        self.assertIn(
            "check_overhang_fan(processed_point.overlap, path.overhang_degree, path.role())",
            gcode,
        )

    def test_bambu_polygon_cleanup_api_preserves_contour_and_hole_paths(self):
        repository = Path(__file__).resolve().parents[1]
        expolygon = (repository / "app/src/main/jni/libslic3r/ExPolygon.hpp").read_text(
            encoding="utf-8"
        )
        self.assertIn("void remove_colinear_points()", expolygon)
        self.assertIn("remove_collinear(contour);", expolygon)
        self.assertIn("remove_collinear(holes);", expolygon)


if __name__ == "__main__":
    unittest.main()
