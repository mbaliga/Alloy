# Alloy consolidation: Phase 1 inventory

Status: **DRAFT for owner approval.** Nothing has been merged, pushed to another branch, closed, tagged or deleted. Phases 2 to 6 wait for approval of §6 (dispositions) and §2 (decisions).

Inventory taken 2026-11-25 against `origin` as fetched that day: 33 branch tips (plus this branch) and 11 open PRs (#1, 2, 4, 5, 6, 8, 9, 12, 13, 14, 16).

## 1. Summary

- **Canonical slicer line: `codex/a1mini-filament-route-matrix` (PR #12, tip `6e8fc0b`, 2026-10-25).** It is the newest Android app line, it contains 11 other tips, and its Android acceptance lane is green (222 connected instrumentation tests, 0 failures). Reasons are in §4.
- Little real work exists outside it. `claude/brave-goldberg-uccywx` = canon + main's licence + iOS and Ubuntu Touch ports. The other `codex/*` tips are older states of the canon. The `stage1-*` tips hold the abandoned "Path B" (SliceBeam/PrusaSlicer desktop) gate experiments, which the canon's overlay-based G3 tooling has superseded. §5 lists 4 port items.
- **Four blockers must be decided before Phase 2** (§2): the licence (the engine is AGPL-3.0 and main is PolyForm NC), owner-supplied models committed in a public repo, a parity gate that has never passed, and whether the iOS/Ubuntu Touch ports come in.
- **Problem found on main:** main tracks `forge/node_modules/` and `forge/dist/`, about 135 MB, although `forge/.gitignore` and CLAUDE.md exclude both. They were added in `99337ac` (#18). The Phase 2 root reconciliation should untrack them (§7).

## 2. Decisions needed from the owner

| # | Decision | Facts | Recommendation |
|---|---|---|---|
| D1 | **Licence of the Slicer** | The canon's root `LICENSE` is the full GNU AGPL-3.0. Its native tree (`app/src/main/jni/`, from SliceBeam@`12b370c` and OrcaSlicer-Mobile@`d996a9c`, with a Bambu Studio lightning-grounding adaptation) is AGPL-3.0, plus LGPL GMP/MPFR/libnoise, CGAL, OpenVDB and ICU (see the canon's `THIRD_PARTY_NOTICES.md`). An APK that links this engine is a combined work, and it cannot be distributed under PolyForm Noncommercial, which adds a field-of-use restriction that the AGPL forbids. brave-goldberg already hit this and set the root to AGPL, keeping PolyForm under `LICENSES/` "pending owner call". | Per-directory licensing. The repo root and `forge/` (later `draft/` and `packages/`) stay PolyForm NC 1.0.0. `android/` carries `android/LICENSE` = AGPL-3.0 plus its `THIRD_PARTY_NOTICES.md`, and the root README states the split. Alloy-authored code inside `android/` can still be dual-licensed by its copyright holder, but the Slicer as shipped is AGPL, which changes the "Commercial edition" story in `docs/ALLOY_FAMILY.md` for the Slicer only. Not legal advice; confirm with counsel before the first native-enabled release. |
| D2 | **Owner-supplied geometry in a public repo** | 9 files under `app/src/debug/assets/` (2.7 MB Redmagic keyboard-case STEP assembly, 5 Redmagic STLs, `a1-mini-v5-owner.3mf`, `catalog-private.json`, and `visuals/a1-mini-reference.mesh`, which the app's own README calls "unlicensed") were added in `7915526` (2026-09-21). They are in 15 tips (every 630 MB `codex/*` tip and brave-goldberg). **The repo is public, so these files can be downloaded today.** The rules say never commit client geometry or anything from outside the repo. | (a) Before the merge, rewrite a **copy** of the canon line with `git filter-repo` to drop those paths, so main never carries them. Commits keep their order, authorship and messages, but their SHAs change. (b) Do not create `archive/*` tags at tips that contain them; record those tip SHAs in this file instead (§6). Then deleting the branches leaves the blobs reachable only through closed-PR refs, which needs GitHub Support to purge. (c) Keep the files privately and load them through the existing `-PalloyIncludeSuppliedReferenceVisuals` / private-build path from outside the repo. If the owner does not consider them sensitive, skip (a) and (b) and just untrack them in Phase 2. |
| D3 | **"Both CI lanes green" vs the strict G3 parity gate** | The native workflow builds the engine, passes API-35 instrumentation and runs the five-fixture pilot, then fails at "Compare native G3 output with exact-PR-head Orca reference" (run 40194718498 on `6e8fc0b`). Print time is 9 to 18% low and filament 2.0 to 2.7% low against a 2% tolerance, and the two sides report different time metrics. The gate has **never passed** on this line: the green native runs (last `05981d7`, 2026-10-16) were push events that skip it. The canon's docs say explicitly: do not loosen thresholds. | Split it. A required "slicer native" lane covers dependency build, compile, instrumentation and pilot, and must be green. The G3 comparison becomes a separate, **non-required** "slicer parity gate" job that stays red and is reported as the printing NO-GO it is. Do not change the threshold. Otherwise Phase 5 cannot honestly be green. |
| D4 | **iOS and Ubuntu Touch ports** (brave-goldberg, PR #16) | Two small, self-contained trees: `ios/` (25 files, 0.6 MB: a SwiftUI shell plus a Swift `AlloyCore`) and `ubuntu-touch/` (35 files, 0.1 MB: Qt/QML plus a C++17 `AlloyCore`), each reimplementing G-code safety, a plate planner and an STL reader. Their CI lanes were green on the tip (2026-11-17). They are off the Android-first plan in `ALLOY_FAMILY.md` and `PORTING_NOTE.md`, and the iOS lane uses macOS runners (more minutes, more artifacts). | Port them as separate commits into `slicer-ports/ios/` and `slicer-ports/ubuntu-touch/`, with path-filtered workflows (`slicer-ports/**`), so they cost nothing when untouched. The three duplicated `AlloyCore`s are input to the Phase 3 shared-core plan. Alternative: archive only. |
| D5 | **Git LFS or a fetch script** | The canon tree is 632 MB in 52,809 files: `jniImports/` (Boost 1.85 static libs and headers) 315 MB, `occt/` (OCCT headers and `.so`) 162 MB, `jni/` engine source 105 MB, `jniLibs/` 36 MB. There are 75 `.a`/`.so` files, the largest 33 MB, and none over GitHub's 50 MB warning. The repo pack is 169 MB today, since the objects are already pushed. | **No LFS.** Migrating means rewriting the history of every line, and free LFS quota (1 GB of bandwidth a month) would run out after a couple of CI checkouts. Keep the prebuilts in-tree in Phase 2. Forge CI uses a sparse checkout that skips `android/`. A later fetch script for `jniImports/` and `occt/` would only cut new checkouts, not history; revisit if the pack nears 1 GB. The existing `cleanup-artifacts.yml` stays as is. |
| D6 | `COMMERCIAL-LICENSE.md` from PR #9 | PR #9's `LICENSE` is byte-identical to main's (#15). Its only extra is a 9-line `COMMERCIAL-LICENSE.md` that points to `asystemofcells.com/contact`. | Close #9 as superseded by #15. Port that file only if the owner wants that contact route in Alloy (port item P4). |

## 3. Branch inventory

Abbreviations: "ahead/behind" is relative to `main`. "Contained in" means the tip is an ancestor of another tip. "Patch-unique" counts commits not in brave-goldberg by `git cherry` (patch-id). "CI on tip" comes from GitHub Actions runs on the exact tip SHA. "Tree" is the size and file count of the checked-out tree. The licence is the root `LICENSE`.

### 3.1 Forge and family (already on main)

| Branch | Last commit | Ahead/behind | Contained in | Content | PR |
|---|---|---|---|---|---|
| `main` | 2026-11-25 `40f15d1` | 0/0 | — | Forge, docs, CLAUDE.md, PolyForm LICENSE, cleanup workflow. Tree 136 MB / 2,325 files (mostly tracked `forge/node_modules`). | — |
| `alloy-family-plan` | 2026-11-20 | 0/7 | main | Family plan, roadmap, Draft spec, style | #18 merged |
| `forge-web-editor` | 2026-11-25 | 0/5 | main | Forge editor | #17 merged |
| `forge-loop-engine` | 2026-11-24 | 0/6 | main, forge-web-editor, forge-occt-kernel | Design loop, vendored loop engine `8141a7e` | #19 merged |
| `forge-occt-kernel` | 2026-11-25 | 0/5 | main | OCCT kernel, document v3, PCB template | #20 merged |
| `codex/local-alloy-main-2026-10-22` | 2026-08-31 | 0/11 | main and 8 others | Old main snapshot (cleanup workflow) | — |
| `claude/great-fermat-e7alky` | 2026-11-16 | 1/9 | — (squash-merged; patch already on main) | PolyForm licence | #15 merged |
| `claude/github-storage-cleanup-7848fg` | 2026-08-31 | 1/12 | — (squash-merged; patch already on main) | Actions-artifact cleanup | #7 merged |

**Forge on main, checked locally:** `cd forge && npm test` gives **36/36 pass** (Node 22, about 61 s), and `npm run build` succeeds. The build rewrites the tracked `forge/dist/app.js` and `index.html`, which shows the committed `dist/` is stale.

### 3.2 Slicer: Android app lines (`app/` at the repo root, AGPL `LICENSE`, about 632 MB, 75 prebuilt `.a`/`.so`)

All of these put a Gradle Android app at the repo root (`app/`, `build.gradle`, `settings.gradle`, `gradle.properties`) next to `ci/`, `parity/`, `tools/`, `docs/`, `release/`, `marketing/`, `branding/` and `third_party/` (submodules `orca-official` → OrcaSlicer, `orca-deps-libnoise`). Their root `docs/`, `README.md`, `LICENSE` and `.github/` collide with main's.

| Branch | Last commit | Ahead/behind main | Contained in | Not in canon (by SHA / by patch) | CI on tip | PR |
|---|---|---|---|---|---|---|
| **`codex/a1mini-filament-route-matrix`** (canon) | 2026-10-25 `6e8fc0b` | 813/12 | brave-goldberg | — | Android v1 ✅, parity tooling ✅, Orca snapshot ✅, desktop Orca reference ✅, profile compatibility ✅; **native ❌ at the G3 parity step only** (D3) | #12 open (base: #13's branch) |
| `claude/brave-goldberg-uccywx` | 2026-11-17 `2885e72` | 829/8 | — | 0 (it is a superset). It adds ports, licence merge, `PORTING_NOTE.md`, `docs/IOS_PORT.md`, `docs/UBUNTU_TOUCH_PORT.md` | Android v1 ✅, iOS ✅, Ubuntu Touch ✅, Linux CLI ✅, parity ✅; native ❌ (G3), G1 OrcaSlicer-Mobile source build ❌, G2 provenance ❌ | #16 open draft |
| `codex/luna-estimate-breakdown` | 2026-10-24 | 806/12 | canon | 0 | never run | — |
| `codex/tree-support-pruning-pr12` | 2026-10-21 | 739/12 | — | 26 / 16, all older forms of canon work (TPU/PETG/PVA recipes are in the canon); one label difference, see P3 | never run | — |
| `codex/luna-next-mvp` | 2026-10-21 | 700/12 | — | 2 / 1 (tree-support pruning; the canon has it plus `test_tree_support_floating_pruning.py`) | never run | — |
| `codex/luna-bbl-counterfactual` | 2026-10-20 | 698/12 | — | 5 / 0 | never run | — |
| `codex/luna-caption-followup` | 2026-10-22 | 677/12 | — | 25 / 12, mostly older forms; AMS Lite notes (P2) and time label (P3) | never run | — |
| `codex/native-probe-follow-up` | 2026-10-18 | 643/12 | — | 2 / 2 (docs, and a label commit also in the canon) | never run | — |
| `codex/local-a1mini-filament-route-matrix-2026-10-22` | 2026-10-18 | 641/12 | — | 2 / 0 | never run | — |
| `codex/integrate-material-on-native-fix` | 2026-10-13 | 497/12 | — | 1 / 0 | Android v1 ✅, native ✅ (push, so G3 skipped) | — |
| `codex/fix-native-test-status` | 2026-10-13 | 483/12 | canon and 9 others | 0 | Android v1 ✅, native ✅ | #13 open |
| `codex/learning-faq-coverage` | 2026-10-13 | 479/12 | — | 4 / 4 by patch, but FAQ content is in the canon (#10 merged it into phone-first-mvp) | Android v1 ✅ | #10 merged |
| `codex/alloy-phone-first-mvp` | 2026-10-13 | 477/12 | canon and 10 others | 0 | no run on tip; parent ❌ | — |
| `codex/g2-android-evidence-update` | 2026-10-13 | 476/12 | — | 1 / 0 | never run | #11 merged |
| `codex/local-alloy-phone-first-ui-2026-10-22` | 2026-10-22 | 266/12 | — | 5 / 1 (older learning assets and CI validators; the canon's are newer) | Android v1 ✅, native ✅ | — |

### 3.3 Slicer: other lines

| Branch | Last commit | Ahead/behind | Layout / weight / licence | Not in canon | CI on tip | PR |
|---|---|---|---|---|---|---|
| `codex/alloy-v1` | 2026-11-11 | 47/12 | A separate, smaller app (`app/` at the root, 1 MB, no native engine, no LICENSE). Its `MainActivity` is about 7,000 lines shorter than the canon's. | 4 / 4: raster adaptive launcher icon and splash (about 390 KB of PNG), dropping `setup-android@v3`, a SIGPIPE guard | Android v1 ✅; G1 ❌, G2 ❌ | #14 open |
| `codex/alloy-phone-first-ui` | 2026-09-21 | 46/12 | Early UI-only app, 1 MB, no LICENSE | 3 / 2: logo and splash, marketing captures (equivalents are in the canon: `branding/`, `marketing/`, `ic_alloy_launcher`) | Android v1 ❌ | — |
| `stage1-foundation` | 2026-08-25 | 17/12 | Docs/CI only (14 files), no LICENSE | 2 / 2: `docs/CODEX_HANDOFF.md` (historical handoff) | G1 ❌, G2 ❌ | #1 open draft |
| `stage1-gates-prep` | 2026-08-24 | 32/12 | Docs, CI, parity (28 files) | 1 / 1: early A1 Mini profile audit (the canon's is newer) | Path B G2 ✅, parity ✅; Path B G1 SliceBeam build ❌ | #2 open draft |
| `stage1-path-b-g4-prep` | 2026-08-24 | 34/12 | 28 files | 0 (contained in 19 tips) | — | #3 merged |
| `stage1-path-b-g1-lean` | 2026-10-13 | 38/12 | 28 files | 7 / 7: a "lean" SliceBeam build without STEP/OCCT, which the canon reversed on purpose (it ships OCCT STEP import) | lean G1 ❌ | #6 open draft |
| `stage1-path-b-prusa-runtime` | 2026-10-13 | 124/12 | 34 files | 87 / 87: Path B proof on desktop PrusaSlicer 2.8 (`path-b-prusa-runtime-slice.yml`, `build_path_b_prusa_config.py`, `check_rendered_gcode_templates.py`) | ❌ (workflow appears not to parse; it has never been green) | #5 open draft |
| `stage1-runtime-parity` | 2026-10-14 | 52/12 | 43 files, with a stub `app/` (test assets only) | 10 / 10: G3 Orca recipe composition (`compose_g3_orca_profiles.py`, `verify_g3_orca_recipe.py`); the canon's `g3-bambu-filament-overlay.json` path superseded it | G4 audit ✅, desktop Orca reference ✅ | #4 open draft |
| `claude/multi-platform-porting-plan-nnvjug` | 2026-10-14 | 3/9 | `PORTING_NOTE.md` only | 0 (in brave-goldberg) | never run | #8 open draft |
| `claude/asystemofcells-redesign-s6yv4c` | 2026-10-08 | 1/9 | PolyForm LICENSE (identical to main's), `COMMERCIAL-LICENSE.md`, README | 1: `COMMERCIAL-LICENSE.md` (D6) | never run | #9 open draft |

Note: brave-goldberg's merge commit `0dd0d90` says the canon is a "superset of luna-*, native-probe, g2, integrate-material, phone-first-mvp, stage1-*". By ancestry that is not true: luna-caption-followup, luna-next-mvp, native-probe-follow-up, tree-support-pruning-pr12 and the stage1 Path B tips are not contained. By content it is mostly true, and §5 lists the exceptions.

### 3.4 Canon checked locally (Python only; no Android SDK in this container)

From a sparse worktree of `6e8fc0b` without the prebuilt native trees:

- `tools/` unittest: **26/26 pass**.
- `ci/test_*.py`, run from the repo root as `ci.*` modules: **5/5 modules pass**.
- `parity/` unittest discover: **225/232 pass**. The other 7 (`test_export_resolved_orca_profiles`) need the `third_party/orca-official` submodule, which was not initialised here; CI fetches it. A further 18 fail if `app/src/main/jni/` is left out, because the wiring tests read engine sources.
- Android build and instrumentation could not run here. Their status comes from Actions: the canon's own `docs/GATE_STATUS.md` (2026-10-25, head `67c464b`) records 222 instrumentation tests, 0 failures, 15 skipped; native build and instrumentation pass; strict G3 fails, so **printing is NO-GO**.

## 4. Canonical slicer line

**`codex/a1mini-filament-route-matrix` at `6e8fc0b`.**

1. **Newest and most complete Android app.** It contains `alloy-phone-first-mvp`, `fix-native-test-status` (#13), `luna-estimate-breakdown`, `stage1-path-b-g4-prep` (#3), and through merged PRs #10, #11 and #13, the learning/FAQ, G2 evidence and native-fix work. Only brave-goldberg contains it.
2. **Best verification record.** It has an exact-head gate log for every head through 2026-10-25, green Android acceptance, a native build that compiles and instruments, and the A1 Mini plus AMS Lite filament route matrix, A1/P1S profile exports, tree-support pruning, a machine-template probe and the learning assets.
3. **Why not brave-goldberg as canon:** it is the canon plus three things that each need their own decision: the AGPL/PolyForm licence resolution (D1), a rewritten root README, and two non-Android ports (D4). Taking the canon and porting those as separate commits keeps the Android line's history intact and makes every extra reviewable, as the brief asks.
4. **Why not the stage1 lines:** they are pre-app gate experiments for Path B (SliceBeam/PrusaSlicer desktop). The project moved to the OrcaSlicer-Mobile engine with in-tree OCCT, and the canon carries the evolved form of every stage1 doc (`ARCHITECTURE`, `STAGE_1_IA`, `UPSTREAM_EVALUATION`, `G1_BOOTSTRAP`, `BAMBU_LAN_TRANSPORT`, `GATE_STATUS`).

## 5. Work outside the canon that is worth porting

| # | Item | Source | Size | Note |
|---|---|---|---|---|
| P1 | iOS and Ubuntu Touch ports, `PORTING_NOTE.md`, `docs/IOS_PORT.md`, `docs/UBUNTU_TOUCH_PORT.md`, `.github/workflows/ios.yml`, `.github/workflows/ubuntu-touch.yml` | brave-goldberg `84ca06f`..`2885e72` (11 commits) | about 6.8k lines | If D4 = port: cherry-pick in order, path-remapped under `slicer-ports/`. `PORTING_NOTE.md` cites another repo (`Personal-Tracker/PORTING_PROGRAM.md`); keep the citation as text. |
| P2 | AMS Lite capability-catalog notes: TPU grade caveat, "unsupported for AMS Lite" note, `ams_lite_source_url` | luna-caption-followup | 3 lines in `bambu-initial-v1.json` | Re-check against Bambu's current AMS Lite guidance, then port with a `validate_capability_catalog.py` run. |
| P3 | "Approx. engine time (unverified)" labelling on the layer and estimate sheets | luna-caption-followup, tree-support-pruning-pr12 | about 10 lines | The canon refactored this into `PrintPlanEstimate.formatTime`. Port only the wording if that method does not already say the time is unverified. Copy only; no estimator change. |
| P4 | `COMMERCIAL-LICENSE.md` | PR #9 | 9 lines | Only if D6 = yes. Lives at the root (PolyForm side). |

Considered and **not** worth porting: alloy-v1's raster launcher icon and splash (the canon has its own `ic_alloy_launcher`; owner may prefer the alloy-v1 art); alloy-v1's removal of `setup-android@v3` (the canon's Android lane is green with it); Path B Prusa runtime, lean no-STEP build, and G3 recipe composition (superseded, see §4); `CODEX_HANDOFF.md` (historical, kept by the archive tag).

## 6. Dispositions

**Merge** = bring into main in Phase 2. **Port** = take named items as separate commits, then archive. **Archive** = tag `archive/<branch>` at the tip, then delete the branch (Phase 6). **Delete** = already in main; no tag needed. If D2 is (b), tips carrying the owner models are recorded here by SHA instead of being tagged.

| Tip | Tip SHA | Disposition | Carries D2 files |
|---|---|---|---|
| `codex/a1mini-filament-route-matrix` | `6e8fc0bc` | **Merge** under `android/` (filtered copy if D2 = a) | yes |
| `claude/brave-goldberg-uccywx` | `2885e722` | **Port** P1 (if D4), then archive | yes |
| `codex/luna-caption-followup` | `e2fec8c9` | **Port** P2, P3, then archive | yes |
| `codex/tree-support-pruning-pr12` | `8ff01f85` | Archive (P3 is covered from luna-caption-followup) | yes |
| `claude/asystemofcells-redesign-s6yv4c` | `130e1295` | **Port** P4 (if D6), then archive | no |
| `codex/fix-native-test-status` | `5e52089c` | Archive (contained in canon) | yes |
| `codex/alloy-phone-first-mvp` | `3c0344f4` | Archive (contained) | yes |
| `codex/luna-estimate-breakdown` | `f3bbff93` | Archive (contained) | yes |
| `codex/luna-next-mvp` | `9fb032fe` | Archive | yes |
| `codex/luna-bbl-counterfactual` | `0ad95167` | Archive | yes |
| `codex/native-probe-follow-up` | `075486c1` | Archive | yes |
| `codex/local-a1mini-filament-route-matrix-2026-10-22` | `fbd294fd` | Archive | yes |
| `codex/local-alloy-phone-first-ui-2026-10-22` | `e487da6a` | Archive | yes |
| `codex/integrate-material-on-native-fix` | `608a515d` | Archive | yes |
| `codex/learning-faq-coverage` | `b6dc0a13` | Archive | yes |
| `codex/g2-android-evidence-update` | `7b5b3b9d` | Archive | yes |
| `codex/alloy-v1` | `aa482f7b` | Archive | no |
| `codex/alloy-phone-first-ui` | `926b2585` | Archive | no |
| `claude/multi-platform-porting-plan-nnvjug` | `0f6bd9f7` | Archive (content arrives with P1) | no |
| `stage1-foundation` | `76d812c3` | Archive | no |
| `stage1-gates-prep` | `4a08926a` | Archive | no |
| `stage1-path-b-g4-prep` | `09873af7` | Archive | no |
| `stage1-path-b-g1-lean` | `aa38a3f3` | Archive | no |
| `stage1-path-b-prusa-runtime` | `25988540` | Archive | no |
| `stage1-runtime-parity` | `0e7d7881` | Archive | no |
| `claude/great-fermat-e7alky` | `b3188ee5` | Archive (squash-merged as #15) | no |
| `claude/github-storage-cleanup-7848fg` | `efa7efcd` | Archive (squash-merged as #7) | no |
| `codex/local-alloy-main-2026-10-22` | — | Delete (ancestor of main) | no |
| `alloy-family-plan`, `forge-web-editor`, `forge-loop-engine`, `forge-occt-kernel` | — | Delete (merged; listed in the brief) | no |

### Open PRs

| PR | Head | Disposition | Comment to leave (Phase 6) |
|---|---|---|---|
| #12 | canon | Close **after** the Phase 2 merge lands (its base is #13's branch, not main) | "Merged into main under `android/` by the Phase 2 consolidation PR" |
| #13 | fix-native-test-status | Close: contained in #12 | Superseded by the Phase 2 consolidation PR |
| #16 | brave-goldberg | Close after P1, or as archived if D4 = no | Ports landed under `slicer-ports/`, or archived |
| #14 | alloy-v1 | Close: separate, older app line | Superseded; tagged `archive/codex/alloy-v1` |
| #9 | asystemofcells licence | Close: superseded by #15 | Superseded by #15; P4 if approved |
| #8 | porting plan | Close: content is in #16 / P1 | — |
| #1, #2, #4, #5, #6 | stage1-* | Close: Path B gate experiments, superseded by the canon's Orca-Mobile line | Link the slicer roadmap (Phase 4) |

## 7. Phase 2 plan (for review now, to run after approval)

1. On `claude/alloy-consolidation-phase-2-*`, start from the canon tip (filtered copy if D2 = a). One commit does `git mv` of everything except `.github/` and `.gitmodules` into `android/`. It rewrites `.gitmodules` paths to `android/third_party/...`, and in every slicer workflow it adds `working-directory: android`, prefixes path filters with `android/` and changes the hard-coded push branches (`stage1-gates-prep`, `codex/alloy-phone-first-mvp`) to `main`.
2. `git merge --no-ff` that branch into a branch from main. The merge base is `af4046a` (the shared initial commit), so history is kept. Resolve root conflicts: `README.md` (family README; the Slicer README moves to `android/README.md`), `LICENSE` (per D1), `.github/workflows/cleanup-artifacts.yml` (keep main's 6-hourly version), and a root `.gitignore` that covers both apps.
3. Port P1 to P4 as separate commits.
4. `git rm --cached -r forge/node_modules forge/dist` (they stay ignored; CI runs `npm ci`). This needs the Forge build and tests to work from a clean `npm ci`, which Phase 5's CI checks.
5. Never force-push main; land everything through the PR.

## 8. Method

`git fetch --all`; per-tip `git rev-list --count`, `git merge-base --is-ancestor` across all pairs, `git cherry` against brave-goldberg for patch-equivalence, and for each tip a content diff against the canon limited to files the tip changed since its fork, to separate "older version of canon work" from "work the canon lacks". Tree weight from `git ls-tree -r -l`. Licences from each tip's `LICENSE` and `THIRD_PARTY_NOTICES.md`. CI status from GitHub Actions runs on each exact tip SHA (first 100 runs per branch). Local test runs as in §3.1 and §3.4.
