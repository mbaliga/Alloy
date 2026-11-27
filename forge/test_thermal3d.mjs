// Headless tests for the A2 thermal 3D package (examples/ext/thermal-3d, built from analysis/voxel-core and analysis/thermal-3d):
// the generated bundle is current, settings validation, the physics of the model, the package through the real runner and a Worker
// (progress, cancel, settings in the cache key, checks, the AI tool), and the validation harness's new case kinds (a reference file
// loader, a skipped case that is not a pass, a two-package comparison). The analytic cases and their tolerances are in
// analysis-validation/cases/thermal-3d.json (npm run test:analysis).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { emptyProject, applyOps, migrate } from './src/model.js';
import { prepareInput } from './src/analysis/setup.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { ExtHost } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { tierOf, validateManifest } from './src/ext/manifest.js';
import { createResolver } from './src/ext/resolve.js';
import { createAnalysisRunner } from './src/analysis/runner.js';
import { createResultCache, memoryStore as memResults } from './src/analysis/cache.js';
import { packDir } from './tools/pack-ext.mjs';
import { bundle, TARGETS } from './tools/bundle-ext.mjs';
import { runValidation, lintCaseFile, renderTable } from './analysis-validation/harness.mjs';

let wasm; const HOSTS = [];
before(async () => { wasm = await Module(); wasm.setup(); });
after(() => HOSTS.forEach((h) => h.stopAll()));
const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };
const EXT = 'xyz.mdhv.thermal-3d', A1 = 'xyz.mdhv.thermal-estimate', K = 273.15;
const core = {}; new Function('__core', fs.readFileSync('examples/ext/thermal-3d/main.js', 'utf8'))(core);

const mat = (id, k, rho = 1000, cp = 1000, eps = 0.9) => ({ id, name: id, class: 'custom', notes: 't', properties: Object.fromEntries([['k', k], ['rho', rho], ['cp', cp], ['emissivity', eps]].map(([n, v]) => [n, { value: v, quality: 'measured', sources: ['user'] }]).concat(['E', 'nu', 'yield_strength', 'tensile_strength', 'interlayer_factor'].map((n) => [n, { value: null, quality: 'n/a', sources: [] }]))) });
const insulate = (body, faces) => faces.map((f) => ({ op: 'set_condition', list: 'mounts', item: { id: 'i' + f[1] + (f[0] === '+' ? 'p' : 'm'), kind: 'contact', body, face: f, conductance_W_m2K: 0 } }));
/** A 60 x 40 x 25 mm ABS case with a board and a 1 W part (the A1 test design); `extra` ops and `ext` settings are applied. */
function enclosure({ watts = 1, ext = {}, ops = [], size = [60, 40, 25] } = {}) {
  const [w, d, h] = size;
  let P = applyOps(migrate(emptyProject()), [{ type: 'box', body: 'Case', size, anchor: 'bottom', pos: [0, 0, 0] }, { type: 'box', body: 'Case', op: 'cut', size: [w - 4, d - 4, h - 4], anchor: 'bottom', pos: [0, 0, 2] }].map((feature) => ({ op: 'add_feature', feature }))).project;
  P.pcb = { ...P.pcb, components: [{ name: 'board', kind: 'box', role: 'board', box: [-25, 25, -16, 16, 6, 7.6] }, { name: 'mcu', kind: 'box', role: 'component', box: [-4, 4, -4, 4, 7.6, 9.6] }] };
  P = applyOps(P, [{ op: 'set_body_material', body: 'Case', material: 'abs' }, { op: 'set_ambient', ambient: { temperature_K: 298.15 } }, ...(watts > 0 ? [{ op: 'set_condition', list: 'heat', item: { id: 'h1', target: { pcb: 'mcu' }, power_W: watts } }] : []), ...ops]).project;
  P.ext = { [EXT]: { settings: { quality: 'draft', backend: 'cpu', ...ext } } };
  return P;
}
/** A solid block of a test material with the +-y, +-z faces insulated: a 1D bar along x. */
function bar({ k = 2, size = [20, 10, 10], heat = 0, ext = {}, ops = [] } = {}) {
  let P = applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: { type: 'box', body: 'Bar', size, anchor: 'bottom', pos: [0, 0, 0] } }, { op: 'set_material', material: mat('tk', k) }, { op: 'set_body_material', body: 'Bar', material: 'tk' }, { op: 'set_ambient', ambient: { temperature_K: 293.15 } },
    ...insulate('Bar', ['+y', '-y', '+z', '-z']), ...(heat ? [{ op: 'set_condition', list: 'heat', item: { id: 'q', target: { body: 'Bar' }, power_W: heat } }] : []), ...ops]).project;
  P.ext = { [EXT]: { settings: { backend: 'cpu', h_mm: 1, convection: 'fixed', h_W_m2K: 10, radiation: false, ...ext } } };
  return P;
}
const inputOf = (P) => { const B = build(P, wasm); try { return prepareInput(P, B); } finally { B.dispose(); } };
const run = (P, over) => core.simulateSync(inputOf(P), over);

