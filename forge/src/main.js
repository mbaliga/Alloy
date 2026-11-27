import Module from 'manifold-3d';
import wasmBin from '../node_modules/manifold-3d/manifold.wasm';
import { makeScope, checkSyntax } from './expr.js';
import { selectionActions, renderSelBar, openCtxMenu } from './selbar.js';
import { TYPES, fieldsFor, defaultFeature, emptyProject, wizardProject, WIZARD_DEFAULTS, applyOps, clone, featureLocked, bodyLocked, migrate, usesOcct, typeDef, extTypes } from './model.js';
import { build } from './kernel.js';
import { createView } from './view.js';
import { toSTL, toOBJ, toSTEP, importFile, importAssembly, zipOne } from './io.js';
import { runChecks } from './checks.js';
import { createOcctClient } from './occt/client.js';
import { pcbEnclosureProject } from '../templates/pcb-enclosure/index.js';
import { renderPcbSection, renderCheckPane, KSTATE } from './pcb-ui.js';
import { mountLoopPane } from './loop-pane.js';
import { assist, resolveField, PROVIDERS, needsKey, hostedSample } from './ai.js';
import { ExtRegistry, idbStore, memoryStore, enabledIn, projectEntry } from './ext/registry.js';
import { ExtHost, ExtError, browserWorkerFactory } from './ext/host.js';
import { ExtApi } from './ext/api.js';
import { createResolver, hasExtFeatures } from './ext/resolve.js';
import { createPaneHost } from './ext/panes.js';
import { mountExtensions } from './ext/ui.js';
import { createAnalysisRunner } from './analysis/runner.js';
import { createRunnerClient } from './analysis/runner-client.js';
import { mountAnalyze } from './analysis/ui.js';
import { createResultCache, idbStore as idbResultStore, memoryStore as memResultStore } from './analysis/cache.js';

const $ = (s, r = document) => r.querySelector(s);
const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (v === true) e.setAttribute(k, ''); else if (v !== false && v != null) e.setAttribute(k, v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) e.append(k.nodeType ? k : document.createTextNode(k));
  return e;
};
const store = {
  get(k) { try { return localStorage.getItem('af.' + k) ?? localStorage.getItem('sw.' + k); } catch (_) { return null; } },
  set(k, v) { try { localStorage.setItem('af.' + k, v); } catch (_) {} },
};

// ── tiny menu blips
let actx = null, soundOn = store.get('sound') !== '0';
function blip(f = 660, d = 0.04) {
  if (!soundOn) return;
  try {
    actx = actx || new (window.AudioContext || window.webkitAudioContext)();
    const o = actx.createOscillator(), g = actx.createGain();
    o.type = 'square'; o.frequency.value = f; g.gain.value = 0.025;
    g.gain.exponentialRampToValueAtTime(0.0001, actx.currentTime + d);
    o.connect(g).connect(actx.destination); o.start(); o.stop(actx.currentTime + d);
  } catch (_) {}
}

const S = {
  project: null, sel: null, selBody: null, solo: null, result: null, undo: [], redo: [], pick: null, measure: [], mode: 'orbit',
  clip: null, pane: 'tree', gizmo: 'translate', snap: true, theme: store.get('theme') || 'dark',
  card: null, cardTok: 0, kstate: 'idle',
  cfg: { provider: 'anthropic', base: '', model: PROVIDERS.anthropic.model, key: '' }, chat: [], busy: false,
};
let wasm, view, loopPane, rebuildT = 0, previewT = 0;
let registry, extHost, resolver, panes, extUi, analysisRunner, analyzeUi; // extension host (src/ext): packages, Workers, resolver, pane bridge, UI
const typeOf = (f) => typeDef(f.type) || { label: String(f.type).split('/').pop(), icon: '◇', desc: 'Needs an extension that is not installed. The feature is kept exactly as it is.', missing: true };

// ───────────────────────── project lifecycle ─────────────────────────
const snapshot = () => JSON.stringify(S.project);
function commit(np) {
  S.undo.push(snapshot()); if (S.undo.length > 100) S.undo.shift();
  S.redo = []; S.project = np; scheduleRebuild(); renderTop();
}
function undo() { if (!S.undo.length) return; S.redo.push(snapshot()); S.project = JSON.parse(S.undo.pop()); scheduleRebuild(); renderTop(); blip(440); }
function redo() { if (!S.redo.length) return; S.undo.push(snapshot()); S.project = JSON.parse(S.redo.pop()); scheduleRebuild(); renderTop(); blip(520); }
function scheduleRebuild() { clearTimeout(rebuildT); rebuildT = setTimeout(() => rebuild(), 30); }
const meta = () => {
  const B = S.project.bodies || {};
  return { hidden: new Set(Object.keys(B).filter((k) => B[k].hidden)), solo: S.solo, selBody: S.selBody, printVolume: S.project.settings.printVolume };
};

function runBuild(p) {
  try { return build(p, wasm); }
  catch (e) { console.error(e); return { bodies: [], refs: [], status: {}, params: {}, dispose() {}, fatal: String(e.message || e) }; }
}
// The OCCT kernel (B-rep) lives in a Web Worker and is only loaded when a document uses it.
let occt = null, buildTok = 0;
function getOcct() {
  if (!occt) occt = createOcctClient({ workerUrl: 'occt-worker.js', onState: (st, detail) => { S.kstate = st; S.kdetail = detail; renderKState(); } });
  return occt;
}
function renderKState() {
  const el = $('#kstate'); if (!el) return;
  const on = usesOcct(S.project);
  el.textContent = on ? (S.kstate === 'error' && S.kdetail ? 'kernel error: ' + S.kdetail : KSTATE[S.kstate] || '') : '';
  el.title = on ? 'The OCCT B-rep kernel runs in a background worker. The 22 MB Open CASCADE wasm loads once, on first use.' : '';
  document.body.classList.toggle('kbusy', on && (S.kstate === 'loading' || S.kstate === 'building'));
  $('#kernel').value = on ? 'occt' : 'manifold';
}
function applyResult(res) {
  if (S.result) S.result.dispose();
  S.result = res; if (analyzeUi) analyzeUi.invalidate();
  const first = !S.fitted && S.result.bodies.length;
  view.setResult(S.result, meta());
  if (first) { view.fit(); S.fitted = true; }
  renderPanes(); renderInspector(); renderStats(); updateGizmo(); renderKState(); refreshBar();
  const wsig = (res.extWarnings || []).map((w) => w.message).join('|');
  if (wsig && wsig !== S.warnSig) say('⚠ ' + res.extWarnings.map((w) => w.message).join(' '));
  S.warnSig = wsig;
  const j = JSON.stringify(S.project);
  store.set('project', j.length < 4e6 ? j : JSON.stringify({ ...S.project, assets: {} }));
  runCard(false);
}
async function rebuild() {
  const tok = ++buildTok;
  // Package feature types are expanded into built-in features first; the kernel builds a derived project and the saved one is untouched.
  let prep = { project: S.project, derived: false, warnings: [], children: {}, errors: {} };
  if (resolver && hasExtFeatures(S.project)) { prep = await resolver.prepare(S.project); if (tok !== buildTok) return; }
  S.prep = prep;
  const fin = (res) => applyResult(prep.derived ? resolver.mergeStatus(res, prep, S.project) : res);
  if (!usesOcct(S.project)) { fin(runBuild(prep.project)); return; }
  renderKState();
  let res;
  try { res = await getOcct().build(prep.project); }
  catch (e) { console.error(e); res = { bodies: [], refs: [], status: {}, params: {}, dispose() {}, fatal: 'OCCT kernel: ' + String(e.message || e) }; }
  if (tok !== buildTok) { res.dispose && res.dispose(); return; } // a newer edit superseded this build
  fin(res);
}
// Checks: exact B-rep scorecard on the OCCT path (runs in the worker), mesh estimates on Manifold.
async function runCard(force) {
  const R = S.result; if (!R) return;
  if (!usesOcct(S.project) && !force) { S.card = null; if (S.pane === 'check') renderCheck(); return; }
  const tok = ++S.cardTok;
  S.card = { busy: true }; if (S.pane === 'check') renderCheck();
  try {
    let res = R.kernel === 'occt' ? await R.check({}) : runChecks(S.prep?.project || S.project, R);
    if (resolver) res = resolver.mergeChecks(res, await resolver.checkRows(S.project, { build: R, runAnalyses: !!force, budget: { left: 60 } })); // rows from extensions; analyses only run when the user asks (60 s)
    if (tok !== S.cardTok) return;
    S.card = { res };
  } catch (e) { if (tok !== S.cardTok) return; S.card = { error: String(e.message || e) }; }
  renderCheck();
}
function renderCheck() { renderCheckPane($('#p-check'), { h, S, runCheck: runCard }); }
function preview(p) {
  if (usesOcct(p) || hasExtFeatures(p)) return; // no live drag preview on the B-rep kernel or with extension features: the build runs in a worker and lands on release
  clearTimeout(previewT);
  previewT = setTimeout(() => { const r = runBuild(p); view.setResult(r, meta()); if (S._prev) S._prev.dispose(); S._prev = r; }, 16);
}

