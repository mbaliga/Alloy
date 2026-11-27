// Headless tests for the Layers sheet's state (ext/layers.js), the `vectors` overlay contract (ext/api.js) and its geometry helpers
// (ext/overlay.js). The sheet itself is exercised in a browser by test_ui_layers.mjs. Run by npm test.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createLayerStore, groupLayers, layerReadout, LAYERS_KEY, MIN_OPACITY, MAX_LAYERS_KEPT } from './src/ext/layers.js';
import { ExtApi } from './src/ext/api.js';
import { vectorMagnitudes, vectorSegments, vectorScale, vectorFieldSamples, rangeOf, nearestPoint } from './src/ext/overlay.js';
import { API } from './src/ext/api-spec.js';

const mem = () => { const m = new Map(); return { m, getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)) }; };
const throwing = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('quota'); } };

test('layer state: defaults are on at full opacity, set clamps opacity', () => {
  const s = createLayerStore({ storage: mem() });
  assert.deepEqual(s.get('a:x'), { on: true, opacity: 1 });
  s.set('a:x', { opacity: 0.5 }); assert.deepEqual(s.get('a:x'), { on: true, opacity: 0.5 });
  s.set('a:x', { opacity: 0 }); assert.equal(s.opacity('a:x'), MIN_OPACITY);
  s.set('a:x', { opacity: 7 }); assert.equal(s.opacity('a:x'), 1);
  s.set('a:x', { opacity: 'nope' }); assert.equal(s.opacity('a:x'), 1);
  s.set('a:x', { on: false }); assert.equal(s.isOn('a:x'), false); assert.equal(s.opacity('a:x'), 1, 'switching off keeps the opacity');
});

test('layer state: show all, hide all and solo', () => {
  const s = createLayerStore({ storage: mem() }), ids = ['a:1', 'a:2', 'b:3'];
  s.hideAll(ids); assert.deepEqual(ids.map((i) => s.isOn(i)), [false, false, false]); assert.equal(s.soloed(ids), null);
  s.showAll(ids); assert.deepEqual(ids.map((i) => s.isOn(i)), [true, true, true]);
  s.set('a:2', { opacity: 0.3 }); s.hideAll(ids); s.showAll(ids); assert.equal(s.opacity('a:2'), 0.3, 'show/hide all keep opacity');
  s.solo(ids, 'b:3'); assert.deepEqual(ids.map((i) => s.isOn(i)), [false, false, true]); assert.equal(s.soloed(ids), 'b:3');
  s.solo(ids, 'a:1'); assert.deepEqual(ids.map((i) => s.isOn(i)), [true, false, false], 'solo moves');
  s.solo(ids, 'a:1'); assert.deepEqual(ids.map((i) => s.isOn(i)), [true, true, true], 'solo on the soloed layer restores all');
  assert.equal(s.soloed(['a:1']), null, 'a single layer is never "soloed"');
});

test('layer state: persisted by layer id, survives a new store (a reload) and a layer being cleared and re-added', () => {
  const st = mem(), a = createLayerStore({ storage: st });
  a.set('xyz.ext:temp', { on: false, opacity: 0.4 }); a.set('analysis:T', { opacity: 0.7 });
  const saved = JSON.parse(st.m.get(LAYERS_KEY)); assert.deepEqual(saved.layers['xyz.ext:temp'], { on: false, opacity: 0.4 });
  const b = createLayerStore({ storage: st });
  assert.deepEqual(b.get('xyz.ext:temp'), { on: false, opacity: 0.4 }); assert.equal(b.opacity('analysis:T'), 0.7);
  assert.deepEqual(b.get('never:seen'), { on: true, opacity: 1 });
  // state is by id and independent of whether an overlay currently exists, so a re-run (clear, then set the same id) finds it again
  assert.equal(b.isOn('xyz.ext:temp'), false);
});

