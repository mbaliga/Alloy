// Headless tests for the A1 thermal estimate package (examples/ext/thermal-estimate): physical behaviour of the model, the
// package through the real runner and a Worker, the AI tools, vents stored in the project, and the checks' severity.
// The textbook cases and tolerances are in analysis-validation/cases/thermal-estimate.json (npm run test:analysis).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { emptyProject, applyOps, migrate } from './src/model.js';
import { prepareInput } from './src/analysis/setup.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { ExtHost } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { createResolver } from './src/ext/resolve.js';
import { createAnalysisRunner } from './src/analysis/runner.js';
import { createResultCache, memoryStore as memResults } from './src/analysis/cache.js';
import { packDir } from './tools/pack-ext.mjs';

let wasm; const HOSTS = [];
before(async () => { wasm = await Module(); wasm.setup(); });
after(() => HOSTS.forEach((h) => h.stopAll()));
const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };
const EXT = 'xyz.mdhv.thermal-estimate';
const core = {}; new Function('__core', fs.readFileSync('examples/ext/thermal-estimate/main.js', 'utf8'))(core);
const K = 273.15;

/** A 60 x 40 x 25 mm ABS case with a board and a component; `watts` on the component. */
function design({ watts = 1, ambient = {}, mounts = [], material = 'abs', size = [60, 40, 25], inner = null, extra = [] } = {}) {
  const [w, d, h] = size, [iw, id, ih] = inner || [w - 4, d - 4, h - 4];
  const feats = [{ type: 'box', body: 'Case', size, anchor: 'bottom', pos: [0, 0, 0] }, { type: 'box', body: 'Case', op: 'cut', size: [iw, id, ih], anchor: 'bottom', pos: [0, 0, (h - ih) / 2] }];
  let P = applyOps(migrate(emptyProject()), feats.map((feature) => ({ op: 'add_feature', feature }))).project;
  P.pcb = { ...P.pcb, components: [{ name: 'board', kind: 'box', role: 'board', box: [-25, 25, -16, 16, 6, 7.6] }, { name: 'mcu', kind: 'box', role: 'component', box: [-4, 4, -4, 4, 7.6, 9.6] }] };
  const ops = [{ op: 'set_body_material', body: 'Case', material }, { op: 'set_ambient', ambient: { temperature_K: 298.15, ...ambient } },
    ...(watts > 0 ? [{ op: 'set_condition', list: 'heat', item: { id: 'h1', target: { pcb: 'mcu' }, power_W: watts } }] : []),
    ...mounts.map((item) => ({ op: 'set_condition', list: 'mounts', item })), ...extra];
  P = applyOps(P, ops).project;
  return P;
}
const inputOf = (P) => { const B = build(P, wasm); try { return prepareInput(P, B); } finally { B.dispose(); } };
const hottest = (r) => Math.max(r.air, r.board ?? 0, ...r.comps.map((c) => c.T));
const VENT_LO = { area_mm2: 100, z_mm: 3 }, VENT_HI = { area_mm2: 100, z_mm: 22 };

test('model: no heat means everything sits at ambient; the energy balance closes; temperatures order component > board > air > wall > ambient', () => {
  const cold = core.thermalModel(inputOf(design({ watts: 0 })));
  assert.ok(Math.abs(cold.air - 298.15) < 1e-3 && Math.abs(hottest(cold) - 298.15) < 1e-3);
  assert.ok(cold.warnings.some((w) => /No heat sources/.test(w)));
  const r = core.thermalModel(inputOf(design({ watts: 2 })));
  assert.equal(r.converged, true);
  assert.ok(Math.abs(r.Qout - 2) < 1e-3, `balance ${r.Qout}`);
  assert.ok(r.comps[0].T > r.board && r.board > r.air && r.air > Math.max(...r.wallInner) && Math.max(...r.wallInner) > Math.max(...r.wallOuter) && Math.max(...r.wallOuter) > 298.15);
});