test('bundle: examples/ext/thermal-3d/main.js is generated from the core and the package source and is current', () => {
  for (const name of Object.keys(TARGETS)) assert.equal(fs.readFileSync(TARGETS[name].out, 'utf8'), bundle(name), `${TARGETS[name].out} is stale: run npm run build:ext`);
  assert.match(fs.readFileSync('examples/ext/thermal-3d/main.js', 'utf8'), /GENERATED by tools\/bundle-ext\.mjs/);
});

test('manifest: an extension (it asks for compute.gpu), valid, with settings and checks declared', async () => {
  const pkg = await packDir('examples/ext/thermal-3d'), registry = new ExtRegistry({ store: memoryStore() });
  const rec = await registry.install(pkg), m = registry.get(EXT).manifest;
  assert.ok(rec); assert.equal(tierOf(m), 'extension'); assert.ok(m.permissions.includes('compute.gpu'));
  const a = m.contributes.analyses[0]; assert.equal(a.validity, 'estimate', 'not validated: the Elmer reference case is pending');
  assert.deepEqual(a.settings.map((s) => s.key), ['quality', 'mode', 't_end_s', 'dt_s', 'limit_C', 'backend']);
  assert.deepEqual(a.checks.map((c) => c.metric), ['max_temp', 'max_outer_surface_temp']);
  assert.throws(() => validateManifest({ ...m, contributes: { analyses: [{ ...a, settings: [{ key: 'x', label: 'x', kind: 'enum' }] }] } }), /enum settings need opts/);
  assert.throws(() => validateManifest({ ...m, contributes: { analyses: [{ ...a, settings: [a.settings[0], a.settings[0]] }] } }), /duplicate setting keys/);
});

test('settings: defaults, validation with a plain message, fixed-temperature faces, and unknown values fall back', () => {
  const d = core.parseSettings({}); assert.equal(d.settings.quality, 'normal'); assert.equal(d.settings.mode, 'steady'); assert.deepEqual(d.warnings, []);
  const r = core.parseSettings({ quality: 'ultra', mode: 'transient', t_end_s: -3, backend: 'gpu', fixed: [{ face: '+x', T_C: 80, body: 'A' }, { face: 'up', T_C: 80 }, { face: '-x', T_K: 5 }], initial_C: 'hot' });
  assert.equal(r.settings.quality, 'normal'); assert.equal(r.settings.mode, 'transient'); assert.equal(r.settings.t_end_s, 600); assert.equal(r.settings.backend, 'gpu');
  assert.equal(r.settings.fixed.length, 1); assert.equal(r.settings.fixed[0].T, 80 + K); assert.equal(r.settings.initial_C, null);
  assert.ok(r.warnings.some((w) => /quality/.test(w)) && r.warnings.some((w) => /t_end_s/.test(w)) && r.warnings.some((w) => /fixed-temperature entry was ignored/.test(w)) && r.warnings.some((w) => /initial_C/.test(w)));
});

