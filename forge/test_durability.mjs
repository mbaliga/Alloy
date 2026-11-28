// Headless tests for A6a (docs/EXTENSIONS_SPEC.md 4A): the durability data (materials, duty profile, joints) and their typed ops, the risk-flag
// type (severity, confidence, table, scorecard rows), the joint/creep package (maths, flags, data gaps, fixes) through the real runner and a Worker,
// the Thermal 3D temperature at the joint, gating on the scorecard, and the worked example on the demo board.
// The numbers with tolerances are in analysis-validation/cases/durability-a6a.json (npm run test:analysis).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import * as R from 'replicad';
import initOC from 'replicad-opencascadejs';
import { makeOps } from './src/occt/ops.js';
import { createSession } from './src/occt/session.js';
import { build } from './src/kernel.js';
import { emptyProject, applyOps, migrate } from './src/model.js';
import { prepareInput } from './src/analysis/setup.js';
import { builtinMaterials, validateMaterial, allMaterials, materialOf, interpT, QUALITIES, LOW_QUALITIES } from './src/analysis/materials.js';
import { BOLTS as HOST_BOLTS, GRADES as HOST_GRADES, validateJoint, validateDuty, makeDuty, DUTY_PRESETS, newJoint, jointBodies } from './src/analysis/durability.js';
import { severityFromMargin as hostSev, confidenceOf as hostConf, stepDown as hostStep, validateFlag, flagsTable, flagsToCsv, flagsToMarkdown, flagRows, gates, SEVERITIES } from './src/analysis/risk.js';
import { validateResult } from './src/analysis/result.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { ExtHost } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { createResolver } from './src/ext/resolve.js';
import { createAnalysisRunner } from './src/analysis/runner.js';
import { createResultCache, memoryStore as memResults } from './src/analysis/cache.js';
import { packDir } from './tools/pack-ext.mjs';
import { pcbEnclosureProject, templateJoints } from './templates/pcb-enclosure/index.js';

const EXT = 'xyz.mdhv.joint-creep', T3D = 'xyz.mdhv.thermal-3d';
let wasm, oc; const HOSTS = [];
before(async () => { wasm = await Module(); wasm.setup(); oc = await initOC(); R.setOC(oc); });
after(() => HOSTS.forEach((h) => h.stopAll()));
const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };
const core = {}; new Function('__core', fs.readFileSync('examples/ext/joint-creep/main.js', 'utf8'))(core);
const clone = (v) => JSON.parse(JSON.stringify(v));

/** A 60 x 40 x 25 mm shell with a heat source and a joint through its wall onto a steel rail. */
function design({ material = 'petg', insert = 'none', duty = 'handheld-light', jointOver = {}, extra = [], watts = 0 } = {}) {
  let P = applyOps(migrate(emptyProject()), [
    { op: 'add_feature', feature: { type: 'box', body: 'Shell', size: [60, 40, 25], anchor: 'bottom', pos: [0, 0, 0] } },
    { op: 'add_feature', feature: { type: 'box', body: 'Shell', op: 'cut', size: [56, 36, 21], anchor: 'bottom', pos: [0, 0, 2] } }]).project;
  P.pcb = { ...P.pcb, components: [{ name: 'board', kind: 'box', role: 'board', box: [-25, 25, -16, 16, 6, 7.6] }, { name: 'mcu', kind: 'box', role: 'component', box: [-4, 4, -4, 4, 7.6, 9.6] }] };
  const ops = [{ op: 'set_body_material', body: 'Shell', material }, ...(duty ? [{ op: 'set_duty', duty: makeDuty(duty) }] : []),
    ...(watts ? [{ op: 'set_condition', list: 'heat', item: { id: 'h1', target: { pcb: 'mcu' }, power_W: watts } }] : []),
    { op: 'set_joint', joint: newJoint({ id: 'J1', name: 'clamp cross-bolt', where: [29, 0, 12], axis: '+x', insert, clamps: [{ body: 'Shell', thickness_mm: 6 }, { external: 'rail', material: 'steel-carbon', thickness_mm: 3 }], ...jointOver }) }, ...extra];
  return applyOps(P, ops, { actor: 'user' }).project;
}
const inputOf = (P) => { const B = build(P, wasm); try { return prepareInput(P, B); } finally { B.dispose(); } };
const flagOf = (r, suffix) => r.flags.find((f) => f.id.endsWith(suffix));

/** A material with datasheet-quality values everywhere, so the confidence is not capped by "typical" data. */
function goodPlastic(over = {}) {
  const base = builtinMaterials().find((m) => m.id === 'petg');
  const m = clone(base); m.id = 'petg-good'; m.name = 'PETG (test, datasheet quality)'; m.class = 'custom'; delete m.custom;
  for (const p of Object.values(m.properties)) if (p.quality !== 'n/a') { p.quality = 'datasheet'; p.sources = ['user']; }
  const d = m.durability;
  for (const k of ['cte', 'E_T', 'yield_T']) { d[k].quality = 'datasheet'; d[k].sources = ['user']; }
  d.creep.n.quality = 'datasheet'; d.creep.beta_T.quality = 'datasheet'; d.creep.linear_limit.quality = 'datasheet'; d.creep.n.sources = d.creep.beta_T.sources = d.creep.linear_limit.sources = ['user'];
  return { ...m, ...over };
}

