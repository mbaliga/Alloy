import fs from 'node:fs';
import path from 'node:path';
import { autoMeshSize, surfaceBodies } from '../lib/casebuild.mjs';
import { compareStudy } from '../lib/post.mjs';

export const STUDY_FACTOR = 1.5; // the second mesh has an element size of h / 1.5 (about 3.4 times the elements)
const STUDY_TOL = {
  structural: { names: ['max_displacement', 'stiffness', 'max_von_mises'], tol: { default: 0.02, max_von_mises: 0.1 } },
  thermal: { names: ['max_temp'], tol: { default: 0.02 } },
};

export function findOnPath(name, pathVar = process.env.PATH || '') {
  for (const d of pathVar.split(path.delimiter)) { const p = path.join(d, name); try { fs.accessSync(p, fs.constants.X_OK); if (fs.statSync(p).isFile()) return p; } catch (_) { /* next */ } }
  return null;
}

/**
 * Run `solveAt(h, sub)` once, or twice for a mesh study. solveAt returns { summary, vtu, warnings, info, h }.
 * The first (coarser, the requested) solve is what is delivered; the second only feeds `study`.
 */
export async function withStudy(job, ctx, solveAt) {
  const bodies = surfaceBodies(job);
  const h = job.options.mesh_size_mm > 0 ? job.options.mesh_size_mm : autoMeshSize(bodies.map((b) => ({ stats: b.stats || { volume: 1, min: [0, 0, 0], max: [1, 1, 1] } })), ctx.config.maxTets);
  const tick = (lo, hi) => (f) => ctx.progress(lo + (hi - lo) * Math.min(1, Math.max(0, f)));
  const study = job.options.mesh_study;
  const first = await solveAt(h, { progress: tick(0, study ? 0.5 : 1), tag: 'a' });
  if (!study) return { ...first, study: null };
  const hFine = +(h / STUDY_FACTOR).toPrecision(3);
  ctx.log(`mesh study: second solve at ${hFine} mm`);
  const second = await solveAt(hFine, { progress: tick(0.5, 1), tag: 'b' });
  const cfg = STUDY_TOL[job.kind];
  return { ...first, study: compareStudy({ summary: first.summary, h }, { summary: second.summary, h: hFine }, cfg.names, cfg.tol) };
}
