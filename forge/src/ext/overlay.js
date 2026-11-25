// Overlay colour mapping and probing, kept free of three.js so it can be tested headless.
// The map is sequential and luminance-monotonic (dark to light), so values stay readable without hue; the legend always prints
// the numbers and the unit, and a tap prints the exact value. Colour is never the only carrier of the value.

// dark blue > teal > green > light yellow (anchors in sRGB, 0..1)
const STOPS = [[0.05, 0.1, 0.35], [0.1, 0.4, 0.55], [0.25, 0.65, 0.5], [0.6, 0.85, 0.4], [0.98, 0.95, 0.6]];

export function colorAt(t) {
  const x = Math.min(1, Math.max(0, Number.isFinite(t) ? t : 0)) * (STOPS.length - 1), i = Math.min(STOPS.length - 2, Math.floor(x)), f = x - i;
  return STOPS[i].map((c, k) => c + (STOPS[i + 1][k] - c) * f);
}
export const cssGradient = () => 'linear-gradient(90deg,' + STOPS.map((c, i) => `rgb(${c.map((v) => Math.round(v * 255)).join(',')}) ${Math.round((i / (STOPS.length - 1)) * 100)}%`).join(',') + ')';

/** Value range of an overlay: the package's own `range`, else min/max of the finite values. */
export function rangeOf(ov) {
  if (ov.range) return ov.range;
  if (ov.field) return fieldRange(ov.field.data);
  let lo = Infinity, hi = -Infinity;
  for (const v of ov.values) if (Number.isFinite(v)) { if (v < lo) lo = v; if (v > hi) hi = v; }
  if (!Number.isFinite(lo)) return [0, 1];
  return hi > lo ? [lo, hi] : [lo - 0.5, hi + 0.5];
}

/** RGB float array (3 per point) for an overlay's values. */
export function colorsFor(ov, [lo, hi] = rangeOf(ov)) {
  const out = new Float32Array(ov.values.length * 3);
  for (let i = 0; i < ov.values.length; i++) { const c = colorAt((ov.values[i] - lo) / (hi - lo)); out[i * 3] = c[0]; out[i * 3 + 1] = c[1]; out[i * 3 + 2] = c[2]; }
  return out;
}

/** Legend rows: five tick values from low to high, formatted. */
export function legendTicks([lo, hi], n = 5) {
  const dp = Math.abs(hi - lo) >= 100 ? 0 : Math.abs(hi - lo) >= 10 ? 1 : 2;
  return Array.from({ length: n }, (_, i) => +(lo + ((hi - lo) * i) / (n - 1)).toFixed(dp));
}

/**
 * Nearest point of a points overlay to a screen position.
 * @param project (x, y, z) -> { x, y, depth } screen position in pixels, or null when behind the camera
 * @returns {{index:number, distance:number, value:number, position:number[]}|null}
 */
export function nearestPoint(ov, project, sx, sy, maxPx = 22) {
  let best = null;
  for (let i = 0; i < ov.values.length; i++) {
    const p = project(ov.positions[i * 3], ov.positions[i * 3 + 1], ov.positions[i * 3 + 2]); if (!p) continue;
    const d = Math.hypot(p.x - sx, p.y - sy);
    if (d <= maxPx && (!best || d < best.distance - 0.01 || (Math.abs(d - best.distance) <= 0.01 && p.depth < best.depth))) best = { index: i, distance: d, depth: p.depth, value: ov.values[i], position: [ov.positions[i * 3], ov.positions[i * 3 + 1], ov.positions[i * 3 + 2]] };
  }
  return best;
}

// ───────── analysis fields (docs/EXTENSIONS_SPEC.md section 3.3): surface maps and voxel slices. Pure functions, tested headless.

export const fieldRange = (data) => {
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < data.length; i++) { const v = data[i]; if (Number.isFinite(v)) { if (v < lo) lo = v; if (v > hi) hi = v; } }
  if (!Number.isFinite(lo)) return [0, 1];
  return hi > lo ? [lo, hi] : [lo - 0.5, hi + 0.5];
};

