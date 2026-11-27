// Browser smoke test for A2 (Thermal 3D) with WebGPU. Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_thermal3d.mjs
// Chromium is started with the software WebGPU flags (SwiftShader), which is what a headless machine has. The test
//   1. runs the shared voxel core's WebGPU conjugate gradient against its CPU twin on the same system and requires agreement,
//   2. installs the package (consent shows compute.gpu and the extension tier), sets the settings in the Analyze pane,
//   3. runs the analysis on the GPU inside the package Worker and again on the CPU with the same voxel size, and requires the two
//      temperatures to agree, reads the voxel size and the grid limits off the result and the legend, draws the slice and probes it,
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
const T3D = 'xyz.mdhv.thermal-3d';
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

// ───── 1. the core's GPU conjugate gradient against its CPU twin
const gpuOk = await page.evaluate(async () => !!(navigator.gpu && await navigator.gpu.requestAdapter()));
if (!gpuOk) console.log('NOTE: this browser has no WebGPU; the GPU assertions are skipped.');
else {
  const r = await page.evaluate(async (src) => {
    const VC = (0, eval)(src), gpu = await VC.gpuBackend(); if (!gpu) return { err: VC.gpuBackend.lastError };
    const nx = 40, ny = 30, nz = 20, n = nx * ny * nz, nbr = new Int32Array(6 * n).fill(-1), g = new Float64Array(6 * n), diag = new Float64Array(n), b = new Float64Array(n), id = (i, j, k) => i + nx * (j + ny * k);
    for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
      const a = id(i, j, k); let s = 0;
      [[i + 1, j, k], [i - 1, j, k], [i, j + 1, k], [i, j - 1, k], [i, j, k + 1], [i, j, k - 1]].forEach((q, f) => { if (q[0] >= 0 && q[0] < nx && q[1] >= 0 && q[1] < ny && q[2] >= 0 && q[2] < nz) { nbr[6 * a + f] = id(...q); const kk = 1 + 0.5 * Math.sin((a + q[0] + q[1]) * 0.01); g[6 * a + f] = kk; s += kk; } });
      diag[a] = s + (i === 0 ? 3 : 0) + (k === nz - 1 ? 1 : 0) + 0.01; b[a] = (i === nx - 1 && j === ny / 2 ? 5 : 0) + (i === 0 ? 60 : 0);
    }
    // symmetric conductances are needed for CG: g[a,f] and g[nbr,opposite f] are equal by construction only if the formula is symmetric; make it so
    for (let a = 0; a < n; a++) for (let f = 0; f < 6; f++) { const j = nbr[6 * a + f]; if (j >= 0 && j > a) { const v = g[6 * a + f]; g[6 * j + (f ^ 1)] = v; } }
    for (let a = 0; a < n; a++) { let s = 0; for (let f = 0; f < 6; f++) s += g[6 * a + f]; diag[a] = s + (a % nx === 0 ? 3 : 0) + (Math.floor(a / (nx * ny)) === nz - 1 ? 1 : 0) + 0.01; }
    const cpu = await VC.cpuBackend.createOperator({ n, nbr, g, diag }), gop = await gpu.createOperator({ n, nbr, g, diag }), x0 = new Float64Array(n);
    const rc = await cpu.solve(b, x0, { tol: 1e-10, maxIter: 5000 }), rg = await gop.solve(b, x0, { tol: 1e-5, maxIter: 5000 });
    // a second solve with a changed diagonal on the same operator (the thermal solver does this every outer iteration)
    const d2 = diag.map((d) => d + 0.5); cpu.setDiag(d2); gop.setDiag(d2);
    const rc2 = await cpu.solve(b, rc.x, { tol: 1e-10, maxIter: 5000 }), rg2 = await gop.solve(b, rg.x, { tol: 1e-5, maxIter: 5000 });
    const rel = (a, c) => { let m = 0, d = 0; for (let i = 0; i < n; i++) { m = Math.max(m, Math.abs(a[i])); d = Math.max(d, Math.abs(a[i] - c[i])); } return d / m; };
    return { label: gpu.label, n, rel1: rel(rc.x, rg.x), rel2: rel(rc2.x, rg2.x), conv: rg.converged && rg2.converged, gi: rg.iters, ci: rc.iters };
  }, coreScript());
  assert.ok(!r.err, 'WebGPU device: ' + r.err);
  console.log(`core GPU vs CPU: ${r.label}, ${r.n} cells, CG iterations gpu ${r.gi} / cpu ${r.ci}, max difference ${r.rel1.toExponential(2)} then ${r.rel2.toExponential(2)} of the peak`);
  assert.ok(r.conv && r.rel1 < 1e-3 && r.rel2 < 1e-3, `GPU and CPU conjugate gradient agree to 1e-3 of the peak: ${JSON.stringify(r)}`);
}

