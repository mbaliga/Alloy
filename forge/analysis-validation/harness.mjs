// Validation harness for analyses (docs/EXTENSIONS_SPEC.md section 3.5).
//   node analysis-validation/harness.mjs [--json out.json] [--md out.md] [--only <case-file-or-case-id>]
//   npm run test:analysis
// A case file names an analysis (and the package folder that provides it), then lists cases: a design (features and materials, built
// through typed ops and the real kernel), and the expected values with an explicit tolerance and the reference they come from.
// Every case runs through the real runner and a real package Worker. A case that misses its tolerance FAILS the run (exit 1); the
// tolerance is never adjusted here. An analysis that returns validity "validated" must pass every one of its cases, or the run fails.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';
import Module from 'manifold-3d';
import { build } from '../src/kernel.js';
import { emptyProject, applyOps, migrate } from '../src/model.js';
import { ExtRegistry, memoryStore } from '../src/ext/registry.js';
import { ExtHost } from '../src/ext/host.js';
import { ExtApi } from '../src/ext/api.js';
import { createAnalysisRunner } from '../src/analysis/runner.js';
import { prepareInput } from '../src/analysis/setup.js';
import { createResultCache, memoryStore as memResults } from '../src/analysis/cache.js';
import { packDir } from '../tools/pack-ext.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..');

const nodeWorker = (script) => { const w = new Worker(script, { eval: true }); return { post: (m) => w.postMessage(m), onMessage: (f) => w.on('message', f), onError: (f) => w.on('error', f), terminate: () => w.terminate() }; };

/** Compare one computed value with its expectation. Exactly one of tol.rel / tol.abs. */
export function compareValue(got, want, tol) {
  const keys = Object.keys(tol || {});
  if (keys.length !== 1 || !['rel', 'abs'].includes(keys[0]) || !(tol[keys[0]] >= 0)) throw new Error('a tolerance must be exactly one of { rel } or { abs }, a non-negative number');
  const err = Math.abs(got - want);
  if (tol.rel != null) return { error: want === 0 ? err : err / Math.abs(want), limit: tol.rel, pass: err <= tol.rel * Math.abs(want), mode: 'rel' };
  return { error: err, limit: tol.abs, pass: err <= tol.abs, mode: 'abs' };
}

export function lintCaseFile(file) {
  const problems = [];
  if (!file.analysis || !file.package) problems.push('needs "analysis" and "package"');
  const ids = new Set();
  for (const c of file.cases || []) {
    if (ids.has(c.id)) problems.push(`duplicate case id ${c.id}`); ids.add(c.id);
    if (!c.reference) problems.push(`${c.id}: every case states its reference (analytic derivation or source)`);
    if (c.kind === 'function' || c.kind === 'core') {
      if (c.kind === 'core' && !(c.setup && (c.setup.features || []).length)) problems.push(`${c.id}: a core case needs a setup with features`);
      if (!(c.calls || []).length) problems.push(`${c.id}: no calls`);
      for (const k of c.calls || []) { if (!k.fn) problems.push(`${c.id}: a call needs "fn"`); try { compareValue(0, 0, k.tol); } catch (er) { problems.push(`${c.id}/${k.fn}: ${er.message}`); } }
      continue;
    }
    if (!(c.expect || []).length) problems.push(`${c.id}: no expectations`);
    for (const e of c.expect || []) { try { compareValue(0, 0, e.tol); } catch (er) { problems.push(`${c.id}/${e.metric}: ${er.message}`); } }
  }
  return problems;
}

function projectFor(c) {
  const ops = (c.setup.features || []).map((f) => ({ op: 'add_feature', feature: f }));
  let P = applyOps(migrate(emptyProject()), ops).project;
  const mats = Object.entries(c.setup.materials || {}).map(([body, material]) => ({ op: 'set_body_material', body, material }));
  if (mats.length) P = applyOps(P, mats).project;
  if (c.setup.pcb) { P.pcb = { ...P.pcb, ...c.setup.pcb }; }
  const cops = [];
  for (const m of c.setup.customMaterials || []) cops.push({ op: 'set_material', material: m });
  for (const [body, material] of Object.entries(c.setup.customAssign || {})) cops.push({ op: 'set_body_material', body, material });
  const cn = c.setup.conditions || {};
  if (cn.ambient) cops.push({ op: 'set_ambient', ambient: cn.ambient });
  for (const list of ['heat', 'mounts', 'loads']) for (const item of cn[list] || []) cops.push({ op: 'set_condition', list, item });
  if (cops.length) P = applyOps(P, cops).project;
  if (c.setup.ext) P.ext = { ...(P.ext || {}), ...c.setup.ext };
  return P;
}

