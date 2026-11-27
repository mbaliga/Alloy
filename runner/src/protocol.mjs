// Job submission: validation of the request body (protocol 1). Everything that arrives is untrusted: sizes, names, numbers and
// geometry are checked here, and a rejected job says which field is wrong. The conditions use the same vocabulary as the Forge
// analysis conditions (faces "+x".."-z" of a body's bounding box, optional box in mm, SI quantities).
import { parseStl, meshStats, isClosedSurface } from './lib/stl.js';
import { FACES } from './lib/tetmesh.mjs';
import { KINDS } from './adapters/index.mjs';

export const PROTOCOL = 1;
export class HttpError extends Error { constructor(status, message, code) { super(message); this.status = status; this.code = code || 'E_REQUEST'; } }
const bad = (m) => { throw new HttpError(400, m, 'E_REQUEST'); };
const isNum = (v, lo = -Infinity, hi = Infinity) => typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi;
const NAME = /^[A-Za-z0-9 _.()-]{1,60}$/;
const ID = /^[A-Za-z0-9_.-]{1,40}$/;

function b64(s, what, maxBytes) {
  if (typeof s !== 'string' || !s.length) bad(`${what}: data must be a base64 string`);
  if (s.length > maxBytes * 1.4) throw new HttpError(413, `${what}: larger than the limit`, 'E_TOO_LARGE');
  if (!/^[A-Za-z0-9+/=\r\n]+$/.test(s)) bad(`${what}: data is not base64`);
  return new Uint8Array(Buffer.from(s, 'base64'));
}

