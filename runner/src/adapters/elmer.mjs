// Elmer (thermal and structural), gmsh meshing, both run as separate processes (GPL; never linked). Pipeline per solve:
//   gmsh (STL -> tetrahedra) -> Elmer mesh files + .sif -> ElmerSolver -> VTU (SI) -> converted to mm / MPa / degrees C -> summary.
// WRITTEN WITHOUT AN ElmerSolver TO TEST AGAINST: the meshing, boundary selection, mesh and .sif files are unit tested as text, the
// gmsh step against the real gmsh, and the post-processing against synthetic fields. The solver call itself has never been run by the
// author (see docs/EXTENSIONS_SPEC.md, A4 done). Treat the first real run as a commissioning step and check it against an analytic case.
import fs from 'node:fs';
import path from 'node:path';
import { buildCase } from '../lib/casebuild.mjs';
import { gmshVersion, run } from '../lib/gmsh.mjs';
import { writeElmerMesh } from '../lib/tetmesh.mjs';
import { structuralSif, thermalSif } from '../lib/sif.mjs';
import { parseVtu } from '../lib/vtu.js';
import { postStructural, postThermal, resultVtu, vonMises6 } from '../lib/post.mjs';
import { findOnPath, withStudy } from './common.mjs';

/** Find a point field of a parsed VTU by a list of lower-case candidate names. */
export function pickField(vtu, names, ncomp) {
  const have = Object.keys(vtu.pointData);
  for (const n of names) { const k = have.find((x) => x.toLowerCase() === n); if (k && vtu.pointData[k].ncomp === ncomp) return vtu.pointData[k].data; }
  return null;
}
/** Read Elmer's VTU of a structural solve (SI) as mm and MPa. */
export function readStructural(vtu, nodeCount) {
  if (vtu.points.length !== 3 * nodeCount) throw new Error(`Elmer's result has ${vtu.points.length / 3} nodes; the mesh has ${nodeCount} (did Elmer renumber or partition the mesh?)`);
  let u = pickField(vtu, ['displacement'], 3);
  if (!u) { const c = [1, 2, 3].map((i) => pickField(vtu, [`displacement ${i}`, `displacement_${i}`, `displacement${i}`], 1)); if (c.every(Boolean)) { u = new Float64Array(3 * nodeCount); for (let q = 0; q < nodeCount; q++) for (let k = 0; k < 3; k++) u[3 * q + k] = c[k][q]; } }
  if (!u) throw new Error(`Elmer's result has no displacement field (fields: ${Object.keys(vtu.pointData).join(', ') || 'none'})`);
  let vm = pickField(vtu, ['vonmises', 'von mises', 'von_mises', 'von mises stress'], 1);
  if (!vm) { const s = pickField(vtu, ['stress'], 6); if (s) { vm = new Float64Array(nodeCount); for (let q = 0; q < nodeCount; q++) vm[q] = vonMises6(s.subarray(6 * q, 6 * q + 6)); } }
  if (!vm) throw new Error(`Elmer's result has no von Mises or stress field (fields: ${Object.keys(vtu.pointData).join(', ')})`);
  return { u: Float64Array.from(u, (v) => v * 1e3), vm: Float64Array.from(vm, (v) => v / 1e6) };
}
export function readThermal(vtu, nodeCount) {
  if (vtu.points.length !== 3 * nodeCount) throw new Error(`Elmer's result has ${vtu.points.length / 3} nodes; the mesh has ${nodeCount}`);
  const T = pickField(vtu, ['temperature'], 1); if (!T) throw new Error(`Elmer's result has no temperature field (fields: ${Object.keys(vtu.pointData).join(', ')})`);
  return Float64Array.from(T, (v) => v - 273.15);
}

