# Alloy Slicer roadmap

Built on 2026-11-25 from the gate documents the slicer carried on its branches (`stage1-*`, `codex/*`) and the current gate log, `android/docs/GATE_STATUS.md`. Status words below are the documents' own; where a document is out of date or two documents disagree, that is said (section 6). This file describes the Slicer; the family plan is `docs/ALLOY_FAMILY.md`, Forge's is `docs/FORGE_ROADMAP.md`.

## 1. Where it stands

The Android app exists and works as software: import (STL, OBJ, 3MF, STEP with the native OCCT importer), prepare, slice with an optional native engine, inspect, export a `.gcode.3mf`, and LAN transport code for the A1 Mini. Its exact-head Android acceptance run passed 222 connected instrumentation tests with 0 failures and 15 skipped (`GATE_STATUS.md`, 2026-10-25, head `67c464b`).

**It is not qualified to print.** The newest gate status reads "Android PASS, native instrumentation PASS, **strict G3 FAIL / NO-GO**" and "**Status: NO-GO for printing and Handy handoff.**" A real A1 Mini physical acceptance, A1/P1S runtime qualification, Bambu Handy acceptance, production signing and release approval are all open. No newer status exists than 2026-10-25.

## 2. Which engine

The documents name three routes (`android/docs/UPSTREAM_EVALUATION.md`, `OFFICIAL_ORCA_G2_PORT_PLAN.md`):

| Route | What | Verdict in the documents |
|---|---|---|
| Primary candidate | Extract the engine from `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9c` (a SliceBeam fork) | **Rejected at G2** on 2026-08-23 (`stage1-gates-prep`): 250 of 488 common source files match (51.23 %). A re-audit on 2026-10-18 gives 314 of 479 (65.55 %), still below the unchanged 85 % threshold. |
| Path B (fallback 1) | `SliceBeam@12b370c`, PrusaSlicer-derived core, Orca profile import experimental | "Activated" 2026-08-23. Its branches ran Orca 2.4.2 reference slices and translated PrusaSlicer 2.8.0 slices: strict 2 % parity **failed** (for example cube time +6.31 % and filament +13.21 %), "not parity-qualified, not printer-ready". G1 for it did not pass. |
| Official Orca port (fallback 2) | A clean Android port of official OrcaSlicer | Only a compile and link probe exists (PASS 2026-10-13, "not integrated"). Planned in `OFFICIAL_ORCA_G2_PORT_PLAN.md`. |

**The tree that ships today (the optional native engine) is the rejected OrcaSlicer-Mobile tree**, built into the app and gated by the checks above (`NATIVE_ENGINE_INTEGRATION.md`). So the engine decision is not closed; it is the first open item (section 5).

## 3. Gates

Go or no-go rule (`UPSTREAM_EVALUATION.md`): G1 to G4 all pass is GO; a material failure is NO-GO and the next route is taken.

| Gate | What must be true | Acceptance | Status in the documents | Biggest blocker |
|---|---|---|---|---|
| **G1** Reproducible build | A clean clone builds the Android app from source (SDK 35, NDK 23.1.7779620) | `assembleDebug` from source | `G1_BOOTSTRAP.md`: "Open". The native engine does build in CI (run 40066724622), but no document records a G1 pass. | GMP/MPFR provenance; no G1 verdict for the shipped engine |
| **G2** Source lineage | The exact engine version is pinned by source lineage | 85 % exact-match threshold | **FAIL / NO-GO** for the primary candidate (above). `GATE_STATUS.md` now only says lineage review "remains open". | The shipped engine is the rejected tree |
| **G3** G-code parity | Same G-code behaviour as desktop Orca on the cube, overhang/support and thin-wall fixtures (a travel-obstacle fixture was added) | Semantic feature sequence equal; print time within **2 %**; filament within **2 %**; no missing Arachne walls, supports or seams | **FAIL / NO-GO** (2026-10-25, run 40066724622). All four fixtures fail time; cube, overhang and thin-wall filament miss by 2.03 to 2.65 %. The 2 % threshold is unchanged and the documents forbid loosening it. | Travel and toolpath ordering (cube: 2,435 travel moves on desktop, 1,059 on Alloy), an unmodelled startup purge, and different time metrics (`model_printing_time` against `estimated_printing_time`) |
| **G4** A1 Mini profile fidelity | The A1 Mini profile matches the desktop-loaded profile field by field, with no silent fallback | Field-by-field comparison | No G4 verdict line. All 64 source-backed profile-identity fields pass and the profile compatibility pre-gate passes; runtime fidelity is not shown. `release/production-readiness.template.json` lists `*_g4_profile_runtime_parity` as PENDING for A1 Mini, A1 and P1S. | Runtime fidelity; A1 and P1S unqualified |
| **G5** Bambu TreeSupport3D | The Bambu tree-support route is ported coherently | Semantic and runtime parity | **Open** ("G5 remains open until the pinned Bambu route is ported coherently and passes semantic/runtime parity"). The source audit result is `PORT_REQUIRED`: all 7 required files differ. | The port itself; supports are out of scope for the first print |

Further gates (from `GATE_STATUS.md`, `RELEASE.md`, the acceptance documents):

