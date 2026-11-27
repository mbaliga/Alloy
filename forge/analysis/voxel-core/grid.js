// Voxel core: conservative mesh voxelizer with per-voxel material ids, enclosed-air detection, sparse active-cell list and the
// grid-size policy (draft / normal / fine). Shared by A2 (thermal), A3 (structural) and A5 (airflow). Pure JS, typed arrays, no
// DOM, no imports: it runs in Node tests and inside a package Worker (the package build concatenates these files).
//
// Conventions. A grid is { origin:[x,y,z] mm, h mm, dims:[nx,ny,nz] }; cell (i,j,k) covers origin + [i,i+1) h; the linear index is
// i + nx (j + ny k) (i fastest). A voxel's material id is 0 = air outside, 1..250 = a solid owner (the caller keeps the table),
// CAVITY (255) = air enclosed by solid. Face order everywhere: +x, -x, +y, -y, +z, -z.

export const CAVITY = 255;
export const MAX_OWNERS = 250;
export const FACE_NAMES = ['+x', '-x', '+y', '-y', '+z', '-z'];
export const FACE_DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

/** Grid covering [lo, hi] (mm) with cubic cells of size h. The origin is lo, so faces on the bounding box are on cell faces. */
export function makeGrid(lo, hi, h) {
  const dims = [0, 1, 2].map((k) => Math.max(1, Math.ceil((hi[k] - lo[k]) / h - 1e-6)));
  return { origin: lo.slice(), h, dims, total: dims[0] * dims[1] * dims[2] };
}

// ───────────────────────── triangle / box overlap (separating axis theorem, Akenine-Moller) ─────────────────────────
const S = new Float64Array(9);
/** Does triangle (a, b, c) (flat xyz arrays of length 3, offset-addressed in `p`) overlap the open box centred (cx,cy,cz) with half size e? Touching is not overlap. */
export function triBoxOverlap(cx, cy, cz, e, p, ia, ib, ic) {
  const v0x = p[ia] - cx, v0y = p[ia + 1] - cy, v0z = p[ia + 2] - cz, v1x = p[ib] - cx, v1y = p[ib + 1] - cy, v1z = p[ib + 2] - cz, v2x = p[ic] - cx, v2y = p[ic + 1] - cy, v2z = p[ic + 2] - cz;
  // 1. the three box axes: bounding box of the triangle against the box
  if (Math.min(v0x, v1x, v2x) >= e || Math.max(v0x, v1x, v2x) <= -e) return false;
  if (Math.min(v0y, v1y, v2y) >= e || Math.max(v0y, v1y, v2y) <= -e) return false;
  if (Math.min(v0z, v1z, v2z) >= e || Math.max(v0z, v1z, v2z) <= -e) return false;
  // 2. the plane of the triangle
  const e0x = v1x - v0x, e0y = v1y - v0y, e0z = v1z - v0z, e1x = v2x - v1x, e1y = v2y - v1y, e1z = v2z - v1z, e2x = v0x - v2x, e2y = v0y - v2y, e2z = v0z - v2z;
  const nx = e0y * e1z - e0z * e1y, ny = e0z * e1x - e0x * e1z, nz = e0x * e1y - e0y * e1x;
  const d = nx * v0x + ny * v0y + nz * v0z, rn = e * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
  if (Math.abs(d) >= rn) return false;
  // 3. nine cross products of triangle edges and box axes
  const E = [e0x, e0y, e0z, e1x, e1y, e1z, e2x, e2y, e2z];
  S[0] = v0x; S[1] = v0y; S[2] = v0z; S[3] = v1x; S[4] = v1y; S[5] = v1z; S[6] = v2x; S[7] = v2y; S[8] = v2z;
  for (let k = 0; k < 3; k++) {
    const fx = E[3 * k], fy = E[3 * k + 1], fz = E[3 * k + 2];
    for (let u = 0; u < 3; u++) {
      const ax = u === 0 ? 0 : u === 1 ? -fz : fy, ay = u === 0 ? fz : u === 1 ? 0 : -fx, az = u === 0 ? -fy : u === 1 ? fx : 0;
      const p0 = ax * S[0] + ay * S[1] + az * S[2], p1 = ax * S[3] + ay * S[4] + az * S[5], p2 = ax * S[6] + ay * S[7] + az * S[8];
      const r = e * (Math.abs(ax) + Math.abs(ay) + Math.abs(az));
      if (r === 0) continue; // an edge parallel to a box axis gives a zero axis: nothing to test
      if (Math.min(p0, p1, p2) >= r || Math.max(p0, p1, p2) <= -r) return false;
    }
  }
  return true;
}