test('physics: no heat leaves everything at ambient; a bar with a source and one convective end is hottest at the insulated end; more power is hotter', () => {
  const cold = run(enclosure({ watts: 0 })); assert.ok(Math.abs(cold.maxTK - 298.15) < 1e-6 && Math.abs(cold.minTK - 298.15) < 1e-6);
  const a = run(bar({ heat: 0.1, ops: [{ op: 'set_condition', list: 'mounts', item: { id: 'x', kind: 'contact', body: 'Bar', face: '+x', conductance_W_m2K: 0 } }] }));
  assert.ok(a.tAt(9.5, 0.5, 0.5) > a.tAt(-9.5, 0.5, 0.5) + 0.1, 'the insulated end is the hot end');
  const b1 = run(bar({ heat: 0.1 })), b2 = run(bar({ heat: 0.2 }));
  assert.ok(Math.abs((b2.maxTK - 293.15) - 2 * (b1.maxTK - 293.15)) < 1e-6 * 100, 'a linear problem: double the power, double the rise');
  const e1 = run(enclosure({ watts: 1 })), e2 = run(enclosure({ watts: 2 }));
  assert.ok(e2.maxCK - 298.15 > e1.maxCK - 298.15 && e2.maxCK - 298.15 < 2 * (e1.maxCK - 298.15) + 1, 'natural convection and radiation grow with the temperature, so the rise is less than proportional');
  assert.ok(e1.maxCK > e1.maxSK + 5 && e1.maxSK > 298.15, 'part hotter than the outer surface, hotter than the room');
});

test('energy: every watt is accounted for, steady and transient, with convection, radiation and a contact all present', () => {
  const P = enclosure({ watts: 2, ops: [{ op: 'set_condition', list: 'mounts', item: { id: 'rail', kind: 'contact', body: 'Case', face: '-z', conductance_W_m2K: 100 } }] });
  const s = run(P, { quality: 'draft' }); assert.ok(s.balance < 1e-6, `steady balance ${s.balance}`); assert.ok(s.q.conv > 0 && s.q.rad > 0 && s.q.contact > 0);
  assert.ok(Math.abs(s.q.conv + s.q.rad + s.q.contact - 2) < 1e-6);
  const t = run(P, { quality: 'draft', mode: 'transient', t_end_s: 120, dt_s: 4 }); assert.ok(t.balance < 1e-6, `transient balance ${t.balance}`);
  assert.ok(t.maxTK < s.maxTK, 'two minutes in the part is cooler than at steady state');
  const t2 = run(P, { quality: 'draft', mode: 'transient', t_end_s: 40000, dt_s: 400 }); assert.ok(Math.abs(t2.maxTK - s.maxTK) < 0.05, `a long transient reaches the steady state: ${t2.maxTK - s.maxTK}`);
});

