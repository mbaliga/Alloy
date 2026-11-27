// Browser smoke test for the Layers sheet (Playwright), part of `npm test`. It needs a browser:
//   PLAYWRIGHT=/opt/node-tools/node_modules/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_layers.mjs
// Without Playwright installed it says so and skips (exit 0), so `npm test` stays usable on a machine with no browser.
// On a 390 px phone it opens the sheet from the Layers button and checks: layers grouped by analysis/extension, each with a switch,
// an opacity slider and a unit/range readout, colour paired with a label, show all / hide all / solo, no sideways scroll and
// 40 px touch targets; that a `vectors` overlay draws, shows a legend and a probe that prints the speed and the vector; that state
// is kept per layer id in localStorage and survives clearing and re-adding the overlays (a re-run) and a page reload; and that the
// editor still works when localStorage throws. No page errors, no network requests beyond the app's own origin.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import assert from 'node:assert/strict';
let chromium;
try { ({ chromium } = await import(process.env.PLAYWRIGHT || 'playwright')); } catch (_) { console.log('SKIP test_ui_layers: Playwright is not available (set PLAYWRIGHT=/path/to/playwright/index.mjs).'); process.exit(0); }
if (!process.env.LAYERS_NO_BUILD) execSync('node build.mjs', { stdio: 'ignore' });

const dist = path.resolve('dist');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.alloyext': 'application/zip' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const url = `http://localhost:${srv.address().port}/`;
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const errors = [], nets = [];
const open = async (ctx, init) => {
  const page = await ctx.newPage(); if (init) await page.addInitScript(init);
  page.on('pageerror', (e) => errors.push(e.message)); page.on('dialog', (d) => d.accept());
  page.on('request', (r) => { const u = new URL(r.url()); if (u.origin !== new URL(url).origin && !/^(data|blob):/.test(r.url())) nets.push(r.url()); });
  await page.goto(url); await page.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 }); return page;
};
// three layers from two sources: an analysis, and an extension with a points layer and a vectors layer; the spy records what the view is asked to draw
const addLayers = (page) => page.evaluate(() => {
  const A = window.__af, ui = A.ext.ui, v = A.view;
  if (!v.__spied) { const o = v.setOverlays; v.setOverlays = (l) => { window.__drawn = l.map((x) => ({ key: x.key, kind: x.ov.kind, opacity: x.opacity })); return o(l); }; v.__spied = true; }
  const bb = v.getBounds();
  const mid = bb.min.map((m, i) => (m + bb.max[i]) / 2), sz = Math.max(bb.max[0] - bb.min[0], 1);
  const pos = new Float32Array(3 * 6), vec = new Float32Array(3 * 6), val = new Float32Array(6);
  for (let i = 0; i < 6; i++) { pos.set([mid[0] + (i - 2.5) * sz * 0.12, mid[1], mid[2] + sz * 0.3], 3 * i); vec.set([0.5 + i * 0.4, 0, 0.2 * i], 3 * i); }
  for (let i = 0; i < 6; i++) val[i] = 20 + i * 13;
  ui.setOverlay('analysis', 'Temperature', { kind: 'points', label: 'Temperature', unit: '°C', validity: 'estimate', positions: pos, values: val, range: null });
  const vpos = Float32Array.from(pos, (x, i) => (i % 3 === 2 ? x - sz * 0.25 : x)); // below the temperature points, so a tap can tell the two layers apart
  ui.setOverlay('xyz.test.air', 'flow', { kind: 'vectors', label: 'Air velocity', unit: 'm/s', validity: 'experimental', positions: vpos, vectors: vec, values: Float32Array.from({ length: 6 }, (_, i) => Math.hypot(vec[3 * i], vec[3 * i + 1], vec[3 * i + 2])), lines: null, scale: null, range: null });
  ui.setOverlay('xyz.test.air', 'box', { kind: 'box', label: 'Inlet zone', unit: '', validity: 'experimental', min: [mid[0] - 5, mid[1] - 5, mid[2] - 5], max: [mid[0] + 5, mid[1] + 5, mid[2] + 5] });
});
const state = (page) => page.evaluate(() => JSON.parse(localStorage.getItem('af.layers') || '{}').layers || {});
const drawn = (page) => page.evaluate(() => window.__drawn);
const row = (k) => `#layersbody .ly[data-layer="${k}"]`;
const KT = 'analysis:Temperature', KV = 'xyz.test.air:flow', KB = 'xyz.test.air:box';

