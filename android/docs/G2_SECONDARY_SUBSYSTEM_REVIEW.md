# G2 secondary subsystem comparison (evidence only)

Date: 2026-10-18

This note records a bounded text comparison of six additional files at the immutable refs below. It is not a source-ownership finding, semantic-equivalence assessment, legal opinion, or G2 pass. Every file's origin/port classification remains `UNREVIEWED`; overall G2 remains FAIL (314/479 candidate blob matches, below the unchanged 85% criterion).

## Reproducible basis

- Mobile: `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`, under `app/src/main/jni/libslic3r/`.
- Official historical candidate: `OrcaSlicer/OrcaSlicer@83738cacd84c616b62fc3cc15be8b65665569be8`, under `src/libslic3r/`.
- Compared Git blob IDs are listed in the table. They were fetched by blob ID from the corresponding repository and compared as text. Line references below are one-based within those exact blobs. Line counts and blob IDs also appear in `docs/G2_SOURCE_FILE_MANIFEST.json`. The inventory's classification field remains `UNREVIEWED`.

## Observations

| File | Mobile blob / lines | Candidate blob / lines | Bounded observable difference |
|---|---|---|---|
| `Brim.cpp` | `8b245e82c5026021c60aac4dcccd3962b885602e` / 991 | `d21ed2e54422bff03434570b2ace4b6c6d7e1786` / 1,783 | Mobile has an EFC-conditioned brim-outline path and helpers to snap painted ears to the matching EFC-adjusted island (mobile lines 55–107, 393–402). Candidate instead has a max-brim-width helper and bottom-layer outline helper in the corresponding early section (candidate lines 57–75). Difference category: brim outline/painted-ear generation. No resulting geometry claim is made. |
| `ExPolygon.cpp` | `185e92508449a425064b26690e3d74d06a16fda8` / 552 | `185e92508449a425064b26690e3d74d06a16fda8` / 552 | Exact byte-level candidate match. This does not establish semantic correctness or ownership. |
| `Print.cpp` | `0d58e2dc00c32945b67229206041d8e2bc7ef56b` / 5,097 | `330b48914162f2877a5e64244f15e8d6796bbfaa` / 4,888 | Mobile's option-transfer list visibly includes additional air-filtration and Bambu filament-tower/wipe-tower/travel keys (mobile lines 220–225, 328–366); candidate's corresponding list is narrower (candidate lines 327–353). Mobile also explicitly inserts a configured wipe-tower filament in extruder ordering (mobile lines 536–539). Difference category: print-config propagation and tool/extruder selection. |
| `PrintObject.cpp` | `925da0c5644e06b6813747ae35b371d1a1555fe1` / 4,726 | `7cc98209cfc5bee5427031a971d09433a5de96cb` / 4,377 | Mobile tracks role-specific outer-wall, inner-wall, internal-solid, top, bottom, and sparse-infill filament IDs when collecting/checking unprintable regions (mobile lines 319–341, 366–416). Candidate's corresponding code uses the coarser `wall_filament` and `solid_infill_filament` fields (candidate lines 312–324, 348–362). Difference category: per-role material routing and printable-area checks; behavior is not adjudicated here. |
| `Support/TreeSupport.cpp` | `7ae0cd94c8f92cdf3d2ce39b3e8cfe2aad9f1b8f` / 3,778 | `50272009cd1556e025e319f4bb4023e2b3beda39` / 3,528 | Mobile guards a tiny raft-to-object residual and subtracts EPSILON before the layer-count ceil (mobile lines 1185–1196); candidate directly ceils the unadjusted distance (candidate lines 1158–1165). Mobile also initializes interface orientation per area group from the configured support angle (mobile lines 1481–1514), while candidate initializes the filler angle in its earlier shared setup (candidate lines 1443–1450). Difference category: raft layer subdivision and tree-support interface path generation. |
| `Support/TreeSupport3D.cpp` | `b5458e8211dfb2476c6e79754fec85d397b5cc07` / 4,199 | `7cf7d3bb03075b169e9db52beab47e7ce31254b6` / 4,029 | In mesh grouping, mobile resets and sets a `zero_top_z_gap` state, while candidate sets `soluble` on the corresponding support-top-distance condition (mobile lines 123–137; candidate lines 129–136). Mobile selects `top_interface_density` for roof paths; candidate uses `interface_density` (mobile lines 706–714; candidate lines 715–722). Mobile also names and propagates a `roof_recovery_depth` value into influence-area calls (mobile lines 889–925); the analogous candidate code uses an inline depth expression. Difference category: tree-support state/configuration and roof recovery. No equivalence or output impact is inferred. |

## Limits and follow-up

The observations are intentionally local to visible text in these six files. A differing blob can combine upstream slicer changes, mobile integration, later edits, or other origins; this comparison cannot distinguish those origins. Nor does a matching blob prove behavior. In particular, the material-routing and tree-support differences merit targeted fixture tests before any restoration or port is considered.

No engine source, thresholds, release policy, or qualification gate was changed by this audit. Do not replace mobile files wholesale to increase the exact-match percentage. Preserve the existing G2 FAIL state and separately required provenance/legal review.
