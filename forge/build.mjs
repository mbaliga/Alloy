import { build } from 'esbuild';
import fs from 'fs';
fs.rmSync('dist', { recursive: true, force: true }); fs.mkdirSync('dist');
const stub={name:'node-stub',setup(b){b.onResolve({filter:/^node:/},a=>({path:a.path,namespace:'stub'}));b.onLoad({filter:/.*/,namespace:'stub'},()=>({contents:'export const createRequire=()=>()=>{};export default {};',loader:'js'}));}};
await build({plugins:[stub],
  entryPoints: ['src/main.js'], bundle: true, format: 'iife', outfile: 'dist/app.js', minify: true,
  loader: { '.wasm': 'binary' }, target: 'es2020', platform: 'browser',
  define: { 'import.meta.url': '__iu', 'process.env.NODE_ENV': '"production"' }, logLevel: 'info',
  banner: { js: 'var __iu=location.href;/* Alloy Forge — Copyright 2026 Madhav Baliga, PolyForm Noncommercial 1.0.0. three.js (MIT), Manifold (Apache-2.0), occt-import-js (LGPL-2.1). */' },
});
fs.copyFileSync('index.src.html', 'dist/index.html');
for (const f of ['occt-import-js.js', 'occt-import-js.wasm']) fs.copyFileSync('node_modules/occt-import-js/dist/' + f, 'dist/' + f);
fs.copyFileSync('node_modules/occt-import-js/dist/license.occt-import-js.txt', 'dist/LICENSE.occt-import-js.txt');
console.log(fs.readdirSync('dist').map((f) => f + ' ' + (fs.statSync('dist/' + f).size / 1024).toFixed(0) + 'KB').join('\n'));
