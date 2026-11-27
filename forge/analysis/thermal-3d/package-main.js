// Thermal 3D (A2): steady and transient heat conduction on a voxel grid, finite volume, implicit time steps, matrix-free PCG on the
// GPU (WebGPU) or the CPU. Built on the shared voxel core (analysis/voxel-core): this file is concatenated after the core files by
// tools/bundle-ext.mjs into examples/ext/thermal-3d/main.js, so the names of the core are in scope here.
//
// Model. Every voxel of every body is a solid cell with the conductivity, density and specific heat of its body's material;
// PCB parts (components, board) are painted into the grid too; air enclosed by the walls is a cell with an EFFECTIVE conductivity
// (conduction times a natural-convection Nusselt number, plus a linearised-radiation conductivity); outside air is not a cell.
// A face between a solid cell and outside air has a boundary conductance: half a cell of conduction in series with the surface
// film  A (h_conv + h_rad)  where h_conv comes from A1's correlations (copied into the voxel core), h_rad is the linearised grey-body
// coefficient, and both are iterated with the face temperature. Contact mounts replace the film by a conductance to a sink at
// ambient; fixed-temperature faces are Dirichlet. Heat sources are volumetric: a PCB part's watts spread over its voxels, a body's
// watts over its volume. Solved as temperature RISES above ambient so single-precision GPU arithmetic keeps its digits.
const T3D_ID = 'xyz.mdhv.thermal-3d';
const KELVIN = 273.15;
const FACE_LIST = ['+x', '-x', '+y', '-y', '+z', '-z'];
// Assumed properties of PCB parts that the document does not give a material for (said in every result).
const BOARD_PROPS = { k: 0.29, rho: 1850, cp: 1100, eps: 0.9, name: 'PCB (assumed: FR-4 through the thickness, copper planes in the plane)' };
const PART_PROPS = { k: 5, rho: 2300, cp: 800, eps: 0.9, name: 'electronic part (assumed: mould compound and silicon, lumped)' };
const AIR_CP_RHO = 1.16 * 1007;

const DEFAULT_SETTINGS = {
  quality: 'normal', mode: 'steady', t_end_s: 600, dt_s: 0, limit_C: 85, backend: 'auto', voxelizer: 'conservative', h_mm: 0,
  board_k_inplane: 20, convection: 'correlation', h_W_m2K: 10, radiation: true, cavity: 'effective', initial_C: null, fixed: [], maxOuter: 60, outerTol_K: 1e-4,
};

/** Validate the stored settings. Unknown or invalid entries fall back to defaults and say so. */
function parseSettings(raw) {
  const s = JSON.parse(JSON.stringify(DEFAULT_SETTINGS)), warnings = [], r = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const en = (k, opts) => { if (r[k] === undefined) return; if (opts.includes(r[k])) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} is not one of ${opts.join(', ')}; ${s[k]} is used.`); };
  const nu = (k, lo, hi) => { if (r[k] === undefined) return; if (typeof r[k] === 'number' && Number.isFinite(r[k]) && r[k] >= lo && r[k] <= hi) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} must be a number from ${lo} to ${hi}; ${s[k]} is used.`); };
  en('quality', QUALITIES_3D); en('mode', ['steady', 'transient']); en('backend', ['auto', 'gpu', 'cpu']); en('convection', ['correlation', 'fixed']); en('voxelizer', ['conservative', 'centre']); en('cavity', ['effective', 'conduction']);
  nu('t_end_s', 0.001, 1e7); nu('dt_s', 0, 1e6); nu('limit_C', -50, 1500); nu('h_mm', 0, 200); nu('h_W_m2K', 0, 1e5); nu('board_k_inplane', 0.29, 400); nu('maxOuter', 1, 500); nu('outerTol_K', 1e-9, 1);
  if (r.initial_C !== undefined && r.initial_C !== null) { if (typeof r.initial_C === 'number' && r.initial_C > -100 && r.initial_C < 1500) s.initial_C = r.initial_C; else warnings.push('Setting initial_C must be a temperature in °C; the ambient temperature is used.'); }
  if (typeof r.radiation === 'boolean') s.radiation = r.radiation;
  if (r.fixed !== undefined) {
    const out = [];
    for (const f of Array.isArray(r.fixed) ? r.fixed : []) {
      const T = f && (typeof f.T_C === 'number' ? f.T_C + KELVIN : typeof f.T_K === 'number' ? f.T_K : NaN);
      if (f && FACE_LIST.includes(f.face) && Number.isFinite(T) && T > 100 && T < 2000 && (f.box == null || (Array.isArray(f.box) && f.box.length === 6 && f.box.every(Number.isFinite)))) out.push({ face: f.face, body: f.body || null, box: f.box || null, T });
      else warnings.push(`A fixed-temperature entry was ignored (needs face, T_C and optionally body and box): ${JSON.stringify(f).slice(0, 80)}.`);
    }
    s.fixed = out;
  }
  return { settings: s, warnings };
}

// ───────────────────────── the model: owners, grid, boundary faces ─────────────────────────
function bboxOfBodies(bodies) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const b of bodies) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], b.bbox.min[k]); hi[k] = Math.max(hi[k], b.bbox.max[k]); }
  return { lo, hi };
}

/**
 * Voxelize the design and set up every per-cell and per-face array a solve needs.
 * input: the analysis input; settings: parsed settings; ctx: { backendKind: 'cpu'|'gpu', device: 'laptop'|'phone' }.
 */
