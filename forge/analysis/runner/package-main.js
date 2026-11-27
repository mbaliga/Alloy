// Alloy Runner client (A4). Two analyses, "runner-structural" and "runner-thermal", that send the design to an Alloy Runner the user runs
// (a small HTTP service that meshes with gmsh and solves with Elmer, see ../../runner) and bring the result back as the same kind of
// surface fields the other analyses make. This file is concatenated after runner/src/lib/stl.js and vtu.js by tools/bundle-ext.mjs into
// examples/ext/runner/main.js, so parseStl / writeStlBinary / parseVtu are in scope.
//
// What goes over the network, and when: this package only ever talks to `forge.compute.remote.fetch`, which the host restricts to the
// runner URL the user typed in Settings and to which it adds the bearer token (this code never sees the token). The geometry leaves
// the browser in ONE call, the job POST; the host refuses that call unless the user has just confirmed the consent text in the Analyze
// pane (an AI tool, a loop run or a scorecard check can never upload). Everything else (health, polling, cancel, download) carries no geometry.
const RUN_ID = 'xyz.mdhv.runner';
const KINDS = { 'runner-structural': 'structural', 'runner-thermal': 'thermal' };
const PROTOCOL = 1;

const DEFAULT_SETTINGS = { adapter: 'auto', mesh_size_mm: 0, mesh_study: 'off', keep: 'off', self_weight: 'off', h_conv_W_m2K: 10, poll_ms: 500, timeout_s: 1800 };

function parseSettings(raw) {
  const s = { ...DEFAULT_SETTINGS }, warnings = [], r = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const en = (k, opts) => { if (r[k] === undefined) return; if (opts.includes(r[k])) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} is not one of ${opts.join(', ')}; ${s[k]} is used.`); };
  const nu = (k, lo, hi) => { if (r[k] === undefined) return; if (typeof r[k] === 'number' && Number.isFinite(r[k]) && r[k] >= lo && r[k] <= hi) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} must be a number from ${lo} to ${hi}; ${s[k]} is used.`); };
  en('adapter', ['auto', 'elmer', 'fake']); en('mesh_study', ['off', 'on']); en('keep', ['off', 'on']); en('self_weight', ['off', 'on']);
  nu('mesh_size_mm', 0, 1000); nu('h_conv_W_m2K', 0, 100000); nu('poll_ms', 20, 10000); nu('timeout_s', 10, 86400);
  return { settings: s, warnings };
}

// ───────────────────────── base64 (no btoa on very long strings) ─────────────────────────
const B64C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
function toBase64(u8) {
  const parts = [], n = u8.length;
  for (let i = 0; i < n; i += 3 * 4096) {
    let s = '';
    for (let j = i; j < Math.min(n, i + 3 * 4096); j += 3) {
      const a = u8[j], b = j + 1 < n ? u8[j + 1] : 0, c = j + 2 < n ? u8[j + 2] : 0, t = (a << 16) | (b << 8) | c;
      s += B64C[(t >> 18) & 63] + B64C[(t >> 12) & 63] + (j + 1 < n ? B64C[(t >> 6) & 63] : '=') + (j + 2 < n ? B64C[t & 63] : '=');
    }
    parts.push(s);
  }
  return parts.join('');
}

// ───────────────────────── the request ─────────────────────────
const MAT_KEYS = { structural: [['E', 'E_Pa'], ['nu', 'nu'], ['rho', 'rho'], ['yield_strength', 'yield_Pa'], ['tensile_strength', 'tensile_Pa']], thermal: [['k', 'k'], ['rho', 'rho'], ['cp', 'cp']] };

