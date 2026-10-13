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
    "retraction_length": "retract_length",
    "retraction_speed": "retract_speed",
    "deretraction_speed": "deretract_speed",
    "change_filament_gcode": "pause_print_gcode",
    "nozzle_temperature": "temperature",
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "filament_flow_ratio": "extrusion_multiplier",
    "chamber_temperatures": "chamber_temperature",
    "fan_max_speed": "max_fan_speed",
    "fan_min_speed": "min_fan_speed",
    "overhang_fan_speed": "bridge_fan_speed",
    "slow_down_layer_time": "slowdown_below_layer_time",
    "slow_down_min_speed": "min_print_speed",

    # Explicit Orca -> Prusa 2.8 equivalents required for A1 Mini fidelity.
    "machine_max_speed_x": "machine_max_feedrate_x",
    "machine_max_speed_y": "machine_max_feedrate_y",
    "machine_max_speed_z": "machine_max_feedrate_z",
    "machine_max_speed_e": "machine_max_feedrate_e",
    "wall_loops": "perimeters",
    "wall_generator": "perimeter_generator",
    "sparse_infill_density": "fill_density",
    "sparse_infill_pattern": "fill_pattern",
    "enable_support": "support_material",
    "support_type": "support_material_style",
    "support_threshold_angle": "support_material_threshold",
    "outer_wall_speed": "external_perimeter_speed",
    "inner_wall_speed": "perimeter_speed",
    "sparse_infill_speed": "infill_speed",
    "internal_solid_infill_speed": "solid_infill_speed",
    "top_surface_speed": "top_solid_infill_speed",
    "initial_layer_speed": "first_layer_speed",
    "initial_layer_print_height": "first_layer_height",
    "filament_max_volumetric_speed": "max_volumetric_speed",
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
    if key == "perimeter_generator":
        aliases = {"classic": "classic", "arachne": "arachne"}
        return aliases.get(value.strip().lower(), value)
    if key == "support_material":
        low = value.strip().lower()
        if low in {"true", "1"}:
            return "1"
        if low in {"false", "0"}:
            return "0"
    if key == "ironing_type" and value == "no ironing":
        return "top"
    return value


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
