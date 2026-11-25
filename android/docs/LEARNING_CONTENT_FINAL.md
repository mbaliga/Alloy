# Alloy learning content — initial release catalogue

**Status:** final editorial source for the first learning bundle. This is
content, not a printer qualification or a promise that a control is available.

**Reading level:** eighth grade. **Default locale:** `en-IN`.

## Editorial contract

Every fact-sensitive card must show its scope in the detail view:

- **Source:** the source name and a dated URL or profile record.
- **Applies to:** the selected printer, nozzle, plate, material, and feed route.
- **Alloy state:** `Qualified`, `Supported by manufacturer`, `Not verified by
  Alloy`, `Not recommended`, or `Unknown`.
- **Last reviewed:** content revision date.

Never use a green check to merge those states. A profile, illustration, or
successful import does not prove that a printer will accept or finish a job.
Where the app has no fresh telemetry, say **Printer state not confirmed**.

All cards use this structure: **See it → Know it → Do this → Stop when → Learn
more**. A symptom is not a diagnosis. Do not tell a user to touch hot or moving
hardware, force an axis, open electrical parts, bypass a safety check, or send
a job despite a gate.

## Start here

### 1. Welcome: a workshop in your pocket

**Body:** Import a model, place it on a real plate, inspect the planned layers,
then export or print only when this printer and job are ready.

**Primary action:** `Start safely`

**Secondary actions:** `Explore a sample` · `I know the basics`

**Truth line:** `Print from Alloy is shown only when the current printer, recipe,
output, and connection pass their own checks.`

**Visual:** `scene-welcome-studio` — a neutral plate and a small sample part in
a local bloom; no fake printer status.

### 2. Choose a route: pair or explore

**Title:** `Choose your workshop`

**Body:** Pair a printer on your private network, or explore with a sample.
Pairing identifies a machine; it does not approve a print.

**States:**

- `Recognized`: show the reported model and ask the user to review it.
- `Unsupported for sending`: keep diagnostics and export available; do not show
  a disabled-looking Print button as if a tap can fix it.
- `Not confirmed`: say `Printer state not confirmed` and provide `Try again`.

**Visual:** `scene-printer-pairing` — phone, neutral printer silhouette, and
local-network ripple. Use runtime geometry or licensed geometry for an exact
printer.

### 3. Meet the setup

**Title:** `Five things must agree`

**Body:** Check the printer, nozzle, plate, filament, and profile before you
slice. A material name by itself is not a complete setup.

**Action:** `Review setup`

**Success state:** `Setup recorded — review any amber items before slicing.`

**Visual:** `diagram-setup-hotspots` — tap targets for plate, nozzle, spool,
feed path, and part, with a text transcript.

### 4. Put a model on the plate

**Title:** `Keep the part inside the printable area`

**Body:** Move, rotate, or arrange the part until it sits within the highlighted
area. Alloy never moves your model without showing the change and offering
Undo.

**Out-of-bounds message:** `Part of this model is outside the printable area.
Move it inside, split it, or choose a compatible plate.`

**Visual:** `scene-bed-and-orientation` — a sample cube moves from an outlined
no-go area into the plate.

### 5. Slice means plan, not promise

**Title:** `Inspect the motion`

**Body:** Slicing turns a shape into layer-by-layer motion. Start with layer 1,
then inspect thin features, bridges, supports, and warnings.

**After slice:** `Toolpath ready to inspect. Estimates help you plan; they are
not guarantees.`

**Visual:** `scene-inspect-before-print` — actual Alloy toolpath screenshot
with vector annotations, not generated G-code imagery.

### 6. Watch the first layer

**Title:** `Stay nearby at the start`

**Body:** Watch for a continuous line attached to the plate. If the print comes
loose, drags, smells burnt, or contacts the nozzle, stop and supervise.

**Action:** `Open first-layer guide`

**Visual:** `scene-first-layer` — reviewed normal/failure pair, captioned.

### 7. Finish safely

**Title:** `Let the plate cool first`

**Body:** When the printer reports completion, let hot parts cool and use the
printer maker's approved removal guidance. Record what worked before changing
the next print.

**Visual:** `scene-print-complete` — a cool-down state, not hands removing a
part.

## Quick cheat sheet

Each card has one `Why it matters` line, one safe next action, and one visual.

