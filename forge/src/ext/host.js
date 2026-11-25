// Extension host: one Worker per package, RPC over postMessage. The host never trusts the Worker: every request it sends goes
// through ExtApi.call, which checks the manifest on every call. Invocations (host -> package) take an AbortSignal, report
// progress and have a timeout; a Worker that does not answer in time is terminated and restarts on next use.
import { API } from './api-spec.js';
import { workerScript } from './worker-boot.js';

export class ExtError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

const METHODS = API.map((a) => ({ name: a.name, register: a.register || null, local: !!a.local }));

/** Browser Worker factory: a Blob worker so the package source needs no eval (see worker-boot.js). */
export function browserWorkerFactory(script, name) {
  const url = URL.createObjectURL(new Blob([script], { type: 'text/javascript' }));
  const w = new Worker(url, { name });
  return {
    post: (m) => w.postMessage(m), onMessage: (f) => { w.onmessage = (e) => f(e.data); }, onError: (f) => { w.onerror = (e) => { e.preventDefault?.(); f(new Error(e.message || 'worker error')); }; },
    terminate: () => { w.terminate(); URL.revokeObjectURL(url); },
  };
}

export class ExtHost {
  /**
   * @param {{createWorker:(script:string,name:string)=>{post,onMessage,onError,terminate}, api:{call:Function}, startTimeoutMs?:number, defaultTimeoutMs?:number, onChange?:Function}} o
   */
  constructor(o) {
    this.create = o.createWorker; this.api = o.api;
    this.startTimeout = o.startTimeoutMs ?? 8000; this.defaultTimeout = o.defaultTimeoutMs ?? 30000;
    this.onChange = o.onChange || (() => {});
    this.entries = new Map(); this.logs = new Map();
  }

  running(id) { return this.entries.has(id); }
  logOf(id) { return this.logs.get(id) || []; }
  #log(id, level, text) { const l = this.logs.get(id) || []; l.push({ t: Date.now(), level, text }); if (l.length > 200) l.shift(); this.logs.set(id, l); this.onChange('log', id); }

  /** Start the package Worker if it is not running. `pkg` = { manifest, source }. */
  ensure(pkg) {
    const id = pkg.manifest.id;
    const have = this.entries.get(id);
    if (have) return have.ready;
    const perms = pkg.manifest.permissions;
    const e = { pkg, pending: new Map(), seq: 0, dead: false };
    this.entries.set(id, e);
    e.ready = new Promise((resolve, reject) => {
      let w;
      try { w = this.create(workerScript(pkg.source), id); } catch (err) { this.entries.delete(id); return reject(new ExtError('E_PACKAGE', 'could not start worker: ' + err.message)); }
      e.w = w;
      const timer = setTimeout(() => { this.stop(id, new ExtError('E_TIMEOUT', 'package did not start in time')); reject(new ExtError('E_TIMEOUT', 'package did not start in time')); }, this.startTimeout);
      e.settleStart = (ok, err) => { clearTimeout(timer); ok ? resolve() : reject(err); };
      w.onMessage((m) => this.#onMessage(e, m));
      w.onError((err) => { const x = new ExtError('E_PACKAGE', 'package error: ' + err.message); this.#log(id, 'error', x.message); e.settleStart(false, x); this.stop(id, x); });
      w.post({ t: 'init', methods: METHODS, grants: { gpu: perms.includes('compute.gpu') } });
    });
    e.ready.catch(() => {});
    this.onChange('start', id);
    return e.ready;
  }

  async #onMessage(e, m) {
    const id = e.pkg.manifest.id;
    if (!m || typeof m !== 'object') return;
    switch (m.t) {
      case 'ready': e.settleStart(true); break;
      case 'initerr': { const x = new ExtError(m.e.code || 'E_PACKAGE', m.e.message); this.#log(id, 'error', x.message); e.settleStart(false, x); this.stop(id, x); break; }
      case 'log': this.#log(id, m.level, m.text); break;
      case 'progress': { const p = e.pending.get(m.id); if (p && p.onProgress) try { p.onProgress(m.v); } catch (_) {} break; }
      case 'invres': {
        const p = e.pending.get(m.id); if (!p) break;
        e.pending.delete(m.id); clearTimeout(p.timer); p.cleanup();
        if (m.ok) p.resolve(m.v); else p.reject(new ExtError(m.e.code || 'E_PACKAGE', m.e.message));
        break;
      }
      case 'req': {
        let res;
        try { res = { t: 'res', id: m.id, ok: true, v: await this.api.call(e.pkg, String(m.m), Array.isArray(m.a) ? m.a : []) }; }
        catch (err) { res = { t: 'res', id: m.id, ok: false, e: { code: err.code || 'E_PACKAGE', message: String(err.message || err) } }; }
        if (!e.dead) e.w.post(res);
        break;
      }
      default: break; // unknown messages are ignored
    }
  }

  /** Call a function the package registered. kind: check | analysis | featureType | agentTool | loopStep | pane. */
  async invoke(pkg, kind, name, args, { signal, onProgress, timeoutMs } = {}) {
    await this.ensure(pkg);
    const id = pkg.manifest.id, e = this.entries.get(id);
    if (!e) throw new ExtError('E_PACKAGE', 'package stopped');
    if (signal?.aborted) throw new ExtError('E_CANCELLED', 'cancelled');
    return new Promise((resolve, reject) => {
      const n = ++e.seq; const to = timeoutMs ?? this.defaultTimeout;
      const onAbort = () => { e.w.post({ t: 'abort', id: n }); };
      const p = { resolve, reject, onProgress, cleanup: () => signal && signal.removeEventListener('abort', onAbort) };
      if (to > 0) p.timer = setTimeout(() => { e.pending.delete(n); p.cleanup(); const x = new ExtError('E_TIMEOUT', `"${name}" did not finish in ${Math.round(to / 1000)} s; the package was stopped`); reject(x); this.stop(id, x); }, to);
      e.pending.set(n, p);
      if (signal) signal.addEventListener('abort', onAbort, { once: true });
      e.w.post({ t: 'inv', id: n, kind, name, args });
    });
  }

  stop(id, reason) {
    const e = this.entries.get(id); if (!e) return;
    this.entries.delete(id); e.dead = true;
    try { e.w.terminate(); } catch (_) {}
    for (const p of e.pending.values()) { clearTimeout(p.timer); p.cleanup(); p.reject(reason || new ExtError('E_CANCELLED', 'package stopped')); }
    e.pending.clear();
    this.onChange('stop', id);
  }
  stopAll() { for (const id of [...this.entries.keys()]) this.stop(id); }
}
