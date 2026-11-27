// Lattice Boltzmann core (A5, airflow): D3Q19 BGK with Smagorinsky large-eddy closure, Guo body force, halfway bounce-back walls
// (also moving walls), non-equilibrium-extrapolation velocity and pressure boundary cells, optional periodic axes, and an optional
// D3Q7 thermal lattice (double distribution) with Boussinesq buoyancy, Dirichlet and adiabatic walls and conducting solids.
// Plain JS typed arrays, no DOM, no imports: it runs in Node tests and inside a package Worker (tools/bundle-ext.mjs concatenates it).
//
// Everything here is in LATTICE units (dx = dt = 1, rho0 = 1, cs^2 = 1/3). `lbmScaling` maps SI to lattice and back.
// Conventions as in grid.js: cell (i,j,k) has index i + nx (j + ny k); the face order is +x -x +y -y +z -z.
//
// Cell flags (Uint8): bits 0-2 the type, bits 3-5 for a boundary cell the face index (0..5) that points to its fluid neighbour.
//   LBM_FLUID    0  ordinary fluid
//   LBM_SOLID    1  wall, no slip (halfway bounce-back: the wall lies half a cell out)
//   LBM_MOVING   2  wall moving with `uWall` (lid)
//   LBM_VEL      3  boundary cell with the velocity `uVel` (or `ubc` per cell): populations = equilibrium + non-equilibrium of the neighbour
//   LBM_PRES     4  boundary cell at the reference density (pressure 0): the velocity is taken from the neighbour
// Thermal flags (Uint8, only with `thermal`): 0 the cell takes part in conduction (fluid or conducting solid), 1 Dirichlet wall at tval,
// 2 adiabatic wall.

export const LBM_Q = 19;
export const LBM_FLUID = 0;
export const LBM_SOLID = 1;
export const LBM_MOVING = 2;
export const LBM_VEL = 3;
export const LBM_PRES = 4;
export const LBM_CX = Int8Array.from([0, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0]);
export const LBM_CY = Int8Array.from([0, 0, 0, 1, -1, 0, 0, 1, -1, -1, 1, 0, 0, 0, 0, 1, -1, 1, -1]);
export const LBM_CZ = Int8Array.from([0, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, -1, -1, 1, 1, -1, -1, 1]);
export const LBM_W = Float64Array.from([1 / 3, ...Array(6).fill(1 / 18), ...Array(12).fill(1 / 36)]);
export const LBM_OPP = Int8Array.from([0, 2, 1, 4, 3, 6, 5, 8, 7, 10, 9, 12, 11, 14, 13, 16, 15, 18, 17]);
// D3Q7 thermal lattice: rest, then the six faces in the order +x -x +y -y +z -z
export const TH_W = Float64Array.from([1 / 4, 1 / 8, 1 / 8, 1 / 8, 1 / 8, 1 / 8, 1 / 8]);
export const TH_CX = Int8Array.from([0, 1, -1, 0, 0, 0, 0]);
export const TH_CY = Int8Array.from([0, 0, 0, 1, -1, 0, 0]);
export const TH_CZ = Int8Array.from([0, 0, 0, 0, 0, 1, -1]);
export const TH_OPP = Int8Array.from([0, 2, 1, 4, 3, 6, 5]);
const FACE_STEP = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];

export const boundaryFlag = (type, face) => type | (face << 3);

/** Equilibrium population i for density r and velocity (ux, uy, uz). */
export function lbmFeq(i, r, ux, uy, uz) {
  const cu = LBM_CX[i] * ux + LBM_CY[i] * uy + LBM_CZ[i] * uz;
  return LBM_W[i] * r * (1 + 3 * cu + 4.5 * cu * cu - 1.5 * (ux * ux + uy * uy + uz * uz));
}

/**
 * Create a simulation. Options:
 *   dims [nx,ny,nz], flags Uint8Array(N), nu (lattice kinematic viscosity), cs (Smagorinsky constant, 0 = off),
 *   periodic [bool,bool,bool], uVel [3], uWall [3], ubc Float32Array(3N) | null (per-cell velocity for LBM_VEL cells),
 *   bodyForce [3] (uniform force density), force Float32Array(3N) | null (per-cell force density),
 *   u0 [3] initial velocity of fluid cells,
 *   thermal: { alpha: Float32Array(N) | number (lattice diffusivity), tflag: Uint8Array(N) | null, tval: Float32Array(N) | null,
 *              q: Float32Array(N) | null (source per step), T0: number | Float32Array(N), Tref, gbeta (g * beta in lattice units), up [3] (unit vector against gravity),
 *              Tamb (temperature of inflow at boundary cells) }
 */
