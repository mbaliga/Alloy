// Structural (A3): linear elasticity on the voxel grid. Every solid voxel is an 8-node hexahedron (trilinear plus condensed
// incompatible modes, voxel-core/elastic.js), solved matrix-free with Jacobi-preconditioned CG on the GPU (WebGPU) or the CPU. This file
// is concatenated after the core files by tools/bundle-ext.mjs into examples/ext/structural/main.js, so the core's names are in scope.
//
// Model. Units inside: mm, N, MPa. Fixed mounts hold every dof of the nodes of the selected faces; slider mounts hold only the
// displacement normal to the face (a symmetry plane or a frictionless support). Loads: a force or a pressure on the selected faces
// of a body, a body force from an acceleration (rho a, toward the stated direction, like gravity), self weight, an equivalent
// acceleration preset (drop or recoil), and a simplified bolt preload (two equal and opposite compressive discs). Output: displacement,
// von Mises stress smoothed over a neighbourhood of 2 voxels, safety factor against yield with an FDM inter-layer knock-down.
const STR_ID = 'xyz.mdhv.structural';
const FACE_LIST = ['+x', '-x', '+y', '-y', '+z', '-z'];
const FACE_N = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
const G_STD = 9.80665;
const SF_CAP = 10;      // the safety-factor field is drawn up to this value
const SF_BIG = 1000;    // the summary row when nothing is stressed

const DEFAULT_SETTINGS = {
  quality: 'normal', backend: 'auto', print_axis: 'z', self_weight: 'off', preset: 'none', preset_g: 1000, drop_m: 1, drop_stop_mm: 2, preset_dir: '-z',
  voxelizer: 'conservative', h_mm: 0, smooth_voxels: 2, knockdown: 'on', tol: 0, preloads: [],
};

