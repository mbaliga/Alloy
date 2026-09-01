# Alloy — Codex handoff

Date: 2026-08-25
Repository: `mbaliga/Alloy`
Working branch: `stage1-foundation`
Pull request: #1 — Stage 1 foundation: Android slicer architecture and A1 Mini LAN probe
Known branch head before this handoff commit: `d570d17a6c59de04d691751e26e15d3a810c2322`

## Mission

Build Alloy as a native Android slicer for the Bambu Lab A1 Mini, phone-first but adaptive to foldables, tablets, and Android desktop/windowed environments.

Core product requirements:
- Slice locally on Android.
- Match Orca/Bambu slicing behavior closely enough to safely target an A1 Mini.
- Export `.gcode.3mf`.
- Later send jobs over Bambu Developer Mode LAN transport without Bambu's proprietary network plugin.
- Own the Android UX instead of porting a desktop wxWidgets UI.

## Architecture decision

Primary engine candidate: extract the native Orca engine/Android build work from `CodeMasterCody3D/OrcaSlicer-Mobile`.

Do not inherit its Java/AppCompat product shell.

Target architecture:
1. Alloy-owned Kotlin/Jetpack Compose product shell.
2. Fresh typed Kotlin/JNI engine API.
3. Upstream-derived Orca native core carried with explicit provenance and minimal Android patches.
4. Dedicated printer transport abstraction.
5. Versioned profile data + parity fixtures.

Fallback order if the primary candidate fails a gate:
1. SliceBeam's PrusaSlicer-derived core.
2. Clean Android port from official OrcaSlicer.

## Important upstream facts

OrcaSlicer-Mobile is real and is a public fork of SliceBeam. It is unofficial/alpha and should not be treated as an official OrcaSlicer release.

