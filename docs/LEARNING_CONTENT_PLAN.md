# Alloy learning content system

## Purpose and non-negotiable boundary

Alloy is asking a phone to guide a user around a machine that gets hot, moves
quickly and can fail in ways that waste material or damage a printer. Learning
content is therefore a **safety and confidence system**, not a marketing layer.
It must make the next safe action obvious, explain unfamiliar vocabulary at the
moment it is needed, and stop short of diagnosing hardware remotely.

The product promise is not that a beginner can "fix anything". It is that a
beginner can learn what they are seeing, make low-risk checks, preserve useful
evidence, and know when to stop and use Bambu Lab's current official support
instructions. No lesson, card, or assistant response may imply that Alloy has
verified a printer, filament, configuration, or physical job unless the
relevant runtime gate has actually passed.

This plan covers:

- first-run onboarding and progressive learning;
- an illustrated, searchable FAQ and fault triage library;
- a printable/offline cheat sheet;
- contextual help in Import, Prepare, Slice, Inspect, Export and Monitor; and
- the content contract and review process required to ship it safely.

It does **not** authorize sending an Alloy-generated job, overriding an
interlock, using an unverified profile, bypassing certificate checks, editing
firmware, or changing a printer's electrical parts. Those remain product
gates, not educational choices.

## Experience principles

1. **Teach only the decision in front of the user.** Import needs a two-minute
   explanation of file types, not a course on acceleration.
2. **Show the object, then name it.** A small animated 3D plate, nozzle, spool,
   layer stack, or failure thumbnail precedes technical terminology.
3. **Separate learning from authority.** "Likely cause" is never "diagnosis";
   "safe check" is never "repair." Use confidence and scope labels.
4. **Use progressive disclosure.** A calm primary step, then _Why this
   matters_, then _Advanced details_. New makers do not see a wall of warnings.
5. **Show live status without inventing it.** Temperature, state, material,
   printer identity, toolpath and profile state are always labelled `Live`,
   `Estimated`, `Imported`, or `Not verified`.
6. **Do not hide safety behind the aesthetic.** The visual language can be
   atmospheric, but warning contrast, text labels, accessible alternatives,
   and explicit blockers always win.

## Information architecture

Learning has one home, plus contextual entry points. It must not become a
second maze inside the app.

```
Learn
├── Start here
│   ├── Your first print
│   ├── The printer, plate and filament
│   ├── Reading a model and layers
│   └── Before you start checklist
├── Make a print
│   ├── Import a model
│   ├── Place and orient it
│   ├── Choose a material/profile
│   ├── Supports and brim
│   ├── Read estimates
│   └── Inspect before export/send
├── During a print
│   ├── Reading printer state
│   ├── First-layer watch
│   ├── Pause, cancel and recovery
│   └── What Alloy can and cannot control
├── Fix a symptom
│   ├── Nothing sticks
│   ├── Stringing / blobs
│   ├── Warping / corners lifting
│   ├── Layer shift / collision
│   ├── Under-extrusion / runout
│   ├── Nozzle or plate temperature issue
│   └── Connection, pairing or send issue
├── Materials
│   ├── PLA, PETG, TPU and supported families
│   ├── Dryness, storage and spool handling
│   └── Material compatibility is profile-specific
└── Reference
    ├── Glossary
    ├── Quick cheat sheet
    ├── Safety and maintenance boundaries
    └── Official support links
```

`Learn` is reachable from the home navigation and from a persistent `?` action
in task screens. Contextual help opens a bottom sheet with one relevant card,
not a full-screen interruption. The sheet includes `Show me`, `Why?`, and
`Open guide`; it must always have an obvious close action.

### Required contextual triggers

