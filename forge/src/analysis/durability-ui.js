// The durability parts of the Analyze pane (docs/EXTENSIONS_SPEC.md section 4A): the duty profile, the joints, the Risk list, and the pins
// in the viewport. Everything goes through typed ops (set_duty, set_joint, remove_joint) so it is validated, lock-checked and undoable;
// "Apply fix" commits a flag's proposal as one op list, i.e. one undo step. Same style as the rest of the editor (docs/STYLE.md): glass,
// mono uppercase labels, explanations behind `i` tips, phone-first, state paired with a word and a shape and never colour alone.
import { allMaterials, materialOf } from './materials.js';
import { DUTY_PRESETS, DUTY_NOTE, makeDuty, HUMIDITY, INSERTS, INSERT_LABEL, BOLTS, GRADES, newJoint, sizeKey } from './durability.js';
import { SEVERITIES, sortFlags, flagsToCsv, flagsToMarkdown } from './risk.js';

export const SEV_GLYPH = { high: '▲', medium: '◆', low: '●', info: '○' };
const CONF_TEXT = {
  low: 'Capped at low: the material data behind it are typical values (not measured) or estimates, or the result is experimental. A high flag with low confidence is shown but does not fail the design.',
  medium: 'Medium: an estimate model with handbook or datasheet data. Never higher while the result is an estimate.',
  high: 'High: a validated result with measured data.',
};
const FACES = ['+x', '-x', '+y', '-y', '+z', '-z'];
const r4 = (x) => (x === null || x === undefined ? 'n/a' : Math.abs(x) >= 1000 || (Math.abs(x) < 0.01 && x !== 0) ? x.toExponential(2) : String(+x.toPrecision(4)));

