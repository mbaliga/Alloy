# Alloy family plan

Alloy is one family of three tools on one shared core: draw it, forge it, slice it.

| Product | What it is | Platform | Release | Status |
|---|---|---|---|---|
| **Alloy Forge** | Parametric 3D CAD for enclosures and small products, AI-native, BYOK | Web (static, self-hostable) | 1 | Working beta in `forge/` (PR #17) |
| **Alloy Slicer** | A real slicer on the phone; sending to the printer is included | Native Android (Kotlin/Compose + NDK slicer engine) | 2 | Architecture gate (see root README) |
| **Alloy Draft** | Precision 2D drafting and vector design, Figma-easy, AutoCAD-precise | Web, mobile-first | 3 | Spec in `docs/DRAFT_SPEC.md` |

## Positioning

- **Forge** is a vertical tool, not a general Creo clone: PCB in, compact and good-looking manufacturable enclosure out, in a house style, with the design data on the user's own machines and their own model key. A version of it is being sold to a client who builds many small electronic products.
- **Slicer** is the product because no capable slicer exists on phones. Nobody needs Alloy to *print*: Bambu Handy already does that. Printing is in the app for retention, so users finish the job without leaving. Prioritise slicing quality, speed and preview; keep printer control thin (LAN Developer Mode first, extend later).
- **Draft** is not a Figma or Penpot replacement. Figma-clones are crowded (Penpot, OpenPencil, Grida, Graphite). Draft's lane is precision drafting with Figma's ease and Concepts' touch feel: jewellery drawings, 2D CAD drawings, icons, posters. No account, no file storage: "we just give you the editor". It also becomes Forge's sketcher (2D profile → extrude/revolve).

Workflow chain: **Draft** (sketch) → **Forge** (solid, checked enclosure) → **Slicer** (G-code and print).

## Alloy Core (shared, not sold on its own)

| Piece | Purpose |
|---|---|
| Geometry kernel interface | Manifold today (fast mesh booleans, previews). Open CASCADE (WASM, e.g. via replicad) next, for fillets, chamfers, draft, shells, lofts and true B-rep STEP. 2D kernel for Draft (paths, booleans, offsets, constraints). |
| Design document | Typed, versioned, diffable JSON: params, features, bodies, requirements, components, style-pack reference. AI never touches geometry directly; it edits this document. |
| Tool registry | One set of typed tools (read, apply ops, build, check, render, export) used by the in-app agent, the MCP server and the CLI loop runner. |
| MCP server | stdio for local agents (Claude Code, Claude Desktop, Cursor), Streamable HTTP for self-hosted teams. |
| BYOK model adapters | Anthropic, OpenAI-compatible (OpenRouter, Ollama, LM Studio), hosted Claude in artifacts. Per-role model choice, per-run spend caps. Keys never leave the browser or the customer's server. |
| Extensions | One package system, two tiers: **plugins** (scripted, API only) and **extensions** (own engine/UI: solvers, overlays, panes, a remote runner). Manifest + declared permissions, sandboxed (workers/iframes, no network by default). See `docs/EXTENSIONS_SPEC.md`. |
| White-label config | `alloy.config.json`: product name, logo, theme tokens, default providers and models, bundled extensions, feature flags. |

Proposed layout once Draft starts (milestone F0 in the Forge roadmap):

```
packages/core/     expr, design document + ops, tool registry, AI adapters, io, extension host
packages/kernel/   kernel interface, manifold + occt backends
packages/mcp/      MCP server + CLI loop runner
forge/             Forge web app
draft/             Draft web app
android/           Alloy Slicer (native Android app, in the repository since the consolidation)
slicer-ports/      iOS and Ubuntu Touch companions (preparation and inspection only)
docs/              this plan, specs, style canon
```

## Where Forge and the Slicer overlap (checked in the code, 2026-11-25)

The Slicer (`android/`, Java + NDK, AGPL-3.0) and Forge (`forge/`, JavaScript, PolyForm Noncommercial) share ideas and facts, not code. These are the overlaps, with what is actually duplicated today:

| Area | Forge today | Slicer today | Same thing? |
|---|---|---|---|
| **OCCT / STEP import** | `occt-import-js` (wasm) reads STEP assemblies into meshes (`src/io.js`: millimetres, bounding-box-ratio deflection 0.001, angular 0.3). `replicad` + Open CASCADE wasm is the B-rep kernel (`src/occt/`). | A native Open CASCADE build (`app/src/main/occt/`, arm64 `.so` and headers) behind `NativeStepImporter` and `OCCTLoader`; the importer feeds the same mesh workspace as STL/OBJ/3MF. | Same upstream library, two builds (wasm and NDK), two sets of tessellation settings that nobody keeps in step. Neither has a STEP conformance corpus of its own. |
| **A1 mini build volume and profiles** | `settings.printVolume` defaults to `[180,180,180]` in five places (`model.js` twice, `checks.js`, `occt/session.js` twice). Overhang defaults to 45 degrees in `occt/checks.js`; min wall 1.2 mm. | Profile JSON `printer: { id: "bambu.a1-mini", bed_x_mm, bed_y_mm, build_z_mm: 180, nozzle_mm: 0.4 }` and `process.support_threshold_degrees`; the same 180 is also hard-coded in `GlesViewportSurface`, `BatchSliceJobController`, `MainActivity` and `parity/a1mini_g4_scope.json`. | The same machine described in at least ten places, and one value has already drifted: Forge's default PLA density is 1.24 g/cm³, the Slicer's A1 mini profile says `filament_density` 1.26. Nothing ties Forge's "fits the printer" and "overhang" checks to the profile the Slicer will actually slice with. |
| **Viewport style** | three.js (`src/view.js`): matte bodies, fog, a grid that blooms under the model, dashed build-volume box, bodies grey out of volume. Dark ground `0x0e1215` (default) and light `0xeef1f3`; accent mint. | OpenGL ES (`GlesViewportSurface`): clear colour `0.965, 0.958, 0.94` (warm off-white), a black PEI-style bed with a 30 mm grid, a filament palette. | `docs/STYLE.md` calls the viewport "shared", but the two do not match (dark vs warm light default, different bed). The shared thing today is a description, not values. |
| **Theme and white-label tokens** | CSS custom properties in `index.src.html` (`--ink`, `--acc`, `--glass`, ...) plus a second copy in `view.js` `THEMES`. | `res/values/colors.xml` holds one colour (`splash_black`); everything else is literals in Java (`StudioPreviewRenderer` scenes, viewport). | **`alloy.config.json` does not exist in either app.** It is specified in this document (name, logo, theme tokens, providers, extensions, flags) and nothing reads it. |
| Also spotted | STL/OBJ/3MF import and export (`io.js`); BYOK model adapters (`ai.js`); filament density (`settings.density`); the bed as a concept. | `MeshModel`, `ModelStore`, `ProjectArchive`; `ByokVisualizationProvider` with Keystore storage; filament density in each profile; `PlateArrangementPlanner`. | Parallel implementations of small things. Not worth sharing code; worth sharing test files and constants. |

## Plan: what moves into shared packages (not started, nothing moves in this PR)

The Slicer is Java and C++, the web apps are JavaScript, and the Slicer is AGPL. So the sharing is **data first, code second**, and only in the direction web to web.

| Step | Package | Content | Who consumes it | Done when |
|---|---|---|---|---|
| S1 | `packages/tokens/` (JSON, language-neutral) | `machines.json` (id, bed X/Y, build Z, nozzle, support threshold), `theme.json` (the dark and light UI tokens and the viewport tokens), and the `alloy.config.json` JSON Schema | Forge (reads at build time, removes the five hard-coded 180s, the density default and the duplicated theme), Draft later, the Slicer through a script | Forge has no literal bed size or theme colour outside the tokens; `android/ci/validate_machine_tokens.py` fails if a profile's `printer` block disagrees with `machines.json`; Android colours are generated from `theme.json` by a script, not hand-edited |
| S2 | `packages/conformance/step/` | A small STEP corpus (the assemblies the Forge UI test already imports, plus one with units and colours) with expected part counts and bounding boxes | Forge test (occt-import-js) and Slicer instrumentation test (native OCCT) | Both importers pass the same corpus; deflection settings are written down once |
| S3 | `packages/core/` (web only) | `expr`, the design document and ops, checks, io, the extension host (`forge/src/ext`), AI adapters | Forge, Draft (when it starts, milestone F0) | Forge imports it with no behaviour change; the existing suites stay green |
| S4 | `packages/kernel/` | Kernel interface, Manifold and OCCT backends | Forge, Draft | As S3 |
| S5 | `alloy.config.json` loader | The reader for the S1 schema: name, wordmark, theme, default models, bundled extensions, flags | Forge (milestone F7) | A white-label build changes only the config file |

**Not shared, on purpose:** the slicing engine and its AGPL code, the native OCCT binaries, and the two viewport implementations (three.js and OpenGL ES). They share the tokens and the spec, not code.

**Decision needed (new, D7): the licence of `packages/tokens/`.** The Slicer is AGPL-3.0 and Forge is PolyForm Noncommercial, which restricts use; the Slicer cannot take a noncommercial-only licensed file into an AGPL build without conflict. Proposal: publish the tokens and the conformance corpus under a permissive licence (for example CC0 or MIT) while the code in `packages/core` and `packages/kernel` stays PolyForm Noncommercial. Needs the owner's call before S1.

**Order and risk.** S1 first: it removes real duplication, touches no engine code and can be reversed by deleting a directory. S2 next because it finds out whether the two OCCT builds actually agree. S3 and S4 wait for Draft (F0), since that is when a second web consumer exists. The risk to watch is drift: every step above ships with a test that fails when the copies disagree.

## Editions (licences, not products)

| Edition | Who | Terms |
|---|---|---|
| Community | Individuals, noncommercial | Free under PolyForm Noncommercial 1.0.0 (current repo licence) |
| Commercial | Companies | Paid commercial licence from the copyright holder |
| White-label | Clients who ship it under their brand | Commercial licence + setup + annual support; their style packs and templates |

## Decisions (defaults chosen so work can start; revisit when the client answers)

| # | Decision | Default for now | Revisit when |
|---|---|---|---|
| 1 | Licence | Keep PolyForm Noncommercial; sell commercial licences. A later move to AGPL + commercial dual licence is possible (needs a CLA from day one so all copyright stays with the holder). | Before the first outside contribution |
| 2 | Client manufacturing process | 3D-printing checks first (wall, overhang, min feature); moulding checks (draft, uniform wall, ribs) as a second check pack | Client confirms process |
| 3 | Server | Static, self-hosted, no backend for v1. Team features (shared libraries, audit log) later and optional. | A client asks for shared libraries |
| 4 | PCB input | STEP of the assembled board (KiCad/Altium export) + in-app tagging of connectors, buttons, LEDs, keep-outs | KiCad plugin or IDF import becomes worth it |

## How execution runs

- Architecture and kernel decisions: a larger model (Opus) session.
- Each milestone: its own Claude Code session on Sonnet, one branch and one PR per milestone, acceptance criteria from the roadmap as the definition of done.
- Kernel migration (F1) gets an Opus review before merge.
- Every PR keeps `npm test` green and adds tests for what it adds.
