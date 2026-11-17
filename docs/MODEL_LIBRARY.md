# Model library and imported designs

Alloy's model library is deliberately small and auditable. The bundled
examples are Alloy-authored geometry for checking the viewport and slice
flow. Their author, license, source description and SHA-256 hashes are
recorded in `app/src/main/assets/models/catalog.json` and checked by CI:

- `box-20mm.stl` — a solid calibration cube;
- `box-and-lid.stl` — two watertight, separated solids for an assembly view;
- `mounting-block.stl` — a simple workshop part.

The Android file picker accepts STL, named-part OBJ, multi-object 3MF and STEP/STP CAD files, including
projects copied to the phone from a browser, Files, or a cloud drive. Multiple
STL files can also be selected together to make one inspectable phone project;
separate files receive a deterministic 5 mm-gap row layout on import. 3MF
build-item and component transforms are preserved when Alloy builds the
visible assembly. STL files have no standard object-name table, so Alloy also
detects disconnected solids in bounded small/medium STL imports and exposes
them as selectable Parts; larger files take the cheaper single-part path.
Selecting a part focuses it in the 3D preview without changing the export
geometry. A direct tap on a visible solid also selects it; large meshes use a
bounded preview triangle budget so inspection remains responsive on a phone.
The private visual-review build also exposes the owner-supplied Bambu Lab A1
Mini v5 3MF in Model Atlas. It is verified by SHA-256 before preview/open, and
its owner/private provenance is shown in the picker. This is an inspection and
modeling reference; it is not treated as a printer package or silently included
in ordinary/public releases.
The parts dialog reports each solid's X/Y/Z dimensions. Support generation is
available as an explicit recipe setting for the native engine boundary; the
offline fallback reports a warning and must not be used as a physical-print
approval. The same inspector reports bounded geometry health (watertight,
open-edge, non-manifold, or degenerate-facet status); very large meshes defer
full edge topology to avoid exhausting phone memory.
STL and OBJ do not carry a reliable unit declaration; if a downloaded design
arrives at an implausible size, use **Prepare → Scale to known dimension** and
anchor one measured width, depth or height before slicing. Alloy keeps that
choice with the plate and still refuses models that exceed the configured
build volume.

The bundled showcase assembly is loaded on a fresh install, so the 3D viewport
is useful before a user has downloaded or copied a design to the phone. The
showcase is an Alloy-authored seven-part study model: body, inner rim, lid,
hinge barrels, front clasp, front badge, and lid inlay. The **Examples** action
can reopen the box, assembly, or mounting block at any time. Bundled examples
are copied into the same content-addressed
offline cache as imported sources, so they can be included in a portable
`.alloy.zip` project without asking the user to re-import the original asset.
Imported bytes are conservatively detected as STL, OBJ, 3MF or STEP
before they enter the cache; provider filenames are retained only as friendly
labels, so a misleading extension from a browser or cloud provider cannot send
a valid model to the wrong parser. OBJ object/group names and negative face
indices are normalized into selectable Parts. Unsupported files still fail
with a user-facing import error rather than being treated as a model accidentally.
Portable `.alloy.zip` project archives preserve OBJ source extensions and
named-part data alongside STL, OBJ, 3MF and STEP sources.
ASCII STL lines are bounded before coordinate parsing so malformed community
files fail closed without creating an unbounded single-line allocation.
After intake, each imported source is copied atomically into Alloy's
app-private, SHA-256-addressed model cache. Saved plates therefore reopen
offline even if the original browser/cloud document grant disappears; the
cache is bounded and prunes only unreferenced files. Cache reads re-check the
content signature as well, so projects made by the earlier filename-based
cache continue to open after an app upgrade.
The Model Atlas also keeps a bounded newest-first **Recent on this phone**
shelf for imported cache files. It stores only the friendly label and the
validated cache URI, not a second copy of the model bytes. Tapping an entry
reopens it directly into the immersive 3D Hero view; stale or corrupted cache
entries are removed from the shelf rather than presented as a broken model.
Recent entries receive a capped reserve of one quarter of the model-cache
budget during pruning, so offline reopening is durable without allowing the
shelf alone to consume the whole cache budget; active plates and undo/history
sources retain stronger protection under pressure.
3MF imports inspect and bound the complete ZIP container, use central-directory-backed
reading for Bambu archives that contain stored data-descriptor entries, reject duplicate or
traversal-style entry names, validate an optional root relationship, and apply separate limits to entry count,
decompressed entry data, and mesh complexity; thumbnails and metadata cannot
bypass those limits.