function buildModel(input, settings, ctx) {
  const S = input.setup, cond = S.conditions, warnings = [], bodies = input.bodies;
  if (!bodies.length) throw new Error('there is no built body to analyse');
  const owners = [null]; // index = material id; owners[0] unused
  const bodyOwner = new Map();
  for (const b of bodies) {
    const sb = S.bodies.find((x) => x.name === b.name), p = sb.material.props;
    if (!(p.k > 0 && p.rho > 0 && p.cp > 0)) throw new Error(`Body "${b.name}": material ${sb.material.name} has no conductivity, density or specific heat`);
    owners.push({ id: owners.length, name: b.name, kind: 'body', k: p.k, rho: p.rho, cp: p.cp, eps: p.emissivity == null ? 0.9 : p.emissivity, material: sb.material.name });
    bodyOwner.set(b.name, owners.length - 1);
    if (owners.length > MAX_OWNERS) throw new Error('too many bodies and parts for one grid');
  }
  const pcb = (S.pcb && S.pcb.components) || [], partOwners = [];
  for (const c of pcb) {
    const role = c.role || 'component';
    if (role !== 'component' && role !== 'board') continue;
    if (!(c.box || c.cyl)) continue;
    if (owners.length > MAX_OWNERS) { warnings.push('Too many PCB parts for one grid; the rest are ignored.'); break; }
    const props = role === 'board' ? BOARD_PROPS : PART_PROPS;
    const o = { id: owners.length, name: c.name, kind: role, k: props.k, rho: props.rho, cp: props.cp, eps: props.eps, material: props.name, box: c.box || null, cyl: c.cyl || null, cylKind: c.kind === 'cylinder' };
    if (role === 'board' && c.box) { const d = [0, 1, 2].map((q) => Math.abs(c.box[2 * q + 1] - c.box[2 * q])), thin = d.indexOf(Math.min(...d)); o.kAxis = [0, 1, 2].map((q) => (q === thin ? props.k : Math.max(props.k, settings.board_k_inplane))); o.material += `, in-plane k ${o.kAxis[(thin + 1) % 3]} W/(m K)`; }
    owners.push(o);
    partOwners.push(owners.length - 1);
  }
  const bb = bboxOfBodies(bodies), size = [0, 1, 2].map((k) => bb.hi[k] - bb.lo[k]);
  const { budget, cls } = gridBudget({ quality: settings.quality, device: ctx.device, backend: ctx.backendKind });
  const meshes = bodies.map((b) => ({ positions: b.positions, indices: b.indices, id: bodyOwner.get(b.name) }));
  const painted = {};
  const paint = (grid, mat) => { for (const id of partOwners) { const o = owners[id]; painted[id] = o.cylKind && o.cyl ? paintCylinder(grid, mat, o.cyl, id) : paintBox(grid, mat, o.box, id); } };
  const vm = buildVoxelModel({ meshes, bbox: bb, paint, conservative: settings.voxelizer !== 'centre', h: settings.h_mm > 0 ? settings.h_mm : null, budget, maxActive: ctx.backendKind === 'gpu' ? GRID_HARD_LIMIT.active : Math.max(GRID_HARD_LIMIT.active, 2500000) });
  const { grid, mat, struct } = vm, n = struct.n, hM = grid.h * 1e-3;
  for (const id of partOwners) { const o = owners[id]; let cnt = 0; for (let v = 0; v < grid.total; v++) if (mat[v] === id) cnt++; o.cells = cnt; }

  // per-cell properties
  const k = new Float64Array(n), cap = new Float64Array(n), eps = new Float32Array(n), own = new Uint8Array(n);
  const kAir = airProps(300).k, aniso = owners.some((o) => o && o.kAxis), kAxis = aniso ? new Float64Array(3 * n) : null;
  let nCav = 0;
  for (let a = 0; a < n; a++) {
    const m = mat[struct.cells[a]]; own[a] = m;
    if (m === CAVITY) { k[a] = kAir; cap[a] = AIR_CP_RHO * hM * hM * hM; eps[a] = 0.9; nCav++; if (kAxis) kAxis.fill(kAir, 3 * a, 3 * a + 3); }
    else { const o = owners[m]; k[a] = o.k; cap[a] = o.rho * o.cp * hM * hM * hM; eps[a] = o.eps; if (kAxis) { const ka = o.kAxis || [o.k, o.k, o.k]; kAxis[3 * a] = ka[0]; kAxis[3 * a + 1] = ka[1]; kAxis[3 * a + 2] = ka[2]; } }
  }
  // heat sources: watts per cell
  const qCell = new Float64Array(n);
  const cellsOf = (id) => { const l = []; for (let a = 0; a < n; a++) if (own[a] === id) l.push(a); return l; };
  let Qin = 0;
  for (const hs of cond.heat || []) {
    let ids = [];
    if (hs.target.pcb != null) ids = partOwners.filter((id) => owners[id].name === hs.target.pcb);
    else if (bodyOwner.has(hs.target.body)) ids = [bodyOwner.get(hs.target.body)];
    const cells = ids.flatMap(cellsOf);
    if (!cells.length) { warnings.push(`Heat source "${hs.id}" (${hs.power_W} W) has no voxels to go into (${hs.target.pcb != null ? `PCB part "${hs.target.pcb}" is not a component or board in the document, or lies outside the model` : `body "${hs.target.body}" has no voxels at this resolution`}); it is ignored.`); continue; }
    for (const a of cells) qCell[a] += hs.power_W / cells.length;
    Qin += hs.power_W;
    if (hs.target.pcb != null && owners[ids[0]].cells < 4) warnings.push(`PCB part "${hs.target.pcb}" is only ${owners[ids[0]].cells} voxel(s) at ${grid.h.toFixed(2)} mm; its temperature is a lumped value for that block.`);
  }
  if (!(Qin > 0)) warnings.push('No heat sources are set (Analyze > Conditions); every temperature equals ambient (or the initial temperature in a transient run).');

  // exposed faces: every face between a solid cell and outside air
  const Tamb = cond.ambient.temperature_K, gravity = cond.ambient.gravity || '-z', wind = cond.ambient.airspeed_ms || 0, gAx = 'xyz'.indexOf(gravity[1]);
  const [nx, ny] = grid.dims, nxy = nx * ny, lo = grid.origin, hMm = grid.h;
  const bodyBox = new Map(bodies.map((b) => [bodyOwner.get(b.name), b.bbox]));
  const mounts = (cond.mounts || []).filter((m) => m.kind === 'contact');
  for (const m of cond.mounts || []) if (m.kind === 'fixed') warnings.push(`Mount "${m.id}" is a fixed (structural) mount; it has no thermal effect here. Use the fixed-temperature setting for a thermal Dirichlet face.`);
  const exCell = [], exFace = [], exKind = [], exParam = [];
  const nearBox = (box, c, tol) => !box || [0, 1, 2].every((q) => c[q] >= Math.min(box[2 * q], box[2 * q + 1]) - tol && c[q] <= Math.max(box[2 * q], box[2 * q + 1]) + tol);
  for (let a = 0; a < n; a++) {
    if (own[a] === CAVITY) continue;
    const v = struct.cells[a];
    for (let f = 0; f < 6; f++) {
      if (struct.nbr[6 * a + f] >= 0) continue;
      const i = v % nx, j = ((v - i) / nx) % ny, kk = (v - i - nx * j) / nxy, ax = f >> 1, plus = (f & 1) === 0, idx3 = [i, j, kk];
      const centre = [lo[0] + (i + 0.5) * hMm, lo[1] + (j + 0.5) * hMm, lo[2] + (kk + 0.5) * hMm];
      const facePos = lo[ax] + (idx3[ax] + (plus ? 1 : 0)) * hMm, fc = centre.slice(); fc[ax] = facePos;
      const owner = owners[own[a]], bx = bodyBox.get(own[a]);
      let kind = 0, param = 0;
      const onBodyFace = (name) => owner.name === name && owner.kind === 'body' && (plus ? facePos >= bx.max[ax] - 0.51 * hMm : facePos <= bx.min[ax] + 0.51 * hMm);
      const fx = settings.fixed.find((q) => q.face === FACE_LIST[f] && (!q.body || q.body === owner.name) && nearBox(q.box, fc, 0.51 * hMm));
      if (fx) { kind = 2; param = fx.T; }
      else {
        const mt = mounts.find((q) => q.face === FACE_LIST[f] && onBodyFace(q.body) && nearBox(q.box, fc, 0.51 * hMm));
        if (mt) { kind = 1; param = mt.conductance_W_m2K; }
      }
      exCell.push(a); exFace.push(f); exKind.push(kind); exParam.push(param);
    }
  }
  const m = exCell.length;
  // area correction: the voxel surface of a curved or tilted body is larger (staircase) than the mesh surface. The film conductance
  // of each body's exposed faces is scaled, per axis, so the voxel faces normal to an axis carry exactly the share of the true mesh
  // area that belongs to that axis (each outward-facing triangle gives A n_axis^2 to its axis; the shares sum to the true area).
  const vox = new Float64Array(owners.length * 3), meshA = new Float64Array(owners.length * 3);
  for (let q = 0; q < m; q++) vox[own[exCell[q]] * 3 + (exFace[q] >> 1)] += hM * hM * 1e6; // mm^2
  for (const b of bodies) {
    const id = bodyOwner.get(b.name), p = b.positions, t = b.indices;
    for (let q = 0; q < t.length; q += 3) {
      const A = t[q] * 3, B = t[q + 1] * 3, C = t[q + 2] * 3;
      const ux = p[B] - p[A], uy = p[B + 1] - p[A + 1], uz = p[B + 2] - p[A + 2], vx = p[C] - p[A], vy = p[C + 1] - p[A + 1], vz = p[C + 2] - p[A + 2];
      const nxv = uy * vz - uz * vy, nyv = uz * vx - ux * vz, nzv = ux * vy - uy * vx, len = Math.hypot(nxv, nyv, nzv); if (len < 1e-12) continue;
      // which air does this triangle face? walk outward until the first cell that is not solid: outside air (0) or enclosed air (CAVITY)
      let outside = false;
      for (const step of [0.55, 1.05, 1.55, 2.05]) {
        const ci = Math.floor(((p[A] + p[B] + p[C]) / 3 + nxv / len * step * hMm - lo[0]) / hMm), cj = Math.floor(((p[A + 1] + p[B + 1] + p[C + 1]) / 3 + nyv / len * step * hMm - lo[1]) / hMm), ck = Math.floor(((p[A + 2] + p[B + 2] + p[C + 2]) / 3 + nzv / len * step * hMm - lo[2]) / hMm);
        if (ci < 0 || cj < 0 || ck < 0 || ci >= grid.dims[0] || cj >= grid.dims[1] || ck >= grid.dims[2]) { outside = true; break; }
        const v = mat[ci + nx * (cj + ny * ck)];
        if (v === 0) { outside = true; break; } if (v === CAVITY) break;
      }
      if (outside) { const area = len / 2, nn = [nxv / len, nyv / len, nzv / len]; for (let ax = 0; ax < 3; ax++) meshA[id * 3 + ax] += area * nn[ax] * nn[ax]; }
    }
  }
  const areaF = new Float64Array(owners.length * 3).fill(1);
  for (let i = 0; i < areaF.length; i++) if (vox[i] > 0 && meshA[i] > 0) areaF[i] = Math.min(1.5, Math.max(0.3, meshA[i] / vox[i]));
  // characteristic lengths of the whole model (as in A1): the height along gravity for side faces, A/P of the plan for horizontal faces
  const oAx = [0, 1, 2].filter((q) => q !== gAx), Lside = Math.max(size[gAx], hMm) * 1e-3, Lhoriz = Math.max((size[oAx[0]] * size[oAx[1]]) / (2 * (size[oAx[0]] + size[oAx[1]])), hMm) * 1e-3;
  const exOrient = new Uint8Array(m); // 0 side, 1 up, 2 down
  for (let q = 0; q < m; q++) { const o = orientOf(FACE_LIST[exFace[q]], gravity); exOrient[q] = o === 'side' ? 0 : o === 'up' ? 1 : 2; }
  // cavity bookkeeping
  const cav = { cells: nCav, V: nCav * hM ** 3, A: 0, internal: [], wall: [] };
  if (nCav) {
    const seenI = new Uint8Array(n);
    for (let a = 0; a < n; a++) {
      if (own[a] !== CAVITY) continue;
      for (let f = 0; f < 6; f++) { const j = struct.nbr[6 * a + f]; if (j >= 0 && own[j] !== CAVITY) { cav.A += hM * hM; if (!seenI[j]) { seenI[j] = 1; (owners[own[j]].kind === 'body' ? cav.wall : cav.internal).push(j); } } }
    }
  }
  let volVox = 0; for (let a = 0; a < n; a++) if (own[a] && own[a] <= bodies.length) volVox++;
  volVox *= grid.h ** 3; const volMesh = bodies.reduce((x, b) => x + b.volume_mm3, 0);
  return { owners, bodyOwner, partOwners, grid, mat, struct, n, hM, k, kAxis, cap, eps, own, qCell, Qin, Tamb, gravity, wind, exCell: Int32Array.from(exCell), exFace: Uint8Array.from(exFace), exKind: Uint8Array.from(exKind), exParam: Float64Array.from(exParam), exOrient, m, areaF, Lside, Lhoriz, cav, nCav, budget, budgetClass: cls, bb, size, volVox, volMesh, warnings, stats: vm.stats, bodies };
}

