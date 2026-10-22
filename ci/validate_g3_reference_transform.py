#!/usr/bin/env python3
"""Validate the travel-obstacle input orientation used by the strict G3 pair.

The Android evidence harness applies the rotation recorded in the test-only
contract. The desktop reference is generated with --arrange, so check its 3MF
effective component/build transform still agrees before comparing toolpaths.
Translation is intentionally ignored: NativeSlicerEngine centers the mesh.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any
from zipfile import BadZipFile, ZipFile
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = REPO_ROOT / "app/src/androidTest/assets/profiles/g3-travel-obstacle-transform.json"
MODEL_PATH = "3D/3dmodel.model"
EXPECTED_FIXTURE = "travel_obstacle"


def load_contract(path: Path = CONTRACT_PATH) -> dict[str, Any]:
    try:
        contract = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read G3 transform contract: {error}") from None
    if not isinstance(contract, dict):
        raise RuntimeError("G3 transform contract must be a JSON object")
    expected_matrix = [0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]
    if contract.get("fixture") != EXPECTED_FIXTURE:
        raise RuntimeError("G3 transform contract is not bound to travel_obstacle")
    if contract.get("scope") != "test-only strict G3 comparison input transform":
        raise RuntimeError("G3 transform contract must remain test-only")
    if contract.get("rotation_degrees") != 90.0:
        raise RuntimeError("native G3 travel_obstacle transform must remain 90 degrees")
    matrix = contract.get("reference_effective_rotation_matrix")
    if not isinstance(matrix, list) or len(matrix) != 9 or any(
        not isinstance(value, (int, float)) or not math.isfinite(value)
        for value in matrix
    ):
        raise RuntimeError("G3 reference rotation matrix must contain nine finite numbers")
    if any(abs(float(actual) - expected) > 1e-6 for actual, expected in zip(matrix, expected_matrix)):
        raise RuntimeError("G3 contract matrix must encode a positive 90-degree Z rotation")
    return contract


def _parse_transform(raw: str | None, label: str) -> tuple[list[list[float]], list[float]]:
    if raw is None:
        values = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0]
    else:
        try:
            values = [float(value) for value in raw.split()]
        except ValueError:
            raise RuntimeError(f"invalid {label} transform in desktop reference") from None
    if len(values) != 12 or any(not math.isfinite(value) for value in values):
        raise RuntimeError(f"{label} transform must contain twelve finite numbers")
    matrix = [values[0:3], values[3:6], values[6:9]]
    translation = values[9:12]
    return matrix, translation


def _compose(
    outer: tuple[list[list[float]], list[float]],
    inner: tuple[list[list[float]], list[float]],
) -> tuple[list[list[float]], list[float]]:
    outer_matrix, outer_translation = outer
    inner_matrix, inner_translation = inner
    matrix = [
        [sum(outer_matrix[row][k] * inner_matrix[k][column] for k in range(3)) for column in range(3)]
        for row in range(3)
    ]
    translation = [
        outer_translation[row] + sum(outer_matrix[row][k] * inner_translation[k] for k in range(3))
        for row in range(3)
    ]
    return matrix, translation


def reference_effective_transform(model_xml: bytes) -> tuple[list[list[float]], list[float]]:
    try:
        root = ET.fromstring(model_xml)
    except ET.ParseError:
        raise RuntimeError("desktop reference 3MF model XML is invalid") from None
    namespace = {"m": "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"}
    items = root.findall("m:build/m:item", namespace)
    if len(items) != 1:
        raise RuntimeError("travel_obstacle desktop reference must contain one build item")
    item = items[0]
    object_id = item.get("objectid")
    objects = [obj for obj in root.findall("m:resources/m:object", namespace) if obj.get("id") == object_id]
    if len(objects) != 1:
        raise RuntimeError("travel_obstacle desktop build item must resolve to one object")
    components = objects[0].findall("m:components/m:component", namespace)
    if len(components) != 1:
        raise RuntimeError("travel_obstacle desktop object must contain one mesh component")
    item_transform = _parse_transform(item.get("transform"), "build-item")
    component_transform = _parse_transform(components[0].get("transform"), "component")
    return _compose(item_transform, component_transform)


def validate_reference_3mf(path: Path, contract_path: Path = CONTRACT_PATH) -> None:
    contract = load_contract(contract_path)
    try:
        with ZipFile(path) as archive:
            model_xml = archive.read(MODEL_PATH)
    except (OSError, BadZipFile, KeyError) as error:
        raise RuntimeError(f"cannot read travel_obstacle desktop 3MF: {error}") from None
    matrix, _translation = reference_effective_transform(model_xml)
    expected = contract["reference_effective_rotation_matrix"]
    flattened = [value for row in matrix for value in row]
    if any(abs(actual - float(target)) > 1e-5 for actual, target in zip(flattened, expected)):
        raise RuntimeError("desktop travel_obstacle 3MF rotation differs from the test-only native transform contract")


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_3mf", type=Path)
    parser.add_argument("--contract", type=Path, default=CONTRACT_PATH)
    args = parser.parse_args()
    validate_reference_3mf(args.reference_3mf, args.contract)
    print("travel_obstacle desktop transform matches test-only native G3 rotation (90 degrees)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
