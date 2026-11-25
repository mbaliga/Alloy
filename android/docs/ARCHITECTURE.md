# Alloy architecture

## Goals

- native Android application; no Termux/X11 dependency
- reliable A1 Mini slicing with Orca/Bambu-compatible profiles
- one adaptive codebase from phone to external-display desktop sessions
- no dependency on Bambu's proprietary networking plugin
- transport replaceable independently of the slicer engine
- engine work reproducible from source under the upstream AGPL obligations

## Modules

### `app`
Application shell, navigation, adaptive layout, Android intents/document handling, persistence and dependency wiring. The current v1 shell uses classic Android Views while the domain seams remain renderer-agnostic.

### `engine-api`
Kotlin-only contract consumed by the app.

Representative API shape:

```text
SlicerEngine
  openModel(document) -> ModelSession
  resolvePresetSet(printer, nozzle, plate, process, filament) -> PresetSet
  validate(session, presetSet, overrides) -> ValidationResult
  slice(session, configuration, progress) -> SliceResult
  cancel(job)

SliceResult
  outputArtifact
  estimate
  warnings
  previewSource
```

The Kotlin side owns opaque handles through scoped objects. Raw native pointers never enter Compose state.

### `engine-native`
C++/CMake + JNI integration derived from the selected OrcaSlicer-Mobile/OrcaSlicer upstream revision.

Responsibilities:
- model loading and repair data
- printer/process/filament configuration resolution
- slicing
- `.gcode.3mf` generation
- toolpath/preview data
- progress + cancellation

The bridge should be small enough to review independently from upstream engine updates.

The current app contains `SlicerEngine`, `LegacyOfflineEngine`, `BatchSliceJobController`, `SliceJobService` and `BatchSliceJobService`. The first is the typed seam, the second wraps the temporary export-only slicer, the batch controller remains the bounded multi-plate worker, and the two foreground services own immutable request snapshots, durable progress, result handoff and cancellation boundaries. Native C++/JNI work should implement the same seam rather than leak engine handles into the UI.

### `viewport`
`ViewportView` is a stable Android container around Alloy's own GLES 2.0
surface. The renderer owns a perspective camera, touch orbit/zoom/pan, part
selection, a bounded machine/build-volume presentation envelope, out-of-volume
shading, active-layer toolpath lines and bounded thumbnails. The host forwards
pause/resume to the `GLSurfaceView`, so Activity recreation does not leave a
render thread running. Printer collision limits remain a separate reviewed
profile gate; the visual envelope is never a print authorization.

The surface is intentionally dependency-free at this stage. An
`AndroidExternalSurface` host can be added later for foldable/desktop input and
resizing without changing the viewport API or slicer boundary.

### `transport-api`

```text
PrinterTransport
  discover() // read-only local SSDP discovery; credentials are never broadcast
  probe(printer)
  upload(artifact, progress)
  startPrint(remoteArtifact, options)
  status()
```

The app never calls MQTT or FTPS directly.

The current app also defines PrinterTransport with explicit probe/upload/start/cancel states and typed printer/artifact/options objects. BambuLanTransport implements the protocol boundary with implicit-TLS FTPS, MQTT/TLS telemetry, digest-checked artifacts and certificate validation. PrinterCredentialStore encrypts the printer name, host, serial and access code with an Android Keystore AES-GCM key. There is intentionally no physical Print action until protocol and hardware gates pass.

### `transport-bambu-lan`
A1 Mini Developer Mode implementation:
- implicit FTPS/TLS on 990 for upload
- MQTT/TLS on 8883
- username `bblp`, printer access code as password
- request topic `device/<serial>/request`
- `project_file` start command for `.gcode.3mf`

Credentials must use Android Keystore-backed storage. Access codes and printer serials must never be committed, logged in plaintext, included in crash reports, or embedded in exported project files.

ArtifactStore stages packages in durable app-private storage and verifies their
package structure, size and SHA-256 digest before recovery or upload. The
storage root and artifact source must be regular files/directories rather than
symbolic links, so a stale checkpoint cannot resolve into unrelated storage.
A torn or unreadable printer checkpoint is represented as
`RECOVERY_REQUIRED`, rather than being treated as an empty store, and therefore
keeps sending disabled until explicit user review. Transport callbacks must
also match the checkpointed printer host, serial, artifact name, size and
SHA-256 before they can advance it; a late callback from an older job is
ignored.
A torn or unreadable printer checkpoint is represented as
`RECOVERY_REQUIRED`, rather than being treated as an empty store, and therefore
keeps sending disabled until explicit user review.

`PrinterJobService` now owns upload, start, telemetry monitoring and
cancellation as an Android foreground service. `PrinterJobStore` gives that
service a short-lived persisted lease and heartbeat: normal Activity
recreation leaves the live transaction alone, while a process restart has no
in-memory owner and therefore still promotes the record to
`RECOVERY_REQUIRED`. The service publishes state through an app-scoped
broadcast and a low-noise notification; it is not restart-sticky and never
retries an unconfirmed printer operation.

