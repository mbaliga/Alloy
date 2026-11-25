// The Forge design loop: a model proposes typed ops, Forge applies and builds them, deterministic checks verify, an
// optional critic scores against a style brief, and the loop repeats until the checks pass and the score clears the
// threshold; a person then approves before the result is applied. The graph is data (loops/enclosure-design.loop.json,
// run by the vendored loop engine in ./loop/); this file supplies the executors that give its nodes meaning.
//
// Rules this file keeps (see CLAUDE.md): the model never edits geometry directly. It returns typed ops, which go through
// applyOps (locks enforced in the ops layer), are built by the kernel, and only reach the user's project through
// onExport / commit, so they stay undoable. Nothing leaves the browser except the model call the user configured.

import { LoopRunDriver } from './loop/index.js';
import designLoop from '../loops/enclosure-design.loop.json' with { type: 'json' };
import { applyOps, projectForAI, clone, featureLocked } from './model.js';
import { build as kernelBuild } from './kernel.js';
import { callModel as defaultCallModel, systemPrompt, extractJSON } from './ai.js';
import { runChecks } from './checks.js';

export const LOOP_DEFINITION = designLoop;
export const DEFAULT_THRESHOLD = 7;
export const VARIANT_HINTS = [
  'Balanced: a sensible, conventional design.',
  'Minimise print time and material: thinner (but still passing) walls, fewer and simpler features.',
  'Maximise robustness: generous walls, corner radii, screw bosses and ribs where they help.',
  'Compact: the smallest footprint that still clears the reference parts and passes every check.',
  'Easy assembly and serviceability: obvious screw access, snap or screw lid, generous clearances.',
  'Cooling and ports first: ventilation and port cut-outs get priority, then everything else.',
];

const est = (s) => Math.ceil(String(s ?? '').length / 4);
const r2 = (v) => Math.round(v * 100) / 100;

/** Locked items of a project in a comparable form (features, params, bodies). */
export function lockedSnapshot(P) {
  return JSON.stringify({
    features: (P.features || []).filter((f) => featureLocked(P, f)).map((f) => [f.id, f]).sort(),
    params: (P.params || []).filter((p) => p.locked).map((p) => [p.name, p.expr, p.locked]).sort(),
    bodies: Object.entries(P.bodies || {}).filter(([, b]) => b && b.locked).sort(),
  });
}
/** True when everything locked in `base` is unchanged in `next` (defence in depth; applyOps already enforces it). */
export const lockedIntact = (base, next) => {
  const a = JSON.parse(lockedSnapshot(base)), b = JSON.parse(lockedSnapshot(next));
  const has = (list, key) => list.some((x) => JSON.stringify(x) === JSON.stringify(key));
  return ['features', 'params', 'bodies'].every((k) => a[k].every((x) => has(b[k], x)));
};

function summariseChecks(res) {
  return res.checks.map((c) => ({ id: c.id, label: c.label, pass: c.pass, detail: c.detail }));
}

/**
 * Run the design loop once.
 * @param {object} o
 *   project     base Forge project (not mutated)
 *   cfg         model config { provider, key, model, base } for ai.callModel
 *   wasm        initialised Manifold module
 *   goal, styleBrief, threshold, hint, maxRounds (4), minWall (1.2)
 *   maxTokens, maxCost, prices {inPerM,outPerM} (USD per million tokens; cost is only counted when set)
 *   approve(ctx) -> {decision:'approved'|'rejected', feedback?}   human gate (default: auto-approve)
 *   onExport(project)   called when approved; the host applies it (as one undoable commit)
 *   onRound(rounds), onProgress({steps,tokens,cost,limits,node}), onEvent(evt)
 *   signal      AbortSignal -> CANCELLED
 *   callModel / build / now   injectable for tests
 * @returns {{cancel, on, promise}}  promise resolves to the result object (see bottom of this function)
 */