| Surface | Trigger | First card | Safe action |
| --- | --- | --- | --- |
| Import | unsupported/large/failed file | “What did you import?” | explain format, retain source URI, retry/import another copy |
| Prepare | part is off plate or overlaps | “Make the plate printable” | centre, arrange, undo; never silently move a user’s model |
| Prepare | part has a large flat overhang | “Will this need support?” | preview/estimate, explain uncertainty, do not promise success |
| Material | material/profile mismatch | “This setup is not verified” | change material/profile or save as draft |
| Slice | slicing begins | “What slicing creates” | show layers/toolpath; no `Print` language before validation |
| Inspect | generated warning | “Review this before exporting” | jump to the affected layer or configuration |
| Export/send | physical gate absent | “Why printing is unavailable” | explain exact missing evidence; export only if structurally permitted |
| Monitor | telemetry stale/error | “Printer state is not confirmed” | avoid local-state claims, show reconnect and official support path |

## Onboarding: the first 12 minutes

Onboarding is resumable, local-first and opt-in after the welcome screen. A
user who already knows printing can choose **“I know the basics”** and gets a
brief setup checklist; they must not be forced through beginner lessons.

### Flow

1. **Welcome — “A workshop in your pocket.”**
   - 3D micro-scene: a small plate appears in a quiet empty workshop, then a
     bright filament line traces a simple Alloy mark once.
   - Copy: “Bring in a model, prepare it on a real plate, inspect every layer.
     Alloy keeps physical printing locked until the job and printer are ready.”
   - Actions: `Start safely`, `I know the basics`, `Explore a sample`.

2. **What Alloy can do today.**
   - Four stateful tiles, not promises: `Import & prepare`, `Slice & inspect`,
     `Export`, `Print from Alloy`.
   - Each is driven by capability state. A locked Print tile reads, for example,
     “Not available: A1 Mini profile verification is still required.”
   - Copy: “A lock is information, not a dead end. We’ll show what is missing.”

3. **Meet the build plate.**
   - Interactive 3D lesson: one safe sample cube starts off-centre; user drags
     it into the highlighted printable area. A ghosted no-go boundary and
     simple `Undo` make success legible.
   - Vocabulary shown only after interaction: `build plate`, `clearance`,
     `orientation`.
   - Completion criterion: model is inside the plate, not merely the user
     pressing Next.

4. **Material without jargon.**
   - Visual spool carousel of only profiles the chosen printer and mounted
     setup actually support. Each uses a material colour, an icon and one
     sentence: “PLA: easiest first material; keep it dry and use a clean plate.”
   - Copy: “Material name alone is not enough. The selected printer, nozzle,
     profile and filament must agree.”
   - Never claim a third-party spool is compatible simply because a material
     family shares a name.

5. **Layers make the object.**
   - Animated cross-section of the sample cube: a single horizontal layer,
     then the stack, then a line travelling across it. Scrubbing is optional.
   - Copy: “A slicer turns geometry into motion. Inspect the motion before you
     trust it.”

6. **Your first inspection.**
   - Highlight: first layer, thin feature, overhang, and estimated material.
   - Copy: “Estimates are planning aids. They are not guarantees, and cleanup,
     purge and failed-print waste may not be included.”

7. **Safe first-print checklist.**
   - `Known material`, `clean plate`, `correct nozzle/profile`, `model within
     bounds`, `reviewed first layers`, `someone available for the first layer`.
   - The final item is an acknowledgement, not a claim that Alloy remotely
     observed the physical condition.

8. **Land in a useful place.**
   - New user: `Library` with the completed sample and one `Import model` call
     to action.
   - Existing user: return to the interrupted task, with a small “Continue
     learning” chip rather than restarting onboarding.

### Onboarding microcopy examples

- **Empty library:** “Every print starts with a model. Import an STL or 3MF,
  or explore a safe sample first.”
- **Bounds warning:** “Part of this model sits outside the printable area. Move
  it inside, split it, or choose a compatible plate—don’t slice it as-is.”
- **Slice complete:** “Toolpath ready to inspect. Start with layer 1, then scan
  bridges, thin features and any highlighted warnings.”
- **Gated send:** “This job can be exported, but Alloy cannot verify it for
  physical printing on this printer yet.”

## Visually rich lesson and FAQ format

Every guide follows a consistent reading rhythm:

1. **Observe** — a 3–6 second loop or still image showing the normal/failure
   state;