`BatchSliceJobController` runs the same engine seam over each populated plate,
rebuilding cached sources and saved transforms before staging a separate
artifact. `BatchSliceJobService` feeds it an immutable, offline-materialized
request and checkpoints each plate in `BatchSliceResultStore` before exposing
the complete result. `BatchArtifactArchive` checks every nested `.gcode.3mf`
with the existing package validator and binds the batch manifest to each
artifact's size and SHA-256. The archive is deliberately not accepted by the
printer transport as a multi-plate job; the user still selects and confirms
one plate.

### `profiles`
Versioned printer/process/filament preset inputs and test fixtures. Profile import is engine-side data, not Compose UI configuration.

### `parity-tests`
Golden-fixture harness comparing Alloy/Orca Android output with known-good desktop Orca/Bambu slices.

### `linux-bridge`

`tools/alloy_linux.py` is the first Linux implementation of the shared model
and artifact boundary. It performs bounded STL/OBJ/3MF inspection and invokes an
installed slicer with an explicit argv vector, then validates the resulting
`.gcode.3mf` before it is eligible for the transport layer. It is deliberately
headless and dependency-free while a native Linux GUI and shared native engine
are still release gates.

## Profile parity gate

Before any generated artifact is trusted for a real print:

1. Pin an upstream Orca engine/profile revision.
2. Select simple deterministic fixtures (cube, overhang/support fixture, thin-wall fixture, bridge, dimensional case fragment).
3. Slice each with a documented A1 Mini 0.4 mm nozzle/process/filament configuration in desktop OrcaSlicer/Bambu Studio.
4. Slice the same source/config through Alloy.
5. Compare normalized configuration, plate metadata, generated G-code semantics and estimated metrics.
6. Investigate differences before physical printing.
7. Only then run low-risk real-printer fixtures.

Byte-for-byte G-code equality is not the goal when timestamps/order differ; semantic configuration and motion/extrusion behavior are.

## Adaptive UI architecture

Use Material 3 adaptive/window size information rather than device-name checks.

- compact: stateful task flow; sheets and viewport
- foldable: include Jetpack WindowManager `FoldingFeature` in layout decisions
- expanded/tablet: list-detail with persistent parameter/object pane
- desktop/external display: fully resizable panes and input-device affordances

`resizeableActivity` remains enabled and orientation is never locked.

## Android desktop input

Desktop features are additive. Core commands are modeled as actions so they can be invoked by touch, toolbar, context menu or shortcut without duplicating business logic.

Examples:
- import
- undo/redo
- select all
- arrange
- center on plate
- slice
- toggle model/toolpath view
- export
- send to printer

## Lifecycle and resource safety

Slicing is CPU/memory intensive and must not be represented as an Activity-owned thread.

- engine jobs have explicit IDs and cancellation
- native resources use deterministic close/dispose semantics
- configuration/model state survives Activity recreation
- UI observes job state rather than owning the job
- cancellation is cooperative across Kotlin/JNI/C++
- application detects low-memory conditions and fails clearly rather than silently corrupting a project

The v1 shell checkpoints the active document URI, recipe and transforms through Android's persistable document permissions. `ModelStore` immediately materializes imported STL/OBJ/3MF/STEP sources into a bounded, content-addressed app-private cache so a cloud/browser grant is not the only copy available offline. STEP sources are converted through OCCT into durable 3MF before the Java renderer/project boundary. `PlateStore` adds bounded multi-plate snapshots for separate box, lid and parts workspaces. `ModelHistoryStore` adds a per-plate, validated snapshot timeline for reversible printable edits and keeps every historical cache model referenced by an undo/redo branch; `ProjectArchive` serializes that bounded timeline, cursor and source bytes for portable export/import. Slice requests are materialized into immutable app-private service inputs; completed single-plate and batch results are retained in bounded durable storage and revalidated from their checkpoints. Extracted source folders from portable archives are also bounded by count and total size while preserving files referenced by the active plate snapshots; Room remains the later boundary for searchable project metadata, richer feature history and thumbnails.

Long-running background behavior is provided by foreground services for an
explicitly initiated slice or upload/start/cancel transaction. Alloy does not
promise slicing or printer recovery after the app has been force-stopped; that
case remains an explicit recovery review.

## Security boundary

LAN printer control is local-network privileged functionality.

- require explicit printer pairing/setup
- display target printer name/IP before first print
- confirm start after upload
- TLS certificate verification constraints are documented because current printer endpoints commonly use self-signed certificates
- never expose a generic remote G-code command surface through exported Android intents
- validate generated artifact is local and complete before upload

## CI gates

1. Android/Kotlin unit tests
2. native ARM64 reproducible build
3. JNI smoke tests
4. profile/parser tests
5. slicing golden fixtures
6. `.gcode.3mf` structure validation
7. transport unit tests against a fake/local Bambu endpoint where feasible
8. debug APK artifact

Physical A1 Mini print validation remains a separate explicit hardware gate.
