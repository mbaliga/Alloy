// Alloy Forge extension API v1: the single source of truth. The permission checker (api.js), the worker-side `forge`
// object (worker-boot.js) and docs/EXTENSION_API.md (tools/gen-extension-api.mjs) are all produced from this table.
//
//   name       dotted path of the call as a package sees it: forge.<name>(...)
//   perm       permission the manifest must list. 'network' means one `network:<host>` entry that matches the call.
//              null = no permission needed (pure computation or a declaration the manifest already makes)
//   contrib    manifest `contributes` kind the call must be declared in (checked on every register call)
//   register   the call hands the host a function; the function stays in the package's Worker and is invoked later
//   args / returns  documentation strings (types are structural, all values are structured-cloneable)

export const FORGE_API = '1.0';

export const PERMISSIONS = {
  'doc.read': { tier: 'plugin', text: 'read your design (parameters, features, bodies, PCB data)' },
  'doc.ops': { tier: 'plugin', text: 'edit your design through typed ops (validated, lock-checked, undoable)' },
  'geometry.read': { tier: 'plugin', text: 'read the built geometry (meshes, B-rep, voxels)' },
  checks: { tier: 'plugin', text: 'add rows to the check scorecard and the design loop' },
  'agent.tools': { tier: 'plugin', text: 'give the AI assistant and the MCP server new tools' },
  loop: { tier: 'plugin', text: 'add steps to the design loop' },
  storage: { tier: 'plugin', text: 'keep its own settings in the project and in this browser' },
  'ui.pane': { tier: 'extension', text: 'show its own pane (a sandboxed page with no network)' },
  'scene.overlay': { tier: 'extension', text: 'draw overlays in the 3D viewport' },
  'compute.gpu': { tier: 'extension', text: 'run compute on your GPU (WebGPU)' },
  'compute.remote': { tier: 'extension', text: 'talk to the Alloy Runner URL you entered in Settings' },
  'network:<host>': { tier: 'extension', text: 'make network requests to the named host only' },
};

/** Manifest `contributes` kinds, and the permission each needs (null = none). */
export const CONTRIBUTES = {
  featureTypes: null, templates: 'doc.ops', stylePacks: null, checks: 'checks', analyses: 'doc.read', exporters: 'geometry.read',
  importers: 'doc.ops', libraries: 'doc.ops', agentTools: 'agent.tools', loopSteps: 'loop', panes: 'ui.pane', overlays: 'scene.overlay', settings: null,
};

