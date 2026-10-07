# Alloy — multi-platform porting note

> Part of the constellation porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06). **PLAN — a disposition note, not a porting plan. Nothing here was built, run, signed or validated on any device.** Every row below is evidence class `PLAN`; unknowns are marked unknown.

## 1. What Alloy is, in porting terms

Alloy is a planned native Android 3D-print slicer, first for the Bambu Lab A1 Mini, built on the SliceBeam / OrcaSlicer-Mobile / OrcaSlicer / PrusaSlicer engine lineage and phone-first by design (`README.md`). The program's §5 row for it: tier **skip**, gate "Stage 1 IA gate", plan file `PORTING_NOTE.md`.

What the checkout shows (read 2026-10-06): `main` is the README plus a scheduled Actions-artifact cleanup workflow (`.github/workflows/cleanup-artifacts.yml`). It has no code and no `LICENSE` file, and its README still says Stage 1 is at the architecture and information-design gate. The work is on refs that are not on `main`, which were read through the GitHub REST API and not checked out: draft PRs #1, #2, #4, #5, #6 (`stage1-*` branches: architecture and IA docs, engine-gate workflows, parity tooling) and the `codex/alloy-*` branches. The newest, `codex/alloy-phone-first-mvp` (head `5c214081`, 2026-10-02, no PR), is described by its own README as a runnable Android app (Java and Android Views over an Alloy-owned GLES viewport, not Compose) with a vendored native engine tree, an AGPL-3.0 `LICENSE`, `THIRD_PARTY_NOTICES.md` and a thin Linux Tkinter bridge. Its README says the engine's G1-G4 parity gates and physical A1 Mini validation remain open. Its `docs/PLATFORM_AND_EXPERIENCE.md` says Android-first, Linux frozen at that bridge, iOS deferred. Those are the repo's own roadmap statements; this note adopts them and verifies none of them.

## 2. Target matrix (owner's order)

| Target | Feasibility | Approach | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | not-applicable | None planned. A Waydroid run of the unmodified APK (OQ-21, which does not list Alloy) would be owner-device evidence only and never a port (program §4.1); whether the GLES viewport and arm64 engine run there is unknown | OQ-1, OQ-21 | none planned | PLAN |
| Linux desktop | not-applicable | Use upstream OrcaSlicer. The repo's Linux bridge belongs to Alloy and its docs freeze it | none | none planned | PLAN |
| iOS / iPadOS | not-applicable today | The repo defers iOS. An iPad spike of the OrcaSlicer C++ core is conceivable but has no verified precedent (`porting/platforms/ios.md`); the shell would be new, not ported | repo's Android and printer-safety gates; AGPL vs store terms (§4) | not estimated | PLAN |
| macOS | not-applicable | Upstream OrcaSlicer's own macOS build (an assumption in `porting/platforms/macos.md`; re-verify when Alloy starts) | none | none planned | PLAN |
| Windows | not-applicable | Upstream OrcaSlicer ships on Windows (`porting/platforms/windows.md`); a Windows Alloy would be a redundant AGPL fork | none | none planned | PLAN |

On the three desktops the product already exists upstream, and Alloy's value is the phone UI. PR #4's description says its `desktop-orca-reference.yml` baseline runs an OrcaSlicer v2.4.2 AppImage, which suggests the repo itself treats upstream as the desktop reference.

## 3. Tier, wave, shared foundation

