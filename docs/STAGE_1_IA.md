# Stage 1 — compact-phone information architecture

Status: **PROPOSED — approval gate**

No Compose screen implementation should begin until this document's interaction model is explicitly approved.

## Product principle

A phone slicer should behave like a focused print-preparation task, not a desktop slicer squeezed into a narrow viewport.

The default path is:

**Import → Prepare → Slice → Inspect → Print / Export**

The 3D model remains the visual anchor. Controls appear only when the current task needs them.

## 1. Import

### Empty state
- dominant `Import model` action
- Android system file picker
- Stage 1 required formats: STL and 3MF
- STEP is deferred if OCCT blocks the reproducible native build gate
- recent local projects below the primary action
- no printer dashboard clutter before a model exists

### After import
The model opens directly into Prepare. Importing another model is available from the project menu and, where Android supports it, share/open-with and drag-and-drop.

## 2. Prepare

### Persistent viewport
The viewport owns most of the screen. Gestures:
- one finger: orbit
- two fingers: pan/zoom
- tap object: select
- long press: contextual object actions

Transform tools are a compact mode strip: move, rotate, scale, arrange. Numeric entry opens a bottom sheet rather than permanent XYZ fields.

### Print recipe bar
A compact summary immediately above the primary action shows:
- printer: Bambu Lab A1 Mini
- plate
- filament
- process/profile, for example `0.20 mm Standard`

Tapping it opens the Recipe sheet. The ordinary case should be confirmable without opening it.

### Quick overrides
Stage 1 exposes a deliberately small set in a bottom sheet:
1. layer height / quality preset
2. walls: count + Arachne / Classic generator
3. infill density
4. infill pattern
5. supports: on/off + engine-supported type
6. brim / build-plate adhesion
7. plate type
8. orientation / scale: lay-flat, rotate, resize

Each override has `Profile default` as a first-class reset state. Alloy must visually distinguish a profile value from a user override.

`Advanced` contains lower-frequency controls such as seam position, ironing, cooling, first-layer details, and max volumetric speed. Values still preserve an explicit profile-default state.

Flow calibration is **not** a slice setting in Alloy's A1 Mini flow; it is a print-start option.

A `More parameters` affordance on phone opens searchable categories, but the desktop-style always-visible parameter tree is reserved for larger width classes.

### Validation
Before Slice becomes active, Alloy reports blocking setup problems in context:
- object outside printable volume
- missing/incompatible printer, nozzle, process or filament preset
- unsupported geometry/file
- configuration combination rejected by engine

Warnings that do not block slicing remain warnings.

## 3. Slice

Primary bottom action: **Slice**.

During slicing:
- viewport remains visible
- progress is non-modal
- user can cancel
- Android process/lifecycle transitions must not leave native engine handles in an indeterminate state
- a completed slice transitions directly to Inspect

Do not show fictional percentage precision if upstream only provides coarse stages.

## 4. Inspect

This is the G-code/toolpath preview, not a static success screen.

Phone controls:
- vertical layer-range scrubber on the viewport edge
- current layer / Z height
- legend mode selector: feature type initially; speed/flow/time when engine data is available
- estimated print time
- filament usage
- total material estimate where profile data supports it
- warning count

The full legend/settings surface lives in a bottom sheet.

A `Back to prepare` action preserves all user overrides and model transforms.

## 5. Print / Export

Primary action after a valid slice: **Print**.

If no compatible printer transport is configured, label it **Export** rather than presenting a dead Print button.

### Print sheet
For A1 Mini Stage 1, only job-start options that the `project_file` path actually supports are shown:
- target printer
- bed leveling
- flow calibration
- vibration calibration
- timelapse
- layer inspection where supported
- explicit confirmation before sending the print-start command

Do **not** present layer height, temperature, infill, walls, or other slice-time values as start-time controls. They are baked into the generated `.gcode.3mf`.

For the initial non-Combo A1 Mini target, `use_ams` is false. AMS mapping is not exposed in Stage 1 until the external-spool payload is physically verified.

Alloy first uploads the completed `.gcode.3mf`, verifies upload success, then issues the MQTT `project_file` command and waits for printer telemetry confirming PREPARE/RUNNING. Upload success, command publish, command acceptance, and print running are distinct states.

### Export
- save `.gcode.3mf` through Android Storage Access Framework
- SD-card/manual-copy workflow remains fully supported even when LAN printing exists

## Navigation

Stage 1 uses state progression rather than a permanent multi-tab desktop navbar.

Top app bar:
- Back where meaningful
- project/model filename
- project overflow: save/export, add model, project information

Bottom:
- context-sensitive primary action (`Slice`, `Print`, or `Export`)
- secondary action only when genuinely useful

Printer management and global app settings live outside the active preparation flow.

## Compact layout constraints

- designed for widths under 600dp first
- no orientation lock
- landscape must remain functional, but Stage 1 optimization target is handheld portrait
- no essential action depends on hover, mouse secondary click, or keyboard
- touch targets follow Android accessibility sizing
- bottom sheets remain usable with gesture navigation and IME visible
- system file picker rather than a bespoke filesystem browser

## Renderer constraint

Stage 1 may host the existing SliceBeam-lineage `GLView` through Compose `AndroidView` **only as bring-up scaffolding for basic model and G-code display**. Do not add Alloy-specific renderer features to the legacy Java view.

Before Stage 2, replace it with an Alloy-owned GLES renderer hosted through `AndroidExternalSurface`, with desktop input/resizing requirements designed in rather than bolted on.

## Adaptive contract for later stages

The phone IA is not thrown away at larger sizes; it unfolds.

- **Foldable book posture:** viewport + preparation/inspection pane can coexist.
- **Tabletop posture:** viewport above hinge, controls below where geometry allows.
- **Tablet:** persistent object/parameter pane + viewport; quick controls become selections in the full editor.
- **Desktop/windowed:** parameter tree, object list, keyboard/hover/right-click, drag-drop, multiple resizable panes; no assumption about conventional 16:9. Validate 5120×1440 / 32:9 explicitly.

## Approval questions

Approval should specifically confirm these choices:
1. task progression rather than desktop tabs/panels on phone
2. viewport-dominant Prepare screen
3. Recipe summary + bottom-sheet editing
4. the revised eight quick-override categories
5. toolpath preview as its own Inspect state
6. explicit upload → verification → print-start → telemetry-confirmed-running sequence
7. `Print` becoming `Export` when no printer transport is configured
8. Stage 1 legacy GLView is scaffolding only, replaced before Stage 2

Once explicitly approved, this document becomes the contract for Stage 1 Compose implementation.
