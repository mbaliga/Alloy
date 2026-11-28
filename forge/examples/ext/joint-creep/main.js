// Joint, preload and creep (A6a): a simplified VDI 2230 bolted-joint calculation, creep relaxation of the clamped plastic over the duty
// temperature profile, and slip / self-loosening per shot. Plain arithmetic, milliseconds, validity "estimate" and never more.
//
//   preload from torque   F = T / (K d)  (nut factor)   or   F = T / (0.16 P + 0.58 d2 mu_G + (D_Km / 2) mu_K)  (thread and under-head friction)
//   bolt compliance       d_S = [0.4 d + l_shank] / (E_S A_N) + l_thread / (E_S A_3) + 0.5 d / (E_S A_3) + 0.4 d / (E_M A_N)   (head, shank, free thread, engaged thread, nut)
//   clamped compliance    pressure cone (VDI 2230): two cones of half angle phi from the head and the nut (w = 1), one cone for a tapped hole (w = 2),
//                         tan(phi) = 0.362 + 0.032 ln(beta_L / 2) + 0.153 ln(y); the cone is cut off at the outer diameter D_A when the part is narrower
//   embedding             F_Z = f_Z / (d_S + d_P)   (VDI guide values for metal interfaces; a plastic interface is a data gap)
//   thermal change        F(T) = [D0 - f_Z + (sum(alpha_i l_i) - alpha_S l_K)(T - T0) - delta_creep] / (d_S + d_P(T)),   D0 = F_M (d_S + d_P)
//   creep (Findley)       clamped plastic creep compliance J(t) = 1 + beta(T) t^n, linear viscoelastic superposition (Boltzmann) at fixed joint length:
//                         F C + sum_i c_i beta_i integral (xi(t) - xi(tau))^n dF(tau) = D - f_Z + shift(T), with a reduced time xi for temperature (see creepRun).
//                         (Earlier drafts used time hardening, F = F0 exp(-kappa beta t^n); that over-predicts the relaxation, 19 % at one year in the reference case.)
//   slip                  margin = mu_clamp n F_residual / F_Q,   F_Q = carried mass x peak recoil acceleration; a thread locker lowers the severity one step
// Everything is SI inside (m, N, Pa, K as differences, s); lengths from the project are mm and are converted at the top of each function.
const G0 = 9.80665, PI = Math.PI;
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const finite = (x) => Number.isFinite(x);
const CAP = 1000; // margins are capped so they stay finite numbers

// ───────────────────────── fastener tables (the same numbers as src/analysis/durability.js; a test compares them) ─────────────────────────
const BOLTS = {
  M2: { d: 2, P: 0.4, d2: 1.740, d3: 1.509, As: 2.07, dk: 3.8, dh: 2.4, s: 4 }, M2_5: { d: 2.5, P: 0.45, d2: 2.208, d3: 1.948, As: 3.39, dk: 4.5, dh: 2.9, s: 5 },
  M3: { d: 3, P: 0.5, d2: 2.675, d3: 2.387, As: 5.03, dk: 5.5, dh: 3.4, s: 5.5 }, M4: { d: 4, P: 0.7, d2: 3.545, d3: 3.141, As: 8.78, dk: 7, dh: 4.5, s: 7 },
  M5: { d: 5, P: 0.8, d2: 4.480, d3: 4.019, As: 14.2, dk: 8.5, dh: 5.5, s: 8 }, M6: { d: 6, P: 1, d2: 5.350, d3: 4.773, As: 20.1, dk: 10, dh: 6.6, s: 10 },
  M8: { d: 8, P: 1.25, d2: 7.188, d3: 6.466, As: 36.6, dk: 13, dh: 9, s: 13 },
};
const GRADES = { '4.8': { Rp: 320, metal: 'steel-carbon' }, '8.8': { Rp: 640, metal: 'steel-carbon' }, '10.9': { Rp: 940, metal: 'steel-carbon' }, '12.9': { Rp: 1100, metal: 'steel-carbon' },
  'A2-70': { Rp: 450, metal: 'stainless-304' }, 'A4-70': { Rp: 450, metal: 'stainless-304' }, 'A4-80': { Rp: 600, metal: 'stainless-304' } };
const NEXT_GRADE = { '4.8': '8.8', '8.8': '10.9', '10.9': '12.9', 'A2-70': 'A4-80', 'A4-70': 'A4-80' };
const SIZES = ['M2', 'M2_5', 'M3', 'M4', 'M5', 'M6', 'M8'];
const LARGE_WASHER_OD = { M2: 6, M2_5: 8, M3: 9, M4: 12, M5: 15, M6: 18, M8: 24 }; // ISO 7093 large series outer diameters, mm
const sizeKey = (s) => String(s).replace('.', '_');

// VDI 2230 guide values for embedding, micrometres, metal interfaces with Rz below 10 um under tension or compression
const EMBED_UM = { thread: 3, head: 2.5, inner: 1.5 };
const YIELD_USE = 0.9;            // allowed utilisation of the bolt proof strength at assembly (VDI 2230)
const BEARING_FRACTION = 0.5;     // rule of thumb, not a standard: keep the plastic bearing pressure below half its yield strength at the hottest temperature
const MIN_KEEP_FRACTION = 0.25;   // placeholder: a joint with under a quarter of its nominal preload left is treated as no longer clamping, unless the joint states min_clamp_N
const SHEAR_YIELD = 0.577;        // von Mises shear / tension yield ratio for the pull-out of an insert or tapped thread in plastic
const ENGAGE_METAL = 0.8, ENGAGE_PLASTIC = 2.0; // minimum thread engagement in multiples of d: rules of thumb, not a standard
const BOSS_OD_FACTOR = 3;         // placeholder: outer diameter of the boss around a thread or insert in plastic, in multiples of d, when the joint does not state it
const MU_G_TORSION = 0.14;        // thread friction assumed for the torsion share of the assembly stress when the nut-factor method gives none

// ───────────────────────── table lookups ─────────────────────────
function interp(points, T) {
  if (!points || !points.length) return null;
  if (T <= points[0][0]) return points[0][1];
  for (let i = 1; i < points.length; i++) if (T <= points[i][0]) { const [t0, y0] = points[i - 1], [t1, y1] = points[i]; return y0 + (y1 - y0) * (T - t0) / (t1 - t0); }
  return points[points.length - 1][1];
}

// ───────────────────────── flags: the same rules as src/analysis/risk.js ─────────────────────────
function severityFromMargin(margin, sf = 3) { if (!(margin >= 1)) return 'high'; if (margin < sf) return 'medium'; if (margin < 10) return 'low'; return 'info'; }
const stepDown = (s) => (s === 'high' ? 'medium' : s === 'medium' ? 'low' : s);
const LOW_Q = ['typical', 'estimate'];
function confidenceOf(validity, qualities) {
  let c = validity === 'validated' ? 'high' : validity === 'estimate' ? 'medium' : 'low';
  const qs = (qualities || []).filter((q) => q && q !== 'n/a');
  if (!qs.length) return 'low';
  if (qs.some((q) => LOW_Q.includes(q))) c = 'low';
  else if (c === 'high' && qs.some((q) => q === 'handbook' || q === 'datasheet')) c = 'medium';
  return c;
}
const capMargin = (m) => (finite(m) ? clamp(m, -CAP, CAP) : CAP);