/** Linear interpolation of a per-vertex value at a point inside triangle `face` (barycentric). Positions in mm. */
export function interpolateSurface(pos, idx, values, face, point) {
  const a = idx[face * 3], b = idx[face * 3 + 1], c = idx[face * 3 + 2];
  const A = [pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2]], B = [pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2]], C = [pos[c * 3], pos[c * 3 + 1], pos[c * 3 + 2]];
  const sub = (p, q) => [p[0] - q[0], p[1] - q[1], p[2] - q[2]], dot = (p, q) => p[0] * q[0] + p[1] * q[1] + p[2] * q[2];
  const v0 = sub(B, A), v1 = sub(C, A), v2 = sub(point, A);
  const d00 = dot(v0, v0), d01 = dot(v0, v1), d11 = dot(v1, v1), d20 = dot(v2, v0), d21 = dot(v2, v1), den = d00 * d11 - d01 * d01;
  if (Math.abs(den) < 1e-18) return values[a];
  const vb = (d11 * d20 - d01 * d21) / den, vc = (d00 * d21 - d01 * d20) / den, va = 1 - vb - vc;
  return va * values[a] + vb * values[b] + vc * values[c];
}

/** Value of a voxel field at a point (mm), or null outside the grid. Cell (i,j,k) covers origin + [i, i+1) h. Data order: i fastest, then j, then k. */
export function sampleVoxel(field, x, y, z) {
  const { origin, h, dims } = field.grid;
  const i = Math.floor((x - origin[0]) / h), j = Math.floor((y - origin[1]) / h), k = Math.floor((z - origin[2]) / h);
  if (i < 0 || j < 0 || k < 0 || i >= dims[0] || j >= dims[1] || k >= dims[2]) return null;
  return field.data[(k * dims[1] + j) * dims[0] + i];
}

/** Axis index and the number of slices along it. */
export const sliceCount = (field, axis) => field.grid.dims['xyz'.indexOf(axis)];

/**
 * RGBA image of one slice. axis 'x' | 'y' | 'z', `index` the cell index along it. Image columns/rows follow the two remaining axes in
 * order (x: y,z; y: x,z; z: x,y). Cells are coloured with the same map as the legend; NaN cells are transparent.
 */
export function sliceImage(field, axis, index, range = fieldRange(field.data)) {
  const [nx, ny, nz] = field.grid.dims, ax = 'xyz'.indexOf(axis);
  if (ax < 0) throw new Error('axis must be x, y or z');
  const n = [nx, ny, nz][ax]; if (index < 0 || index >= n) throw new Error(`slice ${index} is outside 0..${n - 1}`);
  const [w, hgt] = ax === 0 ? [ny, nz] : ax === 1 ? [nx, nz] : [nx, ny];
  const rgba = new Uint8Array(w * hgt * 4);
  for (let r = 0; r < hgt; r++) for (let q = 0; q < w; q++) {
    const [i, j, k] = ax === 0 ? [index, q, r] : ax === 1 ? [q, index, r] : [q, r, index];
    const v = field.data[(k * ny + j) * nx + i], o = (r * w + q) * 4;
    if (!Number.isFinite(v)) { rgba[o + 3] = 0; continue; }
    const c = colorAt((v - range[0]) / (range[1] - range[0]));
    rgba[o] = Math.round(c[0] * 255); rgba[o + 1] = Math.round(c[1] * 255); rgba[o + 2] = Math.round(c[2] * 255); rgba[o + 3] = 255;
  }
  return { w, h: hgt, rgba };
}

/** World placement of a slice plane: the coordinate along the axis and the rectangle it spans (mm). */
export function slicePlane(field, axis, index) {
  const { origin: o, h, dims: d } = field.grid, ax = 'xyz'.indexOf(axis);
  const at = o[ax] + (index + 0.5) * h;
  const span = (a) => [o[a], o[a] + d[a] * h];
  const [u, v] = ax === 0 ? [1, 2] : ax === 1 ? [0, 2] : [0, 1];
  return { axis, at, u: { axis: 'xyz'[u], range: span(u) }, v: { axis: 'xyz'[v], range: span(v) } };
}

/** Plain-language state of a voxel grid for the UI: the voxel size and the limit the framework enforces. */
export const gridText = (g) => `voxel ${+g.h.toFixed(3)} mm, ${g.dims.join('×')} = ${(g.dims[0] * g.dims[1] * g.dims[2]).toLocaleString('en')} cells (limit 8 000 000)`;
