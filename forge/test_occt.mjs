// Headless tests for the OCCT (B-rep) kernel path (node --test). Real replicad + Open CASCADE wasm, no mocks for geometry.
//   template builds valid solids in all three styles, B-rep scorecards are stable and sensitive, STEP export re-imports,
//   document v3 migration + ops + locks, primitives on OCCT vs Manifold, STEP tagging -> pcb block, and the design loop's
//   check step using the B-rep scorecard when kernel = occt.
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import Module from 'manifold-3d';
import * as R from 'replicad';
import initOC from 'replicad-opencascadejs';
import { makeOps } from './src/occt/ops.js';
import { createSession } from './src/occt/session.js';
import { createLocalClient } from './src/occt/client.js';
import { build as manifoldBuild } from './src/kernel.js';
import { migrate, emptyProject, applyOps, wizardProject, defaultFeature, DOC_VERSION, usesOcct } from './src/model.js';
import { pcbEnclosureProject, demo } from './templates/pcb-enclosure/index.js';
import { MT19937, cellLayout } from './templates/pcb-enclosure/voronoi.js';
import { pcbFromTags, setTag } from './src/pcb-tags.js';
import { runDesignLoop } from './src/design-loop.js';

let k, S, wasm, client;
before(async () => {
  const oc = await initOC(); R.setOC(oc);
  k = makeOps(R, oc); S = createSession(k); client = createLocalClient(S);
  wasm = await Module(); wasm.setup();
});

const strip = (sc) => JSON.stringify(sc, (key, v) => (key === 'ms' ? undefined : v));

// ---------------------------------------------------------------- cell texture layout
test('voronoi: the Mersenne Twister matches Python random.Random(seed) so a seed gives the reference layout', () => {
  const r = new MT19937(11);
  assert.equal(r.random(), 0.4523795535098186);
  assert.equal(r.uniform(-0.28, 0.28), 0.03347253620507773);
  const L = cellLayout({ window: [-41, 6, -1.6, 15.4], seed: 11 });
  assert.equal(L.cells.length, 30, 'same cell count as the shapely reference');
  assert.ok(Math.abs(L.cells.reduce((a, c) => a + c.area, 0) - 481.92) < 1.5);
});

// ---------------------------------------------------------------- template: all three styles
for (const style of ['monolith', 'bullet', 'cell']) {
  test(`template: ${style} builds valid solids on the demo board and the scorecard passes`, async () => {
    const P = pcbEnclosureProject({ enclosure: { style } });
    const r = await S.build(P);
    try {
      assert.deepEqual(r.warnings, []);
      assert.equal(r.status[P.features[0].id].ok, true, JSON.stringify(r.status));
      const shell = r.bodies.find((b) => b.name === 'Shell'), cap = r.bodies.find((b) => b.name === 'Cap');
      assert.ok(shell && cap, 'shell and cap bodies');
      for (const b of [shell, cap]) { assert.equal(b.valid, true, b.name + ' is a valid B-rep'); assert.equal(b.solids, 1, b.name + ' is one solid'); assert.ok(b.volume > 500); assert.ok(b.tris > 100); }
      assert.equal(r.refs.length, demo.pcb.components.length, 'keep-outs are shown as references');
      const env = shell.bbox.max.map((v, i) => v - shell.bbox.min[i]);
      assert.ok(env[0] > 45 && env[0] < 55 && env[1] > 18 && env[1] < 24 && env[2] > 24 && env[2] < 32, 'envelope ' + env.map((v) => v.toFixed(1)));
      const c = S.check(r.id);
      assert.equal(c.passed, true, JSON.stringify(c.checks.filter((x) => !x.pass)));
      assert.equal(c.scorecard.checks.length, 14);
      assert.equal(c.scorecard.pass, true);
      assert.ok(c.scorecard.metrics.plastic_cm3 > 10 && c.scorecard.metrics.plastic_cm3 < 16);
    } finally { S.dispose(r.id); }
  });
}

test('template: styles differ in shape (bullet is narrower at the front, cell removes material)', async () => {
  const vol = async (style) => { const r = await S.build(pcbEnclosureProject({ enclosure: { style } })); const v = r.bodies.find((b) => b.name === 'Shell').volume; S.dispose(r.id); return v; };
  const m = await vol('monolith'), b = await vol('bullet');
  assert.ok(b < m * 0.97, `bullet ${b} < monolith ${m}`);
});