// ───────── materials
test('materials: durability data validates, every item has quality and sources, typical caps confidence, fastener metals are present', () => {
  const ms = builtinMaterials(); const ids = ms.map((m) => m.id);
  for (const id of ['steel-carbon', 'stainless-304', 'al6061', 'al7075', 'brass-cuzn']) assert.ok(ids.includes(id), id);
  assert.ok(QUALITIES.includes('typical') && LOW_QUALITIES.includes('typical') && LOW_QUALITIES.includes('estimate'));
  for (const m of ms) {
    validateMaterial(m);
    const d = m.durability; if (!d) { assert.ok(['air'].includes(m.id), `${m.id} has no durability block`); continue; }
    for (const [k, v] of Object.entries(d)) { if (k === 'wet') continue; const items = k === 'creep' ? [v.n, v.beta_T, v.linear_limit] : [v]; for (const it of items) { assert.ok(QUALITIES.includes(it.quality) && it.quality !== 'n/a', `${m.id}.${k} quality`); assert.ok(it.sources.length, `${m.id}.${k} sources`); } }
  }
  const pl = ms.filter((m) => m.class === 'fdm-polymer');
  for (const m of pl) { assert.ok(m.durability.cte && m.durability.E_T && m.durability.yield_T && m.durability.creep, `${m.id} has CTE, E(T), yield(T) and creep`); assert.equal(m.durability.creep.n.quality, 'typical', `${m.id} creep is typical, not measured`); }
  for (const id of ['pa12', 'pa-gf']) assert.ok(ms.find((m) => m.id === id).durability.wet, `${id} has wet/dry factors`);
  assert.equal(ms.find((m) => m.id === 'petg').durability.wet, undefined, 'a value that is not known is absent, never defaulted');
  assert.ok(Math.abs(interpT([[0, 1], [10, 3]], 5) - 2) < 1e-12 && interpT([[0, 1], [10, 3]], -5) === 1 && interpT([[0, 1], [10, 3]], 50) === 3);
});
test('materials: a custom material with a bad durability block is refused, a good one is accepted as a typed op', () => {
  const m = goodPlastic();
  assert.doesNotThrow(() => validateMaterial(m));
  const bad = clone(m); bad.durability.creep.n.value = 1.5; assert.throws(() => validateMaterial(bad), /creep.n/);
  const nosrc = clone(m); nosrc.durability.cte.sources = []; assert.throws(() => validateMaterial(nosrc), /source/);
  const unsorted = clone(m); unsorted.durability.E_T.points = [[50, 1], [20, 1]]; assert.throws(() => validateMaterial(unsorted), /rise/);
  const P = applyOps(migrate(emptyProject()), [{ op: 'set_material', material: m }]).project;
  assert.ok(allMaterials(P).get('petg-good').durability.cte);
});

// ───────── duty profile and joints: typed ops
test('duty: presets validate and are labelled placeholders, set_duty is a typed op, bad values are refused, a project round-trips', () => {
  for (const k of Object.keys(DUTY_PRESETS)) { const d = makeDuty(k); assert.equal(d.placeholder, true, k); validateDuty(d); assert.ok(d.life && d.shots && d.vibration.psd.length && d.temperature && d.humidity && d.safety_factor === 3); }
  let P = migrate(emptyProject()); assert.equal(P.analysis.conditions.duty, null); assert.deepEqual(P.joints, []);
  P = applyOps(P, [{ op: 'set_duty', duty: makeDuty('vehicle') }]).project; assert.equal(P.analysis.conditions.duty.preset, 'vehicle');
  const bad = makeDuty('vehicle'); bad.temperature.max_C = -80; assert.throws(() => applyOps(P, [{ op: 'set_duty', duty: bad }]), /maximum temperature/);
  const bad2 = makeDuty('vehicle'); bad2.humidity = 'steam'; assert.throws(() => applyOps(P, [{ op: 'set_duty', duty: bad2 }]), /humidity/);
  assert.equal(applyOps(P, [{ op: 'set_duty', duty: null }]).project.analysis.conditions.duty, null);
  const back = migrate(JSON.parse(JSON.stringify(P))); assert.equal(back.analysis.conditions.duty.preset, 'vehicle');
});
test('joints: set_joint validates every field, replaces by id, remove_joint removes, an old project migrates with no joints', () => {
  let P = design({ duty: null });
  assert.equal(P.joints.length, 1);
  for (const [what, patch, re] of [['size', { bolt: { size: 'M9', grade: '8.8', length_mm: 16 } }, /size/], ['grade', { bolt: { size: 'M3', grade: '99', length_mm: 16 } }, /grade/], ['axis', { axis: 'up' }, /axis/],
    ['where', { where: [1, 2] }, /where/], ['friction', { mu_clamp: 5 }, /friction/], ['insert', { insert: 'glue' }, /insert/], ['clamps', { clamps: [] }, /clamps/], ['locker', { thread_locker: 'yes' }, /thread_locker/],
    ['torque', { tightening: { mode: 'torque', method: 'nut_factor', torque_Nm: -1, nut_factor: 0.2 } }, /torque/], ['body', { clamps: [{ body: 'Nope', thickness_mm: 3 }] }, /no body/], ['anchor', { thread_in: 'plastic' }, /anchor/]]) {
    assert.throws(() => applyOps(P, [{ op: 'set_joint', joint: { ...P.joints[0], ...patch } }]), re, what);
  }
  const edited = applyOps(P, [{ op: 'set_joint', joint: { ...P.joints[0], thread_locker: true } }]).project; assert.equal(edited.joints.length, 1); assert.equal(edited.joints[0].thread_locker, true);
  assert.equal(applyOps(edited, [{ op: 'remove_joint', id: 'J1' }]).project.joints.length, 0);
  assert.throws(() => applyOps(P, [{ op: 'remove_joint', id: 'zz' }]), /no joint/);
  assert.deepEqual(migrate({ v: 3, name: 'old', params: [], features: [] }).joints, []);
  assert.deepEqual(jointBodies(P.joints[0]), ['Shell']);
});
test('joints: locks are enforced in the ops layer (a locked joint, a locked body, and an unlock)', () => {
  const P = design({ duty: null });
  const lockedJ = applyOps(P, [{ op: 'set_joint', joint: { ...P.joints[0], locked: true } }], { actor: 'user' }).project;
  assert.throws(() => applyOps(lockedJ, [{ op: 'set_joint', joint: { ...lockedJ.joints[0], thread_locker: true } }]), /locked by the user/);
  assert.throws(() => applyOps(lockedJ, [{ op: 'remove_joint', id: 'J1' }]), /locked by the user/);
  assert.throws(() => applyOps(lockedJ, [{ op: 'set_joint', joint: { ...lockedJ.joints[0], locked: false, insert: 'metal_sleeve' } }]), /locked by the user/, 'the AI cannot unlock and edit in one op');
  assert.throws(() => applyOps(lockedJ, [{ op: 'set_joint', joint: { ...lockedJ.joints[0], locked: false } }], { actor: 'ext', extId: 'x' }), /locked by the user/, 'a package cannot unlock');
  assert.throws(() => applyOps(lockedJ, [{ op: 'set_joint', joint: { ...lockedJ.joints[0], thread_locker: true } }], { actor: 'user' }), /locked by the user/, 'even the user must unlock first');
  assert.equal(applyOps(lockedJ, [{ op: 'set_joint', joint: { ...lockedJ.joints[0], locked: false } }], { actor: 'user' }).project.joints[0].locked, false, 'only the user can unlock');
  assert.throws(() => applyOps(P, [{ op: 'set_joint', joint: { ...P.joints[0], locked: true } }, { op: 'set_joint', joint: { ...P.joints[0], locked: false } }]).project, /only the user/, 'the AI cannot flip the lock flag');
  const lockedB = clone(P); lockedB.bodies.Shell.locked = true;
  assert.throws(() => applyOps(lockedB, [{ op: 'set_joint', joint: { ...P.joints[0], insert: 'metal_sleeve' } }]), /body "Shell" is locked/);
  assert.throws(() => applyOps(lockedB, [{ op: 'remove_joint', id: 'J1' }]), /body "Shell" is locked/);
  assert.throws(() => applyOps(lockedB, [{ op: 'set_joint', joint: newJoint({ id: 'J2', clamps: [{ body: 'Shell', thickness_mm: 4 }] }) }]), /body "Shell" is locked/);
});
test('the template marks its fasteners as joints, and a clamp cross-bolt when the spec has one', () => {
  const P = pcbEnclosureProject();
  assert.deepEqual(P.joints.map((j) => j.id), ['CAP1', 'CAP2']);
  for (const j of P.joints) { validateJoint(j, null); assert.equal(j.clamps[0].body, 'Cap'); assert.equal(j.anchor.body, 'Shell'); assert.equal(j.thread_in, 'plastic'); }
  const f = P.features[0], spec = clone(f.spec); spec.clamp = { crossBolt: { where: [-10, 0, 14], axis: '+z', size: 'M3', grade: '8.8', length_mm: 16, thickness_mm: 6, rail: { name: 'rail', material: 'steel-carbon', thickness_mm: 3 } } };
  const js = templateJoints(f, spec, P.pcb); assert.deepEqual(js.map((j) => j.id), ['CAP1', 'CAP2', 'CLAMP1']);
  assert.equal(js[2].clamps[1].external, 'rail'); validateJoint(js[2], null);
});

