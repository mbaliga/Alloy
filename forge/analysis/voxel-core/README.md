# Voxel core

Shared by Alloy Forge's grid-based analyses: A2 (thermal 3D), A3 (structural, uses the two `elastic` files) and A5 (airflow, uses `grid`, `lbm` and `gpu-lbm`). Plain JS typed arrays and WGSL;
no dependencies, no DOM, no imports between the files, so it runs in Node tests and, concatenated by `tools/bundle-ext.mjs`,
inside a package Worker (`tools/bundle-ext.mjs` builds one bundle per package: `thermal-3d` gets `correlations, grid, cg, gpu`, `structural` gets `grid, cg, elastic, gpu-elastic`, `airflow` gets `grid, lbm, gpu-lbm`).

| File | What |
|---|---|
| `grid.js` | conservative mesh voxelizer (parity fill + every voxel a triangle touches) with per-voxel material ids, box and cylinder painters, enclosed-air detection, the sparse active-cell list, face conductances, the grid-size policy (draft / normal / fine) |
| `cg.js` | matrix-free Jacobi-PCG on the active-cell list; CPU backend; one generator driven synchronously (tests) or asynchronously (Worker, cancel and progress between chunks) |
| `gpu.js` | the same solver as WebGPU compute shaders (WGSL); the whole iteration stays on the device |
| `elastic.js` | A3: linear elasticity. One 8-node hexahedron per solid voxel (trilinear plus nine condensed incompatible modes, so it does not lock in bending), the 24 x 24 stiffness of a cube per Poisson ratio, the node/element structure, the matrix-free operator (element scatter, CPU), Jacobi-PCG as a generator, a rigid-body check of the supports, element stress |
| `gpu-elastic.js` | the same operator in WGSL as a node-gather (no atomics), one thread per dof, the whole CG iteration on the device |
| `lbm.js` | A5: lattice Boltzmann. D3Q19 BGK with a Smagorinsky closure, Guo body force, halfway bounce-back (also moving walls), velocity and pressure boundary cells, periodic axes, an optional D3Q7 thermal lattice with Boussinesq buoyancy and conducting solids, SI-to-lattice scaling, lattice budgets. The bulk of the update is a generated unrolled kernel between the GENERATED markers (`tools/gen-lbm-kernel.mjs`, checked for staleness); the loop version is the reference (`S.useKernel = false`) |
| `gpu-lbm.js` | the same step as one WGSL compute kernel (one thread per cell, pull streaming, two ping-pong population buffers), flow only |
| `correlations.js` | copies of A1's convection and radiation correlations (the core owns them; `test_voxel_core.mjs` checks they agree with A1) |

Conventions: grid cell (i,j,k) covers `origin + [i,i+1)·h` (mm); linear index `i + nx(j + ny k)`; face order `+x -x +y -y +z -z`;
material id 0 is outside air, 1 to 250 solid owners (the caller keeps the table), 255 is air enclosed by solid.