/** Validate the stored settings. Unknown or invalid entries fall back to defaults and say so. */
function parseSettings(raw) {
  const s = JSON.parse(JSON.stringify(DEFAULT_SETTINGS)), warnings = [], r = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
  const en = (k, opts) => { if (r[k] === undefined) return; if (opts.includes(r[k])) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} is not one of ${opts.join(', ')}; ${s[k]} is used.`); };
  const nu = (k, lo, hi) => { if (r[k] === undefined) return; if (typeof r[k] === 'number' && Number.isFinite(r[k]) && r[k] >= lo && r[k] <= hi) s[k] = r[k]; else warnings.push(`Setting ${k} = ${JSON.stringify(r[k])} must be a number from ${lo} to ${hi}; ${s[k]} is used.`); };
  en('quality', QUALITIES_3D); en('backend', ['auto', 'gpu', 'cpu']); en('print_axis', ['x', 'y', 'z']); en('self_weight', ['off', 'on']); en('preset', ['none', 'drop', 'recoil']); en('preset_dir', FACE_LIST);
  en('voxelizer', ['conservative', 'centre']); en('knockdown', ['on', 'off']);
  nu('preset_g', 0, 100000); nu('drop_m', 0.01, 100); nu('drop_stop_mm', 0.05, 1000); nu('h_mm', 0, 200); nu('smooth_voxels', 0, 4); nu('tol', 0, 1e-2);
  if (r.preloads !== undefined) {
    const out = [];
    for (const p of Array.isArray(r.preloads) ? r.preloads : []) {
      if (p && ['x', 'y', 'z'].includes(p.axis) && Array.isArray(p.center) && p.center.length === 3 && p.center.every(Number.isFinite) && Number.isFinite(p.radius_mm) && p.radius_mm > 0 && Number.isFinite(p.force_N) && Math.abs(p.force_N) <= 1e6) out.push({ axis: p.axis, center: p.center.slice(), radius_mm: p.radius_mm, force_N: p.force_N, body: p.body || null });
      else warnings.push(`A bolt preload was ignored (needs axis, center [x,y,z] mm, radius_mm > 0 and force_N): ${JSON.stringify(p).slice(0, 80)}.`);
    }
    s.preloads = out;
  }
  return { settings: s, warnings };
}

/** The equivalent quasi-static acceleration (m/s^2) of the drop or recoil preset, or null. */
function presetAccel(settings) {
  if (settings.preset === 'drop') return { a: G_STD * settings.drop_m / (settings.drop_stop_mm * 1e-3), text: `drop from ${settings.drop_m} m stopped over ${settings.drop_stop_mm} mm: v^2 = 2 g H, a = v^2 / (2 s) = g H / s` };
  if (settings.preset === 'recoil') return { a: G_STD * settings.preset_g, text: `recoil as a constant ${settings.preset_g} g acceleration (a placeholder you set; no firearm is modelled)` };
  return null;
}

// ───────────────────────── the model ─────────────────────────
function bboxOfBodies(bodies) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const b of bodies) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], b.bbox.min[k]); hi[k] = Math.max(hi[k], b.bbox.max[k]); }
  return { lo, hi };
}

/**
 * Voxelize so that the number of SOLID voxels (enclosed air does not carry load) lands on the budget. A voxel size chosen from the
 * budget alone almost never divides the design, and the conservative voxelizer then thickens a rectilinear part by up to a voxel on
 * each axis (a 10 mm bar at 0.317 mm becomes 10.16 mm: 5 % too stiff in bending). So the chosen size is snapped so that the thinnest
 * dimension of the model is a whole number of voxels. A size given by the user (h_mm) is used as it is.
 */
function fitSolidModel({ meshes, bbox, h, budget, conservative, maxActive }) {
  const isSolid = (m) => m !== 0 && m !== CAVITY, smin = Math.min(...[0, 1, 2].map((k) => bbox.hi[k] - bbox.lo[k]));
  const snap = (hh) => smin / Math.max(1, Math.round(smin / hh));
  const solidOf = (v) => { let s = 0; for (let i = 0; i < v.mat.length; i++) if (isSolid(v.mat[i])) s++; return s; };
  let vm = buildVoxelModel({ meshes, bbox, h, budget, conservative, maxActive: Infinity }), tries = 0;
  if (!h) {
    try { const hs = snap(vm.grid.h); if (Math.abs(hs - vm.grid.h) > 1e-9 * hs) vm = buildVoxelModel({ meshes, bbox, h: hs, budget, conservative, maxActive: Infinity }); } catch (_) { /* the snapped size makes too many cells: keep the budget's */ }
  }
  let solid = solidOf(vm);
  if (!h) while (tries < 3 && (solid / budget > 1.15 || solid / budget < 0.6) && solid > 0) {
    const hh = snap(vm.grid.h * Math.min(1.6, Math.max(0.5, Math.cbrt(solid / budget))));
    try { vm = buildVoxelModel({ meshes, bbox, h: hh, budget, conservative, maxActive: Infinity }); } catch (_) { break; } solid = solidOf(vm); tries++;
  }
  if (solid > maxActive) throw new Error(`${solid.toLocaleString('en')} solid voxels exceed the limit of ${maxActive.toLocaleString('en')}; choose a coarser quality or a larger voxel size`);
  return { vm, solid };
}

/**
 * Voxelize the design and set up everything a solve needs: elements and nodes, per-element modulus, supports, loads.
 * input: the analysis input; settings: parsed settings; ctx: { backendKind: 'cpu'|'gpu', device: 'laptop'|'phone' }.
 */
function buildModel(input, settings, ctx) {
  const S = input.setup, cond = S.conditions, warnings = [], bodies = input.bodies;
  if (!bodies.length) throw new Error('there is no built body to analyse');
  const owners = [null], bodyOwner = new Map();
  for (const b of bodies) {
    const sb = S.bodies.find((x) => x.name === b.name), p = sb.material.props;
    if (!(p.E > 0) || p.nu == null || !(p.nu >= 0 && p.nu < 0.5)) throw new Error(`Body "${b.name}": material ${sb.material.name} has no Young's modulus or Poisson's ratio; choose a solid material in Analyze > Materials`);
    if (p.nu > 0.45) warnings.push(`Body "${b.name}": Poisson's ratio ${p.nu} is close to 0.5; the hexahedral element locks volumetrically there and the stiffness is overestimated.`);
    let strength = p.yield_strength, strengthKind = 'yield';
    if (!(strength > 0)) { strength = p.tensile_strength; strengthKind = 'tensile strength'; if (strength > 0) warnings.push(`Body "${b.name}": ${sb.material.name} has no yield strength; the tensile strength is used for the safety factor.`); }
    if (!(strength > 0)) { strength = 0; warnings.push(`Body "${b.name}": ${sb.material.name} has no yield or tensile strength; no safety factor is computed for it.`); }
    const f = p.interlayer_factor > 0 && p.interlayer_factor <= 1 ? p.interlayer_factor : 1;
    owners.push({ id: owners.length, name: b.name, E: p.E / 1e6, nu: p.nu, rho: p.rho || 0, strength: strength / 1e6, strengthKind, interlayer: f, material: sb.material.name, assumed: sb.assumed });
    bodyOwner.set(b.name, owners.length - 1);
    if (owners.length > MAX_OWNERS) throw new Error('too many bodies for one grid');
  }
  if ((S.pcb && (S.pcb.components || []).length) || (cond.heat || []).length) warnings.push('PCB parts and heat sources are not structural: parts on the board have no stiffness and no mass here.');
  const bb = bboxOfBodies(bodies), meshes = bodies.map((b) => ({ positions: b.positions, indices: b.indices, id: bodyOwner.get(b.name) }));
  const { budget, cls } = gridBudget({ quality: settings.quality, device: ctx.device, backend: ctx.backendKind });
  const { vm, solid } = fitSolidModel({ meshes, bbox: bb, h: settings.h_mm > 0 ? settings.h_mm : null, budget, conservative: settings.voxelizer !== 'centre', maxActive: GRID_HARD_LIMIT.active });
  const { grid, mat } = vm, h = grid.h, hM = h * 1e-3;
  const st = buildElastic(grid, mat, (m) => m !== 0 && m !== CAVITY), ne = st.ne, nn = st.nn, nd = 3 * nn;
  if (ne === 0) throw new Error('the model has no solid voxels');
  const own = new Uint8Array(ne), nuKeys = [], Ks = [], mIdx = new Uint8Array(ne), scale = new Float64Array(ne), Eel = new Float64Array(ne);
  for (let e = 0; e < ne; e++) {
    const o = owners[mat[st.elemCell[e]]]; own[e] = o.id;
    const key = o.nu.toFixed(6); let m = nuKeys.indexOf(key); if (m < 0) { nuKeys.push(key); Ks.push(hexMatrices(o.nu).K); m = nuKeys.length - 1; }
    mIdx[e] = m; scale[e] = o.E * h; Eel[e] = o.E;
  }
  if (Ks.length > 8) throw new Error('too many different Poisson ratios among the bodies (8 at most)');
  // element coordinates and exposed faces
  const [nx, ny] = grid.dims, nxy = nx * ny, lo = grid.origin;
  const ei = new Int32Array(ne), ej = new Int32Array(ne), ek = new Int32Array(ne), exposed = new Uint8Array(ne); // exposed: bit f set when face f has no solid neighbour
  for (let e = 0; e < ne; e++) {
    const v = st.elemCell[e], i = v % nx, j = ((v - i) / nx) % ny, k = (v - i - nx * j) / nxy; ei[e] = i; ej[e] = j; ek[e] = k;
    let bits = 0; for (let f = 0; f < 6; f++) if (faceNeighbour(grid, st, e, f) < 0) bits |= 1 << f; exposed[e] = bits;
  }
  const bodyBox = new Map(bodies.map((b) => [bodyOwner.get(b.name), b.bbox]));
  const nearBox = (box, c, tol) => !box || [0, 1, 2].every((q) => c[q] >= Math.min(box[2 * q], box[2 * q + 1]) - tol && c[q] <= Math.max(box[2 * q], box[2 * q + 1]) + tol);
  const idx3 = (e) => [ei[e], ej[e], ek[e]];
  /** Exposed element faces of a body in direction `face`: on the body's bounding-box face (default) or inside `box` (mm) when one is given. */
  const faceSet = (bodyName, face, box) => {
    const f = FACE_LIST.indexOf(face), ax = f >> 1, plus = (f & 1) === 0, out = [], id = bodyOwner.get(bodyName);
    if (!(id > 0) && bodyName) return out;
    for (let e = 0; e < ne; e++) {
      if (!(exposed[e] & (1 << f)) || (bodyName && own[e] !== id)) continue;
      const ix = idx3(e), facePos = lo[ax] + (ix[ax] + (plus ? 1 : 0)) * h, fc = [0, 1, 2].map((q) => lo[q] + (ix[q] + 0.5) * h); fc[ax] = facePos;
      if (box) { if (!nearBox(box, fc, 0.49 * h)) continue; }
      else { const bx = bodyBox.get(own[e]); if (!(plus ? facePos >= bx.max[ax] - 0.51 * h : facePos <= bx.min[ax] + 0.51 * h)) continue; }
      out.push(e);
    }
    return out;
  };
  const faceNodes = (e, f) => HEX_FACE_CORNERS[f].map((a) => st.elemNodes[8 * e + a]);
  // supports
  const held = new Uint8Array(nd); let nFixedNodes = 0, nSliderNodes = 0;
  const supports = [];
  for (const m of cond.mounts || []) {
    if (m.kind !== 'fixed' && m.kind !== 'slider') continue;
    const es = faceSet(m.body, m.face, m.box || null);
    if (!es.length) { warnings.push(`Mount "${m.id}" (${m.kind}, ${m.body} ${m.face}${m.box ? ' within the given box' : ''}) matches no exposed faces at this voxel size; it holds nothing.`); continue; }
    const f = FACE_LIST.indexOf(m.face), ax = f >> 1, nodes = new Set();
    for (const e of es) for (const nd2 of faceNodes(e, f)) nodes.add(nd2);
    for (const nd2 of nodes) { if (m.kind === 'fixed') { for (let c = 0; c < 3; c++) held[3 * nd2 + c] = 1; } else held[3 * nd2 + ax] = 1; }
    if (m.kind === 'fixed') nFixedNodes += nodes.size; else nSliderNodes += nodes.size;
    supports.push({ id: m.id, kind: m.kind, body: m.body, face: m.face, faces: es.length, nodes: nodes.size });
  }
  for (const m of cond.mounts || []) if (m.kind === 'contact') warnings.push(`Mount "${m.id}" is a thermal contact mount; it has no structural effect.`);
  // loads
  const F = new Float64Array(nd), vol = new Float64Array(owners.length), loads = [];
  for (let e = 0; e < ne; e++) vol[own[e]] += h ** 3;
  const dens = new Float64Array(owners.length).fill(1); // scale of the density so the mass of a body equals mesh volume x rho (the voxel solid is thicker than the mesh)
  for (const b of bodies) { const id = bodyOwner.get(b.name); if (vol[id] > 0 && b.volume_mm3 > 0) dens[id] = Math.min(2, Math.max(0.5, b.volume_mm3 / vol[id])); }
  const addFaceForce = (e, f, fx, fy, fz) => { for (const nd2 of faceNodes(e, f)) { F[3 * nd2] += fx / 4; F[3 * nd2 + 1] += fy / 4; F[3 * nd2 + 2] += fz / 4; } };
  const addBody = (aVec_ms2, label) => {
    // body force rho a (N/m^3) times the cell volume; rho in kg/m^3, a in m/s^2, cell volume in m^3
    let tot = [0, 0, 0], mass = 0;
    for (let e = 0; e < ne; e++) {
      const o = owners[own[e]]; if (!(o.rho > 0)) continue;
      const m = o.rho * dens[o.id] * hM ** 3; mass += m;
      for (let c = 0; c < 3; c++) { const f = m * aVec_ms2[c] / 8; tot[c] += 8 * f; for (let a = 0; a < 8; a++) F[3 * st.elemNodes[8 * e + a] + c] += f; }
    }
    loads.push({ label, kind: 'body', total_N: tot, mass_kg: mass });
    return mass;
  };
  for (const l of cond.loads || []) {
    if (l.kind === 'force') {
      const es = faceSet(l.body, l.face, l.box || null), f = FACE_LIST.indexOf(l.face);
      if (!es.length) { warnings.push(`Load "${l.id}" (force on ${l.body} ${l.face}) matches no exposed faces at this voxel size; it is ignored.`); continue; }
      for (const e of es) addFaceForce(e, f, l.vector_N[0] / es.length, l.vector_N[1] / es.length, l.vector_N[2] / es.length);
      loads.push({ label: l.id, kind: 'force', total_N: l.vector_N.slice(), faces: es.length });
    } else if (l.kind === 'pressure') {
      const es = faceSet(l.body, l.face, l.box || null), f = FACE_LIST.indexOf(l.face), n = FACE_N[f], pMPa = l.pressure_Pa * 1e-6;
      if (!es.length) { warnings.push(`Load "${l.id}" (pressure on ${l.body} ${l.face}) matches no exposed faces at this voxel size; it is ignored.`); continue; }
      const fa = pMPa * h * h; for (const e of es) addFaceForce(e, f, -fa * n[0], -fa * n[1], -fa * n[2]);
      loads.push({ label: l.id, kind: 'pressure', total_N: [-fa * n[0] * es.length, -fa * n[1] * es.length, -fa * n[2] * es.length], faces: es.length, area_mm2: es.length * h * h });
    } else if (l.kind === 'acceleration') {
      const n = FACE_N[FACE_LIST.indexOf(l.direction)];
      if (!owners.some((o) => o && o.rho > 0)) { warnings.push('An acceleration load needs a material density; none of the bodies has one.'); continue; }
      addBody(n.map((c) => c * l.accel_ms2), `${l.id}: ${+(l.accel_ms2 / G_STD).toFixed(3)} g toward ${l.direction}`);
    }
  }
  if (settings.self_weight === 'on') { const g = FACE_N[FACE_LIST.indexOf(cond.ambient.gravity || '-z')]; addBody(g.map((c) => c * G_STD), `self weight toward ${cond.ambient.gravity || '-z'}`); }
  const preset = presetAccel(settings);
  if (preset) addBody(FACE_N[FACE_LIST.indexOf(settings.preset_dir)].map((c) => c * preset.a), `${settings.preset} preset: ${(preset.a / G_STD).toFixed(1)} g toward ${settings.preset_dir}`);
  for (const p of settings.preloads) {
    const ax = 'xyz'.indexOf(p.axis), o2 = [0, 1, 2].filter((q) => q !== ax), plusF = ax * 2, minusF = ax * 2 + 1, id = p.body ? bodyOwner.get(p.body) : 0;
    const pick = (f) => {
      const cand = []; let ext = null; const plus = (f & 1) === 0;
      for (let e = 0; e < ne; e++) {
        if (!(exposed[e] & (1 << f)) || (id && own[e] !== id)) continue;
        const ix = idx3(e), c = [0, 1, 2].map((q) => lo[q] + (ix[q] + 0.5) * h);
        if (Math.hypot(c[o2[0]] - p.center[o2[0]], c[o2[1]] - p.center[o2[1]]) > p.radius_mm + 0.5 * h) continue;
        const pos = lo[ax] + (ix[ax] + (plus ? 1 : 0)) * h; cand.push([e, pos]); if (ext === null || (plus ? pos > ext : pos < ext)) ext = pos;
      }
      return cand.filter(([, pos]) => Math.abs(pos - ext) < 0.51 * h).map(([e]) => e);
    };
    const A = pick(plusF), B = pick(minusF);
    if (!A.length || !B.length) { warnings.push(`Bolt preload at ${p.center.join(', ')} (${p.axis}, radius ${p.radius_mm} mm) found no faces on ${!A.length ? 'the + side' : 'the - side'}; it is ignored.`); continue; }
    const e3 = [0, 0, 0]; e3[ax] = 1;
    for (const e of A) addFaceForce(e, plusF, -p.force_N * e3[0] / A.length, -p.force_N * e3[1] / A.length, -p.force_N * e3[2] / A.length);
    for (const e of B) addFaceForce(e, minusF, p.force_N * e3[0] / B.length, p.force_N * e3[1] / B.length, p.force_N * e3[2] / B.length);
    loads.push({ label: `bolt preload ${p.force_N} N (${p.axis})`, kind: 'preload', total_N: [0, 0, 0], faces: A.length + B.length });
  }
  const Fall = Float64Array.from(F), Ftot = [0, 0, 0]; for (let q = 0; q < nd; q++) Ftot[q % 3] += F[q]; // the whole applied load, also what lands on held dofs
  let Fabs = 0; for (let q = 0; q < nd; q++) Fabs += Math.abs(F[q]);
  for (let q = 0; q < nd; q++) if (held[q]) F[q] = 0; // a load on a held dof goes straight into the support
  if (!(Fabs > 0)) warnings.push('There are no loads (Analyze > Conditions > Loads, or a preset or self weight in the settings); the displacement and stress are zero.');
  // is every piece fully supported?
  let nHeld = 0; for (let q = 0; q < nd; q++) if (held[q]) nHeld++;
  if (!nHeld) throw new Error('nothing holds the model: add a fixed mount (Analyze > Conditions > Mounts, kind fixed) on a body face, or a slider mount for a symmetry plane');
  const bad = unsupportedPieces(st, (q) => held[q] === 1, h);
  if (bad.length) {
    const names = bad.map((b) => `piece ${b.piece} (${b.nodes.toLocaleString('en')} nodes, ${b.free} of 6 rigid motions free)`);
    throw new Error(`the supports do not hold every body: ${names.join('; ')}. A fixed mount holds all motion; sliders alone leave sliding or rotation free. Add a fixed mount, or a slider on a second plane.`);
  }
  // owner of each node (the body of its first element: bodies that touch share the nodes where they touch) and the nodes on an exposed face
  const nodeOwner = new Uint8Array(nn), onSurface = new Uint8Array(nn);
  for (let q = 0; q < nn; q++) nodeOwner[q] = own[st.nodeElem[8 * q] >> 3];
  for (let e = 0; e < ne; e++) if (exposed[e]) for (let f = 0; f < 6; f++) if (exposed[e] & (1 << f)) for (const nd2 of faceNodes(e, f)) onSurface[nd2] = 1;
  const diag = elasticDiagonal({ ne, nn, elemNodes: st.elemNodes, scale, mIdx, Ks }), inv = new Float64Array(nd);
  for (let q = 0; q < nd; q++) inv[q] = held[q] ? 0 : 1 / diag[q];
  let volVox = 0; for (let e = 0; e < ne; e++) volVox += h ** 3; const volMesh = bodies.reduce((a, b) => a + b.volume_mm3, 0);
  return { owners, bodyOwner, grid, mat, st, ne, nn, nd, h, hM, own, Ks, nuKeys, mIdx, scale, Eel, ei, ej, ek, exposed, nodeOwner, onSurface, held, F, Fall, Ftot, inv, loads, supports, warnings, budget, budgetClass: cls, volVox, volMesh, bodies, bb, dens, solid, preset, stats: vm.stats, nFixedNodes, nSliderNodes };
}

