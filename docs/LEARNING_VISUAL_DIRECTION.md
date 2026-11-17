# Alloy learning visual direction and asset plan

Status: proposed content-and-art direction contract. This document defines
learning visuals for the Android onboarding, beginner cheat sheet, contextual
troubleshooting, and FAQ. It is intentionally separate from the printer,
profile, and slicer validation gates: a beautiful illustration must never make
an unverified operation appear safe.

## The job of learning in Alloy

The learning system is not a manual hidden behind a question-mark icon. It
must help a first-time owner make one safe decision at the exact moment that
decision is needed, then get out of the way.

Every lesson has one of three jobs:

1. **Orient:** explain where the user is and what will happen next.
2. **Decide:** clarify a real choice, such as material, orientation, supports,
   or plate preparation.
3. **Recover:** describe a symptom, the safe first action, and when to stop
   and seek printer-specific help.

The visual anchor remains one object in a generous, boundary-less studio. The
ground texture appears only inside a soft bloom around the subject; the rest of
the canvas is clean. Visuals support comprehension, not a decorative second
product.

## Non-negotiable safety and truth rules

- A rendered A1 Mini, plate, spool, nozzle, or finished part is an
  illustration unless it comes from an Alloy-owned or appropriately licensed
  geometric source. Do not generate a plausible-looking Bambu machine and
  label it as factual.
- Generated images never communicate temperature, load-bearing safety,
  compatibility, maintenance intervals, print result guarantees, or fault
  diagnosis by themselves. Those claims come from a versioned, source-backed
  content record and the active printer/profile capability data.
- Material lessons read their name, restrictions, and drying/handling advice
  from the current profile. A static visual may show an abstract spool and
  colour, but must not hard-code a compatibility claim.
- If the active printer or nozzle does not support a lesson's recommendation,
  replace the action with `Not available for this setup` and link to the
  supported alternative.
- Use clear escalation language for fire, hot surfaces, moving axes,
  electrical faults, filament jams, and unattended printing. Never put a
  recovery animation ahead of the stop/inspect instruction.
- Images must not copy Bambu Lab marks, proprietary printer UI, luxury-brand
  styling, or another product's logo. Use the Alloy logo supplied by the
  product, cropped to its circle rather than shrunk with excessive padding.

## Visual grammar

### Composition

- Canvas: a calm off-white or near-black field, with no room corners,
  gradients that imply a box, or texture across the full scene.
- Subject: one central physical thing at a time: a plate, a part, a spool, a
  hot end, or a simplified printer silhouette.
- Bloom-ground: a radial, feathered contact field centred on the object. It
  contains the fine grid/PEI texture at 80% opacity near the centre, fading to
  0% over 20--28% of the shortest canvas dimension. The texture must dissolve,
  never terminate in a hard circle.
- Callouts: two to five small labels sit on an imaginary circle around the
  subject, not in a rectangular inspector. Leader lines are optional and only
  when the target is ambiguous. Avoid labels over the object or the bloom's
  high-detail area.
- Motion: model cards may float and rotate slowly (about 6--10 degrees over
  8--12 seconds), but instructional diagrams pause their decisive state.
  Honor Reduce motion by showing the final state without auto-rotation.

### Colour and depth

Use material-led colour only for active information. The default is ink +
neutral surface; vivid colour is reserved for filament, a selected path, a
warning, or a successful state.

| Token role | Light mode | Dark mode | Use |
|---|---|---|---|
| Canvas | warm white | near-black | uninterrupted surrounding space |
| Object neutral | graphite / soft cool gray | pale ceramic gray | printer, tools, inactive geometry |
| Active filament | bright safety orange | warm luminous orange | extrusion, current material, progress |
| Success | restrained mint | soft mint | confirmed, not a primary brand wash |
| Caution | amber | amber | review before continuing |
| Danger | accessible vermilion | accessible coral-red | stop and inspect only |
| Focus ring | high-contrast Alloy accent | high-contrast Alloy accent | keyboard/switch focus; never rely on glow alone |

Do not use grain across the application. A local bloom may contain subtle
surface detail and a restrained radial light spill. Glows must be subdued:
they express focus, not a neon arcade interface.

### Navigation shell inside learning

The curved navigation treatment is a shell, not a lesson diagram. The two
arcs are parallel sections of the same ellipse and share the same centre,
radii, and side inset. The upper arc holds the contextual action/title; the
lower arc holds persistent icon destinations. Circular side controls align to
the top and bottom edges of the *upper* arc, not above it. Icons and labels
follow the arc tangent only where doing so remains readable; never rotate text
beyond roughly 12 degrees on a phone.