// ───────────────────────── helpers ─────────────────────────
const r3 = (v) => +(+v).toFixed(3);
const isPlain = (e) => /^\s*-?\d+(\.\d+)?\s*$/.test(String(e));
function isExpr(text) {
  if (String(text).trim() === '') return true;
  try { checkSyntax(text); makeScope(S.project.params).ev(text); return true; } catch (_) { return false; }
}
function writeDelta(orig, origVal, newVal) {
  const d = r3(newVal - origVal);
  if (Math.abs(d) < 1e-4) return orig;
  if (isPlain(orig) || String(orig).trim() === '') return String(r3(newVal));
  const m = String(orig).match(/^(.*\S)\s*([+-])\s*(\d+(?:\.\d+)?)$/);
  if (m && !/[+\-*/%^(,]$/.test(m[1].trim())) {
    const n = r3((m[2] === '-' ? -1 : 1) * +m[3] + d);
    return Math.abs(n) < 1e-4 ? m[1] : `${m[1]} ${n >= 0 ? '+' : '-'} ${Math.abs(n)}`;
  }
  return `${orig} ${d >= 0 ? '+' : '-'} ${Math.abs(d)}`;
}
const selFeature = () => S.project.features.find((x) => x.id === S.sel);
function setBodyMeta(name, patch) {
  const np = clone(S.project); np.bodies = np.bodies || {};
  np.bodies[name] = { ...(np.bodies[name] || {}), ...patch };
  commit(np);
}

// ───────────────────────── panels ─────────────────────────
function renderTop() {
  $('#pname').value = S.project.name;
  $('#undo').disabled = !S.undo.length; $('#redo').disabled = !S.redo.length;
}

function renderPanes() {
  document.querySelectorAll('[data-pane]').forEach((b) => b.classList.toggle('on', b.dataset.pane === S.pane));
  document.querySelectorAll('.pane').forEach((p) => { p.hidden = p.id !== 'p-' + S.pane; });
  renderObjects(); renderTree(); renderParams(); renderCheck();
  if (loopPane) loopPane.refresh();
  if (extUi) { extUi.refreshTabs(); if (S.pane === 'ext') extUi.renderExtPane($('#p-ext')); refreshAddMenu(); }
  if (analyzeUi) { analyzeUi.refreshTabs(); if (S.pane === 'analyze') analyzeUi.render($('#p-analyze')); }
}
// "Add feature" menu: built-ins plus the feature types of the packages switched on in this project
function refreshAddMenu() {
  const sel = $('#addf'); if (!sel) return;
  sel.querySelectorAll('option[data-ext]').forEach((o) => o.remove());
  for (const [k, T] of Object.entries(extTypes())) if (enabledIn(S.project, T.ext)) sel.append(h('option', { value: k, 'data-ext': T.ext }, `${T.icon} ${T.label} · ${registry.get(T.ext)?.manifest.name || T.ext}`));
}
// Only the user changes which extensions are on: a typed op with actor 'user', one undoable step
function setExtension(op) { const r = applyOps(S.project, [op], { actor: 'user' }); commit(r.project); }

function renderObjects() {
  const box = $('#p-objects'); box.textContent = '';
  const R = S.result; if (!R) return;
  const B = S.project.bodies || {};
  const row = (b, ghost) => {
    const m = B[b.name] || {};
    const sel = S.selBody === b.name && !S.sel;
    return h('div', { class: 'row' + (sel ? ' sel' : '') + (m.hidden ? ' off' : '') + (S.solo && S.solo !== b.name ? ' dimmed' : ''),
      onclick: () => { S.selBody = b.name; S.sel = null; blip(740); refreshSel(); showSheet('insp'); } },
      h('i', { class: 'dot', style: `background:${b.color}` }),
      h('span', { class: 'nm' }, b.name),
      ghost ? h('span', { class: 'tag ref' }, 'ref') : h('span', { class: 'tag' }, `${(b.volume / 1000).toFixed(1)} cm³`),
      ghost ? null : h('button', { class: 'g' + (S.solo === b.name ? ' on' : ''), title: 'Solo', onclick: (e) => { e.stopPropagation(); S.solo = S.solo === b.name ? null : b.name; view.setResult(S.result, meta()); renderObjects(); blip(560); } }, 'S'),
      ghost ? null : h('button', { class: 'g' + (m.locked ? ' on lk' : ''), title: m.locked ? 'Unlock body' : 'Lock body', onclick: (e) => { e.stopPropagation(); setBodyMeta(b.name, { locked: !m.locked }); blip(m.locked ? 500 : 300); } }, m.locked ? '⊠' : '□'),
      h('button', { class: 'g', title: m.hidden ? 'Show' : 'Hide', onclick: (e) => { e.stopPropagation(); setBodyMeta(b.name, { hidden: !m.hidden }); blip(360); } }, m.hidden ? '○' : '●'));
  };
  R.bodies.forEach((b) => box.append(row(b, false)));
  R.refs.forEach((b) => box.append(row(b, true)));
  if (!R.bodies.length && !R.refs.length) box.append(h('div', { class: 'empty' }, 'No bodies yet'));
  renderPcbSection(box, { h, S, commit, blip, say });
}

function renderTree() {
  const box = $('#p-tree'); box.textContent = '';
  S.project.features.forEach((f) => {
    const st = S.result?.status?.[f.id] || {};
    const T = typeOf(f);
    const lk = featureLocked(S.project, f);
    box.append(h('div', { class: 'row' + (S.sel === f.id ? ' sel' : '') + (f.suppressed ? ' off' : '') + (lk ? ' locked' : ''),
      onclick: () => { S.sel = f.id; S.selBody = f.body !== '*' ? f.body : null; blip(740); refreshSel(); showSheet('insp'); } },
      h('span', { class: 'ico' }, T.icon),
      h('span', { class: 'nm' }, f.name || T.label),
      h('span', { class: 'tag' + (f.op === 'cut' ? ' cut' : f.op === 'ref' ? ' ref' : '') }, f.type === 'split' ? 'split' : f.type === 'transform' ? f.mode : f.op === 'add' ? f.body : `${f.op} ${f.body === '*' ? '*' : ''}`.trim()),
      st.skipped && st.warning ? h('span', { class: 'tag cut', title: st.warning }, 'needs ' + (projectEntry(S.project, f.ext)?.name || f.ext || 'extension')) : null,
      st.ok === false ? h('span', { class: 'err', title: st.error }, '!') : null,
      h('button', { class: 'g' + (f.locked ? ' on lk' : ''), title: f.locked ? 'Unlock' : 'Lock', onclick: (e) => { e.stopPropagation(); const p = clone(S.project); const g = p.features.find((x) => x.id === f.id); g.locked = !g.locked; commit(p); blip(g.locked ? 300 : 500); } }, f.locked ? '⊠' : '□'),
      h('button', { class: 'g', title: f.suppressed ? 'Enable' : 'Suppress', onclick: (e) => { e.stopPropagation(); if (lk) return say('Unlock it first.'); const p = clone(S.project); const g = p.features.find((x) => x.id === f.id); g.suppressed = !g.suppressed; commit(p); blip(360); } }, f.suppressed ? '○' : '●')));
  });
  if (!S.project.features.length) box.append(h('div', { class: 'empty' }, 'No features'));
}

function renderParams() {
  const box = $('#p-params'); box.textContent = '';
  const vals = S.result?.params || {};
  S.project.params.forEach((p, i) => {
    const v = vals[p.name];
    const inp = h('input', { class: 'pe', value: p.expr, spellcheck: 'false', disabled: !!p.locked,
      onchange: (e) => fieldInput(e.target, { kind: 'param', name: p.name, apply: (expr, np) => { np.params.find((q) => q.name === p.name).expr = String(expr); } }) });
    box.append(h('div', { class: 'prow' + (p.locked ? ' locked' : '') },
      h('span', { class: 'pn', title: p.name }, p.name), inp,
      h('span', { class: 'pv' }, isPlain(p.expr) ? '' : Number.isFinite(v) ? '= ' + +v.toFixed(3) : '= ?'),
      h('button', { class: 'g' + (p.locked ? ' on lk' : ''), title: p.locked ? 'Unlock' : 'Lock', onclick: () => { const np = clone(S.project); np.params[i].locked = !np.params[i].locked; commit(np); blip(400); } }, p.locked ? '⊠' : '□'),
      h('button', { class: 'x', title: 'Remove', disabled: !!p.locked, onclick: () => { const np = clone(S.project); np.params.splice(i, 1); commit(np); } }, '×')));
  });
  const nn = h('input', { class: 'pn-in', placeholder: 'name', spellcheck: 'false' });
  const ne = h('input', { class: 'pe', placeholder: 'value, expression or words', spellcheck: 'false' });
  const add = () => {
    if (!/^[A-Za-z_]\w*$/.test(nn.value) || !ne.value.trim() || S.project.params.some((p) => p.name === nn.value)) { blip(150, 0.1); return; }
    const name = nn.value;
    if (isExpr(ne.value)) { const np = clone(S.project); np.params.push({ name, expr: ne.value.trim() }); commit(np); blip(880); return; }
    fieldInput(ne, { kind: 'param', name, isNew: true, apply: (expr, np) => { np.params.push({ name, expr: String(expr) }); } });
  };
  ne.addEventListener('keydown', (e) => e.key === 'Enter' && add());
  box.append(h('div', { class: 'prow add' }, nn, ne, h('button', { class: 'x ok', onclick: add }, '+')));
}

// Every numeric input accepts a number, an expression — or plain words, which the model turns into an expression.
async function fieldInput(input, o) {
  const text = input.value;
  const vec = o.kind === 'vec3';
  if (isExpr(text)) {
    const np = clone(S.project);
    o.apply(vec ? null : text, np, text);
    commit(np); return;
  }
  if (needsKey(S.cfg) && !S.cfg.key) { say('That isn’t a number or expression. Add an AI key in Settings to type values in words.'); input.value = o.prev ?? ''; return; }
  input.classList.add('nl'); input.disabled = true;
  say('✦ Working out “' + text + '”…');
  try {
    const f = selFeature();
    const out = await resolveField(S.cfg, {
      field: o.kind === 'param' ? 'parameter ' + o.name : o.field, kind: vec ? 'vec3' : 'num', text,
      feature: o.kind === 'param' ? null : f, params: S.project.params, values: S.result?.params, bodies: (S.result?.bodies || []).map((b) => ({ name: b.name, min: b.bbox.min.map(r3), max: b.bbox.max.map(r3) })),
    });
    const np = clone(S.project);
    for (const p of out.new_params) {
      if (!/^[A-Za-z_]\w*$/.test(p.name || '') || np.params.some((q) => q.name === p.name)) continue;
      checkSyntax(String(p.expr)); np.params.push({ name: p.name, expr: String(p.expr) });
    }
    const exprs = vec ? out.exprs.map(String) : [String(out.expr)];
    const sc = makeScope(np.params);
    exprs.forEach((e) => sc.ev(e));
    if (vec) o.applyVec(exprs, np); else o.apply(exprs[0], np);
    commit(np); blip(980);
    say(`✦ ${o.kind === 'param' ? o.name : o.field} ← ${exprs.join(', ')}` + (out.new_params.length ? `  (+ ${out.new_params.map((p) => p.name).join(', ')})` : '') + (out.note ? ' · ' + out.note : ''));
  } catch (e) { say('✕ ' + (e.message || e)); input.value = o.prev ?? ''; blip(150, 0.15); }
  input.classList.remove('nl'); input.disabled = false;
}

function tipToggle(text) {
  const tip = h('div', { class: 'tip', hidden: true }, text);
  const btn = h('button', { class: 'i', type: 'button', title: text, onclick: (e) => { e.preventDefault(); e.stopPropagation(); tip.hidden = !tip.hidden; } }, 'i');
  return [btn, tip];
}

function renderInspector() {
  const box = $('#insp'); box.textContent = '';
  const f = selFeature();
  if (!f && S.selBody) return renderBodyInspector(box);
  if (!f) { box.append(h('div', { class: 'empty' }, 'Select a feature or body')); return; }
  const scope = makeScope(S.project.params);
  const T = typeOf(f);
  const lk = featureLocked(S.project, f);
  const set = (k, v) => { const np = clone(S.project); np.features.find((x) => x.id === f.id)[k] = v; commit(np); };
  const resolved = (e) => { try { return isPlain(e) || e === '' ? '' : '= ' + +scope.ev(e).toFixed(3); } catch (_) { return '= ?'; } };
  const [ib, it] = tipToggle(T.desc);
  box.append(h('div', { class: 'ihead' }, h('b', {}, T.label), ib,
    h('span', { class: 'acts' },
      h('button', { title: f.locked ? 'Unlock' : 'Lock', class: f.locked ? 'on' : '', onclick: () => set('locked', !f.locked) }, f.locked ? '⊠' : '□'),
      h('button', { title: 'Move up', disabled: lk, onclick: () => move(-1) }, '↑'),
      h('button', { title: 'Move down', disabled: lk, onclick: () => move(1) }, '↓'),
      h('button', { title: 'Duplicate', onclick: dup }, '⧉'),
      h('button', { title: 'Delete', disabled: lk, onclick: del }, '✕'))), it);
  if (lk) box.append(h('div', { class: 'lockline' }, f.locked ? 'Locked. The AI and the gizmo can’t change it.' : `Body ${f.body} is locked.`));
  for (const fd of fieldsFor(f.type)) {
    if (fd.show && !fd.show(f)) continue;
    const [tb, tt] = fd.tip ? tipToggle(fd.tip) : [null, null];
    const label = h('label', {}, h('span', { class: 'lb' }, fd.k), tb);
    let ctl;
    if (fd.kind === 'enum') ctl = h('select', { disabled: lk, onchange: (e) => set(fd.k, e.target.value) }, fd.opts.map((o) => h('option', { value: o, selected: f[fd.k] === o }, o === '' ? '—' : o)));
    else if (fd.kind === 'vec3') {
      ctl = h('div', { class: 'vec' }, [0, 1, 2].map((i) => h('input', { value: f[fd.k][i], spellcheck: 'false', title: resolved(f[fd.k][i]), disabled: lk, 'data-f': fd.k + i,
        onchange: (e) => fieldInput(e.target, { kind: 'vec3', field: fd.k, prev: f[fd.k][i],
          apply: (_, np, text) => { np.features.find((x) => x.id === f.id)[fd.k][i] = text; },
          applyVec: (ex, np) => { np.features.find((x) => x.id === f.id)[fd.k] = ex; } }) })));
    } else if (fd.kind === 'num') {
      ctl = h('input', { value: f[fd.k] ?? '', spellcheck: 'false', disabled: lk, 'data-f': fd.k,
        onchange: (e) => fieldInput(e.target, { kind: 'num', field: fd.k, prev: f[fd.k], apply: (ex, np) => { np.features.find((x) => x.id === f.id)[fd.k] = ex; } }) });
    } else ctl = h('input', { value: f[fd.k] ?? '', spellcheck: 'false', disabled: lk && fd.k !== 'name', 'data-f': fd.k, onchange: (e) => set(fd.k, e.target.value) });
    const val = fd.kind === 'num' ? h('span', { class: 'rv' }, resolved(f[fd.k])) : null;
    box.append(h('div', { class: 'fld' + (fd.kind === 'vec3' ? ' v3' : '') }, label, ctl, val, tt));
  }
  box.append(h('div', { class: 'hint' }, 'Fields take numbers, expressions or plain words.'));
  const st = S.result?.status?.[f.id];
  if (st && st.ok === false) box.append(h('div', { class: 'errbox' }, st.error));
  if (st && st.warning) box.append(h('div', { class: 'errbox warn' }, st.warning));
  function move(d) { const np = clone(S.project); const i = np.features.findIndex((x) => x.id === f.id); const j = i + d; if (j < 0 || j >= np.features.length) return; [np.features[i], np.features[j]] = [np.features[j], np.features[i]]; commit(np); blip(600); }
  function dup() { const np = clone(S.project); const i = np.features.findIndex((x) => x.id === f.id); const g = clone(f); g.id = defaultFeature('box').id; g.name = f.name + ' copy'; g.locked = false; np.features.splice(i + 1, 0, g); S.sel = g.id; commit(np); blip(800); }
  function del() { const np = clone(S.project); np.features = np.features.filter((x) => x.id !== f.id); S.sel = null; commit(np); blip(200, 0.08); }
}

function renderBodyInspector(box) {
  const name = S.selBody;
  const b = S.result?.bodies.find((x) => x.name === name) || S.result?.refs.find((x) => x.name === name);
  if (!b) { S.selBody = null; box.append(h('div', { class: 'empty' }, 'Select a feature or body')); return; }
  const m = (S.project.bodies || {})[name] || {};
  const sz = b.bbox.max.map((v, i) => v - b.bbox.min[i]);
  const ctr = b.bbox.max.map((v, i) => r3((v + b.bbox.min[i]) / 2));
  const dens = S.project.settings.density;
  const addF = (type, over) => {
    if (m.locked) return say('Body is locked.');
    const np = clone(S.project); const f = defaultFeature(type, { body: name, ...over }); np.features.push(f); S.sel = f.id; commit(np); blip(900);
  };
  box.append(h('div', { class: 'ihead' }, h('b', {}, b.ghost ? 'Reference' : 'Body'),
    h('span', { class: 'acts' },
      h('button', { title: m.locked ? 'Unlock' : 'Lock', class: m.locked ? 'on' : '', onclick: () => setBodyMeta(name, { locked: !m.locked }) }, m.locked ? '⊠' : '□'),
      h('button', { title: m.hidden ? 'Show' : 'Hide', onclick: () => setBodyMeta(name, { hidden: !m.hidden }) }, m.hidden ? '○' : '●'))));
  const nameIn = h('input', { value: name, spellcheck: 'false', disabled: !!m.locked || b.ghost, onchange: (e) => renameBody(name, e.target.value.trim()) });
  box.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'name')), nameIn));
  if (!b.ghost) box.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'colour')), h('input', { type: 'color', value: b.color, onchange: (e) => setBodyMeta(name, { color: e.target.value }) })));
  box.append(h('div', { class: 'facts' },
    h('div', {}, h('span', {}, 'size'), `${sz.map((v) => v.toFixed(2)).join(' × ')} mm`),
    h('div', {}, h('span', {}, 'centre'), ctr.join(', ')),
    b.ghost ? null : h('div', {}, h('span', {}, 'volume'), `${(b.volume / 1000).toFixed(2)} cm³ · ${(b.volume / 1000 * dens).toFixed(1)} g`),
    h('div', {}, h('span', {}, 'triangles'), String(b.tris))));
  if (!b.ghost) {
    box.append(h('div', { class: 'sub' }, 'Transform'));
    box.append(h('div', { class: 'chips' }, ['move', 'rotate', 'scale', 'shear', 'twist', 'taper', 'bend'].map((md) =>
      h('button', { class: 'chip', disabled: !!m.locked, onclick: () => addF('transform', { name: md[0].toUpperCase() + md.slice(1) + ' ' + name, mode: md, center: ctr.map(String), ...(md === 'twist' ? { amount: '45' } : md === 'taper' ? { amount: '0.7' } : md === 'bend' ? { amount: '30', axis: sz[0] >= sz[1] ? 'x' : 'y' } : {}) }) }, md))));
    box.append(h('div', { class: 'sub' }, 'Add to body'));
    box.append(h('div', { class: 'chips' },
      h('button', { class: 'chip', disabled: !!m.locked, onclick: () => addF('hole', { pos: [String(ctr[0]), String(ctr[1]), String(r3(b.bbox.max[2]))], dir: '-z', op: 'cut' }) }, 'hole'),
      h('button', { class: 'chip', disabled: !!m.locked, onclick: () => addF('standoff', { pos: [String(ctr[0]), String(ctr[1]), String(r3(b.bbox.min[2]))], op: 'add' }) }, 'boss'),
      h('button', { class: 'chip', disabled: !!m.locked, onclick: () => addF('split', { axis: 'z', offset: String(ctr[2]), other: name + 'Top' }) }, 'split'),
      h('button', { class: 'chip', onclick: () => doExport('stl', name) }, 'export STL')));
  }
}