| ID | Card title | Plain-language text | Safe next action | Visual |
| --- | --- | --- | --- | --- |
| `concept.model` | Model | The digital shape you want to make. | Open it, then check its size and parts. | `diagram-model-mesh` |
| `concept.plate` | Build plate | The surface the first layer sticks to. | Confirm the selected plate matches the printer. | `diagram-build-plate` |
| `concept.nozzle` | Nozzle | The heated tip that places filament. | Check its size in the active setup; do not touch it while hot. | `diagram-nozzle` |
| `concept.filament` | Filament | The 1.75 mm plastic strand the printer melts into a part. | Match the loaded spool to the selected material. | `diagram-filament-path` |
| `concept.profile` | Profile | A documented group of printer, material, and quality settings. | Use a matching profile or save the job as a draft. | `diagram-profile-stack` |
| `concept.layers` | Layers | Thin slices stacked to make the object. Smaller layers can improve detail but may take longer. | Inspect layer 1 and any highlighted thin areas. | `diagram-layer-stack` |
| `concept.walls` | Walls | The solid outer shells of a print. | Change wall count only through the recipe, then re-slice. | `diagram-walls` |
| `concept.infill` | Infill | The pattern inside a part. It supports the shell but is not a magic strength setting. | Compare the estimate after changing it. | `diagram-infill` |
| `concept.orientation` | Orientation | Which side of a part faces the plate. It can change strength, support need, and finish. | Try the suggested orientation, then review the preview. | `diagram-orientation` |
| `concept.brim` | Brim | Extra first-layer material around a part that can help it stay down. | Preview it before slicing; remove it only after cooling. | `diagram-brim` |
| `concept.supports` | Supports | Temporary printed scaffolding under hard-to-print areas. They use material and can mark a surface. | Treat supports as `Review` until the selected engine/profile is qualified. | `diagram-supports` |
| `concept.toolpath` | Toolpath and G-code | The planned printer motion. G-code is the instruction file that carries it. | Inspect the path before export or send. | `diagram-toolpath` |

### Quick signals

- **Live:** recent information reported by the selected printer.
- **Estimated:** calculated planning value, not a measurement.
- **Imported:** comes from your file or saved project.
- **Not verified:** Alloy cannot safely make the next physical claim or action.
- **Review:** read the warning before continuing.
- **Stop:** pause or cancel only if it is safe to reach normal printer controls;
  then supervise the machine.

## A1 mini material and feed-route messages

These messages apply only after the active printer is confirmed as **Bambu Lab
A1 mini**. Material availability must come from a versioned compatibility
record, not from a hard-coded chip. Initial published reference: Bambu Lab A1
mini product specification and material guidance, reviewed 2026-09-28. Verify
against the current official source before changing a recipe.

### Picker labels

| State | Label and message | Available action |
| --- | --- | --- |
| Qualified | `Qualified in Alloy` — `Tested for A1 mini / <nozzle> / <plate> / <recipe revision>.` | Show only when matching qualification evidence exists. |
| Manufacturer-supported, not Alloy-qualified | `Supported by Bambu Lab; not yet validated by Alloy for direct print.` | Slice/inspect/export only, subject to other gates. |
| Not recommended | `Not recommended for A1 mini. Alloy will not prepare a direct job with this material.` | Explain why and offer a supported alternative. |
| Unknown or custom | `No validated Alloy recipe for this spool.` | Save as draft or export only. |

### Material-family cards

| Family | A1 mini card copy | Feed-route rule |
| --- | --- | --- |
| PLA variants | `A good first material when the active recipe matches this spool.` | Start with the external-spool/direct path until the exact route is qualified. |
| PETG | `Bambu Lab lists PETG as ideal for A1 mini. It is not Alloy-qualified until this exact recipe has evidence.` | Show the selected route and recipe state; do not infer approval from the name. |
| TPU | `Flexible filament needs a route that is marked compatible for this exact TPU recipe.` | Do not present AMS lite as compatible unless the active material record says it is. For TPU 85A/90A, show `External/direct feed required` when supported by the current Bambu guidance. |
| PVA | `Bambu Lab lists PVA as ideal for A1 mini. Alloy needs a verified recipe before direct print.` | Route and multi-material use remain scoped to the selected, qualified profile. |
| ABS, ASA, PC, PA, PET, CF/GF families | `Not recommended for A1 mini in this release.` | Block direct job preparation. Do not suggest an enclosure or a workaround. |

