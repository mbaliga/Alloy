# Alloy on Ubuntu Touch: status, decisions, evidence

Owner directive 2026-11-17 overrides the plan's earlier "no-port" proposal (`PORTING_NOTE.md`): build Ubuntu Touch,
with the click builds on GitHub Actions (ubuntu + Clickable). Everything lives in `ubuntu-touch/`.

## What this is, honestly

**A preparation and inspection companion, not a port of the Android slicer.** The slicing engine (libslic3r/Orca via
JNI, vendored under `app/src/main/jni` and `third_party/orca-official`) is built only for Android through the NDK; a
Linux arm64 click build of it is unverified and its memory use on a phone is unknown. The Ubuntu Touch app therefore does
**not slice**, says so on every screen that could be mistaken for slicing, and its output is never print-qualified (the
repo's own gate status is strict G3 parity FAIL / NO-GO for printing: `docs/GATE_STATUS.md`). Running the Android APK under
Waydroid would not be a port either (owner ruling OQ-21) and nothing here depends on it.

| Capability | Ubuntu Touch today | Evidence class |
|---|---|---|
| G-code safety preflight (same invariants as Android, incl. bed/Z/arc bounds) | `ubuntu-touch/core` `inspectGcode` + streaming `GcodeStream` | BUILT-HERE (x86_64, gcc 13): the 1,508-case golden corpus produced by the real Java class matches exactly |
| Plate arrangement (best-fit shelf, 0/90 deg) | `planPlate` | BUILT-HERE: 670-case bit-exact float parity vs Java (356 are error cases; includes 70 boundary cases around the 0.0001 epsilon, and a mutated epsilon is detected) |
| STL import (binary + ASCII), bounds | `readStl` | BUILT-HERE: unit tests only; not corpus-diffed against Java |
| Preparation session (import, remove, plate review) | `PreparationSession` | BUILT-HERE: unit tests |
| Core under AddressSanitizer + UBSan | same tests | BUILT-HERE: clean |
| Qt 5.15 adapter (`AlloyBackend`) and QML UI (Models, Plate, G-code, About) | `ubuntu-touch/src`, `ubuntu-touch/qml` | BUILT-HERE on x86_64 Qt 5.15.13: loaded headless (offscreen, software scene graph) by `tests/ui_smoke.cpp`, driven with synthetic mouse clicks, 10 tests pass, 25 of 25 repeated runs; each tab rendered to pixels |
| Click packaging (`manifest.json.in`, AppArmor, `.desktop`, icon, CMake install) | `ubuntu-touch/` | BUILT-HERE for layout only: `cmake --install` into a staging dir passes `tools/check_click_layout.py` (amd64); no `.click` archive was produced here |
| `clickable.yaml` | `ubuntu-touch/clickable.yaml` | BUILT-HERE (schema): Clickable 8.10.0 accepted it, then stopped because there is no Docker daemon here; an unknown key was rejected, so validation is real |
| Content Hub file picker | `qml/ContentHubImporter.qml` | CODE-ONLY: the `Lomiri.Content` module does not exist here; the file has never been loaded. The UI falls back to a typed path field when the module is missing (tested) |
| arm64 / armhf compile of the core and app | `clickable` lanes | CI-LANE-WRITTEN; never compiled here. The core is portable C++17; bit-exact float parity on arm64 depends on `-ffp-contract=off` (set) and is unverified |
| CI lanes (core + sanitizers, Java golden regeneration diff, Qt smoke + layout check, Clickable click for arm64/armhf/amd64, click-review, tag-gated draft release) | `.github/workflows/ubuntu-touch.yml` | CI-LANE-WRITTEN: YAML parses, actions SHA-pinned, no secrets; the `core`, `parity-vs-java` and `ui-smoke` commands were run locally. The `download-artifact` SHA was written from memory (it is the v4.3.0 pin); confirm on first run |
| Everything on a device or in the Lomiri emulator | whole app | NEEDS-DEVICE-VALIDATION |

## Not ported (gaps that remain)

- **Slicing engine**: none. Phase 2 (a CI-only arm64 build of libslic3r with Qt bindings) is not started; boost, CGAL, openvdb,
  TBB, mcut, OpenCV are unverified for it. If it fails the app stays a companion.
- **Mesh formats**: OBJ, 3MF, STEP readers, mesh repair, the `.alloy.zip` project and the G-code package validator are Android-only.
- **3D viewport**: the Android GLES viewport has no counterpart (Qt Quick 3D is the candidate); the Plate tab is a 2-D footprint view.
- **Printer transport** (Bambu LAN MQTT/FTP with TLS pin), printer credential custody, inventory, profile import: not started.
  The AppArmor profile deliberately requests **no** `networking` policy group, so the OS would block a socket. When transport is
  added it must add that group and decide credential custody (plan OQ-22: OS keystore with a visibly weaker fallback shown;
  nothing is stored today, so OQ-22 is not yet triggered).
- **Persistence**: nothing is stored; the session lives in memory.
- **Export**: the G-code verdict is shown on screen only; there is no share/export of a report.

## Decisions recorded

1. **Real app branch**: unchanged from the iOS stage (`origin/codex/a1mini-filament-route-matrix` merged into the dev branch).
2. **Approach**: a Qt-free C++17 core (the same three Java classes the iOS core ports, golden-diffed against the real Java through
   the *same* fixture files as iOS, so there is one Java truth and two ports) + a thin Qt 5.15 / QML shell + a click package built
   by Clickable. Chosen over Rust because Qt/QML is C++-native and the click toolchain is C++/CMake; no concrete reason to prefer
   another stack was found. Native, no web wrapper, no Waydroid.
3. **Qt 5 and plain `QtQuick.Controls 2`, not `Lomiri.Components`**: `ubuntu-sdk-20.04` is Qt 5.15, and Controls 2 picks up the
   system (Suru) style on device. This keeps the UI loadable and renderable headless in CI, which Lomiri.Components is not. The
   price: no Lomiri-specific chrome. Content Hub, which has no Controls 2 equivalent, is isolated in one file and loaded
   dynamically.
4. **Licence (owner decision needed, carried over)**: `LICENSE` is AGPL-3.0 (engine-derived code lives in the repo); the PolyForm
   text from `main` is at `LICENSES/PolyForm-Noncommercial-1.0.0.txt`. The Ubuntu Touch code is Alloy-authored with
   `SPDX-License-Identifier: AGPL-3.0-only`. OpenStore accepts open-source licences; the choice stays the owner's (plan OQ-12).
5. **No telemetry**: no networking policy group, no network code, no SDKs. Stated on the About screen.
6. **Colour never carries meaning alone**: rotated parts use a dashed stroke plus a "(90 deg)" label; the G-code verdict is a
   glyph and a word.
7. **Not merged** (carried over): `codex/tree-support-pruning-pr12` and `codex/alloy-v1`.

## Owner actions (see also `ubuntu-touch/OWNER_VERIFY.md`)

1. Confirm the click name `alloy.mbaliga` and fill `APP_MAINTAINER` (currently an `.invalid` placeholder) in
   `ubuntu-touch/CMakeLists.txt` before any OpenStore upload. The Apple rule `com.asoc.<app>` does not apply to click names.
2. Decide the licence question and review OpenStore terms against it.
3. Push a tag `ut-v0.1.0` (or run the workflow manually) and read the first run; expect to fix whatever the arm64/armhf
   Clickable lanes reveal (the app was never cross-compiled) and read the `click-review` output.
4. Install the arm64 click on your Ubuntu Touch device (OQ-1) and walk `ubuntu-touch/OWNER_VERIFY.md`, especially the Content Hub
   picker, which is code-only.
5. Create an OpenStore account and submit by hand when satisfied; nothing in CI submits anything.

## Re-running the local evidence

```
cmake -S ubuntu-touch/core -B build/core && cmake --build build/core && ctest --test-dir build/core   # 112 unit checks + 2,110 parity checks
sh ios/parity/regen.sh                                                                                  # regenerate Java goldens; must produce no git diff
# needs qtbase5-dev qtdeclarative5-dev qml-module-qtquick-controls2 qml-module-qtquick-layouts:
cmake -S ubuntu-touch -B build/ut -DALLOY_BUILD_UI_TESTS=ON -DCMAKE_INSTALL_PREFIX=/. && cmake --build build/ut
ALLOY_SMOKE_OUT=/tmp/shots build/ut/alloy_ui_smoke
DESTDIR=$PWD/stage cmake --install build/ut && python3 ubuntu-touch/tools/check_click_layout.py stage --arch amd64
```