export function createLbm(o) {
  const [nx, ny, nz] = o.dims, N = nx * ny * nz;
  if (!(o.flags instanceof Uint8Array) || o.flags.length !== N) throw new Error('flags must be a Uint8Array with one entry per cell');
  if (!(o.nu > 0)) throw new Error('the lattice viscosity must be positive');
  const S = {
    nx, ny, nz, N, flags: o.flags, nu: o.nu, tau0: 3 * o.nu + 0.5, cs: o.cs || 0, periodic: o.periodic || [false, false, false],
    uVel: o.uVel || [0, 0, 0], uWall: o.uWall || [0, 0, 0], ubc: o.ubc || null, bodyForce: o.bodyForce || [0, 0, 0], force: o.force || null, rho0: 1, step: 0,
    Fo: new Float32Array(LBM_Q * N), Fn: new Float32Array(LBM_Q * N), rho: new Float32Array(N).fill(1), ux: new Float32Array(N), uy: new Float32Array(N), uz: new Float32Array(N),
    wrapX: wrapTable(nx, 1, (o.periodic || [])[0]), wrapY: wrapTable(ny, nx, (o.periodic || [])[1]), wrapZ: wrapTable(nz, nx * ny, (o.periodic || [])[2]),
    off: Int32Array.from({ length: LBM_Q }, (_, i) => LBM_CX[i] + nx * LBM_CY[i] + nx * ny * LBM_CZ[i]), thermal: null,
  };
  S.near = new Uint8Array(N);
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
    const c = x + nx * (y + ny * z); let nearWall = (S.flags[c] & 7) === LBM_FLUID ? 0 : 1;
    for (let i = 1; i < LBM_Q && !nearWall; i++) {
      const s = S.wrapX[3 * x + 1 - LBM_CX[i]] + S.wrapY[3 * y + 1 - LBM_CY[i]] + S.wrapZ[3 * z + 1 - LBM_CZ[i]];
      if (s < 0 || s !== c - S.off[i]) nearWall = 1; else { const t = S.flags[s] & 7; if (t === LBM_SOLID || t === LBM_MOVING) nearWall = 1; }
    }
    S.near[c] = nearWall;
  }
  { let nb = 0, ne = 0; for (let c = 0; c < N; c++) { if (!S.near[c]) nb++; else if ((S.flags[c] & 7) === LBM_FLUID) ne++; }
    S.bulk = new Int32Array(nb); S.edge = new Int32Array(ne); nb = 0; ne = 0;
    for (let c = 0; c < N; c++) { if (!S.near[c]) S.bulk[nb++] = c; else if ((S.flags[c] & 7) === LBM_FLUID) S.edge[ne++] = c; }
    S.nBulk = nb; S.nEdge = ne; S.useKernel = true; }
  const u0 = o.u0 || [0, 0, 0];
  for (let c = 0; c < N; c++) {
    const t = S.flags[c] & 7; let ux = u0[0], uy = u0[1], uz = u0[2];
    if (t === LBM_SOLID || t === LBM_MOVING) { ux = uy = uz = 0; } else if (t === LBM_VEL) { ux = S.uVel[0]; uy = S.uVel[1]; uz = S.uVel[2]; }
    if (t !== LBM_SOLID && t !== LBM_MOVING) { S.ux[c] = ux; S.uy[c] = uy; S.uz[c] = uz; }
    for (let i = 0; i < LBM_Q; i++) S.Fo[i * N + c] = lbmFeq(i, 1, ux, uy, uz);
  }
  if (o.thermal) {
    const th = o.thermal, g = { alpha: new Float32Array(N), tflag: th.tflag || new Uint8Array(N), tval: th.tval || new Float32Array(N), q: th.q || null, Tref: th.Tref ?? 0, gbeta: th.gbeta || 0, up: th.up || [0, 0, 1], Tamb: th.Tamb ?? th.Tref ?? 0,
      Go: new Float32Array(7 * N), Gn: new Float32Array(7 * N), T: new Float32Array(N) };
    if (typeof th.alpha === 'number') g.alpha.fill(th.alpha); else g.alpha.set(th.alpha);
    for (let c = 0; c < N; c++) { const T0 = typeof th.T0 === 'number' ? th.T0 : th.T0 ? th.T0[c] : g.Tref; g.T[c] = T0; for (let i = 0; i < 7; i++) g.Go[i * N + c] = TH_W[i] * T0 * (1 + 4 * (TH_CX[i] * S.ux[c] + TH_CY[i] * S.uy[c] + TH_CZ[i] * S.uz[c])); }
    S.thermal = g;
  }
  return S;
}

/** Index offsets of the neighbour along one axis: entry 3 x + 1 + d is the (linear-index contribution of the) cell at x + d, or a large negative number beyond a non-periodic edge. */
function wrapTable(n, stride, periodic) {
  const t = new Int32Array(3 * n + 2).fill(-(1 << 29));
  for (let x = 0; x < n; x++) for (let d = -1; d <= 1; d++) { let q = x + d; if (q < 0) q = periodic ? q + n : -1; else if (q >= n) q = periodic ? q - n : -1; t[3 * x + 1 + d] = q < 0 ? -(1 << 29) : q * stride; }
  return t;
}

/** One time step of the flow (and of the thermal lattice when there is one). */
export function lbmStep(S) {
  lbmFlowStep(S);
  if (S.thermal) lbmThermalStep(S);
  S.step++;
}

export function lbmAdvance(S, n) { for (let k = 0; k < n; k++) lbmStep(S); }