// ───────────────────────── solve and recovery ─────────────────────────
const specOf = (model) => ({ ne: model.ne, nn: model.nn, elemNodes: model.st.elemNodes, nodeElem: model.st.nodeElem, scale: model.scale, mIdx: model.mIdx, Ks: model.Ks, inv: model.inv });

/** Reactions at the held dofs and the equilibrium error: with K u = F + R, the sum of R and F must vanish. */
function reactions(model, u) {
  const op = makeElasticOp(specOf(model)), y = new Float64Array(model.nd); op.applyFull(u, y);
  const R = [0, 0, 0], F = model.Fall;
  for (let q = 0; q < model.nd; q++) if (model.held[q]) R[q % 3] += y[q] - F[q];
  const bal = [0, 1, 2].map((c) => R[c] + model.Ftot[c]);
  return { R, balance: Math.hypot(...bal), reaction: Math.hypot(...R), applied: Math.hypot(...model.Ftot) };
}

/** Stress at every element centre (MPa, 6 per element), from the nodal displacements. */
function elementStresses(model, u) {
  const { ne, st, h, Eel, mIdx, nuKeys } = model, sig = new Float32Array(6 * ne), ue = new Float64Array(24), out = new Float64Array(6), Ms = nuKeys.map((k) => hexMatrices(parseFloat(k)));
  for (let e = 0; e < ne; e++) {
    for (let a = 0; a < 8; a++) { const n3 = 3 * st.elemNodes[8 * e + a]; ue[3 * a] = u[n3]; ue[3 * a + 1] = u[n3 + 1]; ue[3 * a + 2] = u[n3 + 2]; }
    elementStress(Ms[mIdx[e]], Eel[e], h, ue, out); for (let c = 0; c < 6; c++) sig[6 * e + c] = out[c];
  }
  return sig;
}

