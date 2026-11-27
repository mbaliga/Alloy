// Turns a solved case into what the runner hands to Forge: one VTU in mm / MPa / degrees C (all bodies, a `body` cell field, nodal
// fields), a list of summary metrics, and warnings. The same code serves every adapter, so a number means the same thing whichever
// solver made it.
import { writeVtu, VTK } from './vtu.js';

const SF_BIG = 1000;
const hypot3 = (a, i) => Math.hypot(a[3 * i], a[3 * i + 1], a[3 * i + 2]);

/** The result VTU: all tetrahedra as VTK_TETRA, `body` as cell data, nodal fields as given: { name: { ncomp, data } }. */
export function resultVtu(C, nodalFields) {
  const nt = C.bodies.reduce((a, b) => a + b.tets.length / 4, 0), conn = new Int32Array(4 * nt), off = new Int32Array(nt), types = new Uint8Array(nt).fill(VTK.TETRA), body = new Float64Array(nt), bodyNode = new Float64Array(C.nodeCount);
  let e = 0;
  for (const b of C.bodies) { for (let k = 0; k < b.tets.length; k += 4) { conn.set(b.tets.subarray(k, k + 4), 4 * e); off[e] = 4 * (e + 1); body[e] = b.index; e++; } for (let q = b.nodeRange[0]; q < b.nodeRange[1]; q++) bodyNode[q] = b.index; }
  return writeVtu({ points: C.nodes, cells: { types, connectivity: conn, offsets: off }, pointData: { ...nodalFields, body: { ncomp: 1, data: bodyNode } }, cellData: { body: { ncomp: 1, data: body } } });
}

const where = (C, q) => [C.nodes[3 * q], C.nodes[3 * q + 1], C.nodes[3 * q + 2]];

/**
 * Structural post-processing. u: Float64Array (3 per node, mm); vm: Float64Array (per node, MPa).
 * Returns { fields, summary, warnings } where fields are the nodal arrays to write.
 */
export function postStructural(C, u, vm) {
  const n = C.nodeCount, mag = new Float64Array(n), sf = new Float64Array(n), warnings = [];
  let uMax = 0, uAt = 0, vMax = 0, vAt = 0, sfMin = Infinity, sfAt = -1;
  for (const b of C.bodies) {
    const strength = b.material.yield_Pa > 0 ? b.material.yield_Pa / 1e6 : b.material.tensile_Pa > 0 ? b.material.tensile_Pa / 1e6 : 0;
    if (!(b.material.yield_Pa > 0)) warnings.push(b.material.tensile_Pa > 0 ? `Body "${b.name}": no yield strength; the tensile strength is used for the safety factor.` : `Body "${b.name}": no yield or tensile strength; no safety factor is computed for it.`);
    for (let q = b.nodeRange[0]; q < b.nodeRange[1]; q++) {
      mag[q] = hypot3(u, q); if (mag[q] > uMax) { uMax = mag[q]; uAt = q; }
      if (vm[q] > vMax) { vMax = vm[q]; vAt = q; }
      sf[q] = strength > 0 ? (vm[q] > 1e-9 ? Math.min(strength / vm[q], 10) : 10) : 10;
      if (strength > 0) { const s = vm[q] > 1e-9 ? strength / vm[q] : SF_BIG; if (s < sfMin) { sfMin = s; sfAt = q; } }
    }
  }
  const summary = [
    { metric: 'max_displacement', value: uMax, unit: 'mm', where: where(C, uAt) },
    { metric: 'max_von_mises', value: vMax, unit: 'MPa', where: where(C, vAt) },
    { metric: 'min_safety_factor', value: sfAt >= 0 ? sfMin : SF_BIG, unit: '', ...(sfAt >= 0 ? { where: where(C, sfAt) } : {}) },
  ];
  // stiffness |F|^2 / (F . u_mean) over the loaded faces, for force and pressure loads (N and mm)
  if (C.loads.length) {
    let F = [0, 0, 0], work = 0;
    for (const l of C.loads) {
      const m = [0, 0, 0]; for (const q of l.nodes) for (let c = 0; c < 3; c++) m[c] += u[3 * q + c] / l.nodes.length;
      for (let c = 0; c < 3; c++) { F[c] += l.vector_N[c]; work += l.vector_N[c] * m[c]; }
    }
    const f2 = F[0] ** 2 + F[1] ** 2 + F[2] ** 2;
    if (work > 1e-30 && f2 > 0) summary.push({ metric: 'stiffness', value: f2 / work, unit: 'N/mm' });
    summary.push({ metric: 'applied_force', value: Math.hypot(...F), unit: 'N' });
  }
  summary.push({ metric: 'mesh_elements', value: C.tets, unit: '' }, { metric: 'mesh_nodes', value: C.nodeCount, unit: '' }, { metric: 'mesh_size', value: C.h, unit: 'mm' });
  return { fields: { displacement: { ncomp: 3, data: u }, displacement_magnitude: { ncomp: 1, data: mag }, von_mises: { ncomp: 1, data: vm }, safety_factor: { ncomp: 1, data: sf } }, summary, warnings };
}

