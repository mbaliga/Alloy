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

`analysis/materials.json` holds thermal conductivity k, density ρ, specific heat cp, emissivity ε, Young's modulus E, Poisson's ratio ν, yield/tensile strength and an FDM inter-layer knock-down, each with a source citation. Materials: PLA, PETG, ASA, ABS, PA12, PA-GF, PC, aluminium 6061, FR-4, copper, air (A6 adds steel, stainless 304, aluminium 7075 and brass, and an optional `durability` block; quality labels are datasheet, handbook, estimate, typical, measured). Users can add their own. Bodies get a material in the Objects pane.

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

Every result carries `validity`. The UI shows it on the summary, never hidden. `experimental` results can't drive a pass/fail check. A6 adds two optional members: `flags[]` (risk flags, section 4A, validated by `src/analysis/risk.js`) and `series[]` (`{ name, x, y, xs, ys }` curves such as preload against time).

### 3.4 Checks and the loop

Each analysis can expose checks with thresholds, for example "max component temperature ≤ 60 °C" or "safety factor ≥ 2 under 1000 g". They join the scorecard and the F4 design loop. The loop budget gets an `analysisSeconds` cap. The critic gets the summary rows, not the fields.

### 3.5 Validation suite

`forge/analysis-validation/` holds cases with analytic or reference answers and tolerances, run by `npm run test:analysis`. Every solver PR reports the table. Reference results from Elmer/OpenFOAM are stored as data files (small), produced once by the runner (A4).

### A0 done

Status: built on branch `forge-a0-analysis-framework` (PR open, not merged). Met items are ticked; deviations and additions are stated.

