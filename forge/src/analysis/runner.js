// Runs analyses declared by packages (contributes.analyses) on the current design and turns their declared checks into scorecard
// rows. The solver code lives in the package's Worker; this file owns: the input, the cache, cancellation, validation of what comes
// back, and the rule that decides whether a check row can fail the design.
//
//   validity 'experimental'  -> the row is advisory: shown, never gating            (spec 3.3)
//   estimate / validated     -> the row is hard: it gates the verdict and the loop
//   not run / over budget    -> advisory, and it says so: a missing result is never a silent pass
import { enabledIn } from '../ext/registry.js';
import { ExtError } from '../ext/host.js';
import { prepareInput } from './setup.js';
import { inputsHash, finalizeResult, validateResult, summaryOf, compare } from './result.js';
import { flagRows } from './risk.js';
import { sampleVoxel } from '../ext/overlay.js';

/** Mean of the finite cells within one voxel of a point (a bolt hole is air in the grid, so the cell itself may hold no value). null when there is none. */
function probeVoxel(field, [x, y, z]) {
  const h = field.grid.h; let sum = 0, n = 0;
  for (const dx of [-1, 0, 1]) for (const dy of [-1, 0, 1]) for (const dz of [-1, 0, 1]) { const v = sampleVoxel(field, x + dx * h, y + dy * h, z + dz * h); if (Number.isFinite(v)) { sum += v; n++; } }
  return n ? sum / n : null;
}

