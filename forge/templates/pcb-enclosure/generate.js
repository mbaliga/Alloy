// PCB enclosure template: generic, parametric, on the OCCT (B-rep) kernel. A port of the logic of an offline Python
// (build123d) enclosure generator: cavity from component keep-outs plus clearance, hollow inset shell with cradles,
// ruled lofts of filleted trapezoid sections along a station table, Voronoi cell texture, rear cap with fasteners and
// screw pilots, and optional regions kept from an original part.
//
// Frame: x = length (front at -x, rear opening at +x), y = up, z = width.
//
//   generateEnclosure(k, pcb, enc, ctx) -> { shell, cap, outer, keepouts: {name: Shape}, xFront, xRear, warnings, ... }
//
//   k    ops from src/occt/ops.js (makeOps)
//   pcb  { components:[...], cutouts:[...], supports:[...], keep:[...] }  (see doc in README.md)
//   enc  numeric enclosure parameters: { style, wall, clearance, yBottom, xFront, xRear?, stations:{monolith,bullet},
//        cap:{...}, fastener, texture:{...}, ... }
//   ctx  { sources: { id: Shape }, warn(msg) }
import { cellLayout } from './voronoi.js';

export const FASTENERS = {
  M2: { pilotR: 0.8, clearR: 1.1, headR: 2.1 },
  'M2.5': { pilotR: 1.0, clearR: 1.4, headR: 2.6 },
  M3: { pilotR: 1.25, clearR: 1.7, headR: 3.1 },
};
export const CLEARANCE_CLASS = { tight: 0.5, normal: 1, loose: 1.5 };

/** Resolve a coordinate that is a number or a string such as "rear", "rear-6.5", "front+1.8". */
export function coord(v, ref) {
  if (typeof v === 'number') return v;
  const m = /^\s*(rear|front)\s*([+-]\s*\d+(?:\.\d+)?)?\s*$/.exec(String(v));
  if (m) return ref[m[1] === 'rear' ? 'xRear' : 'xFront'] + (m[2] ? parseFloat(m[2].replace(/\s+/g, '')) : 0);
  const n = parseFloat(v);
  if (!Number.isFinite(n)) throw new Error(`bad coordinate "${v}"`);
  return n;
}

export function componentBox(c) {
  if (c.kind === 'cylinder') {
    const cy = c.cyl, ax = cy.axis || 'x';
    const a = [cy.a0, cy.a1], u = [cy.c[0] - cy.r, cy.c[0] + cy.r], v = [cy.c[1] - cy.r, cy.c[1] + cy.r];
    return ax === 'x' ? [a[0], a[1], u[0], u[1], v[0], v[1]] : ax === 'y' ? [u[0], u[1], a[0], a[1], v[0], v[1]] : [u[0], u[1], v[0], v[1], a[0], a[1]];
  }
  return c.box;
}

/** Derived frame numbers: xRear, xFront. */
export function frameOf(pcb, enc) {
  const c = enc.clearance, w = enc.wall;
  const comps = pcb.components || [];
  const rear = comps.filter((x) => x.insert === 'rear').map((x) => componentBox(x)[1]);
  const xRear = enc.xRear != null ? enc.xRear : (rear.length ? Math.max(...rear) : 0) + c + w;
  const xFront = enc.xFront != null ? enc.xFront : Math.min(...comps.map((x) => componentBox(x)[0]), 0) - c - w;
  return { xRear, xFront };
}

export function stationsFor(enc, ref, style = enc.style) {
  const key = style === 'bullet' ? 'bullet' : 'monolith';
  const tab = enc.stations[key] || enc.stations.monolith;
  if (!tab) throw new Error('enclosure has no station table for style ' + style);
  return tab.map((r) => ({ x: coord(r[0], ref), hb: r[1], ht: r[2], yb: r[3], yt: r[4], rb: r[5], rt: r[6] }));
}

/** The void profile: every station shrunk by d; the front station moves inward, the rear one runs out through the opening. */
export function insetStations(st, d, ref) {
  const out = st.map((s, i) => ({
    x: i === 0 ? s.x + d : s.x, hb: s.hb - d, ht: s.ht - d, yb: s.yb + d, yt: s.yt - d, rb: Math.max(s.rb - d, 0.4), rt: Math.max(s.rt - d, 0.4),
  }));
  out[out.length - 1] = { ...out[out.length - 1], x: ref.xRear + 1.0 };
  return out;
}

