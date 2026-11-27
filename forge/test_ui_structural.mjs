// Browser smoke test for A3 (Structural) with WebGPU. Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_structural.mjs
// Chromium is started with the software WebGPU flags (SwiftShader), which is what a headless machine has. The test
//   1. runs the elastic conjugate gradient of the shared voxel core on the GPU against its CPU twin on the same system and requires agreement,
//   2. installs the package (consent shows compute.gpu and the extension tier), adds a fixed mount and an acceleration load in the Analyze pane,
//      sets the settings, and shows that an unsupported body is refused with a message,
//   3. runs the analysis on the GPU inside the package Worker and again on the CPU with the same voxel size and requires the two answers to
//      agree, reads the voxel size and the grid limits off the result and the legend, draws the stress map, lists the layers, probes it,
//   4. cancels a run, and repeats the flow on a 390 px phone viewport (no sideways scroll, touch targets, phone grid budget).
// If this machine has no WebGPU at all the GPU assertions are skipped and the output says so.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { coreScript } from './tools/bundle-ext.mjs';
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright');

const dist = path.resolve('dist');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.alloyext': 'application/zip' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const url = `http://localhost:${srv.address().port}/`;
const STR = 'xyz.mdhv.structural';
const FLAGS = ['--no-sandbox', '--enable-unsafe-webgpu', '--use-angle=swiftshader', '--enable-features=Vulkan', '--ignore-gpu-blocklist'];
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: FLAGS });
const errors = [], nets = [];
const newPage = async (ctx) => {
  const page = await ctx.newPage();
  // the viewport renders continuously with a software WebGL, which would starve the software WebGPU the test runs on: slow the loop to 2 fps
  await page.addInitScript(() => { const raf = window.requestAnimationFrame.bind(window); window.requestAnimationFrame = (cb) => setTimeout(() => raf(cb), 500); });
  page.on('pageerror', (e) => errors.push(e.message)); page.on('dialog', (d) => d.accept());
  page.on('request', (r) => { const u = new URL(r.url()); if (u.origin !== new URL(url).origin && !/^(data|blob):/.test(r.url())) nets.push(r.url()); });
  await page.goto(url); await page.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 }); return page;
};
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
const page = await newPage(ctx);

// ───── 1. the core's elastic GPU conjugate gradient against its CPU twin
const gpuOk = await page.evaluate(async () => !!(navigator.gpu && await navigator.gpu.requestAdapter()));
if (!gpuOk) console.log('NOTE: this browser has no WebGPU; the GPU assertions are skipped.');
else {
  const r = await page.evaluate(async (src) => {
    const VC = (0, eval)(src), gpu = await VC.gpuElasticBackend(); if (!gpu) return { err: VC.gpuElasticBackend.lastError };
    // a 14 x 8 x 6 block of one material with a hole (two materials), fixed at x = 0, loaded on the far face and by a body force
    const grid = { origin: [0, 0, 0], h: 1, dims: [14, 8, 6], total: 14 * 8 * 6 }, mat = new Uint8Array(grid.total).fill(1);
    for (let k = 0; k < 6; k++) for (let j = 0; j < 8; j++) for (let i = 0; i < 14; i++) { const v = i + 14 * (j + 8 * k); if (i > 5 && i < 9 && j > 2 && j < 5) mat[v] = 0; else if (i > 9) mat[v] = 2; }
    const st = VC.buildElastic(grid, mat, (m) => m !== 0), nd = 3 * st.nn, Ks = [VC.hexMatrices(0.3).K, VC.hexMatrices(0.4).K], mIdx = new Uint8Array(st.ne), scale = new Float64Array(st.ne);
    for (let e = 0; e < st.ne; e++) { const m = mat[st.elemCell[e]]; mIdx[e] = m - 1; scale[e] = (m === 1 ? 2e5 : 7e4) * grid.h; }
    const held = new Uint8Array(nd), F = new Float64Array(nd);
    for (let q = 0; q < st.nn; q++) { if (st.nodeIJK[3 * q] === 0) for (let c = 0; c < 3; c++) held[3 * q + c] = 1; if (st.nodeIJK[3 * q] === 14) F[3 * q + 2] -= 1; F[3 * q + 1] += 0.01; }
    const diag = VC.elasticDiagonal({ ne: st.ne, nn: st.nn, elemNodes: st.elemNodes, scale, mIdx, Ks }), inv = new Float64Array(nd); for (let q = 0; q < nd; q++) inv[q] = held[q] ? 0 : 1 / diag[q];
    const b = Float64Array.from(F); for (let q = 0; q < nd; q++) if (held[q]) b[q] = 0;
    const spec = { ne: st.ne, nn: st.nn, elemNodes: st.elemNodes, nodeElem: st.nodeElem, scale, mIdx, Ks, inv }, x0 = new Float64Array(nd);
    const cpu = await VC.cpuElasticBackend.createOperator(spec), g = await gpu.createOperator(spec);
    const rc = await cpu.solve(b, x0, { tol: 1e-10, maxIter: 20000 }), rg = await g.solve(b, x0, { tol: 1e-5, maxIter: 20000 });
    let m = 0, d = 0; for (let i = 0; i < nd; i++) { m = Math.max(m, Math.abs(rc.x[i])); d = Math.max(d, Math.abs(rc.x[i] - rg.x[i])); }
    return { label: gpu.label, n: st.ne, rel: d / m, conv: rg.converged, gi: rg.iters, ci: rc.iters };
  }, coreScript('structural'));
  assert.ok(!r.err, 'WebGPU device: ' + r.err);
  console.log(`core elastic GPU vs CPU: ${r.label}, ${r.n} elements, CG iterations gpu ${r.gi} / cpu ${r.ci}, max difference ${r.rel.toExponential(2)} of the peak`);
  assert.ok(r.conv && r.rel < 1e-3, `GPU and CPU conjugate gradient agree to 1e-3 of the peak: ${JSON.stringify(r)}`);
}

