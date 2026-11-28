// Durability inputs saved in the project (docs/EXTENSIONS_SPEC.md section 4A, "A6 shared pieces"): the duty profile
// (project.analysis.conditions.duty) and the bolted joints (project.joints[]). Validation, the fastener table and the placeholder
// presets live here; the ops that write them are in model.js (set_duty, set_joint, remove_joint). SI inside, like the rest of the
// analysis framework, except where a name carries its unit (mm, g, N, Nm, C).

export const HUMIDITY = ['dry', 'conditioned', 'wet'];
export const INSERTS = ['none', 'heat_set_brass', 'metal_sleeve', 'moulded_nut'];
export const INSERT_LABEL = { none: 'none (bolt bears on the plastic)', heat_set_brass: 'heat-set brass insert', metal_sleeve: 'metal compression sleeve', moulded_nut: 'moulded-in nut' };

/** ISO metric coarse thread, ISO 4762 socket head (head diameter dk), medium clearance hole (ISO 273) and ISO 4032 nut width across flats s. Lengths in mm. */
export const BOLTS = {
  M2: { d: 2, P: 0.4, d2: 1.740, d3: 1.509, As: 2.07, dk: 3.8, dh: 2.4, s: 4 },
  M2_5: { d: 2.5, P: 0.45, d2: 2.208, d3: 1.948, As: 3.39, dk: 4.5, dh: 2.9, s: 5 },
  M3: { d: 3, P: 0.5, d2: 2.675, d3: 2.387, As: 5.03, dk: 5.5, dh: 3.4, s: 5.5 },
  M4: { d: 4, P: 0.7, d2: 3.545, d3: 3.141, As: 8.78, dk: 7, dh: 4.5, s: 7 },
  M5: { d: 5, P: 0.8, d2: 4.480, d3: 4.019, As: 14.2, dk: 8.5, dh: 5.5, s: 8 },
  M6: { d: 6, P: 1, d2: 5.350, d3: 4.773, As: 20.1, dk: 10, dh: 6.6, s: 10 },
  M8: { d: 8, P: 1.25, d2: 7.188, d3: 6.466, As: 36.6, dk: 13, dh: 9, s: 13 },
};
/** Proof strength Rp0.2 in MPa (ISO 898-1 nominal for 4.8 to 12.9; ISO 3506-1 for A2/A4) and the metal the bolt is made of. */
export const GRADES = {
  '4.8': { Rp: 320, metal: 'steel-carbon' }, '8.8': { Rp: 640, metal: 'steel-carbon' }, '10.9': { Rp: 940, metal: 'steel-carbon' }, '12.9': { Rp: 1100, metal: 'steel-carbon' },
  'A2-70': { Rp: 450, metal: 'stainless-304' }, 'A4-70': { Rp: 450, metal: 'stainless-304' }, 'A4-80': { Rp: 600, metal: 'stainless-304' },
};
export const sizeKey = (s) => String(s).replace('.', '_');

const ID = /^[A-Za-z0-9_.-]{1,40}$/;
const num = (v, what, lo = -Infinity, hi = Infinity) => { if (typeof v !== 'number' || !Number.isFinite(v) || v < lo || v > hi) throw new Error(`${what} must be a number between ${lo} and ${hi}`); return v; };

// ───────── duty profile
const PSD = {
  handheld: [[10, 0.005], [200, 0.005], [2000, 0.0005]],
  vehicle: [[10, 0.04], [40, 0.04], [500, 0.007], [2000, 0.007]],
};
const duty = (name, o) => ({ preset: name, placeholder: true, ...o });
/** Presets are placeholders, not measurements: every one carries placeholder: true and the UI says so next to it. */
export const DUTY_PRESETS = {
  'handheld-light': () => duty('handheld-light', {
    label: 'Handheld, light use', life: { years: 5, hours_per_day: 1, target_years: 5 }, shots: { per_day: 100, total: 182500, peak_g: 500, pulse_ms: 2 },
    vibration: { preset: 'handheld', psd: PSD.handheld.map((p) => p.slice()), hours: 50 }, temperature: { min_C: -20, max_C: 55, cycles_per_day: 1 }, humidity: 'conditioned', safety_factor: 3 }),
  'handheld-heavy': () => duty('handheld-heavy', {
    label: 'Handheld, heavy use', life: { years: 5, hours_per_day: 3, target_years: 5 }, shots: { per_day: 500, total: 912500, peak_g: 800, pulse_ms: 1.5 },
    vibration: { preset: 'handheld', psd: PSD.handheld.map((p) => p.slice()), hours: 300 }, temperature: { min_C: -30, max_C: 60, cycles_per_day: 1 }, humidity: 'conditioned', safety_factor: 3 }),
  vehicle: () => duty('vehicle', {
    label: 'Vehicle mounted', life: { years: 10, hours_per_day: 4, target_years: 10 }, shots: { per_day: 20, total: 73000, peak_g: 300, pulse_ms: 2 },
    vibration: { preset: 'vehicle', psd: PSD.vehicle.map((p) => p.slice()), hours: 2000 }, temperature: { min_C: -30, max_C: 70, cycles_per_day: 2 }, humidity: 'wet', safety_factor: 3 }),
};
export const DUTY_NOTE = 'Placeholder: invented round numbers so the tools can run. Replace with your own measured duty (shots, recoil pulse, vibration, temperatures) before you read any result as more than a ranking.';
export const makeDuty = (preset = 'handheld-light') => (DUTY_PRESETS[preset] ? DUTY_PRESETS[preset]() : null);

