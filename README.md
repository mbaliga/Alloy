# Alloy

Alloy is a family of three tools on one shared core: **draw it, forge it, slice it.**

| Tool | What it is | Where | Status (2026-11-25) |
|---|---|---|---|
| **Alloy Forge** | Parametric 3D CAD for electronics enclosures and small products, AI-native, bring your own model key. Web, static, self-hostable. | [`forge/`](forge/) | Working beta. The OCCT (B-rep) kernel, the PCB enclosure template, the checks scorecard and the design loop are built; the roadmap says plainly what is not: [docs/FORGE_ROADMAP.md](docs/FORGE_ROADMAP.md). |
| **Alloy Slicer** | A real slicer on the phone, first for the Bambu Lab A1 Mini. Native Android (Java, Android Views, an NDK engine in the OrcaSlicer/PrusaSlicer lineage). Sending to the printer is there for retention; slicing quality, speed and preview come first. | [`android/`](android/) | The app builds and its Android acceptance suite passes. **Slicing is not yet qualified for printing: the strict G3 parity check against desktop Orca fails and the gate status is NO-GO for printing and Bambu Handy handoff.** See [docs/SLICER_ROADMAP.md](docs/SLICER_ROADMAP.md). |
| **Alloy Draft** | Precision 2D drafting and vector design, Figma-easy and AutoCAD-precise. Becomes Forge's sketcher. | `draft/` (not created) | Not started. Spec: [docs/DRAFT_SPEC.md](docs/DRAFT_SPEC.md). |

Workflow chain: **Draft** (sketch) → **Forge** (solid, checked enclosure) → **Slicer** (G-code and print).

Also here: [`slicer-ports/`](slicer-ports/), iOS and Ubuntu Touch companions (preparation and inspection only, no slicing engine, never run on a device; see their docs for the evidence classes).

## Repository layout

```
forge/           Alloy Forge web app (JavaScript, esbuild, three.js, Manifold, Open CASCADE wasm)
android/         Alloy Slicer (Android app, native engine tree, parity and CI tooling, slicer docs)
slicer-ports/    iOS and Ubuntu Touch companions
docs/            family plan, roadmaps, specs, style canon, consolidation record
.github/         CI (cleanup of old Actions artifacts today; lanes are added per app)
```

Start with [docs/ALLOY_FAMILY.md](docs/ALLOY_FAMILY.md). Working rules for people and agents are in [CLAUDE.md](CLAUDE.md); UI work is checked against [docs/STYLE.md](docs/STYLE.md).

## Run it

**Forge** (Node 22):

```
cd forge
npm ci
npm test         # headless kernel, loop and selection tests
npm run build    # writes dist/
npm run serve    # http://localhost:8765
```

**Slicer:** needs the Android SDK and NDK; see [android/README.md](android/README.md). Without them you can still run its Python tooling and parity tests (`android/tools`, `android/ci`, `android/parity`). The native engine build is heavy (the tree is about 630 MB with prebuilt Boost and OpenCASCADE) and runs in CI.

## Licences differ by directory

| Path | Licence |
|---|---|
| repository root, `forge/`, `docs/` | [PolyForm Noncommercial 1.0.0](LICENSE), copyright 2026 Madhav Baliga. Free for noncommercial use; contact the copyright holder for commercial use. |
| `android/` | **GNU AGPL-3.0** ([android/LICENSE](android/LICENSE)), because the slicer links engine code derived from SliceBeam, OrcaSlicer-Mobile, OrcaSlicer and PrusaSlicer. Engine-derived code keeps its notices. |
| `slicer-ports/` | Alloy-authored code with no engine-derived content; same terms as the root unless a file says otherwise. |

Each app keeps its third-party notices: [forge/THIRD_PARTY.md](forge/THIRD_PARTY.md), [android/THIRD_PARTY.md](android/THIRD_PARTY.md). Whether the Slicer's licence situation should change is an open owner decision, recorded in [docs/CONSOLIDATION.md](docs/CONSOLIDATION.md).

## Principles

- **No telemetry.** The only network calls are to the model endpoint you configure (and, for the Slicer, a printer on your LAN). API keys stay in the browser or on your own server.
- **The AI never edits geometry directly.** Every change is a typed op on the design document: validated, built, undoable, with locks enforced.
- **Honest status.** Roadmaps say what is built, what is partly built and what is not. A green build is not a qualified print.

## How this repository came together

On 2026-11-25 the slicer (spread over about 22 branches) was consolidated into `main` under `android/`. What was kept, ported, archived and decided is in [docs/CONSOLIDATION.md](docs/CONSOLIDATION.md).
