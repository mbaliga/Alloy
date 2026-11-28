// Loads and conditions for analyses (docs/EXTENSIONS_SPEC.md section 3.2), stored in the document as project.analysis.conditions.
// Physical quantities are SI inside the document and in what analyses receive: kelvin, watts, newtons, pascals, metres per second,
// metres per second squared. The UI shows °C, W, N, Pa, m/s and g and converts at the edge (units.js). Positions and regions are
// document coordinates in millimetres, like the rest of the document.

import { validateDuty } from './durability.js';

export const G0 = 9.80665;
export const units = {
  cToK: (c) => c + 273.15, kToC: (k) => k - 273.15,
  gToMs2: (g) => g * G0, ms2ToG: (a) => a / G0,
};

export const FACES = ['+x', '-x', '+y', '-y', '+z', '-z'];
export const DIRS = FACES;
const dirVec = { '+x': [1, 0, 0], '-x': [-1, 0, 0], '+y': [0, 1, 0], '-y': [0, -1, 0], '+z': [0, 0, 1], '-z': [0, 0, -1] };
export const dirVector = (d) => dirVec[d];

export const defaultConditions = () => ({
  ambient: { temperature_K: units.cToK(25), airspeed_ms: 0, gravity: '-z' },
  heat: [],      // { id, target: { pcb: 'U1' } | { body: 'Shell' }, power_W }
  mounts: [],    // { id, kind: 'contact' | 'fixed' | 'slider', body, face, box?: [x0,x1,y0,y1,z0,z1] (mm), conductance_W_m2K? (contact) }; slider = holds only the displacement normal to the face (symmetry plane, frictionless support)
  loads: [],     // { id, kind: 'force' | 'pressure' | 'acceleration', body?, face?, vector_N?, direction?, pressure_Pa?, accel_ms2? }
  duty: null,    // the A6 duty profile (analysis/durability.js): life, shots, vibration, temperature, humidity, safety factor; null = not set
  limits: {},    // "<ext>:<analysis>:<check>" -> limit in the check's SI unit (user thresholds for analysis checks)
});

const ID = /^[A-Za-z0-9_.-]{1,40}$/;
const num = (v, what, lo = -Infinity, hi = Infinity) => { if (typeof v !== 'number' || !Number.isFinite(v) || v < lo || v > hi) throw new Error(`${what} must be a number between ${lo} and ${hi}`); return v; };

/** Validates a full conditions object (as stored). Throws a message that says what is wrong. */
export function validateConditions(c, project) {
  if (!c || typeof c !== 'object') throw new Error('conditions must be an object');
  const a = c.ambient || {};
  num(a.temperature_K, 'ambient temperature (K)', 200, 1000);
  num(a.airspeed_ms, 'air speed (m/s)', 0, 100);
  if (!DIRS.includes(a.gravity)) throw new Error(`gravity direction must be one of ${DIRS.join(', ')}`);
  const ids = new Set(), seen = (id, kind) => { if (!ID.test(id || '')) throw new Error(`${kind}: id must be 1 to 40 characters of A-Z a-z 0-9 _ . -`); if (ids.has(kind + id)) throw new Error(`${kind}: duplicate id "${id}"`); ids.add(kind + id); };
  const pcb = new Set(((project && project.pcb && project.pcb.components) || []).map((x) => x.name));
  const bodies = new Set(Object.keys((project && project.bodies) || {}));
  for (const h of c.heat || []) {
    seen(h.id, 'heat');
    num(h.power_W, `heat source ${h.id}: power (W)`, 0, 5000);
    const t = h.target || {};
    if (t.pcb == null === (t.body == null)) throw new Error(`heat source ${h.id}: give exactly one of target.pcb or target.body`);
    if (t.pcb != null && project && !pcb.has(t.pcb)) throw new Error(`heat source ${h.id}: no PCB component "${t.pcb}"`);
    if (t.body != null && project && bodies.size && !bodies.has(t.body)) throw new Error(`heat source ${h.id}: no body "${t.body}"`);
  }
  for (const m of c.mounts || []) {
    seen(m.id, 'mount');
    if (!['contact', 'fixed', 'slider'].includes(m.kind)) throw new Error(`mount ${m.id}: kind must be contact, fixed or slider`);
    if (typeof m.body !== 'string' || !m.body) throw new Error(`mount ${m.id}: needs a body`);
    if (!FACES.includes(m.face)) throw new Error(`mount ${m.id}: face must be one of ${FACES.join(', ')}`);
    if (m.box != null && !(Array.isArray(m.box) && m.box.length === 6 && m.box.every(Number.isFinite))) throw new Error(`mount ${m.id}: box must be [x0,x1,y0,y1,z0,z1] in mm`);
    if (m.kind === 'contact') num(m.conductance_W_m2K, `mount ${m.id}: contact conductance (W/m²K)`, 0, 1e6);
  }
  for (const l of c.loads || []) {
    seen(l.id, 'load');
    if (!['force', 'pressure', 'acceleration'].includes(l.kind)) throw new Error(`load ${l.id}: kind must be force, pressure or acceleration`);
    if (l.kind === 'force') {
      if (!(Array.isArray(l.vector_N) && l.vector_N.length === 3 && l.vector_N.every(Number.isFinite))) throw new Error(`load ${l.id}: vector_N must be [fx, fy, fz] in newtons`);
      if (Math.hypot(...l.vector_N) > 1e6) throw new Error(`load ${l.id}: force larger than 1 MN`);
      if (!FACES.includes(l.face) || !l.body) throw new Error(`load ${l.id}: a force needs a body and the face it acts on`);
    } else if (l.kind === 'pressure') {
      num(l.pressure_Pa, `load ${l.id}: pressure (Pa)`, -1e9, 1e9);
      if (!FACES.includes(l.face) || !l.body) throw new Error(`load ${l.id}: a pressure needs a body and a face`);
    } else {
      num(l.accel_ms2, `load ${l.id}: acceleration (m/s²)`, 0, G0 * 100000);
      if (!DIRS.includes(l.direction)) throw new Error(`load ${l.id}: direction must be one of ${DIRS.join(', ')}`);
    }
  }
  validateDuty(c.duty == null ? null : c.duty);
  for (const [k, v] of Object.entries(c.limits || {})) { if (!/^[\w.-]+:[\w.-]+:[\w.-]+$/.test(k)) throw new Error(`limit key "${k}" must look like <extension>:<analysis>:<check>`); num(v, `limit ${k}`, -1e12, 1e12); }
  return c;
}

/** Plain-language statement of what an acceleration load means, shown next to it in the UI and in reports (spec 3.2). */
export const ACCEL_ASSUMPTION = 'Quasi-static: the part is loaded as if it were held at constant acceleration. Impact and vibration dynamics are not modelled.';
