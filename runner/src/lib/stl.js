// STL reading and writing (binary and ASCII), vertex welding and simple mesh measures. Plain functions with no imports: this file is
// also concatenated into the Forge package `xyz.mdhv.runner` by forge/tools/bundle-ext.mjs, so it must stay free of imports and of
// Node-only APIs. Units are whatever the file holds (the Forge package sends millimetres).

/** Parse a binary or ASCII STL. Returns { positions: Float32Array (3 per vertex), indices: Uint32Array (3 per triangle) }, vertices welded by exact coordinate. */
export function parseStl(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const head = new TextDecoder().decode(u8.subarray(0, Math.min(u8.length, 256)));
  const nBin = u8.length >= 84 ? dv.getUint32(80, true) : -1;
  const isBinary = nBin >= 0 && 84 + 50 * nBin === u8.length && !(/^\s*solid[\s\S]*facet\s+normal/.test(head) && 84 + 50 * nBin !== u8.length);
  const tris = []; // flat x y z per vertex, 9 per triangle
  if (isBinary) {
    for (let t = 0; t < nBin; t++) { const o = 84 + 50 * t + 12; for (let k = 0; k < 9; k++) tris.push(dv.getFloat32(o + 4 * k, true)); }
  } else {
    const text = new TextDecoder().decode(u8), re = /vertex\s+([-+0-9.eE]+)\s+([-+0-9.eE]+)\s+([-+0-9.eE]+)/g; let m;
    while ((m = re.exec(text))) tris.push(+m[1], +m[2], +m[3]);
    if (tris.length % 9) throw new Error('STL: the vertex count is not a multiple of 3');
    if (!tris.length) throw new Error('STL: no triangles found (not a binary STL of the right length, and no ASCII facets)');
  }
  for (const v of tris) if (!Number.isFinite(v)) throw new Error('STL: a coordinate is not a finite number');
  const map = new Map(), pos = [], idx = new Uint32Array(tris.length / 3);
  for (let i = 0; i < tris.length; i += 3) {
    const key = tris[i] + ',' + tris[i + 1] + ',' + tris[i + 2];
    let id = map.get(key); if (id === undefined) { id = pos.length / 3; map.set(key, id); pos.push(tris[i], tris[i + 1], tris[i + 2]); }
    idx[i / 3] = id;
  }
  return { positions: Float32Array.from(pos), indices: idx };
}

/** Binary STL bytes of an indexed triangle mesh. */
export function writeStlBinary(positions, indices) {
  const n = indices.length / 3, out = new Uint8Array(84 + 50 * n), dv = new DataView(out.buffer);
  out.set(new TextEncoder().encode('Alloy Forge STL'), 0); dv.setUint32(80, n, true);
  for (let t = 0; t < n; t++) {
    const a = indices[3 * t] * 3, b = indices[3 * t + 1] * 3, c = indices[3 * t + 2] * 3, o = 84 + 50 * t;
    const ux = positions[b] - positions[a], uy = positions[b + 1] - positions[a + 1], uz = positions[b + 2] - positions[a + 2];
    const vx = positions[c] - positions[a], vy = positions[c + 1] - positions[a + 1], vz = positions[c + 2] - positions[a + 2];
    let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx; const l = Math.hypot(nx, ny, nz) || 1; nx /= l; ny /= l; nz /= l;
    dv.setFloat32(o, nx, true); dv.setFloat32(o + 4, ny, true); dv.setFloat32(o + 8, nz, true);
    for (const [k, q] of [[0, a], [1, b], [2, c]]) { dv.setFloat32(o + 12 + 12 * k, positions[q], true); dv.setFloat32(o + 16 + 12 * k, positions[q + 1], true); dv.setFloat32(o + 20 + 12 * k, positions[q + 2], true); }
  }
  return out;
}

/** Bounding box, enclosed volume (divergence theorem; positive for outward-wound closed meshes) and surface area. */
export function meshStats(positions, indices) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity]; let vol = 0, area = 0;
  for (let i = 0; i < positions.length; i += 3) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], positions[i + k]); hi[k] = Math.max(hi[k], positions[i + k]); }
  for (let t = 0; t < indices.length; t += 3) {
    const a = indices[t] * 3, b = indices[t + 1] * 3, c = indices[t + 2] * 3;
    const ax = positions[a], ay = positions[a + 1], az = positions[a + 2], bx = positions[b], by = positions[b + 1], bz = positions[b + 2], cx = positions[c], cy = positions[c + 1], cz = positions[c + 2];
    vol += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6;
    area += 0.5 * Math.hypot((by - ay) * (cz - az) - (bz - az) * (cy - ay), (bz - az) * (cx - ax) - (bx - ax) * (cz - az), (bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
  }
  return { min: lo, max: hi, volume: vol, area, triangles: indices.length / 3, vertices: positions.length / 3 };
}

/** Is the surface closed and consistently wound (every directed edge appears once, and so does its reverse)? A closed surface is what a volume mesh needs. */
export function isClosedSurface(indices) {
  const seen = new Map();
  for (let t = 0; t < indices.length; t += 3) for (let k = 0; k < 3; k++) {
    const a = indices[t + k], b = indices[t + (k + 1) % 3]; if (a === b) return false;
    const key = a + ':' + b; if (seen.has(key)) return false; seen.set(key, 1);
  }
  for (const key of seen.keys()) { const [a, b] = key.split(':'); if (!seen.has(b + ':' + a)) return false; }
  return true;
}
