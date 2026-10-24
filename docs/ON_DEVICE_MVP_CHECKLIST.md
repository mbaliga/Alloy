# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. Passing software
checks does not establish slicer parity, physical printer safety, Bambu Handy
acceptance, or production signing.

**Latest installable Android software-QA package:** exact PR head
[`4e6cc47`](https://github.com/mbaliga/Alloy/commit/4e6cc473fd924afe7e0db379ab99f4f7fc785a3f).
Android acceptance [run 40052398037](https://github.com/mbaliga/Alloy/actions/runs/40052398037)
passed all 222 connected instrumentation tests. Download the CI-signed QA
APK archive [artifact 12544183939](https://github.com/mbaliga/Alloy/actions/runs/40052398037/artifacts/12544183939).
Its archive SHA-256 is
`36dd5fa26a86bd0d1598cfe520fa71a1307d0858e2084595909453e6c1c6cbef`; the
contained `app-release.apk` SHA-256 is
`3a68ac8804f22b4988b074567b4f348b48940c3b4f16e33cc1bcaf6d18dbe192`.
The exact-head acceptance diagnostics are in [artifact
12544955647](https://github.com/mbaliga/Alloy/actions/runs/40052398037/artifacts/12544955647).
This is a software-QA build, not a production release.

On the earlier source baseline `a32eb30`, the optional native engine built and
API-35 ARM-translated instrumentation passed in [run
39962333540](https://github.com/mbaliga/Alloy/actions/runs/39962333540).
However, its strict G3 comparison **failed** at the unchanged threshold; report
artifact [12510976889](https://github.com/mbaliga/Alloy/actions/runs/39962333540/artifacts/12510976889)
and raw native evidence [12511266224](https://github.com/mbaliga/Alloy/actions/runs/39962333540/artifacts/12511266224).
The physical-pilot APK and five-fixture pilot were skipped. This historical
result is not G3 evidence for `4e6cc47`; its exact-head native run remains
pending below. Do not print, directly send, or hand off a native-generated job
to Bambu Handy. A green Android/native build does not qualify the slicer.

**Current candidate (2026-10-24):** app source `4e6cc47`
([PR #12](https://github.com/mbaliga/Alloy/pull/12)) passed Android acceptance
in [run 40052398037](https://github.com/mbaliga/Alloy/actions/runs/40052398037),
including all 222 connected instrumentation tests; its APK is the software-QA
artifact linked above. Native validation is still running in
[run 40052397964](https://github.com/mbaliga/Alloy/actions/runs/40052397964),
currently building native dependencies. No matching G3 comparison or pilot
artifact has been published, so this Android result does not change the
NO-GO for print/share. A doc-only update to this checklist does not change
that app source.

**Do not send or print unqualified generated jobs.** Direct-send, external-share,
physical-printer, and production-release gates remain fail-closed. A1 Mini
physical acceptance, A1/P1S runtime qualification, real-printer testing,
Bambu Handy acceptance, production signing, and release approval remain
unverified.

## 1. Install the matching artifact

The latest completed Android software-QA build is for `4e6cc47`. Download
[artifact 12544183939](https://github.com/mbaliga/Alloy/actions/runs/40052398037/artifacts/12544183939)
from [run 40052398037](https://github.com/mbaliga/Alloy/actions/runs/40052398037).
Verify the archive SHA-256 and contained APK SHA-256 listed above before
extraction/install. Install `app-release.apk` for software checks only; record
the APK SHA-256 and the phone/Android version with results. It is exact-head
Android QA for `4e6cc47`, but is not a print-qualified or production release.

For a future candidate, wait until its Android acceptance run completes
successfully and confirm the artifact belongs to that run/head before
installing it. Neither the ordinary CI-debug APK nor a restricted native pilot
APK is a production release. Passing Android acceptance does not authorize
printing; direct print and Bambu Handy handoff remain blocked unless their
separate exact-scope gates pass.

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
  When supports are on, the total may include support material. If exported
  G-code has feature-type markers, the separate support amount must be derived
  only from support-tagged positive-E moves and labelled approximate; if those
  markers are absent, show that support is not reported separately rather than
  inventing a value.
- Confirm startup accounting reports only pre-layer positive-E measured in the
  exported G-code, when a layer marker makes that boundary observable. It must
  explicitly exclude printer-firmware flushing and cleaning outside the file;
  never present it as complete printer-side waste or add a fixed assumed
  purge amount. If the G-code has no layer marker, startup priming, purge and
  cleaning must remain explicitly unmodeled. Live printer temperature/idle
  state must remain unavailable unless supplied by a qualified printer
  connection.
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