/** Everything the job carries: what a user is told it contains is exactly this object. */
function buildRequest(kind, input, settings) {
  const S = input.setup, cond = S.conditions, warnings = [];
  const bodies = input.bodies.map((b) => {
    const sb = S.bodies.find((x) => x.name === b.name), mat = {};
    for (const [from, to] of MAT_KEYS[kind]) { const v = sb && sb.material.props[from]; if (typeof v === 'number' && Number.isFinite(v)) mat[to] = v; }
    return { name: b.name, format: 'stl', data_b64: toBase64(writeStlBinary(b.positions, b.indices)), material: mat };
  });
  const names = new Set(input.bodies.map((b) => b.name));
  const conditions = { ambient_K: cond.ambient.temperature_K, gravity: cond.ambient.gravity, self_weight: settings.self_weight === 'on', h_conv_W_m2K: settings.h_conv_W_m2K, mounts: [], loads: [], heat: [] };
  for (const m of cond.mounts) { if (!names.has(m.body)) { warnings.push(`Mount "${m.id}" names the body "${m.body}", which is not in the build; it is not sent.`); continue; } conditions.mounts.push({ id: m.id, kind: m.kind, body: m.body, face: m.face, ...(m.box ? { box: m.box } : {}), ...(m.kind === 'contact' ? { conductance_W_m2K: m.conductance_W_m2K } : {}) }); }
  for (const l of cond.loads) {
    if (l.kind === 'acceleration') { conditions.loads.push({ id: l.id, kind: 'acceleration', direction: l.direction, accel_ms2: l.accel_ms2 }); continue; }
    if (!names.has(l.body)) { warnings.push(`Load "${l.id}" names the body "${l.body}", which is not in the build; it is not sent.`); continue; }
    conditions.loads.push(l.kind === 'force' ? { id: l.id, kind: 'force', body: l.body, face: l.face, ...(l.box ? { box: l.box } : {}), vector_N: l.vector_N } : { id: l.id, kind: 'pressure', body: l.body, face: l.face, ...(l.box ? { box: l.box } : {}), pressure_Pa: l.pressure_Pa });
  }
  for (const h of cond.heat) {
    if (h.target && h.target.body != null && names.has(h.target.body)) conditions.heat.push({ id: h.id, body: h.target.body, power_W: h.power_W });
    else if (kind === 'thermal') warnings.push(`Heat source "${h.id}" is on ${h.target && h.target.pcb != null ? `PCB part "${h.target.pcb}"` : 'a body that is not in the build'}; the runner has no PCB model, so it is NOT sent and the result leaves out ${h.power_W} W. Put the power on a body to include it.`);
  }
  if (kind === 'structural' && S.pcb && (S.pcb.components || []).length) warnings.push('Parts on a PCB are not sent (no stiffness or mass is modelled for them).');
  const req = { protocol: PROTOCOL, kind, adapter: settings.adapter, label: `Forge ${kind}`, bodies, conditions, options: { mesh_size_mm: settings.mesh_size_mm, mesh_study: settings.mesh_study === 'on', keep: settings.keep === 'on' } };
  return { req, warnings };
}

