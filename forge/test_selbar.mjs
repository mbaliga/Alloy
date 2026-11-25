// Headless tests for the universal selection actions (node --test). Real Manifold builds, fake UI context.
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { wizardProject, clone, defaultFeature } from './src/model.js';
import { makeScope } from './src/expr.js';
import { selectionActions, placeBeside } from './src/selbar.js';

let wasm;
before(async () => { wasm = await Module(); wasm.setup(); });

const writeDelta = (orig, a, b) => String(+(+b).toFixed(3)); // the real one keeps expressions; plain numbers are enough here

function harness(project) {
  const S = { project, sel: null, selBody: null, solo: null, clip: null, gizmo: 'translate', mode: 'orbit', result: null };
  const said = [];
  const rebuild = () => { if (S.result) S.result.dispose(); S.result = build(S.project, wasm); };
  const ctx = {
    S, said, view: { fit: (bb) => said.push('fit ' + (bb ? 'box' : 'all')) },
    commit: (np) => { S.project = np; rebuild(); }, refreshSel() {}, refreshBar() {}, setGizmo: (m) => { S.gizmo = m; },
    setBodyMeta: (name, patch) => { const np = clone(S.project); np.bodies = np.bodies || {}; np.bodies[name] = { ...(np.bodies[name] || {}), ...patch }; ctx.commit(np); },
    say: (t) => said.push(t), blip() {}, writeDelta, doExport: (k, b) => said.push(`export ${k} ${b}`),
    ev: (e) => makeScope(S.project.params).ev(e),
  };
  rebuild();
  const act = (id) => { const a = selectionActions(ctx).find((x) => x.id === id); assert.ok(a, `action ${id} offered`); assert.ok(!a.disabled, `action ${id} enabled`); a.run(); };
  const ids = () => selectionActions(ctx).map((a) => a.id);
  const body = (n) => S.result.bodies.find((b) => b.name === n);
  return { S, ctx, act, ids, body, said };
}

test('nothing selected and an empty clipboard: no actions', () => {
  const { ids } = harness(wizardProject());
  assert.deepEqual(ids(), []);
});

test('the same actions are offered in Select, Pick and Measure modes', () => {
  const { S, ids } = harness(wizardProject());
  S.selBody = S.result.bodies[0].name;
  const seen = ['orbit', 'pick', 'measure'].map((m) => { S.mode = m; return ids().join(','); });
  assert.equal(new Set(seen).size, 1);
  for (const id of ['move', 'rotate', 'dup', 'copy', 'hide', 'solo', 'lock', 'frame', 'del']) assert.ok(ids().includes(id), id);
});

test('duplicate a body: a new body, same volume, moved beside the original', () => {
  const { S, act, body } = harness(wizardProject());
  const name = S.result.bodies[0].name, n0 = S.result.bodies.length;
  const v0 = body(name).volume, bb0 = body(name).bbox;
  S.selBody = name; act('dup');
  assert.equal(S.result.bodies.length, n0 + 1);
  const copy = body(S.selBody);
  assert.notEqual(S.selBody, name);
  assert.ok(Math.abs(copy.volume - v0) / v0 < 1e-6, 'same volume');
  const apart = copy.bbox.min[0] >= bb0.max[0] - 1e-6 || copy.bbox.max[0] <= bb0.min[0] + 1e-6 || copy.bbox.min[1] >= bb0.max[1] - 1e-6 || copy.bbox.max[1] <= bb0.min[1] + 1e-6;
  assert.ok(apart, 'placed clear of the original');
  const pv = S.project.settings.printVolume;
  assert.ok(copy.bbox.min[0] >= -pv[0] / 2 && copy.bbox.max[0] <= pv[0] / 2 && copy.bbox.min[1] >= -pv[1] / 2 && copy.bbox.max[1] <= pv[1] / 2, 'and still on the plate');
});