### Feed-route cards

**External spool / direct feed — `Available when qualified`**

`Confirm 1.75 mm filament, a clear visible path, the selected spool, and the
correct recipe. Do not pull filament through a powered feeder or open the
hotend to clear a problem.`

**AMS lite — `Separate qualification required`**

`A feed route is not approved just because the printer is an A1 mini Combo.
Check the exact spool and material record. If the route is not qualified, use
inspection/export only.`

**Regular AMS — `Not available for A1 mini`**

`The regular AMS is not an A1 mini route. Choose an external-spool or a
separately qualified AMS lite setup.`

**Tangled, wet, or near-empty spool — `Review before print`**

`Alloy cannot prove spool condition from its name. Confirm the feed path and
material condition using the filament and printer maker's current guidance.`

### Estimate labels

`Model material` · `Support material` · `Purge / transition material` · `Prime
and cleaning material` · `Safety reserve`

If Alloy cannot calculate one component, display **Not available** for that
component and do not show a deceptively exact total. `Remaining on spool` is
always an estimate unless supplied by fresh printer telemetry or a user-recorded
scale measurement.

## Troubleshooting catalogue

All troubleshooting starts with **Is the printer running now?** If yes, the
first card must decide whether the user needs a stop path. Each family has a
normal/failure visual pair, a short decision path, a save-evidence action, and
an official-support link filtered by printer model.

| Family | First card | Safe next step | Escalate when | Required visual |
| --- | --- | --- | --- | --- |
| `adhesion` | `My print is not sticking` | Review layer 1, selected plate, material, and profile. Use only maker-approved plate preparation. | It lifts, drags, or is struck by the nozzle. | `photo-first-layer-pair` |
| `detached-print` | `My print came loose` | Pause/cancel if safely reachable; do not reach into moving hardware. Save a photo. | A loose part is near moving axes or the nozzle. | `scene-detached-part` |
| `stringing-blobs` | `I see strings or blobs` | Confirm the selected material and profile. Keep the result as a symptom, not a diagnosis. | There is a hotend leak, grinding, or repeated failure. | `photo-stringing` |
| `layer-shift-collision` | `Layers moved, or I hear grinding` | Stop and supervise. Do not force an axis or pull a part free. | Any collision, grinding, or uncertain obstruction. | `diagram-collision-envelope` |
| `thin-flow-clog` | `Lines are thin or filament stopped` | Compare loaded spool and profile; inspect only cold, external path issues allowed by official guidance. | Printer fault, grinding, visible leak, or no clear safe cause. | `diagram-feed-path` |
| `warping-cracking` | `Corners lifted or the part cracked` | Review first-layer preview, material, plate, and profile; preserve the result. | The part is moving, lifting into the nozzle, or the material is outside supported scope. | `photo-warping` |
| `feed-route` | `The spool will not feed` | Confirm the selected route, spool condition, and material-specific route status. | A feeder reports a fault or the path is not clearly safe to inspect. | `diagram-feed-route` |
| `network-recovery` | `Alloy cannot connect, send, or confirm a job` | Treat state as unknown. Keep the artifact and retry only after you review the private-network setup. | Certificate, identity, recovery, or job-state check remains unresolved. | `diagram-job-state-timeline` |

### Common FAQ entries

1. **Can I print this now?** `Only if the app shows a qualified printer,
recipe, output, and connection for this exact job. A green-looking preview is
not physical approval.`
2. **Why is Print locked?** `The lock names the missing evidence. You can still
inspect or export when those actions are safe.`
3. **Why is the estimate different from the spool?** `Estimates are planning
values. They can exclude or separately show purge, support, priming, cleanup,
and failed-print waste.`
4. **Do I need supports?** `Supports are temporary scaffolding. Review the
preview; do not assume they guarantee a clean underside.`
5. **Can I use any PLA spool?** `Choose only a record that matches the printer,
nozzle, plate, feed route, and recipe. A material label alone is not enough.`
6. **Can I use TPU in AMS lite?** `Do not assume so. Alloy shows route-specific
material guidance and blocks unqualified combinations.`
7. **What should I watch at the start?** `A continuous line attached to the
plate, clear motion, and no rubbing, dragging, burning smell, or smoke.`
8. **Can I resume after a connection problem?** `Only after fresh printer
telemetry and explicit job review. Alloy must never guess that a prior command
completed.`

