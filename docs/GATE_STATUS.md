# Alloy engine gate status

Date: 2026-10-13

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

Current evidence (2026-10-13):

- **G1 reproducible arm64 Android source build — IN PROGRESS / NOT PASSED.** The no-STEP lean build is running from source (run `38345683544`); the earlier run `38344141994` failed during dependency compilation. A separate optional native build on the A1 Mini profile PR remains in dependency compilation. Neither produced a verified native APK yet.
- **G2 Prusa engine provenance — NOT VERIFIED.** Do not infer a pass from the profile translator or desktop CLI.
- **G3 slicing/parity — REFERENCE BASELINE PRODUCED; PATH B PARITY NOT PASSED.** The pinned OrcaSlicer 2.4.2 AppImage successfully exported and validated `.gcode.3mf` for all three deterministic A1 Mini fixtures (run `38345407762`). The translated Prusa 2.8 runtime proof is still failing while resolving Bambu A1 Mini start/end G-code placeholders (latest at this edit: run `38345915581`). This is a parser/runtime precondition only, not Android parity or print approval.
- **G4 profile audit — STATIC AUDIT PASSED; RUNTIME FIDELITY NOT COMPLETE.** The pinned Orca machine/process/filament inheritance and field mapping audit passed (run `38345407728`). Engine acceptance, estimates/toolpaths, and physical behavior remain unproven.

The parser work is restricted to explicit mappings for the pinned single-extruder A1 Mini PLA Basic route. Each unsupported vendor template feature must remain visible in the translation report; a green template parser check alone cannot qualify direct send, multi-material behavior, other materials, or physical printing.

## Product/UI gate

Unchanged: **no Alloy Compose product screens until Stage 1 IA receives explicit sign-off.**