// ───────── the risk flag type
const FLAG = { id: 'a', mechanism: 'creep', severity: 'high', body: 'Shell', where: [1, 2, 3], metric: 'm', value: 1, limit: 2, margin: 0.5, why: 'Because.', fix: { text: 'Do x.', proposals: [] }, confidence: 'medium' };
test('risk flag: the exact shape is enforced, data_gap alone may carry nulls', () => {
  validateFlag(FLAG);
  assert.deepEqual(Object.keys(FLAG).sort(), ['body', 'confidence', 'fix', 'id', 'limit', 'margin', 'mechanism', 'metric', 'severity', 'value', 'where', 'why'].sort());
  for (const [k, v, re] of [['mechanism', 'rust', /mechanism/], ['severity', 'bad', /severity/], ['confidence', 'sure', /confidence/], ['where', [1, 2], /where/], ['margin', NaN, /margin/], ['why', '', /why/], ['fix', 'x', /fix/], ['value', null, /value/]]) assert.throws(() => validateFlag({ ...FLAG, [k]: v }), re, k);
  assert.throws(() => validateFlag({ ...FLAG, fix: { text: 'x', proposals: [{ id: 'p', label: 'l', ops: [{}] }] } }), /proposal/);
  validateFlag({ ...FLAG, mechanism: 'data_gap', value: null, limit: null, margin: null });
});
test('risk flag: severity follows the margin (< 1 high, < SF medium, < 10 low, else info) and the package applies the same rule', () => {
  for (const [m, sf, want] of [[0, 3, 'high'], [0.999, 3, 'high'], [1, 3, 'medium'], [2.9, 3, 'medium'], [3, 3, 'low'], [9.9, 3, 'low'], [10, 3, 'info'], [1000, 3, 'info'], [1.5, 2, 'medium'], [2, 2, 'low'], [NaN, 3, 'high']]) {
    assert.equal(hostSev(m, sf), want, `${m}/${sf}`); assert.equal(core.severityFromMargin(m, sf), want, `package ${m}/${sf}`);
  }
  for (const s of SEVERITIES) assert.equal(hostStep(s), core.stepDown(s)); assert.equal(hostStep('high'), 'medium'); assert.equal(hostStep('info'), 'info');
});
test('risk flag: confidence is capped by the result validity and the worst data quality; typical or estimate data cap it at low whatever the margin', () => {
  for (const validity of ['estimate', 'validated', 'experimental']) for (const qs of [[], ['typical'], ['estimate'], ['handbook'], ['datasheet'], ['measured'], ['measured', 'typical'], ['datasheet', 'handbook'], ['measured', 'measured']]) assert.equal(hostConf(validity, qs), core.confidenceOf(validity, qs), `${validity} ${qs}`);
  assert.equal(hostConf('estimate', ['measured']), 'medium', 'an estimate never exceeds medium');
  assert.equal(hostConf('estimate', ['typical', 'measured']), 'low');
  assert.equal(hostConf('validated', ['measured']), 'high'); assert.equal(hostConf('validated', ['estimate']), 'low');
  assert.equal(hostConf('experimental', ['measured']), 'low'); assert.equal(hostConf('estimate', []), 'low');
});
test('risk flag: the table, the CSV and the markdown carry every column, sorted by severity', () => {
  const flags = [{ ...FLAG, id: 'z', severity: 'info', margin: 50 }, { ...FLAG, id: 'b', margin: 0.2 }, { ...FLAG, id: 'a', margin: 0.5 }, { ...FLAG, id: 'q,"x"', severity: 'medium', margin: 2, why: 'has, a comma and "quotes"' }];
  const t = flagsTable(flags); assert.deepEqual(t.columns, ['id', 'mechanism', 'severity', 'confidence', 'body', 'where', 'metric', 'value', 'limit', 'margin', 'why', 'fix']);
  assert.deepEqual(t.rows.map((r) => r[0]), ['b', 'a', 'q,"x"', 'z']);
  const csv = flagsToCsv(flags).trim().split('\n'); assert.equal(csv.length, 5); assert.match(csv[3], /"q,""x"""/); assert.match(csv[3], /"has, a comma and ""quotes"""/);
  const md = flagsToMarkdown(flags, { validity: 'estimate' }); assert.match(md, /\| id \| mechanism \|/); assert.match(md, /Validity: estimate/);
});
test('risk flag: scorecard rows gate by the spec: high fails unless confidence is low (advisory); experimental never gates; no result is advisory "not run"', () => {
  const res = (flags, validity = 'estimate') => ({ flags, validity });
  const meta = { ext: EXT, analysis: 'joint-creep', label: 'Joint' };
  assert.equal(gates({ ...FLAG, confidence: 'medium' }, 'estimate'), true); assert.equal(gates({ ...FLAG, confidence: 'low' }, 'estimate'), false); assert.equal(gates(FLAG, 'experimental'), false); assert.equal(gates({ ...FLAG, severity: 'medium' }, 'estimate'), false);
  let rows = flagRows(res([FLAG]), meta); assert.equal(rows.length, 1); assert.equal(rows[0].severity, 'hard'); assert.equal(rows[0].pass, false);
  rows = flagRows(res([{ ...FLAG, confidence: 'low' }]), meta); assert.equal(rows[0].severity, 'advisory'); assert.equal(rows[0].pass, false); assert.match(rows[0].detail, /advisory/);
  rows = flagRows(res([FLAG], 'experimental'), meta); assert.equal(rows[0].severity, 'advisory');
  rows = flagRows(res([{ ...FLAG, severity: 'medium' }]), meta); assert.equal(rows[0].severity, 'advisory');
  rows = flagRows(res([{ ...FLAG, severity: 'low' }, { ...FLAG, id: 'i', severity: 'info' }]), meta); assert.equal(rows.length, 1); assert.equal(rows[0].pass, true); assert.equal(rows[0].severity, 'hard');
  rows = flagRows(null, meta); assert.equal(rows[0].severity, 'advisory'); assert.match(rows[0].detail, /not run/);
});
test('result model: flags and series are validated and carried; a bad flag fails the whole result', () => {
  const base = { id: 'a/b', version: '1', inputsHash: 'h', summary: [], fields: [], warnings: [], validity: 'estimate' };
  validateResult({ ...base, flags: [FLAG], series: [{ name: 'c', x: 'h', y: 'N', xs: [0, 1], ys: [1, 2] }] });
  assert.throws(() => validateResult({ ...base, flags: [{ ...FLAG, severity: 'huge' }] }), /severity/);
  assert.throws(() => validateResult({ ...base, flags: [FLAG, FLAG] }), /duplicate/);
  assert.throws(() => validateResult({ ...base, series: [{ name: 'c', x: 'h', y: 'N', xs: [0], ys: [1] }] }), /series/);
});

