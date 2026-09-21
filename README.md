# Alloy

Alloy is a phone-first Android 3D-print preparation tool, initially targeting the Bambu Lab A1 Mini. The v1 app follows the compact workflow **Import → Prepare → Slice → Inspect → Export** instead of presenting a desktop slicer compressed onto a phone. Linux has a useful lightweight bridge/model-inspection path, but native Linux feature work is frozen while Android is brought to production; iOS is intentionally later.

## v1 status

The exact owner-only immersive build command and asset policy are in
[`docs/VISUAL_REVIEW_BUILD.md`](docs/VISUAL_REVIEW_BUILD.md).

The repository now contains a runnable Android application. It can:

- import ASCII/binary STL, named-part OBJ, and multi-object 3MF files through the Android file picker or share sheet, including bounded ZIP bundles containing multiple mesh files and selecting multiple separate models into one lightly packed project;
- open bundled box, box-and-lid assembly, and mounting-block examples immediately from the model library;
- show a touch-rotatable, pinch-zoomable perspective model scene with an A1 Mini build-volume presentation envelope, named 3MF parts and disconnected STL solids available in a visual Parts focus view;
- inspect mesh health and run a conservative, reversible repair pass that removes degenerate/duplicate facets, welds near-duplicate vertices and normalizes closed-component winding;
- materialize imported model sources into a bounded, SHA-256-addressed app-private cache so saved plates reopen offline when a browser/cloud document grant disappears, and keep a newest-first **Recent on this phone** shelf in Model Atlas for one-tap offline reopen;
- auto-arrange multi-part assemblies with deterministic, clearance-aware 0/90° XY packing, use bounded axis-aligned auto-orientation for tall or awkward parts, then fine-tune individual parts with persisted placement and orientation;
- duplicate current models into bounded arrays or mirror them across the X/Y footprint centreline as validated, undoable geometry edits;
- prepare assemblies with persisted global scale/X/Y tilt/Z-rotation plus per-part scale, tilt, Z-rotation and X/Y placement on the bed;
- calibrate unitless STL/OBJ imports with a known physical dimension, using a bounded 10%–10,000% plate scale and the same fit gate used before slicing;
- undo and redo printable primitive, sketch, boolean, transform, arrange and repair edits with bounded per-plate history that survives process reload and portable project export/import;
- edit the versioned A1 Mini/PLA recipe on the phone (layer heights, infill, shell counts, temperatures, flow, volumetric limit, motion, cooling and supports);
- slice offline on-device into deterministic perimeter and clipped scanline-infill G-code;
- inspect the generated toolpath one layer at a time; and
- export an inspectable `.gcode.3mf` package for the active plate through Android Storage Access Framework;
- slice every populated plate in sequence and export the independently validated artifacts as one portable `.alloy-batch.zip`;
- keep multi-plate slicing in an Android foreground service with an immutable,
  offline-materialized request and durable per-plate result checkpoints, so
  rotation/backgrounding does not discard a long batch;
- surface a local workshop inventory with reorder and maintenance indicators;
- automatically subtract estimated filament grams only after a paired printer reports a job as completed, with job-ID idempotency and a cumulative usage reading;
- add custom workshop items (tools, spares, consumables and care routines) with
  persisted quantities, reorder thresholds, notes and service intervals,
  including 14-day service-soon and overdue states;
- keep up to eight bounded plate snapshots, each with its own model sources, selection and transforms, so a box, lid and parts can be prepared as one phone project;
- save and reopen a portable `.alloy.zip` project archive containing source models, plate transforms, the typed recipe, workshop inventory state and bounded modeling history, so a project is not tied to one cloud-document URI;
- stage complete gcode.3mf artifacts in durable app-private storage with bounded retention, size/SHA-256 verification, and checkpoint recovery for controlled phone-to-printer upload;
- run upload, start, telemetry monitoring and cancellation in an Android foreground printer-job service so ordinary Activity recreation does not tear down a live LAN session;
- pause and resume an active print through the same foreground session, with telemetry confirmation before the durable job state changes;
- require an explicit SHA-256 leaf-certificate pin before physical upload/start is exposed, while keeping read-only LAN probing available during pairing;
- persist the discovered Bambu model code and refuse the A1 Mini profile at the physical-send boundary unless model `N1` (A1 Mini) is confirmed;
- discover Bambu printers through a bounded read-only local SSDP scan, with manual host entry retained for VLANs and networks that block discovery;
- import one or more user-owned Bambu machine/process/filament JSON presets from the phone, or a resolved Bambu `project_settings.config` export, resolve selected inheritance chains, persist the normalized profile privately, and project only bounded allowlisted scalar settings into the native slicer;
- review the active profile, export the normalized Alloy profile JSON for backup or handoff, or reset an imported override to the bundled A1 Mini profile without touching projects, models, plates, or inventory;
- remember the selected model and recipe locally so a phone-only session can be resumed; and
- recover a missing or invalid saved document into the local inspectable showcase without deleting the saved project checkpoint;
- run slices through a cancellable job boundary with an opt-in adapter for the pinned native engine and a safe fallback by default.
- run a single-plate slice from an immutable request snapshot in an Android foreground service, so rotation/backgrounding does not discard the job; interrupted slices are surfaced for explicit recovery rather than silently retried.
- load the A1 Mini/PLA defaults from a pinned, hash-listed profile asset with visible provenance state.
- run a visible pre-slice setup review that blocks invalid dimensions/recipes and requires acknowledgement of unverified warnings.
- run structural preflight before writing an exported package.

