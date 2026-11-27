// Voxel core: linear elasticity on the voxel grid (A3). Every solid voxel is one 8-node hexahedral element with trilinear displacement
// plus the nine incompatible (bubble) modes of Wilson, Taylor et al., condensed out. For a cube (constant Jacobian) this element
// passes the patch test and removes the shear locking of the plain trilinear hexahedron in bending, which matters because a voxel
// model of a beam has only a handful of elements through its depth. The stiffness of a cube is E h K(nu): one constant 24 x 24
// matrix per Poisson ratio, scaled per element by its own E (MPa) and the voxel size h (mm), so the operator is applied matrix-free
// (nothing is assembled). Units of the solver: mm, N, MPa; stress MPa, displacement mm.
//
// DOF numbering: node n has dofs 3n, 3n+1, 3n+2 (x, y, z). Local corner a = ix + 2 iy + 4 iz of an element sits at lattice node
// (i+ix, j+iy, k+iz). Pure typed arrays, no DOM and no imports, like the rest of the core.

export const HEX_FACE_CORNERS = [[1, 3, 5, 7], [0, 2, 4, 6], [2, 3, 6, 7], [0, 1, 4, 5], [4, 5, 6, 7], [0, 1, 2, 3]]; // +x -x +y -y +z -z

/** 6 x 6 isotropic elasticity matrix for E = 1 (engineering shear, order xx yy zz xy yz zx). */
export function isotropicD(nu) {
  const lam = nu / ((1 + nu) * (1 - 2 * nu)), mu = 1 / (2 * (1 + nu)), D = new Float64Array(36);
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) D[6 * i + j] = lam + (i === j ? 2 * mu : 0);
  for (let i = 3; i < 6; i++) D[6 * i + i] = mu;
  return D;
}

/** Solve A X = B for n x n A (row major, destroyed) and n x m B (destroyed, becomes X) by Gauss elimination with partial pivoting. */
function solveDense(A, B, n, m) {
  for (let c = 0; c < n; c++) {
    let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(A[r * n + c]) > Math.abs(A[p * n + c])) p = r;
    if (Math.abs(A[p * n + c]) < 1e-300) throw new Error('singular matrix in the element condensation');
    if (p !== c) { for (let k = 0; k < n; k++) { const t = A[c * n + k]; A[c * n + k] = A[p * n + k]; A[p * n + k] = t; } for (let k = 0; k < m; k++) { const t = B[c * m + k]; B[c * m + k] = B[p * m + k]; B[p * m + k] = t; } }
    for (let r = c + 1; r < n; r++) {
      const f = A[r * n + c] / A[c * n + c]; if (f === 0) continue;
      for (let k = c; k < n; k++) A[r * n + k] -= f * A[c * n + k];
      for (let k = 0; k < m; k++) B[r * m + k] -= f * B[c * m + k];
    }
  }
  for (let c = n - 1; c >= 0; c--) for (let k = 0; k < m; k++) { let s = B[c * m + k]; for (let j = c + 1; j < n; j++) s -= A[c * n + j] * B[j * m + k]; B[c * m + k] = s / A[c * n + c]; }
  return B;
}

const HEX_CACHE = new Map();
/**
 * The element matrices of a unit cube (h = 1, E = 1) for Poisson ratio nu:
 *   K  24 x 24 condensed stiffness (multiply by E h),
 *   Bc 6 x 24 strain-displacement matrix at the centre (divide by h), the strain there is the average strain of the element,
 *   D  6 x 6 elasticity matrix (multiply by E).
 */
