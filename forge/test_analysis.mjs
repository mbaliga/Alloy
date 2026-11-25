// Headless tests for the analysis framework (A0): materials, conditions and their ops, result model and cache, overlay maths,
// the runner on a real package Worker (progress, cancel, cache), check gating by validity, the loop's analysis budget, the report,
// and the validation harness itself (it must fail a wrong expectation). Run with `npm test`.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { wizardProject, applyOps, migrate, emptyProject, defaultFeature } from './src/model.js';
import { builtinMaterials, validateMaterial, allMaterials, materialOf, SOURCES, PROPS, DEFAULT_MATERIAL } from './src/analysis/materials.js';
import { defaultConditions, validateConditions, units, G0 } from './src/analysis/conditions.js';
import { resolveSetup, prepareInput } from './src/analysis/setup.js';
import { inputsHash, validateResult, finalizeResult, compare } from './src/analysis/result.js';
import { createResultCache, memoryStore as memResults } from './src/analysis/cache.js';
import { createAnalysisRunner } from './src/analysis/runner.js';
import { buildReport } from './src/analysis/report.js';
import { fieldRange, interpolateSurface, sampleVoxel, sliceImage } from './src/ext/overlay.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { ExtHost } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { createResolver } from './src/ext/resolve.js';
import { packDir } from './tools/pack-ext.mjs';
import { runDesignLoop } from './src/design-loop.js';
import { compareValue, lintCaseFile, runValidation, loadCaseFiles } from './analysis-validation/harness.mjs';

let wasm; const HOSTS = [];
before(async () => { wasm = await Module(); wasm.setup(); });
after(() => HOSTS.forEach((h) => h.stopAll()));
const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };
const MASS = 'xyz.mdhv.sample-mass';

const cube = (name = 'Cube', sz = 20) => applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: { type: 'box', body: name, size: [sz, sz, sz], anchor: 'center' } }]).project;
async function rig(P0) {
  const registry = new ExtRegistry({ store: memoryStore() });
  const box = { P: P0, build: null };
  const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true });
  const host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host);
  await registry.install(await packDir('examples/ext/sample-mass'));
  const cache = createResultCache({ store: memResults() });
  const runner = createAnalysisRunner({ registry, host, cache });
  const P = JSON.parse(JSON.stringify(P0)); P.extensions = [{ id: MASS, enabled: true, name: 'Sample: mass properties', version: '0.1.0' }];
  box.P = P; box.build = build(P, wasm);
  return { registry, host, runner, cache, P, B: box.build, box };
}

// ───────── materials
test('materials: the built-in file validates, every value has a quality and sources that exist', () => {
  const ms = builtinMaterials(); assert.ok(ms.length >= 10);
  for (const m of ms) {
    validateMaterial(m);
    for (const p of PROPS) {
      const v = m.properties[p.k]; assert.ok(v, `${m.id}.${p.k}`);
      assert.ok(['datasheet', 'handbook', 'estimate', 'measured', 'n/a'].includes(v.quality), `${m.id}.${p.k} quality`);
      if (v.value != null) { assert.ok(v.value >= p.min && v.value <= p.max, `${m.id}.${p.k} in bounds`); if (v.quality !== 'n/a') assert.ok(v.sources.length, `${m.id}.${p.k} has a source`); for (const s of v.sources) assert.ok(SOURCES[s], `${m.id}.${p.k}: unknown source ${s}`); }
    }
  }
  for (const [id, s] of Object.entries(SOURCES)) assert.ok(s.citation, id);
  assert.equal(DEFAULT_MATERIAL, 'pla');
});
test('materials: bad custom materials are refused', () => {
  const ok = { id: 'mine', name: 'Mine', class: 'custom', notes: '', properties: Object.fromEntries(PROPS.map((p) => [p.k, { value: null, quality: 'n/a', sources: [] }])) };
  validateMaterial(ok);
  assert.throws(() => validateMaterial({ ...ok, id: 'Bad Id' }), /id/);
  const neg = JSON.parse(JSON.stringify(ok)); neg.properties.rho = { value: -5, quality: 'measured', sources: ['user'] };
  assert.throws(() => validateMaterial(neg), /rho/);
});

