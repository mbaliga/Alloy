// Browser smoke test for the Loop pane (Playwright). Not part of `npm test` (needs a browser):
//   npm run build && npm i --no-save playwright && node test_ui_loop.mjs
// Set CHROME=/path/to/chrome to use an installed browser; PLAYWRIGHT=/path/to/playwright/index.mjs to import it from elsewhere.
// The model endpoint is mocked at the network layer: round 1 proposes a too-thin wall (fails the min-wall check), round 2 fixes it.
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
const url = `http://localhost:${srv.address().port}/`;

const browser = await chromium.launch({ executablePath: process.env.CHROME || undefined, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 860 } });
const errors = []; page.on('pageerror', (e) => errors.push(e.message));
let calls = 0;
await page.route('https://api.anthropic.com/**', (route) => {
  const wall = calls++ === 0 ? '0.4' : '2.4';
  const text = JSON.stringify({ explanation: `set wall ${wall}`, ops: [{ op: 'set_param', name: 'wall', expr: wall }] });
  route.fulfill({ json: { content: [{ type: 'text', text }], usage: { input_tokens: 900, output_tokens: 80 } } });
});
await page.addInitScript(() => localStorage.setItem('af.cfg', JSON.stringify({ provider: 'anthropic', base: '', model: 'm', key: 'test-key' })));
await page.goto(url);
await page.waitForSelector('#p-tree .row', { timeout: 20000 });
await page.click('[data-pane=loop]');
await page.waitForSelector('#p-loop .lgraph');
await page.fill('#p-loop textarea', 'sturdy enclosure');
await page.click('#p-loop .btn:has-text("Run loop")');
await page.waitForSelector('#p-loop .approval:not([hidden])', { timeout: 30000 });
const rounds = await page.$$eval('#p-loop .trace .round', (r) => r.map((x) => x.textContent));
assert.equal(rounds.length, 2, 'two rounds in the live trace');
assert.match(rounds[0], /checks fail/); assert.match(rounds[0], /Minimum wall/);
assert.match(rounds[1], /checks pass/);
assert.match(await page.textContent('#p-loop .meters'), /Tokens/);
await page.click('#p-loop .approval .btn:has-text("Approve")');
await page.waitForSelector('#p-loop .afterline:not([hidden])');
assert.match(await page.textContent('#p-loop .lstate'), /SUCCEEDED VERIFIED/);
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await page.click('#p-loop .afterline .lnk:has-text("Undo")');

// variants: N=2 runs two loops in parallel (no approval prompt) and lists results to pick from
calls = 1; // every reply is now the good one
await page.click('#p-loop .afterline .lnk:has-text("Undo")').catch(() => {});
await page.fill('#p-loop .lf:has-text("Variants") input', '2');
await page.click('#p-loop .btn:has-text("Run loop")');
await page.waitForSelector('#p-loop .variants .vh .btn', { timeout: 30000 });
assert.equal((await page.$$('#p-loop .variants .vh')).length, 2);
await page.click('#p-loop .variants .vh .btn >> nth=0');
await page.waitForSelector('#p-loop .afterline:not([hidden])');
assert.match(await page.textContent('#p-loop .afterline'), /Applied variant/);

// ── OCCT kernel: New from template -> PCB enclosure builds in the worker (wasm loads on first use) -> scorecard panel
assert.equal(await page.$eval('#kernel', (e) => e.value), 'manifold', 'Manifold stays the default kernel');
assert.equal((await page.textContent('#kstate')).trim(), '', 'no kernel status until the OCCT kernel is used');
await page.selectOption('#tmpl', 'pcb-enclosure');
await page.waitForSelector('#p-check .crow', { timeout: 180000 });
assert.equal(await page.$eval('#kernel', (e) => e.value), 'occt');
assert.match(await page.textContent('#kstate'), /ready/);
const rowsN = await page.$$eval('#p-check .crow', (r) => r.length);
assert.ok(rowsN >= 14, `scorecard rows: ${rowsN}`);
assert.equal((await page.$$('#p-check .crow.fail')).length, 0, 'every row passes on the demo board');
assert.match(await page.textContent('#card-verdict'), /all checks pass/);
assert.match(await page.textContent('#card-metrics'), /plastic[\s\S]*cm³/);
assert.match(await page.textContent('#card-metrics'), /envelope[\s\S]*mm/);
const bodies = await page.$$eval('#stats .stat b', (r) => r.map((x) => x.textContent));
assert.deepEqual(bodies, ['Shell', 'Cap']);
// export STEP: true B-rep from the OCCT kernel, not the faceted writer
await page.click('#exp'); 
const dl = page.waitForEvent('download');
await page.click('#expm button:has-text("STEP")');
const stepTxt = fs.readFileSync(await (await dl).path(), 'utf8');
assert.match(stepTxt, /ADVANCED_FACE/); assert.match(stepTxt, /CYLINDRICAL_SURFACE|B_SPLINE_SURFACE/);
// tagging an imported STEP assembly in the Objects pane -> keep-outs -> an enclosure feature on OCCT
await page.evaluate(() => { const A = window.__af, np = JSON.parse(JSON.stringify(A.S.project)); np.features = np.features.filter((f) => f.type !== 'enclosure'); np.pcb = { name: '', board: null, components: [], cutouts: [], supports: [], keep: [] }; np.settings.kernel = 'manifold'; A.commit(np); });
await page.waitForFunction(() => window.__af.S.project.settings.kernel === 'manifold' && window.__af.S.result && window.__af.S.result.kernel !== 'occt');
await page.click('[data-pane=objects]');
const fc = page.waitForEvent('filechooser');
await page.click('#impasm');
await (await fc).setFiles(path.resolve('node_modules/occt-import-js/test/testfiles/cax-if/as1-tu-203.stp'));
await page.waitForSelector('#p-objects select[data-part]', { timeout: 30000 });
const nParts = await page.$$eval('#p-objects select[data-part]', (r) => r.length);
assert.ok(nParts >= 2, 'assembly solids are listed for tagging');
await page.selectOption('#p-objects select[data-part="0"]', 'board');
await page.selectOption('#p-objects select[data-part="1"]', 'component');
await page.click('#gen-keepouts');
await page.waitForFunction(() => window.__af.S.project.features.some((f) => f.type === 'enclosure') && window.__af.S.project.pcb.components.length >= 2);
assert.equal(await page.$eval('#kernel', (e) => e.value), 'occt', 'generating keep-outs from a tagged assembly switches the document to OCCT');
assert.deepEqual(errors, []);
console.log('ui loop smoke ok');
await browser.close(); srv.close();
