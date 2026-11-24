# Third-party components

Alloy Forge is covered by the repository licence (PolyForm Noncommercial 1.0.0, see `../LICENSE`). It bundles or loads these components, which keep their own licences:

| Component | Licence | How it is used |
|---|---|---|
| [three.js](https://github.com/mrdoob/three.js) | MIT | 3D viewport, orbit and transform controls; bundled into `app.js` |
| [Manifold](https://github.com/elalish/manifold) (`manifold-3d`) | Apache-2.0 | Mesh boolean kernel (WASM); bundled into `app.js` |
| [occt-import-js](https://github.com/kovacsv/occt-import-js) | LGPL-2.1 (wraps Open CASCADE, LGPL-2.1 with exception) | STEP reader, loaded at runtime as a separate, unmodified `occt-import-js.js` + `.wasm`; `build.mjs` copies its licence to `dist/LICENSE.occt-import-js.txt` |
| [esbuild](https://github.com/evanw/esbuild) | MIT | Build tool only, not shipped |
| Loop engine (`src/loop/`) | Same as this repository (PolyForm Noncommercial 1.0.0), own work vendored from `mbaliga/Shared-Libraries-asoc` `web/loop-engine` | Runs the design loop graph; source commit in each file header and `src/loop/VENDORED.md`. A dependency-free port of the Fonebrew loop engine semantics |
| Loop schema and spec concepts | Fonebrew (`mbaliga/android-ide-core`), `LICENSE-PENDING` upstream | `loops/enclosure-design.loop.json` follows the Loop Definition v2 schema; the schemas are embedded in the vendored engine (`src/loop/schemas.js`). Licence to be confirmed before any redistribution outside this repository |