test('boundary conditions: a zero-conductance mount insulates, a contact conducts, fixed temperature pins the face, and a fully insulated body is an error', () => {
  const mount = (id, face, c) => ({ op: 'set_condition', list: 'mounts', item: { id, kind: 'contact', body: 'Bar', face, conductance_W_m2K: c } });
  const open = run(bar({ heat: 0.1 })), oneEnd = run(bar({ heat: 0.1, ops: [mount('x', '+x', 0)] }));
  assert.ok(oneEnd.maxTK > open.maxTK + 1, 'insulating one end leaves one path, so the bar is hotter');
  assert.ok(Math.abs(oneEnd.q.conv - 0.1) < 1e-6 && oneEnd.q.contact === 0, 'all the heat leaves by the other end');
  const sunk = run(bar({ heat: 0.1, ops: [mount('x', '+x', 0), mount('y', '-x', 1000)] }));
  assert.ok(Math.abs(sunk.q.contact - 0.1) < 1e-6 && sunk.maxTK < oneEnd.maxTK, 'a contact conducts the heat to the sink');
  assert.throws(() => run(bar({ heat: 0.1, ops: [mount('x', '+x', 0), mount('y', '-x', 0)] })), /no heat path to ambient/);
  const pinned = run(bar({ heat: 0.05, ext: { fixed: [{ face: '-x', body: 'Bar', T_C: 60 }, { face: '+x', body: 'Bar', T_C: 60 }] } }));
  assert.ok(pinned.q.fixed > 0.049 && pinned.q.fixed < 0.051, 'all the heat leaves through the two fixed faces'); assert.ok(pinned.maxTK > 333.15 && pinned.tAt(-9.5, 0.5, 0.5) > 333.15 && pinned.tAt(-9.5, 0.5, 0.5) < 333.5, 'faces at 60 C, the middle hotter');
  const half = run(bar({ heat: 0.1, ext: { fixed: [{ face: '-x', body: 'Bar', T_C: 60, box: [-10, -10, -20, 20, -20, 20] }, { face: '+x', body: 'Nobody', T_C: 99 }] } }));
  assert.ok(half.q.fixed > 0.05, 'a box-limited fixed face works'); assert.ok(half.q.fixed < 0.1000001 + 1e-6 && half.maxTK < 400, 'and a fixed face of a body that is not there matches nothing');
});

test('area correction: an aligned box keeps its area, and a cylinder side is scaled by 4/pi per axis so the film area stays the true area', () => {
  const b = run(bar({ heat: 0.1 })).model; for (let o = 1; o <= 1; o++) for (let ax = 0; ax < 3; ax++) assert.ok(Math.abs(b.areaF[o * 3 + ax] - 1) < 1e-9, `box axis ${ax}`);
  let P = applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: { type: 'cylinder', body: 'Rod', d: 20, h: 10 } }, { op: 'set_material', material: mat('tk', 2) }, { op: 'set_body_material', body: 'Rod', material: 'tk' }, { op: 'set_condition', list: 'heat', item: { id: 'q', target: { body: 'Rod' }, power_W: 1 } }]).project;
  P.ext = { [EXT]: { settings: { backend: 'cpu', h_mm: 0.5, convection: 'fixed', h_W_m2K: 50, radiation: false } } };
  const rr = run(P), m = rr.model; assert.ok(m.n < m.grid.total, 'the corners of the box around a cylinder are outside air, not cells');
  assert.ok(Math.abs(m.areaF[3] - Math.PI / 4) < 0.02 && Math.abs(m.areaF[4] - Math.PI / 4) < 0.02, `x and y faces ${m.areaF[3]}, ${m.areaF[4]}`); assert.ok(Math.abs(m.areaF[5] - 1) < 0.06, `the flat ends keep their area (the conservative rim adds a little): ${m.areaF[5]}`);
  const c2 = run(P, { voxelizer: 'centre' }); assert.ok(c2.model.volVox / c2.model.volMesh < m.volVox / m.volMesh, 'centre sampling keeps the volume closer than the conservative voxelizer');
});

test('cavity: sealed walls enclose air with an effective conductivity above still air; the conduction-only option is lower; a vented box has none', () => {
  const sealed = run(enclosure({ watts: 1 })); assert.ok(sealed.model.nCav > 1000);
  const c = core.cavityState(sealed.model, sealed.settings, sealed.sim.T); assert.ok(c.k > c.kAir * 3 && c.Nu >= 1 && c.kRad > 0, `k ${c.k} against still air ${c.kAir}`);
  const cond = run(enclosure({ watts: 1, ext: { cavity: 'conduction' } })); assert.ok(cond.maxCK > sealed.maxCK + 5, 'with air that only conducts the part is hotter');
  // a box with a 6 x 6 mm hole through the wall: the cavity is connected to the outside and is outside air
  const P = enclosure({ watts: 1 });
  const holed = applyOps(P, [{ op: 'add_feature', feature: { type: 'box', body: 'Case', op: 'cut', size: [8, 8, 8], anchor: 'bottom', pos: [0, 20, 8] } }]).project;
  const h = run(holed); assert.equal(h.model.nCav, 0, 'an opening connects the inside to the outside air'); assert.ok(h.balance < 1e-6);
});

