// OCCT kernel operations over replicad (MIT) + Open CASCADE WASM (LGPL-2.1, loaded at runtime as a separate .wasm).
// Environment-agnostic: every function takes the replicad module `R` (and the raw OpenCascade instance `oc` where needed),
// so the same code runs in the browser Worker, in Node tests and in the headless parity runs.
//
//   const k = makeOps(R, oc);
//   k.box(x0,x1,y0,y1,z0,z1)  k.cyl(axis, r, a0, a1, [c1, c2])  k.polySection(...)  k.loftRuled(wires)  k.fuse/cut/common ...
//
// Coordinates: millimetres. Axis frame of the enclosure template: x = length (front at -x), y = up, z = width.

const AXV = { x: [1, 0, 0], y: [0, 1, 0], z: [0, 0, 1] };

export function makeOps(R, oc) {
  const dispose = (...s) => { for (const x of s) { try { x && x.delete && x.delete(); } catch (_) { /* already freed */ } } };

  // ── primitives
  const box = (x0, x1, y0, y1, z0, z1) => R.makeBox([Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1)], [Math.max(x0, x1), Math.max(y0, y1), Math.max(z0, z1)]);
  /** Cylinder along `axis` between a0 and a1 on that axis; centre at (c1, c2) on the other two axes (in x,y,z order). */
  const cyl = (axis, r, a0, a1, c1 = 0, c2 = 0) => {
    const lo = Math.min(a0, a1), len = Math.abs(a1 - a0);
    const o = axis === 'x' ? [lo, c1, c2] : axis === 'y' ? [c1, lo, c2] : [c1, c2, lo];
    return R.makeCylinder(r, len, o, AXV[axis]);
  };
  /** Cone frustum along +axis: radius r0 at a0, r1 at a1 (ruled loft of two circles). */
  const cone = (axis, r0, r1, a0, a1, c1 = 0, c2 = 0) => {
    const at = (a) => (axis === 'x' ? [a, c1, c2] : axis === 'y' ? [c1, a, c2] : [c1, c2, a]);
    return loft([R.assembleWire([R.makeCircle(r0, at(a0), AXV[axis])]), R.assembleWire([R.makeCircle(r1, at(a1), AXV[axis])])], true);
  };
  const sphere = (r) => R.makeSphere(r);

  // ── sections and lofts
  /**
   * Closed wire of a trapezoid with two filleted corner pairs, in the plane x = X (u = y, v = z):
   * bottom edge at y = yb with half-width hb and fillet rb, top edge at y = yt with half-width ht and fillet rt.
   */
  function polySection(X, hb, ht, yb, yt, rb, rt) {
    const P = [[yb, -hb], [yb, hb], [yt, ht], [yt, -ht]];
    const Rr = [rb, rb, rt, rt];
    const W = (u, v) => [X, u, v];
    const segs = [];
    const corner = (i) => {
      const p = P[i], a = P[(i + 3) % 4], b = P[(i + 1) % 4];
      let d1 = [a[0] - p[0], a[1] - p[1]], d2 = [b[0] - p[0], b[1] - p[1]];
      const l1 = Math.hypot(...d1), l2 = Math.hypot(...d2);
      d1 = d1.map((v) => v / l1); d2 = d2.map((v) => v / l2);
      const cosT = d1[0] * d2[0] + d1[1] * d2[1];
      const theta = Math.acos(Math.max(-1, Math.min(1, cosT)));
      let r = Rr[i];
      const tmax = Math.min(l1, l2) * 0.49;
      let t = r / Math.tan(theta / 2);
      if (t > tmax) { t = tmax; r = t * Math.tan(theta / 2); }
      if (r < 1e-6) return { t1: p, t2: p, mid: null };
      let bis = [d1[0] + d2[0], d1[1] + d2[1]]; const bl = Math.hypot(...bis); bis = bis.map((v) => v / bl);
      const cdist = r / Math.sin(theta / 2);
      const c = [p[0] + bis[0] * cdist, p[1] + bis[1] * cdist];
      const mid = [c[0] - bis[0] * r, c[1] - bis[1] * r];
      return { t1: [p[0] + d1[0] * t, p[1] + d1[1] * t], t2: [p[0] + d2[0] * t, p[1] + d2[1] * t], mid };
    };
    const C = [0, 1, 2, 3].map(corner);
    for (let i = 0; i < 4; i++) {
      const c = C[i], nx = C[(i + 1) % 4];
      if (c.mid) segs.push(R.makeThreePointArc(W(...c.t1), W(...c.mid), W(...c.t2)));
      segs.push(R.makeLine(W(...c.t2), W(...nx.t1)));
    }
    return R.assembleWire(segs);
  }
  const loft = (wires, ruled = true) => R.loft(wires, { ruled });
  /** Ruled loft through filleted-trapezoid stations [{x, hb, ht, yb, yt, rb, rt}]. Planar faces between stations. */
  const loftStations = (st) => loft(st.map((s) => polySection(s.x, s.hb, s.ht, s.yb, s.yt, s.rb, s.rt)), true);
  /** Prism: a closed polygon [[a,b],...] on the plane perpendicular to `axis`, from a0 to a1 along it. */
  function prism(axis, pts, a0, a1) {
    const to3 = (p, a) => (axis === 'x' ? [a, p[0], p[1]] : axis === 'y' ? [p[0], a, p[1]] : [p[0], p[1], a]);
    const ring = (a) => R.assembleWire(pts.map((p, i) => R.makeLine(to3(p, a), to3(pts[(i + 1) % pts.length], a))));
    return loft([ring(Math.min(a0, a1)), ring(Math.max(a0, a1))], true);
  }

  // ── booleans (all return a new shape; the inputs are left alone)
  const fuse = (a, b) => a.fuse(b);
  const cut = (a, b) => a.cut(b);
  const common = (a, b) => a.intersect(b);
  const fuseAll = (list) => list.reduce((acc, s) => (acc ? acc.fuse(s) : s), null);
  /** Subtract several tools one after another (a compound of overlapping tools is not a valid boolean argument and traps in OCCT). */
  const cutMany = (a, tools) => tools.reduce((acc, t) => acc.cut(t), a);

  // ── edge treatment, graceful: try fillet, fall back to chamfer, then to the untouched shape
  function edgeTreat(shape, r, filter, warn) {
    try { return shape.fillet(r, filter); } catch (e1) {
      try { const s = shape.chamfer(r, filter); warn && warn(`fillet ${r} mm failed, used a chamfer instead`); return s; } catch (e2) {
        warn && warn(`fillet/chamfer ${r} mm skipped: ${e2.message || e2}`); return shape;
      }
    }
  }
  /** Hollow a solid: open the faces picked by `pick(face) -> bool` and keep walls of `thickness` (inward). */
  function shell(solid, thickness, pick) {
    const faces = solid.faces.filter(pick);
    if (!faces.length) throw new Error('shell: no faces matched to open');
    return solid.shell({ filter: new R.FaceFinder().inList(faces), thickness });
  }
  /** Split a solid with an axis-aligned plane at `off`; returns { above, below } (null when empty). */
  function splitAxis(solid, axis, off) {
    const o = axis === 'x' ? [off, 0, 0] : axis === 'y' ? [0, off, 0] : [0, 0, off];
    const xd = axis === 'x' ? [0, 1, 0] : axis === 'y' ? [0, 0, 1] : [1, 0, 0];
    const plane = new R.Plane(o, xd, AXV[axis]);
    const r = solid.split(plane, 0);
    return { above: r.positive, below: r.negative };
  }

  // ── measurement
  const volume = (s) => R.measureVolume(s);
  const bounds = (s) => { const [mn, mx] = s.boundingBox.bounds; return { min: [...mn], max: [...mx] }; };
  const distance = (a, b) => R.measureDistanceBetween(a, b);
  const isValid = (s) => { try { const an = new oc.BRepCheck_Analyzer(s.wrapped, true, false); const ok = an.IsValid_1 ? an.IsValid_1() : an.IsValid(); an.delete(); return !!ok; } catch (_) { return null; } };
  const solidsOf = (s) => (s.solids ? s.solids : [s]);
  const stepBlob = (shapes) => {
    const list = (Array.isArray(shapes) ? shapes : [shapes]).map((s, i) => (s && s.shape ? s : { shape: s, name: 'Part ' + (i + 1) }));
    return R.exportSTEP(list.map((x) => ({ shape: x.shape, name: x.name, color: x.color, alpha: x.alpha })));
  };
  /** Tessellate to the flat arrays the three.js viewer uses: { pos: Float32Array, idx: Uint32Array, faces: [{start, count, id}] }. */
  function meshOf(s, tol = 0.03, ang = 0.2) {
    const m = s.mesh({ tolerance: tol, angularTolerance: ang });
    return { pos: new Float32Array(m.vertices), idx: new Uint32Array(m.triangles), normals: new Float32Array(m.normals), faces: m.faceGroups };
  }

  return { R, oc, dispose, box, cyl, cone, sphere, polySection, loft, loftStations, prism, fuse, cut, common, fuseAll, cutMany, edgeTreat, shell, splitAxis, volume, bounds, distance, isValid, solidsOf, stepBlob, meshOf };
}

/** Load the Open CASCADE wasm + replicad. `loader` is async () => ({ R, oc }); the browser worker and Node provide their own. */
export async function initKernel(loader) {
  const { R, oc } = await loader();
  R.setOC(oc);
  return makeOps(R, oc);
}
