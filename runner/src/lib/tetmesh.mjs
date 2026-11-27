// Tetrahedral mesh helpers: read gmsh ASCII msh 2.x, boundary faces with outward normals, face selection by direction and box (the same
// vocabulary as the Forge analysis conditions: a face is "+x".."-z" of a body's bounding box, optionally restricted to a box in mm), and
// the Elmer mesh files. Units here are millimetres; the Elmer writer scales to metres because every Elmer input is SI.
import fs from 'node:fs';
import path from 'node:path';

export const FACES = ['+x', '-x', '+y', '-y', '+z', '-z'];
export const FACE_N = { '+x': [1, 0, 0], '-x': [-1, 0, 0], '+y': [0, 1, 0], '-y': [0, -1, 0], '+z': [0, 0, 1], '-z': [0, 0, -1] };

/** Parse gmsh ASCII MSH 2.x: returns { nodes: Float64Array (3 per node, 0-based), tets: Int32Array (4 per tet, 0-based) }. Only 4-node tetrahedra (type 4) are kept. */
export function readMsh2(text) {
  const lines = text.split(/\r?\n/);
  const fmt = /\$MeshFormat\s*\n?/.exec(text) ? lines[lines.findIndex((l) => l.startsWith('$MeshFormat')) + 1].trim().split(/\s+/)[0] : '';
  if (!/^2\./.test(fmt)) throw new Error(`gmsh output: MSH ${fmt || '?'} is not the ASCII 2.x format this reader needs`);
  let i = lines.findIndex((l) => l.startsWith('$Nodes')); if (i < 0) throw new Error('gmsh output: no $Nodes');
  const nn = parseInt(lines[i + 1], 10), ids = new Map(), nodes = new Float64Array(3 * nn);
  for (let k = 0; k < nn; k++) { const p = lines[i + 2 + k].trim().split(/\s+/); ids.set(+p[0], k); nodes[3 * k] = +p[1]; nodes[3 * k + 1] = +p[2]; nodes[3 * k + 2] = +p[3]; }
  i = lines.findIndex((l) => l.startsWith('$Elements')); if (i < 0) throw new Error('gmsh output: no $Elements');
  const ne = parseInt(lines[i + 1], 10), tets = [];
  for (let k = 0; k < ne; k++) {
    const p = lines[i + 2 + k].trim().split(/\s+/).map(Number);
    if (p[1] === 4) { const nt = p[2]; const base = 3 + nt; tets.push(ids.get(p[base]), ids.get(p[base + 1]), ids.get(p[base + 2]), ids.get(p[base + 3])); }
  }
  if (!tets.length) throw new Error('gmsh produced no tetrahedra (is the STL a closed, watertight surface?)');
  return { nodes, tets: Int32Array.from(tets) };
}

/** Drop the nodes no tetrahedron uses and renumber (a node with no element would be a free dof in the solver). */
export function compactMesh(nodes, tets) {
  const map = new Int32Array(nodes.length / 3).fill(-1); let n = 0;
  for (const q of tets) if (map[q] < 0) map[q] = n++;
  const out = new Float64Array(3 * n), t2 = new Int32Array(tets.length);
  for (let q = 0; q < map.length; q++) if (map[q] >= 0) { out[3 * map[q]] = nodes[3 * q]; out[3 * map[q] + 1] = nodes[3 * q + 1]; out[3 * map[q] + 2] = nodes[3 * q + 2]; }
  for (let i = 0; i < tets.length; i++) t2[i] = map[tets[i]];
  return { nodes: out, tets: t2 };
}

/** Orient every tet to positive volume (in place) and return the total volume (mm^3). */
export function orientTets(nodes, tets) {
  let total = 0, flipped = 0;
  for (let e = 0; e < tets.length; e += 4) {
    let v = tetVolume(nodes, tets[e], tets[e + 1], tets[e + 2], tets[e + 3]);
    if (v < 0) { const t = tets[e + 1]; tets[e + 1] = tets[e + 2]; tets[e + 2] = t; v = -v; flipped++; }
    total += v;
  }
  return { volume: total, flipped };
}
export function tetVolume(nodes, a, b, c, d) {
  const ux = nodes[3 * b] - nodes[3 * a], uy = nodes[3 * b + 1] - nodes[3 * a + 1], uz = nodes[3 * b + 2] - nodes[3 * a + 2];
  const vx = nodes[3 * c] - nodes[3 * a], vy = nodes[3 * c + 1] - nodes[3 * a + 1], vz = nodes[3 * c + 2] - nodes[3 * a + 2];
  const wx = nodes[3 * d] - nodes[3 * a], wy = nodes[3 * d + 1] - nodes[3 * a + 1], wz = nodes[3 * d + 2] - nodes[3 * a + 2];
  return (ux * (vy * wz - vz * wy) - uy * (vx * wz - vz * wx) + uz * (vx * wy - vy * wx)) / 6;
}

const LOCAL = [[1, 2, 3], [0, 3, 2], [0, 1, 3], [0, 2, 1]]; // face opposite vertex k, wound outward for a positively oriented tet

