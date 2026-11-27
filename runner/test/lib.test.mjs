import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { parseStl, writeStlBinary, meshStats } from '../src/lib/stl.js';
import { writeVtu, parseVtu, VTK } from '../src/lib/vtu.js';
import { readMsh2, orientTets, boundaryFaces, selectFaces, bboxOf, writeElmerMesh, compactMesh, tetVolume } from '../src/lib/tetmesh.mjs';
import { structuralSif, thermalSif, pressureTraction } from '../src/lib/sif.mjs';
import { loadConfig } from '../src/config.mjs';
import { boxMesh, boxStl, tmpDir } from './helpers.mjs';

test('STL: binary round trip, ASCII, welding, volume and area of a closed box', () => {
  const m = boxMesh(2, 3, 4), s = parseStl(writeStlBinary(m.positions, m.indices));
  assert.equal(s.positions.length / 3, 8); assert.equal(s.indices.length / 3, 12);
  const st = meshStats(s.positions, s.indices); assert.ok(Math.abs(st.volume - 24) < 1e-9); assert.ok(Math.abs(st.area - 52) < 1e-9);
  const ascii = 'solid x\n' + [...Array(12).keys()].map((t) => 'facet normal 0 0 0\nouter loop\n' + [0, 1, 2].map((k) => { const i = m.indices[3 * t + k] * 3; return `vertex ${m.positions[i]} ${m.positions[i + 1]} ${m.positions[i + 2]}`; }).join('\n') + '\nendloop\nendfacet').join('\n') + '\nendsolid';
  assert.equal(parseStl(new TextEncoder().encode(ascii)).indices.length, 36);
  assert.throws(() => parseStl(new TextEncoder().encode('hello')), /STL/);
  const inverted = writeStlBinary(m.positions, Uint32Array.from(m.indices, (v, i) => m.indices[i - (i % 3) + ((i % 3) === 1 ? 2 : (i % 3) === 2 ? 1 : 0)]));
  assert.ok(meshStats(...(({ positions, indices }) => [positions, indices])(parseStl(inverted))).volume < 0, 'an inside-out STL has negative volume (the runner refuses it)');
});

test('VTU: ASCII round trip keeps points, cells and fields; inline base64 binary is read; appended data and wrong files are refused', () => {
  const pts = [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1], mesh = { points: pts, cells: { types: Uint8Array.of(VTK.TETRA), connectivity: Int32Array.of(0, 1, 2, 3), offsets: Int32Array.of(4) }, pointData: { t: { ncomp: 1, data: [1, 2, 3, 4.5] }, u: { ncomp: 3, data: pts.map((v) => v * 2) } }, cellData: { body: { ncomp: 1, data: [0] } } };
  const p = parseVtu(writeVtu(mesh));
  assert.deepEqual([...p.points], pts); assert.deepEqual([...p.cells.types], [10]); assert.deepEqual([...p.pointData.t.data], [1, 2, 3, 4.5]); assert.equal(p.pointData.u.ncomp, 3); assert.deepEqual([...p.cellData.body.data], [0]);
  const f64 = (a) => { const b = Buffer.alloc(4 + 8 * a.length); b.writeUInt32LE(8 * a.length, 0); a.forEach((v, i) => b.writeDoubleLE(v, 4 + 8 * i)); return b.toString('base64'); };
  const bin = `<VTKFile type="UnstructuredGrid" header_type="UInt32"><UnstructuredGrid><Piece NumberOfPoints="4" NumberOfCells="1"><Points><DataArray type="Float64" Name="Points" NumberOfComponents="3" format="binary">${f64(pts)}</DataArray></Points><Cells><DataArray type="Int32" Name="connectivity" format="ascii">0 1 2 3</DataArray><DataArray type="Int32" Name="offsets" format="ascii">4</DataArray><DataArray type="UInt8" Name="types" format="ascii">10</DataArray></Cells><PointData><DataArray type="Float64" Name="T" format="binary">${f64([5, 6, 7, 8])}</DataArray></PointData></Piece></UnstructuredGrid></VTKFile>`;
  assert.deepEqual([...parseVtu(bin).pointData.T.data], [5, 6, 7, 8]);
  assert.throws(() => parseVtu(bin.replace('format="binary">', 'format="appended">')), /appended/);
  assert.throws(() => parseVtu('<VTKFile type="PolyData"></VTKFile>'), /UnstructuredGrid/);
  assert.throws(() => parseVtu(writeVtu(mesh).replace('NumberOfPoints="4"', 'NumberOfPoints="5"')), /points/);
});

