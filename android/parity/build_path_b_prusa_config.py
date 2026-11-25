#!/usr/bin/env python3
"""Translate resolved Orca A1 Mini JSON profiles into a Prusa/SliceBeam INI.

This is the deterministic Alloy replacement for SliceBeam's experimental live
Orca profile importer. It never fetches profiles from `main` and applies the
same known compatibility renames plus explicit A1 Mini mappings discovered by
G4. Unsupported fields are reported, never silently treated as supported.

The resulting file is intended for both desktop PrusaSlicer 2.8.0 and the pinned
SliceBeam/Prusa 2.8 native engine. Passing desktop Prusa parsing/slicing is a
precondition for using it in the Android runtime parity gate.
"""

from __future__ import annotations

from collections import OrderedDict
import argparse
import json
import math
from pathlib import Path
import re

KEY_MAP = {
    # SliceBeam's existing Orca importer mappings.
    "machine_start_gcode": "start_gcode",
    "machine_end_gcode": "end_gcode",
    "printable_area": "bed_shape",
    "printable_height": "max_print_height",
    "layer_change_gcode": "layer_gcode",
    "before_layer_change_gcode": "before_layer_gcode",
    "filament_start_gcode": "start_filament_gcode",
    "filament_end_gcode": "end_filament_gcode",
    "retraction_minimum_level": "retract_before_travel",
    "retraction_minimum_travel": "retract_before_travel",
    "retraction_length": "retract_length",
    "retraction_speed": "retract_speed",
    "deretraction_speed": "deretract_speed",
    "z_hop": "retract_lift",
    "change_filament_gcode": "pause_print_gcode",
    "nozzle_temperature": "temperature",
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "hot_plate_temp": "bed_temperature",
    "hot_plate_temp_initial_layer": "first_layer_bed_temperature",
    "filament_flow_ratio": "extrusion_multiplier",
    "chamber_temperatures": "chamber_temperature",
    "fan_max_speed": "max_fan_speed",
    "fan_min_speed": "min_fan_speed",
    "overhang_fan_speed": "bridge_fan_speed",
    "slow_down_layer_time": "slowdown_below_layer_time",
    "slow_down_min_speed": "min_print_speed",
    "bridge_flow": "bridge_flow_ratio",
    "bridge_no_support": "dont_support_bridges",
    "bottom_surface_pattern": "bottom_fill_pattern",
    "detect_thin_wall": "thin_walls",
    "enable_overhang_speed": "enable_dynamic_overhang_speeds",
    "gap_infill_speed": "gap_fill_speed",
    "infill_wall_overlap": "infill_overlap",
    "initial_layer_acceleration": "first_layer_acceleration",
    "inner_wall_acceleration": "perimeter_acceleration",
    "outer_wall_acceleration": "external_perimeter_acceleration",
    "line_width": "extrusion_width",
    "overhang_1_4_speed": "overhang_speed_0",
    "overhang_2_4_speed": "overhang_speed_1",
    "overhang_3_4_speed": "overhang_speed_2",
    "overhang_4_4_speed": "overhang_speed_3",
    "only_one_wall_top": "top_one_perimeter_type",
    "retract_when_changing_layer": "retract_layer_change",
    "skirt_loops": "skirts",
    "support_bottom_z_distance": "support_material_bottom_contact_distance",
    "support_base_pattern": "support_material_pattern",
    "support_base_pattern_spacing": "support_material_spacing",
    "support_interface_bottom_layers": "support_material_bottom_interface_layers",
    "support_interface_pattern": "support_material_interface_pattern",
    "support_interface_spacing": "support_material_interface_spacing",
    "support_interface_speed": "support_material_interface_speed",
    "support_line_width": "support_material_extrusion_width",
    "support_interface_top_layers": "support_material_interface_layers",
    "support_on_build_plate_only": "support_material_buildplate_only",
    "support_object_xy_distance": "support_material_xy_spacing",
    "support_speed": "support_material_speed",
    "support_top_z_distance": "support_material_contact_distance",
    "tree_support_branch_angle": "support_tree_angle",
    "tree_support_branch_diameter": "support_tree_branch_diameter",
    "tree_support_branch_distance": "support_tree_branch_distance",
    # Bambu's zero tree-support wall count is represented by Prusa's explicit
    # zero threshold, which disables double walls on organic branches.
    "tree_support_wall_count": "support_tree_branch_diameter_double_wall",
    "top_surface_acceleration": "top_solid_infill_acceleration",
    "sparse_infill_acceleration": "infill_acceleration",
    "top_surface_pattern": "top_fill_pattern",
    "fan_cooling_layer_time": "fan_below_layer_time",

    # Explicit Orca -> Prusa 2.8 equivalents required for A1 Mini fidelity.
    "machine_max_speed_x": "machine_max_feedrate_x",
    "machine_max_speed_y": "machine_max_feedrate_y",
    "machine_max_speed_z": "machine_max_feedrate_z",
    "machine_max_speed_e": "machine_max_feedrate_e",
    "wall_loops": "perimeters",
    "wall_generator": "perimeter_generator",
    "sparse_infill_density": "fill_density",
    "sparse_infill_pattern": "fill_pattern",
    # Orca's base infill direction is the Prusa/SliceBeam fill angle. This is
    # a direct geometric equivalent, unlike the separate wall-order fields.
    "infill_direction": "fill_angle",
    "enable_support": "support_material",
    "support_type": "support_material_style",
    "support_threshold_angle": "support_material_threshold",
    "outer_wall_speed": "external_perimeter_speed",
    "inner_wall_speed": "perimeter_speed",
    "sparse_infill_speed": "infill_speed",
    "internal_solid_infill_speed": "solid_infill_speed",
    "minimum_sparse_infill_area": "solid_infill_below_area",
    "top_surface_speed": "top_solid_infill_speed",
    "initial_layer_speed": "first_layer_speed",
    "initial_layer_print_height": "first_layer_height",
    # Bambu stores this as a per-filament cap. SliceBeam exposes both the
    # per-filament array and a global object cap; preserve the source scope.
    "filament_max_volumetric_speed": "filament_max_volumetric_speed",
    "top_shell_layers": "top_solid_layers",
    "bottom_shell_layers": "bottom_solid_layers",
    "top_shell_thickness": "top_solid_min_thickness",
    "bottom_shell_thickness": "bottom_solid_min_thickness",
    "outer_wall_line_width": "external_perimeter_extrusion_width",
    "inner_wall_line_width": "perimeter_extrusion_width",
    "sparse_infill_line_width": "infill_extrusion_width",
    "internal_solid_infill_line_width": "solid_infill_extrusion_width",
    "top_surface_line_width": "top_infill_extrusion_width",
    "initial_layer_line_width": "first_layer_extrusion_width",
    # Exact one-to-one FFF settings shared by the pinned Orca and
    # Prusa/SliceBeam vocabularies.
    "enable_arc_fitting": "arc_fitting",
    "ironing_flow": "ironing_flowrate",
    "spiral_mode": "spiral_vase",
    "brim_object_gap": "brim_separation",
    "print_sequence": "complete_objects",
}