// ───────── the package: tables and maths
test('package: its fastener tables equal the host tables (one copy of the truth, checked)', () => {
  assert.deepEqual(core.BOLTS, HOST_BOLTS);
  assert.deepEqual(Object.fromEntries(Object.entries(core.GRADES).map(([k, v]) => [k, { ...v }])), HOST_GRADES);
  for (const b of Object.values(HOST_BOLTS)) { assert.ok(Math.abs(b.As - Math.PI / 4 * ((b.d2 + b.d3) / 2) ** 2) / b.As < 0.01, `As of M${b.d} is the stress area of the mean diameter`); assert.ok(b.dh > b.d && b.dk > b.dh); }
});
test('package: preload from torque follows the VDI form, and more friction means less preload', () => {
  const o = { method: 'friction', d: 3, P: 0.5, d2: 2.675, dW: 5.5, dh: 3.4, muG: 0.14, muK: 0.14 };
  const F = core.preloadFromTorque(0.9, o); assert.ok(F > 1000 && F < 3000, `${F}`);
  assert.ok(core.preloadFromTorque(0.9, { ...o, muG: 0.2, muK: 0.2 }) < F);
  assert.ok(Math.abs(core.preloadFromTorque(0.9, { method: 'nut_factor', K: 0.2, d: 3 }) - 1500) < 1e-9);
  assert.ok(Math.abs(core.preloadFromTorque(1.8, { ...o }) / F - 2) < 1e-12);
});
test('package: pressure-cone compliance falls with the cone angle, the cut-off at the outer diameter raises it, and the cylinder is the upper bound', () => {
  const base = { x0: 0, x1: 12, lK: 12, dW: 5.5, dh: 3.4, E: 2.1e9, w: 1, tp: 0.5, DA: null };
  const wide = core.layerCompliance(base), flat = core.layerCompliance({ ...base, tp: 0.3 }), steep = core.layerCompliance({ ...base, tp: 0.7 }), narrow = core.layerCompliance({ ...base, DA: 8 }), tube = core.layerCompliance({ ...base, DA: 5.5 });
  assert.ok(steep < wide && wide < flat && wide < narrow && narrow < tube);
  assert.ok(Math.abs(core.cylinderCompliance(12, 5.5, 3.4, 2.1e9) - tube) / tube < 1e-9);
  const parts = [0, 4, 12].slice(1).map((x1, i) => core.layerCompliance({ ...base, x0: [0, 4][i], x1 }));
  assert.ok(Math.abs(parts[0] + parts[1] - wide) / wide < 1e-3, 'layers add up to the whole stack (midpoint rule)');
});
test('package: embedding loss is f/C, the thermal change has the sign of (alpha_clamped - alpha_bolt) dT, and a metal-only joint at its assembly temperature loses nothing to temperature', () => {
  assert.ok(Math.abs(core.embeddingLoss(8, 4e-9) - 2000) < 1e-9);
  const dF = (aP, dT) => core.thermalPreloadChange({ layers: [{ alpha: aP, l: 0.01 }], alphaS: 11.7e-6, lK: 0.01, dT, C: 5e-9 });
  assert.ok(dF(60e-6, 40) > 0 && dF(60e-6, -40) < 0 && dF(5e-6, 40) < 0 && Math.abs(dF(11.7e-6, 40)) < 1e-9);
});
test('package: creep preload falls with time, with temperature and with the plastic share of the compliance, and F never goes below zero', () => {
  const at = (o) => core.creepRelaxNumbers({ F0: 1500, dS: 1.1e-8, dP: 8.4e-8, n: 0.2, days: 1, phases: [{ hours: 1000, beta: 0.15 }], ...o });
  assert.ok(at({}) < 1500 && at({ phases: [{ hours: 5000, beta: 0.15 }] }) < at({}) && at({ phases: [{ hours: 1000, beta: 0.6 }] }) < at({}) && at({ dP: 1e-9 }) > at({}));
  assert.ok(at({ phases: [{ hours: 1e6, beta: 50 }] }) >= 0);
  // superposition: stress history is remembered, so a cold hold after a hot one recovers a little instead of relaxing further, and the force at a hotter temperature is lower
  const hotThenCold = at({ phases: [{ hours: 1000, beta: 0.6 }, { hours: 1000, beta: 0.15 }] }), coldOnly = at({ phases: [{ hours: 2000, beta: 0.15 }] });
  assert.ok(hotThenCold < coldOnly, 'hot hours cost preload that a later cold hold does not give back in full');
  // an open joint carries no load and cannot creep: a cold shift big enough to open it keeps the hot preload at or below the installed one
  const F0 = 300, C = 1.2e-8, open = core.creepRun({ X0: F0 * C, days: 200, segments: [{ T: 1, hours: 1 }, { T: 0, hours: 23 }], sources: [{ n: 0.2, c: () => 4e-9, beta: () => 0.5 }], Cof: () => C, shift: (T) => (T === 0 ? -1.2 * F0 * C : 0) });
  assert.equal(open.force(0), 0, 'the joint is open when cold');
  assert.ok(open.force(1) <= F0 + 1e-9 && open.force(1) >= 0, `hot preload ${open.force(1)} must not exceed the installed ${F0}`);
  // the superposition integral matches an independent fine-grid Volterra integration (log grid, backward Euler) for an isothermal hold
  const dS = 1.1e-8, dP = 8.4e-8, beta = 0.15, n = 0.2, Tend = 8760;
  const ts = [0]; for (let i = 0; i < 3000; i++) ts.push(1e-3 * Math.pow(Tend / 1e-3, i / 2999));
  const F = [1500]; for (let k = 1; k < ts.length; k++) { let h = Math.pow(ts[k], n) * 1500; for (let i = 1; i < k; i++) h += Math.pow(ts[k] - ts[i], n) * (F[i] - F[i - 1]); const w = Math.pow(ts[k] - ts[k - 1], n); F.push(((dS + dP) * 1500 - dP * beta * h + dP * beta * w * F[k - 1]) / (dS + dP + dP * beta * w)); }
  assert.ok(Math.abs(at({ phases: [{ hours: Tend, beta }] }) - F[F.length - 1]) / F[F.length - 1] < 0.02, 'agrees with the Volterra reference within 2 %');
});