// ───────────────────────── geometry and stiffness ─────────────────────────
/** VDI 2230 tan(phi) for a clamped part of total length lK (mm), head/nut diameter dW and outer diameter DA (null: wide). */
function tanPhi(lK, dW, DA, w) {
  const betaL = lK / dW; let t = 0.6;
  for (let i = 0; i < 4; i++) { const y = Math.max(1, (DA || dW + w * lK * t) / dW); t = clamp(0.362 + 0.032 * Math.log(betaL / 2) + 0.153 * Math.log(y), 0.3, 0.8); }
  return t;
}
/**
 * Compliance (m/N) of one clamped layer between depths x0 and x1 (mm from the head) of a stack of total length lK: the pressure cone
 * as the substitute body, integrated slice by slice. Cone half angle from tanPhi(); w = 1 gives a cone from each end, w = 2 one cone
 * from the head. `E` in Pa; lengths in mm.
 */
function layerCompliance({ x0, x1, lK, dW, dh, DA, E, w, tp, n = 400 }) {
  const dx = (x1 - x0) / n; let sum = 0;
  for (let i = 0; i < n; i++) {
    const x = x0 + (i + 0.5) * dx, depth = w === 1 ? Math.min(x, lK - x) : x;
    let D = dW + 2 * depth * tp; if (DA) D = Math.min(D, DA);
    D = Math.max(D, dh * 1.0001);
    sum += dx / (D * D - dh * dh);
  }
  return 4 * sum * 1e3 / (PI * E); // sum is in 1/mm; dx in m over areas in m^2 is 1e-3/1e-6 = 1e3 times that
}
/** Cylinder of outer diameter DA: the limit of layerCompliance with a flat cone (used by the validation cases). */
const cylinderCompliance = (l_mm, DA_mm, dh_mm, E) => 4 * l_mm * 1e-3 / (PI * E * (DA_mm * DA_mm - dh_mm * dh_mm) * 1e-6);

/** Bolt compliance (m/N), VDI 2230 simplified: head, shank, free thread in the clamp, engaged thread and nut. */
function boltCompliance({ d, d3, L, lK, ES, EM, threadInPlastic = false }) {
  const AN = PI * d * d / 4, A3 = PI * d3 * d3 / 4, b = 2 * d + 6, shank = Math.max(0, L - b);
  const lSh = Math.min(lK, shank), lTh = Math.max(0, lK - lSh);
  const m = 1e-3, a = 1e6; // mm -> m and mm^2 -> m^2: length [mm] * m / (E [Pa] * area [mm^2] / a)
  const dHead = 0.4 * d * m / (ES * AN / a), dSh = lSh * m / (ES * AN / a), dTh = lTh * m / (ES * A3 / a), dEng = 0.5 * d * m / (ES * A3 / a);
  const dNut = threadInPlastic ? 0 : 0.4 * d * m / (EM * AN / a);
  return { total: dHead + dSh + dTh + dEng + dNut, parts: { head: dHead, shank: dSh, thread: dTh, engaged: dEng, nut: dNut } };
}

/** Preload (N) from a tightening torque (N m) by the nut factor, or by thread and under-head friction (VDI 2230). d, P, d2, dW, dh in mm. */
function preloadFromTorque(M, { method, K, d, P, d2, dW, dh, muG, muK }) {
  if (method === 'nut_factor') return M / (K * d * 1e-3);
  const Dkm = (dW + dh) / 2;
  return M / ((0.16 * P + 0.58 * d2 * muG + (Dkm / 2) * muK) * 1e-3);
}

/** Preload change (N) of a joint for a temperature change dT (K): sum of alpha_i l_i of the clamped layers minus alpha_S l_K of the bolt, over the total compliance. */
function thermalPreloadChange({ layers, alphaS, lK, dT, C }) {
  const grow = layers.reduce((a, l) => a + l.alpha * l.l, 0) - alphaS * lK; // m per K
  return grow * dT / C;
}

/** Embedding loss (N): F_Z = f_Z / C. */
const embeddingLoss = (fZ_um, C) => fZ_um * 1e-6 / C;

/** Allowed assembly preload (N) at the stated utilisation of the proof strength, with the torsion share of the thread (VDI 2230). */
function allowedAssemblyPreload({ Rp, As, d2, d3, P, muG, nu = YIELD_USE }) {
  const ds = (d2 + d3) / 2, tau = 1.5 * (d2 / ds) * (P / (PI * d2) + 1.155 * muG);
  return nu * Rp * As / Math.sqrt(1 + 3 * tau * tau); // Rp in MPa, As in mm^2 -> N
}

// ───────────────────────── creep ─────────────────────────
/**
 * Preload against time by linear viscoelastic superposition (Boltzmann) of Findley creep, J(t) = 1 + beta t^n per source.
 * The joint has a fixed total length, so at every time
 *     F(t) C(T) + sum_i c_i(T) beta_ref_i H_i(t) = X0 + shift(T),     H_i(t) = integral of (xi_i(t) - xi_i(tau))^n_i dF(tau) over the whole history (F(0) included),
 * c_i(T) the compliance (m/N) of source i at T. Temperature enters by reduced time: xi_i(t) = integral of (beta_i(T)/beta_ref_i)^(1/n_i) dt, so a hot hour
 * ages the material by more than a cold one and the ageing is not undone by cooling (time-temperature superposition; beta_ref_i is beta at the first
 * segment, which only fixes the units of xi). F is held at or above zero: an open joint carries no load.
 * Stepped implicitly with the load increment of each step placed at its midpoint; steps are fine early (the t^n kernel is steep near zero)
 * and one per temperature segment later.
 *   X0        D - f_Z (m)      segments  [{ T, hours }] repeated for `days` days      sources  [{ n, c(T), beta(T) }]
 * Returns { hours, force(T): the preload if the temperature changed to T at the end, checkpoints: [{ t, F, T, Frest }] }.
 */