test('parts: components and the board are painted into the grid with their own properties; a heat source with no voxels is reported and ignored', () => {
  const r = run(enclosure({ watts: 1 })), m = r.model, byName = (n) => m.owners.find((o) => o && o.name === n);
  assert.equal(byName('board').kind, 'board'); assert.equal(byName('mcu').kind, 'component'); assert.ok(byName('mcu').cells >= 1); assert.ok(byName('board').cells > 100);
  assert.deepEqual(byName('board').kAxis.slice().sort((a, b) => a - b), [0.29, 20, 20], 'FR-4 through the thickness, copper planes in the plane');
  assert.ok(m.qCell.some((q) => q > 0) && Math.abs(m.Qin - 1) < 1e-12);
  const bad = run(enclosure({ watts: 0, ops: [{ op: 'set_condition', list: 'heat', item: { id: 'h9', target: { body: 'Case' }, power_W: 1 } }] }));
  assert.equal(bad.model.Qin, 1);
  const P = enclosure({ watts: 1 }); P.analysis.conditions.heat[0].target = { pcb: 'ghost' };
  const g = run(P); assert.equal(g.model.Qin, 0); assert.ok(g.model.warnings.some((w) => /has no voxels to go into/.test(w)));
  const slow = run(enclosure({ watts: 1 }), { board_k_inplane: 0.29 }); assert.ok(slow.maxCK > r.maxCK + 20, 'the board model matters a lot: an FR-4-only board spreads no heat');
});

test('transient: time to limit is interpolated, reported only when reached, and starts at zero when the initial temperature is above the limit', () => {
  const P = bar({ heat: 0.05, ext: { mode: 'transient', t_end_s: 400, dt_s: 2, limit_C: 25 } });
  const a = run(P); assert.ok(a.timeToLimit > 0 && a.timeToLimit < 400 && a.peakTK >= 25 + K);
  const never = run(P, { limit_C: 500 }); assert.equal(never.timeToLimit, null);
  const hot = run(P, { initial_C: 90, limit_C: 25 }); assert.equal(hot.timeToLimit, 0);
  assert.ok(Math.abs(run(P, { dt_s: 1 }).timeToLimit - a.timeToLimit) / a.timeToLimit < 0.02, 'halving the step changes the time by under 2 %');
});

// ───────── the package through the real runner and a Worker
async function rig(P) {
  const registry = new ExtRegistry({ store: memoryStore() }), box = { P, build: null, commits: 0 };
  const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true, userStore: { get() {}, set() {} },
    commitOps: async (ops, o) => { const r = applyOps(box.P, ops, { actor: 'ext', extId: o.extId }); box.P = r.project; box.commits++; return { log: r.log }; } });
  const host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host);
  await registry.install(await packDir('examples/ext/thermal-3d'));
  const runner = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  box.P = { ...P, extensions: [{ id: EXT, enabled: true, name: 'Thermal 3D', version: '0.1.0' }] }; box.build = build(box.P, wasm);
  return { registry, host, runner, box, resolver: createResolver({ registry, host, analysis: runner }) };
}

