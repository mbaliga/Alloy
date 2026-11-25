# Alloy Slicer: third-party components

The full inventory, with provenance and pins, is [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (kept under that name because the CI validators and the README refer to it). Summary:

| Component | Licence | Where |
|---|---|---|
| SliceBeam / OrcaSlicer-Mobile / OrcaSlicer / PrusaSlicer engine lineage (the native tree) | **GNU AGPL-3.0** | `app/src/main/jni/`; text in [LICENSE](LICENSE) and `third_party/licenses/SLICEBEAM_AGPL-3.0.txt`. Engine-derived code keeps its AGPL notices. |
| Bambu Studio lightning-grounding adaptation (organic support) | GNU AGPL-3.0 | inside the native tree; see THIRD_PARTY_NOTICES.md |
| libnoise | LGPL-2.1 | `third_party/orca-deps-libnoise` (submodule), `third_party/licenses/LIBNOISE_LGPL-2.1.txt` |
| GMP, MPFR | LGPL-3.0-or-later | built by `ci/build_gmp_mpfr_android.sh` |
| Open CASCADE (OCCT) | LGPL-2.1 with exception | prebuilt under `app/src/main/occt/` |
| Boost 1.85 | Boost Software Licence | prebuilt under `app/src/main/jniImports/boost/` |
| CGAL, OpenVDB, ICU and others | their own licences | in-tree next to the sources |

## Licence of this directory

`android/` is AGPL-3.0 because the engine it links is. The rest of the repository (`forge/`, `docs/`) is PolyForm Noncommercial 1.0.0 (root `LICENSE`). Alloy-authored files that contain no engine-derived code may be offered under other terms by the copyright holder, but the Slicer as built and distributed is AGPL-3.0, and a native-enabled release must ship the corresponding source offer.
