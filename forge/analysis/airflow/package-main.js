// Airflow (A5): lattice Boltzmann flow (D3Q19, Smagorinsky large-eddy closure) on the voxel grid of the design, on WebGPU with a CPU
// fallback. This file is concatenated after the core files (grid.js, lbm.js, gpu-lbm.js) by tools/bundle-ext.mjs into
// examples/ext/airflow/main.js, so the names of the core are in scope here.
//
// Phase 1 (forced flow, validity `estimate`): the domain is a box around the model (the enclosure interior plus a margin outside the
// vents). Walls are bounce-back; the upstream face (and the sides) carry the free-stream velocity, the downstream face is a pressure
// outlet; fans are a body force on a disc with a linear pressure/flow curve. Outputs: speed, velocity (arrows, streamlines), gauge
// pressure, the flow through each vent, dead zones.
// Phase 2 (mode `natural`, validity `experimental`, CPU only): a D3Q7 temperature lattice with Boussinesq buoyancy, coupled to the
// conduction of the solid bodies (the same lattice, with a per-cell diffusivity), heat sources from components and bodies.
// Units outside the solver: mm for geometry, SI for physics. Inside the solver: lattice units (lbm.js), mapped by `lbmScaling`.
const AF_ID = 'xyz.mdhv.airflow';
const FACE_LIST = ['+x', '-x', '+y', '-y', '+z', '-z'];
const FACE_VEC = { '+x': [1, 0, 0], '-x': [-1, 0, 0], '+y': [0, 1, 0], '-y': [0, -1, 0], '+z': [0, 0, 1], '-z': [0, 0, -1] };
const G_STD = 9.80665;
const L_PER_S_TO_CFM = 2.118880003;
const BOARD_PROPS = { name: 'PCB (assumed: FR-4)', k: 0.29 };
const PART_PROPS = { name: 'electronic part (assumed)', k: 5 };

const DEFAULT_SETTINGS = {
  quality: 'normal', backend: 'auto', mode: 'forced', flow_dir: '+x', speed_ms: 0, margin_mm: 0, flow_throughs: 6, max_seconds: 90,
  smagorinsky: 0.1, min_tau: 0.51, dead_frac: 0.05, streamlines: 150, h_mm: 0, voxelizer: 'conservative', fans: [], delta_T_guess_K: 0,
};

/** Validate the stored settings. Unknown or invalid entries fall back to defaults and say so. */
function parseSettings(raw) {
  const s = JSON.parse(JSON.stringify(DEFAULT_SETTINGS)), warnings = [], r = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const en = (k, opts) => { if (r[k] === undefined) return; if (opts.includes(r[k])) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} is not one of ${opts.join(', ')}; ${s[k]} is used.`); };
  const nu = (k, lo, hi) => { if (r[k] === undefined) return; if (typeof r[k] === 'number' && Number.isFinite(r[k]) && r[k] >= lo && r[k] <= hi) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} must be a number from ${lo} to ${hi}; ${s[k]} is used.`); };
  en('quality', ['draft', 'normal', 'fine']); en('backend', ['auto', 'gpu', 'cpu']); en('mode', ['forced', 'natural']); en('flow_dir', FACE_LIST); en('voxelizer', ['conservative', 'centre']);
  nu('speed_ms', 0, 100); nu('margin_mm', 0, 5000); nu('flow_throughs', 1, 200); nu('max_seconds', 5, 7200); nu('smagorinsky', 0, 0.3); nu('min_tau', 0.501, 1); nu('dead_frac', 0.005, 0.5); nu('streamlines', 0, 600); nu('h_mm', 0, 200); nu('delta_T_guess_K', 0, 300);
  if (r.fans !== undefined) {
    const out = [];
    for (const f of Array.isArray(r.fans) ? r.fans : []) {
      if (f && Array.isArray(f.center) && f.center.length === 3 && f.center.every(Number.isFinite) && FACE_LIST.includes(f.dir) && Number.isFinite(f.radius_mm) && f.radius_mm > 0 && Number.isFinite(f.dp_Pa) && f.dp_Pa > 0 && f.dp_Pa <= 5000 && (f.q_m3s == null || (Number.isFinite(f.q_m3s) && f.q_m3s > 0)))
        out.push({ center: f.center.slice(), dir: f.dir, radius_mm: f.radius_mm, thickness_mm: Number.isFinite(f.thickness_mm) && f.thickness_mm > 0 ? f.thickness_mm : 0, dp_Pa: f.dp_Pa, q_m3s: f.q_m3s == null ? 0 : f.q_m3s });
      else warnings.push(`A fan was ignored (needs center [x,y,z] mm, dir +x..-z, radius_mm > 0, dp_Pa in (0, 5000] and optionally thickness_mm and q_m3s): ${JSON.stringify(f).slice(0, 90)}.`);
    }
    s.fans = out;
  }
  return { settings: s, warnings };
}

/** Air at ambient temperature T (K), 1 atm: density, kinematic viscosity (Sutherland), conductivity, cp, Prandtl, expansion coefficient (ideal gas). */
function airProps(T) {
  const rho = 101325 / (287.05 * T), mu = 1.458e-6 * T ** 1.5 / (T + 110.4), cp = 1007, k = 0.0262 * (T / 300) ** 0.82;
  return { rho, nu: mu / rho, mu, cp, k, Pr: mu * cp / k, beta: 1 / T };
}

// ───────────────────────── the model ─────────────────────────
function bboxOfBodies(bodies) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const b of bodies) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], b.bbox.min[k]); hi[k] = Math.max(hi[k], b.bbox.max[k]); }
  return { lo, hi };
}

/**
 * Voxelize the design into a lattice box and mark the cells.
 * Returns { grid, mat, flags, h, bodyBox, owners, fluid(), counts, ... }. The cells outside the body box are open air; the box faces are
 * boundary cells: the upstream face and the sides carry the free stream, the downstream face is the pressure outlet (every face is an
 * outlet when there is no free stream).
 */
function buildDomain(input, settings, ctx) {
  const S = input.setup, bodies = input.bodies, warnings = [];
  if (!bodies.length) throw new Error('there is no built body to analyse');
  const bb = bboxOfBodies(bodies), size = [0, 1, 2].map((k) => bb.hi[k] - bb.lo[k]), maxDim = Math.max(...size);
  const owners = [null], bodyOwner = new Map();
  for (const b of bodies) {
    const sb = S.bodies.find((x) => x.name === b.name), p = sb.material.props;
    owners.push({ id: owners.length, name: b.name, kind: 'body', k: p.k > 0 ? p.k : 0.2, material: sb.material.name, assumed: sb.assumed, props: p });
    bodyOwner.set(b.name, owners.length - 1);
    if (owners.length > MAX_OWNERS) throw new Error('too many bodies and parts for one grid');
  }
  const partOwners = [];
  for (const c of (S.pcb && S.pcb.components) || []) {
    const role = c.role || 'component'; if (role !== 'component' && role !== 'board') continue; if (!(c.box || c.cyl)) continue;
    if (owners.length > MAX_OWNERS) { warnings.push('Too many PCB parts for one grid; the rest are ignored.'); break; }
    const props = role === 'board' ? BOARD_PROPS : PART_PROPS;
    owners.push({ id: owners.length, name: c.name, kind: role, k: props.k, material: props.name, box: c.box || null, cyl: c.cyl || null, cylKind: c.kind === 'cylinder' });
    partOwners.push(owners.length - 1);
  }
  const free = settings.freeSpeed > 0, ax = FACE_LIST.indexOf(settings.flow_dir) >> 1, sgn = settings.flow_dir[0] === '+' ? 1 : -1;
  const m = settings.margin_mm > 0 ? settings.margin_mm : Math.max(10, (free ? 0.35 : 0.2) * maxDim);
  const lo = bb.lo.map((v) => v - m), hi = bb.hi.map((v) => v + m);
  if (free) { if (sgn > 0) hi[ax] += 1.5 * m; else lo[ax] -= 1.5 * m; }
  const box = { lo, hi }, vol = (hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2]);
  const { budget, cls } = lbmBudget({ quality: settings.quality, device: ctx.device, backend: ctx.backendKind });
  let h = settings.h_mm > 0 ? settings.h_mm : Math.cbrt(vol / budget);
  const meshes = bodies.map((b) => ({ positions: b.positions, indices: b.indices, id: bodyOwner.get(b.name) }));
  const painted = {}, paint = (grid, mat) => { for (const id of partOwners) { const o = owners[id]; painted[id] = o.cylKind && o.cyl ? paintCylinder(grid, mat, o.cyl, id) : paintBox(grid, mat, o.box, id); } };
  let vm = null;
  for (let tries = 0; tries < 6; tries++) {
    const g = makeGrid(lo, hi, h);
    if (g.total > LBM_HARD_LIMIT) { if (settings.h_mm > 0) throw new Error(`${g.total.toLocaleString('en')} lattice cells exceed the limit of ${LBM_HARD_LIMIT.toLocaleString('en')}; use a voxel size of at least ${Math.cbrt(vol / LBM_HARD_LIMIT).toFixed(3)} mm`); h *= Math.cbrt(g.total / LBM_HARD_LIMIT) * 1.01; continue; }
    vm = buildVoxelModel({ meshes, bbox: box, paint, conservative: settings.voxelizer !== 'centre', h, budget, maxActive: Infinity, maxTotal: LBM_HARD_LIMIT }); break;
  }
  if (!vm) throw new Error('could not fit the model into the lattice limit');
  const { grid, mat } = vm, [nx, ny, nz] = grid.dims, N = grid.total, flags = new Uint8Array(N);
  let solid = 0, fluid = 0;
  for (let c = 0; c < N; c++) { if (mat[c] !== 0 && mat[c] !== CAVITY) { flags[c] = LBM_SOLID; solid++; } else fluid++; }
  // body box in cell indices: the wall layer sits at floor(lo) .. ceil(hi) - 1
  const bi0 = [0, 1, 2].map((k) => Math.max(0, Math.floor((bb.lo[k] - grid.origin[k]) / grid.h + 1e-6))), bi1 = [0, 1, 2].map((k) => Math.min(grid.dims[k], Math.ceil((bb.hi[k] - grid.origin[k]) / grid.h - 1e-6)));
  return { grid, mat, flags, h: grid.h, N, dims: grid.dims, bb, bi0, bi1, owners, bodyOwner, partOwners, bodies, solid, fluid, budget, cls, maxDim, free, ax, sgn, margin: m, warnings, nx, ny, nz };
}