function creepRun({ X0, days, segments, sources, Cof, shift, checkpoints = [], probeT = null }) {
  const taus = [], dFs = [], xis = sources.map(() => []); // load increments, the times they act at, and the reduced times there
  const bref = sources.map((s) => { const b = s.beta(segments[0].T); return b > 0 ? b : 1; });
  const xi = sources.map(() => 0);
  let t = 0, Fprev = null; const cps = checkpoints.slice().sort((a, b) => a - b); let ci = 0; const out = [];
  const Hat = (i, xin) => { let h = 0; const n = sources[i].n, xs = xis[i]; for (let j = 0; j < dFs.length; j++) h += Math.pow(Math.max(0, xin - xs[j]), n) * dFs[j]; return h; };
  const solve = (T, xin, dxi) => { // preload at reduced times xin and temperature T; dxi the reduced length of the step (null: instantaneous, no increment of its own)
    const C = Cof(T); let num = X0 + shift(T), den = C;
    for (let i = 0; i < sources.length; i++) {
      const b = sources[i].c(T) * bref[i], w = dxi ? Math.pow(dxi[i] / 2, sources[i].n) : 0; num -= b * Hat(i, xin[i]);
      if (dxi && Fprev != null) { num += b * w * Fprev; den += b * w; }
    }
    return Math.max(0, num / den);
  };
  const step = (T, dt) => {
    if (Fprev == null) { Fprev = Math.max(0, (X0 + shift(T)) / Cof(T)); taus.push(0); dFs.push(Fprev); xis.forEach((x) => x.push(0)); } // the load at t = 0, before any creep
    const dxi = sources.map((s, i) => dt * Math.pow(Math.max(0, s.beta(T)) / bref[i], 1 / s.n)), xn = xi.map((x, i) => x + dxi[i]);
    const F = sources.length ? solve(T, xn, dxi) : Math.max(0, (X0 + shift(T)) / Cof(T));
    taus.push(t + dt / 2); dFs.push(F - Fprev); xis.forEach((x, i) => x.push(xi[i] + dxi[i] / 2)); Fprev = F; t += dt; xn.forEach((x, i) => { xi[i] = x; });
  };
  for (let day = 0; day < days; day++) {
    for (const s of segments) {
      let left = s.hours;
      while (left > 1e-9) {
        const next = ci < cps.length ? cps[ci] - t : Infinity;
        let dt = Math.min(left, Math.max(0.2 * t, 0.1)); if (next > 0 && next < dt) dt = next;
        step(s.T, dt); left -= dt;
        while (ci < cps.length && t >= cps[ci] - 1e-9) { out.push({ t: cps[ci], F: Fprev, T: s.T, Frest: probeT != null ? solve(probeT, xi, null) : Fprev }); ci++; }
      }
    }
  }
  return { hours: t, force: (T) => (Fprev == null ? Math.max(0, (X0 + shift(T)) / Cof(T)) : solve(T, xi, null)), checkpoints: out };
}

/** Test helper: the preload after `days` repetitions of constant-beta phases, for a joint of fixed compliances (the temperature argument of creepRun carries beta here). */
function creepRelaxNumbers({ F0, dS, dP, n, phases, days }) {
  const C = dS + dP, segments = phases.map((p) => ({ T: p.beta, hours: p.hours }));
  const r = creepRun({ X0: F0 * C, days, segments, sources: [{ n, c: () => dP, beta: (b) => b }], Cof: () => C, shift: () => 0 });
  return r.force(segments[segments.length - 1].T);
}

// ───────────────────────── materials ─────────────────────────
const isPlasticMat = (m) => !!m && (m.cls === 'fdm-polymer' || (m.cls === 'custom' && m.props.E != null && m.props.E < 1.5e10));
function matData(m, T, humidity) {
  // E, yield and CTE of a material at T (°C) and humidity; each item carries the quality of the data it came from
  const du = m.durability || {}, q = [];
  let E = m.props.E, Y = m.props.yield_strength, alpha = du.cte ? du.cte.value : null, gaps = [];
  const pl = isPlasticMat(m); // the quality of a metal's yield strength and temperature trend does not enter: only E and the expansion do
  if (E == null) gaps.push('E'); else q.push(m.quality.E);
  if (du.E_T) { E *= interp(du.E_T.points, T); if (pl) q.push(du.E_T.quality); } else if (pl) gaps.push('E_T');
  if (Y != null) { if (pl) q.push(m.quality.yield_strength); if (du.yield_T) { Y *= interp(du.yield_T.points, T); if (pl) q.push(du.yield_T.quality); } else if (pl) gaps.push('yield_T'); }
  if (alpha == null) gaps.push('cte'); else q.push(du.cte.quality);
  let wetE = 1, wetY = 1, wetMissing = false;
  if (humidity !== 'dry' && isPlasticMat(m)) { if (du.wet && du.wet[humidity]) { wetE = du.wet[humidity].E; wetY = du.wet[humidity].strength; q.push(du.wet.quality); } else wetMissing = true; }
  return { E: E == null ? null : E * wetE, Y: Y == null ? null : Y * wetY, alpha, gaps, wetMissing, qualities: q };
}
function creepOf(m, T) {
  const c = m.durability && m.durability.creep; if (!c) return null;
  return { n: c.n.value, beta: Math.max(0, interp(c.beta_T.points, T)), qualities: [c.n.quality, c.beta_T.quality], limit: c.linear_limit ? c.linear_limit.value : null };
}

// ───────────────────────── the joint model ─────────────────────────
const bodyOfJoint = (j) => (j.clamps.find((c) => c.body) || {}).body || (j.anchor && j.anchor.body) || (j.clamps[0] && j.clamps[0].external) || '';
const mm = (x) => x * 1e-3;

/**
 * Everything about one joint: stiffness, preload scatter, thermal and creep history, margins. Pure function of (joint, ctx).
 * ctx: { materials (id -> material), bodyMaterial (body -> material), duty|null, ambientC, upstream temperature or null, massKg, massNote }
 */