export function hexMatrices(nu) {
  const key = nu.toFixed(6); if (HEX_CACHE.has(key)) return HEX_CACHE.get(key);
  if (!(nu >= 0 && nu < 0.5)) throw new Error(`Poisson ratio ${nu} must be from 0 to below 0.5`);
  const D = isotropicD(nu), g = 1 / Math.sqrt(3), Kuu = new Float64Array(576), Kua = new Float64Array(24 * 9), Kaa = new Float64Array(81);
  const sgn = (a, ax) => ((a >> ax) & 1 ? 1 : -1);
  const strain = (xi) => {
    const B = new Float64Array(6 * 24), Ba = new Float64Array(6 * 9);
    for (let a = 0; a < 8; a++) {
      const sx = sgn(a, 0), sy = sgn(a, 1), sz = sgn(a, 2), px = 1 + sx * xi[0], py = 1 + sy * xi[1], pz = 1 + sz * xi[2];
      const dx = 2 * sx * py * pz / 8, dy = 2 * px * sy * pz / 8, dz = 2 * px * py * sz / 8, c = 3 * a;
      B[0 * 24 + c] = dx; B[1 * 24 + c + 1] = dy; B[2 * 24 + c + 2] = dz;
      B[3 * 24 + c] = dy; B[3 * 24 + c + 1] = dx; B[4 * 24 + c + 1] = dz; B[4 * 24 + c + 2] = dy; B[5 * 24 + c] = dz; B[5 * 24 + c + 2] = dx;
    }
    for (let k = 0; k < 3; k++) {
      const gk = -4 * xi[k]; // d/dx_k of the mode 1 - xi_k^2 (xi = 2 x - 1, so d/dx = 2 d/dxi)
      for (let d = 0; d < 3; d++) {
        const col = 3 * k + d;
        if (d === k) Ba[d * 9 + col] += gk;
        else { const row = [3, 4, 5][(d === 0 && k === 1) || (d === 1 && k === 0) ? 0 : (d === 1 && k === 2) || (d === 2 && k === 1) ? 1 : 2]; Ba[row * 9 + col] += gk; }
      }
    }
    return { B, Ba };
  };
  for (const gx of [-g, g]) for (const gy of [-g, g]) for (const gz of [-g, g]) {
    const { B, Ba } = strain([gx, gy, gz]), w = 1 / 8;
    const DB = new Float64Array(6 * 24), DBa = new Float64Array(6 * 9);
    for (let i = 0; i < 6; i++) for (let j = 0; j < 6; j++) { const d = D[6 * i + j]; if (d === 0) continue; for (let c = 0; c < 24; c++) DB[i * 24 + c] += d * B[j * 24 + c]; for (let c = 0; c < 9; c++) DBa[i * 9 + c] += d * Ba[j * 9 + c]; }
    for (let i = 0; i < 6; i++) {
      for (let r = 0; r < 24; r++) { const b = B[i * 24 + r]; if (b === 0) continue; for (let c = 0; c < 24; c++) Kuu[r * 24 + c] += w * b * DB[i * 24 + c]; for (let c = 0; c < 9; c++) Kua[r * 9 + c] += w * b * DBa[i * 9 + c]; }
      for (let r = 0; r < 9; r++) { const b = Ba[i * 9 + r]; if (b === 0) continue; for (let c = 0; c < 9; c++) Kaa[r * 9 + c] += w * b * DBa[i * 9 + c]; }
    }
  }
  // condensation: K = Kuu - Kua Kaa^-1 Kau, with Kau = Kua^T
  const Kau = new Float64Array(9 * 24); for (let r = 0; r < 24; r++) for (let c = 0; c < 9; c++) Kau[c * 24 + r] = Kua[r * 9 + c];
  const X = solveDense(Float64Array.from(Kaa), Float64Array.from(Kau), 9, 24), K = Float64Array.from(Kuu);
  for (let r = 0; r < 24; r++) for (let c = 0; c < 24; c++) { let s = 0; for (let k = 0; k < 9; k++) s += Kua[r * 9 + k] * X[k * 24 + c]; K[r * 24 + c] -= s; }
  for (let r = 0; r < 24; r++) for (let c = r + 1; c < 24; c++) { const m = 0.5 * (K[r * 24 + c] + K[c * 24 + r]); K[r * 24 + c] = m; K[c * 24 + r] = m; }
  const Bc = strain([0, 0, 0]).B, out = { K, Bc, D, nu };
  HEX_CACHE.set(key, out); return out;
}

/**
 * The element and node structure of the solid voxels. `isSolid(materialId)` says which voxels are elements (outside air and enclosed
 * air are not). Returns arrays indexed by element or node; nodes are numbered in order of first use.
 */
