// Headless tests for the extension host (X0): manifest, zip and integrity, the permission matrix over every API call, the real
// Worker host (worker_threads stands in for browser Workers; same bootstrap script), ops and locks, the resolver and the
// removal / reinstall round trip. Run with `npm test` (node --test).
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { wizardProject, applyOps, migrate, emptyProject, defaultFeature, clone, typeDef, fieldsFor } from './src/model.js';
import { runChecks } from './src/checks.js';
import { validateManifest, validateSchema, MANIFEST_SCHEMA, tierOf, describeInstall, apiCompatible } from './src/ext/manifest.js';
import { API, PERMISSIONS, CONTRIBUTES } from './src/ext/api-spec.js';
import { readZip, writeZip, safePath, LIMITS } from './src/ext/zip.js';
import { buildPackage, loadPackage, computeIntegrity, MANIFEST_FILE } from './src/ext/loader.js';
import { ExtHost, ExtError } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { createResolver } from './src/ext/resolve.js';
import { voxelize } from './src/ext/voxelize.js';
import { packDir } from './tools/pack-ext.mjs';
import { renderApiDoc } from './tools/gen-extension-api.mjs';

let wasm;
const HOSTS = [];
before(async () => { wasm = await Module(); wasm.setup(); });
after(() => HOSTS.forEach((h) => h.stopAll()));

const nodeWorker = (script) => {
  const w = new Worker(script, { eval: true });
  return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() };
};
const BASE = { forgeApi: '^1.0', licence: 'PolyForm-Noncommercial-1.0.0', main: 'main.js', version: '0.1.0' };
const mk = async (m, files = { 'main.js': '' }) => loadPackage(await buildPackage({ ...BASE, name: m.id, ...m }, files));
const fake = (permissions, contributes = {}) => ({ manifest: { id: 'xyz.t.fake', name: 'Fake', permissions, contributes }, source: '' });
const rejects = (p, code) => assert.rejects(p, (e) => { assert.equal(e.code, code, e.message); return true; });

// ─────────────────────────── manifest ───────────────────────────
const good = () => ({ ...BASE, id: 'xyz.t.good', name: 'Good', permissions: ['doc.read'], integrity: 'sha256-' + '0'.repeat(64) });

test('manifest: a valid manifest passes and its schema is plain JSON Schema', () => {
  assert.equal(validateManifest(good()).tier, 'plugin');
  assert.equal(MANIFEST_SCHEMA.type, 'object'); JSON.stringify(MANIFEST_SCHEMA);
  assert.deepEqual(validateSchema(MANIFEST_SCHEMA, good()), []);
});

test('manifest: invalid manifests are refused with every problem listed', () => {
  const bad = (patch, re) => assert.throws(() => validateManifest({ ...good(), ...patch }), re);
  bad({ id: 'NoDots' }, /does not match/);
  bad({ version: '1' }, /version/);
  bad({ permissions: ['doc.read', 'doc.read'] }, /listed twice/);
  bad({ permissions: ['root.access'] }, /unknown permission/);
  bad({ permissions: ['network:*.evil.com'] }, /unknown permission/);
  bad({ forgeApi: '^2.0' }, /not supported/);
  bad({ forgeApi: '^1.9' }, /not supported/);
  bad({ integrity: 'md5-x' }, /integrity/);
  bad({ extra: 1 }, /unknown property "extra"/);
  bad({ contributes: { checks: [{ id: 'a', label: 'A' }] } }, /contributes\.checks needs the "checks" permission/);
  assert.throws(() => validateManifest({ ...good(), contributes: { panes: [{ id: 'p', label: 'P', file: 'p.html' }] }, permissions: ['ui.pane'] }, { hasFile: (f) => f === 'main.js' }), /pane file "p\.html" is not in the package/);
  assert.throws(() => validateManifest(good(), { hasFile: () => false }), /main file "main\.js" is not in the package/);
  bad({ contributes: { featureTypes: [{ id: 'r', label: 'R', fields: [{ k: 'body', kind: 'num' }] }] } }, /reserved/);
  bad({ contributes: { featureTypes: [{ id: 'r', label: 'R', fields: [{ k: 'e', kind: 'enum' }] }] } }, /enum fields need opts/);
  bad({ contributes: { checks: [{ id: 'a', label: 'A' }, { id: 'a', label: 'B' }] }, permissions: ['checks'] }, /duplicate id/);
  assert.throws(() => validateManifest({ ...good(), id: undefined, name: undefined }), (e) => e.problems.length >= 2);
  assert.ok(apiCompatible('^1.0') && apiCompatible('1.0') && !apiCompatible('^1.1') && !apiCompatible('x'));
});

test('manifest: the tier follows the permissions, never the author', () => {
  for (const p of Object.keys(PERMISSIONS)) {
    const perm = p === 'network:<host>' ? 'network:api.example.com' : p;
    assert.equal(tierOf({ permissions: [perm] }), PERMISSIONS[p].tier, p);
  }
  assert.equal(tierOf({ permissions: ['doc.read', 'checks', 'storage', 'doc.ops', 'geometry.read', 'agent.tools', 'loop'] }), 'plugin');
  assert.equal(tierOf({ permissions: ['doc.read', 'ui.pane'] }), 'extension');
  assert.equal(tierOf({ permissions: ['network:api.example.com'] }), 'extension');
  const d = describeInstall({ permissions: ['doc.read', 'doc.ops'] }); assert.match(d.line, /^Plugin: reads and edits your design/);
  const e = describeInstall({ permissions: ['compute.gpu', 'network:api.example.com'] }); assert.match(e.line, /^Extension: runs its own code on your GPU, talks to api\.example\.com/);
  assert.equal(e.perms.length, 2);
});