/**
 * Paint one closed triangle mesh into `mat` with material id `id` where the voxel is still empty (0).
 *   - interior: parity fill of +z rays through the voxel centres (correct for watertight meshes), and
 *   - conservative: every voxel that a triangle passes through (so a wall thinner than one voxel is never lost).
 * Returns { inside, touched, oddColumns } counts. `conservative:false` gives the centre-sampled (volume-preserving) fill only.
 */
export function paintMesh(grid, mat, mesh, id, { conservative = true } = {}) {
  const { origin: lo, h, dims: [nx, ny, nz] } = grid, pos = mesh.positions, idx = mesh.indices, nt = idx.length / 3;
  let inside = 0, touched = 0;
  // parity fill: collect (column, z) hits, sort by column with a counting sort
  let cap = 1 << 16, hitCol = new Int32Array(cap), hitZ = new Float64Array(cap), nh = 0;
  const jx = h * 1.37e-4, jy = h * 2.91e-4;
  for (let t = 0; t < nt; t++) {
    const a = idx[3 * t] * 3, b = idx[3 * t + 1] * 3, c = idx[3 * t + 2] * 3;
    const ax = pos[a], ay = pos[a + 1], bx = pos[b], by = pos[b + 1], cx = pos[c], cy = pos[c + 1];
    const den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
    if (Math.abs(den) < 1e-12) continue;
    const i0 = Math.max(0, Math.floor((Math.min(ax, bx, cx) - lo[0]) / h - 0.5)), i1 = Math.min(nx - 1, Math.ceil((Math.max(ax, bx, cx) - lo[0]) / h - 0.5));
    const j0 = Math.max(0, Math.floor((Math.min(ay, by, cy) - lo[1]) / h - 0.5)), j1 = Math.min(ny - 1, Math.ceil((Math.max(ay, by, cy) - lo[1]) / h - 0.5));
    for (let j = j0; j <= j1; j++) {
      const py = lo[1] + (j + 0.5) * h + jy;
      for (let i = i0; i <= i1; i++) {
        const px = lo[0] + (i + 0.5) * h + jx;
        const l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den, l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den, l3 = 1 - l1 - l2;
        if (l1 < 0 || l2 < 0 || l3 < 0) continue;
        if (nh === cap) { cap *= 2; const nc = new Int32Array(cap); nc.set(hitCol); hitCol = nc; const nz2 = new Float64Array(cap); nz2.set(hitZ); hitZ = nz2; }
        hitCol[nh] = j * nx + i; hitZ[nh] = l1 * pos[a + 2] + l2 * pos[b + 2] + l3 * pos[c + 2]; nh++;
      }
    }
  }
  const start = new Int32Array(nx * ny + 1);
  for (let k = 0; k < nh; k++) start[hitCol[k] + 1]++;
  for (let k = 0; k < nx * ny; k++) start[k + 1] += start[k];
  const fill = start.slice(0, nx * ny), zs = new Float64Array(nh);
  for (let k = 0; k < nh; k++) zs[fill[hitCol[k]]++] = hitZ[k];
  let oddColumns = 0;
  for (let col = 0; col < nx * ny; col++) {
    const s = start[col], e = start[col + 1]; if (e === s) continue;
    const seg = zs.subarray(s, e); seg.sort();
    if ((e - s) % 2) oddColumns++;
    for (let q = 0; q + 1 < seg.length; q += 2) {
      const k0 = Math.max(0, Math.ceil((seg[q] - lo[2]) / h - 0.5)), k1 = Math.min(nz - 1, Math.floor((seg[q + 1] - lo[2]) / h - 0.5));
      for (let k = k0; k <= k1; k++) { const v = col + nx * ny * k; if (mat[v] === 0) { mat[v] = id; inside++; } }
    }
  }
  if (conservative) {
    const e = h * 0.5 * (1 - 1e-3);
    for (let t = 0; t < nt; t++) {
      const a = idx[3 * t] * 3, b = idx[3 * t + 1] * 3, c = idx[3 * t + 2] * 3;
      const rng = [0, 1, 2].map((k) => {
        const mn = Math.min(pos[a + k], pos[b + k], pos[c + k]), mx = Math.max(pos[a + k], pos[b + k], pos[c + k]), n = grid.dims[k];
        return [Math.max(0, Math.floor((mn - lo[k]) / h)), Math.min(n - 1, Math.ceil((mx - lo[k]) / h) - 1)];
      });
      for (let k = rng[2][0]; k <= rng[2][1]; k++) for (let j = rng[1][0]; j <= rng[1][1]; j++) for (let i = rng[0][0]; i <= rng[0][1]; i++) {
        const v = i + nx * (j + ny * k); if (mat[v] !== 0) continue;
        if (triBoxOverlap(lo[0] + (i + 0.5) * h, lo[1] + (j + 0.5) * h, lo[2] + (k + 0.5) * h, e, pos, a, b, c)) { mat[v] = id; touched++; }
      }
    }
  }
  return { inside, touched, oddColumns };
}

