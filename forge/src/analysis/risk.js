// The risk flag: the result type every A6 step emits (docs/EXTENSIONS_SPEC.md section 4A, "A6 shared pieces").
//
//   { id, mechanism, severity, body, where:[x,y,z], metric, value, limit, margin, why, fix, confidence }
//
//   mechanism   'preload_loss' | 'self_loosening' | 'creep' | 'fatigue' | 'resonance' | 'shock' | 'thermal_cycling' | 'moisture' | 'data_gap'
//   severity    'high' | 'medium' | 'low' | 'info', from the margin: margin < 1 high; margin < the life safety factor medium; margin < 10 low; else info
//   margin      capacity / demand (a number above 1 is a pass); value, limit and margin are null only for a data_gap flag
//   why         one plain sentence
//   fix         { text, proposals: [{ id, label, ops: Op[] }] }: a suggested change; each proposal is a list of typed ops the user can apply (one undoable step)
//   confidence  'low' | 'medium' | 'high': capped by the result's validity (estimate: medium at most) and by the worst material-data quality used
//               (typical or estimate data: low, whatever the margin)
//
// A package builds its flags with the same rules in its own file (a Worker loads one file); test_durability.mjs checks that the two agree.
// Gating (used by the scorecard rows below): a high flag fails the verdict unless its confidence is low (then it is advisory); an
// experimental result never gates.
import { LOW_QUALITIES } from './materials.js';

export const MECHANISMS = ['preload_loss', 'self_loosening', 'creep', 'fatigue', 'resonance', 'shock', 'thermal_cycling', 'moisture', 'data_gap'];
export const SEVERITIES = ['high', 'medium', 'low', 'info'];
export const CONFIDENCES = ['low', 'medium', 'high'];
export const MARGIN_CAP = 1000;
export const FLAG_COLUMNS = ['id', 'mechanism', 'severity', 'confidence', 'body', 'where', 'metric', 'value', 'limit', 'margin', 'why', 'fix'];

export function severityFromMargin(margin, safetyFactor = 3) {
  if (!(margin >= 1)) return 'high';
  if (margin < safetyFactor) return 'medium';
  if (margin < 10) return 'low';
  return 'info';
}
/** A thread locker or a locking washer lowers the severity one step but never removes the flag: it stays at low or above unless it was info already. */
export function stepDown(sev) { return sev === 'high' ? 'medium' : sev === 'medium' ? 'low' : sev; }

const VALIDITY_CAP = { validated: 'high', estimate: 'medium', experimental: 'low' };
const ORDER = { low: 0, medium: 1, high: 2 };
/** qualities: the quality labels of every material value the flag used. */
export function confidenceOf(validity, qualities) {
  let c = VALIDITY_CAP[validity] || 'low';
  const qs = (qualities || []).filter((q) => q && q !== 'n/a');
  if (!qs.length) return 'low'; // nothing known about the data behind it
  if (qs.some((q) => LOW_QUALITIES.includes(q))) c = 'low';
  else if (qs.some((q) => q === 'handbook' || q === 'datasheet') && ORDER[c] > ORDER.medium) c = 'medium';
  return c;
}

export function validateFlag(f) {
  const bad = (m) => { throw new Error(`invalid risk flag${f && f.id ? ` "${f.id}"` : ''}: ${m}`); };
  if (!f || typeof f !== 'object') bad('not an object');
  if (typeof f.id !== 'string' || !/^[\w.:-]{1,80}$/.test(f.id)) bad('id');
  if (!MECHANISMS.includes(f.mechanism)) bad(`mechanism must be one of ${MECHANISMS.join(', ')}`);
  if (!SEVERITIES.includes(f.severity)) bad(`severity must be one of ${SEVERITIES.join(', ')}`);
  if (typeof f.body !== 'string') bad('body');
  if (!(Array.isArray(f.where) && f.where.length === 3 && f.where.every(Number.isFinite))) bad('where must be [x, y, z] in mm');
  if (typeof f.metric !== 'string' || !f.metric) bad('metric');
  const gap = f.mechanism === 'data_gap';
  for (const k of ['value', 'limit', 'margin']) if (!(Number.isFinite(f[k]) || (gap && f[k] === null))) bad(`${k} must be a finite number${gap ? ' or null' : ''}`);
  if (typeof f.why !== 'string' || !f.why.trim() || f.why.length > 400) bad('why must be one plain sentence (up to 400 characters)');
  const fx = f.fix;
  if (!fx || typeof fx.text !== 'string' || !fx.text.trim() || !Array.isArray(fx.proposals)) bad('fix must be { text, proposals: [] }');
  for (const p of fx.proposals) if (!p || typeof p.id !== 'string' || typeof p.label !== 'string' || !Array.isArray(p.ops) || !p.ops.every((o) => o && typeof o.op === 'string')) bad('each fix proposal needs id, label and ops [{ op, ... }]');
  if (!CONFIDENCES.includes(f.confidence)) bad(`confidence must be one of ${CONFIDENCES.join(', ')}`);
  return f;
}

