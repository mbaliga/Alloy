// The Elmer adapter's plumbing, WITHOUT Elmer. Meshing is the real gmsh (skipped when it is not installed); the "ElmerSolver" here is a
// small stub script that reads the mesh files the adapter wrote and writes a VTU in the dialect the adapter expects. This proves the
// case building, the files handed to the solver, the reading and the unit conversion. It proves nothing about Elmer, whose real
// output has never been seen by this code. No reference file may be made from this test.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { buildCase, autoMeshSize, surfaceBodies } from '../src/lib/casebuild.mjs';
import { validateJob } from '../src/protocol.mjs';
import { elmer, readStructural, readThermal, pickField } from '../src/adapters/elmer.mjs';
import { parseVtu, writeVtu, VTK } from '../src/lib/vtu.js';
import { structuralSif } from '../src/lib/sif.mjs';
import { barJob, thermalJob, testConfig, tmpDir, hasGmsh, boxStl, b64 } from './helpers.mjs';

const g = { skip: hasGmsh ? false : 'gmsh is not installed' };
const ctxOf = (cfg, dir) => ({ dir, config: cfg, signal: new AbortController().signal, log: () => {}, stage: () => {}, progress: () => {} });

test('auto mesh size follows the geometry and the tetrahedron budget', () => {
  const b = (x, y, z) => ({ stats: { volume: x * y * z, min: [0, 0, 0], max: [x, y, z] } });
  assert.ok(autoMeshSize([b(40, 10, 10)], 400000) <= 5, 'at most half the thinnest dimension');
  assert.ok(autoMeshSize([b(400, 100, 100)], 4000) > autoMeshSize([b(400, 100, 100)], 4000000), 'a smaller budget gives a larger element');
});

test('buildCase (real gmsh): a bar fixed at -x and pulled at +x gets the right boundary groups, traction and nodes', g, async () => {
  const cfg = testConfig(), job = validateJob(barJob(), cfg), C = await buildCase(job, 4, ctxOf(cfg, tmpDir()));
  assert.equal(C.bodies.length, 1); assert.ok(Math.abs(C.bodies[0].volume - 4000) < 1e-6 * 4000, 'the tet mesh fills the bar'); assert.ok(C.tets > 100);
  assert.equal(C.bcs.length, 2); const fixed = C.bcs.find((b) => b.fixed.every(Boolean)), pull = C.bcs.find((b) => b.traction);
  assert.ok(Math.abs(pull.traction[0] - 1000 / 100e-6) < 1e-3, 'traction = 1000 N over 100 mm2 in Pa'); assert.equal(pull.traction[1], 0);
  assert.ok(Math.abs(C.loads[0].area_mm2 - 100) < 1e-9); assert.ok(C.loads[0].nodes.length >= 4);
  const faces = (id) => C.groups[0].find((x) => x.bc === id).parent.length;
  assert.ok(faces(fixed.id) >= 2 && faces(pull.id) >= 2, 'both faces are made of boundary triangles');
  assert.equal(C.warnings.filter((w) => /no free boundary/.test(w)).length, 0);
});

test('buildCase: a slider holds only the axis of its face; a pressure becomes a traction into the face; acceleration and self weight become body force', g, async () => {
  const cfg = testConfig(), j = barJob(); j.conditions.mounts = [{ id: 'w', kind: 'fixed', body: 'Bar', face: '-x' }, { id: 's', kind: 'slider', body: 'Bar', face: '-y' }];
  j.conditions.loads = [{ id: 'p', kind: 'pressure', body: 'Bar', face: '+z', pressure_Pa: 2e6 }, { id: 'a', kind: 'acceleration', direction: '-z', accel_ms2: 98.0665 }]; j.conditions.self_weight = true; j.conditions.gravity = '-z';
  const C = await buildCase(validateJob(j, cfg), 5, ctxOf(cfg, tmpDir()));
  const s = C.bcs.find((b) => b.name === 'slider s'); assert.deepEqual(s.fixed, [false, true, false]);
  const p = C.bcs.find((b) => b.name === 'pressure p'); assert.deepEqual(p.traction.map((v) => v + 0), [0, 0, -2e6]);
  assert.ok(Math.abs(C.bodies[0].accel[2] + 98.0665 + 9.80665) < 1e-9); assert.equal(C.bodies[0].accel[0], 0);
  assert.ok(C.loads.length === 1 && Math.abs(C.loads[0].vector_N[2] + 2e6 * 400e-6) < 1e-6, 'the pressure resultant on 40 x 10 mm');
});