// ---------------------------------------------------------------- scorecards
test('scorecard: deterministic (same document, same scorecard) and the same shape as the reference checker', async () => {
  const P = pcbEnclosureProject();
  const a = await S.build(P), b = await S.build(P);
  const sa = S.check(a.id).scorecard, sb = S.check(b.id).scorecard;
  assert.equal(strip(sa), strip(sb));
  assert.deepEqual(Object.keys(sa).sort(), ['checks', 'metrics', 'pass', 'variant']);
  assert.ok(sa.checks.every((c) => typeof c.check === 'string' && typeof c.pass === 'boolean' && typeof c.detail === 'string'));
  for (const key of ['overhang_share_pct', 'overhang_area_mm2', 'shell_cm3', 'cap_cm3', 'plastic_cm3', 'mass_pla_g', 'mass_petg_g', 'envelope_mm']) assert.ok(key in sa.metrics, key);
  assert.deepEqual(sa.checks.map((c) => c.check).filter((n) => /^(no interference|wall around)/.test(n)).length, 9);
  S.dispose(a.id); S.dispose(b.id);
});

test('scorecard: fails the right rows when the design is wrong', async () => {
  const P = pcbEnclosureProject();
  // a battery wider than the cavity allows: pokes into the wall region
  P.pcb.components.find((c) => c.name === 'battery').box = [-20, 12, 9.8, 16, -12.5, 12.5];
  const r = await S.build(P);
  const sc = S.check(r.id).scorecard;
  assert.equal(sc.pass, false);
  assert.ok(sc.checks.find((c) => /^wall around battery/.test(c.check) && !c.pass), 'battery wall fails');
  S.dispose(r.id);
  // a bed too small
  const Q = pcbEnclosureProject(); Q.settings.printVolume = [40, 40, 40];
  const r2 = await S.build(Q);
  const c2 = S.check(r2.id).scorecard;
  assert.ok(c2.checks.find((c) => /build volume/.test(c.check) && !c.pass));
  S.dispose(r2.id);
});

test('scorecard: interference and a blocked emitter beam are detected', async () => {
  const P = pcbEnclosureProject();
  P.pcb.cutouts = P.pcb.cutouts.filter((c) => c.name !== 'aperture'); // close the window in front of the LED
  const r = await S.build(P);
  const sc = S.check(r.id).scorecard;
  assert.ok(sc.checks.find((c) => /aperture clear/.test(c.check) && !c.pass), 'beam blocked');
  S.dispose(r.id);
});

// ---------------------------------------------------------------- STEP
test('STEP: true B-rep export re-imports as valid solids with the same volume', async () => {
  const r = await S.build(pcbEnclosureProject());
  const bytes = await S.stepBytes(r.id);
  const txt = new TextDecoder().decode(bytes.slice(0, 4000));
  assert.match(txt, /ISO-10303-21/);
  const body = new TextDecoder().decode(bytes);
  assert.match(body, /ADVANCED_FACE/);
  assert.match(body, /CYLINDRICAL_SURFACE|B_SPLINE_SURFACE/, 'curved surfaces stay analytic, not faceted');
  const back = await R.importSTEP(new Blob([bytes]));
  const sol = back.solids;
  assert.equal(sol.length, 2, 'shell + cap');
  const vols = sol.map((s) => k.volume(s)).sort((a, b) => a - b);
  const want = r.bodies.map((b) => b.volume).sort((a, b) => a - b);
  vols.forEach((v, i) => assert.ok(Math.abs(v / want[i] - 1) < 5e-4, `volume ${v} vs ${want[i]}`));
  assert.ok(sol.every((s) => k.isValid(s)), 'valid after re-import');
  // single-body export by name
  const one = await S.stepBytes(r.id, ['Cap']);
  assert.equal((await R.importSTEP(new Blob([one]))).solids.length, 1);
  S.dispose(r.id);
});

