#!/usr/bin/env python3
"""Verify that a G3 desktop/native pair used the same effective profile.

G-code parity is not meaningful when the two slicers were given different
profiles. This check reads Bambu's project_settings.config from a .gcode.3mf,
Orca's resolved CONFIG_BLOCK from plain G-code, and the exact SliceBeam/Prusa
config written beside the native G-code. It compares the source-backed fields
that Alloy intentionally projects and keeps engine-specific or explicitly
unsupported fields out of the signature.

The comparison is deliberately conservative: documented vocabulary aliases
are normalized, missing values fail, and unknown values are never guessed.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
import json
from pathlib import Path
import re
from typing import Any
from zipfile import ZipFile


# canonical field -> (Bambu project key, native config key)
# Keep this list source-backed and intentionally smaller than the complete
# profile. Fields not represented by the pinned native adapter belong in G4,
# not in a false G3 identity claim.
FIELDS = OrderedDict([
    ("layer_height", ("layer_height", "layer_height")),
    ("first_layer_height", ("initial_layer_print_height", "first_layer_height")),
    ("fill_density", ("sparse_infill_density", "fill_density")),
    ("perimeters", ("wall_loops", "perimeters")),
    ("top_solid_layers", ("top_shell_layers", "top_solid_layers")),
    ("bottom_solid_layers", ("bottom_shell_layers", "bottom_solid_layers")),
    ("top_solid_min_thickness", ("top_shell_thickness", "top_solid_min_thickness")),
    ("bottom_solid_min_thickness", ("bottom_shell_thickness", "bottom_solid_min_thickness")),
    ("support_material", ("enable_support", "support_material")),
    ("support_material_style", ("support_type", "support_material_style")),
    ("support_material_pattern", ("support_base_pattern", "support_material_pattern")),
    ("support_material_buildplate_only", ("support_on_build_plate_only", "support_material_buildplate_only")),
    ("support_material_bottom_contact_distance", ("support_bottom_z_distance", "support_material_bottom_contact_distance")),
    ("support_material_contact_distance", ("support_top_z_distance", "support_material_contact_distance")),
    ("support_material_xy_spacing", ("support_object_xy_distance", "support_material_xy_spacing")),
    ("support_material_bottom_interface_layers", ("support_interface_bottom_layers", "support_material_bottom_interface_layers")),
    ("support_material_interface_layers", ("support_interface_top_layers", "support_material_interface_layers")),
    ("support_material_interface_pattern", ("support_interface_pattern", "support_material_interface_pattern")),
    ("support_material_interface_spacing", ("support_interface_spacing", "support_material_interface_spacing")),
    ("support_material_extrusion_width", ("support_line_width", "support_material_extrusion_width")),
    ("support_material_speed", ("support_speed", "support_material_speed")),
    ("support_tree_angle", ("tree_support_branch_angle", "support_tree_angle")),
    ("support_tree_branch_diameter", ("tree_support_branch_diameter", "support_tree_branch_diameter")),
    ("support_tree_branch_diameter_angle", ("tree_support_branch_diameter_angle", "tree_support_branch_diameter_angle")),
    ("support_tree_branch_diameter_double_wall", ("tree_support_wall_count", "support_tree_branch_diameter_double_wall")),
    ("support_tree_branch_distance", ("tree_support_branch_distance", "tree_support_branch_distance_organic")),
    ("support_object_first_layer_gap", ("support_object_first_layer_gap", "support_object_first_layer_gap")),
    ("support_interface_not_for_body", ("support_interface_not_for_body", "support_interface_not_for_body")),
    ("support_remove_small_overhang", ("support_remove_small_overhang", "support_remove_small_overhang")),
    ("brim_object_gap", ("brim_object_gap", "brim_object_gap")),
    ("brim_type", ("brim_type", "brim_type")),
    ("brim_width", ("brim_width", "brim_width")),
    ("temperature", ("nozzle_temperature", "temperature")),
    ("first_layer_temperature", ("nozzle_temperature_initial_layer", "first_layer_temperature")),
    ("bed_temperature", ("hot_plate_temp", "bed_temperature")),
    ("first_layer_bed_temperature", ("hot_plate_temp_initial_layer", "first_layer_bed_temperature")),
    ("filament_diameter", ("filament_diameter", "filament_diameter")),
    ("extrusion_multiplier", ("filament_flow_ratio", "extrusion_multiplier")),
    ("filament_max_volumetric_speed", ("filament_max_volumetric_speed", "filament_max_volumetric_speed")),
    ("machine_max_acceleration_x", ("machine_max_acceleration_x", "machine_max_acceleration_x")),
    ("machine_max_acceleration_y", ("machine_max_acceleration_y", "machine_max_acceleration_y")),
    ("machine_max_acceleration_travel", ("machine_max_acceleration_travel", "machine_max_acceleration_travel")),
    ("machine_max_acceleration_extruding", ("machine_max_acceleration_extruding", "machine_max_acceleration_extruding")),
    ("travel_acceleration", ("travel_acceleration", "travel_acceleration")),
    ("default_acceleration", ("default_acceleration", "default_acceleration")),
    ("infill_acceleration", ("sparse_infill_acceleration", "infill_acceleration")),
    ("fill_angle", ("infill_direction", "fill_angle")),
    ("slowdown_below_layer_time", ("slow_down_layer_time", "slowdown_below_layer_time")),
    ("min_print_speed", ("slow_down_min_speed", "min_print_speed")),
    ("small_perimeter_speed", ("small_perimeter_speed", "small_perimeter_speed")),
    ("fan_below_layer_time", ("fan_cooling_layer_time", "fan_below_layer_time")),
    ("thin_walls", ("detect_thin_wall", "thin_walls")),
    ("perimeter_generator", ("wall_generator", "perimeter_generator")),
    ("ironing", ("ironing_type", "ironing")),
    ("sparse_infill_pattern", ("sparse_infill_pattern", "sparse_infill_pattern")),
    ("internal_solid_infill_pattern", ("internal_solid_infill_pattern", "internal_solid_infill_pattern")),
    ("top_surface_pattern", ("top_surface_pattern", "top_surface_pattern")),
    ("bottom_surface_pattern", ("bottom_surface_pattern", "bottom_surface_pattern")),
    ("support_base_pattern", ("support_base_pattern", "support_base_pattern")),
    ("support_base_pattern_spacing", ("support_base_pattern_spacing", "support_base_pattern_spacing")),
    ("wipe_distance", ("wipe_distance", "wipe_distance")),
    ("retraction_minimum_travel", ("retraction_minimum_travel", "retraction_minimum_travel")),
])

# The checked-in Alloy adapter deliberately uses the old Prusa vocabulary for
# a few settings even though the resolved Orca/Bambu source uses newer names.
# Keep these aliases explicit and one-way: they document representation drift
# without allowing an arbitrary fallback or silently ignoring a missing key.
NATIVE_KEY_ALIASES = {
    "retraction_minimum_travel": ("retraction_minimum_travel", "retract_before_travel"),
}


def read_desktop_settings(path: Path) -> dict[str, Any]:
    if path.suffix.lower() in {".gcode", ".gc"}:
        return read_desktop_gcode_settings(path)
    if path.suffix.lower() not in {".3mf", ".zip"}:
        return json.loads(path.read_text())
    with ZipFile(path) as archive:
        candidates = [
            "Metadata/project_settings.config",
            "Metadata/project_settings.json",
        ]
        for name in candidates:
            if name in archive.namelist():
                return json.loads(archive.read(name).decode("utf-8", errors="replace"))
    raise ValueError(f"Bambu project settings not found in {path}")


def read_desktop_gcode_settings(path: Path) -> dict[str, Any]:
    """Read the resolved Orca/Bambu CONFIG_BLOCK from plain G-code.

    A project JSON is useful source intent, but the emitted block is the
    authoritative resolved recipe actually consumed by the desktop slicer.
    Only simple comment assignments are imported; executable G-code and other
    comments are never interpreted as settings.
    """
    if path.suffix.lower() in {".3mf", ".zip"}:
        with ZipFile(path) as archive:
            members = sorted(
                name for name in archive.namelist()
                if re.fullmatch(r"Metadata/plate_\d+\.gcode", name, re.IGNORECASE)
            )
            if not members:
                members = sorted(
                    name for name in archive.namelist()
                    if name.lower().endswith(".gcode")
                )
            if not members:
                raise ValueError(f"resolved G-code member not found in {path}")
            text = archive.read(members[0]).decode("utf-8", errors="replace")
    else:
        text = path.read_text(errors="replace")

    result: dict[str, Any] = {}
    in_block = False
    for raw in text.splitlines():
        line = raw.strip()
        if line == "; CONFIG_BLOCK_START":
            in_block = True
            continue
        if line == "; CONFIG_BLOCK_END":
            break
        if not in_block or not line.startswith("; ") or " = " not in line:
            continue
        key, value = line[2:].split(" = ", 1)
        key = key.strip()
        value = value.strip()
        if key:
            result[key] = value.strip('"')
    if not result:
        raise ValueError(f"resolved CONFIG_BLOCK not found in {path}")
    return result


def read_native_settings(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def merged_desktop_settings(base: Path, overlays: list[Path] | None = None) -> dict[str, Any]:
    """Resolve a desktop recipe from its base settings and explicit overlays.

    Bambu's saved project commonly stores a resolved process recipe separately
    from per-object/support overrides. Merge only caller-provided JSON/3MF
    documents in order; never discover or guess extra files from a directory.
    Later overlays are authoritative for keys they contain.
    """
    # A packaged desktop print is authoritative when it contains resolved
    # G-code; plain project archives remain source-settings inputs.
    result = (read_desktop_gcode_settings(base)
              if base.name.lower().endswith(".gcode.3mf")
              else read_desktop_settings(base))
    for overlay in overlays or []:
        result.update(read_desktop_settings(overlay))
    return result


def _number(value: str) -> str | None:
    try:
        number = float(value)
    except ValueError:
        return None
    if number == 0:
        return "0"
    if number.is_integer():
        return str(int(number))
    return f"{number:.8f}".rstrip("0").rstrip(".")


def _scalar(value: Any) -> str:
    if isinstance(value, (list, tuple)):
        return ",".join(_scalar(item) for item in value)
    if value is None:
        return ""
    return str(value).strip()


def normalize(field: str, value: Any, source: str, settings: dict[str, Any]) -> str:
    result = _scalar(value)
    low = result.lower()

    if field == "support_material_style" and source == "desktop":
        result = {
            "tree(auto)": "organic",
            "normal(auto)": "normal",
        }.get(low, low)
    elif field == "support_material_pattern" and source == "desktop":
        # Bambu/Orca's resolved organic recipe uses `default`; older Alloy
        # snapshots used the equivalent legacy `rectilinear` spelling. Keep
        # the comparison semantic while still rejecting other patterns.
        result = {"default": "default", "rectilinear": "default"}.get(low, low)
    elif field == "support_material_pattern" and source == "native":
        result = {"default": "default", "rectilinear": "default"}.get(low, low)
    elif field in {"sparse_infill_pattern", "internal_solid_infill_pattern", "top_surface_pattern", "bottom_surface_pattern"}:
        result = {
            "zig-zag": "rectilinear",
            "monotoniclines": "monotonicline",
        }.get(low, low)
    elif field == "support_material" and low in {"true", "false"}:
        result = "1" if low == "true" else "0"
    elif field == "ironing" and source == "desktop":
        result = "0" if low == "no ironing" else low

    # Bambu stores sparse-infill acceleration as a percentage of the process
    # default, while the native core stores the resolved absolute value.
    if field == "infill_acceleration" and source == "desktop" and result.endswith("%"):
        percent = _number(result[:-1].strip())
        default = normalize("default_acceleration", settings.get("default_acceleration"), source, settings)
        if percent is not None and default:
            result = _number(str(float(percent) * float(default) / 100.0)) or result

    # Percent signs are presentation syntax; the canonical profile identity
    # compares their numeric value while retaining enum/string values.
    if result.endswith("%"):
        numeric = _number(result[:-1].strip())
        if numeric is not None:
            return numeric + "%"
    if "," in result:
        pieces = result.split(",")
        if all(_number(piece.strip()) is not None for piece in pieces):
            return ",".join(_number(piece.strip()) or piece.strip() for piece in pieces)
    return _number(result) or result


def verify(desktop: dict[str, Any], native: dict[str, str]) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    for field, (desktop_key, native_key) in FIELDS.items():
        desktop_raw = desktop.get(desktop_key)
        native_candidates = NATIVE_KEY_ALIASES.get(field, (native_key,))
        native_match = next(((key, native[key]) for key in native_candidates if key in native), None)
        native_key_used = native_match[0] if native_match else native_key
        native_raw = native_match[1] if native_match else None
        if desktop_raw is None or native_raw is None:
            checks.append({
                "field": field,
                "status": "fail",
                "desktop_key": desktop_key,
                "native_key": native_key_used,
                "desktop": None if desktop_raw is None else _scalar(desktop_raw),
                "native": native_raw,
                "detail": "source-backed field is missing from one side",
            })
            continue
        left = normalize(field, desktop_raw, "desktop", desktop)
        right = normalize(field, native_raw, "native", native)
        checks.append({
            "field": field,
            "status": "pass" if left == right else "fail",
            "desktop_key": desktop_key,
            "native_key": native_key_used,
            "desktop": left,
            "native": right,
        })
    failures = [check for check in checks if check["status"] != "pass"]
    return {
        "result": "pass" if not failures else "fail",
        "checked_fields": len(checks),
        "failed_fields": [check["field"] for check in failures],
        "checks": checks,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("desktop", type=Path, help="Bambu .gcode.3mf or project settings JSON")
    parser.add_argument("native_config", type=Path, help="SliceBeam config.ini beside native G-code")
    parser.add_argument("--desktop-overlay", type=Path, action="append", default=[],
                        help="explicit Bambu project/object override JSON/3MF; repeatable")
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()

    report = verify(merged_desktop_settings(args.desktop, args.desktop_overlay),
                    read_native_settings(args.native_config))
    rendered = json.dumps(report, indent=2) + "\n"
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered)
    print(rendered, end="")
    raise SystemExit(0 if report["result"] == "pass" else 2)


if __name__ == "__main__":
    main()
