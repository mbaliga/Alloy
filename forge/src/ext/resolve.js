// Where packages meet the document and the build.
//
//  prepare(P)      Replace every package feature with the built-in features its package expands it into. The result is a
//                  derived project for the kernel; the saved document is never touched. A feature whose package is missing,
//                  disabled or failing is skipped with a visible warning and stays in the document exactly as it was.
//  checkRows(...)  Package checks as scorecard rows, plus "needs <package>" rows for checks the project used that cannot run.
//
// A package returns feature specs; it never returns geometry. The specs go through the same normalisation as any other
// feature (typed fields, expression syntax), so the kernel only ever builds built-in primitives.
import { TYPES, typeDef, isExtType, defaultFeature, normalizeFeatureFields, fieldsFor } from '../model.js';
import { makeScope } from '../expr.js';
import { enabledIn, projectEntry } from './registry.js';
import { sha256Hex } from './loader.js';

const ALLOWED_CHILD = (t) => TYPES[t] && !TYPES[t].occt && !['import', 'split', 'enclosure'].includes(t);
const MAX_CHILDREN = 64;
const stripAssets = (P) => ({ ...JSON.parse(JSON.stringify({ ...P, assets: {} })), assets: Object.fromEntries(Object.entries(P.assets || {}).map(([k, a]) => [k, { name: a.name, tris: a.tris, bbox: a.bbox }])) });
const bodySummary = (b) => [...(b?.bodies || []).map((x) => ({ name: x.name, ref: false, bbox: x.bbox, volume_mm3: x.volume || 0, tris: x.tris || 0 })), ...(b?.refs || []).map((x) => ({ name: x.name, ref: true, bbox: x.bbox, volume_mm3: 0, tris: x.tris || 0 }))];
export const hasExtFeatures = (P) => (P.features || []).some((f) => isExtType(f.type));

