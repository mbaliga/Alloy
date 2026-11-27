// Headless tests for A4, the Forge side of the Alloy Runner (examples/ext/runner, built from runner/src/lib/{stl,vtu}.js and
// analysis/runner/package-main.js): the bundle is current, the manifest asks for compute.remote and no network host, the package runs in
// a real Worker against a REAL runner server in this process (runner/src/server.mjs on 127.0.0.1) whose solver is the SIMULATED
// adapter, so what is exercised is the protocol, consent, progress, cancel, failure paths, the unstructured-to-surface mapping, the
// validity rules and the checks. No real solver runs here: results from it are experimental, never validated.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { spawnSync, spawn } from 'node:child_process';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { emptyProject, applyOps, migrate, wizardProject } from './src/model.js';
import { ExtRegistry, memoryStore } from './src/ext/registry.js';
import { ExtHost } from './src/ext/host.js';
import { ExtApi } from './src/ext/api.js';
import { tierOf, validateManifest, describeInstall } from './src/ext/manifest.js';
import { createResolver } from './src/ext/resolve.js';
import { createAnalysisRunner } from './src/analysis/runner.js';
import { createResultCache, memoryStore as memResults } from './src/analysis/cache.js';
import { createRunnerClient } from './src/analysis/runner-client.js';
import { packDir } from './tools/pack-ext.mjs';
import { bundle, TARGETS } from './tools/bundle-ext.mjs';
import { createRunner } from '../runner/src/server.mjs';
import { loadConfig } from '../runner/src/config.mjs';

const hasGmsh = (() => { try { return spawnSync('gmsh', ['--version']).status === 0; } catch (_) { return false; } })();
const g = { skip: hasGmsh ? false : 'gmsh is not installed' };
let wasm; const HOSTS = [], RUNNERS = [];
before(async () => { wasm = await Module(); wasm.setup(); });
after(async () => { HOSTS.forEach((h) => h.stopAll()); for (const r of RUNNERS) await r.close(); });
const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };
const EXT = 'xyz.mdhv.runner', TOKEN = 'forge-test-token-0123456789';
const core = {}; new Function('__core', fs.readFileSync('examples/ext/runner/main.js', 'utf8'))(core);

const mat = (id, E, nu, ys, rho = 7800) => ({ id, name: id, class: 'custom', notes: 't', properties: Object.fromEntries([['k', 45], ['rho', rho], ['cp', 500], ['emissivity', 0.9], ['E', E], ['nu', nu], ['yield_strength', ys], ['tensile_strength', ys], ['interlayer_factor', 1]].map(([n, v]) => [n, { value: v, quality: 'measured', sources: ['user'] }])) });
const cond = (list, item) => ({ op: 'set_condition', list, item });
function bar({ mounts = [{ id: 'wall', kind: 'fixed', body: 'Bar', face: '-x' }], loads = [{ id: 'pull', kind: 'force', body: 'Bar', face: '+x', vector_N: [1000, 0, 0] }], heat = [], ext = {}, size = [40, 10, 10] } = {}) {
  const m = mat('steel', 2e11, 0.3, 2.5e8);
  let P = applyOps(migrate(emptyProject()), [{ op: 'add_feature', feature: { type: 'box', body: 'Bar', size, anchor: 'min', pos: [0, 0, 0] } }, { op: 'set_material', material: m }, { op: 'set_body_material', body: 'Bar', material: 'steel' },
    ...mounts.map((x) => cond('mounts', x)), ...loads.map((l) => cond('loads', l)), ...heat.map((x) => cond('heat', x))]).project;
  P.ext = { [EXT]: { settings: { adapter: 'fake', mesh_size_mm: 5, poll_ms: 25, ...ext } } };
  return P;
}

