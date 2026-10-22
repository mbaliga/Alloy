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
import struct
from collections import Counter
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any
from zipfile import BadZipFile, ZipFile
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_PATH = REPO_ROOT / "app/src/androidTest/assets/profiles/g3-travel-obstacle-transform.json"
MODEL_PATH = "3D/3dmodel.model"
CORE_NS = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
PRODUCTION_NS = "http://schemas.microsoft.com/3dmanufacturing/production/2015/06"
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


def _read_stl_triangles(path: Path) -> list[tuple[tuple[float, float, float], ...]]:
    data = path.read_bytes()
    if len(data) >= 84:
        count = struct.unpack_from("<I", data, 80)[0]
        if count > 0 and len(data) == 84 + count * 50:
            triangles = []
            for index in range(count):
                values = struct.unpack_from("<9f", data, 84 + index * 50 + 12)
                triangles.append(tuple(tuple(values[offset:offset + 3]) for offset in (0, 3, 6)))
            return triangles
    try:
        source = data.decode("utf-8")
    except UnicodeDecodeError:
        raise RuntimeError("travel_obstacle source STL is neither valid binary nor UTF-8 ASCII") from None
    vertices = []
    for line_number, line in enumerate(source.splitlines(), 1):
        fields = line.strip().split()
        if fields and fields[0].lower() == "vertex":
            if len(fields) != 4:
                raise RuntimeError(f"invalid source STL vertex on line {line_number}")
            try:
                point = tuple(float(value) for value in fields[1:])
            except ValueError:
                raise RuntimeError(f"invalid source STL vertex on line {line_number}") from None
            if any(not math.isfinite(value) for value in point):
                raise RuntimeError(f"non-finite source STL vertex on line {line_number}")
            vertices.append(point)
    if not vertices or len(vertices) % 3:
        raise RuntimeError("travel_obstacle source STL must contain complete triangles")
    return [tuple(vertices[index:index + 3]) for index in range(0, len(vertices), 3)]


def _apply_transform(
    point: tuple[float, float, float],
    transform: tuple[list[list[float]], list[float]],
) -> tuple[float, float, float]:
    matrix, translation = transform
    return tuple(
        sum(matrix[row][column] * point[column] for column in range(3)) + translation[row]
        for row in range(3)
    )


def _normalized_triangle_multiset(
    triangles: list[tuple[tuple[float, float, float], ...]],
) -> Counter[tuple[tuple[float, float, float], ...]]:
    points = [point for triangle in triangles for point in triangle]
    if not points:
        raise RuntimeError("travel_obstacle geometry contains no triangles")
    center_x = (min(point[0] for point in points) + max(point[0] for point in points)) / 2
    center_y = (min(point[1] for point in points) + max(point[1] for point in points)) / 2
    bottom_z = min(point[2] for point in points)
    result = Counter()
    for triangle in triangles:
        normalized = tuple(sorted((
            round(point[0] - center_x, 4),
            round(point[1] - center_y, 4),
            round(point[2] - bottom_z, 4),
        ) for point in triangle))
        result[normalized] += 1
    return result


def _source_rotated_triangles(
    triangles: list[tuple[tuple[float, float, float], ...]], rotation_degrees: float,
) -> list[tuple[tuple[float, float, float], ...]]:
    points = [point for triangle in triangles for point in triangle]
    center_x = (min(point[0] for point in points) + max(point[0] for point in points)) / 2
    center_y = (min(point[1] for point in points) + max(point[1] for point in points)) / 2
    radians = math.radians(rotation_degrees)
    cosine, sine = math.cos(radians), math.sin(radians)
    bottom_z = min(point[2] for point in points)
    return [tuple((
        center_x + (point[0] - center_x) * cosine - (point[1] - center_y) * sine,
        center_y + (point[0] - center_x) * sine + (point[1] - center_y) * cosine,
        point[2] - bottom_z,
    ) for point in triangle) for triangle in triangles]