export async function runValidation({ caseFiles, wasm, log = () => {} }) {
  wasm = wasm || await Module(); if (!wasm.__ready) { wasm.setup(); wasm.__ready = true; }
  const rows = [], summary = [];
  const registry = new ExtRegistry({ store: memoryStore() });
  let current = { P: null, build: null };
  const api = new ExtApi({ getProject: () => current.P, getBuild: () => current.build, isEnabled: () => true });
  const host = new ExtHost({ createWorker: nodeWorker, api });
  const runner = createAnalysisRunner({ registry, host, cache: createResultCache({ store: memResults() }) });
  try {
    for (const file of caseFiles) {
      const problems = lintCaseFile(file.data);
      if (problems.length) { rows.push({ file: file.name, case: '(file)', metric: '-', reference: '', computed: '-', error: '-', tolerance: '-', pass: false, note: problems.join('; ') }); continue; }
      const [ext, analysisId] = file.data.analysis.split('/');
      const pkgPath = path.resolve(ROOT, file.data.package);
      if (!registry.get(ext)) await registry.install(await packDir(pkgPath));
      const stats = { file: file.name, analysis: file.data.analysis, cases: 0, passed: 0, validity: new Set() };
      for (const c of file.data.cases) {
        stats.cases++; let casePass = true;
        const fail = (metric, note, extra = {}) => { casePass = false; rows.push({ file: file.name, case: c.id, metric, reference: c.reference, computed: '-', error: '-', tolerance: '-', pass: false, note, ...extra }); };
        if (c.kind === 'function' || c.kind === 'core') {
          let built = null;
          try {
            const core = {}; const src = fs.readFileSync(path.join(pkgPath, 'main.js'), 'utf8').replace(/^export /gm, '');
            new Function('__core', src)(core);
            let input = null;
            if (c.kind === 'core') { const P = projectFor(c); P.extensions = [{ id: ext, name: registry.get(ext).manifest.name, version: registry.get(ext).manifest.version, enabled: true }]; built = build(P, wasm); input = prepareInput(P, built); }
            for (const k of c.calls) {
              const args = (k.args || []).map((a) => (a === '$input' ? input : a));
              const [fnName, ...sel] = k.fn.split('.'); let got = core[fnName](...args); for (const key of sel) got = got[key]; got *= k.mul ?? 1; const r = compareValue(got, k.value, k.tol), pass = r.pass && Number.isFinite(got);
              if (!pass) casePass = false;
              rows.push({ file: file.name, case: c.id, metric: k.metric || k.fn, reference: c.reference, unit: k.unit || '', expected: k.value, computed: got, error: r.error, tolerance: r.mode === 'rel' ? `${(r.limit * 100).toPrecision(3)} %` : `${r.limit} ${k.unit || ''}`, errorText: r.mode === 'rel' ? `${(r.error * 100).toPrecision(3)} %` : `${r.error.toPrecision(3)} ${k.unit || ''}`, pass });
            }
          } catch (e) { fail('(run)', String(e.message || e)); } finally { if (built) built.dispose(); }
          if (casePass) stats.passed++;
          continue;
        }
        try {
          const P = projectFor(c);
          P.extensions = [{ id: ext, name: registry.get(ext).manifest.name, version: registry.get(ext).manifest.version, enabled: true }];
          const built = build(P, wasm); current = { P, build: built };
          let res;
          try { res = await runner.run(P, built, ext, analysisId, { force: true, timeoutMs: 120000 }); } finally { built.dispose(); }
          stats.validity.add(res.validity);
          if (c.expectValidity && res.validity !== c.expectValidity) fail('validity', `expected validity "${c.expectValidity}", got "${res.validity}"`);
          for (const e of c.expect) {
            const m = res.summary.find((s) => s.metric === e.metric);
            if (!m) { fail(e.metric, 'the result has no such metric'); continue; }
            const pairs = e.where ? e.where.map((w, i) => [`${e.metric}.${'xyz'[i]}`, m.where ? m.where[i] : NaN, w]) : [[e.metric, m.value, e.value]];
            for (const [name, got, want] of pairs) {
              const r = compareValue(got, want, e.tol), pass = r.pass && Number.isFinite(got);
              if (!pass) casePass = false;
              rows.push({ file: file.name, case: c.id, metric: name, reference: c.reference, unit: e.unit || m.unit, expected: want, computed: got, error: r.error, tolerance: r.mode === 'rel' ? `${(r.limit * 100).toPrecision(3)} %` : `${r.limit} ${e.unit || m.unit}`, errorText: r.mode === 'rel' ? `${(r.error * 100).toPrecision(3)} %` : `${r.error.toPrecision(3)} ${e.unit || m.unit}`, pass });
            }
          }
          for (const re of c.expectWarnings || []) if (!res.warnings.some((w) => new RegExp(re).test(w))) fail('warning', `expected a warning matching /${re}/; got ${JSON.stringify(res.warnings)}`);
        } catch (e) { fail('(run)', String(e.message || e)); }
        if (casePass) stats.passed++;
      }
      stats.validity = [...stats.validity];
      summary.push(stats);
    }
  } finally { host.stopAll(); }
  // an analysis may only claim "validated" if every one of its cases passes
  for (const s of summary) if (s.validity.includes('validated') && s.passed !== s.cases) rows.push({ file: s.file, case: '(rule)', metric: 'validity', reference: 'docs/EXTENSIONS_SPEC.md 3.3', computed: '-', error: '-', tolerance: '-', pass: false, note: `${s.analysis} returns "validated" but ${s.cases - s.passed} of ${s.cases} cases do not pass` });
  return { rows, summary, pass: rows.every((r) => r.pass) };
}

