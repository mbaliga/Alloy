// B-rep checks for the OCCT kernel path: a port of the deterministic enclosure checker (interference, wall to every part,
// emitter beam clear, contacts exposed, switch travel free, rear insertion path, build volume) plus metrics (volume,
// envelope, overhang share, mass). Everything is exact B-rep geometry (booleans and BRepExtrema distances) except the
// overhang share, which is area-weighted over the tessellation, and the optional global min-wall estimate, which is sampled.
//
//   enclosureScorecard(k, gen, pcb, enc, opts) -> { variant, checks: [{check, pass, detail}], metrics, pass }
//
// The scorecard has the same shape as the reference checker's JSON, so a design loop and a person read the same thing.
import { componentBox, coord, CLEARANCE_CLASS } from '../../templates/pcb-enclosure/generate.js';
import { estimateMinWall } from '../checks.js';

const r1 = (v) => Math.round(v * 10) / 10;
const r2 = (v) => Math.round(v * 100) / 100;
export const MATERIALS = { pla: 1.24, petg: 1.27 };
export const OVERLAP_TOL = 0.01; // mm^3 of shared volume that counts as interference (touching faces give 0)

const vol = (k, s) => (s ? k.volume(s) : 0);
const overlapVol = (k, a, b) => {
  if (!a || !b) return 0;
  try { const x = a.intersect(b); return x.solids.length ? k.volume(x) : 0; } catch (_) { return 0; }
};

/** Min distance between a solid and the outer skin (every face of `outer`), exact (BRepExtrema). */
export function skinDistance(k, solid, outer) {
  let best = Infinity;
  for (const f of outer.faces) { const d = k.distance(solid, f); if (d < best) best = d; }
  return best;
}

/** Overhang share of a shape for a print-up direction. Area-weighted over the tessellation. */
export function overhang(k, shape, up = [-1, 0, 0], { angle = 45, tol = 0.02, ang = 0.2 } = {}) {
  const m = k.meshOf(shape, tol, ang);
  const p = m.pos, ix = m.idx, T = ix.length / 3;
  const n = Math.hypot(...up); const u = up.map((v) => v / n);
  const sOf = (i) => p[i * 3] * u[0] + p[i * 3 + 1] * u[1] + p[i * 3 + 2] * u[2];
  let smin = Infinity; for (let i = 0; i < p.length / 3; i++) smin = Math.min(smin, sOf(i));
  // orientation: make the signed volume positive so normals point outward
  let sv = 0;
  for (let t = 0; t < T; t++) {
    const a = ix[t * 3] * 3, b = ix[t * 3 + 1] * 3, c = ix[t * 3 + 2] * 3;
    sv += p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) - p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) + p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c]);
  }
  const sign = sv < 0 ? -1 : 1;
  let total = 0, over = 0;
  const lim = -Math.cos((angle * Math.PI) / 180);
  for (let t = 0; t < T; t++) {
    const a = ix[t * 3], b = ix[t * 3 + 1], c = ix[t * 3 + 2];
    const ux = p[b * 3] - p[a * 3], uy = p[b * 3 + 1] - p[a * 3 + 1], uz = p[b * 3 + 2] - p[a * 3 + 2];
    const vx = p[c * 3] - p[a * 3], vy = p[c * 3 + 1] - p[a * 3 + 1], vz = p[c * 3 + 2] - p[a * 3 + 2];
    const nx = (uy * vz - uz * vy) * sign, ny = (uz * vx - ux * vz) * sign, nz = (ux * vy - uy * vx) * sign;
    const len = Math.hypot(nx, ny, nz), ar = len / 2;
    if (ar < 1e-12) continue;
    total += ar;
    const cosd = (nx * u[0] + ny * u[1] + nz * u[2]) / len;
    const onBed = Math.max(sOf(a), sOf(b), sOf(c)) - smin < 0.05;
    if (cosd < lim && !onBed) over += ar;
  }
  return { share: total ? (over / total) * 100 : 0, area: over, total };
}

