// Protocol and job lifecycle through a real HTTP server on 127.0.0.1, with the SIMULATED adapter (it meshes with the real gmsh when
// that is installed; those tests are skipped otherwise). Nothing here runs a real solver.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRunner } from '../src/server.mjs';
import { parseVtu } from '../src/lib/vtu.js';
import { api, waitState, barJob, thermalJob, testConfig, hasGmsh, b64, boxStl } from './helpers.mjs';

let R, base; const T = 'test-token-0123456789abcdef';
before(async () => { R = createRunner({ config: testConfig() }); const a = await R.listen(); base = `http://127.0.0.1:${a.port}`; });
after(async () => { await R.close(); });
const g = { skip: hasGmsh ? false : 'gmsh is not installed' };

test('auth: health is open but minimal; everything else needs the exact bearer token; the token is never echoed', async () => {
  let r = await api(base, null, 'GET', '/v1/health'); assert.equal(r.status, 200); assert.deepEqual(Object.keys(r.json).sort(), ['name', 'ok', 'protocol', 'version']);
  for (const tok of [null, 'wrong', T + 'x', T.slice(1)]) { r = await api(base, tok, 'GET', '/v1/jobs'); assert.equal(r.status, 401); assert.equal(r.json.error.code, 'E_AUTH'); assert.ok(!r.text.includes(T)); }
  r = await api(base, T, 'GET', '/v1/jobs'); assert.equal(r.status, 200); assert.deepEqual(r.json.jobs, []);
  r = await api(base, T, 'POST', '/v1/jobs', barJob(), { headers: { Authorization: 'Bearer nope' } }); assert.equal(r.status, 401);
});

test('health with the token lists every adapter, why one is unavailable, the limits; OpenFOAM is reported as not found (no OpenFOAM here)', async () => {
  const r = await api(base, T, 'GET', '/v1/health'); assert.equal(r.json.authenticated, true);
  assert.equal(r.json.adapters.fake.ok, true); assert.equal(r.json.adapters.fake.real, false);
  assert.equal(r.json.adapters.openfoam.ok, false); assert.match(r.json.adapters.openfoam.reason, /not found: blockMesh/);
  assert.equal(typeof r.json.adapters.elmer.ok, 'boolean'); if (!r.json.adapters.elmer.ok) assert.match(r.json.adapters.elmer.reason, /not found/);
  assert.ok(r.json.limits.max_tets > 0); assert.equal(r.json.qualification, null);
});