export function validateJob(req, config) {
  if (!req || typeof req !== 'object' || Array.isArray(req)) bad('the body must be a JSON object');
  if (req.protocol !== PROTOCOL) bad(`protocol must be ${PROTOCOL}`);
  if (!KINDS.includes(req.kind)) bad(`kind must be one of ${KINDS.join(', ')}`);
  const adapter = req.adapter === undefined ? 'auto' : req.adapter;
  if (!['auto', 'elmer', 'fake', 'openfoam'].includes(adapter)) bad('adapter must be auto, elmer, openfoam or fake');
  if (adapter === 'openfoam' && req.kind !== 'flow') bad('the openfoam adapter handles kind flow only');
  if (req.kind === 'flow' && !['auto', 'openfoam'].includes(adapter)) bad('a flow job needs adapter auto or openfoam');
  const label = typeof req.label === 'string' ? req.label.slice(0, 80).replace(/[\r\n]/g, ' ') : '';
  if (!Array.isArray(req.bodies) || !req.bodies.length) bad('bodies must be a non-empty array');
  if (req.bodies.length > config.maxBodies) bad(`more than ${config.maxBodies} bodies`);
  const names = new Set(), warnings = [];
  const bodies = req.bodies.map((b, i) => {
    const w = `bodies[${i}]`;
    if (!b || typeof b !== 'object') bad(`${w} must be an object`);
    if (typeof b.name !== 'string' || !NAME.test(b.name)) bad(`${w}.name must be 1 to 60 characters of letters, digits, space and _.()-`);
    if (names.has(b.name)) bad(`${w}.name "${b.name}" is used twice`); names.add(b.name);
    if (!['stl', 'step'].includes(b.format)) bad(`${w}.format must be stl or step`);
    if (b.format === 'step') bad(`${w}.format "step" is not accepted by this runner version; send stl`);
    const bytes = b64(b.data_b64, `${w}`, config.maxBodyBytes);
    let stats;
    try { const m = parseStl(bytes); stats = meshStats(m.positions, m.indices); if (!isClosedSurface(m.indices)) bad(`${w}: the STL is not a closed surface (open edges, a duplicated or inconsistently wound triangle); a volume mesh needs a watertight body`); } catch (e) { if (e instanceof HttpError) throw e; bad(`${w}: ${e.message}`); }
    if (!(stats.triangles >= 4)) bad(`${w}: fewer than 4 triangles`);
    if (!(stats.volume > 0)) bad(`${w}: the STL does not enclose a positive volume (it must be a closed surface with outward-wound triangles)`);
    if (stats.triangles > 2_000_000) throw new HttpError(413, `${w}: more than 2 000 000 triangles`, 'E_TOO_LARGE');
    const m = b.material || {};
    const mat = {};
    for (const [k, lo, hi] of [['E_Pa', 1e3, 1e13], ['nu', 0, 0.499], ['rho', 1e-3, 3e4], ['yield_Pa', 0, 1e12], ['tensile_Pa', 0, 1e12], ['k', 1e-3, 1e4], ['cp', 1, 1e5]]) {
      if (m[k] === undefined || m[k] === null) continue;
      if (!isNum(m[k], lo, hi)) bad(`${w}.material.${k} must be a number from ${lo} to ${hi}`); mat[k] = m[k];
    }
    if (req.kind === 'structural' && !(mat.E_Pa > 0 && mat.nu !== undefined)) bad(`${w}.material needs E_Pa and nu for a structural job`);
    if (req.kind === 'thermal' && !(mat.k > 0)) bad(`${w}.material needs k (W/m K) for a thermal job`);
    return { name: b.name, format: b.format, bytes, stats, material: mat };
  });
  let flow = null;
  if (req.kind === 'flow') {
    const f = req.flow; if (!f || typeof f !== 'object' || Array.isArray(f)) bad('a flow job needs a flow object { speed_ms, dir, ... }');
    if (!isNum(f.speed_ms, 0.01, 100)) bad('flow.speed_ms must be 0.01 to 100'); if (!FACES.includes(f.dir)) bad(`flow.dir must be one of ${FACES.join(', ')}`);
    const nu = f.nu_m2s === undefined ? 1.55e-5 : f.nu_m2s; if (!isNum(nu, 1e-7, 1e-3)) bad('flow.nu_m2s must be 1e-7 to 1e-3'); const margin = f.margin_mm === undefined ? 0 : f.margin_mm; if (!isNum(margin, 0, 5000)) bad('flow.margin_mm must be 0 (automatic) to 5000');
    const planes = (f.planes || []).map((p, i) => { const w = `flow.planes[${i}]`; if (!p || typeof p.id !== 'string' || !ID.test(p.id)) bad(`${w}.id must be 1 to 40 characters of A-Z a-z 0-9 _ . -`); const v3 = (a) => Array.isArray(a) && a.length === 3 && a.every((x) => isNum(x)); if (!v3(p.point_mm) || !v3(p.normal) || !(Math.hypot(...p.normal) > 0)) bad(`${w} needs point_mm [x,y,z] and a non-zero normal [x,y,z]`); if (!(Array.isArray(p.bounds_mm) && p.bounds_mm.length === 6 && p.bounds_mm.every((x) => isNum(x)))) bad(`${w}.bounds_mm must be [x0,x1,y0,y1,z0,z1] in mm`); return { id: p.id, point_mm: p.point_mm, normal: p.normal, bounds_mm: p.bounds_mm }; });
    if (planes.length > 32) bad('more than 32 flow planes'); if (new Set(planes.map((p) => p.id)).size !== planes.length) bad('flow.planes ids must be unique');
    flow = { speed_ms: f.speed_ms, dir: f.dir, nu_m2s: nu, margin_mm: margin, planes };
  }
  const c = req.conditions || {}, cond = { mounts: [], loads: [], heat: [] };
  if (typeof c !== 'object' || Array.isArray(c)) bad('conditions must be an object');
  cond.ambient_K = c.ambient_K === undefined ? 298.15 : c.ambient_K; if (!isNum(cond.ambient_K, 200, 1000)) bad('conditions.ambient_K must be 200 to 1000');
  cond.h_conv_W_m2K = c.h_conv_W_m2K === undefined ? 10 : c.h_conv_W_m2K; if (!isNum(cond.h_conv_W_m2K, 0, 1e5)) bad('conditions.h_conv_W_m2K must be 0 to 100000');
  cond.gravity = c.gravity === undefined ? '-z' : c.gravity; if (!FACES.includes(cond.gravity)) bad(`conditions.gravity must be one of ${FACES.join(', ')}`);
  cond.self_weight = c.self_weight === true;
  const ids = new Set(), uniq = (id, w) => { if (typeof id !== 'string' || !ID.test(id)) bad(`${w}.id must be 1 to 40 characters of A-Z a-z 0-9 _ . -`); if (ids.has(id)) bad(`${w}.id "${id}" is used twice`); ids.add(id); };
  const face = (x, w) => { if (!FACES.includes(x.face)) bad(`${w}.face must be one of ${FACES.join(', ')}`); if (typeof x.body !== 'string') bad(`${w}.body is required`); if (x.box !== undefined && x.box !== null && !(Array.isArray(x.box) && x.box.length === 6 && x.box.every((v) => isNum(v)))) bad(`${w}.box must be [x0,x1,y0,y1,z0,z1] in mm`); };
  (c.mounts || []).forEach((m, i) => { const w = `conditions.mounts[${i}]`; uniq(m.id, w); if (!['fixed', 'slider', 'contact'].includes(m.kind)) bad(`${w}.kind must be fixed, slider or contact`); face(m, w); if (m.kind === 'contact' && !isNum(m.conductance_W_m2K, 0, 1e6)) bad(`${w}.conductance_W_m2K must be 0 to 1e6`); cond.mounts.push({ id: m.id, kind: m.kind, body: m.body, face: m.face, box: m.box || null, conductance_W_m2K: m.conductance_W_m2K }); });
  (c.loads || []).forEach((l, i) => {
    const w = `conditions.loads[${i}]`; uniq(l.id, w); if (!['force', 'pressure', 'acceleration'].includes(l.kind)) bad(`${w}.kind must be force, pressure or acceleration`);
    if (l.kind === 'force') { face(l, w); if (!(Array.isArray(l.vector_N) && l.vector_N.length === 3 && l.vector_N.every((v) => isNum(v)) && Math.hypot(...l.vector_N) <= 1e6)) bad(`${w}.vector_N must be [fx,fy,fz] in N (at most 1 MN)`); }
    else if (l.kind === 'pressure') { face(l, w); if (!isNum(l.pressure_Pa, -1e9, 1e9)) bad(`${w}.pressure_Pa must be -1e9 to 1e9`); }
    else { if (!FACES.includes(l.direction)) bad(`${w}.direction must be one of ${FACES.join(', ')}`); if (!isNum(l.accel_ms2, 0, 9.80665e5)) bad(`${w}.accel_ms2 must be 0 to 980665`); }
    cond.loads.push({ id: l.id, kind: l.kind, body: l.body, face: l.face, box: l.box || null, vector_N: l.vector_N, pressure_Pa: l.pressure_Pa, direction: l.direction, accel_ms2: l.accel_ms2 });
  });
  (c.heat || []).forEach((x, i) => { const w = `conditions.heat[${i}]`; uniq(x.id, w); if (typeof x.body !== 'string') bad(`${w}.body is required`); if (!isNum(x.power_W, 0, 1e5)) bad(`${w}.power_W must be 0 to 100000`); cond.heat.push({ id: x.id, body: x.body, power_W: x.power_W }); });
  const o = req.options || {};
  if (typeof o !== 'object' || Array.isArray(o)) bad('options must be an object');
  const options = { mesh_size_mm: o.mesh_size_mm === undefined ? 0 : o.mesh_size_mm, mesh_study: o.mesh_study === true, keep: o.keep === true };
  if (!isNum(options.mesh_size_mm, 0, 1000)) bad('options.mesh_size_mm must be 0 (automatic) to 1000');
  if (adapter === 'fake' || adapter === 'auto') { if (o.fake_delay_ms !== undefined) options.fake_delay_ms = isNum(o.fake_delay_ms, 0, 5000) ? o.fake_delay_ms : bad('options.fake_delay_ms must be 0 to 5000'); if (o.fake_mesh_dependence !== undefined) options.fake_mesh_dependence = isNum(o.fake_mesh_dependence, 0, 10) ? o.fake_mesh_dependence : bad('options.fake_mesh_dependence must be 0 to 10'); }
  if (req.kind === 'structural') warnings.push('The FDM inter-layer knock-down is not applied by the runner (isotropic material); the safety factor is against yield.');
  if (req.kind === 'flow') warnings.push('Flow jobs use the bodies as closed walls in a box of air; materials and structural or thermal conditions are ignored.');
  return { protocol: PROTOCOL, kind: req.kind, adapter, label, bodies, conditions: cond, options, warnings, flow };
}