// ───── 2. install: the consent shows the GPU permission and the tier; supports, loads and settings in the Analyze pane
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="structural.alloyext"]'); await page.waitForSelector('#extconsent[open]');
const consent = await page.textContent('#extc-body'); assert.match(consent, /GPU|WebGPU/i); assert.match(consent, /extension/i);
await page.click('#extc-ok'); await page.waitForSelector(`.xpkg[data-ext="${STR}"].on`); await page.click('#extclose');
await page.click('#t-an'); await page.waitForSelector('#an-run');
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${STR}/structural`);
assert.ok(await page.$('#an-settings'), 'the analysis settings are in the Analyze pane'); assert.equal(await page.$('[data-setting="drop_m"]'), null, 'drop settings are hidden until the preset is drop');
assert.deepEqual(await page.$$eval('#p-analyze select[aria-label="Mount kind"] option', (o) => o.map((x) => x.value)), ['contact', 'fixed', 'slider'], 'a slider is a mount kind');
const bodies = await page.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
assert.ok(bodies.length >= 2, 'the starter design has two bodies: ' + bodies);
const setSettings = (settings) => page.evaluate(({ id, settings }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: settings }], { actor: 'user' }).project); }, { id: STR, settings });
const facts = async () => Object.fromEntries((await page.$$eval('#an-result .facts > div', (els) => els.map((e) => [e.querySelector('span').textContent, e.textContent.slice(e.querySelector('span').textContent.length)]))).map(([k, v]) => [k, v.trim()]));
const num = (s) => parseFloat(s);
async function runOnce() { await page.click('#an-run'); await page.waitForFunction(() => document.querySelector('#an-result') || document.querySelector('#an-error'), null, { timeout: 240000 }); assert.equal(await page.$('#an-error'), null, 'no error: ' + (await page.textContent('#an-error').catch(() => ''))); return facts(); }
await setSettings({ h_mm: 2.5, backend: 'cpu' }); await page.waitForSelector('#an-run');
// nothing held: a clear refusal, not a crash
await page.click('#an-run'); await page.waitForSelector('#an-error', { timeout: 60000 }); assert.match(await page.textContent('#an-error'), /nothing holds the model: add a fixed mount/);
// a fixed mount on the first body only leaves the second one floating: refused, and it says so
const addMount = async (kind, body, face, count) => { await page.selectOption('#p-analyze select[aria-label="Mount kind"]', kind); await page.selectOption('#p-analyze select[aria-label="Mount body"]', body); await page.selectOption('#p-analyze select[aria-label="Mount face"]', face); await page.click('#mount-add'); await page.waitForFunction((n) => document.querySelectorAll('#p-analyze [data-mount]').length === n, count); };
await addMount('fixed', bodies[0], '-z', 1);
await page.waitForSelector('#an-run'); await page.click('#an-run'); await page.waitForSelector('#an-error', { timeout: 60000 }); assert.match(await page.textContent('#an-error'), /do not hold every body/);
await addMount('fixed', bodies[1], '-z', 2);
// the pane redraws once the document has been rebuilt after the mounts: set the form and press add in one step so a redraw cannot reset it between the two
await page.waitForTimeout(1500);
await page.evaluate(() => { const q = (s) => document.querySelector('#p-analyze ' + s); q('select[aria-label="Load kind"]').value = 'acceleration'; q('input[aria-label="Acceleration g, force N or pressure Pa"]').value = '500'; q('select[aria-label="Direction or face"]').value = '+y'; q('#load-add').click(); });
await page.waitForFunction(() => document.querySelectorAll('#p-analyze [data-load]').length === 1);
assert.match(await page.textContent('#p-analyze'), /500 g toward \+y/);

// ───── 3. GPU run and CPU run on the same voxel size
await page.waitForSelector('#an-run');
const cpuF = await runOnce();
assert.match(await page.textContent('#an-validity'), /estimate/); assert.match(await page.textContent('#an-result'), /Solver: CPU/);
assert.equal(num(cpuF.voxel_size), 2.5); assert.ok(num(cpuF.active_voxels) > 500); assert.ok(num(cpuF.max_displacement) > 0 && num(cpuF.max_von_mises) > 0 && num(cpuF.equilibrium_error) < 1e-3);
await page.waitForSelector('#legend:not([hidden])'); const legendCpu = await page.textContent('#legend');
assert.match(legendCpu, /MPa/); assert.match(legendCpu, /voxel 2\.5 mm/); assert.match(legendCpu, /active of a [\d,]+ budget/); assert.match(legendCpu, /limit 8 000 000/);
assert.match(await page.textContent('#an-result'), /Grid: .* solid \(budget [\d,]+ for .*hard limit 2,000,000 active, 8,000,000 cells\)/);
assert.match(await page.textContent('#an-result'), /Edge artefact likely/); assert.match(await page.textContent('#an-result'), /Alloy Runner/);
// the stress map is a surface field per body; the other fields can be switched on, and the Layers sheet lists them as analysis layers
const fieldNames = await page.$$eval('#an-result [data-field]', (b) => b.map((x) => x.getAttribute('data-field')));
assert.deepEqual(fieldNames.filter((n) => n.startsWith('von_mises')).length, bodies.length); assert.ok(fieldNames.includes(`displacement:${bodies[0]}`) && fieldNames.includes(`safety_factor:${bodies[0]}`));
await page.click(`#an-result [data-field="displacement:${bodies[0]}"]`); await page.waitForSelector(`#an-result [data-field="displacement:${bodies[0]}"].on`);
await page.click('#layers-btn'); await page.waitForSelector('#layersdlg[open]');
const layerText = await page.textContent('#layersbody'); assert.match(layerText, /von_mises/); assert.match(layerText, /displacement/); assert.match(layerText, /MPa/); assert.match(layerText, /mm/);
await page.click('#layersbody [data-act=close]');
await page.click('#vp', { position: { x: 640, y: 420 } }); // the probe reads a value under the pointer
if (gpuOk) {
  await setSettings({ h_mm: 2.5, backend: 'gpu' }); await page.waitForSelector('#an-result', { state: 'detached' }); await page.waitForSelector('#an-run');
  const gpuF = await runOnce(); const text = await page.textContent('#an-result');
  assert.match(text, /Solver: WebGPU/, 'the solve ran on the GPU: ' + text.match(/Solver: [^.]*/)); assert.equal(num(gpuF.voxel_size), 2.5); assert.equal(num(gpuF.active_voxels), num(cpuF.active_voxels));
  const a = num(cpuF.max_displacement), b = num(gpuF.max_displacement);
  console.log(`package GPU vs CPU: max_displacement ${b} against ${a} mm, max_von_mises ${gpuF.max_von_mises} against ${cpuF.max_von_mises} MPa; solver seconds gpu ${gpuF.solve_seconds} cpu ${cpuF.solve_seconds}`);
  assert.ok(Math.abs(b - a) < 0.005 * a, `the GPU and CPU displacements agree to 0.5 %: ${b} against ${a}`);
  assert.ok(Math.abs(num(gpuF.max_von_mises) - num(cpuF.max_von_mises)) < 0.02 * num(cpuF.max_von_mises), 'and the stresses to 2 %');
  assert.ok(Math.abs(num(gpuF.reaction_force) - num(gpuF.applied_force)) < 0.01 * num(gpuF.applied_force), 'the GPU solution still balances the load');
}
// settings are part of the project, and the preset settings appear when chosen
await page.selectOption('[data-setting="backend"]', 'cpu'); await page.waitForFunction((id) => window.__af.S.project.ext[id].settings.backend === 'cpu', STR);
await page.selectOption('[data-setting="preset"]', 'drop'); await page.waitForSelector('[data-setting="drop_m"]'); assert.ok(await page.$('[data-setting="drop_stop_mm"]'));
await page.waitForFunction((id) => window.__af.S.project.ext[id].settings.preset === 'drop', STR); await page.waitForSelector('#an-run');
const dropF = await runOnce(); assert.match(await page.textContent('#an-result'), /Preset drop: drop from 1 m stopped over 2 mm/); assert.ok(num(dropF.applied_force) > 1.3 * num(cpuF.applied_force), 'a 500 g preset toward -z adds to the 500 g load toward +y (a factor of sqrt 2)');
// the checks of this analysis are editable limits in the pane
assert.ok(await page.$(`[data-check="${STR}:structural:min-safety-factor"]`) && await page.$(`[data-check="${STR}:structural:max-displacement"]`));

