// Materials for the analysis framework (docs/EXTENSIONS_SPEC.md section 3.1).
// Built-in data is analysis/materials.json (SI units, every property with a quality label and its sources). A project can add its
// own materials (project.analysis.materials) and assign a material to each body (project.bodies[name].material). Nothing here
// touches geometry; assignments and custom materials change through typed ops (model.js: set_body_material, set_material, ...).
import builtin from '../../analysis/materials.json' with { type: 'json' };

export const MATERIAL_SCHEMA_VERSION = builtin.schema;
export const SOURCES = builtin.sources;
export const DEFAULT_MATERIAL = 'pla';
export const QUALITIES = ['datasheet', 'handbook', 'estimate', 'measured', 'n/a'];

/** Property table: key, label, SI unit, how the UI shows it (factor from SI, unit label, decimals) and the sanity bounds in SI. */
export const PROPS = [
  { k: 'k', label: 'Conductivity k', si: 'W/(m·K)', ui: 'W/(m·K)', f: 1, dp: 3, min: 0.005, max: 500 },
  { k: 'rho', label: 'Density ρ', si: 'kg/m³', ui: 'kg/m³', f: 1, dp: 0, min: 0.1, max: 25000 },
  { k: 'cp', label: 'Specific heat cp', si: 'J/(kg·K)', ui: 'J/(kg·K)', f: 1, dp: 0, min: 100, max: 5000 },
  { k: 'emissivity', label: 'Emissivity ε', si: '1', ui: '', f: 1, dp: 2, min: 0.01, max: 1 },
  { k: 'E', label: "Young's modulus E", si: 'Pa', ui: 'GPa', f: 1e-9, dp: 2, min: 1e5, max: 1e12 },
  { k: 'nu', label: "Poisson's ratio ν", si: '1', ui: '', f: 1, dp: 3, min: 0, max: 0.5 },
  { k: 'yield_strength', label: 'Yield strength', si: 'Pa', ui: 'MPa', f: 1e-6, dp: 1, min: 1e5, max: 5e9 },
  { k: 'tensile_strength', label: 'Tensile strength', si: 'Pa', ui: 'MPa', f: 1e-6, dp: 1, min: 1e5, max: 5e9 },
  { k: 'interlayer_factor', label: 'Interlayer factor', si: '1', ui: '', f: 1, dp: 2, min: 0.05, max: 1 },
];
const PROP_KEYS = PROPS.map((p) => p.k);
const ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;

export const builtinMaterials = () => builtin.materials.map((m) => JSON.parse(JSON.stringify(m)));
export const isBuiltin = (id) => builtin.materials.some((m) => m.id === id);

/** Throws on a material the framework cannot use. A property may be null ("not applicable", e.g. yield strength of air). */
export function validateMaterial(m) {
  if (!m || typeof m !== 'object') throw new Error('material must be an object');
  if (!ID.test(m.id || '')) throw new Error('material id must be 1 to 32 characters of a-z 0-9 _ -');
  if (typeof m.name !== 'string' || !m.name.trim() || m.name.length > 60) throw new Error(`material "${m.id}": needs a name (up to 60 characters)`);
  if (!m.properties || typeof m.properties !== 'object') throw new Error(`material "${m.id}": needs properties`);
  for (const key of Object.keys(m.properties)) if (!PROP_KEYS.includes(key)) throw new Error(`material "${m.id}": unknown property "${key}"`);
  for (const p of PROPS) {
    const v = m.properties[p.k];
    if (v == null) throw new Error(`material "${m.id}": property ${p.k} is missing (use value null for "not applicable")`);
    if (v.value !== null && !(typeof v.value === 'number' && Number.isFinite(v.value))) throw new Error(`material "${m.id}": ${p.k} must be a number or null`);
    if (v.value !== null && (v.value < p.min || v.value > p.max)) throw new Error(`material "${m.id}": ${p.k} = ${v.value} ${p.si} is outside the plausible range ${p.min} to ${p.max}`);
    if (!QUALITIES.includes(v.quality)) throw new Error(`material "${m.id}": ${p.k} quality must be one of ${QUALITIES.join(', ')}`);
    if (v.value !== null && v.quality !== 'n/a' && !(Array.isArray(v.sources) && v.sources.length)) throw new Error(`material "${m.id}": ${p.k} needs at least one source`);
    if (v.range && !(Array.isArray(v.range) && v.range.length === 2 && v.range.every(Number.isFinite) && v.range[0] <= v.range[1])) throw new Error(`material "${m.id}": ${p.k} range must be [low, high]`);
  }
  return m;
}

/** All materials for a project: built-in plus the project's own. A project material cannot reuse a built-in id. */
export function allMaterials(project) {
  const out = new Map(builtin.materials.map((m) => [m.id, m]));
  for (const m of (project && project.analysis && project.analysis.materials) || []) if (!out.has(m.id)) out.set(m.id, { ...m, custom: true });
  return out;
}

/** The material of a body, and whether it was assumed because none was assigned (the result must say so). */
export function materialOf(project, body) {
  const mats = allMaterials(project);
  const id = project && project.bodies && project.bodies[body] && project.bodies[body].material;
  if (id && mats.has(id)) return { material: mats.get(id), assumed: false };
  return { material: mats.get(DEFAULT_MATERIAL), assumed: true, missing: id && !mats.has(id) ? id : null };
}

/** SI numbers only: { k, rho, ... } with null for n/a. */
export const siProps = (m) => Object.fromEntries(PROP_KEYS.map((k) => [k, m.properties[k].value]));

export const toUi = (p, v) => (v == null ? null : +(v * p.f).toFixed(p.dp));
export const fromUi = (p, v) => v / p.f;

/** Qualities of the properties a given analysis actually needs, worst first. Used to put an honest line on a result. */
export function qualityOf(m, keys) {
  const order = ['estimate', 'handbook', 'datasheet', 'measured'];
  let worst = 'measured';
  for (const k of keys) { const q = m.properties[k]?.quality; if (q && q !== 'n/a' && order.indexOf(q) < order.indexOf(worst)) worst = q; }
  return worst;
}