/**
 * Mark the faces of the box as boundary cells (outlet beats inlet beats side). With a free stream the upstream face and the four sides
 * are LBM_VEL cells carrying the free-stream vector (on the sides it is tangential), the downstream face is LBM_PRES; without one every
 * face is LBM_PRES (open, gauge pressure 0). A boundary cell's face index is the direction towards its fluid neighbour.
 */
function markBoundaries(D, uVel) {
  const { flags, dims: [nx, ny, nz], ax, sgn, free } = D, idx = [nx, ny, nz];
  const outletFace = 2 * ax + (sgn > 0 ? 1 : 0); // lbm face of an outlet cell: neighbour is -axis when the cell is at the max end
  let nIn = 0, nOut = 0;
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
    if (x > 0 && x < nx - 1 && y > 0 && y < ny - 1 && z > 0 && z < nz - 1) continue;
    const c = x + nx * (y + ny * z); if ((flags[c] & 7) === LBM_SOLID) continue;
    const p = [x, y, z], faces = [];
    for (let a = 0; a < 3; a++) { if (p[a] === 0) faces.push(2 * a); if (p[a] === idx[a] - 1) faces.push(2 * a + 1); }
    if (!free) { flags[c] = boundaryFlag(LBM_PRES, faces[0]); nOut++; continue; }
    if (faces.includes(outletFace)) { flags[c] = boundaryFlag(LBM_PRES, outletFace); nOut++; continue; }
    const inletFace = 2 * ax + (sgn > 0 ? 0 : 1);
    flags[c] = boundaryFlag(LBM_VEL, faces.includes(inletFace) ? inletFace : faces[0]); nIn++;
  }
  return { inlet: nIn, outlet: nOut, uVel };
}

// ───────────────────────── fans ─────────────────────────
/** Fans as a body force on a disc. Returns [{ cells, mid, dir, dp0, qmax, area, thick_m, dp }] and fills `force` (3 floats per cell, lattice units). */
function buildFans(D, settings, sc, air, force) {
  const out = [], warnings = [], { grid, flags, h } = D, [nx, ny] = grid.dims, hM = h * 1e-3;
  for (const f of settings.fans) {
    const a = FACE_LIST.indexOf(f.dir) >> 1, u = FACE_VEC[f.dir], o = [0, 1, 2].filter((q) => q !== a), t = Math.max(f.thickness_mm || 0, 2 * h), cells = [], mid = [];
    const lo = [0, 1, 2].map((q) => Math.max(0, Math.floor((f.center[q] - (q === a ? t / 2 : f.radius_mm) - grid.origin[q]) / h))), hi = [0, 1, 2].map((q) => Math.min(grid.dims[q] - 1, Math.ceil((f.center[q] + (q === a ? t / 2 : f.radius_mm) - grid.origin[q]) / h)));
    for (let k = lo[2]; k <= hi[2]; k++) for (let j = lo[1]; j <= hi[1]; j++) for (let i = lo[0]; i <= hi[0]; i++) {
      const c = i + nx * (j + ny * k); if ((flags[c] & 7) !== LBM_FLUID) continue;
      const p = [grid.origin[0] + (i + 0.5) * h, grid.origin[1] + (j + 0.5) * h, grid.origin[2] + (k + 0.5) * h];
      if (Math.hypot(p[o[0]] - f.center[o[0]], p[o[1]] - f.center[o[1]]) > f.radius_mm || Math.abs(p[a] - f.center[a]) > t / 2) continue;
      cells.push(c); if (Math.abs(p[a] - f.center[a]) <= h / 2 + 1e-9) mid.push(c);
    }
    if (!cells.length || !mid.length) { warnings.push(`A fan at ${f.center.join(', ')} mm has no free voxels at this resolution (${h.toFixed(2)} mm); it is ignored.`); continue; }
    const thick_m = (cells.length / mid.length) * hM;
    out.push({ cells: Int32Array.from(cells), mid: Int32Array.from(mid), dir: u, dp0: f.dp_Pa, qmax: f.q_m3s, area: mid.length * hM * hM, thick_m, dp: f.dp_Pa, radius: f.radius_mm, center: f.center });
  }
  const setForce = () => {
    force.fill(0);
    for (const f of out) { const a = f.dp / (air.rho * f.thick_m) * sc.accelToLat; for (const c of f.cells) { force[3 * c] += a * f.dir[0]; force[3 * c + 1] += a * f.dir[1]; force[3 * c + 2] += a * f.dir[2]; } }
  };
  setForce();
  return { fans: out, setForce, warnings };
}

/** Flow through each fan (m3/s) from the macroscopic velocity and the relaxed new pressure rise by the fan curve; returns true if a force changed. */
function updateFans(fans, M, sc, hM) {
  let changed = false;
  for (const f of fans) {
    let q = 0; for (const c of f.mid) q += (M.ux[c] * f.dir[0] + M.uy[c] * f.dir[1] + M.uz[c] * f.dir[2]) * sc.velToSI * hM * hM;
    f.q = q; const want = f.qmax > 0 ? f.dp0 * Math.max(0, 1 - q / f.qmax) : f.dp0, next = 0.5 * f.dp + 0.5 * want;
    if (Math.abs(next - f.dp) > 1e-4 * f.dp0) changed = true; f.dp = next;
  }
  return changed;
}

// ───────────────────────── vents and dead zones ─────────────────────────
/**
 * Vents: the connected openings in the wall layers of the body's bounding box. For each of the six faces of the box the cells of the layer
 * where the wall would be that are fluid, grouped by 4-connectivity in the plane; the flow through a cell is the velocity along the
 * outward normal times the cell face. Returns [{ face, cells, area_m2, in_m3s, out_m3s, centre }] (flow in SI; inflow into the box positive).
 */