def _reference_transformed_triangles(
    model_xml: bytes, archive: ZipFile,
) -> list[tuple[tuple[float, float, float], ...]]:
    try:
        root = ET.fromstring(model_xml)
    except ET.ParseError:
        raise RuntimeError("desktop reference 3MF model XML is invalid") from None
    namespace = {"m": CORE_NS}
    items = root.findall("m:build/m:item", namespace)
    if len(items) != 1:
        raise RuntimeError("travel_obstacle desktop reference must contain one build item")
    item = items[0]
    item_transform = _parse_transform(item.get("transform"), "build-item")
    objects = [
        obj for obj in root.findall("m:resources/m:object", namespace)
        if obj.get("id") == item.get("objectid")
    ]
    if len(objects) != 1:
        raise RuntimeError("travel_obstacle desktop build item must resolve to one object")
    components = objects[0].findall("m:components/m:component", namespace)
    if len(components) != 1:
        raise RuntimeError("travel_obstacle desktop object must contain one mesh component")
    component = components[0]
    component_transform = _parse_transform(component.get("transform"), "component")
    part_path = component.get(f"{{{PRODUCTION_NS}}}path")
    if not part_path:
        raise RuntimeError("travel_obstacle desktop component must resolve to an external mesh model")
    relative = PurePosixPath(part_path.lstrip("/"))
    if relative.is_absolute() or ".." in relative.parts:
        raise RuntimeError("travel_obstacle desktop component has an unsafe model path")
    try:
        part_root = ET.fromstring(archive.read(str(relative)))
    except (KeyError, ET.ParseError):
        raise RuntimeError("travel_obstacle desktop component mesh model is missing or invalid") from None
    meshes = part_root.findall("m:resources/m:object/m:mesh", namespace)
    if len(meshes) != 1:
        raise RuntimeError("travel_obstacle desktop component must contain one triangle mesh")
    mesh = meshes[0]
    try:
        vertices = [
            tuple(float(vertex.get(axis)) for axis in ("x", "y", "z"))
            for vertex in mesh.findall("m:vertices/m:vertex", namespace)
        ]
    except (TypeError, ValueError):
        raise RuntimeError("travel_obstacle desktop component has an invalid vertex") from None
    if any(not math.isfinite(value) for vertex in vertices for value in vertex):
        raise RuntimeError("travel_obstacle desktop component has a non-finite vertex")
    facets = mesh.findall("m:triangles/m:triangle", namespace)
    if not vertices or not facets:
        raise RuntimeError("travel_obstacle desktop component mesh is empty")
    triangles = []
    for facet in facets:
        try:
            points = [vertices[int(facet.get(key))] for key in ("v1", "v2", "v3")]
        except (ValueError, IndexError, TypeError):
            raise RuntimeError("travel_obstacle desktop component has an invalid triangle index") from None
        triangles.append(tuple(
            _apply_transform(_apply_transform(point, component_transform), item_transform)
            for point in points
        ))
    return triangles


def validate_reference_geometry(path: Path, source_stl: Path, contract_path: Path = CONTRACT_PATH) -> None:
    """Require the desktop reference mesh to match the test-transformed source STL."""
    contract = load_contract(contract_path)
    source = _read_stl_triangles(source_stl)
    try:
        with ZipFile(path) as archive:
            model_xml = archive.read(MODEL_PATH)
            reference = _reference_transformed_triangles(model_xml, archive)
    except (OSError, BadZipFile, KeyError) as error:
        raise RuntimeError(f"cannot compare travel_obstacle desktop mesh: {error}") from None
    expected = _normalized_triangle_multiset(
        _source_rotated_triangles(source, float(contract["rotation_degrees"]))
    )
    actual = _normalized_triangle_multiset(reference)
    if expected != actual:
        raise RuntimeError(
            "desktop travel_obstacle mesh does not match the test-rotated bundled STL "
            f"(source triangles={sum(expected.values())}, reference triangles={sum(actual.values())})"
        )


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference_3mf", type=Path)
    parser.add_argument("source_stl", type=Path)
    parser.add_argument("--contract", type=Path, default=CONTRACT_PATH)
    args = parser.parse_args()
    validate_reference_3mf(args.reference_3mf, args.contract)
    validate_reference_geometry(args.reference_3mf, args.source_stl, args.contract)
    print("travel_obstacle desktop transform and mesh match the test-only rotated source STL")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