/** Thermal post-processing. T: Float64Array (degrees C per node). Energy balance from the convective boundary groups. */
export function postThermal(C, T) {
  const n = C.nodeCount, warnings = []; let tMax = -Infinity, at = 0, tMin = Infinity, P = 0, out = 0;
  for (let q = 0; q < n; q++) { if (T[q] > tMax) { tMax = T[q]; at = q; } if (T[q] < tMin) tMin = T[q]; }
  for (const b of C.bodies) P += b.power_W || 0;
  const bcById = new Map(C.bcs.map((b) => [b.id, b]));
  for (const b of C.bodies) for (const g of C.groups[b.index]) {
    const bc = bcById.get(g.bc); if (!bc || !(bc.h > 0)) continue;
    for (let k = 0; k < g.parent.length; k++) {
      const a = g.tri[3 * k], bb = g.tri[3 * k + 1], c = g.tri[3 * k + 2];
      const ux = C.nodes[3 * bb] - C.nodes[3 * a], uy = C.nodes[3 * bb + 1] - C.nodes[3 * a + 1], uz = C.nodes[3 * bb + 2] - C.nodes[3 * a + 2], vx = C.nodes[3 * c] - C.nodes[3 * a], vy = C.nodes[3 * c + 1] - C.nodes[3 * a + 1], vz = C.nodes[3 * c + 2] - C.nodes[3 * a + 2];
      const area = 0.5 * Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx) * 1e-6; // m2
      out += bc.h * area * ((T[a] + T[bb] + T[c]) / 3 - (bc.T_ext - 273.15));
    }
  }
  const summary = [
    { metric: 'max_temp', value: tMax, unit: '°C', where: where(C, at) }, { metric: 'min_temp', value: tMin, unit: '°C' },
    { metric: 'heat_in', value: P, unit: 'W' }, { metric: 'heat_out', value: out, unit: 'W' },
    { metric: 'energy_balance_error', value: Math.abs(out - P), unit: 'W' },
    { metric: 'mesh_elements', value: C.tets, unit: '' }, { metric: 'mesh_nodes', value: C.nodeCount, unit: '' }, { metric: 'mesh_size', value: C.h, unit: 'mm' },
  ];
  return { fields: { temperature: { ncomp: 1, data: T } }, summary, warnings };
}

/** Von Mises (any consistent unit) of a symmetric tensor given as xx yy zz xy yz zx. */
export const vonMises6 = (s) => Math.sqrt(0.5 * ((s[0] - s[1]) ** 2 + (s[1] - s[2]) ** 2 + (s[2] - s[0]) ** 2) + 3 * (s[3] ** 2 + s[4] ** 2 + s[5] ** 2));

/** Compare two solves of the same case at two mesh sizes: relative change of the named metrics and whether the study says "mesh independent". */
export function compareStudy(a, b, names, tol) {
  const rows = [], get = (r, m) => (r.summary.find((s) => s.metric === m) || {}).value;
  for (const m of names) { const x = get(a, m), y = get(b, m); if (Number.isFinite(x) && Number.isFinite(y)) rows.push({ metric: m, coarse: x, fine: y, change: Math.abs(y - x) / Math.max(Math.abs(y), 1e-30) }); }
  return { sizes_mm: [a.h, b.h], metrics: rows, tolerance: tol, converged: rows.length > 0 && rows.every((r) => r.change <= (tol[r.metric] ?? tol.default)) };
}
