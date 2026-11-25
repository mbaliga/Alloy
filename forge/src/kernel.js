// Geometry kernel: evaluates the feature tree into Manifold solids. Environment-agnostic (pass the manifold wasm handle).
import { makeScope } from './expr.js';
import { TYPES, fieldsFor } from './model.js';

const DIR_ROT = { '+z': [0, 0, 0], '-z': [180, 0, 0], '+x': [0, 90, 0], '-x': [0, -90, 0], '+y': [-90, 0, 0], '-y': [90, 0, 0] };
const EPS = 0.02;
const PALETTE = ['#a9b9c3', '#c9ad97', '#a9c2a0', '#c3a3ad', '#b2aacb', '#cfc89f', '#98c3bf'];

const assetCache = new Map(); // asset id -> {sig, manifold}

function b64ToBytes(b64) {
  if (typeof atob === 'function') { const s = atob(b64); const u = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }
  return new Uint8Array(Buffer.from(b64, 'base64'));
}
export function bytesToB64(u8) {
  if (typeof btoa === 'function') { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  return Buffer.from(u8).toString('base64');
}

export function assetManifold(wasm, id, asset) {
  const sig = asset.pos.length + ':' + asset.idx.length;
  const c = assetCache.get(id);
  if (c && c.sig === sig) return c.manifold;
  const pb = b64ToBytes(asset.pos), ib = b64ToBytes(asset.idx);
  const vertProperties = new Float32Array(pb.buffer, pb.byteOffset, pb.byteLength >> 2);
  const triVerts = new Uint32Array(ib.buffer, ib.byteOffset, ib.byteLength >> 2);
  const mesh = new wasm.Mesh({ numProp: 3, vertProperties: new Float32Array(vertProperties), triVerts: new Uint32Array(triVerts) });
  mesh.merge();
  const m = new wasm.Manifold(mesh);
  if (m.status() !== 'NoError') throw new Error('imported mesh is not a watertight solid (' + m.status() + ')');
  if (c) c.manifold.delete();
  assetCache.set(id, { sig, manifold: m });
  return m;
}

export function build(project, wasm) {
  const { Manifold, CrossSection } = wasm;
  const sc = makeScope(project.params);
  const seg = Math.max(8, project.settings?.detail || 48);
  const alloc = [];
  const A = (m) => { alloc.push(m); return m; };
  const bodies = new Map();
  const refs = [];
  const status = {};
  const num = (x) => sc.ev(x);
  const vec = (v) => v.map(num);

  function roundedPrism(x, y, h, r) {
    r = Math.max(0, Math.min(r, Math.min(x, y) / 2 - 1e-3));
    if (r < 1e-3) return A(Manifold.cube([x, y, h]).translate([-x / 2, -y / 2, 0]));
    const cs = CrossSection.square([x - 2 * r, y - 2 * r], true).offset(r, 'Round', 2, seg);
    const m = A(cs.extrude(h));
    return m;
  }

  function solidFor(f) {
    switch (f.type) {
      case 'box': {
        const [x, y, z] = vec(f.size);
        const r = num(f.radius || '0');
        let m;
        if (r > 1e-3 && f.edges === 'all') {
          const rr = Math.min(r, Math.min(x, y, z) / 2 - 1e-3);
          const s = A(Manifold.sphere(rr, seg));
          const pts = [];
          for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1])
            pts.push(A(s.translate([sx * (x / 2 - rr), sy * (y / 2 - rr), sz * (z / 2 - rr)])));
          m = A(Manifold.hull(pts)); // centred
        } else {
          m = roundedPrism(x, y, z, r); // base at z=0, centred XY
          m = A(m.translate([0, 0, -z / 2])); // centred
        }
        if (f.anchor === 'bottom') m = A(m.translate([0, 0, z / 2]));
        else if (f.anchor === 'min') m = A(m.translate([x / 2, y / 2, z / 2]));
        return m;
      }
      case 'cylinder': {
        const d = num(f.d), h = num(f.h);
        const d2 = f.d2 === '' || f.d2 == null ? d : num(f.d2);
        let m = A(Manifold.cylinder(h, d / 2, d2 / 2, seg));
        if (f.anchor === 'center') m = A(m.translate([0, 0, -h / 2]));
        return m;
      }
      case 'sphere': return A(Manifold.sphere(num(f.d) / 2, seg));
      case 'slot': {
        const L = num(f.length), W = num(f.width), h = num(f.h);
        const r = W / 2, c = Math.max(L / 2 - r, 0);
        const cs = CrossSection.hull([CrossSection.circle(r, seg).translate([-c, 0]), CrossSection.circle(r, seg).translate([c, 0])]);
        let m = A(cs.extrude(h));
        if (f.anchor === 'center') m = A(m.translate([0, 0, -h / 2]));
        return m;
      }
      case 'hole': {
        const d = num(f.d); let depth = num(f.depth); if (depth <= 0) depth = 500;
        const parts = [A(Manifold.cylinder(depth + EPS, d / 2, d / 2, seg).translate([0, 0, -EPS]))];
        const cb = num(f.cboreD || '0');
        if (cb > d) parts.push(A(Manifold.cylinder(num(f.cboreDepth || '0') + EPS, cb / 2, cb / 2, seg).translate([0, 0, -EPS])));
        const cs = num(f.cskD || '0');
        if (cs > d) { const h = (cs - d) / 2; parts.push(A(Manifold.cylinder(h + EPS, cs / 2 + EPS, d / 2, seg).translate([0, 0, -EPS]))); }
        return A(Manifold.union(parts));
      }
      case 'standoff': {
        const d = num(f.d), h = num(f.h), hd = num(f.holeD || '0');
        let m = A(Manifold.cylinder(h, d / 2, d / 2, seg));
        if (hd > 0) {
          const dep = Math.min(num(f.holeDepth || '0') || h, h);
          m = A(m.subtract(A(Manifold.cylinder(dep + EPS, hd / 2, hd / 2, seg).translate([0, 0, h - dep]))));
        }
        return m;
      }
      case 'shell_box': {
        const [X, Y, Z] = vec(f.size);
        const w = num(f.wall), fl = num(f.floor), r = num(f.radius);
        const outer = roundedPrism(X, Y, Z, r);
        const open = f.top === 'open';
        const ih = Z - fl - (open ? 0 : w) + (open ? 1 : 0);
        if (ih <= 0 || X - 2 * w <= 0 || Y - 2 * w <= 0) throw new Error('wall/floor too thick for this size');
        const inner = A(roundedPrism(X - 2 * w, Y - 2 * w, ih, Math.max(r - w, 0)).translate([0, 0, fl]));
        return A(outer.subtract(inner));
      }
      case 'import': {
        const a = project.assets?.[f.asset];
        if (!a) throw new Error(`asset "${f.asset}" missing (re-import the file)`);
        const s = num(f.scale || '1');
        return A(assetManifold(wasm, f.asset, a).scale([s, s, s]));
      }
    }
    throw new Error('cannot build ' + f.type);
  }


  const AX = { x: 0, y: 1, z: 2 };
  function transformBody(src, f) {
    const c = vec(f.center || ['0', '0', '0']);
    const neg = c.map((v) => -v);
    switch (f.mode) {
      case 'move': return A(src.translate(vec(f.offset)));
      case 'rotate': return A(A(A(src.translate(neg)).rotate(vec(f.angles))).translate(c));
      case 'scale': {
        const s = vec(f.factor);
        if (s.some((v) => Math.abs(v) < 1e-6)) throw new Error('scale factor cannot be 0');
        return A(A(A(src.translate(neg)).scale(s)).translate(c));
      }
      case 'shear': {
        const a = AX[f.axis], b = AX[f.byAxis], k = num(f.amount);
        if (a === b) throw new Error('shear axis and byAxis must differ');
        return A(src.warp((v) => { v[a] += k * (v[b] - c[b]); }));
      }
    }
    const a = AX[f.axis];
    const r = Math.max(0.2, num(f.refine || '1.5'));
    const bb = src.boundingBox();
    const lo = bb.min[a], hi = bb.max[a], L = hi - lo;
    if (L <= 1e-6) throw new Error('body has no extent along ' + f.axis);
    const est = src.numTri() * Math.pow(Math.max(...[0, 1, 2].map((i) => bb.max[i] - bb.min[i])) / r, 1.2);
    if (est > 4e6) throw new Error('refine is too fine for this body — raise it');
    const m = A(src.refineToLength(r));
    const [p, q] = [0, 1, 2].filter((i) => i !== a);
    const amt = num(f.amount);
    if (f.mode === 'twist') {
      return A(m.warp((v) => {
        const t = (v[a] - lo) / L, ang = (amt * t * Math.PI) / 180, cs = Math.cos(ang), sn = Math.sin(ang);
        const x = v[p] - c[p], y = v[q] - c[q];
        v[p] = c[p] + x * cs - y * sn; v[q] = c[q] + x * sn + y * cs;
      }));
    }
    if (f.mode === 'taper') {
      if (amt <= 0) throw new Error('taper amount must be > 0');
      return A(m.warp((v) => { const s = 1 + (amt - 1) * ((v[a] - lo) / L); v[p] = c[p] + (v[p] - c[p]) * s; v[q] = c[q] + (v[q] - c[q]) * s; }));
    }
    if (f.mode === 'bend') {
      const b = AX[f.byAxis];
      if (a === b) throw new Error('bend axis and byAxis must differ');
      const th0 = (amt * Math.PI) / 180;
      if (Math.abs(th0) < 1e-6) return m;
      const R = L / th0;
      return A(m.warp((v) => {
        const s = v[a] - lo, u = v[b] - c[b], th = s / R;
        v[a] = lo + (R - u) * Math.sin(th);
        v[b] = c[b] + R - (R - u) * Math.cos(th);
      }));
    }
    throw new Error('unknown transform mode ' + f.mode);
  }

  function place(m, f) {
    const T = TYPES[f.type];
    if (T.dirBased) m = A(m.rotate(DIR_ROT[f.dir || '+z']));
    else if (f.rot) { const r = vec(f.rot); if (r.some((v) => v)) m = A(m.rotate(r)); }
    return A(m.translate(vec(f.pos || ['0', '0', '0'])));
  }

  function copies(m, f) {
    let set = [m];
    const mir = { x: [1, 0, 0], y: [0, 1, 0], z: [0, 0, 1] };
    if (f.mirror) {
      for (const ch of f.mirror) set = set.concat(set.map((s) => A(s.mirror(mir[ch]))));
    }
    if (f.pat === 'linear') {
      const n = Math.max(1, Math.round(num(f.patN))), d = vec(f.patD);
      const out = [];
      for (const s of set) for (let i = 0; i < n; i++) out.push(i ? A(s.translate(d.map((v) => v * i))) : s);
      set = out;
    } else if (f.pat === 'polar') {
      const n = Math.max(1, Math.round(num(f.patN))), ang = num(f.patAngle), c = vec(f.patCenter);
      const step = Math.abs(ang) >= 360 ? ang / n : n > 1 ? ang / (n - 1) : 0;
      const out = [];
      for (const s of set) for (let i = 0; i < n; i++) {
        if (!i) { out.push(s); continue; }
        const a = step * i, rv = f.patAxis === 'x' ? [a, 0, 0] : f.patAxis === 'y' ? [0, a, 0] : [0, 0, a];
        out.push(A(A(A(s.translate(c.map((v) => -v))).rotate(rv)).translate(c)));
      }
      set = out;
    }
    return set.length === 1 ? set[0] : A(Manifold.union(set));
  }

  for (const f of project.features) {
    status[f.id] = { ok: true };
    if (f.suppressed) { status[f.id] = { ok: true, suppressed: true }; continue; }
    try {
      if (f.type === 'transform') {
        const names = f.body === '*' ? [...bodies.keys()] : [f.body];
        for (const nm of names) {
          const src = bodies.get(nm);
          if (!src) throw new Error(`no body "${nm}" to transform`);
          bodies.set(nm, transformBody(src, f));
        }
        continue;
      }
      if (f.type === 'split') {
        const src = bodies.get(f.body);
        if (!src) throw new Error(`no body "${f.body}" to split`);
        const normal = f.axis === 'x' ? [1, 0, 0] : f.axis === 'y' ? [0, 1, 0] : [0, 0, 1];
        const [above, below] = src.splitByPlane(normal, num(f.offset));
        alloc.push(above, below);
        const keepA = f.keep === 'above';
        bodies.set(f.body, keepA ? above : below);
        if (f.other) bodies.set(f.other, keepA ? below : above);
        continue;
      }
      const m = copies(place(solidFor(f), f), f);
      if (f.op === 'ref') { refs.push({ id: f.id, name: f.name, manifold: m }); continue; }
      const targets = f.body === '*' ? [...bodies.keys()] : [f.body || 'Body'];
      for (const t of targets) {
        const cur = bodies.get(t);
        if (f.op === 'add') bodies.set(t, cur ? A(cur.add(m)) : m);
        else if (!cur) { if (f.body !== '*') throw new Error(`body "${t}" is empty — add something to it before ${f.op}`); }
        else bodies.set(t, A(f.op === 'cut' ? cur.subtract(m) : cur.intersect(m)));
      }
    } catch (e) {
      status[f.id] = { ok: false, error: String(e.message || e) };
    }
  }

  // Package results; free every intermediate that is not a final body or ref.
  const keep = new Set();
  const result = { bodies: [], refs: [], status, params: sc.values() };
  let ci = 0;
  const pack = (name, m, ghost) => {
    keep.add(m);
    const raw = m.getMesh();
    const pos = new Float32Array(raw.vertProperties.length / raw.numProp * 3);
    for (let i = 0, n = pos.length / 3; i < n; i++) for (let k = 0; k < 3; k++) pos[i * 3 + k] = raw.vertProperties[i * raw.numProp + k];
    const bb = m.boundingBox();
    return {
      name, ghost, manifold: m, color: (!ghost && project.bodies?.[name]?.color) || PALETTE[ci++ % PALETTE.length],
      view: { pos, idx: new Uint32Array(raw.triVerts) },
      mesh: { pos, idx: new Uint32Array(raw.triVerts) },
      volume: ghost ? 0 : m.volume(), tris: raw.triVerts.length / 3,
      bbox: { min: [...bb.min], max: [...bb.max] },
    };
  };
  for (const [name, m] of bodies) if (!m.isEmpty()) result.bodies.push(pack(name, m, false));
    else keep.add(m);
  for (const r of refs) result.refs.push(pack(r.name, r.manifold, true));
  const cached = new Set([...assetCache.values()].map((c) => c.manifold));
  for (const m of alloc) if (!keep.has(m) && !cached.has(m)) { try { m.delete(); } catch (_) {} }
  result.dispose = () => { for (const m of keep) { try { m.delete(); } catch (_) {} } };
  return result;
}
