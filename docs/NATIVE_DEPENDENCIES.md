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

Pinned source archives:

- GMP 6.2.1 — `https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2` — SHA-256
  `eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c`
- MPFR 4.2.2 — `https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2` — SHA-256
  `9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b`
