# Alloy on-device MVP verification

Use the exact build lane named in each section. The latest successful native
baseline is run [37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
at app-source commit `64fc3503a1bb1812f8e6a0870a7a8c765198793c`. It passed
the full ARM64 source-dependency/native build, API-35 ARM-translation native
instrumentation, G3 evidence export, and isolated five-fixture A1 Mini
software-pilot lane. The native instrumentation report records **187 tests,
0 failures, 2 skipped**; `NativeEngineSmokeTest` records 7 tests (5 passed,
2 skipped), and the separate software-pilot test passed. The pilot artifact
[`alloy-a1-mini-physical-pilot-ci`](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
has ZIP SHA-256 `c9300883ef7f1d55d7023b1632a5afb9889fa1f56cba526e4d7d34e8bd197c76`.
The native release CI APK artifact ZIP SHA-256 is
`d69137ac5c6074b1af7466e27aee950542dda2fbfa43cbadc0d8444ed63566dc`; the G3
evidence artifact ZIP SHA-256 is
`5b3e854b340f101e97432fae7b7e3c48aaafe78db4e1b4b4746bc7262cb33532`.
The latest ordinary Android run is
[37883851602](https://github.com/mbaliga/Alloy/actions/runs/37883851602) at
the same source commit; it passed and published `alloy-v1-release-ci-apk`
(artifact ZIP SHA-256
`f399275b175629abb01c65d9eed52953c0193df5d18cdd699bea56ec5ebc8f0a`). These
are CI-debug QA artifacts and emulator/software evidence only; they do not
prove a physical print, Bambu Handy acceptance, or production signing. Fresh
G2 provenance remains **INSUFFICIENT / FAIL**, and G3 semantic parity remains
open. No software artifact authorizes general direct printing or production
release promotion.

**Current-source install gate (checked 2026-10-10 06:37 UTC):** ordinary
Android v1 run
[38029581732](https://github.com/mbaliga/Alloy/actions/runs/38029581732),
source `dada7c98`, completed successfully and published artifact
`alloy-v1-release-ci-apk` (artifact ID `11661663592`, ZIP SHA-256
`9450158fa4ad153f6fccb3f6249c6ae4c4382f9529924e2de82acea3c047e1b2`). The
archive contains `app-release.apk` (8,813,118 bytes). This ordinary build has
`alloyNativeEngine=false`. Its API-35 ARM emulator acceptance step completed
189 tests with zero failures; nine native-engine and pilot tests were skipped.
It is suitable for current-source UI, import, planning and conservative
fallback-slicer boundary checks, but is not native slicer evidence and must
keep physical printing blocked. Native run
[38029368382](https://github.com/mbaliga/Alloy/actions/runs/38029368382) has
passed its source-dependency build, generated-input validation, and
source-built GMP/MPFR validation; it is now compiling Alloy with the optional
native engine. It still tests only the first version-header fix (`c37bea15`),
not the follow-up macro-scope repair. Native run
[38029581758](https://github.com/mbaliga/Alloy/actions/runs/38029581758), at
`dada7c98` with the additional target-scoped macro fix, remains queued behind
it. No current-source native APK or pilot artifact is available. Do not use
historical native artifacts as current native evidence; update their IDs and
hashes only after the native build, instrumentation, G3 export and software
pilot steps pass.

## 1. Install the matching artifacts

**Pilot-install status:** the last successful software-only pilot baseline
[37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
succeeded and published `alloy-a1-mini-physical-pilot-ci`. The workflow built
and verified the separate `.pilot` app/test package IDs and passed the bounded
five-fixture software-only package checks. Use only for the scope below; it is
not physical qualification. Do not uninstall a working Alloy installation
merely to replace it with a CI-debug-signed QA build.

1. For current-source ordinary app checks, download
   `alloy-v1-release-ci-apk` from run
   [38029581732](https://github.com/mbaliga/Alloy/actions/runs/38029581732)
   (source commit `dada7c98`; artifact ZIP SHA-256
   `9450158fa4ad153f6fccb3f6249c6ae4c4382f9529924e2de82acea3c047e1b2`),
   extract `app-release.apk`, install it on an ARM64 Android phone and record
   the APK's own SHA-256 separately. This APK omits the optional native engine;
   it can verify the ordinary app and conservative fallback behavior only.
   CI-debug signatures can differ between runs, so Android may refuse to update
   an existing Alloy install. Before uninstalling, export projects you need as
   `.alloy.zip` and back up other app data; uninstalling clears app-private
   data and saved printer credentials. Use a separate Android user/work profile
   or stop if you cannot back up safely.
   A CI-debug QA build verifies the app workflow/Handy package-visibility
   wiring, not native slicing, printer transport, Handy's actual import
   acceptance, or release.
2. Historical native run
   [37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
   produced native QA, instrumentation and G3 evidence, plus five-fixture
   software-pilot evidence and a package-ID-verified isolated pilot APK pair,
   but it is not the current source. Wait for the queued current-source native
   run to pass and publish a fresh artifact before testing native slicing or
   the isolated software pilot. CI-debug signing remains non-production; no
   software artifact establishes successful physical printing or Handy import
   acceptance.
   Fresh G2 provenance remains **INSUFFICIENT / FAIL** (66/72 exact-history
   samples; 314/479 exact common files, 65.55%;
   [run 37741025557](https://github.com/mbaliga/Alloy/actions/runs/37741025557)).
   The separate Bambu Studio lineage diagnostic did not establish a baseline
   (101/445 historical best;
   [run 37741025606](https://github.com/mbaliga/Alloy/actions/runs/37741025606)).
   No physical printer route has been qualified.
3. The five-fixture software evidence is not physical qualification. Run
   37883851621 is an historical software-only baseline; wait for a successful
   current-source pilot artifact before section 6. Keep all
   printer, firmware, transport and production-release
   claims unqualified until the human-reviewed on-device evidence and
   provenance gates pass.

## 1a. Phone navigation and visual system

- Confirm the actual Alloy mark is centered in the header and cropped within
  its black circular field; it must not be a shrunken `ALLOY` wordmark.
- At the bottom of the workshop, confirm the white contextual action band and
  dark destination band follow the same shallow arc. Their curves should stay
  parallel from edge to edge, with no clipped endcaps.
- Confirm the two circular controls sit on the left and right limits of the
  upper band. The left one opens Library/search and the right one opens
  readiness/alerts. Both need spoken accessibility labels.
- Confirm the lower band exposes only five visual destinations: Workshop,
  Library, Prepare, History, and More. A pressed item receives the restrained
  selected fill; each destination remains at least a 48 dp touch target.
- Confirm the upper band reads the current contextual action or page state,
  rather than repeating a page name unnecessarily. Use Library to verify that
  it opens the **LIBRARY** surface (not the retired “Model Atlas” label).

Expected: the navigation is legible at default and enlarged Android text, and
all destinations remain reachable without horizontal scrolling.

## 2. First-use, learning and recovery

- Step through Welcome, Explore, hardware identity, first object, and
  preflight. Verify that “Learn” is available later and that a skipped route
  can be replayed from Learn.
- Open Start, Quick cheat sheet, Materials and Troubleshoot. Search for
  `sticking`, `stringing`, `smoke`, and `tangle`; symptom results should update
  as text is entered, without a separate Find button.
- In **Fix a symptom**, confirm the catalog contains at least 24 cards. With an
  empty query, show the hard-stop cards only; for a search, put matching
  hard-stop guidance before routine remedies. Open
  `Printer state is not confirmed` and confirm the unbranded phone/printer
  illustration is accompanied by native text saying state is unconfirmed.
- Verify every displayed article retains readable safety and source/scope
  labels. A hard-stop article must tell the user to stop, not merely change a
  setting.
- Rotate the phone, increase Android font size, enable dark mode if supported,
  and enable Android reduced motion. Critical text and primary actions must
  remain reachable and understandable.
- Force-close and reopen after importing a model and after producing a slice.
  Confirm the current project/plate returns without silently changing the
  recipe or authorizing a send.

Expected: learning is readable offline and is never a gate-bypass path.

## 3. Library and planning profiles

- Open Library/Model atlas and verify saved/bundled models can be selected,
  favourited and reopened without a duplicate project.
- Open **Profile** and switch each source-pinned planning profile: A1 mini,
  A1, then P1S. Confirm the build volume changes to 180 × 180 × 180 mm for
  A1 mini, 256 × 256 × 256 mm for A1, and **256 × 256 × 250 mm** for the
  source-backed P1S 0.4 mm planning profile. The P1S marketing envelope must
  not override the more conservative profile limit.
- Confirm switching profiles clears an existing slice and labels the profile
  `review required` rather than verified.
- Open the capability view. Confirm A1 mini distinguishes direct/external
  feed, AMS lite, and unsupported regular AMS; it must show 1.75 mm guidance,
  AMS lite spool dimensions, and the TPU/PVA exclusions.
- Open Inventory. Verify ledger rows cover all 14 A1 Mini catalog material
  classes: PLA Basic, PETG, TPU, PVA, Support PLA, Support PETG, ABS, ASA, PC,
  PA/Nylon, PET, PLA-CF, PETG-CF and other CF/GF-filled polymers. The eight
  A1 Mini not-recommended rows must say “Track only” and “not recommended on
  A1 Mini,” and must not create default reorder alerts. Every unmeasured
  built-in quantity should read **Not recorded**, not zero, stocked, or loaded;
  unknown quantities must not generate reorder alerts or display an empty
  gauge that looks like measured zero. Record a physically checked exact
  quantity (including zero) before using add/use stock actions. Service
  history should read **not recorded** until an actual service is logged; an
  interval must not invent a due date from install time. The inventory ledger
  is not a printer sensor or material-compatibility approval. A completed
  print may record estimated filament usage while stock is unknown, but it
  must not fabricate a remaining-stock quantity. Confirm that exact stock
  remains editable and survives project snapshot export/restore.

Expected: the three-printer catalog is available for planning only; all three
remain **Not qualified** for Alloy direct send.

## 4. Real-model geometry workflow

Use a non-sensitive STL or 3MF with at least two independently movable parts.

1. Import it from Android Files or Share into Alloy; check its name, part
   count, plate boundary and dimensions.
2. In **Parts**, focus each part, move and rotate it, then use **Arrange**.
   Check that every object remains inside the plate boundary with visible
   spacing.
3. Use **Model → Lay flat** and **Auto orient** separately. Before each slice,
   visually review the changed first-contact face and whether supports are now
   needed. Do not treat Auto orient as a guarantee of strength or surface
   quality.
4. Set a normal PLA recipe with supports **off** for the first pilot. Confirm
   the active A1 Mini profile says 0.4 mm nozzle and PLA Basic.
5. Slice. Review bottom, middle, and top layers; inspect thin walls, bridges,
   travel lines, model bounds and warnings.
6. While slicing, confirm the dark progress surface appears with the supplied
   hothead artwork near the bottom edge. Confirm its orange rail fills only as
   the durable slice job reports progress, and the nozzle touches the rail's
   lower alignment edge. Confirm the center copy is readable and changes while
   waiting; the smaller line reports the current phase and percentage.
7. Tap **Cancel slice** during a sufficiently long job. The head should hold
   its last reported position until cancellation is acknowledged, then the
   loader should close and the project should remain available for review.
   Repeat with Android font scaling and TalkBack: progress percentage and phase
   must be announced without relying on the animation alone.

Expected: import, layout, bounded orientation, slicing and layer inspection
work without a desktop. Supports remain a review-required feature and are not
part of the first physical pilot.

## 5. Estimate and export boundary

- Open **Print plan** after a slice. It should show the engine-reported
  filament in mm and an *approximate* g value, time if reported, current
  nozzle/plate temperatures and current orientation. With supports off, the
  figure is labelled **MODEL TOOLPATH FILAMENT**; with supports on, it is
  labelled **ENGINE FILAMENT TOTAL** and must not be described as model-only.
- Confirm separate support material is `0 g` only when supports are off.
  When supports are on, the total may include support material, but the support
  portion itself must say separately unreported rather than inventing a value.
- Confirm prime/purge/cleaning and live printer temperature/idle state say
  unavailable/not reported unless a qualified engine or printer supplied them.
- Export the `.gcode.3mf`; reopen it in a compatible desktop verifier if
  available and retain its SHA-256.
- With Bambu Handy installed, tap **Share to Bambu Handy** for a structurally
  validated package. On Android 11+, confirm Alloy resolves the intended
  receiver and launches Handy directly; then check Handy's own import result.
  A successful launch is not proof of acceptance. If Handy is absent or has no
  receiver, confirm the ordinary chooser fallback opens and can be cancelled
  without altering the package. Alloy must not call the file Bambu-ready or
  claim Handy accepted it unless the app visibly confirms the import.
- Return to Alloy and open **Print readiness**. In the ordinary build, confirm
  direct-print controls remain blocked until every native/profile/support/
  artifact/pairing/certificate and recovery gate is actually satisfied.

Expected: a structurally validated, inspectable package can be exported; no
unverified physical dispatch or Bambu Handy compatibility claim is made.

## 6. Narrow A1 Mini evidence run (do not broaden the scope)

Only after sections 1–5 pass, follow
[`NO_SUPPORT_PLA_PILOT.md`](NO_SUPPORT_PLA_PILOT.md) exactly:

- Scope: A1 Mini model N1, 0.4 mm nozzle, PLA Basic, textured PEI, one colour,
  external/direct feed, no AMS and no supports.
- The package-ID-verified pilot artifact from
  [native run 37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
  is an historical successful baseline; its app/test package-ID assertions
  passed, but it does not represent the current branch. Wait for a fresh
  successful native run, then install only that run's `app-release.apk`; its
  `.pilot` ID should coexist with regular Alloy. The matching
  instrumentation APK is optional for ADB diagnostics. Keep the included
  `evidence/run-*/software-pilot-manifest.json` as software evidence; it must
  remain software-only and does not itself authorize upload or start.
- In Alloy, open each of the five untouched bundled fixtures from Library,
  verify the pinned A1 Mini / PLA Basic / 0.4 mm recipe and supports off, then
  slice and inspect the toolpath and preflight. Pair the actual printer, verify
  model N1, save its leaf-certificate pin, and use the two separate upload and
  start confirmations for each fixture. No arbitrary imported model, changed
  recipe, support setting, AMS route, or ordinary build may reach this pilot
  transport path.
- For every physical fixture retain the artifact hash, first-layer and
  completed-part photos, measurements where relevant, redacted printer
  telemetry, and each check result in `release/no-support-pla-pilot.json`.
  The in-app route and physical prints can be performed from the phone; ADB is
  optional for rerunning the software-only instrumentation export. Do not
  automatically retry a printer command. Stop and investigate any unexpected
  motion, temperature, routing, adhesion or printer state.

Review all physical evidence before running
`ci/validate_no_support_pla_pilot.py`; that validator is a release-evidence
review step and need not run on the phone. A passing narrow pilot is not
support parity, A1/P1S qualification, Bambu Handy validation, or production
release approval.