export function runDesignLoop(o) {
  const env = {
    callModel: o.callModel || defaultCallModel, build: o.build || kernelBuild, wasm: o.wasm,
    minWall: o.minWall ?? 1.2, maxRounds: Math.max(1, o.maxRounds ?? 4), prices: o.prices || null,
  };
  const params = { goal: o.goal || 'Improve this enclosure.', styleBrief: o.styleBrief || '', threshold: o.threshold ?? DEFAULT_THRESHOLD, hint: o.hint || 'none' };
  const S = { base: clone(o.project), current: clone(o.project), rounds: [], lastBuild: null, best: null, exported: null, feedback: null, tokens: 0, cost: 0 };
  const round = () => S.rounds[S.rounds.length - 1];
  const tick = () => o.onRound && o.onRound(S.rounds);
  const disposeBuild = () => { try { S.lastBuild?.dispose(); } catch (_) { /* already freed */ } S.lastBuild = null; };
  const usageOf = (tin, tout, estimated) => {
    const cost = env.prices ? (tin * (env.prices.inPerM || 0) + tout * (env.prices.outPerM || 0)) / 1e6 : 0;
    return { tokensIn: tin, tokensOut: tout, cost, estimated };
  };
  const call = async (ctx, system, content) => {
    const meta = {};
    const text = await env.callModel(o.cfg || {}, [{ role: 'user', content }], ctx.signal, system, meta);
    const u = meta.usage ? usageOf(meta.usage.inputTokens, meta.usage.outputTokens, false) : usageOf(est(system) + est(content), est(text), true);
    ctx.reportUsage(u);
    return text;
  };

  const executors = {
    'forge-designer': async (node, input, ctx) => {
      const n = S.rounds.length + 1;
      if (n > env.maxRounds) { // past the round budget: the step budget stops the run right after this; make no model call
        S.rounds.push({ n, overBudget: true, explanation: 'Round budget exhausted.', ops: [] }); tick();
        return { ops: [], explanation: 'round budget exhausted', skipped: true };
      }
      const findings = [];
      const b = input.outputs.build, c = input.outputs.check, k = input.outputs.critic;
      if (b && b.error) findings.push(`Your last ops were rejected: ${b.error}`);
      if (c && !c.passed) for (const x of c.checks.filter((x) => !x.pass)) findings.push(`FAILED CHECK ${x.id} — ${x.label}: ${x.detail}`);
      if (k && !k.skipped && k.score < k.threshold) findings.push(`Critic scored ${k.score}/10 (need ${k.threshold}): ${(k.notes || []).join('; ')}`);
      if (S.feedback) { findings.push(`The person reviewing rejected the last result: ${S.feedback}`); S.feedback = null; }
      const user = 'CURRENT STATE (JSON):\n' + JSON.stringify({ project: projectForAI(S.current) })
        + `\n\nGOAL: ${params.goal}\nROUND: ${n} of ${env.maxRounds}\n`
        + (findings.length ? 'FINDINGS FROM THE PREVIOUS ROUND (fix these first):\n- ' + findings.join('\n- ') + '\n' : 'This is the first round; no findings yet.\n')
        + 'Return the JSON object of typed ops now.';
      const rd = { n, explanation: '', ops: [], findings };
      S.rounds.push(rd); tick();
      const text = await call(ctx, systemPrompt() + '\n\nLOOP INSTRUCTIONS:\n' + input.system, user);
      try {
        const plan = extractJSON(text);
        rd.explanation = String(plan.explanation || '');
        rd.ops = Array.isArray(plan.ops) ? plan.ops : [];
      } catch (e) { rd.error = e.message; }
      tick();
      return { ops: rd.ops, explanation: rd.explanation, error: rd.error || null };
    },

    'forge-build': async (node, input, ctx) => {
      const rd = round();
      const ops = input.outputs.designer?.ops || [];
      const base = S.current;
      let error = rd.error || null, log = [];
      let next = base;
      if (ops.length) {
        try {
          const r = applyOps(base, ops);
          if (!lockedIntact(base, r.project)) throw new Error('the ops changed something the user locked');
          next = r.project; log = r.log;
        } catch (e) { error = e.message; }
      }
      disposeBuild();
      let built;
      try { built = env.build(next, env.wasm); } catch (e) { error = error || `build failed: ${e.message}`; next = base; built = env.build(base, env.wasm); }
      S.lastBuild = built; S.candidate = next; if (!error) S.current = next;
      rd.log = log; rd.opsError = error; tick();
      return { ok: !error, error, applied: log.length, log, bodies: built.bodies.map((x) => x.name) };
    },

    'forge-check': async (node, input, ctx) => {
      const rd = round();
      const P = S.current;
      const res = runChecks(P, S.lastBuild, { minWall: env.minWall });
      if (rd.opsError) { // surface a rejected round as a hard failure so the gateway iterates
        res.checks.unshift({ id: 'ops_valid', label: 'Proposed ops were valid and respected locks', severity: 'hard', pass: false, detail: rd.opsError });
        res.passed = false; res.failed.unshift('ops_valid');
      }
      rd.checks = summariseChecks(res); rd.metrics = res.metrics; rd.hardPassed = res.passed; tick();
      return { passed: res.passed, failed: res.failed, checks: summariseChecks(res), metrics: res.metrics };
    },

    'forge-critic': async (node, input, ctx) => {
      const rd = round();
      const chk = input.outputs.check;
      const out = { score: 0, threshold: params.threshold, hardPassed: !!chk.passed, skipped: false, notes: [] };
      if (!params.styleBrief.trim()) { out.skipped = true; out.notes = ['no style brief; critic skipped']; }
      else if (!chk.passed) { out.skipped = true; out.notes = ['hard checks failed; critic skipped']; }
      else {
        try {
          const content = 'DESIGN (JSON):\n' + JSON.stringify({ project: projectForAI(S.current), metrics: chk.metrics, checks: chk.checks }) + '\n\nScore it now.';
          const text = await call(ctx, input.system, content);
          const j = extractJSON(text);
          const s = Number(j.score);
          if (!Number.isFinite(s)) throw new Error('critic returned no numeric score');
          out.score = Math.max(0, Math.min(10, r2(s)));
          out.notes = Array.isArray(j.notes) ? j.notes.map(String) : [];
        } catch (e) {
          if (ctx.signal?.aborted) throw e;
          // a critic failure must not trap the loop: treat as skipped and say so (the hard checks still gate)
          out.skipped = true; out.notes = [`critic unavailable (${e.message}); skipped`]; out.warning = true;
        }
      }
      rd.score = out.skipped ? null : out.score; rd.skipped = out.skipped; rd.notes = out.notes; rd.threshold = out.threshold;
      const pass = out.hardPassed && (out.skipped || out.score >= out.threshold);
      if (out.hardPassed && (!S.best || (out.score ?? 0) >= (S.best.score ?? 0))) S.best = { project: clone(S.current), score: out.skipped ? null : out.score, round: rd.n, checks: rd.checks };
      rd.passed = pass; tick();
      return out;
    },

    'forge-approve': async (node, input, ctx) => {
      const rd = round();
      const payload = { round: rd.n, project: S.current, checks: rd.checks, score: rd.score, notes: rd.notes, explanation: rd.explanation };
      let ans = { decision: 'approved' };
      if (o.approve) {
        ans = await new Promise((res, rej) => {
          if (ctx.signal?.aborted) return rej(new DOMException('aborted', 'AbortError'));
          ctx.signal?.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError')), { once: true });
          Promise.resolve(o.approve(payload)).then(res, rej);
        });
      }
      const decision = ans.decision === 'approved' ? 'approved' : 'rejected';
      if (decision === 'rejected') S.feedback = ans.feedback || 'No reason given.';
      rd.decision = decision; rd.feedback = ans.feedback || null; tick();
      return { decision, feedback: ans.feedback || null };
    },

    'forge-export': async (node, input, ctx) => {
      S.exported = clone(S.current);
      if (o.onExport) await o.onExport(S.exported);
      return { exported: true, round: round().n };
    },
  };

  const driver = new LoopRunDriver({ executors });
  const maxSteps = 4 * env.maxRounds + 2;
  const budget = { maxSteps };
  if (o.maxTokens) budget.maxTokensTotal = o.maxTokens;
  if (o.maxCost != null && env.prices) budget.maxCost = o.maxCost;
  const limits = { rounds: env.maxRounds, tokens: o.maxTokens || designLoop.budgets.tokens, cost: o.maxCost ?? designLoop.budgets.cost.amount };
  let live = { steps: 0, tokens: 0, cost: 0, node: null };
  const handle = driver.start(designLoop, params.goal, {
    params, budget, signal: o.signal, onStep: (s) => {
      live = { steps: s.index + 1, tokens: live.tokens + (s.tokensIn || 0) + (s.tokensOut || 0), cost: live.cost + (s.cost || 0), node: s.nodeId, estimated: !!s.estimated };
      if (o.onProgress) o.onProgress({ ...live, limits, maxSteps });
    },
  });
  if (o.onEvent) handle.on('*', o.onEvent);

  const promise = handle.promise.then(({ run, result }) => {
    const state = run.runState;
    const ok = state === 'SUCCEEDED_VERIFIED';
    const last = S.rounds[S.rounds.length - 1];
    const bestEffort = S.best?.project || (S.current !== S.base ? S.current : null);
    const out = {
      state, ok, reason: run.terminalReason?.detail || result?.stoppedBecause || '', category: run.terminalReason?.category,
      rounds: S.rounds.filter((r) => !r.overBudget), usage: { steps: live.steps, tokens: live.tokens, cost: r2(live.cost * 1e4) / 1e4, estimated: live.estimated, limits },
      project: S.exported, bestEffort: S.exported ? null : bestEffort ? clone(bestEffort) : null,
      score: S.best?.score ?? last?.score ?? null, checks: last?.checks || [], hint: params.hint, run, ledger: handle.ledger,
    };
    disposeBuild();
    return out;
  }, (e) => { disposeBuild(); throw e; });
  return { cancel: (r) => handle.cancel(r), on: handle.on, handle, promise };
}

