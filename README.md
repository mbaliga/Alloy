# Alloy

Alloy is a native Android 3D-print slicer project, initially targeting the Bambu Lab A1 Mini.

The product is phone-first and is intended to scale to foldables, tablets, and Android desktop/windowed sessions. It will use the open-source OrcaSlicer/PrusaSlicer engine lineage rather than attempting to port Bambu Studio's wxWidgets desktop UI.

## Alloy Forge (web)

[`forge/`](forge/) holds Alloy Forge, a browser-based parametric CAD editor for enclosures and small product design, with BYOK AI editing. See [forge/README.md](forge/README.md).

## Current phase

Stage 1 is at the architecture and information-design gate. Per the project plan, the phone IA and screen behavior must be approved before Jetpack Compose UI implementation begins.

Engineering work that can proceed before that gate includes:

- upstream slicer-engine evaluation and pinning
- Android NDK/JNI boundary design
- Bambu A1 Mini profile-parity test fixtures
- LAN Developer Mode transport isolation and test tooling
- CI/build scaffolding

## Licensing

Any engine-derived code incorporated from SliceBeam, OrcaSlicer-Mobile, OrcaSlicer, or PrusaSlicer will retain its required notices and comply with the applicable GNU AGPL-3.0 obligations.

## Licence

Source-available, free for noncommercial use under the [PolyForm Noncommercial License 1.0.0](LICENSE) (SPDX: `PolyForm-Noncommercial-1.0.0`). Copyright 2026 Madhav Baliga. For commercial use, contact the copyright holder.
