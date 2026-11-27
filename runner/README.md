# Alloy Runner

A small HTTP service you run yourself (on your own machine or server) for the analyses that are too heavy or too important for the
browser. Alloy Forge sends it a design, it meshes the bodies with **gmsh** and solves them with **Elmer**, and Forge draws the
result on the part. Nothing goes anywhere else: the runner makes no outbound connection of any kind, has no telemetry, and Forge only
ever talks to the address you type into Settings.

This is step A4 of `docs/EXTENSIONS_SPEC.md`. Read its "A4 done" block for what is and is not finished. In short:

- The **protocol, job lifecycle, auth, retention, cancel, meshing (real gmsh), boundary selection, Elmer input files and result
  reading** are built and tested.
- **ElmerSolver has never been run by the author** (it was not installable in the build sandbox). Its input files are checked as text
  and the adapter is tested against a stub script, not against Elmer. Treat your first real run as commissioning: compare it with a
  hand calculation (a bar in tension, a cantilever) before you trust a number.
- The container image has **not been built** (no Docker daemon in the sandbox).
- **OpenFOAM is not implemented** (flow arrives with step A5). `GET /v1/health` says so.
- Results from the runner are graded `estimate` at best until your runner carries a qualification file (see below). The simulated
  solver (protocol tests only, off by default) produces `experimental` results labelled SIMULATED.

## Run it

Node 20 or newer, gmsh and ElmerSolver on the `PATH` (or use the container). No `npm install`: there are no dependencies.

```sh
export ALLOY_RUNNER_TOKEN="$(openssl rand -hex 24)"       # required; at least 16 characters
export ALLOY_RUNNER_ORIGINS="https://forge.example.org"   # the origin Forge is served from (comma separated); without it the browser is refused
node bin/alloy-runner.mjs                                 # listens on 127.0.0.1:8787
```

Container (not built or run by the author, see the notes at the top of `Dockerfile`):

```sh
docker compose -f runner/docker-compose.yml up --build    # needs ALLOY_RUNNER_TOKEN in the environment
```

In Forge: Settings > Extensions > install **Alloy Runner**, then enter the address and the token under **Alloy Runner** and press
**test**. The test shows what the runner has installed (gmsh and ElmerSolver, per adapter) and whether it accepts your token. A page served over
`https` can only reach an `https` runner (or `http://localhost`): put a TLS reverse proxy in front for a remote machine.

### Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `ALLOY_RUNNER_TOKEN` | none, required | the bearer token. The runner refuses to start without one |
| `ALLOY_RUNNER_ALLOW_NO_AUTH=1` | off | development only, and only accepted when the host is loopback |
| `ALLOY_RUNNER_HOST`, `ALLOY_RUNNER_PORT` | `127.0.0.1`, `8787` | listen address. The container sets `0.0.0.0` |
| `ALLOY_RUNNER_ORIGINS` | none | CORS allow-list of page origins (`*` is accepted but not recommended). Private-network preflight is answered for listed origins |
| `ALLOY_RUNNER_DATA` | `$TMPDIR/alloy-runner` | where jobs live while they exist |
| `ALLOY_RUNNER_TTL_S` | `3600` | an undownloaded finished job is deleted this long after it finished (unless kept) |
| `ALLOY_RUNNER_JOB_TIMEOUT_S` | `3600` | a running job is stopped after this long |
| `ALLOY_RUNNER_MAX_BODY_MB`, `_MAX_BODIES`, `_MAX_JOBS`, `_MAX_TETS`, `_CONCURRENCY` | 100, 16, 50, 400000, 1 | limits |
| `ALLOY_RUNNER_GMSH`, `ALLOY_RUNNER_ELMER` | `gmsh`, `ElmerSolver` | the programs it starts |
| `ALLOY_RUNNER_ENABLE_FAKE=1` | off | enable the **simulated** solver (protocol tests only; every result says SIMULATED) |
| `ALLOY_RUNNER_QUALIFICATION` | none | path of the qualification file (below) |

## Protocol (version 1)

JSON over HTTP. Everything except the first call needs `Authorization: Bearer <token>`.

