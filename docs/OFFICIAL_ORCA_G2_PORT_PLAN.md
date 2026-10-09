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

The port-plan checkpoint on branch commit
[`657aee56cfb8921cba9ebee7babed6c1a84efecf`](https://github.com/mbaliga/Alloy/commit/657aee56cfb8921cba9ebee7babed6c1a84efecf)
passed the dedicated official-source verification workflow
[`37893118887`](https://github.com/mbaliga/Alloy/actions/runs/37893118887):
the pinned source snapshot, libnoise package-adapter tests, official/legacy
source-list inventory, Android port-surface audit, and a clean source build of
the pinned libnoise closure all succeeded. The run does not compile or link an
official Orca Android `libslic3r` target.

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

   - OpenCV dependency sub-gate: **PASS for the isolated dependency consumer**.
     Android ARM64 OpenCV 4.6.0 was built from the verified source archive and
     pinned SHA-256. CI compiled the official Orca OBJ color-utility smoke
     fixture, configured the Alloy `FindOpenCV.cmake` adapter, and linked an
     Orca-style Android consumer against `opencv_world`, `opencv_tegra_hal`,
     and `opencv_ittnotify` in run
     [`37880324658`](https://github.com/mbaliga/Alloy/actions/runs/37880324658).
     The complete native integration run
     [`37883851621`](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
     also rebuilt OpenCV and passed. This proves the pinned dependency and
     adapter work in isolation; it does **not** prove that the separate
     `official-orca` `libslic3r` target configures, compiles, or links. The
     next build-closure work is to wire the official source list into that
     distinct target and resolve the remaining dependency/build errors without
     stubbing official behavior or replacing the legacy target.

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