/**
 * Stress recovery at the nodes: at every node, a local least-squares fit of a LINEAR field to the element-centre stress tensors of the
 * same body within `R` voxels of the node (a ball of centre distance <= R voxels, about 32 elements for R = 2), evaluated at the node.
 * This averages the stair-step noise of a voxel surface over a 2-voxel neighbourhood and, unlike a plain average of the neighbours,
 * does not read low at a free surface where the stress has a gradient (a plain average over a one-sided window did: see the validation
 * notes). A tiny ridge (1e-7) on the slope terms makes a flat window fall back to the mean. R = 0 averages the elements around the node.
 */
function recoverNodal(model, sig, R) {
  const { ne, nn, grid, st, own, nodeOwner } = model, [nx, ny, nz] = grid.dims, out = new Float32Array(6 * nn), r = Math.ceil(Math.max(R, 1)), offs = [];
  for (let dk = -r; dk <= r; dk++) for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) {
    // element (i+di, j+dj, k+dk) of node (i, j, k): centre offset in voxels
    const cx = di + 0.5, cy = dj + 0.5, cz = dk + 0.5;
    if (R <= 0 ? (Math.abs(cx) < 0.51 && Math.abs(cy) < 0.51 && Math.abs(cz) < 0.51) : cx * cx + cy * cy + cz * cz <= R * R + 1e-9) offs.push([di, dj, dk, cx, cy, cz]);
  }
  const N = new Float64Array(16), rhs = new Float64Array(24), M = new Float64Array(16), X = new Float64Array(24), p = new Float64Array(4);
  for (let q = 0; q < nn; q++) {
    const i = st.nodeIJK[3 * q], j = st.nodeIJK[3 * q + 1], k = st.nodeIJK[3 * q + 2], o = nodeOwner[q];
    N.fill(0); rhs.fill(0); let cnt = 0;
    for (const [di, dj, dk, cx, cy, cz] of offs) {
      const a = i + di, b = j + dj, c = k + dk; if (a < 0 || b < 0 || c < 0 || a >= nx || b >= ny || c >= nz) continue;
      const e = st.elemOf[a + nx * (b + ny * c)]; if (e < 0 || own[e] !== o) continue;
      p[0] = 1; p[1] = cx; p[2] = cy; p[3] = cz; cnt++;
      for (let u = 0; u < 4; u++) { for (let v = 0; v < 4; v++) N[4 * u + v] += p[u] * p[v]; for (let t = 0; t < 6; t++) rhs[4 * t + u] += p[u] * sig[6 * e + t]; }
    }
    if (cnt === 0) continue;
    M.set(N); for (let u = 1; u < 4; u++) M[5 * u] += 1e-7 * cnt;
    X.set(rhs); solveDense4(M, X);
    for (let t = 0; t < 6; t++) out[6 * q + t] = X[4 * t];
  }
  return out;
}
/** Solve M y = x for the six right-hand sides stored as rows of X (4 values each), M 4 x 4 symmetric positive definite; X is overwritten. */
function solveDense4(M, X) {
  const A = M; // in place Gauss elimination (no pivoting: M is SPD)
  for (let c = 0; c < 4; c++) {
    const d = A[5 * c]; if (!(d > 1e-300)) { for (let t = 0; t < 6; t++) X[4 * t + c] = 0; continue; }
    for (let r = c + 1; r < 4; r++) { const f = A[4 * r + c] / d; if (f === 0) continue; for (let k = c; k < 4; k++) A[4 * r + k] -= f * A[4 * c + k]; for (let t = 0; t < 6; t++) X[4 * t + r] -= f * X[4 * t + c]; }
  }
  for (let t = 0; t < 6; t++) for (let c = 3; c >= 0; c--) { let v = X[4 * t + c]; for (let k = c + 1; k < 4; k++) v -= A[4 * c + k] * X[4 * t + k]; X[4 * t + c] = A[5 * c] > 1e-300 ? v / A[5 * c] : 0; }
}

