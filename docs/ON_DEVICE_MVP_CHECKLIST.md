# Alloy on-device MVP verification

This checklist is a verification protocol, not a claim that the app is ready to print. Use the exact source-matched artifacts below and record device results. A CI pass does not establish real-printer safety, Bambu Handy acceptance, or production signing.

**Current source snapshot (2026-10-13):** PR #13 head [ae48c42c313246815e2236f133f0bf96fb51e811](https://github.com/mbaliga/Alloy/commit/ae48c42c313246815e2236f133f0bf96fb51e811). The ordinary Android workflow passed in run [38355476587](https://github.com/mbaliga/Alloy/actions/runs/38355476587) and published alloy-v1-release-ci-apk (artifact ID 11793130471, archive SHA-256 565c24c11d8aba36b0df31e68d82687f94306ff96804ce122cd2364f9e798432). It verifies the ordinary Android app only; it is not native slicing or send evidence.

The corrected optional-native run [38355476630](https://github.com/mbaliga/Alloy/actions/runs/38355476630) is still active at the native-engine build step. It has not yet published native APKs, completed instrumentation, exported native G3 evidence, or run the five-fixture software pilot. Do not use a historical native APK as a substitute for that exact-source evidence.

The latest desktop Orca-to-Prusa comparison, run [38359783217](https://github.com/mbaliga/Alloy/actions/runs/38359783217), passed both slicer runtime jobs but failed semantic parity's strict 2% gate: time deltas are +6.31%, +6.35%, and +28.36%; summary filament deltas are +13.21%, +28.57%, and +22.42% for cube, supported overhang, and thin-wall. Parsed positive-E lengths are diagnostic only in the current comparator; their +4.48%, +11.67%, and +4.05% deltas flag additional toolpath-extrusion differences but are not part of the strict parity verdict. The translation is not parity-qualified or printer-ready.

**Material-coverage integration note:** A1 Mini catalog expansion and sourced AMS Lite exception handling are tracked separately in [PR #12](https://github.com/mbaliga/Alloy/pull/12), branch `codex/a1mini-filament-route-matrix`. That change is not in the source snapshot for the APK above. Until the catalog work is integrated into the tested app branch, do not treat the APK's initial 14 material classes as complete coverage of Bambu's A1 Mini material/spool routes. Re-verify the integrated catalog and all route screens on a new source-matched build.

## 1. Install the matching artifact for ordinary app checks

1. Download alloy-v1-release-ci-apk from current ordinary run [38355476587](https://github.com/mbaliga/Alloy/actions/runs/38355476587). Check the artifact ID and archive digest above, extract app-release.apk, and record the APK's own SHA-256 and Android version/build number.
2. This APK is CI-debug-signed and excludes the optional native engine. Use it only for UI, onboarding, model intake, planning, learning, and conservative fallback checks. Do not infer native slicing, printer-ready output, or send capability from installing or launching it.
3. Avoid uninstalling a working Alloy install to make room. A CI signature may not update an installed production-signed package; use a separate Android user/profile or spare device unless signature compatibility is confirmed.
4. Wait for run 38355476630 to finish before using any native artifact. Even if it publishes APKs, native use requires instrumentation, G3 export and the software pilot to pass for the same commit. Those are software gates only; the separate on-device route and human-reviewed physical evidence are still required.
5. Fresh G2 provenance remains FAIL / NO-GO; Path B desktop profile translation remains not parity-qualified. Direct send, Bambu Handy round-trip and production release remain unverified.

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