// ───────────────────────── talking to the runner ─────────────────────────
const dec = new TextDecoder(), enc = new TextEncoder();
async function rcall(method, path, body) {
  const init = { method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' } };
  if (body !== undefined) init.body = JSON.stringify(body);
  let res;
  try { res = await forge.compute.remote.fetch(path, init); }
  catch (e) {
    if (e && e.code === 'E_CONSENT') throw e;
    if (e && e.code === 'E_UNAVAILABLE') throw new Error('No Alloy Runner is set: enter its address and token under Settings > Extensions > Alloy Runner.');
    throw new Error(`Cannot reach the Alloy Runner (${String((e && e.message) || e).slice(0, 160)}). Check the address, that it is running and that its CORS origins include this page.`);
  }
  const text = dec.decode(res.body); let json = null; try { json = JSON.parse(text); } catch (_) { /* a file, not JSON */ }
  if (res.status >= 400) { const m = json && json.error ? `${json.error.message} (${json.error.code})` : text.slice(0, 200); const e = new Error(`Runner: ${m}`); e.status = res.status; e.runnerCode = json && json.error && json.error.code; throw e; }
  return { status: res.status, json, bytes: res.body, text };
}
const sleep = (ms, signal) => new Promise((resolve) => { const t = setTimeout(resolve, ms); if (signal) signal.addEventListener('abort', () => { clearTimeout(t); resolve(); }, { once: true }); });

// ───────────────────────── mapping the solver's mesh onto the body surfaces ─────────────────────────
/**
 * Interpolate nodal fields of a tetrahedral mesh at surface points. The solver's mesh and the design mesh describe the same surface
 * (the runner re-meshed an STL of it), so every point lies on, or within a hair of, a tetrahedron: the tetrahedron with the largest
 * smallest barycentric coordinate near the point is used, with negative weights clamped to zero and the rest renormalised.
 * tets: Int32Array of 4 node ids per tet (the tets of ONE body); nodes: Float64Array xyz; fields: [{ data: Float64Array, ncomp }].
 * Returns one Float32Array per field (ncomp values per point).
 */
function mapToSurface(nodes, tets, points, fields) {
  const nt = tets.length / 4, np = points.length / 3;
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < tets.length; i++) for (let k = 0; k < 3; k++) { const v = nodes[3 * tets[i] + k]; if (v < lo[k]) lo[k] = v; if (v > hi[k]) hi[k] = v; }
  let edge = 0; for (let e = 0; e < nt; e++) { const a = tets[4 * e], b = tets[4 * e + 1]; edge += Math.hypot(nodes[3 * a] - nodes[3 * b], nodes[3 * a + 1] - nodes[3 * b + 1], nodes[3 * a + 2] - nodes[3 * b + 2]); }
  const cell = Math.max(1e-6, 2 * edge / Math.max(1, nt)), dims = [0, 1, 2].map((k) => Math.max(1, Math.min(128, Math.ceil((hi[k] - lo[k]) / cell) + 1))), cs = [0, 1, 2].map((k) => Math.max(cell, (hi[k] - lo[k]) / dims[k] * 1.0000001));
  const idx = (c) => c[0] + dims[0] * (c[1] + dims[1] * c[2]), at = (p) => [0, 1, 2].map((k) => Math.min(dims[k] - 1, Math.max(0, Math.floor((p[k] - lo[k]) / cs[k]))));
  const bins = new Map();
  for (let e = 0; e < nt; e++) {
    const mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
    for (let a = 0; a < 4; a++) for (let k = 0; k < 3; k++) { const v = nodes[3 * tets[4 * e + a] + k]; if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v; }
    const c0 = at(mn), c1 = at(mx);
    for (let z = c0[2]; z <= c1[2]; z++) for (let y = c0[1]; y <= c1[1]; y++) for (let x = c0[0]; x <= c1[0]; x++) { const key = idx([x, y, z]); let l = bins.get(key); if (!l) bins.set(key, l = []); l.push(e); }
  }
  const out = fields.map((f) => new Float32Array(f.ncomp * np)), w = new Float64Array(4);
  const bary = (e, p) => { // barycentric coordinates of p in tet e (Cramer), into w; returns min coordinate
    const a = tets[4 * e], b = tets[4 * e + 1], c = tets[4 * e + 2], d = tets[4 * e + 3];
    const ax = nodes[3 * a], ay = nodes[3 * a + 1], az = nodes[3 * a + 2];
    const m = [nodes[3 * b] - ax, nodes[3 * c] - ax, nodes[3 * d] - ax, nodes[3 * b + 1] - ay, nodes[3 * c + 1] - ay, nodes[3 * d + 1] - ay, nodes[3 * b + 2] - az, nodes[3 * c + 2] - az, nodes[3 * d + 2] - az];
    const r = [p[0] - ax, p[1] - ay, p[2] - az];
    const det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (Math.abs(det) < 1e-300) return -Infinity;
    const l1 = (r[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (r[1] * m[8] - m[5] * r[2]) + m[2] * (r[1] * m[7] - m[4] * r[2])) / det;
    const l2 = (m[0] * (r[1] * m[8] - m[5] * r[2]) - r[0] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * r[2] - r[1] * m[6])) / det;
    const l3 = (m[0] * (m[4] * r[2] - r[1] * m[7]) - m[1] * (m[3] * r[2] - r[1] * m[6]) + r[0] * (m[3] * m[7] - m[4] * m[6])) / det;
    w[0] = 1 - l1 - l2 - l3; w[1] = l1; w[2] = l2; w[3] = l3;
    return Math.min(w[0], w[1], w[2], w[3]);
  };
  const p = [0, 0, 0];
  for (let q = 0; q < np; q++) {
    p[0] = points[3 * q]; p[1] = points[3 * q + 1]; p[2] = points[3 * q + 2];
    const c = at(p); let best = -1, bestMin = -Infinity;
    for (let dz = -1; dz <= 1 && bestMin < -1e-9; dz++) for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const cc = [c[0] + dx, c[1] + dy, c[2] + dz]; if (cc.some((v, k) => v < 0 || v >= dims[k])) continue;
      const l = bins.get(idx(cc)); if (!l) continue;
      for (const e of l) { const m = bary(e, p); if (m > bestMin) { bestMin = m; best = e; } }
    }
    if (best < 0) continue; // no tetrahedron near this point: its values stay 0 (cannot happen on a body the mesh was made from)
    bary(best, p); let s = 0; for (let a = 0; a < 4; a++) { if (w[a] < 0) w[a] = 0; s += w[a]; } for (let a = 0; a < 4; a++) w[a] /= s || 1;
    fields.forEach((f, fi) => { for (let k = 0; k < f.ncomp; k++) { let v = 0; for (let a = 0; a < 4; a++) v += w[a] * f.data[f.ncomp * tets[4 * best + a] + k]; out[fi][f.ncomp * q + k] = v; } });
  }
  return out;
}

