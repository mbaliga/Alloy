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
- **Import** STEP and STL; **export** STEP (planar B-rep faces), STL, OBJ and project JSON.
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

Tests: `npm test` runs the kernel tests and `test_loop.mjs` (mocked model, real kernel: iteration, gateway routing, budgets, locks, critic, approval, cancel, variants, checks). `npm run test:ui` (needs Playwright and a browser, see the file header) is a browser smoke test of the pane with a mocked model endpoint.

`src/loop/` is vendored from `mbaliga/Shared-Libraries-asoc` `web/loop-engine` (source commit recorded in each file's header and in `src/loop/VENDORED.md`). Do not edit it here; `npm run vendor:loop -- <checkout>` re-vendors. The engine hashes the loop definition with `crypto.subtle`, which browsers expose on https and localhost only.

## Kernel limits

Solids are watertight meshes (Manifold), not NURBS B-reps. No fillet or chamfer on arbitrary existing edges, and curved surfaces export to STEP as facets. Round corners come from the `radius` field on boxes and shells. Open CASCADE is the upgrade path for a true B-rep kernel.

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
