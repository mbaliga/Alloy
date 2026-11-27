# Forge extensions and analysis spec

Status: plan, ready to build. Owner: Madhav Baliga. Extends roadmap milestone F6 and adds the analysis track (A1–A5).
Read with `docs/ALLOY_FAMILY.md`, `docs/FORGE_ROADMAP.md` and `docs/STYLE.md`.

## 1. Plugins and extensions: one system, two tiers

There is one package format, one manifest, one install flow and one host. **What a package asks for decides its tier**; authors never pick it.

| | Plugin | Extension |
|---|---|---|
| For | Feature types, templates, style packs, checks, exporters/importers, component libraries, AI agent tools, loop steps | Anything that brings its own engine or UI: solvers (wasm / WebGPU), viewport overlays, custom panes, a remote compute runner |
| Runs in | One dedicated Web Worker, no DOM | Its own Worker(s), plus sandboxed iframes for panes |
| May ask for | `doc.read`, `doc.ops`, `geometry.read`, `checks`, `agent.tools`, `loop`, `storage` | All plugin permissions, plus `ui.pane`, `scene.overlay`, `compute.gpu`, `compute.remote`, `network:<host>` |
| Install prompt | "Plugin: reads/edits your design" | "Extension: runs its own code on your GPU / talks to <host>" with each permission listed |

Rules that hold for both tiers:

- No network unless declared per host. `compute.remote` only ever reaches the runner URL the user typed in Settings.
- Geometry changes go through typed ops (`doc.ops`) like the AI does: validated, lock-checked, undoable. No package writes geometry directly.
- Removing a package never breaks a saved project (section 2.4).
- No telemetry. A package can't read API keys.

## 2. Extension host (milestone X0)

### 2.1 Package and manifest

A package is a `.alloyext` file (zip) with `alloy-extension.json` at its root.

```json
{
  "id": "xyz.mdhv.thermal-lumped",
  "name": "Thermal estimate",
  "version": "0.1.0",
  "forgeApi": "^1.0",
  "author": "Madhav Baliga",
  "licence": "PolyForm-Noncommercial-1.0.0",
  "main": "main.js",
  "permissions": ["doc.read", "geometry.read", "checks", "loop", "agent.tools"],
  "contributes": {
    "analyses": [{ "id": "thermal-lumped", "label": "Thermal estimate", "fields": ["T_air", "T_component"] }],
    "checks": [{ "id": "max-temp", "label": "Hottest component ≤ limit" }],
    "settings": { "ambient_C": { "type": "number", "default": 25 } }
  },
  "integrity": "sha256-…"
}
```

`contributes` kinds: `featureTypes`, `templates`, `stylePacks`, `checks`, `analyses`, `exporters`, `importers`, `libraries`, `agentTools`, `loopSteps`, `panes`, `overlays`, `settings`.

### 2.2 Host and API

- `forge/src/ext/`: `loader.js` (zip read, manifest validation against a JSON Schema, integrity check), `registry.js`, `host.js` (one Worker per package, RPC over `postMessage`, structured clone, transferables for big buffers), `api.js` (the permission-checked surface), `panes.js` (sandboxed iframes with `sandbox="allow-scripts"`, a message bridge, no `allow-same-origin`).
- API v1 (`forgeApi` 1.x), each call checked against the manifest permissions:
  - `doc.get()`, `doc.applyOps(ops)`, which reuses `applyOps` so locks and validation hold
  - `geometry.mesh(body)` (positions and indices), `geometry.brep(body)` (OCCT path: STEP bytes), `geometry.voxelize(body, h)` (shared voxelizer, section 4.1), `pcb.get()` (the F1 pcb block)
  - `checks.register(id, fn)`: rows join the scorecard and the design loop's check step
  - `analysis.register(id, { run, cancel })`: `run(input, progress)` returns a result (section 3.3)
  - `scene.overlay(result, opts)`: scalar field on the surface, voxel slice plane, isosurface, streamlines, glyphs, probes; colour map + legend in the house style
  - `ui.pane(id, url)`, `agent.tool(schema, fn)`: tools the in-app AI and the MCP server can call
  - `storage.project` (lives in the doc under `ext[id]`), `storage.user` (per browser)
