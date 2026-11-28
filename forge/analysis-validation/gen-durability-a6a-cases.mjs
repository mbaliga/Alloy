// Writes cases/durability-a6a.json (A6a: joint, preload, creep). The reference numbers are computed HERE, from the closed-form equations
// stated in each case, by code that does not import the package (examples/ext/joint-creep/main.js). They are self-consistency
// references: the equations of VDI 2230 and of the Findley power law written out a second time and evaluated by hand-style
// arithmetic. They are NOT published figures. No published worked example was recalled reliably enough to quote its numbers.
//
// The tolerances are fixed below, before the package is ever run against the file, and the harness never changes them:
//   2 % on every number (the spec's figure for the preload and the thermal change, the Findley case and the sleeve case).
//   node analysis-validation/gen-durability-a6a-cases.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TOL = { rel: 0.02 };
const PI = Math.PI;
const cases = [];

// Independent reference for the creep: the Volterra integral equation of linear viscoelastic superposition (Boltzmann), solved here by backward Euler on a
// fine grid (steps of at most 0.5 % of the elapsed time, and at most 1 h), with a reduced-time clock for temperature. Not the package's scheme.
//   dS F(t) + dP [F(t) + beta_ref integral (xi(t) - xi(tau))^n dF(tau)] = (dS + dP) F0,   xi = integral (beta(T)/beta_ref)^(1/n) dt
function volterra({ F0, dS, dP, n, phases, days }) {
  const bref = phases[0].beta, ts = [0], xi = [0], seg = [];
  let t = 0, x = 0;
  for (let d = 0; d < days; d++) for (const p of phases) { let left = p.hours; while (left > 1e-9) { const dt = Math.min(left, Math.max(0.005 * t, 0.005), 1e3); t += dt; x += dt * Math.pow(p.beta / bref, 1 / n); ts.push(t); xi.push(x); left -= dt; } }
  const F = [0], dF = [0];
  for (let k = 1; k < ts.length; k++) {
    let h = 0; for (let j = 1; j < k; j++) h += Math.pow(xi[k] - xi[j - 1], n) * dF[j];
    const w = Math.pow(xi[k] - xi[k - 1], n); // the increment at the start of step k, F_k - F_{k-1}, has lag xi_k - xi_{k-1}; for k = 1 it is the whole load at t = 0
    const Fk = ((dS + dP) * F0 - dP * bref * h + dP * bref * w * F[k - 1]) / (dS + dP + dP * bref * w);
    F.push(Fk); dF.push(Fk - F[k - 1]);
  }
  return F[F.length - 1];
}

// ───────── data shared by the cases (a flat, invented set of material numbers so that every closed form is exact) ─────────
const q = { E: 'handbook', nu: 'handbook', yield_strength: 'handbook', interlayer_factor: 'handbook' };
const steel = { id: 'steel-carbon', name: 'Steel (case)', cls: 'metal', props: { E: 205e9, nu: 0.29, yield_strength: 350e6, rho: 7850, interlayer_factor: 1 }, quality: q, durability: { cte: { value: 11.7e-6, quality: 'handbook', sources: ['case'] } } };
const alu = { id: 'al6061', name: 'Aluminium (case)', cls: 'metal', props: { E: 68.9e9, nu: 0.33, yield_strength: 276e6, rho: 2700, interlayer_factor: 1 }, quality: q, durability: { cte: { value: 23.6e-6, quality: 'handbook', sources: ['case'] } } };
const BETA = 0.15, N = 0.2;
const petg = { id: 'petg', name: 'PETG (case, flat)', cls: 'fdm-polymer', props: { E: 2.1e9, nu: 0.4, yield_strength: 45e6, rho: 1250, interlayer_factor: 0.6 }, quality: q,
  durability: { cte: { value: 60e-6, quality: 'typical', sources: ['case'] }, E_T: { kind: 'factor', points: [[-20, 1], [100, 1]], quality: 'typical', sources: ['case'] }, yield_T: { kind: 'factor', points: [[-20, 1], [100, 1]], quality: 'typical', sources: ['case'] },
    creep: { model: 'findley', time_unit: 'h', n: { value: N, quality: 'typical', sources: ['case'] }, beta_T: { points: [[-20, BETA], [100, BETA]], quality: 'typical', sources: ['case'] }, linear_limit: { value: 0.25, quality: 'typical', sources: ['case'] } } } };