## Universal hard-stop cards

These five cards appear above any repair-style content. They are never
dismissed by a cosmetic confirmation. Each offers `I’ve stopped the job`,
`Save job details`, and `Open official support`.

### `stop.smoke-heat`

**Title:** `Smoke, burning smell, or unusual heat`

**Body:** Stop and supervise the printer. Use normal cancel or emergency
controls only if it is safe to reach them. Do not continue through an app
tutorial.

### `stop.collision-grinding`

**Title:** `Collision, grinding, or a nozzle hitting the part`

**Body:** Pause or cancel if safely reachable. Do not jog axes, force movement,
or pull the part free. Save the exact printer message and a photo after the
machine is safe.

### `stop.loose-moving-part`

**Title:** `A loose part is near moving hardware`

**Body:** Stop and supervise. Keep hands clear of hot and moving parts. Do not
try to catch, hold, or reposition the part while the printer is active.

### `stop.damage-leak`

**Title:** `Damaged cable, loose wire, or molten/leaking hotend`

**Body:** Stop using the printer and follow current manufacturer service
guidance. Do not open electrical covers, disassemble a hotend, or work around
the fault in Alloy.

### `stop.unknown-state`

**Title:** `Printer state is not confirmed after a send, pause, or cancel`

**Body:** Treat the printer as active or uncertain until fresh telemetry and
the physical printer agree. Do not resend automatically or assume a job ended.

## Visual and motion handoff

Use visual assets to explain a physical idea, never to assert a safety fact.

- **Exact geometry:** runtime Alloy GLES or licensed source geometry. This
  includes plate bounds, printer orientation, nozzle clearance, and feed route.
- **Abstract intuition:** original generated 3D stills. One subject, smooth
  light/dark field, and a soft local bloom/grid under the object. No global
  grain, hard-edged bloom, Bambu marks, UI copies, pseudo-text, or unsafe
  operation.
- **Symptoms:** reviewed original/licensed photos with matching normal/failure
  framing. Label them `Example symptom — not a remote diagnosis`.
- **Motion:** one short animation per screen. Respect Reduce Motion, include a
  static equivalent and descriptive alt text, and never use colour or glow as
  the sole warning signal.
- **Loading:** use `anim-filament-sweep`. The hotend travels left to right near
  the bottom safe area; its nozzle tip meets the top edge of the bar at the
  progress boundary. The single rounded filament line fills once. Centre copy
  may read `Reading the model`, `Checking the build plate`, `Planning each
  layer`, `Tracing the toolpath`, or `Getting your print ready` only when it
  matches a real job stage; otherwise use `Working on your model`.

## Recommended UI payload outline

The UI should render the catalogue from signed, versioned local content. Remote
updates are optional and may replace the bundled content only after signature,
schema, and source-revision checks. Capability gates remain in product code;
content can explain them but cannot enable them.

