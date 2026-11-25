# Alloy Forge roadmap

Goal for the client release: give the board (STEP) and a short brief, get several compact, good-looking, manufacturable enclosure variants in one or two prompts, each with a pass/fail scorecard, then refine and export.

Each milestone is one Claude Code session, one branch, one PR. "Done" means every acceptance item is true and tested.

## F0 · Monorepo and core extraction
- Move shared code from `forge/src` into `packages/core` (expr, model/ops, ai, io) and `packages/kernel` (kernel interface + Manifold backend). Forge imports them; behaviour unchanged.
- Workspace build (npm workspaces), one `npm test` at the root running all packages.
- **Done:** Forge builds and passes its existing tests from the new layout; no UI change.

## F1 · B-rep kernel (Open CASCADE)
- Add an OCCT backend (opencascade.js or replicad) behind the kernel interface; Manifold stays for fast previews and mesh imports.
- New features: edge fillet and chamfer (by selector: "vertical edges of body X", "top outer edges"), draft angle, shell with per-face thickness, loft, sweep, revolve, mirror body.
- True B-rep STEP export (curved surfaces as surfaces, not facets); STEP import keeps B-rep when the OCCT backend is active.
- Kernel runs in a Web Worker; the UI never blocks.
- **Done:** a wizard enclosure with 1.5 mm fillets on all outer edges builds in < 2 s on a mid-range phone; exported STEP opens in FreeCAD with cylindrical/toroidal faces; existing features produce the same volumes (±0.5%).

## F2 · Design document v3 and the board
- Schema v3 with migration from v2: `requirements` (exposed connectors, buttons, LEDs, keep-outs, min wall, process, target envelope), `components` (imported board + parts with real dimensions), `stylePack` reference.
- PCB import: assembled-board STEP; tag faces/regions as connector, button, LED, keep-out, mounting hole in the viewport.
- Component library extension format, seeded with common connectors (USB-C, JST-PH, barrel jack), tactile switches, coin cells, LiPo pouches.
- **Done:** importing a board STEP and tagging a USB-C port produces a requirement the checker can verify.

## F3 · Checks, rendering, scorecard
- Deterministic check packs (plain code, no AI): interference with board and components, clearance, wall thickness map, port alignment and access, fastener and boss rules, 3D-print pack (overhang, min feature, bridging), moulding pack (draft, uniform wall, rib ratios).
- Headless rendering of standard views (iso, front, top, section) in a worker, for the critic and for reports.
- Scorecard per design: hard checks pass/fail, metrics (volume, envelope, mass), critic score.
- **Done:** a deliberately bad design (port blocked, 0.6 mm wall) fails the right checks with clear messages; fixing them turns the card green.

## F4 · Design loop
- Shared tool registry used by the in-app agent (and later MCP/CLI): read design, apply ops, build, check, render, export.
- Loop runner: designer proposes ops > kernel builds > checks run > critic judges renders against the style pack > revise. Stops when hard checks pass and the critic score clears the threshold, or at max rounds / spend cap. Fan-out of N variants in parallel.
- Human checkpoints: approve requirements, pick a variant, sign off before export.
- Variant gallery in the editor with scorecards; promote one to the main design.
- Per-role model selection and spend caps in settings.
- **Done:** from a tagged board and a one-line brief, 3 variants come back with all hard checks green within the cap; the run log shows each round's ops and check results.

## F5 · MCP server and CLI
- `packages/mcp`: the tool registry over MCP (stdio; Streamable HTTP behind a token for self-hosting). Resources: design schema, style packs, check catalogue.
- CLI: `alloy-forge run --board board.step --brief brief.md --variants 3 --out out/`; and `--watch` to re-run when the board file changes (PCB revision loop), writing a diff report.
- **Done:** Claude Code can build, check and export an enclosure through the MCP server alone; changing the board STEP re-runs the loop and reports what moved.

## F6 · Extensions, style packs, templates
- Extension manifest (id, version, kinds, permissions), sandboxed host, install from a file or URL, enable/disable per project.
- Style pack format: proportions, radius scale, edge treatment, parting-line rules, surface patterns (vents, grip textures), colour/material presets, critic rubric with reference renders.
- Enclosure templates: clamshell, slide lid, snap-fit two-part, screw-boss four-corner, rail-mount housing (generic mounting interface).
- **Done:** a style pack and a template ship as extensions, change the loop's output measurably, and can be removed without breaking a saved project.

## F7 · White-label and deployment
- `alloy.config.json` drives name, wordmark, theme tokens, default providers/models, bundled extensions, feature flags.
- Static build for self-hosting (intranet, air-gapped option: no external fetches at runtime).
- **Done:** a client-branded build is produced from config alone, with no source edits.

## Benchmark (from F3 onward)
A folder of reference boards (the client's past products and the three `glock_circuit_casing` STEP files) with expected checks. Every loop or kernel PR reports pass rate, rounds and spend against it.