// ───────── ops, locks, conditions
test('ops: material assignment, custom materials, locks, and refusal when in use', () => {
  const P = cube();
  const a = applyOps(P, [{ op: 'set_body_material', body: 'Cube', material: 'petg' }], { actor: 'user' }).project;
  assert.equal(a.bodies.Cube.material, 'petg'); assert.equal(materialOf(a, 'Cube').assumed, false);
  assert.equal(materialOf(P, 'Cube').assumed, true); assert.equal(materialOf(P, 'Cube').material.id, 'pla');
  assert.throws(() => applyOps(P, [{ op: 'set_body_material', body: 'Nope', material: 'pla' }]), /no body/);
  assert.throws(() => applyOps(P, [{ op: 'set_body_material', body: 'Cube', material: 'unobtainium' }]), /no material/);
  const mat = { id: 'resin', name: 'Resin', class: 'custom', notes: '', properties: Object.fromEntries(PROPS.map((p) => [p.k, p.k === 'rho' ? { value: 1180, quality: 'measured', sources: ['user'] } : { value: null, quality: 'n/a', sources: [] }])) };
  const b = applyOps(a, [{ op: 'set_material', material: mat }, { op: 'set_body_material', body: 'Cube', material: 'resin' }]).project;
  assert.ok(allMaterials(b).has('resin'));
  assert.throws(() => applyOps(b, [{ op: 'remove_material', id: 'resin' }]), /used by Cube/);
  assert.throws(() => applyOps(b, [{ op: 'set_material', material: { ...mat, id: 'pla' } }]), /built-in/);
  assert.throws(() => applyOps(b, [{ op: 'remove_material', id: 'pla' }]), /built-in/);
  const L = JSON.parse(JSON.stringify(a)); L.bodies.Cube.locked = true;
  assert.throws(() => applyOps(L, [{ op: 'set_body_material', body: 'Cube', material: 'abs' }]), /lock/i);
  assert.equal(L.bodies.Cube.material, 'petg', 'a rejected op changes nothing');
  assert.throws(() => applyOps(P, [{ op: 'set_material', material: mat }], { actor: 'ext', extId: 'xyz.t.a' }) && 0 || applyOps(P, [{ op: 'set_extension', id: 'x', enabled: true }], { actor: 'ext', extId: 'xyz.t.a' }), /only the user/);
});
test('conditions: SI inside, unit helpers round trip, invalid values refused through the ops', () => {
  assert.ok(Math.abs(units.kToC(units.cToK(25)) - 25) < 1e-9); assert.ok(Math.abs(units.ms2ToG(units.gToMs2(3)) - 3) < 1e-12); assert.equal(units.gToMs2(1), G0);
  const P = cube();
  const d = defaultConditions(); assert.ok(d.ambient.temperature_K > 273 && d.ambient.temperature_K < 320);
  const a = applyOps(applyOps(P, [{ op: 'set_body_material', body: 'Cube', material: 'pla' }]).project, [{ op: 'set_ambient', ambient: { temperature_K: units.cToK(40) } }, { op: 'set_condition', list: 'heat', item: { id: 'h1', target: { body: 'Cube' }, power_W: 3 } }, { op: 'set_condition', list: 'loads', item: { id: 'l1', kind: 'acceleration', accel_ms2: 500 * G0, direction: '-z' } }]).project;
  assert.ok(Math.abs(a.analysis.conditions.ambient.temperature_K - 313.15) < 1e-9);
  assert.equal(a.analysis.conditions.heat.length, 1);
  assert.throws(() => applyOps(a, [{ op: 'set_ambient', ambient: { temperature_K: -5 } }]), /temperature/i);
  assert.throws(() => applyOps(a, [{ op: 'set_condition', list: 'heat', item: { id: 'h2', target: { body: 'Ghost' }, power_W: 1 } }]), /Ghost|body/);
  assert.throws(() => applyOps(a, [{ op: 'set_condition', list: 'heat', item: { id: 'h3', target: { body: 'Cube' }, power_W: -1 } }]), /power/i);
  assert.throws(() => applyOps(a, [{ op: 'remove_condition', list: 'heat', id: 'nope' }]), /no heat/);
  assert.equal(applyOps(a, [{ op: 'remove_condition', list: 'heat', id: 'h1' }]).project.analysis.conditions.heat.length, 0);
  assert.equal(applyOps(a, [{ op: 'set_limit', key: 'x:y:z', value: 7 }]).project.analysis.conditions.limits['x:y:z'], 7);
  assert.deepEqual(validateConditions(d, P), validateConditions(d, P));
});
test('setup: an unassigned body says PLA is assumed; assigned does not', async () => {
  const P = cube(); const B = build(P, wasm);
  try {
    const s = resolveSetup(P, B); assert.ok(s.warnings.some((w) => /no material.*PLA is assumed/.test(w)));
    const Q = applyOps(P, [{ op: 'set_body_material', body: 'Cube', material: 'abs' }]).project; const s2 = resolveSetup(Q, B);
    assert.equal(s2.warnings.length, 0); assert.equal(s2.bodies[0].material.props.rho, 1040);
  } finally { B.dispose(); }
});