// ───────────────────────── physics assembly ─────────────────────────
/** Convection coefficient tables for the three orientations over a range of face temperatures (K), so a million faces need no pow(). */
function hTables(model, settings) {
  const { Tamb, wind, Lside, Lhoriz } = model, lo = Tamb - 80, step = 0.5, N = 800, tabs = [];
  if (settings.convection === 'fixed') return { fixed: settings.h_W_m2K };
  let outside = false;
  for (const orient of ['side', 'up', 'down']) {
    const t = new Float64Array(N + 1);
    for (let i = 0; i <= N; i++) { const Ts = lo + i * step, r = hFace({ orient, hotter: Ts > Tamb, L: orient === 'side' ? Lside : Lhoriz, Ts, Tf: Tamb, airspeed: wind }); t[i] = r.h; if (r.outside && Math.abs(Ts - Tamb) > 2) outside = true; }
    tabs.push(t);
  }
  return { tabs, lo, step, N, outside };
}
const lookupH = (T, Ts, orient) => { if (T.fixed !== undefined) return T.fixed; const x = (Ts - T.lo) / T.step, i = Math.max(0, Math.min(T.N - 1, Math.floor(x))), f = Math.min(1, Math.max(0, x - i)), t = T.tabs[orient]; return t[i] * (1 - f) + t[i + 1] * f; };

