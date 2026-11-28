# Alloy Forge roadmap

Goal for the client release: give the board (STEP) and a short brief, get several compact, good-looking, manufacturable enclosure variants in one or two prompts, each with a pass/fail scorecard, then refine and export.

Each milestone is one Claude Code session, one branch, one PR. "Done" means every acceptance item is true and tested.

**Status key (checked against the code and the tests on 2026-11-25).** `[x]` built and tested. `[~]` partly built; the gap is stated. `[ ]` not built. Test evidence means a named test in `forge/` passes (`npm test`: 46 tests on main; browser smoke tests are run by hand, `npm run test:ui`). "Not measured" means the acceptance number has never been measured, not that it fails.

## F0 · Monorepo and core extraction
**Status: not started.** There is no `packages/` directory. Everything is still in `forge/src` (which has grown: `occt/`, `loop/`, `ext/` in PR #24). The shared-package plan, now data first, is in `docs/ALLOY_FAMILY.md` (S1 to S5). Waits for Draft, which is the second web consumer.

- Move shared code from `forge/src` into `packages/core` (expr, model/ops, ai, io) and `packages/kernel` (kernel interface + Manifold backend). Forge imports them; behaviour unchanged.
- Workspace build (npm workspaces), one `npm test` at the root running all packages.
- **Done:** Forge builds and passes its existing tests from the new layout; no UI change.

## F1 · B-rep kernel (Open CASCADE)
**Status: built for the enclosure path; done except the items below.**
- [x] OCCT backend (replicad + Open CASCADE wasm) selectable per document (`settings.kernel`); Manifold stays the default, for previews and mesh work.
- [x] Kernel runs in a Web Worker, loaded on first use (about 22 MB wasm, cached); the header shows its state; the UI does not block.
- [x] True B-rep STEP export (analytic faces, not facets). Test: the exported STEP re-imports as valid solids with the same volume.
- [x] The classic features build on OCCT and agree with Manifold on volume (test, tolerance **3 %**, which is looser than the roadmap's 0.5 %; the likely cause is exact curved surfaces on OCCT against faceted ones on Manifold, not verified).
- [~] Fillet, chamfer, shell and loft exist as kernel helpers (fillet falls back to chamfer, then to the untouched edge, with a warning; ruled lofts through filleted sections) and drive the PCB enclosure feature. **They are not exposed as feature types.** There is no user-facing edge fillet or chamfer by selector, draft angle, per-face shell, sweep, revolve or mirror-body feature. (This line was added by the consolidation review; it is not on the owner's list of deferred items. Please confirm.)
- [~] STEP import keeps B-rep when OCCT is active: not yet. STEP import still tessellates through `occt-import-js`; tagged parts then become boxes (below).

**Deferred or open (owner's list, plus what was not measured):**
- Shear, twist, taper, bend and scale, and mesh import, are not built on OCCT. They report a clear error and stay on Manifold.
- Tagged parts become axis-aligned boxes only, and assemblies must be pre-oriented to the template frame.
- Sampled min-wall is off by default on OCCT (exact distances are used; a global min-wall is only reported when asked).
- The overhang share is approximate (from the tessellation).
- The cell style builds in about 20 s in Node (monolith and bullet about 3 s); in the browser the first build also pays the wasm download.
- No real-browser or mobile run yet. Only automated headless-Chrome smoke tests exist (`test_ui_loop.mjs`). Not measured: the 1.5 mm-fillet enclosure in < 2 s on a mid-range phone. Not checked: the exported STEP opening in FreeCAD with cylindrical and toroidal faces.

- Add an OCCT backend (opencascade.js or replicad) behind the kernel interface; Manifold stays for fast previews and mesh imports.
- New features: edge fillet and chamfer (by selector: "vertical edges of body X", "top outer edges"), draft angle, shell with per-face thickness, loft, sweep, revolve, mirror body.
- True B-rep STEP export (curved surfaces as surfaces, not facets); STEP import keeps B-rep when the OCCT backend is active.
- Kernel runs in a Web Worker; the UI never blocks.
- **Done:** a wizard enclosure with 1.5 mm fillets on all outer edges builds in < 2 s on a mid-range phone; exported STEP opens in FreeCAD with cylindrical/toroidal faces; existing features produce the same volumes (±0.5%).

## F2 · Design document v3 and the board
**Status: partly built.**
- [x] Document v3 with migration from v1 and v2 (test), carrying a `pcb` block: keep-out components with clearance class, insert side, cradle, edge slot, wall rule and flags (sensitive, emitter, exposed contact, switch paddle), plus cutouts, supports and kept regions.
- [~] The schema is not the one written above. There is no `requirements` object, no `components` list of real imported parts and no `stylePack` reference; the `pcb` block stands in for them.
- [~] PCB import: an assembled-board STEP is imported and its solids are tagged board, component, keep-out or keep-original in the Objects pane. Tagging does not mark connector, button, LED or mounting-hole faces; those semantics are set in the document, not by tagging.
- [ ] Component library extension format and the seeded library (USB-C, JST-PH, barrel jack, tactile switches, coin cells, LiPo pouches).
- [ ] **Done criterion not met as written:** importing a board STEP and tagging a USB-C port does not yet produce a requirement the checker can verify. Tagged keep-outs do feed the checker.

- Schema v3 with migration from v2: `requirements` (exposed connectors, buttons, LEDs, keep-outs, min wall, process, target envelope), `components` (imported board + parts with real dimensions), `stylePack` reference.
- PCB import: assembled-board STEP; tag faces/regions as connector, button, LED, keep-out, mounting hole in the viewport.
- Component library extension format, seeded with common connectors (USB-C, JST-PH, barrel jack), tactile switches, coin cells, LiPo pouches.
- **Done:** importing a board STEP and tagging a USB-C port produces a requirement the checker can verify.

## F3 · Checks, rendering, scorecard
**Status: partly built (OCCT path).**
- [x] Deterministic checks in plain code (OCCT scorecard): interference of keep-outs with shell and cap (exact boolean volume), wall from each part to the outer skin (exact distance), emitter beam path, exposed contacts, switch travel, rear insertion sweep, build volume, plus volume, mass, envelope and overhang share. Same JSON shape as the reference checker's scorecard.
- [x] Scorecard with hard checks and metrics; the critic score joins it inside the design loop.
- [x] Test: a deliberately wrong design fails the right rows; the loop's fixed round turns it green.
- [~] Overhang is approximate (tessellation). The Manifold path has mesh estimates only (interference, a sampled min wall, build volume).
- [ ] Wall thickness map, fastener and boss rules, bridging and min-feature checks, the whole moulding pack (draft, uniform wall, rib ratios).
- [ ] Headless rendering of standard views for the critic and reports. The critic scores the design JSON and the check results, not renders.

- Deterministic check packs (plain code, no AI): interference with board and components, clearance, wall thickness map, port alignment and access, fastener and boss rules, 3D-print pack (overhang, min feature, bridging), moulding pack (draft, uniform wall, rib ratios).
- Headless rendering of standard views (iso, front, top, section) in a worker, for the critic and for reports.
- Scorecard per design: hard checks pass/fail, metrics (volume, envelope, mass), critic score.
- **Done:** a deliberately bad design (port blocked, 0.6 mm wall) fails the right checks with clear messages; fixing them turns the card green.

## F4 · Design loop
**Status: built on a vendored loop engine, with gaps.**
- [x] Loop runner (loop engine pinned at `7a6ea4b`): designer proposes typed ops, the kernel builds, checks run, an optional critic scores, a gateway routes; locks enforced; rounds, token, cost and wall-clock budgets; a human approval gate (approve or rework with a note); N variants in parallel, ranked, one applied as a single undo step. Tests use a mocked model and the real kernel.
- [~] Variant list with scorecards and scores in the Loop pane (not a gallery of renders).
- [ ] A shared tool registry (read, apply ops, build, check, render, export) used by the in-app agent, MCP and CLI. The AI returns op JSON; there is no registry yet.
- [ ] A critic that judges renders against a style pack (there are no renders and no style packs).
- [ ] Per-role model selection (one model setting serves designer and critic).
- [ ] **Done criterion not shown:** three variants from a tagged board and a one-line brief with all hard checks green within the cap has only been run against a mocked model, not a real one.

- Shared tool registry used by the in-app agent (and later MCP/CLI): read design, apply ops, build, check, render, export.
- Loop runner: designer proposes ops > kernel builds > checks run > critic judges renders against the style pack > revise. Stops when hard checks pass and the critic score clears the threshold, or at max rounds / spend cap. Fan-out of N variants in parallel.
- Human checkpoints: approve requirements, pick a variant, sign off before export.
- Variant gallery in the editor with scorecards; promote one to the main design.
- Per-role model selection and spend caps in settings.
- **Done:** from a tagged board and a one-line brief, 3 variants come back with all hard checks green within the cap; the run log shows each round's ops and check results.

## F5 · MCP server and CLI
**Status: not started.** No `packages/mcp`, no CLI. The extension host (PR #24) registers agent tools but nothing calls them yet.

- `packages/mcp`: the tool registry over MCP (stdio; Streamable HTTP behind a token for self-hosting). Resources: design schema, style packs, check catalogue.
- CLI: `alloy-forge run --board board.step --brief brief.md --variants 3 --out out/`; and `--watch` to re-run when the board file changes (PCB revision loop), writing a diff report.
- **Done:** Claude Code can build, check and export an enclosure through the MCP server alone; changing the board STEP re-runs the loop and reports what moved.

## F6 · Extensions, style packs, templates
**Status: the host is built in PR #24 (milestone X0), not yet on main.**
- [~] Manifest, sandboxed host, install from a file, a URL or a bundled sample, per-project enable, and removal that never breaks a saved project: PR #24, 76 headless tests and a browser smoke test, open.
- [ ] Style pack format and any shipped style pack.
- [ ] The five enclosure templates. One template exists (the PCB enclosure, built in, not an extension).
- [ ] **Done criterion not met:** no style pack or template ships as an extension yet.

- Full design in `docs/EXTENSIONS_SPEC.md`: one package system with two tiers (plugin, extension), host and API v1 (milestone X0), analysis framework (A0).
- Extension manifest (id, version, kinds, permissions), sandboxed host, install from a file or URL, enable/disable per project.
- Style pack format: proportions, radius scale, edge treatment, parting-line rules, surface patterns (vents, grip textures), colour/material presets, critic rubric with reference renders.
- Enclosure templates: clamshell, slide lid, snap-fit two-part, screw-boss four-corner, rail-mount housing (generic mounting interface).
- **Done:** a style pack and a template ship as extensions, change the loop's output measurably, and can be removed without breaking a saved project.

## A · Analysis track (after X0/A0, see `docs/EXTENSIONS_SPEC.md` §4)
**Status:** X0 is on main. **A0 (analysis framework) is built and in an open PR** (`forge-a0-analysis-framework`): materials with sources, loads and conditions, result model with `validity`, surface/slice overlays, report export, check and loop integration, validation harness (31 of 31 checks on the sample package). **A1 (thermal estimate, plugin) is built in an open PR** (`forge-a1-thermal-estimate`; its "agreement with A2 within 15 %" check waits for A2). **A2 (voxel core + thermal 3D, extension) is built on `forge-a2-voxel-thermal`, stacked on A1, not merged**: the analytic validation cases pass (14 of 16 cases pass, 1 diverges, 1 skipped); the 3D-enclosure-against-Elmer case needs the Alloy Runner (A4) and is pending, so A2 reports `estimate`; A1-against-A2 agreement within 15 % holds on 2 of 3 reference enclosures (the third is a documented, non-passing divergence) (see `docs/EXTENSIONS_SPEC.md` A2 done). **A3 (structural, extension) is built on `forge-a3-structural`, stacked on `forge-layers`, not merged and no PR opened**: linear elasticity on the voxel core (hexahedra with incompatible modes, matrix-free CG on WebGPU with a CPU fallback), fixed and slider supports, force/pressure/g loads with drop and recoil presets, self weight and a simplified bolt preload, displacement, recovered stress and a safety factor with an FDM knock-down, surface fields in the Layers sheet. It returns `estimate`: 10 of 12 validation cases pass (cantilever 0.71 % against 3 %, also 0.32 % at the quality fine, on demand), 1 diverges as declared (plate with a hole at the default conservative voxelizer: 12.9 % against 10 %; with the volume-preserving fill 7.5 %), 1 is skipped (the bracket against Elmer needs the Alloy Runner); the spec's "validated for displacement and stiffness" is not claimed, fixed faces cannot be picked in the viewport, and the print orientation is a setting (see `docs/EXTENSIONS_SPEC.md` A3 done). A4 and A5 start only after the step before them is on main. **A4 (Alloy Runner) is built on `forge-a4-runner`, stacked on `forge-a3-structural`, not merged and no PR opened**: `runner/` is a dependency-free HTTP job server (auth, CORS, retention, cancel, progress events, limits) that meshes with the real gmsh and has an Elmer adapter, plus the Forge package `xyz.mdhv.runner` (consent dialog before any geometry is sent, job list with cancel, results mapped from the tetrahedral mesh onto the body surfaces and into the Layers sheet, mesh study, checks, AI tools). **Elmer was not available in the sandbox: the Elmer path has never run against Elmer, no reference file exists, the A2 and A3 Elmer cases stay SKIPPED and everything stays `estimate`; OpenFOAM and STEP are not implemented; the container image was not built** (see `docs/EXTENSIONS_SPEC.md` A4 done). **A5 (airflow, extension) is built on `forge-a5-airflow`, stacked on `forge-a4-runner`, not merged and no PR opened:** lattice Boltzmann D3Q19 with a Smagorinsky closure on WebGPU with a CPU fallback (executed only on a software adapter here), free stream or fans in, speed/pressure/dead-zone fields, vectors and streamlines in the Layers sheet, flow per vent, and an experimental natural-convection mode (D3Q7 thermal lattice with Boussinesq buoyancy and conducting solids, CPU only). Phase 1 returns `estimate`: the lid-driven cavity (Ghia, Re 100 and 1000) and the cylinder Strouhal number pass, the enclosure against OpenFOAM is **skipped** (the Runner's OpenFOAM path is written and stub-tested, never run); phase 2 returns `experimental`: the de Vahl Davis cavity passes within 2 % at Ra 1e3 to 1e6, the enclosure against `chtMultiRegionFoam` is **skipped** (see `docs/EXTENSIONS_SPEC.md` A5 done).

- A1 thermal estimate (plugin) > A2 voxel core + thermal 3D > A3 structural > A4 Alloy Runner (Elmer/OpenFOAM on the customer's machine) > A5 airflow (forced, then natural convection).
- A6 durability and mechanical risk (`docs/EXTENSIONS_SPEC.md` §4A): A6a joint, preload and creep > A6b fatigue, thermal cycling, humidity > A6c modes and random vibration > A6d shock > A6e risk register, loop and test plan. Output: located, ranked risk flags with margin, confidence and a suggested fix.
- **Done** per step: its validation cases pass within the stated tolerances, results carry an honest `validity`, and its checks join the scorecard and the design loop.

## F7 · White-label and deployment
**Status: not started.** `alloy.config.json` does not exist in Forge (or the Slicer); the schema is part of shared-package step S1.
- [~] A static, self-hostable build exists (`dist/`, no backend; the only network calls are to the model endpoint the user sets and, with PR #24, a package address the user types). Air-gapped operation has not been checked.

- `alloy.config.json` drives name, wordmark, theme tokens, default providers/models, bundled extensions, feature flags.
- Static build for self-hosting (intranet, air-gapped option: no external fetches at runtime).
- **Done:** a client-branded build is produced from config alone, with no source edits.

## Benchmark (from F3 onward)
**Status: not started.** There is no folder of reference boards in the repository and the `glock_circuit_casing` STEP files are not in it (they are client material and must not be committed). The one demo board is invented.

A folder of reference boards (the client's past products and the three `glock_circuit_casing` STEP files) with expected checks. Every loop or kernel PR reports pass rate, rounds and spend against it.
