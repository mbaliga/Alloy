# Alloy Forge

Browser-based parametric CAD for electronics enclosures and small product design, with an AI-native editing workflow (bring your own key). Part of Alloy; the native Android slicer lives in the rest of this repo.

- **Parametric core**: named parameters and expressions (`outL = inL + 2*wall`), an ordered feature tree, patterns, mirrors, lid/base split.
- **Enclosure wizard**: PCB size, standoffs and port cutouts generate a base, a lid with a lip and a reference PCB.
- **Objects / Features / Params panes**: bodies work as layers (show, solo, lock, colour, rename); features are the history.
- **Locks** on features, parameters and bodies, enforced in the data layer, so neither the gizmo nor the AI can change locked items.
- **Select and transform**: click to select a body; Move/Rotate gizmo with snapping writes deltas back into expressions so drags stay parametric. Transform feature: move, rotate, scale, shear, twist, taper, bend.
- **Words in any field**: type "twice the wall plus clearance" and the model turns it into an expression.
- **AI copilot**: the model returns structured ops that are validated, built, auto-repaired on failure and applied with one-tap undo. Anthropic or any OpenAI-compatible endpoint (OpenRouter, Ollama, …). The key stays in the browser and goes only to the endpoint you set; there is no backend.
- **Design loop** (Loop pane): iterate-until-the-checks-pass with a model. See below.
- **Import** STEP and STL; **export** STEP (planar B-rep faces on Manifold, true B-rep on OCCT), STL, OBJ and project JSON.
- **Inspect**: pick points, one-tap holes and bosses, measure, section view, mass and build-volume check.
- **Alloy rendering**: matte bodies, fog, a grid that blooms under the model and fades out, build-volume box, bodies turn grey when off the plate, light and dark themes.

## Analysis framework (A0)

With an analysis package installed and switched on, an **Analyze** tab appears: pick an analysis, set materials (per body; unassigned means PLA, said in every result), ambient, heat sources, mounts and loads (all saved in the project as typed ops), Run (progress and Cancel), read the summary and its **validity** (validated, estimate or experimental; experimental never decides a pass or fail), draw surface and voxel fields in the viewport with legend and probe, edit the limits of its checks, and export a report zip. Results are cached in the browser and never saved in the project. `npm run test:analysis` runs the validation suite (`analysis-validation/`); `npm run test:analyze-ui` is the browser smoke test. Material values are typical, not measured; each shows its quality and source. Bundled analysis samples: `sample-mass` (mass and centre of mass), `thermal-estimate` (A1: a thermal resistance network, with `thermal-estimate(target_C)` and `set-vents` AI tools; an estimate, see `docs/EXTENSIONS_SPEC.md` A1) and `thermal-3d` (A2: steady and transient heat conduction on a voxel grid, an extension with `compute.gpu`: WebGPU conjugate gradient with a CPU fallback, resolution draft / normal / fine with the voxel size and grid limits stated in every result, settings in the Analyze pane saved in the project; an estimate until the Elmer reference case exists, see `docs/EXTENSIONS_SPEC.md` A2). The shared voxel core is `analysis/voxel-core/` (A3 and A5 build on it); `npm run build:ext` regenerates `examples/ext/thermal-3d/main.js` from it (a test fails if it is stale).

## Structural analysis (A3)