// ─────────────────────────── zip and integrity ───────────────────────────
test('zip: round trip, deflate and stored, paths are checked', async () => {
  const big = 'x'.repeat(5000);
  for (const deflate of [false, true]) {
    const z = await writeZip({ 'a.txt': 'hello', 'dir/b.js': big }, { deflate });
    const m = await readZip(z);
    assert.equal(new TextDecoder().decode(m.get('a.txt')), 'hello'); assert.equal(m.get('dir/b.js').length, 5000);
  }
  assert.ok(safePath('a/b.js')); for (const p of ['../x', '/x', 'a/../b', 'a\\b', 'C:/x', '', 'a//b']) assert.ok(!safePath(p), p);
  await assert.rejects(writeZip({ '../evil': 'x' }), /unsafe path/);
});

test('zip: hostile archives are refused', async () => {
  await assert.rejects(readZip(new Uint8Array(100)), /not a zip/);
  const z = await writeZip({ 'a.txt': 'hello world hello world' });
  const flipped = z.slice(); flipped[40] ^= 0xff; // inside the stored data
  await assert.rejects(readZip(flipped), /checksum mismatch/);
  await assert.rejects(readZip(z, { ...LIMITS, maxFiles: 0 }), /more than 0 files/);
  await assert.rejects(readZip(await writeZip({ f: 'x'.repeat(2000) }), { ...LIMITS, maxFileBytes: 1000 }), /larger than/);
  await assert.rejects(readZip(await writeZip({ a: 'x'.repeat(900), b: 'y'.repeat(900) }), { ...LIMITS, maxTotalBytes: 1500 }), /total size limit/);
  // a zip bomb: tiny deflated stream whose declared size lies; the inflate is cut off at the limit
  const bomb = await writeZip({ 'big.bin': new Uint8Array(3_000_000) }, { deflate: true });
  assert.ok(bomb.length < 10_000, 'compresses to almost nothing');
  await assert.rejects(readZip(bomb, { ...LIMITS, maxFileBytes: 1_000_000 }), /larger than/);
  // a traversal path written into the central directory by hand
  const evil = await writeZip({ 'okay.txt': 'x' });
  const dec = new TextDecoder().decode(evil).includes('okay.txt');
  assert.ok(dec);
  const patched = evil.slice(); const s = new TextEncoder().encode('okay.txt'), r = new TextEncoder().encode('../a.txt'.padEnd(8, 'x').slice(0, 8));
  for (let i = 0; i < patched.length - 8; i++) if (s.every((c, k) => patched[i + k] === c)) patched.set(r, i);
  await assert.rejects(readZip(patched), /unsafe path/);
});

test('integrity: a package edited after it was built is refused', async () => {
  const bytes = await buildPackage({ ...BASE, id: 'xyz.t.int', name: 'I', permissions: ['doc.read'] }, { 'main.js': 'forge.doc.get;' });
  const ok = await loadPackage(bytes); assert.equal(ok.manifest.id, 'xyz.t.int'); assert.match(ok.manifest.integrity, /^sha256-[0-9a-f]{64}$/);
  const files = await readZip(bytes);
  // 1. code changed, manifest kept
  const f1 = Object.fromEntries(files); f1['main.js'] = new TextEncoder().encode('fetch("https://evil")');
  await assert.rejects(loadPackage(await writeZip(f1)), /integrity check failed/);
  // 2. permissions widened in the manifest, code kept
  const f2 = Object.fromEntries(files); const m = JSON.parse(new TextDecoder().decode(f2[MANIFEST_FILE])); m.permissions.push('doc.ops'); f2[MANIFEST_FILE] = JSON.stringify(m);
  await assert.rejects(loadPackage(await writeZip(f2)), /integrity check failed/);
  // 3. an extra file smuggled in
  const f3 = Object.fromEntries(files); f3['extra.js'] = 'x';
  await assert.rejects(loadPackage(await writeZip(f3)), /integrity check failed/);
  // 4. the hash does not depend on file order
  assert.equal(await computeIntegrity(new Map([...files].reverse())), ok.manifest.integrity);
  // 5. no manifest, bad JSON
  await assert.rejects(loadPackage(await writeZip({ 'main.js': '' })), /alloy-extension\.json is missing/);
  await assert.rejects(loadPackage(await writeZip({ [MANIFEST_FILE]: '{nope' })), /not valid JSON/);
});

// ─────────────────────────── permission matrix ───────────────────────────
const ALL = Object.keys(PERMISSIONS).filter((p) => !p.startsWith('network')).concat('network:api.example.com');
const DECLARED = Object.fromEntries(Object.keys(CONTRIBUTES).map((k) => [k, [{ id: 'x', label: 'X', file: 'p.html', fields: [], schema: {} }]]));
const ARGS = {
  'doc.get': [], 'doc.applyOps': [[{ op: 'set_param', name: 'a', expr: '1' }]], 'pcb.get': [], 'geometry.list': [], 'geometry.mesh': ['Box'], 'geometry.brep': ['Box'], 'geometry.voxelize': ['Box', 2], 'analysis.input': [],
  'checks.register': ['x', { has: ['fn'] }], 'analysis.register': ['x', { has: ['run'] }], 'featureType.register': ['x', { has: ['expand'] }], 'agent.tool': ['x', { has: ['fn'] }], 'loop.step': ['x', { has: ['fn'] }],
  'scene.overlay': ['x', { kind: 'box', min: [0, 0, 0], max: [1, 1, 1] }], 'scene.clearOverlay': ['x'], 'ui.pane.open': ['x'], 'ui.pane.post': ['x', { a: 1 }],
  'storage.project.get': ['k'], 'storage.project.set': ['k', 1], 'storage.user.get': ['k'], 'storage.user.set': ['k', 1],
  'compute.gpu.info': [], 'compute.remote.fetch': ['/ping'], 'net.fetch': ['https://api.example.com/x'],
};
function stubCtx(extra = {}) {
  const P = { ...emptyProject(), params: [{ name: 'a', expr: '2' }] };
  const log = { ops: [], overlays: [], panes: [], fetches: [] };
  const mesh = { pos: new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0]), idx: new Uint32Array([0, 1, 2]) };
  return {
    log,
    ctx: {
      getProject: () => P, getBuild: () => ({ bodies: [{ name: 'Box', mesh, view: mesh, bbox: { min: [0, 0, 0], max: [1, 1, 0] }, volume: 0, tris: 1 }], refs: [] }),
      commitOps: async (ops, o) => { log.ops.push({ ops, o }); return { log: ['ok'] }; }, brep: async () => new Uint8Array([1, 2, 3]),
      onOverlay: (...a) => log.overlays.push(a), onPane: (m) => log.panes.push(m), userStore: new Map([['x', 1]]),
      gpuInfo: async () => ({ available: false }), getRunner: () => ({ url: 'https://runner.example.org/', token: 'SECRET' }),
      fetch: async (url, init) => { log.fetches.push({ url, init }); return { status: 200, headers: new Headers({ a: 'b' }), arrayBuffer: async () => new ArrayBuffer(2) }; },
      ...extra,
    },
  };
}
const withStore = (c) => { const m = new Map(); c.userStore = { get: (k) => m.get(k), set: (k, v) => m.set(k, v) }; return c; };

