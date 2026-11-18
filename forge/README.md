# Alloy Forge

Browser-based parametric CAD for electronics enclosures and small product design, with an AI-native editing workflow (bring your own key). Part of Alloy; the native Android slicer lives in the rest of this repo.

- **Parametric core**: named parameters and expressions (`outL = inL + 2*wall`), an ordered feature tree, patterns, mirrors, lid/base split.
- **Enclosure wizard**: PCB size, standoffs and port cutouts generate a base, a lid with a lip and a reference PCB.
- **Objects / Features / Params panes**: bodies work as layers (show, solo, lock, colour, rename); features are the history.
- **Locks** on features, parameters and bodies, enforced in the data layer, so neither the gizmo nor the AI can change locked items.
- **Select and transform**: click to select a body; Move/Rotate gizmo with snapping writes deltas back into expressions so drags stay parametric. Transform feature: move, rotate, scale, shear, twist, taper, bend.
- **Words in any field**: type "twice the wall plus clearance" and the model turns it into an expression.
- **AI copilot**: the model returns structured ops that are validated, built, auto-repaired on failure and applied with one-tap undo. Anthropic or any OpenAI-compatible endpoint (OpenRouter, Ollama, …). The key stays in the browser and goes only to the endpoint you set; there is no backend.
- **Import** STEP and STL; **export** STEP (planar B-rep faces), STL, OBJ and project JSON.
- **Inspect**: pick points, one-tap holes and bosses, measure, section view, mass and build-volume check.
- **Alloy rendering**: matte bodies, fog, a grid that blooms under the model and fades out, build-volume box, bodies turn grey when off the plate, light and dark themes.

## Kernel limits

Solids are watertight meshes (Manifold), not NURBS B-reps. No fillet or chamfer on arbitrary existing edges, and curved surfaces export to STEP as facets. Round corners come from the `radius` field on boxes and shells. Open CASCADE is the upgrade path for a true B-rep kernel.

## Build and run

```
npm ci
npm run build    # writes dist/
npm test         # headless kernel tests
npm run serve    # http://localhost:8765
```

Serve `dist/` over http(s); opening `index.html` from `file://` blocks the STEP reader's wasm fetch.

## Licence

Covered by the repository licence, [PolyForm Noncommercial 1.0.0](../LICENSE). Third-party components keep their own licences; see [THIRD_PARTY.md](THIRD_PARTY.md).
