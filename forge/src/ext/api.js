// The permission-checked API surface (v1). `ExtApi.call(pkg, method, args)` is the only way a package reaches anything:
// every request from a Worker lands here, and the manifest is consulted on every call (permission, then declared contribution).
// The host supplies the environment through `ctx` so this file has no DOM, no storage and no network of its own.
import { API_BY_NAME } from './api-spec.js';
import { isNetworkPerm, networkHost } from './manifest.js';
import { ExtError } from './host.js';
import { voxelize, VOXEL_DEFAULT_MAX } from './voxelize.js';

const MAX_VALUE_BYTES = 256 * 1024, MAX_BODY = 20 * 1024 * 1024, MAX_OVERLAY_POINTS = 2_000_000;
const KEY = /^[A-Za-z0-9_.-]{1,64}$/;
const jsonSize = (v) => { try { return JSON.stringify(v ?? null).length; } catch (_) { throw new ExtError('E_ARG', 'value is not JSON-serialisable'); } };
const clone = (v) => JSON.parse(JSON.stringify(v));

/**
 * ctx (all optional hooks; a missing hook makes the call reject with E_UNAVAILABLE):
 *   getProject()                      current Project
 *   commitOps(ops, {extId})           apply ops as one undoable step; must use applyOps with actor 'ext'
 *   getBuild()                        last kernel result { bodies, refs, kernel }
 *   brep(bodyName)                    Promise<Uint8Array> STEP bytes (OCCT kernel only)
 *   isEnabled(extId)                  is the package enabled in this project
 *   userStore { get(key), set(key, value) }   per-browser, namespaced by the api
 *   onOverlay(extId, id, overlay|null), onPane({kind, extId, id, message})
 *   gpuInfo()                         Promise<{available, adapter?, limits?}>
 *   getRunner()                       { url, token } the user entered, or null
 *   fetch                             fetch implementation (defaults to globalThis.fetch)
 */
export class ExtApi {
  constructor(ctx = {}) { this.ctx = ctx; this.registered = new Map(); }

  /** Registrations the packages made: key `${extId}:${kind}:${id}` -> { has } */
  isRegistered(extId, kind, id) { return this.registered.has(`${extId}:${kind}:${id}`); }