2. **Name it** — plain-language symptom title;
3. **Understand** — one short causal explanation with an uncertainty label;
4. **Do the safe next thing** — at most three reversible checks;
5. **Escalate** — stop criteria, saved evidence and official-support route.

### Art direction for reusable visual assets

- Use Alloy-owned, original 3D illustrations: an A1-style printer silhouette,
  nozzle, plate, extrusion line, spool, layer stack and neutral model forms.
  Do not copy Bambu Lab UI, photography, product marks or proprietary manuals.
- Maintain the Alloy scene language: smooth field, local bloom/grid around the
  active physical object, and bright filament colour as the active signal.
  Never use bloom alone to convey a warning.
- Give each diagnosis a normal/failure paired asset with the same camera and
  lighting. Example: `first_layer_normal` / `first_layer_poor_adhesion`.
- Generate source artwork at 2x Android target density with transparent
  background where possible; derive WebP/AVIF assets and retain the source
  prompt, source file, licence/ownership record and alt text.
- Motion must be optional (`Reduce motion`), pause when offscreen, and have a
  static caption. No strobing, rapid extrusion motion, or colour-only state.

### Asset brief examples for an image-generation/3D-art pass

| Asset | Prompt direction | Must show | Must not imply |
| --- | --- | --- | --- |
| First layer pair | original isometric plate, warm filament, quiet field | smooth adhered line vs loose/skipping line | that a remote image confirms the user’s issue |
| Overhang pair | floating neutral arch and a sliced layer view | unsupported underside vs support preview | supports guarantee a clean surface |
| Spool handling | clean spool on holder, dry-box motif, readable material tag | spool path and material identity | all spool shapes/materials fit every printer |
| Collision warning | nozzle envelope above a lifted/warped part | unsafe proximity and stop state | touching/moving parts while hot is safe |
| Connection card | local network pulses between phone and printer | stale vs fresh telemetry state | a successful request means the printer acted |

## Troubleshooting: decision trees and hard stops

Content must use symptoms rather than uncertain diagnoses. Each branch starts
with **whether a print is currently running**; its severity is visible before
the user opens detailed advice.

### Universal stop card

Display this before all equipment troubleshooting:

> **Stop and supervise the printer** if you smell burning, see smoke, hear
> grinding/collision, see loose wires, observe a damaged cable, a molten or
> leaking hotend assembly, or a print coming loose near moving parts. Use the
> printer's normal cancel/emergency procedure only if it is safe to reach.
> Do not continue through an app tutorial. Power, heat and moving hardware need
> the manufacturer’s current safety guidance or qualified service.

All hard-stop cards offer `I’ve stopped the job`, `Save job details`, and
`Open official support`. They never prescribe dismantling a hotend, mains work,
firmware flashing, bypassing thermal protection, manual force/motion, or
using tools near a hot moving printer.

### Decision tree: “My print is not sticking”

```
Start
 ├─ Is the print lifting, dragging, or being struck by the nozzle now?
 │   ├─ Yes → Pause/cancel according to the printer's normal controls; supervise.
 │   │         Do not touch the plate/nozzle while hot or moving. Save a photo.
 │   └─ No → Continue.
 ├─ Is the symptom visible in Alloy's layer-1 preview?
 │   ├─ Yes → Review placement, first-layer geometry and selected profile.
 │   │         If profile is unverified, do not export/send as validated.
 │   └─ No / unknown → Continue.
 ├─ Can you confirm the correct plate, material and profile are selected?
 │   ├─ No → Stop configuration changes; select a documented compatible setup.
 │   └─ Yes → Perform only manufacturer-approved plate cleaning/prep guidance.
 └─ Still failing after one controlled retry? → Preserve photo, model/profile,
    filament and printer state; link to official troubleshooting/support.
```

### Decision tree: “The nozzle may hit the model / I hear a collision”