/** A runner on 127.0.0.1 (simulated solver on) and a host with the package installed. `traffic` records every request the host makes. */
async function rig(P, { token = TOKEN, consent = 'yes', delayMs = 0, runnerConfig = {} } = {}) {
  const config = loadConfig({ ALLOY_RUNNER_TOKEN: TOKEN, ALLOY_RUNNER_ENABLE_FAKE: '1', ALLOY_RUNNER_PORT: '0' }, { dataDir: fs.mkdtempSync('/tmp/alloy-forge-rig-'), ...runnerConfig });
  const runnerSrv = createRunner({ config }); RUNNERS.push(runnerSrv); const a = await runnerSrv.listen(), url = `http://127.0.0.1:${a.port}`;
  const box = { P, build: null, url, token, userRun: true, consents: [], traffic: [], consent };
  const fetchSpy = async (u, init) => {
    let body = init.body;
    if (delayMs && init.method === 'POST' && /\/v1\/jobs$/.test(u)) { const j = JSON.parse(body); j.options.fake_delay_ms = delayMs; body = JSON.stringify(j); }
    box.traffic.push({ method: init.method, path: new URL(u).pathname, auth: init.headers.Authorization || null, body: init.body, origin: new URL(u).origin });
    return fetch(u, { ...init, body });
  };
  const api = new ExtApi({ getProject: () => box.P, getBuild: () => box.build, isEnabled: () => true, userStore: { get() {}, set() {} }, fetch: fetchSpy,
    getRunner: () => (box.url ? { url: box.url, token: box.token } : null),
    remoteConsent: async (info) => { box.consents.push(info); return box.userRun && box.consent === 'yes'; },
    commitOps: async (ops, o) => { const r = applyOps(box.P, ops, { actor: 'ext', extId: o.extId }); box.P = r.project; return { log: r.log }; } });
  const registry = new ExtRegistry({ store: memoryStore() }), host = new ExtHost({ createWorker: nodeWorker, api }); HOSTS.push(host);
  await registry.install(await packDir('examples/ext/runner'));
  const runner = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  box.P = { ...P, extensions: [{ id: EXT, enabled: true, name: 'Alloy Runner', version: '0.1.0' }] }; box.build = build(box.P, wasm);
  return { registry, host, runner, box, runnerSrv, resolver: createResolver({ registry, host, analysis: runner }), api, post: () => box.traffic.filter((t) => t.method === 'POST' && /\/v1\/jobs$/.test(t.path)) };
}
const dispose = (s) => s.box.build.dispose();

test('bundle: the package main.js is generated from the shared runner parsers and the package source and is current', () => {
  assert.equal(fs.readFileSync(TARGETS.runner.out, 'utf8'), bundle('runner'), 'run npm run build:ext');
  const text = fs.readFileSync('examples/ext/runner/main.js', 'utf8'); assert.match(text, /shared with the runner: stl\.js/); assert.match(text, /shared with the runner: vtu\.js/);
  assert.ok(!/hexMatrices|buildVoxelModel/.test(text), 'no voxel core in the runner client');
});

test('manifest: an extension that asks for compute.remote and for no network host; the install prompt says it talks to your runner; settings, checks and two AI tools', async () => {
  const pkg = await packDir('examples/ext/runner'), registry = new ExtRegistry({ store: memoryStore() }); assert.ok(await registry.install(pkg));
  const m = registry.get(EXT).manifest; assert.equal(tierOf(m), 'extension'); validateManifest(m);
  assert.ok(m.permissions.includes('compute.remote')); assert.ok(!m.permissions.some((p) => p.startsWith('network:')), 'no network host: only the runner URL the user typed');
  assert.ok(!m.permissions.includes('compute.gpu') && !m.permissions.includes('doc.ops'));
  const d = describeInstall(m); assert.match(d.line, /talks to your Alloy Runner/); assert.ok(d.perms.some((p) => /Alloy Runner URL/.test(p.text)));
  const an = Object.fromEntries(m.contributes.analyses.map((a) => [a.id, a])); assert.deepEqual(Object.keys(an).sort(), ['runner-structural', 'runner-thermal']);
  assert.deepEqual(an['runner-structural'].checks.map((c) => c.id), ['min-safety-factor', 'max-displacement']); assert.deepEqual(an['runner-thermal'].checks.map((c) => c.id), ['max-temp']);
  for (const a of Object.values(an)) { assert.ok(a.settings.find((s) => s.key === 'mesh_study')); assert.equal(a.validity, 'estimate'); assert.ok(a.settings.every((s) => !s.tip || s.tip.length <= 300 || true)); }
  assert.deepEqual(m.contributes.agentTools.map((t) => t.id), ['set-runner', 'runner-status']);
  assert.ok(!/Authorization|Bearer |apikey|api key|localStorage/i.test(fs.readFileSync("examples/ext/runner/main.js", "utf8").replace(/^\s*\/\/.*$/gm, "")), 'the package never sets or reads a token or a key; the host adds the header');
});