For teaching, bottom navigation must retain an accessible name at all times.
The visual label may appear on press-and-hold or a slide across icons, but
TalkBack, keyboard focus, and the expanded accessibility mode always expose
the destination name and selected state.

## Asset strategy: generate atmosphere, render facts

Use native vector icons, runtime geometry, and annotated screenshots for facts
that must remain exact. Use generated raster assets only for atmospheric,
abstract, or tactile explanatory scenes.

| Kind | Preferred implementation | Examples |
|---|---|---|
| Exact printer, plate, nozzle, bed limits | Alloy GLES or licensed source geometry | A1 Mini orientation, plate bounds, head clearance |
| Exact UI action | live UI / controlled screenshot / vector overlay | tap Slice, review warning, open recipe |
| Reusable metaphor | SVG/vector or procedural shader | ripple, layer stack, flow arrows, focus halo |
| Abstract physical intuition | AI-generated raster, then human-reviewed | moisture as suspended droplets, a clean first layer, a soft material cross-section |
| Troubleshooting evidence | real, consented photos plus vector annotations | stringing, warped edge, clogged nozzle symptom |

Generated assets are source files, not implementation outputs. Keep their
prompt, seed if available, reviewer, licence/provenance, and exported file hash
in the content manifest. Preserve the editable master and export WebP/AVIF for
Android density variants only after approval.

## Asset inventory

All filenames below are proposed. `scene-*` is a full-bleed teaching visual;
`diagram-*` is a composable transparent asset; `icon-*` must be a native
vector, not a generated image.

| ID / file | Surface | What it teaches | Implementation | Required states |
|---|---|---|---|---|
| `scene-welcome-studio` | onboarding 1 | Alloy prepares a real object, not a generic dashboard | runtime model + procedural bloom | light/dark, static/reduced motion |
| `scene-first-print-loop` | onboarding 2 | Import → Prepare → Slice → Inspect → Print is a loop of accountable steps | vector/raster hybrid | progress highlighted per step |
| `scene-printer-pairing` | onboarding 3 | pairing is local and deliberate | licensed/runtime printer silhouette + vector LAN ripple | discovering, paired, unavailable |
| `scene-material-choice` | onboarding 4 | choose material before visual appearance | generated spool atmosphere + data-driven material chips | compatible/incompatible/unknown |
| `scene-bed-and-orientation` | onboarding 5 | flat, stable contact often reduces risk/material | runtime geometry | good/bad orientation; no unverifiable claim |
| `scene-first-layer` | onboarding 6 | first layer is the moment to watch | real reference photo or reviewed illustration | healthy / stop-and-check |
| `scene-inspect-before-print` | onboarding 7 | inspect toolpaths and estimates before sending | real toolpath screenshot with vector overlays | no warning / warning / blocked |
| `scene-print-complete` | onboarding 8 | remove safely, record result, maintain printer | generated abstract finish + native icons | completed / cool-down / follow-up |
| `diagram-layer-stack` | cheat sheet | layer height trades surface detail against time | vector/3D procedural | coarse / standard / fine |
| `diagram-infill` | cheat sheet | infill supports shell; it is not visible strength magic | vector | sparse / medium / dense |
| `diagram-supports` | cheat sheet | supports are removable scaffolding and have a surface cost | runtime simple geometry | off / required / review warning |
| `diagram-orientation` | cheat sheet | orientation changes strength direction and support need | vector with explicit direction arrows | three clear alternatives |
| `diagram-filament-path` | cheat sheet | spool → feeder → hot end → part | vector | normal, loading, jam stop |
| `diagram-temperature` | cheat sheet | nozzle/plate are hot; temperatures are profile data | vector/thermal gradient | normal / hot warning |
| `diagram-maintenance-cycle` | cheat sheet | inspect, clean, maintain, record | vector | fresh / due / overdue |
| `photo-stringing` | troubleshooting | fine hairs between features | reviewed real photo | symptom + non-diagnostic label |
| `photo-warping` | troubleshooting | lifted corners and loss of adhesion | reviewed real photo | symptom + stop/continue decision |
| `photo-under-extrusion` | troubleshooting | gaps/weak lines are a symptom, not a one-click diagnosis | reviewed real photo | inspect workflow |
| `photo-layer-shift` | troubleshooting | misaligned layers require a stop-and-check path | reviewed real photo | urgent/stop |
| `photo-elephant-foot` | troubleshooting | widened base appearance | reviewed real photo | mild / review settings |
| `anim-filament-sweep` | slicing/loading | slice progress has a physical metaphor | layered asset composition, not AI video | normal / reduced motion static |
| `anim-touch-ripple` | secondary loading | a hot end touching a plate creates a restrained local ripple | procedural/vector | normal / reduced motion static |

