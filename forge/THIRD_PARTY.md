# Third-party components

Alloy Forge is covered by the repository licence (PolyForm Noncommercial 1.0.0, see `../LICENSE`). It bundles or loads these components, which keep their own licences:

| Component | Licence | How it is used |
|---|---|---|
| [three.js](https://github.com/mrdoob/three.js) | MIT | 3D viewport, orbit and transform controls; bundled into `app.js` |
| [Manifold](https://github.com/elalish/manifold) (`manifold-3d`) | Apache-2.0 | Mesh boolean kernel (WASM); bundled into `app.js` |
| [occt-import-js](https://github.com/kovacsv/occt-import-js) | LGPL-2.1 (wraps Open CASCADE, LGPL-2.1 with exception) | STEP reader, loaded at runtime as a separate, unmodified `occt-import-js.js` + `.wasm`; `build.mjs` copies its licence to `dist/LICENSE.occt-import-js.txt` |
| [esbuild](https://github.com/evanw/esbuild) | MIT | Build tool only, not shipped |