export function validateDuty(d) {
  if (d == null) return d; // not set: the A6 packages report that as a data gap
  if (typeof d !== 'object' || Array.isArray(d)) throw new Error('duty must be an object');
  const l = d.life || {}, s = d.shots || {}, v = d.vibration || {}, t = d.temperature || {};
  num(l.years, 'duty life (years)', 0.01, 100); num(l.hours_per_day, 'duty hours per day', 0, 24);
  if (l.target_years != null) num(l.target_years, 'duty target life (years)', 0.01, 100);
  num(s.per_day, 'duty shots per day', 0, 1e6); num(s.total, 'duty total shots', 0, 1e9); num(s.peak_g, 'duty recoil peak (g)', 0, 1e5); num(s.pulse_ms, 'duty recoil pulse (ms)', 0.01, 1000);
  if (v.psd != null && !(Array.isArray(v.psd) && v.psd.length <= 64 && v.psd.every((p) => Array.isArray(p) && p.length === 2 && p[0] > 0 && p[1] >= 0 && p.every(Number.isFinite)))) throw new Error('duty vibration PSD must be [[Hz, g²/Hz], ...] with at most 64 points');
  num(v.hours ?? 0, 'duty vibration hours', 0, 1e6);
  num(t.min_C, 'duty minimum temperature (°C)', -100, 300); num(t.max_C, 'duty maximum temperature (°C)', -100, 400); num(t.cycles_per_day, 'duty temperature cycles per day', 0, 1000);
  if (!(t.max_C >= t.min_C)) throw new Error('duty maximum temperature must not be below the minimum');
  if (!HUMIDITY.includes(d.humidity)) throw new Error(`duty humidity must be one of ${HUMIDITY.join(', ')}`);
  num(d.safety_factor, 'duty safety factor on life', 1, 100);
  return d;
}

// ───────── joints
const FACES = ['+x', '-x', '+y', '-y', '+z', '-z'];
/**
 * A joint (project.joints[]):
 *   { id, name, where:[x,y,z] mm, axis: '+x'..'-z' (head to nut),
 *     bolt:{ size:'M3', grade:'8.8', length_mm },
 *     tightening:{ mode:'torque', method:'nut_factor', torque_Nm, nut_factor } | { mode:'torque', method:'friction', torque_Nm, mu_thread, mu_head } | { mode:'preload', preload_N },  (+ alpha_A?, assembly_C?)
 *     mu_clamp (friction between the clamped parts), thread_locker (bool),
 *     clamps:[{ body } | { external, material }, thickness_mm, outer_d_mm? ]  head to nut,
 *     insert: 'none' | 'heat_set_brass' | 'metal_sleeve' | 'moulded_nut', thread_in: 'metal' | 'plastic',
 *     anchor?: { body, length_mm, d_mm? } (thread or insert in a plastic body), sleeve?: { od_mm? }, washer?: { od_mm },
 *     carried_mass_g?, min_clamp_N?, embedding_um?, note?, locked? }
 */
