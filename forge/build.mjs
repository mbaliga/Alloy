import { build } from 'esbuild';
import fs from 'fs';
fs.rmSync('dist', { recursive: true, force: true }); fs.mkdirSync('dist');
const stub={name:'node-stub',setup(b){b.onResolve({filter:/^node:/},a=>({path:a.path,namespace:'stub'}));b.onLoad({filter:/.*/,namespace:'stub'},()=>({contents:'export const createRequire=()=>()=>{};export default {};',loader:'js'}));}};
await build({plugins:[stub],
  entryPoints: ['src/main.js'], bundle: true, format: 'iife', outfile: 'dist/app.js', minify: true,
  loader: { '.wasm': 'binary' }, target: 'es2020', platform: 'browser',
  define: { 'import.meta.url': '__iu', 'process.env.NODE_ENV': '"production"' }, logLevel: 'info',
  banner: { js: 'var __iu=location.href;/* Alloy Forge — Copyright 2026 Madhav Baliga, PolyForm Noncommercial 1.0.0. three.js (MIT), Manifold (Apache-2.0), occt-import-js (LGPL-2.1), replicad (MIT, worker only). The OCCT B-rep kernel is a separate worker (occt-worker.js) + occt/*.wasm, loaded only on first use. */' },
});
// OCCT B-rep kernel worker. replicad (MIT) is bundled into the worker; the Open CASCADE build (LGPL-2.1) stays a separate,
// replaceable glue .js + .wasm pair under dist/occt/, loaded by the worker at runtime and only when a document uses the kernel.
await build({ entryPoints: ['src/occt/worker.js'], bundle: true, format: 'iife', outfile: 'dist/occt-worker.js', minify: true, target: 'es2020', platform: 'browser', logLevel: 'info',
  banner: { js: '/* Alloy Forge OCCT worker — PolyForm Noncommercial 1.0.0. replicad (MIT), d3-delaunay (ISC). Open CASCADE loads from occt/ at runtime (LGPL-2.1). */' } });
fs.mkdirSync('dist/occt', { recursive: true });
for (const f of ['replicad_single.js', 'replicad_single.wasm']) fs.copyFileSync('node_modules/replicad-opencascadejs/dist/' + f, 'dist/occt/' + f);
fs.copyFileSync('node_modules/replicad-opencascadejs/LICENSE', 'dist/occt/LICENSE.replicad-opencascadejs.txt');
fs.copyFileSync('index.src.html', 'dist/index.html');
// sample extension packages (own work, PolyForm Noncommercial) so Settings > Extensions can install them same-origin
const { packDir } = await import('./tools/pack-ext.mjs');
fs.mkdirSync('dist/ext-samples', { recursive: true });
for (const d of ['sample-rib', 'sample-probe', 'sample-mass', 'thermal-estimate']) fs.writeFileSync(`dist/ext-samples/${d}.alloyext`, await packDir(`examples/ext/${d}`));
for (const f of ['occt-import-js.js', 'occt-import-js.wasm']) fs.copyFileSync('node_modules/occt-import-js/dist/' + f, 'dist/' + f);
fs.copyFileSync('node_modules/occt-import-js/dist/license.occt-import-js.txt', 'dist/LICENSE.occt-import-js.txt');
const walk = (d) => fs.readdirSync(d).flatMap((f) => (fs.statSync(d + '/' + f).isDirectory() ? walk(d + '/' + f) : [d + '/' + f]));
console.log(walk('dist').map((f) => f.slice(5) + ' ' + (fs.statSync(f).size / 1024).toFixed(0) + 'KB').join('\n'));