// ───── phone
const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
let page = await open(phone);
assert.match(await page.textContent('#layers-btn'), /^Layers$/, 'a Layers button, with nothing drawn yet');
await page.tap('#layers-btn'); await page.waitForSelector('#layersdlg[open]');
assert.match(await page.textContent('#layersbody'), /No overlay layers yet/);
await page.tap('#layersbody [data-act=close]'); assert.equal(await page.evaluate(() => document.querySelector('#layersdlg').open), false);
await addLayers(page);
assert.match(await page.textContent('#layers-btn'), /Layers 3\/3/);
await page.tap('#layers-btn'); await page.waitForSelector('#layersdlg[open]');

// grouped, labelled, with unit and range, colour paired with a word
const body = await page.textContent('#layersbody');
assert.deepEqual(await page.$$eval('#layersbody .lygrp', (e) => e.map((x) => x.textContent)), ['Analysis', 'xyz.test.air']);
for (const t of ['Temperature', 'Air velocity', 'Inlet zone', 'unit °C', 'range 20 to 85 °C', 'arrows', 'speed unit m/s', 'box · no value', 'Opacity', 'Show all', 'Hide all']) assert.ok(body.includes(t), `sheet says "${t}"`);
assert.equal(await page.locator(`${row(KT)} .lysw1`).count(), 1, 'colour map swatch beside the label'); assert.equal(await page.locator(`${row(KB)} .lysw1`).count(), 0);
for (const k of [KT, KV, KB]) { assert.equal(await page.locator(`${row(k)} [data-act=switch]`).count(), 1); assert.equal(await page.locator(`${row(k)} [data-act=opacity]`).count(), 1); assert.equal(await page.locator(`${row(k)} [data-act=solo]`).count(), 1); }
await page.tap('#layersbody .i'); assert.ok(await page.isVisible('#layersbody .tip'), 'explanatory copy sits behind an i tip');
assert.match(await page.textContent('#layersbody .tip'), /not in the project/);
// layout: sheet fits the phone, no sideways scroll, touch targets
const lay = await page.evaluate(() => { const d = document.querySelector('#layersdlg').getBoundingClientRect(); return { l: d.left, r: d.right, b: d.bottom, w: innerWidth, sx: document.documentElement.scrollWidth, ox: document.querySelector('#layersbody').scrollWidth - document.querySelector('#layersbody').clientWidth }; });
assert.ok(lay.l >= 0 && lay.r <= lay.w + 0.5 && lay.sx <= lay.w && lay.ox <= 1, 'no sideways scroll: ' + JSON.stringify(lay));
for (const sel of ['#layersbody [data-act=solo]', '#layersbody .lysw', '#layersbody [data-act=all]', '#layersbody [data-act=none]', '#layersbody [data-act=opacity]']) for (const b of await page.$$(sel)) { const r = await b.boundingBox(); assert.ok(r.height >= 39.5, `${sel} is ${r.height}px tall`); }

// switch, opacity: the view is asked to draw accordingly and the state is stored by layer id
assert.deepEqual((await drawn(page)).map((d) => d.key).sort(), [KB, KT, KV].sort());
await page.tap(`${row(KT)} .lysw`); await page.waitForSelector(`${row(KT)}.off`);
assert.deepEqual((await drawn(page)).map((d) => d.key).sort(), [KB, KV].sort()); assert.equal((await state(page))[KT].on, false);
assert.ok(await page.locator('#legend .lg.off[data-overlay="analysis:Temperature"]').count(), 'the legend row dims in step');
assert.match(await page.textContent(`${row(KT)} .lysw`), /OFF/);
await page.tap(`${row(KT)} .lysw`); await page.waitForSelector(`${row(KT)}:not(.off)`);
await page.evaluate((sel) => { const e = document.querySelector(sel); e.value = 40; e.dispatchEvent(new Event('input', { bubbles: true })); }, `${row(KV)} [data-act=opacity]`);
assert.equal((await drawn(page)).find((d) => d.key === KV).opacity, 0.4); assert.equal((await state(page))[KV].opacity, 0.4);
assert.equal(await page.textContent(`${row(KV)} [data-read=opacity]`), '40%');
// show all / hide all / solo
await page.tap('#layersbody [data-act=none]'); assert.deepEqual(await drawn(page), []); assert.equal(await page.locator('#layersbody .ly.off').count(), 3);
await page.tap('#layersbody [data-act=all]'); assert.equal((await drawn(page)).length, 3); assert.equal((await state(page))[KV].opacity, 0.4, 'show all keeps opacity');
await page.tap(`${row(KV)} [data-act=solo]`); assert.deepEqual((await drawn(page)).map((d) => d.key), [KV]); assert.match(await page.textContent(`${row(KV)} [data-act=solo]`), /Solo on/);
await page.tap(`${row(KV)} [data-act=solo]`); assert.equal((await drawn(page)).length, 3, 'solo again brings the rest back');

