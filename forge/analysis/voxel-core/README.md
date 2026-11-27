# Voxel core

Shared by Alloy Forge's grid-based analyses: A2 (thermal 3D), A3 (structural) and A5 (airflow). Plain JS typed arrays and WGSL;
no dependencies, no DOM, no imports between the files, so it runs in Node tests and, concatenated by `tools/bundle-ext.mjs`,
inside a package Worker.

| File | What |
|---|---|
| `grid.js` | conservative mesh voxelizer (parity fill + every voxel a triangle touches) with per-voxel material ids, box and cylinder painters, enclosed-air detection, the sparse active-cell list, face conductances, the grid-size policy (draft / normal / fine) |
| `cg.js` | matrix-free Jacobi-PCG on the active-cell list; CPU backend; one generator driven synchronously (tests) or asynchronously (Worker, cancel and progress between chunks) |
| `gpu.js` | the same solver as WebGPU compute shaders (WGSL); the whole iteration stays on the device |
| `correlations.js` | copies of A1's convection and radiation correlations (the core owns them; `test_voxel_core.mjs` checks they agree with A1) |

Conventions: grid cell (i,j,k) covers `origin + [i,i+1)·h` (mm); linear index `i + nx(j + ny k)`; face order `+x -x +y -y +z -z`;
material id 0 is outside air, 1 to 250 solid owners (the caller keeps the table), 255 is air enclosed by solid.