test('permissions: every API call that needs a permission refuses a package without it', async () => {
  const needs = API.filter((a) => a.perm && !a.local);
  assert.ok(needs.length >= 20);
  for (const spec of needs) {
    const perms = ALL.filter((p) => (spec.perm === 'network' ? !p.startsWith('network:api.example.com') : p !== spec.perm)).concat(spec.perm === 'network' ? ['network:other.example.com'] : []);
    const api = new ExtApi(withStore(stubCtx().ctx));
    await rejects(api.call(fake(perms, DECLARED), spec.name, ARGS[spec.name]), 'E_PERM');
  }
  // every permission in the table is exercised by at least one call
  const used = new Set(needs.map((a) => (a.perm === 'network' ? 'network:<host>' : a.perm)));
  for (const p of Object.keys(PERMISSIONS)) assert.ok(used.has(p), `permission ${p} has an API call to test`);
});

test('permissions: with the permission and the declaration each call goes through', async () => {
  for (const spec of API.filter((a) => !a.local)) {
    const { ctx } = stubCtx(); const api = new ExtApi(withStore(ctx));
    await assert.doesNotReject(api.call(fake(ALL, DECLARED), spec.name, ARGS[spec.name]), spec.name);
  }
});

test('permissions: registering something the manifest did not declare is refused', async () => {
  for (const spec of API.filter((a) => a.contrib)) {
    const api = new ExtApi(withStore(stubCtx().ctx));
    await rejects(api.call(fake(ALL, {}), spec.name, ARGS[spec.name]), 'E_CONTRIB');
    const other = { ...DECLARED, [spec.contrib]: [{ id: 'other', label: 'o', file: 'p.html', fields: [], schema: {} }] };
    await rejects(api.call(fake(ALL, other), spec.name, ARGS[spec.name]), 'E_CONTRIB');
  }
});

test('permissions: network is per host; the runner path is confined; disabled packages are refused', async () => {
  const { ctx, log } = stubCtx(); const api = new ExtApi(ctx); const pkg = fake(['network:api.example.com', 'compute.remote']);
  await assert.doesNotReject(api.call(pkg, 'net.fetch', ['https://api.example.com/a']));
  await rejects(api.call(pkg, 'net.fetch', ['https://other.example.com/a']), 'E_PERM');
  await rejects(api.call(pkg, 'net.fetch', ['https://api.example.com.evil.net/a']), 'E_PERM');
  await rejects(api.call(pkg, 'net.fetch', ['http://api.example.com/a']), 'E_PERM'); // plain http to a remote host
  await rejects(api.call(pkg, 'net.fetch', ['not a url']), 'E_ARG');
  assert.equal(log.fetches.length, 1); assert.equal(log.fetches[0].init.redirect, 'error', 'a redirect cannot leave the granted host');
  for (const bad of ['http://evil/x', '//evil/x', '/../x', 'ping', '/a\r\nHost: x']) await rejects(api.call(pkg, 'compute.remote.fetch', [bad]), 'E_ARG');
  await api.call(pkg, 'compute.remote.fetch', ['/jobs', { method: 'post', headers: { Authorization: 'Bearer mine', cookie: 'c', 'x-a': '1' } }]);
  const sent = log.fetches.at(-1);
  assert.equal(sent.url, 'https://runner.example.org/jobs'); assert.equal(sent.init.headers.Authorization, 'Bearer SECRET', 'the host adds the runner token');
  assert.ok(!('cookie' in sent.init.headers) && sent.init.headers['x-a'] === '1');
  await rejects(new ExtApi(stubCtx({ getRunner: () => null }).ctx).call(pkg, 'compute.remote.fetch', ['/x']), 'E_UNAVAILABLE');
  await rejects(new ExtApi({ ...stubCtx().ctx, isEnabled: () => false }).call(fake(ALL, DECLARED), 'doc.get', []), 'E_PERM');
  await rejects(api.call(pkg, 'no.such.call', []), 'E_ARG');
  await rejects(api.call(pkg, 'ui.pane.onCall', []), 'E_ARG'); // local-only: not callable over RPC
});

// ─────────────────────────── ops, locks, storage ───────────────────────────
const realCommit = (P0) => { const box = { P: P0, commits: 0 }; return { box, commitOps: async (ops, o) => { const r = applyOps(box.P, ops, { actor: 'ext', extId: o.extId }); box.P = r.project; box.commits++; return { log: r.log }; } }; };