  #perm(pkg, spec, args) {
    const perms = pkg.manifest.permissions;
    if (this.ctx.isEnabled && !this.ctx.isEnabled(pkg.manifest.id)) throw new ExtError('E_PERM', `"${pkg.manifest.name}" is disabled in this project`);
    if (spec.perm == null) return;
    if (spec.perm === 'network') {
      let host; try { host = new URL(String(args[0])).hostname; } catch (_) { throw new ExtError('E_ARG', 'net.fetch needs an absolute URL'); }
      if (!perms.some((p) => isNetworkPerm(p) && networkHost(p) === host)) throw new ExtError('E_PERM', `"${pkg.manifest.name}" did not declare network:${host}`);
      return;
    }
    if (!perms.includes(spec.perm)) throw new ExtError('E_PERM', `"${pkg.manifest.name}" needs the "${spec.perm}" permission for ${spec.name}`);
  }

  #contrib(pkg, spec, args) {
    if (!spec.contrib) return;
    const id = args[0];
    const items = (pkg.manifest.contributes || {})[spec.contrib] || [];
    if (typeof id !== 'string' || !items.some((i) => i.id === id)) throw new ExtError('E_CONTRIB', `"${id}" is not declared in contributes.${spec.contrib} of "${pkg.manifest.name}"`);
  }

  async call(pkg, method, args = []) {
    const spec = API_BY_NAME[method];
    if (!spec || spec.local) throw new ExtError('E_ARG', `unknown API call "${method}"`);
    this.#perm(pkg, spec, args);
    this.#contrib(pkg, spec, args);
    const fn = this['api_' + method.replace(/\./g, '_')];
    if (!fn) throw new ExtError('E_UNAVAILABLE', `${method} is not implemented`);
    return fn.call(this, pkg, ...args);
  }

  // ── document
  api_doc_get() {
    const P = clone(this.#need('getProject')());
    P.assets = Object.fromEntries(Object.entries(P.assets || {}).map(([k, a]) => [k, { name: a.name, tris: a.tris, bbox: a.bbox }]));
    return P;
  }
  api_pcb_get() { return clone(this.#need('getProject')().pcb || {}); }

  async api_doc_applyOps(pkg, ops) {
    if (!Array.isArray(ops) || !ops.length || ops.length > 200) throw new ExtError('E_ARG', 'ops must be an array of 1 to 200 ops');
    for (const op of ops) {
      if (!op || typeof op !== 'object' || typeof op.op !== 'string') throw new ExtError('E_ARG', 'each op needs an "op" name');
      if (op.op === 'ext_set' && op.id !== pkg.manifest.id) throw new ExtError('E_PERM', `a package may only write its own storage (ext_set id must be "${pkg.manifest.id}")`);
      if (op.op === 'set_extension') throw new ExtError('E_PERM', 'a package cannot enable, disable or change extensions');
    }
    try { return await this.#need('commitOps')(clone(ops), { extId: pkg.manifest.id }); }
    catch (e) { throw new ExtError('E_OPS', String(e.message || e)); }
  }

  // ── geometry
  #body(name) {
    const b = this.#need('getBuild')();
    const hit = [...(b?.bodies || []), ...(b?.refs || [])].find((x) => x.name === name);
    if (!hit) throw new ExtError('E_ARG', `no body or reference part named "${name}" in the current build`);
    return hit;
  }
  api_geometry_list() {
    const b = this.#need('getBuild')();
    const row = (x, ref) => ({ name: x.name, ref, bbox: clone(x.bbox), volume_mm3: x.volume || 0, tris: x.tris || 0 });
    return [...(b?.bodies || []).map((x) => row(x, false)), ...(b?.refs || []).map((x) => row(x, true))];
  }
  api_geometry_mesh(_pkg, body) {
    const b = this.#body(String(body)); const m = b.mesh || b.view;
    return { positions: Float32Array.from(m.pos), indices: Uint32Array.from(m.idx), bbox: clone(b.bbox), volume_mm3: b.volume || 0 };
  }
  async api_geometry_brep(_pkg, body) {
    this.#body(String(body));
    if (!this.ctx.brep) throw new ExtError('E_UNAVAILABLE', 'B-rep access needs the OCCT kernel (switch the kernel in the header)');
    return { step: await this.ctx.brep(String(body)) };
  }
  api_geometry_voxelize(_pkg, body, h, opts = {}) {
    const b = this.#body(String(body)); const m = b.mesh || b.view;
    return voxelize(m, Number(h), { maxVoxels: Number(opts.maxVoxels) || VOXEL_DEFAULT_MAX });
  }

  // ── registrations (the function itself stays in the Worker)
  #register(kind) { return (pkg, id, info) => { this.registered.set(`${pkg.manifest.id}:${kind}:${id}`, info || {}); }; }
  api_checks_register(pkg, id, info) { this.#register('check')(pkg, id, info); }
  api_analysis_register(pkg, id, info) { this.#register('analysis')(pkg, id, info); }
  api_featureType_register(pkg, id, info) { this.#register('featureType')(pkg, id, info); }
  api_agent_tool(pkg, id, info) { this.#register('agentTool')(pkg, id, info); }
  api_loop_step(pkg, id, info) { this.#register('loopStep')(pkg, id, info); }

  // ── viewport and panes
  api_scene_overlay(pkg, id, o) {
    if (!o || typeof o !== 'object') throw new ExtError('E_ARG', 'overlay must be an object');
    let ov;
    if (o.kind === 'points') {
      const p = o.positions, v = o.values;
      if (!(p instanceof Float32Array) || !(v instanceof Float32Array) || p.length !== v.length * 3) throw new ExtError('E_ARG', 'points overlay needs Float32Array positions (3 per point) and values (1 per point)');
      if (v.length > MAX_OVERLAY_POINTS) throw new ExtError('E_LIMIT', `more than ${MAX_OVERLAY_POINTS} points`);
      ov = { kind: 'points', positions: p, values: v, unit: String(o.unit ?? '').slice(0, 12), label: String(o.label ?? id).slice(0, 60), range: Array.isArray(o.range) && o.range.length === 2 && o.range.every(Number.isFinite) ? o.range : null, validity: ['estimate', 'validated', 'experimental'].includes(o.validity) ? o.validity : 'experimental' };
    } else if (o.kind === 'box') {
      const ok = (a) => Array.isArray(a) && a.length === 3 && a.every(Number.isFinite);
      if (!ok(o.min) || !ok(o.max)) throw new ExtError('E_ARG', 'box overlay needs min and max [x,y,z]');
      ov = { kind: 'box', min: o.min, max: o.max, label: String(o.label ?? id).slice(0, 60), unit: '', validity: 'experimental' };
    } else throw new ExtError('E_ARG', 'overlay kind must be "points" or "box"');
    this.#need('onOverlay')(pkg.manifest.id, id, ov);
  }
  api_scene_clearOverlay(pkg, id) { this.#need('onOverlay')(pkg.manifest.id, String(id), null); }
  api_ui_pane_open(pkg, id) { this.#need('onPane')({ kind: 'open', extId: pkg.manifest.id, id }); }
  api_ui_pane_post(pkg, id, message) { if (jsonSize(message) > MAX_VALUE_BYTES) throw new ExtError('E_LIMIT', 'message larger than 256 KB'); this.#need('onPane')({ kind: 'post', extId: pkg.manifest.id, id, message: clone(message ?? null) }); }

  // ── storage
  #key(k) { if (!KEY.test(String(k))) throw new ExtError('E_ARG', 'key must be 1 to 64 characters of A-Z a-z 0-9 _ . -'); return String(k); }
  api_storage_project_get(pkg, key) { const P = this.#need('getProject')(); return clone((P.ext && P.ext[pkg.manifest.id] && P.ext[pkg.manifest.id][this.#key(key)]) ?? null); }
  async api_storage_project_set(pkg, key, value) {
    if (jsonSize(value) > MAX_VALUE_BYTES) throw new ExtError('E_LIMIT', 'value larger than 256 KB');
    await this.api_doc_applyOps_internal(pkg, [{ op: 'ext_set', id: pkg.manifest.id, key: this.#key(key), value: clone(value ?? null) }]);
  }
  async api_doc_applyOps_internal(pkg, ops) { try { return await this.#need('commitOps')(ops, { extId: pkg.manifest.id }); } catch (e) { throw new ExtError('E_OPS', String(e.message || e)); } }
  api_storage_user_get(pkg, key) { const s = this.#need('userStore'); return s.get(`${pkg.manifest.id}:${this.#key(key)}`) ?? null; }
  api_storage_user_set(pkg, key, value) { if (jsonSize(value) > MAX_VALUE_BYTES) throw new ExtError('E_LIMIT', 'value larger than 256 KB'); this.#need('userStore').set(`${pkg.manifest.id}:${this.#key(key)}`, clone(value ?? null)); }

  // ── compute and network (the host does the work; the package never holds a token or a socket)
  async api_compute_gpu_info() { return this.ctx.gpuInfo ? this.ctx.gpuInfo() : { available: false }; }
  async api_compute_remote_fetch(_pkg, path, init = {}) {
    const r = this.ctx.getRunner && this.ctx.getRunner();
    if (!r || !r.url) throw new ExtError('E_UNAVAILABLE', 'no Alloy Runner URL is set (Settings > Extensions)');
    if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || path.includes('..') || /[\r\n]/.test(path)) throw new ExtError('E_ARG', 'path must start with a single "/" and stay inside the runner');
    const base = r.url.replace(/\/+$/, '');
    return this.#fetch(base + path, init, r.token ? { Authorization: 'Bearer ' + r.token } : {});
  }
  async api_net_fetch(_pkg, url, init = {}) {
    const u = new URL(String(url));
    if (!(u.protocol === 'https:' || (u.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(u.hostname)))) throw new ExtError('E_PERM', 'only https (or http on localhost) is allowed');
    return this.#fetch(u.href, init, {});
  }
  async #fetch(url, init, extra) {
    const f = this.ctx.fetch || globalThis.fetch;
    if (!f) throw new ExtError('E_UNAVAILABLE', 'no fetch available');
    const headers = { ...(init.headers && typeof init.headers === 'object' ? init.headers : {}) };
    for (const k of Object.keys(headers)) if (/^(cookie|authorization|host)$/i.test(k)) delete headers[k]; // the package cannot set these; the host adds the runner token itself
    const res = await f(url, { method: String(init.method || 'GET').toUpperCase(), headers: { ...headers, ...extra }, body: init.body ?? undefined, redirect: 'error', credentials: 'omit', referrerPolicy: 'no-referrer' });
    const buf = new Uint8Array(await res.arrayBuffer());
    if (buf.length > MAX_BODY) throw new ExtError('E_LIMIT', 'response larger than 20 MB');
    return { status: res.status, headers: Object.fromEntries(res.headers ? [...res.headers.entries()] : []), body: buf };
  }

  #need(hook) {
    const v = this.ctx[hook];
    if (v == null) throw new ExtError('E_UNAVAILABLE', `${hook} is not available in this context`);
    return v;
  }
}
