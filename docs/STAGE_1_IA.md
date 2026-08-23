# Stage 1 — compact-phone information architecture

Status: **PROPOSED — approval gate**

No Compose screen implementation should begin until this document's interaction model is approved.

## Product principle

A phone slicer should behave like a focused print-preparation task, not a desktop slicer squeezed into a narrow viewport.

The default path is:

**Import → Prepare → Slice → Inspect → Send / Export**

The 3D model remains the visual anchor. Controls appear only when the current task needs them.

## 1. Import

### Empty state
- dominant `Import model` action
- Android system file picker
- accepted first-slice formats: STL, 3MF, STEP when supported by the engine build
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
- process/profile (for example 0.20 mm Standard)

Tapping it opens the Recipe sheet. The user should be able to confirm the ordinary case without opening it.

### Quick overrides
Stage 1 exposes a deliberately small set in a bottom sheet:
1. layer height / quality preset
2. wall count
3. infill density
4. infill pattern
5. supports: off / auto / manual-compatible mode exposed by engine
6. build-plate adhesion: none / brim
7. seam position
8. ironing where compatible

Each override has `Profile default` as a first-class reset state. Alloy must visually distinguish a profile value from a user override.

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

## 5. Send / Export

Primary action after a valid slice: **Print**.

If no compatible printer transport is configured, label it **Export** rather than presenting a dead Print button.

### Print sheet
For A1 Mini Stage 1:
- target printer
- bed leveling
- flow calibration when supported/appropriate
- vibration calibration when supported/appropriate
- timelapse
- external spool (non-AMS default for the initial non-Combo target)
- explicit confirmation before sending the print-start command

Alloy first uploads the completed `.gcode.3mf`, verifies success, then issues the MQTT `project_file` command. Upload and start are separate states in the UI and implementation.

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

- Designed for widths under 600dp first.
- No orientation lock.
- Landscape must remain functional, but Stage 1 optimization target is handheld portrait.
- No essential action depends on hover, mouse secondary click, or keyboard.
- Touch targets follow Android accessibility sizing.
- Bottom sheets must remain usable with gesture navigation and IME visible.
- System file picker rather than a bespoke filesystem browser.

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
4. the initial eight quick-override categories
5. toolpath preview as its own Inspect state
6. explicit upload → confirmation → print-start sequence
7. `Print` becoming `Export` when no printer transport is configured

Once approved, this document becomes the contract for Stage 1 Compose implementation.