Model intake is bounded and fail-closed across STL, OBJ, 3MF and STEP: named OBJ
parts, negative face references, 3MF units/components and optional package-root
relationships are covered by regression tests, while oversized ASCII STL lines
are rejected before coordinate parsing.

This is a personal-use v1 foundation, not yet a production replacement for Bambu Studio. The optional Android-native path now embeds the pinned Orca `libslic3r` engine with Arachne and TreeSupport3D support generation; the default offline path remains a conservative grid-support fallback. Automatic hole filling, full multi-object arrangement, modifiers, multi-material, advanced cooling/seam logic, exact Bambu metadata, and printer-specific parity are still incomplete. Exported jobs must be opened and checked before any physical print. The app intentionally does not expose a one-tap Print action until engine/profile, support parity, LAN transport, and real-printer gates are proven.

Support settings are passed to the optional native engine; the offline fallback emits a bounded conservative grid and clearly marks it unverified. The previous Stage 1 research and parity tooling remains in `docs/`, `parity/`, `ci/`, and `tools/`. The integrated Orca-Mobile source and pinned commit are recorded in `THIRD_PARTY_NOTICES.md`; it remains an experimental engine dependency and is not represented as Bambu support parity.

The product experience direction, including the premium editorial workspace and skeuomorphic workshop inventory, is recorded in `docs/PLATFORM_AND_EXPERIENCE.md`.
Project recovery and engine-boundary decisions are documented in `docs/ARCHITECTURE.md`.
Bundled model provenance and the rules for importing community designs are documented in `docs/MODEL_LIBRARY.md`.
The phone-first modeling workbench and privacy-preserving visualization providers are documented in `docs/MODELING_AND_VISUALIZATION.md`.
The native dependency boundary and its fail-closed GMP/MPFR checks are documented in `docs/NATIVE_DEPENDENCIES.md`.

## Build and run

Open the repository in Android Studio, or run:

```bash
gradle :app:assembleDebug
```

The resulting local APK is `app/build/outputs/apk/debug/app-debug.apk`. The
GitHub Actions workflow `Alloy Android v1` uses an explicitly CI-debug-signed
release variant for installable release instrumentation and retains that
non-production APK as an artifact.

Linux support includes a small Tkinter model workspace plus the dependency-free
model/artifact bridge in `tools/alloy_linux.py`. It can inspect the bundled box
and parts, show a rotatable 3D-style preview, or invoke an installed Bambu
Studio/OrcaSlicer binary, inspect portable `.alloy.zip` multi-plate projects,
and validate the resulting `.gcode.3mf`; see
`docs/LINUX_DESKTOP.md`.

The `Alloy Android release candidate` workflow source-builds the pinned native
engine, assembles an unsigned native-enabled release APK, runs the same gates
and publishes a SHA-256 manifest on `v*` tags or manual dispatch. Signing,
hardware acceptance and native parity are still required before distribution;
see `docs/RELEASE.md`.