// ───── 4. cancel
await setSettings({ quality: 'fine', backend: 'cpu', preset: 'none' }); await page.waitForSelector('#an-run');
await page.click('#an-run'); await page.waitForSelector('#an-cancel'); await page.waitForFunction(() => document.querySelector('.pbar-fill') && parseFloat(document.querySelector('.pbar-fill').style.width) > 10, null, { timeout: 120000 }).catch(() => {});
const t0 = Date.now(); await page.click('#an-cancel'); await page.waitForSelector('#an-run', { timeout: 30000 }); assert.ok(Date.now() - t0 < 25000, 'cancel is quick'); assert.match(await page.textContent('#an-error'), /Cancelled/);

// ───── 5. phone viewport
const pctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' });
const pp = await newPage(pctx);
await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]');
await pp.click('button[data-sample="structural.alloyext"]'); await pp.waitForSelector('#extconsent[open]'); await pp.click('#extc-ok'); await pp.waitForSelector(`.xpkg[data-ext="${STR}"].on`); await pp.click('#extclose');
await pp.click('#n-an'); await pp.waitForSelector('#an-run'); await pp.selectOption('#p-analyze select[aria-label="Analysis"]', `${STR}/structural`);
const pbodies = await pp.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
await pp.evaluate(({ id, bodies }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: { quality: 'draft', backend: 'cpu', h_mm: 3 } },
  ...bodies.map((b, i) => ({ op: 'set_condition', list: 'mounts', item: { id: 'm' + i, kind: 'fixed', body: b, face: '-z' } })), { op: 'set_condition', list: 'loads', item: { id: 'l1', kind: 'acceleration', accel_ms2: 4903.3, direction: '+y' } }], { actor: 'user' }).project); }, { id: STR, bodies: pbodies });
