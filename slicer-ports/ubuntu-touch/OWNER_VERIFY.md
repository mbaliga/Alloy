# Ubuntu Touch owner verification checklist (NEEDS-DEVICE-VALIDATION)

Nothing below has been run on a device or in the Lomiri emulator. Tick only what you saw. Record device, UT version
(ubuntu-sdk-20.04 expected), CPU architecture and commit. Headless Qt 5.15 on x86_64 is the only thing exercised so far.

## Build and install
- [ ] `ubuntu-touch.yml` `clickable (arm64)` job is green (first run will probably need fixes: the app was never cross-compiled).
- [ ] `click-review` output read; errors fixed (the step is informational until you have seen it).
- [ ] `clickable install` (or OpenStore sideload) of the arm64 click succeeds; app icon and name "Alloy" show in the launcher.
- [ ] App launches to the Models tab; no crash on rotate or on suspend/resume.

## Models / Plate
- [ ] "Import STL" opens the system Content Hub picker (this path is code-only: `Lomiri.Content` 1.1 usage). If the picker shows no
      file sources, note which apps you have installed; the fallback path field should NOT appear on device.
- [ ] A binary STL and an ASCII STL import; dimensions match your CAD to 0.1 mm.
- [ ] A file over 200 MB is refused with a message, no crash (memory behaviour on a phone is unknown).
- [ ] Two or three parts arranged on the 180 mm plate; rotated parts show a dashed outline and "(90 deg)".
- [ ] A part taller than 180 mm is reported on the Plate tab.
- [ ] Remove button updates the Plate tab.
- [ ] Touch targets (Remove, tab buttons) are comfortable; text is legible in your system theme (light and dark).

## G-code
- [ ] A known-good Alloy/A1 Mini G-code passes; one with a move beyond 180.5 mm fails with a line number.
- [ ] A 50 MB+ G-code does not stall the UI (the scan runs on a worker thread and streams in 1 MB chunks) or exhaust memory.

## Privacy and confinement
- [ ] The app's AppArmor profile lists only `content_exchange` (installed `.apparmor` file) and no network access is possible
      (e.g. `aa-status` / `journalctl` show no network use).
- [ ] About screen text is accurate for how you distribute it.

## Not testable yet (do not expect): slicing, 3MF/OBJ/STEP import, printer LAN, 3D viewport, persistence.
