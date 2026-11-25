# Alloy Draft spec

Precision 2D drafting and vector design: Figma's ease, AutoCAD's precision, Concepts' touch feel. Free, no account, no file storage. Files stay on the user's device (open/save via the File System Access API where available, download/upload elsewhere).

Uses: jewellery drawings, parametric 2D CAD drawings, icons, posters, laser/CNC profiles, and sketches that Forge extrudes or revolves.

## Canvas and navigation
- Infinite canvas, pan/zoom (pinch, two-finger pan, wheel, trackpad), zoom to selection / fit / 100%.
- **Rulers** on top and left, in the document unit, with the cursor position tracked on both.
- **Guides** dragged out of the rulers, positioned numerically, lockable, per frame or global.
- **Grids**: square grid with snapping, pixel grid at high zoom, and layout grids per frame (columns, rows, gutters, margins).
- Units: mm, cm, in, pt, px; per-document default, any field accepts a unit (`12mm`, `0.5in`).
- Dark and light themes per `docs/STYLE.md`.

## Structure
- Pages, frames (artboards with presets: A-series, Letter, icon sizes, social sizes, custom), groups.
- **Layers panel** with nesting, rename, reorder by drag, show/hide, lock, solo, colour tag.
- Components and instances with overrides (after v1 core).

## Drawing tools
- Select / direct select (nodes), lasso select.
- Line, polyline, rectangle (corner radius per corner), ellipse, polygon, star, arc (3-point, centre), spline/Bézier pen with tangent handles, pencil with smoothing, text (point and area).
- Precision entry for every tool: type length/angle/coordinates while drawing (`@40<30` relative polar, `100,50` absolute).
- **Command line** (AutoCAD-style, optional): `line`, `circle`, `offset`, `trim`, `extend`, `fillet`, `chamfer`, `array`, `mirror`, `scale`, `rotate`, with aliases.
- Modify: offset, trim, extend, join, break, fillet and chamfer corners, boolean (union, subtract, intersect, exclude), outline stroke, flatten, simplify.
- Arrays: linear, polar, along a path.

## Snapping and measurement
- Object snaps: endpoint, midpoint, centre, quadrant, intersection, perpendicular, tangent, nearest, grid, guide.
- **Smart guides** with live distance and alignment readouts while moving (Figma-style red spacing lines and equal-gap hints).
- Hold-to-measure (Alt on desktop, a measure toggle on touch): distances from the selection to anything hovered.
- Align and distribute; tidy-up into a grid.

## Constraints and dimensions (the CAD part)
- Geometric constraints: coincident, parallel, perpendicular, tangent, concentric, equal, horizontal, vertical, symmetric, fixed.
- Dimensional constraints driven by parameters and expressions (the same expression language and parameters panel as Forge; plain words resolved by the model).
- Dimension annotations (linear, aligned, angular, radius, diameter) that are either driving or reference.
- Solver runs in a worker; over-constrained items are flagged, never silently broken.

## Style
- Fills (solid, linear/radial gradient), multiple strokes (width, caps, joins, dash), opacity, blend modes. Effects kept minimal (drop shadow, blur) in v1.
- Swatches and styles per document; the white-label theme only changes the app, never the user's document.

## Import and export
- Import: SVG, DXF, PDF (vector), images, and `.fig` (ZIP with the kiwi-encoded canvas; decode with the embedded schema) for the shapes, paths, text and frames Draft supports; anything unsupported is reported, not dropped silently.
- Export: SVG, PNG (1x/2x/3x), PDF, DXF (R12/2000 for laser and CAM), and an Alloy sketch for Forge.
- Copy as SVG to the clipboard.

## AI and agents
- Same tool registry, BYOK adapters and MCP server pattern as Forge (`packages/core`, `packages/mcp`).
- Words in any numeric field; prompt bar for edits ("make the ring band 2 mm and add 12 milgrain beads around the bezel").
- Locks respected by the agent, as in Forge.

## Touch and mobile
- Stylus-first drawing with palm rejection; pressure for the pencil tool.
- Radial quick menu on long-press; two-finger tap undo, three-finger tap redo.
- All panels as bottom sheets under 900px wide; precision entry pad for numbers and units.

## v1 cut (first PRs)
1. Canvas, rulers, guides, grids, units, themes.
2. Layers panel, frames, pages, select/transform with smart guides and measurement.
3. Shapes, pen, pencil, text; fills and strokes.
4. Snaps, precision entry, align/distribute, boolean ops, offset/trim/fillet.
5. SVG and DXF import/export; PNG/PDF export.
6. Parameters, constraints and dimensions.
7. `.fig` import; AI prompt bar and MCP tools.
8. Export to Forge as a sketch profile.