/**
 * Boundary conductances of every exposed face at the cell temperatures T (K), and the cavity conductivity. Fills bdiag[a] (W/K),
 * brhs[a] (W K, relative to `Tref`) and returns the bookkeeping the energy accounting and the surface temperatures need.
 */
function assembleBoundary(model, settings, T, Tref, tabs, bd) {
  const { m, exCell, exFace, exKind, exParam, exOrient, own, k, eps, Tamb, hM, areaF } = model;
  bd.diag.fill(0); bd.rhs.fill(0);
  const A0 = hM * hM;
  for (let q = 0; q < m; q++) {
    const a = exCell[q], Tc = T[a], Rs = 1 / (2 * k[a] * hM), f = areaF[own[a] * 3 + (exFace[q] >> 1)];
    let G = 0, hc = 0, hr = 0, Tb = Tamb;
    if (exKind[q] === 2) { G = 1 / Rs; Tb = exParam[q]; }
    else if (exKind[q] === 1) { hc = exParam[q]; G = hc > 0 ? 1 / (Rs + 1 / (f * A0 * hc)) : 0; }
    else {
      // film coefficient at the face temperature, found from the cell temperature by one fixed-point step of the series circuit
      let Ts = Tc;
      for (let it = 0; it < 2; it++) {
        hc = lookupH(tabs, Ts, exOrient[q]); hr = settings.radiation ? hRadiation(eps[a], Ts, Tamb) : 0;
        const Gt = 1 / (Rs + 1 / (f * A0 * (hc + hr))); Ts = Tamb + Gt * (Tc - Tamb) / (f * A0 * (hc + hr));
      }
      G = 1 / (Rs + 1 / (f * A0 * (hc + hr)));
    }
    bd.G[q] = G; bd.hc[q] = hc; bd.hr[q] = hr; bd.Tb[q] = Tb;
    bd.diag[a] += G; bd.rhs[a] += G * (Tb - Tref);
  }
}

/** Effective conductivity of the enclosed air (see the header). Returns { k, Nu, Ra, dT, L, eps }. */
function cavityState(model, settings, T) {
  const { cav, own, owners, nCav } = model;
  if (!nCav) return null;
  const Lc = Math.max(4 * cav.V / Math.max(cav.A, 1e-12), 2 * model.hM);
  let Th = -Infinity, Tw = 0, nw = 0, ew = 0, ei = 0, ni = 0;
  const hotList = cav.internal.length ? cav.internal : cav.wall;
  for (const j of hotList) Th = Math.max(Th, T[j]);
  for (const j of cav.wall) { Tw += T[j]; nw++; ew += owners[own[j]].eps; }
  for (const j of cav.internal) { ei += owners[own[j]].eps; ni++; }
  Tw = nw ? Tw / nw : model.Tamb; const epsW = nw ? ew / nw : 0.9, epsI = ni ? ei / ni : epsW;
  const dT = Math.max(Math.abs(Th - Tw), 0.01), Tm = (Th + Tw) / 2, p = airProps(Tm), Ra = rayleigh(dT, Lc, p);
  const Nu = settings.cavity === 'effective' ? Math.max(1, 0.2 * Math.pow(Ra, 0.25)) : 1;
  const epsEff = 1 / (1 / epsI + 1 / epsW - 1);
  const kRad = settings.cavity === 'effective' ? 4 * SIGMA * Tm ** 3 * epsEff * Lc : 0;
  return { k: p.k * Nu + kRad, Nu, Ra, dT, L: Lc, eps: epsEff, kRad, kAir: p.k };
}

