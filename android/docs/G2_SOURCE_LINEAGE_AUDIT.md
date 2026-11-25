# G2 source-lineage audit: OrcaSlicer-Mobile candidate

Date: 2026-10-18

## Finding

The pinned OrcaSlicer-Mobile engine does not meet the existing G2 source-lineage
criterion. In the retained GitHub Actions audit
[run 37741025557](https://github.com/mbaliga/Alloy/actions/runs/37741025557),
the best historical official-Orca candidate matched 314 of 479 common
`libslic3r` source files (65.55%), below the unchanged 85% threshold. The audit
found exact-history intervals for 66 of 72 sampled files. It scored 401
candidate commits and selected `83738cacd84c616b62fc3cc15be8b65665569be8` as
the best historical candidate.

The compared mobile source was
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`.
The official Orca `main` ref examined by that run resolved to
`18b70844d1dfaeede60a834f5fec41dd81dfe0e2`. That ref snapshot matched 42 of
479 files (8.77%); this is a tree snapshot result, not an ancestry claim. These
refs and numbers describe the retained audit, not a fresh measurement of the
current upstream `main`.

The per-file inventory is retained as
[JSON](G2_SOURCE_FILE_MANIFEST.json) and [CSV](G2_SOURCE_FILE_MANIFEST.csv).
It was reconstructed from the exact pinned source-tree objects: mobile
`libslic3r` tree `5f038896f899f70c558fef85732bc1b84ae7d4bd`, audited Orca-main
`libslic3r` tree `b754917c42234da3f4bac81ace0bb2c40ac58430`, and best-candidate
tree `41966808e128f674dcfe6748a95601768a53bab5`. Its 479 mapped rows contain
314 exact matches, 157 different candidate blobs, and 8 paths absent at the
candidate commit; the main snapshot has 42 exact matches. Every row's
origin/port classification remains `UNREVIEWED`.

The 72 sampled paths are identified in the inventory. The retained G2 log says
66 had exact-history intervals and names the six unmatched blobs, but the
interval commit/date bounds were in an expired Actions artifact. They are
therefore null in the inventory rather than reconstructed or guessed. The
machine-readable inventory validator is
`ci/validate_g2_source_file_manifest.py`.

The best-candidate tree comparison reported 172 changed files, 5,752
insertions, and 15,902 deletions. Largest per-file diff-stat entries were:

| File | Diff-stat lines |
|---|---:|
| `PrintConfig.cpp` | 1,973 |
| `GCode/GCodeProcessor.cpp` | 1,768 |
| `PresetBundle.cpp` | 1,368 |
| `Brim.cpp` | 1,212 |
| `GCode.cpp` | 1,202 |
| `ExPolygon.cpp` | 1,102 |
| `Preset.cpp` | 828 |
| `Print.cpp` | 653 |
| `PrintObject.cpp` | 587 |
| `Support/TreeSupport.cpp` | 534 |
| `Feature/FuzzySkin/FuzzySkin.cpp` | 510 |
| `Support/TreeSupport3D.cpp` | 508 |

These are textual diff-stat sizes, not measures of behavioral importance or
proof that either side is correct. The comparison also identified six sampled
mobile file blobs for which it found no exact blob interval in the examined
official Orca history: `Config.cpp`, `Feature/FuzzySkin/FuzzySkin.cpp`,
`PrintConfig.cpp`, `SLA/IndexedMesh.cpp`, `SVG.hpp`, and `Utils.hpp`. “Not
recoverable” here means that the exact pinned-mobile blob identity was not
found in that official history; it does not mean no upstream counterpart can
be fetched by path.

## Method and limits

The audit hashes C/C++ source and header files under the mobile
`app/src/main/jni/libslic3r` tree, maps files with matching relative paths to
official `src/libslic3r`, and searches official file history for exact Git blob
identities. It samples up to 72 substantial files, intersects their history
intervals to choose candidate commits (with a bounded temporal fallback), then
scores the complete set of 479 common files against each candidate tree. The
G2 high-confidence criterion requires at least 12 sampled history intervals,
at least 100 common files, and an exact-file ratio of at least 85%.

Exact blob identity is byte-level provenance evidence, not semantic
equivalence. The sampled intervals and shared-path set are not a complete
file-by-file origin map. A source file with the same path at the selected
upstream commit is available from history, but copying it over the mobile file
does not establish that the mobile content came from it or that the replacement
preserves Android, SliceBeam, or Bambu-specific behavior. The audit is not a
runtime/parity result, a legal opinion, or a gate pass. G2 remains **FAIL / NO-GO**.

## Prioritized review and port sequence

1. Produce a per-file manifest for all 479 mapped files: mobile blob, candidate
   upstream blob (or missing path), exact-match state, history interval, and
   a reviewed origin/port classification. Resolve the six unmatched sampled
   blobs separately; do not label an upstream path match as their provenance.
2. Review the core configuration and G-code differences first:
   `PrintConfig.cpp`, `Config.cpp`, `Preset.cpp`, `PresetBundle.cpp`,
   `GCode.cpp`, and `GCode/GCodeProcessor.cpp`. Identify changes required for
   the mobile build separately from behavior changes before considering any
   port.
3. Review print and geometry behavior next: `Print.cpp`, `PrintObject.cpp`,
   support/tree files, then `Brim.cpp`, `ExPolygon.cpp`, Arachne, fill, and
   perimeter code. Compare each change against the current native integration
   and fixture evidence.
4. For any proposed restoration, retain the original mobile file and its hash,
   record the exact upstream commit/blob and a small explicit patch, and port
   one coherent change at a time. Do not wholesale-copy a directory or replace
   files solely to raise the percentage. Run native compilation/instrumentation
   and the unchanged strict G3 checks after each meaningful source batch.
5. Re-run the provenance audit against the exact PR source and a freshly pinned
   official Orca ref. Keep the 85% threshold unchanged; separately resolve
   dependency/source completeness and legal review before any provenance or
   release claim.

No source restoration is proposed by this report. The required next step is
the per-file classification and review, not a bulk upstream overwrite.
