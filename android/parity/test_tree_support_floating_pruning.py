import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
TREE_SUPPORT_CPP = REPOSITORY / "app/src/main/jni/libslic3r/Support/TreeSupport.cpp"
TREE_SUPPORT_HPP = REPOSITORY / "app/src/main/jni/libslic3r/Support/TreeSupport.hpp"
SMOKE_TEST = REPOSITORY / "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java"
PARITY_WORKFLOW = REPOSITORY / ".github/workflows/parity-tools.yml"


class TreeSupportFloatingPruningContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cpp = TREE_SUPPORT_CPP.read_text(encoding="utf-8")
        cls.hpp = TREE_SUPPORT_HPP.read_text(encoding="utf-8")
        cls.smoke = SMOKE_TEST.read_text(encoding="utf-8")
        cls.workflow = PARITY_WORKFLOW.read_text(encoding="utf-8")

    def test_prune_is_bound_to_pinned_bambu_source_and_thresholds(self):
        source = "926a7192574bcb9b3a732e1ec59a46d79cb45466"
        self.assertIn(source, self.cpp)
        self.assertIn(source, self.hpp)
        self.assertIn("MIN_CARRIED_RATIO = 0.3", self.hpp)
        self.assertIn("MIN_CARRIED_RATIO_INTERFACE = 0.02", self.hpp)
        prune = self.cpp.split("void TreeSupport::prune_floating_supports()", 1)[1].split(
            "void TreeSupport::draw_circles()", 1
        )[0]
        self.assertIn("carried_area_ratio(carrier, carrier_bboxes, region)", prune)
        self.assertIn("is_interface ? MIN_CARRIED_RATIO_INTERFACE : MIN_CARRIED_RATIO", prune)
        self.assertIn("m_object->get_layer(obj_layer_nr)->lslices", prune)
        self.assertIn("support_below", prune)

    def test_organic_route_remains_separate_and_tree_pass_runs_after_area_finalization(self):
        generate = self.cpp.split("void TreeSupport::generate()", 1)[1].split(
            "coordf_t TreeSupport::calc_branch_radius", 1
        )[0]
        self.assertIn("if (m_support_params.support_style == smsTreeOrganic)", generate)
        self.assertIn("generate_tree_support_3D", generate)
        self.assertIn("return;", generate)
        draw = self.cpp.split("void TreeSupport::draw_circles()", 1)[1].split(
            "void TreeSupport::generate_toolpaths()", 1
        )[0]
        self.assertLess(draw.index("tbb::parallel_for"), draw.index("prune_floating_supports();"))
        self.assertLess(draw.index("prune_floating_supports();"), draw.index("normalize interface_id"))
        self.assertLess(draw.index("prune_floating_supports();"), draw.index("if (with_lightning_infill)"))

    def test_survivors_rebind_groups_and_refresh_support_island_boxes(self):
        prune = self.cpp.split("void TreeSupport::prune_floating_supports()", 1)[1].split(
            "void TreeSupport::draw_circles()", 1
        )[0]
        self.assertIn("assert(area_group.area == &(*regions_by_type[type])[index]);", prune)
        self.assertIn("area_group.area = &regions[index];", prune)
        self.assertIn("assert(area_group.area == &regions[index]);", prune)
        self.assertIn("ts_layer->support_islands = union_ex(islands);", prune)
        self.assertIn("if (!ts_layer->lslices.empty())", prune)
        self.assertIn("ts_layer->lslices = std::move(union_ex(ts_layer->lslices));", prune)
        self.assertIn("ts_layer->lslices_bboxes.clear();", prune)
        self.assertIn("support_below.emplace_back(*area_group.area);", prune)

    def test_native_overhang_fixture_exercises_non_organic_tree_output(self):
        self.assertIn("nonOrganicTreeSupportPruningKeepsOverhangToolpathsValid", self.smoke)
        self.assertIn('config.nativeSettings.put("support_material_style", "tree")', self.smoke)
        self.assertIn('models/overhang-support-fixture.stl', self.smoke)
        self.assertIn('result.gcode.contains(";TYPE:Support")', self.smoke)
        self.assertIn("GcodeSafetyValidator.inspect(result.gcode)", self.smoke)
        self.assertIn('"software regression output must remain unqualified"', self.smoke)
        self.assertIn("parity/test_tree_support_floating_pruning.py", self.workflow)


if __name__ == "__main__":
    unittest.main()