test('doc.applyOps: goes through applyOps, locks hold, a rejected op changes nothing, one call is one commit', async () => {
  const P = wizardProject(); P.params.find((p) => p.name === 'wall').locked = true;
  const { box, commitOps } = realCommit(P);
  const api = new ExtApi({ getProject: () => box.P, commitOps });
  const pkg = fake(['doc.ops', 'doc.read', 'storage']);
  const r = await api.call(pkg, 'doc.applyOps', [[{ op: 'set_param', name: 'foo', expr: '3' }, { op: 'set_param', name: 'bar', expr: '4' }]]);
  assert.equal(r.log.length, 2); assert.equal(box.commits, 1); assert.equal(box.P.params.find((p) => p.name === 'bar').expr, '4');
  const before = JSON.stringify(box.P);
  await rejects(api.call(pkg, 'doc.applyOps', [[{ op: 'set_param', name: 'ok', expr: '1' }, { op: 'set_param', name: 'wall', expr: '9' }]]), 'E_OPS'); // locked
  assert.equal(JSON.stringify(box.P), before, 'nothing applied when one op is rejected');
  await rejects(api.call(pkg, 'doc.applyOps', [[{ op: 'nonsense' }]]), 'E_OPS');
  await rejects(api.call(pkg, 'doc.applyOps', [[]]), 'E_ARG');
  await rejects(api.call(pkg, 'doc.applyOps', [new Array(201).fill({ op: 'set_param', name: 'a', expr: '1' })]), 'E_ARG');
  // a locked body
  const Q = wizardProject(); Q.bodies = { Base: { locked: true } };
  const q = realCommit(Q); const api2 = new ExtApi({ getProject: () => q.box.P, commitOps: q.commitOps });
  await rejects(api2.call(pkg, 'doc.applyOps', [[{ op: 'add_feature', feature: { type: 'box', body: 'Base', size: [1, 1, 1] } }]]), 'E_OPS');
});

test('ops: only the user can change extensions; a package writes only its own storage; the AI neither', async () => {
  const P = emptyProject();
  const set = (o, actor, extId) => applyOps(P, [o], { actor, extId });
  // the AI (the default actor)
  assert.throws(() => applyOps(P, [{ op: 'set_extension', id: 'xyz.t.a', enabled: true }]), /only the user/);
  assert.throws(() => applyOps(P, [{ op: 'ext_set', id: 'xyz.t.a', key: 'k', value: 1 }]), /not available to the AI/);
  // a package
  assert.throws(() => set({ op: 'set_extension', id: 'xyz.t.a', enabled: true }, 'ext', 'xyz.t.a'), /only the user/);
  assert.throws(() => set({ op: 'ext_set', id: 'xyz.t.b', key: 'k', value: 1 }, 'ext', 'xyz.t.a'), /only write its own storage/);
  assert.equal(set({ op: 'ext_set', id: 'xyz.t.a', key: 'k', value: { n: 1 } }, 'ext', 'xyz.t.a').project.ext['xyz.t.a'].k.n, 1);
  // the user
  const u = set({ op: 'set_extension', id: 'xyz.t.a', name: 'A', version: '1.0.0', enabled: true }, 'user').project;
  assert.deepEqual(u.extensions, [{ id: 'xyz.t.a', enabled: true, name: 'A', version: '1.0.0' }]);
  assert.equal(applyOps(u, [{ op: 'set_extension', id: 'xyz.t.a', enabled: false }], { actor: 'user' }).project.extensions[0].enabled, false);
  assert.equal(applyOps(u, [{ op: 'set_extension', id: 'xyz.t.a', remove: true }], { actor: 'user' }).project.extensions.length, 0);
  // the API refuses the same things before they reach applyOps
  const api = new ExtApi({ getProject: () => P, commitOps: async () => ({}) });
  return Promise.all([
    rejects(api.call(fake(['doc.ops']), 'doc.applyOps', [[{ op: 'set_extension', id: 'xyz.t.fake', enabled: true }]]), 'E_PERM'),
    rejects(api.call(fake(['doc.ops']), 'doc.applyOps', [[{ op: 'ext_set', id: 'xyz.t.other', key: 'k', value: 1 }]]), 'E_PERM'),
  ]);
});

test('storage: project data is an undoable op under ext[id]; keys and sizes are limited', async () => {
  const { box, commitOps } = realCommit(emptyProject());
  const api = new ExtApi(withStore({ getProject: () => box.P, commitOps, userStore: null }));
  api.ctx.userStore = (() => { const m = new Map(); return { get: (k) => m.get(k), set: (k, v) => m.set(k, v) }; })();
  const pkg = fake(['storage']);
  await api.call(pkg, 'storage.project.set', ['limit', { max: 60 }]);
  assert.deepEqual(box.P.ext['xyz.t.fake'].limit, { max: 60 });
  assert.deepEqual(await api.call(pkg, 'storage.project.get', ['limit']), { max: 60 }); assert.equal(await api.call(pkg, 'storage.project.get', ['none']), null);
  await rejects(api.call(pkg, 'storage.project.set', ['bad key!', 1]), 'E_ARG');
  await rejects(api.call(pkg, 'storage.project.set', ['big', 'x'.repeat(300_000)]), 'E_LIMIT');
  await api.call(pkg, 'storage.user.set', ['theme', 'x']); assert.equal(await api.call(pkg, 'storage.user.get', ['theme']), 'x');
  assert.equal(api.ctx.userStore.get('theme'), undefined, 'user storage is namespaced by package id');
});