META_KEYS = {
    "type", "name", "from", "instantiation", "setting_id", "description",
    "compatible_printers", "compatible_printers_condition",
    "compatible_prints", "compatible_prints_condition",
}

GCODE_KEYS = {
    "start_gcode", "end_gcode", "layer_gcode", "before_layer_gcode",
    "start_filament_gcode", "end_filament_gcode", "pause_print_gcode",
    "toolchange_gcode", "color_change_gcode",
}

# SliceBeam already performs these two placeholder aliases. Keep them explicit
# so the generated config is the same on desktop and Android.
TEMPLATE_ALIASES = {
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "bed_temperature_initial_layer_single": "first_layer_bed_temperature",
}


def supported_keys(slice_repo: Path) -> set[str]:
    cpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.cpp").read_text(errors="replace")
    hpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.hpp").read_text(errors="replace")
    keys = set(re.findall(r'\badd\s*\(\s*"([^"]+)"', cpp))
    keys.update(
        re.findall(
            r"\(\(\s*ConfigOption[A-Za-z0-9_<>:]*\s*,\s*([A-Za-z0-9_]+)\s*\)\)",
            hpp,
        )
    )
    if len(keys) < 500:
        raise RuntimeError(f"unexpected Prusa config-key count: {len(keys)}")
    return keys


def stringify(value) -> str:
    if isinstance(value, list):
        return ",".join(stringify(v) for v in value)
    if isinstance(value, bool):
        return "1" if value else "0"
    if value is None:
        return ""
    if isinstance(value, (dict, tuple)):
        return json.dumps(value, separators=(",", ":"), ensure_ascii=False)
    return str(value)


def rewrite_template(value: str) -> str:
    out = value
    for old, new in TEMPLATE_ALIASES.items():
        # Replace identifiers only; preserve Orca/Prusa braces, brackets and indexes.
        out = re.sub(rf"(?<![A-Za-z0-9_]){re.escape(old)}(?![A-Za-z0-9_])", new, out)
    return out