const MSH = `$MeshFormat\n2.2 0 8\n$EndMeshFormat\n$Nodes\n5\n1 0 0 0\n2 1 0 0\n3 0 1 0\n4 0 0 1\n9 5 5 5\n$EndNodes\n$Elements\n3\n1 15 2 0 1 9\n2 2 2 0 1 1 2 3\n3 4 2 0 1 1 2 4 3\n$EndElements\n`;
test('gmsh MSH 2.2 reader keeps only 4-node tetrahedra, renumbers ids, compacts unused nodes, and orients to positive volume', () => {
  const { nodes, tets } = readMsh2(MSH); assert.equal(nodes.length, 15); assert.deepEqual([...tets], [0, 1, 3, 2]);
  assert.ok(tetVolume(nodes, ...tets) < 0, 'as written the tet is negatively oriented');
  const c = compactMesh(nodes, tets); assert.equal(c.nodes.length, 12, 'the stray node is dropped');
  const { volume, flipped } = orientTets(c.nodes, c.tets); assert.equal(flipped, 1); assert.ok(Math.abs(volume - 1 / 6) < 1e-12);
  assert.throws(() => readMsh2(MSH.replace('2.2 0 8', '4.1 0 8')), /ASCII 2\.x/); assert.throws(() => readMsh2(MSH.replace('3 4 2 0 1 1 2 4 3', '3 2 2 0 1 1 2 3')), /no tetrahedra/);
});

test('boundary faces: a unit tet has 4, normals point outward, areas add up; faces are selected by direction on the bounding box or inside a box', () => {
  const { nodes, tets } = readMsh2(MSH), c = compactMesh(nodes, tets); orientTets(c.nodes, c.tets);
  const bf = boundaryFaces(c.nodes, c.tets); assert.equal(bf.area.length, 4);
  const cen = [0.25, 0.25, 0.25]; for (let i = 0; i < 4; i++) { const out = [0, 1, 2].map((k) => bf.centroid[3 * i + k] - cen[k]); assert.ok(out[0] * bf.normal[3 * i] + out[1] * bf.normal[3 * i + 1] + out[2] * bf.normal[3 * i + 2] > 0, 'outward'); }
  assert.ok(Math.abs(bf.area.reduce((a, b) => a + b, 0) - (0.5 * 3 + Math.sqrt(3) / 2)) < 1e-12);
  const bb = bboxOf(c.nodes, c.tets);
  assert.equal(selectFaces(bf, bb, '-x', null, 1e-6).length, 1); assert.equal(selectFaces(bf, bb, '-z', null, 1e-6).length, 1); assert.equal(selectFaces(bf, bb, '+x', null, 1e-6).length, 0, 'the slanted face is not on the +x plane');
  assert.equal(selectFaces(bf, bb, '-x', [-1, 1, 0, 1, 0, 1], 1e-6).length, 1); assert.equal(selectFaces(bf, bb, '-x', [0.5, 1, 0, 1, 0, 1], 1e-6).length, 0, 'a box that misses the face');
  assert.throws(() => selectFaces(bf, bb, 'up', null, 0), /unknown face/);
});

test('Elmer mesh files: header counts, 1-based ids, metres, boundary elements point at their parent tet', () => {
  const { nodes, tets } = readMsh2(MSH), c = compactMesh(nodes, tets); orientTets(c.nodes, c.tets);
  const bf = boundaryFaces(c.nodes, c.tets), sel = selectFaces(bf, bboxOf(c.nodes, c.tets), '-x', null, 1e-6);
  const dir = path.join(tmpDir(), 'mesh'), n = writeElmerMesh(dir, c.nodes, [{ tets: c.tets, boundary: [{ bc: 7, tri: Int32Array.from(sel.flatMap((i) => [bf.tri[3 * i], bf.tri[3 * i + 1], bf.tri[3 * i + 2]])), parent: Int32Array.from(sel, (i) => bf.parent[i]) }] }]);
  assert.deepEqual(n, { nodes: 4, elements: 1, boundary: 1 });
  assert.equal(fs.readFileSync(path.join(dir, 'mesh.header'), 'utf8'), '4 1 1\n2\n504 1\n303 1\n');
  assert.match(fs.readFileSync(path.join(dir, 'mesh.nodes'), 'utf8'), /^1 -1 0 0 0\n2 -1 0.001 0 0\n/);
  assert.match(fs.readFileSync(path.join(dir, 'mesh.elements'), 'utf8'), /^1 1 504 \d \d \d \d\n$/);
  assert.match(fs.readFileSync(path.join(dir, 'mesh.boundary'), 'utf8'), /^1 7 1 0 303 \d \d \d\n$/);
});

