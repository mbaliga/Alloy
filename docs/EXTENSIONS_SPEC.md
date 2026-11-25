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

- [ ] Materials file with sources; material assignment UI
- [ ] Loads/conditions editor in the Analyze pane, saved in the doc
- [ ] Result model, overlay renderer (surface map, slice, legend, probe), report export
- [ ] Check + loop integration with `validity` gating
- [ ] Validation harness and table output

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