function findVents(D, M, sc) {
  const { dims: [nx, ny, nz], flags, bi0, bi1, h, grid } = D, hM = h * 1e-3, vents = [], dims = [nx, ny, nz];
  const comp = [0, 1, 2], vel = [M.ux, M.uy, M.uz];
  for (let a = 0; a < 3; a++) for (const side of [0, 1]) {
    const layer = side === 0 ? bi0[a] : bi1[a] - 1, u = comp.filter((q) => q !== a), seen = new Set();
    if (layer < 0 || layer >= dims[a]) continue;
    const at = (p, q) => { const c = [0, 0, 0]; c[a] = layer; c[u[0]] = p; c[u[1]] = q; return c[0] + nx * (c[1] + ny * c[2]); };
    for (let q = bi0[u[1]]; q < bi1[u[1]]; q++) for (let p = bi0[u[0]]; p < bi1[u[0]]; p++) {
      const c0 = at(p, q); if (seen.has(c0) || (flags[c0] & 7) === LBM_SOLID) continue;
      const stack = [[p, q]], cells = []; seen.add(c0);
      while (stack.length) {
        const [pp, qq] = stack.pop(), c = at(pp, qq); cells.push(c);
        for (const [dp, dq] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          const p2 = pp + dp, q2 = qq + dq; if (p2 < bi0[u[0]] || p2 >= bi1[u[0]] || q2 < bi0[u[1]] || q2 >= bi1[u[1]]) continue;
          const c2 = at(p2, q2); if (seen.has(c2) || (flags[c2] & 7) === LBM_SOLID) continue; seen.add(c2); stack.push([p2, q2]);
        }
      }
      let qin = 0, qout = 0, cx = 0, cy = 0, cz = 0;
      for (const c of cells) {
        const un = (side === 0 ? -1 : 1) * vel[a][c] * sc.velToSI * hM * hM; // outward normal flow, m3/s
        if (un < 0) qin += -un; else qout += un;
        const i = c % nx, j = ((c - i) / nx) % ny, k = (c - i - nx * j) / (nx * ny); cx += i; cy += j; cz += k;
      }
      const n = cells.length;
      vents.push({ face: FACE_LIST[2 * a + (side === 0 ? 1 : 0)], cells, area_m2: n * hM * hM, in_m3s: qin, out_m3s: qout, centre: [grid.origin[0] + (cx / n + 0.5) * h, grid.origin[1] + (cy / n + 0.5) * h, grid.origin[2] + (cz / n + 0.5) * h] });
    }
  }
  return vents.sort((x, y) => y.cells.length - x.cells.length);
}

// ───────────────────────── streamlines ─────────────────────────
/**
 * Streamlines of a velocity field (SI, on the lattice) as one `vectors` overlay: seeds are given as mm positions; each line is followed by a
 * midpoint rule with steps of half a voxel until it leaves the box, enters a wall, stalls (speed below `minSpeed`) or reaches `maxSteps`.
 * Returns { positions, vectors, lines } (Float32Array, Float32Array, Uint32Array of line starts) or null if nothing was drawn.
 */
function traceStreamlines({ grid, flags, U, seeds, minSpeed, maxSteps = 700, maxPoints = 300000 }) {
  const [nx, ny, nz] = grid.dims, h = grid.h, o = grid.origin;
  const sample = (x, y, z, out) => {
    const fx = (x - o[0]) / h - 0.5, fy = (y - o[1]) / h - 0.5, fz = (z - o[2]) / h - 0.5; if (fx < -0.5 || fy < -0.5 || fz < -0.5 || fx > nx - 0.5 || fy > ny - 0.5 || fz > nz - 0.5) return false;
    const i0 = Math.min(nx - 2, Math.max(0, Math.floor(fx))), j0 = Math.min(ny - 2, Math.max(0, Math.floor(fy))), k0 = Math.min(nz - 2, Math.max(0, Math.floor(fz))), tx = Math.min(1, Math.max(0, fx - i0)), ty = Math.min(1, Math.max(0, fy - j0)), tz = Math.min(1, Math.max(0, fz - k0));
    out[0] = out[1] = out[2] = 0;
    for (let dk = 0; dk < 2; dk++) for (let dj = 0; dj < 2; dj++) for (let di = 0; di < 2; di++) {
      const c = (i0 + di) + nx * ((j0 + dj) + ny * (k0 + dk)), w = (di ? tx : 1 - tx) * (dj ? ty : 1 - ty) * (dk ? tz : 1 - tz);
      out[0] += w * U[3 * c]; out[1] += w * U[3 * c + 1]; out[2] += w * U[3 * c + 2];
    }
    return true;
  };
  const isWall = (x, y, z) => { const i = Math.floor((x - o[0]) / h), j = Math.floor((y - o[1]) / h), k = Math.floor((z - o[2]) / h); return i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz || (flags[i + nx * (j + ny * k)] & 7) === LBM_SOLID; };
  const P = [], V = [], L = [], v1 = [0, 0, 0], v2 = [0, 0, 0];
  for (const s of seeds) {
    if (P.length / 3 > maxPoints) break;
    let x = s[0], y = s[1], z = s[2]; const start = P.length / 3; let n = 0;
    if (isWall(x, y, z)) continue;
    for (; n < maxSteps; n++) {
      if (!sample(x, y, z, v1)) break; const sp = Math.hypot(v1[0], v1[1], v1[2]); if (sp < minSpeed) break;
      P.push(x, y, z); V.push(v1[0], v1[1], v1[2]);
      const ds = 0.5 * h; if (!sample(x + 0.5 * ds * v1[0] / sp, y + 0.5 * ds * v1[1] / sp, z + 0.5 * ds * v1[2] / sp, v2)) break; const s2 = Math.hypot(v2[0], v2[1], v2[2]); if (s2 < minSpeed) break;
      x += ds * v2[0] / s2; y += ds * v2[1] / s2; z += ds * v2[2] / s2; if (isWall(x, y, z)) break;
    }
    if (n >= 3) L.push(start); else { P.length = start * 3; V.length = start * 3; }
  }
  if (!L.length) return null;
  return { positions: Float32Array.from(P), vectors: Float32Array.from(V), lines: Uint32Array.from(L) };
}

/** Seeds for the streamlines: across the vents' inflow cells first, then spread through the fluid of the box and over the inlet plane. Deterministic. */
function pickSeeds(D, M, sc, vents, count) {
  const { grid, flags, h, dims: [nx, ny, nz], bi0, bi1, free, ax, sgn } = D, seeds = [], vel = [M.ux, M.uy, M.uz];
  let rng = 12345; const rnd = () => { rng = (rng * 1664525 + 1013904223) >>> 0; return rng / 4294967296; };
  const pos = (c) => { const i = c % nx, j = ((c - i) / nx) % ny, k = (c - i - nx * j) / (nx * ny); return [grid.origin[0] + (i + 0.5) * h, grid.origin[1] + (j + 0.5) * h, grid.origin[2] + (k + 0.5) * h]; };
  const inflowSeeds = [];
  for (const v of vents) for (const c of v.cells) { const a = FACE_LIST.indexOf(v.face) >> 1, outward = v.face[0] === '+' ? 1 : -1; if (vel[a][c] * outward < -1e-9) inflowSeeds.push(c); }
  const nIn = Math.min(Math.floor(count * 0.5), inflowSeeds.length);
  for (let s = 0; s < nIn; s++) seeds.push(pos(inflowSeeds[Math.floor((s + rnd() * 0.999) * inflowSeeds.length / nIn)]));
  if (free) { // a grid of seeds on the upstream plane
    const m = Math.ceil(Math.sqrt(count * 0.25)), u = [0, 1, 2].filter((q) => q !== ax), plane = sgn > 0 ? 2 : D.dims[ax] - 3;
    for (let q = 0; q < m; q++) for (let p = 0; p < m; p++) { const c3 = [0, 0, 0]; c3[ax] = plane; c3[u[0]] = Math.floor((p + 0.5) / m * D.dims[u[0]]); c3[u[1]] = Math.floor((q + 0.5) / m * D.dims[u[1]]); seeds.push(pos(c3[0] + nx * (c3[1] + ny * c3[2]))); }
  }
  let guard = 0;
  while (seeds.length < count && guard++ < count * 40) { // inside the body box, where the interesting flow is
    const c = [0, 1, 2].map((q) => Math.floor(bi0[q] + rnd() * (bi1[q] - bi0[q]))), id = c[0] + nx * (c[1] + ny * c[2]);
    if ((flags[id] & 7) === LBM_FLUID) seeds.push(pos(id));
  }
  return seeds;
}

// ───────────────────────── the simulation driver ─────────────────────────
const yieldNow = () => new Promise((r) => setTimeout(r, 0));