/** Split a parsed result VTU into the tets of body `bi` and map the requested point fields onto `points`. */
function bodySurfaceFields(vtu, bi, points, names) {
  const body = vtu.cellData.body && vtu.cellData.body.data, types = vtu.cells.types, conn = vtu.cells.connectivity, off = vtu.cells.offsets, tets = [];
  for (let e = 0; e < types.length; e++) {
    if (types[e] !== 10) continue; if (body && body[e] !== bi) continue;
    const s = e === 0 ? 0 : off[e - 1]; tets.push(conn[s], conn[s + 1], conn[s + 2], conn[s + 3]);
  }
  if (!tets.length) throw new Error(`the runner's result has no tetrahedra for body ${bi}`);
  const fields = names.map((n) => { const f = vtu.pointData[n]; if (!f) throw new Error(`the runner's result has no field "${n}" (fields: ${Object.keys(vtu.pointData).join(', ')})`); return f; });
  return mapToSurface(vtu.points, Int32Array.from(tets), points, fields);
}

// ───────────────────────── validity ─────────────────────────
/**
 * The grade of a runner result. 'validated' needs all of: a real solver ran (not the simulated one), the two-mesh study ran and every
 * metric changed by no more than its tolerance, and the OPERATOR of the runner has attested that it reproduced the reference cases
 * (a qualification file on the runner, shown in the result). Without the file, or without the study, the grade is 'estimate'. A simulated
 * result is 'experimental' and never decides a check.
 */