// ───────── result model, hash, cache
test('result: validation rejects malformed results; validity defaults to experimental; hash is deterministic', async () => {
  const good = { summary: [{ metric: 'a', value: 1, unit: 'mm' }], fields: [], warnings: [] };
  const r = finalizeResult(good, { id: 'x/y', version: '1.0.0', inputsHash: 'h' }); assert.equal(r.validity, 'experimental'); validateResult(r, {});
  assert.throws(() => validateResult({ ...r, summary: [{ metric: 'a', value: NaN, unit: 'mm' }] }, {}), /finite|value/);
  assert.throws(() => validateResult({ ...r, validity: 'great' }, {}), /validity/);
  assert.throws(() => validateResult({ ...r, fields: [{ name: 'f', kind: 'surface', unit: 'K', body: 'B', data: new Float32Array(3) }] }, { B: 5 }), /vertex|value/);
  const a = await inputsHash({ ext: 'e', version: '1', analysis: 'a', setup: { x: 1, y: [2] }, bodies: [] }), b = await inputsHash({ ext: 'e', version: '1', analysis: 'a', setup: { y: [2], x: 1 }, bodies: [] });
  assert.equal(a, b, 'key order does not matter');
  assert.notEqual(a, await inputsHash({ ext: 'e', version: '2', analysis: 'a', setup: { x: 1, y: [2] }, bodies: [] }));
  assert.equal(compare(5, '<=', 5), true); assert.equal(compare(5.1, '<=', 5), false); assert.equal(compare(1, '>', 1), false);
});
test('cache: LRU by entries and bytes', async () => {
  const c = createResultCache({ store: memResults(), maxEntries: 2 }); const r = (n) => ({ fields: [{ data: new Float32Array(n) }] });
  await c.put('a', r(1)); await new Promise((x) => setTimeout(x, 3)); await c.put('b', r(1)); await new Promise((x) => setTimeout(x, 3)); await c.put('c', r(1));
  assert.equal(await c.get('a'), null); assert.ok(await c.get('c'));
});

// ───────── overlay maths
test('overlay maths: field range, barycentric surface probe, voxel sample, slice image', () => {
  assert.deepEqual(fieldRange(new Float32Array([3, 1, 2, NaN])), [1, 3]);
  const pos = new Float32Array([0, 0, 0, 10, 0, 0, 0, 10, 0]), idx = new Uint32Array([0, 1, 2]);
  assert.ok(Math.abs(interpolateSurface(pos, idx, new Float32Array([0, 10, 20]), 0, [5, 0, 0]) - 5) < 1e-6);
  assert.ok(Math.abs(interpolateSurface(pos, idx, new Float32Array([0, 10, 20]), 0, [0, 5, 0]) - 10) < 1e-6);
  const grid = { origin: [0, 0, 0], h: 1, dims: [4, 4, 4] }, data = new Float32Array(64).map((_, i) => i);
  const field = { data, grid };
  assert.equal(sampleVoxel(field, 0.5, 0.5, 0.5), 0); assert.equal(sampleVoxel(field, 3.5, 0.5, 0.5), 3); assert.equal(sampleVoxel(field, 9, 9, 9), null);
  const img = sliceImage(field, 'z', 1); assert.ok(img.w === 4 && img.h === 4 && img.rgba.length === 64);
});

