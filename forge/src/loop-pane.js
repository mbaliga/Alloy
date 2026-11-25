// The "Loop" pane: run the design loop (loops/enclosure-design.loop.json) against the open project, watch it work, and
// pick a result. DOM only; the loop itself lives in design-loop.js and is headless-tested. Style canon: glass panels,
// monospace uppercase labels, explanatory copy behind `i` tips, sounds via the host's blip(), phone-first.

import { LOOP_DEFINITION, runDesignLoop, runVariants, lockedIntact, DEFAULT_THRESHOLD } from './design-loop.js';
import { featureLocked } from './model.js';

const NS = 'http://www.w3.org/2000/svg';
const svg = (tag, attrs = {}, ...kids) => {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  for (const k of kids.flat()) if (k != null) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
};

const STATE_LABEL = {
  SUCCEEDED_VERIFIED: 'Done: every check passed', STOPPED_BUDGET: 'Stopped: budget used up', CANCELLED: 'Stopped by you',
  FAILED_SAFE: 'Failed safely', STOPPED_POLICY: 'Stopped by policy', SUCCEEDED_UNVERIFIED: 'Finished, not verified',
};

/** Read-only SVG of the loop graph; returns {el, setActive(nodeId|null)}. */
export function loopGraph(def = LOOP_DEFINITION) {
  const P = (n) => n.presentation || { x: 0, y: 0 };
  const W = 78, H = 30;
  const xs = def.nodes.map((n) => P(n).x), ys = def.nodes.map((n) => P(n).y);
  const x0 = Math.min(...xs) - 10, y0 = Math.min(...ys) - 18, x1 = Math.max(...xs) + W + 10, y1 = Math.max(...ys) + H + 18;
  const root = svg('svg', { viewBox: `${x0} ${y0} ${x1 - x0} ${y1 - y0}`, class: 'lgraph', role: 'img', 'aria-label': 'Design loop graph' });
  root.append(svg('defs', {}, svg('marker', { id: 'lg-arr', viewBox: '0 0 8 8', refX: 7, refY: 4, markerWidth: 6, markerHeight: 6, orient: 'auto' }, svg('path', { d: 'M0 0L8 4L0 8z', fill: 'currentColor' }))));
  const byId = Object.fromEntries(def.nodes.map((n) => [n.nodeId, n]));
  const gate = Object.fromEntries((def.gateways || []).map((g) => [g.gatewayId, g]));
  for (const e of def.edges) {
    const a = byId[e.fromNodeId], b = byId[e.toNodeId]; if (!a || !b) continue;
    const ax = P(a).x + W / 2, ay = P(a).y + H / 2, bx = P(b).x + W / 2, by = P(b).y + H / 2;
    const back = bx < ax;
    const sx = back ? P(a).x : P(a).x + W, ex = back ? P(b).x + W : P(b).x;
    const d = back ? `M${P(a).x + W / 2} ${P(a).y} C ${ax} ${ay - 46}, ${bx} ${by - 46}, ${P(b).x + W / 2} ${P(b).y}` : `M${sx} ${ay} L${ex - 2} ${by}`;
    root.append(svg('path', { d, fill: 'none', stroke: 'currentColor', 'stroke-width': 1.2, 'marker-end': 'url(#lg-arr)', class: 'le' + (back ? ' back' : '') }));
    if (e.gatewayId) {
      const g = gate[e.gatewayId];
      const label = g?.outcomes.find((o) => o.targetNodeId === e.toNodeId)?.label || (g?.defaultOrTerminalFailurePath?.targetNodeId === e.toNodeId ? g.defaultOrTerminalFailurePath.label : '');
      if (label) root.append(svg('text', { x: back ? (ax + bx) / 2 : (sx + ex) / 2, y: back ? Math.min(ay, by) - 38 : (ay + by) / 2 - 4, class: 'll', 'text-anchor': 'middle' }, label));
    }
  }
  const boxes = {};
  for (const n of def.nodes) {
    const p = P(n), pass = !!n.config?.passthrough;
    const g = svg('g', { class: 'ln' + (pass ? ' pass' : '') + (n.category === 'HUMAN_DECISION' ? ' human' : ''), 'data-node': n.nodeId });
    g.append(svg('rect', { x: p.x, y: p.y, width: W, height: H, rx: pass ? 15 : 8 }), svg('text', { x: p.x + W / 2, y: p.y + H / 2 + 3.5, 'text-anchor': 'middle' }, n.config?.name || n.nodeId));
    root.append(g); boxes[n.nodeId] = g;
  }
  return { el: root, setActive: (id) => Object.entries(boxes).forEach(([k, g]) => g.classList.toggle('active', k === id)) };
}

