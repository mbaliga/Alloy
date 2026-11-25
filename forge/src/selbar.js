// Universal selection actions: the same list drives the selection bar, the right-click / long-press
// menu and the keyboard, in every mode (Select, Pick, Measure). Every action is an undoable commit.
import { clone, defaultFeature, featureLocked, bodyLocked } from './model.js';

const OFFSET = 5; // mm: duplicates and pastes land beside the original, not inside it

// Shift a feature's position expression by dx along x, keeping the expression readable.
function nudge(f, dx, writeDelta, ev) {
  if (!Array.isArray(f.pos)) return;
  try { const x = ev(f.pos[0]); f.pos = [writeDelta(f.pos[0], x, x + dx), f.pos[1], f.pos[2]]; } catch (_) {}
}

export function uniqueBody(project, result, base) {
  const taken = new Set([...(result?.bodies || []), ...(result?.refs || [])].map((b) => b.name));
  (project.features || []).forEach((f) => { taken.add(f.body); if (f.other) taken.add(f.other); });
  const stem = String(base).replace(/\s*\d+$/, '') || 'Body';
  for (let i = 2; ; i++) if (!taken.has(stem + i)) return stem + i;
}

// Features that make up a body: those built into it, minus splits it hands off. A body that exists only
// because another body was split cannot be copied or deleted on its own.
export const bodyFeatures = (project, name) => project.features.filter((f) => f.body === name);
// Everything that shapes a body, in order: its own features plus the all-bodies ("*") steps that come after
// its first feature. A copy replays exactly this, so it looks the same as the original.
export function bodyHistory(project, name) {
  const first = project.features.findIndex((f) => f.body === name);
  if (first < 0) return [];
  return project.features.slice(first).filter((f) => !f.suppressed && (f.body === name || f.body === '*'));
}
export const madeBySplit = (project, name) => project.features.find((f) => f.type === 'split' && f.other === name);

// Where a copy goes: beside the original (+x, -x, +y, -y, in that order), the first spot that stays on the
// print plate and clear of every other body; +x if nothing fits.
export function placeBeside(bbox, others = [], pv = null) {
  const w = bbox.max.map((v, i) => v - bbox.min[i]);
  const cands = [[w[0] + OFFSET, 0], [-(w[0] + OFFSET), 0], [0, w[1] + OFFSET], [0, -(w[1] + OFFSET)]];
  const onPlate = (mn, mx) => !pv || (mn[0] >= -pv[0] / 2 && mx[0] <= pv[0] / 2 && mn[1] >= -pv[1] / 2 && mx[1] <= pv[1] / 2);
  const clear = (mn, mx) => others.every((o) => mx[0] <= o.min[0] || mn[0] >= o.max[0] || mx[1] <= o.min[1] || mn[1] >= o.max[1] || mx[2] <= o.min[2] || mn[2] >= o.max[2]);
  for (const [dx, dy] of cands) {
    const mn = [bbox.min[0] + dx, bbox.min[1] + dy, bbox.min[2]], mx = [bbox.max[0] + dx, bbox.max[1] + dy, bbox.max[2]];
    if (onPlate(mn, mx) && clear(mn, mx)) return [dx, dy, 0];
  }
  return [w[0] + OFFSET, 0, 0];
}

// Copy the features of a body into a new body, moved by `shift` (a number = along x, or [dx, dy, dz]).
export function copyBodyInto(np, name, to, shift, writeDelta, ev) {
  const d = Array.isArray(shift) ? shift : [shift + OFFSET, 0, 0];
  const fs = bodyHistory(np, name).map((f) => {
    const g = clone(f); g.id = defaultFeature('box').id; g.body = to; g.locked = false;
    if (f.body === '*') g.name = f.name + ' (' + to + ')';
    if (g.type === 'split' && g.other) g.other = g.other + '_' + to;
    return g;
  });
  if (!fs.length) return null;
  fs.push(defaultFeature('transform', { name: 'Move ' + to, body: to, mode: 'move', offset: d.map((v) => String(+(+v).toFixed(2))) }));
  np.features.push(...fs);
  const m = np.bodies?.[name]; if (m) { np.bodies[to] = { ...m, locked: false, hidden: false }; }
  return fs;
}

/**
 * ctx: { S, commit, refreshSel, setGizmo, view, say, blip, writeDelta, ev, doExport, meta }
 * Returns [{ id, label, key, title, run, disabled, on }] for the current selection. Empty when nothing is
 * selected and the clipboard is empty.
 */