// ───────────────────────── the solve (a generator: sync for tests and the harness, async in the Worker) ─────────────────────────
function* simulateGen(model, settings, op, tol) {
  const { n, struct, hM, k, cap, qCell, Tamb, own, m } = model;
  const Tref = Tamb, tabs = hTables(model, settings);
  const bd = { diag: new Float64Array(n), rhs: new Float64Array(n), G: new Float64Array(m), hc: new Float64Array(m), hr: new Float64Array(m), Tb: new Float64Array(m) };
  const T = new Float64Array(n), th = new Float64Array(n), b = new Float64Array(n), diag = new Float64Array(n), g = new Float64Array(6 * n);
  const T0 = settings.mode === 'transient' && settings.initial_C != null ? settings.initial_C + KELVIN : Tamb;
  for (let a = 0; a < n; a++) { th[a] = T0 - Tref; T[a] = T0; }
  let cav = null, iters = 0, solves = 0, kCavLast = 0;
  const setConductance = () => {
    if (model.nCav) { cav = cavityState(model, settings, T); if (cav && Math.abs(cav.k - kCavLast) > 1e-9) { for (let a = 0; a < n; a++) if (own[a] === CAVITY) { k[a] = cav.k; if (model.kAxis) model.kAxis.fill(cav.k, 3 * a, 3 * a + 3); } kCavLast = cav.k; faceConductances(struct, k, hM, g, model.kAxis); op.setConductance(g); return; } }
    if (!solves) { faceConductances(struct, k, hM, g, model.kAxis); op.setConductance(g); }
  };
  const gsum = () => { for (let a = 0; a < n; a++) { let s = 0; for (let f = 0; f < 6; f++) s += g[6 * a + f]; diag[a] = s; } };
  const toT = () => { for (let a = 0; a < n; a++) T[a] = th[a] + Tref; };
  let stepsDone = 0, converged = true, outerUsed = 0, maxStep = 0;
  const hist = { peakK: -Infinity, timeToLimit: null, t: 0 };
  const limitK = settings.limit_C + KELVIN;
  const track = (t) => {
    let mx = -Infinity; for (let a = 0; a < n; a++) if (own[a] !== CAVITY && T[a] > mx) mx = T[a];
    if (mx > hist.peakK) hist.peakK = mx;
    return mx;
  };
  const accounting = (dtInv) => {
    // heat leaving through every exposed face with the coefficients in `bd`, and the heat stored per second
    const q = { conv: 0, rad: 0, contact: 0, fixed: 0 };
    for (let e = 0; e < m; e++) {
      const a = model.exCell[e], Q = bd.G[e] * (T[a] - bd.Tb[e]);
      if (model.exKind[e] === 2) q.fixed += Q; else if (model.exKind[e] === 1) q.contact += Q;
      else { const tot = bd.hc[e] + bd.hr[e]; q.conv += tot > 0 ? Q * bd.hc[e] / tot : 0; q.rad += tot > 0 ? Q * bd.hr[e] / tot : 0; }
    }
    return q;
  };
  if (settings.mode === 'steady') {
    const linear = settings.convection === 'fixed' && !settings.radiation && !model.nCav, omega = linear ? 1 : 0.8, D = new Float64Array(n); let floor = settings.outerTol_K;
    for (let outer = 1; outer <= settings.maxOuter; outer++) {
      toT(); setConductance(); gsum();
      assembleBoundary(model, settings, T, Tref, tabs, bd);
      if (outer === 1) { let gs = 0; for (let e = 0; e < m; e++) gs += bd.G[e]; if (!(gs > 0)) throw new Error('there is no heat path to ambient: every outer face is insulated (zero-conductance mounts or zero convection and radiation)'); }
      for (let a = 0; a < n; a++) { b[a] = qCell[a] + bd.rhs[a]; D[a] = diag[a] + bd.diag[a]; }
      op.setDiag(D);
      const sol = yield { t: 'solve', b, x0: th, tol, maxIter: 20000 };
      iters += sol.iters; solves++; if (!sol.converged) converged = false;
      let d = 0, big = 0; for (let a = 0; a < n; a++) { const nw = th[a] + omega * (sol.x[a] - th[a]); d = Math.max(d, Math.abs(nw - th[a])); th[a] = nw; big = Math.max(big, Math.abs(nw)); }
      outerUsed = outer; maxStep = d; floor = Math.max(settings.outerTol_K, 5 * tol * big);
      yield { t: 'progress', v: 0.12 + 0.8 * (1 - Math.exp(-outer / 6)) };
      if (d < floor) break;
      if (outer === settings.maxOuter) converged = false;
    }
    toT(); setConductance(); gsum(); assembleBoundary(model, settings, T, Tref, tabs, bd);
    track(0);
    const q = accounting(0);
    return { T, th, q, iters, solves, outer: outerUsed, converged: converged && maxStep < floor * 5, lastChange_K: maxStep, cav, steps: 0, dt: 0, hist, bd, stored: 0, balance: Math.abs(model.Qin - (q.conv + q.rad + q.contact + q.fixed)), tabs };
  }
  // transient: implicit Euler with coefficients lagged one step
  const dt = settings.dt_s > 0 ? settings.dt_s : settings.t_end_s / 150, steps = Math.min(20000, Math.max(1, Math.ceil(settings.t_end_s / dt - 1e-9))), dtU = settings.t_end_s / steps;
  let t = 0, energyIn = 0, energyOut = 0, lastBalance = 0;
  toT(); track(0);
  if (T0 >= limitK && settings.initial_C != null) hist.timeToLimit = 0;
  for (let s = 1; s <= steps; s++) {
    const Told = Float64Array.from(T), thOld = Float64Array.from(th);
    setConductance(); gsum();
    assembleBoundary(model, settings, T, Tref, tabs, bd);
    const D = new Float64Array(n);
    for (let a = 0; a < n; a++) { const c = cap[a] / dtU; D[a] = diag[a] + bd.diag[a] + c; b[a] = qCell[a] + bd.rhs[a] + c * thOld[a]; }
    op.setDiag(D);
    const sol = yield { t: 'solve', b, x0: th, tol, maxIter: 20000 };
    iters += sol.iters; solves++; if (!sol.converged) converged = false;
    for (let a = 0; a < n; a++) th[a] = sol.x[a];
    toT(); t += dtU; stepsDone = s;
    // accounting with the coefficients this step used, at the new cell temperatures (implicit Euler: exact balance)
    const q = accounting(0), Qout = q.conv + q.rad + q.contact + q.fixed;
    let stored = 0; for (let a = 0; a < n; a++) stored += cap[a] * (T[a] - Told[a]) / dtU;
    energyIn += model.Qin * dtU; energyOut += Qout * dtU; lastBalance = Math.abs(model.Qin - Qout - stored);
    const mxNew = track(t), mxOld = (() => { let mx = -Infinity; for (let a = 0; a < n; a++) if (own[a] !== CAVITY && Told[a] > mx) mx = Told[a]; return mx; })();
    if (hist.timeToLimit === null && mxNew >= limitK) hist.timeToLimit = (t - dtU) + dtU * (limitK - mxOld) / Math.max(mxNew - mxOld, 1e-12);
    hist.t = t; hist.last = { q, stored, Qout };
    if (s % 5 === 0 || s === steps) yield { t: 'progress', v: 0.12 + 0.85 * s / steps };
  }
  const q = hist.last.q;
  return { T, th, q, iters, solves, outer: 0, converged, lastChange_K: 0, cav, steps: stepsDone, dt: dtU, hist, bd, stored: hist.last.stored, balance: lastBalance, tabs, energy: { in: energyIn, out: energyOut } };
}