/** Per node: von Mises of the recovered stress, the effective stress with the inter-layer knock-down, and the safety factor. */
function utilisation(model, sigN, settings) {
  const { nn, nodeOwner, owners } = model, ax = 'xyz'.indexOf(settings.print_axis), vm = new Float32Array(nn), eff = new Float32Array(nn), sf = new Float32Array(nn), s = new Float64Array(6);
  for (let q = 0; q < nn; q++) {
    for (let t = 0; t < 6; t++) s[t] = sigN[6 * q + t];
    const v = vonMises(s), o = owners[nodeOwner[q]], f = settings.knockdown === 'on' ? o.interlayer : 1, tn = Math.max(0, s[ax]); // tension across the layers
    const ef = Math.max(v, f < 1 ? tn / f : 0);
    vm[q] = v; eff[q] = ef; sf[q] = o.strength > 0 ? (ef > 1e-9 ? o.strength / ef : SF_BIG) : SF_BIG;
  }
  return { vm, eff, sf };
}

/** Solve (CPU, synchronous: tests and the validation harness) and recover everything. */
function solveSync(input, override) {
  const key = JSON.stringify(override || {}); let cache = memo.get(input); if (!cache) { cache = new Map(); memo.set(input, cache); } if (cache.has(key)) return cache.get(key);
  const { settings, warnings } = settingsOf(input, override), model = buildModel(input, settings, { backendKind: 'cpu', device: 'laptop' });
  const op = makeElasticOp(specOf(model)), x = new Float64Array(model.nd), b = Float64Array.from(model.F);
  for (let q = 0; q < model.nd; q++) if (model.held[q]) b[q] = 0;
  const t0 = Date.now(), r = runSync(pcgElastic(op, b, x, { tol: settings.tol > 0 ? settings.tol : 1e-9, maxIter: 50000 }));
  const sim = recover(model, settings, x, { iters: r.iters, rel: r.rel, converged: r.converged, seconds: (Date.now() - t0) / 1000, backend: 'CPU' });
  const out = { ...sim, model, settings, warnings };
  cache.set(key, out); return out;
}

function recover(model, settings, u, solveInfo) {
  const sig = elementStresses(model, u), sigN = recoverNodal(model, sig, settings.smooth_voxels), ut = utilisation(model, sigN, settings), rawVm = new Float32Array(model.ne), s = new Float64Array(6);
  for (let e = 0; e < model.ne; e++) { for (let t = 0; t < 6; t++) s[t] = sig[6 * e + t]; rawVm[e] = vonMises(s); }
  let maxU = 0, maxN = -1;
  const mag = new Float32Array(model.nn);
  for (let q = 0; q < model.nn; q++) { const m = Math.hypot(u[3 * q], u[3 * q + 1], u[3 * q + 2]); mag[q] = m; if (m > maxU) { maxU = m; maxN = q; } }
  let nVm = -1, vmMax = -1, eRaw = -1, rawMax = -1, nSf = -1, sfMin = Infinity;
  for (let q = 0; q < model.nn; q++) { if (ut.vm[q] > vmMax) { vmMax = ut.vm[q]; nVm = q; } if (model.owners[model.nodeOwner[q]].strength > 0 && ut.sf[q] < sfMin) { sfMin = ut.sf[q]; nSf = q; } }
  for (let e = 0; e < model.ne; e++) if (rawVm[e] > rawMax) { rawMax = rawVm[e]; eRaw = e; }
  // strain energy 1/2 f.u over the loaded dofs, and the equivalent stiffness |F|^2 / (f.u)
  let fu = 0; for (let q = 0; q < model.nd; q++) fu += model.F[q] * u[q];
  const reac = reactions(model, u);
  const pos = (e) => cellCentre(model.grid, model.st.elemCell[e]);
  const nodePos = (q) => [0, 1, 2].map((c) => model.grid.origin[c] + model.st.nodeIJK[3 * q + c] * model.h);
  return { u, sig, sigN, ut, rawVm, mag, maxU, maxNode: maxN, vmMax, nVm, rawMax, eRaw, sfMin: nSf >= 0 ? sfMin : SF_BIG, nSf, energy: 0.5 * fu, fu, reac, pos, nodePos, boundaryNode: (q) => model.onSurface[q] === 1, solve: solveInfo };
}

// ───────────────────────── sampling at points (surface fields and probes) ─────────────────────────
/** Trilinear interpolation of a nodal field with `comps` values per node at a point (mm), inside the element that holds it, or the nearest element (clamped). Returns an array or null. */
function sampleNodal(model, vals, comps, x, y, z) {
  const { grid, st, h } = model, [nx, ny, nz] = grid.dims, p = [x, y, z], ix = [0, 1, 2].map((q) => Math.floor((p[q] - grid.origin[q]) / h));
  const at = (a, b, c) => (a < 0 || b < 0 || c < 0 || a >= nx || b >= ny || c >= nz ? -1 : st.elemOf[a + nx * (b + ny * c)]);
  let e = at(ix[0], ix[1], ix[2]);
  if (e < 0) { let best = Infinity; for (let r = 1; r <= 2 && e < 0; r++) for (let dk = -r; dk <= r; dk++) for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) { const q = at(ix[0] + di, ix[1] + dj, ix[2] + dk); if (q < 0) continue; const d = di * di + dj * dj + dk * dk; if (d < best) { best = d; e = q; } } }
  if (e < 0) return null;
  const t = [model.ei[e], model.ej[e], model.ek[e]].map((c, q) => Math.min(1, Math.max(0, (p[q] - grid.origin[q]) / h - c))), out = new Array(comps).fill(0);
  for (let a = 0; a < 8; a++) {
    const w = ((a & 1) ? t[0] : 1 - t[0]) * (((a >> 1) & 1) ? t[1] : 1 - t[1]) * ((a >> 2) ? t[2] : 1 - t[2]), n0 = comps * st.elemNodes[8 * e + a];
    for (let c = 0; c < comps; c++) out[c] += w * vals[n0 + c];
  }
  return out;
}
const sampleDisp = (model, u, x, y, z) => sampleNodal(model, u, 3, x, y, z);

