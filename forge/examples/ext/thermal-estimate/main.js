// Thermal estimate (A1): a steady-state thermal resistance network for a hand-held electronics enclosure. Milliseconds, no solver
// dependency, validity "estimate". Everything is SI inside; temperatures in the result are in °C.
//
//   component(s) --> board --> internal air --> inner wall --> (conduction) --> outer wall --> ambient (natural/forced convection + radiation)
//                \________________________^          \--> vents (stack flow carries heat out)        \--> mounting contact to a sink at ambient
//
// The model is deliberately small and its assumptions are listed in every result's warnings. Correlations (all for air near 1 atm):
//   vertical surface         Churchill and Chu (1975), full-range:  Nu = (0.825 + 0.387 Ra^(1/6) / (1 + (0.492/Pr)^(9/16))^(8/27))^2
//   horizontal, hot face up (or cold face down):   Nu = 0.54 Ra^(1/4)  (Ra < 1e7),  0.15 Ra^(1/3)  (Ra >= 1e7);  L = A/P
//   horizontal, hot face down (or cold face up):   Nu = 0.27 Ra^(1/4)
//   forced flow along a plate:  Nu = 0.664 Re^(1/2) Pr^(1/3) (Re < 5e5),  (0.037 Re^(4/5) - 871) Pr^(1/3) otherwise;  mixed with natural: (Nu_n^3 + Nu_f^3)^(1/3)
//   radiation: linearised grey-body, h_r = eps sigma (Ts^2 + Ta^2)(Ts + Ta)
//   vent stack flow: V = Cd A_eff sqrt(2 g H (Ti - To) / Ti),  A_eff = A1 A2 / sqrt(A1^2 + A2^2),  Cd = 0.6
const SIGMA = 5.670374419e-8, G = 9.80665, KEL = 273.15;
const sq = (x) => x * x;
const finite = (x) => Number.isFinite(x);
const FACE = { '+x': [1, 0, 0], '-x': [-1, 0, 0], '+y': [0, 1, 0], '-y': [0, -1, 0], '+z': [0, 0, 1], '-z': [0, 0, -1] };
const FACES = Object.keys(FACE);

// ───────────────────────── air properties at film temperature T (K), 1 atm ─────────────────────────
// Fits to standard tables for dry air between 250 and 450 K; within about 3 % of the Incropera and Cengel tables (which differ from each other by about 2 %).
function airProps(T) {
  const rho = 101325 / (287.05 * T), mu = 1.846e-5 * Math.pow(T / 300, 0.7), k = 0.0260 * Math.pow(T / 300, 0.85);
  const cp = 1007, Pr = Math.min(0.74, Math.max(0.66, 0.71 - 0.00015 * (T - 300)));
  return { rho, mu, nu: mu / rho, k, cp, Pr, beta: 1 / T, alpha: k / (rho * cp) };
}

// ───────────────────────── correlations (Nu for a given Rayleigh / Reynolds number) ─────────────────────────
const rayleigh = (dT, L, p) => G * p.beta * Math.abs(dT) * L * L * L / (p.nu * p.alpha);
const nuVertical = (Ra, Pr) => sq(0.825 + 0.387 * Math.pow(Ra, 1 / 6) / Math.pow(1 + Math.pow(0.492 / Pr, 9 / 16), 8 / 27));
const nuHorizontalEnhanced = (Ra) => (Ra < 1e7 ? 0.54 * Math.pow(Ra, 0.25) : 0.15 * Math.pow(Ra, 1 / 3));
const nuHorizontalRestricted = (Ra) => 0.27 * Math.pow(Ra, 0.25);
const nuForcedPlate = (Re, Pr) => (Re < 5e5 ? 0.664 * Math.sqrt(Re) * Math.cbrt(Pr) : (0.037 * Math.pow(Re, 0.8) - 871) * Math.cbrt(Pr));
const hRadiation = (eps, Ts, Ta) => eps * SIGMA * (sq(Ts) + sq(Ta)) * (Ts + Ta);
const stackFlow = (Aa, Ab, H, Ti, To, Cd = 0.6) => {
  if (!(Aa > 0 && Ab > 0 && H > 0) || !(Ti > To)) return 0;
  const Ae = Aa * Ab / Math.hypot(Aa, Ab);
  return Cd * Ae * Math.sqrt(2 * G * H * (Ti - To) / Ti);
};