// ───────── the package: flags on a joint
const run = (P) => core.evaluate(inputOf(P));
test('package: a joint in plastic gives flags that all validate, each with mechanism, severity, location, margin, why, fix and confidence; the result is an estimate', () => {
  const r = run(design());
  assert.equal(r.validity, 'estimate'); assert.ok(r.flags.length >= 6);
  const fixIds = new Set(); for (const f of r.flags) { validateFlag(f); assert.deepEqual(f.where, [29, 0, 12]); assert.ok(f.why.length > 20 && f.fix.text.length > 5); for (const p of f.fix.proposals) fixIds.add(p.id); }
  const mech = new Set(r.flags.map((f) => f.mechanism)); for (const m of ['preload_loss', 'creep', 'self_loosening', 'thermal_cycling', 'data_gap']) assert.ok(mech.has(m), m);
  for (const f of r.flags) if (f.mechanism !== 'data_gap') assert.ok(Number.isFinite(f.margin) && f.margin <= 1000 && f.margin >= 0);
  for (const id of ['sleeve', 'washer', 'grade', 'size', 'locker']) assert.ok(fixIds.has(id), `a ${id} proposal exists somewhere`);
  const rows = new Map(r.summary.map((s) => [s.metric, s.value]));
  assert.ok(rows.get('J1.preload_nominal') > 1000 && rows.get('J1.preload_end_of_life_min') <= rows.get('J1.preload_installed_min'));
  assert.ok(r.series.length === 1 && r.series[0].ys[0] > r.series[0].ys[r.series[0].ys.length - 1], 'preload against time falls');
  assert.ok(r.warnings.some((w) => /placeholder/.test(w)), 'the placeholder duty is said in the result');
  validateResult({ ...r, id: 'a/b', version: '1', inputsHash: 'h', fields: [] });
});
test('package: with typical material data the creep and slip flags are low confidence, so they cannot gate (the assembly flag is medium and can); datasheet-quality data lifts it to medium and high flags then gate', () => {
  const r = run(design());
  for (const f of r.flags) if (['creep', 'self_loosening', 'preload_loss:preload'].includes(f.mechanism) && !/assembly|engage/.test(f.id)) assert.equal(f.confidence, 'low', f.id);
  assert.ok(r.flags.some((f) => f.severity === 'high'), 'there is something high to be advisory about');
  assert.equal(flagOf(r, ':assembly').confidence, 'medium', 'the assembly flag rests on ISO 898-1 grade strengths and the torque only, so it is medium and can gate on stock data');
  const good = goodPlastic();
  const P = design({ material: 'petg', extra: [{ op: 'set_material', material: good }, { op: 'set_body_material', body: 'Shell', material: 'petg-good' }, { op: 'set_duty', duty: { ...makeDuty('handheld-light'), humidity: 'dry' } }] });
  const g = run(P);
  assert.ok(g.flags.filter((f) => f.mechanism !== 'data_gap' && !/engage/.test(f.id)).every((f) => f.confidence === 'medium'), g.flags.map((f) => f.id + f.confidence).join());
  assert.equal(flagOf(g, ':engage').confidence, 'low', 'the engagement length is a rule of thumb, so it stays low');
  assert.ok(g.flags.some((f) => f.severity === 'high' && f.confidence === 'medium'));
  assert.ok(!g.flags.some((f) => f.confidence === 'high'), 'an estimate never claims high confidence');
});
test('package: missing material data is a data-gap flag and nothing is silently defaulted', () => {
  const noCreep = goodPlastic(); delete noCreep.durability.creep; noCreep.id = 'petg-nocreep';
  let r = run(design({ extra: [{ op: 'set_material', material: noCreep }, { op: 'set_body_material', body: 'Shell', material: 'petg-nocreep' }] }));
  assert.ok(r.flags.some((f) => f.mechanism === 'data_gap' && /creep data/.test(f.why)), 'no creep data is reported');
  assert.equal(flagOf(r, ':creep'), undefined, 'and the end-of-life preload is not invented');
  const noCte = goodPlastic(); delete noCte.durability.cte; noCte.id = 'petg-nocte';
  r = run(design({ extra: [{ op: 'set_material', material: noCte }, { op: 'set_body_material', body: 'Shell', material: 'petg-nocte' }] }));
  assert.ok(r.flags.some((f) => f.mechanism === 'data_gap' && /thermal expansion/.test(f.why)));
  const noDur = goodPlastic(); delete noDur.durability; noDur.id = 'plain';
  r = run(design({ extra: [{ op: 'set_material', material: noDur }, { op: 'set_body_material', body: 'Shell', material: 'plain' }] }));
  const gaps = r.flags.filter((f) => f.mechanism === 'data_gap').map((f) => f.why).join(' ');
  assert.match(gaps, /thermal expansion/); assert.match(gaps, /E against temperature/); assert.match(gaps, /creep data/);
  r = run(design({ duty: null })); assert.ok(r.flags.some((f) => f.id === 'duty:missing' && f.mechanism === 'data_gap')); assert.equal(flagOf(r, ':slip'), undefined, 'no duty, no slip result');
  assert.ok(run(design({ duty: 'handheld-light', extra: [{ op: 'remove_joint', id: 'J1' }] })).flags.some((f) => f.id === 'joints:none' && f.mechanism === 'data_gap'), 'no joints is a data-gap flag, so it reaches the table and the scorecard');
  const unassigned = design({ extra: [{ op: 'set_body_material', body: 'Shell', material: null }] });
  assert.ok(run(unassigned).flags.some((f) => f.mechanism === 'data_gap' && /no material assigned/.test(f.why)), 'the PLA fallback is a flag, not a silence');
  assert.equal(flagOf(run(unassigned), ':creep'), undefined, 'a joint in a body with no material is not calculated with borrowed PLA numbers');
  const ext = design({ jointOver: { clamps: [{ body: 'Shell', thickness_mm: 6 }, { external: 'rail', material: 'unobtainium', thickness_mm: 3 }] } });
  assert.ok(run(ext).flags.some((f) => f.mechanism === 'data_gap' && /unobtainium/.test(f.why)));
  assert.ok(run(design()).flags.some((f) => f.id.endsWith('gap:embedding')), 'a plastic interface is not in the VDI embedding table');
  assert.ok(run(design({ jointOver: { carried_mass_g: 80 } })).flags.every((f) => !f.id.endsWith('gap:mass')), 'giving the mass removes that gap');
});
test('package: a metal compression sleeve removes the creep path and the creep flag says so; creep and bearing flags improve, and the fix proposal is typed ops', () => {
  const none = run(design()), sleeve = run(design({ insert: 'metal_sleeve' }));
  const c0 = flagOf(none, ':creep'), c1 = flagOf(sleeve, ':creep');
  assert.equal(c0.severity, 'high'); assert.equal(c1.severity, 'info'); assert.match(c1.why, /sleeve carries the bolt load/);
  assert.ok(sleeve.summary.find((s) => s.metric === 'J1.preload_end_of_life_min').value >= 0.99 * sleeve.summary.find((s) => s.metric === 'J1.preload_installed_min').value);
  assert.equal(flagOf(sleeve, ':bearing:head'), undefined, 'no plastic under the head, no bearing flag');
  const p = c0.fix.proposals.find((x) => x.id === 'sleeve'); assert.ok(p);
  const P = design(), fixed = applyOps(P, p.ops, { actor: 'user' }).project; assert.equal(fixed.joints[0].insert, 'metal_sleeve');
  const after = run(fixed); assert.equal(flagOf(after, ':creep').severity, 'info');
});
test('package: a thread locker lowers the slip severity one step but never removes the flag; a larger washer lowers the bearing pressure; a bigger bolt raises the preload', () => {
  const base = run(design()), s0 = flagOf(base, ':slip');
  assert.equal(s0.severity, 'high');
  const locked = run(applyOps(design(), s0.fix.proposals.find((x) => x.id === 'locker').ops, { actor: 'user' }).project), s1 = flagOf(locked, ':slip');
  assert.ok(s1, 'the flag is still there'); assert.equal(s1.severity, 'medium'); assert.match(s1.why, /thread locker/);
  assert.equal(flagOf(locked, ':slip').margin, s0.margin, 'the locker changes the severity, not the margin');
  const b0 = flagOf(base, ':bearing:head'), w = run(applyOps(design(), b0.fix.proposals.find((x) => x.id === 'washer').ops, { actor: 'user' }).project);
  assert.ok(flagOf(w, ':bearing:head').value < b0.value / 2, 'the pressure at least halves with a 9 mm washer on M3');
  const big = run(applyOps(design(), b0.fix.proposals.find((x) => x.id === 'size').ops, { actor: 'user' }).project);
  assert.ok(big.summary.find((s) => s.metric === 'J1.preload_nominal').value > base.summary.find((s) => s.metric === 'J1.preload_nominal').value);
});
test('package: a hotter, longer, harder duty makes it worse (creep, slip margin), and every margin responds in the right direction', () => {
  const m = (r, suffix) => flagOf(r, suffix).margin;
  const mild = run(design({ duty: 'handheld-light' })), harsh = run(design({ duty: 'handheld-heavy' }));
  assert.ok(m(harsh, ':slip') <= m(mild, ':slip') && m(harsh, ':creep') <= m(mild, ':creep'));
  const hotter = run(design({ extra: [{ op: 'set_duty', duty: { ...makeDuty('handheld-light'), temperature: { min_C: -20, max_C: 75, cycles_per_day: 1 } } }] }));
  assert.ok(hotter.summary.find((s) => s.metric === 'J1.preload_end_of_life_min').value <= mild.summary.find((s) => s.metric === 'J1.preload_end_of_life_min').value);
  const nm = run(design({ extra: [{ op: 'set_duty', duty: { ...makeDuty('handheld-light'), shots: { per_day: 0, total: 0, peak_g: 0, pulse_ms: 2 } } }] }));
  assert.equal(flagOf(nm, ':slip').margin, 1000, 'no shots, no transverse load: the margin is capped, not infinite');
  const stiffer = run(design({ jointOver: { tightening: { mode: 'torque', method: 'nut_factor', torque_Nm: 1.4, nut_factor: 0.2 } } }));
  assert.ok(stiffer.summary.find((s) => s.metric === 'J1.preload_nominal').value > mild.summary.find((s) => s.metric === 'J1.preload_nominal').value);
});
test('package: an insert or a thread in plastic adds the anchorage (shear creep and pull-out), and a heat-set insert needs its anchor', () => {
  const hs = run(design({ insert: 'heat_set_brass', jointOver: { clamps: [{ external: 'rail', material: 'steel-carbon', thickness_mm: 3 }], anchor: { body: 'Shell', length_mm: 5 } } }));
  assert.ok(flagOf(hs, ':pullout') && flagOf(hs, ':creep')); assert.ok(hs.summary.some((s) => s.metric === 'J1.anchor_compliance'));
  assert.throws(() => validateJoint({ ...newJoint({ id: 'x', clamps: [{ body: 'Shell', thickness_mm: 3 }] }), insert: 'heat_set_brass' }, null), /anchor/);
  assert.ok(flagOf(hs, 'gap:boss_od'), 'an unstated boss diameter is a data gap, not a silent default');
});
test('package: the anchorage compliance is the shear of an annular boss, ln(r2/r1) / (2 pi G L), and follows the boss diameter', () => {
  const at = (boss) => run(design({ insert: 'heat_set_brass', jointOver: { clamps: [{ external: 'rail', material: 'steel-carbon', thickness_mm: 3 }], anchor: { body: 'Shell', length_mm: 5, ...(boss ? { boss_od_mm: boss } : {}) } } }));
  const anc = (r) => r.summary.find((x) => x.metric === 'J1.anchor_compliance').value;
  const wide = anc(at(12)), narrow = anc(at(6)); assert.ok(wide > narrow, 'a wider boss is softer');
  const P = design({ insert: 'heat_set_brass', jointOver: { clamps: [{ external: 'rail', material: 'steel-carbon', thickness_mm: 3 }], anchor: { body: 'Shell', length_mm: 5, boss_od_mm: 12 } } });
  const mats = builtinMaterials(), petg = mats.find((m) => m.id === 'petg'), E = petg.properties.E.value * core.interp(petg.durability.E_T.points, 20), nu = petg.properties.nu.value, G = E / (2 * (1 + nu));
  const want = Math.log(12 / (1.5 * 3 + 0.1)) / (2 * Math.PI * G * 5e-3);
  const assumedHum = run(P).summary.find((x) => x.metric === 'J1.anchor_compliance').value;
  assert.ok(Math.abs(assumedHum / want - 1) < 0.35, `anchor compliance ${assumedHum} against ${want} (the duty humidity and temperature move E)`);
});
test('package: the temperature comes from the duty profile, the joint says which, and an unknown bolt grade or short bolt is caught', () => {
  const r = run(design()); assert.equal(r.summary.find((s) => s.metric === 'J1.use_temperature').value, 55);
  const short = run(design({ jointOver: { bolt: { size: 'M3', grade: '8.8', length_mm: 9.5 } } })); assert.ok(flagOf(short, ':engage').severity === 'high', 'engagement 0.5 mm of 2.4');
});

