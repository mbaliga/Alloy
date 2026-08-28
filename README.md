# Alloy

Alloy is a phone-first Android 3D-print preparation tool, initially targeting the Bambu Lab A1 Mini. The v1 app follows the compact workflow **Import → Prepare → Slice → Inspect → Export** instead of presenting a desktop slicer compressed onto a phone.

## v1 status

The repository now contains a runnable Android application. It can:

- import ASCII/binary STL and single-model 3MF files through the Android file picker;
- show a touch-rotatable, pinch-zoomable model and its A1 Mini build plate;
- edit the conservative v1 recipe (layer height and infill);
- slice offline on-device into deterministic perimeter and clipped scanline-infill G-code;
- inspect the generated toolpath one layer at a time; and
- export an inspectable `.gcode.3mf` package through Android Storage Access Framework.

This is a personal-use v1 foundation, not yet a replacement for OrcaSlicer. The current slicer does not implement organic/tree supports, multi-object arrangement, modifiers, multi-material, advanced cooling/seam logic, or verified Bambu metadata. Exported jobs must be opened and checked before any physical print. The app intentionally does not expose a misleading one-tap Print button until the engine/profile and LAN transport gates are proven.

The previous Stage 1 research and parity tooling remains in `docs/`, `parity/`, `ci/`, and `tools/`. Its rejected Orca-Mobile provenance result is recorded in `docs/GATE_STATUS.md`; the app does not silently claim that fork as an engine dependency.

## Build and run

Open the repository in Android Studio, or run:

```bash
gradle :app:assembleDebug
```

The resulting APK is `app/build/outputs/apk/debug/app-debug.apk`. The GitHub Actions workflow `Alloy Android v1` performs the same debug build and retains the APK as an artifact.

On launch, tap **Import model**, select an STL or 3MF, adjust **Recipe** if needed, tap **Slice**, review layers with **Layer − / Layer +**, and use **Export .3mf**. The model is centered automatically on the 180 × 180 × 180 mm A1 Mini volume.

## Validation

Existing parity tooling can be run from the project directory:

```bash
python3 -m py_compile ci/*.py parity/*.py tools/*.py
python3 -m unittest discover -s parity -p 'test_*.py' -v
```

The Android build is verified in CI because this workspace does not include an Android SDK or JDK. See `docs/STAGE_1_IA.md` for the product contract, `docs/V1_IMPLEMENTATION.md` for the v1 safety boundary, and `docs/ARCHITECTURE.md` for the longer-term native engine boundary.

## Licensing

Any engine-derived code incorporated from SliceBeam, OrcaSlicer-Mobile, OrcaSlicer, or PrusaSlicer will retain its required notices and comply with the applicable GNU AGPL-3.0 obligations. The current app module contains no engine-derived code.
