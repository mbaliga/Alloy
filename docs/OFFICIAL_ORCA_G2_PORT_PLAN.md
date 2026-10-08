# Official Orca G2 remediation plan

Status: **source snapshot complete; separate Android target not implemented; no engine promotion.**

## Why a port is required

The current Android-native candidate is
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`.
The latest fresh official-history checkpoint recorded in
[`GATE_STATUS.md`](GATE_STATUS.md) is run
[`37739472063`](https://github.com/mbaliga/Alloy/actions/runs/37739472063):
314 of 479 common native source files match the best history-constrained
candidate (65.55%), below G2's unchanged 85% threshold, and the profile anchor
has no exact introduction in official Orca history. A later current-ref tree
snapshot is a separate diagnostic and does not replace the history result.
The current candidate is not eligible for a production engine claim.

The authoritative G2 status remains **FAIL / NO-GO** in
[`GATE_STATUS.md`](GATE_STATUS.md). This plan does not promote the current
engine, enable direct print, or relax Bambu TreeSupport3D or physical-printer
gates.

## Reproducible scope record

The official baseline is deliberately pinned before any migration work:

```sh
git clone --filter=blob:none --no-checkout https://github.com/OrcaSlicer/OrcaSlicer.git official-orca
git -C official-orca checkout --detach ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6
git diff --no-index --stat -- \
  app/src/main/jni/libslic3r official-orca/src/libslic3r
```

At the recorded baseline, official `src/libslic3r` contains 428 files and the
tree comparison reports 384 changed/added/removed files. The high-impact
differences include model/config, G-code generation, support, Arachne,
arrange, profile, wipe-tower and CMake integration. A partial copy is not an
official engine and must never be described as one.

## Port stages

1. **Hermetic source snapshot — complete as a source-only checkpoint**
   - Import the exact official source with its license notices and a manifest
     of source paths, Git blobs and SHA-256s.
   - Add a validator that fails if the imported source drifts from the pinned
     official commit, except for an explicit Android adaptation patch series.
   - Keep the existing Android engine selectable only as `legacy-unverified`.
   - Evidence: the pinned snapshot and manifest workflow validate official
     source identity and license notices. This does not compile that source
     for Android or satisfy G2.

2. **Android build closure**
   - Port the official CMake source list and direct dependency closure into a
     separate `official-orca` target; do not overwrite the currently compiling
     legacy target in place.
   - The port-surface audit records direct `libslic3r` package differences,
     but it is an inventory rather than a build. The pinned `libnoise` source
     has since been built in CI and linked by the existing legacy Android
     target; that closes only this dependency for that target. It does not
     create the distinct official-Orca Android target or establish the full
     official dependency closure. Preserve both the legacy and official
     package requirements when building that separate target.
   - Reuse only pinned, source-built Android dependencies (Boost, oneTBB,
     OCCT, GMP and MPFR) and record every new dependency and license.
   - Produce an ARM64 shared library in clean hosted CI before exposing JNI.
   - Current checkpoint: `app/cmake/official-orca/Findlibnoise.cmake` adapts
     Orca's `find_package(libnoise)` to the already-created pinned source
     target, and configure-level tests verify both target-present and
     target-missing behavior. Official-source run
     [`37819859908`](https://github.com/mbaliga/Alloy/actions/runs/37819859908)
     passed those tests, the pinned source manifest, the direct port-surface
     audit, and a host build of libnoise. The CMake source-selection inventory
     lists 190 official C++ sources and 204 legacy target sources: 184 paths
     overlap, while six official paths are not selected by the current target
     (`CurveAnalyzer.cpp`, `Format/svg.cpp`,
     `Interlocking/InterlockingGenerator.cpp`, `Interlocking/VoxelUtils.cpp`,
     `Orient.cpp`, and `Shape/TextShape.cpp`). This compares source selection,
     not file contents or semantics. The adapter is not yet wired to a separate
     `libslic3r` target; there is still no official-Orca Android binary or
     runtime evidence.

   - Newly confirmed build blocker: the pinned official CMake requires
     `find_package(OpenCV REQUIRED core)` and unconditionally links
     `opencv_world`. Its `ObjColorUtils.hpp` uses OpenCV APIs for OBJ color
     quantization. The Android app tree currently contains no OpenCV package
     configuration, headers, or ABI library artifact, so the separate target
     cannot configure/link by adding the six missing C++ files alone. The next
     build-closure step must provide a version-pinned Android OpenCV dependency
     with the APIs used by the official source and prove package discovery and
     ARM64 linking in hosted CI; do not stub color quantization or silently
     omit the dependency.

3. **Narrow JNI adapter**
   - Preserve Alloy's bounded model staging, typed recipe serializer,
     cancellation, output-size limits and G-code safety scanner.
   - Add a distinct JNI namespace/version so a legacy library cannot be loaded
     as the official candidate by mistake.
   - Pass model-read, slice, cancellation and package-validation smoke tests
     on an ARM64 emulator.

4. **Engine/profile parity**
   - Re-run G2 against the imported tree; exact source provenance must pass.
   - Run G3 fixture comparisons (cube, support overhang and thin wall) against
     the same official desktop revision, meeting the documented semantic,
     time and filament tolerances.
   - Re-run G4 field-level A1 Mini profile resolution and retain reviewed
     exceptions rather than silently dropping unsupported fields.

5. **Bambu and physical qualification**
   - Port Bambu TreeSupport3D only as a separate license-complete dependency
     closure; its source audit and support-enabled fixtures remain independent
     gates.
   - Conduct the A1 Mini physical upload/start/cancel and recovery protocol,
     then repeat separately for A1 and P1S before enabling a model's direct
     print lane.

## Promotion rule

Only after stages 1–4 have produced reviewed passing evidence may an
`official-orca` result be eligible for the existing printer-readiness gate.
Physical printer, Bambu Handy and organization-signing gates remain separate.
Until then, the app must continue to label exports and any Android handoff as
unverified for physical printing.