## Owner visual-review catalog

The private visual-review build additionally overlays
`app/src/debug/assets/models/catalog-private.json`. It contains the
owner-provided Redmagic Keyboard Case v0.4 angled print set: the main chassis,
bezel, phone sled, service hatch, corner-fit coupon, and the positioned editable
STEP assembly. These are real supplied design files, not placeholder geometry.
They are included only when the build explicitly enables
`alloyIncludeSuppliedReferenceVisuals`; ordinary and distributable builds do not
package them. The Atlas verifies their SHA-256 and provenance metadata before
preview, and the STEP assembly is tessellated on the native worker before
entering the ordinary renderer. A fresh owner visual-review install opens the
main chassis directly in the immersive Hero view; the complete set remains
available through Model Atlas and the phone import/project flow. It also
includes the exact owner-supplied Bambu Lab A1 Mini v5 3MF as a 16,066-triangle
visual reference; its SHA-256 is recorded in the private catalog
and the Bambu component/build transform is preserved by the importer.

## Bow and other community models

Alloy does not bundle a recurve bow, compound bow, or other community model
until the exact author, license, attribution and redistribution terms have
been verified. “Free download” does not by itself grant redistribution or
commercial-use rights. A user can still import a lawfully obtained STL/OBJ/3MF
or STEP file from the phone and inspect, prepare, slice and export it using the
same flow; native-enabled builds tessellate STEP through OCCT first.

The catalog format carries the author, license, source description and a
content hash beside every bundled model. Community entries must additionally
carry the source URL, license URL and attribution text before they can be
promoted into the bundled library. Android validates the catalog schema and
SHA-256 of a bundled asset again at the moment it is opened, so a damaged or
tampered example cannot silently enter the 3D workspace.

Alloy also accepts a downloaded ZIP bundle from the Android document picker.
Only STL, OBJ, 3MF and STEP entries are imported; MTL files, images, documentation
and scripts are ignored, and bounded entry/path checks run before the extracted
meshes enter the normal cache and geometry validation flow. An `.alloy.zip`
project must instead be opened through **Open project archive**.

The Android model library also exposes a small, non-mirroring directory of
community source pages. The entries are links only: Alloy opens the original
page in the phone browser, and the user can share the downloaded STL/OBJ/3MF
back to Alloy for normal validation and inspection. As checked on 2026-09-04:

- [Recurve Bow on Pinshape](https://pinshape.com/items/33841-3d-printed-recurve-bow)
  is listed as free with CC BY-ND terms; attribution is required and remixes
  are not permitted under that license.
- [100% 3D Printed Compound Bow on Cults3D](https://cults3d.com/en/3d-model/game/100-3d-printed-compound-bow)
  is a personal-use listing and is not an open, redistributable Alloy asset.
- [Compound Bow on MakerWorld](https://makerworld.com/es/models/1140708-compound-bow)
  shows a Standard Digital File License; the current source terms must be
  read before download, modification or sharing.

These pages are not safety endorsements. A printed bow, limb, cam, string
anchor or other stored-energy part must not be treated as strength-rated or
safe for firing merely because a model is downloadable. The app labels these
entries as community sources and deliberately keeps them outside the trusted
bundled catalog.
