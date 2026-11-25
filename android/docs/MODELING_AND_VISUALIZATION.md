# Modeling and visualization

Alloy now has a bounded, phone-first modeling workbench in the Android shell.
It is intended for printable edits and quick assemblies, not as a claim that
the first mobile release is a full desktop CAD package.

## Current Android capability

- Box, parameterized chamfered box, open-top enclosure, cylinder, sphere, wedge
  and tube primitives.
- Open-top enclosures expose bounded wall and floor thickness and preserve a
  printable inner cavity, top rim and exterior shell as one watertight model.
- Chamfered boxes accept a bounded corner parameter for enclosure-like parts;
  openings and cut-outs can be made with the native Boolean tool.
- Current models can be duplicated into a bounded, deterministic 5 mm-clearance
  array while retaining named parts and the A1 Mini build-volume check.
- Current models can be mirrored across the X or Y footprint centreline as a
  real geometry edit; triangle winding is corrected, named parts are retained,
  and the operation participates in undo/redo and project archives.
- Convex 2D sketches (3–16 points) extruded into watertight printable prisms.
- Native OCCT union, subtraction and intersection against a bounded primitive
  tool when the optional native Android build is installed.
- Millimetre dimensions bounded to the A1 Mini build volume.
- Additive assemblies using the same named-part, transform, preview, repair,
  project archive and slicing paths as imported meshes.
- Bounded per-plate undo/redo history for primitive, sketch, boolean, transform,
  arrange and repair edits. Snapshots are validated, survive Activity/process
  reload, and are included in portable project export/import with their
  content-addressed model bytes until the history branch is pruned.
- Deterministic binary STL export into Alloy's content-addressed app-private
  model cache.
- Existing import/prepare tools remain available for downloaded bow parts,
  boxes, fixtures and other community models.
- Unitless STL/OBJ imports can be calibrated from one known width, depth or
  height in **Prepare → Scale to known dimension**. The uniform plate scale is
  bounded to 10%–10,000% and remains subject to the A1 Mini fit preflight.
- Multi-part **Arrange** uses a deterministic, clearance-aware best-fit shelf
  packer. It may rotate a part by 0° or 90° in XY and returns placements in
  source-part order, while preserving the user's scale and tilt settings.
  Automatic Z-up/tilt changes remain explicit because they can change support
  needs and first-layer behavior.
- Native-enabled Android builds also accept STEP/STP CAD sources. They are
  tessellated through the bundled OCCT bridge into a durable 3MF mesh before
  entering the renderer, Parts inspector, plate store or slicer.
- Multi-part assemblies expose a renderer-only Explode/Assemble presentation
  toggle. Exploded spacing is never persisted into the printable mesh and is
  never passed to the slicer or printer transport.

The workbench deliberately does not silently promise shelling, fillets,
constraint sketches, sculpting, mesh decimation or unconstrained desktop-CAD
history. Those are separate geometry-kernel and UX gates. The current history
is a bounded snapshot timeline for printable edits, not a general feature-tree
solver. Boolean editing is native-only and requires one watertight solid per
operand; the app rejects open, non-manifold, oversized or invalid results
instead of falling back to an approximate mesh.

## Visualization providers

The provider boundary accepts a rendered PNG thumbnail and a bounded prompt.
It never needs to upload the original STL/OBJ/3MF, printer credentials or
inventory.

The Android shell also includes a standalone A1 Mini 3D study reachable from
the header. It is useful for inspecting the supplied machine reference before
an object is imported; it is presentation geometry only and never participates
in build-volume validation or printer commands. New user imports land in the
object-first Hero view immediately after parsing, so a supplied box, part,
assembly or native STEP conversion is visible before the denser preparation
workspace; restored projects retain their normal workspace context.

### On-device

The normal 3D renderer and an on-device studio preview are available without a
network connection. The local provider offers bounded finish presets (natural
PLA, matte black, arctic white, safety orange and metallic graphite) and scene
presets (workshop, product photo, outdoor and installed), returning a bounded
PNG through the same provider contract used by BYOK. It is deliberately a
deterministic renderer, not a generative AI model. A generative on-device
implementation remains a separate capability and will only be advertised after
a signed `ai/visualizer/model.bundle` and its approved Android runtime are
shipped. This keeps the UI honest without changing the request contract.

### BYOK cloud

The Android UI accepts a user-owned HTTPS image-edit endpoint, model name and
API key. The provider sends a multipart request containing:

1. one bounded Alloy-rendered PNG reference;
2. the user's prompt;
3. model, size, count and `b64_json` response preferences.

The endpoint must be OpenAI-compatible (or a compatible gateway) for an image
edit request. The request reference and returned image are both required to be
PNG data at the provider boundary; the returned base64 image is bounded and
displayed in the app. HTTPS image URLs are also accepted. The API key is encrypted with a separate
Android Keystore key and is never logged or placed in a project archive.

Useful prompts include “painted product photo”, “assembled in a workshop”,
“installed on a bicycle”, or “finished in matte black PLA”. AI output is a
visual concept only: it does not change geometry, material settings, slice
results or printer commands.

## Privacy and safety

- BYOK is opt-in and visibly labeled before the reference leaves the phone.
- No default endpoint, vendor account or embedded API key is used.
- Cleartext visualization endpoints are rejected, and BYOK requests do not
  follow redirects to an unreviewed or cleartext destination; the printer
  transport keeps its separate certificate-pin gate.
- A generated image cannot become a printable model or alter the active plate.
- Future local-model installation must verify signed model metadata, size and
  runtime compatibility before becoming available.

## Next modeling gates

The next high-value work is broader native solid editing (shells, fillets and
multi-body booleans), constraint-aware sketches and a richer feature tree on
top of the current snapshot timeline. These should land behind mesh validity
and slicer parity tests rather than being approximated with image or UI
effects.
