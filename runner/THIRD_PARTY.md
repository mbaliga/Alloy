# Third-party software used by the Alloy Runner

The runner's own code (this folder) is under the repository licence (PolyForm Noncommercial 1.0.0, see `../LICENSE`). It has **no npm
dependencies**; it uses Node's standard library only.

The programs below are installed in the container image (or on the host) and started as **separate processes** that read and write
files. They are not linked into, bundled with, or distributed inside Forge, and no code of theirs is copied into this repository.
Each keeps its own licence; if you distribute the container image you are distributing them and must follow their terms.

| Program | Used for | Licence | How it is started |
|---|---|---|---|
| gmsh (https://gmsh.info) | STL surface to tetrahedral volume mesh | GPL-2.0-or-later (with an exception for linking against some libraries; see gmsh's own text) | `gmsh body.geo -3 -format msh2` |
| Elmer FEM (https://www.elmerfem.org, ElmerSolver) | structural (linear elasticity) and thermal (heat equation) solves | GPL-2.0-or-later (ElmerSolver; some libraries LGPL) | `ElmerSolver case.sif` |
| OpenFOAM (ESI or Foundation) | flow (planned for step A5; **not used by this version**) | GPL-3.0-or-later | not started |
| Node.js (https://nodejs.org) | runs the service | MIT | the container's entry point |

No file produced by these programs is copied into the repository. A reference result stored under `forge/analysis-validation/reference/`
is a handful of numbers with the solver and version named in its `source`.

Their own dependencies (MUMPS, UMFPACK, OpenCASCADE and others, depending on the packages you install) have their own licences; see
the package metadata (`dpkg -L`, `apt show`) of the image you build.
