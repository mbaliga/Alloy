// The analysis result model (docs/EXTENSIONS_SPEC.md section 3.3) and its validation.
//   { id, version, inputsHash, summary[{metric,value,unit,where?}], fields[{name,unit,kind,data,body?,grid?}], warnings[], validity }
// `validity` is always shown with a result and never hidden. 'experimental' results cannot drive a pass/fail check.
// A package returns { summary, fields, warnings, validity }; the runner adds id, version and inputsHash and validates the whole.
import { sha256Hex } from '../ext/loader.js';

export const VALIDITY = ['estimate', 'validated', 'experimental'];
export const FIELD_KINDS = ['surface', 'voxel', 'vector'];
export const LIMITS = { summary: 200, warnings: 50, fields: 16, fieldBytes: 96 * 1024 * 1024 };

export const VALIDITY_TEXT = {
  validated: 'Validated: agrees with reference cases within the stated tolerance (analysis-validation/).',
  estimate: 'Estimate: a simplified model; use it to compare designs and catch gross errors, not as a sign-off.',
  experimental: 'Experimental: not validated. Shown for exploration; it never decides a pass or fail.',
};

const canon = (v) => (v instanceof Float32Array || v instanceof Float64Array || v instanceof Uint8Array || v instanceof Uint32Array ? { typed: v.constructor.name, n: v.length } : Array.isArray(v) ? v.map(canon) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v);
const hex = async (u8) => sha256Hex(u8);

/** sha256 over everything that can change an answer: the package version, the analysis, the setup, the package's own stored data (project.ext[id]: its settings, vents, ...) and the geometry it saw. */
export async function inputsHash({ ext, version, analysis, setup, bodies, extData }) {
  const parts = [JSON.stringify(canon({ ext, version, analysis, setup, extData: extData === undefined ? null : extData }))];
  for (const b of bodies || []) parts.push(`${b.name}:${await hex(new Uint8Array(b.positions.buffer, b.positions.byteOffset, b.positions.byteLength))}:${await hex(new Uint8Array(b.indices.buffer, b.indices.byteOffset, b.indices.byteLength))}`);
  return 'sha256-' + await sha256Hex(new TextEncoder().encode(parts.join('\n')));
}

const isTyped = (d) => d instanceof Float32Array;

/** Throws an Error listing the first problem. `meshes` ({name: vertexCount}) lets surface fields be checked against their body. */
export function validateResult(r, meshes = {}) {
  const bad = (m) => { throw new Error('invalid analysis result: ' + m); };
  if (!r || typeof r !== 'object') bad('not an object');
  for (const k of ['id', 'version', 'inputsHash']) if (typeof r[k] !== 'string' || !r[k]) bad(`missing ${k}`);
  if (!VALIDITY.includes(r.validity)) bad(`validity must be one of ${VALIDITY.join(', ')}`);
  if (!Array.isArray(r.summary) || r.summary.length > LIMITS.summary) bad(`summary must be an array of at most ${LIMITS.summary} rows`);
  const metrics = new Set();
  for (const s of r.summary) {
    if (!s || typeof s.metric !== 'string' || !/^[\w.-]{1,60}$/.test(s.metric)) bad('every summary row needs a metric name');
    if (metrics.has(s.metric)) bad(`duplicate metric "${s.metric}"`);
    metrics.add(s.metric);
    if (typeof s.value !== 'number' || !Number.isFinite(s.value)) bad(`metric ${s.metric}: value must be a finite number`);
    if (typeof s.unit !== 'string') bad(`metric ${s.metric}: unit must be a string ("" for none)`);
    if (s.where != null && !(Array.isArray(s.where) && s.where.length === 3 && s.where.every(Number.isFinite))) bad(`metric ${s.metric}: where must be [x,y,z] in mm`);
  }
  if (!Array.isArray(r.warnings) || r.warnings.length > LIMITS.warnings || r.warnings.some((w) => typeof w !== 'string')) bad('warnings must be an array of strings');
  if (!Array.isArray(r.fields) || r.fields.length > LIMITS.fields) bad(`fields must be an array of at most ${LIMITS.fields}`);
  let bytes = 0;
  for (const f of r.fields) {
    if (!f || typeof f.name !== 'string' || !f.name) bad('every field needs a name');
    if (typeof f.unit !== 'string') bad(`field ${f.name}: unit must be a string`);
    if (!FIELD_KINDS.includes(f.kind)) bad(`field ${f.name}: kind must be one of ${FIELD_KINDS.join(', ')}`);
    if (!isTyped(f.data)) bad(`field ${f.name}: data must be a Float32Array`);
    bytes += f.data.byteLength;
    if (f.kind === 'surface') {
      if (typeof f.body !== 'string') bad(`field ${f.name}: a surface field needs the name of its body`);
      if (f.body in meshes && f.data.length !== meshes[f.body]) bad(`field ${f.name}: ${f.data.length} values for ${meshes[f.body]} vertices of "${f.body}" (one value per vertex, in the order of the body mesh)`);
    } else {
      const g = f.grid;
      if (!g || !(Array.isArray(g.origin) && g.origin.length === 3 && g.origin.every(Number.isFinite)) || !(g.h > 0) || !(Array.isArray(g.dims) && g.dims.length === 3 && g.dims.every((n) => Number.isInteger(n) && n > 0))) bad(`field ${f.name}: a ${f.kind} field needs grid { origin:[x,y,z] mm, h mm, dims:[nx,ny,nz] }`);
      const n = g.dims[0] * g.dims[1] * g.dims[2] * (f.kind === 'vector' ? 3 : 1);
      if (f.data.length !== n) bad(`field ${f.name}: ${f.data.length} values for a grid that needs ${n}`);
    }
    // NaN is allowed in a voxel field and means "no data here" (outside air): it is drawn transparent. Infinity never is.
    for (let i = 0; i < f.data.length; i += Math.max(1, f.data.length >> 12)) if (!Number.isFinite(f.data[i]) && !(f.kind === 'voxel' && Number.isNaN(f.data[i]))) bad(`field ${f.name}: contains ${f.kind === 'voxel' ? 'Infinity' : 'NaN or Infinity'}`);
  }
  if (bytes > LIMITS.fieldBytes) bad(`fields are ${Math.round(bytes / 1048576)} MB; the limit is ${LIMITS.fieldBytes >> 20} MB`);
  return r;
}

/** Fill in what the runner owns. A package that does not state a validity gets 'experimental', the most cautious reading. */
export function finalizeResult(raw, { id, version, inputsHash: hash, warnings = [] }) {
  const r = raw && typeof raw === 'object' ? raw : {};
  return {
    id, version, inputsHash: hash,
    summary: Array.isArray(r.summary) ? r.summary : [], fields: Array.isArray(r.fields) ? r.fields : [],
    warnings: [...warnings, ...(Array.isArray(r.warnings) ? r.warnings : [])],
    validity: VALIDITY.includes(r.validity) ? r.validity : 'experimental',
  };
}

/** What the critic and the scorecard get: rows only, no fields (spec 3.4). */
export const summaryOf = (r) => ({ id: r.id, validity: r.validity, summary: r.summary.map((s) => ({ metric: s.metric, value: s.value, unit: s.unit })), warnings: r.warnings.slice(0, 5) });

/** Does a check threshold hold? */
export function compare(value, op, limit) {
  switch (op) { case '<=': return value <= limit; case '<': return value < limit; case '>=': return value >= limit; case '>': return value > limit; default: throw new Error(`unknown comparison "${op}"`); }
}
