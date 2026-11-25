// Pack an extension folder into a .alloyext:  node tools/pack-ext.mjs <dir> <out.alloyext>
// <dir> holds alloy-extension.json (without "integrity"; it is computed here) and the files it references.
import fs from 'node:fs';
import path from 'node:path';
import { buildPackage } from '../src/ext/loader.js';

export async function packDir(dir) {
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'alloy-extension.json'), 'utf8'));
  delete manifest.integrity;
  const files = {};
  const walk = (d, rel = '') => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f), r = rel ? rel + '/' + f : f; if (fs.statSync(p).isDirectory()) walk(p, r); else if (r !== 'alloy-extension.json') files[r] = fs.readFileSync(p); } };
  walk(dir);
  return buildPackage(manifest, files);
}
if (import.meta.url === `file://${process.argv[1]}`) {
  const [dir, out] = process.argv.slice(2);
  if (!dir || !out) { console.error('usage: node tools/pack-ext.mjs <dir> <out.alloyext>'); process.exit(2); }
  fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
  fs.writeFileSync(out, await packDir(dir));
  console.log('wrote', out);
}