export const elmer = {
  id: 'elmer', label: 'Elmer FEM (with gmsh)', kinds: ['structural', 'thermal'], real: true,
  async available(config) {
    const gmsh = await gmshVersion(config.gmshBin), solver = config.elmerBin.includes('/') ? (fs.existsSync(config.elmerBin) ? config.elmerBin : null) : findOnPath(config.elmerBin);
    if (!gmsh) return { ok: false, reason: `gmsh not found ("${config.gmshBin}")`, gmsh: null, elmerSolver: !!solver };
    if (!solver) return { ok: false, reason: `ElmerSolver not found ("${config.elmerBin}")`, gmsh, elmerSolver: false };
    return { ok: true, gmsh, elmerSolver: true, version: `gmsh ${gmsh}; ${solver}` };
  },
  async run(job, ctx) {
    const cfg = ctx.config;
    return withStudy(job, ctx, async (h, sub) => {
      const dir = path.join(ctx.dir, `solve-${sub.tag}`); fs.mkdirSync(dir, { recursive: true });
      ctx.stage('meshing'); const C = await buildCase(job, h, { ...ctx, dir, progress: (f) => sub.progress(0.4 * f) });
      ctx.stage('writing the Elmer case');
      const mesh = writeElmerMesh(path.join(dir, 'mesh'), C.nodes, C.bodies.map((b) => ({ tets: b.tets, boundary: C.groups[b.index] })));
      const m = (b) => b.material;
      const sif = job.kind === 'structural'
        ? structuralSif({ bodies: C.bodies.map((b) => ({ name: b.name, E_Pa: m(b).E_Pa, nu: m(b).nu, rho: m(b).rho || 0, accel: b.accel })), bcs: C.bcs })
        : thermalSif({ bodies: C.bodies.map((b) => ({ name: b.name, k: m(b).k, rho: m(b).rho || 1, cp: m(b).cp || 1, source_W_per_kg: b.source_W_per_kg })), bcs: C.bcs.map((x) => ({ id: x.id, name: x.name, h: x.h, T_ext: x.T_ext })) });
      fs.writeFileSync(path.join(dir, 'case.sif'), sif); fs.writeFileSync(path.join(dir, 'ELMERSOLVER_STARTINFO'), 'case.sif\n');
      sub.progress(0.45); ctx.log(`ElmerSolver: ${mesh.elements} elements, ${mesh.nodes} nodes`);
      ctx.stage('solving');
      let r;
      try { r = await run(cfg.elmerBin, ['case.sif'], { cwd: dir, signal: ctx.signal, onLine: (l) => { if (/^\s*(ComputeChange|Linear system|ERROR|WARNING)/i.test(l) || /iter/i.test(l)) ctx.log('elmer: ' + l.trim().slice(0, 160)); } }); }
      catch (e) { if (e.code === 'ENOENT') throw Object.assign(new Error(`ElmerSolver was not found ("${cfg.elmerBin}")`), { code: 'NO_SOLVER' }); throw e; }
      if (r.code !== 0 || /\bERROR\b/.test(r.out)) throw new Error(`ElmerSolver failed (exit ${r.code}): ${r.out.trim().split('\n').filter((l) => /ERROR|STOP/.test(l)).slice(-3).join(' | ') || r.out.trim().split('\n').slice(-3).join(' | ')}`);
      sub.progress(0.9);
      const vtus = fs.readdirSync(dir).filter((f) => /\.vtu$/.test(f)).sort(); if (!vtus.length) throw new Error('ElmerSolver finished but wrote no .vtu file');
      const vtu = parseVtu(fs.readFileSync(path.join(dir, vtus[vtus.length - 1])));
      ctx.stage('post-processing');
      const post = job.kind === 'structural' ? (({ u, vm }) => postStructural(C, u, vm))(readStructural(vtu, C.nodeCount)) : postThermal(C, readThermal(vtu, C.nodeCount));
      return { summary: post.summary, vtu: resultVtu(C, post.fields), warnings: [...C.warnings, ...post.warnings], h, info: { nodes: C.nodeCount, elements: C.tets, bodies: C.bodies.map((b) => b.name), sif_bytes: sif.length } };
    });
  },
};
