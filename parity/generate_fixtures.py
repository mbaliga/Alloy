#!/usr/bin/env python3
"""Generate deterministic ASCII STL fixtures for Alloy G3 parity testing.

Fixtures:
- cube_20mm.stl: baseline geometry and timing/material sanity check.
- overhang_support.stl: connected cantilever geometry that should exercise support generation.
- thin_wall_frame.stl: keyboard-case-like rectangular shell for Arachne/thin-wall behavior.

No randomness is used. Units are millimetres.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import sqrt
from pathlib import Path
import argparse

Vec3 = tuple[float, float, float]
Vec2 = tuple[float, float]
Triangle = tuple[Vec3, Vec3, Vec3]


@dataclass
class Mesh:
    triangles: list[Triangle]

    def add(self, a: Vec3, b: Vec3, c: Vec3) -> None:
        self.triangles.append((a, b, c))

    def quad(self, a: Vec3, b: Vec3, c: Vec3, d: Vec3) -> None:
        self.add(a, b, c)
        self.add(a, c, d)


def normal(a: Vec3, b: Vec3, c: Vec3) -> Vec3:
    ux, uy, uz = (b[i] - a[i] for i in range(3))
    vx, vy, vz = (c[i] - a[i] for i in range(3))
    nx = uy * vz - uz * vy
    ny = uz * vx - ux * vz
    nz = ux * vy - uy * vx
    length = sqrt(nx * nx + ny * ny + nz * nz)
    if length == 0:
        return (0.0, 0.0, 0.0)
    return (nx / length, ny / length, nz / length)


def box(width: float, depth: float, height: float) -> Mesh:
    x0, x1 = -width / 2, width / 2
    y0, y1 = -depth / 2, depth / 2
    z0, z1 = 0.0, height
    v = {
        "000": (x0, y0, z0), "100": (x1, y0, z0),
        "110": (x1, y1, z0), "010": (x0, y1, z0),
        "001": (x0, y0, z1), "101": (x1, y0, z1),
        "111": (x1, y1, z1), "011": (x0, y1, z1),
    }
    m = Mesh([])
    m.quad(v["000"], v["010"], v["110"], v["100"])
    m.quad(v["001"], v["101"], v["111"], v["011"])
    m.quad(v["000"], v["100"], v["101"], v["001"])
    m.quad(v["100"], v["110"], v["111"], v["101"])
    m.quad(v["110"], v["010"], v["011"], v["111"])
    m.quad(v["010"], v["000"], v["001"], v["011"])
    return m


def signed_area(points: list[Vec2]) -> float:
    return 0.5 * sum(
        points[i][0] * points[(i + 1) % len(points)][1]
        - points[(i + 1) % len(points)][0] * points[i][1]
        for i in range(len(points))
    )


def point_in_triangle(p: Vec2, a: Vec2, b: Vec2, c: Vec2) -> bool:
    def cross(u: Vec2, v: Vec2, w: Vec2) -> float:
        return (v[0] - u[0]) * (w[1] - u[1]) - (v[1] - u[1]) * (w[0] - u[0])

    c1 = cross(a, b, p)
    c2 = cross(b, c, p)
    c3 = cross(c, a, p)
    return (c1 >= 0 and c2 >= 0 and c3 >= 0) or (c1 <= 0 and c2 <= 0 and c3 <= 0)


def triangulate_polygon(points: list[Vec2]) -> list[tuple[int, int, int]]:
    if signed_area(points) < 0:
        points.reverse()
    indices = list(range(len(points)))
    result: list[tuple[int, int, int]] = []

    def cross(a: Vec2, b: Vec2, c: Vec2) -> float:
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    while len(indices) > 3:
        ear_found = False
        for pos, curr in enumerate(indices):
            prev = indices[(pos - 1) % len(indices)]
            nxt = indices[(pos + 1) % len(indices)]
            if cross(points[prev], points[curr], points[nxt]) <= 1e-9:
                continue
            if any(
                point_in_triangle(points[j], points[prev], points[curr], points[nxt])
                for j in indices
                if j not in (prev, curr, nxt)
            ):
                continue
            result.append((prev, curr, nxt))
            del indices[pos]
            ear_found = True
            break
        if not ear_found:
            raise ValueError("Could not triangulate fixture polygon")
    result.append(tuple(indices))
    return result


def extrude_xz_polygon(points: list[Vec2], depth: float) -> Mesh:
    pts = list(points)
    if signed_area(pts) < 0:
        pts.reverse()
    tris2d = triangulate_polygon(pts)
    y0, y1 = -depth / 2, depth / 2
    front = [(x, y1, z) for x, z in pts]
    back = [(x, y0, z) for x, z in pts]
    m = Mesh([])

    for a, b, c in tris2d:
        m.add(front[a], front[b], front[c])
        m.add(back[c], back[b], back[a])

    for i in range(len(pts)):
        j = (i + 1) % len(pts)
        m.quad(back[i], back[j], front[j], front[i])
    return m


def thin_wall_frame(outer_w: float = 80.0, outer_d: float = 40.0,
                    wall: float = 1.2, height: float = 8.0) -> Mesh:
    ow, od = outer_w / 2, outer_d / 2
    iw, id_ = ow - wall, od - wall
    z0, z1 = 0.0, height
    outer0 = [(-ow, -od, z0), (ow, -od, z0), (ow, od, z0), (-ow, od, z0)]
    outer1 = [(-ow, -od, z1), (ow, -od, z1), (ow, od, z1), (-ow, od, z1)]
    inner0 = [(-iw, -id_, z0), (iw, -id_, z0), (iw, id_, z0), (-iw, id_, z0)]
    inner1 = [(-iw, -id_, z1), (iw, -id_, z1), (iw, id_, z1), (-iw, id_, z1)]
    m = Mesh([])

    for i in range(4):
        j = (i + 1) % 4
        m.quad(outer0[i], outer0[j], outer1[j], outer1[i])
        m.quad(inner0[j], inner0[i], inner1[i], inner1[j])
        m.quad(outer1[i], outer1[j], inner1[j], inner1[i])
        m.quad(outer0[j], outer0[i], inner0[i], inner0[j])
    return m


def write_ascii_stl(path: Path, name: str, mesh: Mesh) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"solid {name}"]
    for tri in mesh.triangles:
        nx, ny, nz = normal(*tri)
        lines.append(f"  facet normal {nx:.9f} {ny:.9f} {nz:.9f}")
        lines.append("    outer loop")
        for x, y, z in tri:
            lines.append(f"      vertex {x:.6f} {y:.6f} {z:.6f}")
        lines.append("    endloop")
        lines.append("  endfacet")
    lines.append(f"endsolid {name}")
    path.write_text("\n".join(lines) + "\n")


def generate(out: Path) -> None:
    write_ascii_stl(out / "cube_20mm.stl", "cube_20mm", box(20.0, 20.0, 20.0))

    # X/Z side profile: 20x5 base, 6 mm central stem, then a 28 mm cantilever.
    # The long underside at Z=30 is intentionally unsupported beyond the stem.
    overhang_profile = [
        (-10.0, 0.0), (10.0, 0.0), (10.0, 5.0),
        (3.0, 5.0), (3.0, 30.0), (25.0, 30.0),
        (25.0, 34.0), (-3.0, 34.0), (-3.0, 5.0), (-10.0, 5.0),
    ]
    write_ascii_stl(
        out / "overhang_support.stl",
        "overhang_support",
        extrude_xz_polygon(overhang_profile, depth=8.0),
    )

    write_ascii_stl(
        out / "thin_wall_frame.stl",
        "thin_wall_frame",
        thin_wall_frame(),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("parity/fixtures"))
    args = parser.parse_args()
    generate(args.out)
    for path in sorted(args.out.glob("*.stl")):
        print(path)