/** Paint an axis-aligned box [x0,x1,y0,y1,z0,z1] (mm) into empty voxels whose centres lie inside it. At least one voxel (the one holding the centre) is painted. Returns the voxel count. */
export function paintBox(grid, mat, box, id) {
  const { origin: lo, h, dims: [nx, ny, nz] } = grid;
  const b = [Math.min(box[0], box[1]), Math.max(box[0], box[1]), Math.min(box[2], box[3]), Math.max(box[2], box[3]), Math.min(box[4], box[5]), Math.max(box[4], box[5])];
  const r = (k) => [Math.max(0, Math.ceil((b[2 * k] - lo[k]) / h - 0.5)), Math.min(grid.dims[k] - 1, Math.floor((b[2 * k + 1] - lo[k]) / h - 0.5))];
  let n = 0; const [ri, rj, rk] = [r(0), r(1), r(2)];
  for (let k = rk[0]; k <= rk[1]; k++) for (let j = rj[0]; j <= rj[1]; j++) for (let i = ri[0]; i <= ri[1]; i++) { const v = i + nx * (j + ny * k); if (mat[v] === 0) { mat[v] = id; n++; } }
  if (n === 0) { // sub-voxel part: the voxel that holds its centre
    const c = [0, 1, 2].map((k) => Math.floor(((b[2 * k] + b[2 * k + 1]) / 2 - lo[k]) / h));
    if (c[0] >= 0 && c[1] >= 0 && c[2] >= 0 && c[0] < nx && c[1] < ny && c[2] < nz) { const v = c[0] + nx * (c[1] + ny * c[2]); if (mat[v] === 0) { mat[v] = id; n = 1; } }
  }
  return n;
}

/** Paint a cylinder { axis:'x'|'y'|'z', a0, a1, c:[u,v], r } (mm) by voxel centres, like paintBox. */
export function paintCylinder(grid, mat, cyl, id) {
  const { origin: lo, h, dims: [nx, ny, nz] } = grid, ax = 'xyz'.indexOf(cyl.axis || 'x'), o = [0, 1, 2].filter((k) => k !== ax);
  const bb = [0, 0, 0, 0, 0, 0]; bb[2 * ax] = cyl.a0; bb[2 * ax + 1] = cyl.a1; bb[2 * o[0]] = cyl.c[0] - cyl.r; bb[2 * o[0] + 1] = cyl.c[0] + cyl.r; bb[2 * o[1]] = cyl.c[1] - cyl.r; bb[2 * o[1] + 1] = cyl.c[1] + cyl.r;
  const rng = [0, 1, 2].map((k) => [Math.max(0, Math.ceil((Math.min(bb[2 * k], bb[2 * k + 1]) - lo[k]) / h - 0.5)), Math.min(grid.dims[k] - 1, Math.floor((Math.max(bb[2 * k], bb[2 * k + 1]) - lo[k]) / h - 0.5))]);
  let n = 0;
  for (let k = rng[2][0]; k <= rng[2][1]; k++) for (let j = rng[1][0]; j <= rng[1][1]; j++) for (let i = rng[0][0]; i <= rng[0][1]; i++) {
    const p = [lo[0] + (i + 0.5) * h, lo[1] + (j + 0.5) * h, lo[2] + (k + 0.5) * h];
    if (Math.hypot(p[o[0]] - cyl.c[0], p[o[1]] - cyl.c[1]) > cyl.r) continue;
    const v = i + nx * (j + ny * k); if (mat[v] === 0) { mat[v] = id; n++; }
  }
  if (n === 0) {
    const c = [0, 1, 2].map((k) => Math.floor(((bb[2 * k] + bb[2 * k + 1]) / 2 - lo[k]) / h));
    if (c[0] >= 0 && c[1] >= 0 && c[2] >= 0 && c[0] < nx && c[1] < ny && c[2] < nz) { const v = c[0] + nx * (c[1] + ny * c[2]); if (mat[v] === 0) { mat[v] = id; n = 1; } }
  }
  return n;
}

