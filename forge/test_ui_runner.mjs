// Browser smoke test for A4 (Alloy Runner). Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_runner.mjs
// A real runner (runner/src/server.mjs, its SIMULATED solver, real gmsh) listens on another port of 127.0.0.1 and the page talks to it
// across origins, so CORS, the bearer token and the browser's own network are all real. The test checks, in the browser:
//   1. install of the package (consent shows the runner permission), runner URL and token in Settings, the connection test and what it reports,
//   2. the consent dialog: nothing reaches the runner before it, Cancel sends nothing, Send uploads exactly once,
//   3. the result (experimental, SIMULATED), layers, the job list (kept job, delete) and the runner's deletion after download,
//   4. Cancel of a running job (the solve is slowed by rewriting the job request) and the job list afterwards,
//   5. the same on a 390 px phone viewport: no sideways scroll, touch targets, the dialog fits.
// Network: the page may talk to its own origin and to the runner origin only.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRunner } from '../runner/src/server.mjs';
import { loadConfig } from '../runner/src/config.mjs';
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright');

const dist = path.resolve('dist'), types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.alloyext': 'application/zip' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const url = `http://localhost:${srv.address().port}/`, origin = new URL(url).origin;
const TOKEN = 'ui-test-token-0123456789abcdef';
const R = createRunner({ config: loadConfig({ ALLOY_RUNNER_TOKEN: TOKEN, ALLOY_RUNNER_ENABLE_FAKE: '1', ALLOY_RUNNER_PORT: '0' }, { dataDir: fs.mkdtempSync('/tmp/alloy-ui-runner-'), origins: [origin] }) });
const ra = await R.listen(), runnerUrl = `http://127.0.0.1:${ra.port}`;
const EXT = 'xyz.mdhv.runner';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const errors = [], foreign = [];
const newPage = async (ctx, log) => {
  const page = await ctx.newPage();
  await page.addInitScript(() => { const raf = window.requestAnimationFrame.bind(window); window.requestAnimationFrame = (cb) => setTimeout(() => raf(cb), 250); });
  page.on('pageerror', (e) => errors.push(e.message)); page.on('dialog', (d) => d.accept());
  page.on('request', async (r) => {
    const u = new URL(r.url()); if (/^(data|blob):/.test(r.url())) return;
    if (u.origin === origin) return;
    if (u.origin === runnerUrl) { const dlg = await page.evaluate(() => { const d = document.querySelector('#remotedlg'); return !!(d && d.open); }).catch(() => false); log.push({ method: r.method(), path: u.pathname, dialogOpen: dlg, body: r.postData() ? r.postData().length : 0 }); }
    else foreign.push(r.url());
  });
  await page.goto(url); await page.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 }); return page;
};
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
const log = []; const page = await newPage(ctx, log);

// ───── 1. install, runner settings, connection test
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="runner.alloyext"]'); await page.waitForSelector('#extconsent[open]');
const consent = await page.textContent('#extc-body'); assert.match(consent, /extension/i); assert.match(consent, /Alloy Runner/);
await page.click('#extc-ok'); await page.waitForSelector(`.xpkg[data-ext="${EXT}"].on`);
await page.fill('#runner-url', runnerUrl); await page.fill('#runner-token', 'wrong-wrong-wrong-wrong'); await page.click('#runner-test');
await page.waitForFunction(() => /token is NOT accepted/.test(document.querySelector('#runner-status').textContent), null, { timeout: 15000 });
await page.fill('#runner-token', TOKEN); await page.click('#runner-test');
await page.waitForFunction(() => /token is accepted/.test(document.querySelector('#runner-status').textContent), null, { timeout: 15000 });
const status = await page.textContent('#runner-status'); assert.match(status, /alloy-runner 0\.1\.0, protocol 1/); assert.match(status, /fake: ready \(simulated, protocol tests only\)/); assert.match(status, /elmer: not available/); assert.match(status, /openfoam: not available.*not implemented/); assert.match(status, /No qualification file/);
assert.ok(log.every((l) => l.method === 'GET' || l.method === 'OPTIONS'), 'testing the connection sends no geometry: ' + JSON.stringify(log.map((l) => l.method + l.path)));
await page.click('#extclose');

