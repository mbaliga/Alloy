# Alloy on-device MVP verification

Use the exact build lane named in each section. The latest Android v1
artifact is the ordinary CI-signed QA build from run 37744453665, app-source
commit 8bc416109d2b73c1a7ea737bffe88d841ae9853d. It is suitable for phone
workflow and non-native slicer smoke checks, but it does not qualify the native
slicer, printer transport or production release. Native verification for the
current capability-parser change is pending in run 37745898443. The previous
native run built from pre-parser-fix commit 849df7eac15ad07d4ef31bfdde39c6b6d0b08b93
but failed instrumentation because the runtime catalog contract rejected the
new A1 mini support-filament entries. No current-source isolated
`alloyPhysicalPilot` artifact is available yet. None of these builds by itself
authorizes general direct printing, Bambu Handy compatibility, or production
release promotion.

## 1. Install the matching artifacts

1. For the current UI/software smoke pass, download
   [`alloy-v1-release-ci-apk` from Android v1 run 37728381340](https://github.com/mbaliga/Alloy/actions/runs/37728381340).
   Confirm the run targets commit `462ad6ca49c049444960e36f83845a15093a740c`
   and completed successfully before installing. Android v1 passed **193 instrumentation tests (9 skipped, 0 failures)**, including the new shared-cache-lock regression. Its artifact ZIP digest is
   `sha256:ed4234c78aedf68beb7b9e831da0ba125936d3c24361cb771e03969b9f20f8d8`
   (expires **2027-01-06**). After extracting, install `app-release.apk` on an
   ARM64 Android phone; compute and record the APK's own SHA-256 separately.
   This build contains the bounded model-bundle import and shared-cache-lock
   regression checks, plus the already-shipped Library and learning surfaces.
2. For native-runtime checks, download the matching native release and
   instrumentation pair from
   [native-build run 37728381335](https://github.com/mbaliga/Alloy/actions/runs/37728381335),
   commit `462ad6ca49c049444960e36f83845a15093a740c`. Artifact ZIP digests:
   native release `0b47e16c2f219d57dd141584cd0de73f27a494e9aa28642888e63dadc45627aa`,
   instrumentation APK `64022cd614341c9ff469166e7999f5b144ef37c27b43fcd86236e3aa79ba60da`,
   instrumentation report `56a666c6a3a69a53552495a4a98d0d9e5f5a066c3697d2f44ab265884a124fdb`,
   G3 evidence `d8b98666a48daa2be073f911f6426eb760c531e3212684836c0145b769fc5719`.
   The run passed native instrumentation (186 emulator tests; 2 skipped) and
   structurally validated G3 evidence. These CI QA artifacts are not
   production-signed; fresh G2 provenance is still **INSUFFICIENT / FAIL**
   (66/72 exact-history samples; 314/479 exact common files, 65.55%; latest
   [run 37741025557](https://github.com/mbaliga/Alloy/actions/runs/37741025557)).
   A separate Bambu Studio diagnostic also failed to establish a baseline
   (101/445 historical best; [latest run 37741025606](https://github.com/mbaliga/Alloy/actions/runs/37741025606)).
   Desktop semantic-parity warnings remain, and no physical printer route has
   been qualified.
3. Before any physical pilot, download the isolated pilot APK pair and its
   software-only evidence from
   [`alloy-a1-mini-physical-pilot-ci` on run 37728381335](https://github.com/mbaliga/Alloy/actions/runs/37728381335).
   Artifact ZIP digest:
   `sha256:c627c687f5f327d437fd29aa2d4a41b01d524bc290a5b1005a9e00a0f4f8dfd0`.
   CI validated the five unchanged no-support PLA fixtures on an emulator.
   This is not a printer test: physical qualification and direct-send
   qualification remain pending. Install the pilot pair only for the narrow
   procedure in section 6.
4. Confirm a cold launch succeeds without asking for notification, LAN, or
   pairing permission.
5. Capture the APK SHA-256 values, Android version, phone model, and Alloy
   build/version. Do not add printer access codes, serial numbers, LAN hosts,
   or certificate fingerprints to a public record.
Expected: the first-run, five-step orientation route is skippable and has a
safe path to Library. No screen says the printer is qualified for direct send.


## 1a. Phone navigation and visual system