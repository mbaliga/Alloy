# Alloy repo guide for Claude Code

Alloy is a family of three tools on one shared core: **Alloy Forge** (web parametric 3D CAD, `forge/`), **Alloy Slicer** (native Android slicer, `android/` per the root README) and **Alloy Draft** (web precision 2D drafting, `draft/`, not started). Read `docs/ALLOY_FAMILY.md` first.

## Where things are
- `docs/ALLOY_FAMILY.md` — products, shared core, editions, default decisions, Forge/Slicer overlaps and the shared-package plan.
- `docs/FORGE_ROADMAP.md` — Forge milestones F0–F7 with acceptance criteria and honest status marks.
- `docs/SLICER_ROADMAP.md` — Slicer gates and stages, built from the stage1 gate docs, with current status.
- `docs/DRAFT_SPEC.md` — Draft scope and v1 cut.
- `docs/STYLE.md` — house style canon. UI work is checked against it.
- `docs/EXTENSIONS_SPEC.md` — plugins/extensions (one system, two tiers), the extension host (X0), the analysis framework (A0) and the five analysis packages (A1–A5).
- `docs/CONSOLIDATION.md` — how the slicer branches were consolidated: inventory, decisions, what was archived and where.
- `docs/EXTENSION_API.md` — reference for package authors, generated from `forge/src/ext/api-spec.js` (`npm run docs:api`; a test fails if it is stale).
- `forge/` — Alloy Forge web app (see `forge/README.md`; third-party notices in `forge/THIRD_PARTY.md`).
- `android/` — Alloy Slicer Android app (see `android/README.md`; slicer docs in `android/docs/`, gate evidence in `android/docs/GATE_STATUS.md`; third-party notices in `android/THIRD_PARTY.md`).
- `runner/` — Alloy Runner (A4): the HTTP job server that meshes with gmsh and solves with Elmer, in a container; the only place a container is allowed (see `runner/README.md`; third-party notices in `runner/THIRD_PARTY.md`).
- `slicer-ports/` — iOS and Ubuntu Touch companions (see their docs under `slicer-ports/docs/`).

## How to work
- One milestone (or one Draft v1 item, or one X0/A0/A1–A5 step) per session, one branch, one PR. Use the milestone's "Done" list as the definition of done and say in the PR which items are met and which are not.
- **Forge:** keep `cd forge && npm test` green; add tests for new kernel features, checks and ops. Prefer headless tests (Node) over browser tests; add a Playwright smoke test for UI flows.
- **Slicer:** there is no Android SDK in the cloud sandbox. Run what you can (`android/tools`, `android/ci` as `python3 -m unittest` from `android/`, `android/parity`), then rely on the CI lanes for Gradle and native builds. Never loosen a parity tolerance to make a gate green; the G3 comparison is a gate, not a flaky test. Do not treat a green build as a qualified print.
- The AI never edits geometry directly: every change goes through typed ops on the design document, validated, built and undoable. Locks are enforced in the ops layer.
- No telemetry, no backend calls besides the model endpoint the user configures. API keys stay in the browser or the customer's server environment.
- Follow `docs/STYLE.md`: glass panels, monospace uppercase labels, explanatory copy behind `i` tips, sounds toggleable, phone-first sheets.
- Never commit client geometry, owner-supplied models or anything from outside the repo (this repository is public). Do not commit `dist/` or `node_modules/` (both are gitignored); do not commit build output of either app.
- Never force-push `main`. Merge, do not squash, when joining long-lived lines.

## Licence
The repository root, `forge/` and `docs/` are PolyForm Noncommercial 1.0.0 (see `LICENSE`). `android/` is GNU AGPL-3.0 (`android/LICENSE`) because the slicer links AGPL engine code; engine code derived from OrcaSlicer/PrusaSlicer/SliceBeam keeps its AGPL notices. Keep third-party notices in each app's `THIRD_PARTY.md` (`forge/`, `android/`).
