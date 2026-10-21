# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. A CI pass proves
only the software checks that ran; it does not establish slicer parity, physical
printer safety, Bambu Handy acceptance, or production signing.

**Latest installable QA package:** app-source checkpoint
`411bc3499d4ef864347e68e6b5f0df14a9544cc9`. Android acceptance rerun
[39578080505, attempt 2](https://github.com/mbaliga/Alloy/actions/runs/39578080505)
passed 210 tests with 0 failures and 15 native-dependent skips. The CI-signed
QA APK is artifact [12339326912](https://github.com/mbaliga/Alloy/actions/runs/39578080505/artifacts/12339326912);
archive SHA-256: `06759434390a26f71782aa0012558769b9a9cf0b65f1323c89bce33c3cb05717`.
This is for software QA only, not a production release. Current PR head
`df9a1b5` changes only the GLES test's Activity-launch synchronization; app
runtime is unchanged from 411. Its workflows are running separately.

The exact-source native workflow
[39578080512](https://github.com/mbaliga/Alloy/actions/runs/39578080512)
has built Alloy and is running API-35 native instrumentation. G3 parity and the
isolated physical-pilot stages have not run on this checkpoint yet. The last
completed exact-source G3 comparison failed the unchanged 2% gate; see
[`GATE_STATUS.md`](GATE_STATUS.md). Do not infer native print qualification
from Android acceptance.

**Native-engine QA package for import/slice diagnostics:** artifact
[12332570410](https://github.com/mbaliga/Alloy/actions/runs/39562063843/artifacts/12332570410),
archive SHA-256
`d61dcf7e973099a142fa4299cb712719872cb42bfe4b73ed45c51ef27ead70a2`;
extracted `app-release.apk` SHA-256:
`d6c20906198e7ca8764646ef32d046376103d5c1d614bd0b8e20687a69373362`
(about 144 MiB). Use only for real-model import, plate-planning, local-slicing
and layer-review diagnostics. Native instrumentation passed, but strict G3
failed; do not print, share, or send its generated job to Bambu Handy.

**Latest published implementation checkpoint:** [`3e2947d`](https://github.com/mbaliga/Alloy/commit/3e2947d1cc29e3f72ae1866565cb1b114ed34b30)
includes source-pinned A1 PETG Basic / TPU 95A and P1S PETG HF / TPU 95A HF
recipes, the three-printer recipe picker, and grade-specific TPU drying
guidance. Local parity (191 tests) and profile, learning, and Android wiring
validators pass. This, Android acceptance, and native instrumentation are
software evidence only; strict G3, G2 provenance, physical printer acceptance,
Bambu Handy acceptance, production signing, and release approval remain open.

**Do not send or print unqualified generated jobs.** Direct-send and
external-share gates remain fail-closed. A1 Mini physical acceptance, A1/P1S
runtime qualification, real-printer testing, Bambu Handy acceptance, and
production signing remain unverified.

## 1. Install the matching artifacts

For phone-only software QA, use the CI-signed ordinary APK from the
app-source-equivalent checkpoint above:

- [CI-signed Android QA APK, artifact 12339326912](https://github.com/mbaliga/Alloy/actions/runs/39578080505/artifacts/12339326912).
  Verify the downloaded archive SHA-256 is
  `06759434390a26f71782aa0012558769b9a9cf0b65f1323c89bce33c3cb05717`
  before extraction. Then record the extracted `app-release.apk` SHA-256 on
  your device. The retained JUnit report records 210 passed, 0 failed and 15
  native-dependent skipped tests. This is the ordinary software-acceptance
  build, not a production release.
- For real-model local slicing, use the native-engine QA APK from artifact
  [12332570410](https://github.com/mbaliga/Alloy/actions/runs/39562063843/artifacts/12332570410).
  Extracted `app-release.apk` SHA-256:
  `d6c20906198e7ca8764646ef32d046376103d5c1d614bd0b8e20687a69373362`.
  This approximately 144 MiB CI build passed API-35 native instrumentation,
  but its strict G3 comparison failed. It is for import, arrangement, slicing
  and layer-review diagnostics only; never dispatch its G-code.

Before installing, verify the artifact identity and archive digest, and retain
the extracted APK digest. This software QA artifact is not a production
release and does not include a qualified native-slicing path. Native CI APKs
are available for engineering diagnostics, but the dedicated physical-pilot
build was skipped after G3 failed. Do not send generated packages to the
printer or Bambu Handy while qualification is incomplete.

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
  feed only and must not imply AMS compatibility. For P1S TPU 95A HF, confirm
  the picker guidance says to dry before use (Bambu's 70 °C / 8 hour setting);
  do not show that HF-specific advice for A1's standard TPU 95A profile. All
  four profiles remain planning-only; review exact material, route, physical
  spool fit, and printer readiness independently.
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
- Confirm prime/purge/cleaning explicitly says it is **not included in the
  estimate**; never present Alloy's current total as complete printer-side
  consumption. Live printer temperature/idle state must remain unavailable
  unless supplied by a qualified printer connection.
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

