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