function renameBody(from, to) {
  if (!to || to === from || to === '*') return renderInspector();
  if ((S.result?.bodies || []).some((b) => b.name === to)) { say(`A body called ${to} already exists.`); return renderInspector(); }
  const np = clone(S.project);
  np.features.forEach((f) => { if (f.body === from) f.body = to; if (f.type === 'split' && f.other === from) f.other = to; });
  if (np.bodies?.[from]) { np.bodies[to] = np.bodies[from]; delete np.bodies[from]; }
  S.selBody = to; commit(np); blip(800);
}

function renderStats() {
  const R = S.result, box = $('#stats'); if (!R) return;
  box.textContent = '';
  const pv = S.project.settings.printVolume, dens = S.project.settings.density;
  const fits0 = (bb) => bb.min[0] >= -pv[0] / 2 && bb.max[0] <= pv[0] / 2 && bb.min[1] >= -pv[1] / 2 && bb.max[1] <= pv[1] / 2 && bb.min[2] >= -1e-3 && bb.max[2] <= pv[2];
  for (const b of R.bodies) {
    const sz = b.bbox.max.map((v, i) => v - b.bbox.min[i]);
    const fits = (bb) => b.noPlate || fits0(bb);
    box.append(h('div', { class: 'stat' + (S.selBody === b.name ? ' cur' : '') }, h('i', { style: `background:${b.color}` }), h('b', {}, b.name),
      h('span', {}, `${sz.map((v) => v.toFixed(1)).join(' × ')}`),
      h('span', {}, `${(b.volume / 1000 * dens).toFixed(1)} g`),
      fits(b.bbox) ? null : h('span', { class: 'warn', title: `Outside the ${pv.join('×')} build volume` }, 'off plate')));
  }
  if (R.fatal) box.append(h('div', { class: 'warn' }, R.fatal));
  const bad = Object.values(R.status).filter((s) => s.ok === false).length;
  if (bad) box.append(h('div', { class: 'warn' }, `${bad} feature${bad > 1 ? 's' : ''} failing`));
}

