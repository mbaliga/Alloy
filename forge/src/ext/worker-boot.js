// The code that runs inside every package Worker, before the package's own `main.js`.
//
// `bootFn` is serialised with Function.prototype.toString and must stay SELF-CONTAINED (no imports, no closures over module
// scope). The final worker script is: the package source wrapped in a function that is only *defined* (never eval'd, so a strict
// CSP without 'unsafe-eval' still works), then bootFn. After the host's `init` message, bootFn removes network and storage
// globals (unless the manifest grants them), builds the permission-neutral `forge` object from the host's method list and runs
// the package function.
//
// Be clear about what this is: the hard boundaries are (1) a Worker has no DOM and no access to the page, (2) every privileged
// operation is a message to the host, which checks the manifest on every call. Removing network globals here is defence in depth,
// not a sandbox: a Worker started from a blob shares the page's origin. See docs/EXTENSION_API.md, "Security model".

export function bootFn() {
  const isNode = typeof self === 'undefined' || typeof self.postMessage !== 'function';
  let send, listen;
  if (isNode) { const pp = require('node:worker_threads').parentPort; send = (m) => pp.postMessage(m); listen = (f) => pp.on('message', f); }
  else { const post = self.postMessage.bind(self); send = (m, tr) => post(m, tr); listen = (f) => { self.onmessage = (e) => f(e.data); }; }

  const root = globalThis;
  const handlers = Object.create(null), controllers = Object.create(null), pending = Object.create(null);
  let seq = 0, paneCall = null;

  const errObj = (e) => ({ code: (e && e.code) || 'E_PACKAGE', message: String((e && e.message) || e) });
  const mkErr = (o) => { const e = new Error(o.message); e.code = o.code; return e; };

  function lockdown(grants) {
    const names = ['fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', 'WebTransport', 'importScripts', 'Worker', 'SharedWorker', 'indexedDB', 'caches', 'BroadcastChannel', 'require', 'process', 'Request', 'Response', 'Headers'];
    if (!grants.gpu) names.push('GPU');
    const objs = []; for (let o = root; o && objs.length < 8; o = Object.getPrototypeOf(o)) objs.push(o);
    for (const o of objs) for (const n of names) { try { delete o[n]; } catch (_) {} try { if (n in o) Object.defineProperty(o, n, { value: undefined, configurable: true, writable: true }); } catch (_) {} }
    try {
      const nav = root.navigator;
      if (nav) {
        for (const n of ['sendBeacon', 'serviceWorker', 'storage', 'locks', 'connection'].concat(grants.gpu ? [] : ['gpu'])) { try { Object.defineProperty(nav, n, { value: undefined, configurable: true }); } catch (_) {} try { Object.defineProperty(Object.getPrototypeOf(nav), n, { value: undefined, configurable: true }); } catch (_) {} }
      }
    } catch (_) {}
  }

  function call(method, args) {
    return new Promise((resolve, reject) => { const id = ++seq; pending[id] = { resolve, reject }; send({ t: 'req', id, m: method, a: args }); });
  }

  function buildForge(methods) {
    const forge = { api: '1.0' };
    for (const m of methods) {
      const parts = m.name.split('.'); let o = forge;
      for (const p of parts.slice(0, -1)) o = o[p] = o[p] || {};
      const leaf = parts[parts.length - 1];
      if (m.local) continue;
      if (m.register) {
        o[leaf] = (id, impl) => {
          if (typeof id !== 'string') throw new TypeError(m.name + ': id must be a string');
          const fn = typeof impl === 'function' ? impl : null;
          if (!fn && !(impl && typeof impl === 'object')) throw new TypeError(m.name + ': second argument must be a function or an object');
          handlers[m.register + ':' + id] = impl;
          return call(m.name, [id, { has: fn ? ['fn'] : Object.keys(impl).filter((k) => typeof impl[k] === 'function') }]);
        };
      } else o[leaf] = (...a) => call(m.name, a);
    }
    forge.ui = forge.ui || {}; forge.ui.pane = forge.ui.pane || {};
    forge.ui.pane.onCall = (fn) => { paneCall = fn; };
    return forge;
  }

  async function invoke(msg) {
    const { id, kind, name, args } = msg;
    const h = handlers[kind + ':' + name];
    const ac = new AbortController(); controllers[id] = ac;
    const ctx = { signal: ac.signal, progress: (v) => send({ t: 'progress', id, v }), id };
    try {
      let v;
      if (kind === 'pane') { if (!paneCall) throw Object.assign(new Error('pane handler not set (forge.ui.pane.onCall)'), { code: 'E_UNAVAILABLE' }); v = await paneCall(name, args.method, args.data, ctx); }
      else {
        if (h == null) throw Object.assign(new Error(`no ${kind} "${name}" registered by this package`), { code: 'E_UNAVAILABLE' });
        if (kind === 'analysis') v = await h.run(args, ctx);
        else if (kind === 'featureType') v = await h.expand(args, ctx);
        else v = await (typeof h === 'function' ? h(args, ctx) : h.run(args, ctx));
      }
      send({ t: 'invres', id, ok: true, v });
    } catch (e) { send({ t: 'invres', id, ok: false, e: ac.signal.aborted ? { code: 'E_CANCELLED', message: 'cancelled' } : errObj(e) }); }
    finally { delete controllers[id]; }
  }

  listen((msg) => {
    if (!msg || typeof msg !== 'object') return;
    if (msg.t === 'init') {
      try {
        const grants = msg.grants || {};
        const consoleProxy = {};
        for (const lv of ['log', 'info', 'warn', 'error']) consoleProxy[lv] = (...a) => send({ t: 'log', level: lv, text: a.map((x) => { try { return typeof x === 'string' ? x : JSON.stringify(x); } catch (_) { return String(x); } }).join(' ').slice(0, 2000) });
        const forge = buildForge(msg.methods);
        lockdown(grants);
        root.__alloyMain(forge, consoleProxy);
        send({ t: 'ready' });
      } catch (e) { send({ t: 'initerr', e: errObj(e) }); }
    } else if (msg.t === 'res') {
      const p = pending[msg.id]; if (!p) return; delete pending[msg.id];
      if (msg.ok) p.resolve(msg.v); else p.reject(mkErr(msg.e));
    } else if (msg.t === 'inv') invoke(msg);
    else if (msg.t === 'abort') { const c = controllers[msg.id]; if (c) c.abort(); }
  });
}

/** Full Worker script for a package. The package source is wrapped in a function and only defined here, never eval'd. */
export function workerScript(packageSource) {
  return `globalThis.__alloyMain=function(forge,console){"use strict";\n${packageSource}\n};\n(${bootFn.toString()})();`;
}
