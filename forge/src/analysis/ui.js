// The Analyze pane (docs/EXTENSIONS_SPEC.md section 2.3 and 3): pick an analysis, set the loads and conditions, run it (progress bar and
// Cancel), read the summary with its validity, draw its fields in the viewport (legend and probe), edit the thresholds of its checks,
// manage materials, export a report. Same style as the rest of the editor (docs/STYLE.md): glass, mono labels, explanations behind `i`
// tips, phone-first, and state is paired with a word and a shape, never colour alone.
import { allMaterials, materialOf, PROPS, toUi, fromUi, QUALITIES, SOURCES, DEFAULT_MATERIAL } from './materials.js';
import { units, FACES, ACCEL_ASSUMPTION, G0 } from './conditions.js';
import { VALIDITY_TEXT } from './result.js';
import { resolveSetup } from './setup.js';
import { buildReport } from './report.js';
import { gridText, vectorFieldSamples } from '../ext/overlay.js';
import { writeZip } from '../ext/zip.js';
import { mountDurability } from './durability-ui.js';

const BADGE = { validated: '▮ validated', estimate: '◆ estimate', experimental: '◇ experimental' };
const slug = (s) => String(s || 'part').replace(/[^\w-]+/g, '_');

export function mountAnalyze(env) {
  const { h, S, tip, say, blip, runner, extUi, client } = env;
  const st = { sel: '', running: false, userRun: false, jobs: null, jobsError: '', jobsBusy: false, progress: 0, result: null, rows: [], error: '', ac: null, shown: new Set(), slice: { axis: 'z', frac: 0.5 }, setup: null };
  let box = null, bar = null;
  const dur = mountDurability(env, { ops: (arr) => ops(arr), nextId: (p, items) => nextId(p, items), rerender: () => render() });

  const list = () => runner.declared(S.project);
  const current = () => { const l = list(); return l.find((d) => `${d.ext}/${d.item.id}` === st.sel) || l[0] || null; };
  const ops = (arr) => { const ok = env.commitOps(arr); if (ok) { render(); } return ok; };
  const nextId = (prefix, items) => { let n = 1; while (items.some((x) => x.id === `${prefix}${n}`)) n++; return `${prefix}${n}`; };
  const conds = () => S.project.analysis.conditions;
  const needsRemote = (d) => !!(d && d.pkg.manifest.permissions.includes('compute.remote'));
  const runnerUrl = () => { const r = extUi.runner.get(); return r && r.url ? r.url : ''; };
  const bodies = () => (S.result ? S.result.bodies.map((b) => b.name) : []);

  const inputKey = () => {
    const P = S.project, c = P.analysis ? { ...P.analysis.conditions, limits: undefined } : null;
    return JSON.stringify([(S.result ? S.result.bodies : []).map((b) => [b.name, +(b.volume || 0).toFixed(6)]), c, P.analysis && P.analysis.materials, Object.entries(P.bodies || {}).map(([n, b]) => [n, b.material]), P.ext, P.joints]);
  };
  // ───────── running
  async function run() {
    const d = current(); if (!d || st.running) return;
    st.sel = `${d.ext}/${d.item.id}`; st.running = true; st.userRun = true; st.progress = 0; st.error = ''; st.ac = new AbortController(); render(); blip(660);
    if (needsRemote(d)) setTimeout(refreshJobs, 700); // the job list follows the run (list and progress only; no geometry)
    try {
      const r = await runner.run(S.project, S.result, d.ext, d.item.id, { signal: st.ac.signal, onProgress: (v) => { st.progress = v; if (bar) bar.style.width = `${Math.round(v * 100)}%`; } });
      st.result = r; st.key = inputKey(); st.setup = resolveSetup(S.project, S.result); st.rows = await runner.rows(S.project, S.result, { run: false });
      st.shown = new Set((r.fields.filter((f) => f.kind !== 'vector')[0] ? [r.fields.filter((f) => f.kind !== 'vector')[0]] : r.fields.slice(0, 1)).map((f) => f.name)); refreshOverlays(); dur.setResult(r);
      say(`${d.item.label}: done${r.cached ? ' (cached)' : ` in ${r.seconds.toFixed(1)} s`}. Validity: ${r.validity}.`); blip(980);
    } catch (e) { st.error = e.code === 'E_CONSENT' ? 'Nothing was sent: you did not confirm sending the design to the runner.' : e.code === 'E_CANCELLED' || /cancel/i.test(e.message) ? 'Cancelled.' : String(e.message || e); blip(200, 0.08); }
    st.running = false; st.userRun = false; st.ac = null; render(); if (needsRemote(d)) refreshJobs();
  }
  const cancel = () => { if (st.ac) st.ac.abort(); const dlg = env.$('#remotedlg'); if (dlg && dlg.open) dlg.close('no'); };

  // ───────── consent: geometry leaves the browser only after this dialog, and only during a run the user started
  /** Called by the host when a package is about to send a request body to the runner. Resolves true only when the user presses "Send and run" in the dialog this call opens. */
  function requestConsent(info) {
    if (!st.userRun) return Promise.resolve(false); // an AI tool, a loop or a scorecard check can never upload
    const dlg = env.$('#remotedlg'), body = env.$('#remote-body'); if (!dlg || !body) return Promise.resolve(false);
    const d = current(), bs = S.result ? S.result.bodies : [], mb = (info.bytes / 1048576).toFixed(info.bytes < 1048576 ? 2 : 1);
    body.textContent = '';
    const [tb, tt] = tip('Forge talks to no server except the ones you set up yourself. The Alloy Runner is yours: you start it, you choose where it runs, and you paste its token here. This window is the only moment the design leaves the browser for it, and only because you pressed Run.');
    body.append(
      h('p', { id: 'remote-url' }, 'This sends your design to the Alloy Runner at ', h('b', {}, info.url), tb), tt,
      h('div', { class: 'sub' }, `Sent (about ${mb} MB)`),
      h('ul', { id: 'remote-sent' }, ...bs.map((b) => h('li', {}, `${b.name}: triangle mesh, ${(b.tris || 0).toLocaleString('en')} triangles`)),
        h('li', {}, 'the material numbers of each body (stiffness, density, strengths, conductivity)'), h('li', {}, 'your mounts, loads, heat sources and ambient temperature'), h('li', {}, 'this analysis\'s settings')),
      h('div', { class: 'sub' }, 'Not sent'),
      h('ul', { id: 'remote-notsent' }, h('li', {}, 'your project file, its name, undo history or the other settings'), h('li', {}, 'parts on a PCB, reference parts, assets'), h('li', {}, 'your AI key or any other key'), h('li', {}, 'anything to any other address')),
      h('p', { class: 'hint' }, `Whoever runs that server can see the geometry. The runner deletes the job and files once Forge has downloaded the result${(((S.project.ext || {})[d && d.ext] || {}).settings || {}).keep === 'on' ? ' (you chose to keep it, so it stays there until you delete it)' : ''}.`));
    return new Promise((resolve) => {
      let answered = false; const done = (v) => { if (!answered) { answered = true; resolve(v); } };
      env.$('#remote-ok').onclick = () => { done(true); dlg.close('ok'); };
      env.$('#remote-no').onclick = () => { done(false); dlg.close('no'); };
      dlg.onclose = () => done(dlg.returnValue === 'ok'); dlg.returnValue = ''; dlg.showModal();
    });
  }

  // ───────── runner jobs (host-side calls to the runner URL only; no geometry)
  let pollTimer = null;
  async function refreshJobs() {
    const d = current(); if (!d || !needsRemote(d) || !client || !runnerUrl() || st.jobsBusy) return;
    st.jobsBusy = true;
    try { st.jobs = await client.list(); st.jobsError = ''; } catch (e) { st.jobs = null; st.jobsError = String(e.message || e); }
    st.jobsBusy = false; renderJobsOnly(); schedulePoll();
  }
  function schedulePoll() {
    clearTimeout(pollTimer); pollTimer = null;
    if (S.pane !== 'analyze' || !(st.running || (st.jobs && st.jobs.some((j) => j.state === 'queued' || j.state === 'running')))) return;
    pollTimer = setTimeout(refreshJobs, 1500);
  }
  function renderJobsOnly() { const old = box && box.querySelector('#rjobs'); if (old) old.replaceWith(jobsBlock()); }
  function jobsBlock() {
    const wrap = h('div', { class: 'ajobs', id: 'rjobs' });
    const [ib, it] = tip('Jobs on your Alloy Runner, most recent first. A finished job is deleted by the runner once its result has been downloaded (unless you chose to keep it). Cancel stops a queued or running job. Nothing here sends your design.');
    wrap.append(h('div', { class: 'sub' }, 'Runner jobs', ib, h('button', { class: 'chip', id: 'rj-refresh', style: 'margin-left:auto', onclick: refreshJobs }, 'refresh')), it);
    if (!runnerUrl()) { wrap.append(h('div', { class: 'hint' }, 'No runner is set: Settings > Extensions > Alloy Runner.')); return wrap; }
    if (st.jobsError) wrap.append(h('div', { class: 'errbox warn', id: 'rj-error' }, st.jobsError));
    else if (st.jobs && !st.jobs.length) wrap.append(h('div', { class: 'empty' }, 'No jobs on the runner'));
    for (const j of st.jobs || []) {
      const active = j.state === 'queued' || j.state === 'running', sym = { queued: '…', running: '▶', done: '✓', failed: '✕', cancelled: '◌' }[j.state] || '·';
      wrap.append(h('div', { class: 'row', 'data-job': j.id, 'data-state': j.state },
        h('span', { class: 'ico' }, sym), h('span', { class: 'nm' }, `${j.kind} · ${j.stage}`), h('span', { class: 'tag' }, `${j.state} ${Math.round((j.progress || 0) * 100)} %`),
        active ? h('button', { class: 'g', title: 'Cancel this job', 'data-job-cancel': j.id, onclick: async () => { try { await client.cancel(j.id); } catch (e) { say(String(e.message || e)); } refreshJobs(); } }, 'cancel') : h('button', { class: 'g', title: 'Delete this job from the runner', 'data-job-delete': j.id, onclick: async () => { try { await client.remove(j.id); } catch (e) { say(String(e.message || e)); } refreshJobs(); } }, '✕')));
    }
    return wrap;
  }
  function remoteBlock(d) {
    const wrap = h('div', { class: 'aremote', id: 'aremote' });
    const url = runnerUrl();
    wrap.append(h('div', { class: 'hint', id: 'remote-note' }, url ? `Runs on your Alloy Runner at ${url}. Pressing run asks you to confirm before the design is sent.` : 'This analysis runs on your own Alloy Runner. Set its address and token under Settings > Extensions > Alloy Runner; nothing is sent until you confirm.'), jobsBlock());
    return wrap;
  }

  // ───────── overlays from the result's fields
  function overlayFor(f) {
    if (f.kind === 'surface') {
      const b = S.result && S.result.bodies.find((x) => x.name === f.body); if (!b) return null;
      const m = b.mesh || b.view;
      return { kind: 'surface', label: f.name, unit: f.unit, body: f.body, values: f.data, mesh: { pos: m.pos, idx: m.idx }, validity: st.result.validity, range: null, note: f.grid ? gridText(f.grid) : null };
    }
    if (f.kind === 'voxel') {
      const n = f.grid.dims['xyz'.indexOf(st.slice.axis)];
      return { kind: 'slice', label: f.name, unit: f.unit, field: { data: f.data, grid: f.grid }, axis: st.slice.axis, index: Math.round(st.slice.frac * (n - 1)), validity: st.result.validity, range: null,
        onSlice: (axis, frac) => { st.slice = { axis, frac }; refreshOverlays(); } };
    }
    if (f.kind === 'vector') {
      const v = vectorFieldSamples({ data: f.data, grid: f.grid });
      if (!v.values.length) return null;
      return { kind: 'vectors', label: f.name, unit: f.unit, positions: v.positions, vectors: v.vectors, values: v.values, lines: null, scale: null, validity: st.result.validity, range: null };
    }
    return null;
  }
  function refreshOverlays() {
    extUi.clearOverlaysOf('analysis');
    if (!st.result) return;
    for (const f of st.result.fields) if (st.shown.has(f.name)) { const ov = overlayFor(f); if (ov) extUi.setOverlay('analysis', f.name, ov); }
  }

  // ───────── report
  async function exportReport() {
    const d = current(); if (!st.result || !d) return;
    const rows = await runner.rows(S.project, S.result, { run: false });
    const { md, json } = buildReport({ result: st.result, setup: st.setup || resolveSetup(S.project, S.result), project: S.project, analysis: d.item, rows });
    const png = Uint8Array.from(atob(env.snapshot().split(',')[1]), (c) => c.charCodeAt(0));
    const zip = await writeZip({ 'report.md': md, 'report.json': JSON.stringify(json, null, 1), 'report.png': png });
    env.download(new Blob([zip], { type: 'application/zip' }), `${slug(S.project.name)}_${slug(d.item.id)}_report.zip`); say('Report saved (PNG, JSON and markdown in one zip).');
  }

  // ───────── sections
  const num = (v, fn, o = {}) => h('input', { type: 'number', step: 'any', value: v, 'aria-label': o.label || '', style: o.style || '', onchange: (e) => { const x = parseFloat(e.target.value); if (Number.isFinite(x)) fn(x); } });
  const sel = (opts, value, fn, label) => h('select', { 'aria-label': label || '', onchange: (e) => fn(e.target.value) }, opts.map((o) => h('option', { value: o.value ?? o, selected: (o.value ?? o) === value }, o.label ?? o)));

  function analysisSection() {
    const l = list(), d = current();
    const sec = h('div', { class: 'asec' });
    const [ib, it] = tip('An analysis runs in its package\'s own Worker, so the viewport stays interactive. Results are cached in this browser and are not saved in the project; only the setup below is. Every result carries its validity, and an experimental result never decides a pass or fail.');
    sec.append(h('div', { class: 'sub' }, 'Analysis', ib), it);
    sec.append(sel(l.map((x) => ({ value: `${x.ext}/${x.item.id}`, label: `${x.item.label} · ${x.pkg.manifest.name}` })), d ? `${d.ext}/${d.item.id}` : '', (v) => { st.sel = v; st.result = null; st.rows = []; st.jobs = null; st.jobsError = ''; refreshOverlays(); render(); }, 'Analysis'));
    if (d && d.item.description) sec.append(h('div', { class: 'hint' }, d.item.description));
    if (d && (d.item.settings || []).length) sec.append(settingsBlock(d));
    sec.append(h('div', { class: 'chips' },
      st.running ? h('button', { class: 'chip', id: 'an-cancel', onclick: cancel }, 'cancel') : h('button', { class: 'chip on', id: 'an-run', disabled: !S.result || !S.result.bodies.length, onclick: run }, 'run')));
    if (st.running) { bar = h('div', { class: 'pbar-fill', style: `width:${Math.round(st.progress * 100)}%` }); sec.append(h('div', { class: 'pbar', role: 'progressbar', 'aria-label': 'Analysis progress' }, bar)); }
    if (st.error) sec.append(h('div', { class: st.error === 'Cancelled.' ? 'hint' : 'errbox', id: 'an-error' }, st.error));
    if (st.result) sec.append(resultBlock());
    if (d && needsRemote(d)) sec.append(remoteBlock(d));
    if (d) sec.append(checksBlock(d));
    return sec;
  }

  // Settings declared by the analysis (contributes.analyses[].settings), stored in the project as ext[<package id>].settings through
  // the ext_set op, so they are saved with the document, undoable, and part of the result cache key.
  function settingsBlock(d) {
    const stored = (S.project.ext && S.project.ext[d.ext] && S.project.ext[d.ext].settings) || {}, cur = (k, def) => (stored[k] !== undefined ? stored[k] : def);
    const set = (key, value) => ops([{ op: 'ext_set', id: d.ext, key: 'settings', value: { ...stored, [key]: value } }]);
    const wrap = h('div', { class: 'asettings', id: 'an-settings' });
    const [ib, it] = tip('Settings of this analysis. They are saved with the project and change the result, so a changed setting means a new run.');
    wrap.append(h('div', { class: 'sub' }, 'Settings', ib), it);
    for (const s of d.item.settings) {
      if (s.when) { const [wk, wv] = Object.entries(s.when)[0]; if (cur(wk, (d.item.settings.find((x) => x.key === wk) || {}).def) !== wv) continue; }
      const v = cur(s.key, s.def), [sb, st2] = s.tip ? tip(s.tip) : [null, null];
      const ctl = s.kind === 'enum' ? sel(s.opts, v, (x) => set(s.key, x), s.label)
        : s.kind === 'bool' ? h('input', { type: 'checkbox', checked: !!v, 'aria-label': s.label, onchange: (e) => set(s.key, e.target.checked) })
        : h('input', { type: 'number', step: 'any', value: v, min: s.min, max: s.max, 'aria-label': s.label, style: 'width:90px', onchange: (e) => { const x = parseFloat(e.target.value); if (Number.isFinite(x)) set(s.key, Math.min(s.max ?? Infinity, Math.max(s.min ?? -Infinity, x))); } });
      ctl.setAttribute('data-setting', s.key);
      wrap.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, s.label), sb), ctl, s.unit ? h('span', { class: 'tag' }, s.unit) : null));
      if (st2) wrap.append(st2);
    }
    return wrap;
  }

  function resultBlock() {
    const r = st.result, wrap = h('div', { class: 'ares', id: 'an-result' });
    const [vb, vt] = tip(VALIDITY_TEXT[r.validity]);
    wrap.append(h('div', { class: 'sub' }, 'Result', h('span', { class: `vbadge ${r.validity}`, id: 'an-validity' }, BADGE[r.validity]), vb, h('span', { class: 'tag', style: 'margin-left:auto' }, r.cached ? 'cached' : `${r.seconds.toFixed(1)} s`)), vt);
    wrap.append(h('div', { class: 'facts' }, r.summary.map((s) => h('div', { title: s.where ? `at ${s.where.map((v) => +v.toFixed(1)).join(', ')} mm` : '' }, h('span', {}, s.metric), `${+s.value.toPrecision(5)} ${s.unit}`))));
    const rk = dur.riskBlock(r, (current() || {}).item); if (rk) wrap.append(rk);
    for (const w of r.warnings) wrap.append(h('div', { class: 'errbox warn', 'data-warning': '' }, w));
    if (r.fields.length) {
      wrap.append(h('div', { class: 'sub' }, 'Fields'));
      for (const f of r.fields) {
        const on = st.shown.has(f.name), drawable = true;
        wrap.append(h('div', { class: 'row' + (on ? ' sel' : '') },
          h('span', { class: 'ico' }, f.kind === 'surface' ? '▦' : f.kind === 'voxel' ? '▤' : '➚'), h('span', { class: 'nm' }, f.name),
          h('span', { class: 'tag' }, f.kind === 'voxel' ? gridText(f.grid) : f.kind === 'surface' ? `${f.data.length} vertices${f.grid ? ' · ' + gridText(f.grid) : ''}` : 'vectors, drawn as arrows'),
          h('button', { class: 'g' + (on ? ' on' : ''), disabled: !drawable, title: on ? 'Hide in viewport' : 'Show in viewport', 'data-field': f.name, onclick: () => { on ? st.shown.delete(f.name) : st.shown.add(f.name); refreshOverlays(); render(); blip(on ? 360 : 700); } }, on ? '●' : '○')));
      }
    }
    wrap.append(h('div', { class: 'chips' }, h('button', { class: 'chip', id: 'an-report', onclick: exportReport }, 'export report')));
    return wrap;
  }

  function checksBlock(d) {
    const cs = d.item.checks || []; if (!cs.length) return h('div', {});
    const wrap = h('div', { class: 'achecks' });
    const [cb, ct] = tip('Thresholds for this analysis. A check on an experimental result is shown but never fails the design. Change a limit and run the checks in the Check pane.');
    wrap.append(h('div', { class: 'sub' }, 'Checks', cb), ct);
    for (const c of cs) {
      const key = `${d.ext}:${d.item.id}:${c.id}`, lim = conds().limits[key] ?? c.limit, row = st.rows.find((r) => r.id === key);
      wrap.append(h('div', { class: 'row crow ' + (row ? (row.pass ? 'pass' : 'fail') : ''), 'data-check': key },
        h('span', { class: 'ico' }, row ? (row.pass ? '✓' : row.severity === 'advisory' ? '△' : '✕') : '·'), h('span', { class: 'nm' }, c.label),
        h('span', { class: 'tag' }, `${c.metric} ${c.op}`), num(lim, (v) => ops([{ op: 'set_limit', key, value: v }]), { label: `${c.label} limit`, style: 'width:84px' }), h('span', { class: 'tag' }, c.unit || ''),
        row ? h('span', { class: 'tag' }, row.detail) : null));
    }
    return wrap;
  }

  function conditionsSection() {
    const c = conds(), sec = h('div', { class: 'asec' });
    const [ib, it] = tip('Loads and conditions are saved in the project and used by every analysis. Physical quantities are SI inside (K, W, N, Pa, m/s); this pane shows °C, W, N, Pa, m/s and g. Positions are millimetres in the document frame.');
    sec.append(h('div', { class: 'sub' }, 'Conditions', ib), it);
    sec.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'ambient °C')), num(+units.kToC(c.ambient.temperature_K).toFixed(2), (v) => ops([{ op: 'set_ambient', ambient: { temperature_K: units.cToK(v) } }]), { label: 'Ambient temperature °C' })));
    sec.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'air m/s')), num(c.ambient.airspeed_ms, (v) => ops([{ op: 'set_ambient', ambient: { airspeed_ms: Math.max(0, v) } }]), { label: 'Air speed m/s' }), h('span', { class: 'rv' }, c.ambient.airspeed_ms > 0 ? 'forced air' : 'still air')));
    sec.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, 'gravity')), sel(FACES, c.ambient.gravity, (v) => ops([{ op: 'set_ambient', ambient: { gravity: v } }]), 'Gravity direction')));
    // heat sources
    const pcb = ((S.project.pcb && S.project.pcb.components) || []).map((x) => x.name), bs = bodies();
    sec.append(h('div', { class: 'sub' }, 'Heat sources'));
    for (const hs of c.heat) sec.append(h('div', { class: 'row', 'data-heat': hs.id }, h('span', { class: 'ico' }, '♨'), h('span', { class: 'nm' }, hs.target.pcb != null ? `PCB ${hs.target.pcb}` : `Body ${hs.target.body}`), num(hs.power_W, (v) => ops([{ op: 'set_condition', list: 'heat', item: { ...hs, power_W: Math.max(0, v) } }]), { label: 'Power W', style: 'width:76px' }), h('span', { class: 'tag' }, 'W'), h('button', { class: 'g', title: 'Remove', onclick: () => ops([{ op: 'remove_condition', list: 'heat', id: hs.id }]) }, '✕')));
    const hTarget = h('select', { id: 'heat-target', 'aria-label': 'Heat source target' }, [...pcb.map((n) => h('option', { value: `pcb:${n}` }, `PCB ${n}`)), ...bs.map((n) => h('option', { value: `body:${n}` }, `Body ${n}`))]);
    const hW = h('input', { type: 'number', step: 'any', value: '1', id: 'heat-w', 'aria-label': 'Power W', style: 'width:76px' });
    sec.append(h('div', { class: 'xrow' }, hTarget, hW, h('button', { class: 'chip', id: 'heat-add', disabled: !hTarget.options.length, onclick: () => { const [k, ...r] = hTarget.value.split(':'); const w = parseFloat(hW.value); if (!(w >= 0)) return say('Power must be zero or more watts.'); ops([{ op: 'set_condition', list: 'heat', item: { id: nextId('h', c.heat), target: { [k]: r.join(':') }, power_W: w } }]); } }, '+ add')));
    // mounts
    sec.append(h('div', { class: 'sub' }, 'Mounts'));
    for (const m of c.mounts) sec.append(h('div', { class: 'row', 'data-mount': m.id }, h('span', { class: 'ico' }, m.kind === 'fixed' ? '⏚' : m.kind === 'slider' ? '⇔' : '≋'), h('span', { class: 'nm' }, `${m.kind} · ${m.body} ${m.face}`), h('span', { class: 'tag' }, m.kind === 'contact' ? `${m.conductance_W_m2K} W/m²K` : m.kind), h('button', { class: 'g', title: 'Remove', onclick: () => ops([{ op: 'remove_condition', list: 'mounts', id: m.id }]) }, '✕')));
    const mk = sel(['contact', 'fixed', 'slider'], 'contact', () => {}, 'Mount kind'), mb = sel(bs.length ? bs : [''], bs[0] || '', () => {}, 'Mount body'), mf = sel(FACES, '-z', () => {}, 'Mount face'), mc = h('input', { type: 'number', value: '50', step: 'any', 'aria-label': 'Contact conductance W/m²K', style: 'width:84px' });
    sec.append(h('div', { class: 'xrow' }, mk, mb, mf, mc, h('button', { class: 'chip', id: 'mount-add', disabled: !bs.length, onclick: () => ops([{ op: 'set_condition', list: 'mounts', item: { id: nextId('m', c.mounts), kind: mk.value, body: mb.value, face: mf.value, ...(mk.value === 'contact' ? { conductance_W_m2K: parseFloat(mc.value) || 0 } : {}) } }]) }, '+ add')));
    // loads
    const [lb, lt] = tip(ACCEL_ASSUMPTION + ' A drop or recoil becomes an equivalent acceleration in g; the assumption is shown with the load and in reports.');
    sec.append(h('div', { class: 'sub' }, 'Loads', lb), lt);
    for (const l of c.loads) sec.append(h('div', { class: 'row', 'data-load': l.id }, h('span', { class: 'ico' }, l.kind === 'acceleration' ? 'g' : l.kind === 'force' ? '➜' : 'P'),
      h('span', { class: 'nm' }, l.kind === 'acceleration' ? `${+units.ms2ToG(l.accel_ms2).toFixed(2)} g toward ${l.direction}` : l.kind === 'force' ? `${Math.hypot(...l.vector_N).toFixed(1)} N on ${l.body} ${l.face}` : `${l.pressure_Pa} Pa on ${l.body} ${l.face}`),
      h('span', { class: 'tag' }, l.kind === 'acceleration' ? 'quasi-static' : l.kind), h('button', { class: 'g', title: 'Remove', onclick: () => ops([{ op: 'remove_condition', list: 'loads', id: l.id }]) }, '✕')));
    const lk = sel(['acceleration', 'force', 'pressure'], 'acceleration', () => {}, 'Load kind'), lg = h('input', { type: 'number', value: '1000', step: 'any', 'aria-label': 'Acceleration g, force N or pressure Pa', style: 'width:84px' }), ld = sel(FACES, '-z', () => {}, 'Direction or face'), lbd = sel(bs.length ? bs : [''], bs[0] || '', () => {}, 'Load body');
    sec.append(h('div', { class: 'xrow' }, lk, lg, ld, lbd, h('button', { class: 'chip', id: 'load-add', onclick: () => {
      const v = parseFloat(lg.value); if (!Number.isFinite(v)) return say('Enter a number.');
      const item = lk.value === 'acceleration' ? { id: nextId('l', c.loads), kind: 'acceleration', accel_ms2: Math.abs(v) * G0, direction: ld.value }
        : lk.value === 'force' ? { id: nextId('l', c.loads), kind: 'force', body: lbd.value, face: ld.value, vector_N: ld.value.startsWith('-') ? [0, 0, 0].map((_, i) => ('xyz'[i] === ld.value[1] ? -Math.abs(v) : 0)) : [0, 0, 0].map((_, i) => ('xyz'[i] === ld.value[1] ? Math.abs(v) : 0)) }
        : { id: nextId('l', c.loads), kind: 'pressure', body: lbd.value, face: ld.value, pressure_Pa: v };
      ops([{ op: 'set_condition', list: 'loads', item }]); } }, '+ add')));
    return sec;
  }

  function materialsSection() {
    const sec = h('div', { class: 'asec' }), mats = [...allMaterials(S.project).values()];
    const [ib, it] = tip('Typical published values, not measurements of your material. Each property shows where it comes from and how good it is: datasheet, handbook or estimate. Override with your own datasheet by adding a material below. Bodies with no material are assumed to be PLA, and every result says so.');
    sec.append(h('div', { class: 'sub' }, 'Materials', ib), it);
    for (const n of bodies()) {
      const { material, assumed } = materialOf(S.project, n);
      sec.append(h('div', { class: 'fld' }, h('label', {}, h('span', { class: 'lb' }, n)),
        h('select', { 'data-body-material': n, 'aria-label': `Material of ${n}`, onchange: (e) => ops([{ op: 'set_body_material', body: n, material: e.target.value || null }]) },
          [h('option', { value: '' }, `(not set: ${material.name} assumed)`), ...mats.map((m) => h('option', { value: m.id, selected: !assumed && m.id === material.id }, m.name))]), assumed ? h('span', { class: 'tag' }, 'assumed') : null));
    }
    // properties of the material of the selected body
    const selBody = S.selBody && bodies().includes(S.selBody) ? S.selBody : bodies()[0];
    if (selBody) {
      const { material } = materialOf(S.project, selBody);
      sec.append(h('details', { class: 'xlog' }, h('summary', {}, `${material.name}: properties and sources`),
        h('div', { class: 'facts' }, PROPS.map((p) => { const v = material.properties[p.k]; return h('div', { title: (v.sources || []).map((s) => (SOURCES[s] ? SOURCES[s].citation : s)).join('; ') + (v.note ? ' — ' + v.note : '') }, h('span', {}, p.label), v.value == null ? 'n/a' : `${toUi(p, v.value)} ${p.ui} · ${v.quality}${v.range ? ` (${toUi(p, v.range[0])} to ${toUi(p, v.range[1])})` : ''}`); })),
        h('div', { class: 'hint' }, material.notes || '')));
    }
    // custom materials
    for (const m of S.project.analysis.materials) sec.append(h('div', { class: 'row', 'data-material': m.id }, h('span', { class: 'ico' }, '◆'), h('span', { class: 'nm' }, m.name), h('span', { class: 'tag' }, 'yours'), h('button', { class: 'g', title: 'Remove', onclick: () => ops([{ op: 'remove_material', id: m.id }]) }, '✕')));
    const form = h('details', { class: 'xlog' }, h('summary', {}, '+ add your own material')), name = h('input', { placeholder: 'name', 'aria-label': 'Material name', id: 'mat-name' });
    const inputs = PROPS.map((p) => h('label', { class: 'matf' }, h('span', { class: 'lb' }, `${p.label}${p.ui ? ' · ' + p.ui : ''}`), h('input', { type: 'number', step: 'any', 'data-prop': p.k, placeholder: p.k })));
    const qsel = sel(QUALITIES.filter((q) => q !== 'n/a'), 'measured', () => {}, 'Quality of the values');
    form.append(name, h('div', { class: 'matgrid' }, inputs), h('div', { class: 'xrow' }, qsel, h('button', { class: 'chip', id: 'mat-add', onclick: () => {
      const id = slug(name.value.trim().toLowerCase()).replace(/_/g, '-').slice(0, 32);
      const props = {};
      for (const p of PROPS) { const el = form.querySelector(`[data-prop="${p.k}"]`), v = el.value === '' ? null : fromUi(p, parseFloat(el.value)); props[p.k] = v == null ? { value: null, quality: 'n/a', sources: [] } : { value: v, quality: qsel.value, sources: ['user'] }; }
      ops([{ op: 'set_material', material: { id, name: name.value.trim(), class: 'custom', notes: 'Entered by the user.', properties: props } }]);
    } }, 'save')));
    sec.append(form);
    return sec;
  }

  function render(target) {
    if (target) box = target;
    if (!box) return;
    bar = null; box.textContent = '';
    if (!list().length) { box.append(h('div', { class: 'empty' }, 'No analyses installed. Install one in Settings > Extensions.')); return; }
    box.append(analysisSection(), conditionsSection(), dur.dutySection(), dur.jointsSection(), materialsSection());
    const cd = current(); if (cd && needsRemote(cd) && client && runnerUrl() && st.jobs === null && !st.jobsError && !st.jobsBusy) setTimeout(refreshJobs, 0);
  }

  return {
    render, refreshTabs() { for (const id of ['t-an', 'n-an']) { const b = env.$('#' + id); if (b) b.hidden = !list().length; } if (!list().length && S.pane === 'analyze') S.pane = 'tree'; },
    // A result stays on screen while the setup behind it is unchanged (a threshold edit rebuilds the document but not the inputs).
    invalidate() { if (st.running) return; const k = inputKey(); if (st.result && k === st.key) return; st.result = null; st.rows = []; extUi.clearOverlaysOf('analysis'); dur.clear(); },
    state: st, run, cancel, requestConsent, refreshJobs,
  };
}