function jointModel(j, ctx) {
  const bk = BOLTS[sizeKey(j.bolt.size)], g = GRADES[j.bolt.grade], { duty } = ctx;
  const gaps = [], qual = new Set(), assumptions = [];
  const hum = duty ? duty.humidity : 'dry';
  const boltMat = ctx.materials[g.metal], steel = ctx.materials['steel-carbon'];
  const T0 = j.tightening.assembly_C != null ? j.tightening.assembly_C : 20;
  const sleeve = j.insert === 'metal_sleeve';
  const threadInPlastic = (j.thread_in || 'metal') === 'plastic' || j.insert === 'heat_set_brass';
  const w = threadInPlastic ? 2 : 1;
  const dW = j.washer ? j.washer.od_mm : (j.insert === 'moulded_nut' ? bk.s : bk.dk), dh = bk.dh;

  // layers: the clamped parts, head to nut. A metal sleeve replaces the plastic body layers.
  const layers = j.clamps.map((c, i) => {
    let m, label;
    if (c.body != null) { m = ctx.bodyMaterial[c.body] || null; label = c.body; if (!m) gaps.push({ what: `body "${c.body}" is not in the build, so its material is unknown`, key: `body:${c.body}` }); }
    else { m = ctx.materials[c.material] || null; label = c.external; if (!m) gaps.push({ what: `the material "${c.material}" of "${c.external}" is not available`, key: `ext:${c.material}` }); }
    return { i, c, m, label, t: c.thickness_mm, DA: c.outer_d_mm || null, plastic: isPlasticMat(m), sleeved: false };
  });
  for (const l of layers) if (l.c.body != null && ctx.bodyMaterial[l.c.body] && ctx.bodyMaterial[l.c.body].assumed) gaps.push({ what: `body "${l.c.body}" has no material assigned (PLA would only be a guess)`, key: `body:${l.c.body}` });
  if (threadInPlastic && j.anchor && ctx.bodyMaterial[j.anchor.body] && ctx.bodyMaterial[j.anchor.body].assumed) gaps.push({ what: `anchor body "${j.anchor.body}" has no material assigned (PLA would only be a guess)`, key: `body:${j.anchor.body}` });
  if (gaps.some((g) => g.key.startsWith('body:'))) return { ok: false, gaps, reason: 'a body has no material assigned' };
  if (layers.some((l) => !l.m)) return { ok: false, gaps, reason: 'a clamped layer has no material' };
  if (sleeve) {
    if (!steel) return { ok: false, gaps: [...gaps, { what: 'the steel material needed for the sleeve is not available', key: 'sleeve' }], reason: 'no sleeve material' };
    for (const l of layers) if (l.plastic) { l.sleeved = true; l.origM = l.m; l.m = steel; l.DA = j.sleeve && j.sleeve.od_mm ? j.sleeve.od_mm : bk.dk; }
  }
  const lK = layers.reduce((a, l) => a + l.t, 0), L = j.bolt.length_mm;
  const tp = tanPhi(lK, dW, j.clamps.find((c) => c.outer_d_mm) ? j.clamps.find((c) => c.outer_d_mm).outer_d_mm : null, w);

  // temperature history: A2 (or another upstream) at the joint when available, otherwise the duty profile
  const Tmin = duty ? duty.temperature.min_C : T0, Tmaxd = duty ? duty.temperature.max_C : T0;
  const Tuse = ctx.useC != null ? ctx.useC : Tmaxd, source = ctx.useC != null ? ctx.useSource : (duty ? 'the duty profile maximum temperature' : 'the assembly temperature (no duty profile)');
  const Trest = duty ? clamp(ctx.ambientC, Tmin, Math.max(Tmin, Tmaxd)) : T0;
  const Tmax = Math.max(Tmaxd, Tuse);

  // material data at a temperature (E of each layer; bolt and nut)
  const dataAt = (T) => layers.map((l) => matData(l.m, T, l.sleeved ? 'dry' : hum));
  const matGaps = (ls) => { for (const l of ls) { const d = matData(l.m, T0, hum); for (const k of d.gaps) gaps.push({ what: `${l.label} (${l.m.name}) has no ${{ E: 'Young\'s modulus', E_T: 'E against temperature', yield_T: 'yield strength against temperature', cte: 'coefficient of thermal expansion' }[k] || k}`, key: `${l.m.id}:${k}` }); if (d.wetMissing) gaps.push({ what: `${l.label} (${l.m.name}) has no wet/dry factors, so ${hum} humidity is not applied`, key: `${l.m.id}:wet`, level: 'low' }); d.qualities.forEach((q) => qual.add(q)); } };
  matGaps(layers);
  const bd = matData(boltMat, T0, 'dry'); bd.qualities.forEach((q) => qual.add(q)); qual.add('handbook'); // the grade strengths are ISO 898-1 / 3506-1 values
  const Es = boltMat.props.E, Em = (steel || boltMat).props.E, alphaS = bd.alpha;
  if (alphaS == null) gaps.push({ what: `the bolt metal "${boltMat.name}" has no thermal expansion`, key: `${boltMat.id}:cte` });

  const anchorMat = threadInPlastic ? (ctx.bodyMaterial[j.anchor.body] || null) : null;
  if (threadInPlastic && !anchorMat) gaps.push({ what: `the anchor body "${j.anchor && j.anchor.body}" is not in the build`, key: `anchor:${j.anchor && j.anchor.body}` });
  if (threadInPlastic && anchorMat) { const d = matData(anchorMat, T0, hum); for (const k of d.gaps) if (k !== 'cte') gaps.push({ what: `${anchorMat.name} (anchor) has no ${k === 'E_T' ? 'E against temperature' : k === 'yield_T' ? 'yield strength against temperature' : 'Young\'s modulus'}`, key: `${anchorMat.id}:${k}` }); d.qualities.forEach((q) => qual.add(q)); }

  const anchorD = threadInPlastic ? (j.anchor.d_mm != null ? j.anchor.d_mm : (j.insert === 'heat_set_brass' ? 1.5 * bk.d + 0.1 : bk.d)) : 0;
  const bossOd = threadInPlastic ? Math.max(anchorD * 1.05, j.anchor.boss_od_mm != null ? j.anchor.boss_od_mm : BOSS_OD_FACTOR * bk.d) : 0;
  if (threadInPlastic && j.anchor.boss_od_mm == null) gaps.push({ what: `the outer diameter of the boss around the anchor is not stated, so ${BOSS_OD_FACTOR} d (${(BOSS_OD_FACTOR * bk.d).toFixed(1)} mm) is assumed for the anchorage stiffness`, key: 'boss_od', level: 'low' });
  if (j.tightening.method !== 'friction' && j.tightening.mode !== 'preload') gaps.push({ what: `the thread friction is not given by the nut-factor method, so ${MU_G_TORSION} is assumed for the torsion share of the assembly stress`, key: 'mu_torsion', level: 'low' });
  if (j.min_clamp_N == null) gaps.push({ what: 'the clamp load the joint must keep (min_clamp_N) is not stated, so a quarter of the nominal preload is used as a placeholder floor', key: 'min_clamp', level: 'low' });

  // stiffness at a temperature (m/N): bolt + clamped layers (+ the anchor in plastic)
  const compMemo = new Map();
  const comp = (T) => {
    if (compMemo.has(T)) return compMemo.get(T);
    const ds = boltCompliance({ d: bk.d, d3: bk.d3, L, lK, ES: Es * (interp(boltMat.durability && boltMat.durability.E_T ? boltMat.durability.E_T.points : null, T) || 1), EM: Em, threadInPlastic }).total;
    let x = 0; const perLayer = layers.map((l, i) => {
      const d = matData(l.m, T, l.sleeved ? 'dry' : hum), E = d.E;
      const c = layerCompliance({ x0: x, x1: x + l.t, lK, dW, dh, DA: l.DA, E, w, tp }); x += l.t; return { l, c, E, d, i };
    });
    let anc = 0, G = null;
    if (threadInPlastic && anchorMat) { const d = matData(anchorMat, T, hum); G = d.E / (2 * (1 + anchorMat.props.nu)); anc = Math.log(bossOd / anchorD) / (2 * PI * G * mm(j.anchor.length_mm)); } // shear of an annular boss: u = F ln(r2/r1) / (2 pi G L)
    const dp = perLayer.reduce((a, p) => a + p.c, 0);
    const res = { ds, dp, anc, total: ds + dp + anc, perLayer }; compMemo.set(T, res); return res;
  };
  const c0 = comp(T0), C0 = c0.total;
  if (![c0.ds, c0.dp, c0.anc].every(finite) || !(C0 > 0)) return { ok: false, gaps, reason: 'stiffness could not be computed' };

  // preload and scatter
  const t = j.tightening;
  const FM = t.mode === 'preload' ? t.preload_N : preloadFromTorque(t.torque_Nm, { method: t.method, K: t.nut_factor, d: bk.d, P: bk.P, d2: bk.d2, dW, dh, muG: t.mu_thread, muK: t.mu_head });
  const alphaA = t.alpha_A != null ? t.alpha_A : (t.mode === 'preload' ? 1 : 1.6);
  const FMmin = FM * 2 / (1 + alphaA), FMmax = FM * 2 * alphaA / (1 + alphaA);

  // embedding
  const anyPlasticFace = layers.some((l) => l.plastic && !l.sleeved) || threadInPlastic;
  let fZ = j.embedding_um;
  if (fZ == null) {
    fZ = EMBED_UM.thread + EMBED_UM.head + EMBED_UM.inner * (layers.length - 1 + (w === 1 ? 1 : 0));
    if (anyPlasticFace) gaps.push({ what: 'embedding into a plastic face is not covered by the VDI 2230 guide values (metal interfaces); the metal values are used', key: 'embedding', level: 'medium' });
  }
  const FZ = embeddingLoss(fZ, C0);

  // thermal mismatch (m per K)
  const alphaOk = alphaS != null && layers.every((l) => matData(l.m, T0, 'dry').alpha != null);
  const mism = alphaOk ? layers.reduce((a, l) => a + matData(l.m, T0, 'dry').alpha * mm(l.t), 0) - alphaS * mm(lK) : 0;
  const shift = (T) => (alphaOk ? mism * (T - T0) : 0);
  const Cof = (T) => comp(T).total;
  const Dnom = (F) => F * C0 - fZ * 1e-6;
  const force = (F, T, delta = 0) => Math.max(0, (Dnom(F) + shift(T) - delta) / Cof(T)); // a joint cannot pull: below zero it has opened

  // creep sources: the clamped plastic layers (compression) and the anchorage in plastic (shear)
  const noCreep = [];
  const srcMemo = new Map();
  const srcFor = (T) => {
    if (srcMemo.has(T)) return srcMemo.get(T);
    const cc = comp(T), list = [];
    for (const p of cc.perLayer) if (p.l.plastic && !p.l.sleeved) { const cr = creepOf(p.l.m, T); if (!cr) { noCreep.push(p.l.m.name); continue; } list.push({ n: cr.n, c: p.c, beta: cr.beta, q: cr.qualities }); }
    if (threadInPlastic && anchorMat) { const cr = creepOf(anchorMat, T); if (!cr) noCreep.push(anchorMat.name); else list.push({ n: cr.n, c: cc.anc, beta: cr.beta, q: cr.qualities }); }
    srcMemo.set(T, list); return list;
  };
  const plasticPath = srcFor(Tuse).length > 0 || noCreep.length > 0;
  for (const nm of [...new Set(noCreep)]) gaps.push({ what: `${nm} has no creep data (Findley parameters), so the relaxation of the clamped plastic is not computed`, key: `creep:${nm}`, level: 'medium' });
  const creepKnown = noCreep.length === 0;
  const nSrc = srcFor(Tuse).length;
  srcFor(Tuse).forEach((s) => s.q.forEach((q) => qual.add(q)));

  // creep run over the service life, three scatter cases
  const years = duty ? duty.life.years : 0, days = Math.round(years * 365), useH = duty ? clamp(duty.life.hours_per_day, 0, 24) : 0;
  const segments = [{ T: Tuse, hours: useH }, { T: Trest, hours: 24 - useH }].filter((s) => s.hours > 0);
  const lifeH = days * 24;
  const checkpoints = lifeH > 0 ? [1, 10, 100, 1000, 3000, 10000, 30000].filter((x) => x < lifeH).concat([lifeH]) : [];
  const runCase = (F) => {
    if (!duty || !creepKnown || !nSrc || days <= 0) return { delta: [], force: (T) => force(F, T, 0), checkpoints: [], creepN: 0 };
    const sources = srcFor(Tuse).map((s, i) => ({ n: s.n, c: (T) => (srcFor(T)[i] || s).c, beta: (T) => (srcFor(T)[i] || s).beta }));
    const r = creepRun({ X0: Dnom(F), days, segments, sources, Cof, shift, checkpoints, probeT: Trest });
    return { force: (T) => r.force(T), checkpoints: r.checkpoints, creepN: sources.length };
  };
  const cMin = runCase(FMmin), cNom = runCase(FM), cMax = runCase(FMmax);
  const temps = [...new Set([Tmin, Trest, Tuse, Tmax, T0])];
  const minOver = (fn) => temps.reduce((a, T) => Math.min(a, fn(T)), Infinity), maxOver = (fn) => temps.reduce((a, T) => Math.max(a, fn(T)), -Infinity);
  const F_inst_min = minOver((T) => force(FMmin, T, 0));
  const F_eol_min = duty && creepKnown ? minOver((T) => cMin.force(T)) : F_inst_min;
  const F_max_hot = maxOver((T) => force(FMmax, T, 0));

  // loads and the clamp load the joint needs
  const aPeak = duty ? duty.shots.peak_g * G0 : 0, FQ = ctx.massKg * aPeak;
  const nIf = Math.max(1, j.clamps.length - 1), Fnom = FM;
  const FslipReq = FQ / (j.mu_clamp * nIf);
  const FkeepReq = j.min_clamp_N != null ? j.min_clamp_N : MIN_KEEP_FRACTION * Fnom;
  const Freq = Math.max(FslipReq, FkeepReq);

  // bearing: the head (and, for a through bolt, the nut) on a plastic layer
  const bear = [];
  const Abear = (dw) => PI / 4 * (dw * dw - dh * dh) * 1e-6;
  if (layers[0].plastic && !layers[0].sleeved) { const d = matData(layers[0].m, Tuse, hum); bear.push({ at: 'head', l: layers[0], dw: dW, A: Abear(dW), Y: d.Y }); }
  const last = layers[layers.length - 1];
  if (w === 1 && last.plastic && !last.sleeved && layers.length > 1) { const d = matData(last.m, Tuse, hum); bear.push({ at: 'nut', l: last, dw: bk.s, A: Abear(bk.s), Y: d.Y }); }
  const bearing = bear.map((b) => { const p = Math.max(force(FMmax, Tmin, 0), force(FMmax, Tuse, 0), FMmax) / b.A / 1e6; return { ...b, p_MPa: p, allow: b.Y == null ? null : BEARING_FRACTION * b.Y / 1e6 }; });
  const linearLimit = (() => { const l = layers.find((x) => x.plastic && !x.sleeved); const cr = l && creepOf(l.m, Tuse); return cr ? cr.limit : null; })();

  // series: preload at the rest temperature against time, nominal scatter
  return { ok: true, j, bk, g, layers, lK, L, dW, dh, w, tp, T0, Tmin, Tmax, Tuse, Trest, source, c0, C0, FM, FMmin, FMmax, alphaA, fZ, FZ, mism, alphaOk, anchorMat, threadInPlastic,
    F_inst_min, F_eol_min, F_max_hot, FQ, nIf, FslipReq, FkeepReq, Freq, bearing, linearLimit, gaps, qual: [...qual], plasticPath, creepKnown, nSrc, cMin, cNom, cMax, force, comp, creepKnownCount: nSrc,
    stiffness: { deltaS: c0.ds, deltaP: c0.dp, deltaAnchor: c0.anc, phi: c0.ds / C0 }, sleeve, hum, assumptions, sf: duty ? duty.safety_factor : 3, duty };
}