export function mountDurability(env, api) {
  const { h, S, tip, say, blip, view } = env;
  const st = { open: null, sel: null, pins: true, flags: [], validity: 'estimate', cardAt: null, applied: '' };
  const conds = () => S.project.analysis.conditions;
  const sel = (opts, value, fn, label, extra = {}) => h('select', { 'aria-label': label, ...extra, onchange: (e) => fn(e.target.value) }, opts.map((o) => h('option', { value: o.value ?? o, selected: (o.value ?? o) === value }, o.label ?? o)));
  const num = (v, fn, label, o = {}) => h('input', { type: 'number', step: 'any', value: v ?? '', 'aria-label': label, style: o.style || 'width:84px', min: o.min, max: o.max, ...(o.attrs || {}), onchange: (e) => { const x = parseFloat(e.target.value); if (Number.isFinite(x)) fn(x); else if (o.optional && e.target.value === '') fn(null); } });
  const fld = (label, ...ctl) => h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, label)), h('div', { class: 'xrow', style: 'margin:0' }, ...ctl));

  // ───────── duty profile
  function dutySection() {
    const d = conds().duty, sec = h('div', { class: 'asec', id: 'duty-sec' });
    const [ib, it] = tip('The duty profile says how the product is used: service life, shots, vibration, temperatures, humidity, and a safety factor on life (3 for plastics). It is saved in the project and used by the durability analyses. A preset is a placeholder with invented round numbers: replace it with measured duty before reading a result as more than a ranking.');
    sec.append(h('div', { class: 'sub' }, 'Duty profile', ib), it);
    const presets = [{ value: '', label: d ? 'Preset: (keep my numbers)' : 'Choose a preset' }, ...Object.keys(DUTY_PRESETS).map((k) => ({ value: k, label: `${makeDuty(k).label} (placeholder)` })), { value: 'none', label: 'Clear the duty profile' }];
    sec.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'preset')), sel(presets, '', (v) => { if (v === 'none') api.ops([{ op: 'set_duty', duty: null }]); else if (v) api.ops([{ op: 'set_duty', duty: makeDuty(v) }]); }, 'Duty preset', { id: 'duty-preset' })));
    if (!d) { sec.append(h('div', { class: 'hint', id: 'duty-none' }, 'No duty profile: creep, slip and the temperature swing are reported as a data gap.')); return sec; }
    const set = (path, v) => { const nd = JSON.parse(JSON.stringify(d)); let o = nd; const ks = path.split('.'); for (const k of ks.slice(0, -1)) o = o[k]; o[ks[ks.length - 1]] = v; if (path === 'shots.per_day' || path === 'life.years') nd.shots.total = Math.round(nd.shots.per_day * 365 * nd.life.years); api.ops([{ op: 'set_duty', duty: nd }]); };
    sec.append(h('div', { class: 'errbox warn', id: 'duty-flag', 'data-placeholder': String(!!d.placeholder) }, d.placeholder ? `▲ PLACEHOLDER. ${DUTY_NOTE}` : '◆ Your own numbers (not marked as a placeholder).'));
    sec.append(h('label', { class: 'lysw' }, h('input', { type: 'checkbox', checked: !d.placeholder, 'aria-label': 'These are my measured numbers', onchange: (e) => set('placeholder', !e.target.checked) }), 'these are my measured numbers'));
    sec.append(fld('life years', num(d.life.years, (v) => set('life.years', v), 'Service life in years', { min: 0.01 })), fld('hours/day', num(d.life.hours_per_day, (v) => set('life.hours_per_day', v), 'Hours of use per day', { min: 0, max: 24 })),
      fld('target years', num(d.life.target_years, (v) => set('life.target_years', v), 'Target life in years', { min: 0.01 })),
      fld('shots/day', num(d.shots.per_day, (v) => set('shots.per_day', v), 'Shots per day', { min: 0 })), fld('shots total', num(d.shots.total, (v) => set('shots.total', v), 'Total shots', { min: 0, style: 'width:100px' })),
      fld('recoil peak g', num(d.shots.peak_g, (v) => set('shots.peak_g', v), 'Recoil peak in g', { min: 0 })), fld('pulse ms', num(d.shots.pulse_ms, (v) => set('shots.pulse_ms', v), 'Recoil pulse in milliseconds, half sine', { min: 0.01 })),
      fld('temp min °C', num(d.temperature.min_C, (v) => set('temperature.min_C', v), 'Minimum temperature °C')), fld('temp max °C', num(d.temperature.max_C, (v) => set('temperature.max_C', v), 'Maximum temperature °C')),
      fld('cycles/day', num(d.temperature.cycles_per_day, (v) => set('temperature.cycles_per_day', v), 'Temperature cycles per day', { min: 0 })),
      fld('humidity', sel(HUMIDITY.map((x) => ({ value: x, label: { dry: 'dry as moulded', conditioned: 'conditioned 50 % RH', wet: 'wet' }[x] })), d.humidity, (v) => set('humidity', v), 'Humidity')),
      fld('vibration', sel(['handheld', 'vehicle', 'custom'], d.vibration.preset, (v) => { const nd = JSON.parse(JSON.stringify(d)); nd.vibration.preset = v; if (v === 'handheld' || v === 'vehicle') nd.vibration.psd = makeDuty(v === 'handheld' ? 'handheld-light' : 'vehicle').vibration.psd; api.ops([{ op: 'set_duty', duty: nd }]); }, 'Vibration preset'), num(d.vibration.hours, (v) => set('vibration.hours', v), 'Vibration exposure hours', { min: 0 }), h('span', { class: 'tag' }, 'h')),
      fld('life safety factor', num(d.safety_factor, (v) => set('safety_factor', v), 'Safety factor on life', { min: 1 })));
    return sec;
  }

  // ───────── joints
  const bodyNames = () => (S.result ? S.result.bodies.map((b) => b.name) : Object.keys(S.project.bodies || {}));
  const matIds = () => [...allMaterials(S.project).values()].filter((m) => m.class === 'metal' || m.class === 'custom').map((m) => ({ value: m.id, label: m.name }));
  function jointSummary(j) {
    const t = j.tightening, bk = j.bolt;
    return `${bk.size} ${bk.grade} × ${bk.length_mm} · ${t.mode === 'preload' ? `${t.preload_N} N` : `${t.torque_Nm} N·m`} · ${INSERT_LABEL[j.insert]}${j.thread_locker ? ' · locker' : ''}`;
  }
  function jointsSection() {
    const sec = h('div', { class: 'asec', id: 'joints-sec' }), js = S.project.joints || [];
    const [ib, it] = tip('A joint is a bolt that clamps parts together: its size, grade, length, torque or preload, friction, thread locker, what it clamps (a body of this design, or an outside part such as a steel rail) and the insert. The joint analysis uses it for the preload, creep and loosening. Changes are typed ops: validated, lock-checked, undoable.');
    sec.append(h('div', { class: 'sub' }, 'Joints', ib), it);
    if (!js.length) sec.append(h('div', { class: 'hint', id: 'joints-none' }, 'No joints yet. Add one below, or start from the PCB enclosure template, which marks its cap screws.'));
    for (const j of js) {
      const open = st.open === j.id;
      sec.append(h('div', { class: 'row' + (open ? ' sel' : ''), 'data-joint': j.id, onclick: () => { st.open = open ? null : j.id; api.rerender(); } },
        h('span', { class: 'ico' }, j.locked ? '🔒' : '⚙'), h('span', { class: 'nm' }, j.name), h('span', { class: 'tag' }, jointSummary(j))));
      if (open) sec.append(jointEditor(j));
    }
    sec.append(h('div', { class: 'xrow' }, h('button', { class: 'chip', id: 'joint-add', disabled: !bodyNames().length, onclick: () => {
      const id = api.nextId('J', js), b = bodyNames()[0] || '', bb = S.result && S.result.bodies[0] && S.result.bodies[0].bbox;
      const where = bb ? [0, 1, 2].map((i) => +((bb.min[i] + bb.max[i]) / 2).toFixed(2)) : [0, 0, 0];
      if (api.ops([{ op: 'set_joint', joint: newJoint({ id, name: `Joint ${id.slice(1)}`, where, clamps: [{ body: b, thickness_mm: 6 }, { external: 'rail', material: 'steel-carbon', thickness_mm: 3 }] }) }])) { st.open = id; api.rerender(); }
    } }, '+ add joint')));
    return sec;
  }
  function jointEditor(j) {
    const wrap = h('div', { class: 'jedit', 'data-joint-edit': j.id });
    const upd = (fn) => { const nj = JSON.parse(JSON.stringify(j)); fn(nj); api.ops([{ op: 'set_joint', joint: nj }]); };
    const t = j.tightening, bodies = bodyNames();
    wrap.append(
      fld('name', h('input', { value: j.name, 'aria-label': 'Joint name', onchange: (e) => upd((x) => { x.name = e.target.value.trim() || x.name; }) })),
      fld('where mm', ...[0, 1, 2].map((i) => num(j.where[i], (v) => upd((x) => { x.where[i] = v; }), `Joint position ${'xyz'[i]} mm`, { style: 'width:68px' }))),
      fld('axis', sel(FACES, j.axis, (v) => upd((x) => { x.axis = v; }), 'Bolt axis, head to nut')),
      fld('bolt', sel(Object.keys(BOLTS).map((k) => k.replace('_', '.')), j.bolt.size, (v) => upd((x) => { x.bolt.size = v; }), 'Bolt size'), sel(Object.keys(GRADES), j.bolt.grade, (v) => upd((x) => { x.bolt.grade = v; }), 'Bolt grade'), num(j.bolt.length_mm, (v) => upd((x) => { x.bolt.length_mm = v; }), 'Bolt length mm', { style: 'width:64px' }), h('span', { class: 'tag' }, 'mm')),
      fld('tighten', sel([{ value: 'torque:nut_factor', label: 'torque, nut factor' }, { value: 'torque:friction', label: 'torque, friction' }, { value: 'preload', label: 'preload' }], t.mode === 'preload' ? 'preload' : `torque:${t.method}`, (v) => upd((x) => {
        if (v === 'preload') x.tightening = { mode: 'preload', preload_N: x.tightening.preload_N || 1500, alpha_A: x.tightening.alpha_A };
        else { const [, m] = v.split(':'); x.tightening = { mode: 'torque', method: m, torque_Nm: x.tightening.torque_Nm || 0.9, ...(m === 'nut_factor' ? { nut_factor: x.tightening.nut_factor || 0.2 } : { mu_thread: x.tightening.mu_thread || 0.14, mu_head: x.tightening.mu_head || 0.14 }), alpha_A: x.tightening.alpha_A }; }
        if (x.tightening.alpha_A == null) delete x.tightening.alpha_A; }), 'Tightening method')));
    if (t.mode === 'preload') wrap.append(fld('preload N', num(t.preload_N, (v) => upd((x) => { x.tightening.preload_N = v; }), 'Preload in newtons', { style: 'width:90px' })));
    else {
      wrap.append(fld('torque N·m', num(t.torque_Nm, (v) => upd((x) => { x.tightening.torque_Nm = v; }), 'Tightening torque in newton metres')));
      if (t.method === 'nut_factor') wrap.append(fld('nut factor K', num(t.nut_factor, (v) => upd((x) => { x.tightening.nut_factor = v; }), 'Nut factor')));
      else wrap.append(fld('friction µ', num(t.mu_thread, (v) => upd((x) => { x.tightening.mu_thread = v; }), 'Thread friction'), num(t.mu_head, (v) => upd((x) => { x.tightening.mu_head = v; }), 'Under-head friction'), h('span', { class: 'tag' }, 'thread · head')));
    }
    wrap.append(
      fld('tightening a', num(t.alpha_A, (v) => upd((x) => { if (v == null) delete x.tightening.alpha_A; else x.tightening.alpha_A = v; }), 'Tightening factor, max over min preload', { optional: true, style: 'width:70px', attrs: { placeholder: t.mode === 'preload' ? '1' : '1.6' } })),
      fld('clamp friction µ', num(j.mu_clamp, (v) => upd((x) => { x.mu_clamp = v; }), 'Friction between the clamped parts')),
      fld('thread locker', h('input', { type: 'checkbox', checked: j.thread_locker, 'aria-label': 'Thread locker', onchange: (e) => upd((x) => { x.thread_locker = e.target.checked; }) })),
      fld('insert', sel(INSERTS.map((x) => ({ value: x, label: INSERT_LABEL[x] })), j.insert, (v) => upd((x) => { x.insert = v; if (v === 'heat_set_brass' && !x.anchor) x.anchor = { body: bodies[0] || '', length_mm: 5 }; }), 'Insert type')),
      fld('thread in', sel([{ value: 'metal', label: 'metal (nut or steel part)' }, { value: 'plastic', label: 'plastic (tapped)' }], j.thread_in || 'metal', (v) => upd((x) => { x.thread_in = v; if (v === 'plastic' && !x.anchor) x.anchor = { body: bodies[0] || '', length_mm: 6 }; }), 'What the thread screws into')));
    if (j.insert === 'heat_set_brass' || (j.thread_in || 'metal') === 'plastic') wrap.append(fld('anchor', sel(bodies.length ? bodies : [j.anchor.body], j.anchor.body, (v) => upd((x) => { x.anchor.body = v; }), 'Body holding the thread or insert'), num(j.anchor.length_mm, (v) => upd((x) => { x.anchor.length_mm = v; }), 'Insert or thread length mm', { style: 'width:64px' }), h('span', { class: 'tag' }, 'mm')));
    wrap.append(h('div', { class: 'sub' }, 'Clamps, head to nut'));
    j.clamps.forEach((c, i) => wrap.append(h('div', { class: 'xrow', 'data-clamp': i },
      c.body != null ? sel(bodies.length ? bodies : [c.body], c.body, (v) => upd((x) => { x.clamps[i].body = v; }), `Layer ${i + 1} body`)
        : h('input', { value: c.external, 'aria-label': `Layer ${i + 1} outside part`, style: 'max-width:110px;min-width:80px', onchange: (e) => upd((x) => { x.clamps[i].external = e.target.value.trim() || x.clamps[i].external; }) }),
      c.external != null ? sel(matIds(), c.material, (v) => upd((x) => { x.clamps[i].material = v; }), `Layer ${i + 1} material`) : null,
      num(c.thickness_mm, (v) => upd((x) => { x.clamps[i].thickness_mm = v; }), `Layer ${i + 1} thickness mm`, { style: 'width:64px' }), h('span', { class: 'tag' }, 'mm'),
      h('button', { class: 'g', title: 'Remove this layer', disabled: j.clamps.length < 2, onclick: () => upd((x) => { x.clamps.splice(i, 1); }) }, '×'))));
    wrap.append(h('div', { class: 'xrow' }, h('button', { class: 'chip', onclick: () => upd((x) => { x.clamps.push({ body: bodies[0] || '', thickness_mm: 3 }); }) }, '+ body layer'), h('button', { class: 'chip', onclick: () => upd((x) => { x.clamps.push({ external: 'rail', material: 'steel-carbon', thickness_mm: 3 }); }) }, '+ outside part')));
    const [ob, ot] = tip('Optional numbers. Carried mass: the total mass that rides on the joint (default: the bodies\' own mass, the electronics are not counted and that is flagged). Washer: outer diameter of a large washer under the head. Minimum clamp: the clamp load the joint must keep (default a quarter of the nominal preload, a placeholder). Embedding: your measured settling in µm (default: VDI guide values for metal interfaces).');
    wrap.append(h('div', { class: 'sub' }, 'Optional', ob), ot,
      fld('carried g', num(j.carried_mass_g, (v) => upd((x) => { if (v == null) delete x.carried_mass_g; else x.carried_mass_g = v; }), 'Carried mass in grams', { optional: true, style: 'width:80px' })),
      fld('washer Ø mm', num(j.washer && j.washer.od_mm, (v) => upd((x) => { if (v == null) delete x.washer; else x.washer = { od_mm: v }; }), 'Washer outer diameter mm', { optional: true, style: 'width:70px' })),
      fld('min clamp N', num(j.min_clamp_N, (v) => upd((x) => { if (v == null) delete x.min_clamp_N; else x.min_clamp_N = v; }), 'Minimum clamp load in newtons', { optional: true, style: 'width:80px' })),
      fld('embedding µm', num(j.embedding_um, (v) => upd((x) => { if (v == null) delete x.embedding_um; else x.embedding_um = v; }), 'Embedding in micrometres', { optional: true, style: 'width:70px' })));
    wrap.append(h('div', { class: 'xrow' },
      h('button', { class: 'chip' + (j.locked ? ' on' : ''), onclick: () => upd((x) => { if (x.locked) x.locked = false; else x.locked = true; }) }, j.locked ? '🔒 locked' : 'lock'),
      h('button', { class: 'chip', id: 'joint-remove', onclick: () => { if (api.ops([{ op: 'remove_joint', id: j.id }])) { st.open = null; api.rerender(); } } }, 'remove joint')));
    return wrap;
  }

  // ───────── risk flags: the list, the detail, the pins
  const confWord = (c) => `confidence ${c}`;
  function flagDetail(f, opts = {}) {
    const wrap = h('div', { class: 'rdetail', 'data-flag-detail': f.id });
    wrap.append(h('div', { class: 'rwhy' }, f.why));
    wrap.append(h('div', { class: 'facts' },
      ...[['mechanism', f.mechanism.replace('_', ' ')], ['severity', `${SEV_GLYPH[f.severity]} ${f.severity}`], ['metric', f.metric], ['value', r4(f.value)], ['limit', r4(f.limit)], ['margin', r4(f.margin)],
        ['confidence', f.confidence], ['where', f.where.map((v) => +v.toFixed(1)).join(', ') + ' mm'], ['body', f.body || '-']].map(([k, v]) => h('div', {}, h('span', {}, k), String(v)))));
    wrap.append(h('div', { class: 'hint', 'data-conf-why': '' }, CONF_TEXT[f.confidence]));
    wrap.append(h('div', { class: 'rfix' }, h('b', {}, 'fix '), f.fix.text));
    for (const p of f.fix.proposals) wrap.append(h('div', { class: 'xrow' }, h('button', { class: 'chip on', 'data-apply-fix': `${f.id}|${p.id}`, title: 'Applies as one undoable step', onclick: () => applyFix(f, p) }, 'Apply fix'), h('span', { class: 'tag', style: 'text-transform:none;letter-spacing:.02em;white-space:normal' }, p.label)));
    if (!f.fix.proposals.length) wrap.append(h('div', { class: 'hint' }, 'No automatic fix for this one.'));
    void opts;
    return wrap;
  }
  function applyFix(f, p) {
    const ok = api.ops(p.ops);
    if (ok) { st.applied = `${f.id}|${p.id}`; say(`Applied: ${p.label} (one undo step).`); blip(880); hideCard(); }
  }
  function riskBlock(result, item) {
    const flags = (result && result.flags) || null; if (!flags) return null;
    const wrap = h('div', { class: 'arisk', id: 'risk-pane' });
    const [ib, it] = tip('Each flag says what could fail, where, with what margin (capacity over demand: under 1 is high, under the life safety factor medium, under 10 low), how sure the tool is, and a suggested fix. High flags fail the scorecard unless their confidence is low (then they are advisory); an experimental result never decides. Confidence is capped by the quality of the material data: typical values cap it at low. Tap a pin in the viewport to see the same detail.');
    const counts = Object.fromEntries(SEVERITIES.map((s) => [s, flags.filter((f) => f.severity === s).length]));
    wrap.append(h('div', { class: 'sub' }, 'Risk', h('span', { class: 'tag', id: 'risk-counts', style: 'margin-left:8px' }, SEVERITIES.map((s) => `${SEV_GLYPH[s]} ${counts[s]} ${s}`).join('  ')), ib), it);
    wrap.append(h('div', { class: 'chips' },
      h('button', { class: 'chip' + (st.pins ? ' on' : ''), id: 'risk-pins', onclick: () => { st.pins = !st.pins; api.rerender(); drawPins(); } }, st.pins ? 'pins on' : 'pins off'),
      env.closeSheet ? h('button', { class: 'chip', id: 'risk-view', title: 'Close this panel to see the pins on the model', onclick: () => env.closeSheet() }, 'view pins') : null,
      h('button', { class: 'chip', id: 'risk-csv', onclick: () => { env.download(new Blob([flagsToCsv(flags)], { type: 'text/csv' }), `${String(S.project.name || 'design').replace(/[^\w-]+/g, '_')}_risk_flags.csv`); say('Risk flags saved as CSV.'); } }, 'export table (csv)'),
      h('button', { class: 'chip', id: 'risk-md', onclick: () => { env.download(new Blob([flagsToMarkdown(flags, { validity: result.validity })], { type: 'text/markdown' }), `${String(S.project.name || 'design').replace(/[^\w-]+/g, '_')}_risk_flags.md`); say('Risk flags saved as markdown.'); } }, 'export table (md)')));
    for (const f of sortFlags(flags)) {
      const open = st.sel === f.id;
      wrap.append(h('div', { class: 'row crow rflag ' + f.severity + (open ? ' sel' : ''), 'data-flag': f.id, 'data-severity': f.severity, onclick: () => { st.sel = open ? null : f.id; api.rerender(); } },
        h('span', { class: 'ico', title: f.severity }, SEV_GLYPH[f.severity]), h('span', { class: 'nm' }, `${f.severity} · ${f.mechanism.replace('_', ' ')}${f.body ? ' · ' + f.body : ''}`),
        h('span', { class: 'tag' }, `margin ${r4(f.margin)} · ${confWord(f.confidence)}`)));
      if (open) wrap.append(flagDetail(f));
    }
    for (const c of (result.series || [])) wrap.append(seriesBlock(c));
    void item;
    return wrap;
  }
  function seriesBlock(c) {
    // preload against time: log time axis from the first non-zero point, drawn as a polyline with the numbers beside it
    const W = 220, H = 56, xs = c.xs.map((x) => Math.log10(Math.max(x, 0.5))), x0 = xs[0], x1 = xs[xs.length - 1], y1 = Math.max(...c.ys, 1e-9);
    const pts = c.xs.map((_, i) => `${(((xs[i] - x0) / Math.max(x1 - x0, 1e-9)) * W).toFixed(1)},${(H - (c.ys[i] / y1) * H).toFixed(1)}`).join(' ');
    const svg = `<svg viewBox="0 0 ${W} ${H + 2}" width="100%" height="${H + 2}" role="img" aria-label="${c.name} against ${c.x}"><polyline fill="none" stroke="currentColor" stroke-width="1.6" points="${pts}"/></svg>`;
    const d = h('div', { class: 'rseries', 'data-series': c.name });
    d.append(h('div', { class: 'hint' }, `${c.name}: ${r4(c.ys[0])} N at assembly, ${r4(c.ys[c.ys.length - 1])} N after ${r4(c.xs[c.xs.length - 1])} h (${c.y}; time axis logarithmic)`));
    const s = h('div', { class: 'rspark', style: 'color:var(--acc)' }); s.innerHTML = svg; d.append(s);
    return d;
  }

  // pins: one per location (a joint has several flags), worst severity wins; small, a 36 px hit area, and they never sit on a panel
  let layer = null, off = null;
  const pinGroups = () => {
    const groups = new Map();
    for (const f of st.flags) { if (f.severity === 'info' && !st.flags.every((x) => x.severity === 'info')) continue; const k = f.where.map((v) => v.toFixed(2)).join(','); (groups.get(k) || groups.set(k, { where: f.where, flags: [] }).get(k)).flags.push(f); }
    return [...groups.values()].map((g) => ({ ...g, flags: sortFlags(g.flags) }));
  };
  function drawPins() {
    layer = layer || document.getElementById('pins'); if (!layer) return;
    layer.textContent = ''; if (off) { off(); off = null; }
    if (!st.pins || !st.flags.length || !view || !view.screenPoint) { hideCard(); return; }
    const els = pinGroups().map((g) => {
      const worst = g.flags[0], el = h('button', { class: `pin ${worst.severity}`, type: 'button', 'data-pin': worst.id, 'aria-label': `Risk pin: ${worst.severity} ${worst.mechanism.replace('_', ' ')}, ${g.flags.length} flag${g.flags.length === 1 ? '' : 's'}`,
        onclick: (e) => { e.stopPropagation(); showCard(g); } }, h('span', { class: 'pg', 'aria-hidden': 'true' }, SEV_GLYPH[worst.severity]));
      layer.append(el); return { el, g };
    });
    // a pin under a panel (the phone sheet, the legend, the detail card) is hidden rather than drawn on top of it: close the sheet to see it
    const place = () => {
      const covers = ['.sec.show', '#legend:not([hidden])', '#pincard:not([hidden])', 'dialog[open]'].flatMap((q) => [...document.querySelectorAll(q)]).map((e) => e.getBoundingClientRect());
      for (const { el, g } of els) {
        const p = view.screenPoint(...g.where), under = covers.some((r) => p.x > r.left - 14 && p.x < r.right + 14 && p.y > r.top - 14 && p.y < r.bottom + 14);
        const show = p.visible && !under; el.style.display = show ? '' : 'none'; el.dataset.hidden = show ? '' : (under ? 'under-panel' : 'off-screen');
        if (show) el.style.transform = `translate(${p.x.toFixed(1)}px,${p.y.toFixed(1)}px) translate(-50%,-50%)`;
      }
    };
    place(); off = view.onFrame(place);
  }
  let card = null;
  function showCard(g, i = 0) {
    card = card || document.getElementById('pincard'); if (!card) return;
    st.cardAt = g; const f = g.flags[i];
    card.textContent = ''; card.hidden = false;
    card.append(h('div', { class: 'pcnav' },
      h('b', {}, `${SEV_GLYPH[f.severity]} ${f.severity} · ${f.mechanism.replace('_', ' ')}`), h('span', { class: 'tag' }, `${i + 1} of ${g.flags.length}`),
      g.flags.length > 1 ? h('button', { class: 'g', 'aria-label': 'Next flag at this pin', onclick: () => showCard(g, (i + 1) % g.flags.length) }, '›') : null,
      h('button', { class: 'g', id: 'pincard-close', 'aria-label': 'Close', onclick: hideCard }, '×')));
    card.append(flagDetail(f));
  }
  function hideCard() { card = card || document.getElementById('pincard'); if (card) { card.hidden = true; card.textContent = ''; } st.cardAt = null; }

  return {
    dutySection, jointsSection, riskBlock,
    setResult(result) { st.flags = (result && result.flags) || []; st.validity = result ? result.validity : 'estimate'; drawPins(); },
    clear() { st.flags = []; st.sel = null; drawPins(); },
    hideCard, drawPins, state: st,
  };
}
void materialOf; void sizeKey;