```
Start
 ├─ Collision, grinding, sparks, smoke, or a loose part near moving axes?
 │   └─ Yes → Immediate hard stop. Do not jog axes or pull the part free.
 ├─ Is this only a preview concern?
 │   ├─ Yes → Inspect the suspect layer; reposition/orient or remove the part.
 │   └─ No → Keep print paused/cancelled and use manufacturer service guidance.
 └─ After any collision → Alloy marks the job as needing review; it may not
    offer resume merely because its local state says it is possible.
```

### Decision tree: “Filament is not coming out / looks thin”

```
Start
 ├─ Is the printer reporting a fault, making grinding sounds, or is the hotend
 │  visibly leaking? → Stop; capture the exact printer message and escalate.
 ├─ Is the selected material/profile different from the loaded spool?
 │  ├─ Yes → Do not continue this job; correct the setup and re-slice.
 │  └─ No → Check only visible, cold, external spool-path obstructions allowed
 │           by official documentation. Do not disassemble the hotend.
 └─ Still unclear → save evidence and open official support.
```

### Decision tree: “Alloy cannot connect or send”

```
Start
 ├─ Does Alloy show stale/unknown printer state? → Treat printer state as
 │  unknown, not offline or idle. Do not resend automatically.
 ├─ Is the printer identity/certificate/profile gate unresolved? → Explain the
 │  exact blocker. Never offer 'ignore certificate' or 'send anyway'.
 ├─ Is an upload/start checkpoint incomplete? → Enter Recovery Required;
 │  require fresh telemetry and explicit user review before any next action.
 └─ Otherwise → keep/export the artifact, redact diagnostics, retry connection
    only after the user confirms a private reachable network.
```

### Severity model

| Level | Presentation | Examples | Allowed guidance |
| --- | --- | --- | --- |
| Learn | neutral | “What is a brim?” | explain, preview, link to contextual action |
| Review | amber with text | unsupported overhang, stale estimate | review config; export may remain allowed |
| Stop | red with explicit icon and copy | collision, smoke, hotend leak, loose wiring | pause/cancel if safely reachable; official support |
| Gate | locked/neutral | unverified profile, pin mismatch, recovery | explain evidence required; no bypass |

## Cheat sheet: “Your first print, at a glance”

Make this available in-app, offline as a screen-reader-friendly page, and as a
single printable A4/Letter PDF. It is a reference, not a substitute for the
printer manual.

**Front: before / during / after**

| Before | During the first layer | After |
| --- | --- | --- |
| Confirm model, printer, plate, nozzle, material and profile agree. | Stay nearby. Look for a continuous line attached to the plate and no contact/collision. | Let the plate cool; use manufacturer-approved removal guidance. |
| Keep the model inside the printable area and inspect warnings. | If anything sounds wrong, smells burnt, smokes, collides or comes loose: stop and supervise. | Record result and actual material if known; learn from the next change. |
| Treat time and filament as estimates, not guarantees. | Never reach into moving/hot hardware. | Do not reuse a questionable job without reviewing its state. |

**Back: vocabulary and signals**

- **Model:** the shape you want to make.
- **Plate:** the surface it is built on.
- **Profile:** a documented group of printer/material settings.
- **Slice:** turn a model into layer-by-layer motion.
- **Toolpath:** that planned motion; inspect it before export/send.
- **Support:** temporary printed structure; useful, but costs material and may
  mark surfaces.
- **Brim:** extra first-layer material around a part to help it stay down.
- **Estimated:** calculated planning value, not a physical measurement.
- **Live:** recent data reported by a paired printer.
- **Not verified:** Alloy cannot safely make the next physical claim/action.

Include a compact visual key for `Learn`, `Review`, `Stop`, and `Gate`; every
icon is paired with a written label.

## Content data contract

Store lessons as versioned, local JSON (or equivalent typed resources), with
remote updates only after signature/version validation. The app must remain
usable offline with the last valid bundled set. Product state determines what
actions are available; content never overrides product gates.

