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

Tests: `npm test` runs the kernel tests, `test_loop.mjs` and `test_occt.mjs` (OCCT: template styles, scorecards, STEP round trip, document v3, tagging, loop on the B-rep path) (mocked model, real kernel: iteration, gateway routing, budgets, locks, critic, approval, cancel, variants, checks). `npm run test:ui` (needs Playwright and a browser, see the file header) is a browser smoke test of the pane with a mocked model endpoint.

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