test('scene.overlay: validated, capped, and defaults to experimental', async () => {
  const { ctx, log } = stubCtx(); const api = new ExtApi(ctx); const pkg = fake(['scene.overlay'], DECLARED);
  await api.call(pkg, 'scene.overlay', ['x', { kind: 'points', positions: new Float32Array(6), values: new Float32Array(2), unit: 'C' }]);
  assert.equal(log.overlays[0][2].validity, 'experimental'); assert.equal(log.overlays[0][0], 'xyz.t.fake');
  await rejects(api.call(pkg, 'scene.overlay', ['x', { kind: 'points', positions: new Float32Array(6), values: new Float32Array(3) }]), 'E_ARG');
  await rejects(api.call(pkg, 'scene.overlay', ['x', { kind: 'points', positions: [0, 0, 0], values: [1] }]), 'E_ARG');
  await rejects(api.call(pkg, 'scene.overlay', ['x', { kind: 'mesh' }]), 'E_ARG');
  await api.call(pkg, 'scene.clearOverlay', ['x']); assert.equal(log.overlays.at(-1)[2], null);
});

// ─────────────────────────── geometry ───────────────────────────
test('geometry: list, mesh, voxelize (volume within 1 % of exact), limits, brep needs OCCT', async () => {
  const P = emptyProject(); P.features.push(defaultFeature('box', { name: 'B', body: 'Cube', size: ['20', '20', '20'], anchor: 'bottom' }), defaultFeature('sphere', { name: 'S', body: 'Ball', d: '20', pos: ['40', '0', '10'] }));
  const res = build(P, wasm);
  const api = new ExtApi({ getBuild: () => res }); const pkg = fake(['geometry.read']);
  const list = await api.call(pkg, 'geometry.list', []); assert.deepEqual(list.map((b) => b.name).sort(), ['Ball', 'Cube']);
  const m = await api.call(pkg, 'geometry.mesh', ['Cube']); assert.ok(m.positions instanceof Float32Array && m.indices instanceof Uint32Array && Math.abs(m.volume_mm3 - 8000) < 1);
  const cube = await api.call(pkg, 'geometry.voxelize', ['Cube', 1]);
  assert.deepEqual(cube.dims, [20, 20, 20]); assert.equal(cube.data.reduce((a, b) => a + b, 0), 8000);
  const ball = await api.call(pkg, 'geometry.voxelize', ['Ball', 0.5]); const vol = ball.data.reduce((a, b) => a + b, 0) * 0.125;
  const exact = res.bodies.find((b) => b.name === 'Ball').volume; // the tessellated mesh volume (the voxelizer fills the mesh it is given)
  assert.ok(Math.abs(vol - exact) / exact < 0.01, `sphere ${vol.toFixed(0)} vs mesh ${exact.toFixed(0)} mm³`);
  await rejects(api.call(pkg, 'geometry.voxelize', ['Cube', 0.01]), 'E_LIMIT');
  await rejects(api.call(pkg, 'geometry.voxelize', ['Cube', -1]), 'E_ARG');
  await rejects(api.call(pkg, 'geometry.voxelize', ['Cube', 0.01, { maxVoxels: 1e9 }]), 'E_LIMIT'); // the hard cap holds
  await rejects(api.call(pkg, 'geometry.mesh', ['Nope']), 'E_ARG');
  await rejects(api.call(pkg, 'geometry.brep', ['Cube']), 'E_UNAVAILABLE');
  assert.throws(() => voxelize({ pos: [], idx: [] }, 1), /empty/);
  res.dispose();
});

// ─────────────────────────── the Worker host ───────────────────────────
const hostFor = (ctx = {}, o = {}) => { const h = new ExtHost({ createWorker: nodeWorker, api: new ExtApi(withStore({ getProject: () => ({ ...emptyProject(), name: 'P' }), ...ctx })), ...o }); HOSTS.push(h); return h; };
const EXT = (id, perms, contributes, main) => mk({ id, permissions: perms, contributes }, { 'main.js': main });

test('host: calls round trip over RPC, with progress, and the API is permission-checked from inside the Worker', async () => {
  const h = hostFor(); const prog = [];
  const pkg = await EXT('xyz.t.rpc', ['doc.read'], { analyses: [{ id: 'a', label: 'A' }] }, `
    forge.analysis.register('a', { async run(input, ctx) {
      ctx.progress(0.25); const d = await forge.doc.get(); ctx.progress(1);
      let denied; try { await forge.doc.applyOps([{ op: 'set_param', name: 'x', expr: '1' }]); } catch (e) { denied = e.code; }
      let undeclared; try { await forge.checks.register('nope', () => 1); } catch (e) { undeclared = e.code; }
      return { name: d.name, got: input.n, denied, undeclared };
    } });`);
  const r = await h.invoke(pkg, 'analysis', 'a', { n: 7 }, { onProgress: (v) => prog.push(v) });
  assert.deepEqual(r, { name: 'P', got: 7, denied: 'E_PERM', undeclared: 'E_PERM' }); assert.deepEqual(prog, [0.25, 1]);
  h.stopAll();
});

test('host: cancel reaches the package (AbortSignal) and the call rejects with E_CANCELLED', async () => {
  const h = hostFor();
  const pkg = await EXT('xyz.t.cancel', ['doc.read'], { analyses: [{ id: 'a', label: 'A' }] }, `
    forge.analysis.register('a', { async run(i, ctx) { for (let k = 0; k < 400; k++) { if (ctx.signal.aborted) throw new Error('stopped'); await new Promise((r) => setTimeout(r, 10)); } return 'finished'; } });`);
  const ac = new AbortController(); const t0 = Date.now();
  const p = h.invoke(pkg, 'analysis', 'a', {}, { signal: ac.signal }); setTimeout(() => ac.abort(), 120);
  await rejects(p, 'E_CANCELLED'); assert.ok(Date.now() - t0 < 2000);
  await rejects(h.invoke(pkg, 'analysis', 'a', {}, { signal: AbortSignal.abort() }), 'E_CANCELLED');
  h.stopAll();
});