// ───────────────────────── fix proposals (typed ops on the project) ─────────────────────────
const clone = (v) => JSON.parse(JSON.stringify(v));
function proposals(j, kinds, m) {
  const out = [];
  const mk = (id, label, patch) => { const nj = clone(j); patch(nj); out.push({ id, label, ops: [{ op: 'set_joint', joint: nj }] }); };
  const hasPlastic = m.layers.some((l) => l.plastic && !l.sleeved) || m.threadInPlastic;
  if (kinds.includes('sleeve') && j.insert !== 'metal_sleeve' && m.layers.some((l) => l.plastic && !l.sleeved)) mk('sleeve', 'Add a metal compression sleeve through the plastic (the plastic then carries no bolt load)', (x) => { x.insert = 'metal_sleeve'; });
  if (kinds.includes('washer') && !j.washer && hasPlastic) { const od = LARGE_WASHER_OD[sizeKey(j.bolt.size)]; if (od > m.dW) mk('washer', `Add a large washer, ${od} mm outer diameter (bigger bearing area on the plastic)`, (x) => { x.washer = { od_mm: od }; }); }
  if (kinds.includes('grade') && NEXT_GRADE[j.bolt.grade]) mk('grade', `Use grade ${NEXT_GRADE[j.bolt.grade]} (higher proof strength)`, (x) => { x.bolt.grade = NEXT_GRADE[x.bolt.grade]; });
  if (kinds.includes('size')) { const i = SIZES.indexOf(sizeKey(j.bolt.size)); if (i >= 0 && i < SIZES.length - 1) { const nk = SIZES[i + 1], f = (BOLTS[nk].As / m.bk.As) * (BOLTS[nk].d / m.bk.d); mk('size', `Use ${nk.replace('_', '.')} instead of ${j.bolt.size} (more clamp load and bearing area), torque scaled to the same proof-strength utilisation`, (x) => { x.bolt.size = nk.replace('_', '.'); if (x.tightening.mode === 'torque') x.tightening.torque_Nm = +(x.tightening.torque_Nm * f).toFixed(3); else x.tightening.preload_N = +(x.tightening.preload_N * BOLTS[nk].As / m.bk.As).toFixed(0); }); } }
  if (kinds.includes('locker') && !j.thread_locker) mk('locker', 'Add thread locker (lowers the severity one step; the flag stays)', (x) => { x.thread_locker = true; });
  if (kinds.includes('engagement') && m.bk) mk('longer', `Use a longer bolt (${j.bolt.length_mm + 4} mm) for more thread engagement`, (x) => { x.bolt.length_mm = j.bolt.length_mm + 4; });
  return out;
}

