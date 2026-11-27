// DOM for extensions: Settings > Extensions (install, consent prompt, per-project switch, remove, runner), the Ext pane that
// hosts package panes, and the overlay legend and probe. Same style as the rest of the editor (docs/STYLE.md): glass, mono
// labels, explanations behind `i` tips, bottom sheets on phones, state paired with a word and not colour alone.
import { loadPackage } from './loader.js';
import { describeInstall } from './manifest.js';
import { enabledIn, projectEntry } from './registry.js';
import { rangeOf, legendTicks, cssGradient, gridText, sliceCount, vectorScale } from './overlay.js';
import { adapterLine } from '../analysis/runner-client.js';
import { createLayerStore, groupLayers, layerReadout } from './layers.js';

const RUNNER_KEY = 'af.runner';
const SAMPLES = [['Rib (plugin)', 'ext-samples/sample-rib.alloyext'], ['Height probe (extension)', 'ext-samples/sample-probe.alloyext'], ['Mass properties (analysis)', 'ext-samples/sample-mass.alloyext'], ['Thermal estimate (analysis)', 'ext-samples/thermal-estimate.alloyext'], ['Thermal 3D (analysis, GPU)', 'ext-samples/thermal-3d.alloyext'], ['Structural (analysis, GPU)', 'ext-samples/structural.alloyext'], ['Alloy Runner (analysis, remote)', 'ext-samples/runner.alloyext']];