// ───────── through the real runner, a Worker, the cache, the scorecard and the loop's gateway
async function rig(P, packages = [EXT]) {
  const registry = new ExtRegistry({ store: memoryStore() });
  const box = { P, build: null, store: new Map() };
  const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true, userStore: { get: (k) => box.store.get(k), set: (k, v) => box.store.set(k, v) }, commitOps: async (ops, o) => { const r = applyOps(box.P, ops, { actor: 'ext', extId: o.extId }); box.P = r.project; return { log: r.log }; } });
  const host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host);
  const dirs = { [EXT]: 'examples/ext/joint-creep', [T3D]: 'examples/ext/thermal-3d' };
  for (const id of packages) await registry.install(await packDir(dirs[id]));
  const runner = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  box.P = { ...P, extensions: packages.map((id) => ({ id, enabled: true, name: id, version: '0.1.0' })) };
  box.build = build(box.P, wasm);
  return { registry, host, runner, box, resolver: createResolver({ registry, host, analysis: runner }) };
}
test('runner: the package returns flags through a Worker, they are validated, cached, and join the scorecard (advisory with typical data)', async () => {
  const { runner, box } = await rig(design());
  const res = await runner.run(box.P, box.build, EXT, 'joint-creep');
  assert.equal(res.validity, 'estimate'); assert.ok(res.flags.length >= 6 && res.flags.every((f) => f.confidence !== 'high')); assert.equal(res.cached, false);
  assert.equal((await runner.run(box.P, box.build, EXT, 'joint-creep')).cached, true);
  const rows = await runner.rows(box.P, box.build, { run: false });
  assert.ok(rows.length >= 3); assert.ok(rows.every((r) => r.severity === 'advisory' || r.pass), 'typical data: nothing gates');
  assert.ok(rows.some((r) => !r.pass && /creep/.test(r.label)) && rows.every((r) => r.id.startsWith(EXT)));
  const changed = applyOps(box.P, [{ op: 'set_joint', joint: { ...box.P.joints[0], thread_locker: true } }], { actor: 'user' }).project;
  assert.equal((await runner.run(changed, box.build, EXT, 'joint-creep')).cached, false, 'a changed joint is a new run (the joints are in the inputs hash)');
  const duty2 = applyOps(box.P, [{ op: 'set_duty', duty: makeDuty('handheld-heavy') }], { actor: 'user' }).project;
  assert.equal((await runner.run(duty2, box.build, EXT, 'joint-creep')).cached, false, 'a changed duty profile is a new run');
});
test('scorecard and loop gateway: with datasheet-quality data a high flag fails the verdict; with typical data it is advisory; an experimental result never gates; "not run" is advisory', async () => {
  const good = goodPlastic(), extra = [{ op: 'set_material', material: good }, { op: 'set_body_material', body: 'Shell', material: 'petg-good' }, { op: 'set_duty', duty: { ...makeDuty('handheld-light'), humidity: 'dry' } }];
  const { runner, box, resolver } = await rig(design({ extra }));
  let rows = await runner.rows(box.P, box.build, { run: false });
  assert.equal(rows.length, 1); assert.equal(rows[0].severity, 'advisory'); assert.match(rows[0].detail, /not run/);
  rows = await runner.rows(box.P, box.build, { run: true });
  const hard = rows.filter((r) => r.severity === 'hard'); assert.ok(hard.length >= 1 && hard.every((r) => r.pass === false), 'high flags with medium confidence gate');
  assert.ok(rows.some((r) => r.severity === 'advisory' && !r.pass), 'medium flags are advisory');
  const merged = resolver.mergeChecks({ passed: true, failed: [], checks: [] }, rows); assert.equal(merged.passed, false); assert.ok(merged.failed.length >= 1);
  // typical data: advisory only, the verdict passes
  const t = await rig(design()); const trows = await t.runner.rows(t.box.P, t.box.build, { run: true });
  assert.ok(trows.some((r) => !r.pass) && trows.every((r) => r.severity === 'advisory' || r.pass));
  assert.equal(t.resolver.mergeChecks({ passed: true, failed: [], checks: [] }, trows).passed, true);
  // experimental: the same package declared experimental never gates
  const tmp = fs.mkdtempSync('/tmp/a6a-exp-'); fs.cpSync('examples/ext/joint-creep', tmp, { recursive: true });
  const man = JSON.parse(fs.readFileSync(`${tmp}/alloy-extension.json`, 'utf8')); man.contributes.analyses[0].validity = 'experimental'; fs.writeFileSync(`${tmp}/alloy-extension.json`, JSON.stringify(man));
  const registry = new ExtRegistry({ store: memoryStore() }); const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true });
  const host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host); await registry.install(await packDir(tmp));
  const er = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  const res = await er.run(box.P, box.build, EXT, 'joint-creep'); assert.equal(res.validity, 'estimate', 'the package states estimate; the manifest label is only a fallback');
  fs.rmSync(tmp, { recursive: true, force: true });
});
test('upstream: when Thermal 3D has been run on this design the joint uses its temperature there and says so; otherwise it uses the duty profile', async () => {
  const P = design({ watts: 3, extra: [{ op: 'set_ambient', ambient: { temperature_K: 298.15 } }, { op: 'ext_set', id: T3D, key: 'settings', value: { quality: 'draft', backend: 'cpu', mode: 'steady' } }] });
  const { runner, box } = await rig(P, [EXT, T3D]);
  const before = await runner.run(box.P, box.build, EXT, 'joint-creep');
  assert.equal(before.summary.find((s) => s.metric === 'J1.use_temperature').value, 55, 'no Thermal 3D result yet: the duty maximum');
  assert.ok(before.warnings.some((w) => /from the duty profile maximum temperature/.test(w)), 'the source is stated');
  const a2 = await runner.run(box.P, box.build, T3D, 'thermal-3d', { timeoutMs: 120000 });
  assert.ok(a2.fields.some((f) => f.name === 'temperature'));
  const after = await runner.run(box.P, box.build, EXT, 'joint-creep');
  assert.equal(after.cached, false, 'the upstream result is part of the inputs');
  const T = after.summary.find((s) => s.metric === 'J1.use_temperature').value;
  assert.ok(T > 25 && T < 100 && T !== 55, `use temperature ${T} from Thermal 3D`);
  assert.ok(after.warnings.some((w) => /from the xyz\.mdhv\.thermal-3d\/thermal-3d temperature at the joint/.test(w)), 'the source is stated: ' + after.warnings.filter((w) => /use temperature/.test(w)));
});