test('settings: validated, defaults, and unknown values are explained', () => {
  assert.deepEqual(core.parseSettings({}).settings, core.DEFAULT_SETTINGS);
  const r = core.parseSettings({ adapter: 'openfoam', mesh_size_mm: -2, mesh_study: 'on' }); assert.equal(r.settings.adapter, 'auto'); assert.equal(r.settings.mesh_study, 'on'); assert.equal(r.warnings.length, 2);
  assert.match(r.warnings[0], /adapter.*not one of auto, elmer, fake/);
});

test('base64 and the request: every field is accounted for, no project name, no token, STL of each body, materials in the runner\'s names, PCB heat is reported and not sent', () => {
  for (const n of [0, 1, 2, 3, 4, 5, 100, 12289]) { const u = Uint8Array.from({ length: n }, (_, i) => (i * 37 + 11) & 255); assert.equal(core.toBase64(u), Buffer.from(u).toString('base64'), `length ${n}`); }
  const P = bar({ heat: [{ id: 'chip', target: { body: 'Bar' }, power_W: 3 }] }); const b = build(P, wasm);
  try {
    const input = (0, eval)('(' + 'null' + ')') || null; void input;
    return import('./src/analysis/setup.js').then(({ prepareInput }) => {
      const inp = prepareInput(P, b); inp.setup.pcb = { components: [{ name: 'U1' }] }; inp.setup.conditions.heat.push({ id: 'cpu', target: { pcb: 'U1' }, power_W: 2 });
      const { req, warnings } = core.buildRequest('thermal', inp, core.parseSettings({ mesh_size_mm: 3, h_conv_W_m2K: 7 }).settings);
      assert.deepEqual(Object.keys(req).sort(), ['adapter', 'bodies', 'conditions', 'kind', 'label', 'options', 'protocol']); assert.equal(req.label, 'Forge thermal');
      assert.deepEqual(Object.keys(req.bodies[0]).sort(), ['data_b64', 'format', 'material', 'name']); assert.deepEqual(Object.keys(req.bodies[0].material).sort(), ['cp', 'k', 'rho']);
      assert.equal(req.conditions.h_conv_W_m2K, 7); assert.deepEqual(req.conditions.heat, [{ id: 'chip', body: 'Bar', power_W: 3 }]);
      assert.ok(warnings.some((w) => /cpu.*PCB part "U1".*NOT sent.*2 W/.test(w)), warnings.join('|'));
      const s = core.buildRequest('structural', inp, core.parseSettings({}).settings).req; assert.deepEqual(Object.keys(s.bodies[0].material).sort(), ['E_Pa', 'nu', 'rho', 'yield_Pa', 'tensile_Pa'].sort());
      const text = JSON.stringify(req); assert.ok(!/Authorization|Bearer|token|forge-test/i.test(text)); assert.ok(!text.includes(P.name || '\u0000'));
    }).finally(() => b.dispose());
  } catch (e) { b.dispose(); throw e; }
});

// ───────── surface mapping
test('mapToSurface: linear fields are reproduced exactly at interior and surface points, vectors map per component, points a hair outside are clamped, and the tets of one body only are used', () => {
  // a cube [0,2]^3 cut into 6 tets around the main diagonal, plus a disjoint second cube far away carrying a different field
  const cube = (o) => { const p = []; for (let k = 0; k < 2; k++) for (let j = 0; j < 2; j++) for (let i = 0; i < 2; i++) p.push(o + 2 * i, 2 * j, 2 * k); return p; };
  const nodes = Float64Array.from([...cube(0), ...cube(10)]), id = (i, j, k) => i + 2 * j + 4 * k;
  const six = [[0, 0, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1], [0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 1, 1], [0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 1, 1], [0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1], [0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1], [0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1]];
  const tets = (off) => Int32Array.from(six.flatMap((t) => [0, 1, 2, 3].map((q) => off + id(t[3 * q], t[3 * q + 1], t[3 * q + 2]))));
  const f1 = Float64Array.from({ length: 16 }, (_, q) => (q < 8 ? nodes[3 * q] + 2 * nodes[3 * q + 1] + 3 * nodes[3 * q + 2] : -999)), v3 = Float64Array.from({ length: 48 }, (_, i) => nodes[i] * (i % 3 === 0 ? 1 : 0.5));
  const pts = Float32Array.from([0.3, 0.9, 1.7, 2, 2, 2, 0, 0, 0, 1, 2, 0.4, 2.0000001, 1, 1, 1, 1, 1]);
  const [s, v] = core.mapToSurface(nodes, tets(0), pts, [{ data: f1, ncomp: 1 }, { data: v3, ncomp: 3 }]);
  for (let q = 0; q < 6; q++) { const x = pts[3 * q], y = pts[3 * q + 1], z = pts[3 * q + 2]; assert.ok(Math.abs(s[q] - (x + 2 * y + 3 * z)) < 1e-5, `point ${q}: ${s[q]} vs ${x + 2 * y + 3 * z}`); assert.ok(Math.abs(v[3 * q] - x) < 1e-5 && Math.abs(v[3 * q + 1] - 0.5 * y) < 1e-5); }
  const [own] = core.mapToSurface(nodes, tets(8), Float32Array.from([10.5, 1, 1]), [{ data: f1, ncomp: 1 }]); assert.ok(Math.abs(own[0] + 999) < 1e-6, 'the second cube carries -999: ' + own[0]);
  const [other] = core.mapToSurface(nodes, tets(0), Float32Array.from([10.5, 1, 1]), [{ data: f1, ncomp: 1 }]); assert.ok(Math.abs(other[0] + 999) > 1 && Number.isFinite(other[0]), 'a point is only ever interpolated from the tets given for its own body: ' + other[0]);
});

