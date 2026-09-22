# Bambu TreeSupport3D port record

Date: 2026-09-22

## Evidence

The pinned Bambu Studio checkout is `v02.08.02.61` at commit
`926a7192574bcb9b3a732e1ec59a46d79cb45466`.

The exact checkout is now restored locally for reproducible auditing. The
source audit reports `PORT_REQUIRED`: all seven required implementation files
differ from Alloy's current native tree, 30 of 36 direct support includes
differ, and 7 transitive files are absent. This is a source-compatibility
result, not a runtime or production-parity claim.

## ARM64 compatibility result

The clean native build was then run with:

```text
gradle :app:assembleDebug -PalloyNativeEngine=true -PalloyCiDebugSign=true --no-daemon
```

The exact Bambu source set does not compile against Alloy's pinned
Orca-Mobile `libslic3r` API. The compiler exposed these required dependency
classes:

- Clipper2 headers and target wiring;
- Bambu `PrintConfig` fields such as `top_z_overrides_xy_distance`;
- Bambu `SupportParameters` fields such as `interface_density` and
  `soluble_interface`;
- Bambu support-layer enum names and layer-header ordering;
- Bambu polygon helpers including `split_expoly_with_holes`;
- Bambu support utility functions `safe_union` and `safe_offset_inc`;
- constant-scaling differences between Bambu's compile-time `SCALING_FACTOR`
  and Alloy's runtime scaling value.

The overlay was removed after the failed compile. The original Alloy native
tree was rebuilt successfully for ARM64 immediately afterward. No incomplete
Bambu source is included in the shipped build, and the Bambu parity gate
remains open until this dependency closure is ported coherently and the
support-enabled fixtures pass.

## Next port boundary

The next implementation pass must port the surrounding support API as one
reviewable unit—at minimum the Bambu-compatible support parameters/layer
types, polygon helpers, Clipper2 build target, and scaling contract—before
reintroducing the seven TreeSupport3D files. A partial source copy must not
be promoted or treated as Bambu parity.
