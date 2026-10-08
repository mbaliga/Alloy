# Alloy on-device MVP verification

Use the exact build lane named in each section. The latest successful native
verification is run 37768795482 at app-source commit
`4c41928faa645af59185f97966e2a3c6cbc7f76e`. Its native build,
API-35 ARM-translation instrumentation, G3 software evidence export,
five-fixture software pilot, and isolated A1 Mini physical-pilot APK pair all
passed. The pilot package and test package are isolated with the `.pilot`
application ID. The artifact is available as
[`alloy-a1-mini-physical-pilot-ci`](https://github.com/mbaliga/Alloy/actions/runs/37768795482).
This is still software/QA evidence only: it does not prove a physical print,
Bambu Handy acceptance, or production signing. It also predates the latest
inventory and emulator-retry changes on the milestone branch. The latest
current-source ordinary Android run is
[37798978187](https://github.com/mbaliga/Alloy/actions/runs/37798978187)
at commit `2e90a483cef89c2d34dc91af44773b7bcf7fed2b`; its full acceptance
suite passed and its `alloy-v1-release-ci-apk` artifact is available. It is a
CI-debug-signed QA build, not a production release. The matching current-source
native run [37798978170](https://github.com/mbaliga/Alloy/actions/runs/37798978170)
is in progress; wait for it before using a current-source physical-pilot APK.
Neither the ordinary QA APK nor an older native pilot artifact proves physical
printing, Bambu Handy acceptance, or production signing.
Fresh G2 provenance remains **INSUFFICIENT / FAIL**. None of these builds
authorizes general direct printing or production release promotion.

## 1. Install the matching artifacts

**Pilot-install status:** native run
[37768795482](https://github.com/mbaliga/Alloy/actions/runs/37768795482)
succeeded at source commit
[`4c41928`](https://github.com/mbaliga/Alloy/commit/4c41928faa645af59185f97966e2a3c6cbc7f76e)
and published `alloy-a1-mini-physical-pilot-ci`; the workflow built and
verified the separate `.pilot` app/test package IDs. That artifact is usable
only for its bounded no-support PLA software/pilot scope, and it predates later
inventory changes. For the current phone-first build, wait for the current
source run [37798978170](https://github.com/mbaliga/Alloy/actions/runs/37798978170)
to pass and publish a replacement pilot artifact. Do not uninstall a working
Alloy installation to use an older QA build.

1. For the UI/software smoke pass and Handy receiver-resolution check, use the
   current-source `alloy-v1-release-ci-apk` from successful Android v1 run
   [37798978187](https://github.com/mbaliga/Alloy/actions/runs/37798978187)
   (source commit `2e90a483`). After extracting, install `app-release.apk` on
   an ARM64 Android phone; compute and record the APK's own SHA-256 separately.
   CI-debug signatures can differ between runs, so Android may refuse to update
   an existing Alloy install. Before uninstalling, export projects you need as
   `.alloy.zip` and back up other app data; uninstalling clears app-private
   data and saved printer credentials. Use a separate Android user/work profile
   or stop if you cannot back up safely.
   This QA build verifies the app workflow/Handy package-visibility wiring, not
   native slicing, printer transport, Handy's actual import acceptance, or release.
2. Successful [native run 37768795482](https://github.com/mbaliga/Alloy/actions/runs/37768795482)
   produced native QA, instrumentation and G3 evidence, plus five-fixture
   software-pilot evidence and a package-ID-verified isolated pilot APK pair.
   It is an older app revision; prefer the current source run 37798978170 when
   it succeeds and publishes its artifact. CI-debug signing remains
   non-production; no software artifact establishes successful physical
   printing or Handy import acceptance.
   Fresh G2 provenance remains **INSUFFICIENT / FAIL** (66/72 exact-history
   samples; 314/479 exact common files, 65.55%;
   [run 37741025557](https://github.com/mbaliga/Alloy/actions/runs/37741025557)).
   The separate Bambu Studio lineage diagnostic did not establish a baseline
   (101/445 historical best;
   [run 37741025606](https://github.com/mbaliga/Alloy/actions/runs/37741025606)).
   No physical printer route has been qualified.
3. The five-fixture software evidence is not physical qualification. Use the
   current-source pilot artifact from run 37798978170 for section 6 once that
   workflow passes. Keep all printer, firmware, transport and production-release
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
  `sticking`, `stringing`, `smoke`, and `tangle`.
- In **Fix a symptom**, confirm the symptom-first list contains at least 24
  cards and opens the hard-stop guidance before speculative remedies. Open
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
  A1 Mini not-recommended rows must be zero-stock, say “Track only” and
  “not recommended on A1 Mini,” and must not create default reorder alerts.
  Zero stock must not look like a detected loaded spool; the ledger is not a
  printer sensor or material-compatibility approval.

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
  [native run 37768795482](https://github.com/mbaliga/Alloy/actions/runs/37768795482)
  is an older successful baseline. For current phone-first verification, do
  not start section 6 until
  [native run 37798978170](https://github.com/mbaliga/Alloy/actions/runs/37798978170)
  succeeds, publishes `alloy-a1-mini-physical-pilot-ci`, and its app/test
  package-ID assertions pass. Then install only the current-source
  `app-release.apk`; its `.pilot` ID should coexist with regular Alloy. The matching
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
