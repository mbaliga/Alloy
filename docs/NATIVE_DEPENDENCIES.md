# Native dependency inventory

Alloy's native Android slicer has two dependency classes:

- Boost, oneTBB and OCCT are copied from a pinned, source-built dependency
  bootstrap in the hosted native workflows.
- GMP 6.2.1, GMPXX and MPFR are checked in for every packaged ABI with
  matching public headers. The arm64 set is source-built as GMP 6.2.1 and
  MPFR 4.2.2 from pinned official archives; emulator/legacy ABIs retain their
  compatibility fallback inputs until those architectures receive the same
  reproducible source build.

The checked-in fallback class is an explicit, temporary provenance boundary.
It is validated for ELF ABI, header version and arm64 SHA-256 by
`ci/validate_native_prebuilts.py`. The hosted native/release path uses
`ci/build_gmp_mpfr_android.sh`, verifies the official archive SHA-256 values,
cross-compiles the libraries with the pinned NDK, copies matching headers and
records build metadata before the source-built validation pass.

Boost.Math is consumed through its headers in the SliceBeam/PrusaSlicer code
used by Alloy. The pinned Boost-for-Android 1.85 bootstrap does not emit the
legacy `boost_math_*` archives, so Alloy deliberately excludes those unused
archive targets from the native import list while retaining the complete
Boost headers.

The production promotion gate still requires the hosted source build to pass
and its generated libraries to pass ABI/runtime checks. Do not update the
recorded fallback digests merely to make a changed binary pass: first update
this inventory with the new provenance and rerun the native acceptance gates.

`app/CMakeLists.native.txt` also checks every copied Boost, oneTBB, OCCT,
GMP, GMPXX and MPFR input before declaring an imported target. A missing
archive or shared library now fails during CMake configuration with the
dependency name and path, instead of surfacing later as an opaque linker
error. The Boost component list is kept aligned with the canonical
`test_exec_monitor` archive name.

After generated libraries and headers are copied into a clean Alloy checkout,
`ci/validate_native_dependency_inputs.py` checks the exact Boost, oneTBB and
OCCT paths referenced by `app/CMakeLists.native.txt`. This is the Alloy-owned
check used by the native and release workflows; the upstream checker is not a
release gate because it requires optional Boost.Math archives that this pinned
source build does not produce.

Pinned source revisions used by the Android bootstrap are maintained in
`ci/patch_orca_mobile_bootstrap.py` and rechecked in the native workflow:

- SliceBeam bootstrap: `12b370ce305acc2caa59b7e4e78e04069db2f7e3`
- OrcaSlicer-Mobile dependency script: `d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
- OpenVDB-Android / oneTBB bootstrap: `4d4a057d0a26d9cff88d6d7cc7bea80d27ffa7ec`
- Boost-for-Android: `7943955c4d11a5bd61381a8b200c28619323eb0f`
- OCCT: `7d2efad9c8a9a57ea96c4c8587134b34dd503cd8`
- Android NDK: `23.1.7779620`; native workflow Android API: `35`

OpenVDB-Android's nested dependencies are fixed by that source revision's
submodule gitlinks. A build is reproducible only when the script pins and
validates these inputs; a passing hosted build remains distinct from G2 engine
lineage and device qualification.

Pinned source archives:

- GMP 6.2.1 — `https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2` — SHA-256
  `eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c`
- MPFR 4.2.2 — `https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2` — SHA-256
  `9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b`

## Android OpenCV for OBJ color processing

The native Android target uses the pinned OpenCV 4.6.0 opencv_world package
for the existing libslic3r/ObjColorUtils.cpp source. The source archive is
pinned to SHA-256
1ec1cba65f9f20fe5a41fda1586e01c70ea0c9a6d7b67c9e13edf0cfe2239277. The
native build currently targets arm64-v8a; OpenCV itself is configured for
Android API 23 so the resulting static library remains compatible with the
API-35 native build.

Set ALLOY_OPENCV_ANDROID_ROOT to the installed package sdk/native directory
(for example, $PWD/opencv-install-arm64/sdk/native) before configuring the
native Gradle build. Alloy's app/cmake/official-orca/FindOpenCV.cmake adapter
checks the version header, libopencv_world.a, and the required libtegra_hal.a
and libittnotify.a static dependencies. The native workflow builds and
installs this exact package before invoking Gradle; the isolated consumer
workflow also verifies that the pinned package compiles and links for Android
ARM64.

See [the native workflow](../.github/workflows/alloy-native-build.yml) for
the full reproducible configure/build/install recipe and [the OpenCV consumer
workflow](../.github/workflows/official-orca-opencv-android.yml) for the
standalone linker check. The standalone Android consumer passed in [run
37878912402](https://github.com/mbaliga/Alloy/actions/runs/37878912402).
That result does not yet verify the complete Alloy native app link; the
native workflow including this dependency remains the required integration
check.