- [x] Materials file with sources; material assignment UI. `forge/analysis/materials.json` (schema 1): 11 materials, nine properties each, every value has a quality label (`datasheet`, `handbook`, `estimate`, `measured`, `n/a`), source ids that resolve to citations with a checked date, and a range where sources disagree. **Values are typical, not measured; many are `estimate`; the Incropera-based values are recalled, not re-checked against the book.** Users add their own (`set_material`). A body with no material is assumed PLA and every result says so. The assignment is a select per body in the Analyze pane (`set_body_material`, lock-checked), not in the Objects pane.
- [x] Loads/conditions editor in the Analyze pane, saved in the doc (`project.analysis`, document version unchanged). Heat sources by PCB part or body, ambient (°C, air speed, gravity direction), mounts (contact conductance or fixed) and loads (force, pressure, acceleration in g with the quasi-static assumption shown). Deviation: mounts and loads address a body face by **direction** (`+x`…`-z`) with an optional box in mm; there is no per-face picking in the viewport yet.
- [x] Result model, overlay renderer (surface map, slice, legend, probe), report export. Surface fields colour the body mesh per vertex; voxel fields draw as a slice plane with an axis select and a slider; the legend shows unit, gradient, ticks, voxel size and the 8 M cell limit; the probe reads the value under the pointer. Reports export as one zip (PNG of the viewport, JSON, markdown with validity, assumptions, units and sources). **Vector fields are drawn as arrows** (the `vectors` overlay kind, with legend by speed and a probe that prints the speed and the vector; see Layers below). Additions to the contract: a surface field names its `body`; a check's limit is stored in the unit of its metric.
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
- [ ] **Partly met: "agreement with A2 within 15 % on three reference enclosures".** The three enclosures run both packages in `analysis-validation/cases/thermal-3d.json` (A2 step). After the A2 cavity-model correction they agree on two (0.7 % and 6.8 %) and miss on one (20.8 %, documented as an expected divergence): see "A2 done". Neither package is a measurement, so a miss says the two models differ, not which is right.
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
- [ ] **Partly met: Validation** (`analysis-validation/cases/thermal-3d.json`, 16 cases, every one with a reference and an explicit tolerance written before the run). The harness reports **14 of 16 cases pass, 1 DIVERGES (documented, tolerance unchanged, not a pass), 1 SKIPPED (needs the runner, not a pass); 29 of 30 checks pass** (the run exits 0 because the divergence is declared; it is still not a pass and blocks `validated`). The 14 that pass: plane wall with convection (analytic, exact to solver precision), the same wall at voxel sizes 3 and 7 mm that do not divide it (exact, tolerance 1 %), plane wall with generation (0.002 K), long cylinder with a heat source (absolute temperature at the axis, 0.21 K = 0.44 % of the 47.7 K rise, tolerance 2 % of the rise), fin efficiency with a convective tip (0.007 %, tolerance 1 %), lumped capacitance heating (0.014 K, tolerance 0.15 K; time to limit 0.006 %), transient plane wall against the exact series (0.14 K, tolerance 0.3 K), contact conductance with a source, iterated radiation, the Cengel Example 15-10 box (1.15 K, tolerance 3.6 K; a plumbing check against a book value, **not independent evidence**, because the solver uses A1's own correlations), the energy balance steady and transient through the real runner and a Worker, and the A1-vs-A2 enclosures 1 and 3. The other two: `enclosure-2-handheld-pc` DIVERGES (see the next item), `enclosure-vs-elmer-fine` is SKIPPED. There is no sealed-slab analytic case for the enclosed-air model (the parallel-plate conduction and radiation terms are checked in `test_thermal3d.mjs` against their formulas, not in the harness).
- [x] **Reference-file loader and case format** for results produced by the Alloy Runner: `kind: "reference"` cases name a data file; a missing file is reported as `SKIPPED (not a pass): skipped: needs runner`, is counted in the summary line, is not a pass, does not fail the run and blocks a `validated` claim (tested in `test_thermal3d.mjs`). Format in `analysis-validation/README.md`.
- [ ] **Not met: "a 3D enclosure compared with an Elmer reference within 5 % max-temperature error at fine".** There is no Elmer here. The case `enclosure-vs-elmer-fine` exists and is **pending**: it is skipped until the Alloy Runner (A4) writes `analysis-validation/reference/enclosure-elmer.json`. **Consequence: A2 returns `estimate`, and `validated` is not claimed.**
- [ ] **Not met: A1 against A2 within 15 % of the max-temperature rise on three reference enclosures (two of three).** Hottest-component rise above ambient, A2 at normal on the CPU against A1 (cases `enclosure-1-small-abs`, `enclosure-2-handheld-pc`, `enclosure-3-aluminium-rail`), after the enclosed-air correction below: 63.2 K against 63.7 K (0.7 %, pass), **99.2 K against 82.1 K (20.8 % apart, miss)**, 153.0 K against 164.2 K (6.8 %, pass). Before the correction the picture was 52.0 / 63.7 (18.3 %, miss), 78.3 / 82.1 (4.6 %, pass), 126.7 / 164.2 (22.8 %, miss): the enclosed-air model moved the results by 10 to 20 K in both directions, which says the comparison is mostly a comparison of two cavity models. The tolerances were not changed. `enclosure-2-handheld-pc` carries `expectedDivergence` in the case file: the harness prints it as `DIVERGES (documented expected divergence, NOT a pass)` and the run does not exit 1 for it, but it is not counted as a pass and it blocks `validated`. The two passes are not robust either: the small enclosure reads 67.2 K at draft, 63.2 K at normal and 59.9 K at fine (still falling with resolution), so its agreement with A1 at normal is partly luck of the grid. Neither model is a measurement, so a miss says the two disagree, not which is right. The Elmer reference (A4) is the arbiter.
- [x] Deviations and limits (honest list):
  - **Enclosed air is an effective-conductivity medium** (conduction times a Nusselt number of 0.2 Ra^(1/4), not below 1, plus a linearised radiation conductivity), not a flow solution. The gap length is 2V/A (the true gap of a slab-shaped cavity; an earlier 4V/A overstated it about 2x, and with it the Nusselt and radiation terms), the Rayleigh temperature difference is between the mean cavity-facing temperature of the internal parts (the walls when there are none) and of the walls, and the grey exchange uses the enclosure formula with the area ratio of part to wall (a small part in a big box radiates with its own emissivity). For a cube-shaped cavity 2V/A is only a third of the side, so the gap is an estimate there. Air connected to the outside through an opening at the voxel resolution is outside air, so small vents are not resolved at draft and normal.
  - **PCB parts are assumptions**: the board is anisotropic (through-thickness FR-4 0.29, in-plane 20 W/(m K), setting `board_k_inplane`), components a lumped block of k = 5 W/(m K); `pcb` coordinates are taken in the document frame, as A1 does. The hottest component temperature depends strongly on this.
  - **The conservative voxelizer thickens**: walls and curved surfaces grow by up to one voxel (the volume ratio to the mesh is reported in every result: 119 % at draft on the small enclosure). The film area of each body is scaled per axis to the true mesh area, so a curved body keeps its surface (this is what makes the cylinder case pass). `voxelizer: centre` gives the volume-preserving fill and loses thin walls.
  - **Staircase correction of the conduction path.** When h does not divide a rectilinear body (every triangle normal on an axis) that touches no enclosed air, the face conductances and the half-cell resistances of exposed faces are scaled per axis by (V / V_voxel)(E_voxel / E)^2, consistent with the film-area scaling, so a bar at h = 3 or 7 mm keeps its analytic heat flow (uncorrected error 2.5 % and 4.4 %; test and case `slab-nondividing-h`). Curved or tilted bodies and bodies around a cavity are not corrected (the cylinder case shows the prism argument made it worse, so it is not applied there); their conduction path carries the staircase error and the thin-wall volume warning is the only guard.
  - **Body order does not matter.** Every body's interior is painted first and the conservative (surface-touching) passes run afterwards into empty voxels, so a thin body on top of another keeps its voxels. A body so thin or flat that its bounding box exceeds the grid limit at any voxel size is refused after 8 coarsening passes with a clear error instead of hanging.
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

### A3 done

Status: built on branch `forge-a3-structural`, stacked on `forge-layers` (not merged, no PR opened). Package: `forge/examples/ext/structural` (an extension: `compute.gpu`, `doc.read`, `geometry.read`, `storage`, `agent.tools`). **Not claimed as validated: the result says `estimate`.** Met items are ticked; everything that is not met is listed with the number.

- [x] **Linear elasticity on the voxel core** (`analysis/voxel-core/elastic.js`, `gpu-elastic.js`, `analysis/structural/package-main.js`; `tools/bundle-ext.mjs` builds the package's single `main.js` from `grid, cg, elastic, gpu-elastic` and the package source, a test fails if it is stale; the A2 bundle is unchanged). One 8-node hexahedron per solid voxel, **trilinear plus the nine incompatible (bubble) modes condensed out**: for a cube it passes the patch test and does not lock in bending, which a voxel beam a few elements deep needs (a 4-element-deep cantilever is within 0.03 % of Euler-Bernoulli). The stiffness of a cube is E h K(nu), one constant 24 x 24 matrix per Poisson ratio, applied matrix-free (element scatter on the CPU; **node gather in WGSL, one thread per dof, no atomics**, the whole CG iteration on the device). Jacobi-preconditioned CG, one generator driven synchronously by tests or asynchronously with progress and cancel in the Worker. WebGPU first (when allowed and the grid is big enough to benefit), CPU fallback with the smaller CPU budgets, same voxel-size policy and limits as A2 (draft / normal / fine: 150 000 / 600 000 / 1 500 000 solid voxels on a laptop GPU, 40 000 / 120 000 / 250 000 on a phone and on the CPU; hard limits 2 000 000 active and 8 000 000 cells); the budget counts solid voxels, not enclosed air. Every result states the voxel size, the solid count, the budget, the limits, the nodes and the dofs (summary rows, a warning line, and the legend). A voxel size chosen from the budget is **snapped so the thinnest dimension of the model is a whole number of voxels** (found when the first run of the cantilever at fine read 5.8 % low because 10 mm / 0.3175 mm is not an integer and the conservative voxelizer thickened the beam); a size given by the user is kept as it is.
- [x] **Supports**: `fixed` (all three displacements at the nodes of the selected faces) and the new `slider` mount kind (only the displacement normal to the face: a symmetry plane or a frictionless support; added to the conditions, the Analyze pane, the report and the AI op help). The six rigid-body motions of every connected piece are checked against the held dofs before the solve, and an unsupported model is refused with a message that says what to add. Faces are picked by **direction on a body's bounding-box face, or inside a box (mm)**, like the thermal mounts; loads on held nodes go straight into the support and still count in the equilibrium check.
- [ ] **Not met: "fixed faces picked in the viewport, or from the template's clamp region".** There is no per-face picking in the viewport (the A0 deviation stands) and no template exposes a clamp region yet, so neither route exists.
- [x] **Loads**: force on a face (spread over its nodes), pressure (positive pushes into the face), acceleration in g (body force rho a toward the stated direction, as for gravity; density scaled per body so its voxelised mass equals mesh volume times rho), self weight (setting, toward the gravity direction of the Conditions, off by default), the **drop and recoil presets** (equivalent quasi-static acceleration: drop `a = g H / s`, recoil a constant g you choose; the equivalence assumption is printed in every result that uses a preset and in the settings tips), and a **simplified bolt preload** (two equal and opposite compressive discs on the outermost faces around a point; through the settings or the AI tool, no form in the pane). Deviation: the presets are generic, not "for rail-mount parts" (no rail-mount template exists), and the recoil default of 1 000 g is a placeholder that says so. Parts on a PCB have no stiffness and no mass here (said in the result).
- [x] **Outputs**: largest displacement, stiffness (|F|^2 / f.u), von Mises stress, the stress without smoothing, the safety factor against yield (against the tensile strength, said, when a material has no yield), strain energy, applied and reaction force and the equilibrium error, as summary rows; **surface fields per body**: `von_mises:<body>`, `displacement:<body>`, `safety_factor:<body>` (capped at 10 for drawing), drawn by the existing overlay renderer and listed in the Layers sheet (`analysis:<field name>`). No `vector` or `voxel` field: the spec does not ask for one. Checks join the scorecard as hard rows with editable limits: safety factor `>=` 2 and largest displacement `<=` 1 mm.
- [~] **FDM knock-down "based on the print orientation from the template"**: partly. The document has no print orientation, so the print axis is a **setting** (`print_axis`, default z). Tension along it (the layers pulled apart) is divided by the material's inter-layer factor before the safety factor is formed (`SF = strength / max(von Mises, tension_along_axis / factor)`); stiffness is not reduced (isotropic). The definition is exact in the test (`fdm-knockdown`), which checks the code and not the factor: the factors in `materials.json` are `estimate`.
- [x] **Edge artefacts**: stress is computed at the element centres and **recovered at the nodes by a local linear least-squares fit over the elements of the same body within 2 voxels**; the summary also gives the unsmoothed peak; a peak on a surface node is flagged "Edge artefact likely" (row `peak_on_boundary` and a warning that recommends the Alloy Runner for final numbers). How the smoothing got its form, because it decides the Kirsch number: the first version averaged the element tensors in a 2-voxel ball and read **23.7 % low** on a plate with a hole (a one-sided window at a free surface is biased when the stress has a gradient); it was replaced by the least-squares fit before any case file was written, which reads the surface value without that bias.
- [~] **Validation** (`analysis-validation/cases/structural.json`, 12 cases, every one with a reference and an explicit tolerance written before the run, plus `slow/structural-fine.json`). The harness reports **10 of 12 cases pass, 1 DIVERGES (declared, tolerance unchanged, not a pass), 1 SKIPPED (needs the runner, not a pass); 25 of 26 asserted checks pass**. Table at the end of this block.
  - [x] **Cantilever tip deflection, Euler-Bernoulli, within 3 %**: 0.0103125 mm against 0.010240 mm (**0.71 %**) and stiffness 974.0 against 976.6 N/mm (0.26 %) at a voxel size of 1 mm in the default run; at 2.5 mm (4 voxels through the depth) 0.03 %; a half model with a slider on the symmetry plane 0.19 %. **At the quality fine (the spec's wording)** the run takes minutes on the CPU, so it is the on-demand case `slow/structural-fine.json` (`npm run test:analysis:slow`): run here on 2026-11-27: 238 328 solid voxels of 0.3226 mm (the 10 mm depth is exactly 31 voxels), 1 120 CG iterations, 217 s; deflection at the free end 0.010273 mm against 0.010240 mm (**0.32 %**), largest displacement 0.010322 mm (**0.80 %**), both within the 3 % of the spec. It is evidence only for the run that was made; the default run does not repeat it.
  - [~] **Plate with a hole, Kirsch, within 10 % after smoothing**: **met with the volume-preserving voxel fill, missed with the default.** Reference: 3 sigma (Kirsch) for an infinite plate; for the finite plate (d/W 0.25) Kt = 3.2298 from the Peterson/Roark polynomial (recalled, not re-checked against the book) = 322.98 MPa for 100 MPa. A quarter plate with sliders on the three symmetry planes, hole radius 3 mm, voxel 0.25 mm (a/h = 12): `voxelizer: centre` gives 298.6 MPa (**7.54 % low, within 10 %**); the **default conservative voxelizer gives 281.3 MPa (12.9 % low, outside 10 %)** because it shrinks the hole by up to a voxel and leaves a rougher staircase. The second case carries a declared expected divergence in the case file; the tolerance is not changed and it is not a pass. So the package default does not meet this item at this resolution; the user can choose `voxelizer: centre` (volume preserving, but a wall thinner than a voxel can vanish).
  - [ ] **Not met: "a bracket compared with an Elmer reference".** There is no Elmer here. `bracket-vs-elmer` is **SKIPPED (not a pass)** until the Alloy Runner (A4) writes `analysis-validation/reference/bracket-elmer.json`. The spec gives no tolerance for it; 5 % on displacement and stiffness and 25 % on the stress peak are this repository's choice, fixed in the case file before any reference exists.
  - Additional cases (not in the spec, all pass): a bar in uniform tension on one octant with sliders (stress and elongation exact to solver precision, 0.001 % and 0.00 %), a cantilever under an acceleration load (0.67 % against the uniform-load formula, applied force exact), the drop preset and self weight (0.31 % each), the FDM knock-down definition (exact), the bolt preload (a uniform preload gives sigma_zz = -2.5 MPa exactly; a disc preload carries 1 000 N through the mid plane, nodal and element-centre sums). **A first version of the disc-preload case used a fixed wall and read 7.9 % low because the wall carried local reactions of both signs; the case was redesigned with sliders, its tolerance was not changed, and the reason is written in the case.**
- [ ] **Not met: "`validated` for displacement and stiffness once the cases pass".** One result has one validity. The Elmer bracket case has not passed (it has not run), so **A3 returns `estimate` for everything, displacement and stiffness included**, and says so in every result. The spec's split (estimate for stress peaks, validated for displacement) is not implemented: it would need per-metric validity in the result model.
- [x] **AI tool** `set-structural` (quality, backend, print axis, self weight, knock-down, preset and its numbers, direction, voxelizer, fixed voxel size, smoothing radius, bolt preloads; validated, stored in the project, undoable through `ext_set`); supports and loads go through the existing `set_condition` ops. **Settings in the Analyze pane**: resolution, compute, print axis, self weight, load preset (its numbers appear when chosen), preset direction.
- [x] **WebGPU, executed** (headless Chromium with the software adapter, `test_ui_structural.mjs`): the elastic CG on the GPU against the CPU on the same 636-element, two-material system with a hole, largest difference 5.2e-5 of the peak (150 against 181 iterations); the whole package in its Worker with `backend: gpu` and with `backend: cpu` at the same voxel size on the starter design: largest displacement 0.012922 against 0.012923 mm, von Mises 0.29019 against 0.29022 MPa. This says the shaders and the Worker path are correct; it says nothing about speed on a real GPU (the software adapter took 16 s where the CPU took 1.5 s on that small grid).
- [x] **Phone viewport test**: 390 px, touch, mobile user agent: no sideways scroll (page, sheet, with a result and with the Layers sheet open), touch targets of at least 36 px, the grid and its limits in the result and the legend, the Layers sheet lists the stress and displacement layers.
- [x] **Known limits (honest list)**:
  - **Validity is `estimate`, and stress is the weak part**: voxel stair-steps inflate stress at curved edges and corners, and the voxelizer thickens thin walls by up to one voxel (stiffness and weight overstated; the volume ratio is in every result). A sharp feature smaller than a few voxels is under-resolved. Use it to compare designs and catch gross errors; the Runner (A4) is for final numbers.
  - **Bodies that touch are bonded** where their voxels touch (no contact, no friction, no gap). Bolts are not modelled except as the preload discs.
  - **Linear only**: small displacements, one load case per run, isotropic stiffness (also for printed parts), no buckling, plasticity, fatigue, creep, temperature, vibration or drop dynamics (the presets are quasi-static).
  - **Jacobi-PCG, no multigrid.** Iterations grow with the aspect ratio (about 1 100 for the 80 x 10 x 10 mm cantilever at 250 000 voxels, 0.19 s each on this CPU, 3.5 minutes). A warm start from a coarse solution was tried and did not reduce the iterations (731 against 667), so it was not kept. The GPU path is not timed on a real GPU.
  - **Nearly incompressible materials** (Poisson ratio above 0.45) lock volumetrically; a warning says so.
  - The conservative voxelizer is the default (a wall is never lost); `voxelizer: centre` is volume preserving and is more accurate on solid parts with holes (see Kirsch).
  - Measured here (4 CPUs, Node 22, CPU backend): the 80 x 10 x 10 cantilever 8 000 elements 344 iterations 2.5 s, 64 000 elements 687 iterations 43 s, 238 328 elements (fine) 1 120 iterations 217 s; the Kirsch quarter plate 22 592 elements 738 iterations 14 s.

Validation table of A3 (`npm run test:analysis`, 2026-11-27; the reference column is in the case files):

| Case | Metric | Reference value | Computed | Error | Tolerance | Result |
|---|---|---|---|---|---|---|
| cantilever-tip-deflection | max_displacement | 0.01024 mm | 0.0103125 mm | 0.708 % | 3.00 % | PASS |
| cantilever-tip-deflection | stiffness | 976.563 N/mm | 974.026 N/mm | 0.260 % | 3.00 % | PASS |
| cantilever-tip-deflection | reaction_force | 10 N | 10 N | 3.35e-8 % | 0.000100 % | PASS |
| cantilever-tip-deflection | equilibrium_error | 0 N | 3.55787e-9 N | 3.56e-9 N | 0.0001 N | PASS |
| cantilever-coarse-h2 | tip deflection at the free end | -0.01024 mm | -0.0102368 mm | 0.0315 % | 3.00 % | PASS |
| cantilever-half-model-slider | tip deflection at the free end | -0.01024 mm | -0.0102599 mm | 0.194 % | 3.00 % | PASS |
| axial-bar-sliders | elongation of the bar | 0.04 mm | 0.0399995 mm | 0.00125 % | 0.100 % | PASS |
| axial-bar-sliders | stress sigma_xx (recovered) mid-length | 100 MPa | 100 MPa | 0.00 % | 0.100 % | PASS |
| axial-bar-sliders | von Mises stress, largest | 100 MPa | 100 MPa | 0.00 % | 0.200 % | PASS |
| axial-bar-sliders | equilibrium error | 0 N | 0.00000635802 N | 0.00000636 N | 0.0001 N | PASS |
| axial-bar-sliders | reaction on the sliders against the applied force | 10000 N | 10000 N | 7.02e-9 % | 0.000100 % | PASS |
| fdm-knockdown | safety factor, print axis z | 1.5 | 1.5 | 0.00 % | 0.100 % | PASS |
| fdm-knockdown | safety factor, print axis x | 2.5 | 2.5 | 0.00 % | 0.100 % | PASS |
| fdm-knockdown | safety factor, knock-down off | 2.5 | 2.5 | 0.00 % | 0.100 % | PASS |
| cantilever-acceleration-load | max_displacement | 0.0234983 mm | 0.0236547 mm | 0.666 % | 3.00 % | PASS |
| cantilever-acceleration-load | applied_force | 61.1935 N | 61.1935 N | 2.62e-12 % | 1.00e-7 % | PASS |
| drop-preset-and-self-weight | tip deflection, drop preset | -0.117492 mm | -0.117855 mm | 0.309 % | 3.00 % | PASS |
| drop-preset-and-self-weight | tip deflection, self weight | -0.000234983 mm | -0.000235709 mm | 0.309 % | 3.00 % | PASS |
| bolt-preload-uniform | sigma_zz mid-block | -2.5 MPa | -2.5 MPa | 0.00 % | 0.100 % | PASS |
| bolt-preload-uniform | sigma_xx mid-block | 0 MPa | 1.14288e-10 MPa | 1.14e-10 MPa | 0.001 MPa | PASS |
| bolt-preload-uniform | net applied force | 0 N | 0 N | 0.00 N | 1e-9 N | PASS |
| bolt-preload-disc-equilibrium | force through the mid plane (nodal forces of the part above) | 1000 N | 1000 N | 1.17e-8 % | 0.100 % | PASS |
| bolt-preload-disc-equilibrium | force through the mid slab (sum of element-centre sigma_zz h^2) | -1000 N | -1000 N | 9.54e-8 % | 3.00 % | PASS |
| plate-hole-kirsch | peak von Mises stress at the hole, recovered over 2 voxels | 322.979 MPa | 298.621 MPa | 7.54 % | 10.0 % | PASS |
| plate-hole-kirsch | stress far from the hole (sigma_xx) | 100 MPa | 100.254 MPa | 0.254 % | 3.00 % | PASS |
| plate-hole-kirsch-default-voxelizer | peak von Mises stress at the hole, recovered over 2 voxels | 322.979 MPa | 281.259 MPa | 12.9 % | 10.0 % | DIVERGES (declared, not a pass) |
| bracket-vs-elmer | (all) | - | - | - | - | SKIPPED (needs the runner, not a pass) |
| cantilever-fine (on demand, `npm run test:analysis:slow`) | tip deflection at the free end | -0.01024 mm | -0.010273 mm | 0.323 % | 3.00 % | PASS |
| cantilever-fine (on demand) | largest displacement | 0.01024 mm | 0.0103217 mm | 0.798 % | 3.00 % | PASS |

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

### A4 done

Status: built on branch `forge-a4-runner`, stacked on `forge-a3-structural` (not merged, no PR opened). Two parts: the **Alloy Runner** (`runner/`, Node, no dependencies, container files) and the **Forge package** `xyz.mdhv.runner` (`forge/examples/ext/runner`, an extension that asks for `compute.remote` and no network host; two analyses, `runner-structural` and `runner-thermal`). **Elmer was not available in the build sandbox (no Ubuntu package, and installing it from outside the distribution archive was not done), so no solver result in this step is from Elmer, no reference file exists, and nothing is claimed as validated.** gmsh 4.12 (Ubuntu archive) was installed and is exercised for real. Met items are ticked; everything not met is listed with the number.

- [x] **Server in `runner/`**: a small HTTP service (Node standard library only; protocol 1 in `runner/README.md`). `POST /v1/jobs` takes bodies, a case (materials in SI, mounts, loads, heat, ambient) and options; `GET /v1/jobs/:id?since=N` returns state, stage, progress and numbered events; cancel kills the child process; `result` and `files/result.vtu` download the answer; `DELETE`. States queued, running, done, failed, cancelled; one job at a time by default; a time limit per job; size, body, triangle and tetrahedron limits; every field of a request is validated and a rejection names the field (closed, outward-wound STL required; an open or inside-out STL is refused before meshing). **Deviation: progress is polled with a cursor, not streamed** (no SSE), which is simpler through proxies and works with the host's request API.
- [~] **Input "STEP or STL per body"**: **STL only.** `format: "step"` is in the schema and refused with a message. The Forge package always sends STL (`geometry.mesh`); sending STEP through `geometry.brep` was not built.
- [x] **Meshing with gmsh**, run as a separate process on the real gmsh: STL surface to first-order tetrahedra per body. Two strategies, because the first one failed on the product's own starter enclosure: the surface is re-meshed at the element size; when gmsh cannot (needle triangles from boolean operations: "Poor input mesh quality" and a PLC error on the Base shell), the STL triangles are kept as the boundary mesh and the result says so. A strategy counts only when its tetrahedra fill the STL volume to 5 % (a box fills 4800 mm3 to 1e-12). Mesh sizes: automatic from the geometry and the tetrahedron limit, or set.
- [~] **Elmer for thermal and structural**: **written and tested only as text and against a stub, never run.** The adapter finds the boundary faces, attaches mounts (fixed, slider, contact) and loads (force, pressure as a traction into the face, acceleration and self weight as body force, heat as W/kg), writes the Elmer mesh files (metres) and a `.sif` (linear elasticity with stress calculation, heat equation with Robin conditions), runs `ElmerSolver`, reads its VTU (field-name variants, separate components, a stress tensor instead of von Mises), converts to mm / MPa / degrees C and computes the safety factor, stiffness (|F|^2/F.u over the loaded faces) and, for thermal, the energy balance over the convective faces. The `.sif` keywords come from the Elmer documentation and **have not been accepted by a real ElmerSolver**; the tests use a stub script that writes a VTU in the expected dialect, and say so. Expect to fix keywords at the first real run.
- [ ] **Not met: OpenFOAM** (`simpleFoam`, `buoyantSimpleFoam`, `chtMultiRegionFoam`). Flow cases arrive with A5, which owns the cases they must pass; `/v1/health` reports the adapter as "not implemented" and a flow job is refused. *(Update, A5: the `simpleFoam` case writer and adapter now exist, never run against OpenFOAM; `chtMultiRegionFoam` does not. See "A5 done".)*
- [x] **Auth, retention, container files.** Bearer token required (the service refuses to start without one; `ALLOY_RUNNER_ALLOW_NO_AUTH` is honoured only on loopback), compared in constant time; `/v1/health` is open but minimal. CORS only for listed origins, including the private-network preflight. Jobs and files are deleted after the last download unless `keep`, otherwise after a TTL; the STL is dropped from memory and disk when the solve ends; a finished job leaves only `result.json` and `result.vtu`. A test reads the runner source for any outbound client (it has none). `Dockerfile` (gmsh and the Elmer CSC PPA on Ubuntu 24.04, Node copied from the official image, non-root), `docker-compose.yml` (loopback port, read-only root, no capabilities). **The container image has not been built: there is no Docker daemon in the sandbox.**
- [x] **Licences.** gmsh (GPL), Elmer (GPL/LGPL) and OpenFOAM (GPL, later) are installed in the container and started as separate processes; nothing is linked into Forge or copied into the repository; `runner/THIRD_PARTY.md` and `forge/THIRD_PARTY.md` say so. The runner's own code is under the repository licence.
- [x] **Forge side**: runner URL and token in Settings (existing) plus a **test** button (what the runner has installed, whether the token is accepted); the package's two analyses with settings (solver, mesh size, mesh study, keep, self weight, air film coefficient), checks on the scorecard (safety factor >= 2, displacement <= 1 mm, hottest point <= 85 C) and two AI tools (`set-runner` settings only, `runner-status` health only); a **job list** in the Analyze pane (state, progress, Cancel, Delete) that sends no geometry; progress and Cancel through the normal Run button (Cancel tells the runner, which kills the solve, and the job is deleted); every failure explained (no runner set, unreachable with a CORS hint, token refused before anything is sent, a job the runner refuses, a solver failure with Elmer's message).
- [x] **Consent before any geometry leaves the browser, only on an explicit user action.** `compute.remote.fetch` with a request body is refused with `E_CONSENT` unless the host's `remoteConsent` hook says yes for that very request; the Analyze pane's hook shows a dialog (the runner address, each body and its triangle count, the size in MB, what is sent, what is not sent, that whoever runs the server can see it) and answers yes only for **Send and run**. The hook answers no when the run did not come from the Run button, so an AI tool, the design loop or a scorecard check cannot upload; a cached result uploads nothing and asks nothing. Requests without a body (health, polling, cancel, download) need no consent. Tests: the API refuses without a hook, with a refusing or throwing hook, and a package run with consent denied makes no `POST` (headless); in Chromium nothing but `GET` reaches the runner before the dialog is answered, Cancel sends nothing, Send uploads exactly once (`test_ui_runner.mjs`). The token is added by the host and never seen by the package or sent in a body; the AI key is never involved.
- [x] **Unstructured fields on the surface.** The runner's VTU (tetrahedra, `body` cell field, nodal fields) is parsed in the package Worker and interpolated at every surface vertex of each body (barycentric in the best tetrahedron of that body, negative weights clamped), giving `runner_von_mises`, `runner_displacement`, `runner_safety_factor` and `runner_temperature` as surface fields that the existing overlay renderer, legend, probe and Layers sheet draw. The mapping reproduces linear fields exactly in a unit test, and runs on the starter enclosure end to end. One parser serves both sides: `runner/src/lib/vtu.js` and `stl.js` are concatenated into the package by `tools/bundle-ext.mjs` (a test fails if the bundle is stale).
- [~] **Validity "validated (solver-grade), subject to mesh-independence" and "two mesh sizes on request, reporting the change"**: the mesh study is built (a second solve at 1.5 times finer; the change of displacement, stiffness and von Mises is reported as `mesh_change_*` rows; tolerances 2 %, 2 %, 10 %). **`validated` is gated and not reachable in this repository**: it needs a real solver, a converged study **and** a qualification file written by the runner's operator attesting that it reproduced the reference cases (a statement by that person, shown in the result). Without the file the grade is `estimate`; a result from the simulated solver is `experimental` and labelled SIMULATED, so it never decides a check.
- [ ] **Not met: "the runner reproduces the A2/A3 reference cases, and is what produces the stored reference data".** `analysis-validation/reference/bracket-elmer.json` and `enclosure-elmer.json` **do not exist**; both cases stay **SKIPPED (not a pass)** and A2 and A3 stay `estimate`. What was built is the way to produce them: `forge/tools/make-reference.mjs` (`npm run reference:make`) sends the bracket case to a runner and writes the file **only** when the adapter is `elmer`, a mesh study ran and converged; it refuses (and writes nothing) for the simulated solver, an unconverged study or a runner without a working Elmer, which is tested. It supports the structural bracket only: the enclosure case has PCB heat sources, which the runner protocol does not carry.
- [x] **Phone viewport test** (`test_ui_runner.mjs`, 390 px, touch, mobile user agent): no sideways scroll in Settings with the runner status, in the Analyze pane with the job list, with a result and with the Layers sheet open; touch targets of at least 36 px; the consent dialog fits the width and both of its buttons are on screen and touch sized; nothing is uploaded while it is open.
- [x] **CI**: `.github/workflows/forge.yml` gains a `runner` job (Ubuntu's gmsh, `npm test` in `runner/`); `runner/**` triggers the workflow. No Elmer in CI.
- [x] **Known limits (honest list)**:
  - **Elmer has never run here** (above). Structural and thermal numbers from it are unseen; the first run is commissioning, to be compared with a hand calculation before use.
  - **Bodies are meshed separately and are not bonded** where they touch (no shared nodes, no contact): each body needs its own fixed mount (a body without one is refused with a message) and exchanges no heat with a neighbour. A3 bonds touching bodies; the two analyses differ there.
  - **PCB heat sources and PCB parts are not sent.** The result lists how many watts were left out. Heat must be on a body.
  - **Thermal is steady state with one film coefficient** for every free surface (default 10 W/m2K) and no separate radiation; A2 uses correlations and radiation.
  - **Structural is linear, isotropic**: the FDM inter-layer knock-down and the drop/recoil presets of A3 are not applied (use an acceleration load); the safety factor is against yield (tensile strength, said, when there is no yield).
  - **Mesh**: first-order tetrahedra; stress at sharp corners depends on the mesh and the study shows by how much; when the surface cannot be re-meshed the element size near it is the STL's.
  - Polling (about every 0.5 s) rather than streaming; one job at a time; results up to the host's 20 MB response limit (a 400 000-tetrahedron VTU in ASCII is about that size: raise the mesh size if it is refused).
  - The runner trusts its own network position: the token is the only gate, so run it on loopback or behind TLS, and never expose it unauthenticated.

Tests: `cd runner && npm test` (32: protocol, auth, CORS, validation, lifecycle, retention, cancel, timeout, mesh study, no outbound client, STL/VTU/gmsh/Elmer-file writers and readers, the Elmer adapter against a stub, real gmsh on boxes and on the starter enclosure); `forge/test_runner.mjs` (17, in `npm test`: package against a real runner server in the process, the simulated solver and real gmsh); `forge/test_ui_runner.mjs` (Chromium, desktop and phone); `test_ext.mjs` gained the consent cases. **Validation table: nothing was added to `npm run test:analysis`; it reports the A3 table unchanged (103 of 105 checks pass, 2 DIVERGE as declared, 2 cases SKIPPED because the Elmer reference files do not exist).**

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

### A5 done

Status: built on branch `forge-a5-airflow`, stacked on `forge-a4-runner` (not merged, no PR opened). Package: `forge/examples/ext/airflow` (an extension: `compute.gpu`, `scene.overlay`, `doc.read`, `geometry.read`, `storage`, `agent.tools`). **Phase 1 returns `estimate`, phase 2 returns `experimental`; neither is claimed as validated.** Met items are ticked; everything that is not met is listed with the number. Phase 2 of the spec is "then hard (2-3 months)": what is here is the part that could be built and tested in a sandbox with no GPU, no OpenFOAM and no Docker.

- [x] **Lattice Boltzmann D3Q19 with Smagorinsky large-eddy closure on WebGPU, CPU fallback** (`analysis/voxel-core/lbm.js`, `gpu-lbm.js`, `analysis/airflow/package-main.js`; `tools/bundle-ext.mjs` builds the package's single `main.js` from `grid, lbm, gpu-lbm` and the package source; a test fails if it is stale). BGK collision with the Smagorinsky relaxation time solved in closed form from the non-equilibrium momentum flux, Guo body force, halfway bounce-back walls (also moving walls), non-equilibrium-extrapolation velocity and pressure boundary cells, optional periodic axes. The CPU path has the 19 directions written out by a generator (`tools/gen-lbm-kernel.mjs`, checked for staleness; the loop version stays as the reference and a test compares the two): 9 to 11 million cell updates per second against 3 for the plain loop. The WGSL kernel is one thread per cell, pull streaming, two ping-pong population buffers, six storage buffers, a 2D dispatch so a lattice is not limited to 65 535 workgroups. **Executed on WebGPU** (headless Chromium, SwiftShader, a **software** adapter): the kernel against its CPU twin on the same problem (walls, inlet, outlet, obstacle, force, Smagorinsky, periodic axis) agrees to 3.3e-5 of the peak speed after 300 steps, and the whole package runs in its Worker on the GPU and on the CPU. This says the shader is correct; **it says nothing about speed on a real GPU** (the software adapter took 93 s for 267 900 cells where the CPU took 8 s for 17 328).
- [x] **Domain and boundaries.** The domain is the box around the bodies with a margin (default 35 % of the largest dimension with a free stream, 20 % without, 1.5 margins more downstream; setting `margin_mm`). The upstream face and the four sides carry the free-stream velocity (the sides tangentially), the downstream face is a pressure outlet at gauge 0; with no free stream every face is open at gauge 0. The free stream is the air speed of the Conditions (ambient) or the setting `speed_ms`, along `flow_dir`. PCB parts are solid blocks. Deviation: the sides are velocity faces, not slip walls, so a model that blocks much of a small box speeds the flow around it (said in the assumptions).
- [x] **Inlet velocity or fan curve.** Fans (setting `fans`, through the AI tool `set-airflow` or the project data; no form in the pane) are a body force on a disc with a straight-line pressure/flow curve (pressure rise `dp_Pa` falling to zero at `q_m3s`), updated from the flow measured through the disc's middle layer every interval with relaxation. Not modelled: swirl, blade, a measured (non-linear) curve, fan placement by picking in the viewport.
- [x] **Outputs: velocity magnitude, streamlines, flow rate per vent, dead zones.** Voxel fields `speed`, `pressure` (gauge, Pa), `dead_zone` and the vector field `velocity` (the Analyze pane draws arrows; the Layers sheet lists them as `analysis:<field>`), the package's own `streamlines` overlay (a `vectors` overlay with `lines`, layer `xyz.mdhv.airflow:streamlines`, cleared at the start of each run, seeded across the vents' inflow cells, the upstream plane and the interior). Vents are the connected openings in the six wall layers of the model's bounding box; each is reported with the air entering and leaving it separately (`vent_N_in`, `vent_N_out`, L/s, with its centre), so a vent that does both is visible; `through_flow` (L/s and cfm) is the smaller of the total in and out, `mass_balance_error` the mismatch between them. A dead zone is interior air slower than a fraction (setting, 5 %) of the reference speed (the free stream, else the mean speed through the inflow vents), and the reference is stated in the result. Checks with editable limits: dead-zone fraction at most 0.35, through flow at least 0.5 L/s (both defaults are placeholders, not recommendations).
- [x] **Settings in the Analyze pane, grid limits shown, Worker with progress and cancel, phone viewport.** Resolution (draft/normal/fine), compute (auto/gpu/cpu), mode, flow direction, air speed, flow-throughs, time limit, dead-zone threshold; advanced settings (`margin_mm`, `smagorinsky`, `min_tau`, `streamlines`, `h_mm`, `fans`, `delta_T_guess_K`) through the AI tool or the project data. Lattice budgets (whole box, margin included): laptop GPU 262 144 / 1 000 000 / 2 097 152 (128 cubed), phone 32 768 / 110 592 / 262 144 (64 cubed, with a warning), CPU 16 000 / 40 000 / 100 000, hard limit 2 200 000; the voxel size, cell count, budget and limit are in the result, the legend and the warnings. The run reports progress, stops quickly on Cancel, stops at a time limit (default 90 s) and says it did not settle, and repeats itself with a higher viscosity floor (tau 0.51, 0.53, 0.56, 0.62) if the flow blows up, saying so. The browser test (`test_ui_airflow.mjs`) runs it on a 1280 px and a 390 px viewport: no sideways scroll, touch targets at least 36 px.
- [x] **Validation: lid-driven cavity (Ghia et al., Re 100 and 1000), PASS.** Largest difference to Ghia's 17 centreline points in units of the lid speed: Re 100 at 64 x 64 cells 0.0054 (u) and 0.0039 (v) against the tolerance 0.02; Re 1000 at 64 x 64 0.0287 and 0.0250 against 0.05. The Re 1000 case at 128 x 128 (`slow/airflow-slow.json`, about 8 minutes on the CPU) gives 0.0124 and 0.0097 against 0.03: PASS. **The table values of Ghia et al. were typed from memory of the paper's tables and not re-checked against it** (the results agree with them to 0.4 % of the lid speed at Re 100, which says the transcription is not grossly wrong). The tolerances are mine (the spec states none) and were written down before the run.
- [x] **Validation: flow past a cylinder (Strouhal number), PASS.** Benchmark 2D-2 of Schaefer and Turek (Re 100, blockage 0.24, parabolic inflow): 0.2949 against 0.300 (reference range 0.2950 to 0.3050), 1.7 % off, tolerance 5 % (mine, written before the run), 50 cells across the channel, 20 000 steps. At 66 cells across (the slow file, about 3 minutes) 0.2983, 0.6 % off: PASS. Drag and lift are not computed.
- [x] **Plane Poiseuille flow (analytic), PASS** (added: 0.04 % against 0.5 %), **a closed box in a stream** (through flow exactly 0, every interior cell dead) and **a duct open at both ends** (mass balance 9.4 % against 15 %) through the real runner and a Worker.
- [ ] **Not met: "one enclosure compared with OpenFOAM via A4".** There is no OpenFOAM here. `enclosure-forced-vs-openfoam` is **SKIPPED, not a pass**: it waits for `analysis-validation/reference/airflow-enclosure-openfoam.json` (the total flow through the vents within 10 %, my tolerance). The Runner now has an OpenFOAM path (`runner/`: a `flow` job, a steady `simpleFoam` case with blockMesh, snappyHexMesh and k-omega SST, planes read back as `plane_<id>_flow`) that is **written and tested only as text and against stub scripts; OpenFOAM has never been run on it, the image does not install it, and Forge does not submit flow jobs yet** (no client in the Runner package, `tools/make-reference.mjs` does not know the case). **Consequence: phase 1 returns `estimate`, not `validated`.**
- [~] **Phase 2: double-distribution thermal LBM with Boussinesq buoyancy, coupled to the solid conduction.** Mode `natural`, **CPU only**, validity `experimental` (so its checks are advisory rows and it cannot decide a pass or fail). A D3Q7 temperature lattice with the Boussinesq force, Dirichlet and adiabatic walls, and **the solid bodies inside the same lattice with a per-cell diffusivity** from the material's conductivity (heat sources from PCB parts and bodies as in A2, bodies and parts as conducting solids). It is **not coupled to the A2 package's solver**: the conduction is the lattice's own, with a pseudo heat capacity (the steady state is right, the time it takes is not physical, so there is no transient); diffusivities above a lattice cap of 10 are capped and the capped bodies are named (aluminium and copper are capped, so their peaks are overstated); **no radiation** (surface temperatures are overstated where it matters); open faces take ambient where air comes in and zero gradient where it leaves; no heat balance is checked.
- [x] **Validation: de Vahl Davis differentially heated cavity (Ra 1e3 to 1e6, Nusselt within 2 %), PASS** (Pr 0.71, Nusselt from the heat flux through the vertical mid-plane): Ra 1e3 1.1189 against 1.118 (0.08 %, 32 x 32 cells), 1e4 2.2341 against 2.243 (0.40 %, 32 x 32), 1e5 4.4884 against 4.519 (0.68 %, 48 x 48), 1e6 8.7203 against 8.800 (0.91 %, 64 x 64). The cells counts are the ones used; they were chosen for run time and the tolerance was not changed.
- [ ] **Not met: "an enclosure compared with OpenFOAM `chtMultiRegionFoam`".** `enclosure-natural-vs-chtmultiregionfoam` is **SKIPPED, not a pass** (reference file absent; the Runner has no `chtMultiRegionFoam` case at all). Natural convection stays `experimental` until it passes.
- [~] **Grid and performance budget: "128 cubed at an interactive rate on a laptop GPU, 64 cubed on phones with a warning".** The budgets exist (128 cubed = 2 097 152 cells is the laptop fine budget, 64 cubed the phone fine budget, and the phone gets a warning); **no real GPU was available, so the rate is not measured and "interactive" is not shown.** Measured here on the CPU (4 cores, Node 22, one thread): 9 to 11 million cell updates per second in the bulk kernel; the 17 000-cell draft enclosure takes about 8 s.
- [x] **Licences and notices.** No new dependency; `forge/THIRD_PARTY.md` lists the files; OpenFOAM is started as a separate process by the Runner and never linked.
- Known limits (honest list):
  - **BGK at a relaxation time near 1/2 is unstable around sharp features.** The viscosity floor (tau 0.51 by default) is above what the real viscosity of air asks for at every size here, so **the simulated Reynolds number is lower than the real one** (a 2 m/s stream past a 50 mm case has Re 6 000; the lattice at draft carries a Reynolds number of a few hundred), and the Smagorinsky term models the missing turbulence only in part. The result says so every time and says when the floor had to be raised after a blow-up. Natural convection is more sensitive: the same case gave a peak 36.8 C at tau 0.53 and 31.9 C at tau 0.56. This is a coarse-lattice limit, not a fixed property.
  - **No wall function:** near-wall speeds and shear are not reliable; the cell Reynolds number and the lattice Mach number are reported (the lattice Mach number reached 0.2 near the corners of a bluff case, where compressibility error is a few percent).
  - **The conservative voxelizer closes a slot narrower than a voxel** and thickens thin walls by up to one voxel; vents are reliable from about three voxels across. The lattice is uniform: no local refinement.
  - **A steady-state estimate:** the result is the average of the last samples; a flow that sheds vortices is averaged, not resolved in time. The duct test's mass balance is 9 % off at draft resolution.
  - Vents are found in the wall layers of the **bounding box**; an opening that does not reach it (a recess, a vent on a filleted edge) is not a vent, and an edge cell can be counted in two layers.
  - The GPU path covers forced flow only. The GPU readback every few hundred steps and the averaging are not optimised; a 2-million-cell lattice needs a device whose storage buffer holds 160 MB (the package asks for the adapter's maximum and falls back to the CPU lattice with a message when it cannot).
  - The Strouhal and cavity references are 2D problems run as two-layer periodic lattices; no 3D flow case against a published value was run (the Poiseuille test is the only 3D-lattice analytic case besides the heated cavity).

## 4A. Durability and mechanical risk (A6)

A3 answers "does it break on one load". A6 answers **"will it still be attached and intact after sustained use"**: thousands of shots, months of vibration, hot and cold cycles, humidity. The output is a **risk register**: ranked, located flags, each saying what could fail, why, with what margin, how sure we are, and a suggested fix. Life numbers are always `estimate` (plastic fatigue and creep data is scarce and grade-specific); the value is ranking designs and catching mechanisms early, then writing the physical test plan.

### A6 shared pieces

- **Duty profile** (saved in the project, `analysis.conditions.duty`): service life (years, hours per day), shot count (per day and total; recoil pulse as peak g and duration in ms, half-sine), vibration (a PSD in g²/Hz against Hz, from presets "handheld", "vehicle", "custom"; exposure hours), temperature (min, max, cycles per day), humidity (dry as moulded / conditioned 50 % RH / wet), target life and a safety factor on life (default 3 for plastics). Every preset is labelled a placeholder; the UI says to replace it with measured data.
- **Material data** (extend `analysis/materials.json`; every value keeps `quality` and `sources`, and a missing value is a **data-gap flag**, never a silent default):
  - coefficient of thermal expansion α
  - E and yield as functions of temperature (a few points, linear between)
  - wet/dry knock-down factors for polyamides
  - fatigue: Basquin coefficients or S-N points (stress amplitude against cycles, R ratio stated), endurance at 10⁷
  - creep: Findley power-law parameters ε(t) = ε₀ + m tⁿ (σ-, T-dependent) or stress-relaxation points
  - metals for fasteners and rails: steel, stainless, aluminium 6061/7075, brass inserts
- **Component masses**: `pcb.components[].mass_g` (and an optional stiffness class). A3 and the modal solver apply them as lumped masses spread over the faces each part bears on (cradles, stops). This also fixes A3's "parts on the board have no mass" gap.
- **Joints**: `joints[]` in the project: a bolt (size, grade, length, torque or preload, friction coefficients, thread locker yes/no), what it clamps (bodies, plus the external part, e.g. "rail: steel"), and the insert type (none / heat-set brass / metal compression sleeve / moulded nut). The template marks the clamp's cross-bolt as a joint automatically.
- **Risk flag** (the result type every A6 step emits, also exported as a table):
  `{ id, mechanism: 'preload_loss' | 'self_loosening' | 'creep' | 'fatigue' | 'resonance' | 'shock' | 'thermal_cycling' | 'moisture' | 'data_gap', severity: 'high' | 'medium' | 'low' | 'info', body, where: [x,y,z], metric, value, limit, margin, why (one plain sentence), fix (a suggested change; where it is geometric, a typed-ops proposal the user can apply and undo), confidence: 'low' | 'medium' | 'high' }`
  - Severity comes from the margin: margin < 1 → high; margin < the life safety factor → medium; margin < 10 → low; else info.
  - Confidence comes from the result's `validity` and the worst material-data quality used. Low-quality data caps confidence at low, whatever the margin.
- **UI**:
  - A Risk pane (in Analyze) lists flags by severity.
  - Pins in the viewport at each `where`; tap one for the why, the numbers, the confidence and the fix.
  - "Apply fix" commits the proposal as one undoable step.
  - Flags join the scorecard. High flags fail the design loop's gateway unless their confidence is low (then they are advisory). Experimental never gates.
- **Test plan export**: from the flags and the duty profile, a markdown plan, marked as suggestions for a qualified lab:
  - MIL-STD-810H methods 514.8 vibration, 516.8 shock, 501.7/502.7 high/low temperature, 503.7 temperature shock and 507.6 humidity
  - a rounds-fired endurance test with a torque-audit schedule
  - acceptance criteria tied to each flag

### A6a · Joint, preload and creep (plugin) · easy–medium

- **Bolted joint** (VDI 2230, simplified):
  - bolt and clamped-part stiffness (pressure cone)
  - preload from torque (nut factor, or thread and under-head friction)
  - embedding loss
  - preload change with temperature from the CTE difference between bolt, plastic and rail
- **Creep relaxation** of the clamped plastic at the duty temperature: Findley or relaxation data over the service life, integrated over the temperature profile. Output: preload against time.
- **Slip and self-loosening**:
  - Transverse load per shot (recoil mass times g, through the clamp) against friction times residual preload. Where slip is possible, flag self_loosening (Junker mechanism).
  - Thread locker or a locking washer lowers the severity but does not remove the flag.
- **Fixes it proposes**:
  - a metal compression sleeve (the plastic then carries no bolt load, so creep drops out)
  - a larger bearing area
  - a higher grade or larger bolt
  - a thread locker
- **Validation**:
  - a VDI 2230 worked example: preload and thermal change within 2 %
  - a closed-form Findley relaxation case within 2 %
  - a sleeve-versus-no-sleeve case showing the creep path removed

### A6a done

Status: built on branch `forge-a6a-joint-creep` (pushed, no PR opened). Package: `forge/examples/ext/joint-creep` (a plugin: it asks for `doc.read` only; it never writes the design, the host applies a fix). Met items are ticked; deviations and misses are stated.

- [x] **Shared pieces A6a needs.**
  - Duty profile at `analysis.conditions.duty` (`set_duty` op, validated, undoable; `null` = not set, which is a data gap). Presets "handheld light", "handheld heavy" and "vehicle" are labelled placeholders (invented round numbers) in the data and in the UI, which says so beside them and offers a "these are my measured numbers" switch.
  - Material data in `analysis/materials.json` (schema 2): per material an optional `durability` block with CTE, E(T) and yield(T) as factors on the 23 °C value, wet/dry factors (polyamides only), and Findley creep (`n`, `beta(T)`, linear limit); fastener metals `steel-carbon`, `stainless-304`, `al7075`, `brass-cuzn` (aluminium 6061 was already there). Every item has `quality` and `sources`; a new quality `typical` means a value for the material class recalled without a specific datasheet, and **all polymer creep, E(T), yield(T) and CTE values are `typical`**. The sources cited are real and recognisable (VDI 2230, ISO 898-1, ISO 3506-1, Findley, Lai and Onaran 1976, ASM Handbook) and are marked recalled, not re-checked; the creep numbers cite the Findley form and a "typical" source whose text says the values are not from a datasheet and not measured. Steel and stainless E and CTE are `handbook`; aluminium 7075 and brass are `typical`.
  - `joints[]` in the project (`set_joint`, `remove_joint`: validated, undoable, locks enforced: a locked joint, or a locked body that the joint clamps or anchors in, refuses the change). The PCB enclosure template marks its **rear-cap screws** as joints; it also builds the clamp's cross-bolt joint from `spec.clamp.crossBolt` when a spec has one. **The invented demo board has no rail clamp, so it gets the cap screws only** (the clamp cross-bolt joint is exercised in tests and in the README example with a joint added by hand).
  - The risk flag type with the exact shape, severity and confidence rules of the spec: `src/analysis/risk.js` (shape check, severity from margin, confidence from validity and worst data quality, a table with CSV and markdown export, scorecard rows). The result model gains optional `flags[]` and `series[]` (preload against time); a package can set `flags: true` and `upstream: [...]` on its analysis in the manifest. `value`, `limit` and `margin` are `null` for a `data_gap` flag only.
  - `pcb.components[].mass_g` is **not built**: A6a does not need it (the carried mass of a joint is its own optional `carried_mass_g`, default the bodies' own mass, and a missing electronics mass is a data-gap flag). It stays with A6c, where it is a lumped mass.
- [x] **The package.** Simplified VDI 2230: pressure-cone stiffness (cut off at an outer diameter), preload from torque by nut factor or by thread and under-head friction, preload scatter from a tightening factor, embedding loss, thermal preload change from the CTE difference over the duty temperatures. Findley creep of the clamped plastic and of a thread or heat-set insert in plastic, integrated over the daily temperature profile by time hardening, giving preload against time. Slip and self-loosening per shot (carried mass times the peak recoil acceleration against friction times the end-of-life preload); a thread locker lowers the severity one step and never removes the flag. Further flags: assembly utilisation, bolt load at the temperature extremes, thread engagement, pull-out, bearing pressure on the plastic and a linear-limit warning. Temperatures: the Thermal 3D result at the joint when it has been run on this very design (the host hands the package the probe through the new `upstream` mechanism), otherwise the duty profile maximum; every result says which.
- [x] **Fixes** as typed-ops proposals on each flag (`set_joint` with a metal compression sleeve, a large washer, the next grade, the next size with the torque scaled, a thread locker, a longer bolt); "Apply fix" commits one undoable step and a locked body refuses it. Deviation: the sleeve, washer and insert are fields of the joint, not features in the geometry; no feature is added or edited, so nothing in the CAD changes until the user models it.
- [x] **UI**: a Risk list in Analyze (by severity, each with a shape and a word), pins in the viewport at each `where` (one per location, a 36 px hit area, 20 px drawn, hidden under a panel; a "view pins" button closes the phone sheet), a detail card (why, numbers, confidence, fix, Apply fix), duty and joints editors, CSV and markdown export of the table. Phone: no sideways scroll, targets of at least 36 px, pins covering well under 1 % of the viewport (Playwright, desktop and 390 px).
- [x] **Scorecard and loop**: flags join the scorecard as rows; a high flag is a hard row unless its confidence is low (then advisory); an experimental result never gates; no result is an advisory "not run". **With the shipped data every confidence is low** (typical creep and CTE values cap it), so on a stock project no A6a flag can fail the loop; it takes datasheet-quality data in a project material to get a gating flag (tested with such a material). The design-loop critic does not yet receive the flags (A6e).
- [x] **Validation** (`analysis-validation/cases/durability-a6a.json`, 7 cases, 22 checks, tolerance 2 % on every number, written in the generator before the package was run against it): preload from torque, pressure-cone, cylinder and bolt compliance, embedding, thermal change, Findley relaxation (isothermal, two phases, 365 daily cycles), sleeve against no sleeve (function level and through the real runner and a Worker). **All 22 pass, but they are closed-form self-consistency references, not published figures**: no published VDI 2230 worked example could be recalled reliably enough to quote, so the reference numbers come from the VDI and Findley equations written out a second time in `gen-durability-a6a-cases.mjs`, which does not import the package. They show that the code implements the stated equations; they do not show that the equations (or the typical creep numbers) describe a real joint.
- [ ] **Not met / not done.** (1) Nothing was compared with a measurement, a published worked example or FEA; the whole result is a ranking tool. (2) Creep uses typical class values, linear in stress and with time hardening (exact for that rule, but the rule is a choice: strain hardening or a full viscoelastic solution would give different numbers); moisture changes E and strength but not the creep rate; relaxation-point data is not supported, only Findley. (3) The pressure cone, embedding values and engagement lengths are guide values (VDI for metal faces; rules of thumb for plastic, said in each flag). (4) The user-facing material form cannot yet enter a `durability` block; it goes through `set_material` (the AI or a script). (5) Component masses (`mass_g`) are left for A6c. (6) The Thermal 3D probe is the cell average near the joint, not the bolt-hole wall. (7) The geometry is not edited by a fix (see above). (8) No test-plan export (A6e), no fatigue or vibration (A6b to A6d).

### A6b · Fatigue, thermal cycling, humidity (plugin on A3 results) · medium

- **Load blocks**:
  - recoil (A3 with the shot load and component masses, as amplitude about the assembly mean, which includes preload)
  - thermal cycling (A2/A3: the thermal strain range from the CTE mismatch with the rail over the temperature range)
  - vibration (from A6c when present)
- **Per surface vertex**:
  - mean-stress correction (Goodman; Gerber as an option)
  - S-N life at the local temperature with the wet/dry and FDM inter-layer knock-downs
  - Palmgren–Miner damage summed over the blocks
  - life in shots and in years
- **Thermal cycling**: also flags clamp-gap and preload change.
- **Edge artefacts**: stress on boundary voxels flagged by A3 as edge artefacts lowers confidence; the flag says to confirm with the Runner.
- **Validation**:
  - an unnotched bar with a known S-N curve: life within 10 %
  - a two-block Miner case: analytic
  - a Goodman mean-stress case: analytic
  - a bimetal strip (Timoshenko) curvature within 3 %

### A6c · Modes and random vibration (extension, `compute.gpu`) · medium

- **Lowest natural frequencies and mode shapes**:
  - the A3 voxel stiffness with lumped component masses
  - LOBPCG or subspace iteration on WebGPU, with a CPU fallback
  - clamp faces fixed
- **Flags**:
  - resonance where a mode falls inside a band with significant PSD energy, or within ±15 % of a stated excitation frequency
  - a loose-part flag for any component whose cradle mode is the lowest
- **Random response**:
  - per mode, Miles' equation (Grms = √(π/2 · f · Q · PSD(f)), with Q stated as an assumption, default 10)
  - modal stresses scaled to 1σ and 3σ, then cycles counted (Steinberg three-band) into A6b's damage sum
- **Validation**:
  - cantilever first three frequencies (Euler–Bernoulli) within 3 % at fine
  - free-free block: six rigid-body modes near zero
  - a single-degree-of-freedom Miles result against a time-domain Monte Carlo within 10 %

### A6d · Shock (extension, `compute.gpu`) · medium

- **Method**: the shock response spectrum of the recoil and drop pulses (half-sine; sawtooth optional), applied to the A6c modes by mode superposition (SRSS, NRL as an option).
- **Outputs**:
  - peak stress and safety factor per pulse
  - peak component acceleration at each cradle, for checking the parts' own ratings, e.g. a laser diode's shock limit
- **Replaces the A3 constant-g placeholder** for shots and drops. The flag says the dynamic amplification it found.
- **Validation**:
  - the half-sine SRS of a single degree of freedom against the closed form within 2 %
  - a two-mode case against direct Newmark integration within 5 %

### A6e · Risk register, loop and test plan · easy–medium

- **Risk register**:
  - the Risk pane, viewport pins and "apply fix"
  - the register export (CSV + markdown)
  - per-flag history across design revisions, so a fixed flag shows as resolved
- **Design loop**: the critic gets the flags; the designer is told the mechanism and the suggested fix.
- **Test-plan export** as above.
- **Benchmark**: the rail-mount reference enclosure gets a stored register (a generic one only; never client geometry) to catch regressions.

### A6 done (each sub-step)

- [ ] Its validation cases pass within tolerance (table in the PR), and the cases are in `analysis-validation/cases/durability-*.json`.
- [ ] Every flag carries mechanism, severity, location, margin, why, fix and confidence.
- [ ] Missing material data produces data-gap flags, not defaults.
- [ ] Results are `estimate` and never claim more. Flags join the scorecard and the loop with the gating rules above.
- [ ] Headless tests, plus a Playwright smoke test (desktop and a 390 px phone).
- [ ] A worked example in `forge/README.md` on the demo board.

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
| A6a | Joint, preload, creep, loosening | plugin | easy–medium | 1–2 weeks | A0, A2 (temperatures) |
| A6b | Fatigue, thermal cycling, humidity | plugin | medium | 2 weeks | A3, A6a |
| A6c | Modes and random vibration | extension | medium | 2–3 weeks | A3 (voxel stiffness), A6b |
| A6d | Shock (SRS + modes) | extension | medium | 1–2 weeks | A6c |
| A6e | Risk register, loop, test plan | core UI + plugin | easy–medium | 1 week | A6a (grows with each step) |

Sizes assume one developer with Claude Code. Validation is a large share of every analysis step and is not optional.

## 6. Out of scope for now

A public marketplace and signing service; paid packages; nonlinear materials, contact mechanics, explicit impact dynamics (A6 covers fatigue, modes and shock by superposition); EMI and RF. Each can become a package later without changes to the host.

## Layers (overlay visibility)

Every overlay is a layer with the id `<extension id>:<overlay id>` (analysis fields use `analysis:<field name>`). The **Layers** button in the viewport toolbar opens a non-modal glass sheet (a bottom sheet on a phone) listing the layers grouped by analysis or extension. Each layer has an on/off switch, an opacity slider (5 to 100 %), a Solo button, a colour-map swatch and a readout of kind, unit and value range; the sheet has Show all and Hide all, and the colour/range explanation sits behind an `i` tip. On/off and opacity are viewing preferences: they are kept in the browser's localStorage under `af.layers`, keyed by layer id, wrapped in try/catch (without storage they last for the page), never saved in the project, and they survive re-running an analysis. The `vectors` overlay kind (arrows, or streamlines when `lines` is given) exists so airflow (A5) can draw its velocity field; it is validated in the overlay contract (`forge/src/ext/api.js`) and documented in `docs/EXTENSION_API.md`.