// ───── 2. the Analyze pane: a mount and a load, settings, then the consent gate
await page.click('#t-an'); await page.waitForSelector('#an-run');
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${EXT}/runner-structural`);
assert.ok(await page.$('#aremote'), 'the Runner block is shown'); assert.match(await page.textContent('#remote-note'), new RegExp(`Runs on your Alloy Runner at ${runnerUrl.replace(/\./g, '\\.')}`));
await page.waitForSelector('#rjobs .empty'); assert.match(await page.textContent('#rjobs'), /No jobs on the runner/);
const bodies = await page.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
const setup = (ext, extra = []) => page.evaluate(({ id, ext, bodies, extra }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: ext }, ...extra], { actor: 'user' }).project); },
  { id: EXT, ext, bodies, extra });
await setup({ adapter: 'fake', mesh_size_mm: 8, poll_ms: 50 }, [...bodies.map((b, i) => ({ op: 'set_condition', list: 'mounts', item: { id: 'm' + i, kind: 'fixed', body: b, face: '-z' } })), { op: 'set_condition', list: 'loads', item: { id: 'l1', kind: 'acceleration', accel_ms2: 4903.3, direction: '+y' } }]);
await page.waitForSelector('#an-run'); await page.waitForSelector('[data-mount]');
const before = log.length;
await page.click('#an-run'); await page.waitForSelector('#remotedlg[open]');
const dtext = await page.textContent('#remote-body'); assert.match(dtext, new RegExp(`Alloy Runner at ${runnerUrl.replace(/\./g, '\\.')}`)); for (const b of bodies) assert.ok(dtext.includes(b), `the dialog lists ${b}`);
assert.match(dtext, /triangle mesh, [\d,]+ triangles/); assert.match(dtext, /Sent \(about [\d.]+ MB\)/); assert.match(dtext, /Not sent/); assert.match(dtext, /your AI key/); assert.match(dtext, /Whoever runs that server can see the geometry/);
const mid = log.slice(before); assert.ok(mid.every((l) => l.method === 'GET'), 'before the answer only the health read went out: ' + JSON.stringify(mid)); assert.ok(!mid.some((l) => l.method === 'POST'), 'no POST before the consent');
await page.click('#remote-no'); await page.waitForSelector('#an-error'); assert.match(await page.textContent('#an-error'), /Nothing was sent/);
assert.ok(!log.slice(before).some((l) => l.method === 'POST'), 'Cancel sent nothing'); assert.equal(R.store.list().length, 0);
// now confirm
const b2 = log.length; await page.click('#an-run'); await page.waitForSelector('#remotedlg[open]'); await page.click('#remote-ok');
await page.waitForSelector('#an-result, #an-error', { timeout: 120000 }); assert.equal(await page.$('#an-error'), null, 'the run failed: ' + (await page.textContent('#an-error').catch(() => '')));
const posts = log.slice(b2).filter((l) => l.method === 'POST'); assert.equal(posts.length, 1, 'exactly one upload'); assert.equal(posts[0].path, '/v1/jobs'); assert.equal(posts[0].dialogOpen, false, 'the POST happens after the dialog closed on Send (the answer was given)'); assert.ok(posts[0].body > 1000);
assert.match(await page.textContent('#an-validity'), /experimental/); const rt = await page.textContent('#an-result'); assert.match(rt, /SIMULATED/); assert.match(rt, /Validity experimental/); assert.match(rt, /max_displacement/);
const fieldNames = await page.$$eval('#an-result [data-field]', (b) => b.map((x) => x.getAttribute('data-field'))); assert.ok(fieldNames.includes(`runner_von_mises:${bodies[0]}`) && fieldNames.includes(`runner_displacement:${bodies[0]}`) && fieldNames.includes(`runner_safety_factor:${bodies[0]}`));
await page.waitForSelector('#legend:not([hidden])'); assert.match(await page.textContent('#legend'), /MPa/);
await page.click('#layers-btn'); await page.waitForSelector('#layersdlg[open]'); assert.match(await page.textContent('#layersbody'), /runner_von_mises/); await page.click('#layersbody [data-act=close]');
await page.click('#vp', { position: { x: 640, y: 420 } });
for (let i = 0; i < 100 && R.store.list().length; i++) await page.waitForTimeout(30); assert.equal(R.store.list().length, 0, 'the runner deleted the job after the download');
assert.ok(await page.$(`[data-check="${EXT}:runner-structural:min-safety-factor"]`), 'the checks are editable here');
// the same inputs are cached: no new upload, no dialog
const b3 = log.length; await page.click('#an-run'); await page.waitForSelector('#an-result'); await page.waitForTimeout(300); assert.ok(!(await page.$('#remotedlg[open]'))); assert.ok(!log.slice(b3).some((l) => l.method === 'POST'), 'a cached result uploads nothing');

// ───── 3. keep: the job list shows the kept job and can delete it
await setup({ adapter: 'fake', mesh_size_mm: 8, poll_ms: 50, keep: 'on' }); await page.waitForSelector('#an-run'); await page.click('#an-run'); await page.waitForSelector('#remotedlg[open]'); await page.click('#remote-ok'); await page.waitForSelector('#an-result', { timeout: 120000 });
await page.click('#rj-refresh'); await page.waitForSelector('#rjobs [data-job][data-state="done"]'); assert.equal((await page.$$('#rjobs [data-job]')).length, 1);
await page.click('#rjobs [data-job-delete]'); await page.waitForSelector('#rjobs .empty'); assert.equal(R.store.list().length, 0);

// ───── 4. cancel a running job (the page's job request is rewritten to a 5 s solve)
await page.route(`${runnerUrl}/v1/jobs`, async (route) => { const req = route.request(); if (req.method() !== 'POST') return route.continue(); const j = JSON.parse(req.postData()); j.options.fake_delay_ms = 5000; await route.continue({ postData: JSON.stringify(j) }); });
await setup({ adapter: 'fake', mesh_size_mm: 7, poll_ms: 50 }); await page.waitForSelector('#an-run');
await page.click('#an-run'); await page.waitForSelector('#remotedlg[open]'); await page.click('#remote-ok'); await page.waitForSelector('#an-cancel');
await page.waitForSelector('#rjobs [data-job][data-state="running"]', { timeout: 20000 }); assert.ok(await page.$('#rjobs [data-job-cancel]'), 'the job list offers Cancel for a running job');
const tc = Date.now(); await page.click('#an-cancel'); await page.waitForSelector('#an-run', { timeout: 30000 }); assert.ok(Date.now() - tc < 12000, 'cancel is quick'); assert.match(await page.textContent('#an-error'), /Cancelled/);
for (let i = 0; i < 150 && R.store.list().length; i++) await page.waitForTimeout(30); assert.equal(R.store.list().length, 0, 'the cancelled job is gone from the runner');
assert.ok(log.some((l) => l.method === 'POST' && /\/cancel$/.test(l.path)), 'the page sent the cancel to the runner');
await page.unroute(`${runnerUrl}/v1/jobs`);
// cancel from the job list: a second job, cancelled from there
await page.route(`${runnerUrl}/v1/jobs`, async (route) => { const req = route.request(); if (req.method() !== 'POST') return route.continue(); const j = JSON.parse(req.postData()); j.options.fake_delay_ms = 5000; j.options.keep = true; await route.continue({ postData: JSON.stringify(j) }); });
await page.click('#an-run'); await page.waitForSelector('#remotedlg[open]'); await page.click('#remote-ok'); await page.waitForSelector('#rjobs [data-job][data-state="running"]', { timeout: 20000 });
await page.click('#rjobs [data-job-cancel]'); await page.waitForSelector('#an-error', { timeout: 30000 }); assert.match(await page.textContent('#an-error'), /Cancelled/);
await page.waitForSelector('#rjobs .empty', { timeout: 15000 }); assert.equal(R.store.list().length, 0, 'the package deletes a cancelled job even when it asked to keep it'); await page.unroute(`${runnerUrl}/v1/jobs`);

// ───── 5. phone viewport
const plog = []; const pctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36' });
const pp = await newPage(pctx, plog);
await pp.evaluate(({ u, t }) => localStorage.setItem('af.runner', JSON.stringify({ url: u, token: t })), { u: runnerUrl, t: TOKEN }); await pp.reload(); await pp.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 });
await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]'); await pp.click('button[data-sample="runner.alloyext"]'); await pp.waitForSelector('#extconsent[open]'); await pp.click('#extc-ok'); await pp.waitForSelector(`.xpkg[data-ext="${EXT}"].on`);
await pp.click('#runner-test'); await pp.waitForFunction(() => /token is accepted/.test(document.querySelector('#runner-status').textContent), null, { timeout: 15000 });
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll in Settings with the runner status'); await pp.click('#extclose');
await pp.click('#n-an'); await pp.waitForSelector('#an-run'); await pp.selectOption('#p-analyze select[aria-label="Analysis"]', `${EXT}/runner-structural`);
const pb = await pp.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
await pp.evaluate(({ id, bodies }) => { const A = window.__af; A.commit(A.applyOps(A.S.project, [{ op: 'ext_set', id, key: 'settings', value: { adapter: 'fake', mesh_size_mm: 10, poll_ms: 50 } }, ...bodies.map((b, i) => ({ op: 'set_condition', list: 'mounts', item: { id: 'm' + i, kind: 'fixed', body: b, face: '-z' } })), { op: 'set_condition', list: 'loads', item: { id: 'l1', kind: 'acceleration', accel_ms2: 4903.3, direction: '+y' } }], { actor: 'user' }).project); }, { id: EXT, bodies: pb });
await pp.waitForSelector('#an-run'); await pp.waitForSelector('[data-mount]'); await pp.waitForSelector('#rjobs');
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways page scroll'); assert.equal(await pp.evaluate(() => { const l = document.querySelector('#left'); return l.scrollWidth <= l.clientWidth + 1; }), true, 'no sideways scroll in the sheet');
const small = await pp.evaluate(() => [...document.querySelectorAll('#p-analyze button:not(.i), #p-analyze select, #p-analyze input')].filter((e) => e.offsetParent && e.type !== 'checkbox' && e.getBoundingClientRect().height < 36).map((e) => e.getAttribute('aria-label') || e.id || e.textContent.slice(0, 20)));
assert.deepEqual(small, [], 'touch targets are at least 36 px tall');
await pp.tap('#an-run'); await pp.waitForSelector('#remotedlg[open]');
const box = await pp.evaluate(() => { const r = document.querySelector('#remotedlg').getBoundingClientRect(), ok = document.querySelector('#remote-ok').getBoundingClientRect(), no = document.querySelector('#remote-no').getBoundingClientRect(); return { l: r.left, r: r.right, w: window.innerWidth, h: window.innerHeight, okB: ok.bottom, noB: no.bottom, okH: ok.height, noH: no.height }; });
assert.ok(box.l >= -1 && box.r <= box.w + 1, 'the consent dialog fits the phone width: ' + JSON.stringify(box)); assert.ok(box.okB <= box.h + 1 && box.noB <= box.h + 1, 'both buttons are on screen'); assert.ok(box.okH >= 36 && box.noH >= 36, 'both buttons are touch sized');
assert.ok(!plog.some((l) => l.method === 'POST'), 'nothing uploaded while the dialog is open');
await pp.tap('#remote-ok'); await pp.waitForSelector('#an-result', { timeout: 120000 }); assert.match(await pp.textContent('#an-result'), /experimental/);
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with a result on screen');
await pp.tap('#layers-btn'); await pp.waitForSelector('#layersdlg[open]'); assert.match(await pp.textContent('#layersbody'), /runner_von_mises/); assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways scroll with the Layers sheet open');

assert.deepEqual(errors, [], 'no page errors'); assert.deepEqual(foreign, [], 'no request leaves the page origin except to the runner URL: ' + foreign);
await browser.close(); srv.close(); await R.close();
console.log('ok test_ui_runner');
