# Alloy v1 implementation note

## Included

The app module is intentionally small and dependency-light. `MainActivity` owns the compact task flow, `MeshModel` reads STL/3MF, `ViewportView` provides the Stage 1 preview, `Slicer` creates offline toolpaths, and `GcodePackageWriter` emits a portable inspectable archive.

## Safety boundary

The on-device slicer is a conservative personal-use implementation. It is useful for simple single-material models, but it is not equivalent to OrcaSlicer or Bambu Studio. It has no support generation, mesh repair, multi-object arrangement, modifier meshes, or verified A1 Mini machine-start metadata. Alloy v1 exports; it does not present a physical Print action.

Before printing, inspect the exported archive in a known-good desktop slicer and run a low-risk test only after checking bed limits, temperatures, extrusion mode, and first-layer behavior.

## Deliberately deferred

1. Replace the lightweight slicer with a provenance-pinned, reproducible engine after the Path B gates pass.
2. Add a real toolpath renderer and richer object transforms.
3. Add Keystore-backed printer pairing plus FTPS upload/MQTT start only after a physical A1 Mini Developer Mode fixture is available.
4. Add Android instrumentation tests on a physical phone for rotation, lifecycle interruption, large files, and Storage Access Framework behavior.