The **Structural** package (`examples/ext/structural`, bundled sample, an extension: it asks for `compute.gpu`) solves linear elasticity on a voxel grid: one 8-node hexahedron per solid voxel (with condensed incompatible modes, so a beam a few voxels deep does not lock), matrix-free Jacobi-PCG in a package Worker on WebGPU, with a CPU fallback and a smaller grid. In the Analyze pane add **mounts** (`fixed` holds the nodes of a face; `slider` holds only the displacement normal to it, for a symmetry plane) and **loads** (force, pressure, acceleration in g); the package settings add self weight, a drop or recoil preset (an equivalent quasi-static acceleration, with the assumption shown), a print axis for the FDM inter-layer knock-down and, through the AI tool `set-structural`, a simplified bolt preload. It reports displacement, stiffness, von Mises stress recovered over 2 voxels, a safety factor against yield, and states voxel size, solid voxel count, budget and the grid limits in the result and the legend. Stress, displacement and safety factor are surface fields per body, switched in the Analyze result list or the **Layers** sheet. The result is `estimate`: the bracket-against-Elmer reference (A4) does not exist, and a stress peak on a boundary voxel is flagged *edge artefact likely*. `npm run test:analysis` has its validation table (`analysis-validation/cases/structural.json`); `npm run test:analysis:slow` adds the cantilever at the quality fine (minutes); `npm run test:structural-ui` is the Playwright smoke test.

## Airflow (A5)

The **Airflow** package (`examples/ext/airflow`, bundled sample, an extension: it asks for `compute.gpu` and `scene.overlay`) simulates air around and through your design with the lattice Boltzmann method (D3Q19, Smagorinsky closure) on a lattice of voxels, in a package Worker on WebGPU with a CPU fallback and a smaller lattice. Set the **air speed** in the Analyze pane's Conditions (ambient) or the package setting, or add a **fan** (the `set-airflow` AI tool or the project data: centre, direction, radius, pressure rise, maximum flow), pick the resolution, and run. You get the speed, pressure and **dead-zone** fields, the velocity as arrows, **streamlines** (a layer of its own), the **flow in and out of each vent**, the through flow in L/s and cfm, and two checks with editable limits (dead-zone fraction, through flow). Every result states the voxel size, the lattice and its limits (128 cubed on a laptop GPU, 64 cubed on a phone with a warning, much smaller on the CPU), the scaling, and that the real viscosity of air is below what the lattice can carry at these sizes, so the simulated Reynolds number is lower than the real one.

**Natural convection** (setting mode, CPU only) adds a temperature lattice with Boussinesq buoyancy and the heat sources of the Conditions, with the bodies conducting in the same lattice. It is **experimental**: its checks are advisory and it never decides a pass or fail.