/** Trilinear sample of an element-centred scalar at a point (mm); falls back to the nearest element, then to `fallback`. */
function sampleElems(model, vals, x, y, z, fallback) {
  const { grid, st, h } = model, [nx, ny, nz] = grid.dims, fx = (x - grid.origin[0]) / h - 0.5, fy = (y - grid.origin[1]) / h - 0.5, fz = (z - grid.origin[2]) / h - 0.5;
  const i0 = Math.floor(fx), j0 = Math.floor(fy), k0 = Math.floor(fz), tx = fx - i0, ty = fy - j0, tz = fz - k0; let acc = 0, wsum = 0;
  for (let dk = 0; dk < 2; dk++) for (let dj = 0; dj < 2; dj++) for (let di = 0; di < 2; di++) {
    const i = i0 + di, j = j0 + dj, k = k0 + dk; if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) continue;
    const e = st.elemOf[i + nx * (j + ny * k)]; if (e < 0) continue;
    const w = (di ? tx : 1 - tx) * (dj ? ty : 1 - ty) * (dk ? tz : 1 - tz); acc += w * vals[e]; wsum += w;
  }
  if (wsum > 1e-9) return acc / wsum;
  const ci = Math.min(nx - 1, Math.max(0, Math.round(fx))), cj = Math.min(ny - 1, Math.max(0, Math.round(fy))), ck = Math.min(nz - 1, Math.max(0, Math.round(fz)));
  for (let r = 1; r <= 2; r++) for (let dk = -r; dk <= r; dk++) for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) {
    const i = ci + di, j = cj + dj, k = ck + dk; if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) continue;
    const e = st.elemOf[i + nx * (j + ny * k)]; if (e >= 0) return vals[e];
  }
  return fallback;
}

// ───────────────────────── entry points ─────────────────────────
const memo = new WeakMap();
/** Settings of this package stored in the project (ext[<id>].settings), merged under `override`. */
function settingsOf(input, override) {
  const stored = input && input.project && input.project.ext && input.project.ext[STR_ID] && input.project.ext[STR_ID].settings;
  return parseSettings({ ...(stored || {}), ...(override || {}) });
}

/** Single numbers for the validation harness (a case calls a function and compares what it returns). */
const probeOf = (input, override, key) => { const v = solveSync(input, override)[key]; return v === null || v === undefined ? NaN : v; };
const probeU = (input, override, x, y, z, comp) => { const s = solveSync(input, override), d = sampleDisp(s.model, s.u, x, y, z); return d ? d[comp] : NaN; };
/** Recovered (or, with raw, element-centre) von Mises stress at a point, MPa. */
const probeVm = (input, override, x, y, z, raw) => { const s = solveSync(input, override); if (raw) return sampleElems(s.model, s.rawVm, x, y, z, NaN); const v = sampleNodal(s.model, s.ut.vm, 1, x, y, z); return v ? v[0] : NaN; };
/** One component of the stress tensor at a point, MPa: 0 xx, 1 yy, 2 zz, 3 xy, 4 yz, 5 zx. Recovered nodal value, or with raw the element-centre value. */
const probeSigma = (input, override, x, y, z, comp, raw) => {
  const s = solveSync(input, override);
  if (!raw) { const v = sampleNodal(s.model, s.sigN, 6, x, y, z); return v ? v[comp] : NaN; }
  const n = s.model.ne, c = new Float32Array(n); for (let e = 0; e < n; e++) c[e] = s.sig[6 * e + comp]; return sampleElems(s.model, c, x, y, z, NaN);
};

/** Force (N) carried by the section of the slab of elements that holds `pos` (mm) on `axis`: the sum of sigma_comp h^2 over the element centres (comp 0 xx, 1 yy, 2 zz, 3 xy, 4 yz, 5 zx). Equilibrium says it equals the load on one side of the cut. */
const sectionForce = (input, override, axis, pos, comp) => {
  const s = solveSync(input, override), m = s.model, a = 'xyz'.indexOf(axis), idx = Math.floor((pos - m.grid.origin[a]) / m.h), I = a === 0 ? m.ei : a === 1 ? m.ej : m.ek; let f = 0;
  for (let e = 0; e < m.ne; e++) if (I[e] === idx) f += s.sig[6 * e + comp] * m.h * m.h;
  return f;
};

/**
 * The force the lower part exerts on the upper part through the node plane `plane` (a node index along `axis`), from the element nodal forces of
 * the elements above the plane (K u at the plane nodes). Equilibrium of the upper part makes it exactly minus the load applied above, up to the
 * solver tolerance; it is the weak-form check of the section force that a sum of element-centre stresses is not (that one is only about 8 % close).
 */
const sectionForceNodal = (input, override, axis, plane, comp) => {
  const s = solveSync(input, override), m = s.model, a = 'xyz'.indexOf(axis), I = a === 0 ? m.ei : a === 1 ? m.ej : m.ek, ue = new Float64Array(24); let f = 0;
  for (let e = 0; e < m.ne; e++) {
    if (I[e] < plane) continue;
    for (let b = 0; b < 8; b++) { const n3 = 3 * m.st.elemNodes[8 * e + b]; ue[3 * b] = s.u[n3]; ue[3 * b + 1] = s.u[n3 + 1]; ue[3 * b + 2] = s.u[n3 + 2]; }
    const K = m.Ks[m.mIdx[e]];
    for (let b = 0; b < 8; b++) { if (m.st.nodeIJK[3 * m.st.elemNodes[8 * e + b] + a] !== plane) continue; let t = 0; for (let c = 0; c < 24; c++) t += K[(3 * b + comp) * 24 + c] * ue[c]; f += t * m.scale[e]; }
  }
  return f;
};

