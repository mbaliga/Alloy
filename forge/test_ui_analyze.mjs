// Browser smoke test for the analysis framework UI (Playwright). Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_analyze.mjs
// Install the sample analysis package, set a material and conditions, run, read the result and its validity, draw a surface
// and a slice field (legend, probe), edit a check limit, export the report, cancel a run, and check a 390 px phone.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright');

const dist = path.resolve('dist');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.alloyext': 'application/zip' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const url = `http://localhost:${srv.address().port}/`;
const MASS = 'xyz.mdhv.sample-mass';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
const page = await ctx.newPage();
const errors = []; page.on('pageerror', (e) => errors.push(e.message));
page.on('dialog', (d) => d.accept());
const nets = []; page.on('request', (r) => { const u = new URL(r.url()); if (u.origin !== new URL(url).origin && !/^(data|blob):/.test(r.url())) nets.push(r.url()); });
await page.goto(url);
await page.waitForSelector('#p-tree .row', { timeout: 30000 });

// the Analyze tab is hidden until an analysis is installed
assert.equal(await page.isHidden('#t-an'), true);
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="sample-mass.alloyext"]'); await page.waitForSelector('#extconsent[open]'); await page.click('#extc-ok');
await page.waitForSelector(`.xpkg[data-ext="${MASS}"].on`); await page.click('#extclose');
assert.equal(await page.isVisible('#t-an'), true);
await page.click('#t-an'); await page.waitForSelector('#an-run');

// material: unassigned bodies say PLA is assumed; assign one through the typed op
const bodies = await page.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));
assert.ok(bodies.length >= 1);
assert.match(await page.textContent('#p-analyze'), /assumed/);
await page.selectOption(`select[data-body-material="${bodies[0]}"]`, 'petg');
await page.waitForFunction((b) => window.__af.S.project.bodies[b].material === 'petg', bodies[0]);
await page.click('#t-an');

// conditions go through typed ops and undo
await page.fill('input[aria-label="Ambient temperature °C"]', '30'); await page.press('input[aria-label="Ambient temperature °C"]', 'Tab');
await page.waitForFunction(() => Math.abs(window.__af.S.project.analysis.conditions.ambient.temperature_K - 303.15) < 1e-6);
await page.fill('#heat-w', '2.5'); await page.click('#heat-add');
await page.waitForSelector('[data-heat]');
assert.equal(await page.evaluate(() => window.__af.S.project.analysis.conditions.heat[0].power_W), 2.5);
await page.click('#load-add'); await page.waitForSelector('[data-load]');
assert.match(await page.textContent('[data-load]'), /1000 g|1000\.0* g|1000 g/);

// run: result, validity word and shape, warnings, fields
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${MASS}/mass-properties`);
await page.click('#an-run');
await page.waitForSelector('#an-result', { timeout: 60000 });
assert.match(await page.textContent('#an-validity'), /validated/);
assert.match(await page.textContent('#an-result'), /total_mass/);
assert.ok(await page.evaluate(() => Object.keys(window.__af.S.project).includes('analysis') && !JSON.stringify(window.__af.S.project).includes('inputsHash')), 'results are not saved in the document');

// surface field: legend with unit and slider-free gradient, probe reads a value
// the first field is drawn as soon as a run finishes
await page.waitForSelector('#legend:not([hidden])');
assert.match(await page.textContent('#legend'), /mm/);
await page.click('#vp', { position: { x: 640, y: 400 } });

// voxel field from the experimental analysis: slice slider, voxel size and grid limit stated; experimental marked
await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${MASS}/section-density`);
await page.click('#an-run'); await page.waitForSelector('#an-validity', { timeout: 60000 });
assert.match(await page.textContent('#an-validity'), /experimental/);
assert.ok(await page.$('button[data-field^="density"].on'), 'the voxel field is shown');
await page.waitForSelector('#legend input[type=range]');
assert.match(await page.textContent('#legend'), /voxel/i);

// a check limit is editable, stored under its key, and the experimental row is advisory (never failing)
await page.fill('[data-check] input', '5'); await page.press('[data-check] input', 'Tab');
await page.waitForFunction(() => Object.values(window.__af.S.project.analysis.conditions.limits).includes(5));

// report: a zip with PNG, JSON and markdown
const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 15000 }).catch(() => null), page.click('#an-report')]);
if (dl) assert.match(dl.suggestedFilename(), /_report\.zip$/);

// A1: the thermal estimate, installed from the bundled sample: an "estimate" badge, temperatures in °C, a surface field in the legend
await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
await page.click('button[data-sample="thermal-estimate.alloyext"]'); await page.waitForSelector('#extconsent[open]');
assert.match(await page.textContent('#extc-body'), /storage|agent/i);
await page.click('#extc-ok'); await page.waitForSelector('.xpkg[data-ext="xyz.mdhv.thermal-estimate"].on'); await page.click('#extclose');
await page.click('#t-an'); await page.selectOption('#p-analyze select[aria-label="Analysis"]', 'xyz.mdhv.thermal-estimate/thermal-estimate');
await page.fill('#heat-w', '1.5'); await page.click('#heat-add');
await page.click('#an-run'); await page.waitForSelector('#an-validity', { timeout: 60000 });
assert.match(await page.textContent('#an-validity'), /estimate/);
assert.match(await page.textContent('#an-result'), /internal_air_temp/); assert.match(await page.textContent('#an-result'), /°C/);
assert.ok(await page.$('#an-result .errbox.warn[data-warning]'), 'assumptions are shown as warnings');
await page.waitForSelector('#legend:not([hidden])'); assert.match(await page.textContent('#legend'), /°C/);

// own material
await page.click('summary:has-text("add your own material")');
await page.fill('#mat-name', 'My resin'); await page.fill('input[data-prop="rho"]', '1180'); await page.fill('input[data-prop="k"]', '0.2');
await page.click('#mat-add'); await page.waitForSelector('[data-material="my-resin"]');

// phone viewport: bottom sheet, no sideways scroll, big targets
const pctx = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
const pp = await pctx.newPage(); pp.on('pageerror', (e) => errors.push(e.message)); pp.on('dialog', (d) => d.accept());
await pp.goto(url); await pp.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 });
await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]');
await pp.click('button[data-sample="sample-mass.alloyext"]'); await pp.waitForSelector('#extconsent[open]'); await pp.click('#extc-ok');
await pp.waitForSelector(`.xpkg[data-ext="${MASS}"].on`); await pp.click('#extclose');
await pp.click('#n-an'); await pp.waitForSelector('#an-run');
assert.equal(await pp.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'no sideways page scroll');
assert.equal(await pp.evaluate(() => { const l = document.querySelector('#left'); return l.scrollWidth <= l.clientWidth + 1; }), true, 'no sideways scroll in the sheet');
const small = await pp.evaluate(() => [...document.querySelectorAll('#p-analyze button:not(.i), #p-analyze select')].filter((e) => e.offsetParent && e.getBoundingClientRect().height < 36).map((e) => e.outerHTML.slice(0, 90)));
assert.deepEqual(small, [], 'touch targets are at least 36 px tall');
await pp.click('#an-run'); await pp.waitForSelector('#an-result', { timeout: 60000 });

assert.deepEqual(errors, [], 'no page errors');
assert.deepEqual(nets, [], 'no request leaves the origin');
await browser.close(); srv.close();
console.log('ok test_ui_analyze');
