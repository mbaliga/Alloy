// Browser test for the universal selection actions (Playwright). Not part of `npm test` (needs a browser):
//   npm run build && node test_ui_selbar.mjs     (CHROME=/path/to/chrome, PLAYWRIGHT=/path/to/playwright/index.mjs)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT || 'playwright');

const dist = path.resolve('dist');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm' };
const srv = http.createServer((q, s) => {
  const f = path.join(dist, q.url === '/' ? 'index.html' : q.url.split('?')[0]);
  if (!f.startsWith(dist) || !fs.existsSync(f)) { s.writeHead(404); return s.end(); }
  s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(s);
}).listen(0);
const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const errors = [];
const bodies = (page) => page.evaluate(() => window.__af.S.result.bodies.map((b) => b.name));

for (const vp of [{ width: 1280, height: 860, name: 'desktop' }, { width: 390, height: 844, name: 'phone', touch: true }]) {
  const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height }, hasTouch: !!vp.touch });
  page.on('pageerror', (e) => errors.push(`${vp.name}: ${e.message}`));
  await page.goto(`http://localhost:${srv.address().port}/`);
  await page.waitForFunction(() => window.__af?.S?.result?.bodies?.length >= 2, null, { timeout: 30000 });
  const n0 = (await bodies(page)).length;

  // nothing selected: no bar
  assert.equal(await page.isHidden('#selbar'), true, 'bar hidden with no selection');

  // select a body through the same path a click on the model takes
  await page.evaluate(() => { const S = window.__af.S; S.selBody = S.result.bodies[0].name; S.sel = null; });
  await page.keyboard.press('Escape'); // clears, then reselect via the menu path below
  const vpBox = await page.locator('#vp canvas').boundingBox();
  const cx = vpBox.x + vpBox.width / 2, cy = vpBox.y + vpBox.height / 2;
  // right-click (desktop) or long-press (phone) on the model opens the menu and selects the body under it
  if (vp.touch) { // a real long-press through the DevTools protocol
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: cx, y: cy }] });
    await page.waitForTimeout(700);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  }
  else await page.mouse.click(cx, cy, { button: 'right' });
  await page.waitForSelector('#ctxmenu:not([hidden])', { timeout: 3000 });
  const menu = await page.$$eval('#ctxmenu button', (b) => b.map((x) => x.dataset.act));
  for (const id of ['move', 'rotate', 'dup', 'copy', 'hide', 'lock', 'frame', 'del']) assert.ok(menu.includes(id), `${vp.name}: menu has ${id}`);
  await page.click('#ctxmenu button[data-act="dup"]');
  await page.waitForFunction((n) => window.__af.S.result.bodies.length === n + 1, n0);
  assert.equal(await page.isVisible('#selbar'), true, 'bar shows for the new selection');

  // the bar stays in Pick and Measure modes
  for (const mode of ['pick', 'measure', 'orbit']) {
    if (vp.touch) await page.evaluate((m) => document.querySelector(`[data-mode="${m}"]`).click(), mode);
    else await page.click(`[data-mode="${mode}"]`);
    assert.equal(await page.isVisible('#selbar'), true, `${vp.name}: bar visible in ${mode}`);
    assert.ok(await page.$('#selbar [data-act="del"]'), `${vp.name}: delete in ${mode}`);
  }

  // delete from the bar, undo it
  await page.click('#selbar [data-act="del"]');
  await page.waitForFunction((n) => window.__af.S.result.bodies.length === n, n0);
  await page.keyboard.press('Control+z');
  await page.waitForFunction((n) => window.__af.S.result.bodies.length === n + 1, n0);

  // keyboard copy / paste
  await page.evaluate(() => { const S = window.__af.S; S.sel = null; S.selBody = S.result.bodies[0].name; });
  await page.keyboard.press('Control+c'); await page.keyboard.press('Escape'); await page.keyboard.press('Control+v');
  await page.waitForFunction((n) => window.__af.S.result.bodies.length === n + 2, n0);

  // body drag: the gizmo writes a move transform (drive the gizmo's callbacks directly)
  const moved = await page.evaluate(async () => {
    const af = window.__af, S = af.S; const name = S.result.bodies[0].name;
    let cbs, at; const orig = af.view.attachGizmo; af.view.attachGizmo = (p, r, m, s, c) => { cbs = c; at = p; return orig(p, r, m, s, c); };
    S.sel = null; S.selBody = name; document.querySelector('[data-mode="measure"]').click(); // works outside Select mode too
    af.view.attachGizmo = orig;
    const x0 = S.result.bodies.find((b) => b.name === name).bbox.min[0];
    cbs.end({ pos: [at[0] + 10, at[1], at[2]], rot: [0, 0, 0] });
    await new Promise((r) => setTimeout(r, 400));
    const last = S.project.features.filter((f) => f.body === name).pop();
    return { dx: S.result.bodies.find((b) => b.name === name).bbox.min[0] - x0, last: last.type + ':' + last.mode };
  });
  assert.equal(moved.last, 'transform:move');
  assert.ok(Math.abs(moved.dx - 10) < 1e-6, `${vp.name}: body moved 10 mm (got ${moved.dx})`);
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT.replace('.png', `-${vp.name}.png`) });
  await page.close();
}
await browser.close(); srv.close();
assert.deepEqual(errors, [], 'no page errors');
console.log('ui selbar: ok');