// ───────── the runner on a real Worker
test('runner: run, validate, cache, progress, cancel', async () => {
  const { runner, P, B } = await rig(applyOps(cube(), [{ op: 'set_body_material', body: 'Cube', material: 'pla' }]).project);
  try {
    const P2 = JSON.parse(JSON.stringify(P)); P2.bodies = { Cube: { material: 'pla' } };
    const prog = []; const r = await runner.run(P2, B, MASS, 'mass-properties', { onProgress: (v) => prog.push(v) });
    assert.equal(r.validity, 'validated'); assert.equal(r.cached, false);
    const m = Object.fromEntries(r.summary.map((s) => [s.metric, s.value]));
    assert.ok(Math.abs(m.total_mass - 9.92) < 1e-6, `mass ${m.total_mass} g`);
    assert.ok(prog.length >= 1 && prog.every((v) => v >= 0 && v <= 1));
    const again = await runner.run(P2, B, MASS, 'mass-properties'); assert.equal(again.cached, true);
    const forced = await runner.run(P2, B, MASS, 'mass-properties', { force: true }); assert.equal(forced.cached, false);
    // a different material is a different key, so no stale hit
    const P3 = applyOps(P2, [{ op: 'set_body_material', body: 'Cube', material: 'al6061' }]).project; P3.extensions = P2.extensions;
    const r3 = await runner.run(P3, B, MASS, 'mass-properties'); assert.equal(r3.cached, false); assert.ok(r3.summary.find((s) => s.metric === 'total_mass').value > m.total_mass * 2);
    // cancellation
    const ac = new AbortController(); const big = runner.run(P2, B, MASS, 'section-density', { signal: ac.signal, force: true }); ac.abort();
    await assert.rejects(big, (e) => /cancel/i.test(e.code + e.message));
    // switched off or unknown
    await assert.rejects(runner.run({ ...P2, extensions: [] }, B, MASS, 'mass-properties'), /not installed or is switched off/);
    await assert.rejects(runner.run(P2, B, MASS, 'nope'), /not installed|switched off/);
  } finally { B.dispose(); }
});

// ───────── check gating
test('checks: validated rows are hard, experimental rows advisory; limits come from the project; not-run is advisory and says so', async () => {
  const { runner, P, B } = await rig(cube());
  try {
    const before = await runner.rows(P, B, { run: false });
    assert.ok(before.length === 2 && before.every((r) => r.severity === 'advisory' && !r.pass && /not run yet/.test(r.detail)), 'a missing result is never a silent pass');
    const rows = await runner.rows(P, B, { run: true, budget: { left: 60 } });
    const mass = rows.find((r) => r.id === `${MASS}:mass-properties:total-mass`), dens = rows.find((r) => r.id === `${MASS}:section-density:max-density`);
    assert.equal(mass.severity, 'hard'); assert.equal(mass.pass, true); assert.match(mass.detail, /validated/);
    assert.equal(dens.severity, 'advisory'); assert.equal(dens.pass, false); assert.match(dens.detail, /experimental/);
    const tight = applyOps(P, [{ op: 'set_limit', key: `${MASS}:mass-properties:total-mass`, value: 1 }]).project;
    const r2 = (await runner.rows(tight, B, { run: false })).find((r) => r.id.endsWith('total-mass'));
    assert.equal(r2.pass, false); assert.equal(r2.severity, 'hard');
    const none = await runner.rows(P, B, { run: true, budget: { left: 0 } }); assert.ok(none.length === 2);
    const fresh = await rig(cube()); try {
      const skipped = await fresh.runner.rows(fresh.P, fresh.B, { run: true, budget: { left: 0 } });
      assert.ok(skipped.every((r) => r.severity === 'advisory' && /budget is used up/.test(r.detail)));
    } finally { fresh.B.dispose(); }
  } finally { B.dispose(); }
});

