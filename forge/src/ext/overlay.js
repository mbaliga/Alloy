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
  if (ov.kind === 'box') return [0, 1]; // a box carries no values
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
export const gridText = (g) => `voxel ${+g.h.toFixed(3)} mm, ${g.dims.join('×')} = ${(g.dims[0] * g.dims[1] * g.dims[2]).toLocaleString('en')} cells (limit 8 000 000)${g.active ? `, ${g.active.toLocaleString('en')} active${g.budget ? ` of a ${g.budget.toLocaleString('en')} budget` : ''}` : ''}`;

// ───────── vector overlays (arrows and streamlines, e.g. airflow). `positions` and `vectors` are 3 floats per sample, `values` the
// magnitude per sample (the host computes it, so the colour map, legend and probe work as for points). Optional `lines` holds the
// start index of each streamline in sample order: with it the samples are drawn as polylines, without it as one arrow per sample.

/** Magnitude (|v|) per sample of a packed 3-vector array. */
export function vectorMagnitudes(vectors) {
  const n = Math.floor(vectors.length / 3), out = new Float32Array(n);
  for (let i = 0; i < n; i++) out[i] = Math.hypot(vectors[3 * i], vectors[3 * i + 1], vectors[3 * i + 2]);
  return out;
}

/** Arrow length (mm) for the largest magnitude: the package's `scale` is mm per unit, else about one sample spacing (bounding-box diagonal over the cube root of the count). */
export function vectorScale(ov) {
  const n = ov.values.length; let max = 0;
  for (let i = 0; i < n; i++) if (ov.values[i] > max) max = ov.values[i];
  if (!(max > 0)) return { mmPerUnit: 1, maxLen: 0 };
  if (ov.scale > 0) return { mmPerUnit: ov.scale, maxLen: ov.scale * max };
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < n; i++) for (let a = 0; a < 3; a++) { const v = ov.positions[3 * i + a]; if (v < lo[a]) lo[a] = v; if (v > hi[a]) hi[a] = v; }
  const diag = Math.hypot(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) || 1, len = Math.min(diag / 4, (1.2 * diag) / Math.max(2, Math.cbrt(n)));
  return { mmPerUnit: len / max, maxLen: len };
}

/**
 * Line segments for a vectors overlay: Float32Array of segment end points (3 per vertex, 2 vertices per segment) and the magnitude of
 * the sample each vertex belongs to (for colour). Arrows are a shaft and two barbs; streamlines join consecutive samples of a line.
 */
export function vectorSegments(ov) {
  const n = ov.values.length, P = ov.positions, V = ov.vectors, pos = [], val = [];
  const seg = (a, b, m) => { pos.push(a[0], a[1], a[2], b[0], b[1], b[2]); val.push(m, m); };
  if (ov.lines && ov.lines.length) {
    for (let k = 0; k < ov.lines.length; k++) {
      const s = ov.lines[k], e = k + 1 < ov.lines.length ? ov.lines[k + 1] : n;
      for (let i = s; i + 1 < e; i++) seg([P[3 * i], P[3 * i + 1], P[3 * i + 2]], [P[3 * i + 3], P[3 * i + 4], P[3 * i + 5]], ov.values[i]);
    }
  } else {
    const { mmPerUnit } = vectorScale(ov);
    for (let i = 0; i < n; i++) {
      const m = ov.values[i]; if (!(m > 0)) continue;
      const d = [V[3 * i] / m, V[3 * i + 1] / m, V[3 * i + 2] / m], L = m * mmPerUnit, a = [P[3 * i], P[3 * i + 1], P[3 * i + 2]], b = [a[0] + d[0] * L, a[1] + d[1] * L, a[2] + d[2] * L];
      // a unit vector perpendicular to the direction, for the barbs
      const t = Math.abs(d[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0], c = [d[1] * t[2] - d[2] * t[1], d[2] * t[0] - d[0] * t[2], d[0] * t[1] - d[1] * t[0]], cl = Math.hypot(...c) || 1, u = c.map((x) => x / cl), hd = L * 0.28;
      seg(a, b, m);
      for (const s of [1, -1]) seg(b, [b[0] - d[0] * hd + u[0] * hd * 0.5 * s, b[1] - d[1] * hd + u[1] * hd * 0.5 * s, b[2] - d[2] * hd + u[2] * hd * 0.5 * s], m);
    }
  }
  return { positions: Float32Array.from(pos), values: Float32Array.from(val) };
}

/**
 * Arrows for a gridded vector field (an analysis field of kind 'vector': 3 floats per cell, i fastest). Cells are sampled with a
 * stride so at most about `maxArrows` arrows are drawn; cells with NaN or zero magnitude are skipped. Positions are cell centres (mm).
 */
export function vectorFieldSamples(field, maxArrows = 4000) {
  const [nx, ny, nz] = field.grid.dims, { origin, h } = field.grid, total = nx * ny * nz;
  const stride = Math.max(1, Math.ceil(Math.cbrt(total / maxArrows))), P = [], V = [];
  for (let k = 0; k < nz; k += stride) for (let j = 0; j < ny; j += stride) for (let i = 0; i < nx; i += stride) {
    const c = (k * ny + j) * nx + i, x = field.data[3 * c], y = field.data[3 * c + 1], z = field.data[3 * c + 2];
    if (!Number.isFinite(x + y + z) || !(Math.hypot(x, y, z) > 0)) continue;
    P.push(origin[0] + (i + 0.5) * h, origin[1] + (j + 0.5) * h, origin[2] + (k + 0.5) * h); V.push(x, y, z);
  }
  const vectors = Float32Array.from(V);
  return { positions: Float32Array.from(P), vectors, values: vectorMagnitudes(vectors), stride };
}