On a fresh install, the owner’s visual-review build opens the supplied Redmagic Keyboard Case v0.4 editable STEP assembly first when the native OCCT importer is present, and now opens that assembly directly into the immersive Hero view so the complete multi-part design is visible immediately; it falls back to the main chassis STL if that importer is omitted. Ordinary builds open the Alloy-authored box-and-lid assembly because owner assets and the unlicensed A1 reference are omitted. Tap **Import model** to choose an STL, OBJ, 3MF or STEP; native-enabled builds tessellate STEP through the bundled OCCT reader into the same live mesh workspace. A newly imported model now opens directly into the clean, object-first Hero view so the supplied box, parts or CAD conversion is visible before the preparation controls; close that view or tap **Machine view** to continue into printer context. Or tap **Model atlas** to browse the bundled box, box-and-lid assembly, mounting block, overhang/support calibration fixture, and thin-wall fixture in a live, presentation-style 3D surface. The private atlas also includes the exact owner-supplied A1 Mini v5 3MF as a checksum-verified model-library item; it remains excluded from ordinary/public APKs. Selecting **Use & open 3D** takes the chosen model straight into the clean Hero view; the header **3D** button returns there at any time. Alloy preserves 3MF build-item/component transforms, preserves OBJ object/group names, and infers disconnected solids in ordinary STL files for the Parts inspector. Tap **Parts** to focus the box or lid in the 3D view, then use **Transform** to scale, rotate, or move the selected part; the transform is saved with the plate and the result is leveled to the bed before slicing. Tap the **Plate** marker or choose **Print plates** from the project menu to keep separate model sets for a box, lid, bow components or other assemblies. Bundled examples and imported models are copied into the app-private content-addressed cache, so **Save project archive** can embed the current model directly; **Open project archive** restores embedded models, plates, transforms, recipe and bounded modeling history on the phone. Adjust **Recipe** if needed; the scrollable phone editor exposes the typed process/material/motion values that will be passed to the native engine. Tap **Slice**, review layers with **Layer − / Layer +**, and use **Export .3mf**. The model is centered automatically on the 180 × 180 × 180 mm A1 Mini volume.

The **3D study** action opens the full-screen Hero view and the Machine view. Hero is an object-first presentation with a quiet studio field, contact shadow, callouts, and in-stage material/theme controls; multi-part assemblies also expose an **Explode / Assemble** inspection toggle that never changes printable geometry. Machine shows the selected object in its printer context. The same controls are available in the standalone A1 Mini study. Visual-review builds can load the supplied 16,054-triangle A1 mini reference mesh by first running `python3 tools/import_a1_preview_reference.py <preview.zip> app/src/debug/assets/visuals/a1-mini-reference.mesh`. The Android path preserves that handoff's baked 42° crease normals, uses presentation-only region materials for the PEI, frame, carriage/nozzle and spool, adds bounded procedural PEI grain and Alloy-authored can/ball/key scale props, and applies a restrained key/fill/rim studio light pass so the machine study reads as a product view rather than a flat technical mesh. Saved project and BYOK reference thumbnails use the same perspective/depth ordering, per-part material palette and three-point lighting direction so the visual quality does not collapse when the live GLES surface is unavailable. Ordinary releases still omit the unlicensed reference; the owner can build a private visual-review release with `-PalloyIncludeSuppliedReferenceVisuals=true`, and the study labels whether the supplied mesh or Alloy-owned shell is active. The same private build includes the supplied Redmagic Keyboard Case v0.4 assets: the real main chassis opens first in Hero view, while the bezel, phone sled, service hatch, fit coupon, and editable STEP assembly appear in Model Atlas. These files are SHA-256 checked and remain excluded from ordinary/public APKs.

Tap **Model** to create a bounded box, chamfered box with an explicit corner parameter, open-top enclosure with explicit wall and floor thickness, cylinder, sphere, wedge or tube, extrude a convex 2D sketch, duplicate the current model into a bounded array, add a generated part to the current assembly, or (in the optional native build) perform an exact OCCT union, subtraction or intersection against a primitive tool. Generated and boolean-result STLs use the same cache, 3D preview, project, repair and slicing gates as imported geometry. After a model is loaded, **Visualize** offers an on-device studio preview with finish and environment presets plus an opt-in BYOK HTTPS image-edit path; BYOK uploads only the rendered thumbnail and prompt, with the endpoint/model/key stored in Android Keystore-backed preferences. The current APK does not bundle a generative local model, so it reports that capability as unavailable until an approved signed runtime is shipped.

The **Print readiness** action explains every physical-send prerequisite in one place: native engine/profile promotion, verified slice and package, support parity when supports are requested, printer pairing, certificate pin and recovery state. The same checklist is evaluated again at the send boundary, so a stale or unverified screen cannot authorize a physical job.

Alloy does not interrupt the first 3D study with a notification prompt. Android requests notification access only when the user starts a foreground Slice or Slice all operation; declining still permits the operation, with progress retained inside the app.
Tap **Undo**, **Redo**, or **History** after a modeling edit to move through the current plate's bounded validated snapshot timeline. Historical model files remain protected in the app-private cache until the branch is pruned; part focus selection itself is treated as view state and does not destroy the edit branch.