/**
 * Natural (and optionally forced) convection coefficient of one face.
 * orient: 'side' | 'up' | 'down' (the way the surface faces); hotter: the surface is hotter than the fluid; L: characteristic length (m).
 * Returns { h, Ra, Nu, outside } where `outside` says the correlation is used beyond its tabulated range.
 */
function hFace({ orient, hotter, L, Ts, Tf, airspeed = 0 }) {
  const Tm = (Ts + Tf) / 2, p = airProps(Tm), dT = Math.max(Math.abs(Ts - Tf), 1e-3), Ra = rayleigh(dT, L, p);
  let Nu, outside = false;
  if (orient === 'side') { Nu = nuVertical(Ra, p.Pr); outside = Ra < 1e-1; }
  else {
    const enhanced = (orient === 'up') === hotter; // hot face up, or cold face down
    Nu = enhanced ? nuHorizontalEnhanced(Ra) : nuHorizontalRestricted(Ra);
    outside = enhanced ? (Ra < 1e4 || Ra > 1e11) : (Ra < 1e5 || Ra > 1e10);
  }
  Nu = Math.max(Nu, 1); // never below conduction across one length
  if (airspeed > 0) {
    const Nf = Math.max(nuForcedPlate(airspeed * L / p.nu, p.Pr), 0);
    Nu = Math.cbrt(Nu * Nu * Nu + Nf * Nf * Nf);
  }
  return { h: Nu * p.k / L, Ra, Nu, outside };
}

// ───────────────────────── dense linear solve (the network has fewer than 40 nodes) ─────────────────────────
function solveLinear(A, b) {
  const n = b.length, M = A.map((r, i) => [...r, b[i]]);
  for (let c = 0; c < n; c++) {
    let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    if (Math.abs(M[p][c]) < 1e-18) throw new Error('the thermal network is singular (a node has no path to ambient)');
    [M[c], M[p]] = [M[p], M[c]];
    for (let r = c + 1; r < n; r++) { const f = M[r][c] / M[c][c]; if (f) for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; }
  }
  const x = new Array(n);
  for (let r = n - 1; r >= 0; r--) { let s = M[r][n]; for (let k = r + 1; k < n; k++) s -= M[r][k] * x[k]; x[r] = s / M[r][r]; }
  return x;
}

/** Solve a linear resistance network: edges [i, j, G] (j = -1 is ambient at Tamb), Q[i] heat into node i. Returns temperatures. */
function solveNetwork(n, edges, Q, Tamb) {
  const A = Array.from({ length: n }, () => new Array(n).fill(0)), b = Q.slice();
  for (const [i, j, g] of edges) {
    A[i][i] += g;
    if (j < 0) b[i] += g * Tamb; else { A[j][j] += g; A[i][j] -= g; A[j][i] -= g; }
  }
  return solveLinear(A, b);
}

// ───────────────────────── geometry of the enclosure from the meshes ─────────────────────────
const bboxOf = (bodies) => {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const b of bodies) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], b.bbox.min[k]); hi[k] = Math.max(hi[k], b.bbox.max[k]); }
  return { lo, hi, size: hi.map((v, k) => v - lo[k]) };
};
const meshArea = (b) => {
  const p = b.positions, t = b.indices; let a = 0;
  for (let i = 0; i < t.length; i += 3) {
    const o = t[i] * 3, q = t[i + 1] * 3, r = t[i + 2] * 3;
    const ux = p[q] - p[o], uy = p[q + 1] - p[o + 1], uz = p[q + 2] - p[o + 2], vx = p[r] - p[o], vy = p[r + 1] - p[o + 1], vz = p[r + 2] - p[o + 2];
    a += 0.5 * Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
  }
  return a;
};
const compBox = (c) => {
  if (c.kind === 'cylinder' && c.cyl) {
    const { axis = 'x', a0, a1, c: [u, v], r } = c.cyl, ax = 'xyz'.indexOf(axis), o = [0, 1, 2].filter((k) => k !== ax);
    const lo = [0, 0, 0], hi = [0, 0, 0]; lo[ax] = a0; hi[ax] = a1; lo[o[0]] = u - r; hi[o[0]] = u + r; lo[o[1]] = v - r; hi[o[1]] = v + r;
    return [lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]];
  }
  return c.box;
};
const boxDims = (b) => [Math.abs(b[1] - b[0]), Math.abs(b[3] - b[2]), Math.abs(b[5] - b[4])];

