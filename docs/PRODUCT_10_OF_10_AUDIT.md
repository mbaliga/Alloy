# Alloy 10/10 product and learning-content audit

**Status:** proposed acceptance contract; not a claim that these capabilities
are shipped.
**Reviewed:** 2026-09-28
**Audience:** product, Android, slicer, transport, content, visual-design, and
release owners.

## The honest definition of 10/10

For Alloy, 10/10 cannot mean “the most features” or “a prettier Bambu Handy.”
It means that a first-time owner can safely get from an unfamiliar file to a
successful, understandable print on their own printer, while an experienced
owner can work quickly without being trapped in a simplified mode.

The score is earned only when all of these statements are true:

1. A user can tell exactly what their selected printer, nozzle, plate, and
   material allow before slicing.
2. The app does not make a material, support, time, mass, collision, or
   printer-compatibility claim unless the active profile and output path have
   been verified for that exact scope.
3. A novice can recover from the common failures without confusing symptom,
   cause, and risky remedy.
4. A physical print has an auditable beginning, current state, safe stop path,
   and unambiguous end state.
5. The rich visual treatment helps users make a decision; it is never the sole
   carrier of a safety condition, label, or control state.

Until direct A1 Mini physical acceptance, signed distribution, native slicer
verification, and support parity are closed, the current product remains an
alpha. A polished onboarding sequence must not soften that truth.

## Recommended initial printer cohort

Start with three deliberately chosen machines, not an unbounded "Bambu
support" selector:

| Tier | Printer | Why it belongs in v1 | Shipping boundary |
| --- | --- | --- | --- |
| Required | **Bambu Lab A1 mini** | User's available printer; smallest bed makes packing, orientation, and beginner guardrails valuable. | First direct-print validation target. Treat its authenticated model code as `N1` only after fresh telemetry confirms it. |
| Required | **Bambu Lab A1** | Same beginner-oriented open-frame/AMS lite family, but a materially larger bed. It proves profile-driven geometry rather than a scaled A1 mini UI. | Separate profile and full physical acceptance; never inherit A1 mini approval. |
| Required | **Bambu Lab P1S** | Enclosed machine introduces different thermal/material and plate guidance. It prevents Alloy from being falsely framed as "A-series only." | Separate LAN/protocol, profile, nozzle/plate, material, and recovery acceptance. |

The app may *recognize* an unknown Bambu printer, but must label it
**Unsupported for sending** and offer only safe, read-only diagnostics. Do not
substitute P1P, X1, H2, AMS variants, or firmware-family guesses for the three
qualified profiles.

### Per-printer promotion matrix

The following is the unit of support. “Three supported printers” is false
until every cell for each printer is complete.

| Requirement | A1 mini | A1 | P1S |
| --- | --- | --- | --- |
| Profile dimensions/nozzle/plate data reviewed against current official source | required | required | required |
| Native arm64 slice smoke run on physical Android | required | required | required |
| Real printer discovery and identity/firmware capture | required | required | required |
| Pinned certificate and LAN upload/start/monitor/cancel/recovery test | required | required | required |
| No-support PLA Basic, 0.4 mm qualification prints | 5 fixtures | 5 fixtures | 5 fixtures |
| Material-specific qualification | PLA + PETG minimum | PLA + PETG minimum | PLA + PETG + ABS/ASA minimum |
| AMS/AMS lite multicolour | separate future gate | separate future gate | separate future gate |
| Supports | unavailable until verified | unavailable until verified | unavailable until verified |

**Release rule:** profile approval is not transitive. An A1 mini successful
cube neither validates the A1, P1S, a different nozzle, a different plate, nor
a new firmware. Evidence must record the printer model identifier, firmware,
nozzle, plate, material profile version, build hash, APK signer, and output
artifact hash.

## A1 mini material and spool policy

### Confirmed baseline from official Bambu material