/** A CPU "device" with the same interface as the GPU one: step(k), macro(). */
function cpuDevice(S) { return { kind: 'cpu', S, async step(k) { lbmAdvance(S, k); }, macro: async () => ({ rho: S.rho, ux: S.ux, uy: S.uy, uz: S.uz }), setForce(a) { S.force = a; }, destroy() {} }; }

/**
 * Build the lattice, run it to a steady state (or the time cap) and average the last samples. Returns everything the result needs.
 * plan: { uRef (m/s), uLat, mode, ... } is computed by the caller; ctx: { progress(f), signal }.
 */
async function drive({ D, sim, dev, sc, air, fans, th, settings, ctx, plan }) {
  const t0 = Date.now(), N = D.N, fluidIdx = [];
  for (let c = 0; c < N; c++) if ((D.flags[c] & 7) === LBM_FLUID) fluidIdx.push(c);
  const nf = fluidIdx.length, stride = Math.max(1, Math.floor(nf / 200000));
  const keOf = (M) => { let e = 0, n = 0, mx = 0; for (let q = 0; q < nf; q += stride) { const c = fluidIdx[q], m = M.ux[c] * M.ux[c] + M.uy[c] * M.uy[c] + M.uz[c] * M.uz[c]; e += m; n++; if (m > mx) mx = m; if (!(m < 1)) return { ke: NaN, umax: Math.sqrt(mx) }; } return { ke: e / n, umax: Math.sqrt(mx) }; };
  const checkEvery = Math.max(25, Math.round(plan.steps / 40)), chunk = dev.kind === 'gpu' ? Math.min(20, checkEvery) : Math.max(1, Math.min(checkEvery, Math.round(1.2e6 / N)));
  let steps = 0, avgN = 0, steady = 0, lastKe = 0, converged = false, capped = false, umaxSeen = 0, sums = null, M = null, stopAfter = Infinity, keHist = [], tHist = [];
  const avgNeeded = th ? 6 : 5, hardMax = plan.steps * (th ? 3 : 1.6);
  while (steps < hardMax) {
    if (ctx.signal && ctx.signal.aborted) throw new Error('cancelled');
    let k = Math.min(chunk, checkEvery - (steps % checkEvery) || checkEvery); await dev.step(k); steps += k;
    if (dev.kind === 'cpu') await yieldNow();
    const secs = (Date.now() - t0) / 1000; ctx.progress(Math.min(0.97, 0.12 + 0.82 * Math.max(Math.min(1, steps / plan.steps), secs / settings.max_seconds)));
    if (steps % checkEvery !== 0) continue;
    M = await dev.macro(); const { ke, umax } = keOf(M); umaxSeen = Math.max(umaxSeen, umax);
    if (!Number.isFinite(ke) || umax > 0.5) throw Object.assign(new Error(`the flow became unstable at step ${steps} (lattice speed ${Number.isFinite(umax) ? umax.toFixed(2) : 'NaN'}): use a finer resolution, a lower speed or a larger smagorinsky constant`), { unstable: true });
    if (fans.fans.length && updateFans(fans.fans, M, sc, D.h * 1e-3)) { fans.setForce(); dev.setForce(plan.force); }
    const rel = lastKe > 0 ? Math.abs(ke - lastKe) / lastKe : 1; lastKe = ke; keHist.push(ke);
    let tmax = 0; if (th) { for (let c = 0; c < N; c++) if (th.T[c] > tmax) tmax = th.T[c]; tHist.push(tmax); }
    const tRel = th && tHist.length > 1 ? Math.abs(tHist[tHist.length - 1] - tHist[tHist.length - 2]) / Math.max(1e-9, Math.abs(tmax)) : 0;
    if (steady < 3 && steps >= 0.4 * plan.steps && rel < 2e-3 && (!th || tRel < 1e-3)) steady++; else if (rel >= 2e-3 || (th && tRel >= 1e-3)) steady = 0;
    if (steps >= 0.4 * plan.steps && (steady >= 2 || steps >= 0.7 * plan.steps || secs > 0.7 * settings.max_seconds)) { // averaging window
      if (!sums) sums = { ux: new Float64Array(N), uy: new Float64Array(N), uz: new Float64Array(N), rho: new Float64Array(N), T: th ? new Float64Array(N) : null };
      for (let c = 0; c < N; c++) { sums.ux[c] += M.ux[c]; sums.uy[c] += M.uy[c]; sums.uz[c] += M.uz[c]; sums.rho[c] += M.rho[c]; if (th) sums.T[c] += th.T[c]; }
      avgN++; if (avgN >= avgNeeded) { converged = steady >= 2; break; }
    }
    if (secs > settings.max_seconds) { capped = true; break; }
  }
  if (!sums) { M = M || await dev.macro(); sums = { ux: Float64Array.from(M.ux), uy: Float64Array.from(M.uy), uz: Float64Array.from(M.uz), rho: Float64Array.from(M.rho), T: th ? Float64Array.from(th.T) : null }; avgN = 1; }
  const mean = { ux: new Float32Array(N), uy: new Float32Array(N), uz: new Float32Array(N), rho: new Float32Array(N), T: th ? new Float32Array(N) : null };
  for (let c = 0; c < N; c++) { mean.ux[c] = sums.ux[c] / avgN; mean.uy[c] = sums.uy[c] / avgN; mean.uz[c] = sums.uz[c] / avgN; mean.rho[c] = sums.rho[c] / avgN; if (th) mean.T[c] = sums.T[c] / avgN; }
  return { mean, steps, converged, capped, umaxSeen, avgN, seconds: (Date.now() - t0) / 1000, keHist };
}

const AF_ASSUMPTIONS = [
  'Lattice Boltzmann, D3Q19, single relaxation time with a Smagorinsky large-eddy closure (constant 0.1 by default): the flow is incompressible to a few percent (lattice speeds at most about 0.1), turbulence is modelled, not resolved, and there is no wall function: near-wall speeds and wall shear are not reliable.',
  'The model is voxelised (conservative: a wall is never lost, a slot narrower than a voxel is closed). Walls are halfway bounce-back, so a wall sits within half a voxel of the surface and a curved surface is a staircase. Vents and gaps smaller than about 3 voxels are poorly resolved.',
  'The domain is a box around the model with a margin. Upstream and side faces carry the free-stream speed along the chosen direction, the downstream face is a pressure outlet at gauge 0; with no free stream every face is open at gauge 0. The box is finite, so a model that blocks much of it speeds the flow around it.',
  'A fan is a body force on a disc with a straight-line pressure/flow curve (pressure rise falls to zero at the maximum flow you give); it carries no swirl and no blade model.',
  'The reported flow is the average of the last samples; it is a steady-state estimate, not a transient. A flow that does not settle (vortex shedding) is averaged over the window and says so.',
  'Parts on a PCB are solid blocks that obstruct the air (no heat in forced mode). Bodies are solid and impermeable.',
];

