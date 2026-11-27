// A SIMULATED solver, for tests and for trying the protocol without Elmer. It meshes with gmsh when gmsh is installed (so the result is a
// real tetrahedral VTU) and otherwise writes the STL surface; the fields are synthetic and solve no equation:
//   structural: displacement grows with the square of the distance from the fixed faces, toward the load; von Mises grows linearly with it.
//   thermal:    each body sits at ambient + P / (h A), the lumped isothermal answer.
// Everything it returns is marked `simulated: true`, and Forge turns that into validity "experimental" (never gating a check). It is
// OFF unless the runner is started with ALLOY_RUNNER_ENABLE_FAKE=1. It exists to test the protocol and the job lifecycle; it says
// nothing about any physical case and no reference file may ever be made from it.
import { buildCase } from '../lib/casebuild.mjs';
import { gmshVersion } from '../lib/gmsh.mjs';
import { postStructural, postThermal, resultVtu } from '../lib/post.mjs';
import { withStudy } from './common.mjs';

const sleep = (ms, signal) => new Promise((res, rej) => { const t = setTimeout(res, ms); if (signal) signal.addEventListener('abort', () => { clearTimeout(t); rej(Object.assign(new Error('cancelled'), { code: 'CANCELLED' })); }, { once: true }); });

export const fake = {
  id: 'fake', label: 'Simulated solver (protocol tests only)', kinds: ['structural', 'thermal'], real: false,
  async available(config) { return config.enableFake ? { ok: true, version: 'simulated', gmsh: await gmshVersion(config.gmshBin) } : { ok: false, reason: 'disabled (set ALLOY_RUNNER_ENABLE_FAKE=1 to enable the simulated solver)' }; },
  async run(job, ctx) {
    const delay = Math.min(5000, job.options.fake_delay_ms || 0), dep = job.options.fake_mesh_dependence || 0;
    if (!(await gmshVersion(ctx.config.gmshBin))) throw Object.assign(new Error('the simulated solver still needs gmsh to mesh; install gmsh or use the container image'), { code: 'NO_SOLVER' });
    return withStudy(job, ctx, async (h, sub) => {
      ctx.stage('meshing'); const C = await buildCase(job, h, { ...ctx, progress: (f) => sub.progress(0.5 * f) });
      ctx.stage('solving'); for (let i = 1; i <= 5; i++) { await sleep(delay / 5, ctx.signal); sub.progress(0.5 + 0.4 * i / 5); ctx.log(`simulated solve step ${i}/5`); }
      const n = C.nodeCount, drift = 1 + dep * h;
      let post;
      if (job.kind === 'structural') {
        const u = new Float64Array(3 * n), vm = new Float64Array(n);
        const F = [0, 0, 0]; for (const l of C.loads) for (let c = 0; c < 3; c++) F[c] += l.vector_N[c];
        for (const b of C.bodies) {
          if (b.accel) for (let c = 0; c < 3; c++) F[c] += (b.material.rho || 0) * b.volume * 1e-9 * b.accel[c];
          const fixed = new Set(); for (const g of C.groups[b.index]) { const bc = C.bcs.find((x) => x.id === g.bc); if (bc && bc.fixed && bc.fixed.some(Boolean)) for (const q of g.tri) fixed.add(q); }
          const c0 = [0, 0, 0]; for (const q of fixed) for (let c = 0; c < 3; c++) c0[c] += C.nodes[3 * q + c] / Math.max(1, fixed.size);
          let dmax = 1e-9; const d = []; for (let q = b.nodeRange[0]; q < b.nodeRange[1]; q++) { const x = Math.hypot(C.nodes[3 * q] - c0[0], C.nodes[3 * q + 1] - c0[1], C.nodes[3 * q + 2] - c0[2]); d.push(x); dmax = Math.max(dmax, x); }
          const fm = Math.hypot(...F) || 0, dir = fm > 0 ? F.map((v) => v / fm) : [0, 0, 0], E = (b.material.E_Pa || 1e9) / 1e6, A = b.volume / dmax;
          const k = fm * dmax / (E * A) * drift, sig = fm / A * drift;
          for (let q = b.nodeRange[0]; q < b.nodeRange[1]; q++) { const s = d[q - b.nodeRange[0]] / dmax; for (let c = 0; c < 3; c++) u[3 * q + c] = k * s * s * dir[c]; vm[q] = sig * (0.5 + s); }
        }
        post = postStructural(C, u, vm);
      } else {
        const T = new Float64Array(n), amb = C.ambient_K - 273.15;
        for (const b of C.bodies) {
          let hA = 0; for (const bc of C.bcs) if (C.groups[b.index].some((g) => g.bc === bc.id)) hA += bc.h * bc.area_mm2 * 1e-6;
          const rise = hA > 0 ? (b.power_W || 0) / hA * drift : 0;
          for (let q = b.nodeRange[0]; q < b.nodeRange[1]; q++) T[q] = amb + rise;
        }
        post = postThermal(C, T);
      }
      ctx.stage('post-processing');
      return { summary: post.summary, vtu: resultVtu(C, post.fields), warnings: [...C.warnings, ...post.warnings], h, info: { nodes: C.nodeCount, elements: C.tets, bodies: C.bodies.map((b) => b.name) } };
    });
  },
};