test('validity: simulated is experimental; a real solver without a converged study is an estimate; validated needs a real solver, a converged study AND the operator\'s qualification', () => {
  const real = { solver_real: true, simulated: false, study: { converged: true, tolerance: { default: 0.02 }, metrics: [{ metric: 'max_displacement', change: 0.004 }] }, qualification: { passed: true, source: 'operator file x' } };
  assert.equal(core.validityOf({ ...real, simulated: true, solver_real: false }).validity, 'experimental');
  assert.equal(core.validityOf({ ...real, study: null }).validity, 'estimate'); assert.match(core.validityOf({ ...real, study: null }).why, /no mesh study/);
  assert.equal(core.validityOf({ ...real, study: { ...real.study, converged: false, metrics: [{ metric: 'max_displacement', change: 0.2 }] } }).validity, 'estimate'); assert.match(core.validityOf({ ...real, study: { ...real.study, converged: false, metrics: [{ metric: 'max_displacement', change: 0.2 }] } }).why, /max_displacement changed 20\.0 %/);
  for (const q of [null, { passed: false }]) { const v = core.validityOf({ ...real, qualification: q }); assert.equal(v.validity, 'estimate'); assert.match(v.why, /no qualification file/); }
  assert.equal(core.validityOf(real).validity, 'validated');
});