- Every heavy call takes an `AbortSignal` and reports progress. The UI never blocks: solvers run in workers, and the viewport stays interactive.

### 2.3 UI

- Settings › **Extensions**: a list with tier badge, version, permissions, and enabled per project. Install from a file, a URL, or bundled via `alloy.config.json` (F7). Remove.
- An **Analyze** pane appears when any analysis is installed: pick the analysis, set loads and materials, Run, see a progress bar and the result summary. The overlay shows in the viewport, with a legend and probe on tap. Export a report (PNG + JSON + markdown).
- Analyses also appear in the selection bar's menu ("Analyze ›") for the selected body.
- Style: `docs/STYLE.md`. Glass panes, mono labels, explanations behind `i` tips, phone-first bottom sheets.

### 2.4 Document and removal

- `project.extensions: [{ id, version, enabled, settings }]`, and package data at `project.ext[id]`.
- Feature types from a package are saved with `ext: <id>`. If the package is missing, the feature is kept untouched, marked "needs <name>", and skipped in builds with a visible warning. Reinstalling restores it. Nothing is silently dropped.
- Analysis results are not saved in the doc. Only the setup is (loads, materials, mesh size), so files stay small. Results are cached per browser.

### X0 done

Status: built (PR for milestone X0). Deviations and deferred items are listed under each line.

- [x] Manifest schema, loader, registry, Worker host and RPC; iframe panes
  - Integrity covers the manifest and every file (so widening permissions is detected). It is a hash, not a signature.
  - Panes are HTML files inside the package (`contributes.panes[].file`), not URLs: a pane has no network, so there is nothing to fetch.
- [x] API v1 with permission checks, each permission covered by a test that a package without it is refused
  - Added `geometry.list` (a package needs to know which bodies exist) and `ui.pane.open` / `ui.pane.post` / `ui.pane.onCall` in place of a single `ui.pane(id, url)`.
  - `geometry.voxelize` is a CPU reference (4M voxels default, 8M hard cap); the GPU voxelizer arrives with A2 behind the same call.
  - `agent.tool` and `loop.step` register and can be called through the resolver (`agentTools`, `callAgentTool`, `loopSteps`, `callLoopStep`), but the in-app AI (op-JSON, no tool loop yet), the MCP server (F6) and the fixed design-loop graph do not call them yet.
- [x] Extensions settings UI, install/remove, per-project enable
- [x] Removal test: a project using a package's feature type and check opens, builds and saves without it, and is restored when it is reinstalled
- [x] Two sample packages: a plugin (a "rib" feature type + a check) and an extension (a pane + an overlay)
- [x] `docs/EXTENSION_API.md` for authors, generated from the API types where possible (from `src/ext/api-spec.js`)

## 3. Analysis framework (milestone A0, inside the host)

### 3.1 Materials

`analysis/materials.json` holds thermal conductivity k, density ρ, specific heat cp, emissivity ε, Young's modulus E, Poisson's ratio ν, yield/tensile strength and an FDM inter-layer knock-down, each with a source citation. Materials: PLA, PETG, ASA, ABS, PA12, PA-GF, PC, aluminium 6061, FR-4, copper, air. Users can add their own. Bodies get a material in the Objects pane.

### 3.2 Loads and conditions

- **Heat sources:** watts per pcb component (from the F1 pcb block), or per body.
- **Ambient:** temperature, gravity direction (orientation in use), still air or forced air speed.
- **Mounting:** contact faces with a contact conductance (thermal), fixed faces (structural). For the rail-mount family this is the clamp region.
- **Structural loads:** forces, pressures, body acceleration in g (recoil and drop become equivalent quasi-static g-loads, with the assumption shown).
- Inputs are SI internally. The UI shows mm, °C, W, N and g.