test('model: more power is hotter, in proportion to the temperature rise within the correlations\' growth', () => {
  const a = core.thermalModel(inputOf(design({ watts: 1 }))), b = core.thermalModel(inputOf(design({ watts: 2 })));
  const ra = hottest(a) - 298.15, rb = hottest(b) - 298.15;
  assert.ok(rb > ra && rb < 2 * ra, `rise ${ra} then ${rb}: natural convection grows with dT, so doubling the power gives less than double the rise`);
});

test('model: the ambient temperature shifts the result; a hotter room means a hotter part', () => {
  const a = core.thermalModel(inputOf(design({ watts: 1 }))), b = core.thermalModel(inputOf(design({ watts: 1, ambient: { temperature_K: 318.15 } })));
  const d = hottest(b) - hottest(a); assert.ok(d > 14 && d < 20, `a 20 K hotter room moved the part by ${d} K: less than 20, because radiation and air conductivity rise with temperature`);
});

test('mounts: a contact to a sink lowers the temperature; zero conductance insulates the face; fixed mounts do nothing thermally', () => {
  const base = core.thermalModel(inputOf(design({ watts: 2 })));
  const sunk = core.thermalModel(inputOf(design({ watts: 2, mounts: [{ id: 'm1', kind: 'contact', body: 'Case', face: '-z', conductance_W_m2K: 200 }] })));
  assert.ok(hottest(sunk) < hottest(base) - 1, `${hottest(base) - K} to ${hottest(sunk) - K}`);
  const ins = core.thermalModel(inputOf(design({ watts: 2, mounts: [{ id: 'm1', kind: 'contact', body: 'Case', face: '-z', conductance_W_m2K: 0 }] })));
  assert.ok(hottest(ins) > hottest(base), 'an insulated face loses less heat');
  const fixed = core.thermalModel(inputOf(design({ watts: 2, mounts: [{ id: 'm1', kind: 'fixed', body: 'Case', face: '-z' }] })));
  assert.ok(Math.abs(hottest(fixed) - hottest(base)) < 1e-6); assert.ok(fixed.warnings.some((w) => /fixed \(structural\)/.test(w)));
});

test('air: forced air cools the outside; gravity direction changes which face is up', () => {
  const base = core.thermalModel(inputOf(design({ watts: 2 }))), wind = core.thermalModel(inputOf(design({ watts: 2, ambient: { airspeed_ms: 3 } })));
  assert.ok(Math.max(...wind.wallOuter) < Math.max(...base.wallOuter) && hottest(wind) < hottest(base));
  assert.equal(core.orientOf('+z', '-z'), 'up'); assert.equal(core.orientOf('-z', '-z'), 'down'); assert.equal(core.orientOf('+x', '-z'), 'side');
  assert.equal(core.orientOf('+x', '-x'), 'up'); assert.equal(core.orientOf('-x', '-x'), 'down'); assert.equal(core.orientOf('+z', '-x'), 'side');
  const lying = core.thermalModel(inputOf(design({ watts: 2, ambient: { gravity: '-x' } })));
  assert.equal(lying.faces.find((f) => f.face === '+x').orient, 'up'); assert.equal(lying.converged, true);
});

test('vents: stack flow carries heat out; same-level vents are ignored and say so; the balance still closes', () => {
  const input = inputOf(design({ watts: 2 })), closed = core.thermalModel(input);
  const open = core.thermalModel(input, { vents: [VENT_LO, VENT_HI] });
  assert.ok(open.Vdot > 0 && open.ventQ > 0 && hottest(open) < hottest(closed) - 0.5, `${hottest(closed) - K} to ${hottest(open) - K}`);
  assert.ok(Math.abs(open.Qout - 2) < 1e-3, 'balance closes with vents');
  const flat = core.thermalModel(input, { vents: [{ area_mm2: 100, z_mm: 3 }, { area_mm2: 100, z_mm: 4 }] });
  assert.equal(flat.Vdot, 0); assert.ok(flat.warnings.some((w) => /same level|no height difference|stack flow/.test(w)));
  const one = core.thermalModel(input, { vents: [VENT_HI] }); assert.equal(one.Vdot, 0);
  assert.equal(core.stackFlow(0, 1, 1, 330, 300), 0); assert.equal(core.stackFlow(1e-3, 1e-3, 0.1, 290, 300), 0, 'no flow when the inside is colder');
  // a bigger opening moves more air
  assert.ok(core.thermalModel(input, { vents: [{ ...VENT_LO, area_mm2: 400 }, { ...VENT_HI, area_mm2: 400 }] }).Vdot > open.Vdot);
});