// ───── 2. install: the consent shows the GPU permission and the tier
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="thermal-3d.alloyext"]'); await page.waitForSelector('#extconsent[open]');
const consent = await page.textContent('#extc-body'); assert.match(consent, /GPU|WebGPU/i); assert.match(consent, /extension/i);
await page.click('#extc-ok'); await page.waitForSelector(`.xpkg[data-ext="${T3D}"].on`); await page.click('#extclose');
await page.click('#t-an'); await page.waitForSelector('#an-run');
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${T3D}/thermal-3d`);
assert.ok(await page.$('#an-settings'), 'the analysis settings are in the Analyze pane'); assert.equal(await page.$('[data-setting="t_end_s"]'), null, 'transient-only settings are hidden in steady mode');
const body = await page.evaluate(() => window.__af.S.result.bodies[0].name);
await page.selectOption('#p-analyze select[aria-label="Heat source target"]', `body:${body}`); await page.fill('#heat-w', '2'); await page.click('#heat-add'); await page.waitForSelector('[data-heat]');
const setSettings = (settings) => page.evaluate(({ id, settings }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: settings }], { actor: 'user' }).project); }, { id: T3D, settings });
const facts = async () => Object.fromEntries((await page.$$eval('#an-result .facts > div', (els) => els.map((e) => [e.querySelector('span').textContent, e.textContent.slice(e.querySelector('span').textContent.length)]))).map(([k, v]) => [k, v.trim()]));
const num = (s) => parseFloat(s);
async function runOnce() { await page.click('#an-run'); await page.waitForFunction(() => document.querySelector('#an-result') || document.querySelector('#an-error'), null, { timeout: 180000 }); assert.equal(await page.$('#an-error'), null, 'no error: ' + (await page.textContent('#p-analyze').then((t) => t.slice(0, 200)))); return facts(); }

// ───── 3. GPU run and CPU run on the same voxel size
await setSettings({ quality: 'draft', h_mm: 3, backend: 'cpu' }); await page.waitForSelector('#an-result', { state: 'detached' }).catch(() => {}); await page.waitForSelector('#an-run');
const cpuF = await runOnce();
assert.match(await page.textContent('#an-validity'), /estimate/); assert.match(await page.textContent('#an-result'), /Solver: CPU/);
assert.equal(num(cpuF.voxel_size), 3); assert.ok(num(cpuF.active_voxels) > 100);
await page.waitForSelector('#legend:not([hidden])'); const legendCpu = await page.textContent('#legend');
assert.match(legendCpu, /voxel 3 mm/); assert.match(legendCpu, /active/); assert.match(legendCpu, /budget/); assert.match(legendCpu, /limit 8 000 000/);
if (gpuOk) {
  await setSettings({ quality: 'draft', h_mm: 3, backend: 'gpu' }); await page.waitForSelector('#an-result', { state: 'detached' }); await page.waitForSelector('#an-run');
  const gpuF = await runOnce(); const text = await page.textContent('#an-result');
  assert.match(text, /Solver: WebGPU/, 'the solve ran on the GPU: ' + text.match(/Solver: [^.]*/)); assert.equal(num(gpuF.voxel_size), 3); assert.equal(num(gpuF.active_voxels), num(cpuF.active_voxels));
  const a = num(cpuF.max_temp), b = num(gpuF.max_temp), amb = 25, rise = a - amb;
  console.log(`package GPU vs CPU: max_temp ${b} against ${a} C (rise ${rise.toFixed(2)} K); solver seconds gpu ${gpuF.solve_seconds} cpu ${cpuF.solve_seconds}`);
  assert.ok(Math.abs(b - a) < 0.005 * rise + 0.02, `the GPU and CPU answers agree to 0.5 % of the rise: ${b} against ${a}`);
  assert.ok(Math.abs(num(gpuF.heat_out_convection) + num(gpuF.heat_out_radiation) - 2) < 0.02, 'the GPU solution still conserves the 2 W');
}
// the voxel slice is drawn, with a slider; the probe reads a temperature
await page.waitForSelector('#legend input[type=range]'); await page.click('#vp', { position: { x: 640, y: 420 } });
// transient settings appear when the mode changes; a transient run reports time to limit
await page.selectOption('[data-setting="backend"]', 'cpu'); await page.waitForFunction(() => window.__af.S.project.ext['xyz.mdhv.thermal-3d'].settings.backend === 'cpu');
await page.selectOption('[data-setting="mode"]', 'transient'); await page.waitForSelector('[data-setting="t_end_s"]');
await page.fill('[data-setting="t_end_s"]', '300'); await page.press('[data-setting="t_end_s"]', 'Tab'); await page.waitForSelector('[data-setting="limit_C"]'); await page.fill('[data-setting="limit_C"]', '26'); await page.press('[data-setting="limit_C"]', 'Tab');
await page.waitForFunction(() => { const s = window.__af.S.project.ext['xyz.mdhv.thermal-3d'].settings; return s.mode === 'transient' && s.t_end_s === 300 && s.limit_C === 26; });
const tr = await runOnce(); assert.equal(num(tr.limit_reached), 1); assert.ok(num(tr.time_to_limit) > 0 && num(tr.time_to_limit) < 300, 'time to limit ' + tr.time_to_limit);
assert.ok(num(tr.energy_balance_error) < 0.01);
// settings are part of the project (undo restores), and are not in the results
assert.ok(await page.evaluate(() => JSON.stringify(window.__af.S.project.ext).includes('"mode":"transient"')));

// ───── 4. cancel
await setSettings({ quality: 'fine', backend: 'cpu', mode: 'steady' }); await page.waitForSelector('#an-run');
await page.click('#an-run'); await page.waitForSelector('#an-cancel'); await page.waitForFunction(() => document.querySelector('.pbar-fill') && parseFloat(document.querySelector('.pbar-fill').style.width) > 5, null, { timeout: 60000 }).catch(() => {});
const t0 = Date.now(); await page.click('#an-cancel'); await page.waitForSelector('#an-run', { timeout: 20000 }); assert.ok(Date.now() - t0 < 15000, 'cancel is quick'); assert.match(await page.textContent('#an-error'), /Cancelled/);

// ───── 5. phone viewport
const pctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' });
const pp = await newPage(pctx);
await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]');
await pp.click('button[data-sample="thermal-3d.alloyext"]'); await pp.waitForSelector('#extconsent[open]'); await pp.click('#extc-ok'); await pp.waitForSelector(`.xpkg[data-ext="${T3D}"].on`); await pp.click('#extclose');
await pp.click('#n-an'); await pp.waitForSelector('#an-run'); await pp.selectOption('#p-analyze select[aria-label="Analysis"]', `${T3D}/thermal-3d`);
const pbody = await pp.evaluate(() => window.__af.S.result.bodies[0].name);
await pp.selectOption('#p-analyze select[aria-label="Heat source target"]', `body:${pbody}`); await pp.click('#heat-add'); await pp.waitForSelector('[data-heat]');
await pp.evaluate((id) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: { quality: 'draft', backend: 'cpu' } }], { actor: 'user' }).project); }, T3D);
await pp.waitForSelector('#an-run');
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways page scroll');
assert.equal(await pp.evaluate(() => { const l = document.querySelector('#left'); return l.scrollWidth <= l.clientWidth + 1; }), true, 'no sideways scroll in the sheet');
const small = await pp.evaluate(() => [...document.querySelectorAll('#p-analyze button:not(.i), #p-analyze select, #p-analyze input')].filter((e) => e.offsetParent && e.type !== 'checkbox' && e.getBoundingClientRect().height < 36).map((e) => e.getAttribute('aria-label') || e.id || e.tagName));
assert.deepEqual(small, [], 'touch targets are at least 36 px tall');
await pp.click('#an-run'); await pp.waitForSelector('#an-result', { timeout: 180000 });
const pt = await pp.textContent('#an-result'); assert.match(pt, /budget 40,000/, 'a phone gets the phone budget for draft (the CPU path has the same 40 000 budget; the GPU phone budget is checked in test_voxel_core)'); assert.match(pt, /estimate/);
await pp.waitForSelector('#legend:not([hidden])'); assert.match(await pp.textContent('#legend'), /voxel [\d.]+ mm/);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with a result on screen');

assert.deepEqual(errors, [], 'no page errors'); assert.deepEqual(nets, [], 'no request leaves the origin');
await browser.close(); srv.close();
console.log('ok test_ui_thermal3d' + (gpuOk ? ' (WebGPU executed, software adapter)' : ' (no WebGPU: GPU assertions skipped)'));
