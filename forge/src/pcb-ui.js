// DOM for the OCCT kernel and the PCB workflow: kernel indicator text, the "PCB parts" and "Assembly" sections of the
// Objects pane (tagging), and the Check pane (scorecard). Same style as the rest of the editor: rows, tags, chips, tips.
import { TAGS, setTag, pcbFromTags } from './pcb-tags.js';
import { clone } from './model.js';

export const KSTATE = {
  idle: 'ready on demand', loading: 'loading B-rep kernel (first use, ~22 MB)…', building: 'building…', ready: 'ready', error: 'kernel error',
};

const METRIC_LABEL = {
  plastic_cm3: ['plastic', 'cm³'], shell_cm3: ['shell', 'cm³'], cap_cm3: ['cap', 'cm³'], mass_pla_g: ['PLA', 'g'], mass_petg_g: ['PETG', 'g'],
  overhang_share_pct: ['overhang > 45°', '%'], vs_original_plastic_pct: ['vs original', '%'], min_wall_sampled_mm: ['min wall (sampled)', 'mm'],
};

export function renderPcbSection(box, host) {
  const { h, S, commit, blip, say } = host;
  const P = S.project;
  const comps = (P.pcb && P.pcb.components) || [];
  if (comps.length || (P.pcb && P.pcb.keep && P.pcb.keep.length)) {
    box.append(h('div', { class: 'sub' }, 'PCB parts', h('span', { class: 'tag', style: 'margin-left:auto' }, `${comps.length} keep-outs`)));
    comps.forEach((c, i) => {
      const flags = [c.role, c.emitter ? 'emitter' : null, c.insert ? 'in:' + c.insert : null, c.exposed ? 'exposed' : null].filter(Boolean).join(' · ');
      box.append(h('div', { class: 'row' + (c.locked ? ' locked' : '') },
        h('span', { class: 'ico' }, c.kind === 'cylinder' ? '◯' : '▭'), h('span', { class: 'nm' }, c.name), h('span', { class: 'tag ref' }, flags),
        h('button', { class: 'g' + (c.locked ? ' on lk' : ''), title: c.locked ? 'Unlock' : 'Lock', onclick: () => { const np = clone(S.project); np.pcb.components[i].locked = !c.locked; commit(np); blip(400); } }, c.locked ? '⊠' : '□'),
        h('button', { class: 'g', title: 'Remove', disabled: !!c.locked, onclick: () => { const np = clone(S.project); np.pcb.components.splice(i, 1); commit(np); blip(200, 0.08); } }, '×')));
    });
    for (const k of P.pcb.keep || []) box.append(h('div', { class: 'row' }, h('span', { class: 'ico' }, '⌖'), h('span', { class: 'nm' }, k.name), h('span', { class: 'tag' }, 'keep original')));
  }
  for (const [id, a] of Object.entries(P.assets || {})) {
    if (a.kind !== 'assembly') continue;
    box.append(h('div', { class: 'sub' }, `Assembly: ${a.name}`, h('span', { class: 'tag', style: 'margin-left:auto' }, `${a.parts.length} solids`)));
    box.append(h('div', { class: 'hint' }, 'Tag each solid. Orient the assembly first: x = length (front at −x), y = up, z = width.'));
    a.parts.forEach((p, i) => {
      const sel = h('select', { title: 'Role of this solid', 'data-part': i, onchange: (e) => { commit(setTag(S.project, id, i, e.target.value)); blip(700); } },
        TAGS.map(([v, t]) => h('option', { value: v, selected: p.tag === v }, t)));
      const sz = [0, 1, 2].map((k) => (p.bbox.max[k] - p.bbox.min[k]).toFixed(1)).join('×');
      box.append(h('div', { class: 'row' }, h('span', { class: 'nm', title: p.name }, p.name), h('span', { class: 'tag' }, sz), sel));
    });
    const n = a.parts.filter((p) => p.tag).length;
    box.append(h('div', { class: 'chips', style: 'margin-top:8px' },
      h('button', { class: 'chip', disabled: !n, id: 'gen-keepouts', onclick: () => {
        try { const r = pcbFromTags(S.project, id); commit(r.project); blip(980); say(`Generated ${r.components} keep-out${r.components === 1 ? '' : 's'}${r.keeps ? ` and ${r.keeps} kept region${r.keeps === 1 ? '' : 's'}` : ''} from the tagged solids${r.addedFeature ? '; added a PCB enclosure feature on the OCCT kernel' : ''}.`); }
        catch (e) { say('✕ ' + e.message); }
      } }, 'generate keep-outs from boxes')));
  }
}

export function renderCheckPane(box, host) {
  const { h, S } = host;
  box.textContent = '';
  const c = S.card;
  const kern = S.project.settings.kernel === 'occt' ? 'OCCT B-rep checks (exact)' : 'Manifold mesh checks (estimates)';
  box.append(h('div', { class: 'sub' }, 'Checks', h('span', { class: 'tag', style: 'margin-left:auto' }, kern)));
  box.append(h('div', { class: 'chips' }, h('button', { class: 'chip', id: 'run-checks', onclick: () => host.runCheck(true) }, c && c.busy ? 'checking…' : 'run checks')));
  if (!c || (!c.res && !c.error)) { box.append(h('div', { class: 'empty' }, c && c.busy ? 'Checking…' : 'No results yet')); return; }
  if (c.error) { box.append(h('div', { class: 'errbox' }, c.error)); return; }
  const res = c.res;
  box.append(h('div', { class: 'facts', id: 'card-verdict' }, h('div', {}, h('span', {}, 'verdict'), h('b', { class: res.passed ? 'okv' : 'warn' }, res.passed ? 'all checks pass' : `${res.failed.length} failing`))));
  for (const k of res.checks) {
    box.append(h('div', { class: 'row crow ' + (k.pass ? 'pass' : 'fail'), title: k.detail },
      h('span', { class: 'ico', style: `color:${k.pass ? '#8ff0c4' : 'var(--bad, #ff7b7b)'}` }, k.pass ? '✓' : '✕'),
      h('span', { class: 'nm' }, k.label || k.check), h('span', { class: 'tag' }, k.detail)));
  }
  const m = res.metrics || {};
  const rows = Object.entries(METRIC_LABEL).filter(([key]) => m[key] != null);
  if (m.envelope_mm) rows.push(['envelope_mm', ['envelope', 'mm']]);
  if (rows.length) {
    box.append(h('div', { class: 'sub' }, 'Metrics'));
    box.append(h('div', { class: 'facts', id: 'card-metrics' }, rows.map(([key, [lb, u]]) => h('div', {}, h('span', {}, lb), Array.isArray(m[key]) ? m[key].join(' × ') + ' ' + u : `${m[key]} ${u}`))));
  }
}