/** Orientation of a face for a given gravity direction ("-z" means gravity points down the -z axis). */
function orientOf(face, gravity) {
  const n = FACE[face], g = FACE[gravity] || FACE['-z'], d = -(n[0] * g[0] + n[1] * g[1] + n[2] * g[2]); // +1: faces up
  return d > 0.5 ? 'up' : d < -0.5 ? 'down' : 'side';
}

// ───────────────────────── the model ─────────────────────────
// Geometry, wall and mounting of the enclosure, from the bodies and the conditions.
function enclosure(input, warnings) {
  const S = input.setup, cond = S.conditions, bodies = input.bodies;
  if (!bodies.length) throw new Error('there is no built body to analyse');
  const Tamb = cond.ambient.temperature_K, gravity = cond.ambient.gravity || '-z', wind = cond.ambient.airspeed_ms || 0;
  const bb = bboxOf(bodies), mm = 1e-3;
  const gAx = 'xyz'.indexOf(gravity[1]);
  // wall: thickness from mass-conserving estimate t = 2 V / A_mesh (a shell's mesh has an inner and an outer skin)
  const V = bodies.reduce((a, b) => a + b.volume_mm3, 0), Amesh = bodies.reduce((a, b) => a + meshArea(b), 0);
  let t = (2 * V) / Amesh; // mm
  const minDim = Math.min(...bb.size);
  if (t > 0.25 * minDim) { warnings.push(`The body looks solid (average wall ${t.toFixed(1)} mm against a smallest dimension of ${minDim.toFixed(1)} mm); the model assumes a hollow shell, so treat the result as a rough bound.`); t = Math.min(t, 0.25 * minDim); }
  const wallK = vw(bodies, S, (m) => m.props.k), wallEps = vw(bodies, S, (m) => m.props.emissivity);
  const Lm = bb.size.map((x) => x * mm), tm = t * mm;
  const inner = Lm.map((x) => Math.max(x - 2 * tm, 1e-3));
  const faceArea = (dims, face) => { const ax = 'xyz'.indexOf(face[1]), o = [0, 1, 2].filter((k) => k !== ax); return dims[o[0]] * dims[o[1]]; };
  // per-face outer geometry and orientation (the six faces of the bounding box)
  const faces = FACES.map((f) => {
    const ax = 'xyz'.indexOf(f[1]), o = [0, 1, 2].filter((k) => k !== ax), orient = orientOf(f, gravity);
    const Ao = faceArea(Lm, f), Ai = faceArea(inner, f);
    const L = (dims) => (orient === 'side' ? dims[gAx] : (dims[o[0]] * dims[o[1]]) / (2 * (dims[o[0]] + dims[o[1]])));
    return { face: f, ax, orient, Ao, Ai, Lout: L(Lm), Lin: L(inner), mount: 0 };
  });
  // mounting contact: conductance to a sink at ambient, over a part of one face
  const mounts = (cond.mounts || []).filter((m) => m.kind === 'contact');
  for (const m of cond.mounts || []) if (m.kind === 'fixed') warnings.push(`Mount "${m.id}" is a fixed (structural) mount; it has no thermal effect here.`);
  for (const m of mounts) {
    const fc = faces.find((x) => x.face === m.face); if (!fc) continue;
    let A = fc.Ao;
    if (m.box) { const o = [0, 1, 2].filter((k) => k !== fc.ax), ext = (k) => Math.max(0, Math.min(m.box[2 * k + 1], bb.hi[k]) - Math.max(m.box[2 * k], bb.lo[k])) * mm; A = Math.min(fc.Ao, ext(o[0]) * ext(o[1])); }
    fc.mount += A; fc.mountG = (fc.mountG || 0) + m.conductance_W_m2K * A;
  }
  return { S, cond, bodies, Tamb, gravity, wind, bb, mm, gAx, t, wallK, wallEps, Lm, tm, inner, faces };
}

/**
 * input: the analysis input (bodies in mm, setup in SI). opts: { vents: [{ area_mm2, z_mm }], target_C, Cd }.
 * Returns { summary, warnings, nodes, faces, converged, ... } with temperatures in °C.
 */
