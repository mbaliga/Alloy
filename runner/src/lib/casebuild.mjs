// From a validated job to a meshed case: every body is meshed on its own with gmsh, the boundary faces are found, and the mounts and
// loads are attached to boundary groups. Adapters (Elmer, the in-process fake) take it from there.
// Bodies are meshed SEPARATELY and are not bonded where they touch (no shared nodes, no contact model): each body needs its own fixed
// mount (structural) and exchanges no heat with a neighbour (thermal). The result says so.
import path from 'node:path';
import { parseStl, meshStats } from './stl.js';
import { meshStl } from './gmsh.mjs';
import { boundaryFaces, selectFaces, bboxOf, FACE_N } from './tetmesh.mjs';
import { pressureTraction } from './sif.mjs';

const G = 9.80665;

/** Pick a mesh size (mm) so the case lands near `maxTets`/4 tetrahedra at most, from the bounding boxes and volumes (a regular tet of edge h has volume 0.1179 h^3). */
export function autoMeshSize(bodies, maxTets) {
  let vol = 0, diag = 0, thin = Infinity;
  for (const b of bodies) { vol += b.stats.volume; const d = [0, 1, 2].map((k) => b.stats.max[k] - b.stats.min[k]); diag = Math.max(diag, Math.hypot(...d)); thin = Math.min(thin, ...d); }
  let h = Math.min(diag / 30, thin / 2);
  const tetsAt = (hh) => vol / (0.1179 * hh ** 3);
  while (tetsAt(h) > maxTets / 4 && h < diag) h *= 1.1;
  return +h.toPrecision(3);
}

export function surfaceBodies(job) {
  return job.bodies.map((b) => {
    if (b.format !== 'stl') return { ...b, stl: null, stats: null };
    const m = parseStl(b.bytes); return { ...b, stl: m, stats: meshStats(m.positions, m.indices) };
  });
}

