# Official Orca source snapshot

`third_party/orca-official` is a Git submodule pinned to official
OrcaSlicer commit `ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6` from
`https://github.com/OrcaSlicer/OrcaSlicer.git`.

`third_party/orca-deps-libnoise` is separately pinned to
`SoftFever/Orca-deps-libnoise@f25d5331570ae109f0e645cb729ecab155612714`
(the official Orca `1.0` dependency tag). Official Orca's dependency manifest
pins the matching archive hash `96ffd6cc47898dd8147aab53d7d1b1911b507d9dbaecd5613ca2649468afd8b6`.
The upstream README identifies the retained libnoise 1.0 source provenance;
the original libnoise project describes the source distribution as LGPL. This
snapshot is for source/build closure and must receive a complete distribution
notice before it is placed in any shipping binary.

The source-snapshot workflow builds this exact `libnoise_static` source target
on a clean Linux host. That is intentionally a dependency smoke test only; it
does not cross-compile Android, link it into Alloy, or prove a slicer result.

When the workflow validates an initialized official checkout, it also publishes
`official-orca-source-manifest.json`. That artifact inventories every regular
`src/libslic3r` source file with its path, upstream Git blob, SHA-256, and byte
length at the pinned revision. It is reviewable source-provenance evidence,
not a build, runtime, parity, or physical-print claim.

It is an attribution-preserving source snapshot for the separate
`official-orca` Android port described in
[`docs/OFFICIAL_ORCA_G2_PORT_PLAN.md`](../docs/OFFICIAL_ORCA_G2_PORT_PLAN.md).
It is deliberately **not** part of the current Android CMake target: that
target remains the rejected, `legacy-unverified` mobile candidate until the
official port has a complete dependency closure and all G2–G4 gates pass.

The submodule's `LICENSE.txt` is authoritative for the imported source. Do not
replace this Gitlink with a copied directory, update its revision, or route it
into a shipping target without first updating its explicit Android adaptation
patch manifest and passing the snapshot verifier.

To obtain the exact source locally:

```sh
git submodule update --init --recursive third_party/orca-official
python3 ci/validate_official_orca_snapshot.py \
  --source-root third_party/orca-official \
  --libnoise-source-root third_party/orca-deps-libnoise
```