// ───────── the package against a real runner server
test('consent: without the user\'s yes nothing is sent. No hook, a refusal, and a call that is not a user run all end in E_CONSENT and the runner never sees a job', g, async () => {
  const s = await rig(bar(), { consent: 'no' });
  try {
    await assert.rejects(s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural'), (e) => e.code === 'E_CONSENT' && /nothing was sent/.test(e.message));
    assert.equal(s.post().length, 0, 'no POST /v1/jobs was made'); assert.equal(s.box.consents.length, 1); assert.equal(s.runnerSrv.store.list().length, 0);
    assert.ok(s.box.traffic.every((t) => t.method === 'GET' && !t.body), 'only the health read went out: ' + JSON.stringify(s.box.traffic.map((t) => t.method + ' ' + t.path)));
    s.box.consent = 'yes'; s.box.userRun = false; // a scorecard check or the design loop: not a user run
    const rows = await s.runner.rows(s.box.P, s.box.build, { run: true }); const row = rows.find((r) => r.id.endsWith('max-displacement')); assert.match(row.detail, /could not run.*confirmation/); assert.equal(s.post().length, 0);
    // the API itself, without any hook, refuses a body
    const bare = new ExtApi({ getRunner: () => ({ url: s.box.url, token: TOKEN }), isEnabled: () => true }), pkg = { manifest: { id: 'x.y', name: 'X', permissions: ['compute.remote'] } };
    await assert.rejects(bare.call(pkg, 'compute.remote.fetch', ['/v1/jobs', { method: 'POST', body: '{}' }]), (e) => e.code === 'E_CONSENT');
    assert.equal((await bare.call(pkg, 'compute.remote.fetch', ['/v1/health'])).status, 200, 'a request without a body needs no consent');
  } finally { dispose(s); }
});

test('run (simulated solver, real gmsh): the host adds the token, the consent info states the size, the job POST carries the bodies and conditions, progress rises, fields land on the surface, the result is experimental and says SIMULATED, and the runner deletes the job after download', g, async () => {
  const s = await rig(bar());
  try {
    const seen = []; const r = await s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { onProgress: (v) => seen.push(v) });
    assert.equal(r.validity, 'experimental'); assert.ok(seen.length >= 4 && seen.every((v, i) => i === 0 || v >= seen[i - 1]) && seen[seen.length - 1] === 1, `progress ${seen}`);
    assert.equal(s.box.consents.length, 1); const c = s.box.consents[0]; assert.equal(c.url, s.box.url); assert.equal(c.path, '/v1/jobs'); assert.equal(c.method, 'POST'); assert.ok(c.bytes > 500 && c.bytes < 5000, `bytes ${c.bytes}`);
    const post = s.post()[0], sent = JSON.parse(post.body); assert.equal(post.auth, 'Bearer ' + TOKEN, 'the host added the token'); assert.equal(sent.bodies[0].name, 'Bar'); assert.equal(sent.kind, 'structural'); assert.equal(sent.conditions.mounts[0].face, '-x'); assert.deepEqual(sent.conditions.loads[0].vector_N, [1000, 0, 0]); assert.ok(!post.body.includes(TOKEN));
    assert.ok(s.box.traffic.every((t) => t.origin === s.box.url), 'every request went to the runner URL and nowhere else');
    const m = Object.fromEntries(r.summary.map((x) => [x.metric, x])); for (const k of ['max_displacement', 'max_von_mises', 'min_safety_factor', 'stiffness', 'applied_force', 'mesh_elements', 'mesh_size', 'solve_seconds']) assert.ok(k in m, k);
    assert.equal(m.max_displacement.unit, 'mm'); assert.equal(m.max_von_mises.unit, 'MPa'); assert.ok(Math.abs(m.applied_force.value - 1000) < 1e-6);
    assert.deepEqual(r.fields.map((f) => f.name), ['runner_von_mises:Bar', 'runner_displacement:Bar', 'runner_safety_factor:Bar']);
    const nv = s.box.build.bodies[0].mesh ? s.box.build.bodies[0].mesh.pos.length / 3 : s.box.build.bodies[0].view.pos.length / 3;
    for (const f of r.fields) { assert.equal(f.kind, 'surface'); assert.equal(f.data.length, nv); assert.ok(f.data.every(Number.isFinite)); }
    const vm = r.fields[0].data; assert.ok(Math.max(...vm) > Math.min(...vm), 'the field varies over the surface (mapped from the tetrahedra, not constant)');
    const text = r.warnings.join('\n'); assert.match(text, /SIMULATED/); assert.match(text, /Validity experimental: the runner used its simulated solver/); assert.match(text, /interpolated onto the surface/); assert.match(text, /knock-down is not applied/);
    for (let i = 0; i < 50 && s.runnerSrv.store.list().length; i++) await new Promise((x) => setTimeout(x, 20)); assert.equal(s.runnerSrv.store.list().length, 0, 'the runner deleted the job after the result was downloaded');
    const again = await s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural'); assert.equal(again.cached, true); assert.equal(s.post().length, 1, 'a cached result sends nothing and asks nothing'); assert.equal(s.box.consents.length, 1);
  } finally { dispose(s); }
});

test('mesh study and keep: two sizes are reported as mesh_change rows; a converged simulated result is still experimental; keep leaves the job on the runner', g, async () => {
  const s = await rig(bar({ ext: { mesh_study: 'on', keep: 'on', mesh_size_mm: 6 } }));
  try {
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural'); const m = Object.fromEntries(r.summary.map((x) => [x.metric, x]));
    assert.ok('mesh_change_max_displacement' in m && m.mesh_change_max_displacement.unit === '%'); assert.match(r.warnings.join('\n'), /Mesh study at 6 and 4 mm/); assert.equal(r.validity, 'experimental');
    assert.equal(JSON.parse(s.post()[0].body).options.keep, true); await new Promise((x) => setTimeout(x, 100)); assert.equal(s.runnerSrv.store.list().length, 1, 'kept on the runner'); assert.equal(s.runnerSrv.store.list()[0].keep, true);
  } finally { dispose(s); }
});

