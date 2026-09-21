import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

from verify_g3_profile import (FIELDS, merged_desktop_settings, read_desktop_settings,
                                read_desktop_gcode_settings, read_native_settings, verify)


def desktop_settings():
    return {
        "layer_height": 0.2,
        "initial_layer_print_height": 0.2,
        "sparse_infill_density": "15%",
        "wall_loops": 2,
        "top_shell_layers": 5,
        "bottom_shell_layers": 3,
        "top_shell_thickness": 1,
        "bottom_shell_thickness": 0,
        "enable_support": "1",
        "support_type": "tree(auto)",
        "support_base_pattern": "default",
        "support_on_build_plate_only": "0",
        "support_bottom_z_distance": 0.2,
        "support_top_z_distance": 0.2,
        "support_object_xy_distance": 0.35,
        "support_interface_bottom_layers": 2,
        "support_interface_top_layers": 2,
        "support_interface_pattern": "auto",
        "support_interface_spacing": 0.5,
        "support_line_width": 0.42,
        "support_speed": ["150"],
        "tree_support_branch_angle": 45,
        "tree_support_branch_diameter": 2,
        "tree_support_branch_diameter_angle": 5,
        "tree_support_branch_distance": 5,
        "tree_support_wall_count": 0,
        "support_object_first_layer_gap": 0.2,
        "support_interface_not_for_body": 1,
        "support_remove_small_overhang": 1,
        "brim_object_gap": 0.1,
        "brim_type": "auto_brim",
        "brim_width": 5,
        "nozzle_temperature": ["220"],
        "nozzle_temperature_initial_layer": ["220"],
        "hot_plate_temp": ["60"],
        "hot_plate_temp_initial_layer": ["60"],
        "filament_diameter": ["1.75"],
        "filament_flow_ratio": ["0.98"],
        "filament_max_volumetric_speed": ["21"],
        "max_volumetric_speed": ["0"],
        "machine_max_acceleration_x": ["6000", "6000"],
        "machine_max_acceleration_y": ["6000", "6000"],
        "machine_max_acceleration_travel": ["6000", "6000"],
        "machine_max_acceleration_extruding": ["20000", "20000"],
        "travel_acceleration": ["10000"],
        "default_acceleration": ["6000"],
        "sparse_infill_acceleration": ["100%"],
        "infill_direction": 45,
        "slow_down_layer_time": ["6"],
        "slow_down_min_speed": ["20"],
        "small_perimeter_speed": "50%",
        "fan_cooling_layer_time": ["80"],
        "detect_thin_wall": "1",
        "wall_generator": "classic",
        "ironing_type": "no ironing",
        "sparse_infill_pattern": "cubic",
        "internal_solid_infill_pattern": "zig-zag",
        "top_surface_pattern": "zig-zag",
        "bottom_surface_pattern": "zig-zag",
        "support_base_pattern": "default",
        "support_base_pattern_spacing": 2.5,
        "wipe_distance": 0,
        "retraction_minimum_travel": ["1"],
    }


def native_settings():
    result = {}
    for field, (_, native_key) in FIELDS.items():
        # Reverse the exact values expected after normalization. This fixture
        # exercises the array, percent and vocabulary conversions.
        result[native_key] = {
            "support_material_style": "organic",
            "support_material_pattern": "rectilinear",
            "support_material": "1",
            "ironing": "0",
            "fill_density": "15.00000%",
            "small_perimeter_speed": "50%",
            "machine_max_acceleration_x": "6000,6000",
            "machine_max_acceleration_y": "6000,6000",
            "machine_max_acceleration_travel": "6000,6000",
            "machine_max_acceleration_extruding": "20000,20000",
            "filament_max_volumetric_speed": "21",
            "max_volumetric_speed": "0",
            "infill_acceleration": "6000",
            "fill_angle": "45",
            "fan_below_layer_time": "80",
        }.get(native_key, "0")
    # Correct defaults that are not zero-valued in the generic fixture.
    result.update({
        "layer_height": "0.20000", "first_layer_height": "0.20000",
        "perimeters": "2", "top_solid_layers": "5", "bottom_solid_layers": "3",
        "top_solid_min_thickness": "1.0", "bottom_solid_min_thickness": "0",
        "support_material_bottom_contact_distance": "0.2",
        "support_material_contact_distance": "0.2",
        "support_material_xy_spacing": "0.35",
        "support_material_bottom_interface_layers": "2",
        "support_material_interface_layers": "2",
        "support_material_interface_pattern": "auto",
        "support_material_interface_spacing": "0.5",
        "support_material_extrusion_width": "0.42",
        "support_material_speed": "150",
        "support_tree_angle": "45", "support_tree_branch_diameter": "2",
        "tree_support_branch_diameter_angle": "5",
        "support_tree_branch_diameter_double_wall": "0",
        "tree_support_branch_distance_organic": "5",
        "support_object_first_layer_gap": "0.2",
        "support_interface_not_for_body": "1",
        "support_remove_small_overhang": "1",
        "brim_object_gap": "0.1", "brim_type": "auto_brim", "brim_width": "5",
        "temperature": "220", "first_layer_temperature": "220",
        "bed_temperature": "60", "first_layer_bed_temperature": "60",
        "filament_diameter": "1.75000", "extrusion_multiplier": "0.98000",
        "travel_acceleration": "10000", "default_acceleration": "6000",
        "slowdown_below_layer_time": "6", "min_print_speed": "20",
        "thin_walls": "1", "perimeter_generator": "classic",
        "sparse_infill_pattern": "cubic", "internal_solid_infill_pattern": "rectilinear",
        "top_surface_pattern": "rectilinear", "bottom_surface_pattern": "rectilinear",
        "support_base_pattern": "default", "support_base_pattern_spacing": "2.5",
        "retraction_minimum_travel": "1",
    })
    return result


