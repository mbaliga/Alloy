// The OpenFOAM adapter's plumbing, WITHOUT OpenFOAM: the flow job schema, the case files as text, and the adapter against stub programs
// that stand in for blockMesh, snappyHexMesh and simpleFoam and write the file OpenFOAM would. This proves the case building, the order
// the programs run in, progress, failure handling and the reading of the plane flux. It proves nothing about OpenFOAM, whose real output
// has never been seen by this code. No reference file may be made from this test.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { validateJob } from '../src/protocol.mjs';
import { openfoam, readPlaneFlux } from '../src/adapters/openfoam.mjs';
import { foamFiles, flowBox } from '../src/lib/foamcase.mjs';
import { describeAdapters, pickAdapter } from '../src/adapters/index.mjs';
import { boxStl, b64, testConfig, tmpDir } from './helpers.mjs';

const flowJob = (over = {}) => ({
  protocol: 1, kind: 'flow', adapter: 'auto', label: 'duct',
  bodies: [{ name: 'Case', format: 'stl', data_b64: b64(boxStl(60, 40, 30)) }],
  flow: { speed_ms: 2, dir: '+x', planes: [{ id: 'p1', point_mm: [30, 20, 15], normal: [1, 0, 0], bounds_mm: [30, 30, 0, 40, 0, 30] }] },
  options: { mesh_size_mm: 3 }, ...over,
});
const balanced = (t) => (t.match(/\{/g) || []).length === (t.match(/\}/g) || []).length && (t.match(/\(/g) || []).length === (t.match(/\)/g) || []).length;

test('flow jobs: the schema accepts a valid job and names the wrong field; materials are not needed; the structural kinds are unchanged', () => {
  const cfg = testConfig(), job = validateJob(flowJob(), cfg); assert.equal(job.kind, 'flow'); assert.equal(job.flow.nu_m2s, 1.55e-5); assert.equal(job.flow.planes.length, 1); assert.match(job.warnings.join(' '), /closed walls in a box of air/);
  const bad = (mut, re) => { const j = flowJob(); mut(j); assert.throws(() => validateJob(j, cfg), re); };
  bad((j) => { j.flow = undefined; }, /needs a flow object/); bad((j) => { j.flow.speed_ms = 0; }, /speed_ms/); bad((j) => { j.flow.dir = 'up'; }, /flow\.dir/); bad((j) => { j.flow.planes[0].normal = [0, 0, 0]; }, /non-zero normal/);
  bad((j) => { j.flow.planes[0].bounds_mm = [1, 2]; }, /bounds_mm/); bad((j) => { j.flow.planes.push({ ...j.flow.planes[0] }); }, /unique/); bad((j) => { j.adapter = 'elmer'; }, /needs adapter auto or openfoam/);
  assert.throws(() => validateJob({ ...flowJob(), kind: 'structural' }, cfg), /material needs E_Pa/);
});