test('package: runs in a Worker with progress, returns an estimate with the grid stated, a voxel field and a surface field, and every warning is honest', async () => {
  const s = await rig(enclosure({ watts: 1 }));
  try {
    const seen = []; const r = await s.runner.run(s.box.P, s.box.build, EXT, 'thermal-3d', { onProgress: (v) => seen.push(v) });
    assert.equal(r.validity, 'estimate', 'the Elmer reference case does not exist yet, so never validated');
    assert.ok(seen.length >= 3 && seen.every((v, i) => i === 0 || v >= seen[i - 1]) && seen[seen.length - 1] > 0.5, `progress ${seen}`);
    const m = Object.fromEntries(r.summary.map((x) => [x.metric, x]));
    for (const k of ['max_temp', 'max_component_temp', 'max_outer_surface_temp', 'total_heat', 'heat_out_convection', 'heat_out_radiation', 'energy_balance_error', 'voxel_size', 'active_voxels', 'voxel_budget', 'solver_iterations', 'solve_seconds']) assert.ok(m[k], k);
    assert.equal(m.max_temp.unit, '°C'); assert.equal(m.max_temp.where.length, 3); assert.equal(m.voxel_size.unit, 'mm'); assert.ok(m.active_voxels.value <= m.voxel_budget.value * 1.2);
    assert.ok(m.max_component_temp.value >= m.max_outer_surface_temp.value && m.max_temp.value >= m.max_component_temp.value - 1e-9);
    assert.ok(m.energy_balance_error.value < 1e-3);
    const vox = r.fields.find((f) => f.kind === 'voxel'); assert.equal(vox.name, 'temperature'); assert.equal(vox.unit, '°C');
    assert.equal(vox.grid.h, m.voxel_size.value); assert.equal(vox.grid.active, m.active_voxels.value); assert.equal(vox.grid.budget, m.voxel_budget.value);
    assert.equal(vox.data.length, vox.grid.dims[0] * vox.grid.dims[1] * vox.grid.dims[2]); assert.ok(vox.data.every(Number.isFinite), 'a closed box fills its bounding box: every cell is solid or enclosed air');
    const surf = r.fields.find((f) => f.kind === 'surface'); assert.equal(surf.body, 'Case'); assert.ok(surf.data.every(Number.isFinite) && Math.max(...surf.data) <= m.max_temp.value + 1e-3 && Math.min(...surf.data) >= 24.9);
    const text = r.warnings.join('\n');
    assert.match(text, /Grid: \d+×\d+×\d+ voxels of [\d.]+ mm, [\d,]+ active \(budget [\d,]+ for draft on the CPU; hard limit 2,000,000 active, 8,000,000 cells\)/);
    assert.match(text, /Solver: CPU \(JS typed arrays\)/); assert.match(text, /Voxelised solid volume is \d+ %/);
    assert.match(text, /Elmer reference|does not exist yet/); assert.match(text, /^Assumption:/m); assert.match(text, /not a flow solution/);
    assert.match(text, /PCB .*FR-4 through the thickness.*in-plane k 20/); assert.match(text, /gap \d+\.\d mm/); assert.ok(!/gap 0\.0 mm/.test(text));
  } finally { s.box.build.dispose(); }
});

test('package: Cancel stops a long run quickly and a settings change is a new cache key', async () => {
  const s = await rig(enclosure({ watts: 1, ext: { quality: 'normal' } }));
  try {
    const ac = new AbortController(); const t0 = Date.now(); let n = 0;
    const p = s.runner.run(s.box.P, s.box.build, EXT, 'thermal-3d', { signal: ac.signal, force: true, onProgress: () => { if (++n === 2) ac.abort(); } });
    await assert.rejects(p, (e) => e.code === 'E_CANCELLED' || /cancel/i.test(e.message)); assert.ok(Date.now() - t0 < 20000, `cancelled after ${Date.now() - t0} ms`);
    const draft = applyOps(s.box.P, [{ op: 'ext_set', id: EXT, key: 'settings', value: { quality: 'draft', backend: 'cpu' } }], { actor: 'user' }).project;
    const a = await s.runner.run(draft, s.box.build, EXT, 'thermal-3d'), again = await s.runner.run(draft, s.box.build, EXT, 'thermal-3d'); assert.equal(again.cached, true);
    const fine = applyOps(draft, [{ op: 'ext_set', id: EXT, key: 'settings', value: { quality: 'draft', backend: 'cpu', h_mm: 1.4 } }], { actor: 'user' }).project;
    const b = await s.runner.run(fine, s.box.build, EXT, 'thermal-3d'); assert.equal(b.cached, false, 'a different setting is a different inputsHash');
    assert.notEqual(a.inputsHash, b.inputsHash); assert.notEqual(a.summary.find((x) => x.metric === 'voxel_size').value, b.summary.find((x) => x.metric === 'voxel_size').value);
  } finally { s.box.build.dispose(); }
});

