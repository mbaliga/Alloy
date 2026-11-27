// OpenFOAM case writer for a steady incompressible flow job (kind "flow"): a box of air around the bodies, meshed by blockMesh and
// snappyHexMesh, solved with simpleFoam and k-omega SST, with the flow through named planes read back by a surfaceFieldValue function object.
// WRITTEN WITHOUT OpenFOAM TO TEST AGAINST: this module only produces text, and the text is checked by the tests for structure (balanced
// braces, the entries the solvers read), never by running OpenFOAM. The first real run is a commissioning step: look at the mesh in
// paraFoam and compare with a hand calculation (a duct) before trusting a number. Units: the case is in metres (STL and box in mm are scaled).
import { FACES } from './tetmesh.mjs';

const HEAD = (cls, obj, loc = '') => `FoamFile\n{\n    version     2.0;\n    format      ascii;\n    class       ${cls};\n${loc ? `    location    "${loc}";\n` : ''}    object      ${obj};\n}\n`;
const AXIS = { x: 0, y: 1, z: 2 };
const num = (v) => (Math.abs(v) < 1e-12 ? '0' : String(+v.toPrecision(8)));
const vec = (a) => `(${a.map(num).join(' ')})`;

/** Dimensions and the box: bounding box of the bodies plus the margin (mm); upstream margin m, downstream 2m, sides m. Returns { lo, hi } in mm. */
export function flowBox(bodies, flow) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const b of bodies) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], b.stats.min[k]); hi[k] = Math.max(hi[k], b.stats.max[k]); }
  const size = Math.max(...[0, 1, 2].map((k) => hi[k] - lo[k])), m = flow.margin_mm > 0 ? flow.margin_mm : 0.35 * size, ax = AXIS[flow.dir[1]], sgn = flow.dir[0] === '+' ? 1 : -1;
  const L = lo.map((v) => v - m), H = hi.map((v) => v + m); if (sgn > 0) H[ax] += m; else L[ax] -= m;
  return { lo: L, hi: H, size, margin: m };
}