### 3.3 Result model

```ts
type AnalysisResult = {
  id: string; version: string; inputsHash: string;
  summary: { metric: string; value: number; unit: string; where?: [number, number, number] }[];
  fields: { name: string; unit: string; kind: 'surface' | 'voxel' | 'vector';
            data: Float32Array; grid?: { origin: number[]; h: number; dims: number[] } }[];
  warnings: string[]; validity: 'estimate' | 'validated' | 'experimental';
}
```

Every result carries `validity`. The UI shows it on the summary, never hidden. `experimental` results can't drive a pass/fail check.

### 3.4 Checks and the loop

Each analysis can expose checks with thresholds, for example "max component temperature ≤ 60 °C" or "safety factor ≥ 2 under 1000 g". They join the scorecard and the F4 design loop. The loop budget gets an `analysisSeconds` cap. The critic gets the summary rows, not the fields.

### 3.5 Validation suite

`forge/analysis-validation/` holds cases with analytic or reference answers and tolerances, run by `npm run test:analysis`. Every solver PR reports the table. Reference results from Elmer/OpenFOAM are stored as data files (small), produced once by the runner (A4).

### A0 done

Status: built on branch `forge-a0-analysis-framework` (PR open, not merged). Met items are ticked; deviations and additions are stated.

- [x] Materials file with sources; material assignment UI. `forge/analysis/materials.json` (schema 1): 11 materials, nine properties each, every value has a quality label (`datasheet`, `handbook`, `estimate`, `measured`, `n/a`), source ids that resolve to citations with a checked date, and a range where sources disagree. **Values are typical, not measured; many are `estimate`; the Incropera-based values are recalled, not re-checked against the book.** Users add their own (`set_material`). A body with no material is assumed PLA and every result says so. The assignment is a select per body in the Analyze pane (`set_body_material`, lock-checked), not in the Objects pane.
- [x] Loads/conditions editor in the Analyze pane, saved in the doc (`project.analysis`, document version unchanged). Heat sources by PCB part or body, ambient (°C, air speed, gravity direction), mounts (contact conductance or fixed) and loads (force, pressure, acceleration in g with the quasi-static assumption shown). Deviation: mounts and loads address a body face by **direction** (`+x`…`-z`) with an optional box in mm; there is no per-face picking in the viewport yet.
- [x] Result model, overlay renderer (surface map, slice, legend, probe), report export. Surface fields colour the body mesh per vertex; voxel fields draw as a slice plane with an axis select and a slider; the legend shows unit, gradient, ticks, voxel size and the 8 M cell limit; the probe reads the value under the pointer. Reports export as one zip (PNG of the viewport, JSON, markdown with validity, assumptions, units and sources). **Vector fields are validated but not drawn.** Additions to the contract: a surface field names its `body`; a check's limit is stored in the unit of its metric.
- [x] Check + loop integration with `validity` gating. Declared analysis checks join the scorecard; `experimental` rows are advisory and never gate; a missing or over-budget result is an advisory "not run" or "skipped" row, never a silent pass; the loop has an `analysisSeconds` cap (default 120) and the critic gets summary rows only. Analysis checks run only when the user runs the checks or in the loop.
- [x] Validation harness and table output. `npm run test:analysis` (`forge/analysis-validation/`): every case has a reference, one explicit tolerance and runs through the real runner and a package Worker; a miss exits 1; an analysis returning `validated` must pass all its cases. The sample package has 31 checks across 8 cases.

Not part of A0: a real solver (A1 onwards), GPU, the Runner. The sample package (`examples/ext/sample-mass`) exists to exercise the framework end to end.

## 4. The five analysis packages

Build in this order. Each is its own package, branch and PR, and each must pass its validation cases before merge.

### A1 · Thermal estimate (plugin) · easy

A thermal resistance network, solved in milliseconds:

