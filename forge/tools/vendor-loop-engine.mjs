// Vendors web/loop-engine from mbaliga/Shared-Libraries-asoc into src/loop/ ("consumed by copying").
//   node tools/vendor-loop-engine.mjs /path/to/shared-libraries-asoc
// Each copied file gets a header comment naming the source repo and commit. Do not edit src/loop/ by hand: change the
// engine in Shared-Libraries-asoc and re-vendor.
import { readFileSync, writeFileSync, readdirSync, mkdirSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const src = process.argv[2];
if (!src) { console.error('usage: node tools/vendor-loop-engine.mjs <shared-libraries-asoc checkout>'); process.exit(1); }
const sha = execFileSync('git', ['-C', src, 'rev-parse', 'HEAD']).toString().trim();
const dirty = execFileSync('git', ['-C', src, 'status', '--porcelain', 'web/loop-engine']).toString().trim();
if (dirty) { console.error('source has uncommitted changes in web/loop-engine; commit them first so the recorded commit is true'); process.exit(1); }
const from = join(src, 'web', 'loop-engine');
const to = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'loop');
rmSync(to, { recursive: true, force: true }); mkdirSync(to, { recursive: true });
const header = `// Vendored from mbaliga/Shared-Libraries-asoc web/loop-engine @ ${sha}. Do not edit here: change the engine there and re-run tools/vendor-loop-engine.mjs.\n`;
for (const f of readdirSync(from).filter((x) => x.endsWith('.js'))) writeFileSync(join(to, f), header + readFileSync(join(from, f), 'utf8'));
writeFileSync(join(to, 'VENDORED.md'), `Vendored from \`mbaliga/Shared-Libraries-asoc\` \`web/loop-engine\` at commit \`${sha}\`.\nRe-vendor with \`node tools/vendor-loop-engine.mjs <checkout>\`. Licence: see the repository LICENSE; schemas originate from Fonebrew (\`LICENSE-PENDING\` upstream), see THIRD_PARTY.md.\n`);
console.log('vendored', readdirSync(to).length, 'files @', sha);