test('buildCase: two bodies are meshed separately (a warning says so) and each needs its own fixed mount', g, async () => {
  const cfg = testConfig(), j = barJob(); j.bodies.push({ name: 'Pin', format: 'stl', data_b64: b64(boxStl(5, 5, 5)), material: { E_Pa: 7e10, nu: 0.33, rho: 2700 } });
  await assert.rejects(buildCase(validateJob(j, cfg), 5, ctxOf(cfg, tmpDir())), /Body "Pin" has no fixed or slider mount/);
  j.conditions.mounts.push({ id: 'pw', kind: 'fixed', body: 'Pin', face: '-z' }); const C = await buildCase(validateJob(j, cfg), 5, ctxOf(cfg, tmpDir()));
  assert.ok(C.warnings.some((w) => /meshed separately and are not bonded/.test(w))); assert.equal(C.bodies[1].nodeRange[0], C.bodies[0].nodeRange[1], 'node ranges are contiguous');
  assert.ok(C.bodies[1].tets.every((q) => q >= C.bodies[1].nodeRange[0] && q < C.bodies[1].nodeRange[1]), 'the second body only uses its own nodes');
});

test('buildCase: the element budget is enforced', g, async () => {
  const cfg = testConfig({ maxTets: 1000 }); await assert.rejects(buildCase(validateJob(barJob(), cfg), 0.8, ctxOf(cfg, tmpDir())), /more than 1,000 tetrahedra/);
});

test('buildCase (thermal): contact mounts and the free surface become Robin conditions; heat becomes W/kg', g, async () => {
  const cfg = testConfig(), j = thermalJob(); j.conditions.mounts = [{ id: 'pad', kind: 'contact', body: 'Block', face: '-z', conductance_W_m2K: 500 }];
  const C = await buildCase(validateJob(j, cfg), 5, ctxOf(cfg, tmpDir()));
  assert.equal(C.bcs.length, 2); const pad = C.bcs.find((b) => b.h === 500), air = C.bcs.find((b) => b.h === 10); assert.ok(pad && air);
  assert.ok(Math.abs(pad.area_mm2 - 400) < 1e-6); assert.ok(Math.abs(air.area_mm2 - (2 * 400 + 4 * 200 - 400)) < 1e-6, 'everything else is exposed to the air');
  assert.ok(Math.abs(C.bodies[0].source_W_per_kg - 2 / (2700 * 4000e-9)) < 1e-6);
});

// A stub ElmerSolver: reads mesh.nodes (metres) and the case's body count, writes Elmer-style SI fields: displacement = 1e-6 * x (m),
// von Mises 5e6 Pa, temperature 300 K + 10 K. NOT a solver.
const STUB = `#!/usr/bin/env node
const fs = require('fs');
const sif = fs.readFileSync('case.sif', 'utf8'), structural = /StressSolve/.test(sif);
const nodes = fs.readFileSync('mesh/mesh.nodes', 'utf8').trim().split('\\n').map((l) => l.trim().split(/\\s+/).map(Number));
const els = fs.readFileSync('mesh/mesh.elements', 'utf8').trim().split('\\n').map((l) => l.trim().split(/\\s+/).map(Number));
if (process.env.STUB_FAIL) { console.log('ERROR:: StressSolve: singular matrix'); process.exit(1); }
const f = (a) => a.join(' ');
const pts = nodes.flatMap((n) => [n[2], n[3], n[4]]);
const pd = structural
  ? '<DataArray type="Float64" Name="displacement" NumberOfComponents="3" format="ascii">' + f(nodes.flatMap((n) => [1e-6 * n[2] * 1e3, 0, 0])) + '</DataArray><DataArray type="Float64" Name="vonmises" format="ascii">' + f(nodes.map(() => 5e6)) + '</DataArray>'
  : '<DataArray type="Float64" Name="temperature" format="ascii">' + f(nodes.map(() => 310)) + '</DataArray>';
fs.writeFileSync('result_t0001.vtu', '<?xml version="1.0"?><VTKFile type="UnstructuredGrid"><UnstructuredGrid><Piece NumberOfPoints="' + nodes.length + '" NumberOfCells="' + els.length + '"><Points><DataArray type="Float64" Name="Points" NumberOfComponents="3" format="ascii">' + f(pts) + '</DataArray></Points><Cells><DataArray type="Int32" Name="connectivity" format="ascii">' + f(els.flatMap((e) => e.slice(3).map((v) => v - 1))) + '</DataArray><DataArray type="Int32" Name="offsets" format="ascii">' + f(els.map((e, i) => 4 * (i + 1))) + '</DataArray><DataArray type="UInt8" Name="types" format="ascii">' + f(els.map(() => 10)) + '</DataArray></Cells><PointData>' + pd + '</PointData></Piece></UnstructuredGrid></VTKFile>');
console.log('stub solver done');
`;
const stubPath = () => { const d = tmpDir(), p = path.join(d, 'ElmerSolver'); fs.writeFileSync(p, STUB, { mode: 0o755 }); return p; };