const f = (v) => (typeof v === 'number' ? +v.toPrecision(6) : v);
export function renderTable({ rows, summary }) {
  const lines = ['| Case | Metric | Reference | Expected | Computed | Error | Tolerance | Result |', '|---|---|---|---|---|---|---|---|'];
  for (const r of rows) lines.push(`| ${r.case} | ${r.metric} | ${(r.reference || '').replace(/\|/g, '/').slice(0, 70)} | ${r.expected == null ? '' : `${f(r.expected)} ${r.unit || ''}`} | ${r.computed === '-' ? '-' : `${f(r.computed)} ${r.unit || ''}`} | ${r.errorText || r.error} | ${r.tolerance} | ${r.pass ? 'PASS' : 'FAIL' + (r.note ? ': ' + r.note : '')} |`);
  lines.push('', ...summary.map((s) => `- ${s.analysis}: ${s.passed} of ${s.cases} cases pass; result validity ${s.validity.join(', ')}`));
  const bad = rows.filter((r) => !r.pass).length;
  lines.push('', `**${rows.length - bad} of ${rows.length} checks pass${bad ? `, ${bad} FAIL` : ''}.**`);
  return lines.join('\n');
}

export function loadCaseFiles(only) {
  const dir = path.join(HERE, 'cases');
  return fs.readdirSync(dir).filter((n) => n.endsWith('.json') && (!only || n.includes(only))).sort().map((n) => ({ name: n, data: JSON.parse(fs.readFileSync(path.join(dir, n), 'utf8')) }));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const arg = (k) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };
  const caseFiles = loadCaseFiles(arg('--only'));
  if (!caseFiles.length) { console.error('no case files found'); process.exit(2); }
  const out = await runValidation({ caseFiles });
  const md = renderTable(out);
  console.log(md);
  if (arg('--md')) fs.writeFileSync(arg('--md'), md + '\n');
  if (arg('--json')) fs.writeFileSync(arg('--json'), JSON.stringify(out, null, 1));
  process.exit(out.pass ? 0 : 1);
}
