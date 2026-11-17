#!/usr/bin/env python3
"""Generate planning-only A1/P1S PETG profiles from pinned Bambu presets."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import verify_profile_snapshot as snapshot


ROOT = Path(__file__).resolve().parents[1]
RECIPES = {
    "bambu.a1.0.4.petg-basic": {
        "asset": "a1-0.4-petg-basic.json",
        "baseline": "a1-0.4-pla-basic.json",
        "filament_id": "bambu.petg-basic.a1",
        "filament_base": "Bambu PETG Basic @base",
        "filament_parents": ["fdm_filament_pet.json", "fdm_filament_common.json"],
    },
    "bambu.p1s.0.4.petg-hf": {
        "asset": "p1s-0.4-petg-hf.json",
        "baseline": "p1s-0.4-pla-basic.json",
        "filament_id": "bambu.petg-hf.p1s",
        "filament_base": "Bambu PETG HF @base",
        "filament_parents": ["fdm_filament_pet.json", "fdm_filament_common.json"],
    },
    "bambu.a1.0.4.tpu-95a": {
        "asset": "a1-0.4-tpu-95a.json",
        "baseline": "a1-0.4-pla-basic.json",
        "filament_id": "bambu.tpu-95a.a1",
        "filament_base": "Bambu TPU 95A @base",
        "filament_parents": ["fdm_filament_tpu.json", "fdm_filament_common.json"],
    },
    "bambu.p1s.0.4.tpu-95a-hf": {
        "asset": "p1s-0.4-tpu-95a-hf.json",
        "baseline": "p1s-0.4-pla-basic.json",
        "filament_id": "bambu.tpu-95a-hf.p1s",
        "filament_base": "Bambu TPU 95A HF @base",
        "filament_parents": ["fdm_filament_tpu.json", "fdm_filament_common.json"],
    },
}
PRINTER_NAMES = {"bambu.a1": "Bambu Lab A1", "bambu.p1s": "Bambu Lab P1S"}


def one(data: dict, key: str) -> object:
    raw = snapshot.value(data, key)
    if isinstance(raw, list):
        if not raw:
            raise ValueError(f"{key} is empty")
        return raw[0]
    return raw


def build(repo: Path, profile_id: str) -> dict:
    recipe = RECIPES[profile_id]
    asset_path = ROOT / "app/src/main/assets/profiles" / recipe["baseline"]
    asset = json.loads(asset_path.read_text(encoding="utf-8"))
    revision = snapshot.git(repo, "rev-parse", "HEAD")
    selection = snapshot.SELECTIONS[profile_id]
    resolved = {kind: snapshot.resolve(name, snapshot.load_profiles(repo, kind))
                for kind, name in selection.items()}
    machine, process = resolved["machine"], resolved["process"]
    filament = resolved["filament"]

    asset["id"] = profile_id
    asset["name"] = f"{PRINTER_NAMES[asset['printer']['id']]} · 0.4 mm · {filament['name']}"
    asset["verified"] = False
    asset["provenance"]["revision"] = revision
    asset["provenance"]["source"] = (
        f"https://github.com/bambulab/BambuStudio/tree/{revision}/resources/profiles/BBL"
    )
    asset["provenance"]["note"] = (
        f"Pinned-source planning recipe from the Bambu Studio {asset['printer']['id']} machine, "
        "0.20 mm Standard and material inheritance chain. It is not physically qualified; "
        "Alloy send remains disabled."
    )
    retained_paths = [entry["path"] for entry in asset["provenance"]["files"]
                      if not entry["path"].startswith("resources/profiles/BBL/filament/")]
    filament_leaf = str(selection["filament"])
    filament_paths = [
        f"resources/profiles/BBL/filament/{filament_leaf}.json",
        f"resources/profiles/BBL/filament/{recipe['filament_base']}.json",
        *(f"resources/profiles/BBL/filament/{path}" for path in recipe["filament_parents"]),
    ]
    asset["provenance"]["files"] = []
    for path in [*retained_paths, *filament_paths]:
        asset["provenance"]["files"].append({
            "path": path,
            "sha": snapshot.git(repo, "rev-parse", f"{revision}:{path}"),
        })

    asset["printer"].update({
        "bed_x_mm": snapshot.bed_size(machine)[0],
        "bed_y_mm": snapshot.bed_size(machine)[1],
        "build_z_mm": snapshot.number(machine, "printable_height"),
        "nozzle_mm": snapshot.number(machine, "nozzle_diameter"),
    })
    asset["process"].update({
        "layer_height_mm": snapshot.number(process, "layer_height"),
        "first_layer_height_mm": snapshot.number(process, "initial_layer_print_height"),
        "infill_percent": snapshot.number(process, "sparse_infill_density"),
        "perimeters": int(snapshot.number(process, "wall_loops")),
        "top_layers": int(snapshot.number(process, "top_shell_layers")),
        "bottom_layers": int(snapshot.number(process, "bottom_shell_layers")),
        "support_enabled": snapshot.boolean(process, "enable_support"),
        "support_threshold_degrees": snapshot.number(process, "support_threshold_angle"),
        "travel_speed_mm_s": snapshot.number(process, "travel_speed"),
        "outer_wall_speed_mm_s": snapshot.number(process, "outer_wall_speed"),
        "inner_wall_speed_mm_s": snapshot.number(process, "inner_wall_speed"),
        "infill_speed_mm_s": snapshot.number(process, "sparse_infill_speed"),
        "initial_layer_speed_mm_s": snapshot.number(process, "initial_layer_speed"),
    })

    asset["filament"].update({
        "id": recipe["filament_id"],
        "name": str(filament["name"]),
        "material": str(one(filament, "filament_type")),
        "diameter_mm": snapshot.number(filament, "filament_diameter"),
        "nozzle_temperature_c": snapshot.number(filament, "nozzle_temperature"),
        "first_layer_nozzle_temperature_c": snapshot.number(filament, "nozzle_temperature_initial_layer"),
        "bed_temperature_c": snapshot.number(filament, "hot_plate_temp"),
        "first_layer_bed_temperature_c": snapshot.number(filament, "hot_plate_temp_initial_layer"),
        "flow_ratio": snapshot.number(filament, "filament_flow_ratio"),
        "max_volumetric_speed_mm3_s": snapshot.number(filament, "filament_max_volumetric_speed"),
        "fan_min_percent": snapshot.number(filament, "fan_min_speed"),
        "fan_max_percent": snapshot.number(filament, "fan_max_speed"),
    })

    native = {}
    for target, (kind, source_key) in snapshot.NATIVE_PROJECTIONS.items():
        if source_key not in resolved[kind]:
            continue
        expected = snapshot.native_string(resolved[kind], source_key)
        expected = snapshot.normalize_native_projection(target, kind, source_key, expected, resolved)
        expected = snapshot.NATIVE_VALUE_ALIASES.get((kind, source_key), {}).get(expected, expected)
        native[target] = expected
    asset["native_settings"] = native
    return asset


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bambu_checkout", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for profile_id, recipe in RECIPES.items():
        destination = ROOT / "app/src/main/assets/profiles" / recipe["asset"]
        content = json.dumps(build(args.bambu_checkout, profile_id), indent=2, ensure_ascii=False) + "\n"
        if args.check:
            if not destination.is_file() or destination.read_text(encoding="utf-8") != content:
                raise SystemExit(f"pinned material profile is stale: {destination}")
            print(f"verified {profile_id}")
        else:
            destination.write_text(content, encoding="utf-8")
            print(f"generated {destination}")


if __name__ == "__main__":
    main()
