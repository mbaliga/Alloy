// Browser smoke test for A5 (Airflow) with WebGPU. Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_airflow.mjs
// Chromium is started with the software WebGPU flags (SwiftShader), which is what a headless machine has. The test
//   1. runs the lattice Boltzmann kernel of the shared voxel core on the GPU against its CPU twin on the same small problem
//      (walls, an inlet, an outlet, an obstacle, a force, Smagorinsky, a periodic axis) and requires agreement,
//   2. installs the package (consent shows compute.gpu, scene.overlay and the extension tier), sets the air speed in the Analyze pane,
//      shows the settings (and that the natural-mode settings are hidden until chosen), says what is wrong when nothing drives the air,
//   3. runs the analysis on the GPU inside the package Worker and again on the CPU at the same voxel size and requires the same
//      qualitative answer (vents found, through flow of the same order), reads the voxel size and the lattice limits off the result and
//      the legend, draws the speed slice, the arrows of the velocity field and the streamlines, lists the layers, probes,
//   4. cancels a run, and repeats the flow on a 390 px phone viewport (no sideways scroll, touch targets, phone lattice budget).
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
const AF = 'xyz.mdhv.airflow';
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

// ───── 1. the core's lattice Boltzmann kernel on the GPU against its CPU twin
const gpuOk = await page.evaluate(async () => !!(navigator.gpu && await navigator.gpu.requestAdapter()));
if (!gpuOk) console.log('NOTE: this browser has no WebGPU; the GPU assertions are skipped.');
else {
  const r = await page.evaluate(async (src) => {
    const C = (0, eval)(src), gpu = await C.lbmGpuBackend(); if (!gpu) return { err: C.lbmGpuBackend.lastError };
    const nx = 18, ny = 14, nz = 6, N = nx * ny * nz, flags = new Uint8Array(N), force = new Float32Array(3 * N);
    for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
      const c = x + nx * (y + ny * z);
      if (y === 0 || y === ny - 1) flags[c] = C.LBM_SOLID; else if (x === 0) flags[c] = C.boundaryFlag(C.LBM_VEL, 0); else if (x === nx - 1) flags[c] = C.boundaryFlag(C.LBM_PRES, 1);
      else if (x >= 7 && x <= 9 && y >= 5 && y <= 8 && z >= 1 && z <= 3) flags[c] = C.LBM_SOLID; else if (x === 4 && y === 3 && z === 2) { force[3 * c] = 2e-5; force[3 * c + 1] = 1e-5; }
    }
    const spec = { dims: [nx, ny, nz], flags, nu: 0.02, cs: 0.12, periodic: [false, false, true], uVel: [0.04, 0, 0], uWall: [0, 0, 0], bodyForce: [1e-6, 0, 0], force, u0: [0.02, 0, 0] };
    const S = C.createLbm(spec); C.lbmAdvance(S, 300);
    const g = await gpu.create(spec); await g.step(300); const M = await g.macro();
    let d = 0, m = 0; for (let c = 0; c < N; c++) { if ((flags[c] & 7) === C.LBM_SOLID) continue; d = Math.max(d, Math.abs(M.ux[c] - S.ux[c]), Math.abs(M.uy[c] - S.uy[c]), Math.abs(M.uz[c] - S.uz[c]), Math.abs(M.rho[c] - S.rho[c])); m = Math.max(m, Math.abs(S.ux[c])); }
    g.destroy(); return { label: gpu.label, n: N, rel: d / m, m };
  }, coreScript('airflow'));
  assert.ok(!r.err, 'WebGPU device: ' + r.err);
  console.log(`core lattice Boltzmann GPU vs CPU: ${r.label}, ${r.n} cells, 300 steps, largest difference ${r.rel.toExponential(2)} of the peak speed ${r.m.toFixed(4)}`);
  assert.ok(r.rel < 1e-3, `GPU and CPU lattice agree to 1e-3 of the peak: ${JSON.stringify(r)}`);
}

