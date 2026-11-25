#!/usr/bin/env python3
"""Validate Alloy's packaged profile assets before they reach an APK."""

import json
import math
import re
import sys
from pathlib import Path


REQUIRED_TOP_LEVEL = {"schema_version", "id", "name", "verified", "provenance", "printer", "process", "filament", "native_settings"}
REQUIRED_PRINTER = {"id", "bed_x_mm", "bed_y_mm", "build_z_mm", "nozzle_mm"}
REQUIRED_PROCESS = {
    "layer_height_mm", "first_layer_height_mm", "infill_percent", "perimeters", "top_layers", "bottom_layers",
    "support_enabled", "support_threshold_degrees", "travel_speed_mm_s", "outer_wall_speed_mm_s",
    "inner_wall_speed_mm_s", "infill_speed_mm_s", "initial_layer_speed_mm_s",
}
REQUIRED_FILAMENT = {
    "id", "name", "material", "diameter_mm", "nozzle_temperature_c", "first_layer_nozzle_temperature_c",
    "bed_temperature_c", "first_layer_bed_temperature_c", "flow_ratio", "max_volumetric_speed_mm3_s",
    "fan_min_percent", "fan_max_percent",
}
MAX_PROFILE_BYTES = 256 * 1024


def require_keys(value, keys, label):
    missing = sorted(keys - value.keys())
    if missing:
        raise ValueError(f"{label} is missing: {', '.join(missing)}")


