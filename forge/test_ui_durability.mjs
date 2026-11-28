// Browser smoke test for A6a (Playwright). Not part of `npm test`:
//   npm run build && PLAYWRIGHT=/opt/node-tools/node_modules/playwright/index.mjs CHROME=/usr/bin/google-chrome node test_ui_durability.mjs
// Install the Joint and creep package, pick a placeholder duty preset, add and edit a joint, run, read the Risk list, tap a pin for the
// detail card, apply a fix as one undoable step, export the table; then the same on a 390 px phone: no sideways scroll, touch targets of at
// least 36 px, and pins that do not cover the model.
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
const EXT = 'xyz.mdhv.joint-creep';
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const errors = [], nets = [];

async function open(viewport, mobile) {
  const ctx = await browser.newContext({ viewport, ...(mobile ? { hasTouch: true, isMobile: true } : {}) });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(e.message)); page.on('dialog', (d) => d.accept());
  page.on('request', (r) => { const u = new URL(r.url()); if (u.origin !== new URL(url).origin && !/^(data|blob):/.test(r.url())) nets.push(r.url()); });
  await page.goto(url); await page.waitForSelector('#p-tree .row', { state: 'attached', timeout: 30000 });
  await page.click('#gear'); await page.click('#s-ext'); await page.waitForSelector('#extdlg[open]');
  await page.click('button[data-sample="joint-creep.alloyext"]'); await page.waitForSelector('#extconsent[open]');
  assert.match(await page.textContent('#extc-body'), /Plugin/i); await page.click('#extc-ok');
  await page.waitForSelector(`.xpkg[data-ext="${EXT}"].on`); await page.click('#extclose');
  await page.click(mobile ? '#n-an' : '#t-an'); await page.waitForSelector('#an-run');
  return { ctx, page };
}
const noSideways = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1 && (() => { const l = document.querySelector('#p-analyze'); return !l || l.scrollWidth <= l.clientWidth + 1; })());
const smallTargets = (page, sel) => page.evaluate((s) => [...document.querySelectorAll(s)].filter((e) => e.offsetParent && !e.classList.contains('i') && (e.getBoundingClientRect().height < 36 || e.getBoundingClientRect().width < 36 && e.tagName !== 'INPUT' && e.tagName !== 'SELECT')).map((e) => `${e.tagName}.${e.className}:${e.getAttribute('aria-label') || e.textContent.slice(0, 20)} ${Math.round(e.getBoundingClientRect().width)}x${Math.round(e.getBoundingClientRect().height)}`), sel);

