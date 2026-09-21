# Native engine integration

## Current state

Alloy now carries an Android build of the pinned OrcaSlicer-Mobile native tree
under the existing Android module's `app/src/main/jni` boundary. It provides
the current Prusa/Orca-derived `libslic3r` implementation, including Arachne
walls and `TreeSupport3D`, alongside Alloy's OCCT bridge and bounded JNI
seam. The integrated source is pinned to:

`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`

The JNI compatibility layer retains the earlier SliceBeam source lineage:

`utkabobr/SliceBeam@12b370ce305acc2caa59b7e4e78e04069db2f7e3`

The source remains clearly separated from Alloy-authored Java code by its
upstream directory layout and the license copies in
`third_party/licenses/SLICEBEAM_AGPL-3.0.txt`.

The APK packaging path explicitly includes the generated OpenCASCADE ABI
libraries and loads their dependency chain before `libslic3r`; this mirrors
SliceBeam's Android loader instead of relying on transitive linker behavior.

Alloy's `SlicerEngine` seam does not depend on the inherited application UI.
`LegacyOfflineEngine` remains the default implementation. `NativeSlicerEngine`
is now a real Orca adapter: it stages the normalized mesh as bounded ASCII STL,
serializes the typed recipe into a deterministic INI, invokes the pinned native
slice, releases both native handles, and parses the resulting G-code into the
phone inspector's lightweight preview model. Native output parsing has bounded
layer and preview-segment limits so a large or malformed artifact cannot grow
the phone inspector without limit. Native output also passes the shared
comment-aware G-code safety scanner before it can be returned to the artifact
stager; required commands hidden in comments do not satisfy this gate.
The adapter also bounds the native file and every output line before building
the inspector string, verifies the app-owned staging root, and removes job
directories without following symlinks. Cancellation invalidates the active
slice job at the controller boundary and releases the native model/result
handles in all exit paths.
The preview parser also carries Prusa/Orca `;TYPE:` feature markers into
the phone inspector, preserving perimeter and support paths as separate
classes while leaving unknown features neutral until they are explicitly
understood.
`NativeEngine` remains the small smoke-probe entry point.

The pinned A1 Mini profile contributes 123 source-backed native scalar values.
The serializer preserves those values whenever no separate typed phone field
owns the same SliceBeam option; Alloy-owned temperatures and machine
start/end G-code remain authoritative when both representations are present.

The optional build selects `NativeSlicerEngine` through the generated
`BuildConfig.NATIVE_ENGINE_ENABLED` flag. The flag is false by default; enabling
it does not imply that the profile or physical printer is approved. A second
flag, `BuildConfig.NATIVE_ENGINE_VERIFIED`, is also false by default and can
only be promoted after the acceptance gates below; it controls whether native
results are eligible for the printer action.

## Optional native build

The full CMake path is enabled explicitly because it requires source-built
Boost, oneTBB, OCCT, GMP and MPFR artifacts (including headers):

```bash
gradle :app:assembleDebug -PalloyNativeEngine=true
```

When iterating on Java/profile behavior against an already verified ABI bundle,
the release packaging path can consume a complete `lib/<abi>` directory without
rebuilding CMake:

```bash
gradle :app:assembleRelease \
  -PalloyNativeEngine=true \
  -PalloyNativeEnginePrebuiltDir=/path/to/native-bundle/lib
```

This is a packaging convenience for fast profile/UI iteration, not evidence of
the clean source-build gate. The bundle must contain the complete matching
`arm64-v8a` dependency set, including `libslic3r.so`, OCCT, GMP/MPFR and the
shared C++ runtime.

The workflow `.github/workflows/alloy-native-build.yml` builds those missing
dependencies from the pinned bootstrap procedure, builds GMP/MPFR from their
verified official source archives, copies them into the Alloy native input
directories and builds the Alloy APK. It does not use the upstream prebuilt
extraction shortcut as proof of reproducibility.

The same workflow also compiles `NativeEngineSmokeTest`, which loads the
bundled cube on Android, runs the JNI slicer, stages a `.gcode.3mf`, and invokes
the package validator. Execute that test on an arm64 device after installing
the native debug and instrumentation APKs:

```bash
adb shell am instrument -w -e class \
  com.mbaliga.alloy.NativeEngineSmokeTest \
com.mbaliga.alloy.test/androidx.test.runner.AndroidJUnitRunner
```

Before any expensive Android native build, CI also checks the Java-to-JNI
contract. It derives the native declarations in `Native.java` and verifies
that each exact `Java_..._Native_...` export is present in the pinned
`beam_native.cpp` bridge. This catches seam drift before CMake compilation.

The local ARM64 source build now passes, including native library linking,
cube/assembly/workshop-fixture test compilation, G-code safety wiring and
`.gcode.3mf` staging code. The exact signed release/test pair has also loaded
and finished the API-35 ARM64 emulator suite with 0 failures (the latest run
started and finished 131 tests); the optional export-enabled
invocation is separate because it intentionally creates the G3 evidence files
on the device.

The adapter projects the typed recipe into the current Orca keys for wall
loops, shell layers, sparse infill, wall speeds, temperature vectors, surface
patterns and cooling thresholds. Legacy spellings remain in the serialized
projection for stored SliceBeam projects, while typed values are emitted
after them and remain authoritative.

