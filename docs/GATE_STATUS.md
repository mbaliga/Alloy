# Alloy engine gate status

Date: 2026-08-23

## Primary candidate: OrcaSlicer-Mobile

Pinned candidate: `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`

### G1 reproducible Android source build

**Status: still running as evidence.**

This result no longer controls candidate selection because G2 has already produced a material no-go. The build is being allowed to finish so Alloy retains useful Android dependency/bootstrap evidence.

### G2 exact OrcaSlicer engine provenance

**Status: FAIL / NO-GO.**

Hosted evidence from Alloy Actions run `32658450073`:

- mapped engine files shared with official OrcaSlicer: 488
- sampled files with an exact historical blob interval: 67 / 72
- best official OrcaSlicer candidate: `ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6`
- exact common-tree matches at that candidate: **250 / 488 (51.23%)**
- required high-confidence threshold: 85%
- the closest-tree diff contains large divergences in core files including `GCode.cpp`, `GCodeProcessor.cpp`, `PrintConfig.cpp`, `Print.cpp`, `Preset.cpp`, `PresetBundle.cpp`, Arachne, support, wipe tower and model/config code

The bundled mobile `BBL.ini` Git blob was also **not found as an exact blob** in official OrcaSlicer history, so it cannot be used as a direct profile provenance pin.

This is not merely a small Android portability patch set around one identifiable Orca revision. Alloy therefore cannot truthfully claim the primary candidate tracks an exact official OrcaSlicer engine version under the adopted G2 criterion.

**Decision: primary candidate rejected. Activate Path B as required by the correction addendum.**

## Path B: SliceBeam / PrusaSlicer core

Pinned Android candidate:
`utkabobr/SliceBeam@12b370ce305acc2caa59b7e4e78e04069db2f7e3`

Path B must now pass the same discipline:

1. **G1:** reproducible arm64 Android source build.
2. **G2:** identify the closest exact PrusaSlicer engine revision by Git-blob provenance.
3. **G3:** slice the 20 mm cube, overhang/support fixture and thin-wall keyboard-case-like fixture; compare semantic toolpaths plus time/filament estimates.
4. **G4:** prove A1 Mini profile import/resolution fidelity. Because SliceBeam's native profile format is Prusa-oriented and Orca import is experimental, this is likely the hardest Path B gate.

Prepared automation on `stage1-gates-prep`:

- `.github/workflows/path-b-g1-slicebeam-source-build.yml`
- `.github/workflows/path-b-g2-prusa-provenance.yml`
- `ci/identify_slicebeam_prusa_engine.py`
- `parity/generate_fixtures.py`
- `parity/compare_gcode.py`
- `parity/profile_resolver.py`

## Product/UI gate

Unchanged: **no Alloy Compose product screens until Stage 1 IA receives explicit sign-off.**

## Current verified status (2026-10-13; supersedes earlier Path B checklist above)

### Path B desktop profile translation and runtime

- Pinned OrcaSlicer 2.4.2 reference slices for all three deterministic fixtures: **PASS** (Alloy Actions run `38348112118`, job `orca-reference`).
- PrusaSlicer 2.8.0 translated-profile slices and unresolved-template check: **PASS** (runs `38348112118` and `38348770248`, job `translated-profile-runtime`).
- Semantic comparison of Orca references against translated Prusa outputs: **FAIL** (run `38348770248`, job `semantic-parity`). The comparator regression tests and measurement corrections ran before this result.
  - Cube: 101 vs 101 printed extrusion heights; time +6.10%; filament length +10.09%.
  - Overhang/support: 171 vs 171 heights; time +18.45%; filament length +35.44%; candidate lacks the reference `overhang_wall` feature class.
  - Thin-wall frame: 42 vs 42 heights; time +56.84%; filament length +22.66%; candidate lacks the reference `gap_infill` class.
  - Thus a successful Prusa CLI slice is not evidence of Orca-equivalent profile behavior. **Path B desktop translation is not parity-qualified. Do not export or send its output as printer-ready.**
- The comparator now counts Z heights on positive extrusion moves, understands colon-delimited time estimates, ignores all-zero mass placeholders as unavailable, normalizes known cross-slicer feature aliases, includes focused regression tests, and emits compact per-fixture results while preserving full JSON reports.

