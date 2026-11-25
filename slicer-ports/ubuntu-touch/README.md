# Alloy for Ubuntu Touch

A preparation and inspection companion (import STL, check the plate, run the G-code safety preflight). **It does not slice.**
Status, evidence classes and decisions: [`../docs/UBUNTU_TOUCH_PORT.md`](../docs/UBUNTU_TOUCH_PORT.md). Device checklist: [`OWNER_VERIFY.md`](OWNER_VERIFY.md).

```
core/                 Qt-free C++17: G-code safety, plate planner, STL reader, session. Tests + Java parity (core/tests).
src/ qml/ qml.qrc     Qt 5.15 adapter (AlloyBackend) and QtQuick.Controls 2 UI.
tests/ui_smoke.cpp    Headless UI test (offscreen); not a device test.
CMakeLists.txt        Click build (Clickable `builder: cmake`) and install layout; manifest.json.in, alloy.apparmor, alloy.desktop.in
clickable.yaml        Clickable 8.x project file (framework ubuntu-sdk-20.04)
tools/                check_click_layout.py (structure + "no networking" guard)
```

Build a click (needs Docker): `pip install clickable-ut==8.10.0 && cd ubuntu-touch && clickable build --arch arm64`.
Desktop try-out: `clickable desktop`. CI does the same on every push: `.github/workflows/ubuntu-touch.yml`.

Privacy: no telemetry, no network. The AppArmor profile asks only for `content_exchange`; `tools/check_click_layout.py`
fails the build if `networking` or an unconfined template ever appears.