/** The analysis run: forced airflow, or natural convection with conjugate conduction (experimental). */
async function runAirflow(input, ctx) {
  const t0 = Date.now(), { settings: s0, warnings: sw } = settingsOf(input), warnings = [...sw], settings = { ...s0 };
  const S = input.setup, cond = S.conditions, natural = settings.mode === 'natural';
  ctx.progress(0.01);
  const Tamb = cond.ambient.temperature_K, air = airProps(Tamb);
  settings.freeSpeed = natural ? 0 : settings.speed_ms > 0 ? settings.speed_ms : (cond.ambient.airspeed_ms || 0);
  if (!natural && !(settings.freeSpeed > 0) && !settings.fans.length) throw new Error('nothing drives the air: set the air speed (Analyze > Conditions > ambient, or the setting Air speed), add a fan (the set-airflow tool), or choose the natural convection mode');
  if (natural && !((cond.heat || []).length)) throw new Error('natural convection needs at least one heat source (Analyze > Conditions > heat)');
  const settingsAll = settings; let gpu = null;
  if (settings.backend !== 'cpu' && !natural) { gpu = await lbmGpuBackend(); if (!gpu) warnings.push(`WebGPU is not available${lbmGpuBackend.lastError ? ` (${lbmGpuBackend.lastError})` : ''}; the CPU solver is used with a smaller lattice.`); }
  if (natural && settings.backend === 'gpu') warnings.push('Natural convection runs on the CPU only (the thermal lattice is not on the GPU); the CPU solver is used.');
  const device = typeof navigator !== 'undefined' ? deviceClass(navigator) : 'laptop';
  // everything that depends on the lattice, so it can be redone for the CPU when the GPU cannot take the grid
  const prepare = (backendKind, settings = settingsAll) => {
    const D = buildDomain(input, settings, { backendKind, device }), hM = D.h * 1e-3, g = G_STD, up = FACE_VEC[cond.ambient.gravity].map((v) => -v), w = [...D.warnings];
    const totalP = (cond.heat || []).reduce((a, x) => a + x.power_W, 0), bd = [0, 1, 2].map((k) => D.bb.hi[k] - D.bb.lo[k]), areaBox = 2 * (bd[0] * bd[1] + bd[1] * bd[2] + bd[0] * bd[2]) * 1e-6;
    const dT = natural ? (settings.delta_T_guess_K > 0 ? settings.delta_T_guess_K : Math.min(80, Math.max(5, totalP / (8 * areaBox)))) : 0;
    const fanSpeed = settings.fans.length ? Math.sqrt(2 * Math.max(...settings.fans.map((f) => f.dp_Pa)) / air.rho) : 0;
    const uRef = Math.max(0.05, natural ? Math.sqrt(g * air.beta * dT * D.maxDim * 1e-3) : settings.freeSpeed > 0 ? Math.max(settings.freeSpeed, fanSpeed * 0.5) : fanSpeed);
    const uLat = 0.04, sc = lbmScaling({ h_m: hM, uRef, uLat, nu: air.nu, tauFloor: settings.min_tau });
    const force = new Float32Array(3 * D.N), fans = buildFans(D, settings, sc, air, force); w.push(...fans.warnings);
    const uIn = FACE_VEC[settings.flow_dir].map((v) => v * (settings.freeSpeed > 0 ? settings.freeSpeed * sc.velFromSI : 0)), bnd = markBoundaries(D, uIn);
    const spec = { dims: D.dims, flags: D.flags, nu: sc.nuLat, cs: settings.smagorinsky, periodic: [false, false, false], uVel: bnd.uVel, uWall: [0, 0, 0], force: fans.fans.length ? force : null, hasForce: fans.fans.length > 0, u0: bnd.uVel };
    let thSpec = null, qInfo = null;
    if (natural) {
      const alphaAir = sc.nuLat / air.Pr, alpha = new Float32Array(D.N).fill(alphaAir), q = new Float32Array(D.N), capped = new Set(), cap = 10;
      for (let c = 0; c < D.N; c++) { const id = D.mat[c]; if (id > 0 && id < 251) { const a = alphaAir * D.owners[id].k / air.k; alpha[c] = Math.min(a, cap); if (a > cap) capped.add(D.owners[id].name); } }
      const cellsOf = (id) => { const l = []; for (let c = 0; c < D.N; c++) if (D.mat[c] === id) l.push(c); return l; };
      let Qin = 0;
      for (const hs of cond.heat || []) {
        let ids = []; if (hs.target.pcb != null) ids = D.partOwners.filter((id) => D.owners[id].name === hs.target.pcb); else if (D.bodyOwner.has(hs.target.body)) ids = [D.bodyOwner.get(hs.target.body)];
        const cells = ids.flatMap(cellsOf);
        if (!cells.length) { w.push(`Heat source "${hs.id}" (${hs.power_W} W) has no voxels to go into at this resolution; it is ignored.`); continue; }
        const per = hs.power_W * sc.dt / (air.rho * air.cp * hM ** 3) / cells.length; for (const c of cells) q[c] += per; Qin += hs.power_W;
      }
      qInfo = { Qin, capped: [...capped], alphaAir };
      thSpec = { alpha, q, T0: 0, Tref: 0, Tamb: 0, gbeta: g * air.beta * sc.accelToLat, up };
      if (capped.size) w.push(`Conduction in ${[...capped].join(', ')} is capped at a lattice diffusivity of ${cap} (conductivity about ${(cap / alphaAir * air.k).toFixed(0)} W/(m K)); that body is a little less conductive than its material says, so its peak is overstated.`);
    }
    const plan = { uRef, uLat, force, steps: Math.round(settings.flow_throughs * Math.max(...D.dims) / (uLat * (natural ? 0.5 : settings.freeSpeed > 0 ? 1 : 0.7))) };
    return { D, sc, fans, bnd, spec, thSpec, qInfo, plan, dT, uRef, w };
  };
  // BGK at a relaxation time near 1/2 is unstable around sharp features: on blow-up the run is repeated with a higher viscosity floor and says so
  const taus = [settings.min_tau, ...[0.53, 0.56, 0.62].filter((t) => t > settings.min_tau)]; let P, run, attempt = 0;
  for (const tau of taus) {
    attempt++; settings.min_tau_used = tau; const s1 = { ...settings, min_tau: tau };
    P = prepare(gpu ? 'gpu' : 'cpu', s1);
    if (attempt === 1 && gpu && settings.backend === 'auto' && P.D.N < 30000) { gpu = null; P = prepare('cpu', s1); }
    if (gpu && P.D.N > gpu.maxCells) { warnings.push(`This lattice has ${P.D.N.toLocaleString('en')} cells, more than this GPU's buffers hold (${gpu.maxCells.toLocaleString('en')}); the CPU solver is used with a smaller lattice.`); gpu = null; P = prepare('cpu', s1); }
    if (ctx.signal && ctx.signal.aborted) throw new Error('cancelled');
    ctx.progress(0.05);
    let sim = null, dev = null, th = null;
    if (gpu) {
      try { const g1 = await gpu.create(P.spec); dev = { kind: 'gpu', step: (k) => g1.step(k), macro: () => g1.macro(), setForce: (a) => g1.setForce(a), destroy: () => g1.destroy() }; }
      catch (e) { warnings.push(`WebGPU could not allocate this lattice (${String((e && e.message) || e).slice(0, 120)}); the CPU solver is used with a smaller lattice.`); gpu = null; P = prepare('cpu', s1); }
    }
    if (!gpu) { sim = createLbm({ ...P.spec, thermal: P.thSpec ? { ...P.thSpec } : undefined }); th = sim.thermal; dev = cpuDevice(sim); }
    ctx.progress(0.12);
    try { run = await drive({ D: P.D, sim, dev, sc: P.sc, air, fans: P.fans, th, settings, ctx, plan: P.plan }); dev.destroy(); break; }
    catch (e) { dev.destroy(); if (!e.unstable || attempt >= taus.length) throw e; warnings.push(`The flow became unstable with a relaxation time floor of ${tau}; the run was repeated with ${taus[attempt]} (a higher viscosity: the simulated Reynolds number is lower).`); }
  }
  warnings.push(...P.w);
  return await postprocess({ D: P.D, run, sc: P.sc, air, settings, fans: P.fans, bnd: P.bnd, uRef: P.uRef, natural, qInfo: P.qInfo, gpu: !!gpu, warnings, ctx, t0, dT: P.dT, cond, Tamb, device });
}