const ASSUMPTIONS = [
  'Linear elastic, small displacements, isotropic material (Young\'s modulus and Poisson\'s ratio of the material of each body); no plasticity, contact, buckling, creep or fatigue. Bodies that touch are bonded where their voxels touch.',
  'Every solid voxel is one 8-node hexahedron (trilinear displacement plus condensed incompatible modes); enclosed air and outside air carry no load. Voxel stair-steps inflate stress at curved edges and corners and thicken thin walls by up to one voxel (stiffness is overstated there).',
  'Stress is computed at the centre of each element and recovered at the nodes by a local linear least-squares fit over the elements of the same body within 2 voxels (the tensor, then von Mises), which smooths stair-step noise and reads the value at the surface node. A sharp feature smaller than a few voxels is still under-resolved; a peak on a surface node is flagged as an edge artefact likely.',
  'Supports: a fixed mount holds all three displacements at the nodes of the selected faces; a slider mount holds only the displacement normal to the face (symmetry plane or frictionless support). Faces are picked by direction on a body\'s bounding-box face, or inside a box, not in the viewport.',
  'Acceleration loads (including drop and recoil presets and self weight) are quasi-static body forces rho a toward the stated direction, as for gravity: the part is held while loaded as if accelerating. Impact, vibration and stress waves are not modelled. Parts on the board have no mass here.',
  'The safety factor is the yield strength (the tensile strength if there is no yield) over the von Mises stress, with an FDM knock-down: tension across the printed layers (along the print axis, default z) is divided by the material\'s inter-layer factor before it is compared. Stiffness is taken isotropic; real printed parts are weaker and softer across the layers.',
  'A bolt preload is two equal and opposite compressive discs on the outermost faces around a point, a simplification of a bolt and washer (no thread, no contact).',
];

/** The analysis run (in the Worker). */
async function runStructural(input, ctx) {
  const t0 = Date.now(), { settings, warnings: sw } = settingsOf(input), warnings = [...sw];
  ctx.progress(0.02);
  let backend = cpuElasticBackend, gpuNote = null, gpu = null;
  if (settings.backend !== 'cpu') { gpu = await gpuElasticBackend(); if (!gpu) gpuNote = `WebGPU is not available${gpuElasticBackend.lastError ? ` (${gpuElasticBackend.lastError})` : ''}; the CPU solver is used with a smaller grid.`; }
  const device = typeof navigator !== 'undefined' ? deviceClass(navigator) : 'laptop';
  let model = buildModel(input, settings, { backendKind: gpu ? 'gpu' : 'cpu', device });
  if (gpu && settings.backend === 'auto' && model.ne < 20000) { gpu = null; model = buildModel(input, settings, { backendKind: 'cpu', device }); }
  if (gpu && model.nd > gpu.maxDofs) { warnings.push(`This grid has ${model.nd.toLocaleString('en')} dofs, more than this GPU's buffers hold (${gpu.maxDofs.toLocaleString('en')}); the CPU solver is used with a smaller grid.`); gpu = null; model = buildModel(input, settings, { backendKind: 'cpu', device }); }
  if (gpu) backend = gpu;
  if (gpuNote) warnings.push(gpuNote);
  if (ctx.signal.aborted) throw new Error('cancelled');
  ctx.progress(0.1);
  const tVox = (Date.now() - t0) / 1000;
  let op;
  try { op = await backend.createOperator(specOf(model)); } catch (e) {
    if (!gpu) throw e;
    warnings.push(`WebGPU could not allocate this grid (${String((e && e.message) || e).slice(0, 120)}); the CPU solver is used with a smaller grid.`);
    gpu = null; backend = cpuElasticBackend; model = buildModel(input, settings, { backendKind: 'cpu', device }); op = await backend.createOperator(specOf(model));
  }
  const tol = settings.tol > 0 ? settings.tol : backend.kind === 'gpu' ? 1e-5 : 1e-8, b = Float64Array.from(model.F), x0 = new Float64Array(model.nd);
  for (let q = 0; q < model.nd; q++) if (model.held[q]) b[q] = 0;
  const t1 = Date.now(), lt = Math.log10(1 / tol); let r, lastP = 0.1;
  try { r = await op.solve(b, x0, { tol, maxIter: 50000, signal: ctx.signal, onStep: (s) => { const v = Math.min(0.9, 0.1 + 0.8 * Math.max(0, Math.log10(1 / Math.max(s.rel, 1e-30))) / lt); if (v - lastP >= 0.01) { lastP = v; ctx.progress(v); } } }); } finally { op.destroy && op.destroy(); }
  const tSolve = (Date.now() - t1) / 1000;
  ctx.progress(0.92);
  const sim = recover(model, settings, r.x, { iters: r.iters, rel: r.rel, converged: r.converged, seconds: tSolve, backend: backend.label });
  const { grid, owners, h } = model;
  const rows = [
    { metric: 'max_displacement', value: sim.maxU, unit: 'mm', where: sim.nodePos(sim.maxNode) },
    { metric: 'max_von_mises', value: sim.vmMax, unit: 'MPa', where: sim.nodePos(sim.nVm) },
    { metric: 'max_von_mises_unsmoothed', value: sim.rawMax, unit: 'MPa', where: sim.pos(sim.eRaw) },
    { metric: 'min_safety_factor', value: Math.min(sim.sfMin, SF_BIG), unit: '', ...(sim.nSf >= 0 ? { where: sim.nodePos(sim.nSf) } : {}) },
    { metric: 'peak_on_boundary', value: sim.boundaryNode(sim.nVm) ? 1 : 0, unit: '' },
  ];
  if (sim.reac.applied > 1e-12 && sim.fu > 0) rows.push({ metric: 'stiffness', value: sim.reac.applied ** 2 / sim.fu, unit: 'N/mm' });
  rows.push({ metric: 'strain_energy', value: sim.energy, unit: 'N*mm' },
    { metric: 'applied_force', value: sim.reac.applied, unit: 'N' }, { metric: 'reaction_force', value: sim.reac.reaction, unit: 'N' }, { metric: 'equilibrium_error', value: sim.reac.balance, unit: 'N' },
    { metric: 'voxel_size', value: h, unit: 'mm' }, { metric: 'active_voxels', value: model.ne, unit: '' }, { metric: 'voxel_budget', value: model.budget, unit: '' }, { metric: 'dofs', value: model.nd, unit: '' },
    { metric: 'solver_iterations', value: r.iters, unit: '' }, { metric: 'solver_residual', value: r.rel, unit: '' }, { metric: 'solve_seconds', value: +tSolve.toFixed(3), unit: 's' });
  // surface fields per body (stress first so it is the one drawn by default)
  const fields = [], gridInfo = { origin: grid.origin, h, dims: grid.dims, active: model.ne, budget: model.budget }, per = model.bodies.map((bd) => {
    const pos = bd.positions, nv = pos.length / 3, o = owners[model.bodyOwner.get(bd.name)], vmv = new Float32Array(nv), dv = new Float32Array(nv), sfv = new Float32Array(nv);
    for (let i = 0; i < nv; i++) {
      const x = pos[3 * i], y = pos[3 * i + 1], z = pos[3 * i + 2], d = sampleDisp(model, sim.u, x, y, z);
      dv[i] = d ? Math.hypot(d[0], d[1], d[2]) : 0;
      const vv = sampleNodal(model, sim.ut.vm, 1, x, y, z); vmv[i] = vv ? vv[0] : 0;
      const ee = sampleNodal(model, sim.ut.eff, 1, x, y, z), ef = ee ? ee[0] : 0; sfv[i] = o.strength > 0 ? Math.min(SF_CAP, ef > 1e-9 ? o.strength / ef : SF_CAP) : SF_CAP;
    }
    return { bd, vmv, dv, sfv };
  });
  const cand = [...per.map((p) => ({ name: `von_mises:${p.bd.name}`, unit: 'MPa', kind: 'surface', body: p.bd.name, data: p.vmv, grid: gridInfo })), ...per.map((p) => ({ name: `displacement:${p.bd.name}`, unit: 'mm', kind: 'surface', body: p.bd.name, data: p.dv, grid: gridInfo })), ...per.map((p) => ({ name: `safety_factor:${p.bd.name}`, unit: '', kind: 'surface', body: p.bd.name, data: p.sfv, grid: gridInfo }))];
  fields.push(...cand.slice(0, 16)); if (cand.length > 16) warnings.push(`There are ${per.length} bodies: only ${fields.length} of ${cand.length} surface fields are returned (the limit is 16).`);
  // honest statements
  warnings.push(`Grid: ${grid.dims.join('×')} voxels of ${h.toFixed(3)} mm, ${model.ne.toLocaleString('en')} solid (budget ${model.budget.toLocaleString('en')} for ${settings.quality} on ${gpu ? (device === 'phone' ? 'a phone GPU' : 'a laptop GPU') : settings.backend === 'cpu' ? 'the CPU' : 'the CPU fallback'}; hard limit ${GRID_HARD_LIMIT.active.toLocaleString('en')} active, ${GRID_HARD_LIMIT.total.toLocaleString('en')} cells), ${model.nn.toLocaleString('en')} nodes, ${model.nd.toLocaleString('en')} dofs.`);
  warnings.push(`Solver: ${backend.label}, ${r.iters.toLocaleString('en')} CG iterations to a relative residual of ${r.rel.toExponential(1)} (tolerance ${tol.toExponential(0)}); voxelising ${tVox.toFixed(1)} s, solving ${tSolve.toFixed(1)} s.`);
  const ratio = model.volVox / Math.max(model.volMesh, 1e-9);
  warnings.push(`Voxelised solid volume is ${(100 * ratio).toFixed(0)} % of the mesh volume${ratio > 1.15 ? ' (thin walls are thickened by the conservative voxelizer; stiffness and weight are overstated there, use fine or a smaller voxel size)' : ratio < 0.85 ? ' (the mesh is not closed or parts are thinner than a voxel)' : ''}.`);
  warnings.push(...model.warnings);
  for (const o of owners.slice(1)) if (o.assumed) warnings.push(`Body "${o.name}" has no material: ${o.material} is assumed (E ${(o.E / 1000).toFixed(2)} GPa, ${o.strengthKind} ${o.strength.toFixed(0)} MPa).`);
  if (!r.converged) warnings.push(`The solve did not fully converge (relative residual ${r.rel.toExponential(1)}); treat the numbers as approximate.`);
  if (sim.reac.applied > 0 && sim.reac.balance > 1e-3 * sim.reac.applied) warnings.push(`Equilibrium of the reactions and the loads is off by ${sim.reac.balance.toExponential(1)} N (${(100 * sim.reac.balance / sim.reac.applied).toFixed(2)} % of the load).`);
  if (sim.boundaryNode(sim.nVm)) warnings.push(`Edge artefact likely: the peak recovered stress (${sim.vmMax.toFixed(2)} MPa, element-centre peak ${sim.rawMax.toFixed(2)} MPa) sits on a boundary voxel. Voxel stair-steps inflate stress at curved edges and corners, and a sharp feature smaller than a few voxels is under-resolved: use the stress to compare designs, not as a sign-off, and use the Alloy Runner (A4, Elmer) for final numbers.`);
  else warnings.push(`The peak recovered stress (${sim.vmMax.toFixed(2)} MPa) is inside the material, away from the surface. Final numbers at stress concentrations still need the Alloy Runner (A4).`);
  if (model.preset) warnings.push(`Preset ${settings.preset}: ${model.preset.text}; equivalent acceleration ${(model.preset.a / G_STD).toFixed(1)} g toward ${settings.preset_dir}. Quasi-static: impact dynamics are not modelled.`);
  for (const l of model.loads) if (l.kind === 'body') warnings.push(`Body force ${l.label}: total ${Math.hypot(...l.total_N).toFixed(3)} N on ${(l.mass_kg * 1000).toFixed(2)} g of voxelised mass (density scaled so each body's mass equals its mesh volume times rho).`);
  for (const s of model.supports) warnings.push(`Support ${s.id} (${s.kind}) on ${s.body} ${s.face}: ${s.faces} faces, ${s.nodes} nodes held.`);
  const fm = owners.slice(1).filter((o) => o.interlayer < 1 && settings.knockdown === 'on');
  if (fm.length) warnings.push(`FDM knock-down: tension along ${settings.print_axis} (the print axis; the document has no print orientation, so this is the setting) is divided by the inter-layer factor (${fm.map((o) => `${o.name} ${o.interlayer}`).join(', ')}) before the safety factor. Stiffness is not reduced.`);
  warnings.push('Validity "estimate": the beam, bar, pressure, symmetry and body-force cases pass (analysis-validation/cases/structural.json) but the bracket against an Elmer reference from the Alloy Runner does not exist yet (skipped, not a pass), so neither displacement nor stress is claimed as validated.');
  warnings.push(...ASSUMPTIONS.map((a) => 'Assumption: ' + a));
  return { summary: rows, fields, warnings, validity: 'estimate' };
}