Honest limits: **phase 1 is an `estimate`**: the lid-driven cavity (Ghia), the cylinder Strouhal number and a channel flow pass, but the enclosure compared with OpenFOAM through the Runner has not been run (no OpenFOAM here; the Runner's OpenFOAM path is written and stub-tested only), so velocities are not claimed as validated. The WebGPU path was executed on a **software** adapter only: its speed on a real GPU is not known. No wall function, no radiation, no transient. See `docs/EXTENSIONS_SPEC.md` A5 done for the full list and `analysis-validation/` for the table.

## Alloy Runner (A4)

The **Alloy Runner** package (`examples/ext/runner`, bundled sample, an extension: it asks for `compute.remote` and no network host) sends the design to an **Alloy Runner you run yourself** (`../runner/`: a small HTTP service that meshes with gmsh and solves with Elmer) for structural and thermal results, and draws the solution on the surface of each body (`runner_von_mises`, `runner_displacement`, `runner_safety_factor`, `runner_temperature` in the Layers sheet). Enter the runner's address and token under Settings > Extensions > Alloy Runner and press **test**. Nothing is sent anywhere else: no telemetry, and the only address Forge talks to for this is the one you typed.

**Your geometry leaves the browser only when you press Run and then confirm.** The Analyze pane shows a consent dialog (the runner address, each body and its triangle count, the size, what is sent and what is not) the moment the package is about to upload; Cancel sends nothing. An AI tool, the design loop or a scorecard check can never upload, and a cached result sends nothing. The host adds the token; the package never sees it, and the AI key is never involved. The pane lists the jobs on the runner with progress, Cancel and Delete (these send no geometry). Settings: solver, mesh size, **mesh study** (a second solve at 1.5 times finer, reporting how much each result moved), keep job on the runner, and for thermal the air film coefficient.

Honest limits: **ElmerSolver was not available when this was built, so the Elmer path has not been run against real Elmer**; results come back `estimate` at best (validated needs a real solver, a converged mesh study and a qualification file from the runner's operator, which this repository does not ship); with the runner's simulated solver (protocol tests) results are `experimental` and say SIMULATED. Bodies are meshed separately and are not bonded; PCB heat sources are not sent (the result says how many watts that leaves out); the OpenFOAM path (flow jobs, a steady `simpleFoam` case) is written and tested against stubs only and Forge does not submit flow jobs; STEP is not implemented. See `../runner/README.md` and `docs/EXTENSIONS_SPEC.md` (A4 done). `npm run test:runner` runs the runner's own tests; `npm run test:runner-ui` is the browser smoke test (desktop and a 390 px phone, with a real runner on another port).

## Extensions

Forge has one package format (`.alloyext`) and one host. **What a package asks for decides its tier**: a *plugin* (feature types, checks, importers/exporters, AI tools; no DOM) or an *extension* (its own pane, viewport overlays, GPU, a runner, a network host). Settings > Extensions: choose a file, a URL or a bundled sample, read the consent prompt (tier and every permission in words), install, and switch it on per project. Each package runs in its own Web Worker; every call it makes goes through a permission check in the host, and geometry changes are typed ops (`applyOps`: validated, lock-checked, undoable) like the AI's. Packages never produce geometry: a feature type returns built-in feature specs and the kernel builds them.

Removing a package never breaks a project. Its features stay in the file untouched, marked "needs <name>" and skipped in builds with a visible warning; its checks show as "needs <name>" rows that do not gate; reinstalling restores everything. Check rows with `validity: "experimental"` are shown but never decide the verdict.

- Authors: [docs/EXTENSION_API.md](../docs/EXTENSION_API.md) (generated from `src/ext/api-spec.js`, so it cannot drift), manifest schema in `src/ext/manifest.js`, two samples in `examples/ext/` (`sample-rib`: a plugin, `sample-probe`: an extension), `node tools/pack-ext.mjs <dir> <out.alloyext>`.
- Design and scope: [docs/EXTENSIONS_SPEC.md](../docs/EXTENSIONS_SPEC.md) (this is milestone X0).
- Integrity is tamper-evidence (a SHA-256 over the files and the manifest), not a signature. The Worker sandbox is defence in depth, not a hard boundary; see "Security model" in EXTENSION_API.md.

## Selection actions

Whatever mode you are in (Select, Pick or Measure), selecting a feature or body brings up a bar above the prompt with the actions that apply to it. Right-click on the model, or long-press on a phone, opens the same list as a menu.

| Action | Key | Feature | Body |
|---|---|---|---|
| Move / Rotate | G / R | drags the feature's position or rotation | dragging adds a move or rotate step at the end of the body's history, or extends the last one |
| Duplicate | ⌘D | copy 5 mm along x | copy placed beside it, on the plate and clear of other bodies |
| Copy / Paste | ⌘C / ⌘V | pastes into the selected body | pastes as a new body |
| Hide | H | suppress | hide |
| Isolate | I | | solo |
| Lock | L | lock | lock |
| Frame | F | frames its body | frames the body |
| Delete | ⌫ | remove | remove the body's features (not for bodies made by a split) |

Every action is one undoable step. Locked items keep Hide, Lock and Frame, nothing else.

## Design loop

The Loop pane (next to Objects / Features / Params) runs `loops/enclosure-design.loop.json`, a Loop Definition v2 graph executed by the vendored loop engine:

```
start > designer (model: typed ops) > build (applyOps + kernel) > check (verifier) > critic (model: style score) > gateway
        gateway: every hard check passes AND (no brief OR score >= threshold)  -> approve (you) -> export (one undoable commit)
                 otherwise                                                      -> back to designer with the findings
```

- **Checks** (`src/checks.js`, plain code, no model): no failing features; at least one body; no interference between a body and a reference or keep-out part (ops of kind `ref`, such as the PCB) or between bodies; minimum wall thickness (an estimate: inward rays cast from sampled surface triangles, default threshold 1.2 mm, set in the pane); every body fits `settings.printVolume` (X/Y centred, Z from 0). A rejected or unparseable model reply is itself a failed round (`ops_valid`) and is fed back.
- **Budgets**: rounds, tokens and cost (plus the loop file's own ceilings and a 900 s wall clock). Hitting one ends the run `STOPPED_BUDGET`; nothing is applied. Token counts use provider-reported usage when available and a characters / 4 estimate otherwise (marked `~`). Cost is only counted when you enter $/M-token prices.
- **Human gate**: the loop pauses for your approval before applying. "Rework" sends your note back to the designer. Stop cancels the run.
- **Variants: N** runs N independent loops in parallel (own budget each, different design direction, no per-variant approval), lists them best first, and "Use" applies the one you pick as a single undo step. Cost scales with N.
- **Locks** are enforced in the ops layer (`applyOps`) and checked again before anything is applied; a locked item is never changed by the loop.
- The model only returns typed ops. Nothing leaves the browser except the call to the model endpoint you configured.

Tests: `npm test` runs the kernel tests, `test_loop.mjs`, `test_thermal.mjs` (the A1 package: model behaviour, vents, mounts, runner, AI tools, check severity) `test_voxel_core.mjs` (the voxel core: triangle/box test, conservative voxelizer, enclosed air, active cells, PCG, grid policy, correlations against A1, WGSL structure), `test_thermal3d.mjs` (the A2 package: physics, boundary conditions, energy balance, runner and Worker, cancel, checks, the harness's reference and skipped-case handling) `test_structural.mjs` (the A3 package: element matrices, the WGSL structure and its node-gather form against the CPU operator, supports and loads, stress recovery, runner and Worker, cancel, checks, the AI tool, the harness's declared-divergence handling) `test_airflow.mjs` (the A5 package: the unrolled lattice kernel against the reference loop, conservation, walls and boundaries, thermal lattice and conjugate conduction, WGSL structure, vents, fans, streamlines, runner and Worker, cancel, checks, the AI tool) `test_runner.mjs` (the A4 package against a real runner server in this process with its simulated solver and the real gmsh: consent, progress, cancel, failures, the surface mapping, validity rules, checks, AI tools, the reference-file guard) and `test_analysis.mjs` (the analysis framework: materials, ops, results, cache, runner on a real Worker, check gating, loop budget, harness) and `test_ext.mjs` (the extension host: manifest, zip and integrity, a permission matrix over every API call, real Workers, ops and locks, the removal and reinstall round trip) and `test_occt.mjs` (OCCT: template styles, scorecards, STEP round trip, document v3, tagging, loop on the B-rep path) (mocked model, real kernel: iteration, gateway routing, budgets, locks, critic, approval, cancel, variants, checks). `npm run test:ui` (needs Playwright and a browser, see the file header) is a browser smoke test of the pane with a mocked model endpoint; `npm run test:thermal3d-ui` is the browser test of A2 with software WebGPU (GPU against CPU on the core and on the package, settings, transient, cancel, phone sheet); `npm run test:ext-ui` is the browser smoke test of the extension UI (install, consent, package feature and check, removal and restore, sandboxed pane, overlay legend and probe, phone sheet).

`src/loop/` is vendored from `mbaliga/Shared-Libraries-asoc` `web/loop-engine` (source commit recorded in each file's header and in `src/loop/VENDORED.md`). Do not edit it here; `npm run vendor:loop -- <checkout>` re-vendors. The engine hashes the loop definition with `crypto.subtle`, which browsers expose on https and localhost only.

## Two kernels

`settings.kernel` (header selector) is `manifold` or `occt`.

- **Manifold** (default): watertight meshes, instant, for every classic feature type. No fillet or chamfer on existing edges, and curved surfaces export to STEP as facets.
- **OCCT** (Open CASCADE through [replicad](https://replicad.xyz)): true B-rep in a Web Worker, loaded only on first use (the ~22 MB wasm is fetched once and cached; the header shows a loading state). Gives lofts through filleted sections, exact booleans, exact distance queries, and a real B-rep STEP export (analytic cylinders and planes, not facets). It builds the classic primitives (box, cylinder, sphere, slot, hole, standoff, shell box, split, move/rotate), but not the mesh-only features (import, shear/twist/taper/bend, scale), which report a clear error and stay on Manifold.

## PCB enclosure (OCCT)

*Template menu > PCB enclosure* opens an invented demo board with an `enclosure` feature. The feature is generated from the document's `pcb` block (document v3; v1/v2 files migrate on load):

- `pcb.components[]`: keep-out boxes or cylinders with a clearance class, an `insert` side (`rear` slides in from the back, `bottom` passes through the floor), a cradle, an optional edge slot, a wall rule, and flags: `sensitive`/`emitter` (with a direction and beam radius), `exposed` contacts, a switch `paddle`.
- `pcb.cutouts[]` (windows, harness channels), `pcb.supports[]` (ribs and blocks), `pcb.keep[]` (regions kept from an imported original solid).
- `enclosure` fields: style (`monolith`, `bullet`, `cell` with a Voronoi texture), wall, clearance, rear cap and fastener (M2, M2.5, M3), texture density; the `spec` object holds the frame and station tables (ruled lofts of filleted trapezoid sections). Frame: x = length (front at -x), y = up, z = width.

**From your own STEP**: *PCB STEP* imports an assembly and lists its solids in the Objects pane. Tag each as board, component, keep-out, or keep original, then *generate keep-outs from boxes* (axis-aligned bounding boxes become `pcb.components`; an enclosure feature with a starter station table is added if there is none). Orient the assembly to the frame above first.

**Checks** (Check pane, and the design loop's check step whenever the kernel is OCCT): interference between each keep-out and the shell or cap (exact boolean volume), wall from each part to the outer skin (exact BRepExtrema distance, `clearance + extra`), emitter beam path clear, contacts exposed, switch travel free, rear insertion sweep clear, build volume in the print orientation (A1 mini 180 mm cube by default, `settings.printVolume`), plus volume, mass, envelope and overhang share (area of faces more than 45 degrees past the build direction, from the tessellation, so it is approximate). The result has the same shape as the reference checker's scorecard JSON. The sampled min-wall estimate of the mesh checks is not used on this path (distances are exact); a global min-wall metric is only reported when requested.

## Kernel limits

Manifold solids are watertight meshes, not NURBS B-reps (see above for the OCCT path). The OCCT path is slower: the template builds in about 3 s (monolith/bullet) and about 20 s (cell, 24 to 30 rounded cutters) in Node; in the browser the first build also pays the wasm download and compile. A wasm trap in Open CASCADE (rare, on degenerate parameters) marks the worker for restart and is reported as a failed feature with the stage name.

## Build and run

```
npm ci
npm run build    # writes dist/
npm test         # headless kernel + design-loop tests
npm run serve    # http://localhost:8765
```

Serve `dist/` over http(s); opening `index.html` from `file://` blocks the STEP reader's wasm fetch.

## Licence

Covered by the repository licence, [PolyForm Noncommercial 1.0.0](../LICENSE). Third-party components keep their own licences; see [THIRD_PARTY.md](THIRD_PARTY.md).


## Online deployment (GitHub Pages)
`.github/workflows/forge-pages.yml` builds `forge/dist` and publishes it to GitHub Pages on every push to `main` that touches `forge/`. A repository admin must enable it once: Settings, Pages, Source "GitHub Actions". The URL is `https://<owner>.github.io/<repo>/` (shown in the deploy job). The site is static and fully client-side: no backend, no telemetry; designs and API keys stay in the visitor's browser. Analyses run in the browser (CPU, or WebGPU where available); the optional Alloy Runner is a server the user configures themselves.