/**
 * Separate outside air from air enclosed by solid. Outside air is every empty voxel connected (6-neighbours) to the grid boundary;
 * the rest is set to CAVITY. Mutates `mat`. Returns { cavity, outside }.
 */
export function classifyAir(grid, mat) {
  const [nx, ny, nz] = grid.dims, total = grid.total, MARK = 254;
  const stack = new Int32Array(total); let sp = 0, outside = 0;
  const seed = (v) => { if (mat[v] === 0) { mat[v] = MARK; stack[sp++] = v; } };
  for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) { seed(nx * (j + ny * k)); seed(nx - 1 + nx * (j + ny * k)); }
  for (let k = 0; k < nz; k++) for (let i = 0; i < nx; i++) { seed(i + nx * ny * k); seed(i + nx * (ny - 1 + ny * k)); }
  for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) { seed(i + nx * j); seed(i + nx * (j + ny * (nz - 1))); }
  while (sp) {
    const v = stack[--sp], i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / (nx * ny);
    outside++;
    if (i > 0) seed(v - 1); if (i < nx - 1) seed(v + 1); if (j > 0) seed(v - nx); if (j < ny - 1) seed(v + nx); if (k > 0) seed(v - nx * ny); if (k < nz - 1) seed(v + nx * ny);
  }
  let cavity = 0;
  for (let v = 0; v < total; v++) { if (mat[v] === MARK) mat[v] = 0; else if (mat[v] === 0) { mat[v] = CAVITY; cavity++; } }
  return { cavity, outside };
}

/**
 * The sparse active-cell list: every voxel with mat != 0 (solid or enclosed air). cells[i] is the grid index of active cell i,
 * cellOf[v] the active index of grid voxel v (-1 if inactive), nbr[6i+f] the active neighbour through face f (-1: outside air or
 * the edge of the grid).
 */
export function buildStructure(grid, mat) {
  const [nx, ny, nz] = grid.dims, total = grid.total, nxy = nx * ny;
  let n = 0; for (let v = 0; v < total; v++) if (mat[v] !== 0) n++;
  const cells = new Int32Array(n), cellOf = new Int32Array(total).fill(-1), nbr = new Int32Array(6 * n).fill(-1);
  let c = 0; for (let v = 0; v < total; v++) if (mat[v] !== 0) { cells[c] = v; cellOf[v] = c++; }
  for (let a = 0; a < n; a++) {
    const v = cells[a], i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / nxy, o = 6 * a;
    if (i < nx - 1) nbr[o] = cellOf[v + 1]; if (i > 0) nbr[o + 1] = cellOf[v - 1];
    if (j < ny - 1) nbr[o + 2] = cellOf[v + nx]; if (j > 0) nbr[o + 3] = cellOf[v - nx];
    if (k < nz - 1) nbr[o + 4] = cellOf[v + nxy]; if (k > 0) nbr[o + 5] = cellOf[v - nxy];
  }
  return { n, cells, cellOf, nbr };
}

/** Centre (mm) of a grid voxel. */
export function cellCentre(grid, v) {
  const [nx, ny] = grid.dims, i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / (nx * ny), h = grid.h;
  return [grid.origin[0] + (i + 0.5) * h, grid.origin[1] + (j + 0.5) * h, grid.origin[2] + (k + 0.5) * h];
}

/**
 * Face conductances (W/K) of a scalar diffusion operator with per-cell conductivity k (W/m K): for the face between cells i and j
 * g = k_harmonic * h (area h^2 over distance h), h in metres. g[6i+f] is 0 where there is no neighbour. `kAxis` (3 values per cell,
 * x y z) makes the conductivity anisotropic: a face along axis a uses the a-th value of each cell.
 */
