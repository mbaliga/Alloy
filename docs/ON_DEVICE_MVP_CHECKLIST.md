# Alloy on-device MVP verification

Use the exact build lane named in each section. Historical native baseline
[`37883851621`](https://github.com/mbaliga/Alloy/actions/runs/37883851621) at
`64fc3503` passed its ARM64 source build, API-35 emulator instrumentation, G3
evidence export and isolated five-fixture software pilot. Those artifacts are
historical and do not represent the current source snapshot or prove a
physical print, Bambu Handy acceptance or production signing. Fresh G2
provenance remains **FAIL / NO-GO** and G3 semantic parity remains open.

**Latest completed ordinary Android gate (2026-10-13):** Android v1 run
[`38319130553`](https://github.com/mbaliga/Alloy/actions/runs/38319130553) on
commit `a2fdb5f7d2a5817fc57e305431ea77a318e710ca` passed the acceptance suite:
190 tests, 1 skipped, 0 failed. It published `alloy-v1-release-ci-apk`
(artifact ID `11778127759`, ZIP SHA-256
`ff278c4be5abd7c84a5aa55223addd4176ef3dfd10355694065c26cc956826d2`). This
ordinary APK includes the JPEG learning-artwork support and updated A1 Mini
PVA/AMS Lite route guidance, but does not contain the optional native engine
and is not slicing or printer-send evidence.

Native run
[`38295517040`](https://github.com/mbaliga/Alloy/actions/runs/38295517040)
built and published both native-enabled APKs, then failed during UTP install
commit with `ShellCommandUnresponsiveException`; the instrumentation report
shows 0 tests ran. Follow-up run
[`38305679147`](https://github.com/mbaliga/Alloy/actions/runs/38305679147)
reached instrumentation and failed 2 of 162 tests: ARM-translated
`System.loadLibrary` exceeded a 30-second test timeout, and the typed-recipe
test produced an empty failure trace. Commit
[`6e01a9b`](https://github.com/mbaliga/Alloy/commit/6e01a9b83fc93ce8a9b3bbafec51efb279a37ae3)
adds captured logcat/test-result diagnostics, and
[`50520eb`](https://github.com/mbaliga/Alloy/commit/50520eb52b67cc54bb0116502b28ca18cf15b5d9)
extends only that native initialization test’s timeout to 180 seconds. Native
run `38315395721` is currently building the optional engine; follow-up
`38316210206` is pending. Neither has yet proven native runtime or slicing.
No general print or release claim is promoted.

Corrected native run
[`38138260029`](https://github.com/mbaliga/Alloy/actions/runs/38138260029),
at `4ebe473`, completed dependency bootstrap and compiled the official Orca
archive, but failed final linking with unresolved upstream Qhull and
Boost.Locale/ICU symbols. Its diagnostic artifact is ID `11705105774`, SHA-256
`c1266e12ed3848ec9087129fe59acd576d1a77c43af1da0fc7c1dc6b1a79483a`. The current branch contains the Qhull, Boost.Locale/ICU and libiconv
link repairs. Native run
[`38155370545`](https://github.com/mbaliga/Alloy/actions/runs/38155370545)
successfully built both native-enabled APKs, then failed before instrumentation
because the API-35 emulator timed out during boot. This confirms native build
and packaging, but not JNI runtime loading or slicer behavior. The follow-up
allows 1800 seconds for emulator boot and uploads the APK pair before starting
the emulator. Install only the exact native CI APK artifact from a successful
matching run, and do not treat an APK build as slicing, G3, or printer-send
evidence.
The preceding attempt failed on the unavailable CGAL `mesh_segmentation.h`;
the corrected probe uses the byte-checked MCUT compatibility implementation.
Never use historical native artifacts as current evidence. No software
artifact authorizes general direct printing or production-release promotion.

## 1. Install the matching artifacts

**Pilot-install status:** the last successful software-only pilot baseline
[37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
succeeded and published `alloy-a1-mini-physical-pilot-ci`. The workflow built
and verified the separate `.pilot` app/test package IDs and passed the bounded
five-fixture software-only package checks. Use only for the scope below; it is
not physical qualification. Do not uninstall a working Alloy installation
merely to replace it with a CI-debug-signed QA build.

1. For ordinary app checks, download `alloy-v1-release-ci-apk` from run
   [38319130553](https://github.com/mbaliga/Alloy/actions/runs/38319130553)
   (source commit `a2fdb5f7d2a5817fc57e305431ea77a318e710ca`; artifact ID
   `11778127759`, ZIP SHA-256
   `ff278c4be5abd7c84a5aa55223addd4176ef3dfd10355694065c26cc956826d2`),
   extract `app-release.apk`, install it on an ARM64 Android phone and record
   the APK's own SHA-256 separately. This APK omits the optional native engine;
   it verifies the ordinary app and conservative fallback behavior only.
2. Historical native run
   [37883851621](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
   produced native QA, instrumentation and G3 evidence, plus five-fixture
   software-pilot evidence and a package-ID-verified isolated pilot APK pair,
   but it is not the current source. At this checklist revision, native run
   `38315395721` built its optional-native APK pair and is running API-35
   ARM-translation instrumentation. Release APK artifact `11778506239`
   (ZIP SHA-256 `18816864c35c702de6a190c1a2fd57f4fb3e7f3aa8fe509bbd4277b16b9c9161`)
   and instrumentation APK artifact `11778541204` (ZIP SHA-256
   `000575528ecf9ba015c10aa124fa725ba259d815b3f37dbc3fffaf01cedd76c2`) are
   compile/package evidence only—do not use them as native-runtime evidence.
   Follow-up `38316210206` with the 180-second native-load test timeout is
   pending. Do not use a native artifact for slicing checks until a matching
   current-source run completes instrumentation, G3 evidence export and
   software pilot successfully. CI-debug signing remains non-production; no
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
