# Alloy on-device MVP verification

Use this checklist with the native-enabled CI-debug build. The ordinary build
verifies the MVP but keeps physical send blocked. The separately compiled
`alloyPhysicalPilot` build can collect a narrow A1 Mini upload/start record
only for its five bundled, unchanged no-support PLA fixtures. Neither build
authorizes a Bambu Handy handoff claim or production release promotion.

## 1. Install the matching artifacts

1. On an ARM64 Android phone with USB debugging enabled, install the matching
   release APK and instrumentation APK.
2. Confirm a cold launch succeeds without asking for notification, LAN, or
   pairing permission.
3. Capture the APK SHA-256 values, Android version, phone model, and Alloy
   build/version. Do not add printer access codes, serial numbers, LAN hosts,
   or certificate fingerprints to a public record.

Expected: the first-run, five-step orientation route is skippable and has a
safe path to Library. No screen says the printer is qualified for direct send.

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
- Open Inventory. Verify separate zero-stock rows exist for PLA Basic, PETG,
  TPU, PVA, Support PLA and Support PETG. Zero stock must not look like a
  detected loaded spool.

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
  available and retain its SHA-256. Use generic Android Share only as a
  handoff attempt: Alloy must not call the recipient Bambu-ready or confirm
  Bambu Handy accepted it.
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
- Run the opt-in on-device `NoSupportPlaPilotTest` evidence export exactly as
  documented in [`NO_SUPPORT_PLA_PILOT.md`](NO_SUPPORT_PLA_PILOT.md). Retain
  the five staged packages and `software-pilot-manifest.json`; the manifest is
  deliberately marked software-only and does not authorize an upload or start.
- Build and install the isolated `alloyPhysicalPilot` APK as documented in
  [`NO_SUPPORT_PLA_PILOT.md`](NO_SUPPORT_PLA_PILOT.md). Confirm it labels the
  route **PILOT BUILD**. Only then may an untouched bundled fixture proceed to
  the two explicit upload/start confirmations after N1 discovery and leaf-pin
  pairing; every other source or recipe must remain blocked.
- For each fixture retain first-layer and completed-part photos, dimensional
  measurement where relevant, redacted printer telemetry, the artifact hash,
  and the result of each check in `release/no-support-pla-pilot.json`.
- If motion, temperature, material routing, or printer state is unexpected,
  stop. Do not retry commands automatically; mark the fixture failed and
  investigate.

Run `ci/validate_no_support_pla_pilot.py` only after a human has reviewed all
evidence. A passing pilot is narrow evidence, not support parity, A1/P1S
qualification, Bambu Handy validation, or production release approval.