def number(value, label, minimum=None, maximum=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{label} must be a finite number")
    if minimum is not None and value < minimum or maximum is not None and value > maximum:
        raise ValueError(f"{label} is outside its allowed range")
    return value


def approved_native_keys(root):
    source_path = root / "app/src/main/java/com/mbaliga/alloy/NativeSettings.java"
    source = source_path.read_text(encoding="utf-8")
    try:
        block = source.split("APPROVED_KEYS", 1)[1].split(")));", 1)[0]
    except IndexError as error:
        raise ValueError(f"{source_path}: approved native-key set is missing") from error
    keys = set(re.findall(r'"([A-Za-z0-9_]+)"', block))
    if not keys:
        raise ValueError(f"{source_path}: approved native-key set is empty")
    return keys


def validate(path, approved_keys=None):
    if approved_keys is None:
        approved_keys = approved_native_keys(Path(__file__).resolve().parents[1])
    if path.stat().st_size > MAX_PROFILE_BYTES:
        raise ValueError(f"{path.name}: profile exceeds the 256 KB limit")
    with path.open(encoding="utf-8") as handle:
        profile = json.load(handle)
    require_keys(profile, REQUIRED_TOP_LEVEL, path.name)
    if profile["schema_version"] != 1:
        raise ValueError(f"{path.name}: unsupported schema_version")
    if not isinstance(profile["id"], str) or not profile["id"].strip():
        raise ValueError(f"{path.name}: id must be non-empty")
    if not isinstance(profile["verified"], bool):
        raise ValueError(f"{path.name}: verified must be boolean")
    provenance = profile["provenance"]
    if not isinstance(provenance, dict) or not isinstance(provenance.get("source"), str) or not provenance["source"].strip():
        raise ValueError(f"{path.name}: provenance.source must be non-empty")
    revision = provenance.get("revision")
    if not isinstance(revision, str) or len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision.lower()):
        raise ValueError(f"{path.name}: provenance.revision must be a 40-character commit SHA")
    files = provenance.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError(f"{path.name}: provenance.files must list pinned source files")
    for source_file in files:
        if not isinstance(source_file, dict) or not source_file.get("path") or not source_file.get("sha"):
            raise ValueError(f"{path.name}: each provenance file needs path and sha")
    require_keys(profile["printer"], REQUIRED_PRINTER, f"{path.name} printer")
    require_keys(profile["process"], REQUIRED_PROCESS, f"{path.name} process")
    require_keys(profile["filament"], REQUIRED_FILAMENT, f"{path.name} filament")
    native_settings = profile["native_settings"]
    if not isinstance(native_settings, dict) or not native_settings:
        raise ValueError(f"{path.name}: native_settings must be a non-empty object")
    for key, value in native_settings.items():
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_]+", key) or not isinstance(value, str):
            raise ValueError(f"{path.name}: native_settings must contain scalar string values")
        if key not in approved_keys:
            raise ValueError(f"{path.name}: native_settings key is not approved: {key}")
        if len(value) > 4096 or "\n" in value or "\r" in value:
            raise ValueError(f"{path.name}: native_settings value is too long or multiline: {key}")
    printer = profile["printer"]
    number(printer["bed_x_mm"], f"{path.name}: printer.bed_x_mm", 1, 5_000)
    number(printer["bed_y_mm"], f"{path.name}: printer.bed_y_mm", 1, 5_000)
    number(printer["build_z_mm"], f"{path.name}: printer.build_z_mm", 1, 5_000)
    number(printer["nozzle_mm"], f"{path.name}: printer.nozzle_mm", 0.05, 2)
    process = profile["process"]
    number(process["layer_height_mm"], f"{path.name}: process.layer_height_mm", 0.01, 2)
    number(process["first_layer_height_mm"], f"{path.name}: process.first_layer_height_mm", 0.01, 2)
    infill = profile["process"]["infill_percent"]
    number(infill, f"{path.name}: process.infill_percent", 0, 100)
    if not isinstance(process["support_enabled"], bool):
        raise ValueError(f"{path.name}: support_enabled must be boolean")
    number(process["travel_speed_mm_s"], f"{path.name}: process.travel_speed_mm_s", 0.1, 2_000)
    number(process["outer_wall_speed_mm_s"], f"{path.name}: process.outer_wall_speed_mm_s", 0.1, 1_000)
    number(process["inner_wall_speed_mm_s"], f"{path.name}: process.inner_wall_speed_mm_s", 0.1, 1_000)
    number(process["infill_speed_mm_s"], f"{path.name}: process.infill_speed_mm_s", 0.1, 1_000)
    number(process["initial_layer_speed_mm_s"], f"{path.name}: process.initial_layer_speed_mm_s", 0.1, 1_000)
    if isinstance(process["perimeters"], bool) or not isinstance(process["perimeters"], int) or not 1 <= process["perimeters"] <= 20:
        raise ValueError(f"{path.name}: process.perimeters is outside its allowed range")
    for key in ("top_layers", "bottom_layers"):
        if isinstance(process[key], bool) or not isinstance(process[key], int) or not 0 <= process[key] <= 100:
            raise ValueError(f"{path.name}: process.{key} is outside its allowed range")
    number(process["support_threshold_degrees"], f"{path.name}: process.support_threshold_degrees", 0, 90)
    filament = profile["filament"]
    for key in ("nozzle_temperature_c", "first_layer_nozzle_temperature_c"):
        number(filament[key], f"{path.name}: filament.{key}", 0, 400)
    for key in ("bed_temperature_c", "first_layer_bed_temperature_c"):
        number(filament[key], f"{path.name}: filament.{key}", 0, 150)
    number(filament["diameter_mm"], f"{path.name}: filament.diameter_mm", 1, 4)
    number(filament["flow_ratio"], f"{path.name}: filament.flow_ratio", 0.01, 2)
    number(filament["max_volumetric_speed_mm3_s"], f"{path.name}: filament.max_volumetric_speed_mm3_s", 0.01, 200)
    number(filament["fan_min_percent"], f"{path.name}: filament.fan_min_percent", 0, 100)
    number(filament["fan_max_percent"], f"{path.name}: filament.fan_max_percent", 0, 100)
    if filament["fan_min_percent"] > filament["fan_max_percent"]:
        raise ValueError(f"{path.name}: fan_min_percent cannot exceed fan_max_percent")


def main():
    root = Path(__file__).resolve().parents[1]
    approved_keys = approved_native_keys(root)
    paths = sorted((root / "app/src/main/assets/profiles").glob("*.json"))
    if not paths:
        raise ValueError("no packaged profile assets found")
    for path in paths:
        validate(path, approved_keys)
        print(f"validated {path.relative_to(root)}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"profile validation failed: {error}", file=sys.stderr)
        sys.exit(1)
