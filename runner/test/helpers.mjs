import { writeStlBinary } from '../src/lib/stl.js';
import { loadConfig } from '../src/config.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

export const hasGmsh = (() => { try { return spawnSync('gmsh', ['--version']).status === 0; } catch (_) { return false; } })();

/** A closed, outward-wound box [0,x] x [0,y] x [0,z]. */
export function boxMesh(x, y, z, o = [0, 0, 0]) {
  const v = [[0, 0, 0], [x, 0, 0], [x, y, 0], [0, y, 0], [0, 0, z], [x, 0, z], [x, y, z], [0, y, z]];
  const positions = Float32Array.from(v.flatMap((p) => [p[0] + o[0], p[1] + o[1], p[2] + o[2]]));
  const indices = Uint32Array.from([0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7]);
  return { positions, indices };
}
export const boxStl = (...a) => { const m = boxMesh(...a); return writeStlBinary(m.positions, m.indices); };
export const b64 = (u8) => Buffer.from(u8).toString('base64');

export const STEEL = { E_Pa: 2e11, nu: 0.3, rho: 7800, yield_Pa: 2.5e8, k: 45, cp: 500 };

/** A structural job for a bar fixed at -x and pulled at +x. */
export function barJob(over = {}) {
  return {
    protocol: 1, kind: 'structural', adapter: 'auto', label: 'bar',
    bodies: [{ name: 'Bar', format: 'stl', data_b64: b64(boxStl(40, 10, 10)), material: { ...STEEL } }],
    conditions: { mounts: [{ id: 'wall', kind: 'fixed', body: 'Bar', face: '-x' }], loads: [{ id: 'pull', kind: 'force', body: 'Bar', face: '+x', vector_N: [1000, 0, 0] }] },
    options: { mesh_size_mm: 4 }, ...over,
  };
}
export function thermalJob(over = {}) {
  return {
    protocol: 1, kind: 'thermal', adapter: 'auto', label: 'block',
    bodies: [{ name: 'Block', format: 'stl', data_b64: b64(boxStl(20, 20, 10)), material: { k: 200, rho: 2700, cp: 900 } }],
    conditions: { ambient_K: 293.15, h_conv_W_m2K: 10, heat: [{ id: 'chip', body: 'Block', power_W: 2 }] },
    options: { mesh_size_mm: 4 }, ...over,
  };
}

export const tmpDir = () => fs.mkdtempSync(path.join(os.tmpdir(), 'alloy-runner-test-'));
export const testConfig = (over = {}) => loadConfig({ ALLOY_RUNNER_TOKEN: 'test-token-0123456789abcdef', ALLOY_RUNNER_ENABLE_FAKE: '1', ALLOY_RUNNER_PORT: '0' }, { dataDir: tmpDir(), ...over });

export async function api(base, token, method, p, body, extra = {}) {
  const r = await fetch(base + p, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...(extra.headers || {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch (_) { /* not json */ }
  return { status: r.status, json, text, headers: r.headers };
}
export async function waitState(base, token, id, states, ms = 60000) {
  const t0 = Date.now();
  for (;;) { const r = await api(base, token, 'GET', `/v1/jobs/${id}`); if (r.json && states.includes(r.json.state)) return r.json; if (Date.now() - t0 > ms) throw new Error(`timed out waiting for ${states}: ${r.text}`); await new Promise((s) => setTimeout(s, 25)); }
}
