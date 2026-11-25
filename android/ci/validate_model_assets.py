#!/usr/bin/env python3
"""Validate public and owner-private model assets packaged for Android."""

from __future__ import annotations

import hashlib
import json
import math
import re
import struct
from pathlib import Path
from zipfile import BadZipFile, ZipFile


VERTEX = re.compile(
    r"^\s*vertex\s+([-+0-9.eE]+)\s+([-+0-9.eE]+)\s+([-+0-9.eE]+)\s*$"
)


def read_triangles(path: Path) -> list[tuple[tuple[float, float, float], ...]]:
    data = path.read_bytes()
    if len(data) >= 84:
        count = struct.unpack_from("<I", data, 80)[0]
        expected = 84 + count * 50
        if count > 0 and expected == len(data):
            triangles = []
            for index in range(count):
                offset = 84 + index * 50 + 12
                values = struct.unpack_from("<9f", data, offset)
                triangle = tuple(tuple(values[corner * 3:corner * 3 + 3]) for corner in range(3))
                if not all(math.isfinite(value) for vertex in triangle for value in vertex):
                    raise ValueError(f"{path}: non-finite vertex")
                triangles.append(triangle)
            return triangles
    triangles: list[tuple[tuple[float, float, float], ...]] = []
    vertices: list[tuple[float, float, float]] = []
    for line in data.decode("utf-8").splitlines():
        match = VERTEX.match(line)
        if not match:
            continue
        vertex = tuple(float(value) for value in match.groups())
        if not all(math.isfinite(value) for value in vertex):
            raise ValueError(f"{path}: non-finite vertex")
        vertices.append(vertex)
    if len(vertices) < 3 or len(vertices) % 3:
        raise ValueError(f"{path}: vertex count is not a positive multiple of 3")
    for index in range(0, len(vertices), 3):
        triangles.append(tuple(vertices[index : index + 3]))
    return triangles


