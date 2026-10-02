import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ci"))

import verify_profile_snapshot  # noqa: E402
import build_path_b_prusa_config  # noqa: E402


PROFILE = ROOT / "app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json"
PROFILE_DIRECTORY = ROOT / "app/src/main/assets/profiles"


class ProfileSnapshotTests(unittest.TestCase):
    def test_every_packaged_planning_profile_has_a_declared_source_selection(self):
        profile_ids = {
            json.loads(path.read_text(encoding="utf-8"))["id"]
            for path in PROFILE_DIRECTORY.glob("*.json")
        }
        self.assertEqual(
            profile_ids,
            set(verify_profile_snapshot.SELECTIONS),
        )

    def test_auto_brim_is_explicitly_normalized_for_slicebeam(self):
        self.assertEqual(
            "no_brim",
            build_path_b_prusa_config.normalize_value("brim_type", "auto_brim"),
        )
        self.assertEqual(
            "outer_only",
            build_path_b_prusa_config.normalize_value("brim_type", "outer_only"),
        )

    def test_no_brim_policy_zeroes_legacy_width(self):
        merged = {"brim_type": "no_brim", "brim_width": "5"}
        build_path_b_prusa_config.apply_compatibility_overrides(
            merged, {"brim_type", "brim_width"}
        )
        self.assertEqual("0", merged["brim_width"])

    def _git(self, repo, *args):
        return subprocess.run(
            ["git", "-C", str(repo), *args],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        ).stdout.strip()

    def _write(self, repo, relative, value):
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def _make_orca_checkout(self, root):
        repo = root / "orca"
        repo.mkdir()
        self._git(repo, "init", "--quiet")
        machine_files = {
            "resources/profiles/BBL/machine/fdm_machine_common.json": {
                "name": "fdm_machine_common",
                "printable_area": ["0x0", "180x0", "180x180", "0x180"],
                "printable_height": "180",
            },
            "resources/profiles/BBL/machine/fdm_bbl_3dp_001_common.json": {
                "name": "fdm_bbl_3dp_001_common",
                "inherits": "fdm_machine_common",
            },
            "resources/profiles/BBL/machine/Bambu Lab A1 mini 0.4 nozzle.json": {
                "name": "Bambu Lab A1 mini 0.4 nozzle",
                "inherits": "fdm_bbl_3dp_001_common",
                "nozzle_diameter": ["0.4"],
                "printer_variant": "0.4",
                "deretraction_speed": ["30"],
                "machine_max_acceleration_e": ["5000", "5000"],
                "machine_max_acceleration_extruding": ["20000", "20000"],
                "machine_max_acceleration_retracting": ["5000", "5000"],
                "machine_max_acceleration_x": ["20000", "20000"],
                "machine_max_acceleration_y": ["20000", "20000"],
                "machine_max_acceleration_z": ["1500", "1500"],
                "machine_max_speed_e": ["30", "30"],
                "machine_max_speed_x": ["500", "200"],
                "machine_max_speed_y": ["500", "200"],
                "machine_max_speed_z": ["30", "30"],
                "max_layer_height": ["0.28"],
                "min_layer_height": ["0.08"],
                "retract_before_wipe": ["0%"],
                "retract_length_toolchange": ["2"],
                "retract_restart_extra": ["0"],
                "retract_restart_extra_toolchange": ["0"],
                "retract_when_changing_layer": ["1"],
                "retraction_length": ["0.8"],
                "retraction_speed": ["30"],
                "silent_mode": "0",
                "single_extruder_multi_material": "1",
                "wipe": ["1"],
                "machine_max_acceleration_travel": ["9000", "9000"],
                "machine_max_jerk_e": ["3", "3"],
                "machine_max_jerk_x": ["9", "9"],
                "machine_max_jerk_y": ["9", "9"],
                "machine_max_jerk_z": ["5", "5"],
                "machine_min_extruding_rate": ["0", "0"],
                "machine_min_travel_rate": ["0", "0"],
                "printer_model": "Bambu Lab A1 mini",
                "retraction_minimum_travel": ["1"],
                "retract_lift_above": ["0"],
                "retract_lift_below": ["179"],
                "z_hop": ["0.4"],
                "z_hop_types": ["Auto Lift"],
            },
        }
        process_files = {
            "resources/profiles/BBL/process/fdm_process_common.json": {
                "name": "fdm_process_common",
                "line_width": "0.42",
                "support_line_width": "0.42",
            },
            "resources/profiles/BBL/process/fdm_process_single_common.json": {
                "name": "fdm_process_single_common",
                "inherits": "fdm_process_common",
            },
            "resources/profiles/BBL/process/fdm_process_single_0.20.json": {
                "name": "fdm_process_single_0.20",
                "inherits": "fdm_process_single_common",
                "layer_height": ["0.2"],
                "initial_layer_print_height": ["0.2"],
                "initial_layer_line_width": "0.5",
                "sparse_infill_density": ["15%"],
                "sparse_infill_line_width": "0.45",
                "wall_loops": ["2"],
                "top_shell_layers": ["5"],
                "top_shell_thickness": "1.0",
                "bottom_shell_layers": ["3"],
                "bottom_shell_thickness": "0",
                "outer_wall_line_width": "0.42",
                "inner_wall_line_width": "0.45",
                "internal_solid_infill_line_width": "0.42",
                "top_surface_line_width": "0.42",
                "enable_support": ["0"],
                "enable_overhang_speed": ["1"],
                "support_threshold_angle": ["30"],
                "travel_speed": ["700"],
                "outer_wall_speed": ["200"],
                "inner_wall_speed": ["300"],
                "sparse_infill_speed": ["270"],
                "initial_layer_speed": ["50"],
            },
            "resources/profiles/BBL/process/0.20mm Standard @BBL A1M.json": {
                "name": "0.20mm Standard @BBL A1M",
                "inherits": "fdm_process_single_0.20",
                "bridge_speed": ["50"],
                "bridge_flow": "1",
                "bridge_no_support": "0",
                "bottom_surface_pattern": "monotonic",
                "brim_width": "5",
                "brim_object_gap": "0.1",
                "print_sequence": "by layer",
                "default_acceleration": ["6000"],
                "sparse_infill_acceleration": ["100%"],
                "sparse_infill_anchor": ["400%"],
                "sparse_infill_anchor_max": ["20"],
                "draft_shield": "disabled",
                "elefant_foot_compensation": "0",
                "gap_infill_speed": ["250"],
                "initial_layer_acceleration": ["500"],
                "initial_layer_infill_speed": ["105"],
                "initial_layer_travel_acceleration": ["6000"],
                "enable_arc_fitting": "1",
                "inner_wall_acceleration": ["0"],
                "outer_wall_acceleration": ["5000"],
                "overhang_1_4_speed": ["0"],
                "overhang_2_4_speed": ["50"],
                "overhang_3_4_speed": ["30"],
                "overhang_4_4_speed": ["10"],
                "only_one_wall_top": "1",
                "infill_wall_overlap": "15%",
                "interface_shells": "0",
                "ironing_spacing": "0.15",
                "ironing_speed": "30",
                "ironing_type": "no ironing",
                "ironing_flow": "10%",
                "raft_layers": "0",
                "resolution": "0.012",
                "seam_position": "aligned",
                "skirt_distance": "2",
                "skirt_height": "1",
                "skirt_loops": "0",
                "sparse_infill_pattern": "crosshatch",
                "infill_direction": "45",
                "standby_temperature_delta": "-5",
                "support_type": "tree(auto)",
                "support_interface_top_layers": "2",
                "support_interface_pattern": "auto",
                "support_interface_spacing": "0.5",
                "support_interface_speed": ["80"],
                "support_interface_bottom_layers": "2",
                "support_bottom_z_distance": "0.2",
                "support_base_pattern": "rectilinear",
                "support_base_pattern_spacing": "2.5",
                "support_object_xy_distance": "0.35",
                "support_speed": ["150"],
                "support_on_build_plate_only": "0",
                "support_remove_small_overhang": "1",
                "support_top_z_distance": "0.2",
                "tree_support_branch_angle": "45",
                "tree_support_branch_diameter": "2",
                "tree_support_wall_count": "0",
                "detect_thin_wall": "0",
                "top_surface_acceleration": ["2000"],
                "internal_solid_infill_acceleration": ["2000"],
                "wall_generator": "classic",
                "travel_acceleration": ["10000"],
                "wipe_tower_no_sparse_layers": "0",
                "small_perimeter_speed": ["50%"],
                "internal_solid_infill_speed": ["250"],
                "minimum_sparse_infill_area": ["15"],
                "top_surface_speed": ["200"],
                "top_surface_pattern": "monotonicline",
                "travel_speed_z": ["0"],
                "spiral_mode": "0",
            },
        }
        filament_files = {
            "resources/profiles/BBL/filament/fdm_filament_common.json": {
                "name": "fdm_filament_common",
            },
            "resources/profiles/BBL/filament/fdm_filament_pla.json": {
                "name": "fdm_filament_pla",
                "inherits": "fdm_filament_common",
            },
            "resources/profiles/BBL/filament/Bambu PLA Basic @base.json": {
                "name": "Bambu PLA Basic @base",
                "inherits": "fdm_filament_pla",
                "filament_diameter": ["1.75"],
                "nozzle_temperature": ["220"],
                "nozzle_temperature_initial_layer": ["220"],
                "hot_plate_temp": ["60"],
                "hot_plate_temp_initial_layer": ["60"],
                "filament_flow_ratio": ["0.98"],
                "filament_max_volumetric_speed": ["21"],
            },
            "resources/profiles/BBL/filament/Bambu PLA Basic @BBL A1M.json": {
                "name": "Bambu PLA Basic @BBL A1M",
                "inherits": "Bambu PLA Basic @base",
                "fan_min_speed": ["60"],
                "fan_max_speed": ["80"],
                "chamber_temperatures": ["0"],
                "filament_cost": ["24.99"],
                "filament_density": ["1.26"],
                "filament_max_volumetric_speed": ["21"],
                "filament_minimal_purge_on_wipe_tower": ["15"],
                "filament_soluble": ["0"],
                "filament_vendor": ["Bambu Lab"],
                "fan_cooling_layer_time": ["80"],
                "full_fan_speed_layer": ["0"],
                "overhang_fan_speed": ["100"],
                "slow_down_layer_time": ["6"],
                "slow_down_min_speed": ["20"],
            },
        }
        for relative, value in {**machine_files, **process_files, **filament_files}.items():
            self._write(repo, relative, value)
        self._git(repo, "add", ".")
        subprocess.run(
            [
                "git", "-C", str(repo), "-c", "user.name=Alloy tests", "-c",
                "user.email=alloy-tests@example.invalid", "commit", "--quiet", "-m", "fixture",
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return repo, self._git(repo, "rev-parse", "HEAD")

    def test_pinned_projection_matches_resolved_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            repo, revision = self._make_orca_checkout(Path(directory))
            profile = json.loads(PROFILE.read_text(encoding="utf-8"))
            profile["provenance"]["revision"] = revision
            for source in profile["provenance"]["files"]:
                source["sha"] = self._git(repo, "rev-parse", f"{revision}:{source['path']}")
            profile_path = Path(directory) / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")

            report = verify_profile_snapshot.verify(repo, profile_path)

            self.assertEqual(report["blob_count"], 11)
            self.assertEqual(report["projected_field_count"], 26)
            self.assertEqual(
                report["native_projected_field_count"],
                len(verify_profile_snapshot.NATIVE_PROJECTIONS),
            )

    def test_rejects_projection_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            repo, revision = self._make_orca_checkout(Path(directory))
            profile = json.loads(PROFILE.read_text(encoding="utf-8"))
            profile["provenance"]["revision"] = revision
            for source in profile["provenance"]["files"]:
                source["sha"] = self._git(repo, "rev-parse", f"{revision}:{source['path']}")
            profile["process"]["infill_percent"] = 16.0
            profile_path = Path(directory) / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "process.infill_percent"):
                verify_profile_snapshot.verify(repo, profile_path)

    def test_rejects_native_projection_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            repo, revision = self._make_orca_checkout(Path(directory))
            profile = json.loads(PROFILE.read_text(encoding="utf-8"))
            profile["provenance"]["revision"] = revision
            for source in profile["provenance"]["files"]:
                source["sha"] = self._git(repo, "rev-parse", f"{revision}:{source['path']}")
            profile["native_settings"]["ironing"] = "1"
            profile_path = Path(directory) / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "native_settings.ironing"):
                verify_profile_snapshot.verify(repo, profile_path)

    def test_rejects_unverified_native_field(self):
        with tempfile.TemporaryDirectory() as directory:
            repo, revision = self._make_orca_checkout(Path(directory))
            profile = json.loads(PROFILE.read_text(encoding="utf-8"))
            profile["provenance"]["revision"] = revision
            for source in profile["provenance"]["files"]:
                source["sha"] = self._git(repo, "rev-parse", f"{revision}:{source['path']}")
            profile["native_settings"]["unverified_setting"] = "1"
            profile_path = Path(directory) / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "unverified_setting"):
                verify_profile_snapshot.verify(repo, profile_path)


if __name__ == "__main__":
    unittest.main()