async function flow(page, { phone }) {
  const body = await page.evaluate(() => window.__af.S.result.bodies[0].name);
  // the duty profile: a placeholder preset, labelled as such
  assert.match(await page.textContent('#duty-none'), /No duty profile/);
  await page.selectOption('#duty-preset', 'handheld-light');
  await page.waitForFunction(() => window.__af.S.project.analysis.conditions.duty && window.__af.S.project.analysis.conditions.duty.preset === 'handheld-light');
  assert.match(await page.textContent('#duty-flag'), /PLACEHOLDER/);
  assert.equal(await page.getAttribute('#duty-flag', 'data-placeholder'), 'true');
  await page.fill('input[aria-label="Recoil peak in g"]', '600'); await page.press('input[aria-label="Recoil peak in g"]', 'Tab');
  await page.waitForFunction(() => window.__af.S.project.analysis.conditions.duty.shots.peak_g === 600);
  // a material for the body, then a joint
  await page.selectOption(`select[data-body-material="${body}"]`, 'petg');
  await page.waitForFunction((b) => window.__af.S.project.bodies[b].material === 'petg', body);
  await page.click('#joint-add'); await page.waitForSelector('[data-joint="J1"]'); await page.waitForSelector('[data-joint-edit="J1"]');
  assert.equal(await page.evaluate(() => window.__af.S.project.joints.length), 1);
  await page.fill('input[aria-label="Joint name"]', 'Clamp cross-bolt'); await page.press('input[aria-label="Joint name"]', 'Tab');
  await page.waitForFunction(() => window.__af.S.project.joints[0].name === 'Clamp cross-bolt');
  await page.selectOption('select[aria-label="Insert type"]', 'none');
  await page.fill('input[aria-label="Joint position x mm"]', '1.5'); await page.press('input[aria-label="Joint position x mm"]', 'Tab');
  await page.waitForFunction(() => window.__af.S.project.joints[0].where[0] === 1.5);
  // typed-op validation: a bad value is refused and the project is unchanged
  await page.fill('input[aria-label="Friction between the clamped parts"]', '9'); await page.press('input[aria-label="Friction between the clamped parts"]', 'Tab');
  assert.ok((await page.evaluate(() => window.__af.S.project.joints[0].mu_clamp)) <= 1, 'a bad friction value is refused');
  // run
  await page.selectOption('#p-analyze select[aria-label="Analysis"]', `${EXT}/joint-creep`);
  await page.click('#an-run'); await page.waitForSelector('#risk-pane', { timeout: 60000 });
  assert.match(await page.textContent('#an-validity'), /estimate/);
  const rows = await page.$$eval('.rflag', (els) => els.map((e) => e.dataset.severity));
  assert.ok(rows.length >= 6, `flags listed: ${rows.length}`);
  const order = ['high', 'medium', 'low', 'info']; assert.deepEqual(rows, rows.slice().sort((a, b) => order.indexOf(a) - order.indexOf(b)), 'flags are listed by severity');
  assert.match(await page.textContent('#risk-counts'), /high/);
  for (const el of await page.$$('.rflag .ico')) assert.ok((await el.textContent()).trim().length >= 1, 'each flag has a shape as well as a word');
  // a flag opens with why, numbers, confidence and fix
  await page.click('.rflag[data-severity="high"]');
  const det = await page.textContent('.rdetail'); assert.match(det, /margin/); assert.match(det, /confidence/); assert.match(det, /fix/);
  assert.match(await page.textContent('[data-conf-why]'), /Capped at low|Medium|High/);
  // pins: present, small, a 36 px hit area, inside the viewport, covering a small share of it
  if (phone) { await page.click('#risk-view'); }
  await page.waitForSelector('button.pin');
  const geo = await page.evaluate(() => { const vp = { w: innerWidth, h: innerHeight }; return [...document.querySelectorAll('button.pin')].filter((p) => p.style.display !== 'none').map((p) => { const r = p.getBoundingClientRect(), g = p.querySelector('.pg').getBoundingClientRect(); return { hit: [r.width, r.height], vis: [g.width, g.height], cx: r.x + r.width / 2, cy: r.y + r.height / 2, vp }; }); });
  assert.ok(geo.length >= 1, 'a pin is drawn');
  for (const g of geo) { assert.ok(g.hit[0] >= 36 && g.hit[1] >= 36, `pin hit area ${g.hit}`); assert.ok(g.vis[0] <= 24 && g.vis[1] <= 24, 'the drawn pin is small'); assert.ok(g.cx > 0 && g.cx < g.vp.w && g.cy > 0 && g.cy < g.vp.h); }
  const share = geo.reduce((a, g) => a + g.vis[0] * g.vis[1], 0) / (geo[0].vp.w * geo[0].vp.h); assert.ok(share < 0.01, `pins cover ${(share * 100).toFixed(2)} % of the viewport`);
  // tap a pin: the detail card with why, numbers, confidence, fix
  await page.click('button.pin');
  await page.waitForSelector('#pincard:not([hidden])');
  const card = await page.textContent('#pincard'); assert.match(card, /margin/); assert.match(card, /confidence/); assert.match(card, /fix/);
  assert.ok(await page.$('#pincard [data-apply-fix]'), 'the card offers a fix');
  const cardBox = await page.evaluate(() => { const r = document.querySelector('#pincard').getBoundingClientRect(); return { h: r.height, vh: innerHeight, bottom: r.bottom }; });
  assert.ok(cardBox.h <= cardBox.vh * 0.4, `the card leaves the model visible (${Math.round(cardBox.h)} of ${cardBox.vh} px)`);
  // apply the sleeve fix as one undoable step
  const undoBefore = await page.evaluate(() => window.__af.S.undo.length);
  while (!(await page.$('#pincard [data-apply-fix$="|sleeve"]'))) { const next = await page.$('#pincard button[aria-label="Next flag at this pin"]'); assert.ok(next, 'a flag with a sleeve fix exists at this pin'); await next.click(); }
  await page.click('#pincard [data-apply-fix$="|sleeve"]');
  await page.waitForFunction(() => window.__af.S.project.joints[0].insert === 'metal_sleeve');
  assert.equal(await page.evaluate(() => window.__af.S.undo.length), undoBefore + 1, 'one undo step');
  await page.waitForSelector('#pincard', { state: 'hidden' });
  await page.click('#undo'); await page.waitForFunction(() => window.__af.S.project.joints[0].insert === 'none');
  assert.equal(await page.evaluate(() => window.__af.S.undo.length), undoBefore, 'undo returns to the state before the fix');
  if (phone) { await page.click('#n-an'); await page.waitForSelector('select[aria-label="Insert type"]'); }
  // a locked body refuses the fix and says so
  await page.evaluate((b) => { const P = JSON.parse(JSON.stringify(window.__af.S.project)); P.bodies[b] = { ...(P.bodies[b] || {}), locked: true }; window.__af.commit(P); }, body);
  await page.selectOption('select[aria-label="Insert type"]', 'metal_sleeve');
  assert.equal(await page.evaluate(() => window.__af.S.project.joints[0].insert), 'none', 'a locked body refuses the joint change');
  await page.evaluate((b) => { const P = JSON.parse(JSON.stringify(window.__af.S.project)); delete P.bodies[b].locked; window.__af.commit(P); }, body);
  // the table export (the edits above cleared the result, so run again)
  await page.click('#an-run'); await page.waitForSelector('#risk-pane', { timeout: 60000 });
  const nFlags = (await page.$$('.rflag')).length; assert.ok(nFlags > 0, 'there are flags to export');
  const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 15000 }), page.click('#risk-csv')]);
  assert.match(dl.suggestedFilename(), /_risk_flags\.csv$/);
  const csvLines = fs.readFileSync(await dl.path(), 'utf8').trim().split(/\r?\n/);
  assert.match(csvLines[0], /severity/i, 'CSV header row'); assert.ok(csvLines.length >= 1 + nFlags, `CSV has the header and every flag (${csvLines.length} lines, ${nFlags} flags)`);
  const [dm] = await Promise.all([page.waitForEvent('download', { timeout: 15000 }), page.click('#risk-md')]);
  assert.match(dm.suggestedFilename(), /_risk_flags\.md$/);
  const md = fs.readFileSync(await dm.path(), 'utf8'); assert.ok(md.length > 50 && /\|/.test(md) && /estimate/i.test(md), 'markdown table with the validity stated');
  // pins can be switched off
  await page.click('#risk-pins'); assert.equal((await page.$$('button.pin')).length, 0); await page.click('#risk-pins');
}