export function selectionActions(ctx) {
  const { S } = ctx;
  const P = S.project;
  const f = S.sel ? P.features.find((x) => x.id === S.sel) : null;
  const bodyName = f ? (f.body === '*' ? null : f.body) : S.selBody;
  const bres = bodyName ? [...(S.result?.bodies || []), ...(S.result?.refs || [])].find((b) => b.name === bodyName) : null;
  const ghost = !!bres?.ghost;
  const A = [];
  const add = (a) => A.push(a);

  if (f) {
    const lk = featureLocked(P, f);
    add({ id: 'move', label: 'Move', key: 'G', on: S.gizmo === 'translate', disabled: lk || !Array.isArray(f.pos) && !(f.type === 'transform' && f.mode === 'move'), run: () => ctx.setGizmo('translate') });
    add({ id: 'rotate', label: 'Rotate', key: 'R', on: S.gizmo === 'rotate', disabled: lk || !Array.isArray(f.rot) && !(f.type === 'transform' && f.mode === 'rotate'), run: () => ctx.setGizmo('rotate') });
    add({ id: 'dup', label: 'Duplicate', key: '⌘D', run: () => dupFeature(ctx, f) });
    add({ id: 'copy', label: 'Copy', key: '⌘C', run: () => { S.clip = { kind: 'feature', items: [clone(f)] }; ctx.say(`Copied ${f.name}.`); ctx.blip(760); ctx.refreshBar(); } });
    if (S.clip) add({ id: 'paste', label: 'Paste', key: '⌘V', run: () => paste(ctx) });
    add({ id: 'hide', label: f.suppressed ? 'Show' : 'Hide', key: 'H', title: f.suppressed ? 'Unsuppress this feature' : 'Suppress this feature (it stays in the history)', disabled: lk, run: () => setF(ctx, f, { suppressed: !f.suppressed }) });
    add({ id: 'lock', label: f.locked ? 'Unlock' : 'Lock', key: 'L', on: !!f.locked, run: () => setF(ctx, f, { locked: !f.locked }) });
    if (bres) add({ id: 'frame', label: 'Frame', key: 'F', run: () => { ctx.view.fit(bres.bbox); ctx.blip(620); } });
    add({ id: 'del', label: 'Delete', key: '⌫', danger: true, disabled: lk, run: () => delFeature(ctx, f) });
  } else if (bodyName && bres) {
    const m = (P.bodies || {})[bodyName] || {};
    const lk = bodyLocked(P, bodyName);
    const split = madeBySplit(P, bodyName);
    add({ id: 'move', label: 'Move', key: 'G', on: S.gizmo === 'translate', disabled: lk || ghost, run: () => ctx.setGizmo('translate') });
    add({ id: 'rotate', label: 'Rotate', key: 'R', on: S.gizmo === 'rotate', disabled: lk || ghost, run: () => ctx.setGizmo('rotate') });
    add({ id: 'dup', label: 'Duplicate', key: '⌘D', disabled: ghost || !!split, title: split ? `Made by "${split.name}": duplicate its parent body instead` : '', run: () => dupBody(ctx, bodyName, bres) });
    add({ id: 'copy', label: 'Copy', key: '⌘C', disabled: ghost || !!split, run: () => { S.clip = { kind: 'body', name: bodyName, bbox: clone(bres.bbox), items: bodyHistory(P, bodyName).map(clone), meta: clone(m) }; ctx.say(`Copied body ${bodyName}.`); ctx.blip(760); ctx.refreshBar(); } });
    if (S.clip) add({ id: 'paste', label: 'Paste', key: '⌘V', run: () => paste(ctx) });
    add({ id: 'hide', label: m.hidden ? 'Show' : 'Hide', key: 'H', run: () => ctx.setBodyMeta(bodyName, { hidden: !m.hidden }) });
    add({ id: 'solo', label: 'Isolate', key: 'I', on: S.solo === bodyName, disabled: ghost, run: () => { S.solo = S.solo === bodyName ? null : bodyName; ctx.refreshSel(); ctx.blip(560); } });
    add({ id: 'lock', label: m.locked ? 'Unlock' : 'Lock', key: 'L', on: !!m.locked, run: () => ctx.setBodyMeta(bodyName, { locked: !m.locked }) });
    add({ id: 'frame', label: 'Frame', key: 'F', run: () => { ctx.view.fit(bres.bbox); ctx.blip(620); } });
    if (!ghost) add({ id: 'stl', label: 'Export STL', menuOnly: true, title: 'Export this body as STL', run: () => ctx.doExport('stl', bodyName) });
    add({ id: 'del', label: 'Delete', key: '⌫', danger: true, disabled: lk || ghost || !!split, title: split ? `Made by "${split.name}": delete or suppress that split instead` : '', run: () => delBody(ctx, bodyName) });
  } else if (S.clip) {
    add({ id: 'paste', label: 'Paste', key: '⌘V', run: () => paste(ctx) });
  }
  return A;
}