// vectors: arrows in the viewport, a legend, and a probe with the speed and the vector (a real tap on the canvas)
assert.ok((await page.textContent('#legend')).includes('Air velocity') && /m\/s/.test(await page.textContent('#legend')) && /arrows/.test(await page.textContent('#legend')));
await page.tap('#layersbody [data-act=close]');
const spot = await page.evaluate(() => { const v = window.__af.view, r = document.querySelector('#vp canvas').getBoundingClientRect(); for (let y = r.top + 10; y < r.bottom - 10; y += 6) for (let x = r.left + 10; x < r.right - 10; x += 6) { const p = v.probe(x, y); if (p && p.vector) return { x, y, p: { label: p.label, vector: p.vector, value: p.value } }; } return null; });
assert.ok(spot, 'a vectors sample can be probed'); assert.equal(spot.p.label, 'Air velocity'); assert.equal(spot.p.vector.length, 3);
assert.ok(Math.abs(Math.hypot(...spot.p.vector) - spot.p.value) < 1e-3, 'the probe value is the vector magnitude');
await page.touchscreen.tap(spot.x, spot.y); await page.waitForSelector('#probe:not([hidden])');
assert.match(await page.textContent('#probe'), /Air velocity .*m\/s.*vector .*experimental/);

// a re-run: clear and add again; the state is by layer id and comes back
await page.evaluate(() => { const ui = window.__af.ext.ui; ui.clearOverlaysOf('xyz.test.air'); ui.clearOverlaysOf('analysis'); });
assert.equal(await page.evaluate(() => window.__af.ext.ui.overlayCount()), 0);
await page.tap(`#layers-btn`); assert.match(await page.textContent('#layersbody'), /No overlay layers yet/);
await page.evaluate(() => { window.__af.ext.ui.layers.set('analysis:Temperature', { on: false }); });
await addLayers(page);
assert.deepEqual((await drawn(page)).map((d) => d.key).sort(), [KB, KV].sort(), 'Temperature stayed off through the re-run');
assert.equal((await drawn(page)).find((d) => d.key === KV).opacity, 0.4, 'opacity survived the re-run');
assert.equal(await page.inputValue(`${row(KV)} [data-act=opacity]`), '40');

// a reload: stored per layer id in this browser
await page.close(); page = await open(phone); await addLayers(page);
assert.deepEqual((await drawn(page)).map((d) => d.key).sort(), [KB, KV].sort(), 'state survived a reload'); assert.equal((await drawn(page)).find((d) => d.key === KV).opacity, 0.4);
assert.equal(await page.evaluate(() => JSON.stringify(window.__af.S.project).includes('af.layers') || JSON.stringify(window.__af.S.project).includes('Air velocity')), false, 'nothing about layers is saved in the project');
await page.close();

// ───── localStorage that throws: everything still works for the page
const blocked = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
page = await open(blocked, () => { Storage.prototype.getItem = () => { throw new Error('blocked'); }; Storage.prototype.setItem = () => { throw new Error('blocked'); }; });
await addLayers(page); await page.tap('#layers-btn'); await page.waitForSelector('#layersdlg[open]');
await page.tap(`${row(KV)} .lysw`); assert.deepEqual((await drawn(page)).map((d) => d.key).sort(), [KB, KT].sort(), 'switching works without storage');
await page.close();

// ───── desktop: the sheet is a side panel and the viewport stays usable
const desk = await browser.newContext({ viewport: { width: 1280, height: 860 } });
page = await open(desk); await addLayers(page); await page.click('#layers-btn'); await page.waitForSelector('#layersdlg[open]');
const box = await page.locator('#layersdlg').boundingBox(); assert.ok(box.width <= 345 && box.x + box.width <= 1280, 'a side panel, not full width');
await page.click('#layers-btn'); assert.equal(await page.evaluate(() => document.querySelector('#layersdlg').open), false, 'the button toggles the sheet');
await page.close();

await browser.close(); srv.close();
assert.deepEqual(errors, [], 'no page errors'); assert.deepEqual(nets, [], 'no network requests beyond the app');
console.log('layers ui: ok');