const REAR_OVERRUN = 5;     // cavity and cradles run this far past the rear face, so the stack slides in from the back

function cavityShapes(k, pcb, enc, ref) {
  const c = enc.clearance, yB = enc.yBottom, xr = ref.xRear + REAR_OVERRUN;
  const out = [];
  for (const comp of pcb.components || []) {
    const cc = c * (CLEARANCE_CLASS[comp.clearance || 'normal'] ?? 1);
    const b = componentBox(comp);
    if (comp.kind === 'cylinder') {
      const cy = comp.cyl, ax = cy.axis || 'x';
      const a0 = cy.a0 - cc, a1 = comp.insert === 'rear' ? xr : cy.a1 + cc;
      out.push(k.cyl(ax, cy.r + cc, a0, a1, cy.c[0], cy.c[1]));
    } else {
      let [x0, x1, y0, y1, z0, z1] = b;
      x0 -= cc; z0 -= cc; z1 += cc; y1 += cc; y0 -= cc; x1 += cc;
      if (comp.insert === 'rear') x1 = xr;
      if (comp.insert === 'bottom') y0 = yB - 1;
      out.push(k.box(x0, x1, y0, y1, z0, z1));
      if (comp.edgeSlot) {
        const es = comp.edgeSlot;
        out.push(k.box(x0, xr, es.y[0], es.y[1], b[4] - cc - es.depth, b[5] + cc + es.depth));
      }
    }
  }
  for (const co of pcb.cutouts || []) {
    if (co.shape === 'cylinder') {
      const ax = co.axis || 'x', ce = co.center.map((v) => coord(v, ref));
      const rest = ['x', 'y', 'z'].filter((q) => q !== ax).map((q) => ce['xyz'.indexOf(q)]);
      out.push(k.cyl(ax, co.r, ce['xyz'.indexOf(ax)] - co.length / 2, ce['xyz'.indexOf(ax)] + co.length / 2, rest[0], rest[1]));
    } else {
      const b = co.box.map((v) => coord(v, ref));
      out.push(k.box(...b));
    }
  }
  return out;
}

function cradleShapes(k, pcb, enc, ref) {
  const c = enc.clearance, xr = ref.xRear + REAR_OVERRUN;
  const out = [];
  for (const comp of pcb.components || []) {
    const cr = comp.cradle; if (!cr) continue;
    const cc = c * (CLEARANCE_CLASS[comp.clearance || 'normal'] ?? 1), t = cr.t ?? 1.2;
    const [x0, x1, y0, y1, z0, z1] = componentBox(comp);
    const ze = cr.zExtra || 0;
    out.push(k.box(x0 - cc - t, cr.rear ? xr : x1 + cc + t,
      cr.downTo != null ? cr.downTo : y0 - cc - t, cr.upTo != null ? cr.upTo : y1 + cc + t,
      z0 - cc - t - ze, z1 + cc + t + ze));
  }
  for (const s of pcb.supports || []) out.push(k.box(...s.box.map((v) => coord(v, ref))));
  return out;
}

/** Keep-out solids, no clearance (used by the checks and as ghost references). */
export function keepoutShapes(k, pcb) {
  const o = {};
  for (const c of pcb.components || []) {
    if (c.kind === 'cylinder') { const cy = c.cyl; o[c.name] = k.cyl(cy.axis || 'x', cy.r, cy.a0, cy.a1, cy.c[0], cy.c[1]); }
    else o[c.name] = k.box(...c.box);
  }
  return o;
}

