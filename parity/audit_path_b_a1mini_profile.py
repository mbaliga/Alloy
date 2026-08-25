#!/usr/bin/env python3
"""Audit current pinned Orca A1 Mini profiles against SliceBeam/Prusa 2.8 keys.

This is a static G4 pre-gate. It does not prove that SliceBeam slices correctly;
it determines whether a deterministic Alloy profile adapter can represent the
critical Bambu profile fields before native G3/G4 execution.

Inputs:
  1. pinned OrcaSlicer checkout
  2. pinned SliceBeam checkout
  3. output directory

The Orca profile snapshot is expected to be pinned by the caller. No network
fetch occurs inside this script.
"""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re
import sys

MACHINE = "Bambu Lab A1 mini 0.4 nozzle"
PROCESS = "0.20mm Standard @BBL A1M"
FILAMENT = "Bambu PLA Basic @BBL A1M"

# Compatibility renames from Orca/Bambu terminology to the PrusaSlicer 2.8
# configuration vocabulary used by SliceBeam. Most already exist in SliceBeam's
# experimental importer. The machine_max_speed_* -> machine_max_feedrate_*
# mappings are explicit Prusa 2.8 equivalents (M203 feed-rate limits), not a
# relaxation of the G4 criteria.
KEY_MAP = {
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
    "machine_max_speed_x": "machine_max_feedrate_x",
    "machine_max_speed_y": "machine_max_feedrate_y",
    "machine_max_speed_z": "machine_max_feedrate_z",
    "machine_max_speed_e": "machine_max_feedrate_e",
}

CRITICAL = {
    "printer": {
        "printable_area": "bed_shape",
        "printable_height": "max_print_height",
        "machine_start_gcode": "start_gcode",
        "machine_end_gcode": "end_gcode",
        "nozzle_diameter": "nozzle_diameter",
        "machine_max_speed_x": "machine_max_feedrate_x",
        "machine_max_speed_y": "machine_max_feedrate_y",
        "machine_max_speed_z": "machine_max_feedrate_z",
        "machine_max_speed_e": "machine_max_feedrate_e",
        "machine_max_acceleration_x": "machine_max_acceleration_x",
        "machine_max_acceleration_y": "machine_max_acceleration_y",
        "machine_max_acceleration_z": "machine_max_acceleration_z",
        "machine_max_acceleration_e": "machine_max_acceleration_e",
        "machine_max_acceleration_extruding": "machine_max_acceleration_extruding",
        "machine_max_acceleration_retracting": "machine_max_acceleration_retracting",
        "machine_max_acceleration_travel": "machine_max_acceleration_travel",
    },
    "process": {
        "layer_height": "layer_height",
        "wall_loops": "perimeters",
        "sparse_infill_density": "fill_density",
        "sparse_infill_pattern": "fill_pattern",
        "enable_support": "support_material",
    },
    "filament": {
        "nozzle_temperature": "temperature",
        "nozzle_temperature_initial_layer": "first_layer_temperature",
        "filament_flow_ratio": "extrusion_multiplier",
        "filament_max_volumetric_speed": "max_volumetric_speed",
    },
}

# Orca process-key names that are conceptually equivalent to Prusa 2.8 names but
# are not handled by SliceBeam's current importer. We report them separately so
# Alloy can decide whether a lossless adapter is possible.
ALLOY_CANDIDATE_MAP = {
    "wall_loops": "perimeters",
    "sparse_infill_density": "fill_density",
    "sparse_infill_pattern": "fill_pattern",
    "enable_support": "support_material",
    "support_type": "support_material_style",
    "support_threshold_angle": "support_material_threshold",
    "brim_type": "brim_type",
    "brim_width": "brim_width",
    "outer_wall_speed": "external_perimeter_speed",
    "inner_wall_speed": "perimeter_speed",
    "sparse_infill_speed": "infill_speed",
    "internal_solid_infill_speed": "solid_infill_speed",
    "top_surface_speed": "top_solid_infill_speed",
    "initial_layer_speed": "first_layer_speed",
    "initial_layer_print_height": "first_layer_height",
    "filament_max_volumetric_speed": "max_volumetric_speed",
}


def load_profiles(root: Path, kind: str):
    folder = root / "resources/profiles/BBL" / kind
    by_name: dict[str, dict] = {}
    source: dict[str, str] = {}
    for path in folder.rglob("*.json"):
        try:
            data = json.loads(path.read_text(errors="replace"))
        except json.JSONDecodeError:
            continue
        name = data.get("name")
        if isinstance(name, str) and name:
            by_name[name] = data
            source[name] = str(path.relative_to(root))
    return by_name, source


def resolve(name: str, profiles: dict[str, dict], stack=None) -> dict:
    stack = [] if stack is None else stack
    if name in stack:
        raise RuntimeError("profile inheritance cycle: " + " -> ".join(stack + [name]))
    if name not in profiles:
        raise KeyError(f"missing inherited profile: {name}")
    data = profiles[name]
    result: dict = {}
    parent = data.get("inherits")
    if isinstance(parent, str) and parent:
        result.update(resolve(parent, profiles, stack + [name]))
    elif isinstance(parent, list):
        for item in parent:
            if item:
                result.update(resolve(str(item), profiles, stack + [name]))
    result.update({k: v for k, v in data.items() if k != "inherits"})
    return result