/** Everything after the run: fields, vents, dead zones, streamlines, summary rows, honest statements. */
async function postprocess({ D, run, sc, air, settings, fans, bnd, uRef, natural, qInfo, gpu, warnings, ctx, t0, dT, cond, Tamb, device }) {
  const { N, h, grid } = D, hM = h * 1e-3, M = run.mean, k = sc.velToSI;
  const speed = new Float32Array(N), vel = new Float32Array(3 * N), pres = new Float32Array(N), dead = new Float32Array(N);
  let maxSp = 0, maxAt = 0;
  for (let c = 0; c < N; c++) {
    if ((D.flags[c] & 7) === LBM_SOLID) { speed[c] = NaN; pres[c] = NaN; dead[c] = NaN; continue; }
    const ux = M.ux[c] * k, uy = M.uy[c] * k, uz = M.uz[c] * k, sp = Math.hypot(ux, uy, uz); vel[3 * c] = ux; vel[3 * c + 1] = uy; vel[3 * c + 2] = uz; speed[c] = sp;
    pres[c] = (M.rho[c] - 1) / 3 * air.rho * k * k; if (sp > maxSp) { maxSp = sp; maxAt = c; }
  }
  const Mv = { ux: M.ux, uy: M.uy, uz: M.uz }, vents = findVents(D, Mv, sc);
  const posOf = (c) => { const [nx, ny] = D.dims, i = c % nx, j = ((c - i) / nx) % ny, kk = (c - i - nx * j) / (nx * ny); return [grid.origin[0] + (i + 0.5) * h, grid.origin[1] + (j + 0.5) * h, grid.origin[2] + (kk + 0.5) * h]; };
  // interior of the body box: where "dead" means something
  const [nx, ny] = D.dims; let nIn = 0, sumSp = 0; const interior = [];
  for (let kk = D.bi0[2] + 1; kk < D.bi1[2] - 1; kk++) for (let j = D.bi0[1] + 1; j < D.bi1[1] - 1; j++) for (let i = D.bi0[0] + 1; i < D.bi1[0] - 1; i++) { const c = i + nx * (j + ny * kk); if ((D.flags[c] & 7) === LBM_FLUID) { interior.push(c); sumSp += speed[c]; } }
  nIn = interior.length;
  const inflow = vents.reduce((a, v) => a + v.in_m3s, 0), outflow = vents.reduce((a, v) => a + v.out_m3s, 0), through = Math.min(inflow, outflow);
  let ref = settings.freeSpeed > 0 ? settings.freeSpeed : 0, refNote = settings.freeSpeed > 0 ? 'the free-stream speed' : '';
  if (!(ref > 0)) { let s = 0, n = 0; for (const v of vents) for (const c of v.cells) { const a = FACE_LIST.indexOf(v.face) >> 1, o = v.face[0] === '+' ? 1 : -1; if ([M.ux, M.uy, M.uz][a][c] * o < 0) { s += speed[c]; n++; } } if (n) { ref = s / n; refNote = 'the mean speed through the inflow vents'; } }
  if (!(ref > 0) && fans.fans.length) { ref = Math.sqrt(2 * Math.max(...fans.fans.map((f) => f.dp0)) / air.rho) * 0.5; refNote = 'half the speed a fan pressure rise would give'; }
  if (!(ref > 0)) { ref = maxSp; refNote = 'the largest speed in the domain'; }
  let nDead = 0; for (const c of interior) { const d = speed[c] < settings.dead_frac * ref ? 1 : 0; dead[c] = d; nDead += d; }
  for (let c = 0; c < N; c++) if (!Number.isNaN(dead[c]) && dead[c] !== 1) dead[c] = 0;
  const rows = [];
  const add = (metric, value, unit, where) => { if (Number.isFinite(value)) rows.push({ metric, value, unit, ...(where ? { where } : {}) }); };
  add('max_speed', maxSp, 'm/s', posOf(maxAt)); add('reference_speed', ref, 'm/s');
  add('mean_speed_interior', nIn ? sumSp / nIn : 0, 'm/s');
  add('through_flow', through * 1000, 'L/s'); add('through_flow_cfm', through * 1000 * L_PER_S_TO_CFM, 'cfm');
  add('mass_balance_error', inflow + outflow > 0 ? Math.abs(inflow - outflow) / (0.5 * (inflow + outflow)) : 0, '');
  add('dead_zone_fraction', nIn ? nDead / nIn : 0, ''); add('dead_zone_volume', nDead * hM ** 3 * 1e6, 'cm3');
  add('interior_volume', nIn * hM ** 3 * 1e6, 'cm3');
  add('vents', vents.length, '');
  vents.slice(0, 8).forEach((v, i) => { add(`vent_${i + 1}_in`, v.in_m3s * 1000, 'L/s', v.centre); add(`vent_${i + 1}_out`, v.out_m3s * 1000, 'L/s', v.centre); });
  fans.fans.forEach((f, i) => { add(`fan_${i + 1}_flow`, (f.q || 0) * 1000, 'L/s', f.center); add(`fan_${i + 1}_pressure_rise`, f.dp, 'Pa', f.center); });
  const Lc = D.maxDim * 1e-3; add('reynolds', ref * Lc / air.nu, '');
  add('cell_reynolds', maxSp * hM / air.nu, ''); add('lattice_mach', run.umaxSeen * Math.sqrt(3), '');
  add('voxel_size', h, 'mm'); add('lattice_cells', N, ''); add('lattice_budget', D.budget, ''); add('steps', run.steps, ''); add('converged', run.converged ? 1 : 0, '');
  add('solve_seconds', +run.seconds.toFixed(2), 's');
  const fields = [];
  const gridInfo = { origin: grid.origin, h, dims: D.dims, active: N, budget: D.budget };
  fields.push({ name: 'speed', unit: 'm/s', kind: 'voxel', data: speed, grid: gridInfo }, { name: 'velocity', unit: 'm/s', kind: 'vector', data: vel, grid: gridInfo },
    { name: 'pressure', unit: 'Pa', kind: 'voxel', data: pres, grid: gridInfo }, { name: 'dead_zone', unit: '', kind: 'voxel', data: dead, grid: gridInfo });
  if (natural && M.T) {
    const Tf = new Float32Array(N); let tmax = -Infinity, tAt = 0, tSolid = -Infinity, sAt = 0;
    for (let c = 0; c < N; c++) { if ((D.flags[c] & 7) === LBM_SOLID && !(D.mat[c] > 0 && D.mat[c] < 251)) { Tf[c] = NaN; continue; } Tf[c] = M.T[c] + Tamb - 273.15; if (D.mat[c] > 0 && D.mat[c] < 251 && M.T[c] > tSolid) { tSolid = M.T[c]; sAt = c; } if (M.T[c] > tmax) { tmax = M.T[c]; tAt = c; } }
    fields.push({ name: 'temperature', unit: 'C', kind: 'voxel', data: Tf, grid: gridInfo });
    add('max_temperature', tSolid + Tamb - 273.15, 'C', posOf(sAt)); add('max_temperature_rise', tSolid, 'K'); add('max_air_temperature_rise', tmax, 'K', posOf(tAt)); add('heat_in', qInfo.Qin, 'W'); add('delta_T_guess', dT, 'K');
  }
  // streamlines: an overlay of the package
  let streamNote = '';
  if (settings.streamlines > 0 && typeof forge !== 'undefined' && forge.scene && forge.scene.overlay) {
    try {
      const U = new Float32Array(3 * N); for (let c = 0; c < 3 * N; c++) U[c] = vel[c];
      const seeds = pickSeeds(D, Mv, sc, vents, settings.streamlines), sl = traceStreamlines({ grid, flags: D.flags, U, seeds, minSpeed: Math.max(1e-4, 0.01 * ref) });
      if (sl) { await forge.scene.overlay('streamlines', { kind: 'vectors', positions: sl.positions, vectors: sl.vectors, lines: sl.lines, unit: 'm/s', label: 'Airflow streamlines', validity: natural ? 'experimental' : 'estimate' }); streamNote = `${sl.lines.length} streamlines drawn (layer ${AF_ID}:streamlines).`; }
    } catch (e) { streamNote = 'Streamlines could not be drawn: ' + String((e && e.message) || e).slice(0, 100); }
  }
  // honest statements
  warnings.push(`Lattice: ${D.dims.join('×')} = ${N.toLocaleString('en')} cells of ${h.toFixed(3)} mm (budget ${D.budget.toLocaleString('en')} for ${settings.quality} on ${gpu ? (device === 'phone' ? 'a phone GPU' : 'a laptop GPU') : settings.backend === 'cpu' ? 'the CPU' : 'the CPU fallback'}; hard limit ${LBM_HARD_LIMIT.toLocaleString('en')}). Margin ${D.margin.toFixed(1)} mm around the model; ${D.solid.toLocaleString('en')} solid cells.`);
  if (device === 'phone' && gpu) warnings.push('Phone: the lattice is limited to about 64³ cells, so vents and walls are coarse; treat the result as a first look.');
  warnings.push(`Solver: ${gpu ? 'WebGPU' : 'CPU'}, ${run.steps.toLocaleString('en')} steps (${run.avgN} averaged samples) in ${run.seconds.toFixed(1)} s; ${run.converged ? 'the kinetic energy settled (relative change under 0.2 % per interval)' : run.capped ? `stopped at the time limit (${settings.max_seconds} s): NOT converged, treat the numbers as a snapshot` : 'did not fully settle in the planned number of steps: treat the numbers as approximate'}.`);
  warnings.push(`Scaling: lattice speed ${sc.uLat} stands for ${uRef.toFixed(2)} m/s; time step ${(sc.dt * 1e6).toFixed(2)} µs; lattice viscosity ${sc.nuLat.toFixed(5)} (tau ${(3 * sc.nuLat + 0.5).toFixed(3)})${sc.raised ? `; the real viscosity would give ${sc.nuPhysLat.toFixed(5)}, below the stable limit, so the viscosity is RAISED to the limit and the simulated Reynolds number is lower than the real one (the Smagorinsky term models the missing turbulence only in part)` : ''}. Largest lattice Mach number ${(run.umaxSeen * Math.sqrt(3)).toFixed(2)}.`);
  warnings.push(`Dead zone: interior air slower than ${(settings.dead_frac * 100).toFixed(1)} % of ${refNote} (${ref.toFixed(3)} m/s) = ${(settings.dead_frac * ref).toFixed(3)} m/s; ${nIn} interior cells, ${nDead} dead.`);
  if (!vents.length) warnings.push('No vents were found: the body box has no opening in its wall layers at this voxel size (vents narrower than a voxel are closed by the conservative voxelizer). The air inside moves only when a fan or a heat source drives it.');
  else warnings.push(`Vents: ${vents.length} found (largest ${(vents[0].area_m2 * 1e6).toFixed(0)} mm² on ${vents[0].face}); the in and out rows are the air entering and leaving the box through each, positive and separate so a vent that does both is visible.`);
  if (interior.length < 8) warnings.push('The model has almost no air inside its bounding box, so the dead-zone numbers mean little.');
  if (streamNote) warnings.push(streamNote);
  if (inflow + outflow > 0 && Math.abs(inflow - outflow) > 0.1 * 0.5 * (inflow + outflow)) warnings.push(`Mass balance: vents bring in ${(inflow * 1000).toFixed(2)} L/s and take out ${(outflow * 1000).toFixed(2)} L/s (${(100 * Math.abs(inflow - outflow) / (0.5 * (inflow + outflow))).toFixed(0)} % apart): the flow has not settled, or leaks through cells that are not in a wall layer.`);
  for (const o of D.owners.slice(1)) if (o.assumed) warnings.push(`Body "${o.name}" has no material: ${o.material} is assumed.`);
  if (natural) {
    warnings.push(`Natural convection is EXPERIMENTAL: the temperature lattice (D3Q7, Boussinesq buoyancy, conducting solids in the same lattice) reproduces the de Vahl Davis cavity within 2 %, but the enclosure case against OpenFOAM has not been run (skipped), so this result never drives a pass or fail. Conduction in the solids is solved with a pseudo heat capacity (the steady state is right, the time it takes is not physical). Radiation is not modelled: surface temperatures are overstated where radiation matters. The temperature at the open faces is ambient where air comes in and zero gradient where it leaves. Estimated temperature rise used to set the time step: ${dT.toFixed(0)} K.`);
    if (qInfo && qInfo.Qin > 0) warnings.push(`Heat in ${qInfo.Qin.toFixed(2)} W. No heat balance is checked in this phase.`);
  } else warnings.push('Validity "estimate": the lid-driven cavity (Ghia), flow past a cylinder (Strouhal number) and a channel flow (Poiseuille) cases pass in analysis-validation/cases/airflow.json, but the enclosure compared with OpenFOAM through the Alloy Runner has not been run (skipped, not a pass), so the velocities are not claimed as validated.');
  if (run.mean && !natural && D.dims.some((d) => d < 24)) warnings.push('A side of the lattice is under 24 cells: the flow there is coarse.');
  warnings.push(...AF_ASSUMPTIONS.map((a) => 'Assumption: ' + a));
  ctx.progress(1);
  return { summary: rows, fields, warnings, validity: natural ? 'experimental' : 'estimate' };
}

