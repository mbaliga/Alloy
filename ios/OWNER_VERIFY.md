# iOS owner verification checklist (NEEDS-DEVICE-VALIDATION)

Nothing below has been run by anyone. Tick only what you saw on a device. Record the device, iOS version, and commit.

## Build
- [ ] `ios.yml` `app-macos` job is green (first run will likely fail on SwiftUI type errors; fix and re-run).
- [ ] Simulator build launches to the Models tab.

## Models / Plate
- [ ] Import a binary STL and an ASCII STL from Files; dimensions match your CAD to 0.1 mm.
- [ ] Import a >200 MB file: refused with a clear error, no crash.
- [ ] Two or three parts arranged on the 180 mm plate; rotated parts show a dashed outline and "(90 deg)".
- [ ] A part taller than 180 mm is reported, not silently accepted.
- [ ] Deleting a model by swipe updates the Plate tab.

## G-code
- [ ] A known-good Alloy/A1 Mini G-code passes; one with a move beyond 180.5 mm fails with a line number.
- [ ] A large (50 MB+) G-code does not crash or stall the UI beyond what you accept (the check runs on the main actor in this version).

## Privacy
- [ ] Proxy/Little Snitch-style check: the app opens no connections.
- [ ] About screen text is accurate for how you distribute the build.

## Not testable yet (do not expect): slicing, 3MF/OBJ/STEP import, printer LAN, 3D viewport.