def supported_slice_keys(slice_repo: Path) -> set[str]:
    cpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.cpp").read_text(errors="replace")
    hpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.hpp").read_text(errors="replace")

    # Dynamic config definitions are registered with add("key", ...). Static
    # config members are also declared through the CONFIG_* macro tables in the
    # header; machine limit options such as machine_max_acceleration_x and
    # machine_max_feedrate_x live there and must be counted as valid keys.
    keys = set(re.findall(r"\badd\s*\(\s*\"([^\"]+)\"", cpp))
    keys.update(
        re.findall(
            r"\(\(\s*ConfigOption[A-Za-z0-9_<>:]*\s*,\s*([A-Za-z0-9_]+)\s*\)\)",
            hpp,
        )
    )
    if len(keys) < 500:
        raise RuntimeError(f"unexpectedly few PrintConfig keys parsed: {len(keys)}")
    return keys


def translated_key(key: str) -> str:
    return KEY_MAP.get(key, ALLOY_CANDIDATE_MAP.get(key, key))


def json_value(v):
    if isinstance(v, (dict, list)):
        return json.dumps(v, sort_keys=True, separators=(",", ":"))
    return str(v)


def audit_one(kind: str, resolved: dict, supported: set[str]) -> dict:
    rows = []
    counts = Counter()
    for key, value in sorted(resolved.items()):
        if key in {"type", "name", "from", "instantiation", "setting_id"}:
            continue
        mapped = translated_key(key)
        if mapped in supported:
            status = "supported"
        elif key in ALLOY_CANDIDATE_MAP:
            status = "mapped_but_target_missing"
        else:
            status = "unsupported"
        counts[status] += 1
        rows.append({"orca_key": key, "target_key": mapped, "status": status, "value": json_value(value)})

    critical = []
    for source_key, expected_target in CRITICAL[kind].items():
        target = translated_key(source_key)
        critical.append({
            "orca_key": source_key,
            "expected_target": expected_target,
            "translated_target": target,
            "mapping_matches_expected": target == expected_target,
            "present_in_resolved_profile": source_key in resolved,
            "target_supported_by_prusa_core": target in supported,
            "value": json_value(resolved[source_key]) if source_key in resolved else None,
        })
    return {"counts": dict(counts), "critical": critical, "keys": rows}


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: audit_path_b_a1mini_profile.py <orca> <slicebeam> <out>")
    orca, sb, out = map(Path, sys.argv[1:])
    out.mkdir(parents=True, exist_ok=True)
    supported = supported_slice_keys(sb)

    selections = {"printer": MACHINE, "process": PROCESS, "filament": FILAMENT}
    resolved = {}
    sources = {}
    for kind, name in selections.items():
        folder_kind = "machine" if kind == "printer" else kind
        profiles, source = load_profiles(orca, folder_kind)
        resolved[kind] = resolve(name, profiles)
        sources[kind] = source[name]

    audit = {
        "orca_commit": subprocess_rev(orca),
        "slicebeam_commit": subprocess_rev(sb),
        "selections": selections,
        "sources": sources,
        "prusa_supported_key_count": len(supported),
        "audit": {kind: audit_one(kind, cfg, supported) for kind, cfg in resolved.items()},
    }

    # G4 static pre-gate: every declared critical field must exist, map to the
    # explicitly expected equivalent, and be supported by the pinned Prusa core.
    # Native/runtime parity remains a separate gate.
    failures = []
    for kind, info in audit["audit"].items():
        for row in info["critical"]:
            if (
                not row["present_in_resolved_profile"]
                or not row["target_supported_by_prusa_core"]
                or not row["mapping_matches_expected"]
            ):
                failures.append({"kind": kind, **row})
    audit["critical_failures"] = failures
    audit["static_critical_result"] = "pass" if not failures else "fail"

    (out / "path-b-g4-profile-audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    for kind, cfg in resolved.items():
        (out / f"resolved-{kind}.json").write_text(json.dumps(cfg, indent=2) + "\n")

    md = [
        "# Path B G4 static A1 Mini profile audit",
        "",
        f"- Orca profile snapshot: `{audit['orca_commit']}`",
        f"- SliceBeam: `{audit['slicebeam_commit']}`",
        f"- Prusa-core registered/static config keys parsed: {len(supported)}",
        f"- machine: `{MACHINE}`",
        f"- process: `{PROCESS}`",
        f"- filament: `{FILAMENT}`",
        f"- static critical-field result: **{audit['static_critical_result'].upper()}**",
        "",
    ]
    for kind in ("printer", "process", "filament"):
        info = audit["audit"][kind]
        md += [
            f"## {kind.title()}",
            "",
            f"Coverage counts: `{json.dumps(info['counts'], sort_keys=True)}`",
            "",
            "| Orca field | translated field | expected mapping | present | core supports target |",
            "|---|---|---:|---:|---:|",
        ]
        for row in info["critical"]:
            md.append(
                f"| `{row['orca_key']}` | `{row['translated_target']}` | "
                f"{row['mapping_matches_expected']} | {row['present_in_resolved_profile']} | "
                f"{row['target_supported_by_prusa_core']} |"
            )
        md.append("")
    if failures:
        md += ["## Critical failures", ""]
        for row in failures:
            md.append(f"- {row['kind']}: `{row['orca_key']}` → `{row['translated_target']}`")
    (out / "PATH_B_G4_PROFILE_AUDIT.md").write_text("\n".join(md) + "\n")
    print("\n".join(md[:14]))
    raise SystemExit(0 if not failures else 2)


def subprocess_rev(repo: Path) -> str:
    import subprocess
    return subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"],
        text=True,
        stdout=subprocess.PIPE,
        check=True,
    ).stdout.strip()


if __name__ == "__main__":
    main()
