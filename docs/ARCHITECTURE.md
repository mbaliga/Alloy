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
Compose application shell, navigation, adaptive layout, Android intents/document handling, persistence and dependency wiring.

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

### `viewport`
Stage 1 keeps the upstream-proven OpenGL/native renderer and embeds its Android surface inside Compose. The viewport API owns render lifecycle, camera, selection, transforms and toolpath-display state.

Do not make Filament a Stage 1 dependency. A renderer rewrite is orthogonal to proving slicer correctness and would multiply risk.

### `transport-api`

```text
PrinterTransport
  discover()
  probe(printer)
  upload(artifact, progress)
  startPrint(remoteArtifact, options)
  status()
```

The app never calls MQTT or FTPS directly.

### `transport-bambu-lan`
A1 Mini Developer Mode implementation:
- implicit FTPS/TLS on 990 for upload
- MQTT/TLS on 8883
- username `bblp`, printer access code as password
- request topic `device/<serial>/request`
- `project_file` start command for `.gcode.3mf`

Credentials must use Android Keystore-backed storage. Access codes and printer serials must never be committed, logged in plaintext, included in crash reports, or embedded in exported project files.

### `profiles`
Versioned printer/process/filament preset inputs and test fixtures. Profile import is engine-side data, not Compose UI configuration.

### `parity-tests`
Golden-fixture harness comparing Alloy/Orca Android output with known-good desktop Orca/Bambu slices.

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

Long-running background behavior will be chosen only after profiling Android's current execution limits; Stage 1 must not promise slicing after the app has been force-stopped.

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
