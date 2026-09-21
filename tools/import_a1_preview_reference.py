#!/usr/bin/env python3
"""Create Alloy's debug-only A1 reference mesh asset.

The command accepts either the supplied visual-review ZIP or the original
3MF directly::

    python3 tools/import_a1_preview_reference.py \
        "/path/to/3D Rendering Style Identification.zip" out.mesh
    python3 tools/import_a1_preview_reference.py \
        "/path/to/Bambu Lab A1 Mini v5 3mf.3mf" out.mesh

The output is intentionally a compact, Android-friendly presentation mesh.
It is not a print model, a collision authority, or a release asset. The
source 3MF has no declared licence, so redistribution still requires explicit
permission or a replacement with a clearly licensed model.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import re
import struct
import zipfile
from pathlib import Path
from xml.etree import ElementTree


BODY_OBJECTS = ("2", "3", "4")
BED_OBJECT = "1"
CREASE_DEGREES = 42.0
IDENTITY = (1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)


def _finite(value: str, label: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{label} is not finite")
    return result


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _children(element: ElementTree.Element | None, name: str) -> list[ElementTree.Element]:
    if element is None:
        return []
    return [child for child in list(element) if _local_name(child.tag) == name]


def _transform(value: str | None) -> tuple[float, ...]:
    if not value:
        return IDENTITY
    values = tuple(_finite(part, "3MF transform") for part in value.split())
    if len(values) != 12:
        raise ValueError("3MF transform must contain twelve numbers")
    return values


def _apply(transform: tuple[float, ...], vertex: tuple[float, float, float]) -> tuple[float, float, float]:
    # 3MF stores a row-major 4x3 transform. This is the same convention used
    # by the supplied Three.js extraction script.
    x, y, z = vertex
    return (
        x * transform[0] + y * transform[3] + z * transform[6] + transform[9],
        x * transform[1] + y * transform[4] + z * transform[7] + transform[10],
        x * transform[2] + y * transform[5] + z * transform[8] + transform[11],
    )


def _load_3mf(source: Path) -> tuple[list[tuple[float, float, float]], list[tuple[int, int, int]]]:
    with zipfile.ZipFile(source) as archive:
        root = ElementTree.fromstring(archive.read("3D/3dmodel.model"))

    objects: dict[str, tuple[list[tuple[float, float, float]], list[tuple[int, int, int]]]] = {}
    components: dict[str, tuple[float, ...]] = {}
    for element in root.iter():
        if _local_name(element.tag) != "object":
            continue
        object_id = element.attrib.get("id")
        if not object_id:
            continue
        mesh = next((child for child in list(element) if _local_name(child.tag) == "mesh"), None)
        if mesh is not None:
            vertices_element = next((child for child in list(mesh) if _local_name(child.tag) == "vertices"), None)
            triangles_element = next((child for child in list(mesh) if _local_name(child.tag) == "triangles"), None)
            if vertices_element is None or triangles_element is None:
                raise ValueError(f"3MF object {object_id} has an incomplete mesh")
            vertices = [(
                _finite(vertex.attrib["x"], "3MF x"),
                _finite(vertex.attrib["y"], "3MF y"),
                _finite(vertex.attrib["z"], "3MF z"),
            ) for vertex in _children(vertices_element, "vertex")]
            faces = []
            for triangle in _children(triangles_element, "triangle"):
                face = tuple(int(triangle.attrib[key]) for key in ("v1", "v2", "v3"))
                if any(index < 0 or index >= len(vertices) for index in face):
                    raise ValueError(f"3MF object {object_id} contains an invalid triangle index")
                faces.append(face)  # type: ignore[arg-type]
            objects[object_id] = (vertices, faces)
        component_parent = next((child for child in list(element) if _local_name(child.tag) == "components"), None)
        for component in _children(component_parent, "component"):
            component_id = component.attrib.get("objectid")
            if component_id:
                components[component_id] = _transform(component.attrib.get("transform"))

    if BED_OBJECT not in objects or any(object_id not in objects for object_id in BODY_OBJECTS):
        raise ValueError("3MF does not contain the expected A1 Mini objects 1–4")

    body_vertices: list[tuple[float, float, float]] = []
    body_faces: list[tuple[int, int, int]] = []
    for object_id in BODY_OBJECTS:
        vertices, faces = objects[object_id]
        transform = components.get(object_id, IDENTITY)
        offset = len(body_vertices)
        body_vertices.extend(_apply(transform, vertex) for vertex in vertices)
        body_faces.extend((a + offset, b + offset, c + offset) for a, b, c in faces)

    bed_vertices, bed_faces = objects[BED_OBJECT]
    bed_transform = components.get(BED_OBJECT, IDENTITY)
    bed_vertices = [_apply(bed_transform, vertex) for vertex in bed_vertices]
    bed_values = [vertex for face in bed_faces for vertex in (bed_vertices[face[0]], bed_vertices[face[1]], bed_vertices[face[2]])]
    bed_min = tuple(min(vertex[axis] for vertex in bed_values) for axis in range(3))
    bed_max = tuple(max(vertex[axis] for vertex in bed_values) for axis in range(3))
    bed_center = tuple((bed_min[axis] + bed_max[axis]) / 2.0 for axis in range(3))

    # Anchor the presentation to the actual 180 x 180 bed, then rotate the
    # machine's source axes into Alloy's x/y plate plane and z height axis.
    transformed = []
    for vertex in body_vertices:
        x = vertex[0] - bed_center[0]
        y = vertex[1] - bed_max[1]
        z = vertex[2] - bed_center[2]
        transformed.append((z, y, -x))
    return transformed, body_faces


def _face_normals(vertices: list[tuple[float, float, float]], faces: list[tuple[int, int, int]]) -> list[tuple[float, float, float]]:
    result = []
    for a, b, c in faces:
        ax, ay, az = vertices[a]
        bx, by, bz = vertices[b]
        cx, cy, cz = vertices[c]
        ux, uy, uz = bx - ax, by - ay, bz - az
        vx, vy, vz = cx - ax, cy - ay, cz - az
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        length = math.sqrt(nx * nx + ny * ny + nz * nz)
        result.append((0.0, 0.0, 0.0) if length <= 1e-12 else (nx / length, ny / length, nz / length))
    return result


def _normalise(value: tuple[float, float, float]) -> tuple[float, float, float]:
    length = math.sqrt(sum(component * component for component in value))
    if length <= 1e-12:
        return 0.0, 0.0, 1.0
    return tuple(component / length for component in value)  # type: ignore[return-value]


def _compact_mesh(vertices: list[tuple[float, float, float]], faces: list[tuple[int, int, int]]) -> dict[str, object]:
    normals = _face_normals(vertices, faces)
    corners_by_weld: dict[tuple[float, float, float], list[tuple[int, int]]] = {}
    for face_index, face in enumerate(faces):
        for corner, vertex_index in enumerate(face):
            key = tuple(round(value, 3) for value in vertices[vertex_index])
            corners_by_weld.setdefault(key, []).append((face_index, corner))

    cosine = math.cos(math.radians(CREASE_DEGREES))
    corner_normals: dict[tuple[int, int], tuple[float, float, float]] = {}
    for members in corners_by_weld.values():
        for face_index, corner in members:
            current = normals[face_index]
            total = [0.0, 0.0, 0.0]
            for other_face, _ in members:
                other = normals[other_face]
                if sum(current[index] * other[index] for index in range(3)) > cosine:
                    for index in range(3):
                        total[index] += other[index]
            corner_normals[(face_index, corner)] = _normalise(tuple(total))  # type: ignore[arg-type]

    unique: dict[tuple[float, ...], int] = {}
    positions: list[tuple[float, float, float]] = []
    output_normals: list[tuple[float, float, float]] = []
    indices: list[int] = []
    for face_index, face in enumerate(faces):
        for corner, vertex_index in enumerate(face):
            position = vertices[vertex_index]
            normal = corner_normals[(face_index, corner)]
            key = tuple(round(value, 3) for value in position) + tuple(round(value, 2) for value in normal)
            index = unique.get(key)
            if index is None:
                index = len(positions)
                unique[key] = index
                positions.append(position)
                output_normals.append(normal)
            indices.append(index)

    minimum = tuple(min(position[axis] for position in positions) for axis in range(3))
    maximum = tuple(max(position[axis] for position in positions) for axis in range(3))
    scale = max(maximum[axis] - minimum[axis] for axis in range(3)) / 65534.0
    if not math.isfinite(scale) or scale <= 0:
        raise ValueError("A1 reference mesh has no usable extent")

    position_bytes = bytearray()
    for position in positions:
        for axis in range(3):
            value = max(0, min(65535, int(round((position[axis] - minimum[axis]) / scale))))
            position_bytes.extend(struct.pack("<H", value))
    normal_bytes = bytearray()
    for normal in output_normals:
        for value in normal:
            normal_bytes.extend(struct.pack("<b", max(-127, min(127, int(round(value * 127))))))
    indices32 = len(positions) > 65535
    index_bytes = bytearray()
    for index in indices:
        index_bytes.extend(struct.pack("<I" if indices32 else "<H", index))
    return {
        "s": round(scale, 8),
        "o": [round(value, 4) for value in minimum],
        "u32": indices32,
        "p": base64.b64encode(position_bytes).decode("ascii"),
        "n": base64.b64encode(normal_bytes).decode("ascii"),
        "i": base64.b64encode(index_bytes).decode("ascii"),
        "triangles": len(faces),
        "vertices": len(positions),
    }


def _mesh_from_preview_zip(source: Path) -> dict[str, object]:
    with zipfile.ZipFile(source) as archive:
        html = archive.read("a1-mini-preview.html").decode("utf-8")
    match = re.search(r"const A1_MESH=(\{.*?\});", html, re.S)
    if not match:
        raise ValueError("A1_MESH was not found in the supplied preview")
    mesh = json.loads(match.group(1))
    for key in ("s", "o", "u32", "p", "n", "i"):
        if key not in mesh:
            raise ValueError(f"A1_MESH is missing {key}")
    positions = base64.b64decode(mesh["p"], validate=True)
    normals = base64.b64decode(mesh["n"], validate=True)
    indices = base64.b64decode(mesh["i"], validate=True)
    if len(positions) % 6 or len(normals) % 3 or len(normals) != len(positions) // 2:
        raise ValueError("A1_MESH vertex payloads are inconsistent")
    width = 4 if mesh["u32"] else 2
    if len(indices) % width or (len(indices) // width) % 3:
        raise ValueError("A1_MESH index payload is inconsistent")
    return mesh


def _write(output: Path, mesh: dict[str, object], source_label: str) -> None:
    origin = mesh["o"]
    text = "\n".join([
        f"# Debug-only visual reference extracted from {source_label}.",
        "# This file is not included in release variants.",
        "# Redistribution requires a declared licence or explicit permission.",
        "version=1",
        f"scale={mesh['s']}",
        f"originX={origin[0]}",
        f"originY={origin[1]}",
        f"originZ={origin[2]}",
        f"indices32={'true' if mesh['u32'] else 'false'}",
        f"positions={mesh['p']}",
        f"normals={mesh['n']}",
        f"indices={mesh['i']}",
        "",
    ])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="ascii")
    position_bytes = len(base64.b64decode(mesh["p"]))
    index_bytes = len(base64.b64decode(mesh["i"]))
    triangles = mesh.get("triangles", len(base64.b64decode(mesh["i"])) // (4 if mesh["u32"] else 2) // 3)
    vertices = mesh.get("vertices", len(base64.b64decode(mesh["p"])) // 6)
    print(f"wrote {output} ({vertices} vertices, {triangles} triangles, {position_bytes + index_bytes} geometry bytes)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Import the supplied A1 Mini visual reference into Alloy's debug mesh format")
    parser.add_argument("source", type=Path, help="the visual-review ZIP or the original .3mf")
    parser.add_argument("output", type=Path, help="output .mesh path")
    args = parser.parse_args()
    if args.source.suffix.lower() == ".3mf":
        mesh = _compact_mesh(*_load_3mf(args.source))
        _write(args.output, mesh, "the supplied 3MF")
    else:
        _write(args.output, _mesh_from_preview_zip(args.source), "the supplied HTML preview")


if __name__ == "__main__":
    main()
