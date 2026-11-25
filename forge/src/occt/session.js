// OCCT build session: turns a Forge document (v3) into B-rep shapes, meshes, STEP and check results. Environment-agnostic
// (Worker, Node tests, parity runs): construct it with the ops from makeOps(). It owns the shapes; callers hold build ids.
//
//   const S = createSession(k);
//   const r = S.build(project)        -> { id, bodies, refs, status, params, warnings, fatal? }   (meshes are plain arrays)
//   S.check(r.id, opts)               -> { passed, failed, checks, metrics, scorecard? }
//   S.stepBytes(r.id, names?)         -> Uint8Array  (true B-rep STEP)
//   S.dispose(r.id)
import { makeScope } from '../expr.js';
import { TYPES } from '../model.js';
import { generateEnclosure } from '../../templates/pcb-enclosure/generate.js';
import { enclosureScorecard, scorecardToChecks } from './checks.js';

const PALETTE = ['#a9b9c3', '#c9ad97', '#a9c2a0', '#c3a3ad', '#b2aacb', '#cfc89f', '#98c3bf'];
const DIR_ROT = { '+z': [0, 0, 0], '-z': [180, 0, 0], '+x': [0, 90, 0], '-x': [0, -90, 0], '+y': [-90, 0, 0], '-y': [90, 0, 0] };
const EPS = 0.02;
const r2 = (v) => Math.round(v * 100) / 100;
const b64ToBytes = (b64) => (typeof atob === 'function' ? Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)) : new Uint8Array(Buffer.from(b64, 'base64')));

