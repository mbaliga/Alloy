// Layer state for overlays (docs/STYLE.md: a Layers sheet). Pure, no DOM, so it is tested headless.
// What is kept per layer is on/off and opacity, in browser localStorage keyed by layer id ("<extension id>:<overlay id>").
// It is a viewing preference, so it is never saved in the project. Every storage access is wrapped: a private window, blocked site
// data or a full quota just means the state lives for this page only.

export const LAYERS_KEY = 'af.layers';
export const MIN_OPACITY = 0.05, MAX_LAYERS_KEPT = 200;

const clampOpacity = (v) => (Number.isFinite(+v) ? Math.min(1, Math.max(MIN_OPACITY, +v)) : 1);

export function createLayerStore({ storage, key = LAYERS_KEY } = {}) {
  const store = () => { try { return storage !== undefined ? storage : globalThis.localStorage; } catch (_) { return null; } };
  let state = {};
  try {
    const raw = JSON.parse(store()?.getItem(key) || 'null');
    if (raw && typeof raw === 'object' && raw.layers && typeof raw.layers === 'object') {
      for (const [id, v] of Object.entries(raw.layers)) if (v && typeof v === 'object') state[id] = { on: v.on !== false, opacity: clampOpacity(v.opacity ?? 1) };
    }
  } catch (_) { state = {}; }
  const subs = new Set();
  const save = () => {
    const ids = Object.keys(state); if (ids.length > MAX_LAYERS_KEPT) for (const id of ids.slice(0, ids.length - MAX_LAYERS_KEPT)) delete state[id];
    try { store()?.setItem(key, JSON.stringify({ v: 1, layers: state })); } catch (_) { /* viewing preference only */ }
    for (const f of subs) f();
  };
  const get = (id) => ({ on: true, opacity: 1, ...(state[id] || {}) });
  const set = (id, patch) => {
    const cur = get(id), next = { on: patch.on === undefined ? cur.on : !!patch.on, opacity: patch.opacity === undefined ? cur.opacity : clampOpacity(patch.opacity) };
    delete state[id]; state[id] = next; save(); // re-inserting keeps the most recently touched last, so the cap drops the stalest
    return next;
  };
  const setMany = (ids, on) => { for (const id of ids) { const cur = get(id); delete state[id]; state[id] = { on, opacity: cur.opacity }; } save(); };
  return {
    get, set,
    isOn: (id) => get(id).on,
    opacity: (id) => get(id).opacity,
    showAll: (ids) => setMany(ids, true),
    hideAll: (ids) => setMany(ids, false),
    /** Solo one layer: only it stays on. Solo on the layer that is already alone brings the others back. */
    solo(ids, id) { const only = this.soloed(ids) === id; for (const x of ids) { const cur = get(x); delete state[x]; state[x] = { on: only ? true : x === id, opacity: cur.opacity }; } save(); },
    /** The id that is the only one on among `ids` (with at least two layers), else null. */
    soloed(ids) { const on = ids.filter((x) => get(x).on); return ids.length > 1 && on.length === 1 ? on[0] : null; },
    onChange(f) { subs.add(f); return () => subs.delete(f); },
    snapshot: () => JSON.parse(JSON.stringify(state)),
  };
}

/** Layers grouped by analysis or extension, groups in first-seen order. `layers` are { key, extId, name, ov }. */
export function groupLayers(layers) {
  const groups = new Map();
  for (const l of layers) {
    const name = l.extId === 'analysis' ? 'Analysis' : l.name || l.extId;
    if (!groups.has(l.extId)) groups.set(l.extId, { id: l.extId, name, layers: [] });
    groups.get(l.extId).layers.push(l);
  }
  return [...groups.values()];
}

const fmt = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2)).replace(/\.?0+$/, (m) => (m.includes('.') ? '' : m));

/** The unit and range line of a layer, in words. */
export function layerReadout(l) {
  const ov = l.ov, kindWord = { points: 'points', surface: 'surface map', slice: 'slice', vectors: ov.lines && ov.lines.length ? 'streamlines' : 'arrows', box: 'box' }[ov.kind] || ov.kind;
  if (ov.kind === 'box') return `${kindWord} · no value`;
  const [lo, hi] = l.range;
  return `${kindWord} · ${ov.kind === 'vectors' ? 'speed ' : ''}${ov.unit ? 'unit ' + ov.unit + ' · ' : ''}range ${fmt(lo)} to ${fmt(hi)}${ov.unit ? ' ' + ov.unit : ''}`;
}
