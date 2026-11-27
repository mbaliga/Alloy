// Regenerates analysis-validation/cases/structural.json (and slow/structural-fine.json). The analytic reference numbers (beam deflection,
// axial bar, body-force beam, section equilibrium, FDM knock-down, stress concentration) are computed here from their formulas, so the
// case files carry the numbers and their derivation, and every tolerance is written down next to them BEFORE the run and is never
// changed to make a case pass. Run from forge/:  node analysis-validation/gen-structural-cases.mjs
import fs from 'node:fs';
const ID = 'xyz.mdhv.structural';
const G = 9.80665;
const matdef = (id, name, { E, nu, yield: ys, tensile, rho = 7800, f = 1 }) => ({ id, name, class: 'custom', notes: 'Validation test material.', properties: {
  k: { value: 1, quality: 'measured', sources: ['user'] }, rho: { value: rho, quality: 'measured', sources: ['user'] }, cp: { value: 500, quality: 'measured', sources: ['user'] }, emissivity: { value: 0.9, quality: 'measured', sources: ['user'] },
  E: { value: E, quality: 'measured', sources: ['user'] }, nu: { value: nu, quality: 'measured', sources: ['user'] }, yield_strength: { value: ys, quality: 'measured', sources: ['user'] }, tensile_strength: { value: tensile, quality: 'measured', sources: ['user'] },
  interlayer_factor: { value: f, quality: 'measured', sources: ['user'] } } });
const STEEL = { E: 2e11, nu: 0.3, yield: 2.5e8, tensile: 4e8 };
const S = (settings) => ({ [ID]: { settings: { backend: 'cpu', ...settings } } });
const box = (body, size, pos = [0, 0, 0]) => ({ type: 'box', body, size, anchor: 'min', pos });
const fixed = (id, body, face) => ({ id, kind: 'fixed', body, face });
const slider = (id, body, face) => ({ id, kind: 'slider', body, face });
const probe = (metric, fn, args, value, tol, unit, mul) => ({ fn, metric, args, value, unit, tol, ...(mul ? { mul } : {}) });
const cases = [];

