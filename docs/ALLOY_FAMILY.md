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
android/           Alloy Slicer (existing Android plan)
docs/              this plan, specs, style canon
```

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