export function validateJoint(j, project) {
  if (!j || typeof j !== 'object') throw new Error('joint must be an object');
  if (!ID.test(j.id || '')) throw new Error('joint id must be 1 to 40 characters of A-Z a-z 0-9 _ . -');
  const id = j.id, bad = (m) => { throw new Error(`joint ${id}: ${m}`); };
  if (typeof j.name !== 'string' || !j.name.trim() || j.name.length > 60) bad('needs a name (up to 60 characters)');
  if (!(Array.isArray(j.where) && j.where.length === 3 && j.where.every(Number.isFinite))) bad('where must be [x, y, z] in mm');
  if (!FACES.includes(j.axis)) bad(`axis must be one of ${FACES.join(', ')}`);
  const b = j.bolt || {};
  if (!BOLTS[sizeKey(b.size)]) bad(`bolt size must be one of ${Object.keys(BOLTS).map((k) => k.replace('_', '.')).join(', ')}`);
  if (!GRADES[b.grade]) bad(`bolt grade must be one of ${Object.keys(GRADES).join(', ')}`);
  num(b.length_mm, `joint ${id}: bolt length (mm)`, 3, 200);
  const t = j.tightening || {};
  if (t.mode === 'preload') num(t.preload_N, `joint ${id}: preload (N)`, 1, 1e6);
  else if (t.mode === 'torque') {
    num(t.torque_Nm, `joint ${id}: torque (N·m)`, 0.001, 500);
    if (t.method === 'nut_factor') num(t.nut_factor, `joint ${id}: nut factor`, 0.05, 0.6);
    else if (t.method === 'friction') { num(t.mu_thread, `joint ${id}: thread friction`, 0.02, 0.6); num(t.mu_head, `joint ${id}: under-head friction`, 0.02, 0.6); }
    else bad('torque tightening needs method nut_factor or friction');
  } else bad('tightening.mode must be torque or preload');
  if (t.alpha_A != null) num(t.alpha_A, `joint ${id}: tightening factor alpha_A`, 1, 4);
  if (t.assembly_C != null) num(t.assembly_C, `joint ${id}: assembly temperature (°C)`, -40, 80);
  num(j.mu_clamp, `joint ${id}: friction between the clamped parts`, 0.02, 1);
  if (typeof j.thread_locker !== 'boolean') bad('thread_locker must be true or false');
  if (!INSERTS.includes(j.insert)) bad(`insert must be one of ${INSERTS.join(', ')}`);
  if (!['metal', 'plastic'].includes(j.thread_in || 'metal')) bad('thread_in must be metal or plastic');
  if (!(Array.isArray(j.clamps) && j.clamps.length >= 1 && j.clamps.length <= 6)) bad('clamps must list 1 to 6 layers, head to nut');
  const bodies = new Set(Object.keys((project && project.bodies) || {}));
  for (const f of ((project && project.features) || [])) if (f.body && f.body !== '*' && f.op !== 'ref') bodies.add(f.body);
  for (const [i, c] of j.clamps.entries()) {
    if ((c.body == null) === (c.external == null)) bad(`clamps[${i}] needs exactly one of body or external`);
    if (c.body != null && project && bodies.size && !bodies.has(c.body)) bad(`clamps[${i}]: no body "${c.body}"`);
    if (c.external != null && (typeof c.external !== 'string' || !c.external.trim() || typeof c.material !== 'string')) bad(`clamps[${i}]: an external part needs a name and a material id`);
    num(c.thickness_mm, `joint ${id}: clamps[${i}] thickness (mm)`, 0.2, 200);
    if (c.outer_d_mm != null) num(c.outer_d_mm, `joint ${id}: clamps[${i}] outer diameter (mm)`, 1, 500);
  }
  if (j.insert === 'heat_set_brass' || (j.thread_in || 'metal') === 'plastic') {
    const a = j.anchor || {};
    if (typeof a.body !== 'string' || (project && bodies.size && !bodies.has(a.body))) bad('a thread or insert in plastic needs anchor.body (a body of this design)');
    num(a.length_mm, `joint ${id}: anchor length (mm)`, 0.5, 100);
  }
  if (j.sleeve != null && j.sleeve.od_mm != null) num(j.sleeve.od_mm, `joint ${id}: sleeve outer diameter (mm)`, 1, 100);
  if (j.washer != null) num(j.washer.od_mm, `joint ${id}: washer outer diameter (mm)`, 1, 100);
  for (const k of ['carried_mass_g', 'min_clamp_N', 'embedding_um']) if (j[k] != null) num(j[k], `joint ${id}: ${k}`, 0, 1e7);
  return j;
}

/** The bodies a joint touches (clamped or anchoring): a lock on any of them blocks changing the joint (the fix would change that body). */
export const jointBodies = (j) => [...new Set([...(j.clamps || []).filter((c) => c.body).map((c) => c.body), ...(j.anchor && j.anchor.body ? [j.anchor.body] : [])])];

/** A starting joint for the UI and the template; the numbers are typical and shown to the user, not hidden. */
export function newJoint(over = {}) {
  return {
    id: 'J1', name: 'Joint 1', where: [0, 0, 0], axis: '+z', bolt: { size: 'M3', grade: '8.8', length_mm: 16 },
    tightening: { mode: 'torque', method: 'nut_factor', torque_Nm: 0.9, nut_factor: 0.2 }, mu_clamp: 0.2, thread_locker: false,
    clamps: [{ body: '', thickness_mm: 6 }], insert: 'none', thread_in: 'metal', ...over,
  };
}