function thermalModel(input, opts = {}) {
  const warnings = [];
  const { S, cond, bodies, Tamb, gravity, wind, bb, mm, gAx, t, wallK, wallEps, Lm, tm, inner, faces } = enclosure(input, warnings);
  // heat sources, components and board
  const comps = ((S.pcb && S.pcb.components) || []).filter((c) => (c.role || 'component') === 'component' && (c.box || c.cyl));
  const boardC = ((S.pcb && S.pcb.components) || []).find((c) => c.role === 'board' && c.box);
  const fr4 = { k: 0.3 };
  const nodes = [];
  const board = boardC ? (() => {
    const d = boxDims(boardC.box), thin = d.indexOf(Math.min(...d)), o = [0, 1, 2].filter((k) => k !== thin);
    return { name: boardC.name || 'pcb', t: d[thin] * mm, A: d[o[0]] * d[o[1]] * mm * mm, orient: thin === gAx ? 'horizontal' : 'side', L: thin === gAx ? (d[o[0]] * d[o[1]] * mm * mm) / (2 * (d[o[0]] + d[o[1]]) * mm) : d[gAx] * mm };
  })() : null;
  const cs = comps.map((c) => {
    const b = compBox(c), d = boxDims(b).map((x) => x * mm), fp = Math.min(d[0] * d[1], d[0] * d[2], d[1] * d[2]);
    const area = 2 * (d[0] * d[1] + d[0] * d[2] + d[1] * d[2]);
    return { name: c.name, d, fp, ext: Math.max(area - fp, fp), Lc: Math.max(d[gAx], 1e-3), Q: 0, cyl: c.kind === 'cylinder' };
  });
  if (board) for (const c of cs) { if (!(c.Q > 0)) { c.Gcb = fr4.k * Math.max((c.d[0] + 2 * board.t) * (c.d[1] + 2 * board.t), c.fp) / board.t; continue; } const a = (c.d[0] + 2 * board.t) * (c.d[1] + 2 * board.t); c.Gcb = fr4.k * Math.max(a, c.fp) / board.t; warnings.push(`Component "${c.name}" to board: ${(1 / c.Gcb).toFixed(0)} K/W, estimated from the board thickness with 45° spreading through FR-4. A datasheet junction-to-board value is more reliable; this is the least certain number here.`); }
  let Qair = 0, Qtotal = 0;
  for (const h of cond.heat || []) {
    Qtotal += h.power_W;
    if (h.target.pcb != null) {
      const c = cs.find((x) => x.name === h.target.pcb);
      if (c) c.Q += h.power_W; else if (board && h.target.pcb === board.name) board.Q = (board.Q || 0) + h.power_W;
      else { Qair += h.power_W; warnings.push(`Heat source "${h.id}" is on PCB part "${h.target.pcb}", which is not a component or board in the document; its heat goes into the internal air.`); }
    } else { Qair += h.power_W; warnings.push(`Heat source "${h.id}" is on body "${h.target.body}"; the model puts that heat into the internal air.`); }
  }
  if (!(Qtotal > 0)) warnings.push('No heat sources are set (Analyze > Conditions); every temperature equals ambient.');
  if (comps.length && !board) warnings.push('The document has components but no board; components lose heat to the air only.');

  // vents
  const vents = (opts.vents || []).filter((v) => v.area_mm2 > 0);
  const zMid = (bb.lo[2] + bb.hi[2]) / 2, ventAx = gAx; // stack height is measured along the gravity axis
  const sgn = gravity[0] === '-' ? 1 : -1; // height above the bottom, in mm, along "up"
  const ventHeight = (z) => (sgn > 0 ? z - bb.lo[ventAx] : bb.hi[ventAx] - z);
  void zMid;

  // fixed-point iteration: h depends on temperatures
  const nC = cs.length, iB = nC, iA = nC + (board ? 1 : 0), iW = iA + 1, iS = iW + 6, N = iS + 6; // wall inner 6, outer 6
  let T = new Array(N).fill(Tamb + 5);
  const Aw_in = faces.reduce((a, f) => a + f.Ai, 0);
  let converged = false, iter = 0, ventQ = 0, Vdot = 0, outside = false, ventNote = null;
  const hOutArr = new Array(6).fill(0), hInArr = new Array(6).fill(0);
  let edges = [], Q = [];
  const ventGeom = () => {
    if (!vents.length) return null;
    const lo = vents.filter((v) => ventHeight(v.z_mm) < (bb.size[ventAx]) / 2), hi = vents.filter((v) => ventHeight(v.z_mm) >= (bb.size[ventAx]) / 2);
    const Alow = lo.reduce((a, v) => a + v.area_mm2, 0) * 1e-6, Ahigh = hi.reduce((a, v) => a + v.area_mm2, 0) * 1e-6;
    const mean = (l) => l.reduce((a, v) => a + ventHeight(v.z_mm) * v.area_mm2, 0) / Math.max(l.reduce((a, v) => a + v.area_mm2, 0), 1e-12) * mm;
    return { Alow, Ahigh, H: Alow > 0 && Ahigh > 0 ? Math.max(mean(hi) - mean(lo), 0) : 0 };
  };
  const VG = ventGeom();
  if (VG && !(VG.Alow > 0 && VG.Ahigh > 0)) { ventNote = 'All vents are at the same level (all in the lower or all in the upper half); there is no stack flow, so the vents are ignored.'; }
  else if (VG && !(VG.H > 0)) ventNote = 'The vents have no height difference, so there is no stack flow.';
  if (ventNote) warnings.push(ventNote);
  const Cd = opts.Cd || 0.6;

  for (iter = 0; iter < 300; iter++) {
    const Ta = T[iA], pAir = airProps((Ta + Tamb) / 2);
    edges = []; Q = new Array(N).fill(0);
    // vents
    Vdot = VG && VG.H > 0 ? stackFlow(VG.Alow, VG.Ahigh, VG.H, Ta, Tamb, Cd) : 0;
    const rhoA = 101325 / (287.05 * Ta);
    ventQ = rhoA * 1007 * Vdot; // W/K
    if (ventQ > 0) edges.push([iA, -1, ventQ]);
    // inner convection and wall conduction, outer convection + radiation
    faces.forEach((f, i) => {
      const Tin = T[iW + i], Tout = T[iS + i];
      const hi = hFace({ orient: f.orient, hotter: Tin > Ta, L: f.Lin, Ts: Tin, Tf: Ta });
      const ho = hFace({ orient: f.orient, hotter: Tout > Tamb, L: f.Lout, Ts: Tout, Tf: Tamb, airspeed: wind });
      outside = outside || hi.outside || ho.outside;
      hInArr[i] = hi.h; hOutArr[i] = ho.h;
      edges.push([iA, iW + i, hi.h * f.Ai]);
      edges.push([iW + i, iS + i, wallK * f.Ao / tm]);
      const hr = hRadiation(wallEps, Tout, Tamb), Aexp = Math.max(f.Ao - f.mount, 0);
      edges.push([iS + i, -1, (ho.h + hr) * Aexp + (f.mountG || 0)]);
    });
    // board and components
    if (board) {
      const o = board.orient === 'horizontal';
      const up = hFace({ orient: o ? 'up' : 'side', hotter: T[iB] > Ta, L: board.L, Ts: T[iB], Tf: Ta }), dn = hFace({ orient: o ? 'down' : 'side', hotter: T[iB] > Ta, L: board.L, Ts: T[iB], Tf: Ta });
      outside = outside || up.outside || dn.outside;
      edges.push([iB, iA, (up.h + dn.h) * board.A]);
      const hr = hRadiation(0.9, T[iB], Tamb);
      faces.forEach((f, i) => edges.push([iB, iW + i, hr * 2 * board.A * (f.Ai / Aw_in) * 0.9]));
      Q[iB] += board.Q || 0;
    }
    cs.forEach((c, i) => {
      const hc = hFace({ orient: 'side', hotter: T[i] > Ta, L: c.Lc, Ts: T[i], Tf: Ta });
      outside = outside || hc.outside;
      edges.push([i, iA, hc.h * c.ext]);
      const hr = hRadiation(0.9, T[i], Tamb);
      faces.forEach((f, k) => edges.push([i, iW + k, hr * c.ext * (f.Ai / Aw_in) * 0.9]));
      if (board) { edges.push([i, iB, c.Gcb]); }
      Q[i] += c.Q;
    });
    Q[iA] += Qair;
    void pAir;
    const Tn = solveNetwork(N, edges, Q, Tamb);
    let dmax = 0; for (let k = 0; k < N; k++) dmax = Math.max(dmax, Math.abs(Tn[k] - T[k]));
    T = T.map((x, k) => x + 0.5 * (Tn[k] - x));
    if (dmax < 1e-4) { converged = true; break; }
  }
  if (!converged) warnings.push(`The iteration did not converge in ${iter} steps; the temperatures are the last iterate.`);
  // energy balance: heat leaving through outer surfaces + vents equals heat in
  let Qout = ventQ * (T[iA] - Tamb);
  faces.forEach((f, i) => { const hr = hRadiation(wallEps, T[iS + i], Tamb); Qout += ((hOutArr[i] + hr) * Math.max(f.Ao - f.mount, 0) + (f.mountG || 0)) * (T[iS + i] - Tamb); });
  const C = (x) => x - KEL;
  const res = {
    warnings, converged, iterations: iter, outside, Tamb, Qtotal, Qout, ventQ, Vdot, faces, wall: { t, k: wallK, eps: wallEps },
    air: T[iA], board: board ? T[iB] : null, comps: cs.map((c, i) => ({ name: c.name, T: T[i], Q: c.Q })),
    wallOuter: T.slice(iS, iS + 6), wallInner: T.slice(iW, iW + 6), hOut: hOutArr.slice(), hIn: hInArr.slice(),
    ventNote, hasVents: !!(VG && VG.H > 0),
  };
  res.summary = [
    { metric: 'internal_air_temp', value: C(res.air), unit: '°C' },
    ...(board ? [{ metric: 'board_temp', value: C(res.board), unit: '°C' }] : []),
    ...cs.map((c, i) => ({ metric: `component_temp_${String(c.name).replace(/[^\w.-]+/g, '_').slice(0, 40)}`, value: C(T[i]), unit: '°C' })),
    { metric: 'max_component_temp', value: C(Math.max(res.air, ...(board ? [res.board] : []), ...cs.map((c, i) => T[i]))), unit: '°C' },
    { metric: 'max_outer_surface_temp', value: C(Math.max(...res.wallOuter)), unit: '°C' },
    { metric: 'total_heat', value: Qtotal, unit: 'W' },
    { metric: 'heat_through_vents', value: ventQ * (T[iA] - Tamb), unit: 'W' },
    { metric: 'vent_flow', value: Vdot * 1000, unit: 'L/s' },
    { metric: 'energy_balance_error', value: Math.abs(Qout - Qtotal), unit: 'W' },
  ];
  res.cavity = inner; res.outer = Lm; res.bb = bb; res.areaWall = faces.reduce((a, f) => a + f.Ao, 0);
  return res;
}

