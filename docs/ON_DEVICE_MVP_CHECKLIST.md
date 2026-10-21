# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. A CI pass proves
only the software checks that ran; it does not establish slicer parity, physical
printer safety, Bambu Handy acceptance, or production signing.

**Latest installable PR software-QA checkpoint:** source [`d89f9737b0f86b9b1900aff6b754de35d5e06803`](https://github.com/mbaliga/Alloy/commit/d89f9737b0f86b9b1900aff6b754de35d5e06803). The ordinary Android acceptance lane [39542482024](https://github.com/mbaliga/Alloy/actions/runs/39542482024) built the APK and completed emulator acceptance with 0 failures and 15 native-dependent skips.

**Latest local implementation checkpoint:** `ae00dab1` adds source-pinned A1 PETG Basic / TPU 95A and P1S PETG HF / TPU 95A HF recipes and exposes them in the per-printer picker. Each of the four profiles verifies 26 typed fields, 131 native projections and 11 provenance blobs against Bambu Studio revision `926a7192574bcb9b3a732e1ec59a46d79cb45466`. The local parity suite (191 tests) and profile/Android/release wiring validators pass, but this checkpoint is not on the GitHub PR branch and has no Android APK or instrumentation result yet; do not treat these recipes as device-tested.

- Ordinary software-QA artifact: [12320399519](https://github.com/mbaliga/Alloy/actions/runs/39542482024/artifacts/12320399519), archive SHA-256 `e4fc88ad46269eb3175eafb5a65209f84a5394eebb1ab8f659ceaea38da20498`.
- This APK predates the local A1 Mini PVA routing correction in commits `8be99b3a`, `aaab46c6`, and `62ca4642`; do not use it to verify that correction. It is a CI-signed software-QA artifact, not a production release.
- The exact-head native workflow [39542482010](https://github.com/mbaliga/Alloy/actions/runs/39542482010) is still building pinned native dependencies. Native engine tests, strict G3 parity, and physical-pilot stages have not run on this head. The last completed strict G3 result remains **FAIL / NO-GO** at the unchanged 2% tolerance; see [`GATE_STATUS.md`](GATE_STATUS.md) for measured deltas and limits. Do not use either QA APK to print.

**Do not send or print unqualified generated jobs.** Direct-send and
external-share gates remain fail-closed. The prior native checkpoint's
physical-pilot stages were skipped after G3 failed; this exact head has not
reached G3 or the pilot stages yet. A1 Mini physical acceptance, A1/P1S runtime
qualification, real-printer testing, Bambu Handy acceptance, and production
signing remain unverified.

## 1. Install the matching artifacts

For phone-only software QA, use the CI-signed ordinary APK from the exact
source checkpoint above:

- [CI-signed Android QA APK, artifact 12320399519](https://github.com/mbaliga/Alloy/actions/runs/39542482024/artifacts/12320399519).
  Archive SHA-256: `e4fc88ad46269eb3175eafb5a65209f84a5394eebb1ab8f659ceaea38da20498`.
  This is the ordinary software-acceptance build, not a production release.

Before installing, verify the artifact identity and archive digest, and retain
the extracted APK digest. This CI-signed software QA artifact is not a
production release and predates the local PVA correction. No exact-head native
APK pair has been produced yet. Do not send generated packages to the printer
or Bambu Handy while qualification is incomplete.

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
- Open the first-layer gaps, bunched-material, and warping guides. Confirm
  each shared four-panel illustration names the relevant quadrant and says it
  is a visual example, not a remote diagnosis.
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
- With the A1 Mini active, open the material/feed/spool review and choose each
  bundled recipe: PLA Basic, PETG Basic, PETG HF, PETG Translucent, TPU 95A HF,
  and PVA.
  Verify each selection updates the active recipe, clears the existing slice,
  and survives closing/reopening Alloy. Every profile must remain marked
  review-required and direct send must remain unavailable. Also select the
  specific Bambu TPU 95A HF recipe and confirm its feed guidance is
  external/direct only, not AMS Lite, and says to dry before use. Bambu's
  current guidance specifies 70 °C for 8 hours for this HF grade. Confirm PVA
  distinguishes dry PVA
  (listed as AMS Lite compatible) from wet/absorbent PVA (not for AMS Lite);
  PLA/PETG labels must still require checking physical AMS Lite spool fit.
  Do not treat the TPU recipe as generic; other grades need their own reviewed
  profile. Treat material compatibility and feeder route as separate questions
  from recipe availability.
- With the A1 active, choose A1 PETG Basic and TPU 95A; with the P1S active,
  choose P1S PETG HF and TPU 95A HF. Confirm the displayed printer/material
  combination is correct, each choice persists across app restart, and an
  existing slice is cleared. The TPU profiles must be labelled external/direct
  feed only and must not imply AMS compatibility. All four profiles remain
  planning-only; review exact material, route, physical spool fit, and printer
  readiness independently.
- Open the capability view. Confirm A1 mini distinguishes direct/external
  feed, AMS lite, and unsupported regular AMS; it must show 1.75 mm guidance,
  AMS lite spool dimensions, and the TPU/PVA exclusions.
- Confirm the A1 capability list shows PLA, PETG, TPU and PVA individually as
  ideal, and ABS, ASA, PC, PA, PET and CF/GF-filled classes individually as
  not recommended. Its AMS Lite route must remain separate from printer
  suitability.
- Confirm P1S lists PLA, PETG, TPU, PVA, PET, ABS and ASA individually as
  ideal; PA and PC as capable; and CF/GF-filled polymer as not recommended
  for the supplied stainless nozzle. Its external feed and AMS routes must
  remain separate, and AMS Lite must not appear as a P1S route.
- Open Inventory. Verify ledger rows cover all 24 A1 Mini catalog material
  classes: PLA, PETG, TPU, PVA, Support for PLA, Support for PETG, ABS, ASA,
  PC, PA/Nylon, PET, Bambu PLA-CF, PETG-CF, other CF/GF-filled polymers,
  BVOH, PP, POM, HIPS, Bambu PAHT-CF, PLA Glow, PET-CF, PA6-CF, TPU 95A and
  TPE. Every row marked not recommended for A1 Mini must say **Track only**,
  and unrated rows must remain explicitly review-required; none may create
  default reorder alerts. Every unmeasured built-in quantity should read
  **Not recorded**, not zero, stocked, or loaded;
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
  filament in mm and an *approximate* g value, time if reported (clearly
  labelled **approximate engine time, unverified** until desktop and printer
  parity pass), current
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
- While the engine/profile/support gates are unverified, tap both **Share
  package** and **Try Bambu Handy**. Each must block external sharing with a
  clear reason; neither may launch Android's chooser or Handy. **Export package**
  may still save a review copy, visibly marked unverified. Do not open that
  review copy in Handy or print it.
- Only after the package, engine, selected printer profile and any requested
  support engine are qualified should either share action become available.
  Then verify Android resolves and launches Handy (or falls back to a
  cancellable chooser if Handy is unavailable), and inspect Handy's own import
  result. A successful launch is not proof of acceptance.
- Return to Alloy and open **Print readiness**. In the ordinary build, confirm
  direct-print controls remain blocked until every native/profile/support/
  artifact/pairing/certificate and recovery gate is actually satisfied.

Expected: an inspectable package can be exported, but unqualified jobs cannot
be externally handed off through Alloy. No physical dispatch or Bambu Handy
compatibility claim is made until evidence passes.

## 6. Narrow A1 Mini physical pilot — currently blocked

Do not upload or start a physical job from Alloy yet. The current CI pilot
artifact is software evidence only and leaves `physical_qualification=PENDING`
and `direct_send_qualification=PENDING`; desktop slicer semantic parity and
fresh G2 provenance also remain unresolved.

When those gates are independently cleared and a reviewed, source-matched
physical pilot is authorized, use the exact scope and evidence protocol in
[`NO_SUPPORT_PLA_PILOT.md`](NO_SUPPORT_PLA_PILOT.md):

- Scope is limited to A1 Mini model N1, 0.4 mm nozzle, PLA Basic, textured PEI,
  one colour, external/direct feed, no AMS and no supports.
- Use only a newly reviewed source-matched pilot APK pair whose app/test
  package IDs are verified to coexist with the normal Alloy installation.
  Keep the run's software manifest; it is not physical evidence and does not
  authorize upload or start by itself.
- Before any print, verify the profile, recipe, first layer, clear motion path,
  temperature and printer state. Stop if anything differs or if Alloy cannot
  show the required confirmations. Never auto-retry a printer command.
- For every authorized fixture retain the package hash, first-layer and
  completed-part photos, measurements where relevant, redacted printer
  telemetry and each check result in
  `release/no-support-pla-pilot.json`. Review all evidence before running
  `ci/validate_no_support_pla_pilot.py`.

A passing narrow pilot would not establish support parity, A1/P1S
qualification, Bambu Handy acceptance or production release approval.