The current local runtime smoke set also covers the integrated Orca engine's
cube, assembly and workshop fixtures, STEP assembly tessellation, OCCT boolean
round-trips, arc-output inspection and the complete fake-printer transaction.
Those tests pass on the API-35 ARM64 emulator; they do not substitute for a
physical printer or Bambu Studio parity run.

The native runtime/profile gate remains open for hosted clean-source
reproducibility, exact PrusaSlicer provenance, desktop fixture parity and
physical A1 Mini validation.

## Bambu TreeSupport3D parity milestone

The current Orca tree is not Bambu's current `tree(auto)` engine. Bambu
Studio `v02.08.02.61` routes organic/tree support through a separate
`TreeSupport3D` implementation, while the pinned Android tree now contains
Orca's `TreeSupport3D` implementation but not Bambu's implementation. The
reproducible source audit is:

```bash
python3 ci/audit_bambu_treesupport3d.py \
  /path/to/BambuStudio-v02.08.02.61 \
  . \
  /tmp/alloy-bambu-treesupport3d-audit
```

The audit records the pinned Bambu commit, SHA-256 values and line counts for
the seven support implementation files, enumerates the direct quoted-include
surface, reports same-path divergences, missing Alloy headers, and unresolved
quoted dependencies. It currently returns `PORT_REQUIRED`: an existing Alloy
`TreeSupport3D` route is not sufficient because the seven required source
files must match the pinned Bambu hashes as one coherent unit. A legacy `tree` experiment remains confined
to the G3 evidence harness and is not a production profile mapping. The port
must preserve the AGPL notices, adapt the surrounding libslic3r API as one
coherent unit, compile for ARM64 Android, and pass the existing cube,
support-enabled overhang, thin-wall and physical A1 Mini gates before native
engine verification can be promoted.

The report also enumerates unresolved quoted includes by their including file.
Those are a separate dependency class from files that exist upstream but have
not yet been copied into Alloy; both lists must be empty or explicitly
resolved in the coherent port review.

The same audit runs in
`.github/workflows/path-b-g5-bambu-treesupport3d-audit.yml` against the pinned
tag and commit. It uploads the full JSON/Markdown dependency-surface evidence
and intentionally fails while the route remains `PORT_REQUIRED`; this keeps
the production gate visible in pull requests and prevents a partial seven-file
copy from being mistaken for a completed Bambu port.

The optional-native workflow also installs the just-built APK pair directly
after the normal emulator suite and exports the opt-in G3 cube, overhang and
thin-wall artifacts as `alloy-native-g3-evidence`. This is evidence collection,
not an automatic parity promotion; the retained desktop comparison still
controls the G3 decision.

For a local artifact-retaining run, install the already-built release/test APK
pair and invoke the one export test directly so Gradle cannot uninstall the
package before retrieval:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w \
  -e class com.mbaliga.alloy.NativeEngineSmokeTest#exportsNativeG3EvidenceWhenRequested \
  -e export-g3 true -e g3-gcode-flavor marlin \
  com.mbaliga.alloy.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.mbaliga.alloy/files/alloy-g3-evidence /tmp/alloy-g3-evidence
```

The direct invocation is an evidence-harness convenience. It does not change
the app's production behavior, and a retrieved export still requires the
desktop comparator and physical-printer gates before promotion.

The Android adapter now projects the current Bambu/Orca support vocabulary
explicitly: organic tree support uses the resolved `default` base pattern,
the pinned two top/two bottom interface layers, and the source-backed top and
bottom Z gaps. In the controlled 2026-09-10 fixture rerun this corrected the
native output from 146 support / 3 interface sections to 147 / 2 and reduced
the estimate delta to 7.02% (1,126 s versus 1,047 s) and the filament delta to
2.77% (960.03 mm versus 933.39 mm). Those results are materially better but
still outside the G3 tolerance; they do not promote Bambu support parity or
the physical-send gate.

A compile-only probe now confirms that Bambu's pinned `TreeSupport3D.cpp`
compiles against Bambu's own coherent header set plus Alloy's OCCT headers
when the remaining Android crypto dependency is supplied. The same source
staged against Alloy's current header tree stops on Bambu-specific core API
surface (including `ArcFitter`, `Circle`, `CommonDefs` and
`MultiNozzleUtils`), so copying the seven support files alone is explicitly
not considered a valid port. The next native step is a license-complete,
coherent Bambu libslic3r dependency import and Android crypto implementation,
followed by link/runtime and fixture parity evidence.

## Acceptance gates

The native path is not accepted as the production slicer until all of these
are evidenced:

1. Clean ARM64 Android source build and JNI load smoke test.
2. Exact PrusaSlicer provenance report for the imported native tree.
3. Semantic G-code, estimate and visual-toolpath parity on cube, overhang and
   thin-wall fixtures.
4. Field-level A1 Mini printer/process/filament profile parity.
5. Physical A1 Mini validation, including a safe upload-only test and a
   telemetry-confirmed print/cancel test.

Until then, Alloy keeps the Print action unavailable and labels the packaged
profile unverified.