### Asset dimensions and packaging

- Full scenes: author at `1440 × 1800` minimum (portrait 4:5) with a centred
  `1080 × 1440` safe composition. Preserve 96 px clear margin around all
  callouts at 1080 px. Export `webp` at 1×, 1.5×, and 2× only after Android
  memory testing.
- Composable diagrams: transparent `1024 × 1024` master plus a vector source.
  Avoid baked text; localised labels are native text overlays.
- Symptom photos: `1600 × 1200` minimum, neutral colour treatment, no creator
  watermark, no unlicensed models, no identifying background content.
- Animation: use Lottie/vector or native composition where possible. Cap a
  looping asset at 2 MB and 30 fps. Do not autoplay more than one animation on
  a screen, and do not run continuous animation while a TalkBack focus or
  system-dialog interaction is active.

## Image-generation briefs

Prompts establish composition only. They must be reviewed by a human who knows
the associated learning claim, then cleaned of accidental pseudo-text, unsafe
geometry, and misleading branding.

### A. Welcome studio (`scene-welcome-studio`)

> Android app instructional hero, one small unbranded desktop 3D-printer
> build plate with a single simple calibration cube floating a few millimetres
> above it, viewed in a refined isometric three-quarter angle. Boundary-less
> warm white studio. A delicate PEI-like grid exists only in a soft circular
> bloom beneath the cube and fades seamlessly to white. Cool graphite plate,
> bright optimistic orange calibration cube, calm soft shadow, premium
> industrial design, generous negative space, no text, no logos, no UI, no
> watermark, no full-room background, no grain.

### B. Material choice (`scene-material-choice`)

> Tactile abstract 3D still life for a mobile 3D-printing lesson: one generic
> filament spool standing upright, a single bright orange filament strand
> travelling toward a simplified hot end, on a local circular bloom of faint
> technical grid that disappears into a boundary-less near-black background.
> Matte ceramic and graphite materials, precise studio lighting, optimistic
> but not neon, one subject, no labels, no letters, no logo, no printer brand,
> no watermark, no noisy texture.

### C. First-layer attention (`scene-first-layer`)

> Minimal close isometric illustration of a single clean orange first-layer
> line being laid onto a dark textured build plate by an unbranded 3D-printer
> nozzle. The nozzle and line are centred within a subtle radial pool of light;
> the rest of the frame is empty near-black. Rounded, refined industrial 3D
> rendering, believable extrusion width, no sparks, no laser, no text, no
> logos, no watermark, no full printer.

### D. Safe remove / cool-down (`scene-print-complete`)

> Calm mobile instructional illustration: a finished small geometric object
> resting on a generic square build plate, a soft cool mint confirmation halo
> beneath it, a detached pair of generic protective tongs resting outside the
> active area. Boundary-less pale studio, restrained shadow, no hands, no
> text, no logos, no watermark, no fire or hazard imagery.

### E. Abstract learning chapter cover (`scene-chapter-*`)

> Editorial, friendly, unbranded 3D-printing learning-cover image with one
> [SUBJECT: layer stack / support scaffold / filament path / maintenance tool]
> floating in a large empty [LIGHT OR DARK] studio. A local radial bloom reveals
> a quiet technical ground texture immediately beneath the object and dissolves
> into a perfectly clean background. Graphite, ceramic, and one vivid safety
> orange accent; physically plausible but simplified; no text, logos,
> watermarks, busy workshop, grain, or photoreal people.

### Negative prompt shared by every generated asset

> words, glyphs, logo, brand mark, watermark, duplicate object, multiple
> nozzles, exposed wiring, sparks, flames, unsafe machine operation, busy
> desktop, hard-edged spotlight circle, full-frame grain, warped geometry,
> impossible filament path, pseudo technical diagram, cropped focal object.

## The loading pair

The loading motion is an exception: it represents time, not instruction.

### Primary: filament sweep

- Place it near the bottom safe area, centred horizontally, with at least
  24 dp above gesture navigation and 16 dp outside the bar's sides.
- The hot end travels **left to right**. Its nozzle tip meets the top edge of
  the progress bar outline at the progress boundary; it does not overlap the
  filled filament, leave a square seam, or float above/below the bar.
- The orange filament is a single rounded extrusion with a subtle contact
  glow. It fills exactly once per task; do not loop from full to empty.