/**
 * Heat rejected from the outer surface of the box when every outer face is held at `Ts` (K): the part of the model that a
 * textbook "box at a given surface temperature" example covers. Faces with a zero-conductance mount are insulated.
 */
function outerRejection(input, Ts) {
  const e = enclosure(input, []);
  let conv = 0, rad = 0;
  for (const f of e.faces) {
    const A = Math.max(f.Ao - f.mount, 0), h = hFace({ orient: f.orient, hotter: Ts > e.Tamb, L: f.Lout, Ts, Tf: e.Tamb, airspeed: e.wind }).h;
    conv += h * A * (Ts - e.Tamb); rad += hRadiation(e.wallEps, Ts, e.Tamb) * A * (Ts - e.Tamb);
  }
  return { conv, rad, total: conv + rad };
}

// volume-weighted property of the bodies' materials
function vw(bodies, S, pick) {
  let num = 0, den = 0;
  for (const b of bodies) { const m = S.bodies.find((x) => x.name === b.name).material; num += pick(m) * b.volume_mm3; den += b.volume_mm3; }
  return den > 0 ? num / den : pick(S.bodies[0].material);
}

/**
 * Smallest total vent area (mm², half low and half high, separated by `H` metres of height) at which `metric` stays at or below
 * `target_C`. Returns { area_mm2, reachable, current }. 0 when the closed box already meets the target.
 */
