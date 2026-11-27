// Produce a stored reference file (analysis-validation/reference/*.json) from a REAL Alloy Runner solve, as docs/EXTENSIONS_SPEC.md
// section 3.5 describes ("reference results from Elmer are stored as data files, produced once by the runner"):
//
//   node tools/make-reference.mjs --case structural/bracket-vs-elmer --runner http://127.0.0.1:8787 --token <token> [--mesh-mm 1.5] [--out file]
//
// It builds the case's design with the Forge kernel, sends it to the runner (this command IS the explicit user action: you named the
// runner), and writes the file ONLY IF the solve was done by a real solver (adapter "elmer", never the simulated one), a two-mesh study
// ran and every study metric converged. A simulated, unconverged or failed result is refused and nothing is written, so a reference can
// never come out of the protocol-test solver. Today it supports the structural bracket case; the enclosure case needs PCB heat
// sources, which the runner protocol does not carry yet (docs/EXTENSIONS_SPEC.md, A4 done).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Module from 'manifold-3d';
import { build } from '../src/kernel.js';
import { prepareInput } from '../src/analysis/setup.js';
import { projectFor, loadCaseFiles } from '../analysis-validation/harness.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SUPPORTED = { 'structural/bracket-vs-elmer': { kind: 'structural', map: { max_displacement_mm: ['max_displacement', 1], stiffness_N_per_mm: ['stiffness', 1], max_von_mises_MPa: ['max_von_mises', 1] } } };

/** The reference file contents for a finished job result, or throws why it must not be made. */
export function referenceFromResult(res, spec, { runnerVersion = '?', caseId, produced = new Date().toISOString().slice(0, 10) } = {}) {
  if (!res || res.solver_real !== true || res.simulated || res.adapter !== 'elmer') throw new Error(`refused: the result is not from a real Elmer solve (adapter "${res && res.adapter}", simulated ${!!(res && res.simulated)}). A reference file is never made from the simulated solver.`);
  if (!res.study) throw new Error('refused: no mesh study ran (use a runner job with mesh_study on); a reference needs to say how mesh-dependent it is');
  if (!res.study.converged) throw new Error(`refused: the mesh study did not converge (${res.study.metrics.map((m) => `${m.metric} ${(100 * m.change).toFixed(2)} %`).join(', ')}); refine the mesh (--mesh-mm) until it does`);
  const values = {}, get = (m) => (res.summary.find((s) => s.metric === m) || {}).value;
  for (const [key, [metric, mul]] of Object.entries(spec.map)) { const v = get(metric); if (!Number.isFinite(v)) throw new Error(`refused: the result has no metric ${metric}`); values[key] = v * mul; }
  return { source: { tool: 'Elmer FEM', runner: `Alloy Runner ${runnerVersion}`, solver: res.info && res.info.solver || 'elmer', case: caseId, produced, mesh: `tetrahedral, ${get('mesh_elements')} elements, element size ${get('mesh_size')} mm`, study: { sizes_mm: res.study.sizes_mm, changes: Object.fromEntries(res.study.metrics.map((m) => [m.metric, m.change])), tolerance: res.study.tolerance }, qualification: res.qualification || null, note: 'Produced by tools/make-reference.mjs from a runner whose Elmer install the operator vouches for; re-run it after changing the runner, Elmer or the mesh settings.' }, values };
}

async function main() {
  const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
  const caseRef = arg('--case'), url = (arg('--runner') || '').replace(/\/+$/, ''), token = arg('--token', process.env.ALLOY_RUNNER_TOKEN || '');
  if (!caseRef || !url || !SUPPORTED[caseRef]) { console.error(`usage: node tools/make-reference.mjs --case <${Object.keys(SUPPORTED).join('|')}> --runner <url> --token <token> [--mesh-mm n] [--out file]`); process.exit(2); }
  const [file, id] = caseRef.split('/'), cf = loadCaseFiles(file).find((f) => f.name === `${file}.json`), c = cf && cf.data.cases.find((x) => x.id === id);
  if (!c) { console.error('no such case'); process.exit(2); }
  const out = path.resolve(ROOT, arg('--out', c.referenceFile));
  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, j = async (m, p, b) => { const r = await fetch(url + p, { method: m, headers: H, body: b ? JSON.stringify(b) : undefined }); const t = await r.text(); if (!r.ok) throw new Error(`${m} ${p}: ${r.status} ${t.slice(0, 200)}`); return JSON.parse(t); };
  const health = await j('GET', '/v1/health'); if (!health.adapters || !health.adapters.elmer || !health.adapters.elmer.ok) { console.error(`refused: this runner has no working Elmer (${health.adapters && health.adapters.elmer ? health.adapters.elmer.reason : 'not authenticated'}); nothing was sent`); process.exit(1); }
  const wasm = await Module(); wasm.setup(); const P = projectFor(c), B = build(P, wasm), core = {};
  new Function('__core', fs.readFileSync(path.join(ROOT, 'examples/ext/runner/main.js'), 'utf8'))(core);
  const settings = { ...core.DEFAULT_SETTINGS, adapter: 'elmer', mesh_study: 'on', mesh_size_mm: +arg('--mesh-mm', 0) };
  const { req } = core.buildRequest(SUPPORTED[caseRef].kind, prepareInput(P, B), settings); B.dispose();
  console.log(`sending case ${caseRef} (${req.bodies.length} body, ${req.bodies.map((b) => b.data_b64.length).join('+')} base64 bytes) to ${url}`);
  const { id: jobId } = await j('POST', '/v1/jobs', req); let job;
  for (;;) { job = await j('GET', `/v1/jobs/${jobId}`); process.stdout.write(`\r${job.state} ${job.stage} ${Math.round(job.progress * 100)} %   `); if (['done', 'failed', 'cancelled'].includes(job.state)) break; await new Promise((r) => setTimeout(r, 1000)); }
  console.log(); if (job.state !== 'done') { console.error(`refused: the job ${job.state}: ${job.error ? job.error.message : ''}`); process.exit(1); }
  const res = await j('GET', `/v1/jobs/${jobId}/result`); await fetch(`${url}/v1/jobs/${jobId}/files/result.vtu`, { headers: H }); // download the rest so the runner deletes the job
  let ref; try { ref = referenceFromResult(res, SUPPORTED[caseRef], { runnerVersion: health.version, caseId: caseRef }); } catch (e) { console.error(e.message); process.exit(1); }
  fs.mkdirSync(path.dirname(out), { recursive: true }); fs.writeFileSync(out, JSON.stringify(ref, null, 2) + '\n'); console.log('wrote', path.relative(process.cwd(), out), JSON.stringify(ref.values));
}
if (import.meta.url === `file://${process.argv[1]}`) main().catch((e) => { console.error(e.message || e); process.exit(1); });
