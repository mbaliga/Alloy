# G1 reproducible source-build bootstrap

Status: **CI gate running; not passed until a clean hosted run succeeds.**

This document defines how Alloy evaluates `CodeMasterCody3D/OrcaSlicer-Mobile` as the primary Android engine/NDK extraction candidate.

## Pinned inputs

- OrcaSlicer-Mobile: `d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
- Android compile platform: API 35
- Android NDK: `23.1.7779620`
- ABI: `arm64-v8a`
- Java: 17 for the Gradle build runner
- Boost-for-Android bootstrap repository: `7943955c4d11a5bd61381a8b200c28619323eb0f`
- OpenVDB-Android/oneTBB bootstrap repository: `4d4a057d0a26d9cff88d6d7cc7bea80d27ffa7ec`
- OCCT bootstrap repository: `7d2efad9c8a9a57ea96c4c8587134b34dd503cd8`

The dependency pins above are Alloy's reproducibility pins for the G1 experiment. They do not imply that those exact revisions were used by the OrcaSlicer-Mobile maintainer; upstream's script cloned moving branch heads.

## What upstream provides

At the pinned OrcaSlicer-Mobile revision:

- `scripts/build-debug.sh` runs the normal Gradle build and has an alternate `--prebuilt` route.
- `scripts/build_all_deps_android.sh` builds Boost, oneTBB and OCCT, but hard-codes the maintainer's local SDK/NDK paths and clones dependency repositories without commit pins.
- `scripts/check-native-prebuilts.py` enumerates the native imports expected by `app/CMakeLists.txt`.
- GMP, GMPXX and MPFR shared libraries are checked into `app/src/main/jniLibs/arm64-v8a`; GMP headers are checked into `app/src/main/jniImports/gmp/include`.
- the source tree expects Boost and oneTBB static libraries plus OCCT shared libraries before the normal native build can link.

The `--prebuilt` helper extracts additional native libraries from a SliceBeam APK. Alloy deliberately does **not** count that path as G1, because it does not prove the native dependency build can be reproduced from source.

## Alloy's normalization

`ci/patch_orca_mobile_bootstrap.py` patches only the temporary CI checkout. It:

1. replaces `/home/cody/...` SDK/NDK paths with the hosted runner's paths;
2. pins Boost-for-Android, OpenVDB-Android and OCCT to the revisions listed above;
3. leaves the upstream application/native source itself untouched.

The CI workflow is `.github/workflows/g1-orca-mobile-source-build.yml`.

## Clean-run sequence

Conceptually the hosted gate performs:

```bash
git clone --recursive https://github.com/CodeMasterCody3D/OrcaSlicer-Mobile.git upstream
git -C upstream checkout --detach d996a9cadb65b354997f2d5d8734b46bb9ea4efd

sdkmanager \
  'platforms;android-35' \
  'build-tools;35.0.0' \
  'ndk;23.1.7779620' \
  'cmake;3.22.1'

python3 ci/patch_orca_mobile_bootstrap.py \
  upstream/scripts/build_all_deps_android.sh \
  "$ANDROID_SDK_ROOT" \
  "$ANDROID_SDK_ROOT/ndk/23.1.7779620"

cd upstream
bash scripts/build_all_deps_android.sh
python3 scripts/check-native-prebuilts.py arm64-v8a
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

## G1 acceptance

G1 passes only when all of the following are true in one clean hosted run:

- the exact OrcaSlicer-Mobile commit is checked out;
- the pinned missing native dependencies build successfully;
- `check-native-prebuilts.py arm64-v8a` reports the complete required import set;
- Gradle/CMake builds the native application without using `-PusePrebuiltNative=true`;
- a debug APK is produced and archived as CI evidence;
- no required source/build input exists only on the maintainer's local filesystem.

## Remaining provenance issue: GMP/MPFR

The pinned mobile repository ships prebuilt `libgmp.so`, `libgmpxx.so` and `libmpfr.so` plus headers. SliceBeam documents their origin as `flaktack/android-mpfr`, but OrcaSlicer-Mobile's current all-dependencies script does not rebuild them.

Therefore a successful first CI run proves the **application can be rebuilt from a clean clone using its checked-in GMP/MPFR binaries**, but complete third-party binary provenance still requires either:

- a pinned source rebuild of GMP/MPFR with byte/ABI verification, or
- documented source/version/build metadata sufficient to reproduce compatible replacements.

Alloy will record that as a compliance/reproducibility follow-up even if the first G1 application build succeeds.