function ventAreaFor(input, target_C, opts = {}) {
  const pick = (r) => Math.max(r.air, ...(r.board != null ? [r.board] : []), ...r.comps.map((c) => c.T)) - KEL;
  const base = thermalModel(input, { ...opts, vents: [] });
  const current = pick(base);
  if (current <= target_C) return { area_mm2: 0, reachable: true, current, atArea: current };
  const hgt = base.bb.size[ 'xyz'.indexOf((input.setup.conditions.ambient.gravity || '-z')[1]) ];
  const mk = (a) => [{ area_mm2: a / 2, z_mm: base.bb.lo['xyz'.indexOf((input.setup.conditions.ambient.gravity || '-z')[1])] + hgt * 0.1 }, { area_mm2: a / 2, z_mm: base.bb.lo['xyz'.indexOf((input.setup.conditions.ambient.gravity || '-z')[1])] + hgt * 0.9 }];
  const flip = (input.setup.conditions.ambient.gravity || '-z')[0] === '+';
  const place = (a) => { const v = mk(a); if (flip) { const ax = 'xyz'.indexOf((input.setup.conditions.ambient.gravity || '-z')[1]); v.forEach((x) => { x.z_mm = base.bb.lo[ax] + base.bb.hi[ax] - x.z_mm; }); } return v; };
  const maxA = base.areaWall * 1e6 * 0.5;
  const at = (a) => pick(thermalModel(input, { ...opts, vents: place(a) }));
  if (at(maxA) > target_C) return { area_mm2: null, reachable: false, current, atArea: at(maxA), maxArea: maxA };
  let lo = 0, hi = maxA;
  for (let i = 0; i < 40; i++) { const mid = (lo + hi) / 2; if (at(mid) > target_C) lo = mid; else hi = mid; }
  return { area_mm2: hi, reachable: true, current, atArea: at(hi) };
}