export function foamFiles(job, bodies) {
  const flow = job.flow, box = flowBox(bodies, flow), ax = AXIS[flow.dir[1]], sgn = flow.dir[0] === '+' ? 1 : -1;
  const nu = flow.nu_m2s, U = flow.speed_ms, dirv = FACES.includes(flow.dir) ? [0, 0, 0].map((_, i) => (i === ax ? sgn : 0)) : [1, 0, 0];
  const cell = job.options.mesh_size_mm > 0 ? job.options.mesh_size_mm : Math.max(box.size / 24, 0.5);
  const n = [0, 1, 2].map((k) => Math.max(4, Math.round((box.hi[k] - box.lo[k]) / (2 * cell)))); // blockMesh is the coarse background: snappyHexMesh refines by 1 to 2 levels
  const iters = 1500, I = 0.05, k0 = 1.5 * (U * I) ** 2, w0 = Math.sqrt(k0) / (0.09 ** 0.25 * 0.07 * box.size * 1e-3);
  const f = {};
  const lo = box.lo, hi = box.hi, v8 = [[0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0], [0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]].map((c) => c.map((s, k) => (s ? hi[k] : lo[k])));
  const faces = { '-x': [0, 4, 7, 3], '+x': [1, 2, 6, 5], '-y': [0, 1, 5, 4], '+y': [3, 7, 6, 2], '-z': [0, 3, 2, 1], '+z': [4, 5, 6, 7] };
  const inletFace = (sgn > 0 ? '-' : '+') + flow.dir[1], outletFace = (sgn > 0 ? '+' : '-') + flow.dir[1], sides = FACES.filter((x) => x !== inletFace && x !== outletFace);
  f['system/blockMeshDict'] = `${HEAD('dictionary', 'blockMeshDict')}convertToMeters 0.001;\nvertices\n(\n${v8.map((p) => `    ${vec(p)}`).join('\n')}\n);\nblocks\n(\n    hex (0 1 2 3 4 5 6 7) (${n.join(' ')}) simpleGrading (1 1 1)\n);\nedges ();\nboundary\n(\n` +
    [['inlet', 'patch', [inletFace]], ['outlet', 'patch', [outletFace]], ['sides', 'patch', sides]].map(([nm, ty, fs]) => `    ${nm}\n    {\n        type ${ty};\n        faces\n        (\n${fs.map((q) => `            (${faces[q].join(' ')})`).join('\n')}\n        );\n    }`).join('\n') + '\n);\n';
  const geom = bodies.map((b, i) => `        body_${i}\n        {\n            type triSurfaceMesh;\n            file "body_${i}.stl";\n            name body_${i};\n        }`).join('\n');
  const refs = bodies.map((b, i) => `            body_${i} { level (2 2); patchInfo { type wall; } }`).join('\n');
  const inside = [0, 1, 2].map((k) => (k === ax ? (sgn > 0 ? lo[k] + 0.25 * box.margin : hi[k] - 0.25 * box.margin) : lo[k] + 0.25 * box.margin)); // a point of the free air near the upstream corner
  f['system/snappyHexMeshDict'] = `${HEAD('dictionary', 'snappyHexMeshDict')}castellatedMesh true;\nsnap true;\naddLayers false;\ngeometry\n{\n${geom}\n}\ncastellatedMeshControls\n{\n    maxLocalCells 2000000;\n    maxGlobalCells 4000000;\n    minRefinementCells 0;\n    nCellsBetweenLevels 2;\n    features ();\n    refinementSurfaces\n    {\n${refs}\n    }\n    resolveFeatureAngle 30;\n    refinementRegions {}\n    locationInMesh ${vec(inside.map((v) => v * 1e-3))};\n    allowFreeStandingZoneFaces true;\n}\nsnapControls\n{\n    nSmoothPatch 3;\n    tolerance 2.0;\n    nSolveIter 30;\n    nRelaxIter 5;\n    nFeatureSnapIter 10;\n    implicitFeatureSnap true;\n    explicitFeatureSnap false;\n}\naddLayersControls { relativeSizes true; layers {} expansionRatio 1.0; finalLayerThickness 0.3; minThickness 0.1; nGrow 0; featureAngle 60; nRelaxIter 3; nSmoothSurfaceNormals 1; nSmoothNormals 3; nSmoothThickness 10; maxFaceThicknessRatio 0.5; maxThicknessToMedialRatio 0.3; minMedialAxisAngle 90; nBufferCellsNoExtrude 0; nLayerIter 50; }\nmeshQualityControls\n{\n    maxNonOrtho 65;\n    maxBoundarySkewness 20;\n    maxInternalSkewness 4;\n    maxConcave 80;\n    minVol 1e-13;\n    minTetQuality 1e-15;\n    minArea -1;\n    minTwist 0.02;\n    minDeterminant 0.001;\n    minFaceWeight 0.05;\n    minVolRatio 0.01;\n    minTriangleTwist -1;\n    nSmoothScale 4;\n    errorReduction 0.75;\n}\nmergeTolerance 1e-6;\ndebug 0;\n`;
  const planes = (flow.planes || []).map((p) => `        plane_${p.id}\n        {\n            type surfaceFieldValue;\n            libs ("libfieldFunctionObjects.so");\n            writeControl onEnd;\n            writeFields false;\n            regionType sampledSurface;\n            name plane_${p.id};\n            operation sum;\n            fields (phi);\n            sampledSurfaceDict\n            {\n                type cuttingPlane;\n                planeType pointAndNormal;\n                pointAndNormalDict { point ${vec(p.point_mm.map((v) => v * 1e-3))}; normal ${vec(p.normal)}; }\n                interpolate false;\n                bounds ${vec(p.bounds_mm.filter((_, i) => i % 2 === 0).map((v) => v * 1e-3))} ${vec(p.bounds_mm.filter((_, i) => i % 2 === 1).map((v) => v * 1e-3))};\n            }\n        }`).join('\n');
  f['system/controlDict'] = `${HEAD('dictionary', 'controlDict')}application simpleFoam;\nstartFrom startTime;\nstartTime 0;\nstopAt endTime;\nendTime ${iters};\ndeltaT 1;\nwriteControl timeStep;\nwriteInterval ${iters};\npurgeWrite 1;\nwriteFormat ascii;\nwritePrecision 8;\ntimeFormat general;\ntimePrecision 6;\nrunTimeModifiable false;\nfunctions\n{\n${planes}\n}\n`;
  f['system/fvSchemes'] = `${HEAD('dictionary', 'fvSchemes')}ddtSchemes { default steadyState; }\ngradSchemes { default Gauss linear; }\ndivSchemes\n{\n    default none;\n    div(phi,U) bounded Gauss linearUpwind grad(U);\n    div(phi,k) bounded Gauss upwind;\n    div(phi,omega) bounded Gauss upwind;\n    div((nuEff*dev2(T(grad(U))))) Gauss linear;\n}\nlaplacianSchemes { default Gauss linear corrected; }\ninterpolationSchemes { default linear; }\nsnGradSchemes { default corrected; }\nwallDist { method meshWave; }\n`;
  f['system/fvSolution'] = `${HEAD('dictionary', 'fvSolution')}solvers\n{\n    p { solver GAMG; smoother GaussSeidel; tolerance 1e-7; relTol 0.1; }\n    "(U|k|omega)" { solver smoothSolver; smoother symGaussSeidel; tolerance 1e-8; relTol 0.1; }\n}\nSIMPLE\n{\n    nNonOrthogonalCorrectors 1;\n    consistent yes;\n    residualControl { p 1e-4; U 1e-5; "(k|omega)" 1e-5; }\n}\nrelaxationFactors\n{\n    equations { U 0.9; ".*" 0.9; }\n}\n`;
  f['constant/transportProperties'] = `${HEAD('dictionary', 'transportProperties')}transportModel Newtonian;\nnu ${num(nu)};\n`;
  f['constant/momentumTransport'] = `${HEAD('dictionary', 'momentumTransport')}simulationType RAS;\nRAS { model kOmegaSST; turbulence on; printCoeffs on; }\n`;
  const field = (cls, name, dims, internal, inl, out, wall, side) => `${HEAD(cls, name)}dimensions ${dims};\ninternalField uniform ${internal};\nboundaryField\n{\n    inlet { ${inl} }\n    outlet { ${out} }\n    sides { ${side} }\n    ".*body_.*" { ${wall} }\n}\n`;
  f['0/U'] = field('volVectorField', 'U', '[0 1 -1 0 0 0 0]', vec(dirv.map((v) => v * U)), `type fixedValue; value uniform ${vec(dirv.map((v) => v * U))};`, 'type inletOutlet; inletValue uniform (0 0 0); value uniform (0 0 0);', 'type noSlip;', 'type slip;');
  f['0/p'] = field('volScalarField', 'p', '[0 2 -2 0 0 0 0]', '0', 'type zeroGradient;', 'type fixedValue; value uniform 0;', 'type zeroGradient;', 'type zeroGradient;');
  f['0/k'] = field('volScalarField', 'k', '[0 2 -2 0 0 0 0]', num(k0), `type fixedValue; value uniform ${num(k0)};`, 'type inletOutlet; inletValue uniform ' + num(k0) + '; value uniform ' + num(k0) + ';', `type kqRWallFunction; value uniform ${num(k0)};`, 'type zeroGradient;');
  f['0/omega'] = field('volScalarField', 'omega', '[0 0 -1 0 0 0 0]', num(w0), `type fixedValue; value uniform ${num(w0)};`, 'type inletOutlet; inletValue uniform ' + num(w0) + '; value uniform ' + num(w0) + ';', `type omegaWallFunction; value uniform ${num(w0)};`, 'type zeroGradient;');
  f['0/nut'] = field('volScalarField', 'nut', '[0 2 -1 0 0 0 0]', '0', 'type calculated; value uniform 0;', 'type calculated; value uniform 0;', 'type nutkWallFunction; value uniform 0;', 'type calculated; value uniform 0;');
  return { files: f, info: { cells_background: n, cell_mm: cell, box_mm: { lo: box.lo, hi: box.hi }, iterations: iters, k: k0, omega: w0 } };
}