/** Rounded cell cutter: the core polygon dilated by r with true arcs, extruded along z from -h to +h. */
function cellPrism(k, cell, h) {
  const { R } = k, P = cell.core, n = P.length, r = cell.r;
  const nrm = (a, b) => { const ex = b[0] - a[0], ey = b[1] - a[1], l = Math.hypot(ex, ey); return [ey / l, -ex / l]; }; // outward for CCW
  const E = [];
  for (let i = 0; i < n; i++) {
    const a = P[i], b = P[(i + 1) % n], nn = nrm(a, b);
    E.push({ n: nn, a: [a[0] + nn[0] * r, a[1] + nn[1] * r], b: [b[0] + nn[0] * r, b[1] + nn[1] * r] });
  }
  const W = (p) => [p[0], p[1], -h];
  const edges = [];
  for (let i = 0; i < n; i++) {
    const e = E[i], nx = E[(i + 1) % n], v = P[(i + 1) % n];
    edges.push(R.makeLine(W(e.a), W(e.b)));
    let mx = e.n[0] + nx.n[0], my = e.n[1] + nx.n[1]; const ml = Math.hypot(mx, my);
    if (ml < 1e-9) continue;
    mx /= ml; my /= ml;
    edges.push(R.makeThreePointArc(W(e.b), W([v[0] + mx * r, v[1] + my * r]), W(nx.a)));
  }
  const face = R.makeFace(R.assembleWire(edges));
  return R.basicFaceExtrusion(face, new R.Vector([0, 0, 2 * h]));
}

function capScrews(enc) {
  const f = FASTENERS[enc.fastener] || FASTENERS.M2;
  const cap = enc.cap || {};
  return { ...f, positions: cap.screwPositions || [[-2, -7], [-2, 7]], pilotLen: cap.pilotLen ?? 9 };
}

/** Rear cap: a plate in the rear section profile, flush in the opening, plus an optional plug (deeper than the plate it reaches inward) and countersunk fastener holes. */
function buildCap(k, pcb, enc, ref, st, warn) {
  const cap = enc.cap || {};
  if (cap.type === 'none') return null;
  const last = st[st.length - 1];
  const t = cap.thickness ?? 1.8;
  // the plate sits flush inside the rear opening: x from xRear - t to xRear (the envelope does not grow)
  const secA = k.polySection(ref.xRear - t, last.hb, last.ht, last.yb, last.yt, last.rb, last.rt);
  const secB = k.polySection(ref.xRear, last.hb, last.ht, last.yb, last.yt, last.rb, last.rt);
  let plate = k.loft([secA, secB], true);
  plate = k.edgeTreat(plate, cap.edgeFillet ?? 0.8, (e) => e.inPlane('YZ', ref.xRear), warn);
  const comps = (pcb.components || []);
  const plugD = cap.plugDepth ?? 1.6, shrink = cap.plugShrink ?? 0.15;
  const plugs = [];
  for (const nm of cap.plugComponents || []) {
    const comp = comps.find((x) => x.name === nm); if (!comp) { warn(`cap: no component "${nm}" for the plug`); continue; }
    const cc = enc.clearance * (CLEARANCE_CLASS[comp.clearance || 'normal'] ?? 1);
    const [, , y0, y1, z0, z1] = componentBox(comp);
    plugs.push(k.box(ref.xRear - plugD, ref.xRear, y0 - cc + shrink, y1 + cc - shrink, z0 - cc + shrink, z1 + cc - shrink));
  }
  let body = plate;
  if (plugs.length) {
    let plug = k.fuseAll(plugs);
    if (cap.relief) { const r = cap.relief; plug = plug.cut(k.box(ref.xRear + r.x[0], ref.xRear + r.x[1], r.y[0], r.y[1], r.z[0], r.z[1])); }
    body = body.fuse(plug);
  }
  const f = capScrews(enc);
  const tools = [];
  for (const [y, z] of f.positions) {
    tools.push(k.cyl('x', f.clearR, ref.xRear - t - 4, ref.xRear + 4, y, z));
    tools.push(k.cone('x', f.clearR, f.headR, ref.xRear - 1.0, ref.xRear + 0.001, y, z));
  }
  return k.cutMany(body, tools);
}

/**
 * @returns {{ shell, cap, outer, keepouts, xFront, xRear, stations, warnings, timings }}
 */