// ── 1. cantilever tip deflection (spec: Euler-Bernoulli, within 3 % at fine)
const L = 80, w = 10, t = 10, F = 10, E = STEEL.E / 1e6, I = w * t ** 3 / 12, EB = F * L ** 3 / (3 * E * I), kTip = 3 * E * I / L ** 3;
{ const base = { features: [box('Beam', [L, w, t])], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Beam: 't-steel' },
    conditions: { mounts: [fixed('wall', 'Beam', '-x')], loads: [{ id: 'tip', kind: 'force', body: 'Beam', face: '+x', vector_N: [0, 0, -F] }] } };
  cases.push({ id: 'cantilever-tip-deflection', title: 'Cantilever, 80 x 10 x 10 mm steel, 10 N at the free end, voxel size 1 mm (10 voxels through the depth)',
    reference: `Euler-Bernoulli (Gere and Goodno, Mechanics of Materials, table of beam deflections): delta = F L^3 / (3 E I) with F ${F} N, L ${L} mm, E ${E} MPa, I = w t^3 / 12 = ${I.toFixed(3)} mm^4 gives ${EB.toFixed(6)} mm; stiffness 3 E I / L^3 = ${kTip.toFixed(3)} N/mm. The spec asks for within 3 % at fine; the run at the quality fine itself is in slow/structural-fine.json (npm run test:analysis:slow) because it needs about 250 000 voxels on the CPU. Timoshenko shear adds about ${(100 * 0.3 * 2.6 * (t / L) ** 2).toFixed(1)} % to the true deflection of a beam this stocky, inside the tolerance.`,
    setup: { ...base, ext: S({ h_mm: 1 }) },
    expect: [{ metric: 'max_displacement', unit: 'mm', value: EB, tol: { rel: 0.03 } }, { metric: 'stiffness', unit: 'N/mm', value: kTip, tol: { rel: 0.03 } },
      { metric: 'reaction_force', unit: 'N', value: F, tol: { rel: 1e-6 } }, { metric: 'equilibrium_error', unit: 'N', value: 0, tol: { abs: 1e-4 } }] });
  cases.push({ id: 'cantilever-coarse-h2', kind: 'core', title: 'Same cantilever at a coarse voxel size of 2.5 mm (4 voxels through the depth)',
    reference: `Euler-Bernoulli as above, ${EB.toFixed(6)} mm. The incompatible-mode hexahedron is nearly exact in bending, so even 4 elements through the depth stay within the 3 % of the spec (a plain trilinear hexahedron locks and would miss by tens of percent here).`,
    setup: { ...base, ext: S({ h_mm: 2.5 }) },
    calls: [probe('tip deflection at the free end', 'probeU', ['$input', {}, L - 0.01, w / 2, t / 2, 2], -EB, { rel: 0.03 }, 'mm')] });
  // the same at the quality fine, on demand (slow)
  fs.mkdirSync('analysis-validation/slow', { recursive: true });
  fs.writeFileSync('analysis-validation/slow/structural-fine.json', JSON.stringify({ analysis: `${ID}/structural`, package: 'examples/ext/structural', cases: [{ id: 'cantilever-fine', kind: 'core', title: 'Cantilever at the quality fine (about 250 000 voxels on the CPU), run on demand',
    reference: `Euler-Bernoulli as in cantilever-tip-deflection: ${EB.toFixed(6)} mm, spec tolerance 3 % at fine. Slow (minutes on the CPU: about 1 000 conjugate-gradient iterations of 0.25 s), so it is not in the default run; npm run test:analysis:slow.`,
    setup: { ...base, ext: S({ quality: 'fine' }) },
    calls: [probe('tip deflection at the free end', 'probeU', ['$input', {}, L - 0.01, w / 2, t / 2, 2], -EB, { rel: 0.03 }, 'mm'), probe('largest displacement', 'solveSync.maxU', ['$input', {}], EB, { rel: 0.03 }, 'mm')] }] }, null, 1) + '\n');
  // half model with a slider on the mid plane
  cases.push({ id: 'cantilever-half-model-slider', kind: 'core', title: 'Half of the cantilever (y from 0 to 5 mm) with a slider on the mid plane y = 5 mm and half the load',
    reference: `Symmetry: the beam loaded in z is symmetric about y = w/2, so the half model with a slider (only u_y held) on the plane y = 5 mm and F/2 gives the same deflection, Euler-Bernoulli ${EB.toFixed(6)} mm (within 3 %).`,
    setup: { features: [box('Half', [L, w / 2, t])], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Half: 't-steel' },
      conditions: { mounts: [fixed('wall', 'Half', '-x'), slider('mid', 'Half', '+y')], loads: [{ id: 'tip', kind: 'force', body: 'Half', face: '+x', vector_N: [0, 0, -F / 2] }] }, ext: S({ h_mm: 1.25 }) },
    calls: [probe('tip deflection at the free end', 'probeU', ['$input', {}, L - 0.01, w / 4, t / 2, 2], -EB, { rel: 0.03 }, 'mm')] });
}

// ── 2. axial bar, one octant with sliders: exact uniform stress
{ const sig = 100, Lb = 80, d = 10, dl = sig * Lb / E, bar = (size, face, vec) => ({ features: [box('Bar', size)], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Bar: 't-steel' },
    conditions: { mounts: [slider('sx', 'Bar', '-x'), slider('sy', 'Bar', '-y'), slider('sz', 'Bar', '-z')], loads: [{ id: 'p', kind: 'pressure', body: 'Bar', face, pressure_Pa: -sig * 1e6 }] } });
  cases.push({ id: 'axial-bar-sliders', kind: 'core', title: 'Bar in uniform tension: one octant with sliders on its three symmetry planes and a tensile pressure of 100 MPa on the far end',
    reference: `Uniaxial tension (Gere and Goodno, axial loading): sigma = P/A = ${sig} MPa everywhere, strain sigma/E = ${(sig / E).toExponential(3)}, elongation delta = sigma L / E = ${dl.toFixed(6)} mm over L ${Lb} mm (E ${E} MPa); the lateral contraction is free (nu 0.3), so the state is exactly uniaxial and the element must reproduce it to solver precision. Tests the pressure load, the slider supports, the stress recovery and the reactions.`,
    setup: { ...bar([Lb, d, d], '+x'), ext: S({ h_mm: 2 }) },
    calls: [probe('elongation of the bar', 'probeU', ['$input', {}, Lb - 0.001, d / 2, d / 2, 0], dl, { rel: 1e-3 }, 'mm'), probe('stress sigma_xx (recovered) mid-length', 'probeSigma', ['$input', {}, 40, 5, 5, 0], sig, { rel: 1e-3 }, 'MPa'),
      probe('von Mises stress, largest', 'solveSync.vmMax', ['$input', {}], sig, { rel: 2e-3 }, 'MPa'), probe('equilibrium error', 'solveSync.reac.balance', ['$input', {}], 0, { abs: 1e-4 }, 'N'),
      probe('reaction on the sliders against the applied force', 'solveSync.reac.reaction', ['$input', {}], sig * d * d, { rel: 1e-6 }, 'N')] });
  // FDM knock-down: the same bar pulled along z, layers along z (weak) or along x (strong)
  const f = 0.6, bz = { ...bar([d, d, Lb], '+z'), customMaterials: [matdef('t-pla', 'Test layered', { ...STEEL, f })], customAssign: { Bar: 't-pla' } };
  cases.push({ id: 'fdm-knockdown', kind: 'core', title: 'FDM inter-layer knock-down: tension along z of a bar with inter-layer factor 0.6 and yield 250 MPa',
    reference: `Definition used by the package (docs/EXTENSIONS_SPEC.md section 4, A3): tension across the printed layers is divided by the inter-layer factor before it is compared with the yield strength. The bar carries ${sig} MPa along z, so the safety factor is yield / sigma = 2.5 when the layers lie along x or y (the load is in the layers), and f yield / sigma = ${f * 2.5} when the layers stack along z (print axis z, the load pulls the layers apart); with the knock-down switched off it is 2.5 in both. An exact consequence of the definition, not a measurement of any material: it checks the code, not the factor.`,
    setup: { ...bz, ext: S({ h_mm: 2.5 }) },
    calls: [probe('safety factor, print axis z', 'solveSync.sfMin', ['$input', { print_axis: 'z' }], f * 2.5, { rel: 1e-3 }, ''), probe('safety factor, print axis x', 'solveSync.sfMin', ['$input', { print_axis: 'x' }], 2.5, { rel: 1e-3 }, ''),
      probe('safety factor, knock-down off', 'solveSync.sfMin', ['$input', { print_axis: 'z', knockdown: 'off' }], 2.5, { rel: 1e-3 }, '')] });
}

