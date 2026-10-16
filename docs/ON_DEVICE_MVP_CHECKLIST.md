# Alloy on-device MVP verification

This is a verification protocol, not a print-readiness claim. A CI pass proves
only the software checks that ran; it does not establish slicer parity, physical
printer safety, Bambu Handy acceptance, or production signing.

**Verification snapshot (2026-10-16):** PR #12 application/CI source tested
is [`4dc532d6ad71ec7ab6f5a524bf68bee647290947`](https://github.com/mbaliga/Alloy/commit/4dc532d6ad71ec7ab6f5a524bf68bee647290947).
Later checklist-only commits do not change that tested source. Parity tooling
([38802544918](https://github.com/mbaliga/Alloy/actions/runs/38802544918)),
three-printer static profile pre-gate
([38802545030](https://github.com/mbaliga/Alloy/actions/runs/38802545030)),
desktop A1 Mini reference slicing
([38802544908](https://github.com/mbaliga/Alloy/actions/runs/38802544908)),
official Orca source snapshot
([38802544906](https://github.com/mbaliga/Alloy/actions/runs/38802544906)),
and Android v1 build/tests
([38802544927](https://github.com/mbaliga/Alloy/actions/runs/38802544927))
passed. The Android run produced CI QA APK ZIP artifact
[`12006353162`](https://github.com/mbaliga/Alloy/actions/runs/38802544927/artifacts/12006353162)
(ZIP SHA-256 `1f469a0170ea2522ce7d11761d066583445f7921ed17b63f6374b46fe7b24ee3`;
extracted APK SHA-256 `8394676538274415e94266f99d8bf4a7d7fe55bd26ed2ce1195e66f8af519759`).

The native PR run
([38802544902](https://github.com/mbaliga/Alloy/actions/runs/38802544902))
successfully built the native engine, ran Android instrumentation and exported
G3 evidence, but **failed the desktop-parity comparison**. Its report artifact
is [`12010150964`](https://github.com/mbaliga/Alloy/actions/runs/38802544902/artifacts/12010150964).
At the required 2% print-time / filament tolerance, all four comparison
fixtures fail print-time parity: cube 14.08%, supported overhang 18.02%,
thin-wall frame 12.48%, travel obstacle 9.14%. The supported-overhang fixture
also misses filament length by 8.90%, mass by 8.83%, and positive extrusion by
6.04% (5% allowance). **Do not send or print these generated jobs.**

A separate push-triggered software-pilot build
([38802536896](https://github.com/mbaliga/Alloy/actions/runs/38802536896))
produced the native QA APK pair and five software-preflighted sample packages
(artifact [`12010340940`](https://github.com/mbaliga/Alloy/actions/runs/38802536896/artifacts/12010340940)).
Its manifest explicitly marks `software_only: true`,
`engine_verified: false`, and physical/direct-send qualification as pending.
This artifact is suitable only for inspecting the app and package files; do not
upload the generated jobs to Bambu Handy, send them to the printer, or print.
Keep the debug-signed build separate from any working production installation.

The current follow-up candidate [`f62e884f004457aaa2663fe8eeeaf872363790e9`](https://github.com/mbaliga/Alloy/commit/f62e884f004457aaa2663fe8eeeaf872363790e9)
closes both direct Bambu Handy and generic Android share routes for
unqualified print packages, then re-runs physical-print package validation
immediately before creating an external share URI. Its Android/native CI runs
are pending. Results above describe verified behavior at source `4dc532d`, not
this new change.

The completed desktop reference is not native slicer parity. The completed
static profile audit is not runtime profile parity. None of these runs proves
physical printing, Bambu Handy acceptance, direct-send qualification, or
production signing.

## 1. Install the matching artifacts

If you choose to inspect the Android build, download the QA APK from the exact
run above. Before installing, verify its artifact identity and retain both
the archive digest and extracted APK digest. Install only as a software QA
build; these CI-signed artifacts do not qualify a print or replace a
production installation. Do not install the software-pilot packages on the
printer or import them into Bambu Handy while the native parity gate fails.

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