async function loadSettings() { try { const v = await forge.storage.project.get('settings'); return v && typeof v === 'object' ? v : {}; } catch (_) { return {}; } }

if (typeof forge !== 'undefined') {
  forge.analysis.register('structural', { run: (input, ctx) => runStructural(input, ctx) });
  forge.agent.tool('set-structural', async (args) => {
    const cur = await loadSettings(), next = { ...cur, ...(args && typeof args === 'object' ? args : {}) };
    const { settings, warnings } = parseSettings(next);
    if (warnings.length) throw new Error(warnings.join(' '));
    await forge.storage.project.set('settings', next);
    return { saved: next, effective: settings };
  });
}
if (typeof __core !== 'undefined') Object.assign(__core, { parseSettings, presetAccel, buildModel, solveSync, recover, reactions, elementStresses, recoverNodal, utilisation, sampleDisp, sampleNodal, sampleElems, settingsOf, probeOf, probeU, probeVm, probeSigma, sectionForce, sectionForceNodal, runStructural, specOf, hexMatrices, makeElasticOp, pcgElastic, runSync, cpuElasticBackend, elasticWgsl, ELASTIC_KERNELS, gridBudget, buildElastic, unsupportedPieces, elasticDiagonal, elementStress, vonMises, GRID_BUDGETS });
