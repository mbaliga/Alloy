// Regenerates analysis-validation/cases/thermal-3d.json. The analytic reference numbers (plane wall, cylinder, fin, lumped body,
// transient wall series, contact block, radiation) are computed here from their formulas, so the case file carries the numbers and
// their derivation, and the tolerances are written down next to them. Run from forge/:  node analysis-validation/gen-thermal-3d-cases.mjs
import fs from 'node:fs';
const K = 273.15;
const matdef = (id, name, k, rho, cp, eps) => ({ id, name, class: 'custom', notes: 'Validation test material.', properties: {
  k: { value: k, quality: 'measured', sources: ['user'] }, rho: { value: rho, quality: 'measured', sources: ['user'] }, cp: { value: cp, quality: 'measured', sources: ['user'] }, emissivity: { value: eps, quality: 'measured', sources: ['user'] },
  E: { value: null, quality: 'n/a', sources: [] }, nu: { value: null, quality: 'n/a', sources: [] }, yield_strength: { value: null, quality: 'n/a', sources: [] }, tensile_strength: { value: null, quality: 'n/a', sources: [] }, interlayer_factor: { value: null, quality: 'n/a', sources: [] } } });
const ID = 'xyz.mdhv.thermal-3d';
const insulate = (body, faces) => faces.map((f) => ({ id: 'ins' + f[1] + (f[0] === '+' ? 'p' : 'm'), kind: 'contact', body, face: f, conductance_W_m2K: 0 }));
const LAT = ['+y', '-y', '+z', '-z'];
const FIX = (extra = {}) => ({ convection: 'fixed', radiation: false, ...extra });
const S = (settings) => ({ [ID]: { settings } });
const probe = (metric, fn, args, value, tol, unit, mul) => ({ fn, metric, args, value, unit, tol, ...(mul ? { mul } : {}) });
const cases = [];