// ── 3. body force: a cantilever under its own weight scaled by an acceleration
{ const rho = 7800, A = w * t * 1e-6, ag = 100, wl = rho * A * ag * G * 1e-3, dUDL = wl * L ** 4 / (8 * E * I), a = G * 1 / 0.002, dDrop = rho * A * a * 1e-3 * L ** 4 / (8 * E * I), dSelf = rho * A * G * 1e-3 * L ** 4 / (8 * E * I);
  const base = { features: [box('Beam', [L, w, t])], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Beam: 't-steel' } };
  cases.push({ id: 'cantilever-acceleration-load', title: 'Cantilever under an acceleration load of 100 g toward -z (body force rho a)',
    reference: `Uniformly loaded cantilever (Gere and Goodno): delta = w L^4 / (8 E I) with the load per length w = rho A a = ${rho} kg/m3 x ${(A * 1e6).toFixed(0)} mm2 x ${ag} g = ${wl.toFixed(5)} N/mm gives ${dUDL.toFixed(6)} mm at the tip (Euler-Bernoulli, within 3 %). Tests the acceleration load and its direction (a held part loaded as if accelerating toward -z deflects toward -z).`,
    setup: { ...base, conditions: { mounts: [fixed('wall', 'Beam', '-x')], loads: [{ id: 'a', kind: 'acceleration', accel_ms2: ag * G, direction: '-z' }] }, ext: S({ h_mm: 1.25 }) },
    expect: [{ metric: 'max_displacement', unit: 'mm', value: dUDL, tol: { rel: 0.03 } }, { metric: 'applied_force', unit: 'N', value: rho * A * L * 1e-3 * ag * G, tol: { rel: 1e-9 } }] });
  cases.push({ id: 'drop-preset-and-self-weight', kind: 'core', title: 'Drop preset (1 m stopped over 2 mm) and self weight on the same cantilever',
    reference: `The preset is the equivalent acceleration a = g H / s = ${(a / G).toFixed(1)} g (energy: v^2 = 2 g H, constant deceleration over s), giving the uniform-load tip deflection ${dDrop.toFixed(6)} mm (Gere and Goodno, within 3 %); self weight toward the ambient gravity -z gives ${dSelf.toExponential(4)} mm. The equivalence is an assumption stated in the result.`,
    setup: { ...base, conditions: { mounts: [fixed('wall', 'Beam', '-x')] }, ext: S({ h_mm: 1.25 }) },
    calls: [probe('tip deflection, drop preset', 'probeU', ['$input', { preset: 'drop', drop_m: 1, drop_stop_mm: 2, preset_dir: '-z' }, L - 0.01, w / 2, t / 2, 2], -dDrop, { rel: 0.03 }, 'mm'),
      probe('tip deflection, self weight', 'probeU', ['$input', { self_weight: 'on' }, L - 0.01, w / 2, t / 2, 2], -dSelf, { rel: 0.03 }, 'mm')] });
}

