// Generates the unrolled D3Q19 bulk kernel inside analysis/voxel-core/lbm.js (between the GENERATED markers).
//   node tools/gen-lbm-kernel.mjs          rewrite the block         (npm run build:ext runs it too)
//   node tools/gen-lbm-kernel.mjs --check  exit 1 if the block is stale (test_lbm.mjs runs this)
// Why generated: a loop over the 19 directions with table lookups runs at about 3 million cell updates per second in JavaScript; the
// same arithmetic written out for each direction runs about four times faster, which is what the CPU fallback needs. The generator
// is the only place the lattice vectors and weights are written out; lbm.js keeps the loop version as the reference (S.useKernel = false)
// and test_lbm.mjs compares the two.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as L from '../analysis/voxel-core/lbm.js';

const FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../analysis/voxel-core/lbm.js');
const BEGIN = '// BEGIN GENERATED lbm bulk kernel (tools/gen-lbm-kernel.mjs)', END = '// END GENERATED lbm bulk kernel';
const Q = 19, ids = [...Array(Q).keys()], W = (i) => (i === 0 ? '(1/3)' : i < 7 ? '(1/18)' : '(1/36)');

function oneKernel(name, edge) {
  const CX = L.LBM_CX, CY = L.LBM_CY, CZ = L.LBM_CZ, sum = (C) => ids.filter((i) => C[i]).map((i) => (C[i] > 0 ? '+' : '-') + 'f' + i).join('');
  const cuOf = (i) => { const t = []; if (CX[i]) t.push(`${CX[i] > 0 ? '' : '-'}ux`); if (CY[i]) t.push(`${CY[i] > 0 ? '' : '-'}uy`); if (CZ[i]) t.push(`${CZ[i] > 0 ? '' : '-'}uz`); return t.length ? t.join('+').replace(/\+-/g, '-') : '0'; };
  const cF = (i) => { const t = []; if (CX[i]) t.push(`${CX[i] > 0 ? '' : '-'}Fx`); if (CY[i]) t.push(`${CY[i] > 0 ? '' : '-'}Fy`); if (CZ[i]) t.push(`${CZ[i] > 0 ? '' : '-'}Fz`); return t.length ? t.join('+').replace(/\+-/g, '-') : '0'; };
  const cx = (i) => CX[i], cy = (i) => CY[i], cz = (i) => CZ[i];
  const o = [];
  o.push(edge ? '/** The same for the fluid cells next to a wall, an edge or a periodic wrap: every direction looks at its source cell (wall: bounce-back, moving wall: with the wall velocity). */' : '/** The collision-streaming of every bulk fluid cell (no wall, edge or wrap in its 18 neighbours) with the 19 directions written out. `list` lists the cells. */',
    `function ${name}(S, list, n) {`,
    '  const N = S.N, Fo = S.Fo, Fn = S.Fn, off = S.off, RHO = S.rho, UX = S.ux, UY = S.uy, UZ = S.uz, tau0 = S.tau0, cs = S.cs, bf = S.bodyForce, force = S.force, th = S.thermal;',
    '  const gb = th ? th.gbeta : 0, T = th ? th.T : null, Tref = th ? th.Tref : 0, up0 = th ? th.up[0] : 0, up1 = th ? th.up[1] : 0, up2 = th ? th.up[2] : 0, cs18 = 18 * Math.SQRT2 * cs * cs, bf0 = bf[0], bf1 = bf[1], bf2 = bf[2];');
  if (edge) o.push('  const flags = S.flags, WX = S.wrapX, WY = S.wrapY, WZ = S.wrapZ, nx = S.nx, ny = S.ny, nxy = nx * ny, mx0 = S.uWall[0], my0 = S.uWall[1], mz0 = S.uWall[2];');
  else o.push('  const ' + ids.map((i) => `o${i} = off[${i}]`).join(', ') + ';');
  o.push('  for (let k = 0; k < n; k++) {', '    const c = list[k];');
  if (edge) {
    o.push('    const px = c % nx, py = ((c - px) / nx) % ny, pz = (c - px - nx * py) / nxy, bx = 3 * px + 1, by = 3 * py + 1, bz = 3 * pz + 1;');
    for (const i of ids) {
      if (i === 0) { o.push('    const f0 = Fo[c];'); continue; }
      const wv = `6 * ${W(i)} * (${CX[i]} * mx0 + ${CY[i]} * my0 + ${CZ[i]} * mz0)`, opp = `Fo[${L.LBM_OPP[i]} * N + c]`;
      o.push(`    let f${i}; { const s = WX[bx - (${CX[i]})] + WY[by - (${CY[i]})] + WZ[bz - (${CZ[i]})]; if (s < 0) f${i} = ${opp}; else { const t = flags[s] & 7; f${i} = t === 1 ? ${opp} : t === 2 ? ${opp} + ${wv} : Fo[${i} * N + s]; } }`);
    }
  } else for (const i of ids) o.push(`    const f${i} = Fo[${i} * N + c - o${i}];`);
  o.push(`    const r = ${ids.map((i) => 'f' + i).join(' + ')}, ir = 1 / r;`, `    const mx = ${sum(CX).replace(/^\+/, '')}, my = ${sum(CY).replace(/^\+/, '')}, mz = ${sum(CZ).replace(/^\+/, '')};`,
    '    let Fx = bf0, Fy = bf1, Fz = bf2;', '    if (force) { Fx += force[3 * c]; Fy += force[3 * c + 1]; Fz += force[3 * c + 2]; }', '    if (gb) { const b = gb * (T[c] - Tref) * r; Fx += b * up0; Fy += b * up1; Fz += b * up2; }',
    '    const ux = (mx + 0.5 * Fx) * ir, uy = (my + 0.5 * Fy) * ir, uz = (mz + 0.5 * Fz) * ir, u2 = ux * ux + uy * uy + uz * uz, k0 = 1 - 1.5 * u2, hasF = Fx !== 0 || Fy !== 0 || Fz !== 0;',
    '    let om = 1 / tau0;', '    if (cs > 0) {');
  for (const i of ids) o.push(`      const cu${i} = ${cuOf(i)}, d${i} = f${i} - ${W(i)} * r * (k0 + 3 * cu${i} + 4.5 * cu${i} * cu${i});`);
  const pi = (a, b) => ids.filter((i) => a(i) * b(i)).map((i) => `${a(i) * b(i) > 0 ? '+' : '-'}d${i}`).join('').replace(/^\+/, '');
  o.push(`      const pxx = ${pi(cx, cx)}, pyy = ${pi(cy, cy)}, pzz = ${pi(cz, cz)}, pxy = ${pi(cx, cy)}, pxz = ${pi(cx, cz)}, pyz = ${pi(cy, cz)};`,
    '      const pn = Math.sqrt(pxx * pxx + pyy * pyy + pzz * pzz + 2 * (pxy * pxy + pxz * pxz + pyz * pyz));', '      om = 1 / (0.5 * (tau0 + Math.sqrt(tau0 * tau0 + cs18 * pn * ir)));', '    }',
    '    if (hasF) {', '      const fq = 1 - 0.5 * om;');
  for (const i of ids) o.push(`      { const cu = ${cuOf(i)}; Fn[${i} * N + c] = f${i} - om * (f${i} - ${W(i)} * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * ${W(i)} * (3 * (${[cx, cy, cz].map((a, q) => (a(i) ? `(${a(i)}-${['ux', 'uy', 'uz'][q]})*${['Fx', 'Fy', 'Fz'][q]}` : `-${['ux', 'uy', 'uz'][q]}*${['Fx', 'Fy', 'Fz'][q]}`)).join(' + ')}) + 9 * cu * (${cF(i)})); }`);
  o.push('    } else {');
  for (const i of ids) o.push(`      { const cu = ${cuOf(i)}; Fn[${i} * N + c] = f${i} - om * (f${i} - ${W(i)} * r * (k0 + 3 * cu + 4.5 * cu * cu)); }`);
  o.push('    }', '    RHO[c] = r; UX[c] = ux; UY[c] = uy; UZ[c] = uz;', '  }', '}');
  return o.join('\n');
}

export function kernelSource() { return [BEGIN, oneKernel('lbmBulkKernel', false), oneKernel('lbmEdgeKernel', true), END].join('\n'); }

export function rewrite(text) {
  const a = text.indexOf(BEGIN), b = text.indexOf(END);
  if (a < 0 || b < 0) throw new Error('lbm.js has no GENERATED markers');
  return text.slice(0, a) + kernelSource() + text.slice(b + END.length);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const text = fs.readFileSync(FILE, 'utf8'), next = rewrite(text);
  if (process.argv.includes('--check')) { if (next !== text) { console.error('analysis/voxel-core/lbm.js: the generated kernel is stale: run node tools/gen-lbm-kernel.mjs'); process.exit(1); } }
  else { fs.writeFileSync(FILE, next); console.log('wrote the lbm bulk kernel'); }
}