// ───────── the design loop: summaries to the critic, budget
test('loop: analysis rows join the checks, the critic sees summary rows only, the budget is reported', async () => {
  const s = await rig(cube()); s.B.dispose();
  const resolver = createResolver({ registry: s.registry, host: s.host, analysis: s.runner });
  const P = wizardProject(); P.extensions = [{ id: MASS, enabled: true, name: 'Sample: mass properties', version: '0.1.0' }];
  s.box.P = P; // the Worker reads the open document
  const model = async (cfg, messages, signal, system) => (/Score the design/.test(system) ? JSON.stringify({ score: 8, notes: 'fine' }) : JSON.stringify({ explanation: 'no change', ops: [] }));
  const r = await runDesignLoop({ project: P, wasm, goal: 'a case', cfg: {}, callModel: model, ext: resolver, analysisSeconds: 30, approve: () => ({ decision: 'approved' }) }).promise;
  const rows = r.rounds[0].checks.filter((c) => c.id.startsWith(MASS));
  assert.ok(rows.length >= 1, 'analysis rows are in the round checks');
  const an = r.rounds[0].metrics.analysis; assert.ok(an && an.length, 'the round metrics (what the critic sees) carry analysis summaries');
  assert.ok(an.every((x) => x.summary && !x.fields), 'summary rows only, never fields');
  assert.ok(typeof r.usage.analysisSeconds === 'number'); assert.equal(r.usage.limits.analysisSeconds, 30);
});

// ───────── report
test('report: markdown and JSON carry validity, assumptions, units and sources', async () => {
  const { runner, P, B } = await rig(cube());
  try {
    const res = await runner.run(P, B, MASS, 'mass-properties'); const rows = await runner.rows(P, B, { run: false });
    const { md, json } = buildReport({ result: res, setup: resolveSetup(P, B), project: P, analysis: runner.declared(P)[0].item, rows });
    assert.match(md, /validated/); assert.match(md, /PLA is assumed/); assert.match(md, /total_mass/); assert.match(md, /Sources|sources/);
    assert.equal(json.analysis.validity, 'validated'); assert.ok(json.setup); JSON.stringify(json);
  } finally { B.dispose(); }
});

// ───────── the validation harness is itself tested
test('harness: tolerance rules, lint, a good run passes and a wrong expectation FAILS', async () => {
  assert.equal(compareValue(100, 100.4, { rel: 0.005 }).pass, true); assert.equal(compareValue(100, 101, { rel: 0.005 }).pass, false); assert.equal(compareValue(1, 1.2, { abs: 0.1 }).pass, false);
  assert.throws(() => compareValue(1, 1, { rel: 0.1, abs: 0.1 }), /exactly one/); assert.throws(() => compareValue(1, 1, {}), /exactly one/); assert.throws(() => compareValue(1, 1, { rel: -1 }), /exactly one|non-negative/);
  assert.ok(lintCaseFile({ analysis: 'a/b', package: 'p', cases: [{ id: 'c', expect: [{ metric: 'm', value: 1, tol: { rel: 0.1 } }] }] }).some((p) => /reference/.test(p)), 'a case without a reference is refused');
  assert.ok(lintCaseFile({ analysis: 'a/b', package: 'p', cases: [{ id: 'c', reference: 'r', expect: [{ metric: 'm', value: 1, tol: {} }] }] }).length);
  const files = loadCaseFiles('mass-properties');
  const ok = await runValidation({ caseFiles: files, wasm }); assert.equal(ok.pass, true, JSON.stringify(ok.rows.filter((r) => !r.pass)));
  assert.ok(ok.rows.length >= 20);
  const bad = JSON.parse(JSON.stringify(files)); bad[0].data.cases[0].expect[0].value *= 1.5;
  const miss = await runValidation({ caseFiles: bad, wasm }); assert.equal(miss.pass, false, 'a wrong expectation fails the run');
  const exp = loadCaseFiles('section-density'); const bad2 = JSON.parse(JSON.stringify(files)); bad2[0].data.cases[0].expect[0].value *= 2;
  assert.equal((await runValidation({ caseFiles: bad2, wasm })).summary.length, 1);
  assert.ok(exp.length === 1);
});
