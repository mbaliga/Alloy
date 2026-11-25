// CPU reference voxelizer behind forge.geometry.voxelize. One +z ray per (x, y) column, parity fill between hits.
// Correct for closed (watertight) meshes, which is what the kernels produce. It is deliberately simple: A2 replaces it with
// the conservative GPU voxelizer behind the same call. Limits are enforced before any allocation.
import { ExtError } from './host.js';

export const VOXEL_DEFAULT_MAX = 4_000_000, VOXEL_HARD_MAX = 8_000_000;

/** @param {{pos:Float32Array|number[], idx:Uint32Array|number[]}} mesh @param {number} h voxel size in mm */
export function voxelize(mesh, h, { maxVoxels = VOXEL_DEFAULT_MAX } = {}) {
  if (!(h > 0) || !Number.isFinite(h)) throw new ExtError('E_ARG', 'voxel size h must be a positive number (mm)');
  const pos = mesh.pos, idx = mesh.idx, nv = pos.length / 3;
  if (!nv) throw new ExtError('E_ARG', 'mesh is empty');
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < nv; i++) for (let k = 0; k < 3; k++) { const v = pos[i * 3 + k]; if (v < lo[k]) lo[k] = v; if (v > hi[k]) hi[k] = v; }
  const dims = [0, 1, 2].map((k) => Math.max(1, Math.ceil((hi[k] - lo[k]) / h - 1e-9)));
  const total = dims[0] * dims[1] * dims[2], cap = Math.min(maxVoxels, VOXEL_HARD_MAX);
  if (total > cap) {
    const hNeeded = Math.cbrt(((hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2])) / cap);
    throw new ExtError('E_LIMIT', `grid ${dims.join('×')} = ${total} voxels exceeds the limit of ${cap}; use h ≥ ${hNeeded.toFixed(2)} mm`);
  }
  const [nx, ny, nz] = dims, data = new Uint8Array(total);
  // bin triangles by the (x, y) columns their bounding box touches
  const cols = new Map(), nt = idx.length / 3, jit = h * 1e-4;
  for (let t = 0; t < nt; t++) {
    const a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
    const x0 = Math.min(pos[a], pos[b], pos[c]), x1 = Math.max(pos[a], pos[b], pos[c]), y0 = Math.min(pos[a + 1], pos[b + 1], pos[c + 1]), y1 = Math.max(pos[a + 1], pos[b + 1], pos[c + 1]);
    const i0 = Math.max(0, Math.floor((x0 - lo[0]) / h - 0.5)), i1 = Math.min(nx - 1, Math.ceil((x1 - lo[0]) / h - 0.5));
    const j0 = Math.max(0, Math.floor((y0 - lo[1]) / h - 0.5)), j1 = Math.min(ny - 1, Math.ceil((y1 - lo[1]) / h - 0.5));
    for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) { const key = j * nx + i; let l = cols.get(key); if (!l) cols.set(key, (l = [])); l.push(t); }
  }
  for (const [key, tris] of cols) {
    const i = key % nx, j = (key - i) / nx;
    const px = lo[0] + (i + 0.5) * h + jit, py = lo[1] + (j + 0.5) * h + jit * 1.7;
    const zs = [];
    for (const t of tris) {
      const a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
      const ax = pos[a], ay = pos[a + 1], bx = pos[b], by = pos[b + 1], cx = pos[c], cy = pos[c + 1];
      const d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
      if (Math.abs(d) < 1e-12) continue;
      const l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / d, l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / d, l3 = 1 - l1 - l2;
      if (l1 < 0 || l2 < 0 || l3 < 0) continue;
      zs.push(l1 * pos[a + 2] + l2 * pos[b + 2] + l3 * pos[c + 2]);
    }
    zs.sort((p, q) => p - q);
    for (let s = 0; s + 1 < zs.length; s += 2) {
      const k0 = Math.max(0, Math.ceil((zs[s] - lo[2]) / h - 0.5)), k1 = Math.min(nz - 1, Math.floor((zs[s + 1] - lo[2]) / h - 0.5));
      for (let k = k0; k <= k1; k++) data[(k * ny + j) * nx + i] = 1;
    }
  }
  return { origin: lo, h, dims, data };
}