export function generateEnclosure(k, pcb, enc, ctx = {}) {
  const warnings = [];
  const warn = (m) => { warnings.push(m); ctx.warn && ctx.warn(m); };
  const T0 = Date.now(), timings = {};
  const lap = (n) => { timings[n] = Date.now() - T0; };
  // name the stage when an OCCT operation traps, so a failing parameter set can be diagnosed
  const stage = (name, fn) => { try { const r = fn(); lap(name); return r; } catch (e) { throw new Error(`${name}: ${e && e.message ? e.message : 'geometry operation failed'}`); } };
  const ref = frameOf(pcb, enc);
  const style = enc.style || 'monolith';
  const wall = enc.wall;
  const st = stationsFor(enc, ref, style);
  const outer = stage('outer body', () => k.loftStations(st));
  const stInset = insetStations(st, wall, ref);
  const cradleU = stage('cradles', () => k.fuseAll(cradleShapes(k, pcb, enc, ref)));
  const voidS = stage('inner void', () => k.loftStations(stInset).cut(cradleU));
  let shell = outer;
  // regions kept from an original part (e.g. a mounting clamp), taken from a tagged source solid
  for (const keep of pcb.keep || []) {
    const src = ctx.sources && ctx.sources[keep.source];
    if (!src) { warn(`keep "${keep.name}": source "${keep.source}" is not loaded; region skipped`); continue; }
    const pieces = (keep.boxes || []).map((b) => { try { return src.intersect(k.box(...b.map((v) => coord(v, ref)))); } catch (e) { warn(`keep "${keep.name}" region failed: ${e.message}`); return null; } }).filter(Boolean);
    for (const pc of pieces) { // one at a time: fusing the pieces first and then the body failed on OCCT for coplanar contact
      const next = shell.fuse(pc);
      if (next.solids.length) shell = next; else warn(`keep "${keep.name}": a region could not be merged`);
    }
  }
  lap('keep');
  shell = stage('subtract void', () => shell.cut(voidS));
  const cavity = stage('cavity shapes', () => k.fuseAll(cavityShapes(k, pcb, enc, ref)));
  shell = stage('subtract cavity', () => shell.cut(cavity));

  if (style === 'cell') {
    const tx = enc.texture || {};
    const win = tx.window || [ref.xFront + 3.6, ref.xRear - 11.6, enc.yBottom + 2.4, enc.yBottom + 19.4];
    const depth = tx.depth ?? 0.7;
    const { cells } = cellLayout({ window: win, nx: tx.nx ?? 10, ny: tx.ny ?? 3, jitter: tx.jitter ?? 0.28, gap: tx.gap ?? 1.1, round: tx.round ?? 0.6, minArea: tx.minArea ?? 4, seed: tx.seed ?? 11, density: tx.density ?? 1 });
    const prisms = [];
    for (const cell of cells) { try { prisms.push(cellPrism(k, cell, 40)); } catch (e) { warn('a texture cell was skipped: ' + (e.message || e)); } }
    lap('cellPrisms');
    if (prisms.length) {
      const keepClear = tx.keepClearHalfWidth ?? 10.2;
      const [x0, x1, y0, y1] = win;
      const band = k.box(x0, x1, y0, y1, -40, 40).cut(k.box(x0, x1, y0, y1, -keepClear, keepClear));
      let rec = band.intersect(k.R.makeCompound(prisms)); // cells are separated by the gap, so the compound is a valid disjoint tool
      lap('texture:bandCells');
      const innerEnv = k.loftStations(insetStations(stationsFor(enc, ref, 'monolith'), depth, ref));
      rec = rec.cut(innerEnv); lap('texture:cutEnv');
      rec = rec.intersect(outer); lap('texture:clipOuter');
      shell = shell.cut(rec);
    }
    lap('texture');
  }

  // screw pilots (self-tapping holes in the shell's rear floor block)
  if ((enc.cap || {}).type !== 'none') {
    const f = capScrews(enc);
    shell = stage('screw pilots', () => k.cutMany(shell, f.positions.map(([y, z]) => k.cyl('x', f.pilotR, ref.xRear - f.pilotLen, ref.xRear, y, z))));
  }
  lap('pilots');
  const cap = stage('rear cap', () => buildCap(k, pcb, enc, ref, st, warn));
  return { shell, cap, outer, keepouts: keepoutShapes(k, pcb), xFront: ref.xFront, xRear: ref.xRear, stations: st, warnings, timings };
}