// ───────────────────────── selection + gizmo ─────────────────────────
function refreshSel() { renderPanes(); renderInspector(); renderStats(); view.setResult(S.result, meta()); updateGizmo(); refreshBar(); }

// ───────────────────────── universal actions (every mode) ─────────────────────────
const selCtx = () => ({ S, commit, refreshSel, refreshBar, setGizmo, setBodyMeta, view, say, blip, writeDelta, doExport,
  ev: (e) => makeScope(S.project.params).ev(e) });
function selLabel() {
  const f = selFeature();
  if (f) return `${typeOf(f).label} / ${f.name}`;
  if (S.selBody) return `Body / ${S.selBody}`;
  return S.clip ? 'Clipboard' : '';
}
function refreshBar() {
  const box = $('#selbar'); if (!box || !S.project) return;
  renderSelBar(box, selectionActions(selCtx()), selLabel(), h, () => { S.sel = null; S.selBody = null; refreshSel(); blip(480); });
}
function runAction(id) {
  const a = selectionActions(selCtx()).find((x) => x.id === id);
  if (!a || a.disabled) return false;
  a.run(); return true;
}
function onContext(hit, x, y) {
  // right-click / long-press: select what is under the pointer (keeping a selected feature of that body), then show the actions
  if (hit) { const f = selFeature(); if (!(f && f.body === hit.body) && S.selBody !== hit.body) { S.sel = null; S.selBody = hit.body; refreshSel(); } }
  else if (!S.clip) { S.sel = null; S.selBody = null; refreshSel(); }
  openCtxMenu($('#ctxmenu'), selectionActions(selCtx()), x, y, h); S.ctxAt = performance.now(); blip(700);
}