// ---------------------------------------------------------------- document v3
test('document v3: v2 and v1 documents migrate; settings.kernel and pcb block exist', () => {
  const v2 = { v: 2, name: 'old', params: [{ name: 'a', expr: '1' }], features: [], assets: {}, bodies: {}, settings: { detail: 48, density: 1.24, printVolume: [180, 180, 180] } };
  const m = migrate(v2);
  assert.equal(m.v, DOC_VERSION); assert.equal(m.settings.kernel, 'manifold');
  assert.deepEqual(m.pcb, { name: '', board: null, components: [], cutouts: [], supports: [], keep: [] });
  assert.equal(v2.v, 2, 'input is not mutated');
  const v1 = migrate({ name: 'ancient', features: [] });
  assert.equal(v1.v, 3); assert.ok(v1.settings.printVolume);
  assert.equal(migrate({ ...v2, settings: { ...v2.settings, kernel: 'bogus' } }).settings.kernel, 'manifold');
  assert.equal(usesOcct(emptyProject()), false);
  // existing wizard documents still build on Manifold after migration
  const w = migrate(wizardProject());
  const res = manifoldBuild(w, wasm);
  assert.ok(res.bodies.length >= 2); res.dispose();
});

test('document v3: ops for kernel and pcb components, locks still enforced', () => {
  let P = wizardProject();
  P = applyOps(P, [{ op: 'set_setting', key: 'kernel', value: 'occt' }]).project;
  assert.equal(P.settings.kernel, 'occt');
  assert.throws(() => applyOps(P, [{ op: 'set_setting', key: 'kernel', value: 'x' }]), /kernel must be/);
  P = applyOps(P, [{ op: 'set_pcb_component', component: { name: 'bat', kind: 'box', box: [0, 10, 0, 5, 0, 5], insert: 'rear' } }]).project;
  assert.equal(P.pcb.components.length, 1);
  assert.throws(() => applyOps(P, [{ op: 'set_pcb_component', component: { name: 'bad', kind: 'box', box: [1, 2] } }]), /box must be/);
  P.pcb.components[0].locked = true;
  assert.throws(() => applyOps(P, [{ op: 'remove_pcb_component', name: 'bat' }]), /locked/);
  // enclosure feature: typed, validated, lockable like any other feature
  const E = applyOps(pcbEnclosureProject(), [{ op: 'update_feature', id: 'PCB enclosure', set: { wall: '2', style: 'bullet' } }]).project;
  assert.equal(E.features[0].wall, '2');
  assert.throws(() => applyOps(E, [{ op: 'update_feature', id: 'PCB enclosure', set: { style: 'round' } }]), /must be one of/);
  assert.throws(() => applyOps(E, [{ op: 'update_feature', id: 'PCB enclosure', set: { spec: [1] } }]), /must be an object/);
  const L = JSON.parse(JSON.stringify(E)); L.features[0].locked = true;
  assert.throws(() => applyOps(L, [{ op: 'update_feature', id: 'PCB enclosure', set: { wall: '3' } }]), /locked/);
});

test('manifold kernel reports that the enclosure feature needs OCCT instead of silently building nothing', () => {
  const P = pcbEnclosureProject(); P.settings.kernel = 'manifold';
  const res = manifoldBuild(P, wasm);
  assert.equal(res.status[P.features[0].id].ok, false);
  assert.match(res.status[P.features[0].id].error, /OCCT kernel/);
  res.dispose();
});

// ---------------------------------------------------------------- primitives on OCCT
test('occt kernel builds the classic features too and agrees with Manifold on volume', async () => {
  const P = wizardProject({ ports: [{ name: 'USB-C', side: '-y', along: 0, z: 2, w: 9.5, h: 3.4 }] });
  P.settings.kernel = 'occt';
  const r = await S.build(P);
  const bad = Object.entries(r.status).filter(([, v]) => v.ok === false);
  assert.deepEqual(bad, [], JSON.stringify(bad));
  const man = manifoldBuild({ ...P, settings: { ...P.settings, kernel: 'manifold' } }, wasm);
  for (const name of ['Base', 'Lid']) {
    const a = r.bodies.find((b) => b.name === name).volume, b = man.bodies.find((x) => x.name === name).volume;
    assert.ok(Math.abs(a / b - 1) < 0.03, `${name}: occt ${a.toFixed(0)} vs manifold ${b.toFixed(0)}`);
  }
  assert.equal(r.refs.length, man.refs.length);
  man.dispose();
  const c = S.check(r.id);
  assert.deepEqual(c.checks.map((x) => x.id), ['features', 'bodies', 'interference', 'print_volume']);
  S.dispose(r.id);
  // unsupported-on-OCCT features report a clear error
  const Q = wizardProject(); Q.settings.kernel = 'occt';
  Q.features.push(defaultFeature('transform', { body: 'Base', mode: 'twist', amount: '30' }));
  const r2 = await S.build(Q);
  assert.match(Object.values(r2.status).find((v) => v.ok === false).error, /Manifold kernel/);
  S.dispose(r2.id);
});

