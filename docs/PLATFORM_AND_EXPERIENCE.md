# Alloy platform and experience direction

## Product priority

Alloy is Android-first for the foreseeable future. The phone is the primary workstation: importing a model, preparing it, slicing it, inspecting the result, exporting it and eventually sending it to a paired printer must not require a companion computer.

Linux support is appreciated but intentionally frozen at the lightweight bridge/model-inspection milestone. It should share the engine, profile, project and transport contracts with Android rather than becoming a second product. iOS is intentionally deferred until the Android workflow and printer safety gates are proven.

## Visual language

The product shell uses an original, quiet editorial language inspired by premium product configurators:

- pale workshop surfaces, high-contrast typography and restrained olive/gold accents;
- one clear central 3D subject with generous space around it;
- floating parameter markers for the small set of decisions that matter most on a phone;
- tactile cards, circular controls and subtle elevation for a skeuomorphic workshop feel;
- no copied luxury-brand marks, monograms or proprietary visual assets.

The compact phone flow remains:

`Import → Prepare → Slice → Inspect → Export`

The transport step becomes available only after profile, artifact and physical-printer gates pass:

`Export → Upload → Confirm → Monitor`

## Workshop inventory

Inventory is a first-class local feature, not decoration. Built-in and
user-created items persist locally with:

- name, category and equipment association;
- quantity, unit and reorder threshold;
- consumable usage estimate and replacement history;
- completed-print filament usage in grams, with duplicate completion events ignored by job ID;
- last inspection/service date and next due date;
- notes, supplier reference and optional photo (photo attachment remains deferred);
- severity: ready, service soon, due/overdue or reorder. Service-soon alerts
  appear during the 14 days before a scheduled maintenance date.

The compact home strip shows only the number of attention items and the most relevant next action. The full sheet exposes consumables, tools, printer parts and maintenance routines, and lets the user add or remove custom records. Missing stock and overdue maintenance remain visible until the user records a correction.

## Implementation boundary

The current UI pass is an original Android-native shell over an Alloy-owned GLES viewport. It does not change the slicer safety boundary or claim Bambu-compatible output. The phone shell now persists one or more active model URIs, the full editable typed recipe, global scale/X/Y tilt/Z-rotation and per-part scale/tilt/Z-rotation/X/Y transforms, bounded multi-plate snapshots, a conservative separated row auto-arrangement, an explicit reversible mesh-repair pass, runs slicing through a cancellable engine seam, exposes bundled model examples, contains an opt-in adapter for the pinned native engine, and can save/open a bounded portable `.alloy.zip` project with embedded source models, SHA-256-bound model records, bounded modeling history timelines and workshop inventory state. The viewport now supplies perspective orbit, pinch zoom, two-finger pan, part picking, a machine/build-volume presentation envelope, out-of-volume shading, layer toolpaths and lifecycle-safe thumbnails. It also keeps bounded project history, thumbnails and durable staged artifacts with identity-checked recovery. Multi-plate requests and per-plate result manifests now survive Activity recreation through a foreground service. The next product milestones are native build/parity validation, reviewed collision geometry, richer packing/orientation tools, and the physical printer gate.

Linux should reuse the same domain models and engine API when Android reaches its production gates. The current Linux bridge remains useful for inspection and interoperability; new feature work stays Android-first. The desktop shell can adopt a resizable window with a persistent inspector pane later while the phone keeps the marker-based workflow.