function updateGizmo() {
  const f = selFeature();
  document.querySelectorAll('[data-gz]').forEach((b) => b.classList.toggle('on', b.dataset.gz === S.gizmo || (b.dataset.gz === 'snap' && S.snap)));
  // The gizmo works in every mode: Pick and Measure still take clicks, the gizmo takes drags.
  if (S.gizmo === 'off') return view.detachGizmo();
  if (f) {
    if (featureLocked(S.project, f) || f.suppressed) return view.detachGizmo();
    if (f.type === 'transform' && (f.mode === 'move' || f.mode === 'rotate')) return transformGizmo(f);
    if (!Array.isArray(f.pos)) return view.detachGizmo();
    return featureGizmo(f);
  }
  if (S.selBody) return bodyGizmo(S.selBody);
  view.detachGizmo();
}
const snapOpts = () => (S.snap ? { t: 0.5, r: 15 } : null);
const gizmoCbs = (apply) => ({
  change: (v) => preview(apply(v, clone(S.project))),
  end: (v) => { clearTimeout(previewT); commit(apply(v, clone(S.project))); blip(820); },
});
const bboxCentre = (bb) => bb.max.map((v, i) => r3((v + bb.min[i]) / 2));
const findBody = (name) => [...(S.result?.bodies || []), ...(S.result?.refs || [])].find((b) => b.name === name);
function featureGizmo(f) {
  const hasRot = Array.isArray(f.rot);
  const mode = S.gizmo === 'rotate' && hasRot ? 'rotate' : 'translate';
  let pos0, rot0;
  try { const sc = makeScope(S.project.params); pos0 = f.pos.map(sc.ev); rot0 = hasRot ? f.rot.map(sc.ev) : [0, 0, 0]; } catch (_) { return view.detachGizmo(); }
  view.attachGizmo(pos0, rot0, mode, snapOpts(), gizmoCbs((v, p) => {
    const g = p.features.find((x) => x.id === f.id);
    if (mode === 'translate') g.pos = f.pos.map((e, i) => writeDelta(e, pos0[i], v.pos[i]));
    else g.rot = f.rot.map((e, i) => writeDelta(e, rot0[i], v.rot[i]));
    return p;
  }));
}
// A selected move/rotate transform: drag its offset or angles directly.
function transformGizmo(f) {
  const b = findBody(f.body); if (!b) return view.detachGizmo();
  let sc; try { sc = makeScope(S.project.params); } catch (_) { return view.detachGizmo(); }
  if (f.mode === 'move') {
    if (S.gizmo === 'rotate') return view.detachGizmo();
    let off0; try { off0 = f.offset.map(sc.ev); } catch (_) { return view.detachGizmo(); }
    const c0 = bboxCentre(b.bbox);
    return view.attachGizmo(c0, [0, 0, 0], 'translate', snapOpts(), gizmoCbs((v, p) => {
      p.features.find((x) => x.id === f.id).offset = f.offset.map((e, i) => writeDelta(e, off0[i], off0[i] + v.pos[i] - c0[i])); return p;
    }));
  }
  if (S.gizmo !== 'rotate') return view.detachGizmo();
  let ctr, ang0; try { ctr = f.center.map(sc.ev); ang0 = f.angles.map(sc.ev); } catch (_) { return view.detachGizmo(); }
  view.attachGizmo(ctr, ang0, 'rotate', snapOpts(), gizmoCbs((v, p) => {
    p.features.find((x) => x.id === f.id).angles = f.angles.map((e, i) => writeDelta(e, ang0[i], v.rot[i])); return p;
  }));
}
// A selected body: dragging writes a move (or rotate) transform at the end of that body's history,
// reusing the last one if it is already the body's final step. Nothing is added until you drag.
function bodyGizmo(name) {
  const b = findBody(name);
  if (!b || b.ghost || bodyLocked(S.project, name)) return view.detachGizmo();
  const mode = S.gizmo === 'rotate' ? 'rotate' : 'translate';
  const c0 = bboxCentre(b.bbox);
  const sc = makeScope(S.project.params);
  const newId = defaultFeature('box').id;
  view.attachGizmo(c0, [0, 0, 0], mode, snapOpts(), gizmoCbs((v, p) => {
    const mine = p.features.filter((x) => x.body === name && !x.suppressed);
    const last = mine[mine.length - 1];
    const sameCentre = (t) => { try { return t.center.map(sc.ev).every((x, i) => Math.abs(x - c0[i]) < 1e-3); } catch (_) { return false; } };
    if (mode === 'translate') {
      const d = v.pos.map((x, i) => x - c0[i]);
      if (last && last.type === 'transform' && last.mode === 'move' && !last.locked) {
        try { last.offset = last.offset.map((e, i) => { const x = sc.ev(e); return writeDelta(e, x, x + d[i]); }); return p; } catch (_) {}
      }
      p.features.push(defaultFeature('transform', { id: newId, name: 'Move ' + name, body: name, mode: 'move', offset: d.map((x) => String(r3(x))) }));
    } else {
      if (last && last.type === 'transform' && last.mode === 'rotate' && !last.locked && sameCentre(last)) {
        try { last.angles = last.angles.map((e, i) => { const x = sc.ev(e); return writeDelta(e, x, x + v.rot[i]); }); return p; } catch (_) {}
      }
      p.features.push(defaultFeature('transform', { id: newId, name: 'Rotate ' + name, body: name, mode: 'rotate', center: c0.map(String), angles: v.rot.map((x) => String(r3(x))) }));
    }
    return p;
  }));
}
function setGizmo(m) {
  if (m === 'snap') S.snap = !S.snap; else S.gizmo = S.gizmo === m ? 'off' : m;
  updateGizmo(); refreshBar(); blip(600);
}

function axisOf(n) { const a = n.map(Math.abs), i = a.indexOf(Math.max(...a)); return (n[i] >= 0 ? '+' : '-') + 'xyz'[i]; }
const flip = (d) => (d[0] === '+' ? '-' : '+') + d[1];
const r2 = (v) => String(+v.toFixed(2));

function renderPick() {
  const box = $('#pickbar'); box.textContent = '';
  if (S.mode === 'measure' && S.measure.length) {
    const [a, b] = S.measure;
    if (b) { const d = b.map((v, i) => v - a[i]); box.append(h('span', {}, `Δ ${Math.hypot(...d).toFixed(2)} mm`), h('span', { class: 'dim' }, `x ${d[0].toFixed(2)}  y ${d[1].toFixed(2)}  z ${d[2].toFixed(2)}`)); }
    else box.append(h('span', { class: 'dim' }, 'pick second point'));
    box.hidden = false; return;
  }
  if (S.mode === 'pick' && S.pick) {
    const p = S.pick;
    box.append(h('span', {}, p.point.map(r2).join('  ')), h('span', { class: 'dim' }, `${p.body} ${axisOf(p.normal)}`),
      h('button', { onclick: () => addAtPick('hole') }, '+ hole'), h('button', { onclick: () => addAtPick('standoff') }, '+ boss'));
    box.hidden = false; return;
  }
  box.hidden = true;
}
function addAtPick(type) {
  const p = S.pick; if (!p) return;
  if (bodyLocked(S.project, p.body)) return say(`Body ${p.body} is locked.`);
  const np = clone(S.project);
  const n = axisOf(p.normal);
  const f = defaultFeature(type, { pos: p.point.map(r2), body: p.body, dir: type === 'hole' ? flip(n) : n });
  np.features.push(f); S.sel = f.id; commit(np); blip(900); showSheet('insp');
}