export function buildElastic(grid, mat, isSolid) {
  const [nx, ny, nz] = grid.dims, total = grid.total, nxy = nx * ny, lx = nx + 1, lxy = (nx + 1) * (ny + 1);
  let ne = 0; for (let v = 0; v < total; v++) if (isSolid(mat[v])) ne++;
  const elemCell = new Int32Array(ne), elemOf = new Int32Array(total).fill(-1), elemNodes = new Int32Array(8 * ne);
  const lattice = new Int32Array((nx + 1) * (ny + 1) * (nz + 1)).fill(-1);
  let nn = 0, e = 0;
  const nodeLat = [];
  for (let v = 0; v < total; v++) {
    if (!isSolid(mat[v])) continue;
    const i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / nxy;
    elemCell[e] = v; elemOf[v] = e;
    for (let a = 0; a < 8; a++) {
      const li = (i + (a & 1)) + lx * ((j + ((a >> 1) & 1)) + (ny + 1) * (k + (a >> 2)));
      let id = lattice[li]; if (id < 0) { id = nn++; lattice[li] = id; nodeLat.push(li); }
      elemNodes[8 * e + a] = id;
    }
    e++;
  }
  const nodeIJK = new Int32Array(3 * nn);
  for (let q = 0; q < nn; q++) { const li = nodeLat[q], i = li % lx, j = ((li - i) / lx) % (ny + 1), k = (li - i - lx * j) / lxy; nodeIJK[3 * q] = i; nodeIJK[3 * q + 1] = j; nodeIJK[3 * q + 2] = k; }
  const nodeElem = new Int32Array(8 * nn).fill(-1), fill = new Uint8Array(nn);
  for (let q = 0; q < ne; q++) for (let a = 0; a < 8; a++) { const nd = elemNodes[8 * q + a]; nodeElem[8 * nd + fill[nd]++] = q * 8 + a; }
  return { ne, nn, elemCell, elemOf, elemNodes, nodeElem, nodeIJK, lattice, latticeDims: [nx + 1, ny + 1, nz + 1] };
}

/** Is the voxel neighbour of element e through face f (0..5) solid? (false at the edge of the grid) */
export function faceNeighbour(grid, struct, e, f) {
  const [nx, ny, nz] = grid.dims, v = struct.elemCell[e], i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / (nx * ny);
  const di = [1, -1, 0, 0, 0, 0][f], dj = [0, 0, 1, -1, 0, 0][f], dk = [0, 0, 0, 0, 1, -1][f], a = i + di, b = j + dj, c = k + dk;
  if (a < 0 || b < 0 || c < 0 || a >= nx || b >= ny || c >= nz) return -1;
  return struct.elemOf[a + nx * (b + ny * c)];
}

/** The matrix-free elastic operator on the CPU. `scale[e]` = E_e h (N/mm), `mIdx[e]` selects `Ks[m]` (Float64Array 576). `inv` is 1/diag, 0 at constrained dofs. */
export function makeElasticOp({ ne, nn, elemNodes, scale, mIdx, Ks, inv }) {
  const nd = 3 * nn, xe = new Float64Array(24), ye = new Float64Array(24);
  const applyFull = (x, y) => {
    y.fill(0);
    for (let e = 0; e < ne; e++) {
      const K = Ks[mIdx[e]], s = scale[e], o = 8 * e;
      for (let a = 0; a < 8; a++) { const n3 = 3 * elemNodes[o + a]; xe[3 * a] = x[n3]; xe[3 * a + 1] = x[n3 + 1]; xe[3 * a + 2] = x[n3 + 2]; }
      for (let r = 0, q = 0; r < 24; r++, q += 24) { let t = 0; for (let c = 0; c < 24; c++) t += K[q + c] * xe[c]; ye[r] = t * s; }
      for (let a = 0; a < 8; a++) { const n3 = 3 * elemNodes[o + a]; y[n3] += ye[3 * a]; y[n3 + 1] += ye[3 * a + 1]; y[n3 + 2] += ye[3 * a + 2]; }
    }
  };
  return {
    n: nd, nn, inv, applyFull,
    /** y = P A P x: rows and columns of constrained dofs removed (the vectors of the solve are zero there, so only the rows are cleared). */
    apply(x, y) { applyFull(x, y); for (let i = 0; i < nd; i++) if (inv[i] === 0) y[i] = 0; },
  };
}