/**
 * @param {object} host { h, $, root, getProject, getCfg, getWasm, commit, undo, say, blip, needsKey, openSettings }
 */
export function mountLoopPane(host) {
  const { h, root } = host;
  const st = { running: false, run: null, ac: null, rounds: [], progress: null, state: null, approval: null, variants: null, vprog: [], result: null, applied: false, form: { goal: '', styleBrief: '', maxRounds: 4, threshold: DEFAULT_THRESHOLD, maxTokens: 120000, maxCost: 1, inPerM: '', outPerM: '', minWall: '', n: 1 } };
  const graph = loopGraph();

  const tipBtn = (text) => { const tip = h('div', { class: 'tip', hidden: true }, text); return [h('button', { class: 'i', type: 'button', title: text, onclick: (e) => { e.preventDefault(); tip.hidden = !tip.hidden; } }, 'i'), tip]; };
  const num = (k, label, opt = {}) => h('label', { class: 'lf' }, h('span', {}, label), h('input', { type: 'number', min: opt.min ?? 0, step: opt.step ?? 'any', value: st.form[k], placeholder: opt.ph || '', oninput: (e) => { const v = e.target.value; st.form[k] = v === '' ? '' : Number(v); } }));

  // ---- static form
  const goal = h('textarea', { rows: 2, placeholder: 'What should the loop design or fix? e.g. "Make the lid snap-fit and add vents."', oninput: (e) => { st.form.goal = e.target.value; } });
  const brief = h('input', { placeholder: 'Style brief (optional): e.g. "calm, rounded, minimal"', oninput: (e) => { st.form.styleBrief = e.target.value; } });
  const lockline = h('div', { class: 'lockline', hidden: true });
  const runBtn = h('button', { class: 'btn', onclick: () => start() }, 'Run loop');
  const stopBtn = h('button', { class: 'btn ghost', hidden: true, onclick: () => stop() }, 'Stop');
  const meters = h('div', { class: 'meters', hidden: true });
  const status = h('div', { class: 'lstate', hidden: true });
  const approvalBox = h('div', { class: 'approval', hidden: true });
  const trace = h('div', { class: 'trace' });
  const vbox = h('div', { class: 'variants', hidden: true });
  const after = h('div', { class: 'afterline', hidden: true });

  root.append(
    h('div', { class: 'lhead' }, h('span', { class: 'mono' }, 'Design loop'), ...tipBtn('A model proposes typed ops, Forge applies and builds them, deterministic checks verify (no failing features, no interference with reference parts, minimum wall, fits the print volume), and an optional critic scores the result against your style brief. It repeats until everything passes, then waits for you to approve. Locked items are never changed. Your API key is used only to call the model you configured.')),
    graph.el, goal, brief, lockline,
    h('div', { class: 'lgrid' },
      num('maxRounds', 'Rounds', { min: 1, step: 1 }), num('threshold', 'Score ≥', { min: 0, step: 0.5 }),
      num('maxTokens', 'Token cap', { min: 1000, step: 1000 }), num('maxCost', 'Cost cap $', { min: 0, step: 0.1 }),
      num('n', 'Variants', { min: 1, step: 1 }), num('minWall', 'Min wall mm', { min: 0, step: 0.1, ph: '1.2' })),
    h('div', { class: 'lrow' }, h('span', { class: 'mono' }, 'Prices $/M tokens'), ...tipBtn('Optional. Cost is only counted (and the cost cap only applies) when you enter your model’s price per million tokens. Without prices the loop counts tokens and rounds only. Token counts are estimates (characters ÷ 4) unless the provider reports usage.')),
    h('div', { class: 'lgrid two' }, num('inPerM', 'In', { ph: 'e.g. 3' }), num('outPerM', 'Out', { ph: 'e.g. 15' })),
    h('div', { class: 'lrow' }, h('span', { class: 'mono' }, 'Variants'), ...tipBtn('Variants: N runs N independent loops in parallel, each with its own budget and a different design direction. Variants are not asked to approve; you pick the one you like from the list, and it is applied as one undoable step. Note that N runs cost roughly N times as much.')),
    h('div', { class: 'lbtns' }, runBtn, stopBtn), meters, status, approvalBox, trace, vbox, after,
  );

  function lockCount() {
    const P = host.getProject();
    return (P.features || []).filter((f) => featureLocked(P, f)).length + (P.params || []).filter((p) => p.locked).length + Object.values(P.bodies || {}).filter((b) => b && b.locked).length;
  }
  function refresh() {
    const n = lockCount();
    lockline.hidden = !n; lockline.textContent = n ? `${n} locked item${n > 1 ? 's' : ''}: the loop cannot change ${n > 1 ? 'them' : 'it'}.` : '';
    runBtn.disabled = st.running; stopBtn.hidden = !st.running;
  }

  const fmt = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(Math.round(n)));
  function renderMeters() {
    const p = st.progress; meters.hidden = !p;
    if (!p) return;
    const rounds = st.rounds.filter((r) => !r.overBudget).length;
    const cells = [['Rounds', `${rounds}/${p.limits.rounds}`], ['Tokens', `${fmt(p.tokens)}/${fmt(p.limits.tokens)}${p.estimated ? ' ~' : ''}`], ['Cost', st.form.inPerM !== '' || st.form.outPerM !== '' ? `$${p.cost.toFixed(3)}/$${p.limits.cost}` : 'n/a'], ['Steps', `${p.steps}/${p.maxSteps}`]];
    meters.textContent = '';
    for (const [k, v] of cells) meters.append(h('div', {}, h('span', {}, k), h('b', {}, v)));
  }
  function renderTrace(rounds = st.rounds, into = trace) {
    into.textContent = '';
    for (const r of rounds) {
      if (r.overBudget) continue;
      const box = h('div', { class: 'round' + (r.passed ? ' ok' : '') });
      box.append(h('div', { class: 'rh' }, h('span', { class: 'mono' }, `Round ${r.n}`), r.score != null ? h('span', { class: 'sc' }, `score ${r.score}${r.threshold != null ? ' / ' + r.threshold : ''}`) : null, r.hardPassed != null ? h('span', { class: r.hardPassed ? 'good' : 'warn' }, r.hardPassed ? 'checks pass' : 'checks fail') : null));
      if (r.explanation) box.append(h('div', { class: 'ex' }, r.explanation));
      if (r.error) box.append(h('div', { class: 'warn' }, r.error));
      if (r.log && r.log.length) box.append(h('pre', { class: 'ops' }, r.log.join('\n')));
      else if (r.ops && !r.log) box.append(h('div', { class: 'faint' }, `${r.ops.length} op${r.ops.length === 1 ? '' : 's'} proposed…`));
      for (const c of r.checks || []) box.append(h('div', { class: 'ck ' + (c.pass ? 'good' : 'bad') }, h('span', {}, c.pass ? '✓' : '✕'), h('span', {}, `${c.label}: ${c.detail}`)));
      if (r.notes && r.notes.length) box.append(h('div', { class: 'faint' }, r.notes.join(' · ')));
      if (r.decision) box.append(h('div', { class: r.decision === 'approved' ? 'good' : 'warn' }, `You ${r.decision}${r.feedback ? ': ' + r.feedback : ''}`));
      into.append(box);
    }
  }
  function renderStatus() {
    status.hidden = !st.state;
    if (!st.state) return;
    status.textContent = ''; status.className = 'lstate ' + (st.state === 'SUCCEEDED_VERIFIED' ? 'good' : st.state === 'RUNNING' ? '' : 'warn');
    status.append(h('span', { class: 'mono' }, st.state.replace(/_/g, ' ')), STATE_LABEL[st.state] ? ' · ' + STATE_LABEL[st.state] : '');
    if (st.reason && st.state !== 'RUNNING' && st.state !== 'SUCCEEDED_VERIFIED') status.append(h('div', { class: 'faint' }, st.reason));
  }

  function askApproval(payload) {
    return new Promise((resolve) => {
      host.blip(880, 0.08);
      approvalBox.hidden = false; approvalBox.textContent = '';
      const fb = h('input', { placeholder: 'What should change? (used if you ask for rework)' });
      const done = (d) => { approvalBox.hidden = true; st.approval = null; resolve({ decision: d, feedback: fb.value.trim() || (d === 'rejected' ? 'Please rework it.' : '') }); };
      approvalBox.append(h('div', { class: 'mono' }, 'Approve to apply'), h('div', { class: 'ex' }, `Round ${payload.round} passed${payload.score != null ? ` (score ${payload.score})` : ''}. ${payload.explanation || ''}`), fb,
        h('div', { class: 'lbtns' }, h('button', { class: 'btn', onclick: () => done('approved') }, 'Approve and apply'), h('button', { class: 'btn ghost', onclick: () => done('rejected') }, 'Rework')));
      st.approval = { done };
    });
  }

  function common() {
    const f = st.form;
    const prices = f.inPerM !== '' || f.outPerM !== '' ? { inPerM: Number(f.inPerM) || 0, outPerM: Number(f.outPerM) || 0 } : null;
    return {
      project: host.getProject(), cfg: host.getCfg(), wasm: host.getWasm(), occt: host.getOcct ? host.getOcct() : null, ext: host.getExt ? host.getExt() : null, goal: f.goal.trim() || 'Improve this enclosure so it passes every check.', styleBrief: f.styleBrief,
      threshold: f.threshold === '' ? DEFAULT_THRESHOLD : f.threshold, maxRounds: f.maxRounds || 4, maxTokens: f.maxTokens || undefined, maxCost: f.maxCost === '' ? undefined : f.maxCost, prices, minWall: f.minWall === '' || f.minWall == null ? undefined : f.minWall,
    };
  }

  async function start() {
    if (st.running) return;
    if (host.needsKey(host.getCfg()) && !host.getCfg().key) { host.say('Add your API key first (Settings), then run the loop.'); host.openSettings(); return; }
    Object.assign(st, { running: true, rounds: [], progress: null, state: 'RUNNING', reason: '', variants: null, result: null, applied: false, ac: new AbortController() });
    vbox.hidden = true; after.hidden = true; refresh(); renderTrace(); renderStatus(); host.blip(660);
    const base = host.getProject();
    const o = { ...common(), signal: st.ac.signal };
    try {
      if ((st.form.n || 1) > 1) await runMany(o, base);
      else await runOne(o, base);
    } catch (e) { st.state = 'FAILED_SAFE'; st.reason = e.message || String(e); host.blip(150, 0.15); }
    graph.setActive(null); st.running = false; st.ac = null; refresh(); renderStatus(); renderMeters();
  }

  async function runOne(o, base) {
    const run = runDesignLoop({
      ...o,
      onRound: (rounds) => { st.rounds = rounds.slice(); renderTrace(); renderMeters(); },
      onProgress: (p) => { st.progress = p; renderMeters(); },
      onEvent: (e) => { if (e.type === 'node:started') graph.setActive(e.nodeId); },
      approve: askApproval,
      // the one write to the user's project: a single commit, so one Undo reverts the whole loop result
      onExport: (project) => applyResult(project, base),
    });
    st.run = run;
    const r = await run.promise;
    st.rounds = r.rounds; st.state = r.state; st.reason = r.reason; st.result = r;
    renderTrace(); host.blip(r.ok ? 990 : 200, 0.12);
    if (!r.ok && r.bestEffort) {
      after.hidden = false; after.textContent = '';
      after.append(h('span', {}, 'The loop did not finish. '), h('button', { class: 'lnk', onclick: () => applyResult(r.bestEffort, base, 'best attempt') }, 'Use the best attempt so far'));
    }
  }

  async function runMany(o, base) {
    const n = Math.min(Math.max(2, st.form.n), 6);
    st.vprog = Array.from({ length: n }, () => ({ rounds: [], progress: null }));
    const draw = () => {
      vbox.hidden = false; vbox.textContent = '';
      vbox.append(h('div', { class: 'mono' }, `Variants running: ${n}`));
      st.vprog.forEach((v, i) => vbox.append(h('div', { class: 'vrow' }, h('b', {}, `#${i + 1}`), h('span', {}, `round ${v.rounds.filter((r) => !r.overBudget).length}`), h('span', { class: 'faint' }, v.progress ? `${fmt(v.progress.tokens)} tok` : ''))));
    };
    draw();
    const list = await runVariants({
      ...o, n, onVariantRound: (i, rounds) => { st.vprog[i].rounds = rounds; draw(); }, onVariantProgress: (i, p) => { st.vprog[i].progress = p; draw(); },
    });
    st.variants = list; st.state = list.some((v) => v.ok) ? 'SUCCEEDED_VERIFIED' : 'STOPPED_BUDGET'; st.reason = list.some((v) => v.ok) ? '' : 'no variant passed every check';
    renderVariants(base); host.blip(list.some((v) => v.ok) ? 990 : 200, 0.12);
  }

  function renderVariants(base) {
    vbox.hidden = false; vbox.textContent = '';
    vbox.append(h('div', { class: 'mono' }, 'Variants'), ...tipBtn('Best first: variants that passed every check, then by score. “Use” applies that variant as one undoable step. A variant that did not finish is its best attempt and is marked partial.'));
    st.variants.forEach((v) => {
      const row = h('div', { class: 'vrow col' });
      const head = h('div', { class: 'vh' }, h('b', {}, `#${v.index + 1}`), h('span', { class: v.ok ? 'good' : 'warn' }, v.ok ? 'passed' : (v.partial ? 'partial' : v.state)), h('span', {}, `${v.rounds.length} round${v.rounds.length === 1 ? '' : 's'}`),
        v.score != null ? h('span', {}, `score ${v.score}`) : null, v.usage ? h('span', { class: 'faint' }, `${fmt(v.usage.tokens)} tok`) : null,
        h('button', { class: 'btn', disabled: !v.project, onclick: () => applyResult(v.project, base, `variant #${v.index + 1}`) }, 'Use'));
      row.append(head, h('div', { class: 'faint' }, v.hint || ''));
      const bad = (v.checks || []).filter((c) => !c.pass);
      if (bad.length) row.append(h('div', { class: 'warn' }, bad.map((c) => c.label).join(' · ')));
      const det = h('details', {}, h('summary', {}, 'Trace')); const tr = h('div', { class: 'trace' }); renderTrace(v.rounds, tr); det.append(tr); row.append(det);
      vbox.append(row);
    });
  }

  function applyResult(project, base, what = 'result') {
    if (!lockedIntact(base, project)) { host.say('The result would change something you locked, so it was not applied.'); host.blip(150, 0.15); return false; }
    host.commit(project); st.applied = true; host.blip(900);
    after.hidden = false; after.textContent = '';
    after.append(h('span', {}, `Applied ${what}. `), h('button', { class: 'lnk', onclick: () => { host.undo(); after.hidden = true; } }, 'Undo'));
    return true;
  }

  function stop() { st.approval?.done && st.approval.done('rejected'); st.ac?.abort(); st.run?.cancel(); host.blip(300, 0.08); }

  refresh();
  return { refresh, stop, state: st };
}
