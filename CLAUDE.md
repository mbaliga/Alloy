# Alloy repo guide for Claude Code

Alloy is a family of three tools on one shared core: **Alloy Forge** (web parametric 3D CAD, `forge/`), **Alloy Slicer** (native Android slicer, `android/` per the root README) and **Alloy Draft** (web precision 2D drafting, `draft/`, not started). Read `docs/ALLOY_FAMILY.md` first.

## Where things are
- `docs/ALLOY_FAMILY.md` — products, shared core, editions, default decisions.
- `docs/FORGE_ROADMAP.md` — Forge milestones F0–F7 with acceptance criteria.
- `docs/DRAFT_SPEC.md` — Draft scope and v1 cut.
- `docs/STYLE.md` — house style canon. UI work is checked against it.
- `forge/` — current Forge web app (see `forge/README.md`).

## How to work
- One milestone (or one Draft v1 item) per session, one branch, one PR. Use the milestone's "Done" list as the definition of done and say in the PR which items are met.
- Keep `npm test` green; add tests for new kernel features, checks and ops. Prefer headless tests (Node) over browser tests; add a Playwright smoke test for UI flows.
- The AI never edits geometry directly: every change goes through typed ops on the design document, validated, built and undoable. Locks are enforced in the ops layer.
- No telemetry, no backend calls besides the model endpoint the user configures. API keys stay in the browser or the customer's server environment.
- Follow `docs/STYLE.md`: glass panels, monospace uppercase labels, explanatory copy behind `i` tips, sounds toggleable, phone-first sheets.
- Do not commit `dist/` or `node_modules/`.

## Licence
PolyForm Noncommercial 1.0.0 (see `LICENSE`). Keep third-party notices in each app's `THIRD_PARTY.md`. Engine code derived from OrcaSlicer/PrusaSlicer keeps its AGPL notices.
