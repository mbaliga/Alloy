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