test('case files: every dictionary is well formed, names the entries the solvers read, puts the inlet on the upstream face and the planes in the function objects', () => {
  const cfg = testConfig(), job = validateJob(flowJob(), cfg), { files, info } = foamFiles(job, job.bodies);
  for (const f of ['system/blockMeshDict', 'system/snappyHexMeshDict', 'system/controlDict', 'system/fvSchemes', 'system/fvSolution', 'constant/transportProperties', 'constant/momentumTransport', '0/U', '0/p', '0/k', '0/omega', '0/nut']) { assert.ok(files[f], f); assert.ok(balanced(files[f]), `${f}: braces and parentheses balance`); assert.match(files[f], /FoamFile/); }
  assert.match(files['system/controlDict'], /application simpleFoam;/); assert.match(files['system/controlDict'], /surfaceFieldValue/); assert.match(files['system/controlDict'], /name plane_p1;/); assert.match(files['system/controlDict'], /sum;/);
  assert.match(files['0/U'], /inlet \{ type fixedValue; value uniform \(2 0 0\);/); assert.match(files['0/U'], /\.\*body_\.\*/); assert.match(files['constant/transportProperties'], /nu 0\.0000155;/); assert.match(files['constant/momentumTransport'], /kOmegaSST/);
  assert.match(files['system/snappyHexMeshDict'], /file "body_0\.stl"/); assert.match(files['system/snappyHexMeshDict'], /locationInMesh \(/);
  const bm = files['system/blockMeshDict']; assert.match(bm, /convertToMeters 0\.001;/); assert.match(bm, /inlet\s*\{\s*type patch;\s*faces\s*\(\s*\(0 4 7 3\)/); assert.match(bm, /outlet\s*\{\s*type patch;\s*faces\s*\(\s*\(1 2 6 5\)/);
  const down = validateJob(flowJob({ flow: { speed_ms: 1, dir: '-y', planes: [] } }), cfg), f2 = foamFiles(down, down.bodies).files; assert.match(f2['system/blockMeshDict'], /inlet\s*\{\s*type patch;\s*faces\s*\(\s*\(3 7 6 2\)/, 'flow along -y enters on the +y face'); assert.match(f2['0/U'], /uniform \(0 -1 0\)/);
  const b = flowBox(job.bodies, job.flow); assert.ok(b.lo[0] < 0 && b.hi[0] > 60 + b.margin * 0.99, 'downstream margin is twice the side margin'); assert.ok(info.cells_background.every((n) => n >= 4));
});

/** Stub programs that behave like the three OpenFOAM programs far enough for the adapter: they check what the adapter wrote and leave what OpenFOAM would. */
function stubs({ fail = null, flux = -0.0021 } = {}) {
  const dir = tmpDir(), w = (name, body) => { fs.writeFileSync(path.join(dir, name), `#!/bin/sh\n${body}\n`); fs.chmodSync(path.join(dir, name), 0o755); };
  w('blockMesh', `test -f system/blockMeshDict || { echo "FOAM FATAL IO ERROR: no blockMeshDict"; exit 1; }\n${fail === 'blockMesh' ? 'echo "--> FOAM FATAL ERROR: bad block"; exit 1' : 'echo blockMesh ok'}`);
  w('snappyHexMesh', `test "$1" = "-overwrite" && test -f constant/triSurface/body_0.stl || exit 2\n${fail === 'snappy' ? 'echo "--> FOAM FATAL ERROR: cannot find a location"; exit 1' : 'echo snappy ok'}`);
  w('simpleFoam', `test -f 0/U && test -f system/fvSolution || exit 3\nfor i in 1 2 3 4; do echo "Time = $i"; done\nmkdir -p postProcessing/plane_p1/0\nprintf '# Time sum(phi)\\n0\\t0\\n3\\t${flux}\\n' > postProcessing/plane_p1/0/surfaceFieldValue.dat\necho "End"`);
  return dir;
}
const ctxOf = (cfg, dir) => { const seen = { stages: [], progress: [] }; return { seen, dir, config: cfg, signal: new AbortController().signal, log: () => {}, stage: (s) => seen.stages.push(s), progress: (f) => seen.progress.push(f) }; };

test('adapter against stub programs: runs blockMesh, snappyHexMesh and simpleFoam in order, reports progress, and reads the plane flux in litres per second', async () => {
  const cfg = testConfig({ openfoamBin: stubs() }), job = validateJob(flowJob(), cfg), ctx = ctxOf(cfg, tmpDir());
  assert.equal((await openfoam.available(cfg)).ok, true);
  const out = await openfoam.run(job, ctx);
  assert.deepEqual(ctx.seen.stages.filter((s) => /Mesh|simpleFoam|writing|post/.test(s)), ['writing the OpenFOAM case', 'blockMesh', 'snappyHexMesh', 'simpleFoam', 'post-processing']);
  assert.ok(ctx.seen.progress.every((v, i) => i === 0 || v >= ctx.seen.progress[i - 1]) && ctx.seen.progress.at(-1) >= 0.9, `progress ${ctx.seen.progress}`);
  assert.equal(out.vtu, null); const q = out.summary.find((s) => s.metric === 'plane_p1_flow'); assert.ok(Math.abs(q.value - (-2.1)) < 1e-9 && q.unit === 'L/s'); assert.equal(out.summary.find((s) => s.metric === 'inlet_speed').value, 2);
  assert.match(out.warnings.join(' '), /without ever running OpenFOAM/);
});

test('adapter: a failing program says which and why; a missing program makes the adapter unavailable with the names; auto picks it for flow only; a plane with no output is an error', async () => {
  for (const fail of ['blockMesh', 'snappy']) { const cfg = testConfig({ openfoamBin: stubs({ fail }) }); await assert.rejects(openfoam.run(validateJob(flowJob(), cfg), ctxOf(cfg, tmpDir())), new RegExp(`${fail === 'snappy' ? 'snappyHexMesh' : 'blockMesh'} failed.*FOAM FATAL ERROR`)); }
  const none = testConfig({ openfoamBin: tmpDir() }), st = await openfoam.available(none); assert.equal(st.ok, false); assert.match(st.reason, /not found: blockMesh, snappyHexMesh, simpleFoam/);
  assert.equal((await describeAdapters(none)).openfoam.ok, false);
  const ok = testConfig({ openfoamBin: stubs() }); assert.equal((await pickAdapter(validateJob(flowJob(), ok), ok)).id, 'openfoam');
  await assert.rejects(pickAdapter(validateJob(flowJob(), none), none), /no solver is available.*openfoam: not found/);
  assert.throws(() => readPlaneFlux(tmpDir(), 'plane_x'), /wrote no postProcessing/);
  const cfg = testConfig({ openfoamBin: stubs() }), ctx = ctxOf(cfg, tmpDir()), job = validateJob(flowJob(), cfg); job.flow.planes.push({ id: 'p2', point_mm: [1, 1, 1], normal: [1, 0, 0], bounds_mm: [0, 1, 0, 1, 0, 1] });
  await assert.rejects(openfoam.run(job, ctx), /wrote no postProcessing\/plane_p2/);
});

test('cancel: aborting stops the running program and the adapter reports it as cancelled', async () => {
  const dir = stubs(); fs.writeFileSync(path.join(dir, 'simpleFoam'), '#!/bin/sh\nexec sleep 30\n'); fs.chmodSync(path.join(dir, 'simpleFoam'), 0o755);
  const cfg = testConfig({ openfoamBin: dir }), ac = new AbortController(), ctx = { ...ctxOf(cfg, tmpDir()), signal: ac.signal }, t0 = Date.now();
  setTimeout(() => ac.abort(), 600); await assert.rejects(openfoam.run(validateJob(flowJob(), cfg), ctx), /cancel/i); assert.ok(Date.now() - t0 < 10000);
});