/** Diagonal of the operator, summed over the elements at each node. */
export function elasticDiagonal({ ne, nn, elemNodes, scale, mIdx, Ks }) {
  const d = new Float64Array(3 * nn);
  for (let e = 0; e < ne; e++) { const K = Ks[mIdx[e]], s = scale[e]; for (let a = 0; a < 8; a++) { const n3 = 3 * elemNodes[8 * e + a]; for (let c = 0; c < 3; c++) d[n3 + c] += s * K[(3 * a + c) * 24 + 3 * a + c]; } }
  return d;
}

/** Jacobi-preconditioned CG for the operator above, as a generator (same contract as pcgSteps in cg.js). `x` is updated in place. */
export function* pcgElastic(op, b, x, { tol = 1e-8, maxIter = 20000, chunk = 10 } = {}) {
  const n = op.n, inv = op.inv, r = new Float64Array(n), z = new Float64Array(n), p = new Float64Array(n), q = new Float64Array(n);
  op.apply(x, q);
  let bn = 0; for (let i = 0; i < n; i++) { r[i] = b[i] - q[i]; bn += b[i] * b[i]; }
  bn = Math.sqrt(bn) || 1;
  let rz = 0, rr = 0;
  for (let i = 0; i < n; i++) { z[i] = r[i] * inv[i]; p[i] = z[i]; rz += r[i] * z[i]; rr += r[i] * r[i]; }
  let rel = Math.sqrt(rr) / bn, it = 0;
  while (rel > tol && it < maxIter) {
    op.apply(p, q);
    let pq = 0; for (let i = 0; i < n; i++) pq += p[i] * q[i];
    if (!(pq > 0)) break;
    const alpha = rz / pq; let rzn = 0; rr = 0;
    for (let i = 0; i < n; i++) { x[i] += alpha * p[i]; r[i] -= alpha * q[i]; z[i] = r[i] * inv[i]; rzn += r[i] * z[i]; rr += r[i] * r[i]; }
    const beta = rz > 0 ? rzn / rz : 0; rz = rzn;
    for (let i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
    it++; rel = Math.sqrt(rr) / bn;
    if (it % chunk === 0) yield { iter: it, rel };
  }
  return { iters: it, rel, converged: rel <= tol };
}

/** CPU backend of the elastic solve: same shape as cpuBackend (cg.js): createOperator -> { solveSync, solve, destroy }. */
export const cpuElasticBackend = {
  kind: 'cpu', label: 'CPU (JS typed arrays)',
  async createOperator(spec) {
    const op = makeElasticOp(spec);
    return {
      kind: 'cpu', n: op.n, op,
      solveSync(b, x0, o) { const x = Float64Array.from(x0); const r = runSyncGen(pcgElastic(op, b, x, o)); return { x, ...r }; },
      async solve(b, x0, o = {}) {
        const x = Float64Array.from(x0), gen = pcgElastic(op, b, x, o); let s = gen.next();
        while (!s.done) { if (o.signal && o.signal.aborted) throw new Error('cancelled'); if (o.onStep) o.onStep(s.value); await new Promise((res) => setTimeout(res, 0)); s = gen.next(); }
        return { x, ...s.value };
      },
      destroy() {},
    };
  },
};
function runSyncGen(gen) { let s = gen.next(); while (!s.done) s = gen.next(); return s.value; }

/**
 * Do the constraints remove every rigid-body motion of every connected piece? `constrained(dof)` says whether a dof is held. For each
 * connected piece (elements that share nodes) the six rigid motions are tested against the held dofs: the 6 x 6 Gram matrix of the
 * constraint rows must be non-singular. Returns [{ piece, nodes, free: number of unrestrained motions }] for the pieces that are not
 * fully supported (empty when all are). Positions are lattice coordinates times h.
 */
export function unsupportedPieces(struct, constrained, h) {
  const { nn, ne, elemNodes, nodeIJK } = struct, parent = new Int32Array(nn); for (let i = 0; i < nn; i++) parent[i] = i;
  const find = (a) => { while (parent[a] !== a) { parent[a] = parent[parent[a]]; a = parent[a]; } return a; };
  for (let e = 0; e < ne; e++) { const r0 = find(elemNodes[8 * e]); for (let a = 1; a < 8; a++) { const r1 = find(elemNodes[8 * e + a]); if (r1 !== r0) parent[r1] = r0; } }
  const pieces = new Map();
  for (let q = 0; q < nn; q++) { const r = find(q); let p = pieces.get(r); if (!p) { p = { nodes: [], c: [0, 0, 0] }; pieces.set(r, p); } p.nodes.push(q); for (let k = 0; k < 3; k++) p.c[k] += nodeIJK[3 * q + k] * h; }
  const bad = []; let idx = 0;
  for (const p of pieces.values()) {
    idx++; const c = p.c.map((v) => v / p.nodes.length); let L = 0;
    for (const q of p.nodes) for (let k = 0; k < 3; k++) L = Math.max(L, Math.abs(nodeIJK[3 * q + k] * h - c[k]));
    L = L || 1;
    const M = new Float64Array(36);
    for (const q of p.nodes) for (let d = 0; d < 3; d++) {
      if (!constrained(3 * q + d)) continue;
      const r = [nodeIJK[3 * q] * h - c[0], nodeIJK[3 * q + 1] * h - c[1], nodeIJK[3 * q + 2] * h - c[2]], e = [0, 0, 0]; e[d] = 1;
      const row = [e[0], e[1], e[2], (r[1] * e[2] - r[2] * e[1]) / L, (r[2] * e[0] - r[0] * e[2]) / L, (r[0] * e[1] - r[1] * e[0]) / L];
      for (let a = 0; a < 6; a++) for (let b = 0; b < 6; b++) M[6 * a + b] += row[a] * row[b];
    }
    const ev = jacobiEigenvalues(M, 6), mx = Math.max(...ev);
    const freeCount = mx > 0 ? ev.filter((v) => v < 1e-7 * mx).length : 6;
    if (freeCount > 0) bad.push({ piece: idx, nodes: p.nodes.length, free: freeCount });
  }
  return bad;
}

/** Eigenvalues of a symmetric n x n matrix (cyclic Jacobi). */
export function jacobiEigenvalues(M, n) {
  const A = Float64Array.from(M);
  for (let sweep = 0; sweep < 60; sweep++) {
    let off = 0; for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) off += A[i * n + j] ** 2;
    if (off < 1e-30) break;
    for (let p = 0; p < n; p++) for (let q = p + 1; q < n; q++) {
      const apq = A[p * n + q]; if (Math.abs(apq) < 1e-300) continue;
      const th = (A[q * n + q] - A[p * n + p]) / (2 * apq), t = Math.sign(th || 1) / (Math.abs(th) + Math.sqrt(th * th + 1)), c = 1 / Math.sqrt(t * t + 1), s = t * c;
      for (let k = 0; k < n; k++) { const kp = A[k * n + p], kq = A[k * n + q]; A[k * n + p] = c * kp - s * kq; A[k * n + q] = s * kp + c * kq; }
      for (let k = 0; k < n; k++) { const pk = A[p * n + k], qk = A[q * n + k]; A[p * n + k] = c * pk - s * qk; A[q * n + k] = s * pk + c * qk; }
    }
  }
  return Array.from({ length: n }, (_, i) => A[i * n + i]);
}

/** Stress (MPa, 6 components) at the centre of one element from its 24 nodal displacements, for modulus E (MPa), voxel size h (mm). */
export function elementStress(M, E, h, ue, out = new Float64Array(6)) {
  const eps = new Float64Array(6);
  for (let r = 0; r < 6; r++) { let s = 0; for (let c = 0; c < 24; c++) s += M.Bc[r * 24 + c] * ue[c]; eps[r] = s / h; }
  for (let r = 0; r < 6; r++) { let s = 0; for (let c = 0; c < 6; c++) s += M.D[6 * r + c] * eps[c]; out[r] = E * s; }
  return out;
}

/** von Mises stress of a stress vector (xx yy zz xy yz zx). */
export const vonMises = (s) => Math.sqrt(Math.max(0, 0.5 * ((s[0] - s[1]) ** 2 + (s[1] - s[2]) ** 2 + (s[2] - s[0]) ** 2) + 3 * (s[3] * s[3] + s[4] * s[4] + s[5] * s[5])));