Pinned primary-candidate revision:
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`

Observed Android configuration:
- compile/target SDK 35
- minSdk 21
- NDK `23.1.7779620`
- ABI `arm64-v8a`
- package/JNI lineage `ru.ytkab0bp.slicebeam`
- app version `0.4.6`
- native CMake build
- GLES 3 `GLView extends GLSurfaceView` + `GLRenderer`

Do not use `0.4.6` as the Orca engine version. The Android build injects the mobile application version into `SLIC3R_VERSION`; G2 exists specifically to identify the actual official Orca source revision.

## Renderer decision

For Stage 1 engine bring-up only, the inherited SliceBeam/OrcaSlicer-Mobile `GLView` may be hosted from Compose through `AndroidView`.

Constraints:
- basic model/G-code display only
- no new Alloy-specific renderer features in the legacy Java view
- no Filament rewrite during Stage 1
- before Stage 2, replace it with an Alloy-owned GLES renderer hosted through `AndroidExternalSurface`
- desktop requirements must be designed in: hover, right click, resizing, arbitrary aspect ratios, including 5120x1440 / 32:9

## UI approval gate

`docs/STAGE_1_IA.md` is still `PROPOSED — approval gate`.

Do NOT start Compose screen implementation until the owner explicitly approves that IA.

Current proposed flow:
`Import → Prepare → Slice → Inspect → Print / Export`

Quick overrides:
1. layer height / quality
2. walls + Arachne/Classic
3. infill density
4. infill pattern
5. supports + type
6. brim / adhesion
7. plate type
8. orientation / scale

## Adoption gates

The OrcaSlicer-Mobile extraction is a GO only if all four pass.

### G1 — reproducible Android source build

Acceptance:
- clean pinned clone
- SDK 35
- NDK `23.1.7779620`
- native dependencies reproducible from source
- `./gradlew :app:assembleDebug` succeeds
- bootstrap is documented for a fresh machine/CI runner

Implemented:
- `.github/workflows/g1-orca-mobile-source-build.yml`
- `ci/patch_orca_mobile_bootstrap.py`

Current dependency pins in the bootstrap patch:
- OpenVDB-Android `4d4a057d0a26d9cff88d6d7cc7bea80d27ffa7ec`
- Boost-for-Android `7943955c4d11a5bd61381a8b200c28619323eb0f`
- OCCT `7d2efad9c8a9a57ea96c4c8587134b34dd503cd8`

Latest run:
- run: `32658450065`
- job: `97240831867`
- result: FAIL

Important partial result:
- SDK/NDK setup passed
- upstream clone passed
- bootstrap patch passed
- **native dependency source build passed**
- `scripts/check-native-prebuilts.py arm64-v8a` failed
- APK source build did not run because verification blocked it

Treat this as a dependency-output/layout verification failure, not yet an Orca engine compile failure.

Next G1 work:
1. Read job `97240831867` logs/evidence.
2. Enumerate exactly which paths `scripts/check-native-prebuilts.py` says are missing.
3. Compare them with the paths produced by `scripts/build_all_deps_android.sh`.
4. Fix the bootstrap/copy layout or the gate only if the upstream check is demonstrably wrong.
5. Do not bypass the check or switch to APK-extracted native prebuilts as the definition of G1.
6. Rerun until the verifier passes and the native APK build is actually attempted.
7. If APK compilation then fails, diagnose that separately and record it as the next G1 failure.

### G2 — exact official Orca engine provenance

Acceptance:
- identify exact official OrcaSlicer commit/version represented by the Android engine tree
- record it in `docs/UPSTREAM_EVALUATION.md`
- use that exact revision for G3/G4 desktop parity

Implemented:
- `.github/workflows/g2-orca-engine-provenance.yml`
- `ci/find_orca_profile_anchor.py`
- `ci/identify_orca_engine.py`

The matcher uses exact Git blob identity rather than app version/date inference.

Latest run:
- run: `32658450073`
- job: `97240821499`
- result: FAIL

Important partial result:
- mobile clone passed
- official Orca history clone passed
- BBL profile-anchor analysis ran
- engine-tree provenance analysis ran
- evidence artifact was uploaded
- final evidence-threshold enforcement failed

Next G2 work:
1. Read the G2 artifact/logs:
   - `g2-bbl-profile-anchor.json`
   - `G2_BBL_PROFILE_ANCHOR.md`
   - provenance JSON written by `identify_orca_engine.py`
   - `G2_PROVENANCE.md`
2. Determine the best official Orca candidate, common-file count, exact-match ratio, and why the script did not accept it.
3. If evidence is insufficient, improve the matcher by adding justified anchors from BBL/libvgcode/CMake/profile history.
4. Do not lower thresholds merely to get green CI.
5. Once a unique defensible official commit is established, pin it in `docs/UPSTREAM_EVALUATION.md` and make G2 pass on that evidence.

### G3 — G-code parity

Blocked on G1+G2.

Fixtures:
1. 20 mm cube
2. overhang/support test
3. thin-wall keyboard-case-like model

Use identical A1 Mini printer/filament/process inputs on:
- desktop Orca at the G2-pinned revision
- Android engine

Acceptance:
- semantic toolpath feature sequence equivalent
- print-time estimate within 2%
- filament use within 2%
- no missing Arachne walls, supports, or seams in visual/toolpath inspection

Do not require byte-for-byte G-code identity unless later evidence shows that is meaningful.

### G4 — A1 Mini profile fidelity

Blocked on G1+G2.

Acceptance:
- `.orca_printer`/filament/process inheritance resolves correctly
- field-by-field comparison against desktop resolved profile
- at minimum verify bed geometry, machine limits, nozzle/process identity, start/end G-code, printer assumptions
- no silent fallback to Prusa defaults

## Stage 1 native scope

First required import formats:
- STL
- 3MF

Output:
- `.gcode.3mf`

ABI:
- arm64-v8a first

STEP/OCCT may be deferred from the first Alloy extraction if OCCT remains a major reproducibility blocker. Do not let STEP support block proving basic slicing/profile parity.

## Bambu A1 Mini LAN transport

Relevant files:
- `docs/BAMBU_LAN_TRANSPORT.md`
- `tools/bambu_lan_probe.py`
- `tools/requirements.txt`

Current Developer Mode contract being tested:
- implicit FTPS: TCP 990
- MQTT TLS: TCP 8883
- username `bblp`
- password: LAN/Developer access code
- command topic `device/<serial>/request`
- report topic `device/<serial>/report`
- start command `print.project_file`

Shipping semantics:
1. upload `.gcode.3mf`
2. verify transfer/remote file where possible
3. publish `project_file`
4. wait for report telemetry
5. only call it started when intended job reaches PREPARE/RUNNING

Do not equate MQTT publish success with print acceptance.

Physical A1 Mini validation has NOT been completed from the development environment.

Still unverified:
- root `ftp:///...` vs `/cache` + `file:///sdcard/cache/...` URL path on target firmware
- no-AMS `ams_mapping` behavior