async function loadSettings() { try { const v = await forge.storage.project.get('settings'); return v && typeof v === 'object' ? v : {}; } catch (_) { return {}; } }

/** Settings of this package stored in the project (ext[<id>].settings), merged under `override`. */
function settingsOf(input, override) {
  const stored = input && input.project && input.project.ext && input.project.ext[AF_ID] && input.project.ext[AF_ID].settings;
  return parseSettings({ ...(stored || {}), ...(override || {}) });
}

// ───────────────────────── reference problems (used by analysis-validation/cases/airflow.json) ─────────────────────────
// Each builds a small lattice problem with a published answer and runs the same collision-streaming core. They are in the package so the
// harness can call them through __core; they are not used by the analysis itself.

/** Ghia, Ghia and Shin (1982), J. Comput. Phys. 48, tables I and II: u along the vertical and v along the horizontal centreline of the lid-driven cavity. Transcribed from memory of the tables, not re-checked against the paper. */
const GHIA = {
  y: [1.0, 0.9766, 0.9688, 0.9609, 0.9531, 0.8516, 0.7344, 0.6172, 0.5, 0.4531, 0.2813, 0.1719, 0.1016, 0.0703, 0.0625, 0.0547, 0],
  x: [1.0, 0.9688, 0.9609, 0.9531, 0.9453, 0.9063, 0.8594, 0.8047, 0.5, 0.2344, 0.2266, 0.1563, 0.0938, 0.0781, 0.0703, 0.0625, 0],
  u: { 100: [1, 0.84123, 0.78871, 0.73722, 0.68717, 0.23151, 0.00332, -0.13641, -0.20581, -0.2109, -0.15662, -0.1015, -0.06434, -0.04775, -0.04192, -0.03717, 0], 1000: [1, 0.65928, 0.57492, 0.51117, 0.46604, 0.33304, 0.18719, 0.05702, -0.0608, -0.10648, -0.27805, -0.38289, -0.2973, -0.2222, -0.20196, -0.18109, 0] },
  v: { 100: [0, -0.05906, -0.07391, -0.08864, -0.10313, -0.16914, -0.22445, -0.24533, 0.05454, 0.17527, 0.17507, 0.16077, 0.12317, 0.1089, 0.10091, 0.09233, 0], 1000: [0, -0.21388, -0.27669, -0.33714, -0.39188, -0.5155, -0.42665, -0.31966, 0.02526, 0.32235, 0.33075, 0.37095, 0.32627, 0.30353, 0.29012, 0.27485, 0] },
};
function lagrange4(xs, ys, x) { // cubic through the four nearest of an increasing xs
  let k = 0; while (k < xs.length - 2 && xs[k + 1] < x) k++; const a = Math.max(0, Math.min(xs.length - 4, k - 1)); let r = 0;
  for (let i = a; i < a + 4; i++) { let t = ys[i]; for (let j = a; j < a + 4; j++) if (j !== i) t *= (x - xs[j]) / (xs[i] - xs[j]); r += t; }
  return r;
}

/**
 * Lid-driven cavity (quasi-2D: two layers, periodic in z), N fluid cells a side, lid speed 0.1, Smagorinsky off. Runs until the kinetic
 * energy changes by less than `tol` over 2000 steps (or maxSteps). Returns { eu, ev, steps }: the largest difference to Ghia et al. over the 17 points of each
 * centreline, in units of the lid speed.
 */
const REF_MEMO = new Map();
const memo = (name, fn) => (a) => { const key = name + JSON.stringify(a); if (!REF_MEMO.has(key)) REF_MEMO.set(key, fn(a)); return REF_MEMO.get(key); };
function refCavityRaw({ Re, N, maxSteps = 200000, tol = 1e-4 }) {
  const nx = N + 2, ny = N + 2, nz = 2, flags = new Uint8Array(nx * ny * nz), U = 0.1;
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) { const c = x + nx * (y + ny * z); if (y === ny - 1) flags[c] = LBM_MOVING; else if (x === 0 || x === nx - 1 || y === 0) flags[c] = LBM_SOLID; }
  const S = createLbm({ dims: [nx, ny, nz], flags, nu: U * N / Re, cs: 0, periodic: [false, false, true], uWall: [U, 0, 0] });
  let last = 0, steps = 0;
  while (steps < maxSteps) { lbmAdvance(S, 2000); steps += 2000; const e = lbmKinetic(S); if (Math.abs(e - last) / e < tol) break; last = e; }
  const at = (x, y) => x + nx * y, us = [0], ys = [0], vs = [0], xs = [0];
  for (let j = 0; j < N; j++) { ys.push((j + 0.5) / N); us.push((S.ux[at(N / 2, j + 1)] + S.ux[at(N / 2 + 1, j + 1)]) / 2 / U); } ys.push(1); us.push(1);
  for (let i = 0; i < N; i++) { xs.push((i + 0.5) / N); vs.push((S.uy[at(i + 1, N / 2)] + S.uy[at(i + 1, N / 2 + 1)]) / 2 / U); } xs.push(1); vs.push(0);
  let eu = 0, ev = 0;
  for (let k = 0; k < GHIA.y.length; k++) { eu = Math.max(eu, Math.abs(lagrange4(ys, us, GHIA.y[k]) - GHIA.u[Re][k])); ev = Math.max(ev, Math.abs(lagrange4(xs, vs, GHIA.x[k]) - GHIA.v[Re][k])); }
  return { eu, ev, steps };
}