function validityOf(res) {
  if (res.simulated || res.solver_real === false) return { validity: 'experimental', why: 'the runner used its simulated solver (protocol test only)' };
  const q = res.qualification;
  if (!res.study) return { validity: 'estimate', why: 'no mesh study was run (set Mesh study to on to compare two mesh sizes)' };
  if (!res.study.converged) return { validity: 'estimate', why: `the mesh study did not converge (${res.study.metrics.filter((m) => m.change > (res.study.tolerance[m.metric] ?? res.study.tolerance.default)).map((m) => `${m.metric} changed ${(100 * m.change).toFixed(1)} %`).join(', ')})` };
  if (!q || q.passed !== true) return { validity: 'estimate', why: 'the mesh study converged, but this runner has no qualification file stating that it reproduced the reference cases' };
  return { validity: 'validated', why: `real solver, mesh study converged (${res.study.metrics.map((m) => `${m.metric} ${(100 * m.change).toFixed(2)} %`).join(', ')}) and the runner's operator attests the reference cases (${q.source})` };
}

// ───────────────────────── the analysis ─────────────────────────
async function runRunner(kind, input, ctx) {
  const { settings, warnings: sw } = parseSettings(await loadSettings());
  if (sw.length) throw new Error(sw.join(' '));
  if (!input.bodies.length) throw new Error('there is no built body to analyse');
  ctx.progress(0.01);
  const health = (await rcall('GET', '/v1/health')).json;
  if (!health || health.name !== 'alloy-runner') throw new Error('The address you set does not answer like an Alloy Runner (GET /v1/health).');
  if (!health.authenticated) throw new Error('The runner does not accept the token you entered (Settings > Extensions > Alloy Runner). Nothing was sent.');
  if (health.protocol !== PROTOCOL) throw new Error(`The runner speaks protocol ${health.protocol}; this package speaks ${PROTOCOL}. Update the runner or the package.`);
  const { req, warnings: rw } = buildRequest(kind, input, settings);
  if (ctx.signal.aborted) throw new Error('cancelled');
  ctx.progress(0.03);
  const sub = await rcall('POST', '/v1/jobs', req); // the only call that carries geometry; the host asks the user first
  const id = sub.json && sub.json.id; if (!id) throw new Error('the runner did not return a job id');
  console.info(`runner job ${id} submitted (${kind}, ${req.bodies.length} bod${req.bodies.length === 1 ? 'y' : 'ies'})`);
  ctx.progress(0.05);
  const t0 = Date.now(); let since = 0, job = null, cancelled = false;
  const stop = async () => { try { await rcall('POST', `/v1/jobs/${id}/cancel`); } catch (_) { /* the job may be gone already */ } };
  try {
    for (;;) {
      if (ctx.signal.aborted && !cancelled) { cancelled = true; await stop(); }
      if ((Date.now() - t0) / 1000 > settings.timeout_s && !cancelled) { cancelled = true; await stop(); }
      job = (await rcall('GET', `/v1/jobs/${id}?since=${since}`)).json; since = job.next;
      for (const e of job.events) console.info(`runner: ${e.msg}`);
      ctx.progress(0.05 + 0.85 * Math.min(1, job.progress || 0));
      if (job.state === 'done' || job.state === 'failed' || job.state === 'cancelled') break;
      await sleep(settings.poll_ms, ctx.signal.aborted ? null : ctx.signal);
    }
  } catch (e) { if (!cancelled) await stop(); throw e; }
  if (job.state === 'cancelled') { await rcall('DELETE', `/v1/jobs/${id}`).catch(() => {}); throw new Error(job.error && job.error.code === 'E_TIMEOUT' ? job.error.message : 'cancelled'); }
  if (job.state === 'failed') { await rcall('DELETE', `/v1/jobs/${id}`).catch(() => {}); throw new Error(`The runner could not solve this: ${job.error ? job.error.message : 'unknown error'}`); }
  const res = (await rcall('GET', `/v1/jobs/${id}/result`)).json;
  ctx.progress(0.92);
  const vtuBytes = (await rcall('GET', `/v1/jobs/${id}/files/result.vtu`)).bytes; // the last download: the runner deletes the job unless it was kept
  ctx.progress(0.95);
  const vtu = parseVtu(vtuBytes), bodyIndex = new Map(res.bodies.map((n, i) => [n, i])), fields = [];
  const spec = kind === 'structural'
    ? [['von_mises', 'runner_von_mises', 'MPa'], ['displacement_magnitude', 'runner_displacement', 'mm'], ['safety_factor', 'runner_safety_factor', '']]
    : [['temperature', 'runner_temperature', '°C']];
  for (const b of input.bodies) {
    const bi = bodyIndex.get(b.name); if (bi === undefined) continue;
    const arrs = bodySurfaceFields(vtu, bi, b.positions, spec.map((s) => s[0]));
    spec.forEach((s, i) => { let ok = true; for (let q = 0; q < arrs[i].length; q++) if (!Number.isFinite(arrs[i][q])) { ok = false; break; } if (!ok) arrs[i].fill(0); fields.push({ name: `${s[1]}:${b.name}`, unit: s[2], kind: 'surface', body: b.name, data: arrs[i] }); });
  }
  const { validity, why } = validityOf(res), summary = res.summary.map((s) => ({ ...s }));
  if (res.study) for (const m of res.study.metrics) summary.push({ metric: `mesh_change_${m.metric}`.slice(0, 60), value: 100 * m.change, unit: '%' });
  summary.push({ metric: 'solve_seconds', value: res.seconds, unit: 's' });
  const warns = [...rw, ...res.warnings, `Solver: ${res.adapter}${res.solver_real ? '' : ' (SIMULATED, solves no equation)'}; mesh ${res.summary.find((s) => s.metric === 'mesh_elements')?.value ?? '?'} tetrahedra at ${res.summary.find((s) => s.metric === 'mesh_size')?.value ?? '?'} mm.`, `Validity ${validity}: ${why}.`,
    'Fields are the solver\'s tetrahedral solution interpolated onto the surface of each body. Stress peaks at sharp corners depend on the mesh; use the mesh study to see how much.'];
  if (res.study) warns.push(`Mesh study at ${res.study.sizes_mm[0]} and ${res.study.sizes_mm[1]} mm: ${res.study.metrics.map((m) => `${m.metric} ${(100 * m.change).toFixed(2)} %`).join(', ')}.`);
  ctx.progress(1);
  return { summary, fields, warnings: warns, validity };
}