// ───────────────────────── the analysis: flags and summary rows ─────────────────────────
const fmtN = (x) => (Math.abs(x) >= 100 ? x.toFixed(0) : x.toPrecision(3));
function evaluate(input) {
  const setup = input.setup, joints = setup.joints || [], conditions = setup.conditions, duty = conditions.duty || null;
  const mats = setup.materialsById || {}, bodyMaterial = {};
  for (const b of setup.bodies) bodyMaterial[b.name] = { ...b.material, assumed: b.assumed };
  const ambientC = conditions.ambient.temperature_K - 273.15;
  const flags = [], rows = [], series = [], warnings = [];
  const validity = 'estimate';
  const w0 = joints.length ? joints[0].where : [0, 0, 0];
  const flag = (f, quals) => flags.push({ ...f, confidence: confidenceOf(validity, quals) });

  if (!joints.length) { warnings.push('The project has no joints. Add one in Analyze > Joints (the template marks its fasteners).');
    flag({ id: 'joints:none', mechanism: 'data_gap', severity: 'medium', body: '', where: w0, metric: 'joints', value: null, limit: null, margin: null,
      why: 'The project has no joints defined, so no preload, creep or loosening was calculated.', fix: { text: 'Add the fasteners in Analyze > Joints (the pcb-enclosure template marks the rear-cap screws).', proposals: [] } }, []); }
  if (!duty) flag({ id: 'duty:missing', mechanism: 'data_gap', severity: 'medium', body: '', where: w0, metric: 'duty_profile', value: null, limit: null, margin: null,
    why: 'No duty profile is set, so shots, service life and temperature extremes are unknown: creep and slip are not computed, only the assembly state.',
    fix: { text: 'Pick a duty preset or enter the real duty in Analyze > Duty profile; the presets are placeholders.', proposals: [] } }, []);
  else if (duty.placeholder) warnings.push('The duty profile is a placeholder preset (invented round numbers). Replace it with measured duty before reading any result as more than a ranking.');
  let totalMass = 0; for (const b of input.bodies) { const bm = bodyMaterial[b.name]; if (bm && bm.props.rho) totalMass += b.volume_mm3 * 1e-9 * bm.props.rho; }

  for (const j of joints) {
    const massKg = j.carried_mass_g != null ? j.carried_mass_g / 1000 : totalMass;
    const upstreamT = (() => { for (const [id, u] of Object.entries(input.upstream || {})) { const p = (u.probes || []).find((q) => q.joint === j.id && /temperature/i.test(q.field) && q.unit === '°C'); if (p) return { T: p.value, src: `the ${id} temperature at the joint (${u.validity})` }; } return null; })();
    const m = jointModel(j, { materials: mats, bodyMaterial, duty, ambientC, massKg, useC: upstreamT ? upstreamT.T : null, useSource: upstreamT ? upstreamT.src : null });
    const body = bodyOfJoint(j), where = j.where, at = (id) => `joint:${j.id}:${id}`.replace(/[^\w.:-]/g, '_').slice(0, 80);
    const fixedBase = (text, kinds, mm_) => ({ text, proposals: mm_ ? proposals(j, kinds, mm_) : [] });
    if (!m.ok) {
      for (const g of m.gaps) flag({ id: at(`gap:${g.key}`), mechanism: 'data_gap', severity: 'medium', body, where, metric: 'material_data', value: null, limit: null, margin: null, why: `Joint ${j.name}: ${g.what}; the joint was not calculated.`, fix: { text: 'Assign a material to the body (Analyze > Materials) or fix the joint, then run again.', proposals: [] } }, []);
      continue;
    }
    warnings.push(`Joint ${j.name}: the use temperature is ${m.Tuse.toFixed(1)} °C, from ${m.source}; the duty range is ${m.Tmin.toFixed(0)} to ${m.Tmax.toFixed(0)} °C.`);
    // data gaps
    const seen = new Set();
    for (const g of m.gaps) { if (seen.has(g.key)) continue; seen.add(g.key); flag({ id: at(`gap:${g.key}`), mechanism: 'data_gap', severity: g.level || 'medium', body, where, metric: 'material_data', value: null, limit: null, margin: null, why: `Joint ${j.name}: ${g.what}.`, fix: { text: 'Enter the missing value in a project material (Analyze > Materials, or the set_material op with a durability block) or run a test; until then this part of the result is missing or approximate.', proposals: [] } }, ['typical']); }
    for (const l of m.layers) { const bm = l.c.body != null ? bodyMaterial[l.c.body] : null; if (bm && bm.assumed) flag({ id: at(`gap:assumed:${l.c.body}`), mechanism: 'data_gap', severity: 'low', body, where, metric: 'material_assumed', value: null, limit: null, margin: null, why: `Body ${l.c.body} has no material assigned; PLA data was used for joint ${j.name}.`, fix: { text: 'Assign the real material to the body in Analyze > Materials.', proposals: [] } }, ['estimate']); }
    if (j.carried_mass_g == null && duty && duty.shots.peak_g > 0) flag({ id: at('gap:mass'), mechanism: 'data_gap', severity: 'low', body, where, metric: 'carried_mass_g', value: null, limit: null, margin: null, why: `Joint ${j.name}: the mass it carries is taken as the bodies' own mass (${(massKg * 1000).toFixed(1)} g); the electronics and battery are not counted.`, fix: { text: 'Enter the total carried mass of the joint (carried_mass_g).', proposals: [] } }, ['estimate']);

    const q = m.qual, sf = m.sf, id = j.id, name = j.name;
    const R = (metric, value, unit) => rows.push({ metric: `${id}.${metric}`, value: +value.toPrecision(6), unit, where });
    R('preload_nominal', m.FM, 'N'); R('preload_min', m.FMmin, 'N'); R('preload_max', m.FMmax, 'N'); R('embedding_loss', m.FZ, 'N');
    R('load_factor_phi', m.stiffness.phi, ''); R('bolt_compliance', m.stiffness.deltaS, 'm/N'); R('clamp_compliance', m.stiffness.deltaP, 'm/N');
    if (m.threadInPlastic) R('anchor_compliance', m.stiffness.deltaAnchor, 'm/N');
    R('thermal_change_cold', m.force(m.FM, m.Tmin) - m.force(m.FM, m.T0), 'N'); R('thermal_change_hot', m.force(m.FM, m.Tmax) - m.force(m.FM, m.T0), 'N');
    R('preload_installed_min', m.F_inst_min, 'N'); R('preload_end_of_life_min', m.F_eol_min, 'N'); R('preload_hot_max', m.F_max_hot, 'N');
    R('required_clamp', m.Freq, 'N'); R('transverse_load', m.FQ, 'N'); R('use_temperature', m.Tuse, '°C');
    if (duty && m.creepKnown && m.nSrc) R('creep_loss_fraction', 1 - m.cMin.force(m.Trest) / Math.max(1e-9, m.force(m.FMmin, m.Trest)), '');
    if (m.cNom.checkpoints && m.cNom.checkpoints.length) series.push({ name: `${id} preload`, x: 'time (h)', y: 'N at ' + m.Trest.toFixed(0) + ' °C', xs: [0, ...m.cNom.checkpoints.map((c) => c.t)], ys: [m.force(m.FM, m.Trest, 0), ...m.cNom.checkpoints.map((c) => c.Frest)] });

    // A. installed preload after embedding over the temperature range
    {
      const margin = capMargin(m.F_inst_min / m.Freq), sev = severityFromMargin(margin, sf);
      const why = `After tightening, embedding and the hot-cold swing the joint keeps at least ${fmtN(m.F_inst_min)} N of the ${fmtN(m.Freq)} N it needs (${m.FslipReq >= m.FkeepReq ? 'to resist the recoil without slipping' : 'a quarter of the nominal preload, a placeholder floor'}).`;
      flag({ id: at('preload'), mechanism: 'preload_loss', severity: sev, body, where, metric: 'installed_preload_min_N', value: m.F_inst_min, limit: m.Freq, margin, why,
        fix: fixedBase('Tighten to a higher preload, use a stiffer clamp (sleeve, steel parts) or a bigger bolt.', ['sleeve', 'size', 'grade'], m) }, q);
    }
    // B. preload at the end of life after creep
    if (duty) {
      if (m.sleeve && m.layers.some((l) => l.sleeved)) flag({ id: at('creep'), mechanism: 'creep', severity: 'info', body, where, metric: 'preload_end_of_life_min_N', value: m.F_eol_min, limit: m.Freq, margin: capMargin(m.F_eol_min / m.Freq),
        why: `A metal sleeve carries the bolt load, so the plastic does not creep under it; ${fmtN(m.F_eol_min)} N remains at the end of life.`, fix: { text: 'No action: the creep path is removed by the sleeve.', proposals: [] } }, q);
      else if (m.creepKnown && m.nSrc) {
        const margin = capMargin(m.F_eol_min / m.Freq), sev = severityFromMargin(margin, sf), lost = 1 - m.cMin.force(m.Trest) / Math.max(1e-9, m.force(m.FMmin, m.Trest));
        flag({ id: at('creep'), mechanism: 'creep', severity: sev, body, where, metric: 'preload_end_of_life_min_N', value: m.F_eol_min, limit: m.Freq, margin,
          why: `The clamped plastic creeps over ${duty.life.years} years (${duty.life.hours_per_day} h a day at ${m.Tuse.toFixed(0)} °C, the rest at ${m.Trest.toFixed(0)} °C) and the preload falls ${(lost * 100).toFixed(0)} % to ${m.F_eol_min > 0 ? fmtN(m.F_eol_min) + ' N' : 'nothing (the joint opens when cold)'} against ${fmtN(m.Freq)} N needed.`,
          fix: fixedBase('Take the plastic out of the bolt load path with a metal compression sleeve, or spread the load over more plastic.', ['sleeve', 'washer', 'size'], m) }, q);
      }
    }
    // C. bearing pressure on the plastic
    for (const b of m.bearing) if (b.allow != null) {
      const margin = capMargin(b.allow / b.p_MPa), sev = severityFromMargin(margin, sf);
      flag({ id: at(`bearing:${b.at}`), mechanism: 'creep', severity: sev, body: b.l.label, where, metric: `bearing_pressure_${b.at}_MPa`, value: b.p_MPa, limit: b.allow, margin,
        why: `The ${b.at} bears on ${b.l.m.name} at ${b.p_MPa.toFixed(0)} MPa; ${b.allow.toFixed(0)} MPa (half the yield at ${m.Tuse.toFixed(0)} °C, a rule of thumb) is the most that keeps creep and crushing in check.`,
        fix: fixedBase('Spread the load: a large washer, a metal sleeve, a bigger bolt or a lower preload.', ['sleeve', 'washer', 'size'], m) }, q);
      if (m.linearLimit != null && b.Y != null && b.p_MPa * 1e6 > m.linearLimit * b.Y) flag({ id: at(`linear:${b.at}`), mechanism: 'creep', severity: 'medium', body: b.l.label, where, metric: 'stress_over_linear_limit', value: b.p_MPa, limit: m.linearLimit * b.Y / 1e6, margin: capMargin(m.linearLimit * b.Y / 1e6 / b.p_MPa),
        why: `The bearing stress is above ${(m.linearLimit * 100).toFixed(0)} % of yield, where the linear creep model underestimates the relaxation: read the preload loss as optimistic.`, fix: fixedBase('Lower the bearing stress (washer, sleeve) so the linear creep data applies.', ['sleeve', 'washer'], m) }, q);
    }
    // D. slip and self-loosening
    if (duty) {
      const Fres = m.F_eol_min, grip = j.mu_clamp * m.nIf * Fres, margin = m.FQ > 0 ? capMargin(grip / m.FQ) : CAP;
      let sev = severityFromMargin(margin, sf); const raw = sev; if (j.thread_locker) sev = stepDown(sev);
      flag({ id: at('slip'), mechanism: 'self_loosening', severity: sev, body, where, metric: 'friction_grip_over_transverse_N', value: grip, limit: m.FQ, margin,
        why: `Each shot pushes ${fmtN(m.FQ)} N across the joint (${(massKg * 1000).toFixed(0)} g at ${duty.shots.peak_g} g); the clamp can hold ${fmtN(grip)} N at the end of life${margin < 1 ? ', so it can slip and the bolt can work loose (Junker)' : ''}${j.thread_locker && raw !== sev ? '; the thread locker lowers the severity, the flag stays' : ''}.`,
        fix: fixedBase(j.thread_locker ? 'Raise the clamp load or take the plastic out of the load path; a locker only slows the loosening.' : 'Add thread locker, raise the preload (higher grade or bigger bolt) or take the plastic out of the load path.', ['locker', 'grade', 'size', 'sleeve'], m) }, q);
    }
    // E. bolt load at the hot or cold extreme (thermal cycling)
    {
      const Fy = m.g.Rp * m.bk.As, margin = capMargin(Fy / m.F_max_hot), sev = severityFromMargin(margin, sf);
      flag({ id: at('bolt-hot'), mechanism: 'thermal_cycling', severity: sev, body, where, metric: 'bolt_load_max_N', value: m.F_max_hot, limit: Fy, margin,
        why: `At the extreme temperatures and the highest tightening scatter the bolt carries up to ${fmtN(m.F_max_hot)} N against ${fmtN(Fy)} N at its proof strength.`,
        fix: fixedBase('Use a higher grade or a larger bolt, or lower the tightening torque.', ['grade', 'size'], m) }, q);
    }
    // F. assembly utilisation
    {
      const muG = j.tightening.method === 'friction' ? j.tightening.mu_thread : MU_G_TORSION, Fa = allowedAssemblyPreload({ Rp: m.g.Rp, As: m.bk.As, d2: m.bk.d2, d3: m.bk.d3, P: m.bk.P, muG });
      const margin = capMargin(Fa / m.FMmax), sev = severityFromMargin(margin, sf);
      flag({ id: at('assembly'), mechanism: 'preload_loss', severity: sev, body, where, metric: 'assembly_preload_max_N', value: m.FMmax, limit: Fa, margin,
        why: `Tightening to ${fmtN(m.FM)} N with a scatter of ${m.alphaA} puts up to ${fmtN(m.FMmax)} N in the bolt; ${fmtN(Fa)} N is 90 % of its proof strength with the torsion of tightening${j.tightening.method === 'friction' ? '' : ` (thread friction taken as ${MU_G_TORSION}, the nut-factor method gives none)`}.`,
        fix: fixedBase('Lower the torque or use a higher grade or a larger bolt.', ['grade', 'size'], m) }, ['handbook']);
    }
    // G. thread engagement
    {
      const le = m.threadInPlastic ? Math.min(j.anchor.length_mm, m.L - m.lK) : m.L - m.lK, need = (m.threadInPlastic ? ENGAGE_PLASTIC : ENGAGE_METAL) * m.bk.d, margin = capMargin(le / need), sev = severityFromMargin(margin, sf);
      if (margin < 10) flag({ id: at('engage'), mechanism: 'preload_loss', severity: sev, body, where, metric: 'thread_engagement_mm', value: le, limit: need, margin,
        why: `${le.toFixed(1)} mm of thread is engaged against ${need.toFixed(1)} mm (${m.threadInPlastic ? ENGAGE_PLASTIC : ENGAGE_METAL} d, a rule of thumb for ${m.threadInPlastic ? 'plastic' : 'a steel nut or tapped steel'}).`,
        fix: fixedBase('Use a longer bolt or a deeper boss.', ['engagement'], m) }, ['typical']);
    }
    // H. pull-out of an insert or a thread in plastic
    if (m.threadInPlastic && m.anchorMat) {
      const d = matData(m.anchorMat, m.Tmax, m.hum), Da = j.anchor.d_mm != null ? j.anchor.d_mm : (j.insert === 'heat_set_brass' ? 1.5 * m.bk.d + 0.1 : m.bk.d), il = m.anchorMat.props.interlayer_factor;
      if (d.Y != null && il != null) {
        const tau = m.F_max_hot / (PI * Da * j.anchor.length_mm * 1e-6) / 1e6, allow = SHEAR_YIELD * d.Y * il / 1e6, margin = capMargin(allow / tau), sev = severityFromMargin(margin, sf);
        flag({ id: at('pullout'), mechanism: 'preload_loss', severity: sev, body: j.anchor.body, where, metric: 'anchor_shear_stress_MPa', value: tau, limit: allow, margin,
          why: `The ${j.insert === 'heat_set_brass' ? 'insert' : 'thread'} in ${m.anchorMat.name} sees ${tau.toFixed(1)} MPa of shear against ${allow.toFixed(1)} MPa allowed (0.577 of the yield at ${m.Tmax.toFixed(0)} °C, with the inter-layer factor).`,
          fix: fixedBase('Use a longer insert or boss, a metal sleeve, or a lower preload.', ['sleeve', 'size'], m) }, [...q, m.anchorMat.quality.yield_strength, m.anchorMat.quality.interlayer_factor]);
      }
    }
  }

  if (!duty) warnings.push('No duty profile: creep, slip and the temperature swing are not evaluated.');
  warnings.push(...ASSUMPTIONS.map((a) => 'Assumption: ' + a));
  if (rows.length > 190) { warnings.push(`Only the first 190 of ${rows.length} summary rows are returned; the flags are complete.`); rows.length = 190; }
  return { summary: rows, flags, series, warnings, validity };
}