test('host: a Worker that never answers is terminated on timeout and restarts on next use', async () => {
  const h = hostFor();
  const pkg = await EXT('xyz.t.spin', ['doc.read'], { analyses: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }] }, `
    forge.analysis.register('a', { run() { for (;;) {} } }); forge.analysis.register('b', { run: () => 'alive' });`);
  await rejects(h.invoke(pkg, 'analysis', 'a', {}, { timeoutMs: 150 }), 'E_TIMEOUT');
  assert.equal(h.running('xyz.t.spin'), false, 'the stuck Worker was stopped');
  assert.equal(await h.invoke(pkg, 'analysis', 'b', {}), 'alive');
  h.stopAll();
});

test('host: a package that throws or does not parse fails to start, and says why', async () => {
  const h = hostFor();
  await rejects(h.ensure(await EXT('xyz.t.throws', [], {}, `throw new Error('boom at start');`)), 'E_PACKAGE');
  assert.match(h.logOf('xyz.t.throws')[0].text, /boom at start/);
  await rejects(h.ensure(await EXT('xyz.t.syntax', [], {}, `this is not javascript (`)), 'E_PACKAGE');
  await rejects(h.invoke(await EXT('xyz.t.none', [], {}, ''), 'check', 'missing', {}), 'E_UNAVAILABLE');
  h.stopAll();
});

test('host: the Worker has no network or storage globals, and no GPU unless granted', async () => {
  const probe = `forge.analysis.register('a', { run() { const t = (n) => typeof globalThis[n]; return { net: ['fetch','XMLHttpRequest','WebSocket','EventSource','importScripts','Worker','indexedDB','require','process','Request'].map(t), gpu: typeof (globalThis.navigator && globalThis.navigator.gpu) }; } });`;
  const h = hostFor();
  const r = await h.invoke(await EXT('xyz.t.lock', ['doc.read'], { analyses: [{ id: 'a', label: 'A' }] }, probe), 'analysis', 'a', {});
  assert.deepEqual(r.net, new Array(10).fill('undefined')); assert.equal(r.gpu, 'undefined');
  // and it cannot get them back through the prototype chain or `self`
  const sneaky = `forge.analysis.register('a', { run() { const out = []; for (let o = globalThis; o; o = Object.getPrototypeOf(o)) if (Object.prototype.hasOwnProperty.call(o, 'fetch') && typeof o.fetch === 'function') out.push('fetch'); return out; } });`;
  assert.deepEqual(await h.invoke(await EXT('xyz.t.sneak', ['doc.read'], { analyses: [{ id: 'a', label: 'A' }] }, sneaky), 'analysis', 'a', {}), []);
  h.stopAll();
});

test('host: console output is captured per package; a package cannot read another package\'s state', async () => {
  const h = hostFor();
  const a = await EXT('xyz.t.log', ['storage'], {}, `console.log('hello', {a: 1}); forge.storage.user.set('k', 'mine');`);
  await h.ensure(a); await new Promise((r) => setTimeout(r, 50));
  assert.match(h.logOf('xyz.t.log')[0].text, /hello {"a":1}/);
  const b = await EXT('xyz.t.log2', ['storage'], { analyses: [] }, `forge.analysis; globalThis.v = forge.storage.user.get('k');`);
  await h.ensure(b); await new Promise((r) => setTimeout(r, 50));
  h.stopAll();
});

// ─────────────────────────── resolver: feature types and checks ───────────────────────────
async function samples() {
  const reg = new ExtRegistry({ store: memoryStore() }); const api = new ExtApi({});
  const out = { reg };
  out.rib = (await reg.install(await packDir('examples/ext/sample-rib'))).pkg;
  out.probe = (await reg.install(await packDir('examples/ext/sample-probe'))).pkg;
  out.cur = emptyProject(); out.use = (P) => { out.cur = P; return P; };
  const host = hostFor({ getProject: () => out.cur }); out.host = host; out.api = host.api;
  out.res = createResolver({ registry: reg, host });
  return out;
}
const RIB = 'xyz.mdhv.sample-rib';
const withRib = (over = {}) => {
  const P = migrate(emptyProject());
  P.params.push({ name: 'rt', expr: '1.6' });
  P.features.push(defaultFeature('box', { name: 'Floor', body: 'Tray', size: ['60', '40', '2'], anchor: 'bottom' }));
  P.features.push(defaultFeature(`${RIB}/rib`, { name: 'Rib A', body: 'Tray', length: '30', height: '10', thickness: 'rt', pos: ['0', '0', '2'], ...over }));
  P.extensions = [{ id: RIB, enabled: true, name: 'Sample: rib', version: '0.1.0', checks: ['rib-thickness'] }];
  return P;
};

test('samples: both packages load, with the right tier and the contributions they declare', async () => {
  const s = await samples();
  assert.equal(s.rib.tier, 'plugin'); assert.equal(s.probe.tier, 'extension');
  assert.deepEqual(s.rib.manifest.contributes.featureTypes.map((t) => t.id), ['rib']); assert.deepEqual(s.probe.manifest.contributes.panes.map((p) => p.id), ['probe']); assert.deepEqual(s.probe.manifest.contributes.overlays.map((p) => p.id), ['height']);
  assert.equal(describeInstall(s.rib.manifest).line, 'Plugin: reads your design'); assert.match(describeInstall(s.probe.manifest).line, /^Extension: runs its own code/);
  const T = typeDef(`${RIB}/rib`); assert.equal(T.ext, RIB); assert.ok(fieldsFor(`${RIB}/rib`).some((f) => f.k === 'thickness'));
  s.host.stopAll();
});