Bambu's current A1 mini product specification lists **PLA, PETG, TPU, and
PVA** as ideal. It lists **ABS, ASA, PC, PA, PET, and carbon/glass-fibre
reinforced polymers** as not recommended. The A1 mini is a 1.75 mm filament
machine with a 300 C hot end, an 80 C maximum bed, a supplied 0.4 mm stainless
steel nozzle, and optional 0.2/0.6/0.8 mm nozzles. It accepts Bambu Textured
PEI and Smooth PEI plates. [Official A1 mini specification](https://us.store.bambulab.com/products/a1-mini?id=543566369394393101)

That is a printer-material classification, not a guarantee that every
brand/spool/nozzle/plate/ambient condition will print successfully.

### What Alloy may show in its initial A1 mini material picker

| Picker state | Initial entries | Send state | Required UI language |
| --- | --- | --- | --- |
| **Qualified** | `PLA` variants first, after exact recipe evidence exists | allowed only for qualified printer/nozzle/plate/profile scope | “Qualified in Alloy: A1 mini / 0.4 mm / <plate> / <profile revision>.” |
| **Compatible but not Alloy-qualified** | `PETG`, `TPU`, `PVA` | inspection/export only until each has print evidence | “Supported by Bambu as an ideal A1 mini material; not yet validated by Alloy for direct send.” |
| **Support/interface filament** | Support for PLA/PETG; PVA is also listed above as an ideal A1 mini material | inspection/export only until exact product, profile, feed route, and purge behavior are verified | The A1 mini FAQ mentions support filament generally; do not infer that every support SKU or AMS lite route is compatible. |
| **Not recommended** | `ABS`, `ASA`, `PC`, `PA`, `PET`, `PLA-CF`, `PETG-CF`, and all other CF/GF classes | blocked from direct send | “Not recommended for the A1 mini. Alloy will not prepare a direct job.” |
| **Unknown/custom** | material name and manufacturer entered by user | blocked from direct send | “No validated Alloy recipe. Export only; verify with the filament manufacturer and your printer documentation.” |

Do not collapse “Bambu-supported”, “physically feedable”, “AMS compatible”,
and “Alloy-qualified” into one green tick.

### Spools are a separate compatibility model

The requested “all kinds of spools” requires three explicit concepts in the
data model and UI:

1. **Filament material** — PLA, PETG, TPU, PVA, etc.
2. **Spool/feed route** — external holder/direct feed, AMS lite, or an
   explicitly unsupported route.
3. **Spool geometry and condition** — diameter, width, hub form, mass
   remaining, reusable/refill state, moisture state, tangles, and adapter.

For A1 mini, start by qualifying **1.75 mm filament on the external spool
holder/direct path**. An A1 mini Combo uses AMS lite for four-colour work, and
Bambu says the regular AMS is incompatible with A1 mini. [Official A1 mini
FAQ](https://us.store.bambulab.com/products/a1-mini?id=543566369394393101)

The app must not assume that “TPU” means “AMS lite compatible.” Bambu's TPU
85A/90A page states that AMS and AMS lite are not compatible with those
materials; it further distinguishes A-series TPU 85A and 90A compatibility.
[Official TPU guidance](https://us.store.bambulab.com/products/tpu-85a-tpu-90a/)

### Minimum material/spool acceptance criteria

- [ ] Ship profile records with a source URL, source revision/date, exact
  material name, manufacturer, diameter, nozzle, plate, temperatures, flow,
  speed, retraction, drying warning, feed route, and qualification state.
- [ ] The picker filters unavailable combinations before the user can slice:
  e.g. an AMS lite job cannot select a material/profile marked direct-feed
  only.
- [ ] Selecting a fibre-filled material with a 0.2 mm nozzle blocks slicing;
  Bambu notes particulate-filled materials are prone to clog that nozzle.
  [Official A1-series hotend compatibility](https://us.store.bambulab.com/products/bambu-hotend-a1-series?variant=42008721686664)
- [ ] “Remaining filament” is an estimate with a confidence/state label,
  never presented as a measured fact unless derived from printer telemetry or
  a user-confirmed scale measurement.
- [ ] Every multi-material estimate separately reports model material,
  support/interface material, purge/transition material, priming, and a
  safety reserve. If one component cannot be calculated, show “not available”
  rather than a deceptively precise total.
- [ ] An incompatible, unknown, wet, or near-empty spool blocks direct send
  with an explanation and a non-destructive next action.
- [ ] The app never uses RFID as permission to bypass its own recipe,
  printer, or output validation.

### A1 and P1S distinctions that the content must preserve

The A1 also lists PLA/PETG/TPU/PVA as ideal and warns that high-temperature
materials have warping and interlayer-strength risks on its open frame. It
has a 256 mm cubed build volume and a 100 C bed, not the A1 mini's 180 mm
cube/80 C bed. [Official A1 specification and cautions](https://us.store.bambulab.com/products/a1?id=579550514255634440&skr=yes)

The P1S official specification distinguishes its enclosed configuration and
material capability; its profile cannot be an A1 profile with a larger bed.
[Official P1S specification](https://us.store.bambulab.com/products/p1s?id=583855874739507213)

## 10/10 newcomer experience: required learning architecture

Learning must be contextual, skippable, searchable, offline-cached after
first download, and visibly separated from a print-control command. Do not
force a course before a user can inspect a model or stop a print.

### First-run onboarding: five short, recoverable moments

| Moment | User job | Visual teaching device | Completion criterion |
| --- | --- | --- | --- |
| 1. Welcome and safety | Understand that Alloy is a preparation/control tool, not an unattended appliance. | Quiet 3D printer stage with one clear “stay nearby for the first layer” cue. | User acknowledges supervision and emergency-stop location. |
| 2. Pair or explore | Pair a printer or safely use Demo Workshop. | Printer card shows recognized/unsupported status, LAN-only explanation, and why certificate confirmation matters. | No credentials logged; unsupported printer cannot be mislabeled paired. |
| 3. Know your hardware | Identify printer, nozzle, plate, feed route, and material. | Tap-to-reveal 3D hotspot diagram, with a text transcript. | User can review/edit detected setup and sees incompatible combinations. |
| 4. First object | Import or use a provided small calibration fixture. | Object enters the plate; bloom/grid communicates printable boundary without replacing numerical dimensions. | Out-of-bounds and unsupported-overhang warnings are intelligible. |
| 5. First print checklist | Make a safe intentional decision. | A single preflight card groups plate cleanliness, filament, clearance, supervision, and estimated consumption. | Send stays unavailable unless the exact printer/material/output lane is qualified. |

Acceptance:

- [ ] Each moment is under 45 seconds of reading, can be replayed from Help,
  and has a “skip for now” route except explicit safety acknowledgement.
- [ ] Android back, screen reader focus order, large text, reduced motion, and
  landscape are tested for every onboarding state.
- [ ] Every 3D animation has a static illustration plus text equivalent.
- [ ] Copy never claims “one-click perfect print,” “zero calibration,” or
  “safe” when the output/transport gate is pending.

### Just-in-time cheatsheet

This is a searchable, one-screen-per-concept field guide—not a glossary dump.
The initial set must answer:

- What are a model, plate, nozzle, layer, wall, infill, brim, support,
  filament, purge, and G-code?
- Why can a model look printable but fail due to adhesion, geometry,
  clearance, material moisture, or heat?
- What affects strength, finish, time, material, and risk?
- How do I choose PLA versus PETG versus TPU within the selected printer's
  qualified scope?
- What should I check before, during the first layer, and after a print?
- When must I stop, avoid touching hot/moving parts, and consult Bambu's
  official maintenance/support material?

Acceptance:

- [ ] Each concept contains: a plain-language definition, “why it matters,”
  one visual, one safe action, and cross-links to symptoms/recipes.
- [ ] Copy meets an eighth-grade readability target while keeping required
  technical units intact.
- [ ] No article recommends enclosing an A-series printer, bypassing
  interlocks, touching a hot end, or editing unknown G-code.

## Troubleshooting and visually rich FAQ

### Content model: diagnose, do not guess

Every troubleshooting card must follow this order:

1. **Symptom:** what the person can actually see.
2. **Immediate safety action:** pause/stop/power guidance only when it is
   warranted, and never an unsafe physical instruction.
3. **Likely causes:** ordered by probability and risk, tagged with confidence.
4. **Guided checks:** one reversible observation at a time.
5. **Safe remedies:** only actions verified for the active printer/profile.
6. **Escalate:** official Bambu article/contact route, with captured
   non-secret diagnostics.
7. **Outcome:** user records whether the issue resolved, creating an opt-in
   local troubleshooting history.

No “AI diagnosis” may trigger a printer command, assert a cause as fact, or
override a compatibility block.

### Initial issue library

| Symptom family | Required visual | Safety/content requirement |
| --- | --- | --- |
| First layer will not stick | Animated before/after plate and first-layer examples | Explain clean/cool/contaminated plate without prescribing unsafe solvents or hot handling. |
| Spaghetti / detached print | 3D failure silhouette plus stop decision tree | Clearly tell the user when to stop rather than wasting material or risking a collision. |
| Stringing / blobs | Side-by-side print photograph/illustration and a material-moisture cue | Do not promise a temperature tweak fixes every filament. |
| Layer shift / collision | Motion-path diagram and “do not force axes” warning | Direct users to check obstruction and printer-specific official guidance. |
| Under-extrusion / clogs | Transparent feed-path diagram | Separate dry filament, feed resistance, nozzle issue, and calibration; no improvised hot-end repair instructions. |
| Warping/cracking | Plate-edge and thermal-gradient visual | Keep A-series high-temperature warnings visible. |
| AMS lite / feed faults | Feed-route illustration and spool status | Differentiate material incompatibility, spool geometry, tangle, and empty spool. |
| Network/send/recovery | Printer-to-phone state timeline | Never instruct users to disable certificate checks or retry a partially known transaction. |

Acceptance:

- [ ] At least 24 reviewed troubleshooting cards launch with v1, including all
  eight families above and three paths each for A1 mini, A1, and P1S where
  procedure differs.
- [ ] Each card carries `printer scope`, `material scope`, `firmware checked`,
  `last medically/safety reviewed` (where relevant), `source`, and `owner`.
- [ ] Search recognizes beginner phrasing (“my print came loose”) and expert
  terms (“adhesion failure”) without inventing a diagnosis.
- [ ] Photos/illustrations are original or licensed, have alt text, and do not
  depict an unsafe setup as normal.
- [ ] The app captures no printer password, private key, camera feed, or model
  without explicit consent; diagnostic export is previewable and redacted.

## Visual production rules

The work needs a visual system, not one-off generated art. Image generation is
appropriate for non-photoreal explanatory illustrations, texture studies, and
failure-state diagrams—never for counterfeit Bambu UI, fake printer telemetry,
or instructions where exact hardware geometry matters.

### Required asset families

1. **Onboarding stage illustrations:** printer, plate, spool, nozzle,
   phone-to-printer relationship, with a shared perspective/lighting system.
2. **Cheatsheet object diagrams:** clean cutaways and labelled states;
   typography remains native UI text, not baked into images.
3. **Failure-state illustrations:** adhesion, stringing, warping, underflow,
   collision, spool tangle, and network recovery states.
4. **Motion studies:** reduced-motion-safe animation alternatives for slicing,
   toolpath, and loading states.
5. **Accessible variants:** light/dark, high contrast, and monochrome
   equivalents for every safety-critical visual.

Asset acceptance:

- [ ] Every generated asset has a prompt/source record, usage-rights record,
  art direction, reviewer, stable asset ID, and alt text.
- [ ] The image has no embedded unreadable text, logo distortion, fake
  controls, or ambiguous material colour as the only meaning.
- [ ] A human visual QA review checks anatomy/geometry, crop, contrast,
  Android density scaling, dark mode, and localization expansion.
- [ ] Generated printer visuals are branded Alloy learning illustrations unless
  written permission establishes rights to use exact Bambu trade dress/assets.

## Editorial workflow required before UI integration

The requested multi-agent process must be a versioned editorial pipeline,
with real accountable review—not a chain that rubber-stamps generated copy.

| Stage | Owner | Input | Output | Cannot proceed without |
| --- | --- | --- | --- | --- |
| A. Learning plan | Learning-content lead | Support tickets/interviews, printer/material scope, safety policy | curriculum map, learner journeys, prioritised article list | product/safety owner sign-off |
| B. Visual direction | Art director + accessibility reviewer | approved learning plan, Alloy/Hyle design tokens | storyboard, asset list, prompts, static alternatives | brand and accessibility sign-off |
| C. Draft | Technical writer + subject-matter reviewer | source-backed facts and visuals | structured cards with sources/scope | factual source citation per claim |
| D. Adversarial audit | Independent maker, safety, accessibility, and novice reviewers | full draft | severity-ranked issues, misleading-claim and unsafe-remedy report | all blockers resolved or content withheld |
| E. Final edit | Senior editor | audit resolutions | concise localized-ready final copy | style, reading-level, terminology, and scope pass |
| F. UI stitch | Android/design-system implementer | approved content + assets | native accessible UI, offline manifest, analytics events | device QA, links, state handling, and design-system review |

Required review exercises before v1 content lock:

- Five true beginners attempt a first print without verbal coaching.
- Five experienced owners find a material restriction and a recovery action.
- At least one low-vision/screen-reader user completes pairing, preflight, and
  troubleshooting discovery.
- A hostile review tries to interpret every green state as a safety or print
  guarantee. Ambiguous copy is a release blocker.
- An offline test verifies cached help, selected printer/material restrictions,
  and recovery guidance remain available without a network connection.

## UX quality gates

### Core preparation and print path

- [ ] Import a real STL/3MF, reject malformed/unsafe archives gracefully, and
  explain what was imported.
- [ ] Present actual geometry, collision/out-of-bed assessment, and plate
  occupancy with uncertainty when bounds are not trustworthy.
- [ ] Arrange/auto-orient reports what it changed and offers undo; it never
  markets a heuristic as an optimal material-saving orientation.
- [ ] Slice preview shows layer/toolpath data that corresponds to the staged
  artifact, including warnings and estimates.
- [ ] The send sheet has an explicit “not available” reason for every closed
  gate and never displays a decorative disabled button without explanation.
- [ ] Bambu Handy export/share is labelled **export**, not **validated for
  Bambu Handy**, until a documented Android intent and consumer acceptance
  matrix prove it.

### Navigation, visuals, and accessibility

- [ ] Curved/icon-first navigation retains an accessible semantic label,
  visible current-page name, focus order, and a conventional back path.
- [ ] The expressive arc is never clipped at device edges, by gesture areas,
  or by display cutouts; verify compact/large Android phones, font scaling,
  RTL, and gesture navigation.
- [ ] Motion is decorative only. Reduced-motion turns float/rotation/ripple
  animations into stable states without hiding progress.
- [ ] Critical status always has text plus non-colour coding: `Ready`,
  `Attention`, `Blocked`, `Recover`, or `Unsupported`.
- [ ] Favourite glow, 3D bloom, materials, and printer thumbnails maintain
  contrast and do not hide a touch target or live-state change.

## Release gates that a 10/10 claim cannot bypass

Existing release and physical-A1-Mini evidence contracts remain authoritative:
[`RELEASE.md`](RELEASE.md) and
[`PHYSICAL_A1_MINI_ACCEPTANCE.md`](PHYSICAL_A1_MINI_ACCEPTANCE.md).

Add these product-level gates:

- [ ] `printer-qualification.json` exists for each of A1 mini, A1, and P1S;
  it references device-specific physical acceptance evidence rather than a
  shared checkbox.
- [ ] `material-qualification.json` exists for each send-enabled
  printer/nozzle/plate/material scope and contains fixture/output/print
  evidence plus measured versus estimated material/time deltas.
- [ ] A support-enabled direct-send lane remains disabled until TreeSupport3D
  or an independently verified equivalent has passed its parity and physical
  evidence; “basic support” is not a substitute.
- [ ] OTA/firmware changes invalidate direct-send qualification until a
  compatibility owner rechecks the protocol/profile and records the decision.
- [ ] Content source links are health-checked; a failed or stale source
  downgrades the associated procedure to “review required,” not silent advice.
- [ ] A privacy/security review covers local storage, pairing, cert pinning,
  diagnostic export, remote content updates, and generated-asset provenance.
- [ ] A signed production build, update/rollback test, crash-free launch
  target, ANR target, offline recovery test, and accessibility test matrix
  pass before public distribution.

## Scorecard and sequence

| Dimension | Current ceiling | 10/10 evidence |
| --- | ---: | --- |
| Visual/product concept | 8 | Hyle-consistent, accessible implementation on real devices |
| Beginner learning | 2 | Tested onboarding, field guide, 24+ scoped troubleshooting cards |
| Materials/spools | 2 | Source-backed compatibility model plus qualified direct-send lanes |
| Three-printer support | 1 | Separate A1 mini, A1, P1S profiles and physical acceptance matrices |
| Slicing/estimates | 3 | Native, scope-specific geometry, toolpath, material/time validation |
| Direct printing/recovery | 1 | Physical transport evidence on each printer, with safe recovery |
| Release operations | 2 | Signed production delivery and observability/rollback evidence |

Recommended order:

1. **Qualify A1 mini / 0.4 mm / Textured PEI / PLA Basic / no supports** on
   the actual phone and printer; do not expand the marketing claim first.
2. Ship the compatibility data model and visible status language before adding
   more material chips or printer cards.
3. Build and test onboarding, cheatsheet, and troubleshooting with the
   editorial pipeline above.
4. Qualify A1 on the same narrow lane, then P1S; only then broaden PETG and
   carefully scoped P1S ABS/ASA.
5. Add AMS lite/multicolour as a standalone program because purge, material
   routing, spool feed, estimation, and failure recovery materially change.
6. Promote supports only after the existing parity gate closes.

The result is not merely a more beautiful slicer. It is a product that tells
the truth about what it knows, teaches a newcomer enough to act safely, and
earns every green “ready to print” state.