// ── 1D slab: fixed temperature on one face, convection on the other
{ const k = 2, L = 0.02, A = 1e-4, h = 10, T0 = 100, Ti = 20, Q = (T0 - Ti) / (L / (k * A) + 1 / (h * A)), q = Q / A;
  const Tconv = Ti + q / h + q * 0.5e-3 / k, Thot = T0 - q * 0.5e-3 / k;
  const st = FIX({ h_mm: 1, h_W_m2K: h, fixed: [{ face: '-x', body: 'Slab', T_C: T0 }] });
  cases.push({ id: 'slab-1d-convection', kind: 'core', title: 'Plane wall: fixed temperature on one face, convection on the other, other faces insulated',
    reference: `Analytic 1D conduction in series with convection (Incropera, Fundamentals of Heat and Mass Transfer, section 3.1, thermal resistance network): q = (T0 - Tinf) / (L/k + 1/h) with k ${k} W/m K, L 20 mm, h ${h} W/m2K, T0 ${T0} C, Tinf ${Ti} C gives Q = ${Q.toFixed(8)} W through 10 x 10 mm. The profile is exactly linear, so the cell-centre values are exact too: ${(Tconv).toFixed(5)} C in the cell at the convective face, ${Thot.toFixed(5)} C at the fixed face. Tolerances only cover the iterative solver.`,
    setup: { features: [{ type: 'box', body: 'Slab', size: [20, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-k2', 'Test k 2', k, 1000, 1000, 0.9)], customAssign: { Slab: 't-k2' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [], mounts: insulate('Slab', LAT) } },
    calls: [probe('Q through the wall', 'simulateSync.q.fixed', ['$input', st], Q, { rel: 1e-4 }, 'W', -1),
      probe('Q out by convection', 'simulateSync.q.conv', ['$input', st], Q, { rel: 1e-4 }, 'W'),
      probe('T at the convective face cell', 'probeT', ['$input', st, 9.5, 0.5, 0.5], Tconv + K, { abs: 0.01 }, 'K'),
      probe('T at the fixed face cell', 'probeT', ['$input', st, -9.5, 0.5, 0.5], Thot + K, { abs: 0.01 }, 'K')] }); }

// ── the same bar with a voxel size that does not divide its dimensions (staircase correction of the conduction path)
{ const k = 2, L = 0.02, A = 1e-4, h = 10, T0 = 100, Ti = 20, Q = (T0 - Ti) / (L / (k * A) + 1 / (h * A));
  const st = FIX({ h_mm: 3, h_W_m2K: h, fixed: [{ face: '-x', body: 'Slab', T_C: T0 }] });
  cases.push({ id: 'slab-nondividing-h', kind: 'core', title: 'Same plane wall, voxel size 3 mm that does not divide the 20 x 10 x 10 mm bar (7 x 4 x 4 voxels)',
    reference: `Analytic 1D conduction in series with convection as in slab-1d-convection (Incropera section 3.1): Q = ${Q.toFixed(8)} W. With h = 3 mm the conservative voxelizer keeps the partly touched last voxel in every axis (21 x 12 x 12 mm), which would add 2.5 % to Q without the staircase correction of the conduction path (face conductances and half-cell resistances scaled per axis by (V/V_voxel)(E_voxel/E)^2, as the film area already is). Tolerance 1 % of Q (stated before running; the uncorrected error was 2.5 % at h = 3 mm and 4.4 % at 7 mm).`,
    setup: { features: [{ type: 'box', body: 'Slab', size: [20, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-k2', 'Test k 2', k, 1000, 1000, 0.9)], customAssign: { Slab: 't-k2' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [], mounts: insulate('Slab', LAT) } },
    calls: [probe('Q through the wall (h 3 mm)', 'simulateSync.q.fixed', ['$input', st], Q, { rel: 0.01 }, 'W', -1), probe('Q out by convection (h 3 mm)', 'simulateSync.q.conv', ['$input', st], Q, { rel: 0.01 }, 'W'),
      probe('Q through the wall (h 7 mm)', 'simulateSync.q.fixed', ['$input', { ...st, h_mm: 7 }], Q, { rel: 0.01 }, 'W', -1)] }); }

// ── plane wall with generation, convection on both faces
{ const k = 2, Lh = 0.01, h = 10, Ti = 20, qv = 1e5, P = qv * 20e-3 * 10e-3 * 10e-3, x = 0.5e-3;
  const T = Ti + qv * Lh / h + qv * (Lh * Lh - x * x) / (2 * k);
  const st = FIX({ h_mm: 0.5, h_W_m2K: h });
  cases.push({ id: 'slab-generation-convection', kind: 'core', title: 'Plane wall with uniform heat generation and convection on both faces',
    reference: `Analytic (Incropera section 3.5, plane wall with uniform generation, same h and Tinf both sides): T(x) = Tinf + q'''L/h + q''' (L^2 - x^2)/(2k), half thickness L 10 mm, k ${k}, h ${h}, q''' ${qv} W/m3 (P = ${P} W in 20 x 10 x 10 mm), Tinf ${Ti} C. At the cell centre x = 0.5 mm: ${T.toFixed(5)} C. Cell-centred finite volume is second order, so the profile is matched to a small fraction of a kelvin, not to solver precision; all of P leaves by convection (energy conservation, exact).`,
    setup: { features: [{ type: 'box', body: 'Slab', size: [20, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-k2', 'Test k 2', k, 1000, 1000, 0.9)], customAssign: { Slab: 't-k2' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Slab' }, power_W: P }], mounts: insulate('Slab', LAT) } },
    calls: [probe('T near the centre', 'probeT', ['$input', st, 0.25, 0.25, 0.25], Ti + qv * Lh / h + qv * (Lh * Lh - 0.25e-3 ** 2) / (2 * k) + K, { abs: 0.05 }, 'K'),
      probe('T near the face', 'probeT', ['$input', st, 9.75, 0.25, 0.25], Ti + qv * Lh / h + qv * (Lh * Lh - 9.75e-3 ** 2) / (2 * k) + K, { abs: 0.05 }, 'K'),
      probe('Q out by convection', 'simulateSync.q.conv', ['$input', st], P, { rel: 1e-5 }, 'W')] }); }

// ── long cylinder with a heat source
{ const k = 1, R = 0.01, Lc = 0.01, h = 100, Ti = 20, P = 2, qv = P / (Math.PI * R * R * Lc), r = Math.hypot(0.25e-3, 0.25e-3);
  const dT = qv * R / (2 * h) + qv * (R * R - r * r) / (4 * k), dTs = qv * R / (2 * h);
  cases.push({ id: 'cylinder-generation', kind: 'core', title: 'Long cylinder with uniform heat generation, convection on the curved surface, insulated ends',
    reference: `Analytic (Incropera section 3.5, radial system with uniform generation): T(r) = Ts + q''' (R^2 - r^2)/(4k), Ts = Tinf + q''' R/(2h), R 10 mm, k ${k}, h ${h}, Tinf ${Ti} C, P = ${P} W in a 10 mm length (q''' = ${qv.toExponential(4)} W/m3, power per length ${(P / Lc)} W/m). At r = ${(r * 1e3).toFixed(3)} mm (the voxel nearest the axis): rise ${dT.toFixed(4)} K. The kernel's 48-gon is within 0.3 % of the circle in area and 0.1 % in perimeter. Staircase surface: the film conductance is scaled so each body keeps its mesh surface area. The metric is the absolute temperature at the axis voxel (${(Ti + K + dT).toFixed(3)} K = ${Ti + K} K + the rise). Tolerance 2 % of the rise (${(0.02 * dT).toFixed(3)} K, 0.28 % of the temperature shown), stated before running.`,
    setup: { features: [{ type: 'cylinder', body: 'Rod', d: 20, h: 10 }], customMaterials: [matdef('t-k1', 'Test k 1', k, 1000, 1000, 0.9)], customAssign: { Rod: 't-k1' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Rod' }, power_W: P }], mounts: insulate('Rod', ['+z', '-z']) } },
    calls: [probe('T at the axis (rise above ambient ' + dT.toFixed(2) + ' K)', 'probeT', ['$input', FIX({ h_mm: 0.5, h_W_m2K: h }), 0.25, 0.25, 5.25], Ti + K + dT, { abs: 0.02 * dT }, 'K'),
      probe('Q out by convection', 'simulateSync.q.conv', ['$input', FIX({ h_mm: 0.5, h_W_m2K: h })], P, { rel: 1e-5 }, 'W')] }); }

// ── fin efficiency
{ const k = 167, h = 25, t = 0.002, w = 0.01, L = 0.1, Ti = 20, Tb = 100, th = Tb - Ti, Pp = 2 * (w + t), Ac = w * t, m = Math.sqrt(h * Pp / (k * Ac)), M = Math.sqrt(h * Pp * k * Ac) * th, hm = h / (m * k);
  const Q = M * (Math.sinh(m * L) + hm * Math.cosh(m * L)) / (Math.cosh(m * L) + hm * Math.sinh(m * L)), As = Pp * L + Ac, eta = Q / (h * As * th);
  cases.push({ id: 'fin-efficiency', kind: 'core', title: 'Straight rectangular fin with a convective tip: efficiency',
    reference: `Analytic (Incropera section 3.6, rectangular fin, convection from the tip, eq. 3.72 and 3.91): q_f = M (sinh mL + (h/mk) cosh mL)/(cosh mL + (h/mk) sinh mL), M = sqrt(h P k Ac) theta_b, m = sqrt(h P/(k Ac)); eta_f = q_f/(h A_f theta_b). Fin 100 x 10 x 2 mm, k ${k} W/m K (aluminium), h ${h} W/m2K, base ${Tb} C, ambient ${Ti} C: m ${m.toFixed(3)} 1/m, mL ${(m * L).toFixed(3)}, q_f ${Q.toFixed(5)} W, A_f ${(As * 1e6).toFixed(0)} mm2, eta ${eta.toFixed(5)}. The 1D formula neglects the temperature variation across the 2 mm thickness (Biot ${(h * t / 2 / k).toExponential(1)}), so 3D and 1D agree; tolerance 1 % covers discretisation. Efficiency is computed as q_f / (h A_f theta_b) from the heat leaving through the fixed base.`,
    setup: { features: [{ type: 'box', body: 'Fin', size: [100, 10, 2], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-al', 'Test aluminium', k, 2700, 896, 0.09)], customAssign: { Fin: 't-al' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [], mounts: [] } },
    calls: [probe('Fin heat q_f', 'simulateSync.q.fixed', ['$input', FIX({ h_mm: 0.5, h_W_m2K: h, fixed: [{ face: '-x', body: 'Fin', T_C: Tb }] })], Q, { rel: 0.01 }, 'W', -1),
      probe('Fin efficiency', 'simulateSync.q.fixed', ['$input', FIX({ h_mm: 0.5, h_W_m2K: h, fixed: [{ face: '-x', body: 'Fin', T_C: Tb }] })], eta, { rel: 0.01 }, '', -1 / (h * As * th))] }); }

// ── transient: lumped capacitance (heating)
{ const k = 167, rho = 2700, cp = 896, h = 25, a = 0.02, V = a ** 3, A = 6 * a * a, tau = rho * cp * V / (h * A), Ti = 20, rise = 80, P = rise * h * A, tEnd = 600, lim = 70;
  const Tt = Ti + rise * (1 - Math.exp(-tEnd / tau)), tl = -tau * Math.log(1 - (lim - Ti) / rise), Bi = h * (V / A) / k;
  const st = FIX({ h_mm: 1, h_W_m2K: h, mode: 'transient', t_end_s: tEnd, dt_s: 1, limit_C: lim });
  cases.push({ id: 'transient-lumped', kind: 'core', title: 'Lumped-capacitance heating of a 20 mm aluminium cube (Biot 5e-4)',
    reference: `Analytic (Incropera section 5.1, lumped capacitance with generation): T - Tinf = (P/(hA)) (1 - exp(-t/tau)), tau = rho cp V/(h A) = ${tau.toFixed(3)} s, Bi = hV/(A k) = ${Bi.toExponential(2)} (lumped to better than 0.1 %). Cube 20 mm, aluminium k ${k} rho ${rho} cp ${cp}, h ${h} W/m2K on all faces, P = ${P.toFixed(3)} W (steady rise ${rise} K), starting at Tinf ${Ti} C. Volume-mean temperature at ${tEnd} s: ${Tt.toFixed(4)} C; time to reach ${lim} C: ${tl.toFixed(2)} s. First-order time stepping (implicit Euler, 1 s step) contributes about 0.04 K by the first-order estimate (t/tau)(dt/2tau)exp(-t/tau) x ${rise} K (0.014 K measured); the temperature tolerance of 0.15 K (0.2 % of the ${rise} K rise) also covers the voxel volume-mean approximation, and the time tolerance is 1 %. Limits are stated before running.`,
    setup: { features: [{ type: 'box', body: 'Cube', size: [20, 20, 20], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-al', 'Test aluminium', k, rho, cp, 0.09)], customAssign: { Cube: 't-al' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Cube' }, power_W: P }], mounts: [] } },
    calls: [probe('Mean T at 600 s', 'probeOf', ['$input', st, 'meanTK'], Tt + K, { abs: 0.15 }, 'K'), probe('Time to 70 C', 'probeOf', ['$input', st, 'timeToLimit'], tl, { rel: 0.01 }, 's')] }); }

// ── transient: slab with fixed surfaces
{ const k = 1, rho = 1000, cp = 1000, al = k / (rho * cp), Lh = 0.01, T0 = 100, Ts = 20, tEnd = 50, x = 0.5e-3;
  let th = 0; for (let n = 1; n <= 200; n++) { const z = (2 * n - 1) * Math.PI / 2, C = 4 * Math.pow(-1, n + 1) / ((2 * n - 1) * Math.PI); th += C * Math.exp(-z * z * al * tEnd / (Lh * Lh)) * Math.cos(z * x / Lh); }
  const T = Ts + (T0 - Ts) * th, Fo = al * tEnd / (Lh * Lh);
  const st = FIX({ h_mm: 0.5, mode: 'transient', initial_C: T0, t_end_s: tEnd, dt_s: 0.1, fixed: [{ face: '-x', body: 'Slab', T_C: Ts }, { face: '+x', body: 'Slab', T_C: Ts }] });
  cases.push({ id: 'transient-slab', kind: 'core', title: 'Plane wall suddenly held at a fixed surface temperature (transient conduction)',
    reference: `Analytic (Incropera section 5.5, exact one-term-per-eigenvalue solution, plane wall with surface temperature step): theta* = sum_n C_n exp(-zeta_n^2 Fo) cos(zeta_n x/L), zeta_n = (2n-1) pi/2, C_n = 4 (-1)^(n+1)/((2n-1) pi), 200 terms. Wall 20 mm (L = 10 mm), k ${k}, rho ${rho}, cp ${cp} (alpha ${al} m2/s), initially ${T0} C, both faces ${Ts} C from t = 0. At t = ${tEnd} s (Fo ${Fo.toFixed(2)}) and x = 0.5 mm from the mid-plane: ${T.toFixed(4)} C. Implicit Euler with a 0.1 s step and 0.5 mm cells; tolerance 0.3 K (1 % of the ${(T - Ts).toFixed(1)} K remaining difference), stated before running.`,
    setup: { features: [{ type: 'box', body: 'Slab', size: [20, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-k1', 'Test k 1', k, rho, cp, 0.9)], customAssign: { Slab: 't-k1' }, conditions: { ambient: { temperature_K: Ts + K, gravity: '-z' }, heat: [], mounts: insulate('Slab', LAT) } },
    calls: [probe('T near the mid-plane', 'probeT', ['$input', st, 0.25, 0.25, 0.25], T + K, { abs: 0.3 }, 'K')] }); }

// ── contact conductance to a sink, with a source and insulated sides
{ const k = 2, Lc = 0.02, hc = 500, Ti = 20, A = 1e-4, P = 0.5, qv = P / (A * Lc), Tx = (x) => Ti + qv * Lc / hc + qv * (Lc * x - x * x / 2) / k;
  const st = FIX({ h_mm: 0.5, h_W_m2K: 0 });
  cases.push({ id: 'contact-conductance-source', kind: 'core', title: 'Block with a heat source and a contact conductance to a sink on one face, everything else insulated',
    reference: `Analytic 1D: heat q''' (L - x) flows toward the contact face at x = 0, so T(x) = Tinf + q''' L/h_c + q''' (L x - x^2/2)/k with q''' = P/(A L) = ${qv} W/m3 (P ${P} W, 10 x 10 x 20 mm), k ${k}, L 20 mm, contact h_c ${hc} W/m2K to a sink at ${Ti} C (a mount with conductance_W_m2K, as in Analyze > Mounts). At the first cell centre (x = 0.25 mm): ${Tx(0.25e-3).toFixed(4)} C; at the last (x = 19.75 mm): ${Tx(19.75e-3).toFixed(4)} C. All P leaves through the contact (exact).`,
    setup: { features: [{ type: 'box', body: 'Blk', size: [20, 10, 10], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-k2', 'Test k 2', k, 1000, 1000, 0.9)], customAssign: { Blk: 't-k2' }, conditions: { ambient: { temperature_K: Ti + K, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Blk' }, power_W: P }], mounts: [...insulate('Blk', ['+y', '-y', '+z', '-z', '+x']), { id: 'sink', kind: 'contact', body: 'Blk', face: '-x', conductance_W_m2K: hc }] } },
    calls: [probe('T at the contact end', 'probeT', ['$input', st, -9.75, 0.25, 0.25], Tx(0.25e-3) + K, { abs: 0.05 }, 'K'), probe('T at the far end', 'probeT', ['$input', st, 9.75, 0.25, 0.25], Tx(19.75e-3) + K, { abs: 0.05 }, 'K'),
      probe('Q through the contact', 'simulateSync.q.contact', ['$input', st], P, { rel: 1e-5 }, 'W')] }); }

// ── linearised radiation to ambient, iterated
{ const k = 167, a = 0.02, A = 6 * a * a, eps = 0.9, P = 1, Ti = 20, Ta = Ti + K, sig = 5.670374419e-8, Ts = Math.pow(P / (eps * sig * A) + Ta ** 4, 0.25);
  cases.push({ id: 'radiation-iterated', kind: 'core', title: 'Aluminium cube heated in still air with radiation only (iterated linearisation)',
    reference: `Exact balance: P = eps sigma A (Ts^4 - Ta^4) (Stefan-Boltzmann, grey surface in large surroundings; Incropera eq. 1.7 and 1.9), A = ${(A * 1e4).toFixed(0)} cm2, eps ${eps}, P ${P} W, Ta ${Ta} K gives Ts = ${Ts.toFixed(4)} K. The cube is isothermal to ${(P / (a ** 3) * (a / 2) ** 2 / (2 * k)).toFixed(3)} K at most (conduction, k ${k}), so the centre cell is within 0.2 K of Ts; the solver linearises h_r = eps sigma (Ts^2 + Ta^2)(Ts + Ta) and iterates it with the face temperature.`,
    setup: { features: [{ type: 'box', body: 'Cube', size: [20, 20, 20], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-al9', 'Test aluminium eps 0.9', k, 2700, 896, eps)], customAssign: { Cube: 't-al9' }, conditions: { ambient: { temperature_K: Ta, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Cube' }, power_W: P }], mounts: [] } },
    calls: [probe('Surface temperature', 'probeT', ['$input', { h_mm: 1, convection: 'fixed', h_W_m2K: 0, radiation: true }, 0.5, 0.5, 0.5], Ts, { abs: 0.2 }, 'K'), probe('Q radiated', 'simulateSync.q.rad', ['$input', { h_mm: 1, convection: 'fixed', h_W_m2K: 0, radiation: true }], P, { rel: 1e-4 }, 'W')] }); }

// ── Cengel 15-10 with the A2 boundary conditions (natural convection by correlation + radiation)
{ const st = { h_mm: 10, quality: 'draft' };
  cases.push({ id: 'sealed-box-cengel-15-10', kind: 'core', title: 'Copper block with the outer size of the sealed box of Cengel Example 15-10, 112.7 W',
    reference: `Cengel, Heat and Mass Transfer 3rd ed., Example 15-10 (free chapter 15): box 0.40 x 0.30 x 0.15 m, eps 0.85, bottom insulated, room 35 C: published heat loss 112.7 W at a surface temperature of 65 C (convection 48.2 W, radiation 64.5 W). Here a solid copper block (k 401, isothermal to a few tenths of a kelvin) of that size receives 112.7 W through the Analyze conditions and the solver finds its surface temperature with the A1 correlations. Heat flows are about +-15 % from correlation spread (A1 reports 15 % on convection, 8 % on the total), which is about +-3.6 K on the 30 K rise (rise ~ Q^(1/1.25): 30 (1.15^0.8 - 1) = 3.5 K), so the tolerance is 3.6 K around 65 C. Not independent evidence for A2: the solver uses A1's own correlations (copied into the voxel core), so this is a plumbing check of the 3D model against a book value that those correlations already approximate.`,
    setup: { features: [{ type: 'box', body: 'Box', size: [400, 300, 150], anchor: 'bottom', pos: [0, 0, 0] }], customMaterials: [matdef('t-cu85', 'Copper, eps 0.85', 401, 8933, 385, 0.85)], customAssign: { Box: 't-cu85' }, conditions: { ambient: { temperature_K: 35 + K, gravity: '-z' }, heat: [{ id: 'q1', target: { body: 'Box' }, power_W: 112.7 }], mounts: [{ id: 'stand', kind: 'contact', body: 'Box', face: '-z', conductance_W_m2K: 0 }] } },
    calls: [probe('Block temperature', 'probeOf', ['$input', st, 'meanTK'], 65 + K, { abs: 3.6 }, 'K')] }); }

// ── the real runner and a Worker: energy balance, steady and transient
const encl = (size, wall, mat, board, comps, heat, amb, mounts = [], pcbExtra = {}) => ({
  features: [{ type: 'box', body: 'Case', size, anchor: 'bottom', pos: [0, 0, 0] }, { type: 'box', body: 'Case', op: 'cut', size: [size[0] - 2 * wall, size[1] - 2 * wall, size[2] - 2 * wall], anchor: 'bottom', pos: [0, 0, wall] }],
  materials: { Case: mat }, pcb: { components: [{ name: 'board', kind: 'box', role: 'board', box: board }, ...comps.map(([name, box]) => ({ name, kind: 'box', role: 'component', box }))] },
  conditions: { ambient: { temperature_K: amb + K, gravity: '-z' }, heat, mounts }, ...pcbExtra });
const E1 = encl([60, 40, 25], 2, 'abs', [-25, 25, -16, 16, 6, 7.6], [['mcu', [-4, 4, -4, 4, 7.6, 9.6]]], [{ id: 'h1', target: { pcb: 'mcu' }, power_W: 1 }], 25);
cases.push({ id: 'energy-balance-steady', title: 'Enclosure with a board and a 1 W part: every watt leaves (steady)', reference: 'Conservation of energy (exact): at steady state the heat leaving through convection, radiation and contacts equals the 1 W dissipated; the balance is evaluated with the boundary coefficients recomputed at the final temperatures, so it also measures the nonlinear iteration. Not a comparison with a measurement.',
  setup: { ...E1, ext: S({ quality: 'draft', backend: 'cpu' }) }, expectValidity: 'estimate',
  expect: [{ metric: 'energy_balance_error', value: 0, unit: 'W', tol: { abs: 0.001 } }, { metric: 'total_heat', value: 1, unit: 'W', tol: { rel: 1e-9 } }] });
cases.push({ id: 'energy-balance-transient', title: 'Same enclosure, transient: heat in = heat out + heat stored', reference: 'Conservation of energy (exact): at every implicit-Euler step C (T_new - T_old)/dt + Q_out = Q_in with the coefficients the step used; the reported error is the last step, in watts. 300 s from ambient in 5 s steps.',
  setup: { ...E1, ext: S({ quality: 'draft', backend: 'cpu', mode: 'transient', t_end_s: 300, dt_s: 5 }) }, expectValidity: 'estimate',
  expect: [{ metric: 'energy_balance_error', value: 0, unit: 'W', tol: { abs: 0.001 } }, { metric: 'total_heat', value: 1, unit: 'W', tol: { rel: 1e-9 } }] });

// ── A1 versus A2 on three reference enclosures (spec 4, A1 validation: agreement within 15 %)
const A1 = { analysis: 'xyz.mdhv.thermal-estimate/thermal-estimate', package: 'examples/ext/thermal-estimate' };
const cmp = (id, title, setup, extra, expectedDivergence) => cases.push({ id, kind: 'compare', title, reference: `Spec docs/EXTENSIONS_SPEC.md section 4 (A1 validation): A1, the resistance network, and A2, the 3D finite-volume solution, should agree within 15 % on the hottest-part temperature RISE above ambient for three reference enclosures. A1 is the reference side only in the sense of the spec: neither is a measurement. ${extra}`,
  setup: { ...setup, ext: S({ quality: 'normal', backend: 'cpu' }) }, a: { metric: 'max_component_temp' }, b: { ...A1, metric: 'max_component_temp' }, tol: { rel: 0.15 },
  info: ['max_outer_surface_temp', 'internal_air_temp'], ...(expectedDivergence ? { expectedDivergence } : {}) });
cmp('enclosure-1-small-abs', 'Enclosure 1: 60 x 40 x 25 mm ABS case, 2 mm wall, 1 W part on a board, still air 25 C', E1, 'Sealed small box, thick plastic wall, no mount.');
cmp('enclosure-2-handheld-pc', 'Enclosure 2: 140 x 70 x 30 mm polycarbonate case, 3 W SoC and 1 W regulator, still air 30 C',
  encl([140, 70, 30], 2, 'pc', [-60, 60, -28, 28, 6, 7.6], [['soc', [-8, 8, -8, 8, 7.6, 9.6]], ['reg', [30, 36, -3, 3, 7.6, 9.4]]], [{ id: 'h1', target: { pcb: 'soc' }, power_W: 3 }, { id: 'h2', target: { pcb: 'reg' }, power_W: 1 }], 30), 'Larger plastic hand-held case with two heat sources.',
  'Documented expected divergence, tolerance NOT loosened: A2 gives a rise 20.8 % above A1 (A2 129.2 C vs A1 112.1 C at the time of writing). The cause is not isolated: A1 is a resistance network without board spreading and with its own cavity model, A2 treats the enclosed air as an effective conductor (called coarse in its warnings), and neither is a measurement. The case stays red as a check and does not count as a pass; the Elmer reference of step A4 is the arbiter.');
cmp('enclosure-3-aluminium-rail', 'Enclosure 3: 100 x 60 x 30 mm aluminium case, 5 W part, rail-mount contact 150 W/m2K on the bottom, 25 C',
  encl([100, 60, 30], 2, 'al6061', [-40, 40, -22, 22, 5, 6.6], [['pa', [-6, 6, -6, 6, 6.6, 9]]], [{ id: 'h1', target: { pcb: 'pa' }, power_W: 5 }], 25, [{ id: 'rail', kind: 'contact', body: 'Case', face: '-z', conductance_W_m2K: 150 }]), 'Conductive metal case with a contact to a sink.');

// ── the fourth spec case: needs the Alloy Runner
cases.push({ id: 'enclosure-vs-elmer-fine', kind: 'reference', title: '3D enclosure against an Elmer reference, max-temperature error within 5 % at fine',
  reference: 'Spec docs/EXTENSIONS_SPEC.md section 4 (A2 validation): "a 3D enclosure compared with an Elmer reference within 5 % max-temperature error at fine". The reference numbers are produced once by the Alloy Runner (step A4) with Elmer and stored in analysis-validation/reference/enclosure-elmer.json (format in analysis-validation/README.md). That file does not exist yet, so this case is SKIPPED: it is not a pass, and A2 therefore returns validity "estimate".',
  referenceFile: 'analysis-validation/reference/enclosure-elmer.json',
  setup: { ...E1, ext: S({ quality: 'fine', backend: 'cpu' }) },
  expect: [{ metric: 'max_temp', unit: '°C', refKey: 'max_temp_C', tol: { rel: 0.05 } }] });

fs.writeFileSync('analysis-validation/cases/thermal-3d.json', JSON.stringify({ analysis: `${ID}/thermal-3d`, package: 'examples/ext/thermal-3d', cases }, null, 1) + '\n');
console.log(cases.length, 'cases');
