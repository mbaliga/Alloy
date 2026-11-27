// OpenFOAM (steady incompressible flow: blockMesh, snappyHexMesh, simpleFoam with k-omega SST), run as separate processes (GPL, never linked).
// WRITTEN WITHOUT OpenFOAM TO TEST AGAINST. What is tested here: the job schema, the case files as text (lib/foamcase.mjs), and this adapter's
// plumbing against stub scripts that stand in for the three programs and write the files OpenFOAM would. Nothing here says anything about
// OpenFOAM's own output, which this code has never seen. `chtMultiRegionFoam` (conjugate heat transfer, natural convection) is NOT implemented.
// A result is graded `estimate` at best and the first real run is a commissioning step (compare a duct with a hand calculation).
import fs from 'node:fs';
import path from 'node:path';
import { run } from '../lib/gmsh.mjs';
import { foamFiles } from '../lib/foamcase.mjs';
import { findOnPath } from './common.mjs';

const PROGRAMS = ['blockMesh', 'snappyHexMesh', 'simpleFoam'];
const bin = (config, name) => { const dir = config.openfoamBin; return dir ? path.join(dir, name) : findOnPath(name); };

/** The last row of postProcessing/<name>/<time>/surfaceFieldValue.dat as a number (the sum of phi over the plane, m3/s). */
export function readPlaneFlux(dir, name) {
  const base = path.join(dir, 'postProcessing', name); if (!fs.existsSync(base)) throw new Error(`OpenFOAM wrote no postProcessing/${name}`);
  const times = fs.readdirSync(base).filter((t) => /^[0-9.e+-]+$/.test(t)).sort((a, b) => parseFloat(a) - parseFloat(b)); if (!times.length) throw new Error(`postProcessing/${name} has no time directory`);
  const file = path.join(base, times[times.length - 1], 'surfaceFieldValue.dat'); if (!fs.existsSync(file)) throw new Error(`postProcessing/${name} has no surfaceFieldValue.dat`);
  const rows = fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.trim() && !l.startsWith('#')); if (!rows.length) throw new Error('surfaceFieldValue.dat has no data');
  const v = parseFloat(rows[rows.length - 1].trim().split(/\s+/)[1]); if (!Number.isFinite(v)) throw new Error('surfaceFieldValue.dat: the last row has no number');
  return v;
}

export const openfoam = {
  id: 'openfoam', label: 'OpenFOAM (flow, simpleFoam)', kinds: ['flow'], real: true,
  async available(config) {
    const miss = PROGRAMS.filter((p) => { const b = bin(config, p); return !b || (config.openfoamBin && !fs.existsSync(b)); });
    return miss.length ? { ok: false, reason: `not found: ${miss.join(', ')} (set ALLOY_RUNNER_OPENFOAM_BIN to the folder that holds them, or use an image that has OpenFOAM)`, programs: PROGRAMS.filter((p) => !miss.includes(p)) } : { ok: true, programs: PROGRAMS, version: 'unknown (not queried)' };
  },
  async run(job, ctx) {
    const cfg = ctx.config, dir = path.join(ctx.dir, 'case'); fs.mkdirSync(dir, { recursive: true });
    ctx.stage('writing the OpenFOAM case');
    const { files, info } = foamFiles(job, job.bodies);
    for (const [rel, text] of Object.entries(files)) { fs.mkdirSync(path.dirname(path.join(dir, rel)), { recursive: true }); fs.writeFileSync(path.join(dir, rel), text); }
    fs.mkdirSync(path.join(dir, 'constant', 'triSurface'), { recursive: true }); job.bodies.forEach((b, i) => fs.writeFileSync(path.join(dir, 'constant', 'triSurface', `body_${i}.stl`), b.bytes));
    const step = async (name, args, lo, hi, onLine) => {
      ctx.stage(name); const exe = bin(cfg, name); let r;
      try { r = await run(exe, args, { cwd: dir, signal: ctx.signal, onLine }); } catch (e) { if (e.code === 'ENOENT') throw Object.assign(new Error(`${name} was not found`), { code: 'NO_SOLVER' }); throw e; }
      if (r.code !== 0 || /FOAM FATAL (IO )?ERROR/.test(r.out)) throw new Error(`${name} failed (exit ${r.code}): ${r.out.split('\n').filter((l) => /FATAL|ERROR|cannot|not found/i.test(l)).slice(-3).join(' | ') || r.out.trim().split('\n').slice(-3).join(' | ')}`);
      ctx.progress(hi); return r;
    };
    await step('blockMesh', [], 0, 0.1); await step('snappyHexMesh', ['-overwrite'], 0.1, 0.4);
    await step('simpleFoam', [], 0.4, 0.95, (l) => { const m = /^Time = (\d+)/.exec(l); if (m) ctx.progress(0.4 + 0.55 * Math.min(1, +m[1] / info.iterations)); });
    ctx.stage('post-processing');
    const summary = [], warnings = ['OpenFOAM results come from case files this runner wrote without ever running OpenFOAM against them; check the first run against a hand calculation.'];
    for (const p of job.flow.planes || []) { const q = readPlaneFlux(dir, `plane_${p.id}`); summary.push({ metric: `plane_${p.id}_flow`, value: q * 1000, unit: 'L/s' }); }
    summary.push({ metric: 'inlet_speed', value: job.flow.speed_ms, unit: 'm/s' });
    return { summary, vtu: null, warnings, info, study: null };
  },
};