export function createResolver({ registry, host, analysis = null }) {
  const cache = new Map();

  function valuesFor(P, f) {
    const sc = makeScope(P.params), out = {};
    for (const fd of fieldsFor(f.type)) {
      if (['name', 'body', 'op'].includes(fd.k)) continue;
      const v = f[fd.k];
      out[fd.k] = fd.kind === 'num' ? (v === '' || v == null ? null : sc.ev(String(v))) : fd.kind === 'vec3' ? (v || ['0', '0', '0']).map((x) => sc.ev(String(x))) : v;
    }
    return out;
  }

  function childrenFrom(f, specs) {
    if (!Array.isArray(specs)) throw new Error('expand must return an array of feature specs');
    if (specs.length > MAX_CHILDREN) throw new Error(`expand returned more than ${MAX_CHILDREN} features`);
    return specs.map((spec, i) => {
      if (!spec || typeof spec !== 'object' || !ALLOWED_CHILD(spec.type)) throw new Error(`expand entry ${i + 1}: type "${spec && spec.type}" is not a buildable built-in feature type`);
      const c = defaultFeature(spec.type);
      Object.assign(c, normalizeFeatureFields({ ...spec, type: spec.type }));
      c.id = `${f.id}~${i}`; c.name = `${f.name || typeDef(f.type).label} · ${spec.name || i + 1}`;
      c.body = f.body; c.op = ['add', 'cut', 'intersect', 'ref'].includes(spec.op) ? spec.op : f.op; c.suppressed = false; c.locked = false; c.fromExt = f.id;
      return c;
    });
  }

  /** @param {object} P project @param {{cacheOnly?:boolean, signal?:AbortSignal}} o */
  async function prepare(P, o = {}) {
    const prep = { project: P, warnings: [], children: {}, errors: {}, derived: false };
    if (!hasExtFeatures(P)) return prep;
    const features = [];
    for (const f of P.features) {
      if (!isExtType(f.type)) { features.push(f); continue; }
      if (f.suppressed) continue;
      const pkg = registry.get(f.ext || f.type.split('/')[0]);
      const label = projectEntry(P, f.ext)?.name || f.ext || f.type;
      if (!pkg || !typeDef(f.type)) { prep.warnings.push({ id: f.id, feature: f.name, kind: 'missing', message: `"${f.name}" needs the extension "${label}", which is not installed. It is kept as it is and skipped in the build.` }); continue; }
      if (!enabledIn(P, pkg.manifest.id)) { prep.warnings.push({ id: f.id, feature: f.name, kind: 'disabled', message: `"${f.name}" needs "${pkg.manifest.name}", which is switched off in this project. It is skipped in the build.` }); continue; }
      try {
        const values = valuesFor(P, f);
        const key = `${pkg.packageSha}|${f.type}|${JSON.stringify(values)}`;
        let specs = cache.get(key);
        if (!specs) {
          if (o.cacheOnly) { prep.warnings.push({ id: f.id, feature: f.name, kind: 'pending', message: `"${f.name}" is being rebuilt by "${pkg.manifest.name}"…` }); continue; }
          specs = await host.invoke(pkg, 'featureType', f.type.split('/')[1], values, { signal: o.signal, timeoutMs: 10000 });
          cache.set(key, specs); if (cache.size > 500) cache.delete(cache.keys().next().value);
        }
        const kids = childrenFrom(f, specs);
        prep.children[f.id] = kids.map((k) => k.id);
        features.push(...kids);
      } catch (e) {
        prep.errors[f.id] = String(e.message || e);
        prep.warnings.push({ id: f.id, feature: f.name, kind: 'error', message: `"${f.name}": ${prep.errors[f.id]}` });
      }
    }
    prep.project = { ...P, features }; prep.derived = true;
    return prep;
  }

  /** Put parent-level status on a kernel result: skipped / failed package features show up on the feature the user sees. */
  function mergeStatus(result, prep, P) {
    if (!prep.derived) return result;
    result.status = result.status || {};
    for (const [pid, kids] of Object.entries(prep.children)) {
      const bad = kids.map((k) => result.status[k]).find((s) => s && s.ok === false);
      for (const k of kids) delete result.status[k];
      result.status[pid] = bad ? { ok: false, error: bad.error } : { ok: true };
    }
    for (const w of prep.warnings) result.status[w.id] = w.kind === 'error' ? { ok: false, error: prep.errors[w.id] } : { ok: true, skipped: true, warning: w.message };
    for (const f of P.features) if (isExtType(f.type) && f.suppressed) result.status[f.id] = { ok: true, suppressed: true };
    result.extWarnings = prep.warnings;
    return result;
  }

  /**
   * Package checks as rows for the scorecard. `P` is the project (for which packages are on); the checks themselves read the design
   * through the API. validity 'experimental' rows are advisory: shown, never gating.
   */
  async function checkRows(P, { signal, build, runAnalyses = false, budget } = {}) {
    const rows = [];
    const input = { project: stripAssets(P), bodies: bodySummary(build) };
    for (const { ext, pkg, item } of registry.contributions('checks')) {
      if (!enabledIn(P, ext)) continue;
      const base = { id: `${ext}:${item.id}`, label: item.label, ext };
      try {
        const r = await host.invoke(pkg, 'check', item.id, input, { signal, timeoutMs: 20000 });
        if (!r || typeof r.pass !== 'boolean') throw new Error('a check must return { pass: boolean, detail?: string }');
        const validity = ['estimate', 'validated', 'experimental'].includes(r.validity) ? r.validity : 'estimate';
        rows.push({ ...base, severity: validity === 'experimental' ? 'advisory' : 'hard', pass: r.pass, detail: String(r.detail ?? '').slice(0, 400), items: Array.isArray(r.items) ? r.items.slice(0, 50) : [], validity });
      } catch (e) {
        rows.push({ ...base, severity: 'hard', pass: false, detail: `check could not run: ${String(e.message || e)}`, items: [], validity: 'estimate' });
      }
    }
    // declared analysis checks (thresholds on analysis results); validity decides whether a row can fail the design
    if (analysis) rows.push(...await analysis.rows(P, build, { run: runAnalyses, signal, budget }));
    // checks the project recorded but whose package is gone or off: visible, not gating
    for (const e of P.extensions || []) {
      if (e.enabled === false) continue;
      if (!registry.get(e.id) && (e.checks || []).length) rows.push({ id: `${e.id}:*`, label: `Needs "${e.name || e.id}"`, ext: e.id, severity: 'advisory', pass: false, detail: `${e.checks.length} check(s) from "${e.name || e.id}" cannot run: the extension is not installed`, items: [], validity: 'estimate' });
    }
    return rows;
  }

  /** Append package rows to a scorecard result (either kernel). Recomputes the verdict from the hard rows only. */
  function mergeChecks(res, rows) {
    if (!rows.length) return res;
    const hard = rows.filter((c) => c.severity === 'hard');
    return { ...res, checks: [...res.checks, ...rows], passed: res.passed && hard.every((c) => c.pass), failed: [...res.failed, ...hard.filter((c) => !c.pass).map((c) => c.id)] };
  }

  /** Tools the packages switched on in P offer to the AI / MCP server: [{ ext, id, description, schema }]. */
  const agentTools = (P) => registry.contributions('agentTools').filter((c) => enabledIn(P, c.ext)).map((c) => ({ ext: c.ext, id: c.item.id, name: `${c.ext}.${c.item.id}`, description: c.item.description || c.item.label, schema: c.item.schema }));
  const callAgentTool = (P, ext, id, args, o = {}) => {
    const pkg = registry.get(ext);
    if (!pkg || !enabledIn(P, ext) || !(pkg.manifest.contributes?.agentTools || []).some((t) => t.id === id)) return Promise.reject(Object.assign(new Error(`no tool "${id}" from "${ext}" in this project`), { code: 'E_UNAVAILABLE' }));
    return host.invoke(pkg, 'agentTool', id, args ?? {}, { timeoutMs: 20000, ...o });
  };
  /** Loop steps contributed by packages switched on in P, and a way to call one. Not yet part of the fixed design-loop graph. */
  const loopSteps = (P) => registry.contributions('loopSteps').filter((c) => enabledIn(P, c.ext)).map((c) => ({ ext: c.ext, id: c.item.id, label: c.item.label }));
  const callLoopStep = (P, ext, id, args, o = {}) => {
    const pkg = registry.get(ext);
    if (!pkg || !enabledIn(P, ext) || !(pkg.manifest.contributes?.loopSteps || []).some((t) => t.id === id)) return Promise.reject(Object.assign(new Error(`no loop step "${id}" from "${ext}" in this project`), { code: 'E_UNAVAILABLE' }));
    return host.invoke(pkg, 'loopStep', id, args ?? {}, { timeoutMs: 20000, ...o });
  };

  /** Summary rows (never fields) of the analysis results that match the current design: what the critic is shown. */
  const analysisSummaries = (P, build) => (analysis ? analysis.summaries(P, build) : Promise.resolve([]));

  const clear = () => cache.clear();
  return { prepare, mergeStatus, checkRows, mergeChecks, agentTools, callAgentTool, loopSteps, callLoopStep, analysisSummaries, clear, sha: sha256Hex };
}