const ASSUMPTIONS = [
  'Simplified VDI 2230: one axially loaded bolt, pressure-cone stiffness of the clamped parts (cut off at an outer diameter if given), preload scatter from the tightening factor, no bending of the joint and no external axial load.',
  'Preload scatter: the torque gives the nominal preload; the minimum and maximum are F x 2/(1+a) and F x 2a/(1+a) with a the tightening factor (1.6 for a torque wrench unless you set it), so that max/min = a.',
  'Embedding: VDI 2230 guide values for metal interfaces (thread 3 um, head 2.5 um, 1.5 um per inner interface); an interface with plastic is not in the table and is reported as a data gap.',
  'Creep: Findley power law, linear in stress, linear viscoelastic superposition (Boltzmann) with a reduced time for temperature (the creep ageing of a hot hour is not undone by cooling); moisture changes E and strength only (not the creep rate); the creep data are typical values for the material class, not measurements.',
  'The duty temperature profile: the use hours each day at the use temperature (the Thermal 3D result at the joint when it has been run on this design, otherwise the duty maximum), the rest of the day at ambient clipped into the duty range; the minimum and maximum duty temperatures are the extremes checked for the bolt and slip.',
  'Slip: the transverse load per shot is the carried mass times the peak recoil acceleration (quasi-static, no dynamic amplification); the grip is the clamp friction times the residual preload times the number of interfaces. Fretting, a changing coefficient of friction and shock above the stated peak are not modelled.',
  'Not modelled: fatigue of the bolt, vibration, long-term moisture uptake, orthotropy of printed parts (the isotropic E is used), and the plastic loaded beyond its linear range (flagged).',
];

if (typeof forge !== 'undefined') {
  forge.analysis.register('joint-creep', {
    async run(input, ctx) {
      ctx.progress(0.1);
      const r = evaluate(input);
      if (ctx.signal.aborted) throw new Error('cancelled');
      ctx.progress(1);
      return r;
    },
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { BOLTS, GRADES, NEXT_GRADE, LARGE_WASHER_OD, EMBED_UM, interp, severityFromMargin, stepDown, confidenceOf, tanPhi, layerCompliance, cylinderCompliance, boltCompliance, preloadFromTorque, thermalPreloadChange, embeddingLoss, allowedAssemblyPreload, creepRun, creepRelaxNumbers, matData, creepOf, jointModel, evaluate, proposals });