async function loadSettings() { try { const v = await forge.storage.project.get('settings'); return v && typeof v === 'object' ? v : {}; } catch (_) { return {}; } }

if (typeof forge !== 'undefined') {
  for (const [id, kind] of Object.entries(KINDS)) forge.analysis.register(id, { run: (input, ctx) => runRunner(kind, input, ctx) });
  forge.agent.tool('set-runner', async (args) => {
    const cur = await loadSettings(), next = { ...cur, ...(args && typeof args === 'object' ? args : {}) };
    const { settings, warnings } = parseSettings(next);
    if (warnings.length) throw new Error(warnings.join(' '));
    await forge.storage.project.set('settings', next);
    return { saved: next, effective: settings, note: 'Settings only. Running a runner analysis sends the design to your runner and needs the user to confirm in the Analyze pane; no tool can do that.' };
  });
  forge.agent.tool('runner-status', async () => {
    let h; try { h = (await rcall('GET', '/v1/health')).json; } catch (e) { return { reachable: false, message: String(e.message || e) }; }
    return { reachable: true, name: h.name, version: h.version, protocol: h.protocol, authenticated: !!h.authenticated, adapters: h.adapters ? Object.fromEntries(Object.entries(h.adapters).map(([k, v]) => [k, { ok: v.ok, real: v.real, reason: v.reason || null }])) : null, note: 'Reads the runner health only. No geometry is sent.' };
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { parseSettings, buildRequest, toBase64, mapToSurface, bodySurfaceFields, validityOf, runRunner, DEFAULT_SETTINGS });