await pp.waitForSelector('#an-run'); await pp.waitForSelector('[data-mount]');
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways page scroll');
assert.equal(await pp.evaluate(() => { const l = document.querySelector('#left'); return l.scrollWidth <= l.clientWidth + 1; }), true, 'no sideways scroll in the sheet');
const small = await pp.evaluate(() => [...document.querySelectorAll('#p-analyze button:not(.i), #p-analyze select, #p-analyze input')].filter((e) => e.offsetParent && e.type !== 'checkbox' && e.getBoundingClientRect().height < 36).map((e) => e.getAttribute('aria-label') || e.id || e.textContent.slice(0, 20)));
assert.deepEqual(small, [], 'touch targets are at least 36 px tall');
await pp.click('#an-run'); await pp.waitForSelector('#an-result', { timeout: 240000 });
const pt = await pp.textContent('#an-result'); assert.match(pt, /estimate/); assert.match(pt, /Grid: .* solid \(budget /);
await pp.waitForSelector('#legend:not([hidden])'); assert.match(await pp.textContent('#legend'), /MPa/); assert.match(await pp.textContent('#legend'), /voxel [\d.]+ mm/);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with a result on screen');
// the phone grid budget for the GPU path is stated in the settings tip and checked in the core; the Layers sheet opens as a bottom sheet
await pp.tap('#layers-btn'); await pp.waitForSelector('#layersdlg[open]'); assert.match(await pp.textContent('#layersbody'), /von_mises/);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with the Layers sheet open');

assert.deepEqual(errors, [], 'no page errors'); assert.deepEqual(nets, [], 'no request leaves the origin');
await browser.close(); srv.close();
console.log('ok test_ui_structural' + (gpuOk ? ' (WebGPU executed, software adapter)' : ' (no WebGPU: GPU assertions skipped)'));
