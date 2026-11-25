// Cell texture geometry (2D): jittered-grid Voronoi cells, inset by half a gap and rounded. No shapely: d3-delaunay (ISC).
// The jitter uses a Mersenne Twister seeded like Python's random.Random(seed), so a given seed gives the same cell layout
// as the Python reference generator this template was ported from.
import { Delaunay } from 'd3-delaunay';

export class MT19937 {
  constructor(seed) {
    this.mt = new Uint32Array(624); this.i = 625;
    this.initArray([seed >>> 0]);
  }
  initGenrand(s) {
    const mt = this.mt; mt[0] = s >>> 0;
    for (let i = 1; i < 624; i++) { const p = mt[i - 1] ^ (mt[i - 1] >>> 30); mt[i] = (Math.imul(1812433253, p) + i) >>> 0; }
    this.i = 624;
  }
  initArray(key) {
    this.initGenrand(19650218);
    const mt = this.mt; let i = 1, j = 0;
    for (let k = Math.max(624, key.length); k; k--) {
      const p = mt[i - 1] ^ (mt[i - 1] >>> 30);
      mt[i] = ((mt[i] ^ Math.imul(p, 1664525)) + key[j] + j) >>> 0;
      i++; j++; if (i >= 624) { mt[0] = mt[623]; i = 1; } if (j >= key.length) j = 0;
    }
    for (let k = 623; k; k--) {
      const p = mt[i - 1] ^ (mt[i - 1] >>> 30);
      mt[i] = ((mt[i] ^ Math.imul(p, 1566083941)) - i) >>> 0;
      i++; if (i >= 624) { mt[0] = mt[623]; i = 1; }
    }
    mt[0] = 0x80000000; this.i = 624;
  }
  next32() {
    const mt = this.mt;
    if (this.i >= 624) {
      let k = 0, y;
      for (; k < 624 - 397; k++) { y = (mt[k] & 0x80000000) | (mt[k + 1] & 0x7fffffff); mt[k] = mt[k + 397] ^ (y >>> 1) ^ (y & 1 ? 0x9908b0df : 0); }
      for (; k < 623; k++) { y = (mt[k] & 0x80000000) | (mt[k + 1] & 0x7fffffff); mt[k] = mt[k + (397 - 624)] ^ (y >>> 1) ^ (y & 1 ? 0x9908b0df : 0); }
      y = (mt[623] & 0x80000000) | (mt[0] & 0x7fffffff); mt[623] = mt[396] ^ (y >>> 1) ^ (y & 1 ? 0x9908b0df : 0);
      this.i = 0;
    }
    let y = mt[this.i++];
    y ^= y >>> 11; y ^= (y << 7) & 0x9d2c5680; y ^= (y << 15) & 0xefc60000; y ^= y >>> 18;
    return y >>> 0;
  }
  random() { const a = this.next32() >>> 5, b = this.next32() >>> 6; return (a * 67108864 + b) / 9007199254740992; }
  uniform(a, b) { return a + (b - a) * this.random(); }
}

const area = (p) => { let s = 0; for (let i = 0; i < p.length; i++) { const a = p[i], b = p[(i + 1) % p.length]; s += a[0] * b[1] - b[0] * a[1]; } return s / 2; };

/** Shrink a convex CCW polygon by d with mitre joins (intersection with half-planes moved inward). */
export function insetConvex(poly, d) {
  let out = poly;
  for (let i = 0; i < poly.length && out.length; i++) {
    const a = poly[i], b = poly[(i + 1) % poly.length];
    const ex = b[0] - a[0], ey = b[1] - a[1], l = Math.hypot(ex, ey);
    if (l < 1e-9) continue;
    const nx = -ey / l, ny = ex / l;                 // inward normal for CCW
    const c = nx * a[0] + ny * a[1] + d;             // keep n.p >= c
    const next = [];
    for (let k = 0; k < out.length; k++) {
      const p = out[k], q = out[(k + 1) % out.length];
      const dp = nx * p[0] + ny * p[1] - c, dq = nx * q[0] + ny * q[1] - c;
      if (dp >= 0) next.push(p);
      if ((dp >= 0) !== (dq >= 0)) { const t = dp / (dp - dq); next.push([p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t]); }
    }
    out = next;
  }
  return out.length >= 3 && area(out) > 1e-6 ? out : [];
}

/**
 * Cells for the texture window. Returns [{ pts: [[x,y]...] (CCW, the opened polygon before rounding), r, area }]
 * where each polygon is the Voronoi cell, inset by gap/2 (mitre), opened by `round` (inset then dilate: corners rounded
 * with radius `round`). The caller builds the rounded shape (true arcs) from `core` (inset by round) + radius `r`.
 */
export function cellLayout({ window: win, nx = 10, ny = 3, jitter = 0.28, gap = 1.1, round = 0.6, minArea = 4, seed = 11, density = 1 }) {
  const [x0, x1, y0, y1] = win;
  nx = Math.max(1, Math.round(nx * density)); ny = Math.max(1, Math.round(ny * density));
  const rnd = new MT19937(seed);
  const pts = [];
  for (let i = 0; i < nx; i++) for (let j = 0; j < ny; j++) {
    const px = x0 + (i + 0.5 + rnd.uniform(-jitter, jitter)) * (x1 - x0) / nx;
    const py = y0 + (j + 0.5 + rnd.uniform(-jitter, jitter)) * (y1 - y0) / ny;
    pts.push([px, py]);
  }
  const vor = Delaunay.from(pts).voronoi([x0, y0, x1, y1]);
  const cells = [];
  for (let i = 0; i < pts.length; i++) {
    const cell = vor.cellPolygon(i);
    if (!cell) continue;
    let poly = cell.slice(0, -1);
    if (area(poly) < 0) poly = poly.reverse();
    const inset = insetConvex(poly, gap / 2);
    if (!inset.length) continue;
    const core = insetConvex(inset, round);        // eroded by the rounding radius: the dilation by `round` rebuilds the outline
    if (!core.length) continue;
    // area of core dilated by `round` (round corners): A + P*r + pi*r^2
    let per = 0; for (let k = 0; k < core.length; k++) { const a = core[k], b = core[(k + 1) % core.length]; per += Math.hypot(b[0] - a[0], b[1] - a[1]); }
    const ar = area(core) + per * round + Math.PI * round * round;
    if (ar < minArea) continue;
    cells.push({ core, r: round, area: ar });
  }
  return { points: pts, cells };
}