/** Mesh every body at size h and attach boundary groups. `ctx`: { dir, signal, log, progress(lo, hi) -> (frac) => void, config }. */
export async function buildCase(job, h, ctx) {
  const warnings = [], bodies = [], kind = job.kind, cond = job.conditions || {};
  const nodesAll = []; let nOff = 0, tetCount = 0;
  const total = job.bodies.length;
  for (let bi = 0; bi < total; bi++) {
    const jb = job.bodies[bi];
    ctx.log(`meshing "${jb.name}" with gmsh at ${h} mm`);
    const m = await meshStl({ stlBytes: jb.bytes, h, dir: path.join(ctx.dir, `mesh-${bi}`), bin: ctx.config.gmshBin, signal: ctx.signal, expectVolume: jb.stats ? jb.stats.volume : undefined, onLine: (l) => { if (/Error/.test(l)) ctx.log('gmsh: ' + l.slice(0, 200)); } });
    if (m.strategy !== 'remeshed') warnings.push(`Body "${jb.name}": the surface could not be re-meshed at ${h} mm (the STL has needle triangles, typical after boolean operations), so the tetrahedra follow the STL's own triangles; the element size near the surface is not controlled. First attempt: ${m.failures[0].slice(0, 200)}`);
    tetCount += m.tets.length / 4;
    if (tetCount > ctx.config.maxTets) throw Object.assign(new Error(`the mesh would have more than ${ctx.config.maxTets.toLocaleString('en')} tetrahedra; give a larger mesh_size_mm`), { code: 'TOO_LARGE' });
    const bf = boundaryFaces(m.nodes, m.tets), bbox = bboxOf(m.nodes, m.tets);
    const tets = Int32Array.from(m.tets, (q) => q + nOff);
    nodesAll.push(m.nodes); nOff += m.nodes.length / 3;
    bodies.push({ index: bi, name: jb.name, material: jb.material, tets, bf: { ...bf, tri: Int32Array.from(bf.tri, (q) => q + nOff - m.nodes.length / 3) }, bbox, volume: m.volume, stlVolume: jb.stats ? jb.stats.volume : null, nodeRange: [nOff - m.nodes.length / 3, nOff] });
    ctx.progress(0.05 + 0.25 * (bi + 1) / total);
  }
  const nodes = new Float64Array(nOff * 3); { let o = 0; for (const a of nodesAll) { nodes.set(a, o); o += a.length; } }
  for (const b of bodies) if (b.stlVolume > 0 && Math.abs(b.volume - b.stlVolume) / b.stlVolume > 0.03) warnings.push(`Body "${b.name}": the tetrahedral mesh volume (${b.volume.toFixed(1)} mm3) differs from the STL volume (${b.stlVolume.toFixed(1)} mm3) by ${(100 * Math.abs(b.volume - b.stlVolume) / b.stlVolume).toFixed(1)} %; the surface was re-meshed and curved features lose some volume at this mesh size.`);

  // boundary groups. Face tolerance: a fraction of the mesh size, so a face on the bounding-box plane is found even with a re-meshed surface.
  const tol = Math.max(1e-6, 0.05 * h), bodyByName = new Map(bodies.map((b) => [b.name, b]));
  const bcs = [], groups = bodies.map(() => []), loads = [], taken = bodies.map((b) => new Uint8Array(b.bf.area.length));
  let nextBc = 1;
  const claim = (item, label) => {
    const b = bodyByName.get(item.body); if (!b) { warnings.push(`${label} "${item.id}" names the body "${item.body}", which is not in the job; it is ignored.`); return null; }
    const sel = selectFaces(b.bf, b.bbox, item.face, item.box || null, tol).filter((i) => !taken[b.index][i]);
    if (!sel.length) { warnings.push(`${label} "${item.id}" (${item.kind}, ${item.body} ${item.face}${item.box ? ' in the given box' : ''}) matches no free boundary faces at this mesh size; it is ignored.`); return null; }
    let area = 0; for (const i of sel) { taken[b.index][i] = 1; area += b.bf.area[i]; }
    const id = nextBc++;
    groups[b.index].push({ bc: id, tri: Int32Array.from(sel.flatMap((i) => [b.bf.tri[3 * i], b.bf.tri[3 * i + 1], b.bf.tri[3 * i + 2]])), parent: Int32Array.from(sel, (i) => b.bf.parent[i]) });
    const nodeSet = new Set(); for (const i of sel) for (let k = 0; k < 3; k++) nodeSet.add(b.bf.tri[3 * i + k]);
    return { id, body: b, area_mm2: area, faces: sel.length, nodes: Int32Array.from(nodeSet) };
  };
  const mounts = cond.mounts || [], lds = cond.loads || [];
  if (kind === 'structural') {
    for (const m of mounts) {
      if (m.kind === 'contact') { warnings.push(`Mount "${m.id}" is a thermal contact mount; it has no structural effect.`); continue; }
      const g = claim(m, 'Mount'); if (!g) continue;
      const ax = ['x', 'y', 'z'].indexOf(m.face[1]);
      bcs.push({ id: g.id, name: `${m.kind} ${m.id}`, fixed: m.kind === 'fixed' ? [true, true, true] : [0, 1, 2].map((c) => c === ax), traction: null });
    }
    for (const l of lds) {
      if (l.kind === 'acceleration') continue;
      const g = claim(l, 'Load'); if (!g) continue;
      const A = g.area_mm2 * 1e-6;
      let traction, vector;
      if (l.kind === 'force') { traction = l.vector_N.map((f) => f / A); vector = l.vector_N.slice(); }
      else { traction = pressureTraction(l.pressure_Pa, l.face); vector = traction.map((t) => t * A); }
      bcs.push({ id: g.id, name: `${l.kind} ${l.id}`, fixed: [false, false, false], traction });
      loads.push({ id: l.id, kind: l.kind, vector_N: vector, nodes: g.nodes, area_mm2: g.area_mm2 });
    }
    for (const b of bodies) {
      const held = bcs.some((c) => c.fixed.some(Boolean) && groups[b.index].some((g) => g.bc === c.id));
      const fixedFull = bcs.some((c) => c.fixed.every(Boolean) && groups[b.index].some((g) => g.bc === c.id));
      if (!held) throw Object.assign(new Error(`Body "${b.name}" has no fixed or slider mount on it: nothing holds it (bodies are meshed separately and are not bonded, so every body needs its own fixed mount)`), { code: 'UNSUPPORTED' });
      if (!fixedFull && bcs.filter((c) => groups[b.index].some((g) => g.bc === c.id) && c.fixed.some(Boolean)).length < 2) warnings.push(`Body "${b.name}" is held only by sliders on one plane: sliding or rotation may be free; add a fixed mount if the solve does not converge.`);
    }
    const bodyAccel = (b) => {
      const a = [0, 0, 0];
      for (const l of lds) if (l.kind === 'acceleration') { const n = FACE_N[l.direction]; for (let c = 0; c < 3; c++) a[c] += n[c] * l.accel_ms2; }
      if (cond.self_weight) { const n = FACE_N[cond.gravity || '-z']; for (let c = 0; c < 3; c++) a[c] += n[c] * G; }
      return a.some((v) => v !== 0) ? a : null;
    };
    for (const b of bodies) b.accel = bodyAccel(b);
    if (bodies.some((b) => b.accel) && bodies.some((b) => !(b.material.rho > 0))) warnings.push('An acceleration needs a density; a body without one carries no body force.');
    if (!loads.length && !bodies.some((b) => b.accel)) warnings.push('There are no loads (force, pressure, acceleration or self weight); the displacement and stress are zero.');
  } else {
    // thermal: contact mounts are Robin conditions to a sink at the ambient temperature; everything else exchanges heat with the air through h_conv
    const Tamb = cond.ambient_K, hConv = cond.h_conv_W_m2K;
    for (const m of mounts) {
      if (m.kind !== 'contact') { warnings.push(`Mount "${m.id}" is a ${m.kind} (structural) mount; it has no thermal effect.`); continue; }
      const g = claim(m, 'Mount'); if (!g) continue;
      bcs.push({ id: g.id, name: `contact ${m.id}`, h: m.conductance_W_m2K, T_ext: Tamb, area_mm2: g.area_mm2 });
    }
    for (const b of bodies) {
      const rest = []; for (let i = 0; i < b.bf.area.length; i++) if (!taken[b.index][i]) rest.push(i);
      if (rest.length) {
        let area = 0; for (const i of rest) area += b.bf.area[i];
        const id = nextBc++;
        groups[b.index].push({ bc: id, tri: Int32Array.from(rest.flatMap((i) => [b.bf.tri[3 * i], b.bf.tri[3 * i + 1], b.bf.tri[3 * i + 2]])), parent: Int32Array.from(rest, (i) => b.bf.parent[i]) });
        bcs.push({ id, name: `air ${b.name}`, h: hConv, T_ext: Tamb, area_mm2: area });
      }
    }
    const heat = cond.heat || [];
    for (const b of bodies) {
      const P = heat.filter((x) => x.body === b.name).reduce((a, x) => a + x.power_W, 0), mass = (b.material.rho || 0) * b.volume * 1e-9;
      b.power_W = P; b.source_W_per_kg = P > 0 && mass > 0 ? P / mass : 0;
      if (P > 0 && !(mass > 0)) throw Object.assign(new Error(`Body "${b.name}" has heat but no density to turn it into a source`), { code: 'UNSUPPORTED' });
    }
    for (const x of heat) if (!bodyByName.has(x.body)) warnings.push(`Heat source "${x.id}" names the body "${x.body}", which is not in the job; it is ignored.`);
    if (!heat.some((x) => bodyByName.has(x.body))) warnings.push('There is no heat source on any body; the temperature is the ambient everywhere.');
    if (bodies.length > 1) warnings.push('Bodies are meshed separately and exchange no heat where they touch.');
  }
  if (bodies.length > 1 && kind === 'structural') warnings.push('Bodies are meshed separately and are not bonded where they touch (no shared nodes, no contact).');
  return { kind, h, nodes, bodies, bcs, groups, loads, warnings, tets: tetCount, nodeCount: nOff, ambient_K: cond.ambient_K };
}