test('layer state: storage that throws, is missing or holds junk never breaks it', () => {
  const t = createLayerStore({ storage: throwing }); t.set('a:1', { on: false, opacity: 0.5 });
  assert.deepEqual(t.get('a:1'), { on: false, opacity: 0.5 }, 'kept in memory for the page');
  const n = createLayerStore({ storage: null }); n.set('a:1', { on: false }); assert.equal(n.isOn('a:1'), false);
  for (const junk of ['{', 'null', '[]', '{"layers":5}', '{"layers":{"a":7,"b":{"on":false,"opacity":"x"}}}']) {
    const j = mem(); j.m.set(LAYERS_KEY, junk); const s = createLayerStore({ storage: j }); assert.deepEqual(s.get('zzz'), { on: true, opacity: 1 });
  }
  const j = mem(); j.m.set(LAYERS_KEY, '{"layers":{"b":{"on":false,"opacity":"x"}}}'); assert.deepEqual(createLayerStore({ storage: j }).get('b'), { on: false, opacity: 1 });
  // no storage argument and no localStorage global (Node): falls back without throwing
  assert.doesNotThrow(() => createLayerStore().set('a:1', { on: false }));
});

test('layer state: change events fire, and the stored list is capped', () => {
  const st = mem(), s = createLayerStore({ storage: st }); let n = 0; const off = s.onChange(() => n++);
  s.set('a:1', { on: false }); s.showAll(['a:1']); assert.equal(n, 2); off(); s.set('a:1', { on: false }); assert.equal(n, 2);
  for (let i = 0; i < MAX_LAYERS_KEPT + 20; i++) s.set('x:' + i, { on: false });
  assert.equal(Object.keys(JSON.parse(st.m.get(LAYERS_KEY)).layers).length, MAX_LAYERS_KEPT);
  assert.equal(s.isOn('x:' + (MAX_LAYERS_KEPT + 19)), false, 'the newest survive');
});

test('layers: grouped by analysis or extension, with a readout of unit and range', () => {
  const L = (extId, id, ov, name) => ({ key: `${extId}:${id}`, extId, id, name, ov, range: ov.kind === 'box' ? [0, 1] : rangeOf(ov) });
  const pts = { kind: 'points', values: Float32Array.from([20, 85]), unit: '°C', label: 'Temp' };
  const layers = [L('analysis', 'T', { kind: 'surface', values: Float32Array.from([1, 3]), unit: 'mm', label: 'Disp' }), L('xyz.a', 'p', pts, 'Probe'), L('analysis', 'U', { kind: 'vectors', values: Float32Array.from([0, 2]), unit: 'm/s', label: 'Air', lines: new Uint32Array([0]) }), L('xyz.a', 'b', { kind: 'box', min: [0, 0, 0], max: [1, 1, 1], label: 'Box' }, 'Probe')];
  const g = groupLayers(layers);
  assert.deepEqual(g.map((x) => [x.name, x.layers.length]), [['Analysis', 2], ['Probe', 2]]);
  assert.equal(layerReadout(layers[1]), 'points · unit °C · range 20 to 85 °C');
  assert.match(layerReadout(layers[0]), /^surface map · unit mm · range 1 to 3 mm$/);
  assert.match(layerReadout(layers[2]), /^streamlines · speed unit m\/s · range 0 to 2 m\/s$/);
  assert.equal(layerReadout(layers[3]), 'box · no value');
});

// ───────── vectors overlay: the contract
const pkg = { manifest: { id: 'xyz.t.fake', name: 'Fake', permissions: ['scene.overlay'], contributes: { overlays: [{ id: 'x', label: 'X' }] } }, source: '' };
const mk = () => { const log = []; return { api: new ExtApi({ onOverlay: (...a) => log.push(a) }), log }; };
const bad = (p, code) => assert.rejects(p, (e) => { assert.equal(e.code, code, e.message); return true; });
const F = (a) => Float32Array.from(a);

test('vectors overlay: accepted, magnitude computed by the host, validity defaults to experimental', async () => {
  const { api, log } = mk();
  await api.call(pkg, 'scene.overlay', ['x', { kind: 'vectors', positions: F([0, 0, 0, 1, 0, 0]), vectors: F([3, 4, 0, 0, 0, 2]), unit: 'm/s', label: 'Air' }]);
  const ov = log[0][2]; assert.equal(ov.kind, 'vectors'); assert.deepEqual([...ov.values], [5, 2]); assert.equal(ov.validity, 'experimental'); assert.equal(ov.unit, 'm/s'); assert.equal(ov.lines, null);
  await api.call(pkg, 'scene.overlay', ['x', { kind: 'vectors', positions: F([0, 0, 0, 1, 0, 0, 2, 0, 0]), vectors: F([1, 0, 0, 1, 0, 0, 1, 0, 0]), lines: new Uint32Array([0, 2]), scale: 2, validity: 'estimate' }]);
  assert.deepEqual([...log[1][2].lines], [0, 2]); assert.equal(log[1][2].scale, 2); assert.equal(log[1][2].validity, 'estimate');
});