// ───────────────────────── surface field: outer and inner skin temperature ─────────────────────────
function surfaceField(b, res) {
  const p = b.positions, idx = b.indices, nV = p.length / 3, acc = new Float64Array(nV), cnt = new Float64Array(nV);
  const { lo, hi } = res.bb, tol = Math.max(res.wall.t * 0.75, 0.05);
  const Tm = (res.Tamb);
  for (let i = 0; i < idx.length; i += 3) {
    const a = idx[i] * 3, bq = idx[i + 1] * 3, c = idx[i + 2] * 3;
    const ux = p[bq] - p[a], uy = p[bq + 1] - p[a + 1], uz = p[bq + 2] - p[a + 2], vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
    const n = [uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx], len = Math.hypot(...n) || 1;
    let ax = 0; for (let k = 1; k < 3; k++) if (Math.abs(n[k]) > Math.abs(n[ax])) ax = k;
    const sgn = n[ax] >= 0 ? 1 : -1, face = (sgn > 0 ? '+' : '-') + 'xyz'[ax], fi = FACES.indexOf(face);
    const coord = (p[a + ax] + p[bq + ax] + p[c + ax]) / 3, onOuter = Math.abs(coord - (sgn > 0 ? hi[ax] : lo[ax])) < tol;
    void len;
    // a triangle on the bounding box is the outer skin; others take the inner wall temperature of the face they face (inner skin faces inward)
    const T = onOuter ? res.wallOuter[fi] : res.wallInner[FACES.indexOf((sgn > 0 ? '-' : '+') + 'xyz'[ax])] ?? res.air;
    for (const v of [idx[i], idx[i + 1], idx[i + 2]]) { acc[v] += T; cnt[v]++; }
  }
  void Tm;
  const out = new Float32Array(nV);
  for (let v = 0; v < nV; v++) out[v] = (cnt[v] ? acc[v] / cnt[v] : res.air) - KEL;
  return out;
}

const safe = (s) => String(s).replace(/[^\w.-]+/g, '_').slice(0, 40);
const ASSUMPTIONS = [
  'Steady state. One enclosure (all bodies together), treated as a hollow box with the wall thickness implied by its volume and surface area.',
  'One uniform internal air temperature; components and board are single nodes; component-to-board conduction uses the board thickness with 45° spreading (use a datasheet value for real parts).',
  'Air properties near 1 atm. Convection coefficients come from isothermal-plate correlations applied to each face of the box; ribs, curvature, standoffs and gaps are ignored.',
  'Vents act through stack flow only (discharge coefficient 0.6); wind-driven flow and internal fans are not modelled.',
];