function driveSync(gen, op) {
  let s = gen.next();
  while (!s.done) { const c = s.value; s = gen.next(c.t === 'solve' ? op.solveSync(c.b, c.x0, { tol: c.tol, maxIter: c.maxIter }) : undefined); }
  return s.value;
}
async function driveAsync(gen, op, { signal, progress, onStep } = {}) {
  let s = gen.next();
  while (!s.done) {
    if (signal && signal.aborted) throw new Error('cancelled');
    const c = s.value; let back;
    if (c.t === 'solve') back = await op.solve(c.b, c.x0, { tol: c.tol, maxIter: c.maxIter, signal, onStep });
    else { if (progress) progress(c.v); await new Promise((r) => setTimeout(r, 0)); }
    s = gen.next(back);
  }
  return s.value;
}

// ───────────────────────── results ─────────────────────────
function cellAt(model, x, y, z) {
  const { grid, struct } = model, i = Math.floor((x - grid.origin[0]) / grid.h), j = Math.floor((y - grid.origin[1]) / grid.h), kk = Math.floor((z - grid.origin[2]) / grid.h);
  if (i < 0 || j < 0 || kk < 0 || i >= grid.dims[0] || j >= grid.dims[1] || kk >= grid.dims[2]) return -1;
  return struct.cellOf[i + grid.dims[0] * (j + grid.dims[1] * kk)];
}

/** Surface and per-face summary quantities from a finished simulation. */
function summarise(model, sim, settings) {
  const { n, own, owners, m, exCell, exKind, exParam, hM, k, areaF, Tamb, struct, grid } = model, T = sim.T;
  let maxT = -Infinity, maxA = -1, maxC = -Infinity, maxCa = -1, minT = Infinity, maxS = -Infinity, maxSa = -1;
  const compCells = model.partOwners.some((id) => owners[id].kind === 'component');
  for (let a = 0; a < n; a++) {
    if (own[a] === CAVITY) continue;
    if (T[a] > maxT) { maxT = T[a]; maxA = a; } if (T[a] < minT) minT = T[a];
    const ok = compCells ? owners[own[a]].kind === 'component' : true;
    if (ok && T[a] > maxC) { maxC = T[a]; maxCa = a; }
  }
  for (let e = 0; e < m; e++) {
    const a = exCell[e], Rs = 1 / (2 * k[a] * hM); let Ts;
    if (exKind[e] === 2) Ts = exParam[e]; else Ts = T[a] - sim.bd.G[e] * (T[a] - sim.bd.Tb[e]) * Rs;
    if (Ts > maxS) { maxS = Ts; maxSa = a; }
  }
  const where = (a) => cellCentre(grid, struct.cells[a]);
  return { maxT, maxA, maxC, maxCa, minT, maxS, maxSa, where, areaF, compCells };
}

/** Trilinear sample (over active cells) of the cell temperatures at a point (mm); falls back to the nearest active cell, then to `fallback`. */
function sampleCells(model, T, x, y, z, fallback) {
  const { grid, struct } = model, h = grid.h, [nx, ny, nz] = grid.dims;
  const fx = (x - grid.origin[0]) / h - 0.5, fy = (y - grid.origin[1]) / h - 0.5, fz = (z - grid.origin[2]) / h - 0.5;
  const i0 = Math.floor(fx), j0 = Math.floor(fy), k0 = Math.floor(fz), tx = fx - i0, ty = fy - j0, tz = fz - k0;
  let acc = 0, wsum = 0;
  for (let dk = 0; dk < 2; dk++) for (let dj = 0; dj < 2; dj++) for (let di = 0; di < 2; di++) {
    const i = i0 + di, j = j0 + dj, kk = k0 + dk; if (i < 0 || j < 0 || kk < 0 || i >= nx || j >= ny || kk >= nz) continue;
    const a = struct.cellOf[i + nx * (j + ny * kk)]; if (a < 0) continue;
    const w = (di ? tx : 1 - tx) * (dj ? ty : 1 - ty) * (dk ? tz : 1 - tz); acc += w * T[a]; wsum += w;
  }
  if (wsum > 1e-9) return acc / wsum;
  const ci = Math.min(nx - 1, Math.max(0, Math.round(fx))), cj = Math.min(ny - 1, Math.max(0, Math.round(fy))), ck = Math.min(nz - 1, Math.max(0, Math.round(fz)));
  for (let r = 1; r <= 2; r++) for (let dk = -r; dk <= r; dk++) for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) {
    const i = ci + di, j = cj + dj, kk = ck + dk; if (i < 0 || j < 0 || kk < 0 || i >= nx || j >= ny || kk >= nz) continue;
    const a = struct.cellOf[i + nx * (j + ny * kk)]; if (a >= 0) return T[a];
  }
  return fallback;
}

// ───────────────────────── entry points ─────────────────────────
const memo = new WeakMap();
/** Settings of this package stored in the project (ext[<id>].settings), merged under `override`. */
function settingsOf(input, override) {
  const stored = input && input.project && input.project.ext && input.project.ext[T3D_ID] && input.project.ext[T3D_ID].settings;
  return parseSettings({ ...(stored || {}), ...(override || {}) });
}

/** Synchronous CPU run for tests and the validation harness. Returns the simulation with probes: { q, tAt(x,y,z) K, maxTK, ... }. */
function simulateSync(input, override) {
  const key = JSON.stringify(override || {});
  let cache = memo.get(input); if (!cache) { cache = new Map(); memo.set(input, cache); }
  if (cache.has(key)) return cache.get(key);
  const { settings, warnings } = settingsOf(input, override);
  const model = buildModel(input, settings, { backendKind: 'cpu', device: 'laptop' });
  const op = cpuOperatorSync(model);
  const sim = driveSync(simulateGen(model, settings, op, 1e-10), op);
  const sm = summarise(model, sim, settings);
  const out = { model, sim, settings, warnings, q: sim.q, qIn: model.Qin, maxTK: sm.maxT, minTK: sm.minT, maxCK: sm.maxC, maxSK: sm.maxS, iters: sim.iters, outer: sim.outer, converged: sim.converged, balance: sim.balance, h: model.grid.h, active: model.n,
    timeToLimit: sim.hist.timeToLimit, peakTK: sim.hist.peakK,
    tAt: (x, y, z) => { const a = cellAt(model, x, y, z); return a < 0 ? NaN : sim.T[a]; },
    meanTK: (() => { let s = 0, c = 0; for (let a = 0; a < model.n; a++) if (model.own[a] !== CAVITY) { s += sim.T[a]; c++; } return s / c; })() };
  cache.set(key, out);
  return out;
}
function cpuOperatorSync(model) {
  // a synchronous CPU operator (cpuBackend.createOperator is async because the GPU one is)
  const { n, struct } = model, op = { n, nbr: struct.nbr, g: new Float64Array(6 * n), diag: new Float64Array(n) };
  return { setDiag(d) { op.diag.set(d); }, setConductance(gg) { op.g.set(gg); }, solveSync(b, x0, o) { const x = Float64Array.from(x0); const r = runSync(pcgSteps(op, b, x, o)); return { x, ...r }; } };
}