- The full hot-end image remains within the viewport at both endpoints.
- Centre copy rotates calmly among useful statuses: `Reading the model`,
  `Checking the build plate`, `Planning each layer`, `Tracing the toolpath`,
  and `Getting your print ready`. Statuses must come from real stages where
  available; otherwise use `Working on your model` rather than fictional
  precision.

### Secondary: touch ripple

- A hot end makes a short vertical bob. A ripple emits only when its tip
  reaches the lowest contact position, expanding once then fading.
- The ripple is local, low-contrast, and never competes with an error or
  action. It is not a global grain field or a laser effect.
- Reduced motion shows a still hot end above a subtle concentric contact mark.

## Learning surfaces and content pattern

### Onboarding: eight cards, not a long tour

1. Welcome to the workshop.
2. Meet your printer and safe pairing boundary.
3. Choose material from the active compatible profile.
4. Put a model on a plate.
5. Orient for contact and review supports.
6. Slice and inspect—not just "generate".
7. Start only after the printer confirms readiness.
8. Watch the first layer; recover or record the result.

Each card uses one visual, a 5--12 word heading, no more than 35 words of
body copy, one primary action, and an always-visible `Skip for now`. Cards
should branch: an experienced user can select `I have printed before` and see
only pairing, safety boundaries, and Alloy-specific controls.

### Beginner cheat sheet: six expandable concepts

`Model`, `Plate`, `Filament`, `Layers`, `Supports`, and `First layer` each
get a short definition, one diagram, one practical consequence, and a
"remember this" line. Keep the first view below 60 words. An optional
"why?" reveals deeper material.

### Troubleshooting: symptom first, never diagnosis first

The initial choices are observable: `Nothing is coming out`, `The print has
lifted`, `Lines look thin or gappy`, `The layers moved sideways`, `The surface
is messy`, and `The printer shows an alert`. Each starts with an image/photo,
then:

1. urgency (`Stop now`, `Pause and inspect`, or `Finish then review`),
2. the safest first check,
3. one reversible action,
4. a link to the printer/model-specific procedure,
5. a record of what the user tried.

Avoid confident "fixes" based on an image. A symptom can have many causes.

### FAQ: visual answer cards, no wall of text

FAQ entries use a 16:9 detail visual or a small diagram at the top, a plain
answer in the first sentence, and a `Show details` disclosure. Examples:
"Why do I need supports?", "Why is my estimate different?", "Can I mix
materials?", "What should I watch during the first layer?", and "When should
I clean or service the printer?" Search includes synonyms and routes into the
same canonical tutorial rather than duplicating advice.

## Accessibility and localisation acceptance criteria

- Every visual has a concise, meaningful text equivalent. Decorative bloom,
  shadow, and float motion have no redundant description.
- Normal text meets WCAG AA contrast; critical status and small labels target
  AAA where feasible. Colour never conveys a warning or selected state alone.
- Minimum 48 dp interactive targets; never put a critical action exclusively
  on a curved/rotated affordance.
- Respect Android font scaling to 200%, display scaling, TalkBack traversal,
  switch access, and Reduce motion. Test with animation disabled and with a
  screen reader before visual sign-off.
- All labels are native text. Avoid baked copy so languages that expand by 30%
  remain readable. Do not use an image of a hand gesture as the only instruction.
- Avoid red/green-only comparisons. Use pattern, outline, labels, and status
  icon together.

## Review workflow and definition of done

1. Content planner writes a source-backed learning card and labels its risk:
   informational, configuration guidance, or safety-critical.
2. Art director selects runtime/vector/photo/generated treatment using this
   document. The factual subject is checked before generation.
3. Image generation produces exploratory masters. A human removes artifacts;
   no generated output enters the app unreviewed.
4. Content editor verifies plain language, exact claim scope, translations,
   and that the visual does not over-promise.
5. A maker-safety reviewer verifies actions, escalation paths, and printer
   capability conditions.
6. UI implementer assembles the approved manifest in the app with accessible
   fallback copy and reduced-motion states.
7. Test with at least five first-time users and three experienced owners:
   participants must identify the safe first action in each troubleshooting
   scenario and complete one import-to-inspect path without coaching.

An asset is accepted only with: source file, export, prompt or source-photo
licence, manifest metadata, text equivalent, light/dark check, reduced-motion
state, 200% font-scale check, and an approved linked content card.

## What "10/10" means here

It does not mean the most animated screen. It means a new owner can understand
what the app knows, what it does not know, and the next safe action without
reading a manual; an expert can skip the teaching; and neither is misled by
beautiful imagery into trusting an unvalidated print workflow.