From **Print plates**, **Slice all** processes every populated plate in sequence. **Export batch** then writes a portable `.alloy-batch.zip` containing one independently validated `.gcode.3mf` per plate. The batch is not a multi-plate printer transaction; select one plate for the normal upload/start confirmation flow. In **Parts**, **Repair geometry** applies the conservative cleanup pass to the source mesh and persists the choice with the plate; **Original geometry** restores the imported source. Open boundaries are intentionally retained and shown as a review warning.

The **Model library** also has a **Community sources** directory for curated
recurve and compound-bow pages. These are source links, not mirrored APK
assets: download on the phone, then share/open the STL, OBJ, 3MF or STEP with Alloy.
License and mechanical-safety caveats are shown before opening each source;
see [`docs/MODEL_LIBRARY.md`](docs/MODEL_LIBRARY.md).

## Validation

Existing parity tooling can be run from the project directory:

```bash
PYTHONPYCACHEPREFIX=/tmp/alloy-pycache python3 -m py_compile ci/*.py parity/*.py tools/*.py
python3 ci/validate_profile_assets.py
python3 ci/validate_android_workflows.py
python3 ci/verify_profile_snapshot.py <orca-slicer-checkout> app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json
python3 ci/validate_model_assets.py
python3 ci/validate_3mf_contract.py
python3 ci/validate_native_wiring.py
python3 ci/validate_native_jni_contract.py
python3 ci/validate_native_dependency_inputs.py arm64-v8a
python3 ci/validate_native_prebuilts.py
python3 ci/validate_release_wiring.py
python3 ci/validate_transport_wiring.py
python3 ci/audit_bambu_treesupport3d.py <bambu-studio-v02.08.02.61-checkout> . <audit-output-dir>
python3 parity/verify_g3_profile.py <bambu-reference.gcode.3mf> <native.config.ini>
bash -n ci/sign_android_release.sh
python3 -m unittest discover -s parity -p 'test_*.py' -v
python3 -m unittest -v tools/test_alloy_linux.py
python3 tools/alloy_linux_gui.py --help
```

The Android build is verified in CI because this workspace does not include an Android SDK or JDK. See `docs/STAGE_1_IA.md` for the product contract, `docs/V1_IMPLEMENTATION.md` for the v1 safety boundary, and `docs/ARCHITECTURE.md` for the longer-term native engine boundary.

The Android workflow also runs `AndroidPipelineTest` on an API-35 emulator. It
covers the bundled multi-part model, offline slice, staged-package validator,
project checkpoint recovery, portable project archive round-trip, inventory
service recovery, completed-print filament accounting, deterministic Bambu LAN
protocol parser regressions, telemetry reducer transcripts and the native
profile-authority contract. Native slicing is covered separately by the arm64
instrumentation smoke test because it requires the source-built native
dependency tree. The latest local native release-variant run completed 149
executions with 0 failures and 2 intentional skips; the host parity suite is
65/65. One opt-in G3 evidence-export case is intentionally skipped unless
explicitly requested.
It covered native slicing of the cube, box-and-lid assembly,
mounting block, overhang/support fixture, and thin-wall fixture, plus strict
printer-host validation for IPv4, IPv6 and mDNS/DNS inputs.

Foreground slicing may complete without a live GLES surface. Before a
user-visible export or printer upload, Alloy detects that headless artifact
state and re-stages the package with the validated Bambu thumbnail entries,
so background execution does not silently produce a visually incomplete
`.gcode.3mf`.

The optional native engine source-build path is documented in `docs/NATIVE_ENGINE_INTEGRATION.md` and runs with `-PalloyNativeEngine=true`. That build selects the native adapter at runtime; it is not yet the default release runtime because its G1-G4 parity gates and physical A1 Mini validation remain open.

For fast Java/profile iteration against a separately verified ARM64 bundle, the
same packaging path accepts `-PalloyNativeEnginePrebuiltDir=/path/to/lib`;
this does not replace the clean native source-build gate.

The upstream A1 Mini machine start/end templates are audited separately with
`parity/audit_bambu_a1mini_templates.py`. They remain compatibility evidence
until their Bambu macros, indexed placeholders and conditionals have a
firmware-specific adapter and a real-printer transcript; the Android native
artifact carries and enforces the `alloy-safe-a1-mini-v1` baseline policy.

## Licensing

Any engine-derived code incorporated from SliceBeam, OrcaSlicer-Mobile, OrcaSlicer, or PrusaSlicer will retain its required notices and comply with the applicable GNU AGPL-3.0 obligations. The default Java fallback remains Alloy-authored; the optional native tree's pinned provenance is covered by the notice file below.

The optional native tree and its source provenance are tracked in `THIRD_PARTY_NOTICES.md`; native-enabled releases must complete the corresponding license/source audit.