/** Single numbers for the validation harness (a case calls a function and compares what it returns). */
const probeT = (input, override, x, y, z) => simulateSync(input, override).tAt(x, y, z);
const probeOf = (input, override, key) => { const v = simulateSync(input, override)[key]; return v === null || v === undefined ? NaN : v; };

const ASSUMPTIONS = [
  'Heat conduction on a voxel grid of cubic cells (finite volume, cell-centred, harmonic-mean face conductivity). Steady runs iterate the nonlinear boundary conditions; transient runs use implicit Euler, which is first order in time.',
  'The voxelizer is conservative: every voxel a surface passes through is solid, so walls thinner than a voxel are kept but thicken by up to one voxel; the volume ratio to the mesh is reported.',
  'Outside air is not modelled. Each exposed face has half a cell of conduction in series with a film: natural (and, with an air speed, mixed) convection from the plate correlations of A1 (Churchill and Chu; horizontal plates), using the model height or plan length as characteristic length, plus linearised grey-body radiation to ambient. Self-radiation between outer surfaces is ignored. The film area is scaled per axis so a curved body keeps its true surface area.',
  'Air enclosed by the walls is one conducting medium with an effective conductivity: conduction times a Nusselt number of 0.2 Ra^(1/4) (not below 1) plus a linearised radiation conductivity for the gap length 4V/A. This is a coarse stand-in for internal convection and radiation, not a flow solution.',
  'PCB parts are painted into the grid: the board is anisotropic (FR-4, 0.29 W/(m K), through its thickness; in the plane the setting board_k_inplane, default 20 W/(m K), as for a multilayer board with copper planes; no individual traces or vias), components are a lumped block with k = 5 W/(m K). Use the heat-source power as the junction power; junction-to-case resistances are not modelled. The hottest component temperature depends strongly on the board model (an FR-4-only board runs far hotter).',
  'Heat sources are volumetric and uniform over the part or body they target.',
];