// desktop
{
  const { ctx, page } = await open({ width: 1280, height: 860 }, false);
  await flow(page, { phone: false });
  assert.equal(await noSideways(page), true, 'no sideways scroll on desktop ' + JSON.stringify(await page.evaluate(() => { const l = document.querySelector('#p-analyze'); const lr = l.getBoundingClientRect(); return { sw: l.scrollWidth, cw: l.clientWidth, wide: [...l.querySelectorAll('*')].filter((e) => e.getBoundingClientRect().right > lr.right + 1).slice(0, 6).map((e) => e.tagName + '.' + e.className + ':' + (e.getAttribute('aria-label') || e.textContent.slice(0, 20)) + ' ' + Math.round(e.getBoundingClientRect().right - lr.right)) }; })));
  await ctx.close();
}
// phone
{
  const { ctx, page } = await open({ width: 390, height: 844 }, true);
  assert.equal(await noSideways(page), true, 'no sideways page scroll');
  assert.deepEqual(await smallTargets(page, '#p-analyze button, #p-analyze select, #p-analyze summary'), [], 'touch targets are at least 36 px (before the joint editor opens)');
  await flow(page, { phone: true });
  assert.equal(await noSideways(page), true, 'no sideways scroll after the flow');
  assert.deepEqual(await smallTargets(page, '#p-analyze button, #p-analyze select, #p-analyze summary, #pincard button, button.pin'), [], 'touch targets stay at least 36 px with the editor and the risk list open');
  await ctx.close();
}
assert.deepEqual(errors, [], 'no page errors');
assert.deepEqual(nets, [], 'no request leaves the origin');
await browser.close(); srv.close();
console.log('ok test_ui_durability');