Target printer is initially a non-Combo A1 Mini:
- `use_ams: false`
- do not invent/expose AMS mapping until hardware verified

Do not ship or reverse-engineer Bambu's proprietary network plugin into Alloy.

## Licensing

Combined work remains under AGPL-3.0 obligations.

Preserve the attribution chain:
- SliceBeam
- OrcaSlicer-Mobile
- OrcaSlicer
- PrusaSlicer / Slic3r
- BambuStudio

Keep source provenance for every imported native tree/patch.

## Immediate Codex execution order

Start here. Do not work on Compose UI.

1. Checkout `stage1-foundation`.
2. Read:
   - `docs/CODEX_HANDOFF.md`
   - `docs/UPSTREAM_EVALUATION.md`
   - `docs/STAGE_1_IA.md`
   - `docs/ARCHITECTURE.md`
   - `docs/BAMBU_LAN_TRANSPORT.md`
   - both G1/G2 workflows and all `ci/` scripts.
3. Diagnose latest G1 run `32658450065`, job `97240831867`.
4. Fix only the concrete dependency-output/verification mismatch and rerun G1.
5. Get G1 as far as an actual `:app:assembleDebug` native build; fix the next real failure if one appears.
6. Inspect G2 run `32658450073`, job `97240821499` and its evidence artifact.
7. Make G2 produce a defensible exact official Orca pin; update `docs/UPSTREAM_EVALUATION.md`.
8. Once G1 and G2 pass, add deterministic G3 fixture infrastructure.
9. Then implement G4 profile resolution/diff.
10. Record a GO/NO-GO decision. If any gate materially fails, switch to SliceBeam and repeat G1-G4.
11. Keep LAN physical validation separate.
12. Do not begin Compose screens until explicit Stage 1 IA approval.

## Things not to do

- do not call OrcaSlicer-Mobile an official Orca project
- do not inherit its Java/AppCompat UI as Alloy's architecture
- do not infer the Orca revision from mobile app version `0.4.6`
- do not weaken G1/G2/G3/G4 just to make CI green
- do not use APK-extracted native libraries as proof of a reproducible source build
- do not hard-code A1 Mini profiles into Kotlin
- do not rewrite the viewport in Filament during Stage 1
- do not build Alloy UI features on the legacy `GLView`
- do not claim physical LAN printing is validated
- do not guess `ams_mapping`
- do not introduce Bambu's proprietary network plugin

## Suggested next commits

Keep changes small and evidence-driven. Likely commit sequence:

1. `Fix G1 native dependency output verification`
2. `Complete reproducible OrcaSlicer-Mobile source build`
3. `Pin exact Orca engine provenance`
4. `Add A1 Mini parity fixture harness`
5. `Record G1-G4 go-no-go`

## Copy/paste Codex prompt

Work in `mbaliga/Alloy` on branch `stage1-foundation`.

Read `docs/CODEX_HANDOFF.md`, `docs/UPSTREAM_EVALUATION.md`, `docs/STAGE_1_IA.md`, `docs/ARCHITECTURE.md`, and `docs/BAMBU_LAN_TRANSPORT.md` before changing code.

Continue the engine-adoption gates only. Do not implement Compose UI because the Stage 1 IA is still awaiting explicit approval.

First diagnose G1 Actions run `32658450065` / job `97240831867`. The source dependency build succeeded but `scripts/check-native-prebuilts.py arm64-v8a` failed, so identify the exact output/layout mismatch, make the smallest justified fix, and rerun until the native APK build is actually attempted and G1 passes or exposes a genuine engine compile failure.

Then inspect G2 run `32658450073` / job `97240821499` and its uploaded provenance evidence. Establish a defensible exact official OrcaSlicer commit for the mobile engine. Improve the matcher if required, but do not weaken thresholds merely to green CI. Record the exact pin in `docs/UPSTREAM_EVALUATION.md`.

After G1 and G2 pass, proceed to G3/G4 parity/profile fixtures. Commit small evidence-driven changes and keep the PR documentation current.