test('package: a transient run reports time to limit; checks join the scorecard as hard rows with editable limits; the AI tool validates and stores settings', async () => {
  const s = await rig(enclosure({ watts: 3, ext: { mode: 'transient', t_end_s: 600, dt_s: 10, limit_C: 40 } }));
  try {
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'thermal-3d'), m = Object.fromEntries(r.summary.map((x) => [x.metric, x]));
    assert.equal(m.limit_reached.value, 1); assert.ok(m.time_to_limit.value > 0 && m.time_to_limit.value < 600); assert.equal(m.run_time.value, 600); assert.ok(m.max_temp.value >= m.end_max_temp.value - 1e-9);
    assert.ok(r.warnings.some((w) => /implicit-Euler steps of 10\.000 s to 600 s/.test(w)));
    const rows = await s.runner.rows(s.box.P, s.box.build, { run: false }), hot = rows.find((x) => x.id.endsWith('max-temp'));
    assert.equal(hot.severity, 'hard'); assert.match(hot.detail, /estimate/);
    const tight = applyOps(s.box.P, [{ op: 'set_limit', key: `${EXT}:thermal-3d:max-temp`, value: 26 }]).project;
    const r2 = (await s.runner.rows(tight, s.box.build, { run: false })).find((x) => x.id.endsWith('max-temp')); assert.equal(r2.pass, false);
    assert.deepEqual(s.resolver.agentTools(s.box.P).map((t) => t.id), ['set-thermal-3d']);
    await assert.rejects(s.resolver.callAgentTool(s.box.P, EXT, 'set-thermal-3d', { quality: 'ultra' }), /quality/);
    const out = await s.resolver.callAgentTool(s.box.P, EXT, 'set-thermal-3d', { quality: 'fine', fixed: [{ face: '-z', T_C: 40 }] });
    assert.equal(out.effective.quality, 'fine'); assert.equal(s.box.P.ext[EXT].settings.fixed[0].T_C, 40); assert.equal(s.box.P.ext[EXT].settings.mode, 'transient', 'only the keys given change');
  } finally { s.box.build.dispose(); }
});