// ── 4. bolt preload: a self-equilibrated clamp load
{ const Fp = 1000, blk = 20, supports = [slider('sx', 'Block', '-x'), slider('sy', 'Block', '-y'), slider('sz', 'Block', '-z')];
  const bl = (preloads) => ({ features: [box('Block', [blk, blk, blk])], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Block: 't-steel' }, conditions: { mounts: supports }, ext: S({ h_mm: 1, preloads }) });
  cases.push({ id: 'bolt-preload-uniform', kind: 'core', title: 'Bolt preload of 1 000 N spread over the whole 20 x 20 mm faces of a block (disc radius larger than the block)',
    reference: `Uniform compression (exact): ${Fp} N over 20 x 20 mm = ${Fp / (blk * blk)} MPa, so sigma_zz = -${Fp / (blk * blk)} MPa everywhere, sigma_xx = sigma_yy = 0 (the block is free to expand sideways: sliders on the three symmetry planes only hold the normal displacement), and the net applied load is zero. Tests the sign and the size of the preload, the choice of the outermost faces on both sides, and that a preload is self-equilibrated.`,
    setup: bl([{ axis: 'z', center: [10, 10, 10], radius_mm: 15, force_N: Fp }]),
    calls: [probe('sigma_zz mid-block', 'probeSigma', ['$input', {}, 9.5, 9.5, 9.5, 2], -Fp / (blk * blk), { rel: 1e-3 }, 'MPa'), probe('sigma_xx mid-block', 'probeSigma', ['$input', {}, 9.5, 9.5, 9.5, 0], 0, { abs: 1e-3 }, 'MPa'),
      probe('net applied force', 'solveSync.reac.applied', ['$input', {}], 0, { abs: 1e-9 }, 'N')] });
  cases.push({ id: 'bolt-preload-disc-equilibrium', kind: 'core', title: 'Bolt preload of 1 000 N through discs of radius 5 mm on the two faces of the block',
    reference: `Equilibrium (exact): the two discs push toward each other with ${Fp} N, so every cross-section between them carries a compressive force of ${Fp} N. The sliders only hold normal displacements, so they have no z reaction in the upper half and the part above the mid plane is held in equilibrium by ${Fp} N from the part below: the element nodal forces on the plane nodes sum to ${Fp} N (weak-form equilibrium, exact up to the solver tolerance), and the sum of the element-centre sigma_zz h^2 over the slab above the plane agrees with -${Fp} N within 3 %. A first version of this case used a fixed wall instead of sliders and read 7.9 % low: the wall carried local reactions of both signs (zero net), so the section force through the upper half was not the preload; the case was redesigned, not its tolerance.`,
    setup: bl([{ axis: 'z', center: [10, 10, 10], radius_mm: 5, force_N: Fp }]),
    calls: [probe('force through the mid plane (nodal forces of the part above)', 'sectionForceNodal', ['$input', {}, 'z', 10, 2], Fp, { rel: 1e-3 }, 'N'), probe('force through the mid slab (sum of element-centre sigma_zz h^2)', 'sectionForce', ['$input', {}, 'z', 10.4, 2], -Fp, { rel: 0.03 }, 'N')] });
}

