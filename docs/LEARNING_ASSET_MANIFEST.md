# Alloy learning-asset manifest

This manifest records project-bound learning artwork. It is deliberately kept
separate from printer qualification evidence: an illustration may clarify a
boundary, but it never proves a printer state, material condition, or print.

## `learn/first-layer-hotend-v1.png`

- **Asset ID:** `scene-first-layer-hotend-v1`
- **Used by:** `start-inspect`, `first-layer`, `symptom-first-layer-bunched`,
  and `symptom-plate-residue`
- **Type:** existing bundled explanatory illustration, offline PNG
- **SHA-256:** `3dcb86cdf1fc9479588622d25fae1d7c5744c74ebbe9cb381037f00fc041c646`
- **Size:** 1211 × 1299 px, RGBA PNG, 1,099,588 bytes
- **Alt text:** “A generic hotend lays a single orange filament line on a dark
  build plate.”
- **Review status:** visually inspected; image is illustrative, not a slice,
  safety check, diagnosis, or print-success prediction
- **Rights/provenance:** source/master and usage-rights record are not present
  in the repository; confirm before release claims about ownership

## `learn/material-feed-route-v1.png`

- **Asset ID:** `scene-material-feed-route-v1`
- **Used by:** `start-recipe`, `start-material`, `materials-basics`,
  `symptom-feed-route`, `symptom-runout`, and `symptom-moisture`
- **Type:** existing bundled explanatory illustration, offline PNG
- **SHA-256:** `4e089649afc6c23711d089fa64858ebde4bab9f89e6987ce6462c9ea4a6db3b2`
- **Size:** 1145 × 1374 px, RGB PNG, 1,516,744 bytes
- **Alt text:** “An orange filament spool feeds a generic hotend above a dark
  grid.”
- **Review status:** visually inspected; illustration cannot prove spool
  compatibility, material condition, feed route, or printer readiness
- **Rights/provenance:** source/master and usage-rights record are not present
  in the repository; confirm before release claims about ownership

## `learn/build-plate-boundary-v1.png`

- **Asset ID:** `scene-build-plate-boundary-v1`
- **Used by:** `start-place` and `concept-model`
- **Type:** original generated explanatory illustration, offline PNG
- **Source/master:** built-in Image Generation output, generated 2026-10-07;
  original retained in the Codex generated-image store
- **SHA-256:** `44f3692ba4079d82f36aa12161648fafbfa815d59e52ee4709df8df48e389e2b`
- **Size:** 512 × 512 px, RGB PNG, 311,743 bytes
- **Alt text:** “An orange sample model centered within the printable boundary
  on a dark build plate.”
- **Review status:** conceptual artwork; it does not prove physical fit,
  clearance, adhesion, printer identity, or print success
- **Light/dark treatment:** warm ivory stage with a localized plate texture;
  native UI supplies text and card surfaces for both themes
- **Reduced motion:** static; no animation is implied
- **Rights/provenance:** Alloy-owned generated artwork; no third-party brand,
  logo, UI, or reference image was used

### Generation brief

> Square offline learning illustration of a small generic desktop 3D-printer
> build plate in three-quarter view, with a simple orange cube centered within
> the printable outline. Premium precise friendly 3D product rendering, warm
> ivory background, matte charcoal plate, restrained bright orange accent,
> subtle contact shadow and no text, labels, logos, or watermark. Conceptual
> only; staying inside the outline does not prove print safety.

### Acceptance constraints

- Native article copy retains the caveat that visual fit does not prove
  physical clearance, adhesion, or print success.
- The image must never be used as evidence that an object fits a selected
  printer profile; the app's measured bounds and profile remain authoritative.
- A later variant must be versioned and receive its own hash and visual review.

## `learn/printer-phone-recovery-v1.png`

- **Asset ID:** `scene-printer-phone-recovery-v1`
- **Used by:** `connection-not-confirmed`, `symptom-export-rejected`,
  `symptom-printer-busy`, and `symptom-app-interrupted`
- **Type:** original generated explanatory illustration, offline PNG
- **Source/master:** built-in Image Generation output, generated 2026-09-29;
  non-project exploratory master remains in the Codex generated-image store
- **SHA-256:** `3590773fb59694a8ab233f5ade00e9c0d6d3ee99902b00c8b9e771aeab23eb21`
- **Size:** 1254 × 1254 px, RGB PNG, 1,360,212 bytes
- **Alt text:** “A generic idle 3D printer and a blank phone separated by
  teal wireless arcs, showing that connection state needs review.”
- **Review status:** visual/source review pending a named human reviewer;
  shipped only as explanatory artwork, never as a printer-state assertion
- **Light/dark treatment:** light original; dark mode uses the native card
  surface and this image must retain a 4.5:1 surrounding text contrast
- **Reduced motion:** static; the image contains no implied motion requirement
- **Rights/provenance:** Alloy-owned generated artwork; no third-party brand,
  logo, UI, or reference image was used