/** The analysis run (in the Worker). */
async function runThermal3D(input, ctx) {
  const t0 = Date.now(), { settings, warnings: sw } = settingsOf(input);
  const warnings = [...sw];
  ctx.progress(0.02);
  // backend: WebGPU first (when allowed and the grid is big enough to be worth it), otherwise the CPU
  let backend = cpuBackend, gpuNote = null;
  const wantGpu = settings.backend !== 'cpu';
  let gpu = null;
  if (wantGpu) { gpu = await gpuBackend(); if (!gpu) gpuNote = `WebGPU is not available${gpuBackend.lastError ? ` (${gpuBackend.lastError})` : ''}; the CPU solver is used with a smaller grid.`; }
  const device = typeof navigator !== 'undefined' ? deviceClass(navigator) : 'laptop';
  let model = buildModel(input, settings, { backendKind: gpu ? 'gpu' : 'cpu', device });
  if (gpu && settings.backend === 'auto' && model.n < 20000) { gpu = null; model = buildModel(input, settings, { backendKind: 'cpu', device }); }
  if (gpu) backend = gpu;
  if (gpuNote) warnings.push(gpuNote);
  if (ctx.signal.aborted) throw new Error('cancelled');
  ctx.progress(0.1);
  const tVox = (Date.now() - t0) / 1000;
  const mkOp = () => backend.createOperator({ n: model.n, nbr: model.struct.nbr, g: new Float64Array(6 * model.n), diag: new Float64Array(model.n).fill(1) });
  let op;
  try { op = await mkOp(); } catch (e) {
    if (!gpu) throw e;
    // the device refused the buffers (too large for this GPU): fall back to the CPU with the smaller CPU grid and say so
    warnings.push(`WebGPU could not allocate this grid (${String((e && e.message) || e).slice(0, 120)}); the CPU solver is used with a smaller grid.`);
    gpu = null; backend = cpuBackend; model = buildModel(input, settings, { backendKind: 'cpu', device }); op = await mkOp();
  }
  const t1 = Date.now();
  let sim;
  try { sim = await driveAsync(simulateGen(model, settings, op, backend.kind === 'gpu' ? 1e-5 : 1e-8), op, { signal: ctx.signal, progress: ctx.progress }); } finally { op.destroy(); }
  const tSolve = (Date.now() - t1) / 1000;
  const sm = summarise(model, sim, settings), { grid, owners } = model, C = (x) => x - KELVIN;
  const q = sim.q, Qout = q.conv + q.rad + q.contact + q.fixed;
  const rows = [
    { metric: 'max_temp', value: C(settings.mode === 'transient' ? sim.hist.peakK : sm.maxT), unit: '°C', where: sm.where(sm.maxA) },
    { metric: 'max_component_temp', value: C(sm.maxC), unit: '°C', where: sm.where(sm.maxCa) },
    { metric: 'max_outer_surface_temp', value: C(sm.maxS), unit: '°C', where: sm.maxSa >= 0 ? sm.where(sm.maxSa) : undefined },
  ];
  if (settings.mode === 'transient') {
    rows.push({ metric: 'end_max_temp', value: C(sm.maxT), unit: '°C', where: sm.where(sm.maxA) });
    rows.push({ metric: 'limit_reached', value: sim.hist.timeToLimit === null ? 0 : 1, unit: '' });
    if (sim.hist.timeToLimit !== null) rows.push({ metric: 'time_to_limit', value: sim.hist.timeToLimit, unit: 's' });
    rows.push({ metric: 'run_time', value: settings.t_end_s, unit: 's' });
  }
  rows.push(
    { metric: 'total_heat', value: model.Qin, unit: 'W' },
    { metric: 'heat_out_convection', value: q.conv, unit: 'W' }, { metric: 'heat_out_radiation', value: q.rad, unit: 'W' },
    { metric: 'heat_out_contact', value: q.contact, unit: 'W' }, { metric: 'heat_out_fixed_temp', value: q.fixed, unit: 'W' },
    { metric: 'energy_balance_error', value: sim.balance, unit: 'W' },
    { metric: 'voxel_size', value: grid.h, unit: 'mm' }, { metric: 'active_voxels', value: model.n, unit: '' }, { metric: 'voxel_budget', value: model.budget, unit: '' },
    { metric: 'solver_iterations', value: sim.iters, unit: '' }, { metric: 'solve_seconds', value: +(tSolve).toFixed(3), unit: 's' });
  void Qout;
  // fields: voxel temperature (outside air has no value) and a per-vertex surface temperature on each body
  const total = grid.total, vox = new Float32Array(total).fill(NaN);
  for (let a = 0; a < model.n; a++) vox[model.struct.cells[a]] = C(sim.T[a]);
  const gridInfo = { origin: grid.origin, h: grid.h, dims: grid.dims, active: model.n, budget: model.budget };
  const fields = [{ name: 'temperature', unit: '°C', kind: 'voxel', data: vox, grid: gridInfo }];
  for (const b of input.bodies) {
    const pos = b.positions, nv = pos.length / 3, d = new Float32Array(nv);
    for (let i = 0; i < nv; i++) d[i] = C(sampleCells(model, sim.T, pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], model.Tamb));
    fields.push({ name: `surface_temperature:${b.name}`, unit: '°C', kind: 'surface', body: b.name, data: d });
  }
  // honest statements
  const gridLine = `Grid: ${grid.dims.join('×')} voxels of ${grid.h.toFixed(3)} mm, ${model.n.toLocaleString('en')} active (budget ${model.budget.toLocaleString('en')} for ${settings.quality} on ${gpu ? (device === 'phone' ? 'a phone GPU' : 'a laptop GPU') : settings.backend === 'cpu' ? 'the CPU' : 'the CPU fallback'}; hard limit ${GRID_HARD_LIMIT.active.toLocaleString('en')} active, ${GRID_HARD_LIMIT.total.toLocaleString('en')} cells).`;
  warnings.push(gridLine);
  warnings.push(`Solver: ${backend.label}, ${sim.iters.toLocaleString('en')} CG iterations in ${sim.solves} solve(s); voxelising ${tVox.toFixed(1)} s, solving ${tSolve.toFixed(1)} s.`);
  const ratio = model.volVox / Math.max(model.volMesh, 1e-9);
  warnings.push(`Voxelised solid volume is ${(100 * ratio).toFixed(0)} % of the mesh volume${ratio > 1.15 ? ' (thin walls are thickened by the conservative voxelizer; heat paths and thermal mass are overstated, use fine or a smaller voxel size)' : ratio < 0.85 ? ' (the mesh is not closed or parts are thinner than a voxel)' : ''}.`);
  warnings.push(...model.warnings);
  if (model.nCav && sim.cav) warnings.push(`Enclosed air: ${model.nCav.toLocaleString('en')} voxels with effective conductivity ${sim.cav.k.toFixed(3)} W/(m K) (Ra ${sim.cav.Ra.toExponential(1)}, Nu ${sim.cav.Nu.toFixed(2)}, radiation ${sim.cav.kRad.toFixed(3)}, gap ${(sim.cav.L * 1e3).toFixed(1)} mm).`);
  if (!sim.converged) warnings.push(`The solve did not fully converge (last outer change ${sim.lastChange_K.toExponential(1)} K); treat the numbers as approximate.`);
  if (sim.tabs && sim.tabs.outside) warnings.push('A convection correlation was used outside its tabulated Rayleigh-number range; its coefficient is an extrapolation.');
  const hot = C(settings.mode === 'transient' ? sim.hist.peakK : sm.maxT);
  if (hot > 150) warnings.push(`The hottest point is ${hot.toFixed(0)} °C, above what the plastics and parts of a hand-held device tolerate.`);
  if (settings.mode === 'transient') {
    warnings.push(`Transient: ${sim.steps} implicit-Euler steps of ${sim.dt.toFixed(3)} s to ${settings.t_end_s} s${settings.initial_C != null ? `, starting at ${settings.initial_C} °C` : ', starting at ambient'}; limit ${settings.limit_C} °C ${sim.hist.timeToLimit === null ? 'not reached' : `reached at ${sim.hist.timeToLimit.toFixed(1)} s`}.`);
    warnings.push('Transient boundary coefficients are lagged one step; the max temperature rows report the peak over the run, end_max_temp the final state.');
  }
  for (const o of owners.slice(1)) if (o.kind !== 'body') warnings.push(`Part "${o.name}" (${o.kind}): ${o.material}.`);
  warnings.push('Validity "estimate": the analytic cases pass (analysis-validation/cases/thermal-3d.json) but the 3D enclosure reference from the Alloy Runner (Elmer) does not exist yet, so the result is not claimed as validated.');
  warnings.push(...ASSUMPTIONS.map((a) => 'Assumption: ' + a));
  return { summary: rows, fields, warnings, validity: 'estimate' };
}

async function loadSettings() { try { const v = await forge.storage.project.get('settings'); return v && typeof v === 'object' ? v : {}; } catch (_) { return {}; } }

if (typeof forge !== 'undefined') {
  forge.analysis.register('thermal-3d', { run: (input, ctx) => runThermal3D(input, ctx) });
  forge.agent.tool('set-thermal-3d', async (args) => {
    const cur = await loadSettings(), next = { ...cur, ...(args && typeof args === 'object' ? args : {}) };
    const { settings, warnings } = parseSettings(next);
    if (warnings.length) throw new Error(warnings.join(' '));
    await forge.storage.project.set('settings', next);
    return { saved: next, effective: settings };
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { probeT, probeOf, parseSettings, buildModel, simulateGen, simulateSync, summarise, cellAt, sampleCells, cavityState, hTables, lookupH, settingsOf, runThermal3D, driveSync, driveAsync, cpuOperatorSync, airProps, hFace, hRadiation, orientOf, rayleigh, nuVertical, nuHorizontalEnhanced, nuHorizontalRestricted, nuForcedPlate, gridBudget, buildVoxelModel, pcgSteps, runSync, stencilApply, wgslSource, KERNELS, GRID_BUDGETS });