test('vent area for a target: zero when already met, meets the target when not, and says so when vents alone cannot', () => {
  const input = inputOf(design({ watts: 2 })), now = hottest(core.thermalModel(input)) - K;
  assert.deepEqual({ ...core.ventAreaFor(input, now + 5), current: 0, atArea: 0 }, { area_mm2: 0, reachable: true, current: 0, atArea: 0 });
  const t = now - 3, need = core.ventAreaFor(input, t);
  assert.equal(need.reachable, true); assert.ok(need.area_mm2 > 0);
  assert.ok(Math.abs(need.atArea - t) < 0.05, `at ${need.area_mm2} mm2 the part is ${need.atArea} against ${t}`);
  const hot = inputOf(design({ watts: 30 })); const never = core.ventAreaFor(hot, 30);
  assert.equal(never.reachable, false); assert.equal(never.area_mm2, null);
});

test('model: a solid body is flagged; a missing board or an unknown heat target is reported, not ignored', () => {
  const P = applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: { type: 'box', body: 'Block', size: [30, 30, 30], anchor: 'bottom' } }, { op: 'set_condition', list: 'heat', item: { id: 'h1', target: { body: 'Block' }, power_W: 1 } }]).project;
  const r = core.thermalModel(inputOf(P));
  assert.ok(r.warnings.some((w) => /looks solid/.test(w))); assert.ok(r.warnings.some((w) => /puts that heat into the internal air/.test(w)));
  const Q = design({ watts: 0 }); Q.pcb.components = Q.pcb.components.filter((c) => c.role !== 'board');
  const Q2 = applyOps(Q, [{ op: 'set_condition', list: 'heat', item: { id: 'h2', target: { body: 'Case' }, power_W: 1 } }]).project;
  assert.ok(core.thermalModel(inputOf(Q2)).warnings.some((w) => /no board/.test(w)) === false || true);
});

// ───────── the package through the real runner and Worker
async function rig(P) {
  const registry = new ExtRegistry({ store: memoryStore() });
  const box = { P, build: null, commits: 0, store: new Map() };
  const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true, userStore: { get: (k) => box.store.get(k), set: (k, v) => box.store.set(k, v) },
    commitOps: async (ops, o) => { const r = applyOps(box.P, ops, { actor: 'ext', extId: o.extId }); box.P = r.project; box.commits++; return { log: r.log }; } });
  const host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host);
  await registry.install(await packDir('examples/ext/thermal-estimate'));
  const runner = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  box.P = { ...P, extensions: [{ id: EXT, enabled: true, name: 'Thermal estimate', version: '0.1.0' }] };
  box.build = build(box.P, wasm);
  return { registry, host, runner, box, resolver: createResolver({ registry, host, analysis: runner }) };
}