- Component → board → internal air (natural convection inside, by Rayleigh-number correlation for an enclosed cavity).
- Air → wall by convection, then conduction through the wall.
- Wall → ambient: natural convection per face orientation (Churchill–Chu for vertical faces; upward/downward-facing plate correlations), plus linearised radiation.
- Vents: a stack-effect flow estimate from vent area and height difference, and the heat it carries.
- Mounting contact: conductance to a heat sink at ambient (the gun frame), optional.

Outputs: internal air temperature, each component's temperature, and the vent area needed to meet a target. The AI tool is `thermal_estimate(target_C)` → vent area suggestion.
Validation: a sealed box with a known answer from a heat-transfer textbook case (cite it), and agreement with A2 within 15% on three reference enclosures.
Validity: `estimate`.

### A1 done

Status: built on branch `forge-a1-thermal-estimate` (PR open, not merged). Package: `forge/examples/ext/thermal-estimate` (a plugin: `doc.read`, `geometry.read`, `storage`, `agent.tools`).

- [x] Thermal resistance network solved in milliseconds: component, board, internal air, six wall faces (inner and outer surface), ambient. Natural convection per face orientation (Churchill and Chu vertical; horizontal hot-up/hot-down; the gravity direction in the conditions says which face is up), forced air by mixing with the flat-plate correlation, linearised radiation outside and from board and components to the inner walls, stack-effect vents, contact conductance to a sink at ambient (a zero-conductance contact insulates a face). Deviation: inside the box the air-to-wall coefficients use the same plate correlations on the cavity's own dimensions, not an enclosed-cavity Rayleigh correlation, because the air is one mixed node rather than a hot wall facing a cold wall.
- [x] Outputs: internal air, board, each component, hottest part, hottest outer surface, vent flow and heat through vents, energy-balance error, and the **vent area needed** to bring the hottest internal part under a target (default 60 °C; `unreachable` is reported when vents alone cannot). A surface field of the wall temperature is drawn in the viewport.
- [x] AI tools: `thermal-estimate({ target_C })` returns the temperature now, the vent area installed and the area needed; `set-vents({ vents: [{ area_mm2, z_mm }] })` records the vents in the project (`ext[<id>].vents`, one undoable op). Deviation: vents are **not detected from the geometry** (there is no vent feature to read); they are entered through the AI tool or the project storage. The tool ids use hyphens because the manifest pattern requires them.
- [x] Checks: hottest part and outer surface (touch) limits, both editable; an `estimate` result gates the verdict.
- [x] Validation (`analysis-validation/cases/thermal-estimate.json`, 9 cases, 18 checks): the correlations against published Cengel/lecture-note examples with their stated inputs, the radiation term against Cengel Example 15-10, air properties against the quoted table values, the stack-flow formula against a hand calculation, the network solver against an exact two-resistor chain, the outer-surface heat rejection of the Cengel Example 15-10 box (radiation 0.3 %, convection 15 %, total 8 %. Observed: sides within 3 % of the book, top face 45 % higher, because the book uses a simplified correlation and a different characteristic length there; the aggregate tolerance is wide and is stated as such), and an energy-balance run through the real runner and Worker.
- [ ] **Partly met: "agreement with A2 within 15 % on three reference enclosures".** The three enclosures run both packages in `analysis-validation/cases/thermal-3d.json` (A2 step). They agree on one (4.6 %) and miss on two (18 % and 23 %): see "A2 done". Neither package is a measurement, so a miss says the two models differ, not which is right.
- [ ] **Not validated against a measurement or a 3D solution.** Whole-enclosure temperatures are an estimate; the textbook cases check the pieces, not the assembled network. In particular the component-to-board resistance is a crude FR-4 estimate and the internal natural convection and radiation use plate correlations in a closed box; expect errors of tens of percent on internal temperatures.

### A2 · Thermal 3D (extension, `compute.gpu`) · medium

- **Shared voxel core** (`analysis/voxel-core/`), also used by A3 and A5:
  - a conservative voxelizer from mesh (GPU, CPU fallback) with material IDs per voxel
  - a sparse active-cell list
  - a matrix-free preconditioned conjugate gradient solver (Jacobi first, geometric multigrid when needed)
  - WebGPU compute shaders, with a WASM/JS fallback for devices without WebGPU (slower, smaller grids)