| Call | Meaning |
|---|---|
| `GET /v1/health` | without the token: `{ ok, name, version, protocol }`. With it also `adapters` (which solvers are ready and why not), `qualification` and `limits` |
| `POST /v1/jobs` | submit a job (below) -> `202 { id, state }` |
| `GET /v1/jobs` | the jobs on this runner |
| `GET /v1/jobs/:id?since=N` | `{ state, stage, progress (0..1), events: [{n, t, msg, progress, stage}], next, files?, error? }`; states `queued`, `running`, `done`, `failed`, `cancelled` |
| `POST /v1/jobs/:id/cancel` | stop a queued or running job (kills the child process) |
| `GET /v1/jobs/:id/result` | the summary JSON |
| `GET /v1/jobs/:id/files/result.vtu` | the solution (VTU, ASCII): all tetrahedra, `body` cell field, nodal fields. Points in **mm**, displacement in **mm**, stress in **MPa**, temperature in **degrees C** whatever the solver used |
| `DELETE /v1/jobs/:id` | delete now |

**Retention.** When every file of a finished job has been downloaded the runner deletes the job (unless it was submitted with
`options.keep`); otherwise it deletes it `ALLOY_RUNNER_TTL_S` after it finished. The STL a client sent is dropped from memory and disk as soon as the
solve ends. Failed and cancelled jobs are deleted by Forge right away.

**A job** (`POST /v1/jobs`): `{ protocol: 1, kind: "structural" | "thermal", adapter: "auto" | "elmer" | "fake", label, bodies: [{ name, format: "stl", data_b64, material: { E_Pa, nu, rho, yield_Pa, tensile_Pa, k, cp } }], conditions: { ambient_K, h_conv_W_m2K, gravity, self_weight, mounts: [{ id, kind: fixed|slider|contact, body, face: "+x".."-z", box?: [x0,x1,y0,y1,z0,z1], conductance_W_m2K? }], loads: [{ id, kind: force|pressure|acceleration, ... }], heat: [{ id, body, power_W }] }, options: { mesh_size_mm (0 = automatic), mesh_study, keep } }`.
The STL must be closed and wound outward (it is checked). Faces are the same vocabulary as Forge's Analyze pane: a direction of a body's bounding box, optionally inside a box.
`step` is accepted by the schema but **refused in this version** (send STL).

**Mesh study.** With `options.mesh_study` the runner solves twice, at the mesh size and at a size 1.5 times finer, and reports the relative change of
displacement, stiffness and von Mises (tolerances 2 %, 2 %, 10 %) in `study`. `study.converged` is true only when all are within tolerance.

**Qualification.** A result is `validated` in Forge only when a real solver ran, the mesh study converged **and** the runner's operator
has attested that it reproduces the reference cases: a JSON file `{ "passed": true, "cases": [...], "note": "..." }` named by `ALLOY_RUNNER_QUALIFICATION`.
It is a statement by whoever runs the runner, shown in every result; this repository ships none. Without it the grade is `estimate`.

## What it does to a job

1. Validates the request (sizes, names, closed STL, materials, faces).
2. Meshes each body on its own with gmsh. First it re-meshes the surface at the element size; if that fails (surfaces with needle triangles, typical after boolean
   operations) it keeps the STL triangles as the boundary mesh and says so in the result.
3. Finds the boundary faces and attaches the mounts and loads to them. Writes the Elmer mesh (metres) and the `.sif`.
4. Runs `ElmerSolver case.sif` as a child process, killed on cancel or timeout.
5. Reads Elmer's VTU, converts the units, computes the safety factor and the summary metrics, and writes `result.vtu` and `result.json`.

## Known limits

- **Bodies are meshed separately and are not bonded** where they touch (no shared nodes, no contact model); every body needs its own fixed mount
  (structural) and exchanges no heat with a neighbour (thermal).
- **Structural:** linear elasticity, one load case, isotropic material (no FDM knock-down). Mounts: `fixed` and `slider`.
- **Thermal:** steady state; free surfaces lose heat by a single film coefficient (`h_conv_W_m2K`, default 10); no separate radiation. Heat sources must be on bodies:
  Forge does not send PCB parts, and says how many watts it left out.
- **Mesh:** first-order tetrahedra only; stress at sharp corners depends on the mesh (the study shows how much).
- Not implemented: OpenFOAM, STEP input, transient thermal, contact, GPU.

## Tests

`npm test` (Node >= 20, gmsh installed for the meshing tests; they are skipped without it): protocol, auth, CORS, validation, lifecycle, retention, cancel,
timeout and the mesh study with the simulated solver; the real gmsh on boxes and on the starter enclosure; mesh and `.sif` writing as text; the Elmer
adapter against a **stub** `ElmerSolver` script (not Elmer); the VTU, STL and gmsh readers. None of these runs a real solver.

## Licence

The runner's code is under the repository licence (PolyForm Noncommercial 1.0.0). gmsh, Elmer and Node are separate programs with their own licences; see `THIRD_PARTY.md`.
