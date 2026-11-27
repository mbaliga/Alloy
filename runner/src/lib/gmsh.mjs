// Meshing with gmsh, run as a separate process (GPL, never linked): an STL surface becomes a first-order tetrahedral volume mesh.
// The surface is re-meshed at the requested size (ClassifySurfaces with reparametrisation), so a 12-triangle box still gets a graded
// volume mesh. The STL must be closed (watertight); gmsh then fails and the message says so.
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { readMsh2, orientTets, compactMesh } from './tetmesh.mjs';

export function geoScript({ stl, msh, h, angleDeg = 40, reparam = 1 }) {
  return [
    `Merge "${stl}";`,
    `ClassifySurfaces{ ${angleDeg}*Pi/180, 1, ${reparam}, Pi };`,
    'CreateGeometry;',
    's[] = Surface "*";',
    'Surface Loop(1) = s[];',
    'Volume(1) = {1};',
    `Mesh.MeshSizeMax = ${h};`,
    `Mesh.MeshSizeMin = ${h / 4};`,
    'Mesh.MshFileVersion = 2.2;',
    'Mesh.Algorithm3D = 1;',
    `Save "${msh}";`, '',
  ].join('\n');
}

/** Run a command, collecting stdout+stderr (tail only), honouring an AbortSignal. Resolves { code, out }. */
export function run(cmd, args, { cwd, signal, onLine, env } = {}) {
  return new Promise((resolve, reject) => {
    if (signal && signal.aborted) return reject(Object.assign(new Error('cancelled'), { code: 'CANCELLED' }));
    const p = spawn(cmd, args, { cwd, env: { ...process.env, ...(env || {}) }, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', buf = '';
    const feed = (d) => { const s = d.toString(); out = (out + s).slice(-20000); buf += s; let i; while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i); buf = buf.slice(i + 1); if (onLine) onLine(l); } };
    p.stdout.on('data', feed); p.stderr.on('data', feed);
    const onAbort = () => { p.kill('SIGTERM'); setTimeout(() => p.kill('SIGKILL'), 2000).unref(); };
    if (signal) signal.addEventListener('abort', onAbort, { once: true });
    p.on('error', (e) => { if (signal) signal.removeEventListener('abort', onAbort); reject(e); });
    p.on('close', (code) => { if (signal) signal.removeEventListener('abort', onAbort); if (signal && signal.aborted) reject(Object.assign(new Error('cancelled'), { code: 'CANCELLED' })); else resolve({ code, out }); });
  });
}

export async function gmshVersion(bin = 'gmsh') { try { const r = await run(bin, ['--version']); return r.code === 0 ? r.out.trim().split(/\s+/).pop() : null; } catch (_) { return null; } }

/**
 * STL bytes -> { nodes, tets, volume, strategy } at element size h (mm). Two strategies, in order:
 *   "remeshed": the surface is re-parametrised and re-meshed at size h (a 12-triangle box still gets a graded mesh). It fails on
 *               surfaces with needle triangles, which boolean operations in a CAD kernel produce ("Poor input mesh quality").
 *   "stl-surface": the STL triangles are kept as the boundary mesh and the volume is filled from them. Robust, but the element size
 *               near the surface is whatever the STL has, and h only limits the interior.
 * A strategy counts only if it makes tetrahedra whose volume is within 5 % of the STL's (`expectVolume`, when given).
 */
export async function meshStl({ stlBytes, h, dir, bin = 'gmsh', signal, onLine, angleDeg, expectVolume }) {
  dir = path.resolve(dir); fs.mkdirSync(dir, { recursive: true });
  const stl = path.join(dir, 'body.stl'), geo = path.join(dir, 'body.geo'), msh = path.join(dir, 'body.msh'), failures = [];
  fs.writeFileSync(stl, stlBytes);
  for (const [strategy, reparam] of [['remeshed', 1], ['stl-surface', 0]]) {
    fs.rmSync(msh, { force: true }); fs.writeFileSync(geo, geoScript({ stl: 'body.stl', msh: 'body.msh', h, angleDeg, reparam }));
    let r;
    try { r = await run(bin, [geo, '-3', '-format', 'msh2', '-o', msh, '-v', '2'], { cwd: dir, signal, onLine }); }
    catch (e) { if (e.code === 'ENOENT') throw Object.assign(new Error(`gmsh was not found ("${bin}"); install it or run the container image`), { code: 'NO_SOLVER' }); throw e; }
    const last = r.out.trim().split('\n').filter((l) => /Error/.test(l)).slice(-2).join(' | ') || r.out.trim().split('\n').slice(-2).join(' | ');
    if (r.code !== 0 || !fs.existsSync(msh)) { failures.push(`${strategy}: gmsh exit ${r.code}: ${last}`); continue; }
    let raw; try { raw = readMsh2(fs.readFileSync(msh, 'utf8')); } catch (e) { failures.push(`${strategy}: ${e.message}${last ? ' (' + last + ')' : ''}`); continue; }
    const { nodes, tets } = compactMesh(raw.nodes, raw.tets), { volume, flipped } = orientTets(nodes, tets);
    if (expectVolume > 0 && Math.abs(volume - expectVolume) / expectVolume > 0.05) { failures.push(`${strategy}: the tetrahedra fill ${volume.toFixed(1)} mm3 but the STL encloses ${expectVolume.toFixed(1)} mm3`); continue; }
    return { nodes, tets, volume, flipped, strategy, failures };
  }
  throw new Error(`gmsh could not mesh this body. The STL must be a closed, non-self-intersecting surface. ${failures.join(' || ')}`);
}