function onPick(e) {
  if (S.mode === 'measure') { if (!e) return; S.measure = S.measure.length >= 2 ? [e.point] : [...S.measure, e.point]; view.setMarks(S.measure); renderPick(); blip(700); return; }
  if (S.mode === 'pick') { if (!e) return; S.pick = e; view.setMarks([e.point]); renderPick(); blip(700); return; }
  // orbit mode: click selects a body, empty space clears
  S.selBody = e ? e.body : null; S.sel = null; S.pick = e;
  refreshSel(); if (e) { blip(740); showSheet('insp'); }
}
function setMode(m) {
  S.mode = m; S.measure = []; S.pick = null; view.setMarks([]);
  document.querySelectorAll('[data-mode]').forEach((b) => b.classList.toggle('on', b.dataset.mode === m));
  renderPick(); updateGizmo(); refreshBar(); blip(560);
}

// ───────────────────────── sheets ─────────────────────────
const PANES = ['objects', 'tree', 'params', 'check', 'loop', 'ext', 'analyze'];
function showSheet(name) {
  const mobile = matchMedia('(max-width:899px)').matches;
  if (PANES.includes(name)) { S.pane = name; renderPanes(); }
  const showLeft = PANES.includes(name);
  if (mobile) {
    $('#left').classList.toggle('show', showLeft);
    $('#inspsec').classList.toggle('show', name === 'insp');
  }
  document.querySelectorAll('#tabs button[data-sec]').forEach((b) => b.classList.toggle('on', mobile && b.dataset.sec === name));
}

// ───────────────────────── dialogs ─────────────────────────
function openWizard() {
  const d = $('#wizard'), body = $('#wbody'); body.textContent = '';
  const w = { ...WIZARD_DEFAULTS, ports: WIZARD_DEFAULTS.ports.map((p) => ({ ...p })) };
  const labels = { pcbL: 'PCB length', pcbW: 'PCB width', pcbT: 'PCB thickness', clr: 'Clearance', wall: 'Wall', floor: 'Floor', lidT: 'Lid', cornerR: 'Corner radius', innerH: 'Height above floor', standoffH: 'Standoff height', standoffD: 'Standoff Ø', screwD: 'Screw pilot Ø', holeInset: 'Mount hole inset', lipH: 'Lid lip', lipTol: 'Lip tolerance' };
  const grid = h('div', { class: 'wgrid' });
  for (const k of Object.keys(labels)) grid.append(h('label', {}, h('span', {}, labels[k]), h('input', { type: 'number', step: 'any', value: w[k], oninput: (e) => { w[k] = parseFloat(e.target.value) || 0; } })));
  const portBox = h('div', { class: 'ports' });
  const drawPorts = () => {
    portBox.textContent = '';
    w.ports.forEach((p, i) => portBox.append(h('div', { class: 'port' },
      h('input', { value: p.name, class: 'pname', oninput: (e) => { p.name = e.target.value; } }),
      h('select', { onchange: (e) => { p.side = e.target.value; } }, ['-y', '+y', '-x', '+x'].map((s) => h('option', { value: s, selected: s === p.side }, s))),
      ...[['along', 'along'], ['z', 'lift'], ['w', 'w'], ['h', 'h']].map(([k, ph]) => h('input', { type: 'number', step: 'any', placeholder: ph, title: ph, value: p[k], oninput: (e) => { p[k] = parseFloat(e.target.value) || 0; } })),
      h('button', { class: 'x', onclick: () => { w.ports.splice(i, 1); drawPorts(); } }, '×'))));
  };
  drawPorts();
  const [tb, tt] = tipToggle('Cutouts through the wall. along = offset along that side from centre, lift = height above the PCB top, w × h = opening size.');
  body.append(grid, h('div', { class: 'sub' }, 'Ports', tb, h('button', { class: 'lnk', onclick: () => { w.ports.push({ name: 'Port', side: '-y', along: 0, z: 0, w: 8, h: 4 }); drawPorts(); } }, '+ add')), tt, portBox);
  $('#wgo').onclick = () => { const p = wizardProject({ ...w, name: S.project.name }); p.name = S.project.name; S.fitted = false; S.sel = null; S.selBody = null; commit(p); d.close(); blip(980); };
  d.showModal();
}

function openSettings() {
  $('#s-provider').value = S.cfg.provider; $('#s-base').value = S.cfg.base; $('#s-model').value = S.cfg.model; $('#s-key').value = S.cfg.key;
  try { $('#s-remember').checked = !!JSON.parse(store.get('cfg') || '{}').key; } catch (_) {}
  $('#s-sound').checked = soundOn; $('#s-detail').value = S.project.settings.detail; $('#s-density').value = S.project.settings.density;
  $('#s-pv').value = S.project.settings.printVolume.join(',');
  $('#settings').showModal();
}
function saveSettings() {
  const prev = S.cfg.provider;
  S.cfg = { provider: $('#s-provider').value, base: $('#s-base').value.trim(), model: $('#s-model').value.trim(), key: $('#s-key').value.trim() };
  if (prev !== S.cfg.provider && !$('#s-model').dataset.touched) S.cfg.model = PROVIDERS[S.cfg.provider].model;
  store.set('cfg', JSON.stringify({ ...S.cfg, key: $('#s-remember').checked ? S.cfg.key : '' }));
  soundOn = $('#s-sound').checked; store.set('sound', soundOn ? '1' : '0');
  const np = clone(S.project);
  np.settings.detail = Math.max(8, Math.min(160, parseInt($('#s-detail').value) || 48));
  np.settings.density = parseFloat($('#s-density').value) || 1.24;
  const pv = $('#s-pv').value.split(',').map(parseFloat); if (pv.length === 3 && pv.every(Number.isFinite)) np.settings.printVolume = pv;
  if (JSON.stringify(np.settings) !== JSON.stringify(S.project.settings)) commit(np);
}

// ───────────────────────── export / import ─────────────────────────
async function download(blob, name) {
  let dl = null;
  try { dl = window.claude ? await window.claude.use('downloads') : null; } catch (_) {}
  if (dl) {
    const ext = name.split('.').pop();
    try {
      if (['json', 'txt', 'zip'].includes(ext)) await dl.save({ filename: name, data: blob });
      else await dl.save({ filename: name.replace(/\.[^.]+$/, '') + '.zip', data: zipOne(name, new Uint8Array(await blob.arrayBuffer())) });
      return say('Saved ' + name + (ext === 'json' ? '' : ' (inside a .zip)'));
    } catch (e) { if (e && e.code !== 'declined') say('Download unavailable here: ' + (e.message || e.code)); return; }
  }
  const a = h('a', { href: URL.createObjectURL(blob), download: name }); document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}