function boxOf(c) { return componentBox(c); }
function beamShape(k, comp, ref, emitter) {
  const cy = comp.cyl; if (!cy) return null;
  const ax = cy.axis || 'x', dir = emitter.direction || [-1, 0, 0];
  const sgn = dir['xyz'.indexOf(ax)] < 0 ? -1 : 1;
  const lens = sgn < 0 ? cy.a0 : cy.a1;
  const far = sgn < 0 ? ref.xFront - (emitter.reach ?? 8) : lens + (emitter.reach ?? 8);
  const a0 = sgn < 0 ? lens - 0.2 : lens + 0.2; // starts 0.2 mm ahead of the lens face
  return k.cyl(ax, emitter.beamR ?? 2.2, Math.min(a0, far), Math.max(a0, far), cy.c[0], cy.c[1]);
}

/**
 * @param k    ops (makeOps)
 * @param gen  result of generateEnclosure: { shell, cap, outer, keepouts, xFront, xRear, stations }
 * @param pcb  pcb block
 * @param enc  numeric enclosure params
 * @param opts { variant, bed:[x,y,z], bedName, bedMargin, density map, minWallSamples }
 */
export function enclosureScorecard(k, gen, pcb, enc, opts = {}) {
  const { shell, cap, outer, keepouts } = gen;
  const ref = { xFront: gen.xFront, xRear: gen.xRear };
  const R = { variant: opts.variant || enc.style, checks: [], metrics: {} };
  const chk = (name, ok, detail, extra) => R.checks.push({ check: name, pass: !!ok, detail, ...(extra || {}) });
  const yB = enc.yBottom;
  const comps = pcb.components || [];
  const hit = (ko) => overlapVol(k, ko, shell) + overlapVol(k, ko, cap);

  for (const c of comps) {
    const ko = keepouts[c.name];
    const v = hit(ko);
    chk(`no interference: ${c.name}`, v <= OVERLAP_TOL, `${r2(v)} mm³ of the keep-out overlaps material`);
    if (c.exposed || !c.wall) continue; // exposed contacts are flush with the outside by design; no wall rule
    const need = enc.clearance + (c.wall.extra ?? 1.0);
    const minY = c.wall.minY != null ? c.wall.minY : yB + 0.05;
    const [x0, x1, y0, y1, z0, z1] = boxOf(c);
    let part = ko;
    if (y0 < minY) { part = c.kind === 'cylinder' ? ko : k.box(x0, x1, minY, Math.max(y1, minY + 1e-3), z0, z1); }
    const d = skinDistance(k, part, outer);
    chk(`wall around ${c.name} >= ${need.toFixed(1)} mm`, d >= need - 1e-3, `min ${r2(d)} mm (exact distance to the outer skin)`);
  }

  for (const c of comps) if (c.emitter) {
    const beam = beamShape(k, c, ref, c.emitter);
    const v = beam ? hit(beam) : 0;
    chk(`${c.name} aperture clear (r ${c.emitter.beamR ?? 2.2} beam)`, v <= OVERLAP_TOL, `${r2(v)} mm³ of the beam path is blocked`);
  }
  for (const c of comps) if (c.exposed) {
    const dir = c.exposed.dir || c.exposed || '-y';
    const [x0, x1, y0, y1, z0, z1] = boxOf(c);
    const probe = k.box(x0 + 0.01, x1 - 0.01, yB - 1.5, yB + 0.2, z0 + 0.01, z1 - 0.01);
    const v = hit(probe);
    chk(`${c.exposed.label || c.name + ' contacts'} exposed`, v <= OVERLAP_TOL, `${r2(v)} mm³ blocked on the ${dir} face`);
  }
  for (const c of comps) if (c.paddle) {
    const [x0, x1, , , z0, z1] = boxOf(c), s = c.paddle.shrink || [0.3, 0.5];
    const probe = k.box(x0 + s[0], x1 - s[0], yB - 0.5, (c.paddle.upTo ?? 0.0), z0 + s[1], z1 - s[1]);
    const v = hit(probe);
    chk(`${c.paddle.label || c.name + ' paddle slot'} free`, v <= OVERLAP_TOL, `${r2(v)} mm³ blocked`);
  }
  const rear = comps.filter((c) => c.insert === 'rear');
  if (rear.length) {
    let tot = 0;
    for (const c of rear) {
      const b = boxOf(c); const xe = ref.xRear - 0.05;
      const sweep = c.kind === 'cylinder' ? k.cyl(c.cyl.axis || 'x', c.cyl.r, b[0], xe, c.cyl.c[0], c.cyl.c[1]) : k.box(b[0], xe, b[2], b[3], b[4], b[5]);
      tot += overlapVol(k, sweep, shell);
    }
    chk(`rear insertion path clear (${rear.map((c) => c.name).join(', ')})`, tot <= OVERLAP_TOL, `${r2(tot)} mm³ of material in the way of the sweep out of the rear`);
  }

  // build volume: assembled envelope, oriented so the print-up direction is the bed's z
  const bb = (s) => k.bounds(s);
  const b0 = bb(shell), b1 = cap ? bb(cap) : b0;
  const mn = [0, 1, 2].map((i) => Math.min(b0.min[i], b1.min[i])), mx = [0, 1, 2].map((i) => Math.max(b0.max[i], b1.max[i]));
  const dims = mx.map((v, i) => v - mn[i]);
  const up = enc.print?.up || [-1, 0, 0];
  const ua = up.map(Math.abs).indexOf(Math.max(...up.map(Math.abs)));
  const bed = opts.bed || enc.bed || [180, 180, 180], margin = opts.bedMargin ?? 5;
  const plate = dims.filter((_, i) => i !== ua).sort((a, b) => b - a), bedPlate = [bed[0], bed[1]].sort((a, b) => b - a);
  const fits = dims[ua] < bed[2] - margin && plate[0] < bedPlate[0] - margin && plate[1] < bedPlate[1] - margin;
  const bedLabel = bed[0] === bed[1] && bed[1] === bed[2] ? `${bed[0]}³` : bed.join('×');
  chk(`fits ${opts.bedName || enc.bedName || 'A1 mini'} build volume (${bedLabel})`, fits, `assembled envelope ${dims.map(r1).join(' × ')} mm`);

  // metrics
  const oh = overhang(k, shell, up);
  const vs = vol(k, shell), vc = vol(k, cap), vt = vs + vc;
  R.metrics.overhang_share_pct = r1(oh.share);
  R.metrics.overhang_area_mm2 = Math.round(oh.area);
  Object.assign(R.metrics, {
    shell_cm3: r2(vs / 1000), cap_cm3: r2(vc / 1000), plastic_cm3: r2(vt / 1000),
  });
  if (enc.referencePlasticMm3) R.metrics.vs_original_plastic_pct = r1((vt / enc.referencePlasticMm3 - 1) * 100);
  R.metrics.mass_pla_g = r1((vt / 1000) * MATERIALS.pla);
  R.metrics.mass_petg_g = r1((vt / 1000) * MATERIALS.petg);
  R.metrics.envelope_mm = dims.map(r1);
  const st = gen.stations;
  if (st && st.length > 2) {
    R.metrics.body_width_rear_mm = r1(2 * st[st.length - 2].hb);
    R.metrics.body_width_front_mm = r1(2 * Math.max(...st.map((s) => s.hb)));
  }
  R.metrics.shell_solids = shell.solids.length;
  R.metrics.shell_valid = k.isValid(shell);
  if (opts.minWallSamples > 0) {
    try {
      const m = k.meshOf(shell, 0.05, 0.3);
      const w = estimateMinWall({ pos: m.pos, idx: m.idx }, { maxSamples: opts.minWallSamples, maxWork: 2e7 });
      R.metrics.min_wall_sampled_mm = Number.isFinite(w.min) ? r2(w.min) : null;
    } catch (_) { R.metrics.min_wall_sampled_mm = null; }
  }
  R.pass = R.checks.every((c) => c.pass);
  return R;
}

/** Adapt a scorecard to the design loop's runChecks() result shape. */
export function scorecardToChecks(sc, features = {}) {
  const checks = sc.checks.map((c, i) => ({ id: 'brep_' + (i + 1), label: c.check, severity: 'hard', pass: c.pass, detail: c.detail }));
  const failed = checks.filter((c) => !c.pass).map((c) => c.id);
  return { passed: failed.length === 0, checks, failed, metrics: { ...sc.metrics, kernel: 'occt' } };
}
