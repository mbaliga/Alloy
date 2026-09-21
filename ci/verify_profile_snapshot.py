#!/usr/bin/env python3
"""Verify Alloy's compact A1 Mini profile against a pinned Orca checkout.

The Android profile is intentionally a small, typed projection of the full
Orca profile chain. This verifier proves two things before a release build:
the listed source blobs still exist at the declared commit and every projected
value still equals the resolved source value. It does not claim G-code parity.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
from pathlib import Path


SELECTION = {
    "machine": "Bambu Lab A1 mini 0.4 nozzle",
    "process": "0.20mm Standard @BBL A1M",
    "filament": "Bambu PLA Basic @BBL A1M",
}
KINDS = {"machine": "machine", "process": "process", "filament": "filament"}

# Native scalar values intentionally kept outside the typed Android recipe.
# These are the source-backed compatibility settings that are projected into
# SliceBeam's Prusa vocabulary. G-code templates and typed fields remain
# Alloy-owned and are therefore not included here.
NATIVE_PROJECTIONS = {
    "printer_variant": ("machine", "printer_variant"),
    "deretract_speed": ("machine", "deretraction_speed"),
    "machine_max_acceleration_e": ("machine", "machine_max_acceleration_e"),
    "machine_max_acceleration_extruding": ("machine", "machine_max_acceleration_extruding"),
    "machine_max_acceleration_retracting": ("machine", "machine_max_acceleration_retracting"),
    "machine_max_acceleration_x": ("machine", "machine_max_acceleration_x"),
    "machine_max_acceleration_y": ("machine", "machine_max_acceleration_y"),
    "machine_max_acceleration_z": ("machine", "machine_max_acceleration_z"),
    "machine_max_feedrate_e": ("machine", "machine_max_speed_e"),
    "machine_max_feedrate_x": ("machine", "machine_max_speed_x"),
    "machine_max_feedrate_y": ("machine", "machine_max_speed_y"),
    "machine_max_feedrate_z": ("machine", "machine_max_speed_z"),
    "max_layer_height": ("machine", "max_layer_height"),
    "min_layer_height": ("machine", "min_layer_height"),
    "retract_before_wipe": ("machine", "retract_before_wipe"),
    "retract_before_travel": ("machine", "retraction_minimum_travel"),
    "retract_length_toolchange": ("machine", "retract_length_toolchange"),
    "retract_lift": ("machine", "z_hop"),
    "z_hop_types": ("machine", "z_hop_types"),
    "retract_restart_extra": ("machine", "retract_restart_extra"),
    "retract_restart_extra_toolchange": ("machine", "retract_restart_extra_toolchange"),
    "retract_layer_change": ("machine", "retract_when_changing_layer"),
    "retract_length": ("machine", "retraction_length"),
    "retract_speed": ("machine", "retraction_speed"),
    "silent_mode": ("machine", "silent_mode"),
    "single_extruder_multi_material": ("machine", "single_extruder_multi_material"),
    "wipe": ("machine", "wipe"),
    "machine_max_acceleration_travel": ("machine", "machine_max_acceleration_travel"),
    "machine_max_jerk_e": ("machine", "machine_max_jerk_e"),
    "machine_max_jerk_x": ("machine", "machine_max_jerk_x"),
    "machine_max_jerk_y": ("machine", "machine_max_jerk_y"),
    "machine_max_jerk_z": ("machine", "machine_max_jerk_z"),
    "machine_min_extruding_rate": ("machine", "machine_min_extruding_rate"),
    "machine_min_travel_rate": ("machine", "machine_min_travel_rate"),
    "printer_model": ("machine", "printer_model"),
    "retract_lift_above": ("machine", "retract_lift_above"),
    "retract_lift_below": ("machine", "retract_lift_below"),
    "bridge_speed": ("process", "bridge_speed"),
    "bridge_flow_ratio": ("process", "bridge_flow"),
    "dont_support_bridges": ("process", "bridge_no_support"),
    "bottom_solid_min_thickness": ("process", "bottom_shell_thickness"),
    "bottom_fill_pattern": ("process", "bottom_surface_pattern"),
    "brim_width": ("process", "brim_width"),
    "default_acceleration": ("process", "default_acceleration"),
    "outer_wall_jerk": ("process", "outer_wall_jerk"),
    "inner_wall_jerk": ("process", "inner_wall_jerk"),
    "infill_jerk": ("process", "infill_jerk"),
    "top_surface_jerk": ("process", "top_surface_jerk"),
    "initial_layer_jerk": ("process", "initial_layer_jerk"),
    "travel_jerk": ("process", "travel_jerk"),
    "infill_acceleration": ("process", "sparse_infill_acceleration"),
    "infill_anchor": ("process", "sparse_infill_anchor"),
    "infill_anchor_max": ("process", "sparse_infill_anchor_max"),
    "draft_shield": ("process", "draft_shield"),
    "elefant_foot_compensation": ("process", "elefant_foot_compensation"),
    "external_perimeter_extrusion_width": ("process", "outer_wall_line_width"),
    "extrusion_width": ("process", "line_width"),
    "external_perimeter_acceleration": ("process", "outer_wall_acceleration"),
    "first_layer_extrusion_width": ("process", "initial_layer_line_width"),
    "first_layer_acceleration": ("process", "initial_layer_acceleration"),
    "initial_layer_acceleration": ("process", "initial_layer_acceleration"),
    "initial_layer_infill_speed": ("process", "initial_layer_infill_speed"),
    "initial_layer_travel_acceleration": ("process", "initial_layer_travel_acceleration"),
    "gap_fill_speed": ("process", "gap_infill_speed"),
    "enable_dynamic_overhang_speeds": ("process", "enable_overhang_speed"),
    "infill_overlap": ("process", "infill_wall_overlap"),
    "interface_shells": ("process", "interface_shells"),
    "ironing_spacing": ("process", "ironing_spacing"),
    "ironing_speed": ("process", "ironing_speed"),
    "ironing": ("process", "ironing_type"),
    "raft_layers": ("process", "raft_layers"),
    "resolution": ("process", "resolution"),
    "perimeter_generator": ("process", "wall_generator"),
    "perimeter_acceleration": ("process", "inner_wall_acceleration"),
    "inner_wall_acceleration": ("process", "inner_wall_acceleration"),
    "outer_wall_acceleration": ("process", "outer_wall_acceleration"),
    "sparse_infill_acceleration": ("process", "sparse_infill_acceleration"),
    "perimeter_extrusion_width": ("process", "inner_wall_line_width"),
    "seam_position": ("process", "seam_position"),
    "overhang_speed_0": ("process", "overhang_1_4_speed"),
    "overhang_speed_1": ("process", "overhang_2_4_speed"),
    "overhang_speed_2": ("process", "overhang_3_4_speed"),
    "overhang_speed_3": ("process", "overhang_4_4_speed"),
    "top_one_perimeter_type": ("process", "only_one_wall_top"),
    "skirt_distance": ("process", "skirt_distance"),
    "skirt_height": ("process", "skirt_height"),
    "skirts": ("process", "skirt_loops"),
    "fill_pattern": ("process", "sparse_infill_pattern"),
    "fill_angle": ("process", "infill_direction"),
    "standby_temperature_delta": ("process", "standby_temperature_delta"),
    "support_material_style": ("process", "support_type"),
    "travel_acceleration": ("process", "travel_acceleration"),
    "wipe_tower_no_sparse_layers": ("process", "wipe_tower_no_sparse_layers"),
    "small_perimeter_speed": ("process", "small_perimeter_speed"),
    "solid_infill_speed": ("process", "internal_solid_infill_speed"),
    "solid_infill_below_area": ("process", "minimum_sparse_infill_area"),
    "solid_infill_extrusion_width": ("process", "internal_solid_infill_line_width"),
    "infill_extrusion_width": ("process", "sparse_infill_line_width"),
    "support_material_buildplate_only": ("process", "support_on_build_plate_only"),
    "support_material_pattern": ("process", "support_base_pattern"),
    "support_material_spacing": ("process", "support_base_pattern_spacing"),
    "support_material_bottom_contact_distance": ("process", "support_bottom_z_distance"),
    "support_material_bottom_interface_layers": ("process", "support_interface_bottom_layers"),
    "support_material_contact_distance": ("process", "support_top_z_distance"),
    "support_material_interface_layers": ("process", "support_interface_top_layers"),
    "support_material_interface_pattern": ("process", "support_interface_pattern"),
    "support_material_interface_spacing": ("process", "support_interface_spacing"),
    "support_material_interface_speed": ("process", "support_interface_speed"),
    "support_material_extrusion_width": ("process", "support_line_width"),
    "support_material_speed": ("process", "support_speed"),
    "support_material_xy_spacing": ("process", "support_object_xy_distance"),
    "support_remove_small_overhang": ("process", "support_remove_small_overhang"),
    "support_tree_angle": ("process", "tree_support_branch_angle"),
    "support_tree_branch_diameter": ("process", "tree_support_branch_diameter"),
    "support_tree_branch_diameter_double_wall": ("process", "tree_support_wall_count"),
    "thin_walls": ("process", "detect_thin_wall"),
    "top_solid_infill_speed": ("process", "top_surface_speed"),
    "top_solid_infill_acceleration": ("process", "top_surface_acceleration"),
    "top_surface_acceleration": ("process", "top_surface_acceleration"),
    "internal_solid_infill_acceleration": ("process", "internal_solid_infill_acceleration"),
    "top_solid_min_thickness": ("process", "top_shell_thickness"),
    "top_infill_extrusion_width": ("process", "top_surface_line_width"),
    "top_fill_pattern": ("process", "top_surface_pattern"),
    "travel_speed_z": ("process", "travel_speed_z"),
    "chamber_temperature": ("filament", "chamber_temperatures"),
    "filament_cost": ("filament", "filament_cost"),
    "filament_density": ("filament", "filament_density"),
    "filament_minimal_purge_on_wipe_tower": ("filament", "filament_minimal_purge_on_wipe_tower"),
    "filament_soluble": ("filament", "filament_soluble"),
    "filament_vendor": ("filament", "filament_vendor"),
    "fan_below_layer_time": ("filament", "fan_cooling_layer_time"),
    "full_fan_speed_layer": ("filament", "full_fan_speed_layer"),
    "bridge_fan_speed": ("filament", "overhang_fan_speed"),
    "slowdown_below_layer_time": ("filament", "slow_down_layer_time"),
    "min_print_speed": ("filament", "slow_down_min_speed"),
    "filament_max_volumetric_speed": ("filament", "filament_max_volumetric_speed"),
    "arc_fitting": ("process", "enable_arc_fitting"),
    "ironing_flowrate": ("process", "ironing_flow"),
    "spiral_vase": ("process", "spiral_mode"),
    "brim_separation": ("process", "brim_object_gap"),
    "complete_objects": ("process", "print_sequence"),
}

NATIVE_VALUE_ALIASES = {
    ("process", "enable_arc_fitting"): {"1": "emit_center", "0": "disabled"},
    ("process", "wall_sequence"): {
        "inner wall/outer wall": "0",
        "outer wall/inner wall": "1",
    },
    ("process", "ironing_type"): {"no ironing": "0"},
    ("process", "sparse_infill_pattern"): {"crosshatch": "grid"},
    ("process", "support_type"): {"tree(auto)": "organic"},
    ("process", "top_surface_pattern"): {"monotonicline": "monotoniclines"},
    ("process", "support_base_pattern"): {"default": "rectilinear"},
    ("process", "only_one_wall_top"): {"1": "top", "true": "top", "0": "none", "false": "none"},
    ("process", "print_sequence"): {"by layer": "0", "by object": "1"},
}


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout.strip()


def load_profiles(root: Path, kind: str) -> dict[str, dict]:
    directory = root / "resources/profiles/BBL" / KINDS[kind]
    profiles: dict[str, dict] = {}
    if not directory.is_dir():
        raise ValueError(f"missing Orca profile directory: {directory}")
    for path in sorted(directory.rglob("*.json")):
        try:
            value = json.loads(path.read_text(encoding="utf-8", errors="strict"))
        except json.JSONDecodeError:
            continue
        name = value.get("name")
        if isinstance(name, str) and name.strip():
            profiles[name] = value
    return profiles


def resolve(name: str, profiles: dict[str, dict], stack: tuple[str, ...] = ()) -> dict:
    if name in stack:
        raise ValueError("profile inheritance cycle: " + " -> ".join((*stack, name)))
    if name not in profiles:
        raise ValueError(f"missing inherited profile: {name}")
    value = profiles[name]
    result: dict = {}
    parents = value.get("inherits", [])
    if isinstance(parents, str):
        parents = [parents]
    if not isinstance(parents, list):
        raise ValueError(f"invalid inherits value in {name}")
    for parent in parents:
        if parent:
            result.update(resolve(str(parent), profiles, (*stack, name)))
    result.update({key: item for key, item in value.items() if key != "inherits"})
    return result


def value(data: dict, key: str) -> object:
    if key not in data:
        raise ValueError(f"resolved profile is missing {key}")
    result = data[key]
    if isinstance(result, list):
        if not result:
            raise ValueError(f"resolved profile has an empty {key}")
        return result[0]
    return result


def native_string(data: dict, key: str) -> str:
    if key not in data:
        raise ValueError(f"resolved profile is missing {key}")
    raw = data[key]
    if isinstance(raw, bool):
        return "1" if raw else "0"
    if raw is None:
        return ""
    if isinstance(raw, list):
        return ",".join(str(item) for item in raw)
    return str(raw)


def normalize_native_projection(target: str, kind: str, source_key: str,
                                value: str, resolved: dict[str, dict]) -> str:
    # Orca expresses sparse-infill acceleration relative to the process
    # default; SliceBeam/Prusa expects an absolute mm/s² value.
    if target == "infill_acceleration" and kind == "process" and source_key == "sparse_infill_acceleration":
        if value.strip().endswith("%"):
            percent = float(value.strip()[:-1])
            default = float(native_string(resolved["process"], "default_acceleration"))
            resolved_value = default * percent / 100.0
            return str(int(resolved_value)) if resolved_value.is_integer() else f"{resolved_value:.6f}".rstrip("0").rstrip(".")
    return value


def verify_native_projection(asset: dict, resolved: dict[str, dict]) -> list[dict[str, str]]:
    native = asset.get("native_settings")
    if not isinstance(native, dict):
        raise ValueError("native_settings must be an object")
    unknown = sorted(set(native) - set(NATIVE_PROJECTIONS))
    if unknown:
        raise ValueError("native_settings contains unverified fields: " + ", ".join(unknown))
    checks: list[dict[str, str]] = []
    for target, (kind, source_key) in NATIVE_PROJECTIONS.items():
        expected = native_string(resolved[kind], source_key)
        expected = normalize_native_projection(target, kind, source_key, expected, resolved)
        expected = NATIVE_VALUE_ALIASES.get((kind, source_key), {}).get(expected, expected)
        actual = native.get(target)
        if not isinstance(actual, str) or actual != expected:
            raise ValueError(f"native_settings.{target}: asset={actual!r}, source={expected!r}")
        checks.append({"target": target, "profile": kind, "source": source_key, "value": expected})
    return checks


def number(data: dict, key: str) -> float:
    raw = value(data, key)
    if isinstance(raw, bool):
        raise ValueError(f"{key} is boolean, expected number")
    text = str(raw).strip().rstrip("%")
    try:
        result = float(text)
    except ValueError as error:
        raise ValueError(f"{key} is not numeric: {raw!r}") from error
    if not math.isfinite(result):
        raise ValueError(f"{key} is not finite")
    return result


def boolean(data: dict, key: str) -> bool:
    raw = value(data, key)
    if isinstance(raw, bool):
        return raw
    normalized = str(raw).strip().lower()
    if normalized in {"1", "true", "yes"}:
        return True
    if normalized in {"0", "false", "no"}:
        return False
    raise ValueError(f"{key} is not boolean: {raw!r}")


def bed_size(machine: dict) -> tuple[float, float]:
    raw = machine.get("printable_area")
    if not isinstance(raw, list) or not raw:
        raise ValueError("printable_area is missing or not a list")
    points: list[tuple[float, float]] = []
    for item in raw:
        try:
            x, y = str(item).split("x", 1)
            points.append((float(x), float(y)))
        except (ValueError, TypeError) as error:
            raise ValueError(f"invalid printable_area point: {item!r}") from error
    return max(x for x, _ in points) - min(x for x, _ in points), max(y for _, y in points) - min(y for _, y in points)


def close(actual: object, expected: object, label: str) -> None:
    if isinstance(actual, bool) or isinstance(expected, bool):
        if actual != expected:
            raise ValueError(f"{label}: asset={actual!r}, source={expected!r}")
        return
    if not math.isclose(float(actual), float(expected), rel_tol=0.0, abs_tol=0.0001):
        raise ValueError(f"{label}: asset={actual!r}, source={expected!r}")


def verify(repo: Path, profile_path: Path) -> dict:
    asset = json.loads(profile_path.read_text(encoding="utf-8"))
    provenance = asset.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("profile provenance is missing")
    revision = str(provenance.get("revision", ""))
    if len(revision) != 40:
        raise ValueError("profile provenance revision is not a commit SHA")
    git(repo, "rev-parse", "--verify", f"{revision}^{{commit}}")

    blob_checks: list[dict[str, str]] = []
    for source in provenance.get("files", []):
        if not isinstance(source, dict):
            raise ValueError("profile provenance contains a non-object file entry")
        path = str(source.get("path", ""))
        expected_sha = str(source.get("sha", "")).lower()
        if not path or len(expected_sha) != 40:
            raise ValueError(f"invalid provenance entry: {source!r}")
        actual_sha = git(repo, "rev-parse", f"{revision}:{path}").lower()
        if actual_sha != expected_sha:
            raise ValueError(f"provenance mismatch for {path}: asset={expected_sha}, source={actual_sha}")
        blob_checks.append({"path": path, "sha": actual_sha})

    resolved = {
        kind: resolve(SELECTION[kind], load_profiles(repo, kind))
        for kind in SELECTION
    }
    machine, process, filament = resolved["machine"], resolved["process"], resolved["filament"]
    bed_x, bed_y = bed_size(machine)
    printer = asset["printer"]
    process_asset = asset["process"]
    filament_asset = asset["filament"]

    comparisons = {
        "printer.bed_x_mm": (printer["bed_x_mm"], bed_x),
        "printer.bed_y_mm": (printer["bed_y_mm"], bed_y),
        "printer.build_z_mm": (printer["build_z_mm"], number(machine, "printable_height")),
        "printer.nozzle_mm": (printer["nozzle_mm"], number(machine, "nozzle_diameter")),
        "process.layer_height_mm": (process_asset["layer_height_mm"], number(process, "layer_height")),
        "process.first_layer_height_mm": (process_asset["first_layer_height_mm"], number(process, "initial_layer_print_height")),
        "process.infill_percent": (process_asset["infill_percent"], number(process, "sparse_infill_density")),
        "process.perimeters": (process_asset["perimeters"], number(process, "wall_loops")),
        "process.top_layers": (process_asset["top_layers"], number(process, "top_shell_layers")),
        "process.bottom_layers": (process_asset["bottom_layers"], number(process, "bottom_shell_layers")),
        "process.support_enabled": (process_asset["support_enabled"], boolean(process, "enable_support")),
        "process.support_threshold_degrees": (process_asset["support_threshold_degrees"], number(process, "support_threshold_angle")),
        "process.travel_speed_mm_s": (process_asset["travel_speed_mm_s"], number(process, "travel_speed")),
        "process.outer_wall_speed_mm_s": (process_asset["outer_wall_speed_mm_s"], number(process, "outer_wall_speed")),
        "process.inner_wall_speed_mm_s": (process_asset["inner_wall_speed_mm_s"], number(process, "inner_wall_speed")),
        "process.infill_speed_mm_s": (process_asset["infill_speed_mm_s"], number(process, "sparse_infill_speed")),
        "process.initial_layer_speed_mm_s": (process_asset["initial_layer_speed_mm_s"], number(process, "initial_layer_speed")),
        "filament.diameter_mm": (filament_asset["diameter_mm"], number(filament, "filament_diameter")),
        "filament.nozzle_temperature_c": (filament_asset["nozzle_temperature_c"], number(filament, "nozzle_temperature")),
        "filament.first_layer_nozzle_temperature_c": (filament_asset["first_layer_nozzle_temperature_c"], number(filament, "nozzle_temperature_initial_layer")),
        "filament.bed_temperature_c": (filament_asset["bed_temperature_c"], number(filament, "hot_plate_temp")),
        "filament.first_layer_bed_temperature_c": (filament_asset["first_layer_bed_temperature_c"], number(filament, "hot_plate_temp_initial_layer")),
        "filament.flow_ratio": (filament_asset["flow_ratio"], number(filament, "filament_flow_ratio")),
        "filament.max_volumetric_speed_mm3_s": (filament_asset["max_volumetric_speed_mm3_s"], number(filament, "filament_max_volumetric_speed")),
        "filament.fan_min_percent": (filament_asset["fan_min_percent"], number(filament, "fan_min_speed")),
        "filament.fan_max_percent": (filament_asset["fan_max_percent"], number(filament, "fan_max_speed")),
    }
    for label, (actual, expected) in comparisons.items():
        close(actual, expected, label)
    native_checks = verify_native_projection(asset, resolved)
    return {
        "revision": revision,
        "profile": str(profile_path),
        "blob_count": len(blob_checks),
        "projected_field_count": len(comparisons),
        "native_projected_field_count": len(native_checks),
        "native_projection": native_checks,
        "blobs": blob_checks,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("orca_checkout", type=Path)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    report = verify(args.orca_checkout, args.profile)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(
        f"verified {report['projected_field_count']} typed and "
        f"{report['native_projected_field_count']} native projected A1 Mini fields and "
        f"{report['blob_count']} provenance blobs at {report['revision']}"
    )


if __name__ == "__main__":
    main()