// ───────── the validation harness: new case kinds
const slabCase = (extra) => ({ id: 'ref', kind: 'reference', reference: 'test', referenceFile: 'x', setup: { features: [{ type: 'box', body: 'Bar', size: [10, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [mat('tk', 2)], customAssign: { Bar: 'tk' }, conditions: { ambient: { temperature_K: 293.15, gravity: '-z' }, heat: [{ id: 'q', target: { body: 'Bar' }, power_W: 0.05 }], mounts: [] }, ext: { [EXT]: { settings: { quality: 'draft', backend: 'cpu', convection: 'fixed', h_W_m2K: 10, radiation: false, h_mm: 1 } } } }, expect: [{ metric: 'max_temp', unit: '°C', refKey: 'max_temp_C', tol: { rel: 0.01 } }], ...extra });
test('harness: a reference case without its data file is SKIPPED, is not a pass, does not fail the run, and blocks a "validated" claim; with the file it is compared', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a2-ref-')), file = (name, o) => { const f = path.join(dir, name); fs.writeFileSync(f, JSON.stringify(o)); return f; };
  const mk = (cases) => [{ name: 't.json', data: { analysis: `${EXT}/thermal-3d`, package: 'examples/ext/thermal-3d', cases } }];
  assert.deepEqual(lintCaseFile(mk([slabCase({ referenceFile: undefined })])[0].data), ['ref: a reference case names its referenceFile']);
  assert.ok(lintCaseFile(mk([slabCase({ expect: [{ metric: 'max_temp', tol: { rel: 0.01 } }] })])[0].data).some((p) => /names its refKey/.test(p)));
  const missing = await runValidation({ caseFiles: mk([slabCase({ referenceFile: path.join(dir, 'nope.json') })]), wasm });
  assert.equal(missing.rows.length, 1); assert.equal(missing.rows[0].skipped, true); assert.equal(missing.rows[0].pass, false, 'a skipped case is not a pass'); assert.equal(missing.pass, true, 'but it does not fail the run');
  assert.deepEqual(missing.summary.map((x) => [x.cases, x.passed]), [[1, 0]]); assert.match(renderTable(missing), /SKIPPED \(not a pass\).*needs runner/); assert.match(renderTable(missing), /1 case\(s\) SKIPPED/);
  // the same case with the data file: first the number this solver gives (so the loader is what is under test), then a wrong one
  const truth = run(applyOps(migrate(emptyProject()), []).project && (() => { const c = slabCase(); const P = applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: c.setup.features[0] }, { op: 'set_material', material: c.setup.customMaterials[0] }, { op: 'set_body_material', body: 'Bar', material: 'tk' }, { op: 'set_ambient', ambient: c.setup.conditions.ambient }, { op: 'set_condition', list: 'heat', item: c.setup.conditions.heat[0] }]).project; P.ext = c.setup.ext; return P; })()).maxTK - K;
  const ok = await runValidation({ caseFiles: mk([slabCase({ referenceFile: file('ok.json', { source: { tool: 'test' }, values: { max_temp_C: truth } }) })]), wasm });
  assert.equal(ok.rows.length, 1); assert.equal(ok.rows[0].pass, true); assert.equal(ok.pass, true); assert.deepEqual(ok.summary.map((x) => [x.cases, x.passed]), [[1, 1]]);
  const bad = await runValidation({ caseFiles: mk([slabCase({ referenceFile: file('bad.json', { source: { tool: 'test' }, values: { max_temp_C: truth + 10 } }) })]), wasm });
  assert.equal(bad.rows[0].pass, false); assert.equal(bad.pass, false, 'a missed reference tolerance fails the run');
  const broken = await runValidation({ caseFiles: mk([slabCase({ referenceFile: file('broken.json', { values: {} }) })]), wasm }); assert.equal(broken.pass, false);
  fs.rmSync(dir, { recursive: true });
});

test('harness: the committed case file is well formed, every case has a reference and a tolerance, and the Elmer case is pending, not passing', () => {
  const data = JSON.parse(fs.readFileSync('analysis-validation/cases/thermal-3d.json', 'utf8'));
  assert.deepEqual(lintCaseFile(data), []);
  const ids = data.cases.map((c) => c.id);
  for (const id of ['slab-1d-convection', 'slab-generation-convection', 'cylinder-generation', 'fin-efficiency', 'transient-lumped', 'transient-slab', 'energy-balance-steady', 'energy-balance-transient', 'enclosure-1-small-abs', 'enclosure-2-handheld-pc', 'enclosure-3-aluminium-rail', 'enclosure-vs-elmer-fine']) assert.ok(ids.includes(id), id);
  const el = data.cases.find((c) => c.id === 'enclosure-vs-elmer-fine'); assert.equal(el.kind, 'reference'); assert.ok(!fs.existsSync(el.referenceFile), 'the reference file is produced by the Alloy Runner (A4) and does not exist yet');
  for (const id of ['enclosure-1-small-abs', 'enclosure-2-handheld-pc', 'enclosure-3-aluminium-rail']) { const c = data.cases.find((x) => x.id === id); assert.equal(c.kind, 'compare'); assert.deepEqual(c.tol, { rel: 0.15 }); assert.equal(c.b.analysis, `${A1}/thermal-estimate`); }
});