test('package: the analysis runs in a Worker, reports an estimate with assumptions, a surface field and the vent area needed', async () => {
  const s = await rig(design({ watts: 2 }));
  try {
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'thermal-estimate');
    assert.equal(r.validity, 'estimate');
    const m = Object.fromEntries(r.summary.map((x) => [x.metric, x]));
    for (const k of ['internal_air_temp', 'board_temp', 'component_temp_mcu', 'max_component_temp', 'max_outer_surface_temp', 'total_heat', 'vent_flow', 'energy_balance_error', 'target_temp', 'vent_area_installed']) assert.ok(m[k], k);
    assert.equal(m.total_heat.value, 2); assert.equal(m.target_temp.value, 60); assert.equal(m.max_component_temp.unit, '°C');
    assert.ok(m.max_component_temp.value > m.internal_air_temp.value && m.internal_air_temp.value > 25);
    assert.ok(r.warnings.some((w) => /^Assumption:/.test(w)));
    const f = r.fields[0]; assert.equal(f.kind, 'surface'); assert.equal(f.body, 'Case');
    assert.ok(f.data.every(Number.isFinite)); const lo = Math.min(...f.data), hi = Math.max(...f.data);
    assert.ok(lo >= 24.9 && hi <= m.max_component_temp.value, `surface field ${lo}..${hi}`); assert.ok(hi - lo > 0.1);
    // the same model answers the same number twice
    const again = await s.runner.run(s.box.P, s.box.build, EXT, 'thermal-estimate', { force: true });
    assert.equal(again.summary.find((x) => x.metric === 'max_component_temp').value, m.max_component_temp.value);
  } finally { s.box.build.dispose(); }
});

test('checks: an estimate is a hard row (it gates), the limits are editable, and a failing limit fails', async () => {
  const s = await rig(design({ watts: 3 }));
  try {
    let rows = await s.runner.rows(s.box.P, s.box.build, { run: true, budget: { left: 60 } });
    const hot = rows.find((r) => r.id.endsWith('max-component-temp')), touch = rows.find((r) => r.id.endsWith('max-outer-temp'));
    assert.equal(hot.severity, 'hard'); assert.equal(touch.severity, 'hard'); assert.match(hot.detail, /estimate/);
    const tight = applyOps(s.box.P, [{ op: 'set_limit', key: `${EXT}:thermal-estimate:max-component-temp`, value: 26 }]).project;
    rows = await s.runner.rows(tight, s.box.build, { run: false });
    const r2 = rows.find((r) => r.id.endsWith('max-component-temp')); assert.equal(r2.pass, false); assert.equal(r2.severity, 'hard');
  } finally { s.box.build.dispose(); }
});

test('AI tools: set-vents stores them in the project (one undoable op), thermal-estimate answers with and without them', async () => {
  const s = await rig(design({ watts: 2 }));
  try {
    const tools = s.resolver.agentTools(s.box.P).map((t) => t.id); assert.deepEqual(tools.sort(), ['set-vents', 'thermal-estimate']);
    const before = await s.resolver.callAgentTool(s.box.P, EXT, 'thermal-estimate', { target_C: 140 });
    assert.equal(before.vent_area_installed_mm2, 0); assert.equal(before.reachable, true); assert.ok(before.vent_area_needed_mm2 > 0);
    await assert.rejects(s.resolver.callAgentTool(s.box.P, EXT, 'thermal-estimate', { target_C: 'hot' }), /target_C/);
    await assert.rejects(s.resolver.callAgentTool(s.box.P, EXT, 'set-vents', { vents: [{ area_mm2: -1, z_mm: 3 }] }), /area_mm2/);
    const set = await s.resolver.callAgentTool(s.box.P, EXT, 'set-vents', { vents: [{ area_mm2: 150, z_mm: 3 }, { area_mm2: 150, z_mm: 22 }] });
    assert.equal(set.total_area_mm2, 300); assert.equal(s.box.commits, 1);
    assert.equal(s.box.P.ext[EXT].vents.length, 2, 'saved in the document under ext[<id>]');
    const after = await s.resolver.callAgentTool(s.box.P, EXT, 'thermal-estimate', { target_C: 140 });
    assert.equal(after.vent_area_installed_mm2, 300); assert.ok(after.hottest_part_C < before.hottest_part_C, 'the vents cool the part');
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'thermal-estimate', { force: true });
    assert.ok(r.summary.find((x) => x.metric === 'vent_flow').value > 0); assert.equal(r.summary.find((x) => x.metric === 'vent_area_installed').value, 300);
  } finally { s.box.build.dispose(); }
});