export function mountExtensions(env) {
  const { h, $, tip, S, registry, host, resolver, panes, view, say, blip, setExtension, rebuild, showSheet } = env;
  const overlays = new Map(), layers = env.layerStore || createLayerStore();
  let busy = '';

  // ───────── runner settings (A4 uses them; the host adds the token, packages never see it)
  const runner = {
    get() { try { return JSON.parse(localStorage.getItem(RUNNER_KEY) || 'null') || { url: '', token: '' }; } catch (_) { return { url: '', token: '' }; } },
    set(v) { try { localStorage.setItem(RUNNER_KEY, JSON.stringify(v)); } catch (_) {} },
  };
  const validRunnerUrl = (u) => { try { const x = new URL(u); return x.protocol === 'https:' || (x.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(x.hostname)); } catch (_) { return false; } };

  // ───────── install flow
  async function consent(pkg, replaced) {
    const d = describeInstall(pkg.manifest), dlg = $('#extconsent'), body = $('#extc-body'); body.textContent = '';
    const [tb, tt] = tip('Plugins read or edit your design through typed ops, like the AI does. Extensions bring their own engine or interface: a pane, an overlay, your GPU, a network host. A package can only do what it lists here; Forge checks every call.');
    body.append(
      h('div', { class: 'xhead' }, h('b', {}, pkg.manifest.name), h('span', { class: 'tier ' + d.tier }, d.tier === 'plugin' ? '▮ plugin' : '◆ extension'), tb),
      tt, h('div', { class: 'xsub' }, `${pkg.manifest.id} · v${pkg.manifest.version}${pkg.manifest.author ? ' · ' + pkg.manifest.author : ''}${replaced ? ' · replaces v' + replaced : ''}`),
      h('div', { class: 'xline' }, d.line),
      pkg.manifest.description ? h('div', { class: 'xdesc' }, pkg.manifest.description) : null,
      h('div', { class: 'sub' }, 'Permissions'),
      d.perms.length ? h('ul', { class: 'xperms' }, d.perms.map((p) => h('li', {}, h('code', {}, p.id), ' ', p.text))) : h('div', { class: 'xdesc' }, 'None. It only computes.'),
      h('div', { class: 'xdesc dim' }, `Licence ${pkg.manifest.licence}. Package checked: ${pkg.manifest.integrity.slice(0, 18)}… (detects edits; it is not a signature).`));
    return new Promise((res) => { $('#extc-ok').onclick = () => { dlg.close('ok'); }; $('#extc-no').onclick = () => dlg.close('no'); dlg.onclose = () => res(dlg.returnValue === 'ok'); dlg.returnValue = ''; dlg.showModal(); });
  }

  async function installBytes(bytes, label) {
    busy = 'checking ' + label + '…'; renderList();
    try {
      const pkg = await loadPackage(bytes);
      const prev = registry.get(pkg.manifest.id);
      busy = ''; renderList();
      if (!(await consent(pkg, prev ? prev.manifest.version : null))) { say('Install cancelled.'); return; }
      host.stop(pkg.manifest.id); panes.drop(pkg.manifest.id); resolver.clear();
      await registry.install(bytes);
      setExtension({ op: 'set_extension', id: pkg.manifest.id, name: pkg.manifest.name, version: pkg.manifest.version, enabled: true, checks: ((pkg.manifest.contributes || {}).checks || []).map((c) => c.id) });
      blip(980); say(`${pkg.manifest.name} installed and switched on for this project.`);
    } catch (e) { say('✕ ' + String(e.message || e).split('\n')[0]); env.lastError = String(e.message || e); busy = ''; }
    refreshAll();
  }

  async function installFromUrl(url) {
    let u; try { u = new URL(url, location.href); } catch (_) { return say('✕ That is not a valid address.'); }
    busy = 'downloading…'; renderList();
    try {
      const r = await fetch(u.href, { credentials: 'omit', redirect: 'follow', referrerPolicy: 'no-referrer' });
      if (!r.ok) throw new Error(`the server answered ${r.status}`);
      await installBytes(new Uint8Array(await r.arrayBuffer()), u.pathname.split('/').pop() || 'package');
    } catch (e) { busy = ''; say('✕ Could not fetch the package: ' + (e.message || e)); renderList(); }
  }

  // ───────── the Extensions dialog
  function renderList() {
    const box = $('#extbody'); if (!box) return; box.textContent = '';
    const [ib, it] = tip('A package is a .alloyext file. Installing asks you first and shows every permission. Packages are stored in this browser only. Switching one off in a project keeps what it made; removing it never breaks a saved project.');
    box.append(h('div', { class: 'sub' }, 'Install', ib), it);
    const file = h('input', { type: 'file', accept: '.alloyext,application/zip', hidden: true, id: 'extfile', onchange: (e) => { const f = e.target.files[0]; e.target.value = ''; if (f) f.arrayBuffer().then((b) => installBytes(new Uint8Array(b), f.name)); } });
    const url = h('input', { placeholder: 'https://…/package.alloyext', spellcheck: 'false', id: 'exturl', 'aria-label': 'Package address' });
    const [ub, ut] = tip('Fetching a package from an address contacts that address once, from your browser. Nothing is sent but the request. Prefer a file you trust.');
    box.append(h('div', { class: 'xrow' }, file, h('button', { class: 'chip', id: 'extpick', onclick: () => file.click() }, 'choose file'),
      ...SAMPLES.map(([t, p]) => h('button', { class: 'chip', 'data-sample': p.split('/').pop(), onclick: () => installFromUrl(p) }, 'sample · ' + t))),
      h('div', { class: 'xrow' }, url, h('button', { class: 'chip', onclick: () => url.value.trim() && installFromUrl(url.value.trim()) }, 'fetch'), ub), ut);
    if (busy) box.append(h('div', { class: 'hint' }, busy));
    if (env.lastError) box.append(h('div', { class: 'errbox', id: 'exterr' }, env.lastError));
    for (const e of registry.loadErrors) box.append(h('div', { class: 'errbox' }, `Stored package ${e.id} was not loaded: ${e.error}`));

    box.append(h('div', { class: 'sub' }, 'Installed', h('span', { class: 'tag', style: 'margin-left:auto' }, registry.list().length + ' in this browser')));
    if (!registry.list().length) box.append(h('div', { class: 'empty' }, 'Nothing installed'));
    for (const pkg of registry.list()) box.append(packageRow(pkg));
    // packages this project used that are not installed
    for (const e of S.project.extensions || []) if (!registry.get(e.id)) box.append(h('div', { class: 'xpkg missing' }, h('div', { class: 'xhead' }, h('b', {}, e.name || e.id), h('span', { class: 'tier' }, 'needs install')), h('div', { class: 'xsub' }, `${e.id}${e.version ? ' · v' + e.version : ''} · used by this project, not installed. Its features are kept and skipped in builds.`)));

    const r = runner.get();
    const [rb, rt] = tip('The Alloy Runner is an optional server you run yourself for heavy analysis. Packages that need it reach only this address, and Forge adds the token; packages never see it. The token stays in this browser.');
    const ru = h('input', { value: r.url, placeholder: 'https://runner.example.org', spellcheck: 'false', id: 'runner-url', 'aria-label': 'Runner URL' });
    const rk = h('input', { value: r.token, type: 'password', autocomplete: 'off', spellcheck: 'false', id: 'runner-token', 'aria-label': 'Runner token' });
    const save = () => { const v = ru.value.trim(); if (v && !validRunnerUrl(v)) { say('✕ The runner address must be https (or http on localhost).'); return; } runner.set({ url: v.replace(/\/+$/, ''), token: rk.value.trim() }); say(v ? 'Runner saved.' : 'Runner cleared.'); blip(700); };
    const status = h('div', { class: 'hint', id: 'runner-status', role: 'status' });
    const test = async () => {
      save(); status.textContent = 'Testing…'; status.className = 'hint';
      try {
        const r = await env.client.test();
        const lines = [`${r.name} ${r.version}, protocol ${r.protocol}. ${r.authenticated ? 'The token is accepted.' : `The token is NOT accepted${r.authError ? ': ' + r.authError : ''}.`}`];
        if (r.adapters) for (const [id, a] of Object.entries(r.adapters)) lines.push(adapterLine(id, a));
        if (r.authenticated) lines.push(r.qualification ? `Qualification file: ${r.qualification.passed ? 'the operator attests the reference cases' : 'present, but does not attest them'}.` : 'No qualification file: results can be graded estimate at most.');
        status.className = r.authenticated ? 'hint ok' : 'errbox warn'; status.textContent = ''; status.append(...lines.flatMap((l, i) => (i ? [h('br'), l] : [l])));
      } catch (e) { status.className = 'errbox warn'; status.textContent = String(e.message || e); }
    };
    box.append(h('div', { class: 'sub' }, 'Alloy Runner', rb), rt, h('div', { class: 'xrow' }, ru), h('div', { class: 'xrow' }, rk, h('button', { class: 'chip', id: 'runner-save', onclick: save }, 'save'), h('button', { class: 'chip', id: 'runner-test', disabled: !env.client, onclick: test }, 'test')), status);
  }

  function packageRow(pkg) {
    const m = pkg.manifest, on = enabledIn(S.project, m.id), ent = projectEntry(S.project, m.id), d = describeInstall(m);
    const [ib, it] = tip(d.line + '. ' + d.perms.map((p) => p.id + ': ' + p.text).join('; '));
    const contrib = Object.entries(m.contributes || {}).filter(([k, v]) => Array.isArray(v) && v.length).map(([k, v]) => `${v.length} ${k}`);
    const logs = host.logOf(m.id).slice(-5);
    return h('div', { class: 'xpkg' + (on ? ' on' : ''), 'data-ext': m.id },
      h('div', { class: 'xhead' }, h('b', {}, m.name), h('span', { class: 'tier ' + d.tier }, d.tier === 'plugin' ? '▮ plugin' : '◆ extension'), ib,
        h('span', { class: 'xact' },
          h('button', { class: 'chip' + (on ? ' on' : ''), 'data-act': 'toggle', title: on ? 'Switch off in this project' : 'Switch on in this project', onclick: () => { if (on) clearOverlaysOf(m.id); setExtension({ op: 'set_extension', id: m.id, name: m.name, version: m.version, enabled: !on, checks: ((m.contributes || {}).checks || []).map((c) => c.id) }); blip(on ? 360 : 700); refreshAll(); } }, on ? '● on in project' : '○ off in project'),
          h('button', { class: 'chip', 'data-act': 'remove', onclick: () => removePkg(pkg) }, 'remove'))), it,
      h('div', { class: 'xsub' }, `${m.id} · v${m.version}${contrib.length ? ' · ' + contrib.join(', ') : ''}`),
      h('div', { class: 'xperm-chips' }, d.perms.length ? d.perms.map((p) => h('span', { class: 'tag' }, p.id)) : h('span', { class: 'tag' }, 'no permissions')),
      ent && ent.enabled === false ? h('div', { class: 'xdesc' }, 'Switched off in this project: its features are skipped and its checks do not run.') : null,
      logs.length ? h('details', { class: 'xlog' }, h('summary', {}, 'console'), h('pre', {}, logs.map((l) => `${l.level}: ${l.text}`).join('\n'))) : null);
  }

  async function removePkg(pkg) {
    const id = pkg.manifest.id;
    if (!confirm(`Remove "${pkg.manifest.name}" from this browser?\n\nProjects that use it keep its features, marked "needs ${pkg.manifest.name}", and skip them in builds. Installing it again restores them.`)) return;
    host.stop(id); panes.drop(id); clearOverlaysOf(id); resolver.clear();
    await registry.remove(id);
    blip(300); say(`${pkg.manifest.name} removed. Projects that used it keep their features.`);
    refreshAll(); rebuild();
  }

  // ───────── Ext pane: panes of the packages switched on in this project
  function paneList() {
    const out = [];
    for (const { ext, item } of registry.contributions('panes')) if (enabledIn(S.project, ext)) out.push({ ext, pane: item, name: registry.get(ext).manifest.name });
    return out;
  }
  const paneRoot = h('div', { class: 'extpanes' });
  function renderExtPane(box) {
    box.textContent = '';
    const list = paneList();
    box.append(h('div', { class: 'sub' }, 'Extensions', h('button', { class: 'lnk', onclick: openDialog }, 'manage')));
    paneRoot.textContent = '';
    if (!list.length) paneRoot.append(h('div', { class: 'empty' }, 'No extension panes in this project'));
    for (const { ext, pane, name } of list) {
      const wrap = h('div', { class: 'extpane', 'data-pane-id': `${ext}/${pane.id}` }, h('div', { class: 'tag' }, `${pane.label} · ${name}`));
      const slot = h('div', { class: 'extslot' }); wrap.append(slot); paneRoot.append(wrap);
      panes.mount(ext, pane, slot);
    }
    box.append(paneRoot);
  }
  function refreshTabs() {
    const has = paneList().length > 0;
    for (const id of ['t-ext', 'n-ext']) { const b = $('#' + id); if (b) b.hidden = !has; }
    if (!has && S.pane === 'ext') S.pane = 'tree';
  }

  // ───────── overlays: legend + probe
  function setOverlay(extId, id, ov) {
    const key = `${extId}:${id}`;
    if (ov) { overlays.set(key, { key, extId, id, ov, range: rangeOf(ov), name: registry.get(extId)?.manifest.name || extId }); } else overlays.delete(key);
    applyOverlays();
  }
  function clearOverlaysOf(extId) { for (const k of [...overlays.keys()]) if (overlays.get(k).extId === extId) overlays.delete(k); applyOverlays(); }
  function applyOverlays(keepSheet) {
    const shown = [...overlays.values()].filter((o) => layers.isOn(o.key)).map((o) => ({ ...o, opacity: layers.opacity(o.key) }));
    view.setOverlays(shown);
    const lg = $('#legend'); lg.textContent = ''; lg.hidden = !overlays.size;
    for (const o of overlays.values()) {
      const hid = !layers.isOn(o.key), ov = o.ov;
      const row = h('div', { class: 'lg' + (hid ? ' off' : ''), 'data-overlay': o.key },
        h('div', { class: 'lgh' }, h('b', {}, ov.label), h('span', { class: 'tag' }, ov.validity), h('span', { class: 'tag' }, o.name),
          h('button', { class: 'g', title: hid ? 'Show overlay' : 'Hide overlay', onclick: () => { layers.set(o.key, { on: hid }); applyOverlays(); } }, hid ? '○' : '●'),
          h('button', { class: 'g', title: 'Remove overlay', onclick: () => { overlays.delete(o.key); applyOverlays(); } }, '✕')));
      if (ov.kind === 'points' || ov.kind === 'vectors' || ov.kind === 'surface' || ov.kind === 'slice') {
        row.append(h('div', { class: 'lgbar', style: `background:${cssGradient()}` }), h('div', { class: 'lgt' }, legendTicks(o.range).map((t) => h('span', {}, String(t)))));
        const what = ov.kind === 'points' ? `${ov.values.length} points · tap a point to read it` : ov.kind === 'vectors' ? `${ov.values.length} samples drawn as ${ov.lines ? 'streamlines' : `arrows, longest ${+vectorScale(ov).maxLen.toFixed(1)} mm for the top of the range`} · colour is the speed · tap one to read it` : ov.kind === 'surface' ? `one value per vertex on ${ov.body} · tap the surface to read it` : `${gridText(ov.field.grid)} · tap the slice to read it`;
        row.append(h('div', { class: 'tag' }, `${ov.unit ? 'unit ' + ov.unit + ' · ' : ''}${what}${ov.note ? ' · ' + ov.note : ''}`));
        if (ov.kind === 'slice' && ov.onSlice) {
          const n = sliceCount(ov.field, ov.axis);
          row.append(h('div', { class: 'lgslice' },
            h('select', { 'aria-label': 'Slice axis', onchange: (e) => ov.onSlice(e.target.value, 0.5) }, ['x', 'y', 'z'].map((a) => h('option', { value: a, selected: a === ov.axis }, a.toUpperCase()))),
            h('input', { type: 'range', min: 0, max: n - 1, value: ov.index, 'aria-label': 'Slice position', oninput: (e) => ov.onSlice(ov.axis, +e.target.value / Math.max(1, n - 1)) }),
            h('span', { class: 'tag' }, `${ov.index + 1} / ${n}`)));
        }
      } else row.append(h('div', { class: 'tag' }, `box ${ov.min.map((v) => +v.toFixed(1)).join(',')} to ${ov.max.map((v) => +v.toFixed(1)).join(',')} mm`));
      lg.append(row);
    }
    if (!keepSheet) renderLayers();
  }

  // ───────── Layers sheet: every overlay, grouped by analysis or extension, with on/off, opacity, solo and the unit and range.
  // Non-modal, so the viewport stays live while you change a layer. State is kept per layer id in this browser (ext/layers.js).
  const lyDlg = () => $('#layersdlg');
  function renderLayers() {
    const btn = $('#layers-btn'), all = [...overlays.values()];
    if (btn) { const n = all.filter((o) => layers.isOn(o.key)).length; btn.textContent = all.length ? `Layers ${n}/${all.length}` : 'Layers'; btn.classList.toggle('on', lyDlg().open); }
    const dlg = lyDlg(); if (!dlg.open) return;
    const body = $('#layersbody'), ids = all.map((o) => o.key), top = body.scrollTop; body.textContent = '';
    const [tb, tt] = tip('A layer is one thing an analysis or extension draws in the viewport. Switch it off to hide it, or lower its opacity to see the model through it. Solo shows only that layer; tap Solo again to bring the others back. The colour bar next to a name is the colour map for that layer: the numbers are in the readout below it. Your choices are remembered in this browser, not in the project, and they survive re-running an analysis.');
    const sw = (on, label, act) => h('button', { class: 'chip', type: 'button', 'data-act': act, 'aria-pressed': String(on), onclick: () => { ({ all: () => layers.showAll(ids), none: () => layers.hideAll(ids) })[act](); applyOverlays(); blip(on ? 700 : 360, 0.03); } }, label);
    body.append(h('div', { class: 'lyhead' }, sw(true, 'Show all', 'all'), sw(false, 'Hide all', 'none'), h('button', { class: 'chip', type: 'button', 'data-act': 'close', onclick: () => { dlg.close(); renderLayers(); } }, 'Close'), tb), tt);
    if (!all.length) { body.append(h('div', { class: 'lyempty' }, 'No overlay layers yet. Run an analysis, or switch on an extension that draws in the viewport, and its layers appear here.')); return; }
    const solo = layers.soloed(ids);
    for (const g of groupLayers(all)) {
      body.append(h('div', { class: 'lygrp' }, g.name));
      for (const o of g.layers) {
        const st = layers.get(o.key), ov = o.ov, hasMap = ov.kind !== 'box';
        const id = 'lyop-' + o.key.replace(/[^a-z0-9]/gi, '_');
        body.append(h('div', { class: 'ly' + (st.on ? '' : ' off'), 'data-layer': o.key },
          h('div', { class: 'lyrow' },
            hasMap ? h('span', { class: 'lysw1', style: `background:${cssGradient()}`, title: 'Colour map of this layer' }) : null,
            h('b', {}, ov.label), h('span', { class: 'tag' }, ov.validity),
            h('label', { class: 'lysw' }, h('input', { type: 'checkbox', role: 'switch', 'data-act': 'switch', checked: st.on, 'aria-label': `Show ${ov.label}`, onchange: (e) => { layers.set(o.key, { on: e.target.checked }); applyOverlays(); blip(e.target.checked ? 700 : 360, 0.03); } }), st.on ? 'ON' : 'OFF'),
            h('button', { class: 'chip', type: 'button', 'data-act': 'solo', 'aria-pressed': String(solo === o.key), onclick: () => { layers.solo(ids, o.key); applyOverlays(); blip(600, 0.03); } }, solo === o.key ? 'Solo on' : 'Solo')),
          h('div', { class: 'lyop' }, h('span', { class: 'tag' }, 'Opacity'),
            h('input', { type: 'range', id, min: 5, max: 100, step: 5, value: Math.round(st.opacity * 100), 'data-act': 'opacity', 'aria-label': `Opacity of ${ov.label}`, oninput: (e) => { layers.set(o.key, { opacity: +e.target.value / 100 }); e.target.nextSibling.textContent = e.target.value + '%'; applyOverlays(true); } }),
            h('span', { class: 'tag', 'data-read': 'opacity' }, Math.round(st.opacity * 100) + '%')),
          h('div', { class: 'tag lyread' }, layerReadout(o))));
      }
    }
    body.scrollTop = top;
  }
  function toggleLayers(force) { const d = lyDlg(), want = force ?? !d.open; if (want && !d.open) d.show(); else if (!want && d.open) d.close(); renderLayers(); }
  $('#layers-btn').onclick = () => { toggleLayers(); blip(520, 0.03); };
  lyDlg().addEventListener('close', renderLayers);
  let probeT = 0;
  function showProbe(p) {
    const el = $('#probe'); el.textContent = `${p.label}  ${+p.value.toFixed(3)}${p.unit ? ' ' + p.unit : ''}${p.vector ? '  ·  vector ' + p.vector.map((v) => +v.toFixed(3)).join(', ') : ''}  ·  ${p.validity}  ·  at ${p.position.map((v) => +v.toFixed(1)).join(', ')} mm`; el.hidden = false;
    clearTimeout(probeT); probeT = setTimeout(() => { el.hidden = true; }, 5000); blip(820, 0.03);
  }

  function openDialog() { env.lastError = ''; renderList(); const d = $('#extdlg'); if (!d.open) d.showModal(); }
  function refreshAll() { renderList(); refreshTabs(); if (S.pane === 'ext') env.renderPanes(); env.refreshAddMenu(); }

  $('#extclose').onclick = () => $('#extdlg').close();
  $('#s-ext').onclick = () => { $('#settings').close(); openDialog(); };

  return {
    renderExtPane, refreshTabs, refreshAll, openDialog, setOverlay, clearOverlaysOf, showProbe, runner,
    onPane(m) {
      if (m.kind === 'open') { if (!paneList().some((p) => p.ext === m.extId && p.pane.id === m.id)) return; S.pane = 'ext'; env.renderPanes(); showSheet('ext'); }
      else if (m.kind === 'post') panes.post(m.extId, m.id, m.message);
    },
    overlayCount: () => overlays.size, layers, toggleLayers,
  };
}