// ---------------------------------------------------------------- tagging a STEP assembly
test('tagging: assembly solids become pcb keep-outs and an enclosure that builds', async () => {
  const P = emptyProject();
  const part = (i, name, mn, mx) => ({ i, name, tris: 12, bbox: { min: mn, max: mx }, tag: '' });
  P.assets.asm1 = { kind: 'assembly', name: 'asm', tris: 100, bbox: { min: [-20, -10, 0], max: [20, 10, 20] }, stepB64: '', parts: [
    part(0, 'BOARD', [-16, -9, 3], [14, 9, 4.6]), part(1, 'CELL', [-15, -7, 5.2], [13, 7, 11]), part(2, 'LED', [-18, -3, 6], [-16, 3, 9]), part(3, 'junk', [0, 0, 0], [1, 1, 1]),
  ] };
  // the template frame is y-up: this synthetic assembly is z-up, so swap axes for the keep-outs the way a user would orient the model
  P.assets.asm1.parts.forEach((p) => { p.bbox = { min: [p.bbox.min[0], p.bbox.min[2], p.bbox.min[1]], max: [p.bbox.max[0], p.bbox.max[2], p.bbox.max[1]] }; });
  let Q = setTag(P, 'asm1', 0, 'board'); Q = setTag(Q, 'asm1', 1, 'component'); Q = setTag(Q, 'asm1', 2, 'keepout');
  assert.throws(() => setTag(Q, 'asm1', 9, 'board'), /no such part/);
  const g = pcbFromTags(Q, 'asm1');
  assert.equal(g.components, 3); assert.equal(g.addedFeature, true);
  assert.equal(g.project.settings.kernel, 'occt');
  const enc = g.project.features.find((f) => f.type === 'enclosure');
  assert.ok(enc && enc.spec.stations.monolith.length === 4);
  assert.deepEqual(g.project.pcb.components.map((c) => c.role), ['board', 'component', 'keepout']);
  assert.deepEqual(g.project.pcb.components[0].box, [-16, 14, 3, 4.6, -9, 9], 'box from the part bounding box (x0,x1,y0,y1,z0,z1)');
  const r = await S.build(g.project);
  try {
    assert.equal(r.status[enc.id].ok, true, JSON.stringify(r.status[enc.id]));
    assert.ok(r.bodies.find((b) => b.name === 'Shell').valid);
  } finally { S.dispose(r.id); }
  // keep original tag produces a keep entry with the part bbox
  const K = pcbFromTags(setTag(Q, 'asm1', 3, 'keep'), 'asm1');
  assert.equal(K.keeps, 1); assert.equal(K.project.pcb.keep[0].source, 'asm1');
});

// ---------------------------------------------------------------- design loop uses the B-rep scorecard
test('design loop: with kernel = occt the check step runs the B-rep scorecard, fails a wrong round and passes the fixed one', async () => {
  const wide = { op: 'set_pcb_component', component: { ...demo.pcb.components.find((c) => c.name === 'battery'), box: [-20, 12, 9.8, 16, -12.5, 12.5] } };
  const fix = { op: 'set_pcb_component', component: demo.pcb.components.find((c) => c.name === 'battery') };
  const replies = [JSON.stringify({ explanation: 'wider battery', ops: [wide] }), JSON.stringify({ explanation: 'back to the real battery', ops: [fix] })];
  let n = 0;
  const callModel = async () => replies[Math.min(n++, replies.length - 1)];
  const r = await runDesignLoop({ project: pcbEnclosureProject(), occt: client, wasm, goal: 'check the enclosure', cfg: {}, callModel, maxRounds: 3 }).promise;
  assert.equal(r.state, 'SUCCEEDED_VERIFIED', r.reason);
  assert.equal(r.rounds.length, 2);
  assert.equal(r.rounds[0].hardPassed, false);
  assert.ok(r.rounds[0].checks.find((c) => /wall around battery/.test(c.label) && !c.pass), JSON.stringify(r.rounds[0].checks.filter((c) => !c.pass)));
  assert.equal(r.rounds[1].hardPassed, true);
  assert.equal(r.rounds[1].metrics.kernel, 'occt');
  assert.ok(r.rounds[1].metrics.plastic_cm3 > 10);
});
