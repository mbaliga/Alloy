#!/usr/bin/env python3
"""Generate A1 Mini planning recipes from the pinned Orca profile inheritance."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import verify_profile_snapshot as snapshot


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json"
RECIPES = {
    "bambu.a1-mini.0.4.petg-basic": {
        "asset": "a1-mini-0.4-petg-basic.json",
        "leaf": "resources/profiles/BBL/filament/Bambu PETG Basic @BBL A1M 0.4 nozzle.json",
        "parents": [
            "resources/profiles/BBL/filament/Bambu PETG Basic @base.json",
            "resources/profiles/BBL/filament/fdm_filament_pet.json",
            "resources/profiles/BBL/filament/fdm_filament_common.json",
        ],
    },
    "bambu.a1-mini.0.4.petg-hf": {
        "asset": "a1-mini-0.4-petg-hf.json",
        "leaf": "resources/profiles/BBL/filament/Bambu PETG HF @BBL A1M.json",
        "parents": [
            "resources/profiles/BBL/filament/Bambu PETG HF @base.json",
            "resources/profiles/BBL/filament/fdm_filament_pet.json",
            "resources/profiles/BBL/filament/fdm_filament_common.json",
        ],
    },
    "bambu.a1-mini.0.4.petg-translucent": {
        "asset": "a1-mini-0.4-petg-translucent.json",
        "leaf": "resources/profiles/BBL/filament/Bambu PETG Translucent @BBL A1M.json",
        "parents": [
            "resources/profiles/BBL/filament/Bambu PETG Translucent @base.json",
            "resources/profiles/BBL/filament/fdm_filament_pet.json",
            "resources/profiles/BBL/filament/fdm_filament_common.json",
        ],
    },
    "bambu.a1-mini.0.4.tpu-95a-hf": {
        "asset": "a1-mini-0.4-tpu-95a-hf.json",
        "leaf": "resources/profiles/BBL/filament/Bambu TPU 95A HF @BBL A1M.json",
        "parents": [
            "resources/profiles/BBL/filament/Bambu TPU 95A HF @base.json",
            "resources/profiles/BBL/filament/fdm_filament_tpu.json",
            "resources/profiles/BBL/filament/fdm_filament_common.json",
        ],
    },
    "bambu.a1-mini.0.4.pva": {
        "asset": "a1-mini-0.4-pva.json",
        "leaf": "resources/profiles/BBL/filament/Bambu PVA @BBL A1M.json",
        "parents": [
            "resources/profiles/BBL/filament/Bambu PVA @base.json",
            "resources/profiles/BBL/filament/fdm_filament_pva.json",
            "resources/profiles/BBL/filament/fdm_filament_common.json",
        ],
    },
}


def one(data: dict, key: str) -> object:
    raw = snapshot.value(data, key)
    if isinstance(raw, list):
        if not raw:
            raise ValueError(f"{key} is empty")
        return raw[0]
    return raw


def build(repo: Path, profile_id: str) -> dict:
    recipe = RECIPES[profile_id]
    asset = json.loads(BASELINE.read_text(encoding="utf-8"))
    revision = asset["provenance"]["revision"]
    selection = snapshot.SELECTIONS[profile_id]
    resolved = {kind: snapshot.resolve(name, snapshot.load_profiles(repo, kind))
                for kind, name in selection.items()}
    filament = resolved["filament"]
    asset["id"] = profile_id
    asset["name"] = f"Bambu Lab A1 Mini · 0.4 mm · {filament['name']}"
    asset["verified"] = False
    asset["provenance"]["note"] = (
        "Pinned-source planning recipe for A1 Mini, 0.4 mm nozzle and 0.20 mm Standard. "
        "It is not physically qualified; Alloy send remains disabled."
    )
    retained = [entry for entry in asset["provenance"]["files"]
                if not entry["path"].startswith("resources/profiles/BBL/filament/")]
    filament_paths = [recipe["leaf"], *recipe["parents"]]
    for path in filament_paths:
        retained.append({"path": path, "sha": snapshot.git(repo, "rev-parse", f"{revision}:{path}")})
    asset["provenance"]["files"] = retained

    leaf_name = str(filament["name"])
    asset["filament"].update({
        "id": profile_id.replace("bambu.a1-mini.0.4.", "bambu."),
        "name": leaf_name,
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

    native = {key: value for key, value in asset["native_settings"].items()
              if key in snapshot.NATIVE_PROJECTIONS}
    for target, (kind, source_key) in snapshot.NATIVE_PROJECTIONS.items():
        if kind != "filament":
            continue
        if source_key not in resolved["filament"]:
            native.pop(target, None)
            continue
        expected = snapshot.native_string(resolved["filament"], source_key)
        expected = snapshot.normalize_native_projection(target, kind, source_key, expected, resolved)
        expected = snapshot.NATIVE_VALUE_ALIASES.get((kind, source_key), {}).get(expected, expected)
        native[target] = expected
    asset["native_settings"] = native
    return asset


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("orca_checkout", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for profile_id, recipe in RECIPES.items():
        destination = ROOT / "app/src/main/assets/profiles" / recipe["asset"]
        content = json.dumps(build(args.orca_checkout, profile_id), indent=2, ensure_ascii=False) + "\n"
        if args.check:
            if not destination.is_file() or destination.read_text(encoding="utf-8") != content:
                raise SystemExit(f"pinned material profile is stale: {destination}")
            print(f"verified {profile_id}")
        else:
            destination.write_text(content, encoding="utf-8")
            print(f"generated {destination}")


if __name__ == "__main__":
    main()