const slug = () => (S.project.name || 'part').replace(/[^\w-]+/g, '_');
function doExport(kind, body) {
  const bodies = S.result.bodies.filter((b) => !body || b.name === body);
  if (!bodies.length) return say('Nothing to export yet.');
  if (kind === 'stl') download(toSTL(bodies), `${slug()}${body ? '_' + body : ''}.stl`);
  if (kind === 'obj') download(toOBJ(bodies), `${slug()}.obj`);
  if (kind === 'step') {
    if (S.result.kernel === 'occt') { // true B-rep STEP from the OCCT kernel (not the faceted writer)
      S.result.stepBlob(body ? [body] : undefined).then((b) => download(b, `${slug()}.stp`)).catch((e) => say('✕ STEP export: ' + e.message));
      return;
    }
    download(toSTEP(bodies, slug()), `${slug()}.stp`);
  }
  if (kind === 'json') download(new Blob([JSON.stringify(S.project, null, 1)], { type: 'application/json' }), `${slug()}.alloy.json`);
}
async function doImportAssembly(file) {
  if (!file) return;
  say('Reading ' + file.name + '…');
  try {
    const a = await importAssembly(file, '');
    const id = 'asm' + Date.now().toString(36);
    const np = clone(S.project); np.assets[id] = a;
    commit(np); say(`Read ${a.name}: ${a.parts.length} solids. Tag them in Objects, then generate keep-outs.`); showSheet('objects');
  } catch (e) { say(String(e.message || e)); }
}
async function doImport(file) {
  if (!file) return;
  if (S.asmPending) { S.asmPending = false; return doImportAssembly(file); }
  if (file.name.endsWith('.json')) {
    try { const p = JSON.parse(await file.text()); if (!p.features) throw new Error('not an Alloy Forge project'); S.fitted = false; commit(migrate(p)); S.sel = null; } catch (e) { say('Could not open: ' + e.message); }
    return;
  }
  say('Reading ' + file.name + '…');
  try {
    const a = await importFile(file, '');
    const id = 'a' + Date.now().toString(36);
    const np = clone(S.project); np.assets[id] = a;
    const f = defaultFeature('import', { asset: id, name: a.name, body: a.name.replace(/\W+/g, '_').slice(0, 24), op: 'add' });
    np.features.push(f); S.sel = f.id; S.fitted = false; commit(np);
    say(`Imported ${a.name}: ${a.tris} triangles`);
  } catch (e) { say(String(e.message || e)); }
}

// ───────────────────────── AI prompt bar ─────────────────────────
function say(text, extra) {
  const box = $('#reply'); box.textContent = ''; box.hidden = !text;
  if (!text) return;
  box.append(h('div', { class: 'txt' }, text));
  if (extra) box.append(extra);
}
async function sendPrompt() {
  const inp = $('#ask'); const prompt = inp.value.trim();
  if (!prompt || S.busy) return;
  if (needsKey(S.cfg) && !S.cfg.key) { say('Add your API key first.'); openSettings(); return; }
  S.busy = true; document.body.classList.add('thinking'); say('Thinking…');
  const pre = Object.entries(S.result?.status || {}).filter(([, v]) => v.ok === false).map(([k]) => k);
  const f = selFeature();
  const summary = {
    bodies: (S.result?.bodies || []).map((b) => ({ name: b.name, size_mm: b.bbox.max.map((v, i) => r3(v - b.bbox.min[i])), min: b.bbox.min.map(r3), max: b.bbox.max.map(r3), volume_mm3: Math.round(b.volume), locked: bodyLocked(S.project, b.name) || undefined })),
    errors: Object.entries(S.result?.status || {}).filter(([, v]) => v.ok === false).map(([k, v]) => ({ feature: S.project.features.find((x) => x.id === k)?.name, error: v.error })),
    param_values: S.result?.params,
  };
  try {
    const out = await assist({
      cfg: S.cfg, history: S.chat, prompt, project: S.project, summary,
      selection: { selected_feature: f ? f.id : null, selected_body: S.selBody, picked: S.pick ? { point: S.pick.point.map(r2), normal_axis: axisOf(S.pick.normal), body: S.pick.body } : null },
      tryApply: (ops) => {
        const r = applyOps(S.project, ops);
        if (usesOcct(r.project)) return r; // the B-rep build is async in a worker; the result is checked after it is applied
        const res = build(r.project, wasm);
        const newBad = Object.entries(res.status).filter(([k, v]) => v.ok === false && !pre.includes(k));
        const msg = newBad.map(([k, v]) => `"${r.project.features.find((x) => x.id === k)?.name}": ${v.error}`).join('; ');
        res.dispose();
        if (newBad.length) throw new Error('Build errors after applying: ' + msg);
        return r;
      },
    });
    S.chat.push({ role: 'user', content: 'REQUEST: ' + prompt }, { role: 'assistant', content: out.assistantText });
    S.chat = S.chat.slice(-8);
    if (out.res.log.length) { commit(out.res.project); blip(980); }
    const undoBtn = out.res.log.length ? h('button', { class: 'lnk', onclick: () => { undo(); say('Undone.'); } }, 'Undo') : null;
    say(out.plan.explanation || 'Done.', h('div', { class: 'log' }, out.res.log.map((l) => h('code', {}, l)), undoBtn));
    inp.value = '';
  } catch (e) { say('✕ ' + (e.name === 'AbortError' ? 'cancelled' : e.message)); blip(150, 0.15); }
  S.busy = false; document.body.classList.remove('thinking');
}

function setTheme(t) { S.theme = t; document.body.classList.toggle('light', t === 'light'); view.setTheme(t); store.set('theme', t); if (panes) setTimeout(() => panes.theme(), 0); }

