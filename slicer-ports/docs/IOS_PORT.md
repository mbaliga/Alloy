> Moved to `slicer-ports/docs/` in the repository consolidation. Paths below are updated to the current layout (`android/` for the slicer, `slicer-ports/` for this port); statements about "the repo" and about the state of other branches describe 2026-11-17 and are kept as written.

# Alloy on iOS / iPadOS: status, decisions, evidence

Owner directive 2026-11-17 overrides the plan's earlier "no-port" proposal: build iOS, on GitHub Actions.
Apple bundle id rule `com.asoc.<appname>`; the id used is **`com.asoc.alloy`**, set in exactly one place
(`slicer-ports/ios/AlloyApp/project.yml`, `PRODUCT_BUNDLE_IDENTIFIER`).

## What this is, honestly

**A preparation and inspection companion, not a port of the Android slicer.** The slicing engine
(libslic3r/Orca via JNI, vendored under `android/app/src/main/jni`, `android/third_party/orca-official`) is built only
for Android; an iOS build of Orca/libslic3r has no verified precedent. The iOS app therefore does **not slice**,
says so on every screen that could be mistaken for slicing, and its output is never print-qualified
(the repo's own gate status is strict G3 parity FAIL / NO-GO for printing: `android/docs/GATE_STATUS.md`).

| Capability | iOS today | Evidence class |
|---|---|---|
| G-code safety preflight (same invariants as Android, incl. bed/Z/arc bounds) | `AlloyCore.GcodeSafetyValidator` | BUILT-HERE (Linux Swift 6.0.3): 1,508-case golden corpus produced by the real Java class matches exactly |
| Plate arrangement (best-fit shelf, 0/90 deg) | `AlloyCore.PlateArrangementPlanner` | BUILT-HERE: 670-case bit-exact float parity vs Java (356 are error cases; includes epsilon-boundary cases) |
| STL import (binary + ASCII), bounds | `AlloyCore.StlReader` | BUILT-HERE (unit tests; not corpus-diffed against Java) |
| Preparation session logic (import, remove, plate review) | `AlloyCore.PreparationSession` | BUILT-HERE (unit tests) |
| SwiftUI shell (Models, Plate, G-code, About) | `slicer-ports/ios/AlloyApp` | CODE-ONLY: `swiftc -parse` syntax check only; never type-checked against SwiftUI, never built with Xcode |
| Xcode project generation | `slicer-ports/ios/AlloyApp/project.yml` (XcodeGen) | CODE-ONLY (YAML parses) |
| CI lanes (Linux swift test, Java-golden regeneration diff, macOS build, tag-gated archive) | `.github/workflows/ios.yml` | CI-LANE-WRITTEN (YAML parses; `regen.sh` run locally and produced no diff) |
| Everything on a device or simulator | whole app | NEEDS-DEVICE-VALIDATION |

## Not ported (gaps that remain)

- **Slicing engine**: none. Phase-2 spike (CI-only XCFramework build of libslic3r for arm64 iOS) is not started; if
  it fails the app stays a companion. Dependencies (boost, CGAL, openvdb, TBB, mcut, OpenCV) are unverified on iOS.
- **Mesh formats**: OBJ, 3MF, STEP readers, mesh repair, `.alloy.zip` project and the G-code package writer/validator
  (`GcodePackageValidator`, needs ZIP; use Apple `Compression` or a pinned SPM zip package) are Android-only.
- **3D viewport**: the Android GLES viewport has no iOS counterpart yet (Metal/SceneKit); the Plate tab is a 2-D footprint view.
- **Printer transport** (Bambu LAN MQTT/FTP with TLS pin), printer credential custody (Keychain), inventory,
  profile import: not started. Nothing in this build opens a socket. When transport is added it must add
  `NSLocalNetworkUsageDescription` and decide ATS handling, and credentials go in Keychain only (rule OQ-22).
- **Persistence**: nothing is stored; the session lives in memory.

## Decisions recorded

1. **Real app branch**: `origin/codex/a1mini-filament-route-matrix` (813 commits ahead of `main`, superset of the
   luna/native-probe/g2/phone-first-mvp/stage1 lines) was merged into the dev branch; the plan branch was merged first.
   Not merged: `codex/tree-support-pruning-pr12` (26 commits, conflicts in `android/docs/GATE_STATUS.md` and
   `android/docs/ON_DEVICE_MVP_CHECKLIST.md`) and `codex/alloy-v1` (launcher icon and CI fixes). Owner/Android-stage call.
2. **Licence (owner decision needed)**: `main` holds a PolyForm Noncommercial 1.0.0 `LICENSE` (#15); the app branch holds
   AGPL-3.0 because of engine-derived code. The merge kept **AGPL-3.0 as `LICENSE`** and preserved the PolyForm text at
   `LICENSES/PolyForm-Noncommercial-1.0.0.txt`. The iOS code here is Alloy-authored and carries
   `SPDX-License-Identifier: AGPL-3.0-only` to be consistent with the repo, not because it contains engine code (it contains none).
   The plan's open question OQ-12 stands: AGPL-3.0 vs App Store terms is **unreviewed**; do not submit before that review.
3. **Approach**: pure Swift core (ported from Java, golden-diffed against the real Java) + thin SwiftUI shell, as the audit
   recommended. Engine is phase 2. Native Swift, no web wrapper, no Waydroid-style substitute.
4. **No telemetry**: the app has no networking and no SDKs. Only Apple-collected TestFlight/App Store crash reports can
   exist (the approved exception); this is stated in the About screen.
5. **Colour never carries meaning alone** (rotated parts use a dashed stroke and a "(90 deg)" label).

## Owner actions (see also `slicer-ports/ios/OWNER_VERIFY.md`)

1. Add the `com.asoc.alloy` row to `NAMES.md` (plan rule R11; outside this repo).
2. Decide the licence question above and complete the AGPL / App Store review (OQ-12).
3. Enrol/confirm the Apple Developer account; create an App ID, a distribution certificate and a provisioning profile.
4. Create the GitHub environment `ios-release` with required reviewers and secrets `APPLE_CERT_P12_BASE64`,
   `APPLE_CERT_PASSWORD`, `APPLE_PROFILE_BASE64`, `APPLE_PROFILE_NAME`, `APPLE_TEAM_ID`.
5. Run the workflow once (`workflow_dispatch`), fix whatever the first macOS run reveals (expected: SwiftUI compile errors,
   since the shell was never type-checked), then re-label the evidence.
6. Verify on a real iPhone/iPad using `slicer-ports/ios/OWNER_VERIFY.md`.
7. Billing: macOS minutes on a private repo are metered at a multiplier.

## Re-running the local evidence

```
cd slicer-ports/ios/AlloyCore && swift test          # 28 tests (Linux or macOS)
sh slicer-ports/ios/parity/regen.sh                  # regenerate Java goldens (JDK 17 + python3); must produce no git diff
```