export function faceConductances(struct, k, hMetres, out, kAxis = null) {
  const { n, nbr } = struct, g = out || new Float64Array(6 * n);
  for (let i = 0; i < n; i++) for (let f = 0; f < 6; f++) {
    const j = nbr[6 * i + f]; if (j < 0) { g[6 * i + f] = 0; continue; }
    const ki = kAxis ? kAxis[3 * i + (f >> 1)] : k[i], kj = kAxis ? kAxis[3 * j + (f >> 1)] : k[j];
    g[6 * i + f] = (2 * ki * kj / (ki + kj)) * hMetres;
  }
  return g;
}

// ───────────────────────── grid-size policy ─────────────────────────
/** Active-voxel budgets per device class and quality: about 1 to 2 million on a laptop GPU, about 250 thousand on a phone and on the CPU fallback. */
export const GRID_BUDGETS = {
  laptop: { draft: 150000, normal: 600000, fine: 1500000 },
  phone: { draft: 40000, normal: 120000, fine: 250000 },
  cpu: { draft: 40000, normal: 120000, fine: 250000 },
};
export const GRID_HARD_LIMIT = { active: 2000000, total: 8000000 };
export const QUALITIES_3D = ['draft', 'normal', 'fine'];

/** 'phone' for a mobile user agent, else 'laptop'. `nav` is navigator (or a stub in tests). */
export function deviceClass(nav) {
  const ua = String((nav && nav.userAgent) || ''), mobile = nav && nav.userAgentData && nav.userAgentData.mobile;
  return mobile || /Android|iPhone|iPad|iPod|Mobile/i.test(ua) ? 'phone' : 'laptop';
}

/** Budget (active voxels) for a quality on a device and backend. The CPU fallback always uses the small budget. */
export function gridBudget({ quality = 'normal', device = 'laptop', backend = 'cpu' }) {
  if (!QUALITIES_3D.includes(quality)) throw new Error(`quality must be one of ${QUALITIES_3D.join(', ')}`);
  const cls = backend === 'cpu' ? 'cpu' : device;
  return { budget: GRID_BUDGETS[cls][quality], cls };
}

/**
 * Voxelize a set of meshes (and anything `paint` adds) at a voxel size chosen to meet the active-voxel budget.
 *   meshes: [{ positions, indices, id }], bbox: { lo, hi } (mm), paint(grid, mat): optional extra painter (parts that are not meshes)
 *   h: fixed voxel size (mm) or null to choose one from `budget`.
 * Refines or coarsens up to four times so that the active count lands within the budget; refuses grids over the hard limits.
 * Returns { grid, mat, struct, air: { cavity }, stats }.
 */
export function buildVoxelModel({ meshes, bbox, paint = null, h = null, budget = 600000, conservative = true, maxActive = GRID_HARD_LIMIT.active, maxTotal = GRID_HARD_LIMIT.total }) {
  const size = [0, 1, 2].map((k) => bbox.hi[k] - bbox.lo[k]);
  if (!(size.every((s) => s > 0))) throw new Error('the model has no extent along at least one axis');
  const vol = size[0] * size[1] * size[2];
  let hh = h || Math.cbrt(vol / budget), tries = 0, out = null;
  for (;;) {
    const grid = makeGrid(bbox.lo, bbox.hi, hh);
    if (grid.total > maxTotal) {
      if (h) throw new Error(`grid ${grid.dims.join('×')} = ${grid.total.toLocaleString('en')} cells exceeds the limit of ${maxTotal.toLocaleString('en')}; use a voxel size of at least ${Math.cbrt(vol / maxTotal).toFixed(3)} mm`);
      hh = Math.cbrt(vol / maxTotal) * 1.01; continue;
    }
    const mat = new Uint8Array(grid.total), paints = [];
    for (const m of meshes) paints.push({ id: m.id, ...paintMesh(grid, mat, m, m.id, { conservative }) });
    if (paint) paint(grid, mat);
    const air = classifyAir(grid, mat), struct = buildStructure(grid, mat);
    out = { grid, mat, struct, air, stats: { paints, active: struct.n, attempts: tries + 1 } };
    if (h) break;
    const ratio = struct.n / budget;
    if (tries >= 4 || (ratio < 1.15 && ratio > 0.4)) break;
    // sparse shapes fill less of their box than the estimate: rescale h towards the budget (never coarser than 1.6x per step)
    hh = hh * Math.min(1.6, Math.max(0.5, Math.cbrt(ratio))); tries++;
  }
  if (out.struct.n > maxActive) throw new Error(`${out.struct.n.toLocaleString('en')} active voxels exceed the limit of ${maxActive.toLocaleString('en')}; choose a coarser quality or a larger voxel size`);
  return out;
}