// ───────────────────────── boot ─────────────────────────
async function boot() {
  wasm = await Module({ wasmBinary: wasmBin }); wasm.setup();
  view = createView($('#vp'), { onPick, onContext, onProbe: (p) => extUi && extUi.showProbe(p) });
  let proj = null;
  try { const saved = store.get('project'); proj = saved ? JSON.parse(saved) : null; } catch (_) {}
  S.project = proj && proj.features ? migrate(proj) : wizardProject({ name: 'Enclosure' });
  S.project.bodies = S.project.bodies || {};
  let saw = false;
  try { const c = JSON.parse(store.get('cfg') || 'null'); if (c) { S.cfg = { ...S.cfg, ...c }; saw = true; } } catch (_) {}
  if (!saw && window.claude) hostedSample().then((s) => { if (s && !S.cfg.key) S.cfg.provider = 'claude'; });

  $('#pname').addEventListener('change', (e) => { const np = clone(S.project); np.name = e.target.value || 'Untitled'; commit(np); });
  $('#undo').onclick = undo; $('#redo').onclick = redo;
  $('#new').onclick = openWizard; $('#wclose').onclick = () => $('#wizard').close();
  $('#gear').onclick = openSettings; $('#sclose').onclick = () => { saveSettings(); $('#settings').close(); };
  $('#theme').onclick = () => { setTheme(S.theme === 'light' ? 'dark' : 'light'); blip(620); };
  $('#s-model').addEventListener('input', (e) => { e.target.dataset.touched = '1'; });
  $('#file').addEventListener('change', (e) => { doImport(e.target.files[0]); e.target.value = ''; });
  $('#imp').onclick = () => { S.asmPending = false; $('#file').click(); };
  $('#impasm').onclick = () => { S.asmPending = true; $('#file').click(); };
  $('#kernel').onchange = (e) => { const np = clone(S.project); np.settings.kernel = e.target.value; commit(np); blip(640); };
  $('#tmpl').onchange = (e) => {
    const t = e.target.value; e.target.value = ''; if (!t) return;
    if (t === 'pcb-enclosure') { const p = pcbEnclosureProject(); S.fitted = false; S.sel = null; S.selBody = null; commit(p); showSheet('check'); blip(980); say('PCB enclosure template: an invented demo board on the OCCT kernel. The first build loads the B-rep kernel (once).'); }
  };
  $('#addf').addEventListener('change', (e) => {
    const t = e.target.value; e.target.value = ''; if (!t) return;
    const bodies = S.result?.bodies.map((b) => b.name) || [];
    const target = S.selBody && !bodyLocked(S.project, S.selBody) ? S.selBody : bodies.find((b) => !bodyLocked(S.project, b)) || 'Body';
    const np = clone(S.project);
    const f = defaultFeature(t, { body: target }); if (S.pick && 'pos' in f) f.pos = S.pick.point.map(r2);
    np.features.push(f); S.sel = f.id; commit(np); blip(900); showSheet('insp');
  });
  const exp = $('#expm');
  $('#exp').onclick = (e) => {
    e.stopPropagation(); exp.textContent = '';
    const item = (t, fn) => exp.append(h('button', { onclick: () => { exp.hidden = true; fn(); } }, t));
    item('STEP', () => doExport('step')); item('STL (all bodies)', () => doExport('stl'));
    S.result.bodies.forEach((b) => item(`STL — ${b.name}`, () => doExport('stl', b.name)));
    item('OBJ', () => doExport('obj')); item('Project (.json)', () => doExport('json'));
    exp.append(h('button', { onclick: () => { exp.hidden = true; $('#file').click(); } }, 'Open project…'));
    exp.hidden = !exp.hidden;
  };
  // the click that ends a long-press must not close the menu it just opened
  document.addEventListener('click', () => { exp.hidden = true; if (performance.now() - (S.ctxAt || 0) > 600) $('#ctxmenu').hidden = true; });
  addEventListener('wheel', () => { $('#ctxmenu').hidden = true; }, { passive: true });
  $('#ask').addEventListener('keydown', (e) => { if (e.key === 'Enter') sendPrompt(); });
  $('#send').onclick = sendPrompt;
  document.querySelectorAll('[data-view]').forEach((b) => b.onclick = () => { view.setView(b.dataset.view); blip(620); });
  document.querySelectorAll('[data-pane]').forEach((b) => b.onclick = () => { S.pane = b.dataset.pane; renderPanes(); blip(700); });
  document.querySelectorAll('[data-gz]').forEach((b) => b.onclick = () => setGizmo(b.dataset.gz));
  $('#fit').onclick = () => { view.fit(); blip(620); };
  $('#ortho').onclick = (e) => { e.currentTarget.classList.toggle('on'); view.setOrtho(e.currentTarget.classList.contains('on')); blip(620); };
  $('#edges').onclick = (e) => { e.currentTarget.classList.toggle('on'); view.setEdges(e.currentTarget.classList.contains('on')); blip(620); };
  document.querySelectorAll('[data-mode]').forEach((b) => b.onclick = () => setMode(b.dataset.mode));
  const secAxis = $('#sec-axis'), secPos = $('#sec-pos');
  const applySec = () => {
    if (secAxis.value === 'off') { secPos.hidden = true; view.setSection(null); return; }
    const bb = view.getBounds(), i = 'xyz'.indexOf(secAxis.value);
    secPos.hidden = false; secPos.min = Math.floor(bb.min[i]); secPos.max = Math.ceil(bb.max[i]); secPos.value = (+secPos.min + +secPos.max) / 2;
    view.setSection(secAxis.value, +secPos.value);
  };
  secAxis.onchange = () => { applySec(); blip(560); }; secPos.oninput = () => view.setSection(secAxis.value, +secPos.value);
  document.querySelectorAll('#tabs button[data-sec]').forEach((b) => b.onclick = () => { const was = b.classList.contains('on'); showSheet(was ? 'none' : b.dataset.sec); blip(740); });
  addEventListener('keydown', (e) => {
    if (e.target.matches('input,select,textarea')) return;
    const mod = e.ctrlKey || e.metaKey, k = e.key.toLowerCase();
    if (mod && k === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
    if (mod && k === 'y') { e.preventDefault(); redo(); return; }
    if (mod && (k === 'c' || k === 'v' || k === 'd')) { if (runAction({ c: 'copy', v: 'paste', d: 'dup' }[k])) e.preventDefault(); return; }
    if (mod) return;
    if (e.key === 'Escape') { $('#ctxmenu').hidden = true; S.sel = null; S.selBody = null; refreshSel(); return; }
    const map = { g: 'move', w: 'move', r: 'rotate', e: 'rotate', delete: 'del', backspace: 'del', l: 'lock', h: 'hide', i: 'solo', f: 'frame' };
    const id = map[k];
    if (id && runAction(id)) { e.preventDefault(); return; }
    if (k === 'g' || k === 'w') setGizmo('translate');
    else if (k === 'r' || k === 'e') setGizmo('rotate');
    else if (k === 'f') view.fit();
  });
  const addSel = $('#addf'); Object.entries(TYPES).forEach(([k, T]) => addSel.append(h('option', { value: k }, `${T.icon} ${T.label}`)));

  // ───── extension host
  registry = new ExtRegistry({ store: typeof indexedDB !== 'undefined' ? idbStore() : memoryStore() });
  await registry.init().catch((e) => console.error('extensions:', e));
  const userStore = { get: (k) => { try { return JSON.parse(localStorage.getItem('af.ext.' + k)); } catch (_) { return undefined; } }, set: (k, v) => { try { localStorage.setItem('af.ext.' + k, JSON.stringify(v)); } catch (_) {} } };
  const api = new ExtApi({
    getProject: () => S.project, getBuild: () => S.result, isEnabled: (id) => enabledIn(S.project, id), userStore,
    commitOps: async (ops, { extId }) => { const r = applyOps(S.project, ops, { actor: 'ext', extId }); commit(r.project); return { log: r.log }; },
    brep: async (name) => {
      if (S.result?.kernel !== 'occt') throw new ExtError('E_UNAVAILABLE', 'B-rep access needs the OCCT kernel (switch the kernel in the header)');
      return new Uint8Array(await (await S.result.stepBlob([name])).arrayBuffer());
    },
    onOverlay: (e, i, o) => extUi.setOverlay(e, i, o), onPane: (m) => extUi.onPane(m),
    getRunner: () => { const r = extUi.runner.get(); return r.url ? r : null },
    remoteConsent: (info) => (analyzeUi ? analyzeUi.requestConsent(info) : false),
    gpuInfo: async () => {
      if (!navigator.gpu) return { available: false };
      try { const a = await navigator.gpu.requestAdapter(); if (!a) return { available: false }; const i = a.info || {}; return { available: true, adapter: [i.vendor, i.architecture, i.device].filter(Boolean).join(' ') || 'unknown', limits: { maxBufferSize: a.limits.maxBufferSize, maxStorageBufferBindingSize: a.limits.maxStorageBufferBindingSize } }; } catch (_) { return { available: false }; }
    },
  });
  extHost = new ExtHost({ createWorker: browserWorkerFactory, api });
  const runnerClient = createRunnerClient({ getRunner: () => { const r = extUi.runner.get(); return r.url ? r : null; } });
  analysisRunner = createAnalysisRunner({ registry, host: extHost, cache: createResultCache({ store: typeof indexedDB !== 'undefined' ? idbResultStore() : memResultStore() }) });
  resolver = createResolver({ registry, host: extHost, analysis: analysisRunner });
  const themeNow = () => { const cs = getComputedStyle(document.body); return { ink: cs.getPropertyValue('--ink').trim(), dim: cs.getPropertyValue('--dim').trim(), acc: cs.getPropertyValue('--acc').trim(), dark: S.theme !== 'light' }; };
  panes = createPaneHost({ host: extHost, getPkg: (id) => registry.get(id), getTheme: themeNow });
  extUi = mountExtensions({ client: runnerClient, h, $, tip: tipToggle, S, registry, host: extHost, resolver, panes, view, say, blip, setExtension, rebuild: scheduleRebuild, showSheet, renderPanes, refreshAddMenu });
  analyzeUi = mountAnalyze({ h, $, S, tip: tipToggle, say, blip, runner: analysisRunner, extUi, client: runnerClient, snapshot: () => view.snapshot(), download,
    commitOps: (ops) => { try { commit(applyOps(S.project, ops, { actor: 'user' }).project); return true; } catch (e) { say(String(e.message || e)); blip(200, 0.08); return false; } } });
  registry.subscribe(() => { resolver.clear(); });

  setTheme(S.theme);
  loopPane = mountLoopPane({ h, $, root: $('#p-loop'), getProject: () => S.project, getCfg: () => S.cfg, getWasm: () => wasm, getOcct, getExt: () => resolver, commit, undo, say, blip, needsKey, openSettings });
  renderTop(); renderKState(); rebuild(); showSheet(matchMedia('(max-width:899px)').matches ? 'none' : 'tree');
  window.__af = { analyze: analyzeUi, S, ext: { registry, host: extHost, resolver, panes, ui: extUi }, rebuild, applyOps, commit, build: () => build(S.project, wasm), view, doExport, toSTEP, writeDelta, getOcct, runCard };
  document.body.classList.add('ready');
}
boot().catch((e) => { console.error(e); document.body.append(h('pre', { class: 'fatal' }, 'Failed to start: ' + e.message)); });