### Generation brief

> A single square premium 3D illustration of a generic unbranded desktop FDM
> printer and a blank unbranded smartphone, standing apart on a smooth
> warm-white boundaryless stage, connected only by three soft teal wireless
> arcs and a localized faint grid/bloom below them. The printer is idle and
> safe, with a small neutral printed cube on its plate. No writing, letters,
> numbers, checkmarks, crosses, app UI, logos, recognizable device brands,
> watermarks, characters, hands, smoke, fire, or unsafe maintenance depiction.

### Acceptance constraints

- Native UI copy states `Printer state not confirmed`; the picture may not be
  used as a connected/paired success indicator.
- The blank phone is intentional. It must not acquire generated or baked UI.
- A future dark asset may replace this file only as a new version with its own
  checksum, review record, and explicit contrast check.

## `learn/first-layer-adhesion-v1.jpg`

- **Asset ID:** `scene-first-layer-adhesion-v1`
- **Used by:** `symptom-not-sticking`, `symptom-first-layer-gaps`, and
  `symptom-warping`
- **Type:** original generated explanatory illustration, offline JPEG
- **Source/master:** built-in Image Generation output, generated 2026-10-13;
  original master remains in the Codex generated-image store
- **SHA-256:** `6f759139a6e0f9719b5b71172a9648ce4c1fbbfcd51fd529ebc8795d2507fa33`
- **Size:** 512 × 512 px, RGB JPEG, 70,965 bytes
- **Alt text:** “Two orange first-layer test patches on a dark textured plate:
  one continuous and evenly spaced, the other separated and lifting at an edge.”
- **Review status:** generated artwork visually inspected; technical/source
  review remains pending a named human reviewer. The image illustrates
  symptoms, not a remote diagnosis or guaranteed cause.
- **Light/dark treatment:** warm ivory field with dark plate and orange
  filament; keep the image at native aspect ratio and maintain surrounding
  text contrast in both app themes
- **Reduced motion:** static
- **Rights/provenance:** Alloy-owned generated artwork; no third-party brand,
  logo, UI, or reference image was used

### Generation brief

> Square 3D educational render comparing a successful first layer and an
> early failed first layer on one dark charcoal PEI plate. On the left, bright
> orange extrusion lines are continuous, gently flattened, evenly spaced and
> fully adhered. On the right, lines are visibly separated and one edge curls
> slightly upward. Warm near-white studio background, subtle plate grid,
> soft shadow, crisp phone-card composition. No words, labels, logos, UI,
> watermark, hands, printer parts, or extra objects.

### Acceptance constraints

- Article copy must say that the illustration shows possible symptoms, not a
  cause Alloy can diagnose remotely.
- The illustration cannot replace inspection of the selected printer, plate,
  material and recipe, and it never qualifies a profile or predicts success.
- Do not depict touching a hot plate/nozzle or prescribe temperature changes.
- Any replacement must be versioned, re-reviewed, and re-hashed.

## `learn/support-styles-v1.jpg`

- **Asset ID:** `scene-support-styles-v1`
- **Used by:** `concept-supports` and `symptom-bridge-droop`
- **Type:** original generated explanatory illustration, offline JPEG
- **Source/master:** built-in Image Generation output, generated 2026-10-13;
  original master remains in the Codex generated-image store
- **SHA-256:** `35be1228fe45ca850224e751da9bcf39f6e41faaf84f4f1029232f4a3c1103aa`
- **Size:** 512 × 512 px, RGB JPEG, 38,407 bytes
- **Alt text:** “A simple orange overhang shown on two plates, held up by gray
  straight-grid supports on one and a branched tree support on the other.”
- **Review status:** visually inspected; technical/source review remains
  pending a named human reviewer. This is a conceptual comparison, not a
  claim that either support mode is available or qualified in Alloy.
- **Light/dark treatment:** warm ivory field, charcoal plates, orange model,
  muted gray supports; preserve native aspect ratio in both themes
- **Reduced motion:** static
- **Rights/provenance:** Alloy-owned generated artwork; no third-party brand,
  logo, UI, or reference image was used

### Generation brief

> Square 3D educational render comparing two support styles beneath an orange
> FDM overhang: neat gray straight support pillars with a sparse lattice on
> one fully visible charcoal PEI plate, and one smooth gray branched tree
> support under the same orange part on a second fully visible plate. Warm
> near-white background, subtle grid, soft shadows, generous margin, no text,
> labels, logos, UI, watermark, hands, tools, or printer parts.

### Acceptance constraints

- The accompanying article must state that the image explains a concept; it
  is not a slicer output or compatibility/qualification indicator.
- Never infer that Alloy can produce either support type or that a pictured
  style will work for a specific model, material, plate, or printer.
- Any replacement must be versioned, re-reviewed, and re-hashed.