test('sif text: structural and thermal cases contain the solver, material, body, boundary and output sections with SI numbers', () => {
  const s = structuralSif({ bodies: [{ name: 'Bar', E_Pa: 2e11, nu: 0.3, rho: 7800, accel: [0, 0, -9806.65] }, { name: 'Pin', E_Pa: 7e10, nu: 0.33, rho: 2700, accel: null }], bcs: [{ id: 1, name: 'fixed wall', fixed: [true, true, true], traction: null }, { id: 2, name: 'pull', fixed: [false, false, false], traction: [1e7, 0, 0] }, { id: 3, name: 'sym', fixed: [false, true, false], traction: null }] });
  for (const re of [/Procedure = "StressSolve" "StressSolver"/, /Variable = -dofs 3 Displacement/, /Calculate Stresses = Logical True/, /Youngs modulus = 200000000000/, /Poisson ratio = 0\.3/, /Stress Bodyforce 3 = -76491870/, /Body 2\n  Target Bodies\(1\) = 2/, /Boundary Condition 1[\s\S]*Displacement 1 = Real 0\.0\n  Displacement 2 = Real 0\.0\n  Displacement 3 = Real 0\.0/, /Boundary Condition 2[\s\S]*Force 1 = Real 10000000/, /Boundary Condition 3[\s\S]*Displacement 2 = Real 0\.0\nEnd/, /ResultOutputSolver/, /Ascii Output = Logical True/]) assert.match(s, re);
  assert.ok(!/Body Force = 2/.test(s), 'a body without acceleration has no body force');
  const t = thermalSif({ bodies: [{ name: 'Block', k: 200, rho: 2700, cp: 900, source_W_per_kg: 74.07 }], bcs: [{ id: 1, name: 'air', h: 10, T_ext: 293.15 }] });
  for (const re of [/Procedure = "HeatSolve" "HeatSolver"/, /Heat Conductivity = 200/, /Heat Source = 74\.07/, /Heat Transfer Coefficient = 10/, /External Temperature = 293\.15/]) assert.match(t, re);
  assert.throws(() => structuralSif({ bodies: [{ E_Pa: NaN, nu: 0.3, rho: 1 }], bcs: [] }), /finite/);
  assert.deepEqual(pressureTraction(1e6, '+z').map((v) => v + 0), [0, 0, -1e6], 'a positive pressure on +z pushes toward -z (into the face)');
});

test('config: a token is required (or loopback development), short tokens and bad numbers are refused', () => {
  assert.throws(() => loadConfig({}), /ALLOY_RUNNER_TOKEN is not set/);
  assert.throws(() => loadConfig({ ALLOY_RUNNER_TOKEN: 'short' }), /shorter than 16/);
  assert.throws(() => loadConfig({ ALLOY_RUNNER_ALLOW_NO_AUTH: '1', ALLOY_RUNNER_HOST: '0.0.0.0' }), /not set/, 'no-auth is refused off loopback');
  assert.equal(loadConfig({ ALLOY_RUNNER_ALLOW_NO_AUTH: '1' }).token, '');
  assert.throws(() => loadConfig({ ALLOY_RUNNER_TOKEN: 'x'.repeat(20), ALLOY_RUNNER_PORT: 'abc' }), /invalid number/);
  assert.equal(loadConfig({ ALLOY_RUNNER_TOKEN: 'x'.repeat(20) }).host, '127.0.0.1', 'binds to loopback by default');
  assert.equal(loadConfig({ ALLOY_RUNNER_TOKEN: 'x'.repeat(20) }).enableFake, false, 'the simulated solver is off by default');
});
