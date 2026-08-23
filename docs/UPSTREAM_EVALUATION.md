# Alloy upstream evaluation

Date: 2026-08-23

## Decision

**Primary candidate: extract the engine and Android NDK/CMake integration from `CodeMasterCody3D/OrcaSlicer-Mobile`, while building Alloy's own Kotlin/Jetpack Compose product shell and fresh typed JNI API. Adoption is gated, not assumed.**

Fallback order if the primary candidate fails a gate:

1. SliceBeam's PrusaSlicer-derived core.
2. A clean Android port from official OrcaSlicer upstream.

All three paths remain AGPL-3.0-compatible and require corresponding source for distributed Alloy builds.

## Corrected facts

`CodeMasterCody3D/OrcaSlicer-Mobile` exists. It is a public fork of `utkabobr/SliceBeam`. Its README says that it keeps the SliceBeam Android shell and OpenGL preview while replacing the slicing core with OrcaSlicer `libslic3r`. The project is unofficial, experimental/alpha, single-maintainer, and its `PROJECT.md` still has E2E verification in progress/planned rather than passed.

Observed upstream characteristics at the inspected `master` revision:

- repository head inspected: `d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
- app version: `0.4.6`
- application id: `com.codemastercody3d.orcaslicermobile`
- retained Java/JNI package path: `ru.ytkab0bp.slicebeam`
- compile/target SDK: 35
- min SDK: 21
- NDK: `23.1.7779620`
- ABI: `arm64-v8a`
- UI: Java/AppCompat/Material, not Compose
- renderer: Java `GLView extends GLSurfaceView`, OpenGL ES 3, backed by `GLRenderer`
- JNI/native code directly includes Orca/Bambu-derived `libslic3r`, `bbl`, `libvgcode`, and GLES code

The retained package path is a migration concern because native exports and `JNI_OnLoad` class lookups are bound to `ru/ytkab0bp/slicebeam/...`. Alloy may keep thin compatibility bridge classes initially, but the end state should rename/export a clean Alloy JNI surface.

## Upstream roles

### OrcaSlicer-Mobile — primary extraction candidate

Use it for:
- Android-specific Orca `libslic3r` patches
- NDK/CMake integration
- JNI behavior that has already been made Android-compatible
- first-light model/G-code rendering scaffolding

Do **not** inherit its Java application architecture as Alloy's product shell.

### SliceBeam — fallback and reference

Use it for:
- Android dependency build knowledge
- original JNI and GLES implementation lineage
- a fallback PrusaSlicer-core path if the Orca port fails the gates

Its public README explicitly describes a PrusaSlicer core with experimental Orca profile import.

### Official OrcaSlicer — engine authority for parity

Official OrcaSlicer is the comparison target for G2-G4. Even if Alloy extracts code from OrcaSlicer-Mobile, the desktop parity fixture must use the exact upstream Orca revision that the mobile port is determined to track.

## Adoption gates

The primary candidate is accepted only if **all** gates pass.

### G1 — reproducible build

Acceptance:
- clean clone at the pinned OrcaSlicer-Mobile revision
- Android SDK 35 + NDK `23.1.7779620`
- `./gradlew assembleDebug` succeeds from documented source-built dependencies
- bootstrap instructions are sufficient for a fresh CI runner/machine

Current findings:
- upstream includes `scripts/build-debug.sh`
- upstream includes `scripts/build_all_deps_android.sh`
- the dependency script builds/copies oneTBB, Boost 1.85, and OCCT; GMP/MPFR are expected in the native import tree
- upstream also has a `--prebuilt` route that extracts shared libraries from a SliceBeam APK; that route is useful as a smoke test but **does not satisfy G1**
- the source dependency script currently hard-codes the maintainer's SDK paths and is not yet reproducible CI documentation

**Status: IN PROGRESS / NOT PASSED.** Alloy CI must normalize those paths and prove the source build.

### G2 — exact engine version pinned

Acceptance:
- identify the exact OrcaSlicer commit/version represented by the mobile `app/src/main/jni` engine tree
- record the upstream commit here
- compare against that exact revision for G3/G4

Current findings:
- the mobile source clearly identifies itself internally as OrcaSlicer lineage (`SLIC3R_APP_FULL_NAME "Orca Slicer"`, Bambu/Orca-specific code, Arachne and BBL components)
- the Android app's `SLIC3R_VERSION` is overwritten with the mobile app version (`0.4.6`) at build time, so it cannot be used as an Orca upstream version pin
- no trustworthy Orca upstream commit identifier has yet been found in the checked-in metadata

**Status: NOT PASSED.** A file/tree diff against official OrcaSlicer is required; do not guess the version from dates.

### G3 — G-code parity

Fixtures:
1. 20 mm calibration cube
2. overhang/support fixture
3. thin-wall keyboard-case-like fixture

Acceptance, using identical A1 Mini printer/filament/process input on desktop OrcaSlicer at the G2-pinned version and the Android port:
- toolpath feature sequence is semantically equivalent
- print-time estimate within 2%
- filament use within 2%
- visual/toolpath inspection shows no missing Arachne walls, supports, or seams

**Status: BLOCKED by G1 and G2.** This gate also requires an Android execution target; it cannot be claimed from static source inspection.

### G4 — A1 Mini profile fidelity

Acceptance:
- A1 Mini `.orca_printer`/filament bundle imports successfully with inheritance resolution
- field-by-field comparison against desktop-loaded profile for at least bed geometry, machine limits, nozzle/process identity, and start/end G-code
- no silent fallback to Prusa-format defaults

**Status: BLOCKED by G1 and G2.**

## Go / no-go rule

- G1 + G2 + G3 + G4 all pass: **GO** with OrcaSlicer-Mobile engine/NDK extraction.
- Any gate fails materially: **NO-GO**, switch to SliceBeam core and repeat G1-G4.
- If SliceBeam also fails profile/parity requirements: move to clean official-Orca Android port.

## Rendering decision

For Stage 1 bring-up, reuse the existing SliceBeam-lineage renderer only as scaffolding:

- `GLView` is verified as a `GLSurfaceView` using OpenGL ES 3 and `GLRenderer`.
- native/JNI includes raycasting, painting, arrangement, G-code viewer data, and model manipulation hooks.
- host the legacy view in Compose using `AndroidView` only for basic model/G-code display while the engine is being proven.
- **do not build new Alloy interaction features into the legacy Java view.**

Hard checkpoint before Stage 2: replace the legacy view with an Alloy-owned GLES renderer hosted via `AndroidExternalSurface`, designed for foldables, hover, secondary click, resizing, and arbitrary desktop aspect ratios.

Filament is not a Stage 1 dependency.

## Stage 1 native scope

To reduce G1 surface area:
- first required imports: STL and 3MF
- trusted output: `.gcode.3mf`
- STEP/OCCT is desirable but may be removed from the first Alloy engine extraction if it blocks reproducibility; restoring STEP is a subsequent native milestone
- arm64-v8a first

## Licensing and credit chain

Alloy will retain AGPL-3.0 obligations and the credit chain:
- SliceBeam
- OrcaSlicer-Mobile
- OrcaSlicer
- PrusaSlicer / Slic3r
- BambuStudio

Alloy will not ship Bambu's closed networking plugin/Bambu Connect components. Bambu names are compatibility references only, not Alloy branding.

## Extraction policy

- record source repository + exact commit for every imported tree/patch
- port minimal Android engine patches rather than copying the inherited UI wholesale
- keep upstream-derived native code mechanically separable from Alloy-authored Android code where practical
- raw native pointers never enter Compose state
- profiles stay versioned data, not Kotlin constants
- preserve copyright/license notices and make corresponding source available for distributed builds