// ── 5. plate with a hole: stress concentration (Kirsch), quarter model with symmetry sliders
{ const a = 3, Ly = 12, Lx = 30, th = 1, h = 0.25, sig = 100, W = 2 * Ly, r = 2 * a / W, KtNet = 3 - 3.13 * r + 3.66 * r * r - 1.53 * r ** 3, Kt = KtNet / (1 - r), peak = Kt * sig;
  const quarter = { features: [box('Plate', [Lx, Ly, th]), { type: 'cylinder', body: 'Plate', op: 'cut', d: 2 * a, h: th + 2, anchor: 'center', pos: [0, 0, th / 2] }], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Plate: 't-steel' },
    conditions: { mounts: [slider('sx', 'Plate', '-x'), slider('sy', 'Plate', '-y'), slider('sz', 'Plate', '-z')], loads: [{ id: 'p', kind: 'pressure', body: 'Plate', face: '+x', pressure_Pa: -sig * 1e6 }] } };
  const ref = `Kirsch (1898) for an infinite plate: the hoop stress at the edge of a circular hole in uniaxial tension is 3 sigma (Timoshenko and Goodier, Theory of Elasticity, section 58). For a finite width plate the peak is Kt_net sigma_net with Kt_net = 3.00 - 3.13 (d/W) + 3.66 (d/W)^2 - 1.53 (d/W)^3 (Peterson's Stress Concentration Factors / Roark's Formulas for Stress and Strain, plate with a central hole in tension; recalled, not re-checked against the book) and sigma_net = sigma W/(W - d). Here d ${2 * a} mm, W ${W} mm (d/W ${r}): Kt_net ${KtNet.toFixed(4)}, peak = ${Kt.toFixed(4)} x ${sig} MPa = ${peak.toFixed(2)} MPa at the hole edge on the symmetry plane normal to the load. A quarter of the plate (${Lx} x ${Ly} x ${th} mm, hole radius ${a} mm, voxel ${h} mm = a/12) with sliders on the three symmetry planes and ${sig} MPa tension at the far end. The spec asks for the recovered stress within 10 % (after smoothing over 2 voxels).`;
  cases.push({ id: 'plate-hole-kirsch', kind: 'core', title: 'Plate with a central circular hole in tension: stress concentration, volume-preserving voxel fill (voxelizer: centre)',
    reference: ref, setup: { ...quarter, ext: S({ h_mm: h, voxelizer: 'centre' }) },
    calls: [probe('peak von Mises stress at the hole, recovered over 2 voxels', 'solveSync.vmMax', ['$input', {}], peak, { rel: 0.10 }, 'MPa'), probe('stress far from the hole (sigma_xx)', 'probeSigma', ['$input', {}, 27, 10, 0.5, 0], sig, { rel: 0.03 }, 'MPa')] });
  cases.push({ id: 'plate-hole-kirsch-default-voxelizer', kind: 'core', title: 'The same plate with the default conservative voxelizer (the hole shrinks by up to one voxel and its edge is a rougher staircase)',
    reference: ref + ' This is the package default (voxelizer conservative: every voxel a surface touches is kept so a thin wall is never lost).',
    expectedDivergence: 'With the default conservative voxelizer the hole is smaller by up to a voxel and its staircase is rougher, and the recovered peak reads about 13 % under the reference at this resolution (a/h = 12), outside the 10 % of the spec. The tolerance is not changed; the volume-preserving fill passes (plate-hole-kirsch). A finer voxel size closes the gap slowly.',
    setup: { ...quarter, ext: S({ h_mm: h }) },
    calls: [probe('peak von Mises stress at the hole, recovered over 2 voxels', 'solveSync.vmMax', ['$input', {}], peak, { rel: 0.10 }, 'MPa')] });
}

// ── 6. a bracket against an Elmer reference: needs the Alloy Runner (A4)
cases.push({ id: 'bracket-vs-elmer', kind: 'reference', title: 'L bracket against an Elmer reference (displacement within 5 %)',
  reference: 'Spec docs/EXTENSIONS_SPEC.md section 4 (A3 validation): "a bracket compared with an Elmer reference". The reference numbers are produced once by the Alloy Runner (step A4, Elmer FEM, tetrahedral mesh) and stored in analysis-validation/reference/bracket-elmer.json. The spec gives no tolerance for this case: 5 % on displacement and stiffness (as for the A2 reference case) and 25 % on the stress peak (which the spec calls an estimate) are this repository\'s choice, written down before any reference exists.',
  referenceFile: 'analysis-validation/reference/bracket-elmer.json',
  setup: { features: [box('Bracket', [40, 30, 4]), box('Bracket', [40, 4, 30])], customMaterials: [matdef('t-steel', 'Test steel', STEEL)], customAssign: { Bracket: 't-steel' },
    conditions: { mounts: [fixed('base', 'Bracket', '-z')], loads: [{ id: 'pull', kind: 'force', body: 'Bracket', face: '+z', vector_N: [0, 30, 0] }] }, ext: S({ quality: 'fine', backend: 'cpu' }) },
  expect: [{ metric: 'max_displacement', unit: 'mm', refKey: 'max_displacement_mm', tol: { rel: 0.05 } }, { metric: 'stiffness', unit: 'N/mm', refKey: 'stiffness_N_per_mm', tol: { rel: 0.05 } }, { metric: 'max_von_mises', unit: 'MPa', refKey: 'max_von_mises_MPa', tol: { rel: 0.25 } }] });

fs.writeFileSync('analysis-validation/cases/structural.json', JSON.stringify({ analysis: `${ID}/structural`, package: 'examples/ext/structural', cases }, null, 1) + '\n');
console.log(cases.length, 'cases');