export const API = [
  { name: 'doc.get', perm: 'doc.read', args: '()', returns: 'Project (JSON). Asset bytes are left out.', doc: 'The current design document.' },
  { name: 'doc.applyOps', perm: 'doc.ops', args: '(ops: Op[])', returns: '{ log: string[] }',
    doc: 'Apply typed ops to the design. The host runs the same `applyOps` the AI uses: ops are validated, locks are enforced, and the whole call is one undoable step. A rejected op rejects the call and changes nothing. A package cannot enable or disable extensions, and `ext_set` is limited to its own id.' },
  { name: 'pcb.get', perm: 'doc.read', args: '()', returns: 'PcbBlock', doc: 'The `pcb` block of the document (components, cutouts, supports, keep regions).' },
  { name: 'geometry.list', perm: 'geometry.read', args: '()', returns: '{ name, ref: boolean, bbox, volume_mm3, tris }[]', doc: 'The bodies and reference parts of the current build (hidden bodies included).' },
  { name: 'geometry.mesh', perm: 'geometry.read', args: '(body: string)', returns: '{ positions: Float32Array, indices: Uint32Array, bbox, volume_mm3 }', doc: 'Triangle mesh of a built body or reference part, in millimetres.' },
  { name: 'geometry.brep', perm: 'geometry.read', args: '(body: string)', returns: '{ step: Uint8Array }', doc: 'STEP bytes of a body. Only on the OCCT kernel; on Manifold the call rejects with E_UNAVAILABLE.' },
  { name: 'geometry.voxelize', perm: 'geometry.read', args: '(body: string, h: number, opts?: { maxVoxels?: number })', returns: '{ origin: number[3], h: number, dims: number[3], data: Uint8Array }',
    doc: 'Fill a body into a regular grid (1 = inside). CPU reference implementation; refuses grids over `maxVoxels` (default 4 000 000, hard cap 8 000 000) with E_LIMIT. A GPU version replaces it in A2 behind the same call.' },
  { name: 'checks.register', perm: 'checks', contrib: 'checks', register: 'check', args: '(id: string, fn: (input: { project, bodies }, ctx) => CheckRow | Promise<CheckRow>)',
    returns: 'void', doc: 'Add a check. `id` must be declared in `contributes.checks`. `fn(input, ctx)` receives `input = { project, bodies }`: the design being checked (in the design loop that is the candidate, which may differ from the open document, so use `input.project` rather than `forge.doc.get()`) and a summary of its build (`name, ref, bbox, volume_mm3, tris`). It returns `{ pass, detail, items?, validity? }`. A row with `validity: "experimental"` is shown but never gates a pass/fail verdict.' },
  { name: 'analysis.register', perm: 'doc.read', contrib: 'analyses', register: 'analysis', args: '(id: string, impl: { run(input, ctx), cancel?() })',
    returns: 'void', doc: '`id` must be declared in `contributes.analyses`. `run(input, { progress, signal })` returns an AnalysisResult (docs/EXTENSIONS_SPEC.md section 3.3). Runs in the package Worker; the host can cancel it.' },
  { name: 'featureType.register', perm: null, contrib: 'featureTypes', register: 'featureType', args: '(id: string, impl: { expand(values, ctx) })',
    returns: 'void', doc: 'A feature type is a recipe: `expand` receives the evaluated fields and returns built-in feature specs (box, cylinder, hole, ...). The kernel builds those; the package never produces geometry itself. Fields are declared in the manifest.' },
  { name: 'agent.tool', perm: 'agent.tools', contrib: 'agentTools', register: 'agentTool', args: '(id: string, fn: (args, ctx) => any)', returns: 'void',
    doc: 'Expose a tool to the in-app AI and the MCP server. `id` must be declared in `contributes.agentTools` with a JSON Schema for its arguments.' },
  { name: 'loop.step', perm: 'loop', contrib: 'loopSteps', register: 'loopStep', args: '(id: string, fn: (ctx) => any)', returns: 'void', doc: 'A step the design loop can call. `id` must be declared in `contributes.loopSteps`.' },
  { name: 'scene.overlay', perm: 'scene.overlay', contrib: 'overlays', args: '(id: string, overlay: Overlay)', returns: 'void',
    doc: 'Draw or replace an overlay. Kinds: `points` ({ positions: Float32Array, values: Float32Array, unit, label, range? }) and `box` ({ min, max, label }). The host adds the colour map, legend and tap probe. `id` must be declared in `contributes.overlays`.' },
  { name: 'scene.clearOverlay', perm: 'scene.overlay', args: '(id: string)', returns: 'void', doc: 'Remove an overlay.' },
  { name: 'ui.pane.open', perm: 'ui.pane', contrib: 'panes', args: '(id: string)', returns: 'void', doc: 'Show a pane declared in `contributes.panes`. A pane is an HTML file from the package, loaded in a sandboxed iframe (`allow-scripts` only, no network, no same-origin).' },
  { name: 'ui.pane.post', perm: 'ui.pane', contrib: 'panes', args: '(id: string, message: any)', returns: 'void', doc: 'Send a message to an open pane. The pane replies through `alloy.call` / `alloy.on` (see EXTENSION_API.md, "Panes").' },
  { name: 'ui.pane.onCall', perm: 'ui.pane', local: true, args: '(fn: (paneId, method, data) => any)', returns: 'void', doc: 'Local: handle calls a pane makes with `alloy.call(method, data)`. The return value goes back to the pane. No host round trip.' },
  { name: 'storage.project.get', perm: 'storage', args: '(key: string)', returns: 'any', doc: 'Read `project.ext[<id>][key]`.' },
  { name: 'storage.project.set', perm: 'storage', args: '(key: string, value: any)', returns: 'void', doc: 'Write `project.ext[<id>][key]` through the `ext_set` op, so it is saved with the project and is undoable. Values up to 256 KB.' },
  { name: 'storage.user.get', perm: 'storage', args: '(key: string)', returns: 'any', doc: 'Per-browser storage, private to the package.' },
  { name: 'storage.user.set', perm: 'storage', args: '(key: string, value: any)', returns: 'void', doc: 'Values up to 256 KB.' },
  { name: 'compute.gpu.info', perm: 'compute.gpu', args: '()', returns: '{ available: boolean, adapter?: string, limits?: object }', doc: 'Whether WebGPU is available. `navigator.gpu` is only present inside the Worker of a package that holds `compute.gpu`.' },
  { name: 'compute.remote.fetch', perm: 'compute.remote', args: '(path: string, init?: { method, headers, body })', returns: '{ status, headers, body: Uint8Array }',
    doc: 'Request to the Alloy Runner URL entered in Settings. `path` must start with `/`. The host adds the runner token; the package never sees it. Rejects with E_UNAVAILABLE when no runner is set.' },
  { name: 'net.fetch', perm: 'network', args: '(url: string, init?: { method, headers, body })', returns: '{ status, headers, body: Uint8Array }',
    doc: 'Request to a host the package declared as `network:<host>` (exact host name, https, or http on localhost). Anything else is refused.' },
];

export const API_BY_NAME = Object.fromEntries(API.map((a) => [a.name, a]));

export const ERRORS = {
  E_PERM: 'The package did not declare the permission this call needs.',
  E_CONTRIB: 'The id is not declared in the manifest `contributes`.',
  E_ARG: 'Invalid arguments.',
  E_UNAVAILABLE: 'Not available right now (wrong kernel, no runner, no GPU, ...).',
  E_LIMIT: 'A size or count limit was exceeded.',
  E_OPS: 'The ops were rejected (validation or a lock). Nothing was changed.',
  E_TIMEOUT: 'The package Worker did not answer in time and was stopped.',
  E_CANCELLED: 'Cancelled.',
};