- Steady and transient heat conduction (finite volume, implicit time steps), with volumetric sources from components.
- Boundary conditions: convection (h per face from A1's correlations), radiation (linearised, iterated), fixed temperature, contact conductance.
- Grid size: about 1–2 million active voxels on a laptop GPU, about 250k on a phone. Adaptive h with a "draft / normal / fine" setting. Show the voxel size.
- Outputs: temperature field (surface map + slice), hottest point, time-to-limit for transient runs.
- Validation:
  - 1D slab with convection (analytic)
  - long cylinder with a heat source (analytic)
  - fin efficiency (analytic)
  - a 3D enclosure compared with an Elmer reference within 5% max-temperature error at "fine"
- Validity: `validated` once the cases pass; otherwise `estimate`.

### A2 done

Status: built on branch `forge-a2-voxel-thermal`, stacked on `forge-a1-thermal-estimate` (A1 is an open PR, not on main). **Not claimed as validated: the result says `estimate`**, and two of the three A1-against-A2 comparison cases miss their 15 % tolerance. Met items are ticked; everything that is not met is listed with the number.

- [x] **Shared voxel core** (`forge/analysis/voxel-core/`, plain JS and WGSL, no dependency, runs in Node tests and in a package Worker; `tools/bundle-ext.mjs` concatenates it with `analysis/thermal-3d/package-main.js` into the package's single `main.js`, a test fails if the generated file is stale):
  - conservative mesh voxelizer: parity fill through the voxel centres plus every voxel a triangle passes through (separating-axis triangle/box test), per-voxel material ids, box and cylinder painters for PCB parts, enclosed-air detection by flood fill from the grid boundary, and the sparse active-cell list with 6-neighbour indices;
  - matrix-free Jacobi-preconditioned conjugate gradient on the active-cell list behind one backend interface: a JS typed-array CPU backend (a generator, run synchronously by tests and the harness, or asynchronously with progress and cancel between chunks in a Worker) and a WebGPU backend (WGSL: matvec with a fused dot product, on-device alpha and beta, one read-back per 20 iterations);
  - the grid-size policy and `gridBudget`: active-voxel budgets draft 150 000 / normal 600 000 / fine 1 500 000 on a laptop GPU, 40 000 / 120 000 / 250 000 on a phone and on the CPU fallback, hard limits 2 000 000 active and 8 000 000 cells, the voxel size chosen so the active count lands on the budget; the result, the warnings and the legend state voxel size, active count, budget and the limits;
  - the correlations of A1 copied into the core (the A2 package does not depend on A1), with a test that every function agrees with A1 exactly over a sweep.
- [x] **Package `examples/ext/thermal-3d`** (an extension: it asks for `compute.gpu`; also `doc.read`, `geometry.read`, `storage`, `agent.tools`). Steady and transient heat conduction, finite volume, implicit Euler; heat sources from PCB parts (components and board painted into the grid) and bodies; per-body material k, rho, cp and emissivity from `input.setup`; boundary conditions: natural/mixed convection per face orientation with A1's correlations (lagged or iterated with the face temperature), linearised radiation iterated, contact conductance to a sink (the Mounts of the Analyze pane; zero insulates), fixed-temperature faces (setting `fixed`, or the `set-thermal-3d` AI tool). Outputs: a temperature voxel field (slice overlay, outside air is NaN and drawn transparent), a per-vertex surface temperature on each body, the hottest point with its position, hottest component, hottest outer surface, heat out by convection/radiation/contact/fixed, energy-balance error, and for transient runs the peak over the run, the final maximum and the **time to limit** (interpolated within the step, reported only when reached). Checks (editable limits) on the hottest point and the outer surface join the scorecard as hard rows.
- [x] **Settings in the Analyze pane**: resolution (draft/normal/fine), steady or transient, duration, time step, limit, compute (auto/gpu/cpu). Analyses can now declare `settings` in the manifest; the pane draws them and stores them at `ext[<package id>].settings` through `ext_set` (saved, undoable). Package data is now part of the result cache key, and the pane drops a result when it changes. Advanced settings (`h_mm`, `convection`/`h_W_m2K`/`radiation`, `cavity`, `board_k_inplane`, `voxelizer`, `initial_C`, `fixed`) are written through the AI tool or the project data.
- [x] **WebGPU, executed.** Headless Chromium with `--enable-unsafe-webgpu --use-angle=swiftshader --enable-features=Vulkan` exposes `navigator.gpu` (also inside a Worker) here, on a **software** adapter. `test_ui_thermal3d.mjs` runs the GPU conjugate gradient against the CPU one on the same 24 000-cell system (largest difference 4.3e-5 of the peak, 100 iterations against 197, a second solve with a changed diagonal 3.9e-7), and then the whole package in its Worker with `backend: gpu` and with `backend: cpu` at the same voxel size: maximum temperature 47.283 °C on both. This says the shaders are correct and the Worker path works; it says nothing about speed on a real GPU (the software adapter took 1.7 s where the CPU took 0.08 s on that small grid, mostly shader compilation).
- [x] **Validation** (`analysis-validation/cases/thermal-3d.json`, 15 cases, every one with a reference and an explicit tolerance written before the run): plane wall with convection (analytic, exact to solver precision), plane wall with generation (0.002 K), long cylinder with a heat source (0.21 K, 0.44 % of the rise at the axis, tolerance 2 %), fin efficiency with a convective tip (0.007 %, tolerance 1 %), lumped capacitance heating (0.014 K, time to limit 0.006 %), transient plane wall against the exact series (0.14 K, tolerance 0.3 K), contact conductance with a source, iterated radiation, the Cengel Example 15-10 box with A1's correlations (1.15 K, tolerance 3.6 K), and the energy balance steady and transient through the real runner and a Worker. All pass.
- [x] **Reference-file loader and case format** for results produced by the Alloy Runner: `kind: "reference"` cases name a data file; a missing file is reported as `SKIPPED (not a pass): skipped: needs runner`, is counted in the summary line, is not a pass, does not fail the run and blocks a `validated` claim (tested in `test_thermal3d.mjs`). Format in `analysis-validation/README.md`.
- [ ] **Not met: "a 3D enclosure compared with an Elmer reference within 5 % max-temperature error at fine".** There is no Elmer here. The case `enclosure-vs-elmer-fine` exists and is **pending**: it is skipped until the Alloy Runner (A4) writes `analysis-validation/reference/enclosure-elmer.json`. **Consequence: A2 returns `estimate`, and `validated` is not claimed.**
- [ ] **Not met: A1 against A2 within 15 % of the max-temperature rise on three reference enclosures.** Hottest-component rise above ambient, A2 at normal on the CPU against A1 (cases `enclosure-1-small-abs`, `enclosure-2-handheld-pc`, `enclosure-3-aluminium-rail`): 52.0 K against 63.7 K (**18.3 % apart, miss**), 78.3 K against 82.1 K (4.6 %, pass), 126.7 K against 164.2 K (**22.8 % apart, miss**). The tolerances were not changed. Neither model is a measurement, so the miss says the two disagree, not which is right. What drives the difference, measured: the board (copper planes in the plane, 20 W/(m K), against FR-4 only: the small enclosure then runs 117 K above ambient), the enclosed-air model (effective conductivity; with conduction only, 88 K), and resolution (the small enclosure: 55.9 K at draft, 52.0 K at normal, 49.3 K at fine, so the gap to A1 grows with resolution; the 140 x 70 x 30 mm enclosure, which passes at normal, reads 74.5 K at 258 000 voxels and 65.0 K at 1.5 million against A1's 82.1 K, that is **21 % apart at the laptop-GPU fine grid**, so its pass at normal is not robust). The step is blocked on this item until the models are reconciled or the tolerance is changed by a decision, not by the author of the step.
- [x] Deviations and limits (honest list):
  - **Enclosed air is an effective-conductivity medium** (conduction times a Nusselt number of 0.2 Ra^(1/4), not below 1, plus a linearised radiation conductivity for the gap 4V/A), not a flow solution. Air connected to the outside through an opening at the voxel resolution is outside air, so small vents are not resolved at draft and normal.
  - **PCB parts are assumptions**: the board is anisotropic (through-thickness FR-4 0.29, in-plane 20 W/(m K), setting `board_k_inplane`), components a lumped block of k = 5 W/(m K); `pcb` coordinates are taken in the document frame, as A1 does. The hottest component temperature depends strongly on this.
  - **The conservative voxelizer thickens**: walls and curved surfaces grow by up to one voxel (the volume ratio to the mesh is reported in every result: 119 % at draft on the small enclosure). The film area of each body is scaled per axis to the true mesh area, so a curved body keeps its surface (this is what makes the cylinder case pass). `voxelizer: centre` gives the volume-preserving fill and loses thin walls.
  - **Uniform voxel size only.** "Adaptive h" is the choice of h from the budget (draft/normal/fine); there is no local refinement. **No geometric multigrid**: Jacobi-PCG needs 2 000 to 4 000 iterations on the enclosure grids; multigrid would help at fine and is not built.
  - **The voxelizer is CPU JS**, in the package Worker (also on the GPU path). `geometry.voxelize` of the host is unchanged. The CPU fallback is JS typed arrays, not WASM.
  - Transient boundary coefficients are lagged one step and the scheme is first order in time. Radiation sees only ambient (no view factors between outer surfaces); no stack-effect vents (A1's vent model is not carried over, vents come from geometry).
  - The surface field uses cell-centre temperatures near the surface, not the face temperature.
  - A real (non-software) GPU was not available here: the speed of the GPU path is not measured.
- Measured here (4 CPUs, Node 22, steady runs, CPU backend, the 60 x 40 x 25 mm enclosure): draft 40 810 active voxels at 1.145 mm in 1.9 s (2 250 CG iterations, 9 outer iterations); normal 124 032 at 0.794 mm in 7.7 s; fine 258 505 at 0.621 mm in 18.3 s. The 140 x 70 x 30 mm enclosure at fine: 258 419 voxels at 1.056 mm, 21.4 s. Voxelising and building the model takes 0.1 s at 250 000 cells and 0.3 s at the 1 522 664 cells of the laptop-GPU fine grid (enclosure boxes; one CPU stencil product at that size is 27 ms). The laptop-GPU fine grid solved on the CPU, for scale: 1 522 664 active voxels at 0.581 mm, 254 s, 5 786 CG iterations, energy balance 2.5e-9 W (about 0.04 s per iteration; this is what the CPU fallback would cost at a size it is not budgeted for). On the GPU the same grid has not been run: only a software adapter exists here, so the GPU time on a real device is not known.

### A3 · Structural (extension, `compute.gpu`) · medium

- Linear elasticity on the voxel core: 8-node hex elements, matrix-free PCG, the same solver infrastructure as A2.
- Supports: fixed faces (picked in the viewport, or from the template's clamp region), and slider/symmetry faces.
- Loads: forces, pressures, gravity, body acceleration in g (recoil/drop presets for rail-mount parts, with the equivalence assumption stated), bolt preload (simplified).
- Outputs:
  - displacement, von Mises stress, safety factor vs yield
  - an FDM knock-down applied across layers, based on the print orientation from the template
- Voxel stair-steps inflate stress at curved edges. Report stress smoothed over a 2-voxel neighbourhood, flag peaks on boundary voxels as "edge artefact likely", and recommend the runner (A4) for final numbers.
- Validation:
  - cantilever tip deflection (Euler–Bernoulli, <3% at fine)
  - plate with a hole (Kirsch stress concentration, within 10% after smoothing)
  - a bracket compared with an Elmer reference
- Validity: `estimate` for stress peaks, `validated` for displacement and stiffness once the cases pass.

### A4 · Alloy Runner (extension, `compute.remote`) + server · medium

For results an engineer will sign off on. The customer runs it on their own machine or server. Nothing goes anywhere else.

- **Server** (`runner/` in this repo): a small HTTP service in a container (Python FastAPI or Node). It takes a job (STEP or STL per body, a case JSON with materials, loads and BCs), meshes with gmsh, and runs:
  - Elmer for thermal and structural
  - OpenFOAM for flow (`simpleFoam`, `buoyantSimpleFoam`, `chtMultiRegionFoam` for conjugate heat transfer)

  It streams progress, returns results as VTK plus a summary JSON, and can cancel. Auth is a bearer token the user pastes into Forge. Jobs and files are deleted after download unless kept.
- **Licences:** Elmer (GPL/LGPL), OpenFOAM (GPL) and gmsh (GPL) run as separate processes the container installs. They are never linked into Forge. The runner's own code is under the repo licence. `runner/THIRD_PARTY.md` lists them.
- **Forge side:** the runner URL and token in Settings, a job list with progress and cancel, and VTK results imported into the same overlay renderer (unstructured fields mapped to the surface).
- **Validation:** the runner reproduces the A2/A3 reference cases, and it is what produces the stored reference data in section 3.5.
- **Validity:** `validated` (solver-grade), subject to mesh-independence. The runner runs two mesh sizes on request and reports the change.

### A5 · Airflow (extension, `compute.gpu`) · medium–hard, then hard

**Phase 1, forced flow:**
- Lattice Boltzmann D3Q19 on WebGPU, with Smagorinsky LES for stability at higher Reynolds numbers.
- Bounce-back walls on the voxel core; inlet velocity or fan curve; outlet pressure. The domain is the enclosure interior plus a margin outside the vents.
- Outputs: velocity magnitude, streamlines, flow rate per vent, dead zones.
- Validation:
  - lid-driven cavity (Ghia et al., Re 100 and 1000)
  - flow past a cylinder (Strouhal number)
  - one enclosure compared with OpenFOAM via A4
- Validity: `estimate` → `validated` for velocities once the cases pass.

**Phase 2, natural convection and conjugate heat:**
- Double-distribution thermal LBM with Boussinesq buoyancy, coupled to the A2 solid conduction.
- Validation: the de Vahl Davis differentially heated cavity (Ra 10³–10⁶, Nusselt within 2%), then an enclosure compared with OpenFOAM `chtMultiRegionFoam`.
- Ships as `experimental` until those pass, so it can't drive checks until then.

Grid and performance budget for both phases: 128³ lattice at interactive rate on a laptop GPU; 64³ on phones, with a warning.

## 5. Order and sizing

| Step | What | Tier | Difficulty | Rough size | Depends on |
|---|---|---|---|---|---|
| X0 | Extension host + API v1 + UI + samples | core | medium | 2–3 weeks | main |
| A0 | Materials, loads, results, overlays, checks, validation harness | core | medium | 1–2 weeks | X0 |
| A1 | Thermal estimate | plugin | easy | 3–5 days | A0 |
| A2 | Voxel core + thermal 3D | extension | medium | 3–4 weeks | A0 |
| A3 | Structural | extension | medium | 2–3 weeks | A2 (voxel core) |
| A4 | Alloy Runner + Forge client | extension + server | medium | 2–4 weeks | A0 (can run alongside A2/A3) |
| A5 | Airflow: phase 1, then phase 2 | extension | medium–hard → hard | 4–6 weeks, then 2–3 months | A2, A4 for references |

Sizes assume one developer with Claude Code. Validation is a large share of every analysis step and is not optional.

## 6. Out of scope for now

A public marketplace and signing service; paid packages; nonlinear materials, contact mechanics, fatigue, modal and drop-impact dynamics; EMI and RF. Each can become a package later without changes to the host.