def normalize_value(key: str, value: str) -> str:
    # Known enumeration aliases between Orca and Prusa.
    if key == "brim_type" and value.strip().lower() == "auto_brim":
        # The static Prusa/SliceBeam compatibility route still lacks the
        # geometry-dependent resolver. Do not turn Bambu's source width into a
        # fixed outer-only brim while that route remains unverified.
        return "no_brim"
    if key == "perimeter_generator":
        aliases = {"classic": "classic", "arachne": "arachne"}
        return aliases.get(value.strip().lower(), value)
    if key == "support_material":
        low = value.strip().lower()
        if low in {"true", "1"}:
            return "1"
        if low in {"false", "0"}:
            return "0"
    if key == "support_material_pattern" and value.strip().lower() == "default":
        return "rectilinear"
    if key == "arc_fitting":
        return {"1": "emit_center", "0": "disabled"}.get(value.strip().lower(), value)
    if key == "complete_objects":
        return {"by layer": "0", "by object": "1"}.get(value.strip().lower(), value)
    if key == "top_one_perimeter_type":
        aliases = {"1": "top", "true": "top", "0": "none", "false": "none"}
        return aliases.get(value.strip().lower(), value)
    if key == "ironing_type" and value == "no ironing":
        return "top"
    return value


def apply_compatibility_overrides(merged: OrderedDict[str, str], supported: set[str]) -> None:
    """Apply adapter policies that need more than a one-key source alias."""
    # The compatibility route uses an explicit no-brim policy and zeros the
    # paired width because the classic generator otherwise reverses the first
    # layer's perimeter order solely because brim_width remains positive.
    if merged.get("brim_type") == "no_brim" and "brim_width" in supported:
        merged["brim_width"] = "0"

    # Orca stores the sparse-infill acceleration as a percentage of the
    # process default, while the pinned Prusa/SliceBeam core accepts an
    # absolute mm/s² value. Resolve the source-backed percentage after all
    # profile layers have been merged so inheritance precedence is preserved.
    if "infill_acceleration" in merged:
        value = merged["infill_acceleration"].strip()
        if value.endswith("%"):
            try:
                percent = float(value[:-1].strip())
                default = float(merged.get("default_acceleration", "0").strip())
            except ValueError:
                percent = float("nan")
                default = float("nan")
            if math.isfinite(percent) and math.isfinite(default) and default >= 0:
                resolved = default * percent / 100.0
                merged["infill_acceleration"] = (
                    str(int(resolved)) if resolved.is_integer() else f"{resolved:.6f}".rstrip("0").rstrip(".")
                )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("resolved_profile_dir", type=Path)
    p.add_argument("slicebeam_checkout", type=Path)
    p.add_argument("output_ini", type=Path)
    p.add_argument("--report", type=Path)
    args = p.parse_args()

    supported = supported_keys(args.slicebeam_checkout)
    merged: OrderedDict[str, str] = OrderedDict()
    unsupported: list[dict] = []
    mapped: list[dict] = []

    # Same precedence as the production plan: printer -> process -> filament.
    for kind in ("machine", "process", "filament"):
        data = json.loads((args.resolved_profile_dir / f"{kind}.json").read_text())
        for source_key, raw in data.items():
            if source_key in META_KEYS:
                continue
            target = KEY_MAP.get(source_key, source_key)
            if target not in supported:
                unsupported.append({"profile": kind, "source": source_key, "target": target})
                continue
            value = stringify(raw)
            if target in GCODE_KEYS:
                value = rewrite_template(value)
            value = normalize_value(target, value)
            merged[target] = value
            if target != source_key:
                mapped.append({"profile": kind, "source": source_key, "target": target})

    # Force the CLI into the intended technology rather than relying on defaults.
    if "printer_technology" in supported:
        merged["printer_technology"] = "FFF"

    apply_compatibility_overrides(merged, supported)

    # Preserve SliceBeam's special no-ironing behavior when present.
    if merged.get("ironing_type") == "top" and any(
        json.loads((args.resolved_profile_dir / f"{kind}.json").read_text()).get("ironing_type") == "no ironing"
        for kind in ("machine", "process", "filament")
    ):
        if "ironing" in supported:
            merged["ironing"] = "0"

    args.output_ini.parent.mkdir(parents=True, exist_ok=True)
    with args.output_ini.open("w") as f:
        for key, value in merged.items():
            # INI multi-line values use escaped newlines in Prusa/Slic3r config files.
            escaped = value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")
            f.write(f"{key} = {escaped}\n")

    report = {
        "supported_key_count": len(supported),
        "emitted_key_count": len(merged),
        "mapped_keys": mapped,
        "unsupported_count": len(unsupported),
        "unsupported": unsupported,
        "gcode_template_keys": {k: merged[k] for k in GCODE_KEYS if k in merged},
    }
    report_path = args.report or args.output_ini.with_suffix(".report.json")
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {args.output_ini}: {len(merged)} supported keys")
    print(f"unsupported profile fields: {len(unsupported)}")


if __name__ == "__main__":
    main()