test('vectors overlay: malformed overlays are refused with E_ARG', async () => {
  const { api } = mk(), base = { kind: 'vectors', positions: F([0, 0, 0, 1, 0, 0]), vectors: F([1, 0, 0, 1, 0, 0]) };
  const cases = [
    { ...base, vectors: F([1, 0, 0]) }, { ...base, vectors: [1, 0, 0, 1, 0, 0] }, { ...base, positions: [0, 0, 0, 1, 0, 0] }, { ...base, positions: F([0, 0, 0, 1, 0]), vectors: F([1, 0, 0, 1, 0]) },
    { ...base, vectors: F([1, 0, NaN, 1, 0, 0]) }, { ...base, positions: F([0, 0, Infinity, 1, 0, 0]) },
    { ...base, lines: [0] }, { ...base, lines: new Uint32Array([1]) }, { ...base, lines: new Uint32Array([0, 0]) }, { ...base, lines: new Uint32Array([0, 2]) }, { ...base, lines: new Uint32Array([]) },
    { ...base, scale: 0 }, { ...base, scale: -1 }, { ...base, scale: 'x' },
  ];
  for (const c of cases) await bad(api.call(pkg, 'scene.overlay', ['x', c]), 'E_ARG');
  await bad(mk().api.call({ ...pkg, manifest: { ...pkg.manifest, permissions: [] } }, 'scene.overlay', ['x', base]), 'E_PERM');
  assert.ok(API.find((a) => a.name === 'scene.overlay').doc.includes('`vectors`'), 'the API spec documents the kind');
});

test('vectors geometry: arrows and streamlines, scale, colour range and probe vector', () => {
  const positions = F([0, 0, 0, 10, 0, 0, 20, 0, 0]), vectors = F([1, 0, 0, 0, 2, 0, 0, 0, 0]), values = vectorMagnitudes(vectors);
  assert.deepEqual([...values], [1, 2, 0]);
  const ov = { kind: 'vectors', positions, vectors, values, lines: null, scale: 3, unit: 'm/s', label: 'A' };
  assert.deepEqual(vectorScale(ov), { mmPerUnit: 3, maxLen: 6 });
  const auto = vectorScale({ ...ov, scale: null }); assert.ok(auto.maxLen > 0 && auto.maxLen <= 20 / 4 + 1e-9, 'auto length stays a fraction of the extent');
  const a = vectorSegments(ov); // two non-zero samples x (shaft + 2 barbs) x 2 vertices x 3
  assert.equal(a.positions.length, 2 * 3 * 2 * 3); assert.equal(a.values.length, 2 * 3 * 2);
  assert.deepEqual([...a.positions.slice(0, 6)], [0, 0, 0, 3, 0, 0], 'the shaft starts at the sample and is |v| x scale long, along v');
  assert.ok([...a.positions].every(Number.isFinite));
  const s = vectorSegments({ ...ov, lines: new Uint32Array([0, 2]) }); // line 0 = samples 0,1 (one segment), line 1 = sample 2 (none)
  assert.equal(s.positions.length, 6); assert.deepEqual([...s.values], [1, 1]);
  assert.deepEqual(rangeOf(ov), [0, 2]);
  const hit = nearestPoint(ov, (x, y, z) => ({ x, y, depth: z }), 10, 1); assert.equal(hit.index, 1); assert.equal(hit.value, 2);
});

test('vectors from an analysis field: strided, NaN and zero cells skipped, capped', () => {
  const dims = [4, 3, 2], n = 24, data = new Float32Array(n * 3);
  for (let c = 0; c < n; c++) data[3 * c] = 1; data[0] = NaN; data[3] = 0; data[4] = 0; data[5] = 0;
  const v = vectorFieldSamples({ data, grid: { origin: [10, 0, 0], h: 2, dims } });
  assert.equal(v.values.length, 22); assert.equal(v.positions.length, 66); assert.deepEqual([...v.positions.slice(0, 3)], [15, 1, 1], 'cell centres in mm');
  const big = vectorFieldSamples({ data: new Float32Array(3 * 100 ** 3).fill(1), grid: { origin: [0, 0, 0], h: 1, dims: [100, 100, 100] } }, 4000);
  assert.ok(big.values.length <= 4000 * 1.3 && big.values.length > 500, `${big.values.length} arrows`);
});