test('CORS: only a listed origin is answered, preflight is handled, private-network preflight is allowed for it', async () => {
  const R2 = createRunner({ config: testConfig({ origins: ['https://forge.example.org'] }) }), a = await R2.listen(), b2 = `http://127.0.0.1:${a.port}`;
  try {
    let r = await fetch(b2 + '/v1/jobs', { method: 'OPTIONS', headers: { Origin: 'https://forge.example.org', 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type', 'Access-Control-Request-Private-Network': 'true' } });
    assert.equal(r.status, 204); assert.equal(r.headers.get('access-control-allow-origin'), 'https://forge.example.org'); assert.match(r.headers.get('access-control-allow-headers'), /Authorization/); assert.equal(r.headers.get('access-control-allow-private-network'), 'true');
    r = await fetch(b2 + '/v1/health', { headers: { Origin: 'https://evil.example.org' } }); assert.equal(r.headers.get('access-control-allow-origin'), null);
    r = await fetch(b2 + '/v1/health'); assert.equal(r.headers.get('access-control-allow-origin'), null);
  } finally { await R2.close(); }
});

test('submission validation names the wrong field (protocol, kind, names, STL, materials, faces, numbers) and never accepts an open or inside-out STL', async () => {
  const bad = async (mut, re, status = 400) => { const j = barJob(); mut(j); const r = await api(base, T, 'POST', '/v1/jobs', j); assert.equal(r.status, status, r.text); assert.match(r.json.error.message, re); };
  await bad((j) => { j.protocol = 2; }, /protocol must be 1/);
  await bad((j) => { j.kind = 'plasma'; }, /kind must be one of structural, thermal, flow/);
  await bad((j) => { j.adapter = 'openfoam'; }, /openfoam adapter handles kind flow only/);
  await bad((j) => { j.adapter = 'cfx'; }, /adapter must be/);
  await bad((j) => { j.bodies = []; }, /non-empty/);
  await bad((j) => { j.bodies[0].name = '../x'; }, /bodies\[0\]\.name/);
  await bad((j) => { j.bodies.push({ ...j.bodies[0] }); }, /used twice/);
  await bad((j) => { j.bodies[0].format = 'step'; }, /step.*not accepted/);
  await bad((j) => { j.bodies[0].data_b64 = '!!'; }, /not base64/);
  await bad((j) => { j.bodies[0].data_b64 = b64(new TextEncoder().encode('not an stl')); }, /STL/);
  await bad((j) => { j.bodies[0].material = { nu: 0.3 }; }, /needs E_Pa and nu/);
  await bad((j) => { j.bodies[0].material.E_Pa = -1; }, /E_Pa must be a number/);
  await bad((j) => { j.conditions.mounts[0].face = 'up'; }, /face must be one of/);
  await bad((j) => { j.conditions.loads[0].vector_N = [1, 2]; }, /vector_N/);
  await bad((j) => { j.conditions.loads[0].id = 'wall'; }, /used twice/);
  await bad((j) => { j.conditions.mounts[0].box = [1, 2, 3]; }, /box must be/);
  await bad((j) => { j.options.mesh_size_mm = -1; }, /mesh_size_mm/);
  const open = barJob(); const s = Buffer.from(boxStl(10, 10, 10)); s.writeUInt32LE(11, 80); open.bodies[0].data_b64 = b64(s.subarray(0, 84 + 50 * 11));
  const r = await api(base, T, 'POST', '/v1/jobs', open); assert.equal(r.status, 400); assert.match(r.json.error.message, /closed surface|volume/);
  const r2 = await api(base, T, 'POST', '/v1/jobs', 'not json'); assert.equal(r2.status, 400);
});

test('size limit: a body over the limit is refused with 413 before it is parsed', async () => {
  const R2 = createRunner({ config: testConfig({ maxBodyBytes: 1000 }) }), a = await R2.listen();
  try { const r = await api(`http://127.0.0.1:${a.port}`, T, 'POST', '/v1/jobs', barJob()); assert.equal(r.status, 413); assert.equal(r.json.error.code, 'E_TOO_LARGE'); } catch (e) { assert.match(String(e.cause?.code || e.message), /ECONNRESET|UND_ERR|fetch failed/); } finally { await R2.close(); }
});

test('a job asking for a solver that is not there fails with NO_SOLVER and says why (no real solver, simulated one disabled)', async () => {
  const R2 = createRunner({ config: testConfig({ enableFake: false, elmerBin: '/nonexistent/ElmerSolver' }) }), a = await R2.listen(), b2 = `http://127.0.0.1:${a.port}`;
  try {
    const sub = await api(b2, T, 'POST', '/v1/jobs', barJob()); assert.equal(sub.status, 202);
    const j = await waitState(b2, T, sub.json.id, ['failed']); assert.equal(j.error.code, 'NO_SOLVER'); assert.match(j.error.message, /elmer:.*(not found|gmsh)/); assert.match(j.error.message, /fake: disabled/);
  } finally { await R2.close(); }
});

test('lifecycle (simulated solver, real gmsh): queued -> running -> done with ordered progress events, a summary in mm / MPa, a VTU with a body field, and deletion after the last download', g, async () => {
  const sub = await api(base, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake' })); assert.equal(sub.status, 202); const id = sub.json.id; assert.match(id, /^[0-9a-f]{18}$/);
  const j = await waitState(base, T, id, ['done']); assert.equal(j.progress, 1);
  assert.deepEqual(j.events.map((e) => e.n), j.events.map((_, i) => i + 1), 'events are numbered from 1'); const ps = j.events.map((e) => e.progress); assert.deepEqual(ps, [...ps].sort((a, b) => a - b), 'progress never goes backwards');
  assert.ok(j.events.some((e) => /meshing/.test(e.msg)) && j.events.some((e) => /solving/.test(e.msg)) && j.events.some((e) => e.msg === 'done'));
  assert.deepEqual(j.files.map((f) => f.name).sort(), ['result.json', 'result.vtu']);
  const since = await api(base, T, 'GET', `/v1/jobs/${id}?since=${j.next - 1}`); assert.equal(since.json.events.length, 1);
  const res = await api(base, T, 'GET', `/v1/jobs/${id}/result`); assert.equal(res.status, 200);
  assert.equal(res.json.simulated, true); assert.equal(res.json.solver_real, false); assert.equal(res.json.validity_hint, 'experimental'); assert.ok(res.json.warnings.some((w) => /SIMULATED/.test(w)));
  const by = Object.fromEntries(res.json.summary.map((s) => [s.metric, s])); for (const m of ['max_displacement', 'max_von_mises', 'min_safety_factor', 'stiffness', 'applied_force', 'mesh_elements']) assert.ok(m in by, m);
  assert.equal(by.max_displacement.unit, 'mm'); assert.equal(by.max_von_mises.unit, 'MPa'); assert.ok(Math.abs(by.applied_force.value - 1000) < 1e-6);
  assert.ok(res.json.warnings.some((w) => /knock-down/.test(w)));
  let still = await api(base, T, 'GET', `/v1/jobs/${id}`); assert.equal(still.status, 200, 'the job stays until every file is fetched');
  const vtu = await api(base, T, 'GET', `/v1/jobs/${id}/files/result.vtu`); assert.equal(vtu.status, 200);
  const p = parseVtu(vtu.text); assert.ok(p.cells.types.every((t) => t === 10), 'tetrahedra'); assert.ok('displacement' in p.pointData && 'von_mises' in p.pointData && 'body' in p.cellData);
  for (let i = 0; i < 3 && (await api(base, T, 'GET', `/v1/jobs/${id}`)).status === 200; i++) await new Promise((s) => setTimeout(s, 20));
  still = await api(base, T, 'GET', `/v1/jobs/${id}`); assert.equal(still.status, 404, 'deleted after download'); assert.equal(still.json.error.code, 'E_NOT_FOUND');
  assert.ok(!fs.existsSync(`${R.store.config.dataDir}/jobs/${id}`), 'and the files are gone from disk');
});

test('keep: a kept job survives its downloads until it is deleted; geometry is not left on disk after the solve', g, async () => {
  const sub = await api(base, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake', options: { mesh_size_mm: 5, keep: true } })); const id = sub.json.id;
  await waitState(base, T, id, ['done']); for (const f of ['result.json', 'result.vtu']) assert.equal((await api(base, T, 'GET', `/v1/jobs/${id}/files/${f}`)).status, 200);
  await new Promise((s) => setTimeout(s, 60)); assert.equal((await api(base, T, 'GET', `/v1/jobs/${id}`)).status, 200, 'still there');
  const dir = `${R.store.config.dataDir}/jobs/${id}`; assert.deepEqual(fs.readdirSync(dir).sort(), ['result.json', 'result.vtu'], 'only the result files remain (no STL, no mesh)');
  assert.equal(R.store.get(id).job.bodies[0].bytes, null, 'the geometry is dropped from memory');
  assert.equal((await api(base, T, 'DELETE', `/v1/jobs/${id}`)).status, 200); assert.equal((await api(base, T, 'GET', `/v1/jobs/${id}`)).status, 404); assert.ok(!fs.existsSync(dir));
});

test('retention: an undownloaded finished job is removed after the TTL (clock injected); a kept one is not', g, async () => {
  let now = Date.now(); const R2 = createRunner({ config: testConfig({ ttlSeconds: 60 }) }); R2.store.now = () => now; const a = await R2.listen(), b2 = `http://127.0.0.1:${a.port}`;
  try {
    const a1 = (await api(b2, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake', options: { mesh_size_mm: 5 } }))).json.id, a2 = (await api(b2, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake', options: { mesh_size_mm: 5, keep: true } }))).json.id;
    await waitState(b2, T, a1, ['done']); await waitState(b2, T, a2, ['done']);
    now += 30_000; R2.store.sweep(); assert.equal((await api(b2, T, 'GET', `/v1/jobs/${a1}`)).status, 200);
    now += 40_000; R2.store.sweep(); assert.equal((await api(b2, T, 'GET', `/v1/jobs/${a1}`)).status, 404); assert.equal((await api(b2, T, 'GET', `/v1/jobs/${a2}`)).status, 200);
  } finally { await R2.close(); }
});

test('cancel: a running job stops quickly and ends cancelled; a queued job is cancelled before it starts; a finished job cannot be fetched as a result after cancel', g, async () => {
  const slow = barJob({ adapter: 'fake', options: { mesh_size_mm: 5, fake_delay_ms: 5000 } });
  const first = (await api(base, T, 'POST', '/v1/jobs', slow)).json.id, second = (await api(base, T, 'POST', '/v1/jobs', slow)).json.id;
  await waitState(base, T, first, ['running']); const q = await api(base, T, 'GET', `/v1/jobs/${second}`); assert.equal(q.json.state, 'queued', 'one job at a time (concurrency 1)');
  const t0 = Date.now(); assert.equal((await api(base, T, 'POST', '/v1/jobs/' + second + '/cancel')).status, 200); assert.equal((await api(base, T, 'GET', `/v1/jobs/${second}`)).json.state, 'cancelled');
  for (let i = 0; i < 100; i++) { const j = (await api(base, T, 'GET', `/v1/jobs/${first}`)).json; if (j.stage === 'solving') break; await new Promise((s) => setTimeout(s, 20)); }
  await api(base, T, 'POST', `/v1/jobs/${first}/cancel`); const j = await waitState(base, T, first, ['cancelled']); assert.ok(Date.now() - t0 < 4000, 'did not wait for the 5 s solve'); assert.ok(j.events.some((e) => /cancel/.test(e.msg)));
  const r = await api(base, T, 'GET', `/v1/jobs/${first}/result`); assert.equal(r.status, 409); assert.equal(r.json.error.code, 'E_NOT_READY');
  await api(base, T, 'DELETE', `/v1/jobs/${first}`); await api(base, T, 'DELETE', `/v1/jobs/${second}`);
});

test('timeout: a job that runs past the limit is stopped and says so', g, async () => {
  const R2 = createRunner({ config: testConfig({ jobTimeoutSeconds: 1 }) }), a = await R2.listen(), b2 = `http://127.0.0.1:${a.port}`;
  try { const id = (await api(b2, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake', options: { mesh_size_mm: 5, fake_delay_ms: 5000 } }))).json.id; const j = await waitState(b2, T, id, ['cancelled', 'failed'], 15000); assert.equal(j.error.code, 'E_TIMEOUT'); } finally { await R2.close(); }
});

test('mesh study (simulated): two sizes are solved, the change in each metric is reported, and a mesh-dependent result is not "converged"', g, async () => {
  const run = async (dep) => { const id = (await api(base, T, 'POST', '/v1/jobs', barJob({ adapter: 'fake', options: { mesh_size_mm: 6, mesh_study: true, fake_mesh_dependence: dep, keep: true } }))).json.id; await waitState(base, T, id, ['done']); const r = (await api(base, T, 'GET', `/v1/jobs/${id}/result`)).json; await api(base, T, 'DELETE', `/v1/jobs/${id}`); return r; };
  const ok = await run(0); assert.equal(ok.study.converged, true); assert.deepEqual(ok.study.sizes_mm, [6, 4]); assert.ok(ok.study.metrics.every((m) => m.change < 0.02), JSON.stringify(ok.study.metrics));
  const no = await run(0.5); assert.equal(no.study.converged, false); assert.ok(no.study.metrics.find((m) => m.metric === 'max_displacement').change > 0.02);
  assert.equal(no.validity_hint, 'experimental', 'a simulated result is never anything but experimental');
});

test('thermal (simulated): the lumped answer closes the energy balance; contact mounts and heat sources are applied', g, async () => {
  const id = (await api(base, T, 'POST', '/v1/jobs', thermalJob({ adapter: 'fake', options: { mesh_size_mm: 5, keep: true } }))).json.id; await waitState(base, T, id, ['done']);
  const r = (await api(base, T, 'GET', `/v1/jobs/${id}/result`)).json, by = Object.fromEntries(r.summary.map((s) => [s.metric, s.value]));
  assert.ok(Math.abs(by.heat_in - 2) < 1e-9); assert.ok(by.energy_balance_error < 0.02, `balance ${by.energy_balance_error}`); // boundary area of the mesh is the surface area of the box to within the re-meshed facets
  const area = 2 * (20 * 20 + 2 * 20 * 10) * 1e-6, rise = 2 / (10 * area); assert.ok(Math.abs(by.max_temp - (20 + rise)) < 0.05 * rise, `${by.max_temp} vs ${20 + rise}`);
  await api(base, T, 'DELETE', `/v1/jobs/${id}`);
});

test('mounts that match nothing are warned about; a body with nothing holding it is refused', g, async () => {
  let j = barJob({ adapter: 'fake', options: { mesh_size_mm: 6, keep: true } }); j.conditions.loads.push({ id: 'ghost', kind: 'pressure', body: 'Bar', face: '+z', box: [100, 110, 0, 1, 0, 1], pressure_Pa: 1e6 });
  let id = (await api(base, T, 'POST', '/v1/jobs', j)).json.id; await waitState(base, T, id, ['done']); const r = (await api(base, T, 'GET', `/v1/jobs/${id}/result`)).json; assert.ok(r.warnings.some((w) => /ghost.*matches no free boundary faces/.test(w))); await api(base, T, 'DELETE', `/v1/jobs/${id}`);
  j = barJob({ adapter: 'fake', options: { mesh_size_mm: 6 } }); j.conditions.mounts = []; id = (await api(base, T, 'POST', '/v1/jobs', j)).json.id;
  const f = await waitState(base, T, id, ['failed']); assert.equal(f.error.code, 'UNSUPPORTED'); assert.match(f.error.message, /no fixed or slider mount/);
});

test('the runner makes no outbound connection: the source contains no client of any kind', () => {
  for (const f of fs.readdirSync(new URL('../src', import.meta.url), { recursive: true })) {
    if (!/\.(m?js)$/.test(f)) continue; const s = fs.readFileSync(new URL('../src/' + f, import.meta.url), 'utf8');
    assert.ok(!/\bfetch\s*\(|node:https?['"]\)?[^;]*request|http\.request|https\.request|node:net|node:dgram|WebSocket|XMLHttpRequest/.test(s.replace(/^\s*\/\/.*$/gm, '')) || /server\.mjs$/.test(f), f + ' must not open connections'); // server.mjs imports node:http to LISTEN
  }
  assert.ok(!/http\.request|http\.get|https\./.test(fs.readFileSync(new URL('../src/server.mjs', import.meta.url), 'utf8')));
});
