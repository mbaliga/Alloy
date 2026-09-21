# Path B G1 reproducible source-build bootstrap

Status: **Open; the latest audited hosted run failed before compilation.**

This is Alloy's current Path B source-build gate for the pinned SliceBeam
Android engine. The earlier OrcaSlicer-Mobile candidate was rejected as an
exact-engine provenance match; it is used here only for the Android
dependency-bootstrap script that SliceBeam does not carry in the pinned tree.

## Pinned inputs

- SliceBeam: `utkabobr/SliceBeam@12b370ce305acc2caa59b7e4e78e04069db2f7e3`
- Dependency bootstrap source: `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
- Android compile platform: API 35
- Android NDK: `23.1.7779620`
- ABI: `arm64-v8a`
- Java: 17 for the Gradle build runner
- Boost-for-Android bootstrap repository: `7943955c4d11a5bd61381a8b200c28619323eb0f`
- OpenVDB-Android/oneTBB bootstrap repository: `4d4a057d0a26d9cff88d6d7cc7bea80d27ffa7ec`
- OCCT bootstrap repository: `7d2efad9c8a9a57ea96c4c8587134b34dd503cd8`

The dependency pins above are Alloy's reproducibility pins for the G1 experiment. They do not imply that those exact revisions were used by the OrcaSlicer-Mobile maintainer; upstream's script cloned moving branch heads.

## What upstream provides

SliceBeam carries the PrusaSlicer-derived native application and expects Boost
and oneTBB static libraries plus OCCT when its optional STEP path is enabled.
It does not carry the dependency bootstrap used by the Android build, so Alloy
borrows the script from the pinned mobile source. GMP, GMPXX and MPFR shared
libraries are checked into that source and remain a separate provenance
follow-up.

Alloy deliberately does **not** count APK prebuilt extraction as G1, because it
does not prove that the native dependency build can be reproduced from source.

## Alloy's normalization

The CI workflow patches only temporary checkouts. It:

1. replaces hard-coded SDK/NDK paths with the hosted runner's paths;
2. pins the borrowed dependency repositories to the revisions listed above;
3. limits Boost to the required `arm64-v8a` ABI and Android API 26;
4. uses a portable CPU-count fallback for macOS and Linux hosts; and
5. leaves the Alloy source and upstream repositories untouched.

The workflow is `.github/workflows/path-b-g1-slicebeam-source-build.yml`.

## Clean-run sequence

Conceptually the hosted gate performs:

```bash
git clone --recursive https://github.com/utkabobr/SliceBeam.git slicebeam
git -C slicebeam checkout --detach 12b370ce305acc2caa59b7e4e78e04069db2f7e3
git -C slicebeam submodule update --init --recursive

git clone --filter=blob:none --no-checkout https://github.com/CodeMasterCody3D/OrcaSlicer-Mobile.git bootstrap-source
git -C bootstrap-source checkout --detach d996a9cadb65b354997f2d5d8734b46bb9ea4efd
cp bootstrap-source/scripts/build_all_deps_android.sh slicebeam/build_all_deps_android.sh
cp bootstrap-source/scripts/check-native-prebuilts.py slicebeam/check-native-prebuilts.py

sdkmanager \
  'platforms;android-35' \
  'build-tools;35.0.0' \
  'ndk;23.1.7779620' \
  'cmake;3.22.1'

python3 ci/patch_orca_mobile_bootstrap.py \
  slicebeam/build_all_deps_android.sh \
  "$ANDROID_SDK_ROOT" \
  "$ANDROID_SDK_ROOT/ndk/23.1.7779620"
python3 ci/patch_slicebeam_stage1_no_step.py slicebeam

cd slicebeam
bash build_all_deps_android.sh
bash "$GITHUB_WORKSPACE/ci/build_gmp_mpfr_android.sh" app/src/main arm64-v8a 26
python3 check-native-prebuilts.py arm64-v8a
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

## G1 acceptance

G1 passes only when all of the following are true in one clean hosted run:

- the exact SliceBeam commit is checked out;
- the pinned missing native dependencies build successfully;
- the native-prebuilt check reports the complete required import set;
- Gradle/CMake builds the native application without using a prebuilt APK extraction shortcut;
- a debug APK is produced and archived as CI evidence;
- no required source/build input exists only on the maintainer's local filesystem.

## Remaining provenance issue: GMP/MPFR

The pinned mobile repository ships prebuilt `libgmp.so`, `libgmpxx.so` and `libmpfr.so` plus headers. SliceBeam documents their origin as `flaktack/android-mpfr`, but OrcaSlicer-Mobile's current all-dependencies script does not rebuild them. Alloy validates the checked-in inputs for ABI, header version and recorded arm64 digests before CMake runs; this is a guardrail, not a source-build claim.

Alloy's native and release workflows now run
`ci/build_gmp_mpfr_android.sh` after the borrowed dependency bootstrap. That
step verifies pinned official GMP 6.2.1 and MPFR 4.2.2 archives, cross-builds
shared libraries for arm64, copies the matching headers, and requires the
source-built validation mode before CMake. The direct SliceBeam G1 experiment
still retains its own upstream prebuilt inputs for comparison and must be
re-run on hosted CI after the latest scope-patch fix.

The Alloy native and release workflows validate the copied Boost, oneTBB and
OCCT inputs with `ci/validate_native_dependency_inputs.py`. They do not use
the upstream prebuilt checker as their release gate because it requires
optional Boost.Math archives that this pinned Boost-for-Android build does not
produce; the Alloy-owned check follows the exact CMake import contract.

Therefore a successful first CI run proves the **application can be rebuilt from a clean clone using its checked-in GMP/MPFR binaries**, but complete third-party binary provenance still requires either:

- a pinned source rebuild of GMP/MPFR with byte/ABI verification, or
- documented source/version/build metadata sufficient to reproduce compatible replacements.

A passing G1 build still does not promote Alloy to physical-print status. G2
engine provenance, G3 semantic/toolpath parity, G4 A1 Mini profile parity and
real-printer acceptance remain required.

Alloy will record the binary provenance issue as a compliance/reproducibility
follow-up even if the first G1 application build succeeds.
