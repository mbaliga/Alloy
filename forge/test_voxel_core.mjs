// Headless tests for the shared voxel core (analysis/voxel-core): the triangle/box test, the conservative voxelizer and its material
// ids, enclosed-air detection, the sparse active-cell list, the matrix-free PCG on the CPU backend (sync, async, cancel), the grid
// policy, the correlations against A1, and structure checks of the WGSL (the GPU itself is exercised in test_ui_thermal3d.mjs, which
// needs a browser). Run by npm test.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import * as VC from './analysis/voxel-core/index.js';
import * as ELASTIC from './analysis/voxel-core/elastic.js';
import * as GPU_ELASTIC from './analysis/voxel-core/gpu-elastic.js';
import * as LBM from './analysis/voxel-core/lbm.js';
import * as GPU_LBM from './analysis/voxel-core/gpu-lbm.js';
import { coreScript } from './tools/bundle-ext.mjs';

/** Closed box mesh [x0,x1,y0,y1,z0,z1], outward winding. */
function boxMesh(x0, x1, y0, y1, z0, z1, flip = false) {
  const p = []; for (const z of [z0, z1]) for (const y of [y0, y1]) for (const x of [x0, x1]) p.push(x, y, z);
  const quads = [[0, 2, 3, 1], [4, 5, 7, 6], [0, 1, 5, 4], [2, 6, 7, 3], [0, 4, 6, 2], [1, 3, 7, 5]], idx = [];
  for (const q of quads) idx.push(q[0], q[1], q[2], q[0], q[2], q[3]);
  if (flip) for (let i = 0; i < idx.length; i += 3) { const t = idx[i + 1]; idx[i + 1] = idx[i + 2]; idx[i + 2] = t; }
  return { positions: Float32Array.from(p), indices: Uint32Array.from(idx) };
}
const merge = (...ms) => { const pos = [], idx = []; let o = 0; for (const m of ms) { pos.push(...m.positions); idx.push(...Array.from(m.indices, (v) => v + o)); o += m.positions.length / 3; } return { positions: Float32Array.from(pos), indices: Uint32Array.from(idx) }; };
const hollowBox = (n, wall) => merge(boxMesh(0, n, 0, n, 0, n), boxMesh(wall, n - wall, wall, n - wall, wall, n - wall, true));
const count = (mat, v) => { let c = 0; for (const x of mat) if (x === v) c++; return c; };

test('triBoxOverlap: overlap, separation, touching is not overlap, and a triangle that only crosses a corner region', () => {
  const T = (v) => Float32Array.from(v), box = (e = 0.5) => (tri) => VC.triBoxOverlap(0, 0, 0, e, T(tri), 0, 3, 6);
  assert.equal(box()([-2, -2, 0, 2, -2, 0, 0, 2, 0]), true, 'a big triangle through the box');
  assert.equal(box()([2, 2, 2, 3, 2, 2, 2, 3, 2]), false, 'far away');
  assert.equal(box(0.5 * (1 - 1e-3))([-2, -2, 0.5, 2, -2, 0.5, 0, 2, 0.5]), false, 'in the plane of a box face: touching is not overlap');
  assert.equal(box()([-2, -2, 0.4, 2, -2, 0.4, 0, 2, 0.4]), true, 'just inside the face plane');
  assert.equal(box()([0.45, 0.45, 0.45, 2, 2, 2, 2, 2, 1]), true, 'a vertex inside');
  // an edge-edge separating axis: a thin triangle passing the box diagonal-offset
  assert.equal(box(0.5)([1.2, -0.2, -2, 1.2, -0.2, 2, 3, 1, 0]), false);
});

test('voxelizer: an aligned box fills exactly; the conservative mode adds nothing at touching faces; ids are per body', () => {
  const grid = VC.makeGrid([0, 0, 0], [10, 6, 4], 1), mat = new Uint8Array(grid.total);
  const r = VC.paintMesh(grid, mat, boxMesh(0, 10, 0, 6, 0, 4), 7);
  assert.equal(count(mat, 7), 240); assert.equal(r.inside, 240); assert.equal(r.touched, 0, 'faces on cell boundaries touch nothing outside'); assert.equal(r.oddColumns, 0);
  // two bodies: the second only gets empty voxels; a gap stays air
  const g2 = VC.makeGrid([0, 0, 0], [12, 4, 4], 1), m2 = new Uint8Array(g2.total);
  VC.paintMesh(g2, m2, boxMesh(0, 5, 0, 4, 0, 4), 1); VC.paintMesh(g2, m2, boxMesh(7, 12, 0, 4, 0, 4), 2);
  assert.equal(count(m2, 1), 80); assert.equal(count(m2, 2), 80); assert.equal(count(m2, 0), 32);
  assert.equal(m2[5], 0); assert.equal(m2[0], 1); assert.equal(m2[11], 2);
});