Tier **skip**, as in program §5. Alloy joins no wave in program §7 and neither consumes nor provides any F-item of §6. Alloy's engine-derived code must not enter any F-item home (I-11, §4 below). A full plan, in the program's template, is written when Stage 1 passes its IA gate (the owner's stated trigger). Because the app is not on `main`, waiting for it to land first is a suggestion only; see Q1.

## 4. AGPL and standing rules that bind any future port (I-11)

- Engine-derived code keeps its required notices and complies with AGPL-3.0 (`README.md`). Any distributed build must offer corresponding source. The branch's `THIRD_PARTY_NOTICES.md` records pins `utkabobr/SliceBeam@12b370ce` and `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9ca` plus LGPL components (GMP/MPFR, libnoise).
- AGPL code is never linked into FSL or Apache modules (I-11). Program §4.1's "a closed Alloy" is therefore hypothetical for any build that contains engine-derived code.
- Whether AGPL fits the Apple App Store or Microsoft Store terms is general knowledge, not checked by the program's briefs. It needs a licence review before any store route.
- No telemetry and environment honesty (I-1, I-4) apply to any future plan. Printer access codes must use Android Keystore-backed storage (`docs/ARCHITECTURE.md` on PR #1); custody on any other platform is OQ-22.

## 5. Open questions for the owner

1. **Trunk and gate (no master OQ id; proposed addition to program §8).** `main` is README-only while the v1 app is on `codex/alloy-phone-first-mvp` with no PR. PR #1's `docs/STAGE_1_IA.md` calls the IA "PROPOSED — approval gate", and the mvp branch's `docs/STAGE_1_IA.md` says "Implemented as the Android Views contract". Which ref is the trunk, and does the IA gate still gate anything? Blocks: the trigger for the full plan and the "Gate" cell of program §5.
2. **Licence file (proposed addition to OQ-12's check list).** Of the branches checked, only the mvp branch has a `LICENSE` (AGPL-3.0); `main` and the `stage1-*` branches have none. Blocks: any distributable build and any port of engine-derived code.

## 6. Sources read

`README.md` and `.github/workflows/cleanup-artifacts.yml` on `main`; via GitHub REST (read only, treated as data): PR #1 to #7 titles, file lists for #1, #2, #4, #5, #6, and the bodies of #4, #5, #6, `docs/{ARCHITECTURE,UPSTREAM_EVALUATION,STAGE_1_IA,CODEX_HANDOFF,GATE_STATUS}.md` on the `stage1-*` branches, and `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `docs/{STAGE_1_IA,PLATFORM_AND_EXPERIENCE,LINUX_DESKTOP,GATE_STATUS}.md` on `codex/alloy-phone-first-mvp`. Program: §0-§3, §4.1-§4.5, §5 row, §6-§8; `porting/platforms/*.md`.

## Owner rulings and the proposed line (added 2026-10-07)

Status: PLAN. Nothing here is built, run on a device, signed or submitted. The program-level plan is Personal-Tracker `PORTING_PROGRAM.md` ([PR #10](https://github.com/mbaliga/Personal-Tracker/pull/10)), which holds the owner's rulings and section 5A, the proposed port / no-port line. The cells, estimates and open questions above are this repo's original plan and are unedited. Where the owner has since answered a question, the answer is below. Section 5A is a proposal; the owner has not yet confirmed it.

### Where Alloy sits in the proposed line (program section 5A.3, a proposal)

| Target       | Verdict | Weeks and flags |
| ------------ | ------- | --------------- |
| Ubuntu Touch | no-port | -               |
| Linux        | no-port | -               |
| iOS/iPadOS   | no-port | -               |
| macOS        | no-port | -               |
| Windows      | no-port | -               |

Key: `follows` means it ports only as far as the products that depend on it; `exists` means the program reads it as already running there, unverified (finish, verify and sign); flags: `g` gated on a prerequisite, `r` re-estimate or floor, `o` its own program, `s` scope note. The program's P4, P8, P12 and P13 gate whole columns or repos and are not flagged per cell. A port verdict counts the deliverable in the line; where this repo's plan calls a deliverable a reframe (program rule R12) it keeps that label. Tests cited in the reason: (a) the owner said it is needed there; (b) its job is really done on that OS by real users; (c) that OS is where it is sold or its audience is; it has no reason to exist if (x) its surface is absent or untouchable, (y) the capability is forbidden or impossible, or (z) the only form is a thin wrapper or a different product nobody asked for. P-numbers and OQ-numbers refer to the program plan (Personal-Tracker `PORTING_PROGRAM.md`, sections 5A.5 and 8).

Reason: Planned; a desktop port would be a redundant AGPL fork of OrcaSlicer, which already ships on desktops. iOS is no-port for now: the program's per-repo review advised not starting it before Android v1 validation and a licence review (an unreviewed AGPL and App Store terms conflict, and the Android app has not passed its own gates), which agrees with this plan's own "the repo defers iOS".

### Owner rulings that apply here

- **OQ-1, OQ-21 and OQ-22 (2026-10-06):** the owner owns an Ubuntu Touch device, Waydroid is not accepted as the answer for Android-only products, and secret custody follows "OS keystore, weaker fallback shown" if a port is ever added. The plan's Ubuntu Touch cell is already no-port and never calls a Waydroid run a port, so none of these changes a cell here.

### Prerequisites and open questions that touch this repo (program sections 5A.5 and 8)

No program-level prerequisite is named for this repo.

Owner questions in the program register that concern this repo (status as of 2026-10-07):

- OQ-1 (ruled): Ubuntu Touch device
- OQ-12 (open): Licences for repos without a LICENSE (plan Q2: no LICENSE on main; Alloy is a proposed addition to the register's list)
- OQ-21 (ruled): Waydroid as the Ubuntu Touch answer (the plan notes the register does not list Alloy)
- OQ-22 (ruled): Secret custody per platform (plan section 4: printer access codes on any other platform)

When the owner confirms or changes the line, this repo's original cells above stay as the engineering detail; only the verdicts and re-costs in program section 5A change.