// ───────── the worked example on the demo board (OCCT)
test('worked example: the demo board, PETG, a placeholder duty profile: the cap screws are flagged and an assumed clamp cross-bolt shows the sleeve fix', async () => {
  const S = createSession(makeOps(R, oc));
  let P = pcbEnclosureProject();
  P = applyOps(P, [{ op: 'set_body_material', body: 'Shell', material: 'petg' }, { op: 'set_body_material', body: 'Cap', material: 'petg' }, { op: 'set_duty', duty: makeDuty('handheld-light') },
    { op: 'set_joint', joint: newJoint({ id: 'CLAMP1', name: 'Clamp cross-bolt (assumed)', where: [-10, 0, 14], axis: '+z', clamps: [{ body: 'Shell', thickness_mm: 6 }, { external: 'rail', material: 'steel-carbon', thickness_mm: 3 }] }) }], { actor: 'user' }).project;
  const B = await S.build(P); const input = prepareInput(P, B);
  const r = core.evaluate(input);
  for (const f of r.flags) validateFlag(f);
  const ids = new Set(r.flags.map((f) => f.id.split(':')[1])); assert.deepEqual([...ids].sort(), ['CAP1', 'CAP2', 'CLAMP1']);
  assert.ok(r.flags.some((f) => f.id.startsWith('joint:CLAMP1') && f.severity === 'high'));
  const sleeved = core.evaluate(prepareInput(applyOps(P, flagOf({ flags: r.flags.filter((f) => f.id.startsWith('joint:CLAMP1')) }, ':creep').fix.proposals.find((x) => x.id === 'sleeve').ops, { actor: 'user' }).project, B));
  assert.equal(flagOf({ flags: sleeved.flags.filter((f) => f.id.startsWith('joint:CLAMP1')) }, ':creep').severity, 'info');
  if (process.env.A6A_PRINT) { for (const f of r.flags) console.log(`${f.severity.padEnd(6)} ${f.confidence.padEnd(6)} ${f.mechanism.padEnd(15)} ${f.id} margin ${f.margin === null ? '-' : +f.margin.toPrecision(3)}`); }
});