test('voxelizer: a wall thinner than a voxel is kept by the conservative mode and lost by centre sampling', () => {
  // slab 0.4 mm thick at x = 3.3..3.7 inside a 10 mm grid of 1 mm cells: its centre is at 3.5 = a cell centre, so shift it to miss centres
  const slab = boxMesh(3.55, 3.95, 0, 4, 0, 4), grid = VC.makeGrid([0, 0, 0], [10, 4, 4], 1);
  const cons = new Uint8Array(grid.total), centre = new Uint8Array(grid.total);
  VC.paintMesh(grid, cons, slab, 1, { conservative: true }); VC.paintMesh(grid, centre, slab, 1, { conservative: false });
  assert.equal(count(centre, 1), 0, 'centre sampling misses a 0.4 mm wall between centres');
  assert.equal(count(cons, 1), 16, 'the conservative voxelizer keeps one layer of voxels');
});

test('enclosed air: a closed hollow box has a cavity; opening a hole to the outside removes it; a hollow-box wall has the right voxel count', () => {
  const grid = VC.makeGrid([0, 0, 0], [20, 20, 20], 1), mat = new Uint8Array(grid.total);
  VC.paintMesh(grid, mat, hollowBox(20, 2), 1);
  const air = VC.classifyAir(grid, mat);
  assert.equal(count(mat, 1), 8000 - 4096); assert.equal(air.cavity, 4096); assert.equal(count(mat, VC.CAVITY), 4096); assert.equal(count(mat, 0), 0, 'nothing else is outside air');
  const st = VC.buildStructure(grid, mat); assert.equal(st.n, 8000);
  // a box open on top: a wall of 2 mm around and below, the inside is connected to the outside
  const open = merge(boxMesh(0, 20, 0, 20, 0, 2), boxMesh(0, 2, 0, 20, 2, 20), boxMesh(18, 20, 0, 20, 2, 20), boxMesh(2, 18, 0, 2, 2, 20), boxMesh(2, 18, 18, 20, 2, 20));
  const m2 = new Uint8Array(grid.total); VC.paintMesh(grid, m2, open, 1);
  assert.equal(VC.classifyAir(grid, m2).cavity, 0, 'an opening connects the inside to the outside air');
});

test('parts: boxes and cylinders are painted by voxel centres; a sub-voxel part still gets one voxel; cells already solid are kept', () => {
  const grid = VC.makeGrid([0, 0, 0], [10, 10, 10], 1), mat = new Uint8Array(grid.total);
  assert.equal(VC.paintBox(grid, mat, [2, 5, 2, 4, 2, 3], 3), 6);
  assert.equal(VC.paintBox(grid, mat, [3.1, 3.3, 3.1, 3.3, 7.1, 7.3], 4), 1, 'a part of 0.2 mm still occupies the voxel holding its centre');
  assert.equal(VC.paintBox(grid, mat, [2, 5, 2, 4, 2, 3], 5), 0, 'already painted voxels are not overwritten');
  const c = VC.paintCylinder(grid, mat, { axis: 'z', a0: 5, a1: 9, c: [7, 7], r: 2 }, 6);
  assert.ok(c > 40 && c < 60, `a radius 2 mm cylinder 4 mm long is about ${Math.PI * 4 * 4} voxels, got ${c}`);
});

