# Official Orca G2 remediation plan

Status: **planned migration; no engine promotion.**

## Why a port is required

The current Android-native candidate is
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`.
The fresh official-history audit in Alloy Actions run
[`36819659942`](https://github.com/mbaliga/Alloy/actions/runs/36819659942)
found the nearest official baseline to be
`OrcaSlicer@ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6`, with only 250 of 488
common native source files identical (51.23%). G2 requires at least 85% exact
coverage plus an attributable profile baseline, so the current candidate is
not eligible for a production engine claim.

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

1. **Hermetic source snapshot**
   - Import the exact official source with its license notices and a manifest
     of source paths, Git blobs and SHA-256s.
   - Add a validator that fails if the imported source drifts from the pinned
     official commit, except for an explicit Android adaptation patch series.
   - Keep the existing Android engine selectable only as `legacy-unverified`.

2. **Android build closure**
   - Port the official CMake source list and direct dependency closure into a
     separate `official-orca` target; do not overwrite the currently compiling
     legacy target in place.
   - The source-pinned port-surface audit currently records direct
     `libslic3r` package drift: official source requires `libnoise` / its
     `noise::noise` target while the legacy Android tree instead carries a
     `draco` package requirement. Add and source-build the official closure
     deliberately; do not delete either side merely to make the names match.
   - Reuse only pinned, source-built Android dependencies (Boost, oneTBB,
     OCCT, GMP and MPFR) and record every new dependency and license.
   - Produce an ARM64 shared library in clean hosted CI before exposing JNI.

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