def key(vertex: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(round(value, 6) for value in vertex)


def component_count(triangles: list[tuple[tuple[float, float, float], ...]]) -> int:
    parent = list(range(len(triangles)))

    def find(value: int) -> int:
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    owners: dict[tuple[float, float, float], int] = {}
    for index, triangle in enumerate(triangles):
        for vertex in triangle:
            previous = owners.setdefault(key(vertex), index)
            first, second = find(previous), find(index)
            if first != second:
                parent[second] = first
    return len({find(index) for index in range(len(triangles))})


def validate(path: Path) -> tuple[int, tuple[float, float, float], int]:
    triangles = read_triangles(path)
    edges: dict[tuple[tuple[float, float, float], tuple[float, float, float]], int] = {}
    vertices = [vertex for triangle in triangles for vertex in triangle]
    for triangle in triangles:
        points = [key(vertex) for vertex in triangle]
        for first, second in zip(points, points[1:] + points[:1]):
            edge = tuple(sorted((first, second)))
            edges[edge] = edges.get(edge, 0) + 1
    if any(count != 2 for count in edges.values()):
        raise ValueError(f"{path}: mesh is not watertight")
    spans = tuple(max(axis) - min(axis) for axis in zip(*vertices))
    if any(span <= 0 for span in spans):
        raise ValueError(f"{path}: mesh has a zero-sized axis")
    return len(triangles), spans, component_count(triangles)


def validate_3mf(path: Path) -> None:
    """Validate a private 3MF reference without treating it as a print artifact."""
    try:
        with ZipFile(path) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > 128:
                raise ValueError(f"{path}: 3MF entry count is invalid")
            names = set()
            total = 0
            for entry in entries:
                name = entry.filename.replace("\\", "/")
                if not name or name.startswith("/") or ".." in Path(name).parts:
                    raise ValueError(f"{path}: unsafe 3MF entry {entry.filename!r}")
                if name in names:
                    raise ValueError(f"{path}: duplicate 3MF entry {name!r}")
                names.add(name)
                if entry.is_dir():
                    continue
                if entry.file_size < 0 or entry.file_size > 32 * 1024 * 1024:
                    raise ValueError(f"{path}: 3MF entry is too large")
                total += entry.file_size
                if total > 64 * 1024 * 1024:
                    raise ValueError(f"{path}: 3MF is too large")
            required = {"[Content_Types].xml", "3D/3dmodel.model"}
            if not required.issubset(names):
                raise ValueError(f"{path}: required 3MF entries are missing")
            # Read the model and content-type entries so a truncated local
            # copy cannot pass on directory metadata alone.
            for name in required:
                if not archive.read(name):
                    raise ValueError(f"{path}: required 3MF entry is empty: {name}")
    except BadZipFile as error:
        raise ValueError(f"{path}: invalid 3MF ZIP container") from error


def validate_catalog(root: Path, catalog_path: Path, asset_root: Path,
                     require_exact_stl_set: bool) -> None:
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if catalog.get("schema_version") != 1 or not isinstance(catalog.get("entries"), list):
        raise ValueError(f"{catalog_path}: unsupported model catalog schema")
    entries = catalog["entries"]
    catalog_by_path = {entry.get("path"): entry for entry in entries}
    if len(catalog_by_path) != len(entries):
        raise ValueError(f"{catalog_path}: duplicate model paths")
    normalized_paths: set[str] = set()
    for entry in entries:
        relative = entry.get("path", "")
        normalized = relative.replace("\\", "/")
        for prefix in ("app/src/main/assets/", "app/src/debug/assets/"):
            if normalized.startswith(prefix):
                normalized = normalized[len(prefix):]
                break
        if not normalized.startswith("models/") or ".." in Path(normalized).parts:
            raise ValueError(f"{catalog_path}: unsafe model path {relative!r}")
        if normalized in normalized_paths:
            raise ValueError(f"{catalog_path}: duplicate normalized model path {normalized!r}")
        normalized_paths.add(normalized)
        path = asset_root / normalized[len("models/"):]
        if not path.is_file():
            raise ValueError(f"{catalog_path}: missing asset {path}")
        if not entry.get("author") or not entry.get("license") or not entry.get("source"):
            raise ValueError(f"{path}: missing author, license or source metadata")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != entry.get("sha256"):
            raise ValueError(f"{path}: catalog SHA-256 does not match")
        if path.suffix.lower() == ".stl":
            triangles, spans, components = validate(path)
            if path.name == "box-and-lid.stl" and components != 2:
                raise ValueError(f"{path}: expected two disconnected assembly solids, found {components}")
            print(f"validated {path.relative_to(root)}: {triangles} triangles, {components} solid(s), spans {spans} mm")
        elif path.suffix.lower() == ".step":
            sample = path.read_bytes()[:256 * 1024].decode("ascii", errors="ignore").upper()
            if "ISO-10303-21;" not in sample or "HEADER;" not in sample or "DATA;" not in sample:
                raise ValueError(f"{path}: STEP Part 21 signature is missing")
            print(f"validated {path.relative_to(root)}: STEP Part 21 source, {path.stat().st_size} bytes")
        elif path.suffix.lower() == ".3mf":
            validate_3mf(path)
            print(f"validated {path.relative_to(root)}: 3MF reference, {path.stat().st_size} bytes")
        else:
            raise ValueError(f"{path}: unsupported owner asset format")
    if require_exact_stl_set:
        packaged = {"models/" + path.name for path in asset_root.glob("*.stl")}
        if normalized_paths != packaged:
            raise ValueError(f"{catalog_path}: catalog must contain exactly the packaged STL assets")
    else:
        packaged = {"models/" + path.name for path in asset_root.iterdir()
                    if path.is_file() and path.suffix.lower() in {".stl", ".step", ".3mf"}}
        if normalized_paths != packaged:
            raise ValueError(f"{catalog_path}: catalog must contain exactly the owner model assets")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    assets = sorted((root / "app/src/main/assets/models").glob("*.stl"))
    if not assets:
        raise SystemExit("no model assets found")
    validate_catalog(root, root / "app/src/main/assets/models/catalog.json",
                     root / "app/src/main/assets/models", True)
    private_catalog = root / "app/src/debug/assets/models/catalog-private.json"
    if private_catalog.is_file():
        validate_catalog(root, private_catalog, root / "app/src/debug/assets/models", False)


if __name__ == "__main__":
    main()