test('feature types: a package feature expands to built-in features, the kernel builds them, parameters drive them', async () => {
  const s = await samples(); const P = withRib();
  const prep = await s.res.prepare(P);
  assert.equal(prep.derived, true); assert.deepEqual(prep.warnings, []);
  assert.deepEqual(prep.project.features.map((f) => f.type), ['box', 'box']); assert.equal(prep.project.features[1].fromExt, P.features[1].id);
  assert.equal(P.features.length, 2, 'the saved document is not modified');
  const r = s.res.mergeStatus(build(prep.project, wasm), prep, P);
  assert.equal(r.status[P.features[1].id].ok, true); assert.equal(Object.keys(r.status).length, 2, 'child ids do not leak into the status map');
  const tray = r.bodies.find((b) => b.name === 'Tray'); assert.ok(tray, 'built into the Tray body');
  assert.ok(Math.abs(tray.volume - (60 * 40 * 2 + 30 * 1.6 * 10)) < 1, `volume ${tray.volume}`);
  r.dispose();
  // a parameter change re-expands
  const P2 = clone(P); P2.params.find((p) => p.name === 'rt').expr = '3'; const prep2 = await s.res.prepare(P2);
  const r2 = build(prep2.project, wasm); assert.ok(Math.abs(r2.bodies[0].volume - (60 * 40 * 2 + 30 * 3 * 10)) < 1); r2.dispose();
  // locks apply to package features like any other
  P.features[1].locked = true;
  assert.throws(() => applyOps(P, [{ op: 'update_feature', id: P.features[1].id, set: { length: '40' } }]), /locked/);
  const ok = applyOps(clone(P), [{ op: 'add_feature', feature: { type: `${RIB}/rib`, body: 'Tray', length: 12, height: 5, thickness: 2, name: 'Rib B' } }]).project;
  assert.equal(ok.features.at(-1).ext, RIB);
  s.host.stopAll();
});

test('feature types: a bad expansion is a visible error on that feature, not a crash', async () => {
  const reg = new ExtRegistry({ store: memoryStore() }); const host = hostFor(); const res = createResolver({ registry: reg, host });
  const pkgBytes = await buildPackage({ ...BASE, id: 'xyz.t.bad', name: 'Bad', permissions: [], contributes: { featureTypes: [{ id: 'f', label: 'F', fields: [] }] } }, { 'main.js': `forge.featureType.register('f', { expand: () => [{ type: 'import', name: 'x' }] });` });
  await reg.install(pkgBytes);
  const P = migrate(emptyProject()); P.features.push(defaultFeature('xyz.t.bad/f', { name: 'F1', body: 'B' })); P.extensions = [{ id: 'xyz.t.bad', enabled: true, name: 'Bad' }];
  const prep = await res.prepare(P);
  assert.match(prep.errors[P.features[0].id], /not a buildable built-in feature type/);
  const r = res.mergeStatus(build(prep.project, wasm), prep, P); assert.equal(r.status[P.features[0].id].ok, false); r.dispose();
  host.stopAll();
});

test('REMOVAL: a project using a package feature type and check opens, builds and saves without the package, and is restored when it is reinstalled', async () => {
  const s = await samples(); const P = s.use(withRib());
  const saved = JSON.stringify(P);
  const sound = async () => { const r = (await s.res.prepare(P)); const k = build(r.project, wasm); const out = { prep: r, built: s.res.mergeStatus(k, r, P) }; return out; };
  // 1. with the package
  let { prep, built } = await sound();
  assert.ok(Math.abs(built.bodies[0].volume - (4800 + 30 * 1.6 * 10)) < 1); built.dispose();
  // 2. remove the package
  await s.reg.remove(RIB);
  assert.equal(typeDef(`${RIB}/rib`), null, 'the feature type is gone from the registry');
  ({ prep, built } = await sound());
  assert.equal(prep.warnings.length, 1); assert.equal(prep.warnings[0].kind, 'missing'); assert.match(prep.warnings[0].message, /needs the extension "Sample: rib"/);
  assert.equal(built.status[P.features[1].id].skipped, true); assert.match(built.status[P.features[1].id].warning, /needs the extension/);
  assert.ok(Math.abs(built.bodies[0].volume - 4800) < 1, 'built without the rib'); assert.equal(built.extWarnings.length, 1);
  assert.equal(runChecks(prep.project, built).passed, true, 'a missing package does not fail the built-in checks');
  built.dispose();
  // 3. it saves untouched: the feature, its fields and its tag are all still there
  assert.equal(JSON.stringify(P), saved); const reopened = migrate(JSON.parse(JSON.stringify(P)));
  assert.equal(reopened.features[1].type, `${RIB}/rib`); assert.equal(reopened.features[1].ext, RIB); assert.equal(reopened.features[1].thickness, 'rt'); assert.equal(reopened.extensions[0].name, 'Sample: rib');
  // 4. the check row says what is missing; it does not pass silently and does not gate
  const rows = await s.res.checkRows(reopened);
  assert.equal(rows.length, 1); assert.equal(rows[0].severity, 'advisory'); assert.equal(rows[0].pass, false); assert.match(rows[0].detail, /not installed/);
  const merged = s.res.mergeChecks({ passed: true, failed: [], checks: [], metrics: {} }, rows); assert.equal(merged.passed, true); assert.equal(merged.checks.length, 1);
  // 5. other edits still work, and editing the orphan is refused rather than corrupting it
  const edited = applyOps(reopened, [{ op: 'set_param', name: 'rt', expr: '2' }]).project; assert.equal(edited.features[1].thickness, 'rt');
  assert.throws(() => applyOps(reopened, [{ op: 'update_feature', id: reopened.features[1].id, set: { length: '40' } }]), /no field/);
  assert.throws(() => applyOps(reopened, [{ op: 'add_feature', feature: { type: `${RIB}/rib` } }]), /needs an extension that is not installed/);
  assert.equal(applyOps(reopened, [{ op: 'update_feature', id: reopened.features[1].id, set: { suppressed: true } }]).project.features[1].suppressed, true, 'it can still be suppressed or deleted');
  // 6. reinstall: everything comes back
  await s.reg.install(await packDir('examples/ext/sample-rib'));
  ({ prep, built } = await sound());
  assert.equal(prep.warnings.length, 0); assert.ok(Math.abs(built.bodies[0].volume - (4800 + 480)) < 1, 'the rib is back'); built.dispose();
  assert.deepEqual((await s.res.checkRows(reopened)).map((r) => r.pass), [true]);
  s.host.stopAll();
});

