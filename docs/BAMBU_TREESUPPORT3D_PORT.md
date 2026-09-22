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

### Focused compiler confirmation (2026-09-22)

After the full overlay build stopped making useful progress, the three
TreeSupport translation units were compiled directly in the disposable
`/private/tmp/alloy-treeport` worktree with the pinned Android toolchain. The
focused compile fails before linking and confirms the boundary is structural,
not a single missing declaration. The first diagnostics include incomplete
`Layer` access in `SupportParameters.hpp`, missing `PrintConfig` and
`SupportParameters` fields, renamed support-layer enum values, runtime
`SCALING_FACTOR` use in `constexpr` constants, the missing
`ExPolygon::split_expoly_with_holes` helper, and undefined `safe_offset_inc`
and `safe_union` helpers in `TreeSupport3D.cpp`. This evidence is retained as
the next port specification; the disposable overlay remains excluded from the
main branch and from all shipped artifacts.

The first dependency-closure increment is now in the main native tree:
`ExPolygon::split_expoly_with_holes` was added to `ExPolygon.hpp/.cpp` using
Alloy's existing clipping and intersection primitives. Its ARM64 translation
unit compiles successfully. The full clean Gradle package build was started to
validate the complete native link, but was stopped after the clean native
rebuild stopped emitting progress; therefore this increment is not counted as
an end-to-end APK validation until that build completes.

The next API increment adds Bambu's `top_z_overrides_xy_distance` support
setting to Alloy's `PrintConfig` schema with the same default (`false`) and
advanced Support classification. `PrintConfig.cpp` recompiles successfully
for ARM64; the focused native target reports no work remaining afterward.

The support-parameter layer now also exposes Bambu-compatible
`soluble_interface` and aggregate `interface_density` values. Solubility is
derived from Alloy's selected support-interface filament, and the XY gap
calculation honors the new top-Z override setting. The affected
`TreeSupport3D.cpp` ARM64 target compiles successfully; the exact Bambu source
set remains unpromoted until its broader API closure is complete.

The shared safe-geometry boundary is now implemented in Alloy:
`SupportCommon.hpp/.cpp` exposes Bambu-compatible `safe_union` and
`safe_offset_inc` polygon helpers, including collision trimming, stepped
offsetting, simplification, and final-difference behavior. Alloy's own
TreeSupport3D translation unit compiles with the shared implementation. The
exact Bambu probe now advances past those missing symbols; its remaining
errors are concentrated in compile-time scaling, legacy layer overhang
storage, and integer/`constexpr` assumptions.

The shared API now adds an explicit integral-output `scaled<T>` overload that
still uses Alloy's runtime `SCALING_FACTOR`, plus typed overhang storage on
`Layer` (`loverhangs_with_type`) and propagation for shared/lift-detected
layers. The affected `Print.cpp`, `PrintObject.cpp`, and `TreeSupport3D.cpp`
ARM64 targets compile successfully. Runtime `constexpr` sites in the exact
Bambu sources remain intentionally unadapted until they are ported as source,
not hidden by changing Alloy's scale contract.

In the disposable exact-Bambu probe, converting the local runtime-dependent
`constexpr` values to runtime `const` values and applying Alloy's integral
scaling overload removes the prior scaling diagnostics. The probe then stops
at one class-member declaration issue and three `RichInterfacePlacer` calls
whose surrounding inheritance API differs. Those source edits remain confined
to `/private/tmp/alloy-treeport`; no exact Bambu implementation is shipped.

The follow-up probe reaches the same runtime-scale contract with only four
remaining diagnostics: the `m_base_radius` member must be adapted from an
`auto` static member to a runtime object member, and three calls through
`RichInterfacePlacer` expose a surrounding `InterfacePlacer` class-layout
mismatch. This is now the active source-port boundary; the disposable edits
remain outside Alloy's shipped tree.

The next dependency boundary is now closed in Alloy's Android build:
the pinned Bambu Clipper2 sources are vendored under
`app/src/main/jni/clipper2/Clipper2Lib`, exposed as a C++17 static `Clipper2`
target, linked into `slic3r`, and used by the existing `Clipper2Utils.cpp`
bridge. The ARM64 Clipper2 library and utility translation unit both compile
successfully. This removes the missing-header/build-target blocker for the
exact Bambu `TreeSupport.cpp` probe; full support-source promotion remains
open.

With Clipper2 wired into the disposable probe, exact Bambu `TreeSupport.cpp`
now compiles past its missing-header/build-target failure and reaches the next
API boundary. The remaining diagnostics include support-ironing parameter and
extrusion-role fields, `turn90_ccw`, the Bambu bridge-removal signature,
overhang bounding-box comparison helpers, and support-layer cooling metadata.
This confirms Clipper2 is a real dependency closure, while the full Bambu
support-material integration is still a separate port unit.

Alloy's existing `Detected`/`Enforced`/`SharpTail` classifier is now mirrored
into `Layer::loverhangs_with_type` after the final overhang mutations, and the
vector is cleared with each detection pass. The ARM64 `TreeSupport.cpp` target
compiles successfully. This closes the data-shape gap without claiming that
the exact Bambu source has been promoted.

## Next port boundary

The next implementation pass must port the surrounding support API as one
reviewable unit—at minimum the Bambu-compatible support parameters/layer
types, polygon helpers, Clipper2 build target, and scaling contract—before
reintroducing the seven TreeSupport3D files. A partial source copy must not
be promoted or treated as Bambu parity.
