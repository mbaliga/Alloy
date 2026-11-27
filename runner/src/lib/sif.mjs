// Elmer solver input files (.sif), SI units throughout (the mesh is written in metres, see tetmesh.writeElmerMesh).
// NOTE: these files are generated from the Elmer documentation and are checked here only as text. No ElmerSolver was available when this
// was written, so they have NOT been accepted by a real ElmerSolver; docs/EXTENSIONS_SPEC.md (A4 done) says so.
import { FACE_N } from './tetmesh.mjs';

const num = (v) => { if (!Number.isFinite(v)) throw new Error(`sif: ${v} is not a finite number`); return Number(v.toPrecision(12)).toString(); };
const vec = (a) => a.map(num).join(' ');

const HEADER = (out) => `Header
  CHECK KEYWORDS Warn
  Mesh DB "." "mesh"
  Include Path ""
  Results Directory "${out}"
End

Simulation
  Max Output Level = 4
  Coordinate System = Cartesian
  Coordinate Mapping(3) = 1 2 3
  Simulation Type = Steady state
  Steady State Max Iterations = 1
  Output Intervals = 1
End
`;

const OUTPUT_SOLVER = (n) => `
Solver ${n}
  Exec Solver = After Simulation
  Equation = "ResultOutput"
  Procedure = "ResultOutputSolve" "ResultOutputSolver"
  Output File Name = "result"
  Vtu Format = Logical True
  Ascii Output = Logical True
  Single Precision = Logical False
End
`;

const LINSYS = `  Linear System Solver = Iterative
  Linear System Iterative Method = CG
  Linear System Max Iterations = 20000
  Linear System Convergence Tolerance = 1.0e-10
  Linear System Preconditioning = ILU1
  Linear System Residual Output = 50
  Steady State Convergence Tolerance = 1.0e-6`;

/**
 * Structural case. bodies[i]: { E_Pa, nu, rho, accel: [ax, ay, az] m/s^2 | null }. bcs: [{ id (boundary id in the mesh), fixed: [bool,bool,bool], traction: [tx,ty,tz] Pa | null }].
 */
export function structuralSif({ bodies, bcs, resultsDir = '.' }) {
  let s = HEADER(resultsDir) + `
Solver 1
  Equation = "Linear elasticity"
  Procedure = "StressSolve" "StressSolver"
  Variable = -dofs 3 Displacement
  Calculate Stresses = Logical True
  Calculate Principal = Logical False
  Exec Solver = Always
${LINSYS}
End
` + OUTPUT_SOLVER(2) + `
Equation 1
  Active Solvers(2) = 1 2
End
`;
  bodies.forEach((b, i) => {
    s += `
Material ${i + 1}
  Density = ${num(b.rho)}
  Youngs modulus = ${num(b.E_Pa)}
  Poisson ratio = ${num(b.nu)}
End
`;
    if (b.accel) s += `
Body Force ${i + 1}
  Stress Bodyforce 1 = ${num(b.rho * b.accel[0])}
  Stress Bodyforce 2 = ${num(b.rho * b.accel[1])}
  Stress Bodyforce 3 = ${num(b.rho * b.accel[2])}
End
`;
    s += `
Body ${i + 1}
  Target Bodies(1) = ${i + 1}
  Name = "${b.name || 'body' + (i + 1)}"
  Equation = 1
  Material = ${i + 1}${b.accel ? `\n  Body Force = ${i + 1}` : ''}
End
`;
  });
  for (const bc of bcs) {
    s += `
Boundary Condition ${bc.id}
  Target Boundaries(1) = ${bc.id}
  Name = "${bc.name || 'bc' + bc.id}"
`;
    bc.fixed.forEach((f, c) => { if (f) s += `  Displacement ${c + 1} = Real 0.0\n`; });
    if (bc.traction) bc.traction.forEach((t, c) => { if (t !== 0) s += `  Force ${c + 1} = Real ${num(t)}\n`; });
    s += 'End\n';
  }
  return s;
}

/** Thermal case. bodies[i]: { k, rho, cp, source_W_per_kg }. bcs: [{ id, h, T_ext }] Robin conditions (convection, contact conductance to a sink). */
export function thermalSif({ bodies, bcs, resultsDir = '.' }) {
  let s = HEADER(resultsDir) + `
Solver 1
  Equation = "Heat Equation"
  Procedure = "HeatSolve" "HeatSolver"
  Variable = Temperature
  Exec Solver = Always
${LINSYS}
  Nonlinear System Max Iterations = 1
End
` + OUTPUT_SOLVER(2) + `
Equation 1
  Active Solvers(2) = 1 2
End
`;
  bodies.forEach((b, i) => {
    s += `
Material ${i + 1}
  Density = ${num(b.rho)}
  Heat Conductivity = ${num(b.k)}
  Heat Capacity = ${num(b.cp)}
End
`;
    if (b.source_W_per_kg > 0) s += `
Body Force ${i + 1}
  Heat Source = ${num(b.source_W_per_kg)}
End
`;
    s += `
Body ${i + 1}
  Target Bodies(1) = ${i + 1}
  Name = "${b.name || 'body' + (i + 1)}"
  Equation = 1
  Material = ${i + 1}${b.source_W_per_kg > 0 ? `\n  Body Force = ${i + 1}` : ''}
End
`;
  });
  for (const bc of bcs) s += `
Boundary Condition ${bc.id}
  Target Boundaries(1) = ${bc.id}
  Name = "${bc.name || 'bc' + bc.id}"
  Heat Transfer Coefficient = ${num(bc.h)}
  External Temperature = ${num(bc.T_ext)}
End
`;
  return s;
}

/** Traction vector (Pa) of a uniform pressure p (Pa, positive pushes into the face) on a face with outward direction `face`. */
export const pressureTraction = (p, face) => FACE_N[face].map((n) => -p * n);
export { vec };
