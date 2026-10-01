# Official Orca source snapshot

`third_party/orca-official` is a Git submodule pinned to official
OrcaSlicer commit `ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6` from
`https://github.com/OrcaSlicer/OrcaSlicer.git`.

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
  --source-root third_party/orca-official
```