export function createSession(k, { log = () => {} } = {}) {
  const { R } = k;
  const builds = new Map();
  const sources = new Map(); // asset id -> { sig, shape }
  let nextId = 1;

  async function loadSource(id, asset) {
    const sig = (asset.stepB64 || '').length + ':' + id;
    const c = sources.get(id);
    if (c && c.sig === sig) return c.shape;
    const bytes = b64ToBytes(asset.stepB64);
    const shape = await R.importSTEP(new Blob([bytes]));
    sources.set(id, { sig, shape });
    return shape;
  }

  /** Pick the solid of an imported compound that matches a part's bounding box (names do not survive the B-rep reader). */
  function pickPart(compound, part) {
    const sol = compound.solids || [compound];
    if (!part || !part.bbox) return compound;
    const c0 = [0, 1, 2].map((i) => (part.bbox.min[i] + part.bbox.max[i]) / 2), s0 = [0, 1, 2].map((i) => part.bbox.max[i] - part.bbox.min[i]);
    let best = null, bd = Infinity;
    for (const s of sol) {
      const b = k.bounds(s);
      const d = [0, 1, 2].reduce((a, i) => a + Math.abs((b.min[i] + b.max[i]) / 2 - c0[i]) + Math.abs(b.max[i] - b.min[i] - s0[i]), 0);
      if (d < bd) { bd = d; best = s; }
    }
    return bd < 0.5 ? best : null;
  }

  // ── primitive features (a practical subset of the Manifold feature set, on exact B-rep)
  function localSolid(f, num, vec) {
    const sphereOrCyl = (d, d2, h) => (d2 == null || Math.abs(d2 - d) < 1e-9 ? R.makeCylinder(d / 2, h, [0, 0, 0], [0, 0, 1]) : k.cone('z', d / 2, d2 / 2, 0, h));
    switch (f.type) {
      case 'box': {
        const [x, y, z] = vec(f.size); const r = num(f.radius || '0');
        let s = k.box(-x / 2, x / 2, -y / 2, y / 2, -z / 2, z / 2);
        if (r > 1e-3) s = f.edges === 'all' ? s.fillet(Math.min(r, Math.min(x, y, z) / 2 - 1e-3)) : s.fillet(Math.min(r, Math.min(x, y) / 2 - 1e-3), (e) => e.inDirection('Z'));
        if (f.anchor === 'bottom') return s.translate(0, 0, z / 2);
        if (f.anchor === 'min') return s.translate(x / 2, y / 2, z / 2);
        return s;
      }
      case 'cylinder': {
        const d = num(f.d), h = num(f.h), d2 = f.d2 === '' || f.d2 == null ? null : num(f.d2);
        const s = sphereOrCyl(d, d2, h);
        return f.anchor === 'center' ? s.translate(0, 0, -h / 2) : s;
      }
      case 'sphere': return k.sphere(num(f.d) / 2);
      case 'slot': {
        const L = num(f.length), W = num(f.width), h = num(f.h), r = W / 2, c = Math.max(L / 2 - r, 0);
        let s = k.box(-c, c, -r, r, 0, h).fuse(R.makeCylinder(r, h, [-c, 0, 0], [0, 0, 1])).fuse(R.makeCylinder(r, h, [c, 0, 0], [0, 0, 1]));
        if (f.anchor === 'center') s = s.translate(0, 0, -h / 2);
        return s;
      }
      case 'hole': {
        const d = num(f.d); let depth = num(f.depth); if (depth <= 0) depth = 500;
        let s = R.makeCylinder(d / 2, depth + EPS, [0, 0, -EPS], [0, 0, 1]);
        const cb = num(f.cboreD || '0'); if (cb > d) s = s.fuse(R.makeCylinder(cb / 2, num(f.cboreDepth || '0') + EPS, [0, 0, -EPS], [0, 0, 1]));
        const cs = num(f.cskD || '0'); if (cs > d) s = s.fuse(k.cone('z', cs / 2 + EPS, d / 2, -EPS, (cs - d) / 2));
        return s;
      }
      case 'standoff': {
        const d = num(f.d), h = num(f.h), hd = num(f.holeD || '0');
        let s = R.makeCylinder(d / 2, h, [0, 0, 0], [0, 0, 1]);
        if (hd > 0) { const dep = Math.min(num(f.holeDepth || '0') || h, h); s = s.cut(R.makeCylinder(hd / 2, dep + EPS, [0, 0, h - dep], [0, 0, 1])); }
        return s;
      }
      case 'shell_box': {
        const [X, Y, Z] = vec(f.size); const w = num(f.wall), fl = num(f.floor), r = num(f.radius);
        const rr = (x, y, h, q) => { let s = k.box(-x / 2, x / 2, -y / 2, y / 2, 0, h); q = Math.min(q, Math.min(x, y) / 2 - 1e-3); return q > 1e-3 ? s.fillet(q, (e) => e.inDirection('Z')) : s; };
        const open = f.top === 'open';
        const ih = Z - fl - (open ? 0 : w) + (open ? 1 : 0);
        if (ih <= 0 || X - 2 * w <= 0 || Y - 2 * w <= 0) throw new Error('wall/floor too thick for this size');
        return rr(X, Y, Z, r).cut(rr(X - 2 * w, Y - 2 * w, ih, Math.max(r - w, 0)).translate(0, 0, fl));
      }
    }
    throw new Error(`${f.type} is not available on the OCCT kernel yet (use the Manifold kernel for it)`);
  }
  const rotXYZ = (s, a) => { let q = s; if (a[0]) q = q.rotate(a[0], [0, 0, 0], [1, 0, 0]); if (a[1]) q = q.rotate(a[1], [0, 0, 0], [0, 1, 0]); if (a[2]) q = q.rotate(a[2], [0, 0, 0], [0, 0, 1]); return q; };
  function placed(f, num, vec) {
    let s = localSolid(f, num, vec);
    const T = TYPES[f.type];
    if (T.dirBased) s = rotXYZ(s, DIR_ROT[f.dir || '+z']);
    else if (f.rot) { const r = vec(f.rot); if (r.some((v) => v)) s = rotXYZ(s, r); }
    const p = vec(f.pos || ['0', '0', '0']);
    s = s.translate(p[0], p[1], p[2]);
    let set = [s];
    // replicad transforms consume their operand, so every reuse below copies first
    for (const ch of f.mirror || '') { const n = ch === 'x' ? [1, 0, 0] : ch === 'y' ? [0, 1, 0] : [0, 0, 1]; set = set.concat(set.map((q) => q.clone().mirror(n, [0, 0, 0]))); }
    if (f.pat === 'linear') {
      const n = Math.max(1, Math.round(num(f.patN))), d = vec(f.patD), out = [];
      for (const q of set) for (let i = 0; i < n; i++) out.push(i ? q.clone().translate(d[0] * i, d[1] * i, d[2] * i) : q);
      set = out;
    } else if (f.pat === 'polar') {
      const n = Math.max(1, Math.round(num(f.patN))), ang = num(f.patAngle), c = vec(f.patCenter), out = [];
      const step = Math.abs(ang) >= 360 ? ang / n : n > 1 ? ang / (n - 1) : 0;
      const axis = f.patAxis === 'x' ? [1, 0, 0] : f.patAxis === 'y' ? [0, 1, 0] : [0, 0, 1];
      for (const q of set) for (let i = 0; i < n; i++) out.push(i ? q.clone().rotate(step * i, c, axis) : q);
      set = out;
    }
    return set.length === 1 ? set[0] : set.reduce((a, q) => a.fuse(q));
  }

  function encParams(project, f, num) {
    const sp = f.spec || {};
    return {
      style: f.style, wall: num(f.wall), clearance: num(f.clearance),
      yBottom: sp.yBottom ?? 0, xFront: sp.xFront ?? null, xRear: sp.xRear ?? null,
      stations: sp.stations, print: sp.print, bedName: sp.bedName, referencePlasticMm3: sp.referencePlasticMm3,
      bed: project.settings?.printVolume || [180, 180, 180], fastener: f.fastener,
      cap: { ...(sp.cap || {}), type: f.cap === 'none' ? 'none' : 'plug-screws' },
      texture: { ...(sp.texture || {}), density: num(f.textureDensity || '1'), depth: num(f.textureDepth || '0.7'), gap: num(f.textureGap || '1.1'), seed: num(f.textureSeed || '11') },
    };
  }

  /** Build a document. Async only because STEP sources load through the B-rep reader. */
  async function build(project, { tol = 0.03, ang = 0.2 } = {}) {
    const id = nextId++;
    const sc = makeScope(project.params);
    const num = (x) => sc.ev(x), vec = (v) => v.map(num);
    const bodies = new Map(), refs = [], status = {}, warnings = [], extra = {};
    let fatal = null;
    const src = {};
    for (const [aid, a] of Object.entries(project.assets || {})) if (a && a.stepB64) {
      try { const comp = await loadSource(aid, a); src[aid] = comp; } catch (e) { warnings.push(`asset "${aid}" could not be read as B-rep: ${e.message || e}`); }
    }
    for (const f of project.features) {
      status[f.id] = { ok: true };
      if (f.suppressed) { status[f.id] = { ok: true, suppressed: true }; continue; }
      try {
        if (f.type === 'enclosure') {
          const pcb = project.pcb || {};
          const enc = encParams(project, f, num);
          if (!enc.stations) throw new Error('enclosure.spec.stations is missing');
          const sourcesById = {};
          for (const kp of pcb.keep || []) if (src[kp.source]) sourcesById[kp.source] = pickPart(src[kp.source], kp.part) || src[kp.source];
          const gen = generateEnclosure(k, pcb, enc, { sources: sourcesById, warn: (m) => warnings.push(`${f.name}: ${m}`) });
          bodies.set(f.body || 'Shell', gen.shell);
          if (gen.cap && f.capBody) bodies.set(f.capBody, gen.cap);
          for (const c of pcb.components || []) if (gen.keepouts[c.name]) refs.push({ id: f.id + ':' + c.name, name: c.name, shape: gen.keepouts[c.name] });
          extra.enclosure = { feature: f, enc, gen, pcb };
          status[f.id].timings = gen.timings;
          continue;
        }
        if (f.type === 'transform') {
          const names = f.body === '*' ? [...bodies.keys()] : [f.body];
          for (const nm of names) {
            const s = bodies.get(nm); if (!s) throw new Error(`no body "${nm}" to transform`);
            const c = vec(f.center || ['0', '0', '0']);
            if (f.mode === 'move') { const o = vec(f.offset); bodies.set(nm, s.translate(o[0], o[1], o[2])); }
            else if (f.mode === 'rotate') { const a = vec(f.angles); let q = s.translate(-c[0], -c[1], -c[2]); q = rotXYZ(q, a); bodies.set(nm, q.translate(c[0], c[1], c[2])); }
            else throw new Error(`transform "${f.mode}" needs the Manifold kernel (OCCT supports move and rotate)`);
          }
          continue;
        }
        if (f.type === 'split') {
          const s = bodies.get(f.body); if (!s) throw new Error(`no body "${f.body}" to split`);
          const { above, below } = k.splitAxis(s, f.axis, num(f.offset));
          const keepA = f.keep === 'above';
          bodies.set(f.body, keepA ? above : below);
          if (f.other) bodies.set(f.other, keepA ? below : above);
          continue;
        }
        const m = placed(f, num, vec);
        if (f.op === 'ref') { refs.push({ id: f.id, name: f.name, shape: m }); continue; }
        const targets = f.body === '*' ? [...bodies.keys()] : [f.body || 'Body'];
        for (const t of targets) {
          const cur = bodies.get(t);
          if (f.op === 'add') bodies.set(t, cur ? cur.fuse(m) : m);
          else if (!cur) { if (f.body !== '*') throw new Error(`body "${t}" is empty — add something to it before ${f.op}`); }
          else bodies.set(t, f.op === 'cut' ? cur.cut(m) : cur.intersect(m));
        }
      } catch (e) { status[f.id] = { ok: false, error: String(e.message || e) }; }
    }
    const out = { id, kernel: 'occt', bodies: [], refs: [], status, params: sc.values(), warnings, fatal };
    const rec = { project, status, bodies: new Map(), refs: new Map(), extra };
    let ci = 0;
    const encBodies = new Set(); if (extra.enclosure) { encBodies.add(extra.enclosure.feature.body || 'Shell'); if (extra.enclosure.feature.capBody) encBodies.add(extra.enclosure.feature.capBody); }
    const pack = (name, shape, ghost) => {
      const m = k.meshOf(shape, tol, ang), bb = k.bounds(shape);
      return {
        name, ghost, color: (!ghost && project.bodies?.[name]?.color) || PALETTE[ci++ % PALETTE.length],
        view: { pos: m.pos, idx: m.idx }, mesh: { pos: m.pos, idx: m.idx }, volume: ghost ? 0 : k.volume(shape), tris: m.idx.length / 3,
        bbox: bb, valid: ghost ? true : k.isValid(shape), solids: ghost ? 1 : shape.solids.length, noPlate: encBodies.has(name), // enclosure bodies are in the template frame; the scorecard checks the bed with the print orientation
      };
    };
    for (const [name, s] of bodies) { if (!s.solids.length) continue; out.bodies.push(pack(name, s, false)); rec.bodies.set(name, s); }
    for (const r of refs) { out.refs.push(pack(r.name, r.shape, true)); rec.refs.set(r.name, r.shape); }
    builds.set(id, rec);
    return out;
  }

  function check(id, opts = {}) {
    const rec = builds.get(id); if (!rec) throw new Error('build was disposed');
    const P = rec.project, checks = [], metrics = {};
    const add = (c) => checks.push({ severity: 'hard', ...c });
    const nameOf = (fid) => P.features.find((x) => x.id === fid)?.name || fid;
    const failing = Object.entries(rec.status || {}).filter(([, v]) => v.ok === false).map(([fid, v]) => ({ feature: nameOf(fid), id: fid, error: v.error }));
    add({ id: 'features', label: 'No failing features', pass: failing.length === 0, detail: failing.length ? failing.map((f) => `"${f.feature}": ${f.error}`).join('; ') : `${P.features.length} features built`, items: failing });
    add({ id: 'bodies', label: 'Produces a solid body', pass: rec.bodies.size > 0, detail: rec.bodies.size ? [...rec.bodies.keys()].join(', ') : 'the build produced no solid bodies' });
    const enc = rec.extra.enclosure;
    let scorecard = null;
    if (enc && failing.length === 0) {
      scorecard = enclosureScorecard(k, enc.gen, enc.pcb, enc.enc, { variant: enc.enc.style, bed: P.settings?.printVolume, minWallSamples: opts.minWallSamples || 0 });
      const t = scorecardToChecks(scorecard);
      for (const c of t.checks) add(c);
      Object.assign(metrics, t.metrics);
    } else if (!enc) {
      // generic B-rep checks: body vs reference interference, and the print volume
      const hits = [];
      for (const [bn, bs] of rec.bodies) for (const [rn, rs] of rec.refs) {
        try { const x = bs.intersect(rs); const v = x.solids.length ? k.volume(x) : 0; if (v > (opts.interferenceTol ?? 0.05)) hits.push({ a: bn, b: rn, volume_mm3: r2(v) }); } catch (_) { /* disjoint */ }
      }
      add({ id: 'interference', label: 'No interference with reference parts', pass: hits.length === 0, detail: hits.length ? hits.map((h) => `${h.a} overlaps ${h.b} by ${h.volume_mm3} mm³`).join('; ') : `${rec.refs.size} reference parts clear`, items: hits });
      const pv = P.settings?.printVolume || [180, 180, 180];
      const off = [];
      for (const [bn, bs] of rec.bodies) { const b = k.bounds(bs); const ok = b.min[0] >= -pv[0] / 2 - 1e-3 && b.max[0] <= pv[0] / 2 + 1e-3 && b.min[1] >= -pv[1] / 2 - 1e-3 && b.max[1] <= pv[1] / 2 + 1e-3 && b.min[2] >= -1e-3 && b.max[2] <= pv[2] + 1e-3; if (!ok) off.push({ body: bn, min: b.min.map(r2), max: b.max.map(r2) }); }
      add({ id: 'print_volume', label: `Fits the ${pv.join('×')} mm print volume`, pass: off.length === 0, detail: off.length ? off.map((x) => `${x.body} is outside`).join('; ') : 'every body fits', items: off });
      metrics.kernel = 'occt';
    }
    const failed = checks.filter((c) => !c.pass).map((c) => c.id);
    return { passed: failed.length === 0, checks, failed, metrics, scorecard };
  }

  function stepBytes(id, names) {
    const rec = builds.get(id); if (!rec) throw new Error('build was disposed');
    const list = [...rec.bodies].filter(([n]) => !names || names.includes(n)).map(([name, shape]) => ({ shape, name }));
    if (!list.length) throw new Error('nothing to export');
    return k.stepBlob(list).arrayBuffer().then((b) => new Uint8Array(b));
  }
  function dispose(id) {
    const rec = builds.get(id); if (!rec) return;
    for (const s of rec.bodies.values()) k.dispose(s);
    for (const s of rec.refs.values()) k.dispose(s);
    builds.delete(id);
  }
  /** Check helper for the loop: fills in the build status (the loop passes it because status lives in the build result). */
  return { build, check, stepBytes, dispose, builds, k };
}