export function createAnalysisRunner({ registry, host, cache, defaultTimeoutMs = 10 * 60 * 1000 }) {
  const last = new Map(); // `${ext}/${analysis}` -> most recent result in this session

  const declared = (P) => registry.contributions('analyses').filter((c) => enabledIn(P, c.ext));
  const find = (P, ext, id) => declared(P).find((c) => c.ext === ext && c.item.id === id);

  // An analysis can name results it builds on (contributes.analyses[].upstream: ["<ext>/<analysis>"]). When one of those has been run
  // on this very design (its result is in the cache), the host hands over its summary rows and, for every joint of the project, the
  // value of its voxel fields at the joint. Nothing is run on the way: a missing result is simply absent, and the package says which
  // source it used. The probes enter the inputs hash, so a new upstream result means a new answer.
  async function upstreamOf(P, build, item) {
    const out = {};
    for (const id of item.upstream || []) {
      const [uext, uid] = id.split('/'), hit = find(P, uext, uid);
      if (!hit) continue;
      const { hash } = await prepare(P, build, uext, uid, { noUpstream: true });
      const res = await cache.get(hash);
      if (!res) continue;
      const probes = [];
      for (const j of P.joints || []) for (const f of res.fields) if (f.kind === 'voxel' && f.grid) { const v = probeVoxel({ data: f.data, grid: f.grid }, j.where); if (v !== null) probes.push({ joint: j.id, field: f.name, value: +v.toFixed(3), unit: f.unit }); }
      out[id] = { validity: res.validity, summary: res.summary.map((r) => ({ metric: r.metric, value: r.value, unit: r.unit })), probes };
    }
    return out;
  }

  async function prepare(P, build, ext, analysisId, { noUpstream = false } = {}) {
    const pkg = registry.get(ext);
    const input = prepareInput(P, build);
    const item = (((pkg.manifest.contributes || {}).analyses) || []).find((a) => a.id === analysisId) || {};
    if (!noUpstream && (item.upstream || []).length) input.upstream = await upstreamOf(P, build, item);
    const hash = await inputsHash({ ext, version: pkg.manifest.version, analysis: analysisId, setup: { ...input.setup, warnings: undefined, conditions: { ...input.setup.conditions, limits: undefined }, upstream: input.upstream || null }, bodies: input.bodies, extData: (P && P.ext && P.ext[ext]) || null });
    return { pkg, input, hash };
  }

  /**
   * Run one analysis. Rejects with E_CANCELLED when `signal` aborts (the package is told through its own signal) and with the
   * package's error otherwise. `onProgress(0..1)` is called as the package reports it.
   */
  async function run(P, build, ext, analysisId, { signal, onProgress, timeoutMs = defaultTimeoutMs, force = false } = {}) {
    const hit = find(P, ext, analysisId);
    if (!hit) throw new ExtError('E_UNAVAILABLE', `analysis "${analysisId}" from "${ext}" is not installed or is switched off in this project`);
    if (!build || !(build.bodies || []).length) throw new ExtError('E_UNAVAILABLE', 'there is no built body to analyse yet');
    const { pkg, input, hash } = await prepare(P, build, ext, analysisId);
    const key = `${ext}/${analysisId}`;
    if (!force) { const c = await cache.get(hash); if (c) { last.set(key, c); return { ...c, cached: true, seconds: 0 }; } }
    const t0 = Date.now();
    const raw = await host.invoke(pkg, 'analysis', analysisId, input, { signal, onProgress, timeoutMs });
    const res = finalizeResult(raw, { id: key, version: pkg.manifest.version, inputsHash: hash, warnings: input.setup.warnings });
    validateResult(res, Object.fromEntries(input.bodies.map((b) => [b.name, b.positions.length / 3])));
    await cache.put(hash, res); last.set(key, res);
    return { ...res, cached: false, seconds: (Date.now() - t0) / 1000 };
  }

  /**
   * Check rows for every declared analysis check in the project. With `run: true` missing results are computed, spending
   * `budget.left` seconds (a shared { left } object; the design loop passes its analysisSeconds cap this way).
   */
  async function rows(P, build, { run: doRun = false, signal, budget } = {}) {
    const out = [];
    const limits = (P.analysis && P.analysis.conditions && P.analysis.conditions.limits) || {};
    for (const { ext, item } of declared(P)) {
      const checks = item.checks || [];
      if (!checks.length && !item.flags) continue;
      const row = (c, o) => ({ id: `${ext}:${item.id}:${c.id}`, label: c.label, ext, analysis: item.id, items: [], ...o });
      let res = null, problem = null, skipped = null;
      try {
        if (!build || !(build.bodies || []).length) throw new Error('no built body');
        const { hash } = await prepare(P, build, ext, item.id);
        res = await cache.get(hash);
        if (!res && doRun) {
          if (budget && budget.left <= 0) skipped = `skipped: the analysis time budget is used up`;
          else { const r = await run(P, build, ext, item.id, { signal, timeoutMs: budget ? Math.max(1000, budget.left * 1000) : undefined }); if (budget) budget.left -= r.seconds || 0; res = r; }
        }
      } catch (e) { problem = String(e.message || e); }
      if (item.flags) {
        // risk flags join the scorecard (spec 4A): high flags gate unless their confidence is low; experimental never gates; no result is an advisory "not run"
        const label = item.label;
        if (problem) out.push(row({ id: 'risk', label: `${label}: risk flags` }, { id: `${ext}:${item.id}:risk`, severity: (item.validity || 'estimate') === 'experimental' ? 'advisory' : 'hard', pass: false, detail: `analysis could not run: ${problem}`, validity: item.validity || 'estimate' }));
        else out.push(...flagRows(res, { ext, analysis: item.id, label }));
      }
      for (const c of checks) {
        const key = `${ext}:${item.id}:${c.id}`, limit = limits[key] ?? c.limit;
        if (problem) { out.push(row(c, { severity: (item.validity || 'estimate') === 'experimental' ? 'advisory' : 'hard', pass: false, detail: `analysis could not run: ${problem}`, validity: item.validity || 'estimate' })); continue; }
        if (!res) { out.push(row(c, { severity: 'advisory', pass: false, detail: skipped || 'not run yet (Analyze > Run)', validity: item.validity || 'estimate' })); continue; }
        const m = res.summary.find((s) => s.metric === c.metric);
        if (!m) { out.push(row(c, { severity: 'hard', pass: false, detail: `the result has no metric "${c.metric}"`, validity: res.validity })); continue; }
        const pass = compare(m.value, c.op, limit);
        out.push(row(c, { severity: res.validity === 'experimental' ? 'advisory' : 'hard', pass, validity: res.validity,
          detail: `${c.metric} ${+m.value.toPrecision(4)} ${m.unit} ${c.op} ${limit} ${c.unit || m.unit} · ${res.validity}${res.validity === 'experimental' ? ' (does not decide the verdict)' : ''}` }));
      }
    }
    return out;
  }

  /** Summary rows of the results that match the current design, for the critic and the scorecard. Never the fields. */
  async function summaries(P, build) {
    const out = [];
    for (const { ext, item } of declared(P)) {
      try { const { hash } = await prepare(P, build, ext, item.id); const r = await cache.get(hash); if (r) out.push(summaryOf(r)); } catch (_) { /* no build yet */ }
    }
    return out;
  }

  return { run, rows, summaries, declared, lastResult: (ext, id) => last.get(`${ext}/${id}`) || null };
}