function setF(ctx, f, patch) {
  const np = clone(ctx.S.project); Object.assign(np.features.find((x) => x.id === f.id), patch); ctx.commit(np); ctx.blip(640);
}
function dupFeature(ctx, f) {
  const { S } = ctx;
  const np = clone(S.project); const i = np.features.findIndex((x) => x.id === f.id);
  const g = clone(f); g.id = defaultFeature('box').id; g.name = f.name + ' copy'; g.locked = false;
  nudge(g, OFFSET, ctx.writeDelta, ctx.ev);
  np.features.splice(i + 1, 0, g); S.sel = g.id; ctx.commit(np); ctx.blip(800);
}
function delFeature(ctx, f) {
  const { S } = ctx;
  const np = clone(S.project); np.features = np.features.filter((x) => x.id !== f.id); S.sel = null; ctx.commit(np); ctx.blip(200, 0.08);
}
function dupBody(ctx, name, bres) {
  const { S } = ctx;
  const np = clone(S.project); const to = uniqueBody(np, S.result, name);
  if (!copyBodyInto(np, name, to, placeFor(ctx, bres.bbox), ctx.writeDelta, ctx.ev)) return ctx.say('Nothing to duplicate.');
  S.sel = null; S.selBody = to; ctx.commit(np); ctx.blip(800);
}
function placeFor(ctx, bbox) {
  const R = ctx.S.result; const others = (R?.bodies || []).filter((b) => !b.noPlate).map((b) => b.bbox);
  return placeBeside(bbox, others, ctx.S.project.settings?.printVolume);
}
function delBody(ctx, name) {
  const { S } = ctx;
  const np = clone(S.project);
  np.features = np.features.filter((f) => f.body !== name);
  if (np.bodies) delete np.bodies[name];
  if (S.solo === name) S.solo = null;
  S.selBody = null; S.sel = null; ctx.commit(np); ctx.blip(200, 0.08);
}
function paste(ctx) {
  const { S } = ctx; const c = S.clip; if (!c) return;
  const np = clone(S.project);
  if (c.kind === 'feature') {
    const target = S.selBody && !bodyLocked(np, S.selBody) ? S.selBody : null;
    const made = c.items.map((src) => {
      const g = clone(src); g.id = defaultFeature('box').id; g.locked = false;
      if (target && g.body !== '*') g.body = target; else nudge(g, OFFSET, ctx.writeDelta, ctx.ev);
      return g;
    });
    if (made.some((g) => g.body !== '*' && bodyLocked(np, g.body))) return ctx.say(`Body ${made[0].body} is locked.`);
    np.features.push(...made); S.sel = made[made.length - 1].id;
  } else {
    // paste a body: rebuild its features under a fresh name, beside the original
    const to = uniqueBody(np, S.result, c.name);
    const tmp = { ...np, features: c.items.map(clone), bodies: { [c.name]: c.meta } };
    const fs = copyBodyInto(tmp, c.name, to, placeFor(ctx, c.bbox), ctx.writeDelta, ctx.ev); if (!fs) return;
    np.features.push(...fs); np.bodies = { ...(np.bodies || {}), [to]: { ...(c.meta || {}), locked: false, hidden: false } };
    S.sel = null; S.selBody = to;
  }
  ctx.commit(np); ctx.blip(820);
}

// ── rendering: the bar (above the prompt) and the context menu (at the pointer)
export function renderSelBar(box, actions, label, h, onClose) {
  box.textContent = '';
  if (!actions.length) { box.hidden = true; return; }
  box.hidden = false;
  if (label) box.append(h('span', { class: 'slabel' }, label));
  for (const a of actions.filter((x) => !x.menuOnly)) box.append(h('button', { class: 'sb' + (a.on ? ' on' : '') + (a.danger ? ' danger' : ''), disabled: !!a.disabled, title: (a.title || a.label) + (a.key ? ` (${a.key})` : ''), 'data-act': a.id, onclick: (e) => { e.stopPropagation(); a.run(); } }, a.label));
  box.append(h('button', { class: 'sb x', title: 'Deselect (Esc)', onclick: (e) => { e.stopPropagation(); onClose(); } }, '✕'));
}

export function openCtxMenu(menu, actions, x, y, h) {
  menu.textContent = '';
  if (!actions.length) { menu.hidden = true; return; }
  for (const a of actions) menu.append(h('button', { class: a.danger ? 'danger' : '', disabled: !!a.disabled, 'data-act': a.id, onclick: (e) => { e.stopPropagation(); menu.hidden = true; a.run(); } }, h('span', {}, a.label), a.key ? h('kbd', {}, a.key) : null));
  menu.hidden = false;
  const r = menu.getBoundingClientRect();
  menu.style.left = Math.max(8, Math.min(x, innerWidth - r.width - 8)) + 'px';
  menu.style.top = Math.max(8, Math.min(y, innerHeight - r.height - 8)) + 'px';
}