```json
{
  "schemaVersion": 1,
  "bundleId": "alloy-learning-en-IN-2026-09-28",
  "locale": "en-IN",
  "revision": "2026-09-28",
  "contentState": "bundled",
  "sourcePolicy": {
    "requireScopeForFactClaims": true,
    "requireLastReviewed": true,
    "offlineFirst": true,
    "remoteUpdateRequiresSignature": true
  },
  "sourceRecords": [
    {
      "id": "bambu-a1-mini-spec",
      "publisher": "Bambu Lab",
      "title": "A1 mini specification and material guidance",
      "url": "https://us.store.bambulab.com/products/a1-mini?id=543566369394393101",
      "reviewedOn": "2026-09-28",
      "scope": ["A1 mini"]
    }
  ],
  "glossary": [
    {
      "id": "concept.layers",
      "title": "Layers",
      "summary": "Thin slices stacked to make the object.",
      "whyItMatters": "Smaller layers can improve detail but may take longer.",
      "safeAction": {"label": "Inspect the first layer", "route": "inspect/layer/1"},
      "visual": {"asset": "diagram-layer-stack", "alt": "A simple object shown as stacked horizontal layers."},
      "related": ["concept.toolpath", "guide.inspect"]
    }
  ],
  "guides": [
    {
      "id": "start.welcome",
      "kind": "onboarding",
      "title": "A workshop in your pocket",
      "summary": "Prepare and inspect a model before export or a qualified print.",
      "steps": [
        {"id": "intro", "body": "Import a model, place it on a real plate, and inspect the planned layers.", "visual": "scene-welcome-studio"}
      ],
      "actions": [
        {"label": "Start safely", "route": "onboarding/setup"},
        {"label": "Explore a sample", "route": "library/sample"}
      ],
      "accessibility": {"reducedMotionAsset": "scene-welcome-studio-static", "textEquivalent": true}
    }
  ],
  "materialRecords": [
    {
      "id": "a1mini-pla-external",
      "printer": "bambu-a1-mini",
      "materialFamily": "PLA",
      "diameterMm": 1.75,
      "feedRoute": "external_direct",
      "state": "manufacturer_supported_not_alloy_qualified",
      "recipeRef": "profile/a1-mini/<nozzle>/<plate>/pla/<revision>",
      "messages": {"picker": "Supported by Bambu Lab; not yet validated by Alloy for direct print."},
      "sourceRef": "bambu-a1-mini-spec"
    }
  ],
  "troubleshooting": [
    {
      "id": "troubleshoot.adhesion",
      "family": "adhesion",
      "title": "My print is not sticking",
      "severity": "review",
      "summary": "Use a safe check before changing the model or printer.",
      "scope": {"printers": ["bambu-a1-mini", "bambu-a1", "bambu-p1s"], "materials": ["PLA", "PETG", "TPU", "PVA"]},
      "visual": {"asset": "photo-first-layer-pair", "alt": "One attached first-layer line and one loose, irregular line.", "type": "reviewed_symptom_example"},
      "decision": [
        {"id": "running", "prompt": "Is the print lifting, dragging, or being struck by the nozzle now?", "answers": [
          {"label": "Yes or I am unsure", "severity": "stop", "body": "Pause or cancel only if it is safe to reach normal controls. Do not touch hot or moving hardware.", "actions": ["save_evidence", "open_official_support"]},
          {"label": "No", "next": "review-layer-one"}
        ]}
      ],
      "sourceRefs": ["bambu-a1-mini-spec"],
      "lastSafetyReview": "2026-09-28"
    }
  ],
  "hardStops": [
    {"id": "stop.smoke-heat", "severity": "stop", "title": "Smoke, burning smell, or unusual heat", "action": "Stop and supervise the printer.", "actionIds": ["confirm_stopped", "save_evidence", "open_official_support"]}
  ],
  "contextualTriggers": [
    {"surface": "prepare", "when": "part_out_of_bounds", "contentId": "start.plate"},
    {"surface": "slice", "when": "slice_started", "contentId": "concept.toolpath"},
    {"surface": "monitor", "when": "telemetry_stale", "contentId": "stop.unknown-state"}
  ],
  "assets": [
    {
      "id": "scene-welcome-studio",
      "type": "runtime_or_licensed_geometry",
      "light": "learning/scene-welcome-studio-light.webp",
      "dark": "learning/scene-welcome-studio-dark.webp",
      "reducedMotion": "learning/scene-welcome-studio-static.webp",
      "alt": "A small sample object centred on a build plate in a quiet studio.",
      "provenance": "Alloy-owned runtime scene",
      "reviewStatus": "approved"
    }
  ],
  "analytics": {
    "events": ["learning_opened", "learning_completed", "troubleshoot_path_selected"],
    "forbiddenFields": ["printer_password", "certificate", "private_key", "model_geometry", "unredacted_network_address"]
  }
}
```

## Release editorial checks

- Every card works offline after install and at 200% text scale.
- Screen readers announce severity before actions; every visual has useful alt
  text and a reduced-motion equivalent.
- All printer/material claims resolve to a dated source record and a visible
  scope label.
- Search accepts both beginner and expert wording, such as `print came loose`
  and `adhesion failure`.
- A `Stop` or `Gate` card cannot be visually confused with a successful state.
- A user can reach the exact missing print gate from `Why is this locked?`.
- No learning acknowledgement enables direct printing, changes a recipe, or
  bypasses a hardware, profile, certificate, support, or recovery gate.