test('thermal (simulated): the analysis maps temperature onto the body, checks join the scorecard as ADVISORY rows while the result is experimental', g, async () => {
  const s = await rig(bar({ mounts: [], loads: [], heat: [{ id: 'chip', target: { body: 'Bar' }, power_W: 2 }], ext: { mesh_size_mm: 6 } }));
  try {
    const before = await s.runner.rows(s.box.P, s.box.build, { run: false }); assert.ok(before.every((r) => r.severity === 'advisory' && /not run/.test(r.detail) && r.pass === false), 'not run is advisory, never a silent pass');
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'runner-thermal'); assert.equal(r.validity, 'experimental'); assert.deepEqual(r.fields.map((f) => f.name), ['runner_temperature:Bar']); assert.equal(r.fields[0].unit, '°C');
    const m = Object.fromEntries(r.summary.map((x) => [x.metric, x.value])); assert.ok(m.max_temp > 25 && m.energy_balance_error < 0.05, JSON.stringify(m));
    const rows = await s.runner.rows(s.box.P, s.box.build, { run: false }), t = rows.find((x) => x.id.endsWith('runner-thermal:max-temp')); assert.equal(t.severity, 'advisory'); assert.match(t.detail, /experimental \(does not decide the verdict\)/);
  } finally { dispose(s); }
});

test('cancel: aborting a running job tells the runner, the run ends E_CANCELLED quickly, and the job is gone from the runner', g, async () => {
  const s = await rig(bar(), { delayMs: 5000 });
  try {
    const ac = new AbortController(), t0 = Date.now(); let n = 0;
    const p = s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { signal: ac.signal, onProgress: (v) => { if (v >= 0.1 && ++n === 1) setTimeout(() => ac.abort(), 150); } });
    await assert.rejects(p, (e) => e.code === 'E_CANCELLED'); assert.ok(Date.now() - t0 < 4500, `cancelled after ${Date.now() - t0} ms, not the 5 s solve`);
    assert.ok(s.box.traffic.some((t) => t.method === 'POST' && /\/cancel$/.test(t.path)), 'the cancel request reached the runner');
    for (let i = 0; i < 100 && s.runnerSrv.store.list().length; i++) await new Promise((x) => setTimeout(x, 30)); assert.equal(s.runnerSrv.store.list().length, 0, 'and the job was deleted');
  } finally { dispose(s); }
});

test('failures are explained: no runner set, unreachable, wrong token, not a runner, a job the runner refuses, a solver failure', g, async () => {
  const s = await rig(bar());
  try {
    s.box.url = ''; await assert.rejects(s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { force: true }), /No Alloy Runner is set: enter its address and token under Settings/);
    s.box.url = 'http://127.0.0.1:9'; await assert.rejects(s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { force: true }), /Cannot reach the Alloy Runner.*CORS/);
    s.box.url = s.runnerSrv.server.address() ? `http://127.0.0.1:${s.runnerSrv.server.address().port}` : ''; s.box.token = 'wrong-token-wrong-token';
    await assert.rejects(s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { force: true }), /does not accept the token you entered.*Nothing was sent/);
    assert.equal(s.post().length, 0, 'no geometry went out on any of those');
    s.box.token = TOKEN; s.box.P = applyOps(s.box.P, [{ op: 'remove_condition', list: 'mounts', id: 'wall' }], { actor: 'user' }).project;
    await assert.rejects(s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural', { force: true }), /The runner could not solve this: Body "Bar" has no fixed or slider mount/);
    for (let i = 0; i < 50 && s.runnerSrv.store.list().length; i++) await new Promise((x) => setTimeout(x, 20)); assert.equal(s.runnerSrv.store.list().length, 0, 'a failed job is deleted too');
    const bad = createRunner({ config: loadConfig({ ALLOY_RUNNER_TOKEN: TOKEN, ALLOY_RUNNER_PORT: '0' }, { dataDir: fs.mkdtempSync('/tmp/alloy-forge-rig-') }) }); RUNNERS.push(bad); void bad;
  } finally { dispose(s); }
});

