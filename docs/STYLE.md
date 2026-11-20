# Alloy house style (canon)

New UI work in any Alloy product is checked against this file. The current Forge editor is the reference implementation.

## Character

- Futuristic and fast, a little edgy like a sci-fi interface, but organic ("organic silicon"). Not grim: keep the canvas bright enough to read.
- Minimal and spacious. Few bounding boxes; panels are glass over the canvas, not boxed tiles. Form conveys function.
- Mostly monochrome; one accent marks what's active or selected.
- No decorative lines crossing the work area. Empty space is not wasted, but nothing is added just to fill it.

## Surfaces (as built in Forge)

- Dark and light themes, switchable. Dark ground `#0e1215`, light ground `#eef1f3`.
- Glass panels: translucent, `backdrop-filter: blur(16px)`, 20px radius, no borders.
- Accent: mint `#8ff0c4` (dark), `#11916a` (light). Warning `#ffb86b`, error `#ff7b7b`.
- Edge vignette for control legibility; the canvas centre stays bright.
- Viewport (Alloy slicer look, shared by Forge and Slicer): matte low-contrast bodies, geometry dissolving into background-coloured fog with no visible horizon, a grid that blooms under the model and fades to nothing, the build volume as a faint dashed box, and a body turns grey when it leaves the build volume.
- Hyle tokens (violet `#8E7BFF`, radium `#CCEC75`) are available as a theme preset through white-label theme tokens; they are not the default here.

## Type and copy

- Labels: monospace, uppercase, wide letter-spacing, 10–11px. Body: system sans, 13–14px.
- `/` may divide header items; menu items are separated by space alone; sequential steps use `>` chevrons.
- Explanatory copy lives behind an `i` tip, never on the surface. Copy must not read as AI-written: short hints only.

## Interaction

- Short square-wave blips on menu moves and actions (Nintendo-style), toggleable in settings.
- Every action is undoable. AI edits show a one-line log of what changed with an Undo link.
- Locks are visible on the item (feature, parameter, body, layer) and enforced in the data layer.
- State is never encoded by hue alone: pair colour with luminance, an icon or a label.
- Phone-first: every panel works as a bottom sheet under 900px wide; tap targets ≥ 40px.

## Brand marks

- Products are named "Alloy Forge", "Alloy Slicer", "Alloy Draft". Wordmark: `ALLOY` in the accent plus the product name in the muted ink, monospace, letter-spaced.
- White-label builds replace name, wordmark and theme tokens through `alloy.config.json`; nothing else in the UI hard-codes the brand.