test('active cells: neighbours are symmetric, faces on the outside have none, and the conductances follow the harmonic mean', () => {
  const grid = VC.makeGrid([0, 0, 0], [6, 4, 3], 1), mat = new Uint8Array(grid.total);
  VC.paintMesh(grid, mat, boxMesh(0, 6, 0, 4, 0, 3), 1); VC.classifyAir(grid, mat);
  const st = VC.buildStructure(grid, mat); assert.equal(st.n, 72);
  for (let a = 0; a < st.n; a++) for (let f = 0; f < 6; f++) { const j = st.nbr[6 * a + f]; if (j >= 0) assert.equal(st.nbr[6 * j + (f ^ 1)], a, 'the neighbour through the opposite face is me'); }
  let outside = 0; for (let a = 0; a < st.n; a++) for (let f = 0; f < 6; f++) if (st.nbr[6 * a + f] < 0) outside++;
  assert.equal(outside, 2 * (6 * 4 + 6 * 3 + 4 * 3));
  const k = new Float64Array(st.n).fill(2); k[1] = 6; // cell 1 is next to cell 0
  const g = VC.faceConductances(st, k, 1e-3);
  assert.ok(Math.abs(g[6 * 0 + 0] - (2 * 2 * 6 / 8) * 1e-3) < 1e-15, 'harmonic mean of 2 and 6 is 3'); assert.equal(g[6 * 0 + 1], 0, 'no neighbour, no conductance');
  const ka = new Float64Array(3 * st.n); for (let a = 0; a < st.n; a++) { ka[3 * a] = 1; ka[3 * a + 1] = 10; ka[3 * a + 2] = 100; }
  const ga = VC.faceConductances(st, k, 1e-3, null, ka); assert.ok(Math.abs(ga[0] - 1e-3) < 1e-15 && Math.abs(ga[2] - 10e-3) < 1e-15 && Math.abs(ga[4] - 100e-3) < 1e-15, 'a face along an axis uses that axis conductivity');
});