// ───────── independent closed forms ─────────
const cyl = (l_mm, DA, dh, E) => 4 * (l_mm * 1e-3) / (PI * E * (DA * DA - dh * dh) * 1e-6);
const coneVDIm = (lK, dW, dh, E, tp, w) => 2 * Math.log(((dW + dh) * (dW + w * lK * tp - dh)) / ((dW - dh) * (dW + w * lK * tp + dh))) / (w * PI * E * (dh * 1e-3) * tp);
const bolt = (b, L, lK, ES, EM) => { // VDI 2230 simplified bolt compliance, m/N
  const AN = PI * b.d * b.d / 4, A3 = PI * b.d3 * b.d3 / 4, thr = 2 * b.d + 6, shank = Math.max(0, L - thr), lSh = Math.min(lK, shank), lTh = lK - lSh, m = 1e-3, a = 1e-6;
  return (0.4 * b.d + lSh) * m / (ES * AN * a) + lTh * m / (ES * A3 * a) + 0.5 * b.d * m / (ES * A3 * a) + 0.4 * b.d * m / (EM * AN * a);
};
const M8 = { d: 8, P: 1.25, d2: 7.188, d3: 6.466 }, M3 = { d: 3, P: 0.5, d2: 2.675, d3: 2.387 };

// ───────── 1. preload from torque (VDI 2230: M_A = F_M [0.16 P + 0.58 d2 mu_G + (D_Km / 2) mu_K]) ─────────
{
  const dW = 13, dh = 9, muG = 0.14, muK = 0.14, F = 16000, Dkm = (dW + dh) / 2;
  const M = F * (0.16 * M8.P + 0.58 * M8.d2 * muG + (Dkm / 2) * muK) * 1e-3;
  const K = 0.2, d = 8, M2 = F * K * d * 1e-3;
  cases.push({ id: 'vdi-preload-from-torque', kind: 'function', title: 'Preload from tightening torque, thread and under-head friction, and by the nut factor',
    reference: `Closed-form self-consistency reference (VDI 2230 equations, written out independently; not a published figure). M8 coarse (P 1.25, d2 7.188), socket head dk 13, clearance hole 9, D_Km = 11 mm, mu_G = mu_K = 0.14: F_M = M_A / [0.16 P + 0.58 d2 mu_G + (D_Km/2) mu_K] with M_A = ${M.toFixed(4)} N m gives ${F} N (the torque is the one that gives exactly that preload). Nut factor: F = T / (K d), K 0.2, T = ${M2.toFixed(3)} N m gives ${F} N.`,
    calls: [
      { fn: 'preloadFromTorque', metric: 'F_M friction method', unit: 'N', args: [M, { method: 'friction', d: 8, P: 1.25, d2: 7.188, dW, dh, muG, muK }], value: F, tol: TOL },
      { fn: 'preloadFromTorque', metric: 'F_M nut factor', unit: 'N', args: [M2, { method: 'nut_factor', K, d: 8 }], value: F, tol: TOL },
    ] });
}
// ───────── 2. stiffness: cylinder, pressure cone (VDI closed form), bolt, load factor ─────────
{
  const E = 205e9, dW = 13, dh = 9, lK = 20, DA = 13, ES = 205e9;
  const dP = cyl(lK, DA, dh, E), dS = bolt(M8, 40, lK, ES, ES), tp = 0.5;
  const c1 = coneVDIm(lK, dW, dh, E, tp, 1), c2 = coneVDIm(lK, dW, dh, E, tp, 2);
  cases.push({ id: 'vdi-stiffness', kind: 'function', title: 'Clamped-part and bolt compliance: cylinder, pressure cone (w = 1 and w = 2), simplified VDI bolt',
    reference: `Closed-form self-consistency reference. Cylinder (outer diameter D_A = ${DA} mm = head diameter, so no cone): delta_P = 4 l_K / (pi E (D_A^2 - d_h^2)) = ${dP.toExponential(5)} m/N for l_K 20 mm, d_h 9 mm, E 205 GPa. Pressure cone, VDI 2230 closed form delta_P = 2 ln[(d_W + d_h)(d_W + w l_K tan(phi) - d_h) / ((d_W - d_h)(d_W + w l_K tan(phi) + d_h))] / (w pi E d_h tan(phi)) with tan(phi) 0.5: w = 1 ${c1.toExponential(5)}, w = 2 ${c2.toExponential(5)} m/N (the package integrates the cone slice by slice, a different route to the same quantity). Bolt M8 x 40, l_K 20 (shank 18, free thread 2): delta_S = (0.4 d + l_shank)/(E A_N) + l_thread/(E A_3) + 0.5 d/(E A_3) + 0.4 d/(E A_N) = ${dS.toExponential(5)} m/N. tan(phi) from the VDI fit at beta_L = 1, y = 2: 0.362 + 0.032 ln(0.5) + 0.153 ln(2) = ${(0.362 + 0.032 * Math.log(0.5) + 0.153 * Math.log(2)).toFixed(5)}.`,
    calls: [
      { fn: 'layerCompliance', metric: 'delta_P cylinder', unit: 'm/N', args: [{ x0: 0, x1: lK, lK, dW, dh, DA, E, w: 1, tp: 0.5 }], value: dP, tol: TOL },
      { fn: 'layerCompliance', metric: 'delta_P cone w=1', unit: 'm/N', args: [{ x0: 0, x1: lK, lK, dW, dh, DA: null, E, w: 1, tp }], value: c1, tol: TOL },
      { fn: 'layerCompliance', metric: 'delta_P cone w=2', unit: 'm/N', args: [{ x0: 0, x1: lK, lK, dW, dh, DA: null, E, w: 2, tp }], value: c2, tol: TOL },
      { fn: 'boltCompliance.total', metric: 'delta_S', unit: 'm/N', args: [{ d: 8, d3: 6.466, L: 40, lK, ES, EM: ES }], value: dS, tol: TOL },
      { fn: 'tanPhi', metric: 'tan(phi)', unit: '', args: [13, 13, 26, 1], value: 0.362 + 0.032 * Math.log(0.5) + 0.153 * Math.log(2), tol: TOL },
    ] });
  // ───────── 3. embedding and thermal change on that joint ─────────
  const C = dS + dP, FM = 16000, fZ = 3 + 2.5 + 1.5 * 2, FZ = fZ * 1e-6 / C;
  const aluE = 68.9e9, dPal = cyl(lK, DA, dh, aluE), Cal = dS + dPal, dT = 60, aS = 11.7e-6, aP = 23.6e-6;
  const dFth = lK * 1e-3 * (aP - aS) * dT / Cal;
  cases.push({ id: 'vdi-embedding-thermal', kind: 'function', title: 'Embedding loss, and the preload change with temperature from the CTE difference (aluminium clamped by a steel bolt)',
    reference: `Closed-form self-consistency reference. Embedding F_Z = f_Z / (delta_S + delta_P) with the VDI guide values thread 3 um + head 2.5 um + 2 inner interfaces x 1.5 um = ${fZ} um on the steel case above: ${FZ.toFixed(2)} N. Thermal change (VDI 2230): dF = l_K (alpha_P - alpha_S) dT / (delta_S + delta_P); aluminium 6061 clamped (alpha 23.6e-6, E 68.9 GPa, delta_P = ${dPal.toExponential(4)}) by a steel bolt (alpha 11.7e-6) over dT = +${dT} K, l_K 20 mm: ${dFth.toFixed(2)} N (an increase, the clamped aluminium grows more than the bolt).`,
    calls: [
      { fn: 'embeddingLoss', metric: 'F_Z', unit: 'N', args: [fZ, C], value: FZ, tol: TOL },
      { fn: 'thermalPreloadChange', metric: 'dF thermal', unit: 'N', args: [{ layers: [{ alpha: aP, l: lK * 1e-3 }], alphaS: aS, lK: lK * 1e-3, dT, C: Cal }], value: dFth, tol: TOL },
    ] });
  void FM;
}
// ───────── 4. Findley relaxation (closed form) ─────────
{
  const dS = 1.1e-8, dP = 8.4e-8, F0 = 1500, beta = 0.15, n = 0.2;
  const t1 = 8760, F1 = volterra({ F0, dS, dP, n, phases: [{ hours: t1, beta }], days: 1 });
  // two temperature phases: beta1 = 0.15 for the first 1000 h, beta2 = 0.6 for the next 7760 h
  const b1 = 0.15, b2 = 0.6, ta = 1000, tb = 8760, F2 = volterra({ F0, dS, dP, n, phases: [{ hours: ta, beta: b1 }, { hours: tb - ta, beta: b2 }], days: 1 });
  // a daily cycle for one year: 1 h at beta 0.6 then 23 h at beta 0.15
  const F3 = volterra({ F0, dS, dP, n, phases: [{ hours: 1, beta: b2 }, { hours: 23, beta: b1 }], days: 365 });
  cases.push({ id: 'findley-relaxation-closed-form', kind: 'function', title: 'Preload against time by Findley creep of the clamped plastic (linear viscoelastic superposition): isothermal, two temperature phases, a daily cycle',
    reference: `Self-consistency reference from an independent numerical solution, not a published figure and not a measurement. Findley power law, linear in stress: creep compliance J(t) = 1 + beta t^n. The preload relaxes by Boltzmann superposition on a joint of bolt compliance delta_S ${dS} and plastic compliance delta_P ${dP} m/N at fixed total length: delta_S F + delta_P [F + beta_ref integral (xi(t) - xi(tau))^n dF(tau)] = (delta_S + delta_P) F0, with the reduced time xi = integral (beta(T)/beta_ref)^(1/n) dt for temperature. The reference solves this Volterra equation here by backward Euler on a fine grid (steps of at most 0.5 % of the elapsed time and 1 h); the package uses an implicit midpoint stepping with far fewer steps, so the two schemes are independent. F0 1500 N, n 0.2. (a) beta 0.15 for 8760 h: ${F1.toFixed(2)} N. (b) beta 0.15 for 1000 h then 0.6 to 8760 h: ${F2.toFixed(2)} N. (c) 365 daily cycles of 1 h at beta 0.6 then 23 h at beta 0.15: ${F3.toFixed(2)} N. NOTE: an earlier version of this package used time hardening (dF/F = -kappa d(beta t^n)); that over-predicts relaxation by about 19 % for (a) (664 N against ${F1.toFixed(0)} N) and far more for hot, long holds, which is why it was replaced.`,
    calls: [
      { fn: 'creepRelaxNumbers', metric: 'F after 8760 h isothermal', unit: 'N', args: [{ F0, dS, dP, n, phases: [{ hours: t1, beta }], days: 1 }], value: F1, tol: TOL },
      { fn: 'creepRelaxNumbers', metric: 'F after two temperature phases', unit: 'N', args: [{ F0, dS, dP, n, phases: [{ hours: ta, beta: b1 }, { hours: tb - ta, beta: b2 }], days: 1 }], value: F2, tol: TOL },
      { fn: 'creepRelaxNumbers', metric: 'F after 365 daily cycles', unit: 'N', args: [{ F0, dS, dP, n, phases: [{ hours: 1, beta: b2 }, { hours: 23, beta: b1 }], days: 365 }], value: F3, tol: TOL },
    ] });
}
// ───────── 5. sleeve versus no sleeve ─────────
{
  const bk = { M3, dk: 5.5, dh: 3.4 }, Fm = 1500, L = 16, lK = 9, fZ = 3 + 2.5 + 1.5 * 2, DA = 5.5, dh = 3.4;
  const dS = bolt(M3, L, lK, 205e9, 205e9);
  const cSteel3 = cyl(3, DA, dh, 205e9), cPet6 = cyl(6, DA, dh, 2.1e9), cSteel6 = cyl(6, DA, dh, 205e9);
  const Cn = dS + cPet6 + cSteel3, Cs = dS + cSteel6 + cSteel3;
  const Fin = Fm - fZ * 1e-6 / Cn, Fis = Fm - fZ * 1e-6 / Cs;
  const tEnd = Math.round(5 * 365) * 24, Fen = volterra({ F0: Fin, dS: Cn - cPet6, dP: cPet6, n: N, phases: [{ hours: tEnd, beta: BETA }], days: 1 });
  void bk;
  const joint = (insert) => ({ id: 'J1', name: 'case joint', where: [0, 0, 0], axis: '+z', bolt: { size: 'M3', grade: '8.8', length_mm: L }, tightening: { mode: 'preload', preload_N: Fm, assembly_C: 23 }, mu_clamp: 0.2, thread_locker: false,
    clamps: [{ body: 'Shell', thickness_mm: 6, outer_d_mm: DA }, { external: 'rail', material: 'steel-carbon', thickness_mm: 3, outer_d_mm: DA }], insert, thread_in: 'metal' });
  const duty = { preset: 'custom', placeholder: false, life: { years: 5, hours_per_day: 24, target_years: 5 }, shots: { per_day: 100, total: 182500, peak_g: 500, pulse_ms: 2 }, vibration: { preset: 'custom', psd: [[10, 0.01], [100, 0.01]], hours: 0 }, temperature: { min_C: 23, max_C: 23, cycles_per_day: 0 }, humidity: 'dry', safety_factor: 3 };
  const ctx = { materials: { 'steel-carbon': steel }, bodyMaterial: { Shell: petg }, duty, ambientC: 23, massKg: 0.075, useC: null, useSource: null };
  cases.push({ id: 'sleeve-vs-no-sleeve', kind: 'function', title: 'A metal compression sleeve takes the plastic out of the bolt load: the creep path is removed',
    reference: `Closed-form self-consistency reference, not a published figure. M3 grade 8.8 x 16, a 6 mm plastic layer (E 2.1 GPa, Findley beta ${BETA}, n ${N}, flat in temperature, dry) over a 3 mm steel rail layer, both cylinders of outer diameter 5.5 mm, hole 3.4 mm, preload 1500 N, embedding 8.5 um, held at 23 C for 5 years (${tEnd} h). Compliances: delta_S ${dS.toExponential(4)}, plastic ${cPet6.toExponential(4)}, steel rail ${cSteel3.toExponential(4)}, steel sleeve of the same 6 mm ${cSteel6.toExponential(4)} m/N. Installed preload F - f_Z/C: no sleeve ${Fin.toFixed(1)} N, sleeve ${Fis.toFixed(1)} N. End of life, no sleeve: independent Volterra (Boltzmann superposition) integration of the Findley creep, as in case findley-relaxation-closed-form: ${Fen.toFixed(1)} N, a loss of ${((1 - Fen / Fin) * 100).toFixed(1)} %. With the sleeve the plastic carries no bolt load, so the end-of-life preload equals the installed preload (${Fis.toFixed(1)} N, no creep sources).`,
    calls: [
      { fn: 'jointModel.F_inst_min', metric: 'installed preload, no sleeve', unit: 'N', args: [joint('none'), ctx], value: Fin, tol: TOL },
      { fn: 'jointModel.F_eol_min', metric: 'end-of-life preload, no sleeve', unit: 'N', args: [joint('none'), ctx], value: Fen, tol: TOL },
      { fn: 'jointModel.nSrc', metric: 'creep sources, no sleeve', unit: '', args: [joint('none'), ctx], value: 1, tol: { abs: 0 } },
      { fn: 'jointModel.F_inst_min', metric: 'installed preload, sleeve', unit: 'N', args: [joint('metal_sleeve'), ctx], value: Fis, tol: TOL },
      { fn: 'jointModel.F_eol_min', metric: 'end-of-life preload, sleeve', unit: 'N', args: [joint('metal_sleeve'), ctx], value: Fis, tol: TOL },
      { fn: 'jointModel.nSrc', metric: 'creep sources, sleeve', unit: '', args: [joint('metal_sleeve'), ctx], value: 0, tol: { abs: 0 } },
    ] });
}
// ───────── 6. the same two joints through the real runner and a package Worker ─────────
{
  const bk = { M3 }; void bk;
  const dS = bolt(M3, 16, 9, 205e9, 205e9), cSteel3 = cyl(3, 5.5, 3.4, 205e9), cPet6 = cyl(6, 5.5, 3.4, 2.1e9), cSteel6 = cyl(6, 5.5, 3.4, 205e9);
  const Cn = dS + cPet6 + cSteel3, Cs = dS + cSteel6 + cSteel3, fZ = 8.5, Fin = 1500 - fZ * 1e-6 / Cn, Fis = 1500 - fZ * 1e-6 / Cs;
  const tEnd = 1825 * 24, Fen = volterra({ F0: Fin, dS: Cn - cPet6, dP: cPet6, n: N, phases: [{ hours: tEnd, beta: BETA }], days: 1 });
  const props = (o) => Object.fromEntries(Object.entries({ k: 0.2, rho: 1250, cp: 1200, emissivity: 0.9, E: 2.1e9, nu: 0.4, yield_strength: 45e6, tensile_strength: 50e6, interlayer_factor: 0.6, ...o }).map(([k, v]) => [k, { value: v, quality: 'datasheet', sources: ['user'] }]));
  const mat = { id: 'petg-flat', name: 'PETG flat (case)', class: 'custom', notes: 'Validation case material: creep flat in temperature.', properties: props({}), durability: petg.durability };
  const joint = (insert) => ({ id: 'J1', name: 'case joint', where: [0, 0, 5], axis: '+z', bolt: { size: 'M3', grade: '8.8', length_mm: 16 }, tightening: { mode: 'preload', preload_N: 1500, assembly_C: 23 }, mu_clamp: 0.2, thread_locker: false,
    clamps: [{ body: 'Shell', thickness_mm: 6, outer_d_mm: 5.5 }, { external: 'rail', material: 'steel-carbon', thickness_mm: 3, outer_d_mm: 5.5 }], insert, thread_in: 'metal' });
  const duty = { preset: 'custom', placeholder: false, life: { years: 5, hours_per_day: 24, target_years: 5 }, shots: { per_day: 100, total: 182500, peak_g: 500, pulse_ms: 2 }, vibration: { preset: 'custom', psd: [[10, 0.01], [100, 0.01]], hours: 0 }, temperature: { min_C: 23, max_C: 23, cycles_per_day: 0 }, humidity: 'dry', safety_factor: 3 };
  const run = (insert, want) => ({ features: [{ type: 'box', body: 'Shell', size: [30, 20, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [mat], customAssign: { Shell: 'petg-flat' }, conditions: { ambient: { temperature_K: 296.15 }, duty }, joints: [joint(insert)], want });
  for (const [id, insert, preload, eol, note] of [['run-no-sleeve', 'none', Fin, Fen, 'no sleeve: the plastic carries the bolt, so the preload creeps down'], ['run-sleeve', 'metal_sleeve', Fis, Fis, 'sleeve: the creep path is removed, end-of-life preload equals installed']]) {
    const r = run(insert);
    cases.push({ id, title: `Through the runner and a Worker: ${note}`, reference: `The closed form of case sleeve-vs-no-sleeve (installed ${preload.toFixed(1)} N, end of life ${eol.toFixed(1)} N), here with the built-in steel (E falls 0.1 % at 23 C against the 205 GPa of the closed form) and the joint stored in the project.`,
      setup: { features: r.features, customMaterials: r.customMaterials, customAssign: r.customAssign, conditions: r.conditions, joints: r.joints },
      expect: [{ metric: 'J1.preload_installed_min', value: preload, unit: 'N', tol: TOL }, { metric: 'J1.preload_end_of_life_min', value: eol, unit: 'N', tol: TOL }] });
  }
}
const out = { analysis: 'xyz.mdhv.joint-creep/joint-creep', package: 'examples/ext/joint-creep',
  $comment: 'Generated by gen-durability-a6a-cases.mjs. Tolerances (2 %) were fixed in the generator before the package was run against this file.', cases };
fs.writeFileSync(path.join(HERE, 'cases', 'durability-a6a.json'), JSON.stringify(out, null, 1) + '\n');
console.log('wrote cases/durability-a6a.json', cases.length, 'cases');