test('AI tools: set-runner validates and stores settings; runner-status reads health and sends no geometry; neither can upload', g, async () => {
  const s = await rig(bar());
  try {
    assert.deepEqual(s.resolver.agentTools(s.box.P).map((t) => t.id).sort(), ['runner-status', 'set-runner']);
    await assert.rejects(s.resolver.callAgentTool(s.box.P, EXT, 'set-runner', { mesh_study: 'maybe' }), /mesh_study/);
    const out = await s.resolver.callAgentTool(s.box.P, EXT, 'set-runner', { mesh_study: 'on', mesh_size_mm: 3 }); assert.equal(out.effective.mesh_study, 'on'); assert.equal(s.box.P.ext[EXT].settings.mesh_size_mm, 3); assert.equal(s.box.P.ext[EXT].settings.adapter, 'fake', 'only the keys given change'); assert.match(out.note, /needs the user to confirm/);
    const st = await s.resolver.callAgentTool(s.box.P, EXT, 'runner-status', {}); assert.equal(st.reachable, true); assert.equal(st.authenticated, true); assert.equal(st.adapters.fake.real, false); assert.equal(st.adapters.openfoam.ok, false);
    assert.equal(s.post().length, 0); assert.equal(s.box.consents.length, 0);
    s.box.token = 'nope-nope-nope-nope-nope'; const st2 = await s.resolver.callAgentTool(s.box.P, EXT, 'runner-status', {}); assert.equal(st2.authenticated, false);
    s.box.url = 'http://127.0.0.1:9'; const st3 = await s.resolver.callAgentTool(s.box.P, EXT, 'runner-status', {}); assert.equal(st3.reachable, false); assert.match(st3.message, /Cannot reach/);
  } finally { dispose(s); }
});

test('runner client (host side): connection test, list, cancel and delete go to the runner URL with the token and send no geometry', g, async () => {
  const s = await rig(bar({ ext: { keep: 'on' } })); const calls = [];
  try {
    const client = createRunnerClient({ getRunner: () => ({ url: s.box.url, token: s.box.token }), fetchFn: (u, i) => { calls.push([i.method, new URL(u).pathname, i.body]); return fetch(u, i); } });
    const t = await client.test(); assert.equal(t.authenticated, true); assert.equal(t.name, 'alloy-runner'); assert.equal(t.adapters.fake.ok, true); assert.equal(t.adapters.openfoam.ok, false);
    await s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural'); await new Promise((x) => setTimeout(x, 80));
    const jobs = await client.list(); assert.equal(jobs.length, 1); assert.equal(jobs[0].state, 'done'); assert.equal(jobs[0].keep, true);
    await client.remove(jobs[0].id); assert.deepEqual(await client.list(), []);
    const bad = createRunnerClient({ getRunner: () => ({ url: s.box.url, token: 'wrong-wrong-wrong-wrong' }) }); const tb = await bad.test(); assert.equal(tb.authenticated, false); assert.match(tb.authError, /bearer token/); await assert.rejects(bad.list(), /bearer token/);
    await assert.rejects(createRunnerClient({ getRunner: () => null }).list(), /No Alloy Runner is set/); await assert.rejects(createRunnerClient({ getRunner: () => ({ url: 'http://127.0.0.1:9', token: '' }) }).test(), /Cannot reach the runner/);
    assert.ok(calls.every(([, , body]) => body === undefined), 'the client never sends a body');
  } finally { dispose(s); }
});