function lbmFlowStep(S) {
  const { nx, ny, nz, N, flags, Fo, Fn, off, rho: RHO, ux: UX, uy: UY, uz: UZ, periodic, uWall, uVel, ubc, bodyForce, force, cs } = S;
  if (S.useKernel) { lbmBulkKernel(S, S.bulk, S.nBulk); lbmEdgeKernel(S, S.edge, S.nEdge); }
  const useK = S.useKernel, th = S.thermal, tau0 = S.tau0, fin = new Float64Array(LBM_Q), feq = new Float64Array(LBM_Q), nxy = nx * ny;
  const near = S.near, WX = S.wrapX, WY = S.wrapY, WZ = S.wrapZ, mx = uWall[0], my = uWall[1], mz = uWall[2], cs2x18 = 18 * Math.SQRT2 * cs * cs;
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) {
    for (let x = 0; x < nx; x++) {
      const c = x + nx * y + nxy * z, fl = flags[c], ty = fl & 7;
      if (ty === LBM_SOLID || ty === LBM_MOVING) continue;
      if (useK && ty === LBM_FLUID) continue;
      if (ty >= LBM_VEL) { // boundary cell: equilibrium of the wanted state plus the non-equilibrium part of the fluid neighbour
        const d = FACE_STEP[(fl >> 3) & 7], n = c + d[0] + nx * d[1] + nxy * d[2];
        let rn = 0, mxn = 0, myn = 0, mzn = 0;
        for (let i = 0; i < LBM_Q; i++) { const v = Fo[i * N + n]; rn += v; mxn += LBM_CX[i] * v; myn += LBM_CY[i] * v; mzn += LBM_CZ[i] * v; }
        const unx = mxn / rn, uny = myn / rn, unz = mzn / rn;
        let rb, wx, wy, wz;
        if (ty === LBM_VEL) { rb = rn; if (ubc) { wx = ubc[3 * c]; wy = ubc[3 * c + 1]; wz = ubc[3 * c + 2]; } else { wx = uVel[0]; wy = uVel[1]; wz = uVel[2]; } }
        else { rb = 1; wx = unx; wy = uny; wz = unz; }
        for (let i = 0; i < LBM_Q; i++) Fn[i * N + c] = lbmFeq(i, rb, wx, wy, wz) + (Fo[i * N + n] - lbmFeq(i, rn, unx, uny, unz));
        RHO[c] = rb; UX[c] = wx; UY[c] = wy; UZ[c] = wz;
        continue;
      }
      let r = 0, mxx = 0, myy = 0, mzz = 0;
      if (near[c] === 0) { // no wall, edge or wrap around: a plain pull
        for (let i = 0; i < LBM_Q; i++) { const v = Fo[i * N + c - off[i]]; fin[i] = v; r += v; mxx += LBM_CX[i] * v; myy += LBM_CY[i] * v; mzz += LBM_CZ[i] * v; }
      } else {
        const bx = 3 * x + 1, by = 3 * y + 1, bz = 3 * z + 1;
        for (let i = 0; i < LBM_Q; i++) {
          const s = WX[bx - LBM_CX[i]] + WY[by - LBM_CY[i]] + WZ[bz - LBM_CZ[i]];
          let v;
          if (s < 0) v = Fo[LBM_OPP[i] * N + c]; // beyond a non-periodic edge: a wall
          else {
            const ts = flags[s] & 7;
            v = ts === LBM_SOLID ? Fo[LBM_OPP[i] * N + c] : ts === LBM_MOVING ? Fo[LBM_OPP[i] * N + c] + 6 * LBM_W[i] * (LBM_CX[i] * mx + LBM_CY[i] * my + LBM_CZ[i] * mz) : Fo[i * N + s];
          }
          fin[i] = v; r += v; mxx += LBM_CX[i] * v; myy += LBM_CY[i] * v; mzz += LBM_CZ[i] * v;
        }
      }
      let Fx = bodyForce[0], Fy = bodyForce[1], Fz = bodyForce[2];
      if (force) { Fx += force[3 * c]; Fy += force[3 * c + 1]; Fz += force[3 * c + 2]; }
      if (th && th.gbeta) { const b = th.gbeta * (th.T[c] - th.Tref) * r; Fx += b * th.up[0]; Fy += b * th.up[1]; Fz += b * th.up[2]; }
      const ir = 1 / r, ux = (mxx + 0.5 * Fx) * ir, uy = (myy + 0.5 * Fy) * ir, uz = (mzz + 0.5 * Fz) * ir, u2 = ux * ux + uy * uy + uz * uz;
      let tau = tau0;
      if (cs > 0) {
        let pxx = 0, pyy = 0, pzz = 0, pxy = 0, pxz = 0, pyz = 0;
        for (let i = 0; i < LBM_Q; i++) {
          const cx = LBM_CX[i], cy = LBM_CY[i], cz = LBM_CZ[i], cu = cx * ux + cy * uy + cz * uz, fe = LBM_W[i] * r * (1 + 3 * cu + 4.5 * cu * cu - 1.5 * u2), fn = fin[i] - fe;
          feq[i] = fe; pxx += cx * cx * fn; pyy += cy * cy * fn; pzz += cz * cz * fn; pxy += cx * cy * fn; pxz += cx * cz * fn; pyz += cy * cz * fn;
        }
        const pi = Math.sqrt(pxx * pxx + pyy * pyy + pzz * pzz + 2 * (pxy * pxy + pxz * pxz + pyz * pyz));
        tau = 0.5 * (tau0 + Math.sqrt(tau0 * tau0 + cs2x18 * pi * ir));
      }
      const om = 1 / tau, fq = 1 - 0.5 * om, hasF = Fx !== 0 || Fy !== 0 || Fz !== 0;
      for (let i = 0; i < LBM_Q; i++) {
        if (cs <= 0) { const cu = LBM_CX[i] * ux + LBM_CY[i] * uy + LBM_CZ[i] * uz; feq[i] = LBM_W[i] * r * (1 + 3 * cu + 4.5 * cu * cu - 1.5 * u2); }
        let v = fin[i] - om * (fin[i] - feq[i]);
        if (hasF) {
          const cx = LBM_CX[i], cy = LBM_CY[i], cz = LBM_CZ[i], cu = cx * ux + cy * uy + cz * uz;
          v += fq * LBM_W[i] * (3 * ((cx - ux) * Fx + (cy - uy) * Fy + (cz - uz) * Fz) + 9 * cu * (cx * Fx + cy * Fy + cz * Fz));
        }
        Fn[i * N + c] = v;
      }
      RHO[c] = r; UX[c] = ux; UY[c] = uy; UZ[c] = uz;
    }
  }
  S.Fo = Fn; S.Fn = Fo;
}

function lbmThermalStep(S) {
  const { nx, ny, nz, N, flags, rho0 } = S, th = S.thermal, { Go, Gn, alpha, tflag, tval, q, T } = th, nxy = nx * ny, periodic = S.periodic, ux = S.ux, uy = S.uy, uz = S.uz;
  const gin = new Float64Array(7);
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
    const c = x + nx * y + nxy * z, ty = flags[c] & 7;
    if (ty === LBM_VEL || ty === LBM_PRES) { // open boundary: ambient temperature where air comes in, zero gradient where it leaves
      const d = FACE_STEP[(flags[c] >> 3) & 7], n = c + d[0] + nx * d[1] + nxy * d[2], inflow = ux[c] * d[0] + uy[c] * d[1] + uz[c] * d[2] > 0, Tb = inflow ? th.Tamb : T[n];
      for (let i = 0; i < 7; i++) Gn[i * N + c] = TH_W[i] * Tb * (1 + 4 * (TH_CX[i] * ux[c] + TH_CY[i] * uy[c] + TH_CZ[i] * uz[c]));
      T[c] = Tb; continue;
    }
    if (tflag[c] !== 0) continue; // a wall
    let Ts = 0;
    for (let i = 0; i < 7; i++) {
      let sx = x - TH_CX[i], sy = y - TH_CY[i], sz = z - TH_CZ[i], out = false;
      if (sx < 0) { if (periodic[0]) sx += nx; else out = true; } else if (sx >= nx) { if (periodic[0]) sx -= nx; else out = true; }
      if (sy < 0) { if (periodic[1]) sy += ny; else out = true; } else if (sy >= ny) { if (periodic[1]) sy -= ny; else out = true; }
      if (sz < 0) { if (periodic[2]) sz += nz; else out = true; } else if (sz >= nz) { if (periodic[2]) sz -= nz; else out = true; }
      let v;
      if (out) v = Go[TH_OPP[i] * N + c];
      else {
        const s = sx + nx * sy + nxy * sz, tf = tflag[s];
        v = tf === 0 ? Go[i * N + s] : tf === 1 ? -Go[TH_OPP[i] * N + c] + 2 * TH_W[i] * tval[s] : Go[TH_OPP[i] * N + c];
      }
      gin[i] = v; Ts += v;
    }
    T[c] = Ts;
    const om = 1 / (4 * alpha[c] + 0.5), Q = q ? q[c] : 0, vx = ux[c], vy = uy[c], vz = uz[c];
    for (let i = 0; i < 7; i++) {
      const geq = TH_W[i] * Ts * (1 + 4 * (TH_CX[i] * vx + TH_CY[i] * vy + TH_CZ[i] * vz));
      Gn[i * N + c] = gin[i] - om * (gin[i] - geq) + TH_W[i] * Q;
    }
  }
  th.Go = Gn; th.Gn = Go;
}