async function loadVents() { try { const v = await forge.storage.project.get('vents'); return Array.isArray(v) ? v : []; } catch (_) { return []; } }

if (typeof forge !== 'undefined') {
  forge.analysis.register('thermal-estimate', {
    async run(input, ctx) {
      ctx.progress(0.05);
      const vents = await loadVents(), target = (await (async () => { try { return await forge.storage.project.get('target_C'); } catch (_) { return null; } })());
      const tC = Number.isFinite(target) ? target : 60;
      const r = thermalModel(input, { vents });
      if (ctx.signal.aborted) throw new Error('cancelled');
      ctx.progress(0.5);
      const warnings = [...input.setup.warnings, ...r.warnings];
      const rows = r.summary.slice();
      let need = null;
      if (r.Qtotal > 0) {
        need = ventAreaFor(input, tC);
        rows.push({ metric: 'target_temp', value: tC, unit: '°C' });
        if (need.reachable) rows.push({ metric: 'vent_area_needed', value: need.area_mm2, unit: 'mm²' });
        else warnings.push(`Even vents of ${Math.round(need.maxArea)} mm² (half the wall area) leave the hottest part at ${need.atArea.toFixed(1)} °C, above the ${tC} °C target; vents alone cannot meet it.`);
      }
      rows.push({ metric: 'vent_area_installed', value: vents.reduce((a, v) => a + (v.area_mm2 || 0), 0), unit: 'mm²' });
      if (r.outside) warnings.push('At least one correlation was used outside its tabulated Rayleigh-number range; its coefficient is an extrapolation.');
      warnings.push(...ASSUMPTIONS.map((a) => 'Assumption: ' + a));
      const fields = input.bodies.map((b) => ({ name: `surface_temperature:${b.name}`, unit: '°C', kind: 'surface', body: b.name, data: surfaceField(b, r) }));
      ctx.progress(1);
      return { summary: rows, fields, warnings, validity: 'estimate' };
    },
  });

  // AI tools: ask for the vent area that meets a target, and record vents the user describes
  forge.agent.tool('thermal-estimate', async (args) => {
    const t = Number(args && args.target_C); if (!Number.isFinite(t) || t < -50 || t > 300) throw new Error('target_C must be a temperature in °C between -50 and 300');
    const input = await forge.analysis.input(), vents = await loadVents();
    const now = thermalModel(input, { vents }), need = ventAreaFor(input, t);
    const hot = Math.max(now.air, ...(now.board != null ? [now.board] : []), ...now.comps.map((c) => c.T)) - KEL;
    return { hottest_part_C: +hot.toFixed(1), air_C: +(now.air - KEL).toFixed(1), target_C: t, vent_area_installed_mm2: vents.reduce((a, v) => a + (v.area_mm2 || 0), 0),
      vent_area_needed_mm2: need.reachable ? +need.area_mm2.toFixed(0) : null, reachable: need.reachable,
      note: need.reachable ? 'Needed area is the total of one low and one high opening of equal size, ~80 % of the box height apart. Estimate only (validity: estimate).' : `Vents alone cannot reach the target; the hottest part stays at ${need.atArea.toFixed(1)} °C with very large vents. Reduce power, add a heat path (mount contact) or raise the target.` };
  });
  forge.agent.tool('set-vents', async (args) => {
    const v = (args && args.vents) || []; if (!Array.isArray(v) || v.length > 32) throw new Error('vents must be a list of at most 32 entries');
    const clean = v.map((x) => { if (!(x.area_mm2 >= 0 && x.area_mm2 < 1e6) || !Number.isFinite(x.z_mm)) throw new Error('each vent needs area_mm2 (0 to 1e6) and z_mm (height of its centre in the document frame)'); return { area_mm2: +x.area_mm2, z_mm: +x.z_mm, note: x.note ? String(x.note).slice(0, 80) : undefined }; });
    await forge.storage.project.set('vents', clean);
    return { vents: clean.length, total_area_mm2: clean.reduce((a, x) => a + x.area_mm2, 0) };
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { airProps, rayleigh, nuVertical, nuHorizontalEnhanced, nuHorizontalRestricted, nuForcedPlate, hRadiation, stackFlow, hFace, outerRejection, solveLinear, solveNetwork, thermalModel, ventAreaFor, orientOf });