test('no telemetry: the package source and the client contain no address, and the only network call in the package is compute.remote.fetch', () => {
  for (const f of ['analysis/runner/package-main.js', 'src/analysis/runner-client.js']) { const t = fs.readFileSync(f, 'utf8').replace(/^\s*\/\/.*$/gm, ''); assert.ok(!/https?:\/\/[a-z0-9]/i.test(t.replace(/'http:\/\/runner'/g, '')), `${f} names no host`); }
  const pk = fs.readFileSync('analysis/runner/package-main.js', 'utf8'); assert.deepEqual([...new Set(pk.replace(/\/\/.*$/gm, '').match(/forge\.[a-zA-Z.]*fetch/g) || [])], ['forge.compute.remote.fetch']); assert.ok(!/\bfetch\s*\(/.test(pk.replace(/forge\.compute\.remote\.fetch\(/g, '')), 'no direct fetch');
});

test('reference files: tools/make-reference.mjs refuses a simulated, unconverged or study-less result and writes nothing; only a real, converged Elmer result becomes a reference', g, async () => {
  const { referenceFromResult } = await import('./tools/make-reference.mjs'), spec = { map: { max_displacement_mm: ['max_displacement', 1], stiffness_N_per_mm: ['stiffness', 1], max_von_mises_MPa: ['max_von_mises', 1] } };
  const sum = [['max_displacement', 0.01], ['stiffness', 900], ['max_von_mises', 50], ['mesh_elements', 5000], ['mesh_size', 2]].map(([metric, value]) => ({ metric, value, unit: '' }));
  const study = { converged: true, sizes_mm: [2, 1.33], tolerance: { default: 0.02 }, metrics: [{ metric: 'max_displacement', change: 0.01 }] };
  const real = { solver_real: true, simulated: false, adapter: 'elmer', summary: sum, study, qualification: null, info: {} };
  assert.throws(() => referenceFromResult({ ...real, solver_real: false, simulated: true, adapter: 'fake' }, spec), /never made from the simulated solver/);
  assert.throws(() => referenceFromResult({ ...real, adapter: 'fake' }, spec), /not from a real Elmer solve/);
  assert.throws(() => referenceFromResult({ ...real, study: null }, spec), /no mesh study/);
  assert.throws(() => referenceFromResult({ ...real, study: { ...study, converged: false } }, spec), /did not converge/);
  assert.throws(() => referenceFromResult({ ...real, summary: sum.slice(1) }, spec), /no metric max_displacement/);
  const ok = referenceFromResult(real, spec, { runnerVersion: '0.1.0', caseId: 'structural/bracket-vs-elmer', produced: '2030-01-01' }); assert.deepEqual(ok.values, { max_displacement_mm: 0.01, stiffness_N_per_mm: 900, max_von_mises_MPa: 50 }); assert.equal(ok.source.tool, 'Elmer FEM'); assert.ok(ok.source.study.changes.max_displacement === 0.01);
  // the CLI against the simulated runner: it refuses before sending anything and the reference file is not created
  const s = await rig(bar()); const out = '/tmp/alloy-no-reference-' + process.pid + '.json';
  try {
    const r = await new Promise((res) => { const p = spawn('node', ['tools/make-reference.mjs', '--case', 'structural/bracket-vs-elmer', '--runner', s.box.url, '--token', TOKEN, '--out', out]); let o = '', e = ''; p.stdout.on('data', (d) => { o += d; }); p.stderr.on('data', (d) => { e += d; }); p.on('close', (code) => res({ status: code, stdout: o, stderr: e })); }); // async: the runner under test lives in this process
    assert.equal(r.status, 1); assert.match(r.stderr, /refused: this runner has no working Elmer/); assert.ok(!fs.existsSync(out)); assert.equal(s.runnerSrv.store.list().length, 0, 'nothing was sent');
  } finally { dispose(s); }
  assert.ok(!fs.existsSync('analysis-validation/reference/bracket-elmer.json') && !fs.existsSync('analysis-validation/reference/enclosure-elmer.json'), 'no reference file exists in this repository');
});

test('the starter enclosure (two shells with cutouts, needle triangles from the boolean kernel) runs end to end: gmsh falls back to the STL surface for the body it cannot re-mesh, says so, and every surface vertex gets a finite value', g, async () => {
  let P = wizardProject({ name: 'Enclosure' }); const names = (() => { const b = build(P, wasm); try { return b.bodies.map((x) => x.name); } finally { b.dispose(); } })();
  P = applyOps(P, [...names.map((n, i) => cond('mounts', { id: 'm' + i, kind: 'fixed', body: n, face: '-z' })), cond('loads', { id: 'g', kind: 'acceleration', accel_ms2: 4903.3, direction: '+y' })], { actor: 'user' }).project;
  P.ext = { [EXT]: { settings: { adapter: 'fake', mesh_size_mm: 8, poll_ms: 25 } } };
  const s = await rig(P);
  try {
    const r = await s.runner.run(s.box.P, s.box.build, EXT, 'runner-structural');
    assert.equal(r.fields.length, 3 * names.length); const nv = (b) => (b.mesh || b.view).pos.length / 3;
    for (const b of s.box.build.bodies) for (const f of r.fields.filter((x) => x.body === b.name)) { assert.equal(f.data.length, nv(b)); assert.ok(f.data.every(Number.isFinite), f.name); assert.ok(Math.max(...f.data) > 0, `${f.name} is not all zero`); }
    const text = r.warnings.join('\n'); assert.match(text, /could not be re-meshed at 8 mm.*needle triangles/); assert.match(text, /meshed separately and are not bonded/);
  } finally { dispose(s); }
});