/** A random SPD stencil operator on a small box: returns { n, nbr, g, diag, b }. */
function system(nx = 12, ny = 9, nz = 7, seed = 1) {
  let s = seed; const rnd = () => ((s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const n = nx * ny * nz, nbr = new Int32Array(6 * n).fill(-1), g = new Float64Array(6 * n), diag = new Float64Array(n), b = new Float64Array(n);
  const id = (i, j, k) => i + nx * (j + ny * k), gv = new Map();
  for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
    const a = id(i, j, k);
    [[i + 1, j, k], [i - 1, j, k], [i, j + 1, k], [i, j - 1, k], [i, j, k + 1], [i, j, k - 1]].forEach((q, f) => {
      if (q[0] < 0 || q[1] < 0 || q[2] < 0 || q[0] >= nx || q[1] >= ny || q[2] >= nz) return;
      const c = id(...q), key = a < c ? `${a},${c}` : `${c},${a}`; if (!gv.has(key)) gv.set(key, 0.2 + rnd());
      nbr[6 * a + f] = c; g[6 * a + f] = gv.get(key); diag[a] += gv.get(key);
    });
    diag[a] += (i === 0 ? 2 : 0) + 0.01; b[a] = rnd() * 3;
  }
  return { n, nbr, g, diag, b };
}

test('PCG: the stencil is symmetric, the solution has a tiny residual, and sync, async and the dense solve agree', async () => {
  const S = system(), { n } = S;
  const x = Float64Array.from({ length: n }, (_, i) => Math.sin(i)), y = Float64Array.from({ length: n }, (_, i) => Math.cos(i * 1.3));
  const Ax = new Float64Array(n), Ay = new Float64Array(n); VC.stencilApply(n, S.nbr, S.g, S.diag, x, Ax); VC.stencilApply(n, S.nbr, S.g, S.diag, y, Ay);
  let a = 0, b = 0; for (let i = 0; i < n; i++) { a += y[i] * Ax[i]; b += x[i] * Ay[i]; } assert.ok(Math.abs(a - b) < 1e-9 * Math.abs(a), 'x.Ay = y.Ax');
  const op = await VC.cpuBackend.createOperator(S);
  const sync = op.solveSync(S.b, new Float64Array(n), { tol: 1e-12 }); assert.equal(sync.converged, true);
  const r = new Float64Array(n); VC.stencilApply(n, S.nbr, S.g, S.diag, sync.x, r); let rn = 0, bn = 0; for (let i = 0; i < n; i++) { rn += (r[i] - S.b[i]) ** 2; bn += S.b[i] ** 2; } assert.ok(Math.sqrt(rn / bn) < 1e-11);
  const asyncR = await op.solve(S.b, new Float64Array(n), { tol: 1e-12 }); assert.deepEqual(Array.from(asyncR.x), Array.from(sync.x), 'the same generator gives the same iterates');
  assert.equal(asyncR.iters, sync.iters);
  // against a dense Gaussian elimination on a small system
  const T = system(4, 3, 2, 7), N = T.n, A = Array.from({ length: N }, () => new Float64Array(N + 1));
  for (let i = 0; i < N; i++) { A[i][i] = T.diag[i]; A[i][N] = T.b[i]; for (let f = 0; f < 6; f++) if (T.nbr[6 * i + f] >= 0) A[i][T.nbr[6 * i + f]] -= T.g[6 * i + f]; }
  for (let c = 0; c < N; c++) { let p = c; for (let r2 = c + 1; r2 < N; r2++) if (Math.abs(A[r2][c]) > Math.abs(A[p][c])) p = r2; [A[c], A[p]] = [A[p], A[c]]; for (let r2 = c + 1; r2 < N; r2++) { const f = A[r2][c] / A[c][c]; for (let k = c; k <= N; k++) A[r2][k] -= f * A[c][k]; } }
  const xd = new Float64Array(N); for (let r2 = N - 1; r2 >= 0; r2--) { let s2 = A[r2][N]; for (let k = r2 + 1; k < N; k++) s2 -= A[r2][k] * xd[k]; xd[r2] = s2 / A[r2][r2]; }
  const small = (await VC.cpuBackend.createOperator(T)).solveSync(T.b, new Float64Array(N), { tol: 1e-13 });
  for (let i = 0; i < N; i++) assert.ok(Math.abs(small.x[i] - xd[i]) < 1e-9, `cell ${i}`);
});

test('PCG: the diagonal can change between solves (setDiag), a cancelled solve stops, progress is reported between chunks', async () => {
  const S = system(), op = await VC.cpuBackend.createOperator(S);
  const a = op.solveSync(S.b, new Float64Array(S.n), { tol: 1e-10 });
  op.setDiag(S.diag.map((d) => d + 1)); const b2 = op.solveSync(S.b, new Float64Array(S.n), { tol: 1e-10 });
  assert.ok(Math.max(...b2.x) < Math.max(...a.x), 'a larger diagonal (more loss) lowers the solution');
  op.setDiag(S.diag);
  const ac = new AbortController(); let steps = 0;
  await assert.rejects(op.solve(S.b, new Float64Array(S.n), { tol: 1e-14, signal: ac.signal, onStep: () => { steps++; ac.abort(); } }), /cancelled/);
  assert.equal(steps, 1);
  let seen = 0; await op.solve(S.b, new Float64Array(S.n), { tol: 1e-12, onStep: ({ iter, rel }) => { seen++; assert.ok(iter > 0 && rel > 0); } }); assert.ok(seen >= 1);
});

test('grid policy: budgets by quality and device, phone and CPU smaller than laptop GPU, the hard limits, and the voxel size lands on the budget', () => {
  const B = VC.GRID_BUDGETS;
  assert.ok(B.laptop.fine >= 1e6 && B.laptop.fine <= 2e6, 'about 1 to 2 million active voxels on a laptop GPU');
  assert.ok(B.phone.fine === 250000, 'about 250 thousand on a phone');
  for (const q of VC.QUALITIES_3D) { assert.ok(B.laptop[q] >= B.phone[q]); assert.ok(B.cpu[q] <= B.laptop[q]); }
  assert.ok(B.laptop.draft < B.laptop.normal && B.laptop.normal < B.laptop.fine);
  assert.equal(VC.gridBudget({ quality: 'fine', device: 'laptop', backend: 'cpu' }).budget, B.cpu.fine, 'the CPU fallback is limited to the small budget even on a laptop');
  assert.equal(VC.gridBudget({ quality: 'fine', device: 'phone', backend: 'gpu' }).budget, B.phone.fine);
  assert.throws(() => VC.gridBudget({ quality: 'ultra' }), /quality must be one of/);
  assert.equal(VC.deviceClass({ userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari' }), 'phone'); assert.equal(VC.deviceClass({ userAgent: 'Mozilla/5.0 (X11; Linux x86_64) Chrome/120' }), 'laptop');
  assert.equal(VC.deviceClass({ userAgent: 'x', userAgentData: { mobile: true } }), 'phone');
  assert.ok(VC.GRID_HARD_LIMIT.active <= 2000000 && VC.GRID_HARD_LIMIT.total <= 8000000);
  for (const budget of [20000, 100000]) {
    const m = VC.buildVoxelModel({ meshes: [{ ...boxMesh(0, 40, 0, 30, 0, 20), id: 1 }], bbox: { lo: [0, 0, 0], hi: [40, 30, 20] }, budget });
    assert.ok(m.struct.n > budget * 0.4 && m.struct.n < budget * 1.2, `${m.struct.n} active for a budget of ${budget} (h ${m.grid.h.toFixed(3)})`);
  }
  assert.throws(() => VC.buildVoxelModel({ meshes: [{ ...boxMesh(0, 400, 0, 300, 0, 200), id: 1 }], bbox: { lo: [0, 0, 0], hi: [400, 300, 200] }, h: 0.5 }), /exceeds the limit.*voxel size of at least/);
});

test('correlations: the core owns copies of A1\'s functions and they agree with A1 at every point of a sweep', () => {
  const a1 = {}; new Function('__core', fs.readFileSync('examples/ext/thermal-estimate/main.js', 'utf8'))(a1);
  for (const T of [250, 280, 300, 333.15, 380, 450]) { const x = a1.airProps(T), y = VC.airProps(T); for (const k of Object.keys(x)) assert.equal(y[k], x[k], `airProps(${T}).${k}`); }
  for (const Ra of [1e-2, 1e3, 1e5, 1e7, 3e8, 1e10, 1e12]) {
    assert.equal(VC.nuVertical(Ra, 0.71), a1.nuVertical(Ra, 0.71)); assert.equal(VC.nuHorizontalEnhanced(Ra), a1.nuHorizontalEnhanced(Ra)); assert.equal(VC.nuHorizontalRestricted(Ra), a1.nuHorizontalRestricted(Ra));
  }
  for (const Re of [1e2, 1e4, 4e5, 6e5, 1e7]) assert.equal(VC.nuForcedPlate(Re, 0.71), a1.nuForcedPlate(Re, 0.71));
  for (const [e, Ts, Ta] of [[0.9, 330, 300], [0.05, 400, 290], [0.85, 338.15, 308.15]]) assert.equal(VC.hRadiation(e, Ts, Ta), a1.hRadiation(e, Ts, Ta));
  for (const orient of ['side', 'up', 'down']) for (const [Ts, Tf] of [[330, 300], [290, 300], [310, 300.5]]) for (const L of [0.01, 0.2, 0.6]) for (const airspeed of [0, 2]) {
    const x = a1.hFace({ orient, hotter: Ts > Tf, L, Ts, Tf, airspeed }), y = VC.hFace({ orient, hotter: Ts > Tf, L, Ts, Tf, airspeed });
    assert.deepEqual(y, x, `${orient} ${Ts}/${Tf} L ${L} wind ${airspeed}`);
  }
  for (const f of ['+x', '-x', '+y', '-y', '+z', '-z']) for (const g of ['+x', '-x', '+y', '-y', '+z', '-z']) assert.equal(VC.orientOf(f, g), a1.orientOf(f, g));
  assert.equal(VC.SIGMA, 5.670374419e-8); assert.equal(VC.GRAV, 9.80665);
});

// ───────── WebGPU: what can be checked without a GPU
test('WGSL: every kernel exists, binds exactly the buffers the host binds, and stays within eight storage buffers', () => {
  const src = VC.wgslSource(), names = { P: 0, nbr: 1, g: 2, diag: 3, xv: 4, rv: 5, zv: 6, pv: 7, qv: 8, bv: 9, part: 10, sc: 11 };
  const fn = (name) => { const m = new RegExp(`fn ${name}\\([^)]*\\)[^{]*\\{`).exec(src); assert.ok(m, `fn ${name}`); let d = 0, i = m.index + m[0].length - 1, j = i; for (; j < src.length; j++) { if (src[j] === '{') d++; else if (src[j] === '}' && --d === 0) break; } return src.slice(m.index, j + 1); };
  const body = (name, seen = new Set()) => { if (seen.has(name)) return ''; seen.add(name); const t = fn(name); return t + [...t.matchAll(/\b(stencil_[xp]|reduce_group|reduce_red)\(/g)].map((m) => body(m[1], seen)).join('\n'); };
  for (const [kernel, binds] of Object.entries(VC.KERNELS)) {
    assert.match(src, new RegExp(`@compute @workgroup_size\\(\\d+\\)\\s*fn ${kernel}\\(`), `entry point ${kernel}`);
    const text = body(kernel), used = Object.entries(names).filter(([id]) => new RegExp(`\\b${id}\\b`).test(text.replace(/fn \w+\(([^)]*)\)/g, ''))).map(([, v]) => v).sort((a, b) => a - b);
    assert.deepEqual(used, binds.slice().sort((a, b) => a - b), `${kernel} binds ${binds} but uses ${used}`);
    assert.ok(binds.filter((b) => b !== 0).length <= 8, `${kernel} needs more than 8 storage buffers`);
  }
  assert.match(src, /var<uniform> P: Params/); assert.equal((src.match(/@group\(0\) @binding\(\d+\)/g) || []).length, 12);
  assert.ok(VC.GPU_MAX_CELLS >= 8000000, 'one dispatch row covers the 2 million active-voxel limit with room');
  assert.ok(VC.WG === 128 && VC.WG_RED === 256);
});

test('WGSL: balanced braces, no stray template text, and the GPU backend reports a clear reason when there is no WebGPU', async () => {
  const src = VC.wgslSource(); assert.equal((src.match(/\{/g) || []).length, (src.match(/\}/g) || []).length); assert.ok(!/\$\{|undefined|NaN/.test(src));
  assert.equal(await VC.gpuBackend(), null); assert.match(VC.gpuBackend.lastError, /WebGPU is not available/);
});

test('bundle: the core evaluates as one script with every export, ready for a Worker', () => {
  const core = (0, eval)(coreScript());
  for (const k of ['buildVoxelModel', 'paintMesh', 'pcgSteps', 'cpuBackend', 'gpuBackend', 'wgslSource', 'hFace', 'faceConductances', 'gridBudget', 'deviceClass']) assert.equal(typeof core[k] === 'function' || typeof core[k] === 'object', true, k);
  assert.equal(Object.keys(core).length, Object.keys(VC).length - Object.keys(ELASTIC).length - Object.keys(GPU_ELASTIC).length - Object.keys(LBM).length - Object.keys(GPU_LBM).length, 'the thermal bundle and the module expose the same names, less the elastic solver of A3 and the lattice Boltzmann solver of A5 (test_structural.mjs and test_airflow.mjs bundle those)');
  for (const k of Object.keys(LBM)) assert.ok(!(k in core), `${k} (A5 only) is not in the thermal bundle`);
  for (const k of Object.keys(ELASTIC)) assert.ok(!(k in core), `${k} (A3 only) is not in the thermal bundle`);
});

test('voxelizer: the result does not depend on body order (a thin body on top of another keeps its voxels), and a very thin body neither hangs nor loops', () => {
  const A = { ...boxMesh(0, 10, 0, 10, 0, 10.3), id: 1 }, B = { ...boxMesh(0, 10, 0, 10, 10.3, 11.2), id: 2 }, bbox = { lo: [0, 0, 0], hi: [10, 10, 11.2] };
  const count = (m) => { const c = {}; for (const v of m.mat) if (v) c[v] = (c[v] || 0) + 1; return c; };
  const ab = count(VC.buildVoxelModel({ meshes: [A, B], bbox, h: 1 })), ba = count(VC.buildVoxelModel({ meshes: [B, A], bbox, h: 1 }));
  assert.deepEqual(ab, ba, 'same voxels per body in either order'); assert.ok(ab[2] >= 100, `the thin body keeps a layer: ${JSON.stringify(ab)}`);
  // a 100 x 100 x 0.001 mm foil: the grid limit is hit by area, not volume; it must finish (coarser) instead of looping
  const t0 = Date.now(), foil = { ...boxMesh(0, 100, 0, 100, 0, 0.001), id: 1 };
  const m = VC.buildVoxelModel({ meshes: [foil], bbox: { lo: [0, 0, 0], hi: [100, 100, 0.001] }, budget: 600000 });
  assert.ok(m.grid.total <= VC.GRID_HARD_LIMIT.total && Date.now() - t0 < 20000, 'finished within the grid limit');
  // and a body that cannot fit at all is an error with a message, after a bounded number of passes
  assert.throws(() => VC.buildVoxelModel({ meshes: [foil], bbox: { lo: [0, 0, 0], hi: [100, 100, 0.001] }, budget: 600000, maxTotal: 3 }), /too thin or flat/);
});