// BEGIN GENERATED lbm bulk kernel (tools/gen-lbm-kernel.mjs)
/** The collision-streaming of every bulk fluid cell (no wall, edge or wrap in its 18 neighbours) with the 19 directions written out. `list` lists the cells. */
function lbmBulkKernel(S, list, n) {
  const N = S.N, Fo = S.Fo, Fn = S.Fn, off = S.off, RHO = S.rho, UX = S.ux, UY = S.uy, UZ = S.uz, tau0 = S.tau0, cs = S.cs, bf = S.bodyForce, force = S.force, th = S.thermal;
  const gb = th ? th.gbeta : 0, T = th ? th.T : null, Tref = th ? th.Tref : 0, up0 = th ? th.up[0] : 0, up1 = th ? th.up[1] : 0, up2 = th ? th.up[2] : 0, cs18 = 18 * Math.SQRT2 * cs * cs, bf0 = bf[0], bf1 = bf[1], bf2 = bf[2];
  const o0 = off[0], o1 = off[1], o2 = off[2], o3 = off[3], o4 = off[4], o5 = off[5], o6 = off[6], o7 = off[7], o8 = off[8], o9 = off[9], o10 = off[10], o11 = off[11], o12 = off[12], o13 = off[13], o14 = off[14], o15 = off[15], o16 = off[16], o17 = off[17], o18 = off[18];
  for (let k = 0; k < n; k++) {
    const c = list[k];
    const f0 = Fo[0 * N + c - o0];
    const f1 = Fo[1 * N + c - o1];
    const f2 = Fo[2 * N + c - o2];
    const f3 = Fo[3 * N + c - o3];
    const f4 = Fo[4 * N + c - o4];
    const f5 = Fo[5 * N + c - o5];
    const f6 = Fo[6 * N + c - o6];
    const f7 = Fo[7 * N + c - o7];
    const f8 = Fo[8 * N + c - o8];
    const f9 = Fo[9 * N + c - o9];
    const f10 = Fo[10 * N + c - o10];
    const f11 = Fo[11 * N + c - o11];
    const f12 = Fo[12 * N + c - o12];
    const f13 = Fo[13 * N + c - o13];
    const f14 = Fo[14 * N + c - o14];
    const f15 = Fo[15 * N + c - o15];
    const f16 = Fo[16 * N + c - o16];
    const f17 = Fo[17 * N + c - o17];
    const f18 = Fo[18 * N + c - o18];
    const r = f0 + f1 + f2 + f3 + f4 + f5 + f6 + f7 + f8 + f9 + f10 + f11 + f12 + f13 + f14 + f15 + f16 + f17 + f18, ir = 1 / r;
    const mx = f1-f2+f7-f8+f9-f10+f11-f12+f13-f14, my = f3-f4+f7-f8-f9+f10+f15-f16+f17-f18, mz = f5-f6+f11-f12-f13+f14+f15-f16-f17+f18;
    let Fx = bf0, Fy = bf1, Fz = bf2;
    if (force) { Fx += force[3 * c]; Fy += force[3 * c + 1]; Fz += force[3 * c + 2]; }
    if (gb) { const b = gb * (T[c] - Tref) * r; Fx += b * up0; Fy += b * up1; Fz += b * up2; }
    const ux = (mx + 0.5 * Fx) * ir, uy = (my + 0.5 * Fy) * ir, uz = (mz + 0.5 * Fz) * ir, u2 = ux * ux + uy * uy + uz * uz, k0 = 1 - 1.5 * u2, hasF = Fx !== 0 || Fy !== 0 || Fz !== 0;
    let om = 1 / tau0;
    if (cs > 0) {
      const cu0 = 0, d0 = f0 - (1/3) * r * (k0 + 3 * cu0 + 4.5 * cu0 * cu0);
      const cu1 = ux, d1 = f1 - (1/18) * r * (k0 + 3 * cu1 + 4.5 * cu1 * cu1);
      const cu2 = -ux, d2 = f2 - (1/18) * r * (k0 + 3 * cu2 + 4.5 * cu2 * cu2);
      const cu3 = uy, d3 = f3 - (1/18) * r * (k0 + 3 * cu3 + 4.5 * cu3 * cu3);
      const cu4 = -uy, d4 = f4 - (1/18) * r * (k0 + 3 * cu4 + 4.5 * cu4 * cu4);
      const cu5 = uz, d5 = f5 - (1/18) * r * (k0 + 3 * cu5 + 4.5 * cu5 * cu5);
      const cu6 = -uz, d6 = f6 - (1/18) * r * (k0 + 3 * cu6 + 4.5 * cu6 * cu6);
      const cu7 = ux+uy, d7 = f7 - (1/36) * r * (k0 + 3 * cu7 + 4.5 * cu7 * cu7);
      const cu8 = -ux-uy, d8 = f8 - (1/36) * r * (k0 + 3 * cu8 + 4.5 * cu8 * cu8);
      const cu9 = ux-uy, d9 = f9 - (1/36) * r * (k0 + 3 * cu9 + 4.5 * cu9 * cu9);
      const cu10 = -ux+uy, d10 = f10 - (1/36) * r * (k0 + 3 * cu10 + 4.5 * cu10 * cu10);
      const cu11 = ux+uz, d11 = f11 - (1/36) * r * (k0 + 3 * cu11 + 4.5 * cu11 * cu11);
      const cu12 = -ux-uz, d12 = f12 - (1/36) * r * (k0 + 3 * cu12 + 4.5 * cu12 * cu12);
      const cu13 = ux-uz, d13 = f13 - (1/36) * r * (k0 + 3 * cu13 + 4.5 * cu13 * cu13);
      const cu14 = -ux+uz, d14 = f14 - (1/36) * r * (k0 + 3 * cu14 + 4.5 * cu14 * cu14);
      const cu15 = uy+uz, d15 = f15 - (1/36) * r * (k0 + 3 * cu15 + 4.5 * cu15 * cu15);
      const cu16 = -uy-uz, d16 = f16 - (1/36) * r * (k0 + 3 * cu16 + 4.5 * cu16 * cu16);
      const cu17 = uy-uz, d17 = f17 - (1/36) * r * (k0 + 3 * cu17 + 4.5 * cu17 * cu17);
      const cu18 = -uy+uz, d18 = f18 - (1/36) * r * (k0 + 3 * cu18 + 4.5 * cu18 * cu18);
      const pxx = d1+d2+d7+d8+d9+d10+d11+d12+d13+d14, pyy = d3+d4+d7+d8+d9+d10+d15+d16+d17+d18, pzz = d5+d6+d11+d12+d13+d14+d15+d16+d17+d18, pxy = d7+d8-d9-d10, pxz = d11+d12-d13-d14, pyz = d15+d16-d17-d18;
      const pn = Math.sqrt(pxx * pxx + pyy * pyy + pzz * pzz + 2 * (pxy * pxy + pxz * pxz + pyz * pyz));
      om = 1 / (0.5 * (tau0 + Math.sqrt(tau0 * tau0 + cs18 * pn * ir)));
    }
    if (hasF) {
      const fq = 1 - 0.5 * om;
      { const cu = 0; Fn[0 * N + c] = f0 - om * (f0 - (1/3) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/3) * (3 * (-ux*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (0)); }
      { const cu = ux; Fn[1 * N + c] = f1 - om * (f1 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * ((1-ux)*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (Fx)); }
      { const cu = -ux; Fn[2 * N + c] = f2 - om * (f2 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * ((-1-ux)*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (-Fx)); }
      { const cu = uy; Fn[3 * N + c] = f3 - om * (f3 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (Fy)); }
      { const cu = -uy; Fn[4 * N + c] = f4 - om * (f4 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fy)); }
      { const cu = uz; Fn[5 * N + c] = f5 - om * (f5 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (Fz)); }
      { const cu = -uz; Fn[6 * N + c] = f6 - om * (f6 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (-Fz)); }
      { const cu = ux+uy; Fn[7 * N + c] = f7 - om * (f7 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (Fx+Fy)); }
      { const cu = -ux-uy; Fn[8 * N + c] = f8 - om * (f8 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fx-Fy)); }
      { const cu = ux-uy; Fn[9 * N + c] = f9 - om * (f9 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (Fx-Fy)); }
      { const cu = -ux+uy; Fn[10 * N + c] = f10 - om * (f10 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fx+Fy)); }
      { const cu = ux+uz; Fn[11 * N + c] = f11 - om * (f11 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (Fx+Fz)); }
      { const cu = -ux-uz; Fn[12 * N + c] = f12 - om * (f12 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (-Fx-Fz)); }
      { const cu = ux-uz; Fn[13 * N + c] = f13 - om * (f13 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (Fx-Fz)); }
      { const cu = -ux+uz; Fn[14 * N + c] = f14 - om * (f14 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (-Fx+Fz)); }
      { const cu = uy+uz; Fn[15 * N + c] = f15 - om * (f15 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (1-uy)*Fy + (1-uz)*Fz) + 9 * cu * (Fy+Fz)); }
      { const cu = -uy-uz; Fn[16 * N + c] = f16 - om * (f16 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (-1-uy)*Fy + (-1-uz)*Fz) + 9 * cu * (-Fy-Fz)); }
      { const cu = uy-uz; Fn[17 * N + c] = f17 - om * (f17 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (1-uy)*Fy + (-1-uz)*Fz) + 9 * cu * (Fy-Fz)); }
      { const cu = -uy+uz; Fn[18 * N + c] = f18 - om * (f18 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (-1-uy)*Fy + (1-uz)*Fz) + 9 * cu * (-Fy+Fz)); }
    } else {
      { const cu = 0; Fn[0 * N + c] = f0 - om * (f0 - (1/3) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux; Fn[1 * N + c] = f1 - om * (f1 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux; Fn[2 * N + c] = f2 - om * (f2 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy; Fn[3 * N + c] = f3 - om * (f3 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy; Fn[4 * N + c] = f4 - om * (f4 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uz; Fn[5 * N + c] = f5 - om * (f5 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uz; Fn[6 * N + c] = f6 - om * (f6 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux+uy; Fn[7 * N + c] = f7 - om * (f7 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux-uy; Fn[8 * N + c] = f8 - om * (f8 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux-uy; Fn[9 * N + c] = f9 - om * (f9 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux+uy; Fn[10 * N + c] = f10 - om * (f10 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux+uz; Fn[11 * N + c] = f11 - om * (f11 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux-uz; Fn[12 * N + c] = f12 - om * (f12 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux-uz; Fn[13 * N + c] = f13 - om * (f13 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux+uz; Fn[14 * N + c] = f14 - om * (f14 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy+uz; Fn[15 * N + c] = f15 - om * (f15 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy-uz; Fn[16 * N + c] = f16 - om * (f16 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy-uz; Fn[17 * N + c] = f17 - om * (f17 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy+uz; Fn[18 * N + c] = f18 - om * (f18 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
    }
    RHO[c] = r; UX[c] = ux; UY[c] = uy; UZ[c] = uz;
  }
}
/** The same for the fluid cells next to a wall, an edge or a periodic wrap: every direction looks at its source cell (wall: bounce-back, moving wall: with the wall velocity). */
function lbmEdgeKernel(S, list, n) {
  const N = S.N, Fo = S.Fo, Fn = S.Fn, off = S.off, RHO = S.rho, UX = S.ux, UY = S.uy, UZ = S.uz, tau0 = S.tau0, cs = S.cs, bf = S.bodyForce, force = S.force, th = S.thermal;
  const gb = th ? th.gbeta : 0, T = th ? th.T : null, Tref = th ? th.Tref : 0, up0 = th ? th.up[0] : 0, up1 = th ? th.up[1] : 0, up2 = th ? th.up[2] : 0, cs18 = 18 * Math.SQRT2 * cs * cs, bf0 = bf[0], bf1 = bf[1], bf2 = bf[2];
  const flags = S.flags, WX = S.wrapX, WY = S.wrapY, WZ = S.wrapZ, nx = S.nx, ny = S.ny, nxy = nx * ny, mx0 = S.uWall[0], my0 = S.uWall[1], mz0 = S.uWall[2];
  for (let k = 0; k < n; k++) {
    const c = list[k];
    const px = c % nx, py = ((c - px) / nx) % ny, pz = (c - px - nx * py) / nxy, bx = 3 * px + 1, by = 3 * py + 1, bz = 3 * pz + 1;
    const f0 = Fo[c];
    let f1; { const s = WX[bx - (1)] + WY[by - (0)] + WZ[bz - (0)]; if (s < 0) f1 = Fo[2 * N + c]; else { const t = flags[s] & 7; f1 = t === 1 ? Fo[2 * N + c] : t === 2 ? Fo[2 * N + c] + 6 * (1/18) * (1 * mx0 + 0 * my0 + 0 * mz0) : Fo[1 * N + s]; } }
    let f2; { const s = WX[bx - (-1)] + WY[by - (0)] + WZ[bz - (0)]; if (s < 0) f2 = Fo[1 * N + c]; else { const t = flags[s] & 7; f2 = t === 1 ? Fo[1 * N + c] : t === 2 ? Fo[1 * N + c] + 6 * (1/18) * (-1 * mx0 + 0 * my0 + 0 * mz0) : Fo[2 * N + s]; } }
    let f3; { const s = WX[bx - (0)] + WY[by - (1)] + WZ[bz - (0)]; if (s < 0) f3 = Fo[4 * N + c]; else { const t = flags[s] & 7; f3 = t === 1 ? Fo[4 * N + c] : t === 2 ? Fo[4 * N + c] + 6 * (1/18) * (0 * mx0 + 1 * my0 + 0 * mz0) : Fo[3 * N + s]; } }
    let f4; { const s = WX[bx - (0)] + WY[by - (-1)] + WZ[bz - (0)]; if (s < 0) f4 = Fo[3 * N + c]; else { const t = flags[s] & 7; f4 = t === 1 ? Fo[3 * N + c] : t === 2 ? Fo[3 * N + c] + 6 * (1/18) * (0 * mx0 + -1 * my0 + 0 * mz0) : Fo[4 * N + s]; } }
    let f5; { const s = WX[bx - (0)] + WY[by - (0)] + WZ[bz - (1)]; if (s < 0) f5 = Fo[6 * N + c]; else { const t = flags[s] & 7; f5 = t === 1 ? Fo[6 * N + c] : t === 2 ? Fo[6 * N + c] + 6 * (1/18) * (0 * mx0 + 0 * my0 + 1 * mz0) : Fo[5 * N + s]; } }
    let f6; { const s = WX[bx - (0)] + WY[by - (0)] + WZ[bz - (-1)]; if (s < 0) f6 = Fo[5 * N + c]; else { const t = flags[s] & 7; f6 = t === 1 ? Fo[5 * N + c] : t === 2 ? Fo[5 * N + c] + 6 * (1/18) * (0 * mx0 + 0 * my0 + -1 * mz0) : Fo[6 * N + s]; } }
    let f7; { const s = WX[bx - (1)] + WY[by - (1)] + WZ[bz - (0)]; if (s < 0) f7 = Fo[8 * N + c]; else { const t = flags[s] & 7; f7 = t === 1 ? Fo[8 * N + c] : t === 2 ? Fo[8 * N + c] + 6 * (1/36) * (1 * mx0 + 1 * my0 + 0 * mz0) : Fo[7 * N + s]; } }
    let f8; { const s = WX[bx - (-1)] + WY[by - (-1)] + WZ[bz - (0)]; if (s < 0) f8 = Fo[7 * N + c]; else { const t = flags[s] & 7; f8 = t === 1 ? Fo[7 * N + c] : t === 2 ? Fo[7 * N + c] + 6 * (1/36) * (-1 * mx0 + -1 * my0 + 0 * mz0) : Fo[8 * N + s]; } }
    let f9; { const s = WX[bx - (1)] + WY[by - (-1)] + WZ[bz - (0)]; if (s < 0) f9 = Fo[10 * N + c]; else { const t = flags[s] & 7; f9 = t === 1 ? Fo[10 * N + c] : t === 2 ? Fo[10 * N + c] + 6 * (1/36) * (1 * mx0 + -1 * my0 + 0 * mz0) : Fo[9 * N + s]; } }
    let f10; { const s = WX[bx - (-1)] + WY[by - (1)] + WZ[bz - (0)]; if (s < 0) f10 = Fo[9 * N + c]; else { const t = flags[s] & 7; f10 = t === 1 ? Fo[9 * N + c] : t === 2 ? Fo[9 * N + c] + 6 * (1/36) * (-1 * mx0 + 1 * my0 + 0 * mz0) : Fo[10 * N + s]; } }
    let f11; { const s = WX[bx - (1)] + WY[by - (0)] + WZ[bz - (1)]; if (s < 0) f11 = Fo[12 * N + c]; else { const t = flags[s] & 7; f11 = t === 1 ? Fo[12 * N + c] : t === 2 ? Fo[12 * N + c] + 6 * (1/36) * (1 * mx0 + 0 * my0 + 1 * mz0) : Fo[11 * N + s]; } }
    let f12; { const s = WX[bx - (-1)] + WY[by - (0)] + WZ[bz - (-1)]; if (s < 0) f12 = Fo[11 * N + c]; else { const t = flags[s] & 7; f12 = t === 1 ? Fo[11 * N + c] : t === 2 ? Fo[11 * N + c] + 6 * (1/36) * (-1 * mx0 + 0 * my0 + -1 * mz0) : Fo[12 * N + s]; } }
    let f13; { const s = WX[bx - (1)] + WY[by - (0)] + WZ[bz - (-1)]; if (s < 0) f13 = Fo[14 * N + c]; else { const t = flags[s] & 7; f13 = t === 1 ? Fo[14 * N + c] : t === 2 ? Fo[14 * N + c] + 6 * (1/36) * (1 * mx0 + 0 * my0 + -1 * mz0) : Fo[13 * N + s]; } }
    let f14; { const s = WX[bx - (-1)] + WY[by - (0)] + WZ[bz - (1)]; if (s < 0) f14 = Fo[13 * N + c]; else { const t = flags[s] & 7; f14 = t === 1 ? Fo[13 * N + c] : t === 2 ? Fo[13 * N + c] + 6 * (1/36) * (-1 * mx0 + 0 * my0 + 1 * mz0) : Fo[14 * N + s]; } }
    let f15; { const s = WX[bx - (0)] + WY[by - (1)] + WZ[bz - (1)]; if (s < 0) f15 = Fo[16 * N + c]; else { const t = flags[s] & 7; f15 = t === 1 ? Fo[16 * N + c] : t === 2 ? Fo[16 * N + c] + 6 * (1/36) * (0 * mx0 + 1 * my0 + 1 * mz0) : Fo[15 * N + s]; } }
    let f16; { const s = WX[bx - (0)] + WY[by - (-1)] + WZ[bz - (-1)]; if (s < 0) f16 = Fo[15 * N + c]; else { const t = flags[s] & 7; f16 = t === 1 ? Fo[15 * N + c] : t === 2 ? Fo[15 * N + c] + 6 * (1/36) * (0 * mx0 + -1 * my0 + -1 * mz0) : Fo[16 * N + s]; } }
    let f17; { const s = WX[bx - (0)] + WY[by - (1)] + WZ[bz - (-1)]; if (s < 0) f17 = Fo[18 * N + c]; else { const t = flags[s] & 7; f17 = t === 1 ? Fo[18 * N + c] : t === 2 ? Fo[18 * N + c] + 6 * (1/36) * (0 * mx0 + 1 * my0 + -1 * mz0) : Fo[17 * N + s]; } }
    let f18; { const s = WX[bx - (0)] + WY[by - (-1)] + WZ[bz - (1)]; if (s < 0) f18 = Fo[17 * N + c]; else { const t = flags[s] & 7; f18 = t === 1 ? Fo[17 * N + c] : t === 2 ? Fo[17 * N + c] + 6 * (1/36) * (0 * mx0 + -1 * my0 + 1 * mz0) : Fo[18 * N + s]; } }
    const r = f0 + f1 + f2 + f3 + f4 + f5 + f6 + f7 + f8 + f9 + f10 + f11 + f12 + f13 + f14 + f15 + f16 + f17 + f18, ir = 1 / r;
    const mx = f1-f2+f7-f8+f9-f10+f11-f12+f13-f14, my = f3-f4+f7-f8-f9+f10+f15-f16+f17-f18, mz = f5-f6+f11-f12-f13+f14+f15-f16-f17+f18;
    let Fx = bf0, Fy = bf1, Fz = bf2;
    if (force) { Fx += force[3 * c]; Fy += force[3 * c + 1]; Fz += force[3 * c + 2]; }
    if (gb) { const b = gb * (T[c] - Tref) * r; Fx += b * up0; Fy += b * up1; Fz += b * up2; }
    const ux = (mx + 0.5 * Fx) * ir, uy = (my + 0.5 * Fy) * ir, uz = (mz + 0.5 * Fz) * ir, u2 = ux * ux + uy * uy + uz * uz, k0 = 1 - 1.5 * u2, hasF = Fx !== 0 || Fy !== 0 || Fz !== 0;
    let om = 1 / tau0;
    if (cs > 0) {
      const cu0 = 0, d0 = f0 - (1/3) * r * (k0 + 3 * cu0 + 4.5 * cu0 * cu0);
      const cu1 = ux, d1 = f1 - (1/18) * r * (k0 + 3 * cu1 + 4.5 * cu1 * cu1);
      const cu2 = -ux, d2 = f2 - (1/18) * r * (k0 + 3 * cu2 + 4.5 * cu2 * cu2);
      const cu3 = uy, d3 = f3 - (1/18) * r * (k0 + 3 * cu3 + 4.5 * cu3 * cu3);
      const cu4 = -uy, d4 = f4 - (1/18) * r * (k0 + 3 * cu4 + 4.5 * cu4 * cu4);
      const cu5 = uz, d5 = f5 - (1/18) * r * (k0 + 3 * cu5 + 4.5 * cu5 * cu5);
      const cu6 = -uz, d6 = f6 - (1/18) * r * (k0 + 3 * cu6 + 4.5 * cu6 * cu6);
      const cu7 = ux+uy, d7 = f7 - (1/36) * r * (k0 + 3 * cu7 + 4.5 * cu7 * cu7);
      const cu8 = -ux-uy, d8 = f8 - (1/36) * r * (k0 + 3 * cu8 + 4.5 * cu8 * cu8);
      const cu9 = ux-uy, d9 = f9 - (1/36) * r * (k0 + 3 * cu9 + 4.5 * cu9 * cu9);
      const cu10 = -ux+uy, d10 = f10 - (1/36) * r * (k0 + 3 * cu10 + 4.5 * cu10 * cu10);
      const cu11 = ux+uz, d11 = f11 - (1/36) * r * (k0 + 3 * cu11 + 4.5 * cu11 * cu11);
      const cu12 = -ux-uz, d12 = f12 - (1/36) * r * (k0 + 3 * cu12 + 4.5 * cu12 * cu12);
      const cu13 = ux-uz, d13 = f13 - (1/36) * r * (k0 + 3 * cu13 + 4.5 * cu13 * cu13);
      const cu14 = -ux+uz, d14 = f14 - (1/36) * r * (k0 + 3 * cu14 + 4.5 * cu14 * cu14);
      const cu15 = uy+uz, d15 = f15 - (1/36) * r * (k0 + 3 * cu15 + 4.5 * cu15 * cu15);
      const cu16 = -uy-uz, d16 = f16 - (1/36) * r * (k0 + 3 * cu16 + 4.5 * cu16 * cu16);
      const cu17 = uy-uz, d17 = f17 - (1/36) * r * (k0 + 3 * cu17 + 4.5 * cu17 * cu17);
      const cu18 = -uy+uz, d18 = f18 - (1/36) * r * (k0 + 3 * cu18 + 4.5 * cu18 * cu18);
      const pxx = d1+d2+d7+d8+d9+d10+d11+d12+d13+d14, pyy = d3+d4+d7+d8+d9+d10+d15+d16+d17+d18, pzz = d5+d6+d11+d12+d13+d14+d15+d16+d17+d18, pxy = d7+d8-d9-d10, pxz = d11+d12-d13-d14, pyz = d15+d16-d17-d18;
      const pn = Math.sqrt(pxx * pxx + pyy * pyy + pzz * pzz + 2 * (pxy * pxy + pxz * pxz + pyz * pyz));
      om = 1 / (0.5 * (tau0 + Math.sqrt(tau0 * tau0 + cs18 * pn * ir)));
    }
    if (hasF) {
      const fq = 1 - 0.5 * om;
      { const cu = 0; Fn[0 * N + c] = f0 - om * (f0 - (1/3) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/3) * (3 * (-ux*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (0)); }
      { const cu = ux; Fn[1 * N + c] = f1 - om * (f1 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * ((1-ux)*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (Fx)); }
      { const cu = -ux; Fn[2 * N + c] = f2 - om * (f2 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * ((-1-ux)*Fx + -uy*Fy + -uz*Fz) + 9 * cu * (-Fx)); }
      { const cu = uy; Fn[3 * N + c] = f3 - om * (f3 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (Fy)); }
      { const cu = -uy; Fn[4 * N + c] = f4 - om * (f4 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fy)); }
      { const cu = uz; Fn[5 * N + c] = f5 - om * (f5 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (Fz)); }
      { const cu = -uz; Fn[6 * N + c] = f6 - om * (f6 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/18) * (3 * (-ux*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (-Fz)); }
      { const cu = ux+uy; Fn[7 * N + c] = f7 - om * (f7 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (Fx+Fy)); }
      { const cu = -ux-uy; Fn[8 * N + c] = f8 - om * (f8 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fx-Fy)); }
      { const cu = ux-uy; Fn[9 * N + c] = f9 - om * (f9 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + (-1-uy)*Fy + -uz*Fz) + 9 * cu * (Fx-Fy)); }
      { const cu = -ux+uy; Fn[10 * N + c] = f10 - om * (f10 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + (1-uy)*Fy + -uz*Fz) + 9 * cu * (-Fx+Fy)); }
      { const cu = ux+uz; Fn[11 * N + c] = f11 - om * (f11 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (Fx+Fz)); }
      { const cu = -ux-uz; Fn[12 * N + c] = f12 - om * (f12 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (-Fx-Fz)); }
      { const cu = ux-uz; Fn[13 * N + c] = f13 - om * (f13 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((1-ux)*Fx + -uy*Fy + (-1-uz)*Fz) + 9 * cu * (Fx-Fz)); }
      { const cu = -ux+uz; Fn[14 * N + c] = f14 - om * (f14 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * ((-1-ux)*Fx + -uy*Fy + (1-uz)*Fz) + 9 * cu * (-Fx+Fz)); }
      { const cu = uy+uz; Fn[15 * N + c] = f15 - om * (f15 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (1-uy)*Fy + (1-uz)*Fz) + 9 * cu * (Fy+Fz)); }
      { const cu = -uy-uz; Fn[16 * N + c] = f16 - om * (f16 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (-1-uy)*Fy + (-1-uz)*Fz) + 9 * cu * (-Fy-Fz)); }
      { const cu = uy-uz; Fn[17 * N + c] = f17 - om * (f17 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (1-uy)*Fy + (-1-uz)*Fz) + 9 * cu * (Fy-Fz)); }
      { const cu = -uy+uz; Fn[18 * N + c] = f18 - om * (f18 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)) + fq * (1/36) * (3 * (-ux*Fx + (-1-uy)*Fy + (1-uz)*Fz) + 9 * cu * (-Fy+Fz)); }
    } else {
      { const cu = 0; Fn[0 * N + c] = f0 - om * (f0 - (1/3) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux; Fn[1 * N + c] = f1 - om * (f1 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux; Fn[2 * N + c] = f2 - om * (f2 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy; Fn[3 * N + c] = f3 - om * (f3 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy; Fn[4 * N + c] = f4 - om * (f4 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uz; Fn[5 * N + c] = f5 - om * (f5 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uz; Fn[6 * N + c] = f6 - om * (f6 - (1/18) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux+uy; Fn[7 * N + c] = f7 - om * (f7 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux-uy; Fn[8 * N + c] = f8 - om * (f8 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux-uy; Fn[9 * N + c] = f9 - om * (f9 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux+uy; Fn[10 * N + c] = f10 - om * (f10 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux+uz; Fn[11 * N + c] = f11 - om * (f11 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux-uz; Fn[12 * N + c] = f12 - om * (f12 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = ux-uz; Fn[13 * N + c] = f13 - om * (f13 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -ux+uz; Fn[14 * N + c] = f14 - om * (f14 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy+uz; Fn[15 * N + c] = f15 - om * (f15 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy-uz; Fn[16 * N + c] = f16 - om * (f16 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = uy-uz; Fn[17 * N + c] = f17 - om * (f17 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
      { const cu = -uy+uz; Fn[18 * N + c] = f18 - om * (f18 - (1/36) * r * (k0 + 3 * cu + 4.5 * cu * cu)); }
    }
    RHO[c] = r; UX[c] = ux; UY[c] = uy; UZ[c] = uz;
  }
}
// END GENERATED lbm bulk kernel

// ───────────────────────── scaling SI <-> lattice ─────────────────────────
/**
 * Fix the lattice units from a length step h (m), a reference speed (m/s) mapped to uLat, and the fluid's kinematic viscosity (m^2/s):
 * dt = uLat h / uRef (s), nuLat = nu dt / h^2. tauFloor keeps BGK stable: when the physical viscosity asks for a smaller relaxation
 * time the lattice viscosity is raised to it and `raised` is true (the simulated Reynolds number is then lower than the real one).
 */
export function lbmScaling({ h_m, uRef, uLat = 0.05, nu = 1.516e-5, tauFloor = 0.505 }) {
  const dt = uLat * h_m / uRef, nuPhys = nu * dt / (h_m * h_m), nuMin = (tauFloor - 0.5) / 3, raised = nuPhys < nuMin;
  return { dt, dx: h_m, uLat, nuLat: Math.max(nuPhys, nuMin), nuPhysLat: nuPhys, raised, velToSI: h_m / dt, velFromSI: dt / h_m, accelToLat: dt * dt / h_m, pressToSI: (rho) => rho / 3 * (h_m / dt) ** 2 };
}

/** Mean kinetic energy of the fluid cells (lattice units), used as the steadiness monitor. */
export function lbmKinetic(S) {
  let e = 0, n = 0; const { flags, ux, uy, uz, N } = S;
  for (let c = 0; c < N; c++) { if ((flags[c] & 7) !== LBM_FLUID) continue; e += ux[c] * ux[c] + uy[c] * uy[c] + uz[c] * uz[c]; n++; }
  return n ? e / n : 0;
}

/** True when a velocity is not a finite number or is beyond the low-Mach limit (the run has diverged). */
export function lbmDiverged(S, limit = 0.57) {
  const { flags, ux, uy, uz, N } = S;
  for (let c = 0; c < N; c += 1) { if ((flags[c] & 7) >= LBM_SOLID && (flags[c] & 7) <= LBM_MOVING) continue; const m = ux[c] * ux[c] + uy[c] * uy[c] + uz[c] * uz[c]; if (!(m < limit * limit)) return true; }
  return false;
}

// ───────────────────────── lattice budget (A5) ─────────────────────────
/** Lattice nodes (the whole box, outside margin included) per quality: 128^3 on a laptop GPU, 64^3 on a phone, much less on the CPU fallback. */
export const LBM_BUDGETS = {
  laptop: { draft: 262144, normal: 1000000, fine: 2097152 },
  phone: { draft: 32768, normal: 110592, fine: 262144 },
  cpu: { draft: 16000, normal: 40000, fine: 100000 },
};
export const LBM_HARD_LIMIT = 2200000;
export function lbmBudget({ quality = 'normal', device = 'laptop', backend = 'cpu' }) {
  if (!['draft', 'normal', 'fine'].includes(quality)) throw new Error('quality must be one of draft, normal, fine');
  const cls = backend === 'cpu' ? 'cpu' : device;
  return { budget: LBM_BUDGETS[cls][quality], cls };
}