test('elmer adapter plumbing with a stub ElmerSolver: files handed to the solver, fields read back and converted to mm / MPa, the stub is what produced the numbers', g, async () => {
  const cfg = testConfig({ elmerBin: stubPath() }), job = validateJob(barJob({ adapter: 'elmer' }), cfg), dir = tmpDir();
  const st = await elmer.available(cfg); assert.equal(st.ok, true);
  const out = await elmer.run(job, ctxOf(cfg, dir));
  const sif = fs.readFileSync(path.join(dir, 'solve-a', 'case.sif'), 'utf8'); assert.match(sif, /Youngs modulus = 200000000000/); assert.match(sif, /Force 1 = Real 10000000/);
  for (const f of ['mesh.header', 'mesh.nodes', 'mesh.elements', 'mesh.boundary']) assert.ok(fs.existsSync(path.join(dir, 'solve-a', 'mesh', f)), f);
  const by = Object.fromEntries(out.summary.map((s) => [s.metric, s]));
  assert.ok(Math.abs(by.max_von_mises.value - 5) < 1e-9, 'Pa -> MPa'); assert.equal(by.max_von_mises.unit, 'MPa');
  assert.ok(Math.abs(by.max_displacement.value - 1e-6 * 40 * 1e3) < 1e-6 * 1e3, 'metres -> mm (the stub makes 1e-6 m per mm of x, 40 mm long)');
  assert.ok(Math.abs(by.min_safety_factor.value - 250 / 5) < 1e-9, 'yield 250 MPa over 5 MPa (not capped in the summary)');
  const v = parseVtu(out.vtu); assert.ok(v.points.every((x, i) => i % 3 !== 0 || x <= 40 + 1e-9), 'points are in mm'); assert.deepEqual(Object.keys(v.pointData).sort(), ['body', 'displacement', 'displacement_magnitude', 'safety_factor', 'von_mises']);
  assert.equal(out.study, null);
});

test('elmer adapter: a failing ElmerSolver becomes a job failure that carries its message; thermal Kelvin becomes degrees C; a mesh study runs two solves', g, async () => {
  const bin = stubPath(), cfg = testConfig({ elmerBin: bin });
  process.env.STUB_FAIL = '1'; try { await assert.rejects(elmer.run(validateJob(barJob({ adapter: 'elmer' }), cfg), ctxOf(cfg, tmpDir())), /ElmerSolver failed.*singular matrix/); } finally { delete process.env.STUB_FAIL; }
  const t = await elmer.run(validateJob(thermalJob({ adapter: 'elmer' }), cfg), ctxOf(cfg, tmpDir())); assert.ok(Math.abs(t.summary.find((s) => s.metric === 'max_temp').value - (310 - 273.15)) < 1e-9);
  const j = barJob({ adapter: 'elmer' }); j.options.mesh_study = true; const s = await elmer.run(validateJob(j, cfg), ctxOf(cfg, tmpDir()));
  assert.equal(s.study.sizes_mm.length, 2); assert.ok(s.study.sizes_mm[1] < s.study.sizes_mm[0]); assert.equal(s.study.converged, true, 'the stub gives the same numbers on both meshes');
});

test('reading Elmer output: field name variants, separate components, a stress tensor instead of von Mises, a node-count mismatch and a missing field all give clear messages', () => {
  const mk = (pointData, n = 2) => parseVtu(writeVtu({ points: new Float64Array(3 * n), cells: { types: Uint8Array.of(VTK.VERTEX), connectivity: Int32Array.of(0), offsets: Int32Array.of(1) }, pointData }));
  const r = readStructural(mk({ 'displacement 1': { ncomp: 1, data: [0.001, 0] }, 'displacement 2': { ncomp: 1, data: [0, 0] }, 'displacement 3': { ncomp: 1, data: [0, 0] }, stress: { ncomp: 6, data: [1e6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0] } }), 2);
  assert.equal(r.u[0], 1); assert.ok(Math.abs(r.vm[0] - 1) < 1e-12, 'uniaxial 1 MPa has von Mises 1 MPa');
  assert.throws(() => readStructural(mk({}), 2), /no displacement field \(fields: none\)/);
  assert.throws(() => readStructural(mk({ displacement: { ncomp: 3, data: [0, 0, 0, 0, 0, 0] } }), 2), /no von Mises or stress field/);
  assert.throws(() => readStructural(mk({}), 3), /has 2 nodes; the mesh has 3/);
  assert.throws(() => readThermal(mk({}), 2), /no temperature field/);
  assert.equal(pickField(mk({ Temperature: { ncomp: 1, data: [1, 2] } }), ['temperature'], 1)[1], 2, 'names are matched without regard to case');
});

test('elmer availability explains what is missing', g, async () => {
  assert.match((await elmer.available(testConfig({ elmerBin: '/nonexistent/ElmerSolver' }))).reason, /ElmerSolver not found/);
  assert.match((await elmer.available(testConfig({ gmshBin: '/nonexistent/gmsh' }))).reason, /gmsh not found/);
});