class G3ProfileTests(unittest.TestCase):
    def test_documented_aliases_and_resolved_acceleration_pass(self):
        report = verify(desktop_settings(), native_settings())
        self.assertEqual("pass", report["result"], report)
        self.assertEqual([], report["failed_fields"])

    def test_real_profile_drift_fails_closed(self):
        desktop = desktop_settings()
        desktop["sparse_infill_density"] = "20%"
        report = verify(desktop, native_settings())
        self.assertEqual("fail", report["result"])
        self.assertIn("fill_density", report["failed_fields"])

    def test_native_prusa_alias_is_used_for_retraction_minimum_travel(self):
        desktop = desktop_settings()
        native = native_settings()
        native["retract_before_travel"] = native.pop("retraction_minimum_travel")
        report = verify(desktop, native)
        self.assertEqual("pass", report["result"], report)
        check = next(item for item in report["checks"]
                     if item["field"] == "retraction_minimum_travel")
        self.assertEqual("retract_before_travel", check["native_key"])

    def test_bambu_project_settings_archive_is_read(self):
        with tempfile.TemporaryDirectory() as root:
            archive = Path(root) / "reference.gcode.3mf"
            with ZipFile(archive, "w", ZIP_DEFLATED) as output:
                output.writestr("Metadata/project_settings.config", json.dumps({"layer_height": 0.2}))
            self.assertEqual({"layer_height": 0.2}, read_desktop_settings(archive))

    def test_resolved_desktop_gcode_config_block_is_read(self):
        with tempfile.TemporaryDirectory() as root:
            gcode = Path(root) / "reference.gcode"
            gcode.write_text(
                "; HEADER_BLOCK_START\n"
                "; CONFIG_BLOCK_START\n"
                "; nozzle_temperature = 200\n"
                "; filament_max_volumetric_speed = 2\n"
                "; CONFIG_BLOCK_END\n"
                "; CONFIG_BLOCK_START\n"
            )
            self.assertEqual(
                {"nozzle_temperature": "200", "filament_max_volumetric_speed": "2"},
                read_desktop_gcode_settings(gcode),
            )

    def test_packaged_gcode_3mf_config_block_is_read(self):
        with tempfile.TemporaryDirectory() as root:
            archive = Path(root) / "reference.gcode.3mf"
            with ZipFile(archive, "w", ZIP_DEFLATED) as output:
                output.writestr(
                    "Metadata/auxiliary.gcode",
                    "; CONFIG_BLOCK_START\n; layer_height = 9\n; CONFIG_BLOCK_END\n",
                )
                output.writestr(
                    "Metadata/plate_1.gcode",
                    "; CONFIG_BLOCK_START\n; layer_height = 0.2\n; CONFIG_BLOCK_END\n",
                )
            self.assertEqual({"layer_height": "0.2"}, read_desktop_gcode_settings(archive))

    def test_explicit_desktop_overlays_are_merged_in_order(self):
        with tempfile.TemporaryDirectory() as root:
            base = Path(root) / "base.json"
            overlay = Path(root) / "support.json"
            base.write_text(json.dumps({"top_shell_layers": 4, "support_speed": ["80"]}))
            overlay.write_text(json.dumps({"support_speed": ["150"], "tree_support_wall_count": 0}))
            self.assertEqual(
                {"top_shell_layers": 4, "support_speed": ["150"], "tree_support_wall_count": 0},
                merged_desktop_settings(base, [overlay]),
            )

    def test_native_ini_is_bounded_to_key_value_lines(self):
        with tempfile.TemporaryDirectory() as root:
            config = Path(root) / "native.config.ini"
            config.write_text("# comment\nlayer_height = 0.2\nmalformed\n")
            self.assertEqual({"layer_height": "0.2"}, read_native_settings(config))


if __name__ == "__main__":
    unittest.main()
