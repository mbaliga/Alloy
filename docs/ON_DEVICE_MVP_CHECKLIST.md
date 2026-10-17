# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. A CI pass proves
only the software checks that ran; it does not establish slicer parity, physical
printer safety, Bambu Handy acceptance, or production signing.

**Verified software checkpoint (2026-10-17):** application source
[`05981d75e70026cdb011f5d6ea9cf8a33609db52`](https://github.com/mbaliga/Alloy/commit/05981d75e70026cdb011f5d6ea9cf8a33609db52).
Documentation-only commits after this checkpoint do not change its APKs.

- [Android acceptance 38979999571](https://github.com/mbaliga/Alloy/actions/runs/38979999571)
  passed: 210 tests reported, zero failures, 11 skipped (199 executed successfully).
- [Native run 38979999591](https://github.com/mbaliga/Alloy/actions/runs/38979999591)
  compiled the ARM64 engine and passed instrumentation: 199 tests reported,
  zero failures/errors, two skipped (197 executed successfully). G3 evidence
  export succeeded, but the subsequent strict desktop comparison failed.
- Parity tooling, the official Orca snapshot, three-printer profile pre-gate,
  and desktop A1 Mini reference slices all passed at this source.
- Ordinary and organic support recipes now retain their distinct settings
  across style switches; three new Android regression tests cover this.
  Android acceptance reports are retained even on failed runs.

The [exact-source G3 report](https://github.com/mbaliga/Alloy/actions/runs/38979999591/artifacts/12082788519)
still fails the unchanged 2% tolerance:

| Fixture | Print-time difference | Other failed checks |
| --- | ---: | --- |
| Cube | 14.08% | None |
| Supported overhang | 17.63% | Filament length 2.31%; mass 2.12% |
| Thin-wall frame | 12.48% | None |
| Travel obstacle | 9.14% | None |

For the supported overhang, the deposited support path now differs by only
0.18% and positive support extrusion by 0.07%. These aggregate diagnostics
are encouraging but do not prove spatial equivalence or physical print quality.
All 62 checked profile fields match for each fixture. Remaining timing work
must account for different machine templates, including desktop per-layer
timelapse/inspection motions, not just extrusion geometry. See
[Bambu template policy](BAMBU_A1_MINI_TEMPLATE_POLICY.md).

**Do not send or print unqualified generated jobs.** Direct-send and
external-share gates remain fail-closed. The physical-pilot stages were
skipped after G3 failed. A1/P1S runtime qualification, real-printer testing,
Bambu Handy acceptance, and production signing remain unverified.

## 1. Install the matching artifacts

For phone-only QA, use artifacts from the source checkpoint above:

- [Native review APK](https://github.com/mbaliga/Alloy/actions/runs/38979999591/artifacts/12082363518):
  native slicing/inspection, still unqualified for printing.
  Archive SHA-256: `ec27dd7eacf9af5fe080c8f4d61d7c9fb82210699be5ef19be8f0bf5d8b635d9`.
- [Ordinary QA APK](https://github.com/mbaliga/Alloy/actions/runs/38979999571/artifacts/12081690906):
  UI/import/pipeline checks; this is not the native slicing build.
  Archive SHA-256: `fadb7f08366d387fe3bc11d9065f37e0e93d1fb548254e2ea08a488c15cd1c49`.

Before installing, verify the artifact identity and archive digest, and retain
the extracted APK digest. These are CI-debug-signed software QA artifacts,
not production releases. The native APK artifact expires October 24, 2026;
if unavailable, build the exact source rather than substituting an unrelated
APK. Do not send generated packages to the printer or Bambu Handy while
qualification is incomplete.

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