/** Boundary triangles of one body's tets: { tri: Int32Array (3 per face), parent: Int32Array (tet), normal: Float64Array (unit, outward), area: Float64Array, centroid: Float64Array }. */
export function boundaryFaces(nodes, tets) {
  const map = new Map(), nt = tets.length / 4;
  for (let e = 0; e < nt; e++) for (let f = 0; f < 4; f++) {
    const tri = LOCAL[f].map((q) => tets[4 * e + q]), key = [...tri].sort((a, b) => a - b).join(',');
    const hit = map.get(key); if (hit) hit.count++; else map.set(key, { tri, e, count: 1 });
  }
  const faces = [...map.values()].filter((h) => h.count === 1), n = faces.length;
  const out = { tri: new Int32Array(3 * n), parent: new Int32Array(n), normal: new Float64Array(3 * n), area: new Float64Array(n), centroid: new Float64Array(3 * n) };
  faces.forEach((h, i) => {
    out.tri.set(h.tri, 3 * i); out.parent[i] = h.e;
    const [a, b, c] = h.tri, ux = nodes[3 * b] - nodes[3 * a], uy = nodes[3 * b + 1] - nodes[3 * a + 1], uz = nodes[3 * b + 2] - nodes[3 * a + 2], vx = nodes[3 * c] - nodes[3 * a], vy = nodes[3 * c + 1] - nodes[3 * a + 1], vz = nodes[3 * c + 2] - nodes[3 * a + 2];
    let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx; const l = Math.hypot(nx, ny, nz) || 1;
    out.area[i] = l / 2; out.normal[3 * i] = nx / l; out.normal[3 * i + 1] = ny / l; out.normal[3 * i + 2] = nz / l;
    for (let k = 0; k < 3; k++) out.centroid[3 * i + k] = (nodes[3 * a + k] + nodes[3 * b + k] + nodes[3 * c + k]) / 3;
  });
  return out;
}

/** Bounding box of the nodes used by `tets`. */
export function bboxOf(nodes, tets) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const q of tets) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], nodes[3 * q + k]); hi[k] = Math.max(hi[k], nodes[3 * q + k]); }
  return { min: lo, max: hi };
}

/**
 * The boundary faces of a body that a mount or load addresses: facing `face` (normal within ~60 degrees of the direction) and either on the
 * body's bounding-box plane of that face (default, within `tol`) or with the centroid inside `box` ([x0,x1,y0,y1,z0,z1] mm, padded by `tol`).
 * Returns the face indices into `bf`.
 */
export function selectFaces(bf, bbox, face, box, tol) {
  const n = FACE_N[face]; if (!n) throw new Error(`unknown face "${face}"`);
  const ax = FACES.indexOf(face) >> 1, plus = face[0] === '+', out = [];
  for (let i = 0; i < bf.area.length; i++) {
    const d = bf.normal[3 * i] * n[0] + bf.normal[3 * i + 1] * n[1] + bf.normal[3 * i + 2] * n[2];
    if (d < 0.5) continue;
    const c = [bf.centroid[3 * i], bf.centroid[3 * i + 1], bf.centroid[3 * i + 2]];
    if (box) { if (![0, 1, 2].every((q) => c[q] >= Math.min(box[2 * q], box[2 * q + 1]) - tol && c[q] <= Math.max(box[2 * q], box[2 * q + 1]) + tol)) continue; }
    else if (!(plus ? c[ax] >= bbox.max[ax] - tol : c[ax] <= bbox.min[ax] + tol)) continue;
    out.push(i);
  }
  return out;
}

/**
 * Write an Elmer mesh directory (mesh.header, mesh.nodes, mesh.elements, mesh.boundary) in metres.
 * bodies: [{ tets (Int32Array 4 per tet, indices into the shared `nodes`), boundary: [{ bc: <boundary id>, tri: Int32Array(3*k), parent: Int32Array(k) (tet index within the body) }] }]
 */
export function writeElmerMesh(dir, nodes, bodies) {
  fs.mkdirSync(dir, { recursive: true });
  const nn = nodes.length / 3, S = 1e-3, nodeLines = [], elemLines = [], bLines = [];
  for (let i = 0; i < nn; i++) nodeLines.push(`${i + 1} -1 ${nodes[3 * i] * S} ${nodes[3 * i + 1] * S} ${nodes[3 * i + 2] * S}`);
  let eid = 0, bid = 0; const base = [];
  bodies.forEach((b, bi) => { base.push(eid); for (let e = 0; e < b.tets.length; e += 4) { eid++; elemLines.push(`${eid} ${bi + 1} 504 ${b.tets[e] + 1} ${b.tets[e + 1] + 1} ${b.tets[e + 2] + 1} ${b.tets[e + 3] + 1}`); } });
  bodies.forEach((b, bi) => { for (const g of b.boundary || []) for (let k = 0; k < g.parent.length; k++) { bid++; bLines.push(`${bid} ${g.bc} ${base[bi] + g.parent[k] + 1} 0 303 ${g.tri[3 * k] + 1} ${g.tri[3 * k + 1] + 1} ${g.tri[3 * k + 2] + 1}`); } });
  fs.writeFileSync(path.join(dir, 'mesh.header'), `${nn} ${eid} ${bid}\n${bid ? 2 : 1}\n504 ${eid}\n${bid ? `303 ${bid}\n` : ''}`);
  fs.writeFileSync(path.join(dir, 'mesh.nodes'), nodeLines.join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'mesh.elements'), elemLines.join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'mesh.boundary'), bLines.join('\n') + (bLines.length ? '\n' : ''));
  return { nodes: nn, elements: eid, boundary: bid };
}