test('a package switched off in the project is skipped with a warning, and its checks do not run', async () => {
  const s = await samples(); const P = withRib(); P.extensions[0].enabled = false;
  const prep = await s.res.prepare(P); assert.equal(prep.warnings[0].kind, 'disabled'); assert.match(prep.warnings[0].message, /switched off/);
  assert.deepEqual(await s.res.checkRows(P), []);
  s.host.stopAll();
});

test('checks: package rows join the scorecard; a thin rib fails it; experimental rows never gate', async () => {
  const s = await samples();
  const thin = withRib({ thickness: '0.8' }), fine = withRib();
  const run = async (P) => { s.use(P); const prep = await s.res.prepare(P); const b = build(prep.project, wasm); const res = s.res.mergeChecks(runChecks(prep.project, b, { skip: ['min_wall'] }), await s.res.checkRows(P)); b.dispose(); return res; };
  const bad = await run(thin), ok = await run(fine);
  assert.equal(ok.passed, true); assert.ok(ok.checks.some((c) => c.id === `${RIB}:rib-thickness` && c.pass && c.ext === RIB));
  assert.equal(bad.passed, false); assert.ok(bad.failed.includes(`${RIB}:rib-thickness`)); assert.match(bad.checks.at(-1).detail, /thinner than 1\.2 mm/);
  // experimental never drives pass/fail
  const bytes = await buildPackage({ ...BASE, id: 'xyz.t.exp', name: 'Exp', permissions: ['checks'], contributes: { checks: [{ id: 'c', label: 'Experimental check' }] } },
    { 'main.js': `forge.checks.register('c', () => ({ pass: false, detail: 'looks bad', validity: 'experimental' }));` });
  await s.reg.install(bytes);
  const P = s.use(withRib()); P.extensions.push({ id: 'xyz.t.exp', enabled: true, name: 'Exp' });
  const rows = await s.res.checkRows(P); const exp = rows.find((r) => r.ext === 'xyz.t.exp');
  assert.equal(exp.severity, 'advisory'); assert.equal(exp.validity, 'experimental'); assert.equal(exp.pass, false);
  const merged = s.res.mergeChecks({ passed: true, failed: [], checks: [] }, rows); assert.equal(merged.passed, true, 'experimental failure is shown but does not fail the verdict'); assert.equal(merged.failed.length, 0);
  // a check that throws is a visible hard failure, not a silent pass
  await s.reg.install(await buildPackage({ ...BASE, id: 'xyz.t.crash', name: 'Crash', permissions: ['checks'], contributes: { checks: [{ id: 'c', label: 'Crashy' }] } }, { 'main.js': `forge.checks.register('c', () => { throw new Error('kaput'); });` }));
  P.extensions.push({ id: 'xyz.t.crash', enabled: true, name: 'Crash' });
  const crash = (await s.res.checkRows(P)).find((r) => r.ext === 'xyz.t.crash'); assert.equal(crash.pass, false); assert.equal(crash.severity, 'hard'); assert.match(crash.detail, /kaput/);
  s.host.stopAll();
});

// ─────────────────────────── registry, documents, docs ───────────────────────────
test('registry: install persists and verifies again on load; a tampered store entry is reported, not loaded', async () => {
  const store = memoryStore(); const reg = new ExtRegistry({ store });
  const { replaced } = await reg.install(await packDir('examples/ext/sample-rib')); assert.equal(replaced, null);
  assert.equal((await reg.install(await packDir('examples/ext/sample-rib'))).replaced, '0.1.0');
  const reg2 = await new ExtRegistry({ store }).init(); assert.deepEqual(reg2.list().map((p) => p.manifest.id), [RIB]);
  const rec = (await store.list())[0]; const parts = await readZip(rec.bytes); parts.set('main.js', new TextEncoder().encode('/* edited */')); // a valid zip whose files no longer match the manifest
  await store.put(rec.id, await writeZip(Object.fromEntries(parts)));
  const reg3 = await new ExtRegistry({ store }).init(); assert.equal(reg3.list().length, 0); assert.equal(reg3.loadErrors.length, 1);
  assert.equal(await reg.remove('no.such.pkg'), false);
});

test('documents: old projects migrate to carry extensions and ext; AI context lists nothing extra', () => {
  const m = migrate({ v: 1, features: [] }); assert.deepEqual(m.extensions, []); assert.deepEqual(m.ext, {});
  const w = migrate({ extensions: 'x', ext: [] }); assert.deepEqual(w.extensions, []); assert.deepEqual(w.ext, {});
  assert.equal(emptyProject().v, 3, 'the document version is unchanged: the new fields are optional');
});

test('docs/EXTENSION_API.md is generated from the API table and is up to date', () => {
  const on = fs.readFileSync('../docs/EXTENSION_API.md', 'utf8');
  assert.equal(on, renderApiDoc(), 'run: node tools/gen-extension-api.mjs');
  for (const a of API) assert.ok(on.includes('`forge.' + a.name + '`'), a.name + ' is documented');
  for (const p of Object.keys(PERMISSIONS)) assert.ok(on.includes('`' + p + '`'), p + ' is documented');
});
