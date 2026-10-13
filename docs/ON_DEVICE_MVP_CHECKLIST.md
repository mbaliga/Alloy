# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. A CI pass proves
only the software checks that ran; it does not establish slicer parity, physical
printer safety, Bambu Handy acceptance, or production signing.

**Current combined candidate (2026-10-13):** PR #12 head
[`63ece8dee14ee8f40b631d5a59afe6ab3c106323`](https://github.com/mbaliga/Alloy/commit/63ece8dee14ee8f40b631d5a59afe6ab3c106323),
stacked on the native instrumentation fix in PR #13. The app, test, and native
workflow source blobs in this merged tree were verified identical to the
previously tested combined candidate
[`f009282dd50a2485fab5e071abad406709314373`](https://github.com/mbaliga/Alloy/commit/f009282dd50a2485fab5e071abad406709314373).
That candidate's Android run [38365197015](https://github.com/mbaliga/Alloy/actions/runs/38365197015)
passed (194 tests, 0 failures; Android-lane skips are not native evidence);
native run [38365197013](https://github.com/mbaliga/Alloy/actions/runs/38365197013)
passed native build, API-35 ARM-translation instrumentation, G3 export and the
five-fixture software pilot. The source candidate's commit-scoped Android/native checks and the
official-source snapshot run are recorded in PR #12's Checks tab; consult that
live view for current status instead of relying on a status that can go stale
in this checklist. The source blobs match the tested baseline, but CI artifacts
do not qualify printing. Artifact IDs and digests are listed below.

The five-fixture manifest is explicitly software-only; physical and direct-send
qualification remain pending. The desktop Orca-to-Prusa strict semantic-parity
gate remains failed, and fresh G2 provenance remains FAIL / NO-GO. Do not
upload a print job or treat these artifacts as printer-ready evidence. Bambu
Handy acceptance and production signing are also unverified.

## 1. Install the matching artifacts

**Current software-test artifacts:** the combined-source Android run
[38365197015](https://github.com/mbaliga/Alloy/actions/runs/38365197015)
passed 194 tests with 0 failures; its release APK artifact is
`alloy-v1-release-ci-apk` (artifact `11797078339`, ZIP SHA-256
`f8cd3e896c934f8074a0c279701e45cce31dfda0f84b057eb6498273f1f78da0`).
The combined native run
[38365197013](https://github.com/mbaliga/Alloy/actions/runs/38365197013)
passed native build, API-35 ARM-translation instrumentation, G3 export, and
the software-only five-fixture pilot. It produced native release APK artifact
`11799896657` (ZIP SHA-256
`c650f697974fdea7b392be16a51231e5dfd05f61a6f0fa7aebd04043112548cb`),
instrumentation APK artifact `11799302723` (ZIP SHA-256
`79e5412e5869583963193c520348eff81a669e8eb2cb396e9cb2cd942337600f`),
instrumentation report artifact `11801485175` (SHA-256
`7f7c8961fa82fb64b65cf615a22b4f488c797ab5c153f269b8b8c42e511aee28`),
G3 evidence artifact `11800453610` (SHA-256
`452f5b0b3b210e375b6d44b46f33b01e341660bc60a7627d1db15d8aca4e1532`),
and software pilot artifact `11801016471` (SHA-256
`e9d7ca4eb76b12f3607da5ec8c58435c8279b518fda22869e1c137346150c956`).
These app/test/native source blobs match the current combined PR candidate,
but the fresh current-head runs above must finish before treating those checks
as current-head CI evidence. The APKs are CI-debug signed and do not qualify
printing or production release.

For ordinary app checks, install the release APK on an ARM64 Android phone.
For native-engine checks, use the matching native release and instrumentation
APK pair only after a successful, current-source native instrumentation run.
Record each APK's own SHA-256 separately after extraction. Do not uninstall a
working Alloy installation merely to replace it with a CI-debug-signed QA
build.

The five-fixture pilot is software evidence only. Its manifest records
`software_only=true`, `physical_qualification=PENDING`,
`direct_send_qualification=PENDING`, and `engine_verified=false`
for every fixture. Do not upload or start a physical job from Alloy yet.
Desktop slicer semantic parity and fresh G2 provenance also remain unresolved;
Bambu Handy acceptance, signed install/upgrade and production signing are
unverified.

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