/** Does this flag fail the verdict? high, not low confidence, and the result is not experimental. */
export const gates = (f, validity) => f.severity === 'high' && f.confidence !== 'low' && validity !== 'experimental';

const sevRank = (s) => SEVERITIES.indexOf(s);
export const sortFlags = (flags) => flags.slice().sort((a, b) => sevRank(a.severity) - sevRank(b.severity) || (a.margin ?? -1) - (b.margin ?? -1) || a.id.localeCompare(b.id));

const r = (x) => (x === null || x === undefined ? '' : Math.abs(x) >= 1000 || (Math.abs(x) < 0.01 && x !== 0) ? x.toExponential(2) : String(+x.toPrecision(4)));
/** The flags as a table: columns and string rows (the same cells the CSV and markdown exports use). */
export function flagsTable(flags) {
  return { columns: FLAG_COLUMNS, rows: sortFlags(flags).map((f) => [f.id, f.mechanism, f.severity, f.confidence, f.body, f.where.map((v) => +v.toFixed(2)).join(' '), f.metric, r(f.value), r(f.limit), r(f.margin), f.why, f.fix.text]) };
}
const csvCell = (s) => (/[",\n]/.test(String(s)) ? `"${String(s).replace(/"/g, '""')}"` : String(s));
export const flagsToCsv = (flags) => { const t = flagsTable(flags); return [t.columns, ...t.rows].map((row) => row.map(csvCell).join(',')).join('\n') + '\n'; };
export function flagsToMarkdown(flags, { validity = 'estimate', title = 'Risk flags' } = {}) {
  const t = flagsTable(flags), esc = (s) => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' ');
  return [`## ${title}`, '', `Validity: ${validity}. Every number is an estimate; confidence is capped by the quality of the material data used.`, '',
    `| ${t.columns.join(' | ')} |`, `| ${t.columns.map(() => '---').join(' | ')} |`, ...t.rows.map((row) => `| ${row.map(esc).join(' | ')} |`), ''].join('\n');
}

/**
 * Scorecard rows for a result's flags (the same row shape as an analysis check; see runner.js). One row per high or medium flag,
 * advisory unless it gates; one passing row when none is that serious. `res` is null when the analysis has not run.
 */
export function flagRows(res, { ext, analysis, label }) {
  const base = { ext, analysis, items: [] };
  if (!res) return [{ ...base, id: `${ext}:${analysis}:risk`, label: `${label}: risk flags`, severity: 'advisory', pass: false, detail: 'not run yet (Analyze > Run)', validity: 'estimate' }];
  const flags = res.flags || [], serious = flags.filter((f) => f.severity === 'high' || f.severity === 'medium');
  if (!serious.length) return [{ ...base, id: `${ext}:${analysis}:risk`, label: `${label}: no high or medium risk flags`, severity: res.validity === 'experimental' ? 'advisory' : 'hard', pass: true, validity: res.validity,
    detail: `${flags.length} flag${flags.length === 1 ? '' : 's'}, none above low · ${res.validity}` }];
  return sortFlags(serious).map((f) => {
    const hard = gates(f, res.validity);
    return { ...base, id: `${ext}:${analysis}:flag:${f.id}`, label: `${f.mechanism.replace('_', ' ')}: ${f.body || 'part'}`, severity: hard ? 'hard' : 'advisory', pass: false, validity: res.validity,
      detail: `${f.severity} · ${f.why} (margin ${f.margin === null ? 'n/a' : r(f.margin)}, confidence ${f.confidence}${hard ? '' : f.severity === 'high' ? ', advisory' : ''}) · ${res.validity}`, items: [{ where: f.where, flag: f.id }] };
  });
}