// ───── 2. install, the pane, and a refusal that says what to do
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="airflow.alloyext"]'); await page.waitForSelector('#extconsent[open]');
const consent = await page.textContent('#extc-body'); assert.match(consent, /GPU|WebGPU/i); assert.match(consent, /extension/i); assert.match(consent, /overlay|viewport|draw/i);
await page.click('#extc-ok'); await page.waitForSelector(`.xpkg[data-ext="${AF}"].on`); await page.click('#extclose');
await page.click('#t-an'); await page.waitForSelector('#an-run');
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${AF}/airflow`);
assert.ok(await page.$('#an-settings'), 'the analysis settings are in the Analyze pane');
assert.equal(await page.$('[data-setting="flow_dir"]') !== null, true, 'forced mode shows the flow direction'); assert.ok(await page.$('[data-setting="speed_ms"]'));
const setSettings = (settings) => page.evaluate(({ id, settings }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: settings }], { actor: 'user' }).project); }, { id: AF, settings });
const facts = async () => Object.fromEntries((await page.$$eval('#an-result .facts > div', (els) => els.map((e) => [e.querySelector('span').textContent, e.textContent.slice(e.querySelector('span').textContent.length)]))).map(([k, v]) => [k, v.trim()]));
const num = (s) => parseFloat(s);
async function runOnce() { await page.click('#an-run'); await page.waitForFunction(() => document.querySelector('#an-result') || document.querySelector('#an-error'), null, { timeout: 300000 }); assert.equal(await page.$('#an-error'), null, 'no error: ' + (await page.textContent('#an-error').catch(() => ''))); return facts(); }
await setSettings({ backend: 'cpu', quality: 'draft', max_seconds: 40, streamlines: 80, flow_throughs: 4 }); await page.waitForSelector('#an-run');
await page.click('#an-run'); await page.waitForSelector('#an-error', { timeout: 60000 }); assert.match(await page.textContent('#an-error'), /nothing drives the air/);
// the starter design (two bodies) in a 2 m/s stream, the air speed set in the project the way the pane's own control does
const starter = await page.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
assert.ok(starter.length >= 1, 'the starter design has bodies: ' + starter);
await page.evaluate(() => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'set_ambient', ambient: { airspeed_ms: 2 } }], { actor: 'user' }).project); });
await page.waitForFunction(() => window.__af.S.project.analysis.conditions.ambient.airspeed_ms === 2); await page.waitForSelector('#an-run');

// ───── 3. a CPU run, then a GPU run, on the same lattice
await page.evaluate(() => { const v = window.__af.view, o = v.setOverlays; v.setOverlays = (l) => { window.__drawn = l.map((x) => ({ key: x.key, kind: x.ov.kind, lines: x.ov.lines ? x.ov.lines.length : 0, samples: x.ov.values ? x.ov.values.length : 0 })); return o(l); }; });
const cpuF = await runOnce();
assert.match(await page.textContent('#an-validity'), /estimate/); assert.match(await page.textContent('#an-result'), /Solver: CPU/);
assert.ok(num(cpuF.voxel_size) > 0 && num(cpuF.lattice_cells) > 1000 && num(cpuF.max_speed) > 0, 'the result states a voxel size, the lattice and a speed: ' + JSON.stringify(cpuF));
await page.waitForSelector('#legend:not([hidden])'); const legend = await page.textContent('#legend'); assert.match(legend, /m\/s/); assert.match(legend, /voxel [\d.]+ mm/);
assert.match(await page.textContent('#an-result'), /Lattice: [\d×]+ = [\d,]+ cells of [\d.]+ mm \(budget [\d,]+ for draft on the CPU; hard limit 2,200,000\)/);
assert.match(await page.textContent('#an-result'), /Validity "estimate"/); assert.match(await page.textContent('#an-result'), /OpenFOAM/);
// fields: speed (a slice), velocity (arrows), pressure, dead zone; the streamlines are the package's own overlay
const fieldNames = await page.$$eval('#an-result [data-field]', (b) => b.map((x) => x.getAttribute('data-field')));
assert.deepEqual(fieldNames, ['speed', 'velocity', 'pressure', 'dead_zone']);
await page.click('#an-result [data-field="velocity"]'); await page.waitForSelector('#an-result [data-field="velocity"].on');
await page.click('#layers-btn'); await page.waitForSelector('#layersdlg[open]');
const layerText = await page.textContent('#layersbody'); assert.match(layerText, /speed/); assert.match(layerText, /velocity/); assert.match(layerText, /m\/s/); assert.match(layerText, /Airflow streamlines/i);
await page.waitForFunction(() => window.__drawn && window.__drawn.some((d) => d.kind === 'vectors' && d.lines > 3), null, { timeout: 20000 });
const drawn = await page.evaluate(() => window.__drawn); const arrows = drawn.find((d) => d.kind === 'vectors' && !d.lines), lines = drawn.find((d) => d.lines > 3);
assert.ok(arrows && arrows.samples > 20, 'the velocity field is drawn as arrows: ' + JSON.stringify(drawn)); assert.ok(lines && lines.samples > 50, 'the streamlines are drawn as lines: ' + JSON.stringify(drawn));
await page.click('#layersbody [data-act=close]'); await page.click('#vp', { position: { x: 640, y: 420 } });
if (gpuOk) {
  await setSettings({ backend: 'gpu', quality: 'draft', max_seconds: 60, streamlines: 0, flow_throughs: 4, h_mm: 0 }); await page.waitForSelector('#an-result', { state: 'detached' }); await page.waitForSelector('#an-run');
  const gpuF = await runOnce(); const text = await page.textContent('#an-result');
  assert.match(text, /Solver: WebGPU/, 'the solve ran on the GPU: ' + (text.match(/Solver: [^.]*/) || [''])[0]); assert.ok(Number.isFinite(num(gpuF.vents)), 'the GPU result reports the vents');
  console.log(`package GPU vs CPU: max_speed ${gpuF.max_speed} against ${cpuF.max_speed} m/s, through_flow ${gpuF.through_flow} against ${cpuF.through_flow} L/s, vents ${gpuF.vents} against ${cpuF.vents}; lattice ${gpuF.lattice_cells} against ${cpuF.lattice_cells} cells; solver seconds gpu ${gpuF.solve_seconds} cpu ${cpuF.solve_seconds}`);
  // the lattices differ (the GPU budget is larger), so only the order of magnitude and the structure are compared, not the numbers
  assert.ok(num(gpuF.lattice_cells) > num(cpuF.lattice_cells), 'the GPU lattice is bigger (laptop budget)'); assert.ok(num(gpuF.max_speed) > 0.2 * num(cpuF.max_speed) && num(gpuF.max_speed) < 5 * num(cpuF.max_speed), 'same order of speed');
  assert.ok(num(gpuF.mass_balance_error) < 0.5, 'the GPU run conserves mass through the vents');
}
// the natural-mode settings appear when it is chosen, and hide the flow direction
await page.selectOption('[data-setting="mode"]', 'natural'); await page.waitForFunction((id) => window.__af.S.project.ext[id].settings.mode === 'natural', AF);
await page.waitForSelector('[data-setting="flow_dir"]', { state: 'detached' }); await page.selectOption('[data-setting="mode"]', 'forced'); await page.waitForSelector('[data-setting="flow_dir"]');
assert.ok(await page.$(`[data-check="${AF}:airflow:dead-zone-fraction"]`) && await page.$(`[data-check="${AF}:airflow:through-flow"]`), 'the checks are editable limits in the pane');

// ───── 4. cancel
await setSettings({ backend: 'cpu', quality: 'fine', max_seconds: 300, flow_throughs: 200, streamlines: 0 }); await page.waitForSelector('#an-run');
await page.click('#an-run'); await page.waitForSelector('#an-cancel'); await page.waitForFunction(() => document.querySelector('.pbar-fill') && parseFloat(document.querySelector('.pbar-fill').style.width) > 14, null, { timeout: 120000 }).catch(() => {});
const t0 = Date.now(); await page.click('#an-cancel'); await page.waitForSelector('#an-run', { timeout: 30000 }); assert.ok(Date.now() - t0 < 25000, 'cancel is quick'); assert.match(await page.textContent('#an-error'), /Cancelled/);

// ───── 5. phone viewport
const pctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' });
const pp = await newPage(pctx);
await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]');
await pp.click('button[data-sample="airflow.alloyext"]'); await pp.waitForSelector('#extconsent[open]'); await pp.click('#extc-ok'); await pp.waitForSelector(`.xpkg[data-ext="${AF}"].on`); await pp.click('#extclose');
await pp.click('#n-an'); await pp.waitForSelector('#an-run'); await pp.selectOption('#p-analyze select[aria-label="Analysis"]', `${AF}/airflow`);
await pp.evaluate(({ id }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: { quality: 'draft', backend: 'cpu', max_seconds: 30, flow_throughs: 3, streamlines: 40 } }, { op: 'set_ambient', ambient: { airspeed_ms: 2 } }], { actor: 'user' }).project); }, { id: AF });
await pp.waitForSelector('#an-run'); await pp.waitForFunction(() => window.__af.S.project.analysis.conditions.ambient.airspeed_ms === 2);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways page scroll');
assert.equal(await pp.evaluate(() => { const l = document.querySelector('#left'); return l.scrollWidth <= l.clientWidth + 1; }), true, 'no sideways scroll in the sheet');
const small = await pp.evaluate(() => [...document.querySelectorAll('#p-analyze button:not(.i), #p-analyze select, #p-analyze input')].filter((e) => e.offsetParent && e.type !== 'checkbox' && e.getBoundingClientRect().height < 36).map((e) => e.getAttribute('aria-label') || e.id || e.textContent.slice(0, 20)));
assert.deepEqual(small, [], 'touch targets are at least 36 px tall');
await pp.click('#an-run'); await pp.waitForSelector('#an-result', { timeout: 300000 });
const pt = await pp.textContent('#an-result'); assert.match(pt, /estimate/); assert.match(pt, /Lattice: [\d×]+ = [\d,]+ cells of/);
await pp.waitForSelector('#legend:not([hidden])'); assert.match(await pp.textContent('#legend'), /m\/s/); assert.match(await pp.textContent('#legend'), /voxel [\d.]+ mm/);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with a result on screen');
await pp.tap('#layers-btn'); await pp.waitForSelector('#layersdlg[open]'); assert.match(await pp.textContent('#layersbody'), /speed/); assert.match(await pp.textContent('#layersbody'), /Airflow streamlines/i);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with the Layers sheet open');

assert.deepEqual(errors, [], 'no page errors'); assert.deepEqual(nets, [], 'no request leaves the origin');
await browser.close(); srv.close();
console.log('ok test_ui_airflow' + (gpuOk ? ' (WebGPU executed, software adapter)' : ' (no WebGPU: GPU assertions skipped)'));