test('copy and paste a body, then delete it', () => {
  const { S, act, body } = harness(wizardProject());
  const name = S.result.bodies[0].name, n0 = S.result.bodies.length;
  S.selBody = name; act('copy');
  S.selBody = null; S.sel = null; act('paste');
  assert.equal(S.result.bodies.length, n0 + 1);
  const pasted = S.selBody; assert.ok(body(pasted));
  act('del');
  assert.equal(S.result.bodies.length, n0);
  assert.ok(!S.project.features.some((f) => f.body === pasted), 'its features are gone');
});

test('duplicate a feature lands 5 mm along x; copy/paste a feature into another body', () => {
  const { S, act, ctx } = harness(wizardProject());
  const f = S.project.features.find((x) => Array.isArray(x.pos) && x.body !== '*');
  S.sel = f.id; act('dup');
  const g = S.project.features.find((x) => x.id === S.sel);
  assert.equal(g.name, f.name + ' copy');
  assert.equal(ctx.ev(g.pos[0]), ctx.ev(f.pos[0]) + 5);
  act('copy');
  const other = S.result.bodies.map((b) => b.name).find((n) => n !== f.body);
  S.sel = null; S.selBody = other; act('paste');
  assert.equal(S.project.features.find((x) => x.id === S.sel).body, other);
});

test('hide and lock: features suppress, locked items cannot move or be deleted', () => {
  const { S, act, ctx } = harness(wizardProject());
  const by = () => Object.fromEntries(selectionActions(ctx).map((a) => [a.id, a]));
  const f = S.project.features.find((x) => x.body !== '*' && x.type !== 'split' && Array.isArray(x.pos));
  S.sel = f.id; act('hide');
  assert.equal(S.project.features.find((x) => x.id === f.id).suppressed, true);
  act('hide'); act('lock');
  assert.ok(by().del.disabled && by().move.disabled, 'locked feature: no move, no delete');
  S.sel = null; S.selBody = f.body; act('lock');
  const b = by();
  assert.ok(b.del.disabled && b.move.disabled && !b.hide.disabled, 'locked body: hide still allowed');
});

test('a body made by a split cannot be duplicated or deleted on its own', () => {
  const p = wizardProject();
  p.features.push(defaultFeature('split', { body: 'Base', axis: 'z', offset: '4', other: 'BaseTop' }));
  const { S, ctx } = harness(p);
  assert.ok(S.result.bodies.some((b) => b.name === 'BaseTop'), 'split made a body');
  S.selBody = 'BaseTop';
  const a = Object.fromEntries(selectionActions(ctx).map((x) => [x.id, x]));
  assert.ok(a.del.disabled && a.dup.disabled);
  assert.match(a.del.title, /split/);
});

test('isolate and frame work on a body', () => {
  const { S, act, said } = harness(wizardProject());
  S.selBody = S.result.bodies[0].name;
  act('solo'); assert.equal(S.solo, S.selBody);
  act('frame'); assert.ok(said.includes('fit box'));
});

test('a move transform added for a body builds and shifts it (what a body drag writes)', () => {
  const { S, ctx, body } = harness(wizardProject());
  const name = S.result.bodies[0].name; const x0 = body(name).bbox.min[0];
  const np = clone(S.project);
  np.features.push(defaultFeature('transform', { body: name, mode: 'move', offset: ['12', '0', '0'] }));
  ctx.commit(np);
  assert.ok(Math.abs(body(name).bbox.min[0] - (x0 + 12)) < 1e-6);
});

test('placement: beside the original, on the plate, clear of other bodies', () => {
  const bb = { min: [60, -10, 0], max: [80, 10, 5] };
  assert.deepEqual(placeBeside(bb, [bb], [180, 180, 180]), [-25, 0, 0], '+x would leave the plate, so -x');
  const blocker = { min: [20, -10, 0], max: [55, 10, 5] };
  assert.deepEqual(placeBeside(bb, [bb, blocker], [180, 180, 180]), [0, 25, 0], '-x is taken, so +y');
});
