#!/usr/bin/env python3
"""Linux-capable Alloy model inspection and slicer bridge.

This is intentionally dependency-free. It gives the Linux side of Alloy the
same first useful contract as the Android shell: inspect a model, invoke a
known slicer executable, and validate the resulting project before transport.
The slicer remains an external, user-installed engine until Alloy's native
desktop engine is proven against the Android build.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import tempfile
from typing import Iterable
from xml.etree import ElementTree
from zipfile import BadZipFile, ZipFile


MAX_INPUT_BYTES = 256 * 1024 * 1024
MAX_TRIANGLES = 2_000_000
MAX_STL_LINE_CHARS = 1 * 1024 * 1024
MAX_OBJ_VERTICES = 6_000_000
MAX_OBJ_LINE_CHARS = 1 * 1024 * 1024
MAX_OBJ_FACE_VERTICES = 100_000
MAX_3MF_ENTRY_BYTES = 128 * 1024 * 1024
MAX_3MF_TOTAL_BYTES = 256 * 1024 * 1024
MAX_PACKAGE_ENTRIES = 4096
MAX_VALIDATION_TEXT_BYTES = 4 * 1024 * 1024
MAX_THUMBNAIL_BYTES = 1 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PROJECT_MANIFEST = "alloy/project.json"
PROJECT_THUMBNAIL = "alloy/thumbnail.png"
BATCH_MANIFEST = "alloy-batch.json"
MAX_PROJECT_PLATES = 8
MAX_PROJECT_MODELS_PER_PLATE = 32
PROJECT_MODEL_PATH = re.compile(r"models/[0-9]{4}\.(?:stl|obj|3mf)\Z")
BATCH_ARTIFACT_PATH = re.compile(r"plates/plate-[0-9]{2}\.gcode\.3mf\Z")
MAX_BATCH_BYTES = 512 * 1024 * 1024
MAX_BATCH_ENTRY_BYTES = 256 * 1024 * 1024

FLOAT = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
VERTEX_RE = re.compile(r"^\s*vertex\s+(" + FLOAT + r")\s+(" + FLOAT + r")\s+(" + FLOAT + r")\s*$", re.I)
NUMBER_RE = re.compile(FLOAT)

Vertex = tuple[float, float, float]
Triangle = tuple[Vertex, Vertex, Vertex]


class AlloyError(RuntimeError):
    """A user-actionable validation or slicing error."""


@dataclass(frozen=True)
class PartStats:
    name: str
    triangles: int
    size_mm: tuple[float, float, float]


@dataclass(frozen=True)
class ModelStats:
    path: str
    format: str
    bytes: int
    triangles: int
    solids: int
    size_mm: tuple[float, float, float]
    min_mm: tuple[float, float, float]
    max_mm: tuple[float, float, float]
    boundary_edges: int
    non_manifold_edges: int
    degenerate_facets: int
    parts: tuple[PartStats, ...]


def _finite_vertex(values: Iterable[str], source: str) -> Vertex:
    vertex = tuple(float(value) for value in values)
    if len(vertex) != 3 or not all(math.isfinite(value) for value in vertex):
        raise AlloyError(f"{source}: model contains a non-finite vertex")
    return vertex  # type: ignore[return-value]


def _bounds(triangles: list[Triangle]) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
    if not triangles:
        raise AlloyError("model contains no triangles")
    values = [vertex for triangle in triangles for vertex in triangle]
    minimum = tuple(min(vertex[axis] for vertex in values) for axis in range(3))
    maximum = tuple(max(vertex[axis] for vertex in values) for axis in range(3))
    return minimum, maximum


def _size(minimum: tuple[float, float, float], maximum: tuple[float, float, float]) -> tuple[float, float, float]:
    result = tuple(maximum[index] - minimum[index] for index in range(3))
    if any(not math.isfinite(value) or value <= 0 for value in result):
        raise AlloyError("model has a zero-sized or non-finite axis")
    return tuple(round(value, 5) for value in result)


def _key(vertex: Vertex) -> tuple[float, float, float]:
    return tuple(round(value, 6) for value in vertex)


def _connected_groups(triangles: list[Triangle]) -> list[list[int]]:
    parent = list(range(len(triangles)))
    rank = [0] * len(triangles)

    def find(value: int) -> int:
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    def union(first: int, second: int) -> None:
        first, second = find(first), find(second)
        if first == second:
            return
        if rank[first] < rank[second]:
            first, second = second, first
        parent[second] = first
        if rank[first] == rank[second]:
            rank[first] += 1

    owners: dict[tuple[float, float, float], int] = {}
    for index, triangle in enumerate(triangles):
        for vertex in triangle:
            previous = owners.setdefault(_key(vertex), index)
            union(previous, index)
    groups: dict[int, list[int]] = {}
    for index in range(len(triangles)):
        groups.setdefault(find(index), []).append(index)
    return list(groups.values())


def _geometry_report(triangles: list[Triangle]) -> tuple[int, int, int, int]:
    """Return connected solids, boundary edges, non-manifold edges, degenerates."""
    edges: dict[tuple[tuple[float, float, float], tuple[float, float, float]], int] = {}
    degenerate = 0
    for index, triangle in enumerate(triangles):
        first, second, third = triangle
        ab = (second[0] - first[0], second[1] - first[1], second[2] - first[2])
        ac = (third[0] - first[0], third[1] - first[1], third[2] - first[2])
        cross = (
            ab[1] * ac[2] - ab[2] * ac[1],
            ab[2] * ac[0] - ab[0] * ac[2],
            ab[0] * ac[1] - ab[1] * ac[0],
        )
        if sum(value * value for value in cross) <= 1e-16:
            degenerate += 1
        points = [_key(vertex) for vertex in triangle]
        for first_point, second_point in zip(points, points[1:] + points[:1]):
            edge = tuple(sorted((first_point, second_point)))
            edges[edge] = edges.get(edge, 0) + 1
    solids = len(_connected_groups(triangles))
    boundary = sum(count == 1 for count in edges.values())
    non_manifold = sum(count > 2 for count in edges.values())
    return solids, boundary, non_manifold, degenerate


def _stats(path: Path, model_format: str, triangles: list[Triangle], parts: list[PartStats] | None = None) -> ModelStats:
    if not triangles or len(triangles) > MAX_TRIANGLES:
        raise AlloyError(f"{path}: expected 1..{MAX_TRIANGLES} triangles")
    minimum, maximum = _bounds(triangles)
    size = _size(minimum, maximum)
    solids, boundary, non_manifold, degenerate = _geometry_report(triangles)
    if parts is None:
        groups = _connected_groups(triangles)
        if len(groups) <= 1:
            parts = [PartStats(path.name, len(triangles), size)]
        else:
            parts = []
            lower_name = path.name.lower()
            for index, group in enumerate(groups):
                group_triangles = [triangles[triangle] for triangle in group]
                group_minimum, group_maximum = _bounds(group_triangles)
                if len(groups) == 2 and "box" in lower_name and "lid" in lower_name:
                    label = ("Box", "Lid")[index]
                else:
                    label = f"Solid {index + 1}"
                parts.append(PartStats(label, len(group), _size(group_minimum, group_maximum)))
    return ModelStats(
        path=str(path),
        format=model_format,
        bytes=path.stat().st_size,
        triangles=len(triangles),
        solids=solids,
        size_mm=size,
        min_mm=tuple(round(value, 5) for value in minimum),
        max_mm=tuple(round(value, 5) for value in maximum),
        boundary_edges=boundary,
        non_manifold_edges=non_manifold,
        degenerate_facets=degenerate,
        parts=tuple(parts),
    )


def _read_bounded(path: Path) -> bytes:
    if not path.is_file():
        raise AlloyError(f"not a file: {path}")
    size = path.stat().st_size
    if size <= 0:
        raise AlloyError(f"empty model: {path}")
    if size > MAX_INPUT_BYTES:
        raise AlloyError(f"{path}: exceeds the {MAX_INPUT_BYTES // (1024 * 1024)} MB import limit")
    return path.read_bytes()


def _read_stl(path: Path, data: bytes) -> list[Triangle]:
    triangles: list[Triangle] = []
    if len(data) >= 84:
        count = int.from_bytes(data[80:84], "little", signed=False)
        expected = 84 + count * 50
        if 0 < count <= MAX_TRIANGLES and expected <= len(data):
            for index in range(count):
                offset = 84 + index * 50 + 12
                vertices = []
                for vertex_index in range(3):
                    start = offset + vertex_index * 12
                    # STL stores these as IEEE-754 little-endian binary32 values.
                    values = struct.unpack_from("<fff", data, start)
                    vertices.append(_finite_vertex((str(value) for value in values), str(path)))
                triangles.append((vertices[0], vertices[1], vertices[2]))
            return triangles

    vertices: list[Vertex] = []
    for line_number, line in enumerate(_iter_stl_lines(path, data), 1):
        match = VERTEX_RE.match(line)
        if match:
            vertices.append(_finite_vertex(match.groups(), str(path)))
            if len(vertices) > MAX_TRIANGLES * 3:
                raise AlloyError(f"{path}: exceeds the supported triangle count")
    if len(vertices) < 3 or len(vertices) % 3:
        raise AlloyError(f"{path}: ASCII STL has no complete triangles")
    return [tuple(vertices[index:index + 3]) for index in range(0, len(vertices), 3)]  # type: ignore[misc]


def _iter_stl_lines(path: Path, data: bytes) -> Iterable[str]:
    """Yield UTF-8 STL lines without slicing a hostile oversized line first."""
    start = 0
    while True:
        end = data.find(b"\n", start)
        terminal = end < 0
        if terminal:
            end = len(data)
        if end - start > MAX_STL_LINE_CHARS:
            raise AlloyError(f"{path}: STL line exceeds the 1 MB limit")
        raw = data[start:end]
        if raw.endswith(b"\r"):
            raw = raw[:-1]
        try:
            yield raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise AlloyError(f"{path}: not a supported ASCII or binary STL") from exc
        if terminal:
            return
        start = end + 1


def _obj_index(raw: str, vertex_count: int, path: Path, line_number: int) -> int:
    value = raw.split("/", 1)[0]
    if not value:
        raise AlloyError(f"{path}: OBJ face has no vertex index at line {line_number}")
    try:
        index = int(value)
    except ValueError as exc:
        raise AlloyError(f"{path}: OBJ face has an invalid vertex index at line {line_number}") from exc
    if index == 0:
        raise AlloyError(f"{path}: OBJ vertex index cannot be zero at line {line_number}")
    resolved = index - 1 if index > 0 else vertex_count + index
    if resolved < 0 or resolved >= vertex_count:
        raise AlloyError(f"{path}: OBJ face references a missing vertex at line {line_number}")
    return resolved


def _obj_part_name(arguments: list[str], fallback: str, ordinal: int) -> str:
    value = " ".join(arguments).strip()
    if not value:
        value = f"{fallback} · part {ordinal}"
    return value[:120]


def _part_stats(name: str, triangles: list[Triangle]) -> PartStats:
    minimum, maximum = _bounds(triangles)
    return PartStats(name, len(triangles), _size(minimum, maximum))


def _read_obj(path: Path, data: bytes) -> tuple[list[Triangle], list[PartStats] | None]:
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise AlloyError(f"{path}: OBJ is not valid UTF-8") from exc
    vertices: list[Vertex] = []
    triangles: list[Triangle] = []
    parts: list[PartStats] = []
    part_name = path.name
    part_start = 0
    has_named_parts = False
    for line_number, raw_line in enumerate(text.splitlines(), 1):
        if len(raw_line) > MAX_OBJ_LINE_CHARS:
            raise AlloyError(f"{path}: OBJ line {line_number} exceeds the 1 MB limit")
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = line.split()
        directive, arguments = fields[0], fields[1:]
        if directive == "v":
            if len(arguments) < 3:
                raise AlloyError(f"{path}: OBJ vertex is incomplete at line {line_number}")
            if len(vertices) >= MAX_OBJ_VERTICES:
                raise AlloyError(f"{path}: exceeds the supported vertex count")
            vertices.append(_finite_vertex(arguments[:3], f"{path}: OBJ line {line_number}"))
        elif directive in ("o", "g"):
            if len(triangles) > part_start:
                parts.append(_part_stats(part_name, triangles[part_start:]))
            part_name = _obj_part_name(arguments, path.name, len(parts) + 1)
            part_start = len(triangles)
            has_named_parts = True
        elif directive == "f":
            if len(arguments) < 3:
                raise AlloyError(f"{path}: OBJ face is incomplete at line {line_number}")
            if len(arguments) > MAX_OBJ_FACE_VERTICES:
                raise AlloyError(f"{path}: OBJ face has too many vertices at line {line_number}")
            indices = [_obj_index(value, len(vertices), path, line_number) for value in arguments]
            for index in range(1, len(indices) - 1):
                if len(triangles) >= MAX_TRIANGLES:
                    raise AlloyError(f"{path}: exceeds the supported triangle count")
                triangles.append((vertices[indices[0]], vertices[indices[index]], vertices[indices[index + 1]]))
    if not triangles:
        raise AlloyError(f"{path}: OBJ contains no complete faces")
    if len(triangles) > part_start:
        parts.append(_part_stats(part_name, triangles[part_start:]))
    return triangles, parts if has_named_parts else None


def _likely_obj(data: bytes) -> bool:
    sample = data[:64 * 1024].decode("utf-8", errors="ignore")
    vertex = False
    face = False
    for raw_line in sample.splitlines():
        line = raw_line.strip()
        if line.startswith(("v ", "v\t")):
            vertex = True
        if line.startswith(("f ", "f\t")):
            face = True
        if vertex and face:
            return True
    return False


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _children(element: ElementTree.Element, name: str) -> list[ElementTree.Element]:
    return [child for child in element if _local_name(child.tag) == name]


def _attr_float(element: ElementTree.Element, name: str, source: str, default: float | None = None) -> float:
    raw = element.attrib.get(name)
    if raw is None and default is not None:
        return default
    if raw is None:
        raise AlloyError(f"{source}: 3MF element is missing {name}")
    try:
        value = float(raw)
    except ValueError as exc:
        raise AlloyError(f"{source}: invalid 3MF number {raw!r}") from exc
    if not math.isfinite(value):
        raise AlloyError(f"{source}: non-finite 3MF number")
    return value


def _transform(raw: str | None, source: str) -> tuple[float, ...]:
    if not raw:
        return (1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
    values = [float(value) for value in NUMBER_RE.findall(raw)]
    if len(values) != 12 or not all(math.isfinite(value) for value in values):
        raise AlloyError(f"{source}: 3MF transform must contain 12 finite values")
    # 3MF stores the 3x4 matrix as columns followed by translation.
    return (values[0], values[3], values[6], values[9], values[1], values[4], values[7], values[10], values[2], values[5], values[8], values[11])


def _multiply(first: tuple[float, ...], second: tuple[float, ...]) -> tuple[float, ...]:
    # Row-major 3x4 affine matrices: first * second.
    result = [0.0] * 12
    for row in range(3):
        for column in range(3):
            result[row * 4 + column] = sum(first[row * 4 + k] * second[k * 4 + column] for k in range(3))
        result[row * 4 + 3] = sum(first[row * 4 + k] * second[k * 4 + 3] for k in range(3)) + first[row * 4 + 3]
    return tuple(result)


def _apply(transform: tuple[float, ...], vertex: Vertex, scale: float) -> Vertex:
    x, y, z = vertex
    return (
        (transform[0] * x + transform[1] * y + transform[2] * z + transform[3]) * scale,
        (transform[4] * x + transform[5] * y + transform[6] * z + transform[7]) * scale,
        (transform[8] * x + transform[9] * y + transform[10] * z + transform[11]) * scale,
    )


UNIT_SCALE = {"micron": 0.001, "millimeter": 1.0, "centimeter": 10.0, "meter": 1000.0, "inch": 25.4, "foot": 304.8}


@dataclass
class _Object:
    name: str
    triangles: list[Triangle]
    components: list[tuple[str, tuple[float, ...]]]


def _safe_zip_names(zf: ZipFile) -> list[str]:
    if len(zf.infolist()) > MAX_PACKAGE_ENTRIES:
        raise AlloyError("3MF contains too many ZIP entries")
    names: list[str] = []
    total = 0
    for info in zf.infolist():
        name = info.filename
        if not name or name.startswith("/") or "\\" in name or ".." in PurePosixPath(name).parts:
            raise AlloyError(f"3MF contains an unsafe ZIP entry: {name!r}")
        if name in names:
            raise AlloyError(f"3MF contains a duplicate ZIP entry: {name}")
        if info.file_size > MAX_3MF_ENTRY_BYTES:
            raise AlloyError(f"3MF entry exceeds the {MAX_3MF_ENTRY_BYTES // (1024 * 1024)} MB limit: {name}")
        total += info.file_size
        if total > MAX_3MF_TOTAL_BYTES:
            raise AlloyError("3MF decompressed content exceeds the 256 MB limit")
        names.append(name)
    return names


def _read_zip_text(zf: ZipFile, name: str) -> str:
    try:
        data = zf.read(name)
    except KeyError as exc:
        raise AlloyError(f"3MF is missing {name}") from exc
    if len(data) > MAX_3MF_ENTRY_BYTES:
        raise AlloyError(f"3MF entry exceeds the size limit: {name}")
    return data.decode("utf-8")


def _model_root(names: list[str], zf: ZipFile) -> str:
    conventional = "3D/3dmodel.model"
    if conventional not in names:
        raise AlloyError("3MF is missing 3D/3dmodel.model")
    if "_rels/.rels" not in names:
        return conventional
    root = _read_zip_text(zf, "_rels/.rels")
    if "3dmodel" not in root or "/3D/3dmodel.model" not in root:
        raise AlloyError("3MF root relationships do not target 3D/3dmodel.model")
    return conventional


def _read_3mf(path: Path, data: bytes) -> tuple[list[Triangle], list[PartStats]]:
    # ZipFile accepts file-like objects; keeping the bounded bytes avoids a
    # second filesystem race between validation and parsing.
    from io import BytesIO
    try:
        zf = ZipFile(BytesIO(data))
    except BadZipFile as exc:
        raise AlloyError(f"{path}: invalid 3MF ZIP container") from exc
    with zf:
        names = _safe_zip_names(zf)
        model_name = _model_root(names, zf)
        try:
            root = ElementTree.fromstring(_read_zip_text(zf, model_name))
        except ElementTree.ParseError as exc:
            raise AlloyError(f"{path}: invalid 3MF model XML") from exc
        unit = UNIT_SCALE.get(root.attrib.get("unit", "millimeter").lower(), 1.0)
        objects: dict[str, _Object] = {}
        resources = next((child for child in root if _local_name(child.tag) == "resources"), None)
        if resources is None:
            raise AlloyError(f"{path}: 3MF is missing resources")
        for element in resources.iter():
            if _local_name(element.tag) != "object":
                continue
            object_id = element.attrib.get("id")
            if not object_id or object_id in objects:
                raise AlloyError(f"{path}: duplicate or missing 3MF object id")
            name = element.attrib.get("name") or f"Object {object_id}"
            mesh = next((child for child in element if _local_name(child.tag) == "mesh"), None)
            triangles: list[Triangle] = []
            if mesh is not None:
                vertices_element = next((child for child in mesh if _local_name(child.tag) == "vertices"), None)
                triangles_element = next((child for child in mesh if _local_name(child.tag) == "triangles"), None)
                if vertices_element is None or triangles_element is None:
                    raise AlloyError(f"{path}: object {object_id} has an incomplete mesh")
                vertices = [(_attr_float(vertex, "x", str(path)), _attr_float(vertex, "y", str(path)), _attr_float(vertex, "z", str(path))) for vertex in _children(vertices_element, "vertex")]
                for triangle in _children(triangles_element, "triangle"):
                    indices = [int(triangle.attrib.get(key, "-1")) for key in ("v1", "v2", "v3")]
                    if any(index < 0 or index >= len(vertices) for index in indices):
                        raise AlloyError(f"{path}: object {object_id} contains an invalid triangle index")
                    triangles.append(tuple(vertices[index] for index in indices))  # type: ignore[arg-type]
            components_element = next((child for child in element if _local_name(child.tag) == "components"), None)
            components: list[tuple[str, tuple[float, ...]]] = []
            if components_element is not None:
                for component in _children(components_element, "component"):
                    component_id = component.attrib.get("objectid")
                    if not component_id:
                        raise AlloyError(f"{path}: 3MF component is missing objectid")
                    components.append((component_id, _transform(component.attrib.get("transform"), str(path))))
            if not triangles and not components:
                raise AlloyError(f"{path}: 3MF object {object_id} is empty")
            objects[object_id] = _Object(name, triangles, components)

        build = next((child for child in root if _local_name(child.tag) == "build"), None)
        items = _children(build, "item") if build is not None else []
        if not items:
            raise AlloyError(f"{path}: 3MF build has no items")
        all_triangles: list[Triangle] = []
        parts: list[PartStats] = []

        def expand(object_id: str, transform: tuple[float, ...], stack: tuple[str, ...]) -> list[Triangle]:
            if object_id in stack:
                raise AlloyError(f"{path}: 3MF component graph contains a cycle")
            if object_id not in objects:
                raise AlloyError(f"{path}: 3MF references unknown object {object_id}")
            current = objects[object_id]
            output = [_apply(transform, vertex, unit) for triangle in current.triangles for vertex in triangle]
            result = [tuple(output[index:index + 3]) for index in range(0, len(output), 3)]  # type: ignore[misc]
            for child_id, child_transform in current.components:
                result.extend(expand(child_id, _multiply(transform, child_transform), (*stack, object_id)))
            return result

        for index, item in enumerate(items):
            object_id = item.attrib.get("objectid")
            if not object_id:
                raise AlloyError(f"{path}: 3MF build item is missing objectid")
            item_triangles = expand(object_id, _transform(item.attrib.get("transform"), str(path)), ())
            if not item_triangles:
                raise AlloyError(f"{path}: 3MF build item {object_id} is empty")
            if len(all_triangles) + len(item_triangles) > MAX_TRIANGLES:
                raise AlloyError(f"{path}: exceeds the supported triangle count")
            all_triangles.extend(item_triangles)
            minimum, maximum = _bounds(item_triangles)
            label = item.attrib.get("name") or objects[object_id].name or f"Part {index + 1}"
            parts.append(PartStats(label, len(item_triangles), _size(minimum, maximum)))
        return all_triangles, parts


def inspect_model(path: Path) -> ModelStats:
    data = _read_bounded(path)
    suffix = path.name.lower()
    # A document provider's suffix can be wrong or absent. Prefer bounded
    # content signatures, then use the suffix to choose the strict parser for
    # malformed files that should fail closed instead of being reinterpreted.
    if data[:4] == b"PK\x03\x04" or data[:4] == b"PK\x05\x06" or data[:4] == b"PK\x07\x08":
        triangles, parts = _read_3mf(path, data)
        return _stats(path, "3mf", triangles, parts)
    if _likely_obj(data):
        triangles, parts = _read_obj(path, data)
        return _stats(path, "obj", triangles, parts)
    if suffix.endswith(".3mf"):
        triangles, parts = _read_3mf(path, data)
        return _stats(path, "3mf", triangles, parts)
    if suffix.endswith(".obj"):
        triangles, parts = _read_obj(path, data)
        return _stats(path, "obj", triangles, parts)
    if suffix.endswith(".stl"):
        return _stats(path, "stl", _read_stl(path, data))
    if _likely_obj(data):
        triangles, parts = _read_obj(path, data)
        return _stats(path, "obj", triangles, parts)
    raise AlloyError(f"{path}: unsupported model format; use .stl, .obj or .3mf")


def _safe_project_entries(zf: ZipFile) -> dict[str, object]:
    """Validate project ZIP names and both compressed/decompressed budgets."""
    infos = zf.infolist()
    if len(infos) > MAX_PACKAGE_ENTRIES:
        raise AlloyError("Alloy project contains too many ZIP entries")
    entries: dict[str, object] = {}
    uncompressed = 0
    compressed = 0
    for info in infos:
        name = info.filename
        if (not name or info.is_dir() or name.startswith("/") or "\\" in name
                or ".." in PurePosixPath(name).parts
                or any(ord(character) < 0x20 or ord(character) == 0x7f for character in name)
                or name in entries):
            raise AlloyError(f"Alloy project contains an unsafe or duplicate ZIP entry: {name!r}")
        if info.file_size > MAX_3MF_ENTRY_BYTES:
            raise AlloyError(f"Alloy project entry exceeds the 128 MB limit: {name}")
        uncompressed += info.file_size
        compressed += info.compress_size
        if uncompressed > MAX_3MF_TOTAL_BYTES or compressed > MAX_INPUT_BYTES:
            raise AlloyError("Alloy project ZIP data exceeds the 256 MB limit")
        entries[name] = info
    return entries


def _project_int(value: object, key: str, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise AlloyError(f"Alloy project integer is invalid: {key}")
    return value


def _project_name(value: object, fallback: str) -> str:
    if value is None or (isinstance(value, str) and not value.strip()):
        return fallback
    if not isinstance(value, str) or not value.strip():
        raise AlloyError("Alloy project name is invalid")
    return value.strip()[:150]


def _project_manifest(zf: ZipFile, entries: dict[str, object]) -> tuple[dict[str, object], int]:
    if PROJECT_MANIFEST not in entries:
        raise AlloyError("Alloy project manifest is missing")
    info = entries[PROJECT_MANIFEST]
    if not hasattr(info, "file_size") or info.file_size > MAX_VALIDATION_TEXT_BYTES:  # type: ignore[union-attr]
        raise AlloyError("Alloy project manifest is too large")
    try:
        value = json.loads(zf.read(PROJECT_MANIFEST).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AlloyError("Alloy project manifest is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict) or value.get("format") != "alloy-project":
        raise AlloyError("Alloy project manifest format is invalid")
    version = _project_int(value.get("version"), "version", 1, 2)
    return value, version


def _copy_project_model(zf: ZipFile, info: object, destination: Path) -> tuple[int, str]:
    if not hasattr(info, "file_size"):
        raise AlloyError("Alloy project model entry is invalid")
    expected_size = info.file_size  # type: ignore[union-attr]
    if expected_size <= 0 or expected_size > 64 * 1024 * 1024:
        raise AlloyError("Alloy project model exceeds the 64 MB limit")
    digest = hashlib.sha256()
    copied = 0
    # The caller has already validated the ZIP name and destination directory.
    with zf.open(info) as source, destination.open("wb") as output:  # type: ignore[arg-type]
        while True:
            chunk = source.read(32 * 1024)
            if not chunk:
                break
            copied += len(chunk)
            if copied > expected_size or copied > 64 * 1024 * 1024:
                raise AlloyError("Alloy project model decompression exceeded its limit")
            digest.update(chunk)
            output.write(chunk)
    if copied != expected_size:
        raise AlloyError("Alloy project model size does not match its ZIP record")
    return copied, digest.hexdigest()


def inspect_project(path: Path) -> dict[str, object]:
    """Validate a portable .alloy.zip and inspect every referenced model."""
    if not path.is_file() or path.stat().st_size <= 0:
        raise AlloyError(f"missing or empty Alloy project: {path}")
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise AlloyError("Alloy project exceeds the 256 MB limit")
    try:
        with ZipFile(path) as zf:
            entries = _safe_project_entries(zf)
            manifest, version = _project_manifest(zf, entries)
            active_declared = _project_int(manifest.get("active_plate", 0), "active_plate", 0, MAX_PROJECT_PLATES - 1)
            encoded_plates = manifest.get("plates")
            if not isinstance(encoded_plates, list) or not encoded_plates or len(encoded_plates) > MAX_PROJECT_PLATES:
                raise AlloyError("Alloy project plate list is invalid")

            references: dict[str, dict[str, object]] = {}
            plates: list[dict[str, object]] = []
            seen_indices: set[int] = set()
            for ordinal, encoded in enumerate(encoded_plates, 1):
                if not isinstance(encoded, dict):
                    raise AlloyError("Alloy project plate is invalid")
                index = _project_int(encoded.get("index"), "plate index", 0, MAX_PROJECT_PLATES - 1)
                if index in seen_indices:
                    raise AlloyError("Alloy project contains duplicate plate indices")
                seen_indices.add(index)
                encoded_models = encoded.get("models")
                if not isinstance(encoded_models, list) or len(encoded_models) > MAX_PROJECT_MODELS_PER_PLATE:
                    raise AlloyError("Alloy project model list is invalid")
                plate_paths: set[str] = set()
                models: list[dict[str, object]] = []
                for encoded_model in encoded_models:
                    if not isinstance(encoded_model, dict):
                        raise AlloyError("Alloy project model record is invalid")
                    model_path = encoded_model.get("path")
                    if not isinstance(model_path, str) or not PROJECT_MODEL_PATH.fullmatch(model_path):
                        raise AlloyError(f"Alloy project model path is invalid: {model_path!r}")
                    if model_path in plate_paths:
                        raise AlloyError(f"Alloy project model is duplicated on plate {index + 1}: {model_path}")
                    if model_path not in entries:
                        raise AlloyError(f"Alloy project model entry is missing: {model_path}")
                    plate_paths.add(model_path)
                    record = {"path": model_path, "name": _project_name(encoded_model.get("name"), "model.stl")}
                    if version >= 2:
                        record["declared_bytes"] = _project_int(encoded_model.get("bytes"), "model bytes", 1, 64 * 1024 * 1024)
                        digest = encoded_model.get("sha256")
                        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                            raise AlloyError(f"Alloy project model digest is invalid: {model_path}")
                        record["declared_sha256"] = digest.lower()
                    references.setdefault(model_path, record)
                    models.append(record)
                plates.append({
                    "index": index,
                    "name": _project_name(encoded.get("name"), f"Plate {ordinal}"),
                    "models": models,
                })

            model_entries = {name for name in entries if PROJECT_MODEL_PATH.fullmatch(name)}
            unexpected_entries = set(entries) - model_entries - {PROJECT_MANIFEST, PROJECT_THUMBNAIL}
            if unexpected_entries:
                raise AlloyError(f"Alloy project contains an unexpected entry: {sorted(unexpected_entries)[0]}")
            if version >= 2 and model_entries != set(references):
                raise AlloyError("Alloy project contains an unreferenced or missing model entry")
            if active_declared not in seen_indices:
                active_declared = plates[0]["index"]  # type: ignore[assignment]

            with tempfile.TemporaryDirectory(prefix=".alloy-project-inspect-") as temporary:
                scratch = Path(temporary)
                inspected: dict[str, dict[str, object]] = {}
                for model_path, record in references.items():
                    info = entries[model_path]
                    local = scratch / PurePosixPath(model_path).name
                    actual_bytes, actual_digest = _copy_project_model(zf, info, local)
                    if version >= 2 and (actual_bytes != record["declared_bytes"] or actual_digest != record["declared_sha256"]):
                        raise AlloyError(f"Alloy project model integrity check failed: {model_path}")
                    model_stats = asdict(inspect_model(local))
                    model_stats["path"] = model_path
                    inspected[model_path] = {
                        "path": model_path,
                        "name": record["name"],
                        "bytes": actual_bytes,
                        "sha256": actual_digest,
                        "inspection": model_stats,
                    }

            for plate in plates:
                # A source can be reused on multiple plates with different
                # friendly labels; keep the per-plate manifest name while
                # reusing the expensive geometry inspection.
                plate["models"] = [
                    dict(inspected[model["path"]], name=model["name"])
                    for model in plate["models"]
                ]  # type: ignore[index]
                plate["model_count"] = len(plate["models"])
            thumbnail = PROJECT_THUMBNAIL in entries
            if thumbnail:
                thumbnail_info = entries[PROJECT_THUMBNAIL]
                if thumbnail_info.file_size > MAX_THUMBNAIL_BYTES:  # type: ignore[union-attr]
                    raise AlloyError("Alloy project thumbnail exceeds the 1 MB limit")
                if zf.read(PROJECT_THUMBNAIL)[:8] != PNG_SIGNATURE:
                    raise AlloyError("Alloy project thumbnail is not a PNG")
            return {
                "path": str(path),
                "format": "alloy-project",
                "version": version,
                "bytes": path.stat().st_size,
                "active_plate": active_declared,
                "plates": plates,
                "model_count": len(references),
                "total_model_bytes": sum(model["bytes"] for model in inspected.values()),
                "thumbnail": thumbnail,
                "validated": True,
            }
    except (BadZipFile, UnicodeDecodeError) as exc:
        raise AlloyError(f"invalid Alloy project: {path}") from exc


def _artifact_names(zf: ZipFile) -> list[str]:
    names: list[str] = []
    total = 0
    infos = zf.infolist()
    if len(infos) > MAX_PACKAGE_ENTRIES:
        raise AlloyError("G-code package contains too many ZIP entries")
    for info in infos:
        name = info.filename
        if not name or name.startswith("/") or "\\" in name or ".." in PurePosixPath(name).parts:
            raise AlloyError(f"G-code package contains an unsafe entry: {name!r}")
        if name in names:
            raise AlloyError(f"G-code package contains a duplicate entry: {name}")
        if info.file_size > MAX_3MF_ENTRY_BYTES:
            raise AlloyError(f"G-code package entry exceeds the 128 MB limit: {name}")
        total += info.file_size
        if total > MAX_3MF_TOTAL_BYTES:
            raise AlloyError("G-code package decompressed content exceeds the 256 MB limit")
        names.append(name)
    return names


def validate_gcode_package(path: Path) -> dict[str, object]:
    if not path.is_file() or path.stat().st_size <= 0:
        raise AlloyError(f"missing or empty G-code package: {path}")
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise AlloyError("G-code package exceeds the 256 MB limit")
    try:
        with ZipFile(path) as zf:
            names = _artifact_names(zf)
            required = {
                "[Content_Types].xml",
                "_rels/.rels",
                "3D/3dmodel.model",
                "3D/_rels/3dmodel.model.rels",
                "Metadata/plate_1.gcode",
                "Metadata/plate_1.json",
                "Metadata/slice_info.config",
            }
            missing = sorted(required - set(names))
            if missing:
                raise AlloyError(f"G-code package is missing: {', '.join(missing)}")
            for name in names:
                if name.lower().endswith(".png"):
                    info = zf.getinfo(name)
                    if info.file_size > MAX_THUMBNAIL_BYTES:
                        raise AlloyError(f"G-code package thumbnail exceeds the 1 MB limit: {name}")
                    with zf.open(info) as thumbnail:
                        if thumbnail.read(8) != PNG_SIGNATURE:
                            raise AlloyError(f"G-code package thumbnail is not a PNG: {name}")
            content_types = _read_validation_text(zf, "[Content_Types].xml")
            root_relationships = _read_validation_text(zf, "_rels/.rels")
            model_relationships = _read_validation_text(zf, "3D/_rels/3dmodel.model.rels")
            model = _read_validation_text(zf, "3D/3dmodel.model")
            metadata = _read_validation_text(zf, "Metadata/plate_1.json")
            config = _read_validation_text(zf, "Metadata/slice_info.config")
            gcode_md5 = None
            if "Metadata/plate_1.gcode.md5" in names:
                gcode_md5 = _read_validation_text(zf, "Metadata/plate_1.gcode.md5").strip()
            optional_model_settings = None
            if "Metadata/model_settings.config" in names:
                optional_model_settings = _read_validation_text(zf, "Metadata/model_settings.config")
            optional_project_settings = None
            if "Metadata/project_settings.config" in names:
                optional_project_settings = _read_validation_text(zf, "Metadata/project_settings.config")
            optional_print_profile = None
            if "Metadata/print_profile.config" in names:
                optional_print_profile = _read_validation_text(zf, "Metadata/print_profile.config")
            actual_gcode_md5 = hashlib.md5()
            with zf.open("Metadata/plate_1.gcode") as gcode_stream:
                for chunk in iter(lambda: gcode_stream.read(32 * 1024), b""):
                    actual_gcode_md5.update(chunk)
    except (BadZipFile, UnicodeDecodeError) as exc:
        raise AlloyError(f"invalid G-code package: {path}") from exc
    model_type = "3dmodel.model" in content_types or 'Extension="model"' in content_types
    gcode_type = "Metadata/plate_1.gcode" in content_types or 'Extension="gcode"' in content_types
    if not model_type or not gcode_type or "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" not in content_types:
        raise AlloyError("G-code package content types do not describe its model and toolpath")
    if "3dmodel" not in root_relationships or "/3D/3dmodel.model" not in root_relationships:
        raise AlloyError("G-code package root relationship does not target the 3D model")
    if "Relationships" not in model_relationships:
        raise AlloyError("G-code package model relationships are malformed")
    for marker in ("<model", "<resources", "<object", "<mesh", "<vertices", "<triangles", "<build", "<item"):
        if marker not in model:
            raise AlloyError(f"G-code package model is missing {marker}")
    for marker in ("\"engine_verified\":", "\"printer\":", "\"material\":", "\"layer_height_mm\":", "\"first_layer_height_mm\":", "\"infill_percent\":", "\"perimeters\":", "\"top_layers\":", "\"bottom_layers\":", "\"supports\":", "\"support_threshold_degrees\":", "\"nozzle_temperature_c\":", "\"first_layer_nozzle_temperature_c\":", "\"bed_temperature_c\":", "\"first_layer_bed_temperature_c\":", "\"extrusion_multiplier\":", "\"max_volumetric_speed_mm3_s\":"):
        if marker not in metadata:
            raise AlloyError(f"G-code package metadata is missing {marker}")
    canonical_slice_info = ("<config" in config and "<plate" in config
                            and 'key="index"' in config and 'key="gcode_file"' in config
                            and "Metadata/plate_1.gcode" in config)
    if not canonical_slice_info:
        for marker in ("[print]", "engine_verified=", "layer_height_mm=", "first_layer_height_mm=", "infill_percent=", "perimeters=", "top_layers=", "bottom_layers=", "supports=", "support_threshold_degrees=", "nozzle_temperature_c=", "first_layer_nozzle_temperature_c=", "bed_temperature_c=", "first_layer_bed_temperature_c=", "extrusion_multiplier=", "max_volumetric_speed_mm3_s="):
            if marker not in config:
                raise AlloyError(f"G-code package config is missing canonical slice-info XML or {marker}")
    if optional_model_settings is not None and not ("<config" in optional_model_settings and "<object" in optional_model_settings and "<part" in optional_model_settings):
        raise AlloyError("G-code package model settings are malformed")
    if optional_project_settings is not None and not ('"type":"project"' in optional_project_settings and '"printer_model":' in optional_project_settings):
        raise AlloyError("G-code package project settings are malformed")
    if optional_print_profile is not None and not all(marker in optional_print_profile for marker in ("layer_height", "nozzle_diameter", "filament_diameter")):
        raise AlloyError("G-code package print profile is incomplete")
    if gcode_md5 is not None and (not re.fullmatch(r"[0-9a-fA-F]{32}", gcode_md5) or gcode_md5.lower() != actual_gcode_md5.hexdigest()):
        raise AlloyError("G-code package MD5 does not match its toolpath")
    for marker in ("G90", "M104 S0"):
        if not _gcode_contains(path, marker):
            raise AlloyError(f"G-code package toolpath is missing {marker}")
    if not (_gcode_contains(path, "M82") or _gcode_contains(path, "M83")):
        raise AlloyError("G-code package toolpath is missing M82 or M83")
    return {"path": str(path), "bytes": path.stat().st_size, "gcode_entry": "Metadata/plate_1.gcode", "validated": True}


def validate_batch_archive(path: Path) -> dict[str, object]:
    """Validate a phone-exported .alloy-batch.zip and each nested artifact."""
    if not path.is_file() or path.stat().st_size <= 0:
        raise AlloyError(f"missing or empty Alloy batch archive: {path}")
    if path.stat().st_size > MAX_BATCH_BYTES:
        raise AlloyError("Alloy batch archive exceeds the 512 MB limit")
    actual: dict[str, dict[str, object]] = {}
    manifest: bytes | None = None
    total = 0
    try:
        with ZipFile(path) as zf:
            infos = zf.infolist()
            if len(infos) > MAX_PROJECT_PLATES + 1:
                raise AlloyError("Alloy batch contains too many ZIP entries")
            names: set[str] = set()
            for info in infos:
                name = info.filename
                if (not name or info.is_dir() or name.startswith("/") or "\\" in name
                        or ".." in PurePosixPath(name).parts or name in names):
                    raise AlloyError(f"Alloy batch contains an unsafe or duplicate entry: {name!r}")
                names.add(name)
                if name == BATCH_MANIFEST:
                    if info.file_size > MAX_VALIDATION_TEXT_BYTES:
                        raise AlloyError("Alloy batch manifest is too large")
                    manifest = zf.read(info)
                    continue
                if not BATCH_ARTIFACT_PATH.fullmatch(name):
                    raise AlloyError(f"Alloy batch contains an unexpected entry: {name}")
                if info.file_size <= 0 or info.file_size > MAX_BATCH_ENTRY_BYTES:
                    raise AlloyError(f"Alloy batch artifact exceeds the 256 MB limit: {name}")
                with tempfile.NamedTemporaryFile(prefix="alloy-batch-", suffix=".gcode.3mf") as temporary:
                    copied = 0
                    digest = hashlib.sha256()
                    with zf.open(info) as source:
                        while True:
                            chunk = source.read(32 * 1024)
                            if not chunk:
                                break
                            copied += len(chunk)
                            total += len(chunk)
                            if copied > MAX_BATCH_ENTRY_BYTES or total > MAX_BATCH_BYTES:
                                raise AlloyError("Alloy batch decompressed data exceeds the 512 MB limit")
                            digest.update(chunk)
                            temporary.write(chunk)
                    temporary.flush()
                    nested = Path(temporary.name)
                    validate_gcode_package(nested)
                    actual[name] = {"bytes": copied, "sha256": digest.hexdigest()}
    except (BadZipFile, UnicodeDecodeError) as exc:
        raise AlloyError(f"invalid Alloy batch archive: {path}") from exc
    if manifest is None:
        raise AlloyError("Alloy batch manifest is missing")
    try:
        encoded = json.loads(manifest.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise AlloyError("Alloy batch manifest is not valid UTF-8 JSON") from exc
    if not isinstance(encoded, dict) or encoded.get("format") != "alloy-batch" or encoded.get("version") != 1:
        raise AlloyError("Alloy batch manifest format is invalid")
    plates = encoded.get("plates")
    if not isinstance(plates, list) or not plates or len(plates) > MAX_PROJECT_PLATES:
        raise AlloyError("Alloy batch manifest plate list is invalid")
    seen_indices: set[int] = set()
    referenced: set[str] = set()
    summaries: list[dict[str, object]] = []
    for plate in plates:
        if not isinstance(plate, dict) or isinstance(plate.get("index"), bool) or not isinstance(plate.get("index"), int):
            raise AlloyError("Alloy batch manifest plate is invalid")
        index = plate["index"]
        entry = plate.get("entry")
        if not 0 <= index < MAX_PROJECT_PLATES or index in seen_indices or not isinstance(entry, str):
            raise AlloyError("Alloy batch manifest plate index is invalid")
        expected = f"plates/plate-{index + 1:02d}.gcode.3mf"
        if entry != expected or entry in referenced or entry not in actual:
            raise AlloyError("Alloy batch manifest artifact reference is invalid")
        seen_indices.add(index)
        referenced.add(entry)
        declared_bytes = plate.get("bytes")
        digest = plate.get("sha256")
        if (isinstance(declared_bytes, bool) or not isinstance(declared_bytes, int)
                or declared_bytes != actual[entry]["bytes"]
                or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", digest)
                or digest.lower() != actual[entry]["sha256"]):
            raise AlloyError(f"Alloy batch artifact integrity check failed: {entry}")
        summaries.append({
            "index": index,
            "name": _project_name(plate.get("name"), f"Plate {index + 1}"),
            "entry": entry,
            "bytes": declared_bytes,
            "sha256": digest.lower(),
            "layers": plate.get("layers"),
            "filament_mm": plate.get("filament_mm"),
            "print_time_seconds": plate.get("print_time_seconds"),
            "engine": plate.get("engine"),
            "engine_verified": plate.get("engine_verified"),
        })
    if referenced != set(actual):
        raise AlloyError("Alloy batch contains an unreferenced or missing artifact")
    return {
        "path": str(path),
        "format": "alloy-batch",
        "version": 1,
        "bytes": path.stat().st_size,
        "plates": summaries,
        "validated": True,
    }


def _read_validation_text(zf: ZipFile, name: str) -> str:
    info = zf.getinfo(name)
    if info.file_size > MAX_VALIDATION_TEXT_BYTES:
        raise AlloyError(f"G-code package text metadata exceeds the 4 MB validation limit: {name}")
    try:
        return zf.read(name).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise AlloyError(f"G-code package text entry is not UTF-8: {name}") from exc


def _gcode_contains(path: Path, marker: str) -> bool:
    with ZipFile(path) as zf, zf.open("Metadata/plate_1.gcode") as stream:
        needle = marker.encode("ascii")
        carry = b""
        while True:
            chunk = stream.read(32 * 1024)
            if not chunk:
                return needle in carry
            searchable = carry + chunk
            if needle in searchable:
                return True
            carry = searchable[-len(needle):]


ENGINE_NAMES = {"bambu": "bambu-studio", "orca": "orca-slicer"}


def find_executable(engine: str, requested: Path | None) -> Path:
    if requested is not None:
        if not requested.is_file() or not shutil.which(str(requested)):
            raise AlloyError(f"slicer executable is not runnable: {requested}")
        return requested
    found = shutil.which(ENGINE_NAMES[engine])
    if not found:
        raise AlloyError(f"could not find {ENGINE_NAMES[engine]!r} on PATH; pass --executable")
    return Path(found)


def build_engine_command(
    executable: Path,
    model: Path,
    workdir: Path,
    output: Path,
    machine: Path | None = None,
    process: Path | None = None,
    filament: Path | None = None,
) -> list[str]:
    command = [str(executable), "--debug", "2", "--arrange", "1", "--slice", "0"]
    if machine or process:
        if not machine or not process:
            raise AlloyError("--machine and --process must be supplied together")
        command.extend(["--load-settings", f"{machine};{process}"])
    if filament:
        command.extend(["--load-filaments", str(filament)])
    command.extend(["--allow-newer-file", "--outputdir", str(workdir), "--export-3mf", str(output), str(model)])
    return command


def slice_model(args: argparse.Namespace) -> dict[str, object]:
    model = args.model.resolve()
    inspect_model(model)
    if not args.output.name.lower().endswith(".gcode.3mf"):
        raise AlloyError("--output must end with .gcode.3mf")
    executable = find_executable(args.engine, args.executable.resolve() if args.executable else None)
    for profile in (args.machine, args.process, args.filament):
        if profile is not None and not profile.is_file():
            raise AlloyError(f"profile is not a file: {profile}")
    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and not args.overwrite:
        raise AlloyError(f"refusing to overwrite existing artifact: {destination} (use --overwrite)")
    with tempfile.TemporaryDirectory(prefix=".alloy-slice-", dir=str(destination.parent)) as temporary:
        workdir = Path(temporary)
        staged = workdir / destination.name
        command = build_engine_command(executable, model, workdir, staged, args.machine, args.process, args.filament)
        try:
            completed = subprocess.run(command, check=False, shell=False, text=True, capture_output=True, timeout=args.timeout)
        except subprocess.TimeoutExpired as exc:
            raise AlloyError(f"slicer timed out after {args.timeout} seconds") from exc
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout or "no slicer diagnostics").strip()
            raise AlloyError(f"slicer exited with status {completed.returncode}: {detail[-4000:]}")
        if not staged.is_file():
            candidates = sorted(workdir.glob("*.gcode.3mf"))
            if len(candidates) != 1:
                raise AlloyError("slicer completed but did not produce exactly one .gcode.3mf artifact")
            staged = candidates[0]
        validate_gcode_package(staged)
        staged.replace(destination)
    return {
        "artifact": str(destination),
        "bytes": destination.stat().st_size,
        "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
        "engine": args.engine,
        "executable": str(executable),
        "validated": True,
    }


def _print_json(value: object) -> None:
    print(json.dumps(value, indent=2, ensure_ascii=False))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="alloy-linux", description="Inspect models and safely bridge to a Linux slicer")
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect_parser = subparsers.add_parser("inspect", help="inspect STL, OBJ or 3MF geometry")
    inspect_parser.add_argument("models", nargs="+", type=Path)
    inspect_parser.add_argument("--json", action="store_true", dest="as_json")

    project_parser = subparsers.add_parser("inspect-project", help="validate and inspect a portable .alloy.zip project")
    project_parser.add_argument("project", type=Path)

    validate_parser = subparsers.add_parser("validate", help="validate a generated .gcode.3mf")
    validate_parser.add_argument("artifact", type=Path)

    batch_parser = subparsers.add_parser("validate-batch", help="validate a phone-exported .alloy-batch.zip")
    batch_parser.add_argument("batch", type=Path)

    slice_parser = subparsers.add_parser("slice", help="invoke an installed Linux slicer and validate its output")
    slice_parser.add_argument("model", type=Path)
    slice_parser.add_argument("--engine", choices=sorted(ENGINE_NAMES), default="orca")
    slice_parser.add_argument("--executable", type=Path)
    slice_parser.add_argument("--machine", type=Path, help="resolved machine JSON profile")
    slice_parser.add_argument("--process", type=Path, help="resolved process JSON profile")
    slice_parser.add_argument("--filament", type=Path, help="resolved filament JSON profile")
    slice_parser.add_argument("--output", type=Path, required=True, help="destination .gcode.3mf")
    slice_parser.add_argument("--timeout", type=int, default=3600)
    slice_parser.add_argument("--overwrite", action="store_true")

    args = parser.parse_args(argv)
    try:
        if args.command == "inspect":
            values = [asdict(inspect_model(model.resolve())) for model in args.models]
            _print_json(values if len(values) > 1 else values[0])
        elif args.command == "inspect-project":
            _print_json(inspect_project(args.project.resolve()))
        elif args.command == "validate":
            _print_json(validate_gcode_package(args.artifact.resolve()))
        elif args.command == "validate-batch":
            _print_json(validate_batch_archive(args.batch.resolve()))
        else:
            if args.timeout <= 0:
                raise AlloyError("--timeout must be positive")
            _print_json(slice_model(args))
        return 0
    except (AlloyError, OSError, ValueError) as exc:
        parser.exit(2, f"alloy-linux: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