### Android native engine and hardware

- Optional native-engine build in run `38343350949`: dependency source builds, Alloy native compile, instrumentation APK compile, and APK packaging have passed. Android API-35 instrumentation is **currently running**; native G3 export and isolated five-fixture pilot steps are still pending.
- A separate lean no-STEP dependency bootstrap run `38345683544` failed before APK build. The GitHub log endpoint has been intermittently unavailable; failure cause remains unverified.
- No physical A1 Mini print, successful printer telemetry confirmation, or Bambu Handy handoff is evidenced here. Direct-send and printer-ready status remain **unqualified**.

### Product release gates

- A1 Mini material/AMS Lite route matrix Android acceptance passed in run `38343351024`; it does not establish slice, export, or physical-print correctness.
- A1, P1S, multi-model real-file slicing, Android G3 parity, printer transfer, interruption/recovery, onboarding/troubleshooting content, and release/device testing remain open. No production release claim is warranted.
## Superseding evidence (2026-10-13)

The earlier semantic run `38350223253` is superseded by run `38355683497` on PR #5 head `5940ef170b1a1fa2f574eac1eb668e9581234141`.

- Both Orca reference and translated Prusa 2.8.0 runtime slices succeeded for all three deterministic fixtures.
- Feature-class aliases are now normalized correctly: the report finds no missing major feature classes for cube, overhang/support, or thin-wall fixtures. This corrects the earlier label-only reports (`Overhang perimeter` vs `Overhang wall`; `Gap fill` vs `Gap infill`).
- The parity gate still **fails** on real reported estimate deltas (2% tolerance): cube time +6.10%, filament +10.09%; overhang time +18.45%, filament +35.44%; thin-wall time +56.84%, filament +22.66%. The annotation correction does not resolve or waive these numeric mismatches.
- Consequently the translated desktop profile is still **not parity-qualified** and must not be treated as printer-ready or sent to hardware.

Native workflow repair is tracked separately in PR #13 (`codex/fix-native-test-status`, head `ae48c42c313246815e2236f133f0bf96fb51e811`). The earlier API-35 run completed its instrumentation tests (196 reported, zero failures, two skipped) but failed afterward in shell status handling; the G3 export and five-fixture pilot were not executed. Corrected checks are running. Until they produce the expected native artifacts, Android G3/pilot evidence remains absent.

No physical A1 Mini print, transfer/telemetry confirmation, or Bambu Handy round-trip is evidenced. Direct send, exported-job handoff, and production readiness remain unqualified.


## Superseding evidence (2026-10-13, run 38359783217)

This section supersedes earlier Path B semantic metrics above. PR #5 head is `311667722c09998162eec6db499b2a1298a631d5`.

- Both pinned Orca 2.4.2 reference slicing and translated PrusaSlicer 2.8.0 runtime slicing completed for all three fixtures. The translated-profile job confirmed explicit support-enabled profiles and found no unresolved template directives in executable G-code.
- The semantic-parity job confirmed support toolpaths in both overhang outputs and reported matching extrusion-layer counts and feature classes, but **failed** the strict 2% threshold:

| Fixture | Time delta | Summary filament delta | Positive-E length delta |
| --- | ---: | ---: | ---: |
| 20 mm cube | +6.31% | +13.21% | +4.48% |
| Overhang with supports | +6.35% | +28.57% | +11.67% |
| Thin-wall frame | +28.36% | +22.42% | +4.05% |

- The reported summary filament estimate and parsed positive extrusion length still disagree materially, particularly on the supported overhang. Neither metric is waived; this needs a validated like-for-like measurement definition before parity can pass.
- Crosshatch translation to Prusa Grid remains explicitly approximate, not equivalent. The desktop profile translation is **not parity-qualified**, not printer-ready, and must not be presented as approved for export/send.

Native Android evidence remains tracked separately in PR #13 (`ae48c42c313246815e2236f133f0bf96fb51e811`). Its ordinary Android workflow passed (run 38355476587). The corrected optional native-engine run 38355476630 is still active at the native-engine build step; instrumentation, native G3 export and the five-fixture pilot remain pending. No physical A1 Mini print, successful printer telemetry confirmation, or Bambu Handy round-trip is evidenced. Direct send, validated Bambu-ready handoff and production readiness remain unqualified.