```json
{
  "schemaVersion": 1,
  "id": "troubleshoot.first-layer.adhesion",
  "revision": "2026-09-28",
  "locale": "en-IN",
  "kind": "decision_tree",
  "title": "My print is not sticking",
  "summary": "Use a safe check before changing the model or printer.",
  "audience": ["beginner", "intermediate"],
  "tags": ["first-layer", "adhesion", "pla"],
  "capabilityRequirements": [],
  "disclosure": {
    "claimLevel": "general_guidance",
    "lastSafetyReview": "2026-09-28",
    "officialSupportUrl": "https://support.example.invalid/replace",
    "notMedicalOrElectricalAdvice": true
  },
  "visual": {
    "heroAsset": "learning/adhesion/hero-paired.webp",
    "alt": "Two first-layer lines: one smoothly attached to a plate, one loose and irregular.",
    "reducedMotionAsset": "learning/adhesion/hero-paired-static.webp",
    "credit": "Alloy original illustration"
  },
  "steps": [
    {
      "id": "live-risk",
      "prompt": "Is the print lifting, dragging, or being struck by the nozzle now?",
      "answers": [
        {
          "label": "Yes or I am unsure",
          "severity": "stop",
          "body": "Pause or cancel using normal printer controls only if it is safe to reach. Do not touch hot or moving hardware.",
          "actions": ["save_evidence", "open_official_support"]
        },
        {"label": "No", "next": "preview-check"}
      ]
    }
  ],
  "analytics": {
    "event": "learning_opened",
    "collectContentOnly": true,
    "forbiddenFields": ["printer_password", "certificate", "model_geometry"]
  }
}
```

Additional implementation resources:

- `learning-index.json`: titles, tags, related guide IDs, locale and revision.
- `glossary.json`: plain term, short definition, spoken pronunciation hint,
  related visual and context keys.
- `printer-compatibility.json`: model/profile/material capability facts with a
  source/version/date; **never infer compatibility from a name**.
- `content-safety-rules.json`: banned prescriptions and mandatory hard-stop
  tokens, checked in CI against content text.
- `learning-progress.json`: local completion/checklist state; no safety
  acknowledgement should silently expire or grant a physical-print capability.

## Editorial and safety workflow

Every lesson needs four independent passes before release:

1. **Learning designer:** writes task, prerequisite, vocabulary, success state
   and the shortest viable path.
2. **Visual director/artist:** creates the normal/failure pair, motion and alt
   text against the Alloy asset brief.
3. **Safety and product reviewer:** verifies each action is reversible,
   capability-gated, not a remote diagnosis, and consistent with current
   manufacturer documentation. They mark uncertain recommendations as such.
4. **Editor:** makes the text short, culturally clear, translatable and
   consistent with the UI's terminology.

The final UI integrator receives a signed content bundle, asset manifest,
layout states, alt text, reduced-motion assets, analytics event names, and
test cases. They cannot replace a gated action with an enabled button.

### Required review tests

- A first-time user can identify `model`, `plate`, `material`, `profile`,
  `slice` and `not verified` after onboarding, without reading a manual.
- A user on a small Android device can finish every guide at 200% text scale.
- Screen reader focus order announces severity before action; images have useful
  alt text; motion respects system settings.
- No article tells a user to touch a hot plate/nozzle, force an axis, modify
  mains/electronics, bypass a safety/certificate/firmware check, or continue
  after a collision/smoke/heat incident.
- A stale printer state never renders as `idle`, `complete` or `safe`.
- All printer/material compatibility claims resolve to a dated source record.
- Content remains coherent when `Print from Alloy` is unavailable.

## Release acceptance for the learning experience

The learning system is ready for a public beta only when:

- all first-run and critical fault flows work offline;
- every visual has alt text, reduced-motion treatment and ownership/licence
  provenance;
- beginner testing demonstrates successful completion of the safe sample flow;
- safety review has approved each `Stop` and `Gate` branch;
- official support links are reviewed for each supported printer family;
- the UI exposes only real runtime capabilities; and
- no visual or copy implies Bambu compatibility, print success, material
  support, or physical printer control beyond verified evidence.

This turns Alloy's educational layer into a differentiator without using it to
paper over the hard engineering gates still required for a production slicer.