/**
 * Schaefer and Turek (1996) benchmark 2D-2, Re 100: a cylinder in a channel (H = 0.41, D = 0.1, length 2.2, centre 0.2 from the inlet and
 * from the lower wall) with a parabolic inflow, mean speed 2/3 of the peak. H fluid cells across. Returns the Strouhal number f D / U_mean
 * from the zero crossings of the transverse velocity 0.6 downstream of the inlet (reference range 0.2950 to 0.3050).
 */
function refCylinderRaw({ H, steps }) {
  const Lc = Math.round(2.2 * H / 0.41), nx = Lc + 2, ny = H + 2, nz = 2, Dl = 0.1 * H / 0.41, R = Dl / 2, xc = 0.2 * H / 0.41, yc = 0.2 * H / 0.41, Umax = 0.1, Um = 2 / 3 * Umax;
  const flags = new Uint8Array(nx * ny * nz), ubc = new Float32Array(3 * nx * ny * nz);
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
    const c = x + nx * (y + ny * z);
    if (y === 0 || y === ny - 1) { flags[c] = LBM_SOLID; continue; }
    if (x === 0) { flags[c] = boundaryFlag(LBM_VEL, 0); const yy = y - 0.5; ubc[3 * c] = 4 * Umax * yy * (H - yy) / (H * H); continue; }
    if (x === nx - 1) { flags[c] = boundaryFlag(LBM_PRES, 1); continue; }
    if (Math.hypot(x - 0.5 - xc, y - 0.5 - yc) <= R) flags[c] = LBM_SOLID;
  }
  const S = createLbm({ dims: [nx, ny, nz], flags, nu: Um * Dl / 100, cs: 0, periodic: [false, false, true], ubc, u0: [Um, 0, 0] });
  const probe = 1 + Math.round(0.6 * H / 0.41) + nx * (1 + Math.round(0.2 * H / 0.41)), sig = [];
  for (let s = 0; s < steps; s += 10) { lbmAdvance(S, 10); sig.push([S.step, S.uy[probe]]); }
  const s2 = sig.slice(Math.floor(sig.length * 0.45)), mean = s2.reduce((a, b) => a + b[1], 0) / s2.length, cr = [];
  for (let i = 1; i < s2.length; i++) { const a = s2[i - 1][1] - mean, b = s2[i][1] - mean; if (a < 0 && b >= 0) cr.push(s2[i - 1][0] + 10 * (-a) / (b - a)); }
  if (cr.length < 4) return { St: NaN, crossings: cr.length };
  const per = []; for (let i = 1; i < cr.length; i++) per.push(cr[i] - cr[i - 1]); const T = per.slice(-6).reduce((a, b) => a + b, 0) / Math.min(6, per.length);
  return { St: Dl / (T * Um), crossings: cr.length, period: T };
}

/** Plane Poiseuille flow between no-slip walls driven by a body force: the profile against u(y) = F y (H - y) / (2 nu), as the largest relative error. Wall at half a cell, tau chosen so the bounce-back error cancels (3/16 rule). */
function refPoiseuilleRaw({ H = 24, steps = 12000 }) {
  const nx = 3, ny = H + 2, nz = 3, flags = new Uint8Array(nx * ny * nz), tau = 0.5 + Math.sqrt(3 / 16), nu = (tau - 0.5) / 3, F = 1e-5;
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) if (y === 0 || y === ny - 1) flags[x + nx * (y + ny * z)] = LBM_SOLID;
  const S = createLbm({ dims: [nx, ny, nz], flags, nu, cs: 0, periodic: [true, false, true], bodyForce: [F, 0, 0] });
  lbmAdvance(S, steps);
  let err = 0, ref = 0; for (let y = 1; y <= H; y++) { const yy = y - 0.5, ua = F * yy * (H - yy) / (2 * nu), u = S.ux[1 + nx * (y + ny)]; err = Math.max(err, Math.abs(u - ua)); ref = Math.max(ref, ua); }
  return { relErr: err / ref };
}

/**
 * Differentially heated square cavity (de Vahl Davis 1983), Pr 0.71: hot left wall, cold right wall, adiabatic top and bottom, N cells a side.
 * Ra from 1e3 to 1e6. Returns the average Nusselt number from the heat flux through the vertical mid-plane (convective plus conductive).
 */
function refDeVahlRaw({ Ra, N, uRef = 0.3, maxSteps = 600000, tol = 2e-6 }) {
  const Pr = 0.71, nx = N + 2, ny = N + 2, nz = 2, tot = nx * ny * nz, flags = new Uint8Array(tot), tflag = new Uint8Array(tot), tval = new Float32Array(tot);
  for (let z = 0; z < nz; z++) for (let y = 0; y < ny; y++) for (let x = 0; x < nx; x++) {
    const c = x + nx * (y + ny * z);
    if (x === 0 || x === nx - 1 || y === 0 || y === ny - 1) { flags[c] = LBM_SOLID; if (x === 0) { tflag[c] = 1; tval[c] = 1; } else if (x === nx - 1) tflag[c] = 1; else tflag[c] = 2; }
  }
  const nu = uRef * N * Math.sqrt(Pr / Ra), alpha = nu / Pr, T0 = new Float32Array(tot); for (let c = 0; c < tot; c++) T0[c] = Math.min(1, Math.max(0, 1 - ((c % nx) - 0.5) / N));
  const S = createLbm({ dims: [nx, ny, nz], flags, nu, cs: 0, periodic: [false, false, true], thermal: { alpha, tflag, tval, T0, Tref: 0.5, gbeta: uRef * uRef / N, up: [0, 1, 0] } }), th = S.thermal;
  const nuMid = () => { const xi = Math.floor(nx / 2) - 1; let q = 0; for (let y = 1; y <= N; y++) { const a = xi + nx * y, b = xi + 1 + nx * y; q += 0.5 * (S.ux[a] + S.ux[b]) * 0.5 * (th.T[a] + th.T[b]) - alpha * (th.T[b] - th.T[a]); } return q / N / (alpha / N); };
  let last = 0, steps = 0;
  while (steps < maxSteps) { lbmAdvance(S, 2000); steps += 2000; const n = nuMid(); if (steps > 6000 && Math.abs(n - last) / Math.abs(n) < tol) { last = n; break; } last = n; }
  return { Nu: last, steps };
}

const refCavity = memo('cavity', refCavityRaw), refCylinder = memo('cylinder', refCylinderRaw), refPoiseuille = memo('poiseuille', refPoiseuilleRaw), refDeVahl = memo('devahl', refDeVahlRaw);

if (typeof forge !== 'undefined') {
  forge.analysis.register('airflow', { run: async (input, ctx) => { try { if (forge.scene && forge.scene.clearOverlay) await forge.scene.clearOverlay('streamlines'); } catch (_) { /* no overlay surface in this host */ } return runAirflow(input, ctx); } });
  forge.agent.tool('set-airflow', async (args) => {
    const cur = await loadSettings(), next = { ...cur, ...(args && typeof args === 'object' ? args : {}) };
    const { settings, warnings } = parseSettings(next);
    if (warnings.length) throw new Error(warnings.join(' '));
    await forge.storage.project.set('settings', next);
    return { saved: next, effective: settings };
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { parseSettings, airProps, buildDomain, markBoundaries, buildFans, findVents, traceStreamlines, pickSeeds, runAirflow, settingsOf, drive, cpuDevice, refCavity, refCylinder, refPoiseuille, refDeVahl, GHIA, createLbm, lbmAdvance, lbmScaling, lbmStep, lbmKinetic, LBM_BUDGETS, lbmBudget });