| Gate | Status |
|---|---|
| Stage 1 IA gate | `STAGE_1_IA.md`: "Implemented as the Android Views contract; the Alloy-owned GLES viewport is now in place, while native engine and hardware gates remain open." No approval record exists. |
| No-support PLA pilot (A1 Mini, 0.4 mm, PLA Basic, no AMS, 5 fixtures) | The software pilot ran through API-35 ARM translation only, "not a physical A1 Mini pilot". Physical and direct-send qualification are PENDING. |
| Physical A1 Mini acceptance (9 cases: discovery, upload, start, running, completion, pause/resume, cancel, recovery, offline; pinned leaf certificate) | "open"; `BAMBU_LAN_TRANSPORT.md`: "physical-printer gate not yet executed". |
| A1 and P1S runtime qualification | "open"; separate profile, toolpath and transport evidence per printer. |
| Bambu Handy handoff | NO-GO. Intent resolution is not a compatibility claim. |
| Production signing | "open"; CI uses the debug key; needs the organisation keystore and Play App Signing. |
| Release readiness | `release/production-readiness.json` must have every gate PASS with hashed evidence. The template has 17 gates, all PENDING; the real file does not exist. |

## 4. Stages

- **Stage 1:** the phone flow Import, Prepare, Slice, Inspect, Print/Export for the A1 Mini. Inputs STL, OBJ, 3MF (STEP deferrable), output `.gcode.3mf`, arm64 first. Includes the engine gates G1 to G4 and the LAN transport.
- **Stage 2:** adaptive layouts for foldables, tablets and desktop windows, behind the checkpoint of an Alloy-owned OpenGL ES renderer (which now exists). No later stage is defined.
- Linux support is frozen at a thin bridge; iOS and Ubuntu Touch exist only as preparation and inspection companions in `slicer-ports/`. The Slicer is release 2 of the family.

## 5. Roadmap (in dependency order)

| # | Milestone | Done when | Depends on |
|---|---|---|---|
| S1 | **Decide and record the engine route** | A written decision: keep the OrcaSlicer-Mobile tree or integrate official Orca. The G2 status is restated to match; the four documents that say "current Path B" and the integration note agree. | none |
| S2 | **Pass G2** for the chosen route | Source-lineage audit at or above 85 %, with the run linked in `GATE_STATUS.md` | S1 |
| S3 | **Close G3** | The four fixtures pass at the unchanged 2 % on time and filament, using one agreed time metric; the startup purge is modelled from the pinned machine sequence; travel ordering explained and fixed | S2 (a different engine changes everything) |
| S4 | **Finish G4 runtime fidelity** for the A1 Mini | Runtime parity entries for the A1 Mini move from PENDING to PASS with evidence, including start and end G-code hooks | S2, S3 |
| S5 | **Physical pilot** | The five-fixture no-support PLA pilot printed on a real A1 Mini from a fresh, source-matched pilot build, then the 9-case LAN acceptance with reviewed evidence | S3, S4 |
| S6 | **Release readiness** | `release/production-readiness.json` exists with hashed evidence for every gate; production signing set up; Bambu Handy acceptance decided | S5 |
| S7 | **Supports (G5)** and A1/P1S qualification | TreeSupport3D ported and at parity; A1 and P1S each qualified separately | S3, S4 (supports can follow the first print) |
| S8 | **Stage 2** | Adaptive layouts verified on a foldable and a tablet | S6 |

Until S3 passes, nothing the Slicer produces should be printed or handed to Bambu Handy.

## 6. Documentation to fix (found while building this roadmap)

These are disagreements between documents; none changes a status above, but each can mislead.

1. The old root README said Stage 1 was still "at the architecture and information-design gate" before Compose; the app is built, with Android Views (not Compose), and `ALLOY_FAMILY.md` still said Kotlin/Compose. (The root README and `ALLOY_FAMILY.md` are corrected in the same change.)
2. `STAGE_1_IA.md` says "Implemented" at the top and "Once explicitly approved" further down; no approval is recorded.
3. `UPSTREAM_EVALUATION.md` (2026-08-23) was never updated: it still shows G2 not passed and G3 and G4 blocked, although G2 was recorded as failed and G3 has since been run.
4. `G1_BOOTSTRAP.md`, `LINUX_ALTERNATIVES.md`, `LINUX_DESKTOP.md` and `V1_IMPLEMENTATION.md` call Path B current, while the shipped optional engine is the OrcaSlicer-Mobile tree that G2 rejected.
5. `OFFICIAL_ORCA_G2_PORT_PLAN.md` points to a run (37739472063) and an "authoritative" G2 verdict in `GATE_STATUS.md` that the current file does not contain; the G2 numbers differ between the branch (51.23 %) and the re-audit (65.55 %).
6. `android/README.md` says the app "embeds the pinned Orca libslic3r"; `OFFICIAL_ORCA_G2_PORT_PLAN.md` says the shipping engine is not integrated with official Orca. Both are true only if "Orca" means the Mobile fork.
7. `NO_SUPPORT_PLA_PILOT.md` tells the reader to wait for a run from 2026-10-11 that is stale; newer pilot artifacts exist.