/**
 * Fan out N independent loops in parallel (each with its own budget), differing by direction hint. Variants auto-approve:
 * a person chooses between finished variants instead of approving each one. Returns a promise of a list sorted best first.
 */
export async function runVariants(o) {
  const n = Math.max(1, Math.min(o.n || 3, VARIANT_HINTS.length));
  const runs = [];
  for (let i = 0; i < n; i++) {
    const hint = i === 0 && o.hint ? o.hint : VARIANT_HINTS[i % VARIANT_HINTS.length] + (o.hint && i ? ' ' + o.hint : '');
    const r = runDesignLoop({
      ...o, hint, approve: null, onExport: null,
      onRound: o.onVariantRound && ((rounds) => o.onVariantRound(i, rounds)),
      onProgress: o.onVariantProgress && ((p) => o.onVariantProgress(i, p)),
      onEvent: null,
    });
    runs.push(r);
  }
  o.signal?.addEventListener('abort', () => runs.forEach((r) => r.cancel('cancelled by user')), { once: true });
  const settled = await Promise.allSettled(runs.map((r) => r.promise));
  const list = settled.map((s, i) => {
    if (s.status === 'rejected') return { index: i, hint: VARIANT_HINTS[i], state: 'FAILED_SAFE', ok: false, error: s.reason?.message || String(s.reason), rounds: [], usage: null, score: null, project: null, checks: [] };
    const v = s.value;
    return { index: i, hint: v.hint, state: v.state, ok: v.ok, reason: v.reason, rounds: v.rounds, usage: v.usage, score: v.score, checks: v.checks, project: v.project || v.bestEffort, partial: !v.ok };
  });
  list.sort((a, b) => (b.ok - a.ok) || ((b.score ?? -1) - (a.score ?? -1)) || (a.index - b.index));
  return list;
}
