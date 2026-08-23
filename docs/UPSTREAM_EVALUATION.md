# Alloy upstream evaluation

Date: 2026-08-23

## Decision

**Build Alloy as its own Kotlin/Jetpack Compose product shell around an extracted, upstream-tracked OrcaSlicer Android native core/JNI boundary. Do not fork the SliceBeam UI, and do not adopt OrcaSlicer-Mobile's Java/AppCompat UI as Alloy's product architecture.**

The discovery of `CodeMasterCody3D/OrcaSlicer-Mobile` changes the original handoff recommendation. It already combines SliceBeam's Android/native integration work with OrcaSlicer-derived slicing code, which is much closer to the Bambu/Orca engine behavior Alloy needs than starting from SliceBeam's PrusaSlicer lineage and then recreating Orca deltas ourselves.

## Candidates

### SliceBeam

Strengths:
- proven native Android slicer architecture
- existing NDK/JNI and OpenGL viewport integration
- useful reference for Android lifecycle, model manipulation, and native memory ownership

Weaknesses for Alloy:
- engine lineage is further from current Orca/Bambu behavior
- adopting its UI creates migration work that does not advance Alloy's phone-first Compose interaction model
- Bambu A1 Mini profile fidelity would need more adaptation and validation

License: GNU AGPL-3.0.

### OrcaSlicer-Mobile

Strengths:
- Android project with OrcaSlicer-derived `libslic3r`/native tree already integrated
- source-native CMake build path is present; prebuilt native libraries are optional rather than mandatory
- ARM64 Android target matches modern phones
- best current upstream reference for Alloy's slicer engine work

Observed upstream Android build characteristics at evaluation time:
- application id: `com.codemastercody3d.orcaslicermobile`
- compile/target SDK 35
- min SDK 21
- NDK 23.1.7779620
- ARM64 only
- Java/AppCompat + Material UI rather than Compose
- native CMake build in release configuration for slicer performance

Weaknesses for Alloy:
- product shell is inherited from SliceBeam and is not suitable for Alloy's adaptive phone/foldable/tablet/desktop UX
- large Java activities make incremental conversion to Compose less attractive than a clean shell
- upstream needs to remain a reference/pinned engine source, not become Alloy's UX architecture

License: GNU AGPL-3.0.

## Recommendation: own shell, reuse native work

Use three conceptual layers:

1. **alloy-engine** — OrcaSlicer-derived C++ slicer core and the smallest possible Android-specific bridge.
2. **engine-api** — typed Kotlin API that owns JNI handles, jobs, progress, cancellation, diagnostics, model metadata, config/preset resolution, and preview data.
3. **alloy-app** — Compose UI, adaptive navigation, Android document import/export, printer transport, settings, and lifecycle.

The JNI API must expose domain operations rather than UI concepts. Compose should never know about raw C++ pointers or upstream singleton state.

## Why not migrate the existing UI to Compose?

That path optimizes for getting an inherited slicer UI on screen quickly, but Alloy's defining requirement is a different information architecture on compact devices. Converting screen-by-screen would preserve the wrong navigation and parameter model and then require a second redesign.

The existing OpenGL/native viewport is still valuable. For Stage 1, host the proven rendering surface inside Compose via `AndroidView`/`SurfaceView` and retain the native renderer. Do **not** rewrite the viewport in Filament during the first slice. Filament can be revisited after slicing/profile/preview parity is stable.

## Engineering-effort comparison

These are planning ranges, not delivery promises. They assume one experienced Android/C++ engineer, existing upstream code remains buildable, and exclude prolonged printer-firmware reverse engineering.

| Path | Useful Stage 1 | Long-term cost | Recommendation |
|---|---:|---|---|
| Fork SliceBeam UI + retrofit Orca behavior | ~6–10 engineer-weeks | High: engine/profile drift plus UI rewrite debt | Reject |
| Fork OrcaSlicer-Mobile whole app + convert UI gradually | ~5–9 engineer-weeks | Medium-high: Java/AppCompat migration and inherited IA | Reject |
| Alloy Compose shell + extracted OrcaSlicer-Mobile native boundary | ~8–14 engineer-weeks | Lowest: work maps directly to target architecture | **Choose** |

The own-shell option can look slower on day one but avoids doing the phone UX twice.

## Upstream integration policy

- Record exact upstream repository and commit whenever native source is imported.
- Keep upstream-derived code mechanically separable from Alloy-authored Android code where practical.
- Preserve copyright/license notices.
- Keep the distributed application and corresponding source compliant with AGPL-3.0 obligations.
- Do not depend on Bambu's closed network plugin.
- Treat Orca/Bambu printer/process/filament presets as versioned input data with parity fixtures, not hand-copied magic constants in Kotlin.

## Gate before engine import

Before importing the large native tree, CI should prove a reproducible ARM64 build from the selected upstream revision and capture the produced JNI ABI surface. That prevents Alloy from vendoring a native snapshot we cannot reproduce.
